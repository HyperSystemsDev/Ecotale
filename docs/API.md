# Ecotale API

## Overview

`EcotaleAPI` is the public entry point for other plugins to interact with the economy. All methods are static and thread-safe.

```java
import com.ecotale.api.EcotaleAPI;

if (EcotaleAPI.isAvailable()) {
    double balance = EcotaleAPI.getBalance(playerUuid);
}
```

## Read Operations (Not Rate Limited)

```java
// Balance
double balance = EcotaleAPI.getBalance(playerUuid);
boolean canAfford = EcotaleAPI.hasBalance(playerUuid, 100.0);

// Config values
String symbol = EcotaleAPI.getCurrencySymbol();   // e.g. "$"
String prefix = EcotaleAPI.getHudPrefix();         // e.g. "Bank"
String formatted = EcotaleAPI.format(1500.0);      // "$1,500.00"
double maxBal = EcotaleAPI.getMaxBalance();

// Language
String lang = EcotaleAPI.getLanguage();            // "en-US"
boolean perPlayer = EcotaleAPI.isUsePlayerLanguage();

// Leaderboard
List<PlayerBalance> top = EcotaleAPI.getTopBalances(10);
double total = EcotaleAPI.getTotalCirculating();
Set<UUID> all = EcotaleAPI.getAllPlayerUUIDs();

// Transactions
List<TransactionEntry> history = EcotaleAPI.getTransactionHistory(playerUuid, 50);
```

## Write Operations (Rate Limited)

Rate limit: 50 burst, 10 ops/sec per player.

```java
try {
    boolean ok = EcotaleAPI.deposit(playerUuid, 100.0, "Quest reward");
    boolean ok = EcotaleAPI.withdraw(playerUuid, 50.0, "Shop purchase");

    EconomyManager.TransferResult result = EcotaleAPI.transfer(
        fromUuid, toUuid, 100.0, "Trade"
    );

    EcotaleAPI.setBalance(playerUuid, 1000.0, "Admin set");
    EcotaleAPI.resetBalance(playerUuid, "Admin reset");

} catch (EcotaleRateLimitException e) {
    // Wait and retry
    Thread.sleep(e.getRetryAfterMs());
}
```

## Physical Coins Integration

Requires [EcotaleCoins](../README.md) addon.

```java
if (EcotaleAPI.isPhysicalCoinsAvailable()) {
    PhysicalCoinsProvider coins = EcotaleAPI.getPhysicalCoins();

    // Inventory operations
    long value = coins.countInInventory(player);
    CoinOperationResult result = coins.giveCoins(player, 500L);
    CoinOperationResult result = coins.takeCoins(player, 200L);

    // World drops (ECS-safe via CommandBuffer)
    coins.dropCoinsAtEntity(entityRef, store, commandBuffer, 1000L);
    coins.dropCoins(store, commandBuffer, position, 500L);

    // Bank operations
    long bankBal = coins.getBankBalance(playerUuid);
    CoinOperationResult result = coins.bankDeposit(player, playerUuid, 100L);
    CoinOperationResult result = coins.bankWithdraw(player, playerUuid, 100L);

    // Total wealth (bank + inventory)
    long total = coins.getTotalWealth(player, playerUuid);
}
```

### CoinOperationResult

All coin operations return a `CoinOperationResult` with one of these statuses:

| Status | Meaning |
|--------|---------|
| `SUCCESS` | Operation completed |
| `NOT_ENOUGH_SPACE` | Inventory full |
| `INSUFFICIENT_FUNDS` | Not enough coins/bank balance |
| `INVALID_AMOUNT` | Amount was zero or negative |
| `INVALID_PLAYER` | Player entity was null |

```java
CoinOperationResult result = coins.giveCoins(player, 500L);
if (!result.isSuccess()) {
    String msg = result.getMessage(); // Human-readable
    long requested = result.getRequestedAmount();
}
```

## Version

```java
int apiVersion = EcotaleAPI.getAPIVersion();        // 2
String pluginVersion = EcotaleAPI.getPluginVersion(); // Read from JAR manifest (e.g. "1.0.8")
```
