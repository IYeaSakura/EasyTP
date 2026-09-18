package net.sakurain.mc.easytp.command;

import net.sakurain.mc.easytp.gui.GuiListener;
import net.sakurain.mc.easytp.manager.TeleportManager;
import net.sakurain.mc.easytp.util.MessageUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * /homelist opens the home management GUI.
 *
 * <p>Opening the GUI is deliberately <em>not</em> dimension-gated: it only shows information, so it
 * works in every dimension. The actions taken from inside it are gated individually — teleporting
 * follows {@code dimensions.home} and {@code teleport.allow-cross-dimension}, relocating a home
 * follows {@code dimensions.sethome}, and deleting follows {@code dimensions.delhome}. Only
 * switching off the whole home command class disables /homelist itself.</p>
 */
public class HomeListCommand implements CommandExecutor {

    private final TeleportManager teleportManager;
    private final GuiListener guiListener;

    public HomeListCommand(@NotNull TeleportManager teleportManager, @NotNull GuiListener guiListener) {
        this.teleportManager = teleportManager;
        this.guiListener = guiListener;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            MessageUtil.send(sender, "player-only");
            return true;
        }
        if (!teleportManager.checkCooldown(player, "homelist")) {
            return true;
        }
        guiListener.openHomeList(player);
        teleportManager.setCooldown(player, "homelist");
        return true;
    }
}
