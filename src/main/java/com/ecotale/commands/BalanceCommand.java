package com.ecotale.commands;

import com.ecotale.Main;
import com.ecotale.hud.BalanceHud;
import com.ecotale.locale.Messages;
import com.ecotale.systems.BalanceHudSystem;
import com.ecotale.util.HudHelper;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

import java.awt.Color;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Balance command - toggles HUD visibility.
 * 
 * Usage:
 *   /bal         - Toggle HUD on/off
 *   /bal show    - Force show HUD
 *   /bal hide    - Force hide HUD
 */
public class BalanceCommand extends AbstractAsyncCommand {
    
    private final OptionalArg<String> actionArg;
    
    public BalanceCommand() {
        super("bal", "Toggle your balance HUD visibility");
        this.addAliases("balance", "money");
        this.setPermissionGroup(GameMode.Adventure);
        
        this.actionArg = this.withOptionalArg("action", "show or hide", ArgTypes.STRING);
    }
    
    @NonNullDecl
    @Override
    protected CompletableFuture<Void> executeAsync(CommandContext ctx) {
        CommandSender sender = ctx.sender();
        
        if (!(sender instanceof Player player)) {
            ctx.sendMessage(Message.raw("This command can only be used by players").color(Color.RED));
            return CompletableFuture.completedFuture(null);
        }
        
        var playerEntity = player.getReference();
        if (playerEntity == null || !playerEntity.isValid()) {
            ctx.sendMessage(Message.raw("Error: Could not get player data").color(Color.RED));
            return CompletableFuture.completedFuture(null);
        }
        
        var store = playerEntity.getStore();
        var world = store.getExternalData().getWorld();
        if (world == null) {
            return CompletableFuture.completedFuture(null);
        }
        
        CompletableFuture<Void> future = new CompletableFuture<>();
        world.execute(() -> {
            PlayerRef playerRef = store.getComponent(playerEntity, PlayerRef.getComponentType());
            if (playerRef == null) {
                ctx.sendMessage(Message.raw("Error: Could not get player reference").color(Color.RED));
                future.complete(null);
                return;
            }
            
            // Check global config first
            if (!Main.CONFIG.get().isEnableHudDisplay()) {
                player.sendMessage(Message.raw(Messages.get(playerRef, "ecotale.hud.globally_disabled"))
                    .color(new Color(255, 170, 0)));
                future.complete(null);
                return;
            }
            
            UUID playerUuid = playerRef.getUuid();
            String action = ctx.get(actionArg);
            
            // Get current visibility from storage
            Main.getInstance().getEconomyManager().getStorageProvider()
                .getHudVisible(playerUuid)
                .thenAccept(currentlyVisible -> {
                    boolean newVisibility;
                    
                    if ("show".equalsIgnoreCase(action)) {
                        newVisibility = true;
                    } else if ("hide".equalsIgnoreCase(action)) {
                        newVisibility = false;
                    } else {
                        // Toggle
                        newVisibility = !currentlyVisible;
                    }
                    
                    // Save preference
                    Main.getInstance().getEconomyManager().getStorageProvider()
                        .setHudVisible(playerUuid, newVisibility);
                    
                    // Apply immediately on world thread
                    final boolean visible = newVisibility;
                    world.execute(() -> {
                        if (visible) {
                            showHud(player, playerRef);
                            player.sendMessage(Message.raw(Messages.get(playerRef, "ecotale.hud.shown"))
                                .color(new Color(100, 255, 100)));
                        } else {
                            hideHud(player, playerRef);
                            player.sendMessage(Message.raw(Messages.get(playerRef, "ecotale.hud.hidden"))
                                .color(new Color(255, 200, 100)));
                        }
                    });
                })
                .whenComplete((v, ex) -> future.complete(null));
        });
        
        return future;
    }
    
    private void showHud(Player player, PlayerRef playerRef) {
        UUID playerUuid = playerRef.getUuid();
        
        // Check if already registered
        if (BalanceHudSystem.getHud(playerUuid) != null) {
            return; // Already visible
        }
        
        var playerEntity = player.getReference();
        if (playerEntity == null || !playerEntity.isValid()) return;
        
        // Create and show HUD
        BalanceHud hud = new BalanceHud(playerRef);
        HudHelper.setCustomHud(player, playerRef, hud);
        BalanceHudSystem.registerHud(playerUuid, hud);
        
        // Update with current balance
        var balance = Main.getInstance().getEconomyManager().getPlayerBalance(playerUuid);
        if (balance != null) {
            hud.updateBalance(balance.getBalance());
        }
    }
    
    private void hideHud(Player player, PlayerRef playerRef) {
        UUID playerUuid = playerRef.getUuid();
        
        // Remove from system
        BalanceHudSystem.removePlayerHud(playerUuid);
        
        // Hide via HudHelper (handles MultipleHUD or vanilla)
        HudHelper.hideCustomHud(player, playerRef);
    }
}
