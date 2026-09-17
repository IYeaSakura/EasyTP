package net.sakurain.mc.easytp.rtp.scheduler;

import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * Thread scheduler abstraction used by the RTP engine.
 *
 * <p>A Paper implementation routes work through BukkitScheduler. A Folia
 * implementation could be added later by dispatching to region schedulers.</p>
 */
public interface RtpScheduler {

    /**
     * Run a task asynchronously as soon as possible.
     */
    void runAsync(@NotNull Runnable task);

    /**
     * Run a task on the main server thread as soon as possible.
     */
    void runSync(@NotNull Runnable task);

    /**
     * Run a task asynchronously after the given delay in ticks.
     */
    void runLaterAsync(@NotNull Runnable task, long delayTicks);

    /**
     * Run a task on the main thread after the given delay in ticks.
     */
    void runLaterSync(@NotNull Runnable task, long delayTicks);

    /**
     * Run a task asynchronously on a repeating timer.
     */
    void runTimerAsync(@NotNull Runnable task, long delayTicks, long periodTicks);

    /**
     * Run a task on the main thread on a repeating timer.
     */
    void runTimerSync(@NotNull Runnable task, long delayTicks, long periodTicks);

    /**
     * Cancel all tasks owned by this scheduler.
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
