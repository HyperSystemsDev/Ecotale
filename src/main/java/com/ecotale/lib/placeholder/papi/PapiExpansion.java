package com.ecotale.lib.placeholder.papi;

import at.helpch.placeholderapi.expansion.PlaceholderExpansion;
import com.ecotale.api.EcotaleAPI;
import com.ecotale.lib.placeholder.EcotalePlaceholders;
import com.ecotale.lib.placeholder.PlaceholderProvider;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;

/**
 * HelpChat PlaceholderAPI adapter for Ecotale.
 * <p>
 * Registered placeholders: {@code %ecotale_<params>%}
 * <p>
 * This is a thin translation layer. All resolution logic lives in
 * {@link EcotalePlaceholders#resolve(UUID, String)}.
 */
public class PapiExpansion extends PlaceholderExpansion implements PlaceholderProvider {

    private volatile boolean registered = false;
    @Override
    @Nonnull
    public String getIdentifier() {
        return "ecotale";
    }

    @Override
    @Nonnull
    public String getAuthor() {
        return "Ecotale";
    }

    @Override
    @Nonnull
    public String getVersion() {
        return EcotaleAPI.getPluginVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    @Nullable
    public String onPlaceholderRequest(@Nullable PlayerRef player, @Nonnull String params) {
        UUID uuid = (player != null) ? player.getUuid() : null;
        return EcotalePlaceholders.resolve(uuid, params);
    }
    @Override
    public String name() {
        return "PlaceholderAPI (HelpChat)";
    }

    @Override
    public boolean registerExpansion() {
        try {
            super.register();
            registered = true;
            return true;
        } catch (Exception e) {
            registered = false;
            return false;
        }
    }

    @Override
    public void unregisterExpansion() {
        if (registered) {
            try {
                super.unregister();
            } catch (Exception ignored) {}
            registered = false;
        }
    }

    @Override
    public boolean isExpansionRegistered() {
        return registered;
    }
}
