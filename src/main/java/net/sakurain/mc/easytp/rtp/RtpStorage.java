package net.sakurain.mc.easytp.rtp;

import net.sakurain.mc.easytp.rtp.memory.RegionState;
import net.sakurain.mc.easytp.storage.DatabaseManager;
import net.sakurain.mc.easytp.util.DebugLog;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * SQLite persistence for RTP spatial memory and spiral generator indices.
 *
 * <p>Both datasets are written through an in-memory write-behind buffer drained by a
 * single background thread:</p>
 * <ul>
 *   <li>{@link #incrementAndGetSpiralIndex} only bumps an in-memory counter and marks the
 *       slot dirty. The persisted index is best-effort anyway, because the generator
 *       reduces it modulo the ring's point count.</li>
 *   <li>{@link #queueRegionState} coalesces repeated updates to the same grid cell, so a
 *       cell written many times between two flushes costs exactly one statement.</li>
 * </ul>
 *
 * <p>Consequently no SQLite write ever runs on the server main thread, and reads only
 * touch the database on a spatial-memory cache miss.</p>
 */
public class RtpStorage {

    /** Interval between background flushes. */
    private static final long FLUSH_INTERVAL_SECONDS = 5L;

    /** Upper bound on how long {@link #close()} waits for the final flush. */
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5L;

    private static final String WRITER_THREAD_NAME = "EasyTP-RTP-Storage";

    private final DatabaseManager databaseManager;
    private final ConcurrentHashMap<SpiralKey, SpiralSlot> spiralSlots = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CellKey, CellWrite> pendingCells = new ConcurrentHashMap<>();

    // Diagnostics, drained by the RTP engine's periodic summary.
    private final LongAdder flushedCells = new LongAdder();
    private final LongAdder flushedSpiral = new LongAdder();
    private final LongAdder flushFailures = new LongAdder();

    private volatile ScheduledExecutorService writer;

    public RtpStorage(@NotNull DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    /**
     * Create RTP tables if they do not already exist, then start the background flusher.
     */
    public void initialize() {
        try {
            databaseManager.runWithConnection(connection -> {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("""
                            CREATE TABLE IF NOT EXISTS easytp_rtp_spiral (
                                world TEXT NOT NULL,
                                ring_name TEXT NOT NULL,
                                index_value INTEGER NOT NULL DEFAULT 0,
                                updated_at INTEGER NOT NULL,
                                PRIMARY KEY (world, ring_name)
                            )
                            """);
                    statement.execute("""
                            CREATE TABLE IF NOT EXISTS easytp_rtp_spatial_memory (
                                world TEXT NOT NULL,
                                cell_x INTEGER NOT NULL,
                                cell_z INTEGER NOT NULL,
                                state TEXT NOT NULL,
                                updated_at INTEGER NOT NULL,
                                PRIMARY KEY (world, cell_x, cell_z)
                            )
                            """);
                    statement.execute("CREATE INDEX IF NOT EXISTS idx_rtp_memory_world ON easytp_rtp_spatial_memory(world)");
                    // One-off cleanup for rows written by older versions, which persisted
                    // out-of-border cells as permanently unsafe. The border can move, so those
                    // verdicts are not trustworthy and are dropped on every startup (a no-op
                    // once they are gone).
                    statement.execute("DELETE FROM easytp_rtp_spatial_memory WHERE state = 'unsafe_border'");
                }
            });
        } catch (SQLException e) {
            throw new RuntimeException("Could not create RTP tables", e);
        }
        startWriter();
    }

    /**
     * Flush any buffered writes and stop the background thread.
     *
     * <p>The final flush runs on the writer thread so plugin shutdown stays off the disk
     * path; if the executor is already gone the flush falls back to the calling thread.</p>
     */
    public synchronized void close() {
        ScheduledExecutorService current = writer;
        writer = null;
        if (current == null) {
            flushSafely();
            return;
        }

        try {
            current.submit(this::flushSafely);
        } catch (RejectedExecutionException e) {
            flushSafely();
        }
        current.shutdown();

        try {
            if (!current.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                current.shutdownNow();
            }
        } catch (InterruptedException e) {
            current.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Write every buffered change to disk. Called periodically and on {@link #close()}.
     */
    public void flush() {
        flushSpiralIndices();
        flushRegionStates();
    }

    // region Spiral indices

    /**
     * Atomically increment the spiral index for the given world and ring.
     *
     * <p>The new value is buffered in memory; persisting it is deferred to the next flush.</p>
     */
    public long incrementAndGetSpiralIndex(@NotNull String world, @NotNull String ring) {
        SpiralKey key = new SpiralKey(world, ring);
        SpiralSlot slot = spiralSlots.get(key);
        if (slot == null) {
            // Load outside the map's bin lock so the other threads are not blocked on disk I/O.
            long persisted = loadSpiralIndex(world, ring);
            slot = spiralSlots.computeIfAbsent(key, k -> new SpiralSlot(new AtomicLong(persisted), new AtomicBoolean(false)));
        }
        long value = slot.index().incrementAndGet();
        slot.dirty().set(true);
        return value;
    }

    private void flushSpiralIndices() {
        for (Map.Entry<SpiralKey, SpiralSlot> entry : spiralSlots.entrySet()) {
            SpiralSlot slot = entry.getValue();
            if (!slot.dirty().compareAndSet(true, false)) {
                continue;
            }
            try {
                saveSpiralIndex(entry.getKey(), slot.index().get());
                flushedSpiral.increment();
            } catch (RuntimeException e) {
                // Keep the slot dirty so the next flush retries it.
                slot.dirty().set(true);
                flushFailures.increment();
                logWarning("Could not flush spiral index " + entry.getKey(), e);
            }
        }
    }

    private long loadSpiralIndex(@NotNull String world, @NotNull String ring) {
        String sql = "SELECT index_value FROM easytp_rtp_spiral WHERE world = ? AND ring_name = ?";
        try {
            Long value = databaseManager.withConnection(connection -> {
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, world);
                    ps.setString(2, ring);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            return rs.getLong("index_value");
                        }
                    }
                }
                return null;
            });
            return value == null ? 0L : value;
        } catch (SQLException e) {
            throw new RuntimeException("Could not load spiral index", e);
        }
    }

    private void saveSpiralIndex(@NotNull SpiralKey key, long value) {
        String sql = """
                INSERT INTO easytp_rtp_spiral (world, ring_name, index_value, updated_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(world, ring_name) DO UPDATE SET
                    index_value = excluded.index_value,
                    updated_at = excluded.updated_at
                """;
        try {
            databaseManager.runWithConnection(connection -> {
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, key.world());
                    ps.setString(2, key.ring());
                    ps.setLong(3, value);
                    ps.setLong(4, System.currentTimeMillis());
                    ps.executeUpdate();
                }
            });
        } catch (SQLException e) {
            throw new RuntimeException("Could not save spiral index", e);
        }
    }

    // endregion

    // region Spatial memory

    /**
     * Buffer a region state write. Passing {@code null} deletes the record.
     *
     * <p>Repeated calls for the same cell replace the pending value, so only the newest
     * state reaches the database.</p>
     */
    public void queueRegionState(@NotNull String world, int cellX, int cellZ, @Nullable RegionState state) {
        pendingCells.put(new CellKey(world, cellX, cellZ), new CellWrite(world, cellX, cellZ, state));
    }

    /**
     * Return a region state that is buffered but not yet written to disk.
     *
     * <p>This lets the spatial memory cache evict entries safely: a cell whose write is
     * still pending can be re-read from this buffer instead of the stale database row.</p>
     *
     * @return the buffered state, or null if nothing is buffered for that cell
     */
    @Nullable
    public RegionState peekPendingRegionState(@NotNull String world, int cellX, int cellZ) {
        CellWrite write = pendingCells.get(new CellKey(world, cellX, cellZ));
        return write == null ? null : write.state();
    }

    /**
     * Load the persisted region state for a spatial memory cell.
     *
     * @return the stored state, or {@link RegionState#UNKNOWN} if no record exists
     */
    @NotNull
    public RegionState loadRegionState(@NotNull String world, int cellX, int cellZ) {
        String sql = "SELECT state FROM easytp_rtp_spatial_memory WHERE world = ? AND cell_x = ? AND cell_z = ?";
        try {
            RegionState state = databaseManager.withConnection(connection -> {
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, world);
                    ps.setInt(2, cellX);
                    ps.setInt(3, cellZ);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            return RegionState.fromKey(rs.getString("state"));
                        }
                    }
                }
                return null;
            });
            return state == null ? RegionState.UNKNOWN : state;
        } catch (SQLException e) {
            throw new RuntimeException("Could not load region state", e);
        }
    }

    private void flushRegionStates() {
        if (pendingCells.isEmpty()) {
            return;
        }

        List<Map.Entry<CellKey, CellWrite>> batch = new ArrayList<>();
        for (Map.Entry<CellKey, CellWrite> entry : pendingCells.entrySet()) {
            // Two-arg remove only succeeds while the pending value is unchanged, so a newer
            // state queued during this iteration stays buffered for the next flush.
            if (pendingCells.remove(entry.getKey(), entry.getValue())) {
                batch.add(entry);
            }
        }
        if (batch.isEmpty()) {
            return;
        }

        try {
            databaseManager.runWithConnection(connection -> {
                boolean autoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try {
                    for (Map.Entry<CellKey, CellWrite> entry : batch) {
                        writeRegionState(connection, entry.getValue());
                    }
                    connection.commit();
                } catch (SQLException e) {
                    connection.rollback();
                    throw e;
                } finally {
                    connection.setAutoCommit(autoCommit);
                }
            });
            flushedCells.add(batch.size());
            DebugLog.log("storage", "flushed %d spatial memory cell(s) in one transaction (now pending %d)",
                    batch.size(), pendingCells.size());
        } catch (SQLException | RuntimeException e) {
            // Put the batch back without clobbering newer states queued in the meantime.
            for (Map.Entry<CellKey, CellWrite> entry : batch) {
                pendingCells.putIfAbsent(entry.getKey(), entry.getValue());
            }
            flushFailures.increment();
            logWarning("Could not flush " + batch.size() + " RTP spatial memory cell(s)", e);
        }
    }

    /**
     * Cells buffered but not yet written. A number that keeps growing means the flush is failing
     * or the database cannot keep up.
     */
    public int pendingCellCount() {
        return pendingCells.size();
    }

    /**
     * Read and reset the flushed-cell counter.
     */
    public long drainFlushedCells() {
        return flushedCells.sumThenReset();
    }

    /**
     * Read and reset the flushed spiral-index counter.
     */
    public long drainFlushedSpiral() {
        return flushedSpiral.sumThenReset();
    }

    /**
     * Read and reset the storage failure counter.
     */
    public long drainFlushFailures() {
        return flushFailures.sumThenReset();
    }

    private void writeRegionState(@NotNull Connection connection, @NotNull CellWrite write) throws SQLException {
        if (write.state() == null) {
            String sql = "DELETE FROM easytp_rtp_spatial_memory WHERE world = ? AND cell_x = ? AND cell_z = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, write.world());
                ps.setInt(2, write.cellX());
                ps.setInt(3, write.cellZ());
                ps.executeUpdate();
            }
            return;
        }

        String sql = """
                INSERT INTO easytp_rtp_spatial_memory (world, cell_x, cell_z, state, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(world, cell_x, cell_z) DO UPDATE SET
                    state = excluded.state,
                    updated_at = excluded.updated_at
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, write.world());
            ps.setInt(2, write.cellX());
            ps.setInt(3, write.cellZ());
            ps.setString(4, write.state().getKey());
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    // endregion

    private synchronized void startWriter() {
        if (writer != null) {
            return;
        }
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, WRITER_THREAD_NAME);
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::flushSafely, FLUSH_INTERVAL_SECONDS, FLUSH_INTERVAL_SECONDS, TimeUnit.SECONDS);
        this.writer = executor;
    }

    private void flushSafely() {
        try {
            flush();
        } catch (RuntimeException e) {
            logWarning("Unexpected RTP storage flush failure", e);
        }
    }

    private void logWarning(@NotNull String message, @NotNull Exception e) {
        databaseManager.getLogger().warning(message + ": " + e.getMessage());
    }

    private record SpiralKey(@NotNull String world, @NotNull String ring) {
    }

    private record SpiralSlot(@NotNull AtomicLong index, @NotNull AtomicBoolean dirty) {
    }

    private record CellKey(@NotNull String world, int cellX, int cellZ) {
    }

    private record CellWrite(@NotNull String world, int cellX, int cellZ, @Nullable RegionState state) {
    }
}
