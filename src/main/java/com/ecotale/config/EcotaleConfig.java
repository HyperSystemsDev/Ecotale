package com.ecotale.config;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

import java.text.DecimalFormat;

/**
 * Configuration for Ecotale economy plugin.
 * 
 * All settings are saved to Ecotale.json in the universe folder.
 * 
 * NOTE: BuilderCodec keys MUST start with uppercase (PascalCase)
 */
public class EcotaleConfig {
    
    public static final BuilderCodec<EcotaleConfig> CODEC = BuilderCodec.builder(EcotaleConfig.class, EcotaleConfig::new)
        // Currency settings
        .append(new KeyedCodec<>("CurrencySymbol", Codec.STRING), 
            (c, v, e) -> c.currencySymbol = v, (c, e) -> c.currencySymbol).add()
        .append(new KeyedCodec<>("HudPrefix", Codec.STRING),
            (c, v, e) -> c.hudPrefix = v, (c, e) -> c.hudPrefix).add()
        
        // Balance settings
        .append(new KeyedCodec<>("StartingBalance", Codec.DOUBLE),
            (c, v, e) -> c.startingBalance = v, (c, e) -> c.startingBalance).add()
        .append(new KeyedCodec<>("MaxBalance", Codec.DOUBLE),
            (c, v, e) -> c.maxBalance = v, (c, e) -> c.maxBalance).add()
        
        // Transaction settings
        .append(new KeyedCodec<>("TransferFee", Codec.DOUBLE),
            (c, v, e) -> c.transferFee = v, (c, e) -> c.transferFee).add()
        .append(new KeyedCodec<>("MinimumTransaction", Codec.DOUBLE),
            (c, v, e) -> c.minimumTransaction = v, (c, e) -> c.minimumTransaction).add()
        
        // Rate limiting
        .append(new KeyedCodec<>("RateLimitBurst", Codec.INTEGER),
            (c, v, e) -> c.rateLimitBurst = v, (c, e) -> c.rateLimitBurst).add()
        .append(new KeyedCodec<>("RateLimitRefill", Codec.INTEGER),
            (c, v, e) -> c.rateLimitRefill = v, (c, e) -> c.rateLimitRefill).add()
        
        // Storage settings
        .append(new KeyedCodec<>("StorageProvider", Codec.STRING),
            (c, v, e) -> c.storageProvider = v, (c, e) -> c.storageProvider).add()
        .append(new KeyedCodec<>("EnableBackups", Codec.BOOLEAN),
            (c, v, e) -> c.enableBackups = v, (c, e) -> c.enableBackups).add()
        
        // MySQL settings (only used if StorageProvider = "mysql")
        .append(new KeyedCodec<>("MysqlHost", Codec.STRING),
            (c, v, e) -> c.mysqlHost = v, (c, e) -> c.mysqlHost).add()
        .append(new KeyedCodec<>("MysqlPort", Codec.INTEGER),
            (c, v, e) -> c.mysqlPort = v, (c, e) -> c.mysqlPort).add()
        .append(new KeyedCodec<>("MysqlDatabase", Codec.STRING),
            (c, v, e) -> c.mysqlDatabase = v, (c, e) -> c.mysqlDatabase).add()
        .append(new KeyedCodec<>("MysqlUsername", Codec.STRING),
            (c, v, e) -> c.mysqlUsername = v, (c, e) -> c.mysqlUsername).add()
        .append(new KeyedCodec<>("MysqlPassword", Codec.STRING),
            (c, v, e) -> c.mysqlPassword = v, (c, e) -> c.mysqlPassword).add()
        .append(new KeyedCodec<>("MysqlTablePrefix", Codec.STRING),
            (c, v, e) -> c.mysqlTablePrefix = v, (c, e) -> c.mysqlTablePrefix).add()
        
        // MongoDB settings (only used if StorageProvider = "mongodb")
        .append(new KeyedCodec<>("MongoUri", Codec.STRING),
            (c, v, e) -> c.mongoUri = v, (c, e) -> c.mongoUri).add()
        .append(new KeyedCodec<>("MongoDatabase", Codec.STRING),
            (c, v, e) -> c.mongoDatabase = v, (c, e) -> c.mongoDatabase).add()
        
        // Auto-save
        .append(new KeyedCodec<>("AutoSaveInterval", Codec.INTEGER),
            (c, v, e) -> c.autoSaveInterval = v, (c, e) -> c.autoSaveInterval).add()

