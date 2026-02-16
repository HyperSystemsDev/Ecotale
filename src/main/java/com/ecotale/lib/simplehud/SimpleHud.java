package com.ecotale.lib.simplehud;

import com.ecotale.util.HudHelper;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A high-level API for creating Custom HUDs in Hytale.
 * <p>
 * This abstract class handles the complexity of the Hytale UI system, providing:
 * <ul>
 *     <li>Automatic state tracking to minimize bandwidth (smart updates).</li>
 *     <li>Thread-safe updates via World.execute() for MultipleHUD compatibility.</li>
 *     <li>Incremental updates instead of full re-registration.</li>
 *     <li>Robust error handling for invalid IDs or null values.</li>
 *     <li>Fluent API for easy chaining.</li>
 * </ul>
 * 
 * Originally from SimpleHUD-Lib by Terabytez, inlined into Ecotale.
 * Enhanced for MultipleHUD compatibility by michiweon.
 */
public abstract class SimpleHud extends CustomUIHud {

    private static final Logger LOGGER = Logger.getLogger(SimpleHud.class.getName());

    private final String uiPath;
    protected final PlayerRef ownerRef;
    
    // Concurrent map to ensure thread safety during async updates
    private final Map<String, String> values = new ConcurrentHashMap<>();
    
    // Track last sent values to avoid unnecessary packet spam
    private final Map<String, String> lastSentValues = new ConcurrentHashMap<>();
    
    // Auto-size configuration
    private AutoSizeConfig autoSizeConfig = null;
    
    // Track if HUD has been shown at least once (for MultipleHUD compatibility)
    private volatile boolean initialized = false;
    
    // Cached world reference for thread-safe updates
    protected volatile World cachedWorld = null;

    /**
     * Create a new SimpleHud.
     * 
     * @param playerRef The player to show this HUD to.
     * @param uiPath The path to the .ui file (e.g., "Pages/MyPlugin_Hud.ui"). 
     */
    public SimpleHud(@Nonnull PlayerRef playerRef, @Nonnull String uiPath) {
        super(playerRef);
        this.ownerRef = playerRef;
        
        if (uiPath == null || uiPath.trim().isEmpty()) {
            throw new IllegalArgumentException("UI Path cannot be null or empty");
        }
        
        this.uiPath = uiPath;
    }

    /**
     * Set a text value for a UI element (Label).
     */
    public SimpleHud setText(@Nonnull String elementId, @Nullable String value) {
        String safeValue = (value == null) ? "" : value;
        String normalizedId = normalizeId(elementId);
        
        if (!normalizedId.contains(".")) {
            normalizedId += ".Text";
        }
        
        updateValue(normalizedId, safeValue);
        
        // If auto-size is enabled and this is the tracked element, recalculate width
        if (autoSizeConfig != null && elementId.equals(autoSizeConfig.textElementId)) {
            int calculatedWidth = autoSizeConfig.calculateWidth(safeValue);
            setProperty(autoSizeConfig.panelElementId, "Anchor.Width", String.valueOf(calculatedWidth));
        }
        
        return this;
    }
    
    /**
     * Generic method to set any property on a UI element.
     */
    public SimpleHud setProperty(@Nonnull String elementId, @Nonnull String property, @Nonnull String value) {
        String normalizedId = normalizeId(elementId);
        String fullKey = normalizedId + "." + property;
        updateValue(fullKey, value);
        return this;
    }
    
    /**
     * Set the source image for an Image element.
     */
    public SimpleHud setImage(@Nonnull String elementId, @Nonnull String imagePath) {
        String normalizedId = normalizeId(elementId);
        if (!normalizedId.contains(".")) {
            normalizedId += ".Source";
        }
        updateValue(normalizedId, imagePath);
        return this;
    }

    /**
     * Set the visibility of an element.
     */
    public SimpleHud setVisible(@Nonnull String elementId, boolean visible) {
        String value = visible ? "Visible" : "Collapsed";
        return setProperty(elementId, "Visibility", value);
    }
    
    /**
     * Enable auto-sizing for a panel based on text content.
     */
    public SimpleHud enableAutoSize(@Nonnull AutoSizeConfig config) {
        this.autoSizeConfig = config;
        return this;
    }
    
    /**
     * Enable auto-sizing with default configuration.
     */
    public SimpleHud enableAutoSize(@Nonnull String textElementId, @Nonnull String panelElementId) {
        return enableAutoSize(AutoSizeConfig.withDefaults(textElementId, panelElementId));
    }

    /**
     * Set a style property directly.
     */
    public SimpleHud setStyleProperty(@Nonnull String elementId, @Nonnull String property, @Nonnull String value) {
        return setProperty(elementId, property, value);
    }
    
    private void updateValue(String key, String value) {
        values.put(key, value);
    }
    
    private String normalizeId(String id) {
        if (id == null) return "#Unknown";
        if (!id.startsWith("#")) {
            return "#" + id;
        }
        return id;
    }

    /**
     * Refresh the cached World reference for thread-safe updates.
     * Call this periodically if the player might change worlds.
     */
    public void refreshWorldCache() {
        try {
            if (ownerRef == null) return;
            Ref<EntityStore> ref = ownerRef.getReference();
            if (ref == null || !ref.isValid()) return;
            Player player = ref.getStore().getComponent(ref, Player.getComponentType());
            if (player != null && !player.wasRemoved()) {
                this.cachedWorld = player.getWorld();
            }
        } catch (Exception e) {
            // Keep existing cache
        }
    }

