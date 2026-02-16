package com.ecotale.lib.placeholder;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Lightweight TTL cache for placeholder values that are expensive to compute.
 * <p>
 * Used for: leaderboard sorting, total circulating, median balance, per-player rank.
 * <p>
 * Thread-safe via {@link ConcurrentHashMap}. No scheduled cleanup — stale entries
 * are lazily replaced on next {@link #get}. The cache is small (under 200 keys at peak)
 * so unbounded growth is not a concern.
 * <p>
 * The minor race where two threads both compute a stale entry simultaneously is
 * acceptable: the computation is idempotent and the cost of synchronizing
 * outweighs the cost of an occasional duplicate computation on a 30s TTL cache.
 */
public final class PlaceholderCache {

    private final ConcurrentHashMap<String, CacheEntry<?>> entries = new ConcurrentHashMap<>();
    private final long defaultTtlMs;

    /**
     * @param defaultTtlMs Default time-to-live in milliseconds (e.g., 30_000 for 30s)
     */
    public PlaceholderCache(long defaultTtlMs) {
        this.defaultTtlMs = defaultTtlMs;
    }

    /**
     * Get a cached value, computing it if absent or expired.
     *
     * @param key      Cache key (e.g., "leaderboard", "total_circulating")
     * @param supplier Computation to run on cache miss. Must not return null.
     * @param <T>      Value type
     * @return Cached or freshly computed value
     */
    public <T> T get(String key, Supplier<T> supplier) {
        return get(key, supplier, defaultTtlMs);
    }

    /**
     * Get with custom TTL override.
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Supplier<T> supplier, long ttlMs) {
        long now = System.currentTimeMillis();
        CacheEntry<?> existing = entries.get(key);
        if (existing != null && (now - existing.timestamp) < ttlMs) {
            return (T) existing.value;
        }
        T value = supplier.get();
        entries.put(key, new CacheEntry<>(value, now));
        return value;
    }

    /** Invalidate a specific key. */
    public void invalidate(String key) {
        entries.remove(key);
    }

    /** Invalidate all cached entries. */
    public void invalidateAll() {
        entries.clear();
    }

    private record CacheEntry<T>(T value, long timestamp) {}
}
