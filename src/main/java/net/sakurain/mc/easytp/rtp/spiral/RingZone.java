package net.sakurain.mc.easytp.rtp.spiral;

import org.jetbrains.annotations.NotNull;

/**
 * A single ring zone used by the Archimedean spiral coordinate generator.
 */
public record RingZone(
        @NotNull String name,
        int minRadius,
        int maxRadius,
        double weight,
        double cumulativeWeight
) {

    /**
     * Area of this ring in square blocks.
     */
    public long area() {
        return (long) (Math.PI * ((long) maxRadius * maxRadius - (long) minRadius * minRadius));
    }

    /**
     * Estimated number of grid points inside this ring given the spacing.
     */
    public long estimatedPoints(int gridSpacing) {
        long spacingSq = (long) gridSpacing * gridSpacing;
        if (spacingSq <= 0) {
            return 1;
        }
        return Math.max(1, area() / spacingSq);
    }
}
