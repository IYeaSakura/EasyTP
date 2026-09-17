package net.sakurain.mc.easytp.storage;

import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Repository for player home CRUD operations backed by SQLite.
 *
 * <p>Statements run through {@link DatabaseManager#withConnection} /
 * {@link DatabaseManager#runWithConnection}, so this class never closes the shared
 * connection and never races the RTP storage writer thread.</p>
 */
public class HomeRepository {

    private final DatabaseManager databaseManager;

    public HomeRepository(@NotNull DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    /**
     * Save or replace a home. Uses INSERT OR REPLACE to update existing homes.
     *
     * @param home the home data to persist
     * @throws SQLException if a database error occurs
     */
    public void save(@NotNull HomeData home) throws SQLException {
        String sql = """
                INSERT OR REPLACE INTO easytp_homes
                (player_uuid, home_name, world, x, y, z, yaw, pitch, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, COALESCE((SELECT created_at FROM easytp_homes WHERE player_uuid = ? AND home_name = ?), ?), ?)
                """;
        long now = System.currentTimeMillis();
        databaseManager.runWithConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, home.playerUuid().toString());
                statement.setString(2, home.name());
                statement.setString(3, home.worldName());
                statement.setDouble(4, home.x());
                statement.setDouble(5, home.y());
                statement.setDouble(6, home.z());
                statement.setFloat(7, home.yaw());
                statement.setFloat(8, home.pitch());
                statement.setString(9, home.playerUuid().toString());
                statement.setString(10, home.name());
                statement.setLong(11, now);
                statement.setLong(12, now);
                statement.executeUpdate();
            }
        });
    }

    /**
     * Delete a home by player UUID and name.
     *
     * @param playerUuid the owning player
     * @param name       the home name
     * @throws SQLException if a database error occurs
     */
    public void delete(@NotNull UUID playerUuid, @NotNull String name) throws SQLException {
        String sql = "DELETE FROM easytp_homes WHERE player_uuid = ? AND home_name = ?";
        databaseManager.runWithConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, playerUuid.toString());
                statement.setString(2, name);
                statement.executeUpdate();
            }
        });
    }

    /**
     * Find a single home by player UUID and name.
     *
     * @param playerUuid the owning player
     * @param name       the home name
     * @return the home data, or null if not found
     * @throws SQLException if a database error occurs
     */
    @Nullable
    public HomeData find(@NotNull UUID playerUuid, @NotNull String name) throws SQLException {
        String sql = "SELECT world, x, y, z, yaw, pitch FROM easytp_homes WHERE player_uuid = ? AND home_name = ?";
        return databaseManager.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, playerUuid.toString());
                statement.setString(2, name);
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) {
                        return new HomeData(
                                playerUuid,
                                name,
                                result.getString("world"),
                                result.getDouble("x"),
                                result.getDouble("y"),
                                result.getDouble("z"),
                                result.getFloat("yaw"),
                                result.getFloat("pitch")
                        );
                    }
                }
            }
            return null;
        });
    }

    /**
     * List all homes for a player, sorted alphabetically by name.
     *
     * @param playerUuid the owning player
     * @return a list of home data
     * @throws SQLException if a database error occurs
     */
    @NotNull
    public List<HomeData> findByPlayer(@NotNull UUID playerUuid) throws SQLException {
        String sql = "SELECT home_name, world, x, y, z, yaw, pitch FROM easytp_homes WHERE player_uuid = ? ORDER BY home_name COLLATE NOCASE";
        return databaseManager.withConnection(connection -> {
            List<HomeData> homes = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, playerUuid.toString());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        homes.add(new HomeData(
                                playerUuid,
                                result.getString("home_name"),
                                result.getString("world"),
                                result.getDouble("x"),
                                result.getDouble("y"),
                                result.getDouble("z"),
                                result.getFloat("yaw"),
                                result.getFloat("pitch")
                        ));
                    }
                }
            }
            return homes;
        });
    }

    /**
     * Count how many homes a player has.
     *
     * @param playerUuid the owning player
     * @return the number of homes
     * @throws SQLException if a database error occurs
     */
    public int countByPlayer(@NotNull UUID playerUuid) throws SQLException {
        String sql = "SELECT COUNT(*) FROM easytp_homes WHERE player_uuid = ?";
        return databaseManager.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, playerUuid.toString());
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) {
                        return result.getInt(1);
                    }
                }
            }
            return 0;
        });
    }

    /**
     * Rename a home.
     *
     * @param playerUuid the owning player
     * @param oldName    the current name
     * @param newName    the desired name
     * @throws SQLException if a database error occurs
     */
    public void rename(@NotNull UUID playerUuid, @NotNull String oldName, @NotNull String newName) throws SQLException {
        String sql = "UPDATE easytp_homes SET home_name = ?, updated_at = ? WHERE player_uuid = ? AND home_name = ?";
        databaseManager.runWithConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, newName);
                statement.setLong(2, System.currentTimeMillis());
                statement.setString(3, playerUuid.toString());
                statement.setString(4, oldName);
                statement.executeUpdate();
            }
        });
    }

    /**
     * Update a home's location to the given location.
     *
     * @param playerUuid the owning player
     * @param name       the home name
     * @param location   the new location
     * @throws SQLException if a database error occurs
     */
    public void updateLocation(@NotNull UUID playerUuid, @NotNull String name, @NotNull Location location) throws SQLException {
        if (location.getWorld() == null) {
            throw new IllegalArgumentException("Location must have a world");
        }
        String sql = "UPDATE easytp_homes SET world = ?, x = ?, y = ?, z = ?, yaw = ?, pitch = ?, updated_at = ? WHERE player_uuid = ? AND home_name = ?";
        databaseManager.runWithConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, location.getWorld().getName());
                statement.setDouble(2, location.getX());
                statement.setDouble(3, location.getY());
                statement.setDouble(4, location.getZ());
                statement.setFloat(5, location.getYaw());
                statement.setFloat(6, location.getPitch());
                statement.setLong(7, System.currentTimeMillis());
                statement.setString(8, playerUuid.toString());
                statement.setString(9, name);
                statement.executeUpdate();
            }
        });
    }
}
