package com.ecotale;

import com.ecotale.api.EcotaleAPI;
import com.ecotale.commands.BalanceCommand;
import com.ecotale.commands.EcoAdminCommand;
import com.ecotale.commands.TopBalanceCommand;
import com.ecotale.commands.PayCommand;
import com.ecotale.config.EcotaleConfig;
import com.ecotale.economy.EconomyManager;
import com.ecotale.hud.BalanceHud;
import com.ecotale.lib.cassaforte.CassaforteSetup;
import com.ecotale.lib.placeholder.PlaceholderManager;
import com.ecotale.lib.vaultunlocked.VaultUnlockedPlugin;
import com.ecotale.storage.H2StorageProvider;
import com.ecotale.util.PlayerNameService;
import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.common.semver.SemverRange;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.util.Config;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

import java.util.logging.Level;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Ecotale - Economy plugin for Hytale
 * 
 * Features:
 * - Player balances with persistent storage
 * - On-screen HUD balance display
 * - Player-to-player transfers
 * - Admin economy commands
 * - Public API for other plugins
 */
public class Main extends JavaPlugin {
    
    private static Main instance;
    public static Config<EcotaleConfig> CONFIG;
    
    private EconomyManager economyManager;
    private PlaceholderManager placeholderManager;
    private ScheduledExecutorService snapshotScheduler;
    
    public Main(@NonNullDecl final JavaPluginInit init) {
        super(init);
        CONFIG = this.withConfig("Ecotale", EcotaleConfig.CODEC);
    }
    
