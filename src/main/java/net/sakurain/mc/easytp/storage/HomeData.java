package net.sakurain.mc.easytp.storage;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Immutable record representing a single saved home location.
 */
public record HomeData(
        @NotNull UUID playerUuid,
        @NotNull String name,
        @NotNull String worldName,
        double x,
        double y,
        double z,
        float yaw,
        float pitch
) {

    /**
     * Convert this home data to a Bukkit Location.
     *
     * @return the location, or null if the world is not loaded
     */
    @Nullable
    public Location toLocation() {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }
        return new Location(world, x, y, z, yaw, pitch);
    }

    /**
     * Create a HomeData from a player location.
     *
     * @param playerUuid the owning player
     * @param name       the home name
     * @param location   the location to store
     * @return a new HomeData instance
     */
    @NotNull
    public static HomeData fromLocation(@NotNull UUID playerUuid, @NotNull String name, @NotNull Location location) {
        World world = location.getWorld();
        if (world == null) {
            throw new IllegalArgumentException("Location must have a world");
        }
        return new HomeData(
                playerUuid,
                name,
                world.getName(),
                location.getX(),
                location.getY(),
                location.getZ(),
                location.getYaw(),
                location.getPitch()
        );
    }
}