        // Top balance snapshot schedule
        .append(new KeyedCodec<>("TopBalanceSnapshotTime", Codec.STRING),
            (c, v, e) -> c.topBalanceSnapshotTime = v, (c, e) -> c.topBalanceSnapshotTime).add()
        .append(new KeyedCodec<>("TopBalanceSnapshotTimeZone", Codec.STRING),
            (c, v, e) -> c.topBalanceSnapshotTimeZone = v, (c, e) -> c.topBalanceSnapshotTimeZone).add()
        
        // HUD settings
        .append(new KeyedCodec<>("EnableHudDisplay", Codec.BOOLEAN),
            (c, v, e) -> c.enableHudDisplay = v, (c, e) -> c.enableHudDisplay).add()
        .append(new KeyedCodec<>("EnableHudAnimation", Codec.BOOLEAN),
            (c, v, e) -> c.enableHudAnimation = v, (c, e) -> c.enableHudAnimation).add()
        .append(new KeyedCodec<>("UseHudTranslation", Codec.BOOLEAN),
            (c, v, e) -> c.useHudTranslation = v, (c, e) -> c.useHudTranslation).add()
        .append(new KeyedCodec<>("SymbolOnRight", Codec.BOOLEAN),
            (c, v, e) -> c.symbolOnRight = v, (c, e) -> c.symbolOnRight).add()
        .append(new KeyedCodec<>("DecimalPlaces", Codec.INTEGER),
            (c, v, e) -> c.decimalPlaces = v, (c, e) -> c.decimalPlaces).add()
        
        // Language settings
        .append(new KeyedCodec<>("Language", Codec.STRING),
            (c, v, e) -> c.language = v, (c, e) -> c.language).add()
        .append(new KeyedCodec<>("UsePlayerLanguage", Codec.BOOLEAN),
            (c, v, e) -> c.usePlayerLanguage = v, (c, e) -> c.usePlayerLanguage).add()
        
        // Debug mode
        .append(new KeyedCodec<>("DebugMode", Codec.BOOLEAN),
            (c, v, e) -> c.debugMode = v, (c, e) -> c.debugMode).add()
        .build();
    
    // Currency
    private String currencySymbol = "$";
    private String hudPrefix = "Bank";
    
    // Balance limits
    private double startingBalance = 100.0;
    private double maxBalance = 1_000_000_000.0; // 1 billion
    
    // Transactions
    private double transferFee = 0.05; // 5% fee
    private double minimumTransaction = 1.0;
    
    // Rate limiting (token bucket)
    private int rateLimitBurst = 50;    // Max burst capacity
    private int rateLimitRefill = 10;   // Tokens per second
    
    // Storage - "h2" (default), "json" (file-based), or "mysql" (shared database)
    private String storageProvider = "h2";
    private boolean enableBackups = true;
    
    // MySQL settings (only used if storageProvider = "mysql")
    private String mysqlHost = "localhost";
    private int mysqlPort = 3306;
    private String mysqlDatabase = "ecotale";
    private String mysqlUsername = "root";
    private String mysqlPassword = "";
    private String mysqlTablePrefix = "eco_";
    
    // MongoDB settings (only used if storageProvider = "mongodb")
    private String mongoUri = "mongodb://localhost:27017";
    private String mongoDatabase = "ecotale";
    
    // Auto-save
    private int autoSaveInterval = 300; // 5 minutes in seconds

    // Top balance snapshot schedule (HH:mm) + timezone
    private String topBalanceSnapshotTime = "03:00";
    private String topBalanceSnapshotTimeZone = "System"; // "System" = system default
    
    // HUD
    private boolean enableHudDisplay = true;
    private boolean enableHudAnimation = false; // Default false for stability with MultipleHUD
    private boolean useHudTranslation = false;  // Default false: use raw config HudPrefix value
    private boolean symbolOnRight = false;       // Default false: symbol on left (e.g., "$ 100")
    private int decimalPlaces = 2;
    
    // Language - "en-US" or "es-ES", etc.
    private String language = "en-US";
    private boolean usePlayerLanguage = true; // true = use player's client language
    
    // Debug mode - enables verbose logging and /ecotest command
    private boolean debugMode = false; // Set true for development/troubleshooting
    
    public EcotaleConfig() {}
    /** 
     * Get the currency symbol displayed before amounts (e.g., "$").
     * @return Currency symbol string
     */
    public String getCurrencySymbol() { return currencySymbol; }
    
    /**
     * Set the currency symbol.
     * @param symbol Symbol to display (e.g., "$", "€", "¥")
     */
    public void setCurrencySymbol(String symbol) { this.currencySymbol = symbol; }
    
