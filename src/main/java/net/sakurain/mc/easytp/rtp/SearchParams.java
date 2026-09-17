package net.sakurain.mc.easytp.rtp;

import net.sakurain.mc.easytp.EasyTPPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable parameters that control RTP location searching and safety validation.
 *
 * <p>Only settings the engine actually reads are held here.</p>
 */
public record SearchParams(
        boolean surfaceOnly,
        int maxScanDepth,
        boolean allowLiquid,
        int gridSpacing,
        int spatialCellSize,
        int spatialMemoryMaxEntries,
        @NotNull List<RingConfig> rings,
        int poolBaseSize,
        double poolSizeMultiplier,
        int maxValidationsPerTick,
        int maxChunkLoadsPerTick,
        int maxInFlightLoads,
        int playerWaitTimeoutSeconds,
        int maxQueuedPlayers
) {

    /**
     * Load search parameters from the plugin configuration.
     * New spiral and pool keys are used when present; otherwise legacy keys are honoured.
     */
    @NotNull
    public static SearchParams fromConfig(@NotNull EasyTPPlugin plugin) {
        FileConfiguration config = plugin.getConfig();
        int legacyMin = config.getInt("rtp.min-radius", 2000);
        int legacyMax = config.getInt("rtp.max-radius", 5000);

        List<RingConfig> rings = loadRings(config, legacyMin, legacyMax);

        return new SearchParams(
                config.getBoolean("rtp.overworld-surface-only", true),
                config.getInt("rtp.max-scan-depth", 16),
                config.getBoolean("rtp.allow-liquid", false),
                config.getInt("rtp.spiral.grid-spacing", 16),
                config.getInt("rtp.spatial-memory.cell-size", 32),
                config.getInt("rtp.spatial-memory.max-entries", 50000),
                rings,
                config.getInt("rtp.pool.base-size", 12),
                config.getDouble("rtp.pool.size-multiplier", 2.0),
                config.getInt("rtp.pool.max-validations-per-tick", 2),
                config.getInt("rtp.pool.max-chunk-loads-per-tick", 2),
                config.getInt("rtp.pool.max-in-flight-loads", 8),
                config.getInt("rtp.pool.player-wait-timeout-seconds", 15),
                config.getInt("rtp.pool.max-queued-players", 10)
        );
    }

    @NotNull
    private static List<RingConfig> loadRings(@NotNull FileConfiguration config, int fallbackMin, int fallbackMax) {
        ConfigurationSection section = config.getConfigurationSection("rtp.spiral.ring-zones");
        if (section == null) {
            return List.of(new RingConfig("default", fallbackMin, fallbackMax, 1.0));
        }

        List<RingConfig> rings = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            ConfigurationSection ringSection = section.getConfigurationSection(key);
            if (ringSection == null) {
                continue;
            }
            int min = ringSection.getInt("min-radius", fallbackMin);
            int max = ringSection.getInt("max-radius", fallbackMax);
            double weight = ringSection.getDouble("weight", 1.0);
            rings.add(new RingConfig(key, Math.max(0, min), Math.max(min + 1, max), Math.max(0.0, weight)));
        }

        if (rings.isEmpty()) {
            return List.of(new RingConfig("default", fallbackMin, fallbackMax, 1.0));
        }
        return Collections.unmodifiableList(rings);
    }

    /**
     * Configuration for a single weighted ring zone used by the spiral generator.
     */
    public record RingConfig(@NotNull String name, int minRadius, int maxRadius, double weight) {
    }
}
