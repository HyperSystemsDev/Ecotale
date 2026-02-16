package com.ecotale.commands;

import com.ecotale.Main;
import com.ecotale.economy.PlayerBalance;
import com.ecotale.gui.EcoAdminGui;
import com.ecotale.hud.BalanceHud;
import com.ecotale.systems.BalanceHudSystem;
import com.ecotale.util.PlayerNameService;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.CommandUtil;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

import java.awt.Color;
import java.util.Comparator;
import java.util.List;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Economy admin commands for managing and testing balances.
 * 
 * Commands:
 * - /eco set <amount> - Set your balance
 * - /eco give <amount> - Add to your balance
 * - /eco take <amount> - Remove from your balance  
 * - /eco reset - Reset to starting balance
 * - /eco top - Show top balances
 * - /eco save - Force save data
 */
public class EcoAdminCommand extends AbstractAsyncCommand {
    
    public EcoAdminCommand() {
        super("eco", "Economy administration commands");
        this.addAliases("economy", "ecoadmin");
        this.setPermissionGroup(null); // Admin only - requires ecotale.ecotale.command.eco permission
        
        this.addSubCommand(new EcoSetCommand());
        this.addSubCommand(new EcoGiveCommand());
        this.addSubCommand(new EcoTakeCommand());
        this.addSubCommand(new EcoResetCommand());
        this.addSubCommand(new EcoTopCommand());
        this.addSubCommand(new EcoSaveCommand());
        this.addSubCommand(new EcoHudCommand());
        this.addSubCommand(new EcoMetricsCommand());
    }
    
    @NonNullDecl
    @Override
    protected CompletableFuture<Void> executeAsync(CommandContext commandContext) {
        CommandSender sender = commandContext.sender();
        
        // Open admin GUI if player
        if (sender instanceof Player player) {
            var ref = player.getReference();
            if (ref != null && ref.isValid()) {
                var store = ref.getStore();
                var world = store.getExternalData().getWorld();
                if (world == null) {
                    return CompletableFuture.completedFuture(null);
                }
                CompletableFuture<Void> future = new CompletableFuture<>();
                world.execute(() -> {
                    PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
                    if (playerRef != null) {
                        player.getPageManager().openCustomPage(ref, store, new EcoAdminGui(playerRef));
                    }
                    future.complete(null);
                });
                return future;
            }
        }
        
        // Fallback: show help text
        commandContext.sender().sendMessage(Message.raw("=== Ecotale Economy Admin ===").color(new Color(255, 215, 0)));
        commandContext.sender().sendMessage(Message.raw("  /eco set <amount> - Set your balance").color(Color.GRAY));
        commandContext.sender().sendMessage(Message.raw("  /eco give <amount> - Add to balance").color(Color.GRAY));
        commandContext.sender().sendMessage(Message.raw("  /eco take <amount> - Remove from balance").color(Color.GRAY));
        commandContext.sender().sendMessage(Message.raw("  /eco reset - Reset to starting balance").color(Color.GRAY));
        commandContext.sender().sendMessage(Message.raw("  /eco top - Show top balances").color(Color.GRAY));
        commandContext.sender().sendMessage(Message.raw("  /eco metrics - Show performance stats").color(Color.GRAY));
        commandContext.sender().sendMessage(Message.raw("  /eco save - Force save data").color(Color.GRAY));
        return CompletableFuture.completedFuture(null);
    }
    private static class EcoSetCommand extends AbstractAsyncCommand {
        
        public EcoSetCommand() {
            super("set", "Set a player's balance to a specific amount");
            this.setAllowsExtraArguments(true);
            // No defined args - we parse manually
        }
        
