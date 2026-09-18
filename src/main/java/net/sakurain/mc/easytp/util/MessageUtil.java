package net.sakurain.mc.easytp.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.sakurain.mc.easytp.EasyTPPlugin;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Message utility that loads localized MiniMessage strings from language files.
 */
public final class MessageUtil {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final Map<String, String> MESSAGES = new HashMap<>();
    private static final String DEFAULT_LOCALE = "en";
    private static String prefix = "";

    private MessageUtil() {
        // utility class
    }

    /**
     * Load messages for the configured locale.
     * If the locale file does not exist in the plugin folder, it is copied from the jar resources.
     * Missing keys are filled from the default English file bundled in the jar.
     *
     * @param plugin the plugin instance
     * @param locale the locale code, e.g. "en" or "zh"
     */
    public static void load(@NotNull EasyTPPlugin plugin, @NotNull String locale) {
        MESSAGES.clear();

        File langFolder = new File(plugin.getDataFolder(), "lang");
        if (!langFolder.exists()) {
            langFolder.mkdirs();
        }

        String fileName = "messages_" + locale + ".yml";
        File localeFile = new File(langFolder, fileName);

        // Copy from jar if missing
        if (!localeFile.exists()) {
            InputStream resource = plugin.getResource("lang/" + fileName);
            if (resource != null) {
                plugin.saveResource("lang/" + fileName, false);
            }
        }

        FileConfiguration config;
        if (localeFile.exists()) {
            config = YamlConfiguration.loadConfiguration(localeFile);
        } else {
            config = loadDefaultFromJar(plugin, locale);
            if (config == null) {
                config = loadDefaultFromJar(plugin, DEFAULT_LOCALE);
            }
        }

        prefix = config.getString("messages.prefix", "");

        // Load all keys from default English, then override with the selected locale
        FileConfiguration defaults = loadDefaultFromJar(plugin, DEFAULT_LOCALE);
        if (defaults != null && defaults.contains("messages") && defaults.isConfigurationSection("messages")) {
            org.bukkit.configuration.ConfigurationSection defaultMessages = defaults.getConfigurationSection("messages");
            for (String key : defaultMessages.getKeys(true)) {
                if (!defaultMessages.isString(key)) {
                    continue;
                }
                String fullKey = "messages." + key;
                String value = config.contains(fullKey) ? config.getString(fullKey, "") : defaultMessages.getString(key, "");
                MESSAGES.put(key, value);
            }
        } else if (config.contains("messages") && config.isConfigurationSection("messages")) {
            for (String key : config.getConfigurationSection("messages").getKeys(true)) {
                if (config.isString("messages." + key)) {
                    MESSAGES.put(key, config.getString("messages." + key));
                }
            }
        }
    }

    /**
     * Load a default language file directly from the plugin jar.
     *
     * @param plugin the plugin instance
     * @param locale the locale code
     * @return the file configuration, or null if not found
     */
    @org.jetbrains.annotations.Nullable
    private static FileConfiguration loadDefaultFromJar(@NotNull EasyTPPlugin plugin, @NotNull String locale) {
        String fileName = "lang/messages_" + locale + ".yml";
        InputStream resource = plugin.getResource(fileName);
        if (resource == null) {
            return null;
        }
        try (InputStreamReader reader = new InputStreamReader(resource, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not read default language file " + fileName + ": " + e.getMessage());
            return null;
        }
    }

    @NotNull
    public static String getRaw(@NotNull String key) {
        return MESSAGES.getOrDefault(key, key);
    }

    public static void send(@NotNull CommandSender sender, @NotNull String key, @NotNull TagResolver... resolvers) {
        String raw = getRaw(key);
        if (raw.isEmpty()) {
            return;
        }
        sender.sendMessage(MINI_MESSAGE.deserialize(prefix + raw, resolvers));
    }

    public static void send(@NotNull CommandSender sender, @NotNull String key, @NotNull String playerName) {
        send(sender, key, Placeholder.unparsed("player", playerName));
    }

    public static void send(@NotNull CommandSender sender, @NotNull String key, int value) {
        send(sender, key, Placeholder.unparsed("seconds", String.valueOf(value)));
    }

    public static void sendComponent(@NotNull CommandSender sender, @NotNull Component component) {
        sender.sendMessage(component);
    }

    @NotNull
    public static Component parse(@NotNull String key, @NotNull TagResolver... resolvers) {
        return MINI_MESSAGE.deserialize(prefix + getRaw(key), resolvers);
    }

    /**
     * Parse a raw MiniMessage string without adding the configured prefix.
     *
     * @param raw       the raw MiniMessage string
     * @param resolvers optional tag resolvers
     * @return the parsed component
     */
    @NotNull
    public static Component parseRaw(@NotNull String raw, @NotNull TagResolver... resolvers) {
        return MINI_MESSAGE.deserialize(raw, resolvers);
    }

    /**
     * Parse a message key without adding the configured prefix.
     * Useful for inventory titles and item names.
     *
     * @param key       the message key
     * @param resolvers optional tag resolvers
     * @return the parsed component
     */
    @NotNull
    public static Component parseNoPrefix(@NotNull String key, @NotNull TagResolver... resolvers) {
        return MINI_MESSAGE.deserialize(getRaw(key), resolvers);
    }

    @NotNull
    public static TagResolver.Single coord(@NotNull String name, double value) {
        return Placeholder.unparsed(name, String.valueOf(Math.round(value)));
    }

    /**
     * Localized display name for a world environment, as a component so it can be embedded in
     * another message with {@link Placeholder#component(String, Component)}.
     *
     * <p>The values in the language files are deliberately plain text (no colour tags): the
     * surrounding message template decides how the name is styled.</p>
     *
     * @param environment the world environment
     * @return the localized dimension name, or the enum name for custom environments
     */
    @NotNull
    public static Component dimensionName(@NotNull World.Environment environment) {
        String key = switch (environment) {
            case NORMAL -> "dimension-overworld";
            case NETHER -> "dimension-nether";
            case THE_END -> "dimension-the-end";
            default -> null;
        };
        if (key == null) {
            return Component.text(environment.name());
        }
        return parseNoPrefix(key);
    }
}