    /**
     * Get the HUD prefix label (e.g., "Bank").
     * @return HUD prefix string
     */
    public String getHudPrefix() { return hudPrefix; }
    
    /**
     * Set the HUD prefix label.
     * @param prefix Prefix to display in HUD (e.g., "Bank", "Wealth")
     */
    public void setHudPrefix(String prefix) { this.hudPrefix = prefix; }
    /**
     * Get the starting balance for new players.
     * @return Starting balance amount
     */
    public double getStartingBalance() { return startingBalance; }
    
    /**
     * Set the starting balance for new players.
     * @param balance Starting amount (must be >= 0)
     */
    public void setStartingBalance(double balance) { this.startingBalance = balance; }
    
    /**
     * Get the maximum balance a player can have.
     * Deposits that would exceed this limit are rejected.
     * @return Maximum balance (default: 1 billion)
     */
    public double getMaxBalance() { return maxBalance; }
    
    /**
     * Set the maximum balance limit.
     * @param balance Maximum amount (must be > 0)
     */
    public void setMaxBalance(double balance) { this.maxBalance = balance; }
    /**
     * Get the transfer fee as a decimal (e.g., 0.05 = 5%).
     * This fee is charged to the sender on player-to-player transfers.
     * @return Fee percentage as decimal (0.0 to 1.0)
     */
    public double getTransferFee() { return transferFee; }
    
    /**
     * Set the transfer fee percentage.
     * @param fee Fee as decimal (0.05 = 5%, 0.0 = no fee)
     */
    public void setTransferFee(double fee) { this.transferFee = fee; }
    
    /**
     * Get the minimum transaction amount.
     * @return Minimum amount for deposits/withdrawals
     */
    public double getMinimumTransaction() { return minimumTransaction; }
    /**
     * Get the rate limit burst capacity for API calls.
     * This is the maximum number of requests that can be made instantly.
     * @return Burst capacity (default: 50)
     */
    public int getRateLimitBurst() { return rateLimitBurst; }
    
    /**
     * Get the rate limit refill rate.
     * This is how many tokens are added per second.
     * @return Tokens per second (default: 10)
     */
    public int getRateLimitRefill() { return rateLimitRefill; }
    /**
     * Get the storage provider type.
     * @return "h2" for H2 database, "json" for JSON files, or "mysql" for MySQL database
     */
    public String getStorageProvider() { return storageProvider; }
    
    /**
     * Check if automatic backups are enabled.
     * @return true if backups are enabled
     */
    public boolean isEnableBackups() { return enableBackups; }
    /** Get MySQL host address. @return Host (default: "localhost") */
    public String getMysqlHost() { return mysqlHost; }
    
    /** Get MySQL port. @return Port number (default: 3306) */
    public int getMysqlPort() { return mysqlPort; }
    
    /** Get MySQL database name. @return Database name (default: "ecotale") */
    public String getMysqlDatabase() { return mysqlDatabase; }
    
    /** Get MySQL username. @return Username (default: "root") */
    public String getMysqlUsername() { return mysqlUsername; }
    
    /** Get MySQL password. @return Password (default: empty) */
    public String getMysqlPassword() { return mysqlPassword; }
    
    /** Get MySQL table prefix. @return Prefix for tables (default: "eco_") */
    public String getMysqlTablePrefix() { return mysqlTablePrefix; }
    /** Get MongoDB connection URI. @return URI (default: "mongodb://localhost:27017") */
    public String getMongoUri() { return mongoUri; }
    
    /** Get MongoDB database name. @return Database name (default: "ecotale") */
    public String getMongoDatabase() { return mongoDatabase; }
    /**
     * Get the auto-save interval in seconds.
     * Dirty player data is saved automatically at this interval.
     * @return Interval in seconds (default: 300 = 5 minutes)
     */
    public int getAutoSaveInterval() { return autoSaveInterval; }

    public String getTopBalanceSnapshotTime() { return topBalanceSnapshotTime; }
    public String getTopBalanceSnapshotTimeZone() { return topBalanceSnapshotTimeZone; }
    /**
     * Check if the on-screen balance HUD is enabled.
     * @return true if HUD should be displayed to players
     */
    public boolean isEnableHudDisplay() { return enableHudDisplay; }
    
    /**
     * Enable or disable the balance HUD.
     * @param enabled true to show HUD to players
     */
    public void setEnableHudDisplay(boolean enabled) { this.enableHudDisplay = enabled; }
    
    /**
     * Get the number of decimal places for balance display.
     * @return Decimal places (0-4, default: 2)
     */
    public int getDecimalPlaces() { return decimalPlaces; }
    
