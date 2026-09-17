package net.sakurain.mc.easytp.gui;

import net.kyori.adventure.text.Component;
import net.sakurain.mc.easytp.EasyTPPlugin;
import net.sakurain.mc.easytp.storage.HomeData;
import net.sakurain.mc.easytp.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Builds the single-home edit GUI.
 */
public class HomeEditGui {

    public static final int GUI_SIZE = 27;

    private final EasyTPPlugin plugin;

    public HomeEditGui(@NotNull EasyTPPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Open the edit GUI for a specific home.
     *
     * @param player the player
     * @param home   the home being edited
     */
    public void open(@NotNull Player player, @NotNull HomeData home) {
        Component title = MessageUtil.parseNoPrefix("home-gui-edit-title",
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("name", home.name()));
        Inventory inventory = Bukkit.createInventory(null, GUI_SIZE, title);

        inventory.setItem(11, HomeIcon.createControl(plugin, Material.COMPASS,
                MessageUtil.parseNoPrefix("home-gui-edit-reset"),
                List.of(),
                HomeIcon.GUI_TYPE_EDIT,
                "reset_coords"));

        inventory.setItem(13, HomeIcon.createControl(plugin, Material.NAME_TAG,
                MessageUtil.parseNoPrefix("home-gui-edit-rename"),
                List.of(),
                HomeIcon.GUI_TYPE_EDIT,
                "rename"));

        inventory.setItem(15, HomeIcon.createControl(plugin, Material.OAK_DOOR,
                MessageUtil.parseNoPrefix("home-gui-edit-back"),
                List.of(),
                HomeIcon.GUI_TYPE_EDIT,
                "back"));

        // Store the home name in a hidden slot for retrieval on click
        org.bukkit.inventory.ItemStack marker = HomeIcon.createHomeIcon(plugin, home);
        inventory.setItem(GUI_SIZE - 1, marker);

        player.openInventory(inventory);
        GuiListener.setEditingHome(player.getUniqueId(), home.name());
    }
}
