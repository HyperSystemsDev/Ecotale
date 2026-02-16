package com.ecotale.commands;

import com.ecotale.gui.TopBalanceGui;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

import java.awt.Color;
import java.util.concurrent.CompletableFuture;

/**
 * TopBalance command - opens leaderboard UI for players.
 */
public class TopBalanceCommand extends AbstractAsyncCommand {

    public TopBalanceCommand() {
        super("topb", "Open the Top Balance leaderboard");
        this.addAliases("topbalance");
        this.setPermissionGroup(GameMode.Adventure);
    }

    @NonNullDecl
    @Override
    protected CompletableFuture<Void> executeAsync(CommandContext ctx) {
        if (!(ctx.sender() instanceof Player player)) {
            ctx.sendMessage(Message.raw("This command can only be used by players").color(Color.RED));
            return CompletableFuture.completedFuture(null);
        }

        var ref = player.getReference();
        if (ref == null || !ref.isValid()) {
            ctx.sendMessage(Message.raw("Error: Could not get your player data").color(Color.RED));
            return CompletableFuture.completedFuture(null);
        }

        var store = ref.getStore();
        var world = store.getExternalData().getWorld();
        if (world == null) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Void> future = new CompletableFuture<>();
        world.execute(() -> {
            PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
            if (playerRef == null) {
                ctx.sendMessage(Message.raw("Error: Could not get player reference").color(Color.RED));
                future.complete(null);
                return;
            }

            player.getPageManager().openCustomPage(ref, store, new TopBalanceGui(playerRef));
            future.complete(null);
        });

        return future;
    }
}
