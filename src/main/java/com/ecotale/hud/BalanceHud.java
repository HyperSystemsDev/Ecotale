package com.ecotale.hud;

import com.ecotale.lib.simplehud.SimpleHud;
import com.ecotale.lib.simplehud.HudScheduler;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.Message;

import java.util.concurrent.ScheduledFuture;

/**
 * HUD to display player balance with smart animated counting.
 * Features:
 * - Progressive counter animation
 * - Trailing digits for large balances (shows ...005 during animation)
 * @author michidev
 */
public class BalanceHud extends SimpleHud {
    
    // HUD enabled/disabled is now controlled by config: EnableHudDisplay
    
    private static final int MIN_STEPS = 15;
    private static final int MAX_STEPS = 30;
    private static final long STEP_INTERVAL_MS = 50;
    
    // If change is less than 0.1% of balance, use trailing digits
    private static final double TRAILING_THRESHOLD = 0.001;
    private final PlayerRef ownerRef;

    
    private double displayedBalance = 0;
    private double targetBalance = 0;
    private boolean useTrailingDigits = false;
    private ScheduledFuture<?> animationFuture;
    private boolean warnedDisabled = false;

    public BalanceHud(PlayerRef playerRef) {
        super(playerRef, "Pages/Ecotale_BalanceHud.ui");
        this.ownerRef = playerRef;
        
        double currentBalance = com.ecotale.Main.getInstance().getEconomyManager().getBalance(playerRef.getUuid());
        displayedBalance = currentBalance;
        targetBalance = currentBalance;
        
        // FIX: Prepare initial values WITHOUT calling show()
        // HudManager.setCustomHud() will call show() automatically when registering the HUD
        // Previously, calling updateDisplayFinal() here caused a race condition where
        // show() was called BEFORE the HUD was registered, causing invisible HUD for some players
        prepareInitialDisplay(currentBalance);
        
        // Initialize world cache early for thread-safe updates
        this.refreshWorldCache();
    }
    
    /**
     * Prepare initial display values for when the HUD is first shown.
     * This method sets up the values map but does NOT call pushUpdates()/show().
     * The actual show() is handled by HudManager.setCustomHud() which calls it automatically.
     */
    private void prepareInitialDisplay(double balance) {
        if (!com.ecotale.Main.CONFIG.get().isEnableHudDisplay()) {
            return;
        }
        var config = com.ecotale.Main.CONFIG.get();
        String symbol = config.getCurrencySymbol();
        String hudPrefix = getHudPrefixText();
        String amountNum = config.formatShortNoSymbol(balance);
        
        // Handle symbol position - if right, we put symbol in BalanceAmount and clear BalanceSymbol
        String displaySymbol = config.isSymbolOnRight() ? "" : symbol;
        String displayAmount = config.isSymbolOnRight() ? (amountNum + " " + symbol) : amountNum;
        
        // Set values but do NOT call pushUpdates() - that happens in build()
        this.setText("CurrencyName", hudPrefix);
        this.setText("BalanceSymbol", displaySymbol);
        this.setText("BalanceAmount", displayAmount);
    }
    
    /**
     * Get the HUD prefix text, respecting the useHudTranslation config.
     * When useHudTranslation is false, returns the raw config HudPrefix value.
     * When true, uses TranslationHelper to get localized value.
     */
    private String getHudPrefixText() {
        if (com.ecotale.Main.CONFIG.get().isUseHudTranslation()) {
            return com.ecotale.util.TranslationHelper.t(ownerRef, "hud.prefix", 
                com.ecotale.Main.CONFIG.get().getHudPrefix());
        }
        return com.ecotale.Main.CONFIG.get().getHudPrefix();
    }

    @Override
    protected void build(com.hypixel.hytale.server.core.ui.builder.UICommandBuilder builder) {
        // Check config instead of hardcoded boolean
        if (!com.ecotale.Main.CONFIG.get().isEnableHudDisplay()) {
            return;
        }
        super.build(builder);
    }

    public void updateBalance(double newBalance) {
        // Refresh world cache for thread-safe dispatch when called from async context
        this.refreshWorldCache();
        
        if (Math.abs(newBalance - targetBalance) < 0.01) {
            return;
        }
        
        // If animation is disabled, update instantly (safer for MultipleHUD compatibility)
        if (!com.ecotale.Main.CONFIG.get().isEnableHudAnimation()) {
            targetBalance = newBalance;
            displayedBalance = newBalance;
            updateDisplayFinal(newBalance);
            return;
        }
        
        double change = Math.abs(newBalance - targetBalance);
        double ratio = targetBalance > 0 ? change / targetBalance : 1.0;
        
        // Use trailing digits if change is tiny relative to balance AND balance >= 10K
        useTrailingDigits = (ratio < TRAILING_THRESHOLD) && (targetBalance >= 10_000);
        
        targetBalance = newBalance;
        startAnimation();
    }
    
