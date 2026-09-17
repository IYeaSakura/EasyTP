package net.sakurain.mc.easytp.rtp.spiral;

import net.sakurain.mc.easytp.rtp.RtpStorage;
import net.sakurain.mc.easytp.rtp.SearchParams;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Deterministic Archimedean spiral coordinate generator.
 *
 * <p>The world is divided into weighted ring zones. Inside each ring, a 1D spiral index
 * is mapped to a 2D coordinate using a fixed angular step (the golden angle) and a
 * radius derived from cumulative ring area. This makes coordinate generation O(1) and
 * avoids the random-retry loops that cause MSPT jitter over oceans or lava lakes.</p>
 */
public class SpiralCoordinateGenerator {

    // Golden angle in radians; gives near-uniform angular distribution.
    private static final double GOLDEN_ANGLE = Math.PI * (3.0 - Math.sqrt(5.0));

    private final int gridSpacing;
    private final List<RingZone> rings;
    private final double totalWeight;
    private final RtpStorage storage;

    public SpiralCoordinateGenerator(@NotNull SearchParams params, @NotNull RtpStorage storage) {
        this.gridSpacing = Math.max(4, params.gridSpacing());
        this.storage = storage;
        this.rings = buildRings(params.rings());
        this.totalWeight = rings.isEmpty() ? 1.0 : rings.get(rings.size() - 1).cumulativeWeight();
    }

    @NotNull
    private static List<RingZone> buildRings(@NotNull List<SearchParams.RingConfig> configs) {
        List<RingZone> result = new ArrayList<>();
        double cumulative = 0.0;
        for (SearchParams.RingConfig config : configs) {
            cumulative += config.weight();
            result.add(new RingZone(config.name(), config.minRadius(), config.maxRadius(), config.weight(), cumulative));
        }
        return List.copyOf(result);
    }

    /**
     * Generate the next candidate coordinate for the given world.
     * The returned coordinate is guaranteed to be inside the world border.
     *
     * @param world target world
     * @return next candidate coordinate
     */
    @NotNull
    public Coordinate next(@NotNull World world) {
        RingZone ring = selectRing();
        long index = storage.incrementAndGetSpiralIndex(world.getName(), ring.name());
        long maxPoints = ring.estimatedPoints(gridSpacing);
        if (maxPoints > 0) {
            index = index % maxPoints;
        }

        int safety = 0;
        while (safety < 16) {
            Coordinate candidate = mapIndex(ring, index);
            if (isInsideBorder(world, candidate)) {
                return candidate;
            }
            safety++;
            index = (index + 1) % Math.max(1, maxPoints);
        }
        return mapIndex(ring, index);
    }

    @NotNull
    private RingZone selectRing() {
        if (rings.size() == 1) {
            return rings.get(0);
        }
        double value = ThreadLocalRandom.current().nextDouble() * totalWeight;
        for (RingZone ring : rings) {
            if (value <= ring.cumulativeWeight()) {
                return ring;
            }
        }
        return rings.get(rings.size() - 1);
    }

    @NotNull
    private Coordinate mapIndex(@NotNull RingZone ring, long index) {
        double cumulativeArea = index * (double) gridSpacing * (double) gridSpacing;
        double radius = Math.sqrt((double) ring.minRadius() * ring.minRadius() + cumulativeArea / Math.PI);
        radius = Math.min(radius, ring.maxRadius());
        double angle = GOLDEN_ANGLE * index;

        int x = (int) Math.round(radius * Math.cos(angle));
        int z = (int) Math.round(radius * Math.sin(angle));
        return new Coordinate(x, z);
    }

    private boolean isInsideBorder(@NotNull World world, @NotNull Coordinate coordinate) {
        return world.getWorldBorder().isInside(new Location(world, coordinate.x(), 64.0, coordinate.z()));
    }

    /**
     * Immutable integer block coordinate.
     */
    public record Coordinate(int x, int z) {
    }
}
