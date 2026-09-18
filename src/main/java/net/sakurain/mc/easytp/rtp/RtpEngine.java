package net.sakurain.mc.easytp.rtp;

import net.sakurain.mc.easytp.EasyTPPlugin;
import net.sakurain.mc.easytp.rtp.memory.RegionState;
import net.sakurain.mc.easytp.rtp.memory.SpatialMemory;
import net.sakurain.mc.easytp.rtp.pool.RtpCandidate;
import net.sakurain.mc.easytp.rtp.pool.RtpLocation;
import net.sakurain.mc.easytp.rtp.pool.RtpPool;
import net.sakurain.mc.easytp.rtp.scheduler.RtpScheduler;
import net.sakurain.mc.easytp.rtp.spiral.SpiralCoordinateGenerator;
import net.sakurain.mc.easytp.storage.DatabaseManager;
import net.sakurain.mc.easytp.util.DebugLog;
import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * High-performance random teleport engine.
 *
 * <p>The engine combines four layers:</p>
 * <ul>
 *   <li>Spiral coordinate generator: deterministic O(1) coordinate generation with weighted ring zones.</li>
 *   <li>Spatial memory: persistent grid-cell state cache that skips known-bad regions.</li>
 *   <li>Preload pool: per-world L1 hot, L2 cold, and L3 candidate queues.</li>
 *   <li>Async validation pipeline: ChunkSnapshot-based safety checks off the main thread.</li>
 * </ul>
 */
public class RtpEngine {

    private final EasyTPPlugin plugin;
    private final DatabaseManager databaseManager;
    private final RtpScheduler scheduler;
    private final RtpStorage storage;
    private final Map<String, RtpPool> worldPools = new ConcurrentHashMap<>();
    // Config-derived state, swapped wholesale by reload().
    private volatile SpatialMemory spatialMemory;
    private volatile SpiralCoordinateGenerator spiral;
    private volatile Set<Material> unsafeBlocks;
    private volatile Set<NamespacedKey> biomeBlacklist;
    private final Map<UUID, RtpCallback> pendingCallbacks = new ConcurrentHashMap<>();
    /** Chunk loads started but not yet completed. Used as the backpressure signal. */
    private final AtomicInteger inFlightLoads = new AtomicInteger();

    // Diagnostics. LongAdder is cheap enough to update unconditionally, so the numbers are
    // meaningful the moment debug is switched on rather than only after it. The periodic summary
    // drains them.
    private final LongAdder statLoadsRequested = new LongAdder();
    private final LongAdder statLoadsSucceeded = new LongAdder();
    private final LongAdder statLoadsFailed = new LongAdder();
    private final LongAdder statSlowLoads = new LongAdder();
    private final AtomicLong statSlowestLoadMillis = new AtomicLong();
    private final LongAdder statBackpressureSkips = new LongAdder();
    private final LongAdder statIdleSkips = new LongAdder();
    private final LongAdder statGenerated = new LongAdder();
    private final LongAdder statRejectedBorder = new LongAdder();
    private final LongAdder statRejectedMemory = new LongAdder();
    private final LongAdder statValidated = new LongAdder();
    private final LongAdder statUnsafe = new LongAdder();
    private final LongAdder statServedHot = new LongAdder();
    private final LongAdder statServedCold = new LongAdder();
    private final LongAdder statQueued = new LongAdder();
    private final LongAdder statQueueRejected = new LongAdder();
    private final LongAdder statWaitTimeouts = new LongAdder();
    private final LongAdder statStaleFallbacks = new LongAdder();

    /** Chunk loads slower than this are reported individually, not just counted. */
    private static final long SLOW_LOAD_MILLIS = 500L;

    private volatile long lastSummaryAt = System.currentTimeMillis();

    private volatile SearchParams params;
    private volatile boolean running = false;

