package net.sakurain.mc.easytp.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.sakurain.mc.easytp.EasyTPPlugin;
import net.sakurain.mc.easytp.storage.HomeData;
import net.sakurain.mc.easytp.util.MessageUtil;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Helper for creating home GUI icons and persistent data keys.
 */
public final class HomeIcon {

    public static final String GUI_TYPE_LIST = "home_list";
    public static final String GUI_TYPE_EDIT = "home_edit";

    private static final String KEY_HOME_NAME = "home_name";
    private static final String KEY_GUI_TYPE = "gui_type";

    private HomeIcon() {
        // utility class
    }

    /**
     * Create an icon representing a single home.
     *
     * @param plugin the plugin instance
     * @param home   the home data
     * @return the home icon item
     */
    @NotNull
    public static ItemStack createHomeIcon(@NotNull EasyTPPlugin plugin, @NotNull HomeData home) {
        Material material = resolveMaterial(home.worldName());
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        Component name = MessageUtil.parseNoPrefix("home-gui-icon-name",
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("name", home.name()));
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(MiniMessage.miniMessage().deserialize("<gray>World: <yellow>" + home.worldName()).decoration(TextDecoration.ITALIC, false));
        lore.add(MiniMessage.miniMessage().deserialize(String.format("<gray>Loc: <yellow>%.0f %.0f %.0f", home.x(), home.y(), home.z())).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(MessageUtil.parseNoPrefix("home-gui-icon-teleport").decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.parseNoPrefix("home-gui-icon-delete").decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.parseNoPrefix("home-gui-icon-edit").decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(getKey(plugin, KEY_HOME_NAME), PersistentDataType.STRING, home.name());
        pdc.set(getKey(plugin, KEY_GUI_TYPE), PersistentDataType.STRING, GUI_TYPE_LIST);

        item.setItemMeta(meta);
        return item;
    }

    /**
     * Create a simple control button for the GUI.
     *
     * @param plugin   the plugin instance
     * @param material the item material
     * @param name     the display name component
     * @param lore     optional lore lines
     * @param guiType  the GUI type marker
     * @param actionId the action identifier
     * @return the control item
     */
    @NotNull
    public static ItemStack createControl(@NotNull EasyTPPlugin plugin, @NotNull Material material,
                                          @NotNull Component name, @NotNull List<Component> lore,
                                          @NotNull String guiType, @NotNull String actionId) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        if (!lore.isEmpty()) {
            meta.lore(lore.stream().map(c -> c.decoration(TextDecoration.ITALIC, false)).toList());
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(getKey(plugin, KEY_GUI_TYPE), PersistentDataType.STRING, guiType);
        pdc.set(getKey(plugin, actionId), PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Read the home name stored in an item's persistent data.
     *
     * @param plugin the plugin instance
     * @param item   the item
     * @return the home name, or null if not present
     */
    @org.jetbrains.annotations.Nullable
    public static String readHomeName(@NotNull EasyTPPlugin plugin, @NotNull ItemStack item) {
        if (!item.hasItemMeta()) {
            return null;
        }
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        return pdc.get(getKey(plugin, KEY_HOME_NAME), PersistentDataType.STRING);
    }

    /**
     * Read the GUI type marker from an item.
     *
     * @param plugin the plugin instance
     * @param item   the item
     * @return the GUI type, or null if not present
     */
    @org.jetbrains.annotations.Nullable
    public static String readGuiType(@NotNull EasyTPPlugin plugin, @NotNull ItemStack item) {
        if (!item.hasItemMeta()) {
            return null;
        }
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        return pdc.get(getKey(plugin, KEY_GUI_TYPE), PersistentDataType.STRING);
    }

    /**
     * Check whether an item has a specific boolean action key.
     *
     * @param plugin   the plugin instance
     * @param item     the item
     * @param actionId the action key name
     * @return true if the action key is present
     */
    public static boolean hasAction(@NotNull EasyTPPlugin plugin, @NotNull ItemStack item, @NotNull String actionId) {
        if (!item.hasItemMeta()) {
            return false;
        }
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        return pdc.has(getKey(plugin, actionId), PersistentDataType.BYTE);
    }

    @NotNull
    private static NamespacedKey getKey(@NotNull EasyTPPlugin plugin, @NotNull String key) {
        return new NamespacedKey(plugin, key);
    }

    @NotNull
    private static Material resolveMaterial(@NotNull String worldName) {
        World world = org.bukkit.Bukkit.getWorld(worldName);
        if (world == null) {
            return Material.PLAYER_HEAD;
        }
        return switch (world.getEnvironment()) {
            case NORMAL -> Material.GRASS_BLOCK;
            case NETHER -> Material.NETHERRACK;
            case THE_END -> Material.END_STONE;
            default -> Material.PLAYER_HEAD;
        };
    }
}
