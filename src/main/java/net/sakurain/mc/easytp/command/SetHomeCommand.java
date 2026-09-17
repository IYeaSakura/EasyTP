package net.sakurain.mc.easytp.command;

import net.sakurain.mc.easytp.manager.TeleportManager;
import net.sakurain.mc.easytp.util.MessageUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * /sethome [name] 设置家，不带参数时默认名为 home
 */
public class SetHomeCommand implements CommandExecutor {

    private final TeleportManager teleportManager;

    public SetHomeCommand(@NotNull TeleportManager teleportManager) {
        this.teleportManager = teleportManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            MessageUtil.send(sender, "player-only");
            return true;
        }
        String name = args.length == 0 ? "home" : args[0];
        teleportManager.setHome(player, name);
        return true;
    }
}
