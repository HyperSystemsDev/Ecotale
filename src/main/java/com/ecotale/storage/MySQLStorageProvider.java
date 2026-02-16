package com.ecotale.storage;

import com.ecotale.Main;
import com.ecotale.config.EcotaleConfig;
import com.ecotale.economy.PlayerBalance;
import com.ecotale.economy.TopBalanceEntry;
import com.ecotale.economy.TransactionEntry;
import com.ecotale.economy.TransactionType;
import com.hypixel.hytale.logger.HytaleLogger;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.annotation.Nonnull;
import java.sql.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

/**
 * MySQL storage provider for shared economy data across servers.
 * Uses HikariCP connection pooling for robust connection management.
 * Full H2 feature parity - includes snapshots, transactions, player names, trends.
 * 
 * @author michidev
 */
public class MySQLStorageProvider implements StorageProvider {
    
    private static final HytaleLogger LOGGER = HytaleLogger.getLogger().getSubLogger("Ecotale-MySQL");
    private static final DateTimeFormatter TIME_FORMATTER = 
        DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
    
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Ecotale-MySQL-IO");
        t.setDaemon(false);
        return t;
    });
    
    private HikariDataSource dataSource;
    private String tablePrefix;
    private int playerCount = 0;
    
    @Override
    public CompletableFuture<Void> initialize() {
        return CompletableFuture.runAsync(() -> {
            try {
                EcotaleConfig config = Main.CONFIG.get();
                tablePrefix = config.getMysqlTablePrefix();
                
                String host = config.getMysqlHost();
                int port = config.getMysqlPort();
                String database = config.getMysqlDatabase();
                String username = config.getMysqlUsername();
                String password = config.getMysqlPassword();
                
                LOGGER.at(Level.INFO).log("Connecting to MySQL: %s:%d/%s", host, port, database);
                
                // Configure HikariCP
                HikariConfig hikariConfig = new HikariConfig();
                hikariConfig.setDriverClassName("com.mysql.cj.jdbc.Driver");  // Explicit for Hytale classloaders
                hikariConfig.setJdbcUrl(String.format("jdbc:mysql://%s:%d/%s", host, port, database));
                hikariConfig.setUsername(username);
                hikariConfig.setPassword(password);
                
                // Connection pool settings - sized for 500+ concurrent users
                hikariConfig.setMaximumPoolSize(25);       // Handles high concurrency peaks
                hikariConfig.setMinimumIdle(5);            // Always ready connections
                hikariConfig.setIdleTimeout(300000);       // 5 minutes
                hikariConfig.setMaxLifetime(1800000);      // 30 minutes
                hikariConfig.setConnectionTimeout(10000);  // 10 seconds - fail-fast
                hikariConfig.setKeepaliveTime(60000);      // 1 minute - prevents wait_timeout
                
                // MySQL optimizations
                hikariConfig.addDataSourceProperty("cachePrepStmts", "true");
                hikariConfig.addDataSourceProperty("prepStmtCacheSize", "250");
                hikariConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
                hikariConfig.addDataSourceProperty("useServerPrepStmts", "true");
                hikariConfig.addDataSourceProperty("useSSL", "false");
                hikariConfig.addDataSourceProperty("allowPublicKeyRetrieval", "true");
                
                hikariConfig.setPoolName("Ecotale-MySQL-Pool");
                
                dataSource = new HikariDataSource(hikariConfig);
                
                createTables();
                
                try (Connection conn = dataSource.getConnection();
                     Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + tablePrefix + "balances")) {
                    if (rs.next()) {
                        playerCount = rs.getInt(1);
                    }
                }
                
                LOGGER.at(Level.INFO).log("MySQL connected successfully with HikariCP (%d players)", playerCount);
                
            } catch (SQLException e) {
                LOGGER.at(Level.SEVERE).log("Failed to connect to MySQL: %s", e.getMessage());
                throw new RuntimeException("MySQL connection failed", e);
            }
        }, executor);
    }
    
    private void createTables() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            // Balances table with player_name
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS %sbalances (
                    uuid VARCHAR(36) PRIMARY KEY,
                    player_name VARCHAR(64),
                    balance DOUBLE DEFAULT 0.0,
                    total_earned DOUBLE DEFAULT 0.0,
                    total_spent DOUBLE DEFAULT 0.0,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    INDEX idx_player_name (player_name),
                    INDEX idx_balance (balance DESC)
                )
                """.formatted(tablePrefix));
            
            // Transactions table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS %stransactions (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    timestamp BIGINT NOT NULL,
                    type VARCHAR(20) NOT NULL,
                    source_uuid VARCHAR(36),
                    target_uuid VARCHAR(36),
                    player_name VARCHAR(64),
                    amount DOUBLE,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    INDEX idx_timestamp (timestamp DESC),
                    INDEX idx_player (player_name)
                )
                """.formatted(tablePrefix));
            
            // Balance snapshots table for trends
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS %sbalance_snapshots (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    snap_day DATE NOT NULL,
                    uuid VARCHAR(36) NOT NULL,
                    balance DOUBLE NOT NULL,
                    UNIQUE KEY uk_snap (snap_day, uuid),
                    INDEX idx_snap_day (snap_day)
                )
                """.formatted(tablePrefix));
            
            // Migration: Add hud_visible column for player HUD preferences
            try {
                stmt.execute("ALTER TABLE " + tablePrefix + "balances ADD COLUMN hud_visible BOOLEAN DEFAULT TRUE");
            } catch (SQLException ignored) {
                // Column already exists
            }
        }
    }
    @Override
    public CompletableFuture<PlayerBalance> loadPlayer(@Nonnull UUID playerUuid) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection()) {
                String sql = "SELECT balance, total_earned, total_spent FROM " + tablePrefix + "balances WHERE uuid = ?";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, playerUuid.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            PlayerBalance pb = new PlayerBalance(playerUuid);
                            pb.setBalance(rs.getDouble("balance"), "Loaded from MySQL");
                            return pb;
                        }
                    }
                }
                
                PlayerBalance newBalance = new PlayerBalance(playerUuid);
                newBalance.setBalance(Main.CONFIG.get().getStartingBalance(), "Initial balance");
                playerCount++;
                return newBalance;
                
            } catch (SQLException e) {
                LOGGER.at(Level.SEVERE).log("Failed to load player %s: %s", playerUuid, e.getMessage());
                return new PlayerBalance(playerUuid);
            }
        }, executor);
    }
    
    @Override
    public CompletableFuture<Void> savePlayer(@Nonnull UUID playerUuid, @Nonnull PlayerBalance balance) {
        return CompletableFuture.runAsync(() -> savePlayerSync(playerUuid, balance), executor);
    }
    
    private void savePlayerSync(UUID playerUuid, PlayerBalance balance) {
        try (Connection conn = dataSource.getConnection()) {
            String sql = """
                INSERT INTO %sbalances (uuid, balance, total_earned, total_spent, updated_at)
                VALUES (?, ?, ?, ?, NOW())
                ON DUPLICATE KEY UPDATE 
                    balance = VALUES(balance),
                    total_earned = VALUES(total_earned),
                    total_spent = VALUES(total_spent),
                    updated_at = NOW()
                """.formatted(tablePrefix);
            
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
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
    public void updatePlayerName(@Nonnull UUID playerUuid, @Nonnull String playerName) {
        CompletableFuture.runAsync(() -> {
            try (Connection conn = dataSource.getConnection()) {
                String sql = """
                    INSERT INTO %sbalances (uuid, player_name, balance)
                    VALUES (?, ?, ?)
                    ON DUPLICATE KEY UPDATE player_name = VALUES(player_name)
                    """.formatted(tablePrefix);
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, playerUuid.toString());
                    ps.setString(2, playerName);
                    ps.setDouble(3, Main.CONFIG.get().getStartingBalance());
                    ps.executeUpdate();
                }
            } catch (SQLException e) {
                LOGGER.at(Level.WARNING).log("Failed to update player name: %s", e.getMessage());
            }
        }, executor);
    }
    
    public CompletableFuture<String> getPlayerNameAsync(@Nonnull UUID playerUuid) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection()) {
                String sql = "SELECT player_name FROM " + tablePrefix + "balances WHERE uuid = ?";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
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
    
    public CompletableFuture<UUID> getPlayerUuidByName(@Nonnull String playerName) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection()) {
                String sql = "SELECT uuid FROM " + tablePrefix + "balances WHERE LOWER(player_name) = LOWER(?)";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, playerName);
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
    
    public Map<UUID, String> getAllPlayerNamesSync() {
        Map<UUID, String> result = new HashMap<>();
        try (Connection conn = dataSource.getConnection()) {
            String sql = "SELECT uuid, player_name FROM " + tablePrefix + "balances WHERE player_name IS NOT NULL";
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    String name = rs.getString("player_name");
                    if (name != null && !name.isBlank()) {
                        result.put(UUID.fromString(rs.getString("uuid")), name);
                    }
                }
            }
        } catch (SQLException e) {
            LOGGER.at(Level.WARNING).log("Failed to get all player names: %s", e.getMessage());
        }
        return result;
    }
    @Override
    public CompletableFuture<Boolean> getHudVisible(@Nonnull UUID playerUuid) {
        return CompletableFuture.supplyAsync(() -> getHudVisibleSync(playerUuid), executor);
    }
    
    public boolean getHudVisibleSync(@Nonnull UUID playerUuid) {
        try (Connection conn = dataSource.getConnection()) {
            String sql = "SELECT hud_visible FROM " + tablePrefix + "balances WHERE uuid = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, playerUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        Boolean visible = rs.getObject("hud_visible", Boolean.class);
                        return visible == null || visible;
                    }
                }
            }
        } catch (SQLException e) {
            LOGGER.at(Level.WARNING).log("Failed to get HUD visibility: %s", e.getMessage());
        }
        return true;
    }
    
    @Override
    public CompletableFuture<Void> setHudVisible(@Nonnull UUID playerUuid, boolean visible) {
        return CompletableFuture.runAsync(() -> {
            try (Connection conn = dataSource.getConnection()) {
                String sql = """
                    INSERT INTO %sbalances (uuid, balance, hud_visible)
                    VALUES (?, ?, ?)
                    ON DUPLICATE KEY UPDATE hud_visible = VALUES(hud_visible)
                    """.formatted(tablePrefix);
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, playerUuid.toString());
                    ps.setDouble(2, Main.CONFIG.get().getStartingBalance());
                    ps.setBoolean(3, visible);
                    ps.executeUpdate();
                }
            } catch (SQLException e) {
                LOGGER.at(Level.WARNING).log("Failed to set HUD visibility: %s", e.getMessage());
            }
        }, executor);
    }
    public CompletableFuture<List<PlayerBalance>> getTopBalances(int limit) {
        return CompletableFuture.supplyAsync(() -> {
            List<PlayerBalance> result = new ArrayList<>();
            try (Connection conn = dataSource.getConnection()) {
                String sql = "SELECT uuid, balance FROM " + tablePrefix + "balances ORDER BY balance DESC LIMIT ?";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
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
                LOGGER.at(Level.WARNING).log("Failed to get top balances: %s", e.getMessage());
            }
            return result;
        }, executor);
    }
    
    public CompletableFuture<List<TopBalanceEntry>> queryTopBalancesAsync(int limit, int offset) {
        return CompletableFuture.supplyAsync(() -> {
            List<TopBalanceEntry> result = new ArrayList<>();
            try (Connection conn = dataSource.getConnection()) {
                String sql = "SELECT uuid, player_name, balance FROM " + tablePrefix + 
                    "balances ORDER BY balance DESC LIMIT ? OFFSET ?";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setInt(1, limit);
                    ps.setInt(2, offset);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            result.add(new TopBalanceEntry(
                                UUID.fromString(rs.getString("uuid")),
                                rs.getString("player_name"),
                                rs.getDouble("balance"),
                                0.0
                            ));
                        }
                    }
                }
            } catch (SQLException e) {
                LOGGER.at(Level.WARNING).log("Failed to query top balances: %s", e.getMessage());
            }
            return result;
        }, executor);
    }
    
    public CompletableFuture<List<TopBalanceEntry>> queryTopBalancesPeriodAsync(int limit, int offset, int daysAgo) {
        return CompletableFuture.supplyAsync(() -> {
            List<TopBalanceEntry> result = new ArrayList<>();
            try (Connection conn = dataSource.getConnection()) {
                String sql = """
                    SELECT b.uuid, b.player_name, b.balance, 
                           COALESCE(b.balance - s.balance, 0) as trend
                    FROM %sbalances b
                    LEFT JOIN %sbalance_snapshots s ON b.uuid = s.uuid AND s.snap_day = DATE_SUB(CURDATE(), INTERVAL ? DAY)
                    ORDER BY trend DESC
                    LIMIT ? OFFSET ?
                    """.formatted(tablePrefix, tablePrefix);
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setInt(1, daysAgo);
                    ps.setInt(2, limit);
                    ps.setInt(3, offset);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            result.add(new TopBalanceEntry(
                                UUID.fromString(rs.getString("uuid")),
                                rs.getString("player_name"),
                                rs.getDouble("balance"),
                                rs.getDouble("trend")
                            ));
                        }
                    }
                }
            } catch (SQLException e) {
                LOGGER.at(Level.WARNING).log("Failed to query period balances: %s", e.getMessage());
            }
            return result;
        }, executor);
    }
    public CompletableFuture<Void> snapshotTodayAsync() {
        return snapshotForDateAsync(LocalDate.now());
    }
    
    public CompletableFuture<Void> snapshotForDateAsync(@Nonnull LocalDate date) {
        return CompletableFuture.runAsync(() -> {
            try (Connection conn = dataSource.getConnection()) {
                String sql = """
                    INSERT INTO %sbalance_snapshots (snap_day, uuid, balance)
                    SELECT ?, uuid, balance FROM %sbalances
                    ON DUPLICATE KEY UPDATE balance = VALUES(balance)
                    """.formatted(tablePrefix, tablePrefix);
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setDate(1, java.sql.Date.valueOf(date));
                    ps.executeUpdate();
                }
            } catch (SQLException e) {
                LOGGER.at(Level.WARNING).log("Failed to snapshot balances: %s", e.getMessage());
            }
        }, executor);
    }
    public CompletableFuture<Integer> countPlayersAsync() {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection()) {
                String sql = "SELECT COUNT(*) AS total FROM " + tablePrefix + "balances";
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(sql)) {
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
    
    public CompletableFuture<Integer> countPlayersWithBalanceGreaterAsync(double balance) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection()) {
                String sql = "SELECT COUNT(*) AS total FROM " + tablePrefix + "balances WHERE balance > ?";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
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
    @Override
    public CompletableFuture<Void> saveAll(@Nonnull Map<UUID, PlayerBalance> dirtyPlayers) {
        return CompletableFuture.runAsync(() -> saveAllSync(dirtyPlayers), executor);
    }
    
    public void saveAllSync(@Nonnull Map<UUID, PlayerBalance> dirtyPlayers) {
        if (dirtyPlayers.isEmpty()) return;
        
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            String sql = """
                INSERT INTO %sbalances (uuid, balance, total_earned, total_spent, updated_at) 
                VALUES (?, ?, ?, ?, NOW())
                ON DUPLICATE KEY UPDATE 
                    balance = VALUES(balance),
                    total_earned = VALUES(total_earned),
                    total_spent = VALUES(total_spent),
                    updated_at = NOW()
                """.formatted(tablePrefix);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (var entry : dirtyPlayers.entrySet()) {
                    ps.setString(1, entry.getKey().toString());
                    ps.setDouble(2, entry.getValue().getBalance());
                    ps.setDouble(3, entry.getValue().getTotalEarned());
                    ps.setDouble(4, entry.getValue().getTotalSpent());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            conn.commit();
            LOGGER.at(Level.INFO).log("Saved %d player balances to MySQL", dirtyPlayers.size());
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Failed to batch save: %s", e.getMessage());
        }
    }
    
    @Override
    public CompletableFuture<Map<UUID, PlayerBalance>> loadAll() {
        return CompletableFuture.supplyAsync(() -> {
            Map<UUID, PlayerBalance> result = new HashMap<>();
            try (Connection conn = dataSource.getConnection()) {
                String sql = "SELECT uuid, balance, total_earned, total_spent FROM " + tablePrefix + "balances";
                try (Statement stmt = conn.createStatement();
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
            try (Connection conn = dataSource.getConnection()) {
                String sql = "SELECT 1 FROM " + tablePrefix + "balances WHERE uuid = ?";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
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
            try (Connection conn = dataSource.getConnection()) {
                String sql = "DELETE FROM " + tablePrefix + "balances WHERE uuid = ?";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, playerUuid.toString());
                    int affected = ps.executeUpdate();
                    if (affected > 0) playerCount--;
                }
            } catch (SQLException e) {
                LOGGER.at(Level.SEVERE).log("Failed to delete player %s: %s", playerUuid, e.getMessage());
            }
        }, executor);
    }
    public void logTransaction(TransactionEntry entry) {
        executor.execute(() -> {
            try (Connection conn = dataSource.getConnection()) {
                String sql = """
                    INSERT INTO %stransactions (timestamp, type, source_uuid, target_uuid, player_name, amount)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """.formatted(tablePrefix);
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setLong(1, entry.timestamp().toEpochMilli());
                    ps.setString(2, entry.type().name());
                    ps.setString(3, entry.sourcePlayer() != null ? entry.sourcePlayer().toString() : null);
                    ps.setString(4, entry.targetPlayer() != null ? entry.targetPlayer().toString() : null);
                    ps.setString(5, entry.playerName());
                    ps.setDouble(6, entry.amount());
                    ps.executeUpdate();
                }
            } catch (SQLException e) {
                LOGGER.at(Level.WARNING).log("Failed to log transaction: %s", e.getMessage());
            }
        });
    }
    
    public CompletableFuture<List<TransactionEntry>> queryTransactionsAsync(String playerFilter, int limit, int offset) {
        return CompletableFuture.supplyAsync(() -> {
            List<TransactionEntry> results = new ArrayList<>();
            try (Connection conn = dataSource.getConnection()) {
                String sql;
                if (playerFilter != null && !playerFilter.isEmpty()) {
                    sql = """
                        SELECT * FROM %stransactions 
                        WHERE LOWER(player_name) LIKE ? 
                        ORDER BY timestamp DESC 
                        LIMIT ? OFFSET ?
                        """.formatted(tablePrefix);
                } else {
                    sql = """
                        SELECT * FROM %stransactions 
                        ORDER BY timestamp DESC 
                        LIMIT ? OFFSET ?
                        """.formatted(tablePrefix);
                }
                
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
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
    
    public CompletableFuture<Integer> countTransactionsAsync(String playerFilter) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection()) {
                String sql;
                if (playerFilter != null && !playerFilter.isEmpty()) {
                    sql = "SELECT COUNT(*) FROM " + tablePrefix + "transactions WHERE LOWER(player_name) LIKE ?";
                } else {
                    sql = "SELECT COUNT(*) FROM " + tablePrefix + "transactions";
                }
                
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    if (playerFilter != null && !playerFilter.isEmpty()) {
                        ps.setString(1, "%" + playerFilter.toLowerCase() + "%");
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            return rs.getInt(1);
                        }
                    }
                }
            } catch (SQLException e) {
                LOGGER.at(Level.WARNING).log("Failed to count transactions: %s", e.getMessage());
            }
            return 0;
        }, executor);
    }
    
    private TransactionEntry resultSetToEntry(ResultSet rs) throws SQLException {
        long timestampMs = rs.getLong("timestamp");
        Instant timestamp = Instant.ofEpochMilli(timestampMs);
        
        TransactionType type = TransactionType.valueOf(rs.getString("type"));
        
        String sourceUuidStr = rs.getString("source_uuid");
        UUID sourceUuid = sourceUuidStr != null ? UUID.fromString(sourceUuidStr) : null;
        
        String targetUuidStr = rs.getString("target_uuid");
        UUID targetUuid = targetUuidStr != null ? UUID.fromString(targetUuidStr) : null;
        
        String playerName = rs.getString("player_name");
        double amount = rs.getDouble("amount");
        
        String formattedTime = TIME_FORMATTER.format(timestamp);
        
        return new TransactionEntry(timestamp, formattedTime, type, sourceUuid, targetUuid, amount, playerName);
    }
    @Override
    public CompletableFuture<Void> shutdown() {
        executor.shutdown();
        
        LOGGER.at(Level.INFO).log("MySQL shutdown: closing HikariCP pool...");
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
        LOGGER.at(Level.INFO).log("MySQL HikariCP pool closed");
        
        return CompletableFuture.completedFuture(null);
    }
    
    @Override
    public String getName() {
        return "MySQL (HikariCP pool)";
    }
    
    @Override
    public int getPlayerCount() {
        return playerCount;
    }
}
