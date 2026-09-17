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
 * Builds the double-chest home list GUI.
 */
public class HomeListGui {

    public static final int GUI_SIZE = 54;
    public static final int HOMES_PER_PAGE = 45;

    private final EasyTPPlugin plugin;

    public HomeListGui(@NotNull EasyTPPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Open the home list GUI for a player at the specified page.
     *
     * @param player the player
     * @param homes  all homes of the player
     * @param page   zero-based page index
     */
    public void open(@NotNull Player player, @NotNull List<HomeData> homes, int page) {
        Component title = MessageUtil.parseNoPrefix("home-gui-title");
        Inventory inventory = Bukkit.createInventory(null, GUI_SIZE, title);

        int totalPages = Math.max(1, (homes.size() + HOMES_PER_PAGE - 1) / HOMES_PER_PAGE);
        int safePage = Math.min(Math.max(0, page), Math.max(0, totalPages - 1));

        int start = safePage * HOMES_PER_PAGE;
        int end = Math.min(homes.size(), start + HOMES_PER_PAGE);
        for (int i = start; i < end; i++) {
            inventory.setItem(i - start, HomeIcon.createHomeIcon(plugin, homes.get(i)));
        }

        // Bottom row controls
        inventory.setItem(GUI_SIZE - 9, createNavButton(Material.ARROW, "home-gui-prev-page", "prev_page", safePage > 0));
        inventory.setItem(GUI_SIZE - 5, createNavButton(Material.BARRIER, "home-gui-close", "close", true));
        inventory.setItem(GUI_SIZE - 1, createNavButton(Material.ARROW, "home-gui-next-page", "next_page", safePage < totalPages - 1));

        player.openInventory(inventory);
        GuiListener.setPage(player.getUniqueId(), safePage);
    }

    @NotNull
    private Component getControlName(@NotNull String key) {
        String raw = MessageUtil.getRaw(key);
        if (raw.isEmpty()) {
            return Component.text(key);
        }
        return MessageUtil.parseNoPrefix(key);
    }

    @NotNull
    private org.bukkit.inventory.ItemStack createNavButton(@NotNull Material material, @NotNull String nameKey, @NotNull String actionId, boolean enabled) {
        Component name;
        if (enabled) {
            name = getControlName(nameKey);
        } else {
            String raw = MessageUtil.getRaw(nameKey);
            name = MessageUtil.parseRaw(raw.isEmpty() ? nameKey : "<gray>" + raw);
        }
        return HomeIcon.createControl(plugin,
                enabled ? material : Material.GRAY_STAINED_GLASS_PANE,
                name,
                List.of(),
                HomeIcon.GUI_TYPE_LIST,
                actionId);
    }
}