    /**
     * Push all pending updates to the client with thread-safe execution.
     * 
     * This method:
     * 1. Uses World.execute() to ensure updates run on the correct WorldThread
     * 2. Registers HUD with MHUD only once (on first call)
     * 3. Uses incremental update(false) for subsequent updates
     * 
     * @param world The world to execute updates on (for thread safety). 
     *              If null, uses cached world or gets from Universe.
     */
    public void pushIncrementalUpdate(@Nullable World world) {
        // Use provided world or fall back to cached world
        World targetWorld = (world != null) ? world : this.cachedWorld;
        
        Runnable updateTask = () -> {
            try {
                if (!initialized) {
                    // First time: register with MHUD or vanilla
                    registerHudInitial();
                    initialized = true;
                } else {
                    // Subsequent updates: use update(false) for incremental changes
                    UICommandBuilder builder = new UICommandBuilder();
                    boolean hasChanges = false;
                    
                    for (Map.Entry<String, String> entry : values.entrySet()) {
                        String lastValue = lastSentValues.get(entry.getKey());
                        if (!entry.getValue().equals(lastValue)) {
                            builder.set(entry.getKey(), entry.getValue());
                            lastSentValues.put(entry.getKey(), entry.getValue());
                            hasChanges = true;
                        }
                    }
                    
                    if (hasChanges) {
                        this.update(false, builder);
                    }
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to push HUD updates: " + e.getMessage());
            }
        };
        
        // Execute on WorldThread if we have a valid World reference
        if (targetWorld != null) {
            targetWorld.execute(updateTask);
            return;
        }
        
        // Fallback: get any available World from Universe and use it to dispatch
        // This works from ANY thread - Universe.get().getWorlds() is thread-safe
        try {
            for (World w : com.hypixel.hytale.server.core.universe.Universe.get().getWorlds().values()) {
                if (w != null) {
                    this.cachedWorld = w; // Cache for future use
                    w.execute(updateTask);
                    return;
                }
            }
        } catch (Exception e) {
            // Universe not available
        }
        
        // Absolute last resort - should rarely happen
        updateTask.run();
    }

    /**
     * Register the HUD for the first time.
     * Uses MultipleHUD API if available, otherwise vanilla show().
     */
    private void registerHudInitial() {
        if (HudHelper.isMultipleHudAvailable()) {
            try {
                if (ownerRef == null) {
                    this.show();
                    return;
                }
                Ref<EntityStore> ref = ownerRef.getReference();
                if (ref == null || !ref.isValid()) {
                    this.show();
                    return;
                }
                Player player = ref.getStore().getComponent(ref, Player.getComponentType());
                if (player == null || player.wasRemoved()) {
                    this.show();
                    return;
                }
                // Register with MultipleHUD - this is called only ONCE
                HudHelper.setCustomHud(player, ownerRef, this);
                this.cachedWorld = player.getWorld();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to register with MultipleHUD, using vanilla: " + e.getMessage());
                this.show();
            }
        } else {
            // Vanilla mode
        this.show();
        }
    }

    /**
     * Push pending updates to the HUD.
     * Internally delegates to pushIncrementalUpdate for thread-safety.
     */
    public void pushUpdates() {
        // Delegate to new method with cached world
        pushIncrementalUpdate(this.cachedWorld);
    }
    
    /**
     * Force full rebuild of the HUD.
     * Same as pushUpdates() but with explicit naming.
     */
    public void forceFullRebuild() {
        pushUpdates();
    }

    @Override
    protected void build(@Nonnull UICommandBuilder builder) {
        try {
            builder.append(uiPath);

            for (Map.Entry<String, String> entry : values.entrySet()) {
                builder.set(entry.getKey(), entry.getValue());
                lastSentValues.put(entry.getKey(), entry.getValue());
            }
            
            onBuild(builder);
            
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "CRITICAL: Failed to build HUD " + uiPath, e);
        }
    }
    
    /**
     * Lifecycle hook called during the build process.
     */
    protected void onBuild(UICommandBuilder builder) {
        // Optional override
    }
    
    /**
     * Show a temporary UI element that auto-removes after a delay.
     */
    public void showTemporary(@Nonnull String id, @Nonnull String parentSelector, 
                              @Nonnull String uiCode, long durationMs) {
        try {
            UICommandBuilder builder = new UICommandBuilder();
            builder.appendInline(parentSelector, uiCode);
            this.update(false, builder);
            
            if (durationMs > 0) {
                HudScheduler.runLater(() -> removeElement("#" + id), durationMs);
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to show temporary element: " + id, e);
        }
    }
    
    /**
     * Remove a UI element by its selector.
     */
    public void removeElement(@Nonnull String selector) {
        try {
            UICommandBuilder builder = new UICommandBuilder();
            builder.remove(selector);
            this.update(false, builder);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to remove element: " + selector, e);
        }
    }
    
    /**
     * Send an incremental update without rebuilding the entire HUD.
     */
    public void sendUpdate(@Nonnull UICommandBuilder builder) {
        try {
            this.update(false, builder);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to send incremental HUD update", e);
        }
    }
    
    /**
     * Create a new UICommandBuilder for building incremental updates.
     */
    public UICommandBuilder createBuilder() {
        return new UICommandBuilder();
    }
}
