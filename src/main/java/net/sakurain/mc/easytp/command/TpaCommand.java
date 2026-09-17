package net.sakurain.mc.easytp.command;

import net.sakurain.mc.easytp.manager.TeleportManager;
import net.sakurain.mc.easytp.util.MessageUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * /tpa <player> 请求传送到其他玩家
 */
public class TpaCommand implements CommandExecutor {

    private final TeleportManager teleportManager;

    public TpaCommand(@NotNull TeleportManager teleportManager) {
        this.teleportManager = teleportManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            MessageUtil.send(sender, "player-only");
            return true;
        }
        if (args.length < 1) {
            player.sendMessage("§c用法: /tpa <玩家>");
            return true;
        }
        teleportManager.sendRequest(player, args[0], false);
        return true;
    }
}
