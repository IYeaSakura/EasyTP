package net.sakurain.mc.easytp.command;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.sakurain.mc.easytp.EasyTPPlugin;
import net.sakurain.mc.easytp.util.MessageUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Administrative command: {@code /easytp reload}.
 */
public class EasyTPCommand implements CommandExecutor, TabCompleter {

    /** Permission declared for /easytp in plugin.yml. */
    private static final String PERMISSION = "easytp.admin.reload";

    private static final List<String> SUB_COMMANDS = List.of("reload");

    private final EasyTPPlugin plugin;

    public EasyTPCommand(@NotNull EasyTPPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length != 1 || !args[0].equalsIgnoreCase("reload")) {
            MessageUtil.send(sender, "reload-usage");
            return true;
        }

        EasyTPPlugin.ReloadResult result = plugin.reloadConfiguration();
        if (!result.applied()) {
            MessageUtil.send(sender, "reload-failed");
            return true;
        }

        MessageUtil.send(sender, "reload-success");
        for (String setting : result.restartRequired()) {
            MessageUtil.send(sender, "reload-restart-required",
                    Placeholder.unparsed("setting", setting));
        }
        return true;
    }

    /**
     * Tab completion is not permission-gated by Bukkit, so the check is repeated here. The
     * executor itself relies on the permission declared in plugin.yml, like every other command.
     */
    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            return List.of();
        }
        if (args.length == 1) {
            String partial = args[0].toLowerCase();
            return SUB_COMMANDS.stream()
                    .filter(sub -> sub.startsWith(partial))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