        @NonNullDecl
        @Override
        protected CompletableFuture<Void> executeAsync(CommandContext ctx) {
            String input = ctx.getInputString();
            String rawArgsStr = CommandUtil.stripCommandName(input);
            String[] rawArgs = rawArgsStr.trim().isEmpty() ? new String[0] : rawArgsStr.trim().split("\\s+");
            List<String> args = Arrays.asList(rawArgs);
            CommandSender sender = ctx.sender();
            
            int argOffset = 0;
            if (!args.isEmpty() && (args.get(0).equalsIgnoreCase("set") || args.get(0).equalsIgnoreCase("eco"))) {
                argOffset++;
            }
            if (args.size() > argOffset && args.get(argOffset).equalsIgnoreCase("set")) { // Handle double "eco set set" edge case if any
                 argOffset++;
            }

            if (args.size() <= argOffset) {
                ctx.sendMessage(Message.raw("Usage: /eco set <amount> [player]").color(Color.RED));
                return CompletableFuture.completedFuture(null);
            }
            
            // 1. Parse Amount
            Double amount;
            try {
                amount = Double.parseDouble(args.get(argOffset));
            } catch (NumberFormatException e) {
                ctx.sendMessage(Message.raw("Invalid amount: " + args.get(argOffset)).color(Color.RED));
                return CompletableFuture.completedFuture(null);
            }
            
            if (amount < 0) {
                ctx.sendMessage(Message.raw("Amount must be 0 or positive").color(Color.RED));
                return CompletableFuture.completedFuture(null);
            }
            
            // 2. Determine Target
            String targetName = null;
            if (args.size() > argOffset + 1) {
                targetName = args.get(argOffset + 1);
            } else if (sender instanceof Player player) {
                targetName = player.getDisplayName(); // Self
            } else {
                ctx.sendMessage(Message.raw("Usage: /eco set <amount> <player> (required for console)").color(Color.RED));
                return CompletableFuture.completedFuture(null);
            }
            
            return lookupAndExecute(ctx, targetName, amount);
        }
        
        private CompletableFuture<Void> lookupAndExecute(CommandContext ctx, String targetName, double amount) {
            if (!targetName.matches("^[a-zA-Z0-9_]{3,16}$")) {
                ctx.sendMessage(Message.raw("Invalid username format: " + targetName).color(Color.RED));
                return CompletableFuture.completedFuture(null);
            }
            final String finalTargetName = targetName;
            return PlayerNameService.getInstance().resolveUuid(targetName).thenCompose(uuid -> {
                if (uuid != null) {
                    return executeOnTarget(ctx, uuid, finalTargetName, amount);
                }
                ctx.sendMessage(Message.raw("Player not found: " + finalTargetName).color(Color.RED));
                return CompletableFuture.completedFuture(null);
            });
        }

        private CompletableFuture<Void> executeOnTarget(CommandContext ctx, UUID targetUuid, String targetName, double amount) {
            double oldBalance = Main.getInstance().getEconomyManager().getBalance(targetUuid);
            Main.getInstance().getEconomyManager().setBalance(targetUuid, amount, "Admin set for " + targetName);
            
            ctx.sendMessage(Message.join(
                Message.raw("Set " + targetName + " balance: ").color(Color.GREEN),
                Message.raw(Main.CONFIG.get().format(oldBalance)).color(Color.GRAY),
                Message.raw(" -> ").color(Color.WHITE),
                Message.raw(Main.CONFIG.get().format(amount)).color(new Color(50, 205, 50))
            ));
            
            updateHud(targetUuid, amount);
            return CompletableFuture.completedFuture(null);
        }
    }
    private static class EcoGiveCommand extends AbstractAsyncCommand {
        
        public EcoGiveCommand() {
            super("give", "Add money to a player's balance");
            this.addAliases("add");
            this.setAllowsExtraArguments(true);
        }
        
        @NonNullDecl
        @Override
        protected CompletableFuture<Void> executeAsync(CommandContext ctx) {
            String input = ctx.getInputString();
            String rawArgsStr = CommandUtil.stripCommandName(input);
            String[] rawArgs = rawArgsStr.trim().isEmpty() ? new String[0] : rawArgsStr.trim().split("\\s+");
            List<String> args = Arrays.asList(rawArgs);
            CommandSender sender = ctx.sender();
            
            int argOffset = 0;
            // Handle "give" or "add" being the first token
            if (!args.isEmpty() && (args.get(0).equalsIgnoreCase("give") || args.get(0).equalsIgnoreCase("add") || args.get(0).equalsIgnoreCase("eco"))) {
                argOffset++;
            }
            if (args.size() > argOffset && (args.get(argOffset).equalsIgnoreCase("give") || args.get(argOffset).equalsIgnoreCase("add"))) {
                 argOffset++;
            }
            
            if (args.size() <= argOffset) {
                ctx.sendMessage(Message.raw("Usage: /eco give <amount> [player]").color(Color.RED));
                return CompletableFuture.completedFuture(null);
            }
            
            // 1. Parse Amount
            Double amount;
            try {
                amount = Double.parseDouble(args.get(argOffset));
            } catch (NumberFormatException e) {
                ctx.sendMessage(Message.raw("Invalid amount: " + args.get(argOffset)).color(Color.RED));
                return CompletableFuture.completedFuture(null);
            }
            
            if (amount <= 0) {
                ctx.sendMessage(Message.raw("Amount must be positive").color(Color.RED));
                return CompletableFuture.completedFuture(null);
            }
            
            // 2. Determine Target
            String targetName = null;
            if (args.size() > argOffset + 1) {
                targetName = args.get(argOffset + 1);
            } else if (sender instanceof Player player) {
                targetName = player.getDisplayName(); // Self
            } else {
                ctx.sendMessage(Message.raw("Usage: /eco give <amount> <player> (required for console)").color(Color.RED));
                return CompletableFuture.completedFuture(null);
            }
            
            return lookupAndExecute(ctx, targetName, amount);
        }
        
