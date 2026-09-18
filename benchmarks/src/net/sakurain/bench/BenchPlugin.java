package net.sakurain.bench;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.sakurain.mc.easytp.EasyTPPlugin;
import net.sakurain.mc.easytp.rtp.RtpStorage;
import net.sakurain.mc.easytp.rtp.SearchParams;
import net.sakurain.mc.easytp.rtp.memory.RegionState;
import net.sakurain.mc.easytp.rtp.memory.SpatialMemory;
import net.sakurain.mc.easytp.rtp.pool.RtpCandidate;
import net.sakurain.mc.easytp.rtp.pool.RtpLocation;
import net.sakurain.mc.easytp.rtp.pool.RtpPool;
import net.sakurain.mc.easytp.rtp.spiral.SpiralCoordinateGenerator;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headless benchmark harness for the EasyTP RTP pipeline.
 *
 * <p>Runs on a real Paper server and drives the production classes directly, so the
 * reported numbers describe the shipped implementation rather than a model of it.
 * {@code /rtp} needs a connected client, which a headless run cannot provide, so the
 * harness calls the engine's own public entry points instead.</p>
 *
 * <p>The suite runs on an <b>async</b> thread. Anything that must touch the server
 * thread is dispatched through {@code GlobalRegionScheduler} and awaited from there;
 * blocking the server thread would deadlock, because asynchronous chunk loads cannot
 * complete while the tick loop is stalled.</p>
 *
 * <p>Output goes to {@code bench-out/} in the server working directory. Besides the CSV
 * dumps, every run writes {@code console.txt}: B1 and B5 report wall-clock timings that
 * no CSV column carries, so that file is the only durable record of them.</p>
 */
public final class BenchPlugin extends JavaPlugin {

    private static final int SPIRAL_POINTS = 200_000;
    private static final int CHUNK_SAMPLES = 64;
    private static final int POOL_TICKS = 60;
    private static final int LRU_CELLS = 60_000;
    private static final int LRU_CAPACITY = 50_000;
    private static final int POOL_ONLINE = 1;
    private static final int POOL_BASE_SIZE = 12;

    private Path outDir;

    /**
     * Every informational line is kept as well as logged, so a run leaves a
     * {@code console.txt} behind next to the CSV output. The timing lines are the only
     * record of B1 and B5 -- they are not derivable from the coordinate dumps -- so
     * without this file those numbers exist only in the server log.
     */
    private final List<String> notes = Collections.synchronizedList(new ArrayList<>());

    private void note(String message) {
        getLogger().info(message);
        notes.add(message);
    }

    private final ConcurrentLinkedQueue<Double> tickSamples = new ConcurrentLinkedQueue<>();
    private volatile boolean sampling = false;
    private ScheduledTask samplerTask;

    @Override
    public void onEnable() {
        outDir = getServer().getWorldContainer().toPath().resolve("bench-out");
        try {
            Files.createDirectories(outDir);
        } catch (IOException e) {
            getLogger().severe("cannot create " + outDir + ": " + e.getMessage());
            return;
        }

        EasyTPPlugin easyTP = EasyTPPlugin.getInstance();
        if (easyTP == null) {
            getLogger().severe("EasyTP is not loaded; nothing to benchmark");
            Bukkit.shutdown();
            return;
        }

        // Let startup finish, then hand the whole suite to an async thread.
        Bukkit.getGlobalRegionScheduler().runDelayed(this, task ->
                Bukkit.getAsyncScheduler().runNow(this, async -> {
                    try {
                        runAll(easyTP);
                    } catch (Throwable t) {
                        getLogger().severe("benchmark failed: " + t);
                        t.printStackTrace();
                    } finally {
                        Bukkit.shutdown();
                    }
                }), 200L);
    }

    @Override
    public void onDisable() {
        if (samplerTask != null) {
            samplerTask.cancel();
        }
    }

    // ------------------------------------------------------------------
    // Suite
    // ------------------------------------------------------------------

