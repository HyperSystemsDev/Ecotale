package com.ecotale.storage;

import com.ecotale.Main;
import com.ecotale.economy.PlayerBalance;
import com.ecotale.economy.TopBalanceEntry;
import com.ecotale.economy.TransactionEntry;
import com.ecotale.economy.TransactionType;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import com.hypixel.hytale.logger.HytaleLogger;

import org.bson.Document;

import javax.annotation.Nonnull;
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
 * MongoDB storage provider for economy data.
 * Mirrors H2StorageProvider's full feature set.
 * 
 * Collections:
 * - balances: Player balance data
 * - transactions: Transaction history
 * - snapshots: Daily balance snapshots for trends
 */
public class MongoDBStorageProvider implements StorageProvider {
    
    private static final HytaleLogger LOGGER = HytaleLogger.getLogger().getSubLogger("Ecotale-MongoDB");
    private static final DateTimeFormatter TIME_FORMATTER = 
        DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
    
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Ecotale-MongoDB-IO");
        t.setDaemon(false);
        return t;
    });
    
    private MongoClient client;
    private MongoDatabase database;
    private MongoCollection<Document> balancesCollection;
    private MongoCollection<Document> transactionsCollection;
    private MongoCollection<Document> snapshotsCollection;
    private int playerCount = 0;
    
    @Override
    public CompletableFuture<Void> initialize() {
        return CompletableFuture.runAsync(() -> {
            try {
                String uri = Main.CONFIG.get().getMongoUri();
                String dbName = Main.CONFIG.get().getMongoDatabase();
                
                client = MongoClients.create(uri);
                database = client.getDatabase(dbName);
                
                balancesCollection = database.getCollection("balances");
                transactionsCollection = database.getCollection("transactions");
                snapshotsCollection = database.getCollection("snapshots");
                
                // Create indexes
                balancesCollection.createIndex(new Document("uuid", 1));
                balancesCollection.createIndex(new Document("player_name", 1));
                balancesCollection.createIndex(new Document("balance", -1));
                transactionsCollection.createIndex(new Document("timestamp", -1));
                transactionsCollection.createIndex(new Document("player_name", 1));
                snapshotsCollection.createIndex(new Document("snap_day", 1).append("uuid", 1));
                
                playerCount = (int) balancesCollection.countDocuments();
                
                LOGGER.at(Level.INFO).log("MongoDB connected: %s/%s (%d players)", uri, dbName, playerCount);
                
            } catch (Exception e) {
                LOGGER.at(Level.SEVERE).log("Failed to initialize MongoDB: %s", e.getMessage());
                throw new RuntimeException(e);
            }
        }, executor);
    }
    @Override
    public CompletableFuture<PlayerBalance> loadPlayer(@Nonnull UUID playerUuid) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Document doc = balancesCollection.find(Filters.eq("uuid", playerUuid.toString())).first();
                
                if (doc != null) {
                    PlayerBalance pb = new PlayerBalance(playerUuid);
                    pb.setBalance(doc.getDouble("balance"), "Loaded from MongoDB");
                    return pb;
                }
                
                // Create new account with starting balance
                double startingBalance = Main.CONFIG.get().getStartingBalance();
                PlayerBalance newBalance = new PlayerBalance(playerUuid);
                newBalance.setBalance(startingBalance, "New account");
                savePlayerSync(playerUuid, newBalance);
                playerCount++;
                return newBalance;
                
            } catch (Exception e) {
                LOGGER.at(Level.SEVERE).log("Failed to load player %s: %s", playerUuid, e.getMessage());
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
            Document doc = new Document()
                .append("uuid", playerUuid.toString())
                .append("balance", balance.getBalance())
                .append("total_earned", balance.getTotalEarned())
                .append("total_spent", balance.getTotalSpent())
                .append("updated_at", new Date());
            
            balancesCollection.replaceOne(
                Filters.eq("uuid", playerUuid.toString()),
                doc,
                new ReplaceOptions().upsert(true)
            );
        } catch (Exception e) {
            LOGGER.at(Level.SEVERE).log("Failed to save player %s: %s", playerUuid, e.getMessage());
        }
    }
    public void updatePlayerName(@Nonnull UUID playerUuid, @Nonnull String playerName) {
        CompletableFuture.runAsync(() -> {
            try {
                balancesCollection.updateOne(
                    Filters.eq("uuid", playerUuid.toString()),
                    Updates.combine(
                        Updates.set("player_name", playerName),
                        Updates.setOnInsert("balance", Main.CONFIG.get().getStartingBalance())
                    ),
                    new com.mongodb.client.model.UpdateOptions().upsert(true)
                );
            } catch (Exception e) {
                LOGGER.at(Level.WARNING).log("Failed to update player name: %s", e.getMessage());
            }
        }, executor);
    }
    
    public CompletableFuture<String> getPlayerNameAsync(@Nonnull UUID playerUuid) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Document doc = balancesCollection.find(Filters.eq("uuid", playerUuid.toString())).first();
                return doc != null ? doc.getString("player_name") : null;
            } catch (Exception e) {
                LOGGER.at(Level.WARNING).log("Failed to get player name: %s", e.getMessage());
                return null;
            }
        }, executor);
    }
    
    public CompletableFuture<UUID> getPlayerUuidByName(@Nonnull String playerName) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Document doc = balancesCollection.find(
                    Filters.regex("player_name", "(?i)^" + playerName + "$")
                ).first();
                return doc != null ? UUID.fromString(doc.getString("uuid")) : null;
            } catch (Exception e) {
                LOGGER.at(Level.WARNING).log("Failed to get UUID by name: %s", e.getMessage());
                return null;
            }
        }, executor);
    }
    
    public Map<UUID, String> getAllPlayerNamesSync() {
        Map<UUID, String> result = new HashMap<>();
        try {
            for (Document doc : balancesCollection.find(Filters.exists("player_name"))) {
                String name = doc.getString("player_name");
                if (name != null && !name.isBlank()) {
                    result.put(UUID.fromString(doc.getString("uuid")), name);
                }
            }
        } catch (Exception e) {
            LOGGER.at(Level.WARNING).log("Failed to get all player names: %s", e.getMessage());
        }
        return result;
    }
    @Override
    public CompletableFuture<Boolean> getHudVisible(@Nonnull UUID playerUuid) {
        return CompletableFuture.supplyAsync(() -> getHudVisibleSync(playerUuid), executor);
    }
    
    public boolean getHudVisibleSync(@Nonnull UUID playerUuid) {
        try {
            Document doc = balancesCollection.find(Filters.eq("uuid", playerUuid.toString())).first();
            if (doc != null) {
                Boolean visible = doc.getBoolean("hud_visible");
                return visible == null || visible; // Default true if field missing
            }
        } catch (Exception e) {
            LOGGER.at(Level.WARNING).log("Failed to get HUD visibility: %s", e.getMessage());
        }
        return true;
    }
    
    @Override
    public CompletableFuture<Void> setHudVisible(@Nonnull UUID playerUuid, boolean visible) {
        return CompletableFuture.runAsync(() -> {
            try {
                balancesCollection.updateOne(
                    Filters.eq("uuid", playerUuid.toString()),
                    Updates.combine(
                        Updates.set("hud_visible", visible),
                        Updates.setOnInsert("balance", Main.CONFIG.get().getStartingBalance())
                    ),
                    new com.mongodb.client.model.UpdateOptions().upsert(true)
                );
            } catch (Exception e) {
                LOGGER.at(Level.WARNING).log("Failed to set HUD visibility: %s", e.getMessage());
            }
        }, executor);
    }
    public CompletableFuture<List<PlayerBalance>> getTopBalances(int limit) {
        return CompletableFuture.supplyAsync(() -> {
            List<PlayerBalance> result = new ArrayList<>();
            try {
                for (Document doc : balancesCollection.find()
                        .sort(Sorts.descending("balance"))
                        .limit(limit)) {
                    UUID uuid = UUID.fromString(doc.getString("uuid"));
                    PlayerBalance pb = new PlayerBalance(uuid);
                    pb.setBalance(doc.getDouble("balance"), "Top query");
                    result.add(pb);
                }
            } catch (Exception e) {
                LOGGER.at(Level.WARNING).log("Failed to query top balances: %s", e.getMessage());
            }
            return result;
        }, executor);
    }
    
    public CompletableFuture<List<TopBalanceEntry>> queryTopBalancesAsync(int limit, int offset) {
        return CompletableFuture.supplyAsync(() -> {
            List<TopBalanceEntry> result = new ArrayList<>();
            try {
                for (Document doc : balancesCollection.find()
                        .sort(Sorts.descending("balance"))
                        .skip(offset)
                        .limit(limit)) {
                    UUID uuid = UUID.fromString(doc.getString("uuid"));
                    double balance = doc.getDouble("balance");
                    String name = doc.getString("player_name");
                    result.add(new TopBalanceEntry(uuid, name, balance, 0.0));
                }
            } catch (Exception e) {
                LOGGER.at(Level.WARNING).log("Failed to query top balances: %s", e.getMessage());
            }
            return result;
        }, executor);
    }
    
    public CompletableFuture<List<TopBalanceEntry>> queryTopBalancesPeriodAsync(int limit, int offset, int daysAgo) {
        return CompletableFuture.supplyAsync(() -> {
            List<TopBalanceEntry> result = new ArrayList<>();
            try {
                String snapDay = LocalDate.now().minusDays(daysAgo).toString();
                
                // Get current balances
                Map<String, Document> currentBalances = new HashMap<>();
                for (Document doc : balancesCollection.find()) {
                    currentBalances.put(doc.getString("uuid"), doc);
                }
                
                // Get snapshot balances
                Map<String, Double> snapshotBalances = new HashMap<>();
                for (Document doc : snapshotsCollection.find(Filters.eq("snap_day", snapDay))) {
                    snapshotBalances.put(doc.getString("uuid"), doc.getDouble("balance"));
                }
                
                // Calculate trends and sort
                List<TopBalanceEntry> allEntries = new ArrayList<>();
                for (Map.Entry<String, Document> entry : currentBalances.entrySet()) {
                    Document doc = entry.getValue();
                    double currentBal = doc.getDouble("balance");
                    double snapshotBal = snapshotBalances.getOrDefault(entry.getKey(), 0.0);
                    double trend = currentBal - snapshotBal;
                    
                    allEntries.add(new TopBalanceEntry(
                        UUID.fromString(entry.getKey()),
                        doc.getString("player_name"),
                        currentBal,
                        trend
                    ));
                }
                
                // Sort by trend descending
                allEntries.sort((a, b) -> Double.compare(b.trend(), a.trend()));
                
                // Apply pagination
                int end = Math.min(offset + limit, allEntries.size());
                if (offset < allEntries.size()) {
                    result = allEntries.subList(offset, end);
                }
                
            } catch (Exception e) {
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
            try {
                String snapDay = date.toString();
                
                // Delete existing snapshots for this day
                snapshotsCollection.deleteMany(Filters.eq("snap_day", snapDay));
                
                // Insert new snapshots
                List<Document> snapshots = new ArrayList<>();
                for (Document doc : balancesCollection.find()) {
                    snapshots.add(new Document()
                        .append("snap_day", snapDay)
                        .append("uuid", doc.getString("uuid"))
                        .append("balance", doc.getDouble("balance"))
                    );
                }
                
                if (!snapshots.isEmpty()) {
                    snapshotsCollection.insertMany(snapshots);
                }
                
            } catch (Exception e) {
                LOGGER.at(Level.WARNING).log("Failed to snapshot balances: %s", e.getMessage());
            }
        }, executor);
    }
    public CompletableFuture<Integer> countPlayersAsync() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return (int) balancesCollection.countDocuments();
            } catch (Exception e) {
                LOGGER.at(Level.WARNING).log("Failed to count players: %s", e.getMessage());
                return 0;
            }
        }, executor);
    }
    
    public CompletableFuture<Integer> countPlayersWithBalanceGreaterAsync(double balance) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return (int) balancesCollection.countDocuments(Filters.gt("balance", balance));
            } catch (Exception e) {
                LOGGER.at(Level.WARNING).log("Failed to count balance rank: %s", e.getMessage());
                return 0;
            }
        }, executor);
    }
    @Override
    public CompletableFuture<Void> saveAll(@Nonnull Map<UUID, PlayerBalance> dirtyPlayers) {
        return CompletableFuture.runAsync(() -> saveAllSync(dirtyPlayers), executor);
    }
    
    public void saveAllSync(@Nonnull Map<UUID, PlayerBalance> dirtyPlayers) {
        if (dirtyPlayers.isEmpty()) return;
        
        try {
            for (var entry : dirtyPlayers.entrySet()) {
                savePlayerSync(entry.getKey(), entry.getValue());
            }
            LOGGER.at(Level.INFO).log("Saved %d player balances to MongoDB", dirtyPlayers.size());
        } catch (Exception e) {
            LOGGER.at(Level.SEVERE).log("Failed to batch save: %s", e.getMessage());
        }
    }
    
    @Override
    public CompletableFuture<Map<UUID, PlayerBalance>> loadAll() {
        return CompletableFuture.supplyAsync(() -> {
            Map<UUID, PlayerBalance> result = new HashMap<>();
            try {
                for (Document doc : balancesCollection.find()) {
                    UUID uuid = UUID.fromString(doc.getString("uuid"));
                    PlayerBalance pb = new PlayerBalance(uuid);
                    pb.setBalance(doc.getDouble("balance"), "Bulk load");
                    result.put(uuid, pb);
                }
                playerCount = result.size();
            } catch (Exception e) {
                LOGGER.at(Level.SEVERE).log("Failed to load all balances: %s", e.getMessage());
            }
            return result;
        }, executor);
    }
    
    @Override
    public CompletableFuture<Boolean> playerExists(@Nonnull UUID playerUuid) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return balancesCollection.find(Filters.eq("uuid", playerUuid.toString())).first() != null;
            } catch (Exception e) {
                return false;
            }
        }, executor);
    }
    
    @Override
    public CompletableFuture<Void> deletePlayer(@Nonnull UUID playerUuid) {
        return CompletableFuture.runAsync(() -> {
            try {
                long deleted = balancesCollection.deleteOne(Filters.eq("uuid", playerUuid.toString())).getDeletedCount();
                if (deleted > 0) playerCount--;
            } catch (Exception e) {
                LOGGER.at(Level.SEVERE).log("Failed to delete player %s: %s", playerUuid, e.getMessage());
            }
        }, executor);
    }
    public void logTransaction(TransactionEntry entry) {
        executor.execute(() -> {
            try {
                Document doc = new Document()
                    .append("timestamp", entry.timestamp().toEpochMilli())
                    .append("type", entry.type().name())
                    .append("source_uuid", entry.sourcePlayer() != null ? entry.sourcePlayer().toString() : null)
                    .append("target_uuid", entry.targetPlayer() != null ? entry.targetPlayer().toString() : null)
                    .append("player_name", entry.playerName())
                    .append("amount", entry.amount())
                    .append("created_at", new Date());
                
                transactionsCollection.insertOne(doc);
                LOGGER.at(Level.INFO).log("Logged transaction to MongoDB: %s %s %.0f", 
                    entry.type(), entry.playerName(), entry.amount());
            } catch (Exception e) {
                LOGGER.at(Level.WARNING).log("Failed to log transaction: %s", e.getMessage());
            }
        });
    }
    
    public CompletableFuture<List<TransactionEntry>> queryTransactionsAsync(String playerFilter, int limit, int offset) {
        return CompletableFuture.supplyAsync(() -> {
            List<TransactionEntry> results = new ArrayList<>();
            try {
                var query = playerFilter != null && !playerFilter.isEmpty()
                    ? Filters.regex("player_name", "(?i).*" + playerFilter + ".*")
                    : Filters.empty();
                
                for (Document doc : transactionsCollection.find(query)
                        .sort(Sorts.descending("timestamp"))
                        .skip(offset)
                        .limit(limit)) {
                    results.add(documentToEntry(doc));
                }
            } catch (Exception e) {
                LOGGER.at(Level.WARNING).log("Failed to query transactions: %s", e.getMessage());
            }
            return results;
        }, executor);
    }
    
    public CompletableFuture<Integer> countTransactionsAsync(String playerFilter) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                var query = playerFilter != null && !playerFilter.isEmpty()
                    ? Filters.regex("player_name", "(?i).*" + playerFilter + ".*")
                    : Filters.empty();
                return (int) transactionsCollection.countDocuments(query);
            } catch (Exception e) {
                LOGGER.at(Level.WARNING).log("Failed to count transactions: %s", e.getMessage());
                return 0;
            }
        }, executor);
    }
    
    private TransactionEntry documentToEntry(Document doc) {
        long timestampMs = doc.getLong("timestamp");
        Instant timestamp = Instant.ofEpochMilli(timestampMs);
        
        TransactionType type = TransactionType.valueOf(doc.getString("type"));
        
        String sourceUuidStr = doc.getString("source_uuid");
        UUID sourceUuid = sourceUuidStr != null ? UUID.fromString(sourceUuidStr) : null;
        
        String targetUuidStr = doc.getString("target_uuid");
        UUID targetUuid = targetUuidStr != null ? UUID.fromString(targetUuidStr) : null;
        
        String playerName = doc.getString("player_name");
        double amount = doc.getDouble("amount");
        
        String formattedTime = TIME_FORMATTER.format(timestamp);
        
        return new TransactionEntry(timestamp, formattedTime, type, sourceUuid, targetUuid, amount, playerName);
    }
    @Override
    public CompletableFuture<Void> shutdown() {
        executor.shutdown();
        
        LOGGER.at(Level.INFO).log("MongoDB shutdown: closing connection...");
        try {
            if (client != null) {
                client.close();
            }
            LOGGER.at(Level.INFO).log("MongoDB connection closed");
        } catch (Exception e) {
            LOGGER.at(Level.WARNING).log("Error closing MongoDB connection: %s", e.getMessage());
        }
        
        return CompletableFuture.completedFuture(null);
    }
    
    @Override
    public String getName() {
        return "MongoDB";
    }
    
    @Override
    public int getPlayerCount() {
        return playerCount;
    }
}
