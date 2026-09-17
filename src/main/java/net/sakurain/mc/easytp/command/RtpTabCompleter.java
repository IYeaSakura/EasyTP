package net.sakurain.mc.easytp.command;

import net.sakurain.mc.easytp.manager.TeleportManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.stream.Collectors;

/**
 * /rtp 命令的 TAB 补全
 *
 * <p>{@code structure} is only offered while the deprecated structure mode is enabled.</p>
 */
public class RtpTabCompleter implements TabCompleter {

    private static final List<String> SUB_COMMANDS = List.of("structure");

    // 原版 /locate structure 支持的全部结构 ID（1.21）
    private static final List<String> STRUCTURES = List.of(
            "minecraft:ancient_city",
            "minecraft:bastion_remnant",
            "minecraft:buried_treasure",
            "minecraft:desert_pyramid",
            "minecraft:end_city",
            "minecraft:fortress",
            "minecraft:igloo",
            "minecraft:jungle_temple",
            "minecraft:mansion",
            "minecraft:mineshaft",
            "minecraft:monument",
            "minecraft:nether_fossil",
            "minecraft:ocean_ruin",
            "minecraft:pillager_outpost",
            "minecraft:ruined_portal",
            "minecraft:shipwreck",
            "minecraft:stronghold",
            "minecraft:swamp_hut",
            "minecraft:trail_ruins",
            "minecraft:trial_chambers",
            "minecraft:village_desert",
            "minecraft:village_plains",
            "minecraft:village_savanna",
            "minecraft:village_snowy",
            "minecraft:village_taiga"
    );

    private final TeleportManager teleportManager;

    public RtpTabCompleter(@NotNull TeleportManager teleportManager) {
        this.teleportManager = teleportManager;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player)) {
            return List.of();
        }

        if (!teleportManager.isStructureRtpEnabled()) {
            return List.of();
        }

        if (args.length == 1) {
            String partial = args[0].toLowerCase();
            return SUB_COMMANDS.stream()
                    .filter(s -> s.startsWith(partial))
                    .collect(Collectors.toList());
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("structure")) {
            String partial = args[1].toLowerCase();
            return STRUCTURES.stream()
                    .filter(s -> s.startsWith(partial))
                    .collect(Collectors.toList());
        }

        return List.of();
    }
}
