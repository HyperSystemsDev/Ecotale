package com.ecotale.lib.placeholder.wiflow;

import com.ecotale.api.EcotaleAPI;
import com.ecotale.lib.placeholder.EcotalePlaceholders;
import com.ecotale.lib.placeholder.PlaceholderProvider;
import com.wiflow.placeholderapi.WiFlowPlaceholderAPI;
import com.wiflow.placeholderapi.context.PlaceholderContext;
import com.wiflow.placeholderapi.expansion.PlaceholderExpansion;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;

/**
 * WiFlowPlaceholderAPI adapter for Ecotale.
 * <p>
 * Registered placeholders: {@code {ecotale_<params>}}
 * <p>
 * This is a thin translation layer. All resolution logic lives in
 * {@link EcotalePlaceholders#resolve(UUID, String)}.
 */
public class WiFlowExpansion extends PlaceholderExpansion implements PlaceholderProvider {

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
    @Nullable
    public String onPlaceholderRequest(@Nonnull PlaceholderContext context, @Nonnull String params) {
        UUID uuid = context.getPlayerUuid();
        return EcotalePlaceholders.resolve(uuid, params);
    }
    @Override
    public String name() {
        return "WiFlowPlaceholderAPI";
    }

    @Override
    public boolean registerExpansion() {
        try {
            WiFlowPlaceholderAPI.registerExpansion(this);
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
                WiFlowPlaceholderAPI.unregisterExpansion("ecotale");
            } catch (Exception ignored) {}
            registered = false;
        }
    }

    @Override
    public boolean isExpansionRegistered() {
        return registered;
    }
}
