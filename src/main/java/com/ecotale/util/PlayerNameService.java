package com.ecotale.util;

import com.ecotale.api.PlayerDBService;
import com.ecotale.storage.StorageProvider;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Centralized player name resolution service.
 * <p>
 * Provides a single point of truth for resolving player names across the entire plugin,
 * replacing 5 different fragmented implementations. Supports 3 levels of resolution:
 * <ol>
 *   <li><b>In-memory cache</b> — populated at startup via {@link #warmup()} and on player join</li>
 *   <li><b>Storage DB</b> — queries the active storage backend (H2/MySQL/MongoDB)</li>
 *   <li><b>PlayerDB HTTP API</b> — external API fallback for unknown players</li>
 * </ol>
 *
 * <p>Two resolution modes:
 * <ul>
 *   <li>{@link #resolve(UUID)} — sync, no I/O, for placeholders and GUIs</li>
 *   <li>{@link #resolveAsync(UUID)} — async with DB/HTTP fallback, for commands</li>
 * </ul>
 */
public final class PlayerNameService {

    private static final int UUID_PREVIEW_LENGTH = 8;

    private static PlayerNameService instance;

    private final ConcurrentHashMap<UUID, String> cache = new ConcurrentHashMap<>();
    private final StorageProvider storage;

    public PlayerNameService(@Nonnull StorageProvider storage) {
        this.storage = storage;
        instance = this;
    }

    @Nullable
    public static PlayerNameService getInstance() {
        return instance;
    }

    /**
     * Load all known player names from the storage backend into memory.
     * Called once at plugin startup. Safe to call on any backend —
     * JSON returns an empty map (default).
     */
    public void warmup() {
        cache.putAll(storage.getAllPlayerNamesSync());
    }

    /**
     * Update name cache and storage on player join.
     *
     * @param uuid Player UUID
     * @param name Current username
     */
    public void onPlayerJoin(@Nonnull UUID uuid, @Nonnull String name) {
        cache.put(uuid, name);
        storage.updatePlayerName(uuid, name);
    }
    /**
     * Resolve a player name synchronously. No I/O, no blocking.
     * <p>
     * Resolution chain: cache -> Universe (online) -> PlayerDB cache -> truncated UUID.
     * <p>
     * Use this in placeholders, GUIs, and logging where blocking is not acceptable.
     * The cache is pre-populated at startup via {@link #warmup()} and updated on
     * player join, so cache misses are rare for known players.
     *
     * @param uuid Player UUID
     * @return Resolved name or truncated UUID (never null)
     */
    @Nonnull
    public String resolve(@Nonnull UUID uuid) {
        // 1. In-memory cache (warmup + join)
        String cached = cache.get(uuid);
        if (cached != null) {
            return cached;
        }

        // 2. Online player (Universe)
        PlayerRef player = Universe.get().getPlayer(uuid);
        if (player != null) {
            String name = player.getUsername();
            cache.put(uuid, name);
            return name;
        }

        // 3. PlayerDB in-memory cache (populated by prior API calls)
        String apiCached = PlayerDBService.getCachedName(uuid);
        if (apiCached != null) {
            cache.put(uuid, apiCached);
            return apiCached;
        }

        // 4. Truncated UUID fallback
        return uuid.toString().substring(0, UUID_PREVIEW_LENGTH) + "...";
    }
    /**
     * Resolve a player name asynchronously with full fallback chain.
     * <p>
     * Resolution chain: sync resolve -> Storage DB -> PlayerDB HTTP.
     * <p>
     * Use this in commands where the caller can wait for a {@link CompletableFuture}.
     * Names resolved via DB or HTTP are cached for future sync calls.
     *
     * @param uuid Player UUID
     * @return Future containing the resolved name (never null in the result)
     */
    @Nonnull
    public CompletableFuture<String> resolveAsync(@Nonnull UUID uuid) {
        // Try sync resolution first
        String fast = resolve(uuid);
        if (!fast.endsWith("...")) {
            return CompletableFuture.completedFuture(fast);
        }

        // Async: Storage DB
        return storage.getPlayerNameAsync(uuid).thenCompose(dbName -> {
            if (dbName != null && !dbName.isBlank()) {
                cache.put(uuid, dbName);
                return CompletableFuture.completedFuture(dbName);
            }

            // Async: PlayerDB HTTP
            return PlayerDBService.lookupName(uuid).thenApply(apiName -> {
                if (apiName != null) {
                    cache.put(uuid, apiName);
                    storage.updatePlayerName(uuid, apiName);
                    return apiName;
                }
                return uuid.toString().substring(0, UUID_PREVIEW_LENGTH) + "...";
            });
        });
    }

    /**
     * Resolve a player UUID by username asynchronously.
     * <p>
     * Resolution chain: Universe (online scan) -> Storage DB -> PlayerDB HTTP.
     * <p>
     * Names/UUIDs resolved via DB or HTTP are cached for future calls.
     *
     * @param name Player username
     * @return Future containing UUID if found, or null
     */
    @Nonnull
    public CompletableFuture<UUID> resolveUuid(@Nonnull String name) {
        // 1. Online player scan
        for (PlayerRef p : Universe.get().getPlayers()) {
            if (p.getUsername().equalsIgnoreCase(name)) {
                return CompletableFuture.completedFuture(p.getUuid());
            }
        }

        // 2. Storage DB
        return storage.getPlayerUuidByName(name).thenCompose(uuid -> {
            if (uuid != null) {
                return CompletableFuture.completedFuture(uuid);
            }

            // 3. PlayerDB HTTP
            return PlayerDBService.lookupUuid(name).thenApply(apiUuid -> {
                if (apiUuid != null) {
                    cache.put(apiUuid, name);
                    storage.updatePlayerName(apiUuid, name);
                }
                return apiUuid;
            });
        });
    }
    /**
     * Unmodifiable view of all cached player names.
     * Used by EcoAdminGui for bulk name display.
     *
     * @return Map of UUID to player name
     */
    @Nonnull
    public Map<UUID, String> getAllCached() {
        return Collections.unmodifiableMap(cache);
    }
}
