package net.sakurain.mc.easytp.command;

import org.bukkit.command.Command;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

/**
 * A group of related commands sharing a single enable switch.
 *
 * <p>Command classes are the unit for {@code commands.<class>.enable}. Disabling a class
 * unregisters its commands from the server command map, so they behave as if the plugin had
 * never declared them — no executor, no tab completion, no permission error.</p>
 *
 * <p>Cooldowns and countdown delays are configured separately per individual command under
 * {@code commands.<command>.cooldown} / {@code commands.<command>.delay} and are counted
 * independently for every player.</p>
 */
public enum CommandClass {

    /** Random teleport: {@code /rtp}. */
    RTP("rtp", List.of("rtp")),

    /** Player-to-player teleports: {@code /tpa}, {@code /tphere}, {@code /tpaccept}, {@code /tpdeny}. */
    PLAYER_TELEPORT("player-teleport", List.of("tpa", "tphere", "tpaccept", "tpdeny")),

    /** Home management: {@code /home}, {@code /sethome}, {@code /homelist}, {@code /delhome}. */
    HOME("home", List.of("home", "sethome", "homelist", "delhome"));

    private final String configKey;
    private final List<String> commands;

    CommandClass(@NotNull String configKey, @NotNull List<String> commands) {
        this.configKey = configKey;
        this.commands = commands;
    }

    /**
     * The {@code commands.<key>.enable} switch that controls this class.
     */
    @NotNull
    public String configKey() {
        return configKey;
    }

    /**
     * The command names belonging to this class, in registration order.
     */
    @NotNull
    public List<String> commands() {
        return commands;
    }

    /**
     * Whether this class is enabled. Defaults to {@code true} when the key is absent.
     */
    public boolean isEnabled(@NotNull JavaPlugin plugin) {
        return plugin.getConfig().getBoolean("commands." + configKey + ".enable", true);
    }

    /**
     * Remove every command of this class from the server command map, including aliases.
     *
     * <p>Both the {@link Command#unregister} call and the known-commands sweep are performed:
     * the sweep also drops alias entries, which reference the same command object.</p>
     */
    public void unregisterAll(@NotNull JavaPlugin plugin) {
        Map<String, Command> knownCommands = plugin.getServer().getCommandMap().getKnownCommands();
        for (String name : commands) {
            PluginCommand command = plugin.getCommand(name);
            if (command == null) {
                continue;
            }
            command.unregister(plugin.getServer().getCommandMap());
            knownCommands.values().removeIf(known -> known == command);
        }
    }
}