        private CompletableFuture<Void> lookupAndExecute(CommandContext ctx, String targetName, double amount) {
            if (!targetName.matches("^[a-zA-Z0-9_]{3,16}$")) {
                ctx.sendMessage(Message.raw("Invalid username format: " + targetName).color(Color.RED));
                return CompletableFuture.completedFuture(null);
            }
            final String finalTargetName = targetName;
            return PlayerNameService.getInstance().resolveUuid(targetName).thenCompose(uuid -> {
                if (uuid != null) {
                    return executeOnTarget(ctx, uuid, finalTargetName, amount);
                }
                ctx.sendMessage(Message.raw("Player not found: " + finalTargetName).color(Color.RED));
                return CompletableFuture.completedFuture(null);
            });
        }

        private CompletableFuture<Void> executeOnTarget(CommandContext ctx, UUID targetUuid, String targetName, double amount) {
            Main.getInstance().getEconomyManager().deposit(targetUuid, amount, "Admin give to " + targetName);
            double newBalance = Main.getInstance().getEconomyManager().getBalance(targetUuid);
            
            ctx.sendMessage(Message.join(
                Message.raw("Added ").color(Color.GREEN),
                Message.raw("+" + Main.CONFIG.get().format(amount)).color(new Color(50, 205, 50)),
                Message.raw(" to " + targetName).color(Color.WHITE),
                Message.raw(" | New balance: ").color(Color.GRAY),
                Message.raw(Main.CONFIG.get().format(newBalance)).color(Color.WHITE)
            ));
            
            updateHud(targetUuid, newBalance);
            return CompletableFuture.completedFuture(null);
        }
    }
    private static class EcoTakeCommand extends AbstractAsyncCommand {
        
        public EcoTakeCommand() {
            super("take", "Remove money from a player's balance");
            this.addAliases("remove");
            this.setAllowsExtraArguments(true);
            // No defined args
        }
        
        @NonNullDecl
        @Override
        protected CompletableFuture<Void> executeAsync(CommandContext ctx) {
            String input = ctx.getInputString();
            String rawArgsStr = CommandUtil.stripCommandName(input);
            String[] rawArgs = rawArgsStr.trim().isEmpty() ? new String[0] : rawArgsStr.trim().split("\\s+");
            List<String> args = Arrays.asList(rawArgs);
            CommandSender sender = ctx.sender();
            
            int argOffset = 0;
            // Handle "take" or "remove" being the first token
            if (!args.isEmpty() && (args.get(0).equalsIgnoreCase("take") || args.get(0).equalsIgnoreCase("remove") || args.get(0).equalsIgnoreCase("eco"))) {
                argOffset++;
            }
             if (args.size() > argOffset && (args.get(argOffset).equalsIgnoreCase("take") || args.get(argOffset).equalsIgnoreCase("remove"))) {
                 argOffset++;
            }
            
            if (args.size() <= argOffset) {
                ctx.sendMessage(Message.raw("Usage: /eco take <amount> [player]").color(Color.RED));
                return CompletableFuture.completedFuture(null);
            }
            
            // 1. Parse Amount
            Double amount;
            try {
                amount = Double.parseDouble(args.get(argOffset));
            } catch (NumberFormatException e) {
                ctx.sendMessage(Message.raw("Invalid amount: " + args.get(argOffset)).color(Color.RED));
                return CompletableFuture.completedFuture(null);
            }
            
            if (amount <= 0) {
                ctx.sendMessage(Message.raw("Amount must be positive").color(Color.RED));
                return CompletableFuture.completedFuture(null);
            }
            
            // 2. Determine Target
            String targetName = null;
            if (args.size() > argOffset + 1) {
                targetName = args.get(argOffset + 1);
            } else if (sender instanceof Player player) {
                targetName = player.getDisplayName(); // Self
            } else {
                ctx.sendMessage(Message.raw("Usage: /eco take <amount> <player> (required for console)").color(Color.RED));
                return CompletableFuture.completedFuture(null);
            }
            
            return lookupAndExecute(ctx, targetName, amount);
        }
        
