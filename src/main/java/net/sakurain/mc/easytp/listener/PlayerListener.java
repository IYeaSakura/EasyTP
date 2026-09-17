package net.sakurain.mc.easytp.listener;

import net.sakurain.mc.easytp.manager.TeleportManager;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

/**
 * 玩家事件监听：移动或受到伤害取消延迟传送，退出清理
 */
public class PlayerListener implements Listener {

    private final TeleportManager teleportManager;

    public PlayerListener(@NotNull TeleportManager teleportManager) {
        this.teleportManager = teleportManager;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerMove(@NotNull PlayerMoveEvent event) {
        if (!teleportManager.hasPendingTeleport(event.getPlayer())) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        if (from.getBlockX() != to.getBlockX() || from.getBlockY() != to.getBlockY() || from.getBlockZ() != to.getBlockZ()) {
            teleportManager.cancelPendingTeleport(event.getPlayer(), true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityDamage(@NotNull EntityDamageEvent event) {
        if (!(event.getEntity() instanceof org.bukkit.entity.Player player)) {
            return;
        }
        if (teleportManager.hasPendingTeleport(player)) {
            teleportManager.cancelPendingTeleportOnDamage(player);
        }
    }

    @EventHandler
    public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
        teleportManager.cancelPendingTeleport(event.getPlayer(), false);
    }
}
