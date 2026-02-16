package com.ecotale.gui;

import com.ecotale.Main;
import com.ecotale.economy.PlayerBalance;
import com.ecotale.economy.TopBalanceEntry;
import com.ecotale.storage.H2StorageProvider;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * TopBalance GUI for players.
 * Displays top balances with online/offline status and pagination.
 */
public class TopBalanceGui extends InteractiveCustomUIPage<TopBalanceGui.TopBalanceData> {

    private static final int PAGE_SIZE = 10;
    private static final int WEEK_DAYS = 7;
    private static final int MONTH_DAYS = 30;

    private final PlayerRef playerRef;
    private int currentPage = 0;
    private Mode mode = Mode.ALL_TIME;

    private enum Mode {
        ALL_TIME,
        WEEKLY,
        MONTHLY
    }

    public TopBalanceGui(@NonNullDecl PlayerRef playerRef) {
        super(playerRef, CustomPageLifetime.CanDismiss, TopBalanceData.CODEC);
        this.playerRef = playerRef;
    }

    @Override
    public void build(@NonNullDecl Ref<EntityStore> ref, @NonNullDecl UICommandBuilder cmd,
                      @NonNullDecl UIEventBuilder events, @NonNullDecl Store<EntityStore> store) {
        cmd.append("Pages/Ecotale_TopBalancePage.ui");

        // Bind events
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton",
            EventData.of("Action", "Close"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#PrevButton",
            EventData.of("Action", "Prev"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#NextButton",
            EventData.of("Action", "Next"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#TabAllTime",
            EventData.of("Action", "TabAllTime"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#TabWeekly",
            EventData.of("Action", "TabWeekly"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#TabMonthly",
            EventData.of("Action", "TabMonthly"), false);

        // Static sections
        double myBalance = Main.getInstance().getEconomyManager().getBalance(playerRef.getUuid());
        cmd.set("#YourBalanceValue.Text", Main.CONFIG.get().format(myBalance));
        cmd.set("#YourRankValue.Text", "-");
        cmd.set("#TotalBalanceDisplay.Text", Main.CONFIG.get().formatShort(myBalance));

        // Initial loading state
        cmd.clear("#TopList");
        cmd.appendInline("#TopList", "Label { Text: \"Loading...\"; Style: (FontSize: 12, TextColor: #888888); Padding: (Top: 12); }");
        cmd.set("#PageLabel.Text", "Page " + (currentPage + 1));
        cmd.set("#CountLabel.Text", "");

        loadPage(ref, store, myBalance);
    }

    @Override
    public void handleDataEvent(@NonNullDecl Ref<EntityStore> ref, @NonNullDecl Store<EntityStore> store,
                                @NonNullDecl TopBalanceData data) {
        super.handleDataEvent(ref, store, data);

        if (data.action != null) {
            switch (data.action) {
                case "Close" -> {
                    this.close();
                    return;
                }
                case "Prev" -> {
                    if (currentPage > 0) {
                        currentPage--;
                    }
                }
                case "Next" -> currentPage++;
                case "TabAllTime" -> {
                    mode = Mode.ALL_TIME;
                    currentPage = 0;
                }
                case "TabWeekly" -> {
                    mode = Mode.WEEKLY;
                    currentPage = 0;
                }
                case "TabMonthly" -> {
                    mode = Mode.MONTHLY;
                    currentPage = 0;
                }
            }
        }

        double myBalance = Main.getInstance().getEconomyManager().getBalance(playerRef.getUuid());
        refreshUI(ref, store, myBalance);
    }

    private void refreshUI(@NonNullDecl Ref<EntityStore> ref, @NonNullDecl Store<EntityStore> store, double myBalance) {
        UICommandBuilder cmd = new UICommandBuilder();
        cmd.clear("#TopList");
        cmd.appendInline("#TopList", "Label { Text: \"Loading...\"; Style: (FontSize: 12, TextColor: #888888); Padding: (Top: 12); }");
        cmd.set("#PageLabel.Text", "Page " + (currentPage + 1));
        cmd.set("#YourBalanceValue.Text", Main.CONFIG.get().format(myBalance));
        this.sendUpdate(cmd, new UIEventBuilder(), false);

        loadPage(ref, store, myBalance);
    }

    private void loadPage(@NonNullDecl Ref<EntityStore> ref, @NonNullDecl Store<EntityStore> store, double myBalance) {
        H2StorageProvider h2 = Main.getInstance().getEconomyManager().getH2Storage();
        int offset = currentPage * PAGE_SIZE;

        if (h2 != null) {
            // Fast fallback from in-memory cache (Admin GUI uses this too)
            CachedPage cached = getCachedEntries(offset);
            if (!cached.entries.isEmpty()) {
                updateList(cached.entries, cached.totalCount, myBalance,
                    CompletableFuture.completedFuture(null));
            }

            CompletableFuture<List<TopBalanceEntry>> listFuture = switch (mode) {
                case ALL_TIME -> invokeTopQuery(h2, PAGE_SIZE, offset);
                case WEEKLY -> invokeTopPeriodQuery(h2, PAGE_SIZE, offset, WEEK_DAYS);
                case MONTHLY -> invokeTopPeriodQuery(h2, PAGE_SIZE, offset, MONTH_DAYS);
            };
            CompletableFuture<Integer> countFuture = invokeCountPlayers(h2);
            CompletableFuture<Integer> rankFuture = invokeCountRank(h2, myBalance);

            listFuture.thenCombineAsync(countFuture, (entries, total) -> {
                // If DB is empty or not yet populated, keep cached view
                if (entries == null || entries.isEmpty()) {
                    return null;
                }
                updateList(entries, total, myBalance, rankFuture);
                return null;
            });
            return;
        }

        // Fallback: cached leaderboard
        CachedPage cached = getCachedEntries(offset);
        updateList(cached.entries, cached.totalCount, myBalance, CompletableFuture.completedFuture(null));
    }

    private CachedPage getCachedEntries(int offset) {
        Map<UUID, PlayerBalance> allBalances = Main.getInstance().getEconomyManager().getAllBalances();
        List<Map.Entry<UUID, PlayerBalance>> sorted = allBalances.entrySet().stream()
            .sorted((a, b) -> Double.compare(b.getValue().getBalance(), a.getValue().getBalance()))
            .toList();

        int start = Math.min(offset, sorted.size());
        int end = Math.min(offset + PAGE_SIZE, sorted.size());
        List<TopBalanceEntry> entries = new ArrayList<>();
        for (int i = start; i < end; i++) {
            var entry = sorted.get(i);
            UUID uuid = entry.getKey();
            PlayerBalance balance = entry.getValue();
            String name = resolveName(uuid, null);
            entries.add(new TopBalanceEntry(uuid, name, balance.getBalance(), 0.0));
        }
        return new CachedPage(entries, sorted.size());
    }

    private record CachedPage(List<TopBalanceEntry> entries, int totalCount) {
    }

    private void updateList(List<TopBalanceEntry> entries, int totalCount, double myBalance,
                            CompletableFuture<Integer> rankFuture) {
        UICommandBuilder cmd = new UICommandBuilder();
        cmd.clear("#TopList");

        if (entries.isEmpty()) {
            cmd.appendInline("#TopList",
                "Label { Text: \"No data available\"; Style: (FontSize: 12, TextColor: #888888); Padding: (Top: 12); }");
        } else {
            int baseRank = currentPage * PAGE_SIZE;
            for (int i = 0; i < entries.size(); i++) {
                TopBalanceEntry entry = entries.get(i);
                int rank = baseRank + i + 1;
                String name = resolveName(entry.uuid(), entry.name());
                boolean online = Universe.get().getPlayer(entry.uuid()) != null;

                cmd.append("#TopList", "Pages/Ecotale_TopBalanceEntry.ui");
                cmd.set("#TopList[" + i + "] #Rank.Text", "#" + rank);
                cmd.set("#TopList[" + i + "] #PlayerName.Text", name);
                cmd.set("#TopList[" + i + "] #Balance.Text", Main.CONFIG.get().format(entry.balance()));
                cmd.set("#TopList[" + i + "] #Trend.Text", formatTrend(entry.trend()));
                cmd.set("#TopList[" + i + "] #StatusText.Text", online ? "Online" : "Offline");
            }
        }

        cmd.set("#PageLabel.Text", "Page " + (currentPage + 1));
        if (totalCount > 0) {
            cmd.set("#CountLabel.Text", totalCount + " players");
        } else {
            cmd.set("#CountLabel.Text", "");
        }

        cmd.set("#PrevButton.Visible", currentPage > 0);
        cmd.set("#NextButton.Visible", entries.size() >= PAGE_SIZE);

        rankFuture.thenAcceptAsync(rank -> {
            UICommandBuilder rankCmd = new UICommandBuilder();
            if (rank != null) {
                rankCmd.set("#YourRankValue.Text", "#" + (rank + 1));
            }
            this.sendUpdate(rankCmd, new UIEventBuilder(), false);
        });

        this.sendUpdate(cmd, new UIEventBuilder(), false);
    }

    private String formatTrend(double trend) {
        if (mode == Mode.ALL_TIME) {
            return "-";
        }
        if (Math.abs(trend) < 0.01) {
            return "0";
        }
        String sign = trend > 0 ? "+" : "-";
        return sign + Main.CONFIG.get().formatShort(Math.abs(trend));
    }

    private String resolveName(UUID uuid, String cachedName) {
        if (cachedName != null && !cachedName.isBlank()) return cachedName;
        var service = com.ecotale.util.PlayerNameService.getInstance();
        return (service != null) ? service.resolve(uuid) : uuid.toString().substring(0, 8) + "...";
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<List<TopBalanceEntry>> invokeTopQuery(H2StorageProvider h2, int limit, int offset) {
        try {
            var method = h2.getClass().getMethod("queryTopBalancesAsync", int.class, int.class);
            Object result = method.invoke(h2, limit, offset);
            if (result instanceof CompletableFuture<?> future) {
                return (CompletableFuture<List<TopBalanceEntry>>) future;
            }
        } catch (Exception ignored) {
        }
        return CompletableFuture.completedFuture(new ArrayList<>());
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<List<TopBalanceEntry>> invokeTopPeriodQuery(H2StorageProvider h2, int limit, int offset, int daysAgo) {
        try {
            var method = h2.getClass().getMethod("queryTopBalancesPeriodAsync", int.class, int.class, int.class);
            Object result = method.invoke(h2, limit, offset, daysAgo);
            if (result instanceof CompletableFuture<?> future) {
                return (CompletableFuture<List<TopBalanceEntry>>) future;
            }
        } catch (Exception ignored) {
        }
        return CompletableFuture.completedFuture(new ArrayList<>());
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<Integer> invokeCountPlayers(H2StorageProvider h2) {
        try {
            var method = h2.getClass().getMethod("countPlayersAsync");
            Object result = method.invoke(h2);
            if (result instanceof CompletableFuture<?> future) {
                return (CompletableFuture<Integer>) future;
            }
        } catch (Exception ignored) {
        }
        return CompletableFuture.completedFuture(0);
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<Integer> invokeCountRank(H2StorageProvider h2, double balance) {
        try {
            var method = h2.getClass().getMethod("countPlayersWithBalanceGreaterAsync", double.class);
            Object result = method.invoke(h2, balance);
            if (result instanceof CompletableFuture<?> future) {
                return (CompletableFuture<Integer>) future;
            }
        } catch (Exception ignored) {
        }
        return CompletableFuture.completedFuture(null);
    }
    public static class TopBalanceData {
        private static final String KEY_ACTION = "Action";

        public String action;

        public static final BuilderCodec<TopBalanceData> CODEC = BuilderCodec
            .<TopBalanceData>builder(TopBalanceData.class, TopBalanceData::new)
            .append(new KeyedCodec<>(KEY_ACTION, Codec.STRING), (d, v, e) -> d.action = v, (d, e) -> d.action).add()
            .build();
    }
}
