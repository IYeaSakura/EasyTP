package net.sakurain.mc.easytp.manager;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import net.sakurain.mc.easytp.EasyTPPlugin;
import net.sakurain.mc.easytp.rtp.RtpEngine;
import net.sakurain.mc.easytp.storage.DatabaseManager;
import net.sakurain.mc.easytp.storage.HomeData;
import net.sakurain.mc.easytp.storage.HomeRepository;
import net.sakurain.mc.easytp.util.DebugLog;
import net.sakurain.mc.easytp.util.MessageUtil;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.generator.structure.Structure;
import org.bukkit.generator.structure.StructureType;
import org.bukkit.util.StructureSearchResult;
import org.bukkit.configuration.file.FileConfiguration;

import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.*;

/**
 * Manages random teleport, TPA requests, delayed teleports, and home persistence.
 */
public class TeleportManager {

    private final EasyTPPlugin plugin;
    private final HomeRepository homeRepository;
    private final RtpEngine rtpEngine;
    private final Map<String, Long> cooldowns = new HashMap<>();
    private final Map<UUID, TeleportRequest> pendingRequests = new HashMap<>();
    private final Map<UUID, PendingTeleport> pendingTeleports = new HashMap<>();

    public TeleportManager(@NotNull EasyTPPlugin plugin, @NotNull DatabaseManager databaseManager, @NotNull RtpEngine rtpEngine) {
        this.plugin = plugin;
        this.homeRepository = new HomeRepository(databaseManager);
        this.rtpEngine = rtpEngine;
    }

    public void shutdown() {
        for (PendingTeleport pt : pendingTeleports.values()) {
            pt.task().cancel();
        }
        pendingTeleports.clear();
        pendingRequests.clear();
        cooldowns.clear();
        rtpEngine.shutdown();
    }

    // region Homes

    public void setHome(@NotNull Player player, @NotNull String name) {
        if (!isValidHomeName(name)) {
            MessageUtil.send(player, "home-invalid-name");
            return;
        }
        if (!checkDimension(player, "sethome")) {
            return;
        }
        if (!checkCooldown(player, "sethome")) {
            return;
        }

        UUID uuid = player.getUniqueId();
        try {
            HomeData existing = homeRepository.find(uuid, name);
            int currentHomes = homeRepository.countByPlayer(uuid);
            int maxHomes = getMaxHomes(player);

            if (existing == null && currentHomes >= maxHomes) {
                MessageUtil.send(player, "home-max-reached",
                        Placeholder.unparsed("max", String.valueOf(maxHomes)));
                return;
            }

            HomeData data = HomeData.fromLocation(uuid, name, player.getLocation());
            homeRepository.save(data);
            Location loc = player.getLocation();
            MessageUtil.send(player, existing == null ? "home-set" : "home-updated",
                    Placeholder.unparsed("name", name),
                    MessageUtil.coord("x", loc.getX()),
                    MessageUtil.coord("y", loc.getY()),
                    MessageUtil.coord("z", loc.getZ()));
            setCooldown(player, "sethome");
        } catch (Exception e) {
            plugin.getLogger().severe("Could not save home: " + e.getMessage());
            MessageUtil.send(player, "database-error");
        }
    }

    public void teleportHome(@NotNull Player player, @NotNull String name) {
        HomeData home;
        try {
            home = homeRepository.find(player.getUniqueId(), name);
        } catch (Exception e) {
            plugin.getLogger().severe("Could not load home: " + e.getMessage());
            MessageUtil.send(player, "database-error");
            return;
        }

        if (home == null) {
            MessageUtil.send(player, "home-not-set",
                    Placeholder.unparsed("name", name));
            return;
        }

        Location location = home.toLocation();
        if (location == null) {
            MessageUtil.send(player, "home-world-unloaded");
            return;
        }

        if (!checkDimension(player, "home")) {
            return;
        }
        if (!checkCrossDimension(player, player.getWorld(), location.getWorld())) {
            return;
        }
        if (!checkCooldown(player, "home")) {
            return;
        }
        startDelayedTeleport(player, location, "home", () -> {
            setCooldown(player, "home");
            MessageUtil.send(player, "teleport-success");
        });
    }

