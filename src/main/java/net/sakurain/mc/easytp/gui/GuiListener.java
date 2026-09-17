package net.sakurain.mc.easytp.gui;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.sakurain.mc.easytp.EasyTPPlugin;
import net.sakurain.mc.easytp.manager.TeleportManager;
import net.sakurain.mc.easytp.storage.HomeData;
import net.sakurain.mc.easytp.util.MessageUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles all home GUI interactions and chat-based rename input.
 */
public class GuiListener implements Listener {

    private final EasyTPPlugin plugin;
    private final TeleportManager teleportManager;
    private final HomeListGui homeListGui;
    private final HomeEditGui homeEditGui;

    // pendingRename is touched from the async chat thread as well as the main thread,
    // so all three maps are concurrent.
    private static final Map<UUID, Integer> playerPage = new ConcurrentHashMap<>();
    private static final Map<UUID, String> editingHome = new ConcurrentHashMap<>();
    private static final Map<UUID, String> pendingRename = new ConcurrentHashMap<>();

    public GuiListener(@NotNull EasyTPPlugin plugin, @NotNull TeleportManager teleportManager) {
        this.plugin = plugin;
        this.teleportManager = teleportManager;
        this.homeListGui = new HomeListGui(plugin);
        this.homeEditGui = new HomeEditGui(plugin);
    }

    /**
     * Open the home list GUI for a player on their current or first page.
     *
     * @param player the player
     */
    public void openHomeList(@NotNull Player player) {
        try {
            List<HomeData> homes = teleportManager.getHomeRepository().findByPlayer(player.getUniqueId());
            if (homes.isEmpty()) {
                MessageUtil.send(player, "home-gui-empty");
                return;
            }
            int page = playerPage.getOrDefault(player.getUniqueId(), 0);
            homeListGui.open(player, homes, page);
        } catch (Exception e) {
            plugin.getLogger().severe("Could not open home list GUI: " + e.getMessage());
            MessageUtil.send(player, "database-error");
        }
    }

    /**
     * Refresh the home list GUI for a player, keeping the current page if possible.
     *
     * @param player the player
     */
    public void refreshHomeList(@NotNull Player player) {
        try {
            List<HomeData> homes = teleportManager.getHomeRepository().findByPlayer(player.getUniqueId());
            int page = playerPage.getOrDefault(player.getUniqueId(), 0);
            if (page * HomeListGui.HOMES_PER_PAGE >= homes.size() && page > 0) {
                page--;
            }
            playerPage.put(player.getUniqueId(), page);
            homeListGui.open(player, homes, page);
        } catch (Exception e) {
            plugin.getLogger().severe("Could not refresh home list GUI: " + e.getMessage());
            MessageUtil.send(player, "database-error");
        }
    }

    static void setPage(@NotNull UUID uuid, int page) {
        playerPage.put(uuid, page);
    }

    static void setEditingHome(@NotNull UUID uuid, @NotNull String homeName) {
        editingHome.put(uuid, homeName);
    }

    @EventHandler
    public void onInventoryClick(@NotNull InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        Inventory inventory = event.getClickedInventory();
        if (inventory == null) {
            return;
        }

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir()) {
            return;
        }

        String guiType = HomeIcon.readGuiType(plugin, clicked);
        if (guiType == null) {
            return;
        }

        event.setCancelled(true);

        if (guiType.equals(HomeIcon.GUI_TYPE_LIST)) {
            handleListClick(player, clicked, event);
        } else if (guiType.equals(HomeIcon.GUI_TYPE_EDIT)) {
            handleEditClick(player, clicked);
        }
    }

    private void handleListClick(@NotNull Player player, @NotNull ItemStack clicked, @NotNull InventoryClickEvent event) {
        String homeName = HomeIcon.readHomeName(plugin, clicked);

        if (homeName != null) {
            boolean left = event.isLeftClick();
            boolean shiftRight = event.isRightClick() && event.isShiftClick();
            boolean right = event.isRightClick() && !event.isShiftClick();

            if (left) {
                player.closeInventory();
                MessageUtil.send(player, "home-gui-teleport",
                        net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("name", homeName));
                teleportManager.teleportHome(player, homeName);
            } else if (shiftRight) {
                teleportManager.deleteHome(player, homeName);
                refreshHomeList(player);
            } else if (right) {
                HomeData home = teleportManager.getHome(player.getUniqueId(), homeName);
                if (home != null) {
                    homeEditGui.open(player, home);
                }
            }
            return;
        }

        if (HomeIcon.hasAction(plugin, clicked, "prev_page")) {
            int current = playerPage.getOrDefault(player.getUniqueId(), 0);
            if (current > 0) {
                refreshHomeListAtPage(player, current - 1);
            }
        } else if (HomeIcon.hasAction(plugin, clicked, "next_page")) {
            int current = playerPage.getOrDefault(player.getUniqueId(), 0);
            refreshHomeListAtPage(player, current + 1);
        } else if (HomeIcon.hasAction(plugin, clicked, "close")) {
            player.closeInventory();
        }
    }

    private void handleEditClick(@NotNull Player player, @NotNull ItemStack clicked) {
        String homeName = editingHome.get(player.getUniqueId());
        if (homeName == null) {
            homeName = HomeIcon.readHomeName(plugin, clicked);
        }

        if (HomeIcon.hasAction(plugin, clicked, "reset_coords")) {
            if (homeName != null) {
                teleportManager.updateHomeLocation(player, homeName);
            }
            player.closeInventory();
        } else if (HomeIcon.hasAction(plugin, clicked, "rename")) {
            if (homeName != null) {
                pendingRename.put(player.getUniqueId(), homeName);
                player.closeInventory();
                MessageUtil.send(player, "home-gui-rename-prompt");
            }
        } else if (HomeIcon.hasAction(plugin, clicked, "back")) {
            refreshHomeList(player);
        }
    }

    private void refreshHomeListAtPage(@NotNull Player player, int page) {
        try {
            List<HomeData> homes = teleportManager.getHomeRepository().findByPlayer(player.getUniqueId());
            int totalPages = Math.max(1, (homes.size() + HomeListGui.HOMES_PER_PAGE - 1) / HomeListGui.HOMES_PER_PAGE);
            int safePage = Math.min(Math.max(0, page), Math.max(0, totalPages - 1));
            playerPage.put(player.getUniqueId(), safePage);
            homeListGui.open(player, homes, safePage);
        } catch (Exception e) {
            plugin.getLogger().severe("Could not refresh home list page: " + e.getMessage());
            MessageUtil.send(player, "database-error");
        }
    }

    @EventHandler
    public void onInventoryClose(@NotNull InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        editingHome.remove(player.getUniqueId());
    }

    /**
     * Drop per-player GUI state on disconnect. Without this a pending rename could fire
     * on the next chat message after the player rejoins.
     */
    @EventHandler
    public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        editingHome.remove(playerId);
        pendingRename.remove(playerId);
        playerPage.remove(playerId);
    }

    @EventHandler
    public void onPlayerChat(@NotNull AsyncChatEvent event) {
        Player player = event.getPlayer();
        String oldName = pendingRename.remove(player.getUniqueId());
        if (oldName == null) {
            return;
        }

        event.setCancelled(true);
        String newName = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();

        org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
            teleportManager.renameHome(player, oldName, newName);
            refreshHomeList(player);
        });
    }
}
