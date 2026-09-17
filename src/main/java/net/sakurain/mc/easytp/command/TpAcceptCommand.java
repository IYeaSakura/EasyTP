package net.sakurain.mc.easytp.command;

import net.sakurain.mc.easytp.manager.TeleportManager;
import net.sakurain.mc.easytp.util.MessageUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * /tpaccept 同意传送请求
 */
public class TpAcceptCommand implements CommandExecutor {

    private final TeleportManager teleportManager;

    public TpAcceptCommand(@NotNull TeleportManager teleportManager) {
        this.teleportManager = teleportManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            MessageUtil.send(sender, "player-only");
            return true;
        }
        teleportManager.acceptRequest(player);
        return true;
    }
}
