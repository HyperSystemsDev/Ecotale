package com.ecotale.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nullable;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Professional service for looking up Hytale player information via PlayerDB API.
 * Used as fallback when players are not found in local storage.
 * 
 * Features:
 * - Async HTTP requests
 * - TTL cache (5 minutes) to avoid API spam
 * - Rate limiting (max 10 req/sec)
 * - Proper error handling
 * 
 * API: https://playerdb.co/api/player/hytale/{name}
 */
public class PlayerDBService {
    
    private static final HytaleLogger LOGGER = HytaleLogger.getLogger().getSubLogger("Ecotale-PlayerDB");
    private static final String API_URL = "https://playerdb.co/api/player/hytale/";
    
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    
    // TTL Cache: Key -> CacheEntry (UUID/Name + timestamp)
    private static final ConcurrentHashMap<String, CacheEntry<UUID>> uuidCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, CacheEntry<String>> nameCache = new ConcurrentHashMap<>();
    
    // Cache configuration
    private static final long CACHE_TTL_MS = TimeUnit.MINUTES.toMillis(5);
    private static final int MAX_CACHE_SIZE = 1000;
    
    // Rate limiting
    private static volatile long lastRequestTime = 0;
    private static final long MIN_REQUEST_INTERVAL_MS = 100; // 10 req/sec max
    
    /**
     * Look up a player's UUID by username.
     * 
     * @param name The player username to look up (case-insensitive)
     * @return Future containing UUID if found, or null if not found/error
     */
    public static CompletableFuture<UUID> lookupUuid(String name) {
        if (name == null || name.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        
        String lowerName = name.toLowerCase();
        
        // Check cache first
        CacheEntry<UUID> cached = uuidCache.get(lowerName);
        if (cached != null && !cached.isExpired()) {
            return CompletableFuture.completedFuture(cached.value);
        }
        
        return queryApi(name).thenApply(result -> {
            if (result != null) {
                // Update both caches
                uuidCache.put(lowerName, new CacheEntry<>(result.uuid));
                nameCache.put(result.uuid, new CacheEntry<>(result.username));
                
                // Cleanup if cache is too large
                if (uuidCache.size() > MAX_CACHE_SIZE) {
                    evictOldEntries();
                }
                
                return result.uuid;
            }
            return null;
        });
    }
    
    /**
     * Look up a player's username by UUID.
     * 
     * @param uuid The player UUID to look up
     * @return Future containing username if found, or null if not found/error
     */
    public static CompletableFuture<String> lookupName(UUID uuid) {
        if (uuid == null) {
            return CompletableFuture.completedFuture(null);
        }

        // Check cache first
        CacheEntry<String> cached = nameCache.get(uuid);
        if (cached != null && !cached.isExpired()) {
            return CompletableFuture.completedFuture(cached.value);
        }

        // PlayerDB supports UUID lookup: /api/player/hytale/{uuid}
        return queryApi(uuid.toString()).thenApply(result -> {
            if (result != null) {
                nameCache.put(result.uuid, new CacheEntry<>(result.username));
                uuidCache.put(result.username.toLowerCase(), new CacheEntry<>(result.uuid));
                return result.username;
            }
            return null;
        });
    }
    
    /**
     * Get cached name for a UUID without making API call.
     * Useful for display purposes when we've previously looked up the player.
     */
    @Nullable
    public static String getCachedName(UUID uuid) {
        CacheEntry<String> cached = nameCache.get(uuid);
        return (cached != null && !cached.isExpired()) ? cached.value : null;
    }
    
    /**
     * Query the PlayerDB API for player info.
     */
    private static CompletableFuture<PlayerInfo> queryApi(String name) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Rate limiting
                long now = System.currentTimeMillis();
                long timeSinceLastRequest = now - lastRequestTime;
                if (timeSinceLastRequest < MIN_REQUEST_INTERVAL_MS) {
                    Thread.sleep(MIN_REQUEST_INTERVAL_MS - timeSinceLastRequest);
                }
                lastRequestTime = System.currentTimeMillis();
                
                LOGGER.at(Level.FINE).log("Querying PlayerDB for: %s", name);
                
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(API_URL + name))
                        .timeout(Duration.ofSeconds(5))
                        .header("User-Agent", "Ecotale-Hytale-Plugin/1.0")
                        .GET()
                        .build();
                
                HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                
                if (response.statusCode() == 200) {
                    JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                    
                    if (json.has("success") && json.get("success").getAsBoolean() &&
                        json.has("code") && "player.found".equals(json.get("code").getAsString())) {
                        
                        JsonObject data = json.getAsJsonObject("data");
                        JsonObject player = data.getAsJsonObject("player");
                        
                        String uuidStr = player.get("id").getAsString();
                        String username = player.get("username").getAsString();
                        UUID uuid = UUID.fromString(uuidStr);
                        
                        LOGGER.at(Level.INFO).log("PlayerDB found: %s -> %s", username, uuid);
                        return new PlayerInfo(uuid, username);
                    }
                } else if (response.statusCode() == 404) {
                    // Player not found is expected, don't log as warning
                    LOGGER.at(Level.FINE).log("Player not found on PlayerDB: %s", name);
                } else {
                    LOGGER.at(Level.WARNING).log("PlayerDB API error %d for %s", response.statusCode(), name);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                LOGGER.at(Level.WARNING).log("Failed to query PlayerDB for %s: %s", name, e.getMessage());
            }
            return null;
        });
    }
    
    /**
     * Evict expired entries from caches to prevent memory leaks.
     */
    private static void evictOldEntries() {
        long now = System.currentTimeMillis();
        uuidCache.entrySet().removeIf(e -> e.getValue().isExpired(now));
        nameCache.entrySet().removeIf(e -> e.getValue().isExpired(now));
    }
    
    /**
     * Clear all cached data. Useful for testing or manual refresh.
     */
    public static void clearCache() {
        uuidCache.clear();
        nameCache.clear();
    }
    private static class CacheEntry<T> {
        final T value;
        final long timestamp;
        
        CacheEntry(T value) {
            this.value = value;
            this.timestamp = System.currentTimeMillis();
        }
        
        boolean isExpired() {
            return isExpired(System.currentTimeMillis());
        }
        
        boolean isExpired(long now) {
            return now - timestamp > CACHE_TTL_MS;
        }
    }
    
    private static class PlayerInfo {
        final UUID uuid;
        final String username;
        
        PlayerInfo(UUID uuid, String username) {
            this.uuid = uuid;
            this.username = username;
        }
    }
}