    public void deleteHome(@NotNull Player player, @NotNull String name) {
        if (!checkDimension(player, "delhome")) {
            return;
        }
        if (!checkCooldown(player, "delhome")) {
            return;
        }
        try {
            HomeData home = homeRepository.find(player.getUniqueId(), name);
            if (home == null) {
                MessageUtil.send(player, "home-not-set",
                        Placeholder.unparsed("name", name));
                return;
            }
            homeRepository.delete(player.getUniqueId(), name);
            MessageUtil.send(player, "home-deleted",
                    Placeholder.unparsed("name", name));
            setCooldown(player, "delhome");
        } catch (Exception e) {
            plugin.getLogger().severe("Could not delete home: " + e.getMessage());
            MessageUtil.send(player, "database-error");
        }
    }

    @NotNull
    public List<String> getHomeNames(@NotNull Player player) {
        try {
            return homeRepository.findByPlayer(player.getUniqueId()).stream()
                    .map(HomeData::name)
                    .sorted()
                    .toList();
        } catch (Exception e) {
            plugin.getLogger().severe("Could not retrieve home names: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    @Nullable
    public HomeData getHome(@NotNull UUID uuid, @NotNull String name) {
        try {
            return homeRepository.find(uuid, name);
        } catch (Exception e) {
            plugin.getLogger().severe("Could not load home: " + e.getMessage());
            return null;
        }
    }

    /**
     * Move a home to the player's current position, as offered by the home edit GUI.
     *
     * <p>This is the GUI equivalent of setting a home, so it obeys {@code dimensions.sethome}:
     * with that whitelist excluding a dimension, a home cannot be relocated from there.</p>
     */
    public void updateHomeLocation(@NotNull Player player, @NotNull String name) {
        if (!checkDimension(player, "sethome")) {
            return;
        }
        try {
            HomeData home = homeRepository.find(player.getUniqueId(), name);
            if (home == null) {
                MessageUtil.send(player, "home-not-set",
                        Placeholder.unparsed("name", name));
                return;
            }
            homeRepository.updateLocation(player.getUniqueId(), name, player.getLocation());
            MessageUtil.send(player, "home-gui-coords-updated",
                    Placeholder.unparsed("name", name));
        } catch (Exception e) {
            plugin.getLogger().severe("Could not update home location: " + e.getMessage());
            MessageUtil.send(player, "database-error");
        }
    }

    public void renameHome(@NotNull Player player, @NotNull String oldName, @NotNull String newName) {
        if (!isValidHomeName(newName)) {
            MessageUtil.send(player, "home-invalid-name");
            return;
        }
        if (oldName.equalsIgnoreCase(newName)) {
            MessageUtil.send(player, "home-gui-renamed",
                    Placeholder.unparsed("name", newName));
            return;
        }

        try {
            HomeData existing = homeRepository.find(player.getUniqueId(), oldName);
            if (existing == null) {
                MessageUtil.send(player, "home-not-set",
                        Placeholder.unparsed("name", oldName));
                return;
            }
            HomeData conflict = homeRepository.find(player.getUniqueId(), newName);

            if (conflict != null && !conflict.name().equals(oldName)) {
                MessageUtil.send(player, "home-name-exists",
                        Placeholder.unparsed("name", newName));
                return;
            }

            homeRepository.rename(player.getUniqueId(), oldName, newName);
            MessageUtil.send(player, "home-gui-renamed",
                    Placeholder.unparsed("name", newName));
        } catch (Exception e) {
            plugin.getLogger().severe("Could not rename home: " + e.getMessage());
            MessageUtil.send(player, "database-error");
        }
    }

    private int getMaxHomes(@NotNull Player player) {
        return plugin.getConfig().getInt("home.max-homes", 5);
    }

    private boolean isValidHomeName(@NotNull String name) {
        if (name.isBlank() || name.length() > 16) {
            return false;
        }
        return name.chars().noneMatch(c -> c == '.' || c == '/' || Character.isWhitespace(c));
    }

    @NotNull
    public HomeRepository getHomeRepository() {
        return homeRepository;
    }

    // endregion

    // region Cooldown

    public boolean checkCooldown(@NotNull Player player, @NotNull String commandKey) {
        if (player.hasPermission("easytp.admin.bypass-cooldown")) {
            return true;
        }
        int cooldownSeconds = readCommandCooldown(commandKey);
        if (cooldownSeconds <= 0) {
            return true;
        }
        long now = System.currentTimeMillis();
        Long last = cooldowns.get(cooldownKey(player.getUniqueId(), commandKey));
        if (last != null && now - last < cooldownSeconds * 1000L) {
            long remaining = (cooldownSeconds * 1000L - (now - last)) / 1000L + 1;
            DebugLog.log("cooldown", "%s blocked on '%s': %ds of %ds remaining",
                    player.getName(), commandKey, remaining, cooldownSeconds);
            MessageUtil.send(player, "cooldown-active", (int) remaining);
            return false;
        }
        return true;
    }

    private int readCommandCooldown(@NotNull String commandKey) {
        FileConfiguration config = plugin.getConfig();
        int cooldown = config.getInt("commands." + commandKey + ".cooldown", -1);
        if (cooldown >= 0) {
            return cooldown;
        }

        // Legacy fallback keys
        return switch (commandKey) {
            case "rtp" -> config.getInt("rtp.cooldown", 0);
            case "home" -> config.getInt("home.cooldown", 0);
            case "tpa" -> config.getInt("tpa.cooldown", 0);
            case "tphere" -> config.getInt("tphere.cooldown", 0);
            default -> 0;
        };
    }

    /**
     * Record the cooldown timestamp for one command.
     *
     * <p>Cooldowns are tracked per command, so using /rtp does not block /tpa or /tphere.</p>
     */
    public void setCooldown(@NotNull Player player, @NotNull String commandKey) {
        cooldowns.put(cooldownKey(player.getUniqueId(), commandKey), System.currentTimeMillis());
    }

    @NotNull
    private String cooldownKey(@NotNull UUID playerId, @NotNull String commandKey) {
        return playerId + ":" + commandKey;
    }

    // endregion

    // region Dimension policy

    /** Every dimension EasyTP understands, used when a command has no whitelist configured. */
    private static final Set<World.Environment> ALL_DIMENSIONS = Collections.unmodifiableSet(
            EnumSet.of(World.Environment.NORMAL, World.Environment.NETHER, World.Environment.THE_END));

    /**
     * Read {@code dimensions.<command>} and resolve it to a set of environments.
     *
     * <p>A missing or empty list means "no restriction", and so does a list whose entries are all
     * unrecognised — locking a command out of every dimension because of a typo would be a nasty
     * surprise, so unknown entries are reported and ignored instead.</p>
     */
    @NotNull
    private Set<World.Environment> readCommandDimensions(@NotNull String commandKey) {
        List<String> configured = plugin.getConfig().getStringList("dimensions." + commandKey);
        if (configured.isEmpty()) {
            return ALL_DIMENSIONS;
        }
        EnumSet<World.Environment> allowed = EnumSet.noneOf(World.Environment.class);
        for (String raw : configured) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                allowed.add(World.Environment.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Unknown dimension '" + raw + "' in dimensions." + commandKey);
            }
        }
        return allowed.isEmpty() ? ALL_DIMENSIONS : allowed;
    }

    /**
     * Check that {@code commandKey} may be used from the dimension the player is standing in.
     *
     * @return {@code true} when the command may proceed
     */
    public boolean checkDimension(@NotNull Player player, @NotNull String commandKey) {
        World.Environment current = player.getWorld().getEnvironment();
        if (readCommandDimensions(commandKey).contains(current)) {
            return true;
        }
        DebugLog.log("dimension", "%s denied '%s' in %s (allowed: %s)",
                player.getName(), commandKey, current, readCommandDimensions(commandKey));
        MessageUtil.send(player, "dimension-not-allowed",
                Placeholder.component("dimension", MessageUtil.dimensionName(current)));
        return false;
    }

    /**
     * Check that a teleport may cross between two worlds, honouring
     * {@code teleport.allow-cross-dimension}.
     *
     * @param recipient who receives the denial message
     * @param from      the world being left, or null if unknown
     * @param to        the destination world, or null if unknown
     * @return {@code true} when the teleport may proceed
     */
    public boolean checkCrossDimension(@NotNull Player recipient, @Nullable World from, @Nullable World to) {
        if (plugin.getConfig().getBoolean("teleport.allow-cross-dimension", true)) {
            return true;
        }
        if (from == null || to == null || from.getUID().equals(to.getUID())) {
            return true;
        }
        DebugLog.log("dimension", "%s blocked a cross-dimension teleport %s -> %s",
                recipient.getName(), from.getEnvironment(), to.getEnvironment());
        MessageUtil.send(recipient, "cross-dimension-denied",
                Placeholder.component("from", MessageUtil.dimensionName(from.getEnvironment())),
                Placeholder.component("to", MessageUtil.dimensionName(to.getEnvironment())));
        return false;
    }

    // endregion

    // region Random Teleport

    public void randomTeleport(@NotNull Player player) {
        randomTeleportInternal(player, "rtp");
    }

    private void randomTeleportInternal(@NotNull Player player, @NotNull String cooldownKey) {
        if (!plugin.getConfig().getBoolean("rtp.enabled", true)) {
            MessageUtil.send(player, "feature-disabled");
            return;
        }
        if (!checkDimension(player, cooldownKey)) {
            return;
        }
        if (!checkCooldown(player, cooldownKey)) {
            return;
        }

        MessageUtil.send(player, "rtp-searching");

        rtpEngine.randomTeleport(player,
                safe -> startDelayedTeleport(player, safe, "rtp", () -> {
                    setCooldown(player, cooldownKey);
                    MessageUtil.send(player, "rtp-success",
                            MessageUtil.coord("x", safe.getX()),
                            MessageUtil.coord("y", safe.getY()),
                            MessageUtil.coord("z", safe.getZ()));
                }),
                () -> MessageUtil.send(player, "rtp-failed")
        );
    }

    /**
     * Whether the deprecated structure RTP mode is enabled. Defaults to {@code false}.
     */
    public boolean isStructureRtpEnabled() {
        return plugin.getConfig().getBoolean("rtp.structure.enabled", false);
    }

    /**
     * Teleport near the nearest instance of a structure.
     *
     * @deprecated Structure RTP is deprecated and disabled by default behind
     *             {@code rtp.structure.enabled}; it is a candidate for removal.
     */
    @Deprecated(forRemoval = true)
    public void randomTeleportNearStructure(@NotNull Player player, @NotNull String structureKey) {
        if (!isStructureRtpEnabled()) {
            MessageUtil.send(player, "structure-disabled");
            return;
        }
        if (!plugin.getConfig().getBoolean("rtp.enabled", true)) {
            MessageUtil.send(player, "feature-disabled");
            return;
        }
        if (!checkDimension(player, "rtp")) {
            return;
        }
        if (!checkCooldown(player, "rtp")) {
            return;
        }

        MessageUtil.send(player, "rtp-searching");
        Location origin = player.getLocation();
        World world = origin.getWorld();

        // Structure location must be triggered synchronously
        Bukkit.getScheduler().runTask(plugin, () -> {
            NamespacedKey key = structureKey.indexOf(':') >= 0
                    ? NamespacedKey.fromString(structureKey.toLowerCase(Locale.ROOT))
                    : NamespacedKey.minecraft(structureKey.toLowerCase(Locale.ROOT));
            if (key == null) {
                MessageUtil.send(player, "invalid-structure", Placeholder.unparsed("structure", structureKey));
                return;
            }

            Location targetStructure = locateStructure(world, origin, key);
            if (targetStructure == null) {
                MessageUtil.send(player, "structure-not-found");
                return;
            }
            int maxSafeDistance = plugin.getConfig().getInt("rtp.structure.max-safe-distance", 256);
            Location safe = findSafeLocationNear(world, targetStructure.getBlockX(), targetStructure.getBlockZ(), maxSafeDistance);
            if (safe == null) {
                MessageUtil.send(player, "rtp-failed");
                return;
            }

            double distanceToPlayer = origin.distance(targetStructure);
            double distanceToSafe = targetStructure.distance(safe);
            plugin.getLogger().info(String.format("[RTP Structure] %s -> %s at [%d, %d, %d] (%.1f blocks from player, safe spot %.1f blocks from structure)",
                    player.getName(), structureKey, targetStructure.getBlockX(), targetStructure.getBlockY(), targetStructure.getBlockZ(), distanceToPlayer, distanceToSafe));

            startDelayedTeleport(player, safe, "rtp", () -> {
                setCooldown(player, "rtp");
                MessageUtil.send(player, "rtp-structure-success",
                        Placeholder.unparsed("structure", structureKey),
                        MessageUtil.coord("x", targetStructure.getX()),
                        MessageUtil.coord("y", targetStructure.getY()),
                        MessageUtil.coord("z", targetStructure.getZ()),
                        Placeholder.unparsed("distance", String.format("%.1f", distanceToPlayer)),
                        Placeholder.unparsed("safe-distance", String.format("%.1f", distanceToSafe)));
            });
        });
    }

    @Nullable
    private Location locateStructure(@NotNull World world, @NotNull Location origin, @NotNull NamespacedKey key) {
        try {
            Registry<Structure> structureRegistry = RegistryAccess.registryAccess().getRegistry(RegistryKey.STRUCTURE);
            Structure structure = structureRegistry.get(key);
            if (structure != null) {
                StructureSearchResult result = world.locateNearestStructure(origin, structure, 10000, false);
                return result == null ? null : result.getLocation();
            }
        } catch (IllegalArgumentException | NoSuchElementException ignored) {
        }

        StructureType type = Registry.STRUCTURE_TYPE.get(key);
        if (type != null) {
            StructureSearchResult result = world.locateNearestStructure(origin, type, 10000, false);
            return result == null ? null : result.getLocation();
        }
        return null;
    }

    @Nullable
    private Location findSafeLocationNear(@NotNull World world, int sx, int sz, int maxDistance) {
        return rtpEngine.findSafeSpotNearSync(world, sx, sz, maxDistance);
    }

    // endregion

    // region TPA Requests

    public void sendRequest(@NotNull Player requester, @NotNull String targetName, boolean here) {
        if (!plugin.getConfig().getBoolean("tpa.enabled", true)) {
            MessageUtil.send(requester, "feature-disabled");
            return;
        }
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            MessageUtil.send(requester, "player-not-found", targetName);
            return;
        }
        if (requester.getUniqueId().equals(target.getUniqueId())) {
            MessageUtil.send(requester, "cannot-request-self");
            return;
        }
        String commandKey = here ? "tphere" : "tpa";
        if (!checkDimension(requester, commandKey)) {
            return;
        }
        // Whoever ends up moving must not cross into another dimension when that is disabled:
        // /tpa moves the requester to the target, /tphere moves the target to the requester.
        World origin = here ? target.getWorld() : requester.getWorld();
        World destination = here ? requester.getWorld() : target.getWorld();
        if (!checkCrossDimension(requester, origin, destination)) {
            return;
        }
        if (!checkCooldown(requester, commandKey)) {
            return;
        }
        if (pendingRequests.containsKey(target.getUniqueId())) {
            MessageUtil.send(requester, "pending-request");
            return;
        }

        int timeout = plugin.getConfig().getInt("tpa.timeout", 30);
        pendingRequests.put(target.getUniqueId(), new TeleportRequest(requester.getUniqueId(), target.getUniqueId(), here, System.currentTimeMillis() + timeout * 1000L));
        MessageUtil.send(requester, "request-sent", target.getName());
        sendRequestMessage(target, requester.getName(), here);
        setCooldown(requester, commandKey);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            TeleportRequest req = pendingRequests.remove(target.getUniqueId());
            if (req != null) {
                Player stillTarget = Bukkit.getPlayer(target.getUniqueId());
                Player stillRequester = Bukkit.getPlayer(requester.getUniqueId());
                if (stillTarget != null) {
                    MessageUtil.send(stillTarget, "request-expired");
                }
                if (stillRequester != null) {
                    MessageUtil.send(stillRequester, "request-expired");
                }
            }
        }, timeout * 20L);
    }

    private void sendRequestMessage(@NotNull Player target, @NotNull String requesterName, boolean here) {
        Component accept = MessageUtil.parseNoPrefix("tpa-button-accept")
                .clickEvent(ClickEvent.runCommand("/tpaccept"))
                .hoverEvent(HoverEvent.showText(MessageUtil.parseNoPrefix("tpa-button-accept-hover")));
        Component deny = MessageUtil.parseNoPrefix("tpa-button-deny")
                .clickEvent(ClickEvent.runCommand("/tpdeny"))
                .hoverEvent(HoverEvent.showText(MessageUtil.parseNoPrefix("tpa-button-deny-hover")));

        Component message = MessageUtil.parse(here ? "request-received-here" : "request-received",
                Placeholder.unparsed("player", requesterName),
                Placeholder.component("accept", accept),
                Placeholder.component("deny", deny));
        target.sendMessage(message);
    }

    public void acceptRequest(@NotNull Player target) {
        if (!checkDimension(target, "tpaccept")) {
            return;
        }
        // Both checks run before the request is consumed, otherwise a rejection would silently
        // discard the pending request.
        if (!checkCooldown(target, "tpaccept")) {
            return;
        }
        TeleportRequest request = pendingRequests.get(target.getUniqueId());
        if (request == null) {
            MessageUtil.send(target, "no-pending-request");
            return;
        }
        if (System.currentTimeMillis() > request.expiry()) {
            pendingRequests.remove(target.getUniqueId());
            MessageUtil.send(target, "request-expired");
            return;
        }

        Player requester = Bukkit.getPlayer(request.requesterId());
        if (requester == null) {
            pendingRequests.remove(target.getUniqueId());
            MessageUtil.send(target, "request-player-offline");
            return;
        }

        Player toTeleport = request.here() ? target : requester;
        Player destinationPlayer = request.here() ? requester : target;
        // Re-checked on accept as well: either player may have changed dimension since the request
        // was sent. The request is deliberately left pending so they can retry after moving.
        if (!checkCrossDimension(target, toTeleport.getWorld(), destinationPlayer.getWorld())) {
            return;
        }

        pendingRequests.remove(target.getUniqueId());
        MessageUtil.send(target, "request-accepted", requester.getName());
        MessageUtil.send(requester, "request-accepted-notify", target.getName());

        String commandKey = request.here() ? "tphere" : "tpa";
        startDelayedTeleport(toTeleport, destinationPlayer.getLocation(), commandKey,
                () -> MessageUtil.send(toTeleport, "teleport-success"));
        setCooldown(target, "tpaccept");
    }

    public void denyRequest(@NotNull Player target) {
        if (!checkDimension(target, "tpdeny")) {
            return;
        }
        if (!checkCooldown(target, "tpdeny")) {
            return;
        }
        TeleportRequest request = pendingRequests.remove(target.getUniqueId());
        if (request == null) {
            MessageUtil.send(target, "no-pending-request");
            return;
        }
        Player requester = Bukkit.getPlayer(request.requesterId());
        if (requester == null) {
            MessageUtil.send(target, "request-player-offline");
            return;
        }
        MessageUtil.send(target, "request-denied", requester.getName());
        MessageUtil.send(requester, "request-denied-notify", target.getName());
        setCooldown(target, "tpdeny");
    }

    // endregion

    // region Delayed Teleport

    public void startDelayedTeleport(@NotNull Player player, @NotNull Location destination, @NotNull String commandKey, @Nullable Runnable onComplete) {
        cancelPendingTeleport(player, false);
        int delaySeconds = readCommandDelay(commandKey);
        if (delaySeconds <= 0) {
            DebugLog.log("teleport", "%s -> %s via '%s' with no delay", player.getName(),
                    describe(destination), commandKey);
            player.teleportAsync(destination).thenRun(() -> {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    spawnArrivalParticles(destination);
                    playTeleportSound(destination);
                    if (onComplete != null) {
                        onComplete.run();
                    }
                });
            });
            return;
        }

        DebugLog.log("teleport", "%s -> %s via '%s', countdown %ds", player.getName(),
                describe(destination), commandKey, delaySeconds);
        MessageUtil.send(player, "teleport-countdown", delaySeconds);
        final int[] remaining = {delaySeconds};
        Location origin = player.getLocation().clone();
        BukkitTask task = new DelayedTeleportTask(player, destination, remaining, onComplete)
                .runTaskTimer(plugin, 20L, 20L);
        pendingTeleports.put(player.getUniqueId(), new PendingTeleport(task, origin));
        showTitleCountdown(player, delaySeconds);
    }

    @NotNull
    private String describe(@NotNull Location location) {
        return String.format(Locale.ROOT, "%s[%.0f, %.0f, %.0f]",
                location.getWorld() == null ? "?" : location.getWorld().getName(),
                location.getX(), location.getY(), location.getZ());
    }

    private int readCommandDelay(@NotNull String commandKey) {
        FileConfiguration config = plugin.getConfig();
        int delay = config.getInt("commands." + commandKey + ".delay", -1);
        if (delay >= 0) {
            return delay;
        }
        return config.getInt("teleport.delay", 5);
    }

    private void spawnTeleportParticles(@NotNull Player player, int remaining) {
        if (!areEffectsEnabled()) {
            return;
        }
        Location base = player.getLocation().add(0, 1, 0);
        World world = player.getWorld();
        spawnParticleSafe(world, Particle.PORTAL, base, 80, 0.8, 1.2, 0.8, 0.1);
        spawnParticleSafe(world, Particle.ENCHANT, base, 50, 0.8, 1.2, 0.8, 0.2);

        if (remaining <= 1) {
            spawnParticleSafe(world, Particle.FLAME, base, 40, 0.5, 1.0, 0.5, 0.1);
            spawnParticleSafe(world, Particle.LAVA, base, 20, 0.3, 0.5, 0.3, 0.05);
        }
    }

    private void spawnArrivalParticles(@NotNull Location destination) {
        if (!areEffectsEnabled()) {
            return;
        }
        Location loc = destination.clone().add(0, 0.1, 0);
        World world = destination.getWorld();
        spawnParticleSafe(world, Particle.END_ROD, loc, 100, 1.2, 0.3, 1.2, 0.03);
        spawnParticleSafe(world, Particle.HAPPY_VILLAGER, loc, 60, 1.2, 0.3, 1.2, 0.05);
        spawnParticleSafe(world, Particle.FIREWORK, loc, 40, 0.5, 0.2, 0.5, 0.05);
        spawnParticleSafe(world, Particle.SOUL_FIRE_FLAME, loc, 30, 0.8, 0.2, 0.8, 0.02);
    }

    private void spawnCancelParticles(@NotNull Player player) {
        if (!areEffectsEnabled()) {
            return;
        }
        Location loc = player.getLocation().add(0, 1, 0);
        World world = player.getWorld();
        spawnParticleSafe(world, Particle.SMOKE, loc, 50, 0.5, 0.8, 0.5, 0.05);
        spawnParticleSafe(world, Particle.CLOUD, loc, 30, 0.5, 0.5, 0.5, 0.02);
    }

    private void spawnParticleSafe(@NotNull World world, @NotNull Particle particle, @NotNull Location location, int count, double offsetX, double offsetY, double offsetZ, double extra) {
        try {
            world.spawnParticle(particle, location, count, offsetX, offsetY, offsetZ, extra);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Particle " + particle.name() + " is not supported in this server version: " + e.getMessage());
        }
    }

    private void playTeleportSound(@NotNull Location location) {
        if (!areEffectsEnabled()) {
            return;
        }
        World world = location.getWorld();
        if (world != null) {
            world.playSound(location, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
        }
    }

    private boolean areEffectsEnabled() {
        return plugin.getConfig().getBoolean("effects.enabled", true);
    }

    private void showTitleCountdown(@NotNull Player player, int seconds) {
        if (!plugin.getConfig().getBoolean("teleport.show-title", true)) {
            return;
        }
        Component title = MessageUtil.parseNoPrefix("teleport-title");
        Component subtitle = MessageUtil.parseNoPrefix("teleport-subtitle",
                Placeholder.unparsed("seconds", String.valueOf(seconds)));
        Title.Times times = Title.Times.times(Duration.ZERO, Duration.ofMillis(1100), Duration.ZERO);
        player.showTitle(Title.title(title, subtitle, times));
    }

    public void cancelPendingTeleport(@NotNull Player player, boolean sendMessage) {
        PendingTeleport pt = pendingTeleports.remove(player.getUniqueId());
        if (pt == null) {
            return;
        }
        pt.task().cancel();
        spawnCancelParticles(player);
        if (sendMessage) {
            DebugLog.log("teleport", "%s cancelled the pending teleport by moving", player.getName());
            MessageUtil.send(player, "teleport-cancelled-move");
        }
    }

    public void cancelPendingTeleportOnDamage(@NotNull Player player) {
        PendingTeleport pt = pendingTeleports.remove(player.getUniqueId());
        if (pt == null) {
            return;
        }
        pt.task().cancel();
        spawnCancelParticles(player);
        DebugLog.log("teleport", "%s cancelled the pending teleport by taking damage", player.getName());
        MessageUtil.send(player, "teleport-cancelled-damage");
    }

    public boolean hasPendingTeleport(@NotNull Player player) {
        return pendingTeleports.containsKey(player.getUniqueId());
    }

    // endregion

    /**
     * Delayed teleport countdown task.
     */
    private final class DelayedTeleportTask extends BukkitRunnable {
        private final Player player;
        private final Location destination;
        private final int[] remaining;
        private final Runnable onComplete;

        DelayedTeleportTask(Player player, Location destination, int[] remaining, Runnable onComplete) {
            this.player = player;
            this.destination = destination;
            this.remaining = remaining;
            this.onComplete = onComplete;
        }

        @Override
        public void run() {
            if (!player.isOnline()) {
                cancel();
                pendingTeleports.remove(player.getUniqueId());
                DebugLog.log("teleport", "%s went offline during the countdown; teleport aborted", player.getName());
                return;
            }
            spawnTeleportParticles(player, remaining[0]);
            spawnArrivalParticles(destination);
            remaining[0]--;
            if (remaining[0] <= 0) {
                cancel();
                pendingTeleports.remove(player.getUniqueId());
                player.teleportAsync(destination).thenRun(() -> {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        spawnArrivalParticles(destination);
                        playTeleportSound(destination);
                        DebugLog.log("teleport", "%s arrived at %s", player.getName(), describe(destination));
                        if (onComplete != null) {
                            onComplete.run();
                        }
                    });
                });
            } else {
                MessageUtil.send(player, "teleport-countdown", remaining[0]);
                showTitleCountdown(player, remaining[0]);
            }
        }
    }

    /**
     * TPA request record.
     */
    private record TeleportRequest(UUID requesterId, UUID targetId, boolean here, long expiry) {
    }

    /**
     * Pending teleport record.
     */
    private record PendingTeleport(BukkitTask task, Location origin) {
    }
}
