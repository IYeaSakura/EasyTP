package net.sakurain.mc.easytp.rtp.scheduler;

import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * Threading abstraction used by the RTP engine.
 *
 * <p>The implementation is built on the four schedulers that Paper and Folia share, so the same
 * code runs on both. "Global" here means <em>the global region tick thread</em>: on Paper that is
 * the ordinary main thread, and on Folia it is the region that owns global state. It is the right
 * place for work that touches neither a specific location nor a specific entity.</p>
 *
 * <p>Work that touches a player, a block or a chunk must <strong>not</strong> go through this
 * interface — schedule it against the owning player or region instead, because on Folia only the
 * thread that owns that region may touch it.</p>
 */
public interface RtpScheduler {

    /**
     * Run a task on the async pool as soon as possible. Safe from any thread.
     */
    void runAsync(@NotNull Runnable task);

    /**
     * Run a task on the global region tick thread as soon as possible.
     */
    void runGlobal(@NotNull Runnable task);

    /**
     * Run a task on the async pool after the given delay in ticks.
     */
    void runLaterAsync(@NotNull Runnable task, long delayTicks);

    /**
     * Run a task on the global region tick thread after the given delay in ticks.
     */
    void runLaterGlobal(@NotNull Runnable task, long delayTicks);

    /**
     * Repeat a task on the async pool.
     */
    void runTimerAsync(@NotNull Runnable task, long initialDelayTicks, long periodTicks);

    /**
     * Repeat a task on the global region tick thread.
     */
    void runTimerGlobal(@NotNull Runnable task, long initialDelayTicks, long periodTicks);

    /**
     * Cancel every delayed and repeating task this scheduler created.
     *
     * <p>One-shot {@link #runAsync(Runnable)} and {@link #runGlobal(Runnable)} calls are not
     * tracked: they finish immediately and keeping a reference to them would leak.</p>
     */
    void cancelAll();

    /**
     * Factory used by the plugin to create the scheduler implementation.
     */
    @NotNull
    static RtpScheduler create(@NotNull JavaPlugin plugin) {
        return new PaperRtpScheduler(plugin);
    }
}
