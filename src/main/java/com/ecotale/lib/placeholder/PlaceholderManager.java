package com.ecotale.lib.placeholder;

import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.common.semver.SemverRange;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.HytaleServer;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Discovers available placeholder APIs and manages the lifecycle of providers.
 * <p>
 * Detection uses {@link HytaleServer#getPluginManager()} — same pattern as the
 * VaultUnlocked and Cassaforte integrations. Adapter classes are instantiated via
 * reflection to prevent {@link NoClassDefFoundError} when the API plugin is absent.
 * <p>
 * Usage in Main.java:
 * <pre>
 *   // In setup():
 *   placeholderManager = PlaceholderManager.init(this.getLogger());
 *
 *   // On player join:
 *   placeholderManager.onPlayerJoin(uuid, balance);
 *
 *   // In shutdown():
 *   if (placeholderManager != null) placeholderManager.shutdown();
 * </pre>
 */
public final class PlaceholderManager {

    private static final long CACHE_TTL_MS = 30_000;

    private final HytaleLogger logger;
    private final PlaceholderCache cache;
    private final List<PlaceholderProvider> providers = new ArrayList<>();

    private PlaceholderManager(HytaleLogger logger) {
        this.logger = logger;
        this.cache = new PlaceholderCache(CACHE_TTL_MS);
    }

    /**
     * Initialize the placeholder system. Detects available APIs, registers adapters.
     *
     * @param logger Plugin logger
     * @return Manager instance (even if no APIs found, for clean shutdown)
     */
    public static PlaceholderManager init(@Nonnull HytaleLogger logger) {
        PlaceholderManager manager = new PlaceholderManager(logger);
        EcotalePlaceholders.init(manager.cache);

        // Detect and register adapters
        manager.tryRegister(
                "HelpChat:PlaceholderAPI",
                "com.ecotale.lib.placeholder.papi.PapiExpansion"
        );
        manager.tryRegister(
                "com.wiflow:WiFlowPlaceholderAPI",
                "com.ecotale.lib.placeholder.wiflow.WiFlowExpansion"
        );

        long count = manager.providers.stream().filter(PlaceholderProvider::isExpansionRegistered).count();
        if (count > 0) {
            logger.atInfo().log("Placeholder integration active: %d provider(s) registered.", count);
        } else {
            logger.atInfo().log("No placeholder APIs detected. Placeholder integration disabled.");
        }

        return manager;
    }

    /**
     * Try to detect a placeholder API and register a provider via reflection.
     * <p>
     * Reflection is required because the adapter class extends a class from the
     * API plugin (e.g., {@code at.helpch.placeholderapi.expansion.PlaceholderExpansion}).
     * If we referenced the adapter directly, the JVM would attempt to load the superclass
     * at class-load time, causing {@link NoClassDefFoundError} when the API is absent.
     */
    private void tryRegister(String pluginId, String adapterFqcn) {
        boolean present = HytaleServer.get().getPluginManager()
                .hasPlugin(PluginIdentifier.fromString(pluginId), SemverRange.WILDCARD);

        if (!present) {
            logger.atInfo().log("%s not detected, skipping.", pluginId);
            return;
        }

        try {
            Class<?> adapterClass = Class.forName(adapterFqcn);
            PlaceholderProvider provider = (PlaceholderProvider) adapterClass
                    .getDeclaredConstructor()
                    .newInstance();

            if (provider.registerExpansion()) {
                providers.add(provider);
                logger.atInfo().log("Registered Ecotale expansion with %s.", provider.name());
            } else {
                logger.at(Level.WARNING).log("Failed to register expansion with %s.", provider.name());
            }
        } catch (Exception e) {
            logger.at(Level.WARNING).log("Error initializing %s adapter: %s", pluginId, e.getMessage());
        }
    }
    /**
     * Snapshot the player's balance at login for session-based placeholders
     * (session_change, trend_session, etc.).
     *
     * @param playerUuid Player UUID
     * @param balance    Current balance at the time of login
     */
    public void onPlayerJoin(@Nonnull UUID playerUuid, double balance) {
        EcotalePlaceholders.loginBalances.put(playerUuid, balance);
    }

    /**
     * Clean up session data on player leave. Prevents unbounded memory growth.
     *
     * @param playerUuid Player UUID
     */
    public void onPlayerLeave(@Nonnull UUID playerUuid) {
        EcotalePlaceholders.loginBalances.remove(playerUuid);
        // Also invalidate per-player rank cache
        cache.invalidate("rank_" + playerUuid);
    }
    /**
     * Shutdown all providers. Must be called before economy shutdown.
     */
    public void shutdown() {
        for (PlaceholderProvider provider : providers) {
            try {
                provider.unregisterExpansion();
                logger.atInfo().log("Unregistered expansion from %s.", provider.name());
            } catch (Exception e) {
                logger.at(Level.WARNING).log("Error unregistering from %s: %s", provider.name(), e.getMessage());
            }
        }
        providers.clear();
        EcotalePlaceholders.loginBalances.clear();
        cache.invalidateAll();
    }

    /** Unmodifiable view of registered providers (for debug/admin). */
    public List<PlaceholderProvider> getProviders() {
        return Collections.unmodifiableList(providers);
    }

    /** Shared cache instance (for manual invalidation after admin commands). */
    public PlaceholderCache getCache() {
        return cache;
    }
}