    private void runAll(EasyTPPlugin easyTP) throws Exception {
        World world = Bukkit.getWorlds().get(0);
        note("=== EasyTP RTP benchmark ===");
        note("world=" + world.getName()
                + " border=" + world.getWorldBorder().getSize()
                + " minY=" + world.getMinHeight() + " maxY=" + world.getMaxHeight()
                + " cores=" + Runtime.getRuntime().availableProcessors()
                + " maxHeapMB=" + (Runtime.getRuntime().maxMemory() >> 20)
                + " java=" + System.getProperty("java.version"));

        RtpStorage storage = new RtpStorage(easyTP.getDatabaseManager());
        storage.initialize();

        SearchParams single = singleRingParams();
        SearchParams shipped = SearchParams.fromConfig(easyTP);

        List<SpiralCoordinateGenerator.Coordinate> singlePts = benchSpiral(world, storage, single, "single");
        benchSpiral(world, storage, shipped, "shipped");
        benchPoolConservation(singlePts);
        benchSpatialMemory(world, storage);
        benchChunkCost(easyTP, world, singlePts);

        storage.flush();
        storage.close();
        note("=== benchmark complete ===");
        writeConsoleSummary();
    }

    /** Persists the logged lines so the timing numbers survive outside the server log. */
    private void writeConsoleSummary() {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(
                outDir.resolve("console.txt"), StandardCharsets.UTF_8))) {
            for (String line : notes) {
                w.println(line);
            }
        } catch (IOException e) {
            getLogger().warning("cannot write console.txt: " + e.getMessage());
        }
    }

    /** Matches figure 3: one ring 2000-5000, s=16. */
    private SearchParams singleRingParams() {
        return new SearchParams(
                true, 16, false, 16, 32, LRU_CAPACITY,
                List.of(new SearchParams.RingConfig("single", 2000, 5000, 1.0)),
                POOL_BASE_SIZE, 2.0, 2, 2, 8, 15, 10);
    }

    // ------------------------------------------------------------------
    // B1: coordinate generation through the production generator
    // ------------------------------------------------------------------

    private List<SpiralCoordinateGenerator.Coordinate> benchSpiral(
            World world, RtpStorage storage, SearchParams params, String tag) throws IOException {

        SpiralCoordinateGenerator spiral = new SpiralCoordinateGenerator(params, storage);

        for (int i = 0; i < 20_000; i++) {
            spiral.next(world);
        }

        List<SpiralCoordinateGenerator.Coordinate> pts = new ArrayList<>(SPIRAL_POINTS);
        long t0 = System.nanoTime();
        for (int i = 0; i < SPIRAL_POINTS; i++) {
            pts.add(spiral.next(world));
        }
        long ns = System.nanoTime() - t0;

        note(String.format(Locale.ROOT,
                "[B1 %s] %d points in %.1f ms -> %.0f points/s (%.0f ns/point)",
                tag, SPIRAL_POINTS, ns / 1e6, SPIRAL_POINTS * 1e9 / ns, (double) ns / SPIRAL_POINTS));

        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(
                outDir.resolve("spiral_" + tag + ".csv"), StandardCharsets.UTF_8))) {
            w.println("x,z");
            for (SpiralCoordinateGenerator.Coordinate c : pts) {
                w.println(c.x() + "," + c.z());
            }
        }

        long estimated = 0;
        for (SearchParams.RingConfig r : params.rings()) {
            estimated += (long) (Math.PI * ((long) r.maxRadius() * r.maxRadius()
                    - (long) r.minRadius() * r.minRadius())) / (params.gridSpacing() * params.gridSpacing());
        }
        note("[B1 " + tag + "] analytic ring capacity = " + estimated);

        return pts;
    }

    // ------------------------------------------------------------------
    // B3: pipeline conservation with the production RtpPool
    // ------------------------------------------------------------------

    private void benchPoolConservation(List<SpiralCoordinateGenerator.Coordinate> pts) throws IOException {
        int validationsPerTick = 2;

        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(
                outDir.resolve("pool_conservation.csv"), StandardCharsets.UTF_8))) {
            w.println("metric,tick,candidates,validated,generated_total,validated_total,target");

            for (String metric : new String[]{"candidate_only", "validated_plus_candidate"}) {
                RtpPool pool = new RtpPool(POOL_BASE_SIZE, 2.0, 10, 15);
                // The engine derives the refill target and the trim target from the same
                // call, so the harness must too; using a constant here would compare two
                // different policies and invalidate the result.
                int target = pool.targetSize(POOL_ONLINE);

                int cursor = 0;
                long generatedTotal = 0;
                long validatedTotal = 0;

                for (int tick = 1; tick <= POOL_TICKS; tick++) {
                    int candidateOnly = pool.candidateSize();
                    int pending = metric.equals("candidate_only")
                            ? candidateOnly
                            : pool.validatedSize() + candidateOnly;

                    int needed = target - pending;
                    if (needed > 0) {
                        int toGenerate = Math.min(needed, POOL_BASE_SIZE);
                        for (int i = 0; i < toGenerate; i++) {
                            SpiralCoordinateGenerator.Coordinate c = pts.get(cursor++ % pts.size());
                            pool.offerCandidate(new RtpCandidate("bench", c.x(), c.z()));
                        }
                        generatedTotal += toGenerate;
                    }

                    for (int i = 0; i < validationsPerTick; i++) {
                        RtpCandidate c = pool.pollCandidate();
                        if (c == null) {
                            break;
                        }
                        pool.addValidated(new RtpLocation(c.worldName(), c.x(), 64.0, c.z()), false);
                        validatedTotal++;
                    }

                    pool.trimToTarget(POOL_ONLINE);
                    w.printf(Locale.ROOT, "%s,%d,%d,%d,%d,%d,%d%n",
                            metric, tick, pool.candidateSize(), pool.validatedSize(),
                            generatedTotal, validatedTotal, target);
                }

                note(String.format(Locale.ROOT,
                        "[B3 %s] target=%d after %d ticks: generatedTotal=%d validatedTotal=%d"
                                + " candidates=%d validatedPool=%d",
                        metric, target, POOL_TICKS, generatedTotal, validatedTotal,
                        pool.candidateSize(), pool.validatedSize()));
            }
        }
    }

    // ------------------------------------------------------------------
    // B5: spatial memory LRU bound, hit rate and the pending-write layer
    // ------------------------------------------------------------------

    private void benchSpatialMemory(World world, RtpStorage storage) throws IOException {
        SpatialMemory memory = new SpatialMemory(storage, 32, LRU_CAPACITY);
        Random rnd = new Random(7);

        int n = LRU_CELLS;
        int[] xs = new int[n];
        int[] zs = new int[n];
        Set<Long> seen = new HashSet<>(n * 2);
        int i = 0;
        while (i < n) {
            int x = rnd.nextInt(400_000) - 200_000;
            int z = rnd.nextInt(400_000) - 200_000;
            if (seen.add((((long) x) << 32) ^ (z & 0xffffffffL))) {
                xs[i] = x;
                zs[i] = z;
                i++;
            }
        }

        long t0 = System.nanoTime();
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(
                outDir.resolve("spatial_memory.csv"), StandardCharsets.UTF_8))) {
            w.println("cells_written,cache_size,capacity");
            for (int k = 0; k < n; k++) {
                memory.setState(world, xs[k], zs[k], RegionState.SAFE);
                if ((k + 1) % 5_000 == 0) {
                    w.printf(Locale.ROOT, "%d,%d,%d%n", k + 1, memory.cacheSize(), LRU_CAPACITY);
                }
            }
        }
        long writeNs = System.nanoTime() - t0;

        // xs[0] was written first and is therefore evicted from the LRU. If the
        // pending-write layer works, this read still returns SAFE without a database
        // round trip; without it the read would return UNKNOWN, because the row has
        // not been flushed yet.
        int pendingBefore = storage.pendingCellCount();
        long t1 = System.nanoTime();
        RegionState probe = memory.getState(world, xs[0], zs[0]);
        long probeNs = System.nanoTime() - t1;

        for (int k = 0; k < 20_000; k++) {
            int j = rnd.nextInt(n);
            memory.getState(world, xs[j], zs[j]);
        }
        long hits = memory.drainHits();
        long misses = memory.drainMisses();

        note(String.format(Locale.ROOT,
                "[B5] wrote %d cells in %.0f ms (%.0f ns/cell); cacheSize=%d (capacity=%d)",
                n, writeNs / 1e6, (double) writeNs / n, memory.cacheSize(), LRU_CAPACITY));
        note(String.format(Locale.ROOT,
                "[B5] pending-write probe: evicted cell returns %s in %.0f us (pendingCellCount=%d)",
                probe, probeNs / 1e3, pendingBefore));
        note(String.format(Locale.ROOT,
                "[B5] after 20000 random reads: hits=%d misses=%d hitRate=%.1f%%",
                hits, misses, 100.0 * hits / Math.max(1, hits + misses)));

        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(
                outDir.resolve("spatial_memory_probe.txt"), StandardCharsets.UTF_8))) {
            w.printf(Locale.ROOT,
                    "cells=%d%ncacheSize=%d%ncapacity=%d%nprobe_state=%s%n"
                            + "probe_micros=%.1f%npending_before_probe=%d%nhits=%d%nmisses=%d%n",
                    n, memory.cacheSize(), LRU_CAPACITY, probe, probeNs / 1e3, pendingBefore, hits, misses);
        }
    }

    // ------------------------------------------------------------------
    // B2: real chunk-load and real column-scan cost
    // ------------------------------------------------------------------

    private void benchChunkCost(EasyTPPlugin easyTP, World world,
                                List<SpiralCoordinateGenerator.Coordinate> pts) throws Exception {

        int n = Math.min(CHUNK_SAMPLES, pts.size() / 4);
        // Disjoint sample sets: the capped run must not inherit chunks the burst run
        // already generated, or it would measure warm-cache latency instead.
        int[] burstX = new int[n];
        int[] burstZ = new int[n];
        int[] cappedX = new int[n];
        int[] cappedZ = new int[n];
        for (int i = 0; i < n; i++) {
            SpiralCoordinateGenerator.Coordinate a = pts.get(i * 7 % pts.size());
            burstX[i] = a.x();
            burstZ[i] = a.z();
            SpiralCoordinateGenerator.Coordinate b = pts.get((i * 7 + pts.size() / 2) % pts.size());
            cappedX[i] = b.x();
            cappedZ[i] = b.z();
        }

        startSampler();
        long[] burstLatency = loadChunks(world, burstX, burstZ, Integer.MAX_VALUE, "burst");
        double burstTickP99 = stopSampler();
        note(String.format(Locale.ROOT,
                "[B2 burst] n=%d uncapped load p50=%.0fms p99=%.0fms max=%.0fms | tickTime p99=%.1fms",
                n, pct(burstLatency, 50), pct(burstLatency, 99), pct(burstLatency, 100), burstTickP99));

        startSampler();
        long[] cappedLatency = loadChunks(world, cappedX, cappedZ, 8, "capped");
        double cappedTickP99 = stopSampler();
        note(String.format(Locale.ROOT,
                "[B2 capped] n=%d capped-at-8 load p50=%.0fms p99=%.0fms max=%.0fms | tickTime p99=%.1fms",
                n, pct(cappedLatency, 50), pct(cappedLatency, 99), pct(cappedLatency, 100), cappedTickP99));

        Object engine = engineOf(easyTP);
        Method scan = engine.getClass().getMethod("findSafeSpotSync", World.class, int.class, int.class);

        List<Long> acquireNs = new ArrayList<>();
        List<Long> snapshotNs = new ArrayList<>();
        List<Long> scanNs = new ArrayList<>();
        AtomicInteger safe = new AtomicInteger();
        AtomicInteger hadToLoad = new AtomicInteger();

        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(
                outDir.resolve("chunk_cost.csv"), StandardCharsets.UTF_8))) {
            w.println("stage,index,millis,note");

            // Chunk access and the column scan must run on the server thread. Batching
            // keeps each stall short instead of freezing the tick loop for the whole set.
            int batch = 8;
            for (int from = 0; from < n; from += batch) {
                final int lo = from;
                final int hi = Math.min(n, from + batch);
                runOnMainAndWait(() -> {
                    for (int i = lo; i < hi; i++) {
                        int cx = burstX[i] >> 4;
                        int cz = burstZ[i] >> 4;
                        boolean wasLoaded = world.isChunkLoaded(cx, cz);

                        long t0 = System.nanoTime();
                        Chunk chunk = world.getChunkAt(cx, cz);
                        long t1 = System.nanoTime();
                        chunk.getChunkSnapshot(true, true, false);
                        long t2 = System.nanoTime();
                        Object loc;
                        try {
                            loc = scan.invoke(engine, world, burstX[i], burstZ[i]);
                        } catch (ReflectiveOperationException e) {
                            throw new IllegalStateException(e);
                        }
                        long t3 = System.nanoTime();

                        acquireNs.add(t1 - t0);
                        snapshotNs.add(t2 - t1);
                        scanNs.add(t3 - t2);
                        if (loc != null) {
                            safe.incrementAndGet();
                        }
                        if (!wasLoaded) {
                            hadToLoad.incrementAndGet();
                        }
                        w.printf(Locale.ROOT, "chunk_acquire,%d,%.4f,%s%n", i, (t1 - t0) / 1e6,
                                wasLoaded ? "already_loaded" : "had_to_load");
                        w.printf(Locale.ROOT, "snapshot,%d,%.4f,%s%n", i, (t2 - t1) / 1e6,
                                wasLoaded ? "already_loaded" : "had_to_load");
                        w.printf(Locale.ROOT, "column_scan,%d,%.4f,%s%n", i, (t3 - t2) / 1e6,
                                loc == null ? "unsafe" : "safe");
                    }
                });
            }

            for (int i = 0; i < n; i++) {
                w.printf(Locale.ROOT, "load_burst,%d,%.3f,burst%n", i, burstLatency[i] / 1e6);
                w.printf(Locale.ROOT, "load_capped,%d,%.3f,capped%n", i, cappedLatency[i] / 1e6);
            }
        }

        long[] acqArr = toArray(acquireNs);
        long[] snapArr = toArray(snapshotNs);
        long[] scnArr = toArray(scanNs);
        note(String.format(Locale.ROOT,
                "[B2 sync] n=%d (had to load %d) | chunk acquire p50=%.3fms p99=%.3fms"
                        + " | snapshot p50=%.3fms p99=%.3fms"
                        + " | column scan p50=%.4fms p99=%.4fms | safe=%d/%d (%.0f%%)",
                acqArr.length, hadToLoad.get(),
                pct(acqArr, 50), pct(acqArr, 99),
                pct(snapArr, 50), pct(snapArr, 99),
                pct(scnArr, 50), pct(scnArr, 99),
                safe.get(), scnArr.length, 100.0 * safe.get() / Math.max(1, scnArr.length)));
        note(String.format(Locale.ROOT,
                "[B2 totals] over %d columns: sync acquire=%.1fms snapshot=%.1fms scan=%.1fms",
                scnArr.length, sumMs(acqArr), sumMs(snapArr), sumMs(scnArr)));

        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(
                outDir.resolve("chunk_summary.txt"), StandardCharsets.UTF_8))) {
            w.printf(Locale.ROOT,
                    "burst_p50_ms=%.3f%nburst_p99_ms=%.3f%nburst_max_ms=%.3f%nburst_tick_p99_ms=%.2f%n"
                            + "capped_p50_ms=%.3f%ncapped_p99_ms=%.3f%ncapped_max_ms=%.3f%ncapped_tick_p99_ms=%.2f%n"
                            + "acquire_p50_ms=%.4f%nacquire_p99_ms=%.4f%n"
                            + "snapshot_p50_ms=%.4f%nsnapshot_p99_ms=%.4f%n"
                            + "scan_p50_ms=%.5f%nscan_p99_ms=%.5f%n"
                            + "safe=%d%nn=%d%nhad_to_load=%d%n",
                    pct(burstLatency, 50), pct(burstLatency, 99), pct(burstLatency, 100), burstTickP99,
                    pct(cappedLatency, 50), pct(cappedLatency, 99), pct(cappedLatency, 100), cappedTickP99,
                    pct(acqArr, 50), pct(acqArr, 99),
                    pct(snapArr, 50), pct(snapArr, 99),
                    pct(scnArr, 50), pct(scnArr, 99),
                    safe.get(), scnArr.length, hadToLoad.get());
        }
    }

    private long[] loadChunks(World world, int[] xs, int[] zs,
                              int maxInFlight, String tag) throws InterruptedException {
        int n = xs.length;
        long[] latency = new long[n];
        CountDownLatch done = new CountDownLatch(n);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicLong peak = new AtomicLong();
        AtomicLong failures = new AtomicLong();

        Thread submitter = new Thread(() -> {
            for (int i = 0; i < n; i++) {
                if (maxInFlight != Integer.MAX_VALUE) {
                    while (inFlight.get() >= maxInFlight) {
                        Thread.onSpinWait();
                    }
                }
                int idx = i;
                int cx = xs[i] >> 4;
                int cz = zs[i] >> 4;
                inFlight.incrementAndGet();
                peak.accumulateAndGet(inFlight.get(), Math::max);
                long t0 = System.nanoTime();
                world.getChunkAtAsync(cx, cz).whenComplete((chunk, err) -> {
                    latency[idx] = System.nanoTime() - t0;
                    if (err != null) {
                        failures.incrementAndGet();
                    }
                    inFlight.decrementAndGet();
                    done.countDown();
                });
            }
        }, "bench-submit-" + tag);
        submitter.setDaemon(true);
        submitter.start();

        if (!done.await(300, TimeUnit.SECONDS)) {
            getLogger().warning("[" + tag + "] timed out waiting for chunk loads");
        }
        note("[" + tag + "] peak in-flight=" + peak.get()
                + " failures=" + failures.get()
                + " loadedNow=" + countLoaded(world, xs, zs) + "/" + n);
        return latency;
    }

    /** Dispatch to the server thread and wait for it from the calling async thread. */
    private void runOnMainAndWait(Runnable body) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Bukkit.getGlobalRegionScheduler().run(this, task -> {
            try {
                body.run();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(120, TimeUnit.SECONDS)) {
            throw new IllegalStateException("main-thread task timed out");
        }
        Throwable t = error.get();
        if (t != null) {
            throw new IllegalStateException("main-thread task failed", t);
        }
    }

    private static int countLoaded(World world, int[] xs, int[] zs) {
        int c = 0;
        for (int i = 0; i < xs.length; i++) {
            if (world.isChunkLoaded(xs[i] >> 4, zs[i] >> 4)) {
                c++;
            }
        }
        return c;
    }

    private Object engineOf(EasyTPPlugin easyTP) throws Exception {
        Field f = EasyTPPlugin.class.getDeclaredField("rtpEngine");
        f.setAccessible(true);
        return f.get(easyTP);
    }

    // ------------------------------------------------------------------
    // Tick-time sampling (written on the server thread, read on the async thread)
    // ------------------------------------------------------------------

    private void startSampler() {
        tickSamples.clear();
        sampling = true;
        samplerTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> {
            if (sampling) {
                tickSamples.add(Bukkit.getAverageTickTime());
            }
        }, 1L, 1L);
    }

    private double stopSampler() {
        sampling = false;
        if (samplerTask != null) {
            samplerTask.cancel();
            samplerTask = null;
        }
        double[] arr = new double[tickSamples.size()];
        int k = 0;
        for (double v : tickSamples) {
            arr[k++] = v;
        }
        Arrays.sort(arr);
        if (arr.length == 0) {
            return 0.0;
        }
        return arr[(int) Math.min(arr.length - 1, Math.round(arr.length * 0.99))];
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static long[] toArray(List<Long> list) {
        long[] a = new long[list.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = list.get(i);
        }
        return a;
    }

    private static double pct(long[] values, int p) {
        if (values.length == 0) {
            return 0.0;
        }
        long[] copy = values.clone();
        Arrays.sort(copy);
        int idx = (int) Math.min(copy.length - 1, Math.max(0, Math.round(copy.length * p / 100.0) - 1));
        return copy[idx] / 1e6;
    }

    private static double sumMs(long[] ns) {
        long s = 0;
        for (long v : ns) {
            s += v;
        }
        return s / 1e6;
    }
}
