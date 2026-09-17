package net.sakurain.mc.easytp.rtp.memory;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Classification of a spatial memory cell.
 */
public enum RegionState {

    UNKNOWN("unknown"),
    SAFE("safe"),
    UNSAFE_BLOCK("unsafe_block"),
    UNSAFE_BIOME("unsafe_biome"),
    UNSAFE_CLAIM("unsafe_claim"),
    UNSAFE_VOID("unsafe_void"),
    /**
     * Legacy state, no longer written: out-of-border coordinates are skipped instead of
     * persisted, because the world border can move. Retained so existing rows still parse.
     */
    UNSAFE_BORDER("unsafe_border");

    private final String key;

    RegionState(@NotNull String key) {
        this.key = key;
    }

    @NotNull
    public String getKey() {
        return key;
    }

    /**
     * Convert a persisted key back into a region state.
     */
    @NotNull
    public static RegionState fromKey(@Nullable String key) {
        if (key == null) {
            return UNKNOWN;
        }
        for (RegionState state : values()) {
            if (state.key.equalsIgnoreCase(key)) {
                return state;
            }
        }
        return UNKNOWN;
    }

    /**
     * Returns true if this state means the location should be skipped.
     */
    public boolean isUnsafe() {
        return this != UNKNOWN && this != SAFE;
    }
}