    /**
     * Refresh HUD display with current config (used when symbol/formatting changes)
     */
    public void refresh() {
        // Refresh world cache for thread-safe dispatch when called from async context
        this.refreshWorldCache();
        updateDisplayFinal(displayedBalance);
    }
    
    /**
     * Cleanup resources when HUD is removed (cancel pending animations)
     */
    public void cleanup() {
        if (animationFuture != null) {
            HudScheduler.cancel(animationFuture);
            animationFuture = null;
        }
    }
    
    private void startAnimation() {
        if (animationFuture != null) {
            HudScheduler.cancel(animationFuture);
        }
        
        double startValue = displayedBalance;
        double endValue = targetBalance;
        int totalSteps = calculateSteps(Math.abs(endValue - startValue));
        
        animateWithEasing(startValue, endValue, 0, totalSteps);
    }
    
    private int calculateSteps(double delta) {
        int steps = (int) Math.ceil(delta / 1.5);
        return Math.max(MIN_STEPS, Math.min(steps, MAX_STEPS));
    }
    
    private void animateWithEasing(double start, double end, int currentStep, int totalSteps) {
        if (currentStep >= totalSteps) {
            displayedBalance = end;
            useTrailingDigits = false;
            updateDisplayFinal(end);
            return;
        }
        
        double t = (double) currentStep / totalSteps;
        double eased = 1.0 - Math.pow(1.0 - t, 2.5);
        
        displayedBalance = start + (end - start) * eased;
        
        if (useTrailingDigits) {
            updateDisplayTrailing(displayedBalance);
        } else {
            updateDisplayFinal(displayedBalance);
        }
        
        animationFuture = HudScheduler.runLater(() -> {
            // Refresh world cache before animation step for correct threading
            this.refreshWorldCache();
            animateWithEasing(start, end, currentStep + 1, totalSteps);
        }, STEP_INTERVAL_MS);
    }
    
    /**
     * Show trailing digits during animation for large balances.
     * Example: 1,100,000,005 shows as "...005"
     */
    private void updateDisplayTrailing(double balance) {
        if (!com.ecotale.Main.CONFIG.get().isEnableHudDisplay()) {
            notifyDisabledOnce();
            return;
        }
        var config = com.ecotale.Main.CONFIG.get();
        String symbol = config.getCurrencySymbol();
        long rounded = Math.round(balance);
        // Show last 5 digits
        long lastDigits = rounded % 100_000;
        String amountNum = "..." + lastDigits;
        
        // Handle symbol position - if right, we put symbol in BalanceAmount and clear BalanceSymbol
        String displaySymbol = config.isSymbolOnRight() ? "" : symbol;
        String displayAmount = config.isSymbolOnRight() ? (amountNum + " " + symbol) : amountNum;
        
        // Use helper that respects useHudTranslation config
        String hudPrefix = getHudPrefixText();
        
        this.setText("CurrencyName", hudPrefix);
        this.setText("BalanceSymbol", displaySymbol);
        this.setText("BalanceAmount", displayAmount);
        this.pushUpdates();
    }
    
    /**
     * Show final abbreviated format (K/M/B).
     */
    private void updateDisplayFinal(double balance) {
        if (!com.ecotale.Main.CONFIG.get().isEnableHudDisplay()) {
            notifyDisabledOnce();
            return;
        }
        var config = com.ecotale.Main.CONFIG.get();
        String symbol = config.getCurrencySymbol();
        String hudPrefix = getHudPrefixText();
        String amountNum = config.formatShortNoSymbol(balance);
        
        // Handle symbol position - if right, we put symbol in BalanceAmount and clear BalanceSymbol
        String displaySymbol = config.isSymbolOnRight() ? "" : symbol;
        String displayAmount = config.isSymbolOnRight() ? (amountNum + " " + symbol) : amountNum;

        this.setText("CurrencyName", hudPrefix);
        this.setText("BalanceSymbol", displaySymbol);
        this.setText("BalanceAmount", displayAmount);
        this.pushUpdates();
    }

    private void notifyDisabledOnce() {
        if (warnedDisabled) {
            return;
        }
        warnedDisabled = true;
        ownerRef.sendMessage(Message.raw("Balance HUD desactivado por error de UI."));
    }
}

