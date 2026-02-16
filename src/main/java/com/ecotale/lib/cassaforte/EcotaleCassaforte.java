package com.ecotale.lib.cassaforte;

import com.ecotale.Main;
import com.ecotale.api.EcotaleAPI;
import it.cassaforte.api.economy.AbstractEconomy;
import it.cassaforte.api.economy.EconomyResponse;

import java.util.UUID;

/**
 * Cassaforte Economy implementation for Ecotale.
 *
 * Exposes Ecotale's economy system through the Cassaforte service registry,
 * allowing third-party plugins to interact with balances via the standard
 * Cassaforte Economy interface.
 *
 * Bank operations are not supported and delegate to AbstractEconomy defaults
 * (returning NOT_IMPLEMENTED).
 */
public class EcotaleCassaforte extends AbstractEconomy {

    private final Main plugin = Main.getInstance();

    @Override
    public String getName() {
        return "Ecotale";
    }

    @Override
    public int fractionalDigits() {
        return Main.CONFIG.get().getDecimalPlaces();
    }

    @Override
    public String format(double amount) {
        return Main.CONFIG.get().format(amount);
    }

    @Override
    public String currencyNamePlural() {
        return Main.CONFIG.get().getCurrencySymbol();
    }

    @Override
    public String currencyNameSingular() {
        return Main.CONFIG.get().getCurrencySymbol();
    }

    @Override
    public boolean hasAccount(UUID playerId) {
        return plugin.getEconomyManager().getAllBalances().containsKey(playerId);
    }

    @Override
    public double getBalance(UUID playerId) {
        return EcotaleAPI.getBalance(playerId);
    }

    @Override
    public EconomyResponse withdrawPlayer(UUID playerId, double amount) {
        boolean result = EcotaleAPI.withdraw(playerId, amount, "Cassaforte withdraw");
        EconomyResponse.ResponseType status = result
                ? EconomyResponse.ResponseType.SUCCESS
                : EconomyResponse.ResponseType.FAILURE;
        return new EconomyResponse(amount, getBalance(playerId), status,
                "Withdrawal result: " + status.name());
    }

    @Override
    public EconomyResponse depositPlayer(UUID playerId, double amount) {
        boolean result = EcotaleAPI.deposit(playerId, amount, "Cassaforte deposit");
        EconomyResponse.ResponseType status = result
                ? EconomyResponse.ResponseType.SUCCESS
                : EconomyResponse.ResponseType.FAILURE;
        return new EconomyResponse(amount, getBalance(playerId), status,
                "Deposit result: " + status.name());
    }

    @Override
    public boolean createPlayerAccount(UUID playerId) {
        plugin.getEconomyManager().ensureAccount(playerId);
        return true;
    }
}
