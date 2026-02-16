package com.ecotale.lib.placeholder;

import com.ecotale.Main;
import com.ecotale.api.EcotaleAPI;
import com.ecotale.config.EcotaleConfig;
import com.ecotale.economy.EconomyManager;
import com.ecotale.economy.PlayerBalance;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Central placeholder resolver for Ecotale.
 * <p>
 * All placeholder logic lives here. API-specific adapters extract the player UUID
 * from their respective context objects and delegate to {@link #resolve(UUID, String)}.
 * <p>
 * Placeholder identifier: {@code ecotale}
 * <p>
 * Design decisions:
 * <ul>
 *   <li>Static switch for O(1) common placeholders, regex fallback for dynamic ones</li>
 *   <li>All expensive computations (leaderboard sort, median, totals) are cached via {@link PlaceholderCache}</li>
 *   <li>Leaderboard and rank derived entirely from {@link EconomyManager#getAllBalances()} (in-memory) —
 *       works on all storage backends including JSON</li>
 *   <li>Trend placeholders use {@code loginBalances} snapshot (session-based) which works universally,
 *       no dependency on DB-specific snapshot tables</li>
 * </ul>
 */
public final class EcotalePlaceholders {

    // Pre-compiled patterns for dynamic placeholders
    private static final Pattern BALANCE_DP = Pattern.compile("^balance_(\\d{1,2})dp$");
    private static final Pattern TOP_NAME   = Pattern.compile("^top_name_(\\d{1,3})$");
    private static final Pattern TOP_BAL    = Pattern.compile("^top_balance_(\\d{1,3})$");

    // Maximum rank index for top_name/top_balance to prevent query abuse
    private static final int MAX_TOP_RANK = 100;

    // Cache keys
    private static final String CK_LEADERBOARD = "leaderboard";
    private static final String CK_TOTAL       = "total_circulating";
    private static final String CK_ACCOUNTS    = "total_accounts";
    private static final String CK_MEDIAN      = "median_balance";

    // TTLs
    private static final long TTL_GLOBAL = 30_000;  // 30s for server-wide stats
    private static final long TTL_RANK   = 15_000;  // 15s for per-player rank

    // Shared instances — set once by PlaceholderManager.init()
    private static PlaceholderCache cache;

    // Login balance snapshots — populated by PlaceholderManager.onPlayerJoin()
    static final ConcurrentHashMap<UUID, Double> loginBalances = new ConcurrentHashMap<>();

    private EcotalePlaceholders() {}

    /** Called once by {@link PlaceholderManager#init}. */
    static void init(PlaceholderCache placeholderCache) {
        cache = placeholderCache;
    }

    /**
     * Resolve a placeholder for a given player.
     *
     * @param playerUuid Player UUID (nullable for server-wide placeholders)
     * @param params     Placeholder params after "ecotale_" (e.g., "balance", "top_name_1")
     * @return Resolved value, never null. Returns empty string for unknown placeholders.
     */
    @Nonnull
    public static String resolve(@Nullable UUID playerUuid, @Nonnull String params) {
        if (!EcotaleAPI.isAvailable()) {
            return "";
        }

        String key = params.toLowerCase();

        return switch (key) {
            // === Balance (5) ===
            case "balance"           -> fmtRaw(getBalance(playerUuid));
            case "balance_formatted" -> config().format(getBalance(playerUuid));
            case "balance_short"     -> config().formatShort(getBalance(playerUuid));
            case "balance_commas"    -> fmtCommas(getBalance(playerUuid));

            // === Activity (4) ===
            case "profit"            -> resolveProfit(playerUuid);
            case "profit_ratio"      -> resolveProfitRatio(playerUuid);
            case "session_change"    -> resolveSessionChange(playerUuid);
            case "last_activity"     -> resolveLastActivity(playerUuid);

            // === Rank (3) ===
            case "rank"              -> resolveRank(playerUuid);
            case "rank_suffix"       -> resolveRankSuffix(playerUuid);
            case "rank_percentile"   -> resolvePercentile(playerUuid);

            // === Competitive (3) ===
            case "gap_to_first"      -> resolveGapToFirst(playerUuid);
            case "gap_to_next"       -> resolveGapToNext(playerUuid);
            case "ahead_of"          -> resolveAheadOf(playerUuid);

            // === Server economy (4) ===
            case "server_total"      -> resolveServerTotal();
            case "server_average"    -> resolveServerAverage();
            case "server_median"     -> resolveServerMedian();
            case "server_players"    -> resolveServerPlayers();

            // === Trend (4) ===
            case "trend_session"         -> resolveTrendSession(playerUuid);
            case "trend_session_percent" -> resolveTrendSessionPercent(playerUuid);
            case "trend_session_arrow"   -> resolveTrendArrow(playerUuid);
            case "trend_session_label"   -> resolveTrendLabel(playerUuid);

            // === Config (2) ===
            case "currency_symbol"   -> config().getCurrencySymbol();
            case "currency_name"     -> config().getHudPrefix();

            // === Dynamic (regex fallback) ===
            default -> resolveDynamic(playerUuid, key);
        };
    }
    private static double getBalance(@Nullable UUID uuid) {
        return (uuid != null) ? EcotaleAPI.getBalance(uuid) : 0.0;
    }
    private static String resolveProfit(@Nullable UUID uuid) {
        if (uuid == null) return config().format(0);
        PlayerBalance pb = getPlayerBalance(uuid);
        if (pb == null) return config().format(0);
        double profit = pb.getTotalEarned() - pb.getTotalSpent();
        return (profit >= 0 ? "+" : "") + config().format(profit);
    }

    private static String resolveProfitRatio(@Nullable UUID uuid) {
        if (uuid == null) return "0.00x";
        PlayerBalance pb = getPlayerBalance(uuid);
        if (pb == null) return "0.00x";
        double spent = pb.getTotalSpent();
        if (spent <= 0) {
            return pb.getTotalEarned() > 0 ? "INF" : "0.00x";
        }
        double ratio = pb.getTotalEarned() / spent;
        return String.format("%.2fx", ratio);
    }

    private static String resolveSessionChange(@Nullable UUID uuid) {
        if (uuid == null) return config().format(0);
        Double loginBal = loginBalances.get(uuid);
        if (loginBal == null) return "N/A";
        double current = EcotaleAPI.getBalance(uuid);
        double delta = current - loginBal;
        return (delta >= 0 ? "+" : "") + config().format(delta);
    }

    private static String resolveLastActivity(@Nullable UUID uuid) {
        if (uuid == null) return "Never";
        PlayerBalance pb = getPlayerBalance(uuid);
        if (pb == null || pb.getLastTransactionTime() <= 0) return "Never";

        long elapsed = System.currentTimeMillis() - pb.getLastTransactionTime();
        if (elapsed < 0) return "Just now";

        long seconds = elapsed / 1000;
        if (seconds < 60) return seconds + "s ago";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "m ago";
        long hours = minutes / 60;
        if (hours < 24) return hours + "h ago";
        long days = hours / 24;
        if (days < 30) return days + "d ago";
        long months = days / 30;
        return months + "mo ago";
    }
    private static int computeRank(@Nullable UUID uuid) {
        if (uuid == null) return -1;
        double myBalance = EcotaleAPI.getBalance(uuid);
        String cacheKey = "rank_" + uuid;
        return cache.get(cacheKey, () -> {
            int higher = 0;
            for (PlayerBalance pb : economy().getAllBalances().values()) {
                if (pb.getBalance() > myBalance) {
                    higher++;
                }
            }
            return higher + 1;
        }, TTL_RANK);
    }

    private static String resolveRank(@Nullable UUID uuid) {
        int rank = computeRank(uuid);
        return rank > 0 ? "#" + rank : "N/A";
    }

    private static String resolveRankSuffix(@Nullable UUID uuid) {
        int rank = computeRank(uuid);
        if (rank <= 0) return "N/A";
        return rank + ordinalSuffix(rank);
    }

    private static String resolvePercentile(@Nullable UUID uuid) {
        int rank = computeRank(uuid);
        if (rank <= 0) return "N/A";
        int total = cachedAccountCount();
        if (total <= 0) return "N/A";
        double percentile = ((double) rank / total) * 100.0;
        if (percentile <= 1)  return "Top 1%";
        if (percentile <= 5)  return "Top 5%";
        if (percentile <= 10) return "Top 10%";
        if (percentile <= 25) return "Top 25%";
        if (percentile <= 50) return "Top 50%";
        return "Top " + (int) Math.ceil(percentile) + "%";
    }
    private static String resolveGapToFirst(@Nullable UUID uuid) {
        if (uuid == null) return "N/A";
        List<LeaderboardEntry> lb = cachedLeaderboard();
        if (lb.isEmpty()) return "N/A";
        double first = lb.getFirst().balance();
        double mine = EcotaleAPI.getBalance(uuid);
        double gap = first - mine;
        if (gap <= 0) return config().format(0);
        return config().format(gap);
    }

    private static String resolveGapToNext(@Nullable UUID uuid) {
        if (uuid == null) return "N/A";
        List<LeaderboardEntry> lb = cachedLeaderboard();
        double myBalance = EcotaleAPI.getBalance(uuid);

        // Find the player just above me
        double closestAbove = -1;
        for (LeaderboardEntry entry : lb) {
            if (entry.balance() > myBalance) {
                // Track the smallest balance that is still above mine
                if (closestAbove < 0 || entry.balance() < closestAbove) {
                    closestAbove = entry.balance();
                }
            }
        }

        if (closestAbove < 0) return "You're #1!";
        return config().format(closestAbove - myBalance);
    }

    private static String resolveAheadOf(@Nullable UUID uuid) {
        if (uuid == null) return "0";
        int rank = computeRank(uuid);
        if (rank <= 0) return "0";
        int total = cachedAccountCount();
        int aheadOf = total - rank;
        return aheadOf + " player" + (aheadOf != 1 ? "s" : "");
    }
    private static String resolveServerTotal() {
        double total = cache.get(CK_TOTAL, EcotaleAPI::getTotalCirculating, TTL_GLOBAL);
        return config().formatShort(total);
    }

    private static String resolveServerAverage() {
        double total = cache.get(CK_TOTAL, EcotaleAPI::getTotalCirculating, TTL_GLOBAL);
        int count = cachedAccountCount();
        if (count <= 0) return config().format(0);
        return config().format(total / count);
    }

    private static String resolveServerMedian() {
        double median = cache.get(CK_MEDIAN, () -> {
            var balances = economy().getAllBalances().values().stream()
                    .mapToDouble(PlayerBalance::getBalance)
                    .sorted()
                    .toArray();
            if (balances.length == 0) return 0.0;
            int mid = balances.length / 2;
            if (balances.length % 2 == 0) {
                return (balances[mid - 1] + balances[mid]) / 2.0;
            }
            return balances[mid];
        }, TTL_GLOBAL);
        return config().format(median);
    }

    private static String resolveServerPlayers() {
        return String.valueOf(cachedAccountCount());
    }
    private static double sessionDelta(@Nullable UUID uuid) {
        if (uuid == null) return 0;
        Double loginBal = loginBalances.get(uuid);
        if (loginBal == null) return 0;
        return EcotaleAPI.getBalance(uuid) - loginBal;
    }

    private static String resolveTrendSession(@Nullable UUID uuid) {
        if (uuid == null || !loginBalances.containsKey(uuid)) return "N/A";
        double delta = sessionDelta(uuid);
        return (delta >= 0 ? "+" : "") + config().format(delta);
    }

    private static String resolveTrendSessionPercent(@Nullable UUID uuid) {
        if (uuid == null) return "N/A";
        Double loginBal = loginBalances.get(uuid);
        if (loginBal == null) return "N/A";
        if (loginBal <= 0) return sessionDelta(uuid) > 0 ? "+INF%" : "0.0%";
        double pct = (sessionDelta(uuid) / loginBal) * 100.0;
        return (pct >= 0 ? "+" : "") + String.format("%.1f%%", pct);
    }

    /**
     * ASCII arrow indicator: UP / DOWN / -- (no Unicode).
     */
    private static String resolveTrendArrow(@Nullable UUID uuid) {
        if (uuid == null || !loginBalances.containsKey(uuid)) return "--";
        double delta = sessionDelta(uuid);
        if (delta > 0) return "UP";
        if (delta < 0) return "DOWN";
        return "--";
    }

    /**
     * Human-readable trend label combining arrow and amount.
     * Example: "UP +$350.00" or "DOWN -$120.00" or "-- $0.00"
     */
    private static String resolveTrendLabel(@Nullable UUID uuid) {
        if (uuid == null || !loginBalances.containsKey(uuid)) return "N/A";
        double delta = sessionDelta(uuid);
        String arrow = delta > 0 ? "UP" : delta < 0 ? "DOWN" : "--";
        String amount = (delta >= 0 ? "+" : "") + config().format(delta);
        return arrow + " " + amount;
    }
    @Nonnull
    private static String resolveDynamic(@Nullable UUID uuid, @Nonnull String key) {
        // balance_<n>dp
        Matcher m = BALANCE_DP.matcher(key);
        if (m.matches()) {
            int dp = Math.min(Integer.parseInt(m.group(1)), 10);
            return String.format("%." + dp + "f", getBalance(uuid));
        }

        // top_name_<n>
        m = TOP_NAME.matcher(key);
        if (m.matches()) {
            int rank = Integer.parseInt(m.group(1));
            return resolveTopName(rank);
        }

        // top_balance_<n>
        m = TOP_BAL.matcher(key);
        if (m.matches()) {
            int rank = Integer.parseInt(m.group(1));
            return resolveTopBalance(rank);
        }

        return "";
    }

    @Nonnull
    private static String resolveTopName(int rank) {
        if (rank < 1 || rank > MAX_TOP_RANK) return "N/A";
        List<LeaderboardEntry> lb = cachedLeaderboard();
        if (rank > lb.size()) return "N/A";
        LeaderboardEntry entry = lb.get(rank - 1);
        if (entry.name() != null && !entry.name().isEmpty()) {
            return entry.name();
        }
        return entry.uuid().toString().substring(0, 8) + "...";
    }

    @Nonnull
    private static String resolveTopBalance(int rank) {
        if (rank < 1 || rank > MAX_TOP_RANK) return "N/A";
        List<LeaderboardEntry> lb = cachedLeaderboard();
        if (rank > lb.size()) return "N/A";
        return config().format(lb.get(rank - 1).balance());
    }
    /**
     * Build sorted leaderboard from in-memory cache. Includes player name resolution
     * via {@link com.hypixel.hytale.server.core.universe.Universe} for online players,
     * falls back to storage name cache.
     */
    private static List<LeaderboardEntry> cachedLeaderboard() {
        return cache.get(CK_LEADERBOARD, () -> {
            Map<UUID, PlayerBalance> all = economy().getAllBalances();
            List<LeaderboardEntry> entries = new ArrayList<>(all.size());
            for (var e : all.entrySet()) {
                String name = resolvePlayerName(e.getKey());
                entries.add(new LeaderboardEntry(e.getKey(), name, e.getValue().getBalance()));
            }
            entries.sort(Comparator.comparingDouble(LeaderboardEntry::balance).reversed());
            // Cap at MAX_TOP_RANK to avoid holding huge lists
            if (entries.size() > MAX_TOP_RANK) {
                return entries.subList(0, MAX_TOP_RANK);
            }
            return entries;
        }, TTL_GLOBAL);
    }

    private static int cachedAccountCount() {
        return cache.get(CK_ACCOUNTS, () -> economy().getAllBalances().size(), TTL_GLOBAL);
    }
    @Nullable
    private static PlayerBalance getPlayerBalance(@Nonnull UUID uuid) {
        return economy().getPlayerBalance(uuid);
    }

    private static EcotaleConfig config() {
        return Main.CONFIG.get();
    }

    private static EconomyManager economy() {
        return Main.getInstance().getEconomyManager();
    }

    @Nonnull
    private static String resolvePlayerName(@Nonnull UUID uuid) {
        var service = com.ecotale.util.PlayerNameService.getInstance();
        return (service != null) ? service.resolve(uuid) : uuid.toString().substring(0, 8) + "...";
    }

    private static String fmtRaw(double value) {
        // Avoid trailing ".0" for whole numbers
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }

    private static String fmtCommas(double value) {
        return new DecimalFormat("#,##0").format(value);
    }

    /**
     * English ordinal suffix for a rank number.
     */
    private static String ordinalSuffix(int n) {
        int mod100 = n % 100;
        if (mod100 >= 11 && mod100 <= 13) return "th";
        return switch (n % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
    }

    /** Lightweight DTO for cached leaderboard entries. */
    record LeaderboardEntry(UUID uuid, String name, double balance) {}
}
