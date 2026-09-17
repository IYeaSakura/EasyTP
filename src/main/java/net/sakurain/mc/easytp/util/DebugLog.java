package net.sakurain.mc.easytp.util;

import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.logging.Logger;

/**
 * Console diagnostics gate.
 *
 * <p>Debug output is opt-in through {@code debug.enabled}. Everything funnels through this class so
 * that enabling the setting is enough — no call site has to know how the flag is stored, and every
 * line carries a {@code [DEBUG][category]} prefix that makes it greppable.</p>
 *
 * <p>High-frequency events should be <em>counted</em> rather than logged line by line; the RTP
 * engine keeps aggregate counters and prints a periodic summary. Per-event debug lines are reserved
 * for things that happen rarely or that explain a stall (slow chunk loads, failures, queue
 * timeouts).</p>
 */
public final class DebugLog {

    private static final String PREFIX = "[DEBUG]";
    private static final int MIN_SUMMARY_INTERVAL_SECONDS = 5;

    private static volatile boolean enabled = false;
    private static volatile int summaryIntervalSeconds = 30;
    private static volatile Logger logger = Logger.getLogger("EasyTP");

    private DebugLog() {
        // utility class
    }

    /**
     * Read the debug settings. Called on enable and again on every configuration reload.
     */
    public static void init(@NotNull JavaPlugin plugin) {
        logger = plugin.getLogger();
        enabled = plugin.getConfig().getBoolean("debug.enabled", false);
        summaryIntervalSeconds = Math.max(MIN_SUMMARY_INTERVAL_SECONDS,
                plugin.getConfig().getInt("debug.summary-interval-seconds", 30));
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static int summaryIntervalSeconds() {
        return summaryIntervalSeconds;
    }

    /**
     * Log one debug line. {@code message} is used verbatim when no arguments are supplied, so a
     * literal {@code %} in a message is never reinterpreted.
     */
    public static void log(@NotNull String category, @NotNull String message, @NotNull Object... args) {
        if (!enabled) {
            return;
        }
        String rendered;
        try {
            rendered = args.length == 0 ? message : String.format(Locale.ROOT, message, args);
        } catch (RuntimeException e) {
            // Debug lines are emitted from inside the RTP pipeline and the chunk-load callbacks, so
            // a malformed format string must never escape into the caller.
            rendered = message + " [debug format error: " + e.getMessage() + "]";
        }
        logger.info(PREFIX + "[" + category + "] " + rendered);
    }
}
