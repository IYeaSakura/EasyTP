package net.sakurain.mc.easytp.command;

import net.sakurain.mc.easytp.manager.TeleportManager;
import net.sakurain.mc.easytp.util.MessageUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * /rtp random teleport command.
 */
public class RtpCommand implements CommandExecutor {

    private final TeleportManager teleportManager;

    public RtpCommand(@NotNull TeleportManager teleportManager) {
        this.teleportManager = teleportManager;
    }

    /**
     * The {@code structure} branch routes into a deprecated, disabled-by-default code path,
     * so the deprecation warnings are suppressed here deliberately. javac reports
     * {@code forRemoval} deprecations under the separate {@code removal} lint key.
     */
    @Override
    @SuppressWarnings({"deprecation", "removal"})
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            MessageUtil.send(sender, "player-only");
            return true;
        }

        if (args.length >= 2 && args[0].equalsIgnoreCase("structure")) {
            teleportManager.randomTeleportNearStructure(player, args[1]);
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("structure")) {
            return false;
        }

        teleportManager.randomTeleport(player);
        return true;
    }
}
