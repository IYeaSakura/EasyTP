package net.sakurain.mc.easytp.command;

import net.sakurain.mc.easytp.manager.TeleportManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * /home 和 /delhome 的 Tab 补全：补玩家拥有的家名
 */
public class HomeTabCompleter implements TabCompleter {

    private final TeleportManager teleportManager;

    public HomeTabCompleter(@NotNull TeleportManager teleportManager) {
        this.teleportManager = teleportManager;
    }

    @Override
    @Nullable
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (!(sender instanceof Player player) || args.length != 1) {
            return Collections.emptyList();
        }
        String prefix = args[0].toLowerCase();
        List<String> result = new ArrayList<>();
        for (String name : teleportManager.getHomeNames(player)) {
            if (name.toLowerCase().startsWith(prefix)) {
                result.add(name);
            }
        }
        return result;
    }
}