    /**
     * Set the number of decimal places.
     * @param places Decimal places (0 for whole numbers, 2 for cents)
     */
    public void setDecimalPlaces(int places) { this.decimalPlaces = places; }
    
    /**
     * Check if HUD animation is enabled.
     * Disable for better stability with MultipleHUD and other HUD mods.
     * @return true if animated balance counting is enabled
     */
    public boolean isEnableHudAnimation() { return enableHudAnimation; }
    
    /**
     * Enable or disable HUD animation.
     * @param enabled true for animated counting, false for instant updates
     */
    public void setEnableHudAnimation(boolean enabled) { this.enableHudAnimation = enabled; }
    
    /**
     * Check if HUD prefix should use translation system.
     * When false, uses the raw HudPrefix config value directly.
     * When true, uses TranslationHelper to get localized "hud.prefix" value.
     * @return true if translations are used for HUD prefix
     */
    public boolean isUseHudTranslation() { return useHudTranslation; }
    
    /**
     * Enable or disable HUD translation.
     * @param enabled true to use localized hud.prefix, false for raw config value
     */
    public void setUseHudTranslation(boolean enabled) { this.useHudTranslation = enabled; }
    
    /**
     * Check if currency symbol should be displayed on the right side of amounts.
     * When false: "$ 100" (left), When true: "100 $" (right)
     * @return true if symbol should be on the right
     */
    public boolean isSymbolOnRight() { return symbolOnRight; }
    
    /**
     * Set whether currency symbol should be on the right side.
     * @param onRight true for "100 $", false for "$ 100"
     */
    public void setSymbolOnRight(boolean onRight) { this.symbolOnRight = onRight; }
    /**
     * Get the server's default language code.
     * @return Language code (e.g., "en-US", "es-ES", "ru-RU")
     */
    public String getLanguage() { return language; }
    
    /**
     * Set the server's default language.
     * @param lang Language code (e.g., "en-US", "es-ES")
     */
    public void setLanguage(String lang) { this.language = lang; }
    
    /**
     * Check if per-player language selection is enabled.
     * @return true if each player can have their own language preference
     */
    public boolean isUsePlayerLanguage() { return usePlayerLanguage; }
    
    /**
     * Enable or disable per-player language selection.
     * @param perPlayer true to allow individual language preferences
     */
    public void setUsePlayerLanguage(boolean perPlayer) { this.usePlayerLanguage = perPlayer; }
    /**
     * Check if debug mode is enabled.
     * When true: verbose logging, /ecotest command available.
     * @return true if debug mode is active
     */
    public boolean isDebugMode() { return debugMode; }
    
    /**
     * Enable or disable debug mode.
     * @param debug true for verbose logging
     */
    public void setDebugMode(boolean debug) { this.debugMode = debug; }
    /**
     * Format amount with full precision (e.g., "$1,234.56")
     */
    public String format(double amount) {
        StringBuilder pattern = new StringBuilder("#,##0");
        if (decimalPlaces > 0) {
            pattern.append(".");
            pattern.append("0".repeat(decimalPlaces));
        }
        DecimalFormat df = new DecimalFormat(pattern.toString());
        String formatted = df.format(amount);
        return symbolOnRight ? (formatted + " " + currencySymbol) : (currencySymbol + " " + formatted);
    }
    
    /**
     * Format amount in compact form (e.g., "$1.2M", "$500K")
     */
    public String formatShort(double amount) {
        String formatted;
        if (amount >= 1_000_000_000) {
            formatted = String.format("%.1fB", amount / 1_000_000_000);
        } else if (amount >= 1_000_000) {
            formatted = String.format("%.1fM", amount / 1_000_000);
        } else if (amount >= 10_000) {
            formatted = String.format("%.1fK", amount / 1_000);
        } else {
            // Under 10K: show as whole number for cleaner HUD display
            formatted = String.valueOf(Math.round(amount));
        }
        return symbolOnRight ? (formatted + " " + currencySymbol) : (currencySymbol + " " + formatted);
    }
    
    /**
     * Format amount in compact form WITHOUT symbol (for HUD which displays symbol separately).
     * Example: 1234567 -> "1.2M", 50000 -> "50.0K", 500 -> "500"
     */
    public String formatShortNoSymbol(double amount) {
        if (amount >= 1_000_000_000) {
            return String.format("%.1fB", amount / 1_000_000_000);
        } else if (amount >= 1_000_000) {
            return String.format("%.1fM", amount / 1_000_000);
        } else if (amount >= 10_000) {
            return String.format("%.1fK", amount / 1_000);
        }
        return String.valueOf(Math.round(amount));
    }
}
