package net.sakurain.mc.easytp;

import net.sakurain.mc.easytp.command.*;
import net.sakurain.mc.easytp.gui.GuiListener;
import net.sakurain.mc.easytp.listener.PlayerListener;
import net.sakurain.mc.easytp.manager.TeleportManager;
import net.sakurain.mc.easytp.rtp.RtpEngine;
import net.sakurain.mc.easytp.rtp.scheduler.RtpScheduler;
import net.sakurain.mc.easytp.storage.DatabaseManager;
import net.sakurain.mc.easytp.util.DebugLog;
import net.sakurain.mc.easytp.util.MessageUtil;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * EasyTP plugin main class.
 */
public class EasyTPPlugin extends JavaPlugin {

    private static EasyTPPlugin instance;
    private DatabaseManager databaseManager;
    private RtpScheduler rtpScheduler;
    private RtpEngine rtpEngine;
    private TeleportManager teleportManager;
    private GuiListener guiListener;

    // Snapshot of the settings read only during onEnable, used to report them after a reload.
    private final Map<String, Boolean> startupCommandClassState = new HashMap<>();
    private String startupDatabaseFile;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        reloadConfig();
        migrateLegacyConfig();

        String locale = getConfig().getString("language", "en");
        MessageUtil.load(this, locale);
        // Initialised before the managers so their first log lines already honour the setting.
        DebugLog.init(this);
        snapshotStartupOnlySettings();

        this.databaseManager = new DatabaseManager(this);
        this.databaseManager.initialize();

        this.rtpScheduler = RtpScheduler.create(this);
        this.rtpEngine = new RtpEngine(this, databaseManager, rtpScheduler);
        this.teleportManager = new TeleportManager(this, databaseManager, rtpEngine);
        this.guiListener = new GuiListener(this, teleportManager);

        rtpEngine.start();

        registerCommands();
        getServer().getPluginManager().registerEvents(new PlayerListener(teleportManager), this);
        getServer().getPluginManager().registerEvents(guiListener, this);