        private CompletableFuture<Void> lookupAndExecute(CommandContext ctx, String targetName, double amount) {
            if (!targetName.matches("^[a-zA-Z0-9_]{3,16}$")) {
                ctx.sendMessage(Message.raw("Invalid username format: " + targetName).color(Color.RED));
                return CompletableFuture.completedFuture(null);
            }
            final String finalTargetName = targetName;
            return PlayerNameService.getInstance().resolveUuid(targetName).thenCompose(uuid -> {
                if (uuid != null) {
                    return executeOnTarget(ctx, uuid, finalTargetName, amount);
                }
                ctx.sendMessage(Message.raw("Player not found: " + finalTargetName).color(Color.RED));
                return CompletableFuture.completedFuture(null);
            });
        }

        private CompletableFuture<Void> executeOnTarget(CommandContext ctx, UUID targetUuid, String targetName, double amount) {
            boolean success = Main.getInstance().getEconomyManager().withdraw(targetUuid, amount, "Admin take from " + targetName);
            double newBalance = Main.getInstance().getEconomyManager().getBalance(targetUuid);
            
            if (success) {
                ctx.sendMessage(Message.join(
                    Message.raw("Removed ").color(Color.YELLOW),
                    Message.raw("-" + Main.CONFIG.get().format(amount)).color(new Color(255, 99, 71)),
                    Message.raw(" from " + targetName).color(Color.WHITE),
                    Message.raw(" | New balance: ").color(Color.GRAY),
                    Message.raw(Main.CONFIG.get().format(newBalance)).color(Color.WHITE)
                ));
                updateHud(targetUuid, newBalance);
            } else {
                ctx.sendMessage(Message.raw(targetName + " has insufficient funds").color(Color.RED));
            }
            
            return CompletableFuture.completedFuture(null);
        }
    }
    private static class EcoResetCommand extends AbstractAsyncCommand {
        public EcoResetCommand() {
            super("reset", "Reset balance to starting amount");
        }
        
        @NonNullDecl
        @Override
        protected CompletableFuture<Void> executeAsync(CommandContext ctx) {
            CommandSender sender = ctx.sender();
            if (!(sender instanceof Player player)) {
                ctx.sendMessage(Message.raw("This command can only be used by players").color(Color.RED));
                return CompletableFuture.completedFuture(null);
            }
            
            var ref = player.getReference();
            if (ref == null || !ref.isValid()) return CompletableFuture.completedFuture(null);
            
            var store = ref.getStore();
            var world = store.getExternalData().getWorld();
            
            return CompletableFuture.runAsync(() -> {
                PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
                if (playerRef == null) return;
                
                double startingBalance = Main.CONFIG.get().getStartingBalance();
                Main.getInstance().getEconomyManager().setBalance(playerRef.getUuid(), startingBalance, "Admin reset");
                updateHud(playerRef.getUuid(), startingBalance);
                
                player.sendMessage(Message.join(
                    Message.raw("Balance reset to ").color(Color.GREEN),
                    Message.raw(Main.CONFIG.get().format(startingBalance)).color(new Color(50, 205, 50))
                ));
            }, world);
        }
    }
    private static class EcoTopCommand extends AbstractAsyncCommand {
        public EcoTopCommand() {
            super("top", "Show top balances");
        }
        