    public RtpEngine(@NotNull EasyTPPlugin plugin, @NotNull DatabaseManager databaseManager, @NotNull RtpScheduler scheduler) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.scheduler = scheduler;
        this.params = SearchParams.fromConfig(plugin);
        this.storage = new RtpStorage(databaseManager);
        this.spatialMemory = new SpatialMemory(storage, params.spatialCellSize(), params.spatialMemoryMaxEntries());
        this.spiral = new SpiralCoordinateGenerator(params, storage);
        this.unsafeBlocks = loadUnsafeBlocks();
        this.biomeBlacklist = loadBiomeBlacklist();
    }

    /**
     * Initialize storage and start background refill/validation tasks.
     */
    public void start() {
        if (running) {
            return;
        }
        storage.initialize();
        running = true;
        // Generate candidates into L3 every 10 ticks.
        scheduler.runTimerAsync(this::refillTick, 20L, 10L);
        // Validate candidates every tick.
        scheduler.runTimerAsync(this::validationTick, 30L, 1L);
        // Emit the debug summary on its own timer, independently of player count, so an idle
        // server — the case that used to exhaust the heap — stays observable.
        scheduler.runTimerAsync(this::debugSummaryTick, 100L, 100L);
    }

    /**
     * Stop background tasks and fail any pending player requests.
     */
    public void shutdown() {
        running = false;
        scheduler.cancelAll();
        for (RtpCallback callback : pendingCallbacks.values()) {
            try {
                callback.onFailure().run();
            } catch (Exception ignored) {
            }
        }
        pendingCallbacks.clear();
        // Final flush of buffered spiral indices and spatial memory, then stop the writer thread.
        storage.close();
    }

    /**
     * Re-derive every config-driven field without restarting the background tasks.
     *
     * <p>The preload pools are updated in place, so already-pooled locations and queued players
     * survive the reload. The spatial memory cache is only rebuilt when its own settings changed,
     * because rebuilding discards the cached cell states — they are re-read from SQLite on demand,
     * but there is no reason to pay for that when nothing about the cache changed.</p>
     */
    public void reload() {
        SearchParams previous = this.params;
        SearchParams updated = SearchParams.fromConfig(plugin);
        this.params = updated;
        this.unsafeBlocks = loadUnsafeBlocks();
        this.biomeBlacklist = loadBiomeBlacklist();
        this.spiral = new SpiralCoordinateGenerator(updated, storage);

        if (updated.spatialCellSize() != previous.spatialCellSize()
                || updated.spatialMemoryMaxEntries() != previous.spatialMemoryMaxEntries()) {
            this.spatialMemory = new SpatialMemory(storage, updated.spatialCellSize(), updated.spatialMemoryMaxEntries());
        }

        for (RtpPool pool : worldPools.values()) {
            pool.applyConfig(updated.poolBaseSize(), updated.poolSizeMultiplier(),
                    updated.maxQueuedPlayers(), updated.playerWaitTimeoutSeconds());
        }
    }

    /**
     * Main entry point for a player-initiated random teleport.
     * The success callback is executed on the main thread.
     */
    public void randomTeleport(@NotNull Player player, @NotNull Consumer<Location> onSuccess, @NotNull Runnable onFailure) {
        if (!running) {
            onFailure.run();
            return;
        }

        World world = player.getWorld();
        if (!isRtpEnabledForWorld(world)) {
            onFailure.run();
            return;
        }
        RtpPool pool = getPool(world);

        // L1 hot pool: validated location whose chunk is already loaded.
        Location hot = tryHotPool(world, pool);
        if (hot != null) {
            statServedHot.increment();
            DebugLog.log("rtp", "%s served from the hot pool at %s", player.getName(), formatLocation(hot));
            onSuccess.accept(hot);
            return;
        }

        // L2 cold pool: validated location that needs a chunk load before teleport.
        RtpLocation cold = pool.pollCold();
        if (cold != null) {
            statServedCold.increment();
            DebugLog.log("rtp", "%s serving from the cold pool %s[%d, %d]",
                    player.getName(), cold.worldName(), (int) cold.x(), (int) cold.z());
            // A pooled location can go stale while the pool sits idle (its chunk unloads, or the
            // terrain changed). Fall back to generating a fresh one rather than failing outright.
            loadAndDeliver(player, cold, onSuccess, () -> {
                statStaleFallbacks.increment();
                DebugLog.log("rtp", "%s: pooled location %s[%d, %d] no longer validates; generating a fresh one",
                        player.getName(), cold.worldName(), (int) cold.x(), (int) cold.z());
                enqueueForFreshLocation(player, world, pool, onSuccess, onFailure);
            });
            return;
        }

        DebugLog.log("rtp", "%s: pools are empty; queueing for a fresh location", player.getName());
        enqueueForFreshLocation(player, world, pool, onSuccess, onFailure);
    }

    /**
     * Queue a player while fresh candidates are generated, failing them if the pool does not
     * produce a location within {@code rtp.pool.player-wait-timeout-seconds}.
     */
    private void enqueueForFreshLocation(@NotNull Player player, @NotNull World world, @NotNull RtpPool pool,
                                         @NotNull Consumer<Location> onSuccess, @NotNull Runnable onFailure) {
        if (!pool.queuePlayer(player, pool.currentGeneration())) {
            statQueueRejected.increment();
            DebugLog.log("rtp", "%s: RTP queue is full (max %d); failing the request",
                    player.getName(), params.maxQueuedPlayers());
            onFailure.run();
            return;
        }
        statQueued.increment();
        DebugLog.log("rtp", "%s: queued (waiting=%d, timeout=%ds, max queued=%d)",
                player.getName(), pool.waitingSize(), params.playerWaitTimeoutSeconds(), params.maxQueuedPlayers());
        pendingCallbacks.put(player.getUniqueId(), new RtpCallback(onSuccess, onFailure));
        scheduler.runAsync(() -> refillWorld(world, Math.max(4, params.poolBaseSize() / 2)));

        scheduler.runLaterGlobal(() -> {
            if (pendingCallbacks.remove(player.getUniqueId()) != null) {
                pool.removePlayer(player.getUniqueId());
                statWaitTimeouts.increment();
                DebugLog.log("rtp", "%s: timed out after %ds waiting for a pooled location",
                        player.getName(), params.playerWaitTimeoutSeconds());
                onFailure.run();
            }
        }, Math.max(20L, params.playerWaitTimeoutSeconds() * 20L));
    }

    @NotNull
    private String formatLocation(@NotNull Location location) {
        return String.format(Locale.ROOT, "%s[%.0f, %.0f, %.0f]",
                location.getWorld() == null ? "?" : location.getWorld().getName(),
                location.getX(), location.getY(), location.getZ());
    }

    @NotNull
    private RtpPool getPool(@NotNull World world) {
        return worldPools.computeIfAbsent(world.getName(), name -> new RtpPool(
                params.poolBaseSize(),
                params.poolSizeMultiplier(),
                params.maxQueuedPlayers(),
                params.playerWaitTimeoutSeconds()
        ));
    }

    @NotNull
    private Set<Material> loadUnsafeBlocks() {
        Set<Material> set = EnumSet.noneOf(Material.class);
        for (String name : plugin.getConfig().getStringList("rtp.unsafe-blocks")) {
            try {
                set.add(Material.valueOf(name.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Unknown unsafe block material: " + name);
            }
        }
        return set;
    }

    @NotNull
    private Set<NamespacedKey> loadBiomeBlacklist() {
        Set<NamespacedKey> set = new HashSet<>();
        for (String name : plugin.getConfig().getStringList("rtp.biome-blacklist")) {
            try {
                set.add(NamespacedKey.minecraft(name.toLowerCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid biome key in rtp.biome-blacklist: " + name);
            }
        }
        return set;
    }

    // region Pool fulfilment

    @Nullable
    private Location tryHotPool(@NotNull World world, @NotNull RtpPool pool) {
        RtpLocation location = pool.pollHot();
        if (location == null) {
            return null;
        }
        if (!world.getName().equals(location.worldName())) {
            // Wrong world: put back into the appropriate pool as a cold entry.
            World correctWorld = Bukkit.getWorld(location.worldName());
            if (correctWorld != null) {
                getPool(correctWorld).addValidated(location, false);
            }
            return null;
        }
        int blockX = (int) Math.floor(location.x());
        int blockZ = (int) Math.floor(location.z());
        if (!world.isChunkLoaded(blockX >> 4, blockZ >> 4)) {
            // Promote back to cold pool since the chunk unloaded.
            pool.addValidated(location, false);
            return null;
        }
        // Final main-thread sanity check using the live chunk.
        return fastRevalidate(world, blockX, (int) Math.floor(location.y()), blockZ);
    }

    private void loadAndDeliver(@NotNull Player player, @NotNull RtpLocation location, @NotNull Consumer<Location> onSuccess, @NotNull Runnable onFailure) {
        World world = Bukkit.getWorld(location.worldName());
        if (world == null || !world.equals(player.getWorld())) {
            onFailure.run();
            return;
        }
        int blockX = (int) Math.floor(location.x());
        int blockZ = (int) Math.floor(location.z());
        int chunkX = blockX >> 4;
        int chunkZ = blockZ >> 4;

        // Player-driven loads are never blocked by the in-flight cap, but they do count towards
        // it so the background preloader yields while a player is being served.
        loadChunkAsync(world, chunkX, chunkZ, "RTP delivery",
                chunk -> {
                    // includeBiome=true is required for biome checks.
                    ChunkSnapshot snapshot = chunk.getChunkSnapshot(true, true, false);
                    Location safe = evaluateSnapshot(world, snapshot, blockX, blockZ);
                    scheduler.runGlobal(() -> {
                        if (safe != null) {
                            spatialMemory.markSafe(world, blockX, blockZ);
                            onSuccess.accept(safe);
                        } else {
                            spatialMemory.markUnsafe(world, blockX, blockZ, RegionState.UNSAFE_BLOCK);
                            onFailure.run();
                        }
                    });
                },
                () -> scheduler.runGlobal(onFailure));
    }

    /**
     * Re-check a pooled location against the live chunk before handing it to a player.
     *
     * <p>Reading a chunk is only legal on the thread that owns its region, and on Folia the pooled
     * location is by definition far from the player, so this thread almost never owns it. Returning
     * {@code null} in that case is safe: the caller falls back to the cold path, which loads the
     * chunk asynchronously and re-evaluates it there. On Paper the check is always true from the
     * main thread, so behaviour is unchanged.</p>
     */
    @Nullable
    private Location fastRevalidate(@NotNull World world, int x, int y, int z) {
        Location location = new Location(world, x, y, z);
        if (!Bukkit.isOwnedByCurrentRegion(location)) {
            DebugLog.log("rtp", "skipping hot-pool revalidation of %s: region not owned by this thread",
                    formatLocation(location));
            return null;
        }
        Chunk chunk = world.getChunkAt(x >> 4, z >> 4);
        ChunkSnapshot snapshot = chunk.getChunkSnapshot(true, true, false);
        return evaluateSnapshot(world, snapshot, x, z);
    }

    // endregion

    // region Background refill

    private void refillTick() {
        if (!running) {
            return;
        }
        int online = Bukkit.getOnlinePlayers().size();
        if (online <= 0) {
            // Nobody is online, so no player can consume a pooled location. Generating one would
            // load — and usually terrain-generate — a chunk that nobody will ever use. This is
            // what let an idle server exhaust its heap.
            statIdleSkips.increment();
            return;
        }
        for (World world : Bukkit.getWorlds()) {
            if (!isRtpEnabledForWorld(world)) {
                continue;
            }
            RtpPool pool = getPool(world);
            int target = pool.targetSize(online);
            // Count everything the pipeline already holds. Measuring only the L3 queue meant the
            // pool never reached a resting state: candidates were drained into the validated pools
            // and the queue was refilled again, forever. Once the validated pools are full this
            // yields <= 0, so generation idles until a teleport consumes a location.
            int pending = pool.validatedSize() + pool.candidateSize();
            int needed = target - pending;
            if (needed > 0) {
                int toGenerate = Math.min(needed, params.poolBaseSize());
                DebugLog.log("rtp", "refill[%s]: online=%d target=%d pending=%d -> generating %d",
                        world.getName(), online, target, pending, toGenerate);
                refillWorld(world, toGenerate);
            }
            pool.trimToTarget(online);
        }
    }

    private void refillWorld(@NotNull World world, int count) {
        RtpPool pool = getPool(world);
        for (int i = 0; i < count; i++) {
            SpiralCoordinateGenerator.Coordinate coordinate = spiral.next(world);
            // The spiral falls back to an out-of-border point after its retry budget, so
            // drop those before probing: unreachable cells must not fill the spatial
            // memory cache or the candidate queue.
            if (!isInsideBorder(world, coordinate.x(), coordinate.z())) {
                statRejectedBorder.increment();
                continue;
            }
            RegionState state = spatialMemory.getState(world, coordinate.x(), coordinate.z());
            if (state == RegionState.UNKNOWN || state == RegionState.SAFE) {
                pool.offerCandidate(new RtpCandidate(world.getName(), coordinate.x(), coordinate.z()));
                statGenerated.increment();
            } else {
                statRejectedMemory.increment();
            }
        }
    }

    private boolean isInsideBorder(@NotNull World world, int x, int z) {
        return world.getWorldBorder().isInside(new Location(world, x, 64.0, z));
    }

    private boolean isRtpEnabledForWorld(@NotNull World world) {
        return switch (world.getEnvironment()) {
            case NORMAL, NETHER, THE_END -> true;
            default -> false;
        };
    }

    // endregion

    // region Background validation

    private void validationTick() {
        if (!running) {
            return;
        }
        if (Bukkit.getOnlinePlayers().isEmpty()) {
            // Chunk loads are the expensive part; do not issue any while the server is idle.
            statIdleSkips.increment();
            return;
        }
        // Backpressure. The per-tick settings are request *rates*, not a concurrency limit:
        // validateCandidate returns as soon as the load is submitted, so without this the request
        // rate is unbounded relative to how fast the chunk system can actually generate terrain.
        // Waiting for outstanding loads keeps the pending-IO queue from growing without bound.
        int budget = params.maxInFlightLoads() - inFlightLoads.get();
        if (budget <= 0) {
            statBackpressureSkips.increment();
            return;
        }
        int limit = Math.max(1, Math.min(Math.min(params.maxValidationsPerTick(), params.maxChunkLoadsPerTick()), budget));
        List<RtpPool> pools = List.copyOf(worldPools.values());
        int processed = 0;
        int round = 0;
        while (processed < limit) {
            boolean any = false;
            for (RtpPool pool : pools) {
                if (processed >= limit) {
                    break;
                }
                // Drain each pool in round-robin order to respect the global per-tick limit.
                RtpCandidate candidate = pool.pollCandidate();
                if (candidate == null) {
                    continue;
                }
                any = true;
                validateCandidate(pool, candidate);
                processed++;
            }
            if (!any) {
                break;
            }
            round++;
            if (round > limit) {
                break;
            }
        }
    }

    private void validateCandidate(@NotNull RtpPool pool, @NotNull RtpCandidate candidate) {
        World world = Bukkit.getWorld(candidate.worldName());
        if (world == null || !isRtpEnabledForWorld(world)) {
            return;
        }

        int chunkX = candidate.x() >> 4;
        int chunkZ = candidate.z() >> 4;
        // Border check first: reject out-of-border candidates before the spatial memory
        // probe, so they never cause a database lookup.
        if (!isInsideBorder(world, candidate.x(), candidate.z())) {
            // Not persisted: the world border can move, and a stored UNSAFE_BORDER would
            // permanently skip a cell that later becomes reachable again.
            statRejectedBorder.increment();
            return;
        }

        RegionState memory = spatialMemory.getState(world, candidate.x(), candidate.z());
        if (memory.isUnsafe()) {
            statRejectedMemory.increment();
            return;
        }

        loadChunkAsync(world, chunkX, chunkZ, "RTP validation",
                chunk -> {
                    // includeBiome=true is required for biome checks.
                    ChunkSnapshot snapshot = chunk.getChunkSnapshot(true, true, false);
                    Location safe = evaluateSnapshot(world, snapshot, candidate.x(), candidate.z());
                    scheduler.runGlobal(() -> {
                        if (safe != null) {
                            spatialMemory.markSafe(world, candidate.x(), candidate.z());
                            pool.addValidated(new RtpLocation(world.getName(), safe.getX(), safe.getY(), safe.getZ()), chunk.isLoaded());
                            statValidated.increment();
                            fulfillWaitingPlayer(pool, world);
                        } else {
                            RegionState reason = determineUnsafeReason(world, snapshot, candidate.x(), candidate.z());
                            spatialMemory.markUnsafe(world, candidate.x(), candidate.z(), reason);
                            statUnsafe.increment();
                            DebugLog.log("rtp", "rejected %s[%d, %d] as %s",
                                    world.getName(), candidate.x(), candidate.z(), reason.getKey());
                        }
                    });
                });
    }

    /**
     * Report a failed chunk load. The in-flight slot is released by the caller that acquired it.
     */
    private void logLoadFailure(@NotNull String phase, @NotNull Throwable error) {
        plugin.getLogger().warning(phase + ": async chunk load failed: " + error.getMessage());
    }

    /**
     * Submit an asynchronous chunk load that holds one in-flight slot until it completes.
     *
     * <p>The slot is released exactly once on every path — normal completion, exceptional
     * completion, an exception thrown by {@code onLoaded}, and a synchronous failure of
     * {@code getChunkAtAsync} itself. Leaking a slot would permanently shrink the budget and
     * eventually stall validation for good, so this is deliberately the only place that touches
     * the counter.</p>
     */
    private void loadChunkAsync(@NotNull World world, int chunkX, int chunkZ, @NotNull String phase,
                                @NotNull Consumer<Chunk> onLoaded, @NotNull Runnable onFailed) {
        inFlightLoads.incrementAndGet();
        statLoadsRequested.increment();
        long startedAt = System.nanoTime();
        DebugLog.log("rtp", "%s: requesting %s[%d, %d] (in-flight %d/%d)",
                phase, world.getName(), chunkX, chunkZ, inFlightLoads.get(), params.maxInFlightLoads());
        CompletableFuture<Chunk> future;
        try {
            future = world.getChunkAtAsync(chunkX, chunkZ);
        } catch (RuntimeException e) {
            inFlightLoads.decrementAndGet();
            statLoadsFailed.increment();
            DebugLog.log("rtp", "%s: %s[%d, %d] could not even be requested: %s",
                    phase, world.getName(), chunkX, chunkZ, e.getMessage());
            logLoadFailure(phase, e);
            onFailed.run();
            return;
        }
        future.whenComplete((chunk, error) -> {
            try {
                if (error != null) {
                    statLoadsFailed.increment();
                    logLoadFailure(phase, error);
                    onFailed.run();
                    return;
                }
                statLoadsSucceeded.increment();
                recordLoadTiming(phase, world, chunkX, chunkZ, (System.nanoTime() - startedAt) / 1_000_000L);
                onLoaded.accept(chunk);
            } catch (RuntimeException e) {
                statLoadsFailed.increment();
                logLoadFailure(phase, e);
                onFailed.run();
            } finally {
                inFlightLoads.decrementAndGet();
            }
        });
    }

    /**
     * Track how long a chunk load took. Slow loads are the leading indicator of the chunk system
     * falling behind, so they are reported individually rather than only counted — this is the
     * signal that would have exposed the runaway preloader.
     */
    private void recordLoadTiming(@NotNull String phase, @NotNull World world, int chunkX, int chunkZ, long elapsedMillis) {
        statSlowestLoadMillis.accumulateAndGet(elapsedMillis, Math::max);
        if (elapsedMillis >= SLOW_LOAD_MILLIS) {
            statSlowLoads.increment();
            DebugLog.log("rtp", "%s: %s[%d, %d] took %dms", phase, world.getName(), chunkX, chunkZ, elapsedMillis);
        }
    }

    /** Variant for callers where a failed load needs no follow-up action. */
    private void loadChunkAsync(@NotNull World world, int chunkX, int chunkZ, @NotNull String phase,
                                @NotNull Consumer<Chunk> onLoaded) {
        loadChunkAsync(world, chunkX, chunkZ, phase, onLoaded, () -> {
        });
    }

    /**
     * Hand a freshly validated location to the next queued player.
     *
     * <p>This runs from the chunk-load callback, which is an async thread. The delivery itself
     * touches the player — chat messages, the countdown, the teleport — so on Folia it must be
     * scheduled onto the player's own region rather than run inline.</p>
     */
    private void fulfillWaitingPlayer(@NotNull RtpPool pool, @NotNull World world) {
        RtpPool.QueuedPlayer queued = pool.pollWaitingPlayer();
        if (queued == null) {
            return;
        }
        RtpCallback callback = pendingCallbacks.remove(queued.playerId());
        Player player = Bukkit.getPlayer(queued.playerId());
        if (callback == null || player == null || !player.isOnline()) {
            return;
        }
        player.getScheduler().run(plugin, scheduled -> deliverToWaitingPlayer(pool, world, queued, callback, player), null);
    }

    private void deliverToWaitingPlayer(@NotNull RtpPool pool, @NotNull World world,
                                        @NotNull RtpPool.QueuedPlayer queued, @NotNull RtpCallback callback,
                                        @NotNull Player player) {
        if (!player.isOnline()) {
            return;
        }
        Location hot = tryHotPool(world, pool);
        if (hot != null) {
            callback.onSuccess().accept(hot);
            DebugLog.log("rtp", "fulfilled waiting player %s from the hot pool", player.getName());
            return;
        }
        RtpLocation cold = pool.pollCold();
        if (cold != null) {
            loadAndDeliver(player, cold, callback.onSuccess(), callback.onFailure());
            return;
        }
        // No location available right now; restore the callback and player for the next fulfilled location.
        pendingCallbacks.put(queued.playerId(), callback);
        pool.requeuePlayer(queued);
    }

    @NotNull
    private RegionState determineUnsafeReason(@NotNull World world, @NotNull ChunkSnapshot snapshot, int x, int z) {
        if (isBlacklistedBiome(snapshot, x, z, world)) {
            return RegionState.UNSAFE_BIOME;
        }
        int localX = x & 0xF;
        int localZ = z & 0xF;
        for (int y = world.getMaxHeight() - 1; y >= world.getMinHeight(); y--) {
            Material type = snapshot.getBlockType(localX, y, localZ);
            if (type.isAir()) {
                continue;
            }
            if (type == Material.LAVA || type == Material.WATER || type == Material.BEDROCK) {
                return RegionState.UNSAFE_BLOCK;
            }
            return RegionState.UNSAFE_BLOCK;
        }
        return RegionState.UNSAFE_VOID;
    }

    // endregion

    // region Snapshot evaluation

    @Nullable
    private Location evaluateSnapshot(@NotNull World world, @NotNull ChunkSnapshot snapshot, int x, int z) {
        if (isBlacklistedBiome(snapshot, x, z, world)) {
            return null;
        }
        return switch (world.getEnvironment()) {
            case NORMAL -> findSafeSpotOverworld(snapshot, x, z, world);
            case NETHER -> findSafeSpotNether(snapshot, x, z, world);
            case THE_END -> findSafeSpotEnd(snapshot, x, z, world);
            default -> findSafeSpotOverworld(snapshot, x, z, world);
        };
    }

    private boolean isBlacklistedBiome(@NotNull ChunkSnapshot snapshot, int x, int z, @NotNull World world) {
        if (biomeBlacklist.isEmpty() || world.getEnvironment() != World.Environment.NORMAL) {
            return false;
        }
        int localX = x & 0xF;
        int localZ = z & 0xF;
        Biome biome = snapshot.getBiome(localX, world.getSeaLevel(), localZ);
        return biomeBlacklist.contains(biome.getKey());
    }

    @Nullable
    private Location findSafeSpotOverworld(@NotNull ChunkSnapshot snapshot, int x, int z, @NotNull World world) {
        int localX = x & 0xF;
        int localZ = z & 0xF;
        int surfaceY = findSurfaceY(snapshot, localX, localZ, world);
        int minY = Math.max(world.getMinHeight() + 1, surfaceY - params.maxScanDepth());
        Location fallbackSpot = null;

        for (int y = surfaceY; y >= minY; y--) {
            Material floor = snapshot.getBlockType(localX, y - 1, localZ);
            Material foot = snapshot.getBlockType(localX, y, localZ);
            Material head = snapshot.getBlockType(localX, y + 1, localZ);

            if (floor == Material.BEDROCK || isLiquidSurface(foot)) {
                continue;
            }
            if (!isSafeFloor(floor) || !isPassable(foot) || !isPassable(head)) {
                continue;
            }

            Location candidate = new Location(world, x + 0.5, y, z + 0.5);
            // Open sky: the highest non-air block in this column is below the player's head.
            if (surfaceY < y + 1) {
                return candidate;
            }
            if (fallbackSpot == null) {
                fallbackSpot = candidate;
            }
        }

        return params.surfaceOnly() ? null : fallbackSpot;
    }

    @Nullable
    private Location findSafeSpotNether(@NotNull ChunkSnapshot snapshot, int x, int z, @NotNull World world) {
        int localX = x & 0xF;
        int localZ = z & 0xF;
        int startY = Math.min(world.getMaxHeight() - 5, 120);

        for (int y = startY; y >= world.getMinHeight() + 1; y--) {
            Material floor = snapshot.getBlockType(localX, y - 1, localZ);
            Material foot = snapshot.getBlockType(localX, y, localZ);
            Material head = snapshot.getBlockType(localX, y + 1, localZ);

            if (floor == Material.BEDROCK || floor == Material.LAVA) {
                continue;
            }
            if (isLiquidSurface(foot) || isLiquidSurface(head)) {
                continue;
            }
            if (isSafeFloor(floor) && isPassable(foot) && isPassable(head)) {
                return new Location(world, x + 0.5, y, z + 0.5);
            }
        }
        return null;
    }

    @Nullable
    private Location findSafeSpotEnd(@NotNull ChunkSnapshot snapshot, int x, int z, @NotNull World world) {
        int localX = x & 0xF;
        int localZ = z & 0xF;
        int startY = Math.min(world.getMaxHeight() - 5, 80);

        for (int y = startY; y >= world.getMinHeight() + 1; y--) {
            Material floor = snapshot.getBlockType(localX, y - 1, localZ);
            Material foot = snapshot.getBlockType(localX, y, localZ);
            Material head = snapshot.getBlockType(localX, y + 1, localZ);

            if (floor == Material.BEDROCK) {
                continue;
            }
            if (isLiquidSurface(foot) || isLiquidSurface(head)) {
                continue;
            }
            if (isSafeFloor(floor) && isPassable(foot) && isPassable(head)) {
                return new Location(world, x + 0.5, y, z + 0.5);
            }
        }
        return null;
    }

    private int findSurfaceY(@NotNull ChunkSnapshot snapshot, int localX, int localZ, @NotNull World world) {
        for (int y = world.getMaxHeight() - 1; y >= world.getMinHeight(); y--) {
            if (!snapshot.getBlockType(localX, y, localZ).isAir()) {
                return y;
            }
        }
        return world.getMinHeight();
    }

    private boolean isLiquidSurface(@NotNull Material type) {
        if (params.allowLiquid()) {
            return false;
        }
        return type == Material.WATER
                || type == Material.LAVA
                || type == Material.BUBBLE_COLUMN
                || type == Material.KELP
                || type == Material.KELP_PLANT
                || type == Material.SEAGRASS
                || type == Material.TALL_SEAGRASS;
    }

    private boolean isSafeFloor(@NotNull Material type) {
        return type.isSolid() && !unsafeBlocks.contains(type);
    }

    private boolean isPassable(@NotNull Material type) {
        if (type.isAir()
                || type == Material.VINE
                || type == Material.TALL_GRASS
                || type == Material.SHORT_GRASS
                || type == Material.SNOW) {
            return true;
        }
        if (!params.allowLiquid() && (type == Material.WATER || type == Material.LAVA)) {
            return false;
        }
        return !type.isSolid();
    }

    // endregion

    // region Sync helpers for structure RTP

    /**
     * Synchronously evaluates a single column.
     *
     * <p>Intended for callers already running on the thread that owns the target region — structure
     * RTP schedules itself there first. Reading a chunk from a thread that does not own its region
     * is illegal on Folia, so this refuses rather than risking a crash; the caller reports a normal
     * search failure.</p>
     */
    @Nullable
    public Location findSafeSpotSync(@NotNull World world, int x, int z) {
        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        if (!Bukkit.isOwnedByCurrentRegion(world, chunkX, chunkZ)) {
            DebugLog.log("rtp", "refusing synchronous column scan at %s[%d, %d]: region not owned by this thread",
                    world.getName(), x, z);
            return null;
        }
        Chunk chunk = world.getChunkAt(chunkX, chunkZ);
        ChunkSnapshot snapshot = chunk.getChunkSnapshot(true, true, false);
        return evaluateSnapshot(world, snapshot, x, z);
    }

    /**
     * Searches for a safe spot near the given origin. Intended for main-thread callers (structure RTP).
     */
    @Nullable
    public Location findSafeSpotNearSync(@NotNull World world, int sx, int sz, int maxDistance) {
        if (world.getWorldBorder().isInside(new Location(world, sx, 64, sz))) {
            Location immediate = findSafeSpotSync(world, sx, sz);
            if (immediate != null) {
                return immediate;
            }
        }

        int attempts = Math.max(30, maxDistance / 4);
        for (int i = 0; i < attempts; i++) {
            double angle = ThreadLocalRandom.current().nextDouble() * 2 * Math.PI;
            int radius = Math.min(maxDistance, 5 + i * 4);
            int x = sx + (int) (Math.cos(angle) * radius);
            int z = sz + (int) (Math.sin(angle) * radius);
            if (!world.getWorldBorder().isInside(new Location(world, x, 64, z))) {
                continue;
            }
            Location loc = findSafeSpotSync(world, x, z);
            if (loc != null) {
                return loc;
            }
        }
        return null;
    }

    // endregion

    // region Debug summary

    /**
     * Emit the pipeline summary at the configured interval.
     *
     * <p>Runs on its own timer rather than piggy-backing on the refill or validation ticks, because
     * those deliberately do nothing while nobody is online — and an idle server is exactly the
     * situation this output needs to describe.</p>
     */
    private void debugSummaryTick() {
        if (!DebugLog.isEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastSummaryAt < DebugLog.summaryIntervalSeconds() * 1000L) {
            return;
        }
        long elapsedSeconds = Math.max(1L, (now - lastSummaryAt) / 1000L);
        lastSummaryAt = now;
        emitDebugSummary(elapsedSeconds);
    }

    private void emitDebugSummary(long elapsedSeconds) {
        long requested = statLoadsRequested.sumThenReset();
        long succeeded = statLoadsSucceeded.sumThenReset();
        long failed = statLoadsFailed.sumThenReset();
        long slow = statSlowLoads.sumThenReset();
        long slowestMillis = statSlowestLoadMillis.getAndSet(0L);

        DebugLog.log("rtp", "---- RTP summary over %ds ----", elapsedSeconds);
        DebugLog.log("rtp", "chunk loads: requested=%d ok=%d failed=%d slow(>=%dms)=%d slowest=%dms",
                requested, succeeded, failed, SLOW_LOAD_MILLIS, slow, slowestMillis);
        DebugLog.log("rtp", "throttle: in-flight=%d/%d backpressureSkips=%d idleSkips=%d",
                inFlightLoads.get(), params.maxInFlightLoads(),
                statBackpressureSkips.sumThenReset(), statIdleSkips.sumThenReset());
        DebugLog.log("rtp", "pipeline: generated=%d borderRejected=%d memoryRejected=%d validated=%d unsafe=%d",
                statGenerated.sumThenReset(), statRejectedBorder.sumThenReset(), statRejectedMemory.sumThenReset(),
                statValidated.sumThenReset(), statUnsafe.sumThenReset());
        DebugLog.log("rtp", "serve: hot=%d cold=%d queued=%d queueRejected=%d waitTimeouts=%d staleFallbacks=%d",
                statServedHot.sumThenReset(), statServedCold.sumThenReset(), statQueued.sumThenReset(),
                statQueueRejected.sumThenReset(), statWaitTimeouts.sumThenReset(), statStaleFallbacks.sumThenReset());
        DebugLog.log("rtp", "spatial memory: hits=%d misses=%d cachedCells=%d",
                spatialMemory.drainHits(), spatialMemory.drainMisses(), spatialMemory.cacheSize());
        DebugLog.log("rtp", "storage: cellsFlushed=%d spiralFlushed=%d failures=%d pendingCells=%d",
                storage.drainFlushedCells(), storage.drainFlushedSpiral(),
                storage.drainFlushFailures(), storage.pendingCellCount());

        for (World world : Bukkit.getWorlds()) {
            RtpPool pool = worldPools.get(world.getName());
            if (pool == null) {
                continue;
            }
            // How many pooled locations still have a loaded chunk is the direct measure of whether
            // probed chunks are being retained. A value that stays near the pool size while the
            // server is otherwise idle means the pool is pinning chunks.
            int stillLoaded = pool.countValidated(this::isLocationChunkLoaded);
            DebugLog.log("rtp", "pool[%s]: hot=%d cold=%d candidates=%d waiting=%d chunksStillLoaded=%d/%d",
                    world.getName(), pool.hotSize(), pool.coldSize(), pool.candidateSize(),
                    pool.waitingSize(), stillLoaded, pool.validatedSize());
        }
    }

    private boolean isLocationChunkLoaded(@NotNull RtpLocation location) {
        World world = Bukkit.getWorld(location.worldName());
        if (world == null) {
            return false;
        }
        int blockX = (int) Math.floor(location.x());
        int blockZ = (int) Math.floor(location.z());
        return world.isChunkLoaded(blockX >> 4, blockZ >> 4);
    }

    // endregion

    private record RtpCallback(@NotNull Consumer<Location> onSuccess, @NotNull Runnable onFailure) {
    }
}
