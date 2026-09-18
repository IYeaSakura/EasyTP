package net.sakurain.mc.easytp.rtp.scheduler;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Implementation of {@link RtpScheduler} on the schedulers Paper and Folia share.
 *
 * <p>The legacy {@code BukkitScheduler} is deliberately not used anywhere: it throws
 * {@code UnsupportedOperationException} on Folia, because there is no single main thread to run
 * work on. {@code GlobalRegionScheduler} and {@code AsyncScheduler} exist on both platforms — on
 * Paper they land on the main thread and the async pool respectively, on Folia on the global
 * region's tick thread and the async pool.</p>
 */
class PaperRtpScheduler implements RtpScheduler {

    /** A server tick is always 50 ms; the async scheduler counts in real time, not ticks. */
    private static final long MILLIS_PER_TICK = 50L;

    private final JavaPlugin plugin;
    private final List<ScheduledTask> tasks = Collections.synchronizedList(new ArrayList<>());

    PaperRtpScheduler(@NotNull JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void runAsync(@NotNull Runnable task) {
        Bukkit.getAsyncScheduler().runNow(plugin, scheduled -> task.run());
    }

    @Override
    public void runGlobal(@NotNull Runnable task) {
        Bukkit.getGlobalRegionScheduler().run(plugin, scheduled -> task.run());
    }

    @Override
    public void runLaterAsync(@NotNull Runnable task, long delayTicks) {
        if (delayTicks <= 0) {
            runAsync(task);
            return;
        }
        track(Bukkit.getAsyncScheduler().runDelayed(plugin, scheduled -> task.run(),
                delayTicks * MILLIS_PER_TICK, TimeUnit.MILLISECONDS));
    }

    @Override
    public void runLaterGlobal(@NotNull Runnable task, long delayTicks) {
        if (delayTicks <= 0) {
            runGlobal(task);
            return;
        }
        track(Bukkit.getGlobalRegionScheduler().runDelayed(plugin, scheduled -> task.run(), delayTicks));
    }

    @Override
    public void runTimerAsync(@NotNull Runnable task, long initialDelayTicks, long periodTicks) {
        track(Bukkit.getAsyncScheduler().runAtFixedRate(plugin, scheduled -> task.run(),
                Math.max(1L, initialDelayTicks) * MILLIS_PER_TICK,
                Math.max(1L, periodTicks) * MILLIS_PER_TICK,
                TimeUnit.MILLISECONDS));
    }

    @Override
    public void runTimerGlobal(@NotNull Runnable task, long initialDelayTicks, long periodTicks) {
        track(Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, scheduled -> task.run(),
                Math.max(1L, initialDelayTicks), Math.max(1L, periodTicks)));
    }

    @Override
    public void cancelAll() {
        synchronized (tasks) {
            for (ScheduledTask task : tasks) {
                if (task != null) {
                    task.cancel();
                }
            }
            tasks.clear();
        }
    }

    /**
     * Remember a delayed or repeating task so {@link #cancelAll()} can stop it, dropping entries
     * that have already finished so the list cannot grow without bound.
     */
    private void track(@NotNull ScheduledTask task) {
        tasks.removeIf(ScheduledTask::isCancelled);
        tasks.add(task);
    }
}
