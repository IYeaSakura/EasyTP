package net.sakurain.mc.easytp.command;

import net.sakurain.mc.easytp.manager.TeleportManager;
import net.sakurain.mc.easytp.util.MessageUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * /tphere <player> 请求其他玩家传送到自己
 */
public class TpHereCommand implements CommandExecutor {

    private final TeleportManager teleportManager;

    public TpHereCommand(@NotNull TeleportManager teleportManager) {
        this.teleportManager = teleportManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            MessageUtil.send(sender, "player-only");
            return true;
        }
        if (args.length < 1) {
            player.sendMessage("§c用法: /tphere <玩家>");
            return true;
        }
        teleportManager.sendRequest(player, args[0], true);
        return true;
    }
}
