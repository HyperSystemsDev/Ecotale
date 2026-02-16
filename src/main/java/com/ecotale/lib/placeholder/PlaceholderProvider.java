package com.ecotale.lib.placeholder;

/**
 * Abstraction for placeholder API integrations.
 * <p>
 * Each supported placeholder API (HelpChat PlaceholderAPI, WiFlowPlaceholderAPI, etc.)
 * implements this interface as a thin adapter. All placeholder resolution logic lives
 * in {@link EcotalePlaceholders} — adapters only translate the API-specific callback
 * into a {@code resolve(UUID, String)} call.
 * <p>
 * Adding a new placeholder API requires only one new adapter class + one
 * {@code hasPlugin()} check in {@link PlaceholderManager}.
 */
public interface PlaceholderProvider {

    /** Human-readable name for logging (e.g., "PlaceholderAPI (HelpChat)"). */
    String name();

    /**
     * Register the expansion with the external placeholder API.
     * Called once during plugin setup if the API is detected on the server.
     * <p>
     * Named {@code registerExpansion} to avoid collision with {@code final register()}
     * in PlaceholderExpansion superclasses.
     *
     * @return true if registration succeeded
     */
    boolean registerExpansion();

    /**
     * Unregister the expansion from the external placeholder API.
     * Called during plugin shutdown. Must be idempotent.
     * <p>
     * Named {@code unregisterExpansion} to avoid collision with {@code final unregister()}
     * in PlaceholderExpansion superclasses.
     */
    void unregisterExpansion();

    /** Whether this provider successfully registered and has not been unregistered. */
    boolean isExpansionRegistered();
}
