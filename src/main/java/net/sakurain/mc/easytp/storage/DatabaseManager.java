package net.sakurain.mc.easytp.storage;

import net.sakurain.mc.easytp.EasyTPPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

/**
 * Manages the SQLite database connection, schema, and migration from legacy homes.yml.
 *
 * <p>The plugin keeps a single long-lived JDBC connection. Callers must never close it;
 * every statement goes through {@link #withConnection(SqlAction)} or
 * {@link #runWithConnection(SqlCommand)}, which serialise access with a reentrant lock.
 * This keeps the main thread, the Bukkit async pool, and the RTP storage writer thread
 * from interleaving statements on the same connection.</p>
 */
public class DatabaseManager {

    /**
     * A statement body that produces a value while holding the shared connection lock.
     */
    @FunctionalInterface
    public interface SqlAction<T> {
        T run(@NotNull Connection connection) throws SQLException;
    }

    /**
     * A statement body that produces no value while holding the shared connection lock.
     */
    @FunctionalInterface
    public interface SqlCommand {
        void run(@NotNull Connection connection) throws SQLException;
    }

    private final EasyTPPlugin plugin;
    private final File databaseFile;
    private final ReentrantLock lock = new ReentrantLock();
    private Connection connection;

    public DatabaseManager(@NotNull EasyTPPlugin plugin) {
        this.plugin = plugin;
        String fileName = plugin.getConfig().getString("database.file", "data.db");
        this.databaseFile = new File(plugin.getDataFolder(), fileName);
    }

    /**
     * Initialize the database connection, create tables, and migrate legacy data if needed.
     */
    public void initialize() {
        lock.lock();
        try {
            Class.forName("org.sqlite.JDBC");
            this.connection = openConnection();
            createTables();
            migrateLegacyHomes();
        } catch (ClassNotFoundException e) {
            plugin.getLogger().severe("SQLite JDBC driver not found: " + e.getMessage());
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not connect to SQLite database: " + e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Run a statement body against the shared connection while holding its lock.
     *
     * <p>The lock is reentrant, so nested calls from the same thread are safe.</p>
     *
     * @param action the statement body
     * @param <T>    the produced value type
     * @return the value produced by the action
     * @throws SQLException if a database error occurs
     */
    public <T> T withConnection(@NotNull SqlAction<T> action) throws SQLException {
        lock.lock();
        try {
            return action.run(ensureConnection());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Run a statement body against the shared connection while holding its lock.
     *
     * @param command the statement body
     * @throws SQLException if a database error occurs
     */
    public void runWithConnection(@NotNull SqlCommand command) throws SQLException {
        lock.lock();
        try {
            command.run(ensureConnection());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Return the plugin logger, for collaborators that persist data on background threads.
     *
     * @return the plugin logger
     */
    @NotNull
    public Logger getLogger() {
        return plugin.getLogger();
    }

    /**
     * Close the database connection.
     */
    public void close() {
        lock.lock();
        try {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException e) {
                    plugin.getLogger().warning("Error closing database: " + e.getMessage());
                } finally {
                    connection = null;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Return an open shared connection. Must be called while holding {@link #lock}.
     */
    @NotNull
    private Connection ensureConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            connection = openConnection();
        }
        return connection;
    }

    @NotNull
    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + databaseFile.getAbsolutePath());
    }

    private void createTables() throws SQLException {
        runWithConnection(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS easytp_homes (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            player_uuid TEXT NOT NULL,
                            home_name TEXT NOT NULL,
                            world TEXT NOT NULL,
                            x REAL NOT NULL,
                            y REAL NOT NULL,
                            z REAL NOT NULL,
                            yaw REAL NOT NULL,
                            pitch REAL NOT NULL,
                            created_at INTEGER NOT NULL,
                            updated_at INTEGER NOT NULL,
                            UNIQUE(player_uuid, home_name)
                        )
                        """);
                statement.execute("CREATE INDEX IF NOT EXISTS idx_homes_player ON easytp_homes(player_uuid)");
            }
        });
    }

    private void migrateLegacyHomes() {
        File legacyFile = new File(plugin.getDataFolder(), "homes.yml");
        if (!legacyFile.exists()) {
            return;
        }

        FileConfiguration legacy = YamlConfiguration.loadConfiguration(legacyFile);
        if (legacy.getKeys(false).isEmpty()) {
            return;
        }

        HomeRepository repository = new HomeRepository(this);
        long now = System.currentTimeMillis();
        int count = 0;

        for (String uuidString : legacy.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(uuidString);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Skipping invalid UUID in homes.yml: " + uuidString);
                continue;
            }

            ConfigurationSection playerSection = legacy.getConfigurationSection(uuidString);
            if (playerSection == null) {
                continue;
            }

            for (String homeName : playerSection.getKeys(false)) {
                ConfigurationSection homeSection = playerSection.getConfigurationSection(homeName);
                if (homeSection == null) {
                    continue;
                }

                String world = homeSection.getString("world");
                double x = homeSection.getDouble("x");
                double y = homeSection.getDouble("y");
                double z = homeSection.getDouble("z");
                float yaw = (float) homeSection.getDouble("yaw");
                float pitch = (float) homeSection.getDouble("pitch");

                if (world == null) {
                    continue;
                }

                HomeData data = new HomeData(uuid, homeName, world, x, y, z, yaw, pitch);
                try {
                    repository.save(data);
                    count++;
                } catch (SQLException e) {
                    plugin.getLogger().warning("Failed to migrate home " + homeName + " for " + uuid + ": " + e.getMessage());
                }
            }
        }

        plugin.getLogger().info("Migrated " + count + " homes from homes.yml to SQLite.");

        File migratedFile = new File(plugin.getDataFolder(), "homes.yml.migrated");
        if (migratedFile.exists()) {
            migratedFile.delete();
        }
        if (!legacyFile.renameTo(migratedFile)) {
            plugin.getLogger().warning("Could not rename homes.yml to homes.yml.migrated; deleting instead.");
            legacyFile.delete();
        }
    }
}
