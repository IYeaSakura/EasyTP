package net.sakurain.mc.easytp.rtp.memory;

import net.sakurain.mc.easytp.rtp.RtpStorage;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * In-memory spatial memory cache backed by SQLite.
 *
 * <p>The world is partitioned into square cells. Each cell stores a {@link RegionState}
 * that allows the RTP engine to skip known-bad regions without loading chunks.</p>
 *
 * <p>The cache is a bounded least-recently-used map, so memory stays flat no matter how
 * far the spiral generator roams. Evicted cells are reloaded from the database on demand,
 * which trades repeat lookups for memory but never costs correctness.</p>
 *
 * <p>{@link RegionState#UNKNOWN} is deliberately never cached: the generator probes far
 * more cells than it ever resolves, and a negative result carries no information worth
 * keeping.</p>
 */
public class SpatialMemory {

    private final RtpStorage storage;
    private final int cellSize;
    private final Map<String, RegionState> cache;
    // Diagnostics. LongAdder updates are cheap enough to leave unconditional, so the numbers are
    // already meaningful the moment debug is switched on.
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();

    public SpatialMemory(@NotNull RtpStorage storage, int cellSize, int maxEntries) {
        this.storage = storage;
        this.cellSize = Math.max(16, cellSize);
        int capacity = Math.max(1, maxEntries);
        this.cache = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(@NotNull Map.Entry<String, RegionState> eldest) {
                return size() > capacity;
            }
        });
    }

    /**
     * Return the state of the cell containing the given block coordinate.
     */
    @NotNull
    public RegionState getState(@NotNull World world, int blockX, int blockZ) {
        return getState(world.getName(), cell(blockX), cell(blockZ));
    }

    /**
     * Return the state of a specific cell.
     *
     * <p>Database access happens outside the cache lock, so a slow lookup never blocks
     * the server main thread.</p>
     */
    @NotNull
    public RegionState getState(@NotNull String world, int cellX, int cellZ) {
        String key = cacheKey(world, cellX, cellZ);
        RegionState cached = cache.get(key);
        if (cached != null) {
            hits.increment();
            return cached;
        }
        misses.increment();

        // A state that is queued for the database but not yet flushed must win over the
        // stored row, otherwise an evicted-and-reloaded cell could be read back stale.
        RegionState pending = storage.peekPendingRegionState(world, cellX, cellZ);
        if (pending != null) {
            cache.putIfAbsent(key, pending);
            return pending;
        }

        RegionState loaded = storage.loadRegionState(world, cellX, cellZ);
        if (loaded != RegionState.UNKNOWN) {
            // Do not clobber a newer state stored concurrently while we were reading.
            cache.putIfAbsent(key, loaded);
        }
        return loaded;
    }

    /**
     * Mark a cell as safe.
     */
    public void markSafe(@NotNull World world, int blockX, int blockZ) {
        setState(world, blockX, blockZ, RegionState.SAFE);
    }

    /**
     * Mark a cell with the given unsafe reason.
     */
    public void markUnsafe(@NotNull World world, int blockX, int blockZ, @NotNull RegionState reason) {
        if (reason == RegionState.UNKNOWN || reason == RegionState.SAFE) {
            throw new IllegalArgumentException("Unsafe reason must be an unsafe state");
        }
        setState(world, blockX, blockZ, reason);
    }

    /**
     * Set the state of the cell containing the given block coordinate.
     *
     * <p>The in-memory cache is updated immediately so later reads are consistent, while
     * the database write is buffered and performed by the RTP storage writer thread. This
     * keeps cell updates off the server main thread.</p>
     */
    public void setState(@NotNull World world, int blockX, int blockZ, @NotNull RegionState state) {
        int cellX = cell(blockX);
        int cellZ = cell(blockZ);
        cache.put(cacheKey(world.getName(), cellX, cellZ), state);
        storage.queueRegionState(world.getName(), cellX, cellZ, state);
    }

    private int cell(int blockCoord) {
        return Math.floorDiv(blockCoord, cellSize);
    }

    /**
     * Number of cells currently held in memory.
     */
    public int cacheSize() {
        return cache.size();
    }

    /**
     * Read and reset the cache hit counter.
     */
    public long drainHits() {
        return hits.sumThenReset();
    }

    /**
     * Read and reset the cache miss counter. A high miss rate against a small
     * {@code rtp.spatial-memory.max-entries} means the LRU is thrashing.
     */
    public long drainMisses() {
        return misses.sumThenReset();
    }

    @NotNull
    private String cacheKey(@NotNull String world, int cellX, int cellZ) {
        return world + ":" + cellX + ":" + cellZ;
    }
}
