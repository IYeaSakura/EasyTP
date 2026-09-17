package net.sakurain.mc.easytp.rtp.pool;

import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;

/**
 * A validated safe RTP destination stored in the preload pool.
 */
public record RtpLocation(
        @NotNull String worldName,
        double x,
        double y,
        double z
) {

    /**
     * Convert this pool entry to a Bukkit location.
     */
    @NotNull
    public Location toLocation(@NotNull World world) {
        return new Location(world, x, y, z);
    }
}