        @NonNullDecl
        @Override
        protected CompletableFuture<Void> executeAsync(CommandContext ctx) {
            Map<UUID, PlayerBalance> balances = Main.getInstance().getEconomyManager().getAllBalances();
            
            if (balances.isEmpty()) {
                ctx.sendMessage(Message.raw("No player balances found").color(Color.GRAY));
                return CompletableFuture.completedFuture(null);
            }
            
            ctx.sendMessage(Message.raw("=== Top Balances ===").color(new Color(255, 215, 0)));

            // Get top 10 sorted by balance
            List<PlayerBalance> top10 = balances.values().stream()
                .sorted(Comparator.comparingDouble(PlayerBalance::getBalance).reversed())
                .limit(10)
                .toList();

            // Build name resolution futures via centralized PlayerNameService
            var nameService = PlayerNameService.getInstance();
            List<CompletableFuture<String>> nameFutures = top10.stream()
                .map(balance -> nameService != null
                    ? nameService.resolveAsync(balance.getPlayerUuid())
                    : CompletableFuture.completedFuture(balance.getPlayerUuid().toString().substring(0, 8) + "..."))
                .toList();
            
            // Wait for all names to resolve, then display
            return CompletableFuture.allOf(nameFutures.toArray(new CompletableFuture[0]))
                .thenAccept(v -> {
                    for (int i = 0; i < top10.size(); i++) {
                        PlayerBalance balance = top10.get(i);
                        String displayName = nameFutures.get(i).join(); // Already completed
                        String formatted = Main.CONFIG.get().format(balance.getBalance());
                        ctx.sendMessage(Message.join(
                            Message.raw("#" + (i + 1) + " ").color(Color.GRAY),
                            Message.raw(displayName).color(Color.WHITE),
                            Message.raw(" - ").color(Color.GRAY),
                            Message.raw(formatted).color(new Color(50, 205, 50))
                        ));
                    }
                });
        }
    }
    private static class EcoSaveCommand extends AbstractAsyncCommand {
        public EcoSaveCommand() {
            super("save", "Force save all data");
        }
        
        @NonNullDecl
        @Override
        protected CompletableFuture<Void> executeAsync(CommandContext ctx) {
            Main.getInstance().getEconomyManager().forceSave();
            ctx.sendMessage(Message.raw("✓ Economy data saved successfully").color(Color.GREEN));
            return CompletableFuture.completedFuture(null);
        }
    }
    private static class EcoHudCommand extends AbstractAsyncCommand {
        public EcoHudCommand() {
            super("hud", "Toggle HUD display on/off");
        }
        
        @NonNullDecl
        @Override
        protected CompletableFuture<Void> executeAsync(CommandContext ctx) {
            var config = Main.CONFIG.get();
            boolean newValue = !config.isEnableHudDisplay();
            config.setEnableHudDisplay(newValue);
            
            String status = newValue ? "§aEnabled" : "§cDisabled";
            ctx.sendMessage(Message.raw("HUD Display: " + status).color(newValue ? Color.GREEN : Color.RED));
            ctx.sendMessage(Message.raw("Use /eco save to persist this change").color(Color.GRAY));
            return CompletableFuture.completedFuture(null);
        }
    }
    private static class EcoMetricsCommand extends AbstractAsyncCommand {
        public EcoMetricsCommand() {
            super("metrics", "Show performance and scaling metrics");
            this.addAliases("stats", "perf");
        }
        
        @NonNullDecl
        @Override
        protected CompletableFuture<Void> executeAsync(CommandContext ctx) {
            var monitor = com.ecotale.util.PerformanceMonitor.getInstance();
            if (monitor != null) {
                Color gold = new Color(255, 215, 0);
                Color white = Color.WHITE;
                Color green = new Color(50, 205, 50);

                ctx.sendMessage(Message.raw("--- Ecotale Economy Metrics ---").color(gold));
                
                ctx.sendMessage(Message.join(
                    Message.raw("Cached Balances: ").color(white),
                    Message.raw(monitor.getCachedPlayers() + " / 1000").color(green)
                ));
                
                ctx.sendMessage(Message.raw("---------------------------------").color(gold));
                ctx.sendMessage(Message.raw("System metrics moved to /guard metrics").color(Color.GRAY));
            } else {
                ctx.sendMessage(Message.raw("Performance monitor is not active.").color(Color.RED));
            }
            return CompletableFuture.completedFuture(null);
        }
    }
    private static void updateHud(UUID playerUuid, double newBalance) {
        BalanceHud hud = BalanceHudSystem.getHud(playerUuid);
        if (hud != null) {
            hud.updateBalance(newBalance);
        }
    }
}

