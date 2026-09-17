package net.sakurain.mc.easytp.rtp.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Paper implementation of {@link RtpScheduler} backed by BukkitScheduler.
 */
class PaperRtpScheduler implements RtpScheduler {

    private final JavaPlugin plugin;
    private final List<BukkitTask> tasks = new ArrayList<>();

    PaperRtpScheduler(@NotNull JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void runAsync(@NotNull Runnable task) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    }

    @Override
    public void runSync(@NotNull Runnable task) {
        Bukkit.getScheduler().runTask(plugin, task);
    }

    @Override
    public void runLaterAsync(@NotNull Runnable task, long delayTicks) {
        if (delayTicks <= 0) {
            runAsync(task);
            return;
        }
        BukkitTask bukkitTask = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, delayTicks);
        tasks.add(bukkitTask);
    }

    @Override
    public void runLaterSync(@NotNull Runnable task, long delayTicks) {
        if (delayTicks <= 0) {
            runSync(task);
            return;
        }
        BukkitTask bukkitTask = Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
        tasks.add(bukkitTask);
    }

    @Override
    public void runTimerAsync(@NotNull Runnable task, long delayTicks, long periodTicks) {
        BukkitTask bukkitTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, delayTicks, periodTicks);
        tasks.add(bukkitTask);
    }

    @Override
    public void runTimerSync(@NotNull Runnable task, long delayTicks, long periodTicks) {
        BukkitTask bukkitTask = Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks);
        tasks.add(bukkitTask);
    }

    @Override
    public void cancelAll() {
        for (BukkitTask task : tasks) {
            if (task != null) {
                task.cancel();
            }
        }
        tasks.clear();
    }
}
