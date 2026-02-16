package com.ecotale.storage;

import com.ecotale.Main;
import com.ecotale.economy.PlayerBalance;
import com.ecotale.economy.TopBalanceEntry;
import com.ecotale.economy.TransactionEntry;
import com.ecotale.economy.TransactionType;
import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nonnull;
import java.io.File;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;


public class H2StorageProvider implements StorageProvider {
    
    private static final String DB_NAME = "ecotale";
    private static final HytaleLogger LOGGER = HytaleLogger.getLogger().getSubLogger("Ecotale-H2");
    

    private static final Path ECOTALE_PATH = Path.of("mods", "Ecotale_Ecotale");
    
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Ecotale-H2-IO");
        t.setDaemon(false); // Must be non-daemon to ensure tasks complete during shutdown
        return t;
    });
    
    private Connection connection;
    private String dbPath;
    private int playerCount = 0;
    
    @Override
    public CompletableFuture<Void> initialize() {
        return CompletableFuture.runAsync(() -> {
            try {
                // Create data directory in universe/Ecotale/h2/
                File dataDir = ECOTALE_PATH.toFile();
                if (!dataDir.exists()) {
                    dataDir.mkdirs();
                }
                
                dbPath = new File(dataDir, DB_NAME).getAbsolutePath();
                
                // Explicitly register H2 driver (needed due to classloader issues)
                try {
                    Class.forName("org.h2.Driver");
                } catch (ClassNotFoundException e) {
                    LOGGER.at(Level.SEVERE).log("H2 Driver class not found: %s", e.getMessage());
                    throw new RuntimeException("H2 Driver not available", e);
                }
                
                // Connect to H2 (creates file if not exists)
                connection = DriverManager.getConnection(
                    "jdbc:h2:" + dbPath + ";MODE=MySQL;AUTO_SERVER=FALSE;DB_CLOSE_ON_EXIT=FALSE",
                    "sa", ""
                );
                
                // Create tables
                createTables();
                
                // Count existing players
                try (Statement stmt = connection.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM balances")) {
                    if (rs.next()) {
                        playerCount = rs.getInt(1);
                    }
                }
                
                LOGGER.at(Level.INFO).log("H2 database initialized: %s.mv.db (%d players)", dbPath, playerCount);
                
            } catch (SQLException e) {
                LOGGER.at(Level.SEVERE).log("Failed to initialize H2 database: %s", e.getMessage());
                throw new RuntimeException(e);
            }
        }, executor);
    }
    
    private void createTables() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            // Balances table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS balances (
                    uuid VARCHAR(36) PRIMARY KEY,
                    player_name VARCHAR(64),
                    balance DOUBLE DEFAULT 0.0,
                    total_earned DOUBLE DEFAULT 0.0,
                    total_spent DOUBLE DEFAULT 0.0,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
            """);
            
            // Migration: Add player_name column if missing (for existing databases)
            try {
                stmt.execute("ALTER TABLE balances ADD COLUMN IF NOT EXISTS player_name VARCHAR(64)");
            } catch (SQLException ignored) {
                // Column already exists or syntax not supported
            }
            
            // Migration: Add hud_visible column for player HUD preferences
            try {
                stmt.execute("ALTER TABLE balances ADD COLUMN IF NOT EXISTS hud_visible BOOLEAN DEFAULT TRUE");
            } catch (SQLException ignored) {
                // Column already exists or syntax not supported
            }
            
            // Transactions table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS transactions (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    timestamp BIGINT NOT NULL,
                    type VARCHAR(20) NOT NULL,
                    source_uuid VARCHAR(36),
                    target_uuid VARCHAR(36),
                    player_name VARCHAR(64),
                    amount DOUBLE,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
            """);
            
            // Create indexes if not exist
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_tx_timestamp ON transactions(timestamp DESC)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_tx_player ON transactions(player_name)");

            // Balance snapshots table (for weekly/monthly trends)
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS balance_snapshots (
                    snap_day DATE NOT NULL,
                    uuid VARCHAR(36) NOT NULL,
                    balance DOUBLE DEFAULT 0.0,
                    PRIMARY KEY(snap_day, uuid)
                )
            """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_snap_day ON balance_snapshots(snap_day)");
        }
    }
    @Override
    public CompletableFuture<PlayerBalance> loadPlayer(@Nonnull UUID playerUuid) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String sql = "SELECT balance, total_earned, total_spent FROM balances WHERE uuid = ?";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, playerUuid.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            PlayerBalance pb = new PlayerBalance(playerUuid);
                            // Use setBalance to set the loaded balance
                            pb.setBalance(rs.getDouble("balance"), "Loaded from DB");
                            return pb;
                        }
                    }
                }
                
                // Create new account with starting balance
                double startingBalance = Main.CONFIG.get().getStartingBalance();
                PlayerBalance newBalance = new PlayerBalance(playerUuid);
                newBalance.setBalance(startingBalance, "New account");
                savePlayerSync(playerUuid, newBalance);
                playerCount++;
                return newBalance;
                
            } catch (SQLException e) {
                LOGGER.at(Level.SEVERE).log("Failed to load player %s: %s", playerUuid, e.getMessage());
                // Return default balance on error
                PlayerBalance pb = new PlayerBalance(playerUuid);
                pb.setBalance(Main.CONFIG.get().getStartingBalance(), "Error fallback");
                return pb;
            }
        }, executor);
    }
    
    @Override
    public CompletableFuture<Void> savePlayer(@Nonnull UUID playerUuid, @Nonnull PlayerBalance balance) {
        return CompletableFuture.runAsync(() -> savePlayerSync(playerUuid, balance), executor);
    }
    
    private void savePlayerSync(UUID playerUuid, PlayerBalance balance) {
        try {
            String sql = """
                MERGE INTO balances (uuid, balance, total_earned, total_spent, updated_at) 
                KEY(uuid) 
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
            """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, playerUuid.toString());
                ps.setDouble(2, balance.getBalance());
                ps.setDouble(3, balance.getTotalEarned());
                ps.setDouble(4, balance.getTotalSpent());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Failed to save player %s: %s", playerUuid, e.getMessage());
        }
    }
    
    /**
     * Update player's cached name.
     * Call this on player join to keep names current.
     */
    public void updatePlayerName(@Nonnull UUID playerUuid, @Nonnull String playerName) {
        CompletableFuture.runAsync(() -> {
            try {
                String sql = "UPDATE balances SET player_name = ? WHERE uuid = ?";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, playerName);
                    ps.setString(2, playerUuid.toString());
                    int updated = ps.executeUpdate();
                    
                    // If no row was updated, insert a new one with just the name
                    if (updated == 0) {
                        String insertSql = """
                            INSERT INTO balances (uuid, player_name, balance) 
                            VALUES (?, ?, ?)
                        """;
                        try (PreparedStatement insertPs = connection.prepareStatement(insertSql)) {
                            insertPs.setString(1, playerUuid.toString());
                            insertPs.setString(2, playerName);
                            insertPs.setDouble(3, com.ecotale.Main.CONFIG.get().getStartingBalance());
                            insertPs.executeUpdate();
                        }
                    }
                }
            } catch (SQLException e) {
                LOGGER.at(Level.WARNING).log("Failed to update player name: %s", e.getMessage());
            }
        }, executor);
    }
    
    /**
     * Get cached player name from database (async).
     * Returns null if not found.
     */
    public CompletableFuture<String> getPlayerNameAsync(@Nonnull UUID playerUuid) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String sql = "SELECT player_name FROM balances WHERE uuid = ?";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, playerUuid.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            return rs.getString("player_name");
                        }
                    }
                }
            } catch (SQLException e) {
                LOGGER.at(Level.WARNING).log("Failed to get player name: %s", e.getMessage());
            }
            return null;
        }, executor);
    }

    /**
     * Look up a player's UUID by name (async, case-insensitive).
     */
    public CompletableFuture<UUID> getPlayerUuidByName(@Nonnull String playerName) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String sql = "SELECT uuid FROM balances WHERE LOWER(player_name) = ? LIMIT 1";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, playerName.toLowerCase());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            return UUID.fromString(rs.getString("uuid"));
                        }
                    }
                }
            } catch (SQLException e) {
                LOGGER.at(Level.WARNING).log("Failed to get UUID by name: %s", e.getMessage());
            }
            return null;
        }, executor);
    }

    /**
     * Get all player names from database (sync).
     * Used by Admin GUI to display player names for offline players.
     * @return Map of UUID to player name (null names are excluded)
     */
    public Map<UUID, String> getAllPlayerNamesSync() {
        Map<UUID, String> result = new HashMap<>();
        try {
            String sql = "SELECT uuid, player_name FROM balances WHERE player_name IS NOT NULL";
            try (PreparedStatement ps = connection.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String uuidStr = rs.getString("uuid");
                    String name = rs.getString("player_name");
                    if (name != null && !name.isBlank()) {
                        result.put(UUID.fromString(uuidStr), name);
                    }
                }
            }
        } catch (SQLException e) {
            LOGGER.at(Level.WARNING).log("Failed to get all player names: %s", e.getMessage());
        }
        return result;
    }
    /**
     * Get player's HUD visibility preference (async).
     * Returns true if no record exists.
     */
    @Override
    public CompletableFuture<Boolean> getHudVisible(@Nonnull UUID playerUuid) {
        return CompletableFuture.supplyAsync(() -> getHudVisibleSync(playerUuid), executor);
    }
    
    /**
     * Get player's HUD visibility preference (sync).
     * Used during player join event to avoid async delay.
     */
    public boolean getHudVisibleSync(@Nonnull UUID playerUuid) {
        try {
            String sql = "SELECT hud_visible FROM balances WHERE uuid = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, playerUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        // Handle null (old records before migration)
                        Boolean visible = rs.getObject("hud_visible", Boolean.class);
                        return visible == null || visible;
                    }
                }
            }
        } catch (SQLException e) {
            LOGGER.at(Level.WARNING).log("Failed to get HUD visibility for %s: %s", playerUuid, e.getMessage());
        }
        return true; // Default visible
    }
    
    /**
     * Set player's HUD visibility preference (async).
     */
    @Override
    public CompletableFuture<Void> setHudVisible(@Nonnull UUID playerUuid, boolean visible) {
        return CompletableFuture.runAsync(() -> {
            try {
                String sql = "UPDATE balances SET hud_visible = ? WHERE uuid = ?";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setBoolean(1, visible);
                    ps.setString(2, playerUuid.toString());
                    int updated = ps.executeUpdate();
                    
                    // If player has no record yet, insert one
                    if (updated == 0) {
                        String insertSql = """
                            INSERT INTO balances (uuid, balance, hud_visible)
                            VALUES (?, ?, ?)
                        """;
                        try (PreparedStatement insertPs = connection.prepareStatement(insertSql)) {
                            insertPs.setString(1, playerUuid.toString());
                            insertPs.setDouble(2, Main.CONFIG.get().getStartingBalance());
                            insertPs.setBoolean(3, visible);
                            insertPs.executeUpdate();
                        }
                    }
                }
            } catch (SQLException e) {
                LOGGER.at(Level.WARNING).log("Failed to set HUD visibility for %s: %s", playerUuid, e.getMessage());
            }
        }, executor);
    }

    /**
     * Get top balances directly from DB (async).
     */
    public CompletableFuture<List<PlayerBalance>> getTopBalances(int limit) {
        return CompletableFuture.supplyAsync(() -> {
            List<PlayerBalance> result = new ArrayList<>();
            try {
                String sql = "SELECT uuid, balance, total_earned, total_spent FROM balances ORDER BY balance DESC LIMIT ?";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setInt(1, limit);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            UUID uuid = UUID.fromString(rs.getString("uuid"));
                            PlayerBalance pb = new PlayerBalance(uuid);
                            pb.setBalance(rs.getDouble("balance"), "Top query");
                            result.add(pb);
                        }
                    }
                }
            } catch (SQLException e) {
                LOGGER.at(Level.WARNING).log("Failed to query top balances: %s", e.getMessage());
            }
            return result;
        }, executor);
    }

    /**
     * Query top balances with pagination (async).
     */
    public CompletableFuture<List<TopBalanceEntry>> queryTopBalancesAsync(int limit, int offset) {
        return CompletableFuture.supplyAsync(() -> {
            List<TopBalanceEntry> result = new ArrayList<>();
            try {
                String sql = "SELECT uuid, balance, player_name FROM balances ORDER BY balance DESC LIMIT ? OFFSET ?";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setInt(1, limit);
                    ps.setInt(2, offset);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            UUID uuid = UUID.fromString(rs.getString("uuid"));
                            double balance = rs.getDouble("balance");
                            String name = rs.getString("player_name");
                            result.add(new TopBalanceEntry(uuid, name, balance, 0.0));
                        }
                    }
                }
            } catch (SQLException e) {
                LOGGER.at(Level.WARNING).log("Failed to query top balances: %s", e.getMessage());
            }
            return result;
        }, executor);
    }

    /**
     * Query top balances by trend over a period (async).
     * Trend = current balance - snapshot balance (days ago).
     */
    public CompletableFuture<List<TopBalanceEntry>> queryTopBalancesPeriodAsync(int limit, int offset, int daysAgo) {
        return CompletableFuture.supplyAsync(() -> {
            List<TopBalanceEntry> result = new ArrayList<>();
            try {
                String sql = """
                    SELECT b.uuid, b.balance, b.player_name,
                           (b.balance - COALESCE(s.balance, 0)) AS trend
                    FROM balances b
                                        LEFT JOIN balance_snapshots s
                                            ON s.uuid = b.uuid AND s.snap_day = ?
                    ORDER BY trend DESC
                    LIMIT ? OFFSET ?
                """;
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setDate(1, java.sql.Date.valueOf(LocalDate.now().minusDays(daysAgo)));
                    ps.setInt(2, limit);
                    ps.setInt(3, offset);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            UUID uuid = UUID.fromString(rs.getString("uuid"));
                            double balance = rs.getDouble("balance");
                            String name = rs.getString("player_name");
                            double trend = rs.getDouble("trend");
                            result.add(new TopBalanceEntry(uuid, name, balance, trend));
                        }
                    }
                }
            } catch (SQLException e) {
                LOGGER.at(Level.WARNING).log("Failed to query period balances: %s", e.getMessage());
            }
            return result;
        }, executor);
    }

    /**
     * Snapshot all balances for today (async). Overwrites existing day snapshot.
     */
    public CompletableFuture<Void> snapshotTodayAsync() {
        return snapshotForDateAsync(LocalDate.now());
    }

    public CompletableFuture<Void> snapshotForDateAsync(@Nonnull LocalDate date) {
        return CompletableFuture.runAsync(() -> {
            try {
                String sql = """
                    MERGE INTO balance_snapshots (snap_day, uuid, balance)
                    KEY(snap_day, uuid)
                    SELECT ?, uuid, balance FROM balances
                """;
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setDate(1, java.sql.Date.valueOf(date));
                    ps.executeUpdate();
                }
            } catch (SQLException e) {
                LOGGER.at(Level.WARNING).log("Failed to snapshot balances: %s", e.getMessage());
            }
        }, executor);
    }

    /**
     * Count total players in balances table (async).
     */
    public CompletableFuture<Integer> countPlayersAsync() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String sql = "SELECT COUNT(*) AS total FROM balances";
                try (PreparedStatement ps = connection.prepareStatement(sql);
                     ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt("total");
                    }
                }
            } catch (SQLException e) {
                LOGGER.at(Level.WARNING).log("Failed to count players: %s", e.getMessage());
            }
            return 0;
        }, executor);
    }

    /**
     * Count players with balance greater than the given value (async).
     */
    public CompletableFuture<Integer> countPlayersWithBalanceGreaterAsync(double balance) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String sql = "SELECT COUNT(*) AS total FROM balances WHERE balance > ?";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setDouble(1, balance);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            return rs.getInt("total");
                        }
                    }
                }
            } catch (SQLException e) {
                LOGGER.at(Level.WARNING).log("Failed to count balance rank: %s", e.getMessage());
            }
            return 0;
        }, executor);
    }
    
    /**
     * Get cached player name from database (sync, for backward compat).
     * @deprecated Use getPlayerNameAsync() to avoid potential deadlocks
     */
    @Deprecated
    public String getPlayerName(@Nonnull UUID playerUuid) {
        return getPlayerNameAsync(playerUuid).join();
    }
    
    @Override
    public CompletableFuture<Void> saveAll(@Nonnull Map<UUID, PlayerBalance> dirtyPlayers) {
        return CompletableFuture.runAsync(() -> saveAllSync(dirtyPlayers), executor);
    }
    
    /**
     * Synchronous version of saveAll for use during shutdown.
     * Call this directly from the shutdown thread to avoid executor issues.
     */
    public void saveAllSync(@Nonnull Map<UUID, PlayerBalance> dirtyPlayers) {
        if (dirtyPlayers.isEmpty()) return;
        
        try {
            connection.setAutoCommit(false);
            String sql = """
                MERGE INTO balances (uuid, balance, total_earned, total_spent, updated_at) 
                KEY(uuid) 
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
            """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                for (var entry : dirtyPlayers.entrySet()) {
                    ps.setString(1, entry.getKey().toString());
                    ps.setDouble(2, entry.getValue().getBalance());
                    ps.setDouble(3, entry.getValue().getTotalEarned());
                    ps.setDouble(4, entry.getValue().getTotalSpent());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            connection.commit();
            LOGGER.at(Level.INFO).log("Saved %d player balances to H2", dirtyPlayers.size());
        } catch (SQLException e) {
            try {
                connection.rollback();
            } catch (SQLException ignored) {}
            LOGGER.at(Level.SEVERE).log("Failed to batch save: %s", e.getMessage());
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ignored) {}
        }
    }

    @Override
    public CompletableFuture<Map<UUID, PlayerBalance>> loadAll() {
        return CompletableFuture.supplyAsync(() -> {
            Map<UUID, PlayerBalance> result = new HashMap<>();
            try {
                String sql = "SELECT uuid, balance, total_earned, total_spent FROM balances";
                try (Statement stmt = connection.createStatement();
                     ResultSet rs = stmt.executeQuery(sql)) {
                    while (rs.next()) {
                        UUID uuid = UUID.fromString(rs.getString("uuid"));
                        PlayerBalance pb = new PlayerBalance(uuid);
                        pb.setBalance(rs.getDouble("balance"), "Bulk load");
                        result.put(uuid, pb);
                    }
                }
                playerCount = result.size();
            } catch (SQLException e) {
                LOGGER.at(Level.SEVERE).log("Failed to load all balances: %s", e.getMessage());
            }
            return result;
        }, executor);
    }
    
    @Override
    public CompletableFuture<Boolean> playerExists(@Nonnull UUID playerUuid) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String sql = "SELECT 1 FROM balances WHERE uuid = ?";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, playerUuid.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next();
                    }
                }
            } catch (SQLException e) {
                return false;
            }
        }, executor);
    }
    
    @Override
    public CompletableFuture<Void> deletePlayer(@Nonnull UUID playerUuid) {
        return CompletableFuture.runAsync(() -> {
            try {
                String sql = "DELETE FROM balances WHERE uuid = ?";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, playerUuid.toString());
                    int affected = ps.executeUpdate();
                    if (affected > 0) playerCount--;
                }
            } catch (SQLException e) {
                LOGGER.at(Level.SEVERE).log("Failed to delete player %s: %s", playerUuid, e.getMessage());
            }
        }, executor);
    }
    /**
     * Log a transaction to the database.
     * Called asynchronously to avoid blocking economy operations.
     */
    public void logTransaction(TransactionEntry entry) {
        executor.execute(() -> {
            try {
                String sql = """
                    INSERT INTO transactions (timestamp, type, source_uuid, target_uuid, player_name, amount)
                    VALUES (?, ?, ?, ?, ?, ?)
                """;
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setLong(1, entry.timestamp().toEpochMilli());
                    ps.setString(2, entry.type().name());
                    ps.setString(3, entry.sourcePlayer() != null ? entry.sourcePlayer().toString() : null);
                    ps.setString(4, entry.targetPlayer() != null ? entry.targetPlayer().toString() : null);
                    ps.setString(5, entry.playerName());
                    ps.setDouble(6, entry.amount());
                    ps.executeUpdate();
                    LOGGER.at(Level.INFO).log("Logged transaction to H2: %s %s %.0f", entry.type(), entry.playerName(), entry.amount());
                }
            } catch (SQLException e) {
                LOGGER.at(Level.WARNING).log("Failed to log transaction: %s", e.getMessage());
            }
        });
    }
    
    /**
     * Query transactions with optional player filter and pagination (async).
     */
    public CompletableFuture<List<TransactionEntry>> queryTransactionsAsync(String playerFilter, int limit, int offset) {
        return CompletableFuture.supplyAsync(() -> {
            List<TransactionEntry> results = new ArrayList<>();
            try {
                String sql;
                if (playerFilter != null && !playerFilter.isEmpty()) {
                    sql = """
                        SELECT * FROM transactions 
                        WHERE LOWER(player_name) LIKE ? 
                        ORDER BY timestamp DESC 
                        LIMIT ? OFFSET ?
                    """;
                } else {
                    sql = """
                        SELECT * FROM transactions 
                        ORDER BY timestamp DESC 
                        LIMIT ? OFFSET ?
                    """;
                }
                
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    int paramIndex = 1;
                    if (playerFilter != null && !playerFilter.isEmpty()) {
                        ps.setString(paramIndex++, "%" + playerFilter.toLowerCase() + "%");
                    }
                    ps.setInt(paramIndex++, limit);
                    ps.setInt(paramIndex, offset);
                    
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            results.add(resultSetToEntry(rs));
                        }
                    }
                }
            } catch (SQLException e) {
                LOGGER.at(Level.WARNING).log("Failed to query transactions: %s", e.getMessage());
            }
            return results;
        }, executor);
    }
    
    /**
     * Query transactions (sync, for backward compat).
     * @deprecated Use queryTransactionsAsync() to avoid potential deadlocks
     */
    @Deprecated
    public List<TransactionEntry> queryTransactions(String playerFilter, int limit, int offset) {
        return queryTransactionsAsync(playerFilter, limit, offset).join();
    }
    
    /**
     * Count total transactions matching filter (async).
     */
    public CompletableFuture<Integer> countTransactionsAsync(String playerFilter) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String sql;
                if (playerFilter != null && !playerFilter.isEmpty()) {
                    sql = "SELECT COUNT(*) FROM transactions WHERE LOWER(player_name) LIKE ?";
                } else {
                    sql = "SELECT COUNT(*) FROM transactions";
                }
                
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    if (playerFilter != null && !playerFilter.isEmpty()) {
                        ps.setString(1, "%" + playerFilter.toLowerCase() + "%");
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            int count = rs.getInt(1);
                            LOGGER.at(Level.INFO).log("H2 transaction count: %d (filter: %s)", count, playerFilter);
                            return count;
                        }
                    }
                }
            } catch (SQLException e) {
                LOGGER.at(Level.WARNING).log("Failed to count transactions: %s", e.getMessage());
            }
            return 0;
        }, executor);
    }
    
    /**
     * Count total transactions (sync, for backward compat).
     * @deprecated Use countTransactionsAsync() to avoid potential deadlocks
     */
    @Deprecated
    public int countTransactions(String playerFilter) {
        return countTransactionsAsync(playerFilter).join();
    }
    
    private TransactionEntry resultSetToEntry(ResultSet rs) throws SQLException {
        long timestampMs = rs.getLong("timestamp");
        Instant timestamp = Instant.ofEpochMilli(timestampMs);
        
        String typeStr = rs.getString("type");
        TransactionType type = TransactionType.valueOf(typeStr);
        
        String sourceUuidStr = rs.getString("source_uuid");
        UUID sourceUuid = sourceUuidStr != null ? UUID.fromString(sourceUuidStr) : null;
        
        String targetUuidStr = rs.getString("target_uuid");
        UUID targetUuid = targetUuidStr != null ? UUID.fromString(targetUuidStr) : null;
        
        String playerName = rs.getString("player_name");
        double amount = rs.getDouble("amount");
        
        // Re-format timestamp for display
        java.time.format.DateTimeFormatter formatter = 
            java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(java.time.ZoneId.systemDefault());
        String formattedTime = formatter.format(timestamp);
        
        return new TransactionEntry(timestamp, formattedTime, type, sourceUuid, targetUuid, amount, playerName);
    }
    @Override
    public CompletableFuture<Void> shutdown() {
        // Signal executor to stop accepting new tasks
        executor.shutdown();
        
        // Close connection synchronously - we're already being called during server shutdown
        // No need to submit to executor since saveAll() has already completed
        LOGGER.at(Level.INFO).log("H2 shutdown: closing connection...");
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
            LOGGER.at(Level.INFO).log("H2 database connection closed");
        } catch (SQLException e) {
            LOGGER.at(Level.WARNING).log("Error closing H2 connection: %s", e.getMessage());
        }
        
        // Return completed future since we closed synchronously
        return CompletableFuture.completedFuture(null);
    }
    
    @Override
    public String getName() {
        return "H2 Database";
    }
    
    @Override
    public int getPlayerCount() {
        return playerCount;
    }
    
    /**
     * Get the database connection for advanced operations.
     * Use with caution - prefer dedicated methods.
     */
    public Connection getConnection() {
        return connection;
    }
}
