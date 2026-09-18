# RTP benchmark harness

A throwaway Paper plugin used to measure the RTP pipeline on a real server. It exists
to answer one question the unit-test-less codebase otherwise cannot: **does the design
behave on a live server the way the analysis says it should?**

It is not shipped and not part of the plugin's runtime. Delete the directory if you do
not need it.

## What it does

`BenchPlugin` drives the production classes directly — same bytecode, no reimplementation:

| Probe | Drives | Measures |
|---|---|---|
| B1 | `SpiralCoordinateGenerator.next(World)` | generation throughput; dumps coordinates for distribution analysis |
| B2 | `World.getChunkAtAsync` / `getChunkAt`, `Chunk#getChunkSnapshot`, `RtpEngine.findSafeSpotSync` | chunk-acquisition cost vs. validation cost |
| B3 | `RtpPool` | cumulative generation and validation under both refill metrics |
| B5 | `SpatialMemory` + `RtpStorage` | LRU bound, hit rate, and whether the pending-write layer actually serves evicted cells |

`findSafeSpotSync` is reached reflectively because `RtpEngine` has no public accessor;
everything else is constructed directly.

The suite runs on an **async** thread and dispatches the chunks that must touch the
server thread through `GlobalRegionScheduler`. Results land in `bench-out/` in the
server working directory — the CSV dumps plus `console.txt`, which holds the logged
summary lines and is the only durable record of the B1 and B5 wall-clock timings. The
server shuts itself down when the run finishes.

## Running it

```powershell
mvn clean package                 # build EasyTP first
.\benchmarks\build_bench.ps1 -ServerDir <path-to-paper-server> -FreshWorld
cd <path-to-paper-server>
java -Xms2G -Xmx2G -jar paper-*.jar --nogui
```

Run it twice to get both cost regimes, because chunk acquisition differs by an order of
magnitude between them:

- `-FreshWorld` deletes the level directories first, so every sampled chunk has to be
  terrain-generated.
- Without it the world is reused, so the same coordinates only need a disk read.

`build_bench.ps1` is Windows PowerShell. The harness itself is plain Java and runs
anywhere; only the deploy helper is Windows-specific.

The server directory needs `eula.txt` with `eula=true` and a `plugins/` folder.

## Four traps that corrupt the data silently

Each of these produced plausible-looking but wrong numbers before being noticed. None
of them raises an error.

1. **A stale spiral index.** The index is persisted in `plugins/EasyTP/data.db`. If a
   previous run left one behind, the next run starts mid-cycle and wraps at the ring
   capacity, tearing a hole in the radial coverage. `build_bench.ps1` deletes the file
   first. The symptom is a "distribution defect" that is really just missing coverage.

2. **Blocking the server thread on an async chunk load.** `CountDownLatch.await` on the
   main thread deadlocks: chunk loads cannot progress while the tick loop is stalled,
   and the watchdog dumps every thread before the timeout expires.

3. **Comparing against a region that was not sampled.** The harness warms up before
   recording, so the measured points cover a sub-annulus. Binning the analytic model
   over the whole ring compares two different regions and makes a correct
   implementation look biased. Recover the index interval from the measured radii
   first — `SpiralCoordinateGenerator` has no public accessor for its rings.

4. **Treating repeated rounds as independent samples.** B2 derives its 64 candidate
   columns from a fixed stride over a deterministic sequence (`pts.get(i * 7 % size)`),
   so every run measures *the same* 64 columns. Summing the `safe` counts across runs as
   though each contributed 64 fresh columns manufactures a confidence interval that does
   not exist: four rounds agreeing on `6/64` is the deterministic sampler working as
   written, not four independent estimates. Change the stride or the starting index if a
   genuinely larger sample is needed.

## Comparing the refill metrics

B3 reproduces the pipeline-conservation fix. The one thing to get right: the refill
target and the trim target must come from the same call, `RtpPool.targetSize(online)`.
Using a constant for one of them manufactures extra regeneration and inflates the
original implementation's apparent workload.