    @Override
    protected void setup() {
        super.setup();
        instance = this;
        CONFIG.save();
        
        // Initialize economy manager
        this.economyManager = new EconomyManager(this);

        // Schedule daily balance snapshot if H2 is active
        H2StorageProvider h2 = this.economyManager.getH2Storage();
        if (h2 != null) {
            this.snapshotScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "Ecotale-TopBalance-Snapshot");
                t.setDaemon(false);
                return t;
            });
            LocalTime snapshotTime = parseSnapshotTime(CONFIG.get().getTopBalanceSnapshotTime());
            ZoneId zoneId = parseSnapshotZone(CONFIG.get().getTopBalanceSnapshotTimeZone());
            long initialDelay = computeInitialDelayMillis(snapshotTime, zoneId);
            this.snapshotScheduler.scheduleAtFixedRate(
                () -> h2.snapshotForDateAsync(LocalDate.now(zoneId)),
                initialDelay,
                TimeUnit.DAYS.toMillis(1),
                TimeUnit.MILLISECONDS
            );
        }
        
        // Initialize centralized player name service (warmup loads names from DB)
        new PlayerNameService(this.economyManager.getStorageProvider()).warmup();

        // Initialize API with configured rate limiting
        EcotaleAPI.init(
            this.economyManager,
            CONFIG.get().getRateLimitBurst(),
            CONFIG.get().getRateLimitRefill()
        );

        // Check for VaultUnlocked support
        initVaultUnlocked();

        // Check for Cassaforte support
        initCassaforte();

        // Initialize placeholder integrations (PlaceholderAPI, WiFlowPlaceholderAPI)
        this.placeholderManager = PlaceholderManager.init(this.getLogger());

        // Check for MultipleHUD compatibility
        com.ecotale.util.HudHelper.init();
        
        // Register commands
        this.getCommandRegistry().registerCommand(new BalanceCommand());
        this.getCommandRegistry().registerCommand(new PayCommand());
        this.getCommandRegistry().registerCommand(new EcoAdminCommand());
        this.getCommandRegistry().registerCommand(new TopBalanceCommand());
        
        // Register player join event for HUD setup
        this.getEventRegistry().registerGlobal(AddPlayerToWorldEvent.class, (event) -> {
            final var player = event.getHolder().getComponent(Player.getComponentType());
            final var playerRef = event.getHolder().getComponent(PlayerRef.getComponentType());
            
            if (player != null && playerRef != null) {
                // Ensure player has an account
                this.economyManager.ensureAccount(playerRef.getUuid());

                // Snapshot balance at login for session-based placeholders
                if (this.placeholderManager != null) {
                    var pb = this.economyManager.getPlayerBalance(playerRef.getUuid());
                    double loginBalance = (pb != null) ? pb.getBalance() : 0.0;
                    this.placeholderManager.onPlayerJoin(playerRef.getUuid(), loginBalance);
                }

                // Cache player name for leaderboards (all backends via centralized service)
                PlayerNameService.getInstance().onPlayerJoin(playerRef.getUuid(), playerRef.getUsername());
                
                // Setup Balance HUD if enabled
                if (Main.CONFIG.get().isEnableHudDisplay()) {
                    // Check player's HUD visibility preference
                    boolean hudVisible = true;
                    var storage = this.economyManager.getStorageProvider();
                    
                    // Use sync method if available to avoid async join delay
                    if (storage instanceof com.ecotale.storage.H2StorageProvider h2StorageHud) {
                        hudVisible = h2StorageHud.getHudVisibleSync(playerRef.getUuid());
                    } else if (storage instanceof com.ecotale.storage.MySQLStorageProvider mysqlStorage) {
                        hudVisible = mysqlStorage.getHudVisibleSync(playerRef.getUuid());
                    } else if (storage instanceof com.ecotale.storage.JsonStorageProvider jsonStorage) {
                        hudVisible = jsonStorage.getHudVisibleSync(playerRef.getUuid());
                    } else if (storage instanceof com.ecotale.storage.MongoDBStorageProvider mongoStorage) {
                        hudVisible = mongoStorage.getHudVisibleSync(playerRef.getUuid());
                    }
                    
                    if (hudVisible) {
                        // Check if player already has a HUD (world change, not first join)
                        BalanceHud existingHud = com.ecotale.systems.BalanceHudSystem.getHud(playerRef.getUuid());
                        if (existingHud != null) {
                            // Reuse existing HUD, just re-register with HudManager
                            com.ecotale.util.HudHelper.setCustomHud(player, playerRef, existingHud);
                        } else {
                            // First join - create new HUD
                            BalanceHud hud = new BalanceHud(playerRef);
                            com.ecotale.util.HudHelper.setCustomHud(player, playerRef, hud);
                            com.ecotale.systems.BalanceHudSystem.registerHud(playerRef.getUuid(), hud);
                        }
                    }
                }
            }
        });
        
        // Cleanup when player disconnects
        this.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, (event) -> {
            var playerRef = event.getPlayerRef();
            if (playerRef != null) {
                com.ecotale.systems.BalanceHudSystem.removePlayerHud(playerRef.getUuid());
                // TODO: PERF-03 Lock eviction - method not yet implemented
                // this.economyManager.scheduleEviction(playerRef.getUuid());
                com.ecotale.api.EcotaleAPI.resetRateLimit(playerRef.getUuid());

                // Cleanup placeholder session data
                if (this.placeholderManager != null) {
                    this.placeholderManager.onPlayerLeave(playerRef.getUuid());
                }
            }
        });
        


        // Initialize security logger
        // Initialize performance monitor
        new com.ecotale.util.PerformanceMonitor();

        this.getLogger().at(Level.INFO).log("Ecotale Economy loaded - HUD balance display active!");
    }

    private void initCassaforte() {
        if (HytaleServer.get().getPluginManager().hasPlugin(
                PluginIdentifier.fromString("com.cassaforte:Cassaforte"),
                SemverRange.WILDCARD
                                                           )) {
            CassaforteSetup.setup(this.getLogger());
        } else {
            this.getLogger().at(Level.INFO).log("Cassaforte is not installed, disabling Cassaforte support.");
        }
    }

    private void initVaultUnlocked() {
        if (HytaleServer.get().getPluginManager().hasPlugin(
                PluginIdentifier.fromString("TheNewEconomy:VaultUnlocked"),
                SemverRange.WILDCARD
                                                           )) {
            this.getLogger().atInfo().log("VaultUnlocked is installed, enabling VaultUnlocked support.");

            VaultUnlockedPlugin.setup(this.getLogger());

        } else {
            this.getLogger().at(Level.INFO).log("VaultUnlocked is not installed, disabling VaultUnlocked support.");
        }
    }
    
    @Override
    protected void shutdown() {
        this.getLogger().at(Level.INFO).log("Ecotale shutting down - saving data...");
        
        // Shutdown security logger
        if (com.ecotale.security.SecurityLogger.getInstance() != null) {
            com.ecotale.security.SecurityLogger.getInstance().shutdown();
        }

        // Shutdown performance monitor
        if (com.ecotale.util.PerformanceMonitor.getInstance() != null) {
            com.ecotale.util.PerformanceMonitor.getInstance().shutdown();
        }

        // Shutdown placeholder system before economy (placeholders depend on economy data)
        if (this.placeholderManager != null) {
            this.placeholderManager.shutdown();
        }

        if (this.economyManager != null) {
            this.economyManager.shutdown();
        }
        if (this.snapshotScheduler != null) {
            this.snapshotScheduler.shutdown();
        }
        this.getLogger().at(Level.INFO).log("Ecotale shutdown complete!");
    }
    
    public static Main getInstance() {
        return instance;
    }
    
    public EconomyManager getEconomyManager() {
        return this.economyManager;
    }

    public PlaceholderManager getPlaceholderManager() {
        return this.placeholderManager;
    }

    private static LocalTime parseSnapshotTime(String value) {
        try {
            return LocalTime.parse(value);
        } catch (Exception e) {
            return LocalTime.of(3, 0);
        }
    }

    private static ZoneId parseSnapshotZone(String value) {
        try {
            if (value == null || value.isBlank() || value.equalsIgnoreCase("System")) {
                return ZoneId.systemDefault();
            }
            return ZoneId.of(value);
        } catch (Exception e) {
            return ZoneId.systemDefault();
        }
    }

    private static long computeInitialDelayMillis(LocalTime snapshotTime, ZoneId zoneId) {
        ZonedDateTime now = ZonedDateTime.now(zoneId);
        ZonedDateTime next = now.with(snapshotTime);
        if (!next.isAfter(now)) {
            next = next.plusDays(1);
        }
        return Duration.between(now, next).toMillis();
    }

}
