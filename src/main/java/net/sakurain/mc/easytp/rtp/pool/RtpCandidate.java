package net.sakurain.mc.easytp.rtp.pool;

import org.jetbrains.annotations.NotNull;

/**
 * A candidate RTP coordinate bound to a specific world.
 */
public record RtpCandidate(@NotNull String worldName, int x, int z) {
}
