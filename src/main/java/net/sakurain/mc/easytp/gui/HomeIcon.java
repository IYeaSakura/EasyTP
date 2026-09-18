package net.sakurain.mc.easytp.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.sakurain.mc.easytp.EasyTPPlugin;
import net.sakurain.mc.easytp.storage.HomeData;
import net.sakurain.mc.easytp.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
        World world = Bukkit.getWorld(home.worldName());
        // The icon material is the dimension cue: grass block, netherrack or end stone.
        ItemStack item = new ItemStack(resolveMaterial(world));
        ItemMeta meta = item.getItemMeta();

        Component name = MessageUtil.parseNoPrefix("home-gui-icon-name",
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("name", home.name()));
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(MessageUtil.parseNoPrefix("home-gui-icon-world",
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("world", home.worldName())));
        lore.add(MessageUtil.parseNoPrefix("home-gui-icon-location",
                MessageUtil.coord("x", home.x()),
                MessageUtil.coord("y", home.y()),
                MessageUtil.coord("z", home.z())));
        // The stored home only knows its world name, so the dimension is only resolvable while
        // that world is loaded.
        if (world == null) {
            lore.add(MessageUtil.parseNoPrefix("home-gui-icon-dimension-unknown"));
        } else {
            lore.add(MessageUtil.parseNoPrefix("home-gui-icon-dimension",
                    net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.component(
                            "dimension", MessageUtil.dimensionName(world.getEnvironment()))));
        }
        lore.add(Component.empty());
        lore.add(MessageUtil.parseNoPrefix("home-gui-icon-teleport").decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.parseNoPrefix("home-gui-icon-delete").decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.parseNoPrefix("home-gui-icon-edit").decoration(TextDecoration.ITALIC, false));
        meta.lore(lore.stream().map(line -> line.decoration(TextDecoration.ITALIC, false)).toList());

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

    /**
     * Pick the icon material that stands for a home's dimension: grass block for the Overworld,
     * netherrack for the Nether, end stone for The End.
     *
     * @param world the home's world, or null when that world is not loaded
     */
    @NotNull
    private static Material resolveMaterial(@Nullable World world) {
        if (world == null) {
            // A stored home only knows its world name, so the dimension cannot be resolved while
            // that world is unloaded. Fall back to a neutral icon rather than guessing.
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
