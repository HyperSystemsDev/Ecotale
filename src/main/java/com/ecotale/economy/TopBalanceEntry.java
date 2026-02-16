package com.ecotale.economy;

import java.util.UUID;

/**
 * Lightweight DTO for top balance queries.
 */
public record TopBalanceEntry(UUID uuid, String name, double balance, double trend) {
}