        getLogger().info("EasyTP enabled!");
    }

    @Override
    public void onDisable() {
        if (teleportManager != null) {
            teleportManager.shutdown();
        }
        if (rtpScheduler != null) {
            rtpScheduler.cancelAll();
        }
        if (databaseManager != null) {
            databaseManager.close();
        }
        getServer().getScheduler().cancelTasks(this);
        getLogger().info("EasyTP disabled!");
    }

    /**
     * Copy legacy config values into the new commands.* structure if the new keys are missing.
     */
    private void migrateLegacyConfig() {
        FileConfiguration config = getConfig();
        if (!config.contains("commands")) {
            config.createSection("commands");
        }

        migrateCommandConfig(config, "rtp", "rtp.cooldown", 15);
        migrateCommandConfig(config, "home", "home.cooldown", 0);
        migrateCommandConfig(config, "tpa", "tpa.cooldown", 15);
        migrateCommandConfig(config, "tphere", "tphere.cooldown", 15);

        // Legacy global delay becomes the default for each command if not already set
        if (config.contains("teleport.delay")) {
            int legacyDelay = config.getInt("teleport.delay", 5);
            for (String key : new String[]{"rtp", "home", "tpa", "tphere"}) {
                if (!config.contains("commands." + key + ".delay")) {
                    config.set("commands." + key + ".delay", legacyDelay);
                }
            }
        }

        saveConfig();
    }

    private void migrateCommandConfig(@NotNull FileConfiguration config, @NotNull String commandKey, @NotNull String legacyKey, int defaultValue) {
        if (!config.contains("commands." + commandKey + ".cooldown") && config.contains(legacyKey)) {
            config.set("commands." + commandKey + ".cooldown", config.getInt(legacyKey, defaultValue));
        }
    }

    /**
     * Register the commands of every enabled {@link CommandClass}. A disabled class has its
     * commands unregistered, so they are invisible to players and tab completion.
     */
    private void registerCommands() {
        for (CommandClass commandClass : CommandClass.values()) {
            if (commandClass.isEnabled(this)) {
                registerCommandClass(commandClass);
            } else {
                commandClass.unregisterAll(this);
                getLogger().info("Command class '" + commandClass.configKey() + "' is disabled; not registering: "
                        + String.join(", ", commandClass.commands()));
            }
        }

        // Administrative commands are always registered; access is controlled by permission.
        EasyTPCommand adminCommand = new EasyTPCommand(this);
        setExecutor("easytp", adminCommand);
        setTabCompleter("easytp", adminCommand);
    }

    private void registerCommandClass(@NotNull CommandClass commandClass) {
        switch (commandClass) {
            case RTP -> {
                setExecutor("rtp", new RtpCommand(teleportManager));
                setTabCompleter("rtp", new RtpTabCompleter(teleportManager));
            }
            case PLAYER_TELEPORT -> {
                setExecutor("tpa", new TpaCommand(teleportManager));
                setExecutor("tphere", new TpHereCommand(teleportManager));
                setExecutor("tpaccept", new TpAcceptCommand(teleportManager));
                setExecutor("tpdeny", new TpDenyCommand(teleportManager));
            }
            case HOME -> {
                setExecutor("home", new HomeCommand(teleportManager));
                setTabCompleter("home", new HomeTabCompleter(teleportManager));
                setExecutor("sethome", new SetHomeCommand(teleportManager));
                setExecutor("delhome", new DelHomeCommand(teleportManager));
                setTabCompleter("delhome", new HomeTabCompleter(teleportManager));
                setExecutor("homelist", new HomeListCommand(teleportManager, guiListener));
            }
        }
    }

    private void setExecutor(@NotNull String name, @NotNull CommandExecutor executor) {
        Objects.requireNonNull(getCommand(name), "Command not declared in plugin.yml: " + name).setExecutor(executor);
    }

    private void setTabCompleter(@NotNull String name, @NotNull TabCompleter completer) {
        Objects.requireNonNull(getCommand(name), "Command not declared in plugin.yml: " + name).setTabCompleter(completer);
    }

    /**
     * Outcome of {@link #reloadConfiguration()}.
     *
     * @param applied         whether the new configuration was applied
     * @param restartRequired settings that changed but only take effect after a restart
     */
    public record ReloadResult(boolean applied, @NotNull List<String> restartRequired) {

        static ReloadResult aborted() {
            return new ReloadResult(false, List.of());
        }
    }

    /**
     * Re-read {@code config.yml} and the language files, then push the new values into the
     * managers.
     *
     * <p>The file is parsed before anything is applied. A malformed {@code config.yml} otherwise
     * makes {@code YamlConfiguration} fall back to defaults silently, which would quietly reset
     * every setting instead of reporting the problem.</p>
     *
     * @return the outcome, including settings that still require a restart
     */
    @NotNull
    public ReloadResult reloadConfiguration() {
        if (!isConfigFileParsable()) {
            return ReloadResult.aborted();
        }

        reloadConfig();
        MessageUtil.load(this, getConfig().getString("language", "en"));
        // Re-read the debug switches so /easytp reload can turn diagnostics on or off live.
        DebugLog.init(this);
        rtpEngine.reload();

        List<String> restartRequired = detectRestartOnlyChanges();
        getLogger().info(restartRequired.isEmpty()
                ? "EasyTP configuration reloaded."
                : "EasyTP configuration reloaded; restart required for: " + String.join(", ", restartRequired));
        DebugLog.log("reload", "debug.enabled=%s summaryInterval=%ds restartRequired=%s",
                DebugLog.isEnabled(), DebugLog.summaryIntervalSeconds(), restartRequired);
        return new ReloadResult(true, restartRequired);
    }

    /**
     * Record the settings that are only read during {@code onEnable}, so a later reload can
     * report changes to them instead of silently ignoring those changes.
     */
    private void snapshotStartupOnlySettings() {
        startupCommandClassState.clear();
        for (CommandClass commandClass : CommandClass.values()) {
            startupCommandClassState.put(commandClass.configKey(), commandClass.isEnabled(this));
        }
        startupDatabaseFile = getConfig().getString("database.file", "data.db");
    }

    /**
     * Compare the current configuration against the startup snapshot and list what cannot be
     * hot-reloaded. Command classes are (un)registered once in {@code onEnable}, and the SQLite
     * connection is already open, so neither can change while the server runs.
     */
    @NotNull
    private List<String> detectRestartOnlyChanges() {
        List<String> changed = new ArrayList<>();
        for (CommandClass commandClass : CommandClass.values()) {
            Boolean wasEnabled = startupCommandClassState.get(commandClass.configKey());
            if (wasEnabled != null && wasEnabled != commandClass.isEnabled(this)) {
                changed.add("commands." + commandClass.configKey() + ".enable");
            }
        }
        String databaseFile = getConfig().getString("database.file", "data.db");
        if (startupDatabaseFile != null && !startupDatabaseFile.equals(databaseFile)) {
            changed.add("database.file");
        }
        return changed;
    }

    /**
     * Validate {@code config.yml} by parsing it directly, so a syntax error aborts the reload
     * instead of silently reverting every setting to its default.
     */
    private boolean isConfigFileParsable() {
        File file = new File(getDataFolder(), "config.yml");
        if (!file.isFile()) {
            return true;
        }
        try {
            YamlConfiguration probe = new YamlConfiguration();
            probe.loadFromString(Files.readString(file.toPath(), StandardCharsets.UTF_8));
            return true;
        } catch (IOException | InvalidConfigurationException e) {
            getLogger().severe("config.yml could not be parsed; reload aborted: " + e.getMessage());
            return false;
        }
    }

    public static EasyTPPlugin getInstance() {
        return instance;
    }

    public TeleportManager getTeleportManager() {
        return teleportManager;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public GuiListener getGuiListener() {
        return guiListener;
    }
}
