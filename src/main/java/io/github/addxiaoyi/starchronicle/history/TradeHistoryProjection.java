package io.github.addxiaoyi.starchronicle.history;

import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import io.github.addxiaoyi.starchronicle.api.trade.TradeHistoryReceipt;
import io.github.addxiaoyi.starchronicle.api.contribution.*;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Pure projection of the receipt. It cannot execute a trade or award a story effect. */
public final class TradeHistoryProjection {
    public static final String NAMESPACE = "starcore.trade";
    private TradeHistoryProjection() {}

    public static String operation(TradeHistoryReceipt receipt) {
        return "starcore:trade:" + receipt.authorityDomain() + ":" + receipt.trade().recordId();
    }
    public static String eventId(TradeHistoryReceipt receipt) {
        return "starcore:trade-change:" + receipt.authorityDomain() + ":" + receipt.sequence();
    }
    public static WorldEvent event(TradeHistoryReceipt receipt) {
        var trade = receipt.trade();
        Map<String,String> facts = new TreeMap<>();
        facts.put("title", switch (trade.status()) {
            case COMPLETED -> "贸易成交（原生提交）"; case DISPUTED -> "贸易争议（贡献暂停）";
            case REFUNDED -> "贸易退款（贡献冲正）"; case CANCELLED -> "贸易取消（贡献冲正）";
        });
        facts.put("history.namespace", NAMESPACE); facts.put("history.authority", receipt.authorityDomain().toString());
        facts.put("history.sequence", Long.toString(receipt.sequence())); facts.put("history.operation", trade.recordId().toString());
        facts.put("history.committed_at", receipt.committedAt().toString()); facts.put("time_basis", "COMMITTED_SOURCE");
        facts.put("trade.executed_at", trade.executedAt().toString()); facts.put("trade.status", trade.status().name());
        facts.put("trade.previous_status", receipt.previousStatus() == null ? "NONE" : receipt.previousStatus().name());
        facts.put("trade.resource", trade.resource()); facts.put("trade.amount", Long.toString(trade.amount()));
        facts.put("trade.price_per_unit", Double.toString(trade.pricePerUnit())); facts.put("trade.total_value", Double.toString(trade.totalValue()));
        facts.put("trade.tax_amount", Double.toString(trade.taxAmount())); facts.put("trade.net_value", Double.toString(trade.netValue()));
        facts.put("trade.order_type", trade.orderType()); facts.put("trade.order_source", trade.orderSource());
        put(facts, "trade.seller_player", trade.sellerPlayer()); put(facts, "trade.buyer_player", trade.buyerPlayer());
        put(facts, "trade.seller_nation", trade.sellerNation()); put(facts, "trade.buyer_nation", trade.buyerNation());
        put(facts, "trade.buy_order", trade.buyOrder()); put(facts, "trade.sell_order", trade.sellOrder());
        facts.put("contribution.policy", "RESOURCE_QUANTITY_PER_DISTINCT_SUBJECT_V1");
        if ((trade.sellerPlayer() == null && trade.sellerNation() != null)
                || (trade.buyerPlayer() == null && trade.buyerNation() != null))
            facts.put("contribution.unattributed_nation", "No player actor in native receipt; retain history only for that side");
        if (trade.sellerPlayer() == null && trade.buyerPlayer() == null)
            facts.put("contribution.omission", "NO_NATIVE_PLAYER_ACTOR");
        if (trade.notes() != null) {
            facts.put("trade.notes_sha256", hash(trade.notes()));
            facts.put("trade.notes_length", Integer.toString(trade.notes().length()));
            // The full notes are always preserved in the separately queryable receipt. A long
            // native note must neither truncate evidence nor block the continuous source.
            if (trade.notes().length() <= 4096) facts.put("trade.notes", trade.notes());
        }
        facts.put("trade.receipt_sha256", hash(SqliteStore.json(receipt)));
        Set<UUID> players = new HashSet<>(); Set<String> nations = new HashSet<>();
        if (trade.sellerPlayer() != null) players.add(trade.sellerPlayer());
        if (trade.buyerPlayer() != null) players.add(trade.buyerPlayer());
        if (trade.sellerNation() != null) nations.add(trade.sellerNation().toString());
        if (trade.buyerNation() != null) nations.add(trade.buyerNation().toString());
        String type = receipt.previousStatus() == trade.status() ? "TRADE_UPDATED"
                : trade.status() == TradeHistoryReceipt.Status.COMPLETED && receipt.previousStatus() != null
                    ? "TRADE_RESTORED" : "TRADE_" + trade.status();
        return new WorldEvent(eventId(receipt), type, receipt.occurredAt(), players, nations, facts,
                "StarCore", "TradeHistoryService.tradeChangesAfter; authority=" + receipt.authorityDomain()
                + "; sequence=" + receipt.sequence() + "; record=" + trade.recordId()
                + "; full native receipt retained; original actors and nations; no asset operation");
    }

    /** Both sides participate; count each player/nation once per trade. A nation row needs
     * an actor on that same native side. Same-player conflicting nations remain unattributed. */
    public static List<ContributionRequest> contributions(TradeHistoryReceipt receipt) {
        var t = receipt.trade();
        Map<String,UUID> actors = new LinkedHashMap<>(); Map<String,UUID> nations = new HashMap<>();
        side(actors, nations, t.sellerPlayer(), t.sellerNation());
        side(actors, nations, t.buyerPlayer(), t.buyerNation());
        List<ContributionRequest> result = new ArrayList<>();
        for (var entry : actors.entrySet()) {
            UUID nation = nations.get(entry.getKey());
            result.add(new ContributionRequest(operation(receipt), ContributionType.TRADE, entry.getKey(), eventId(receipt),
                    entry.getValue(), nation == null ? null : nation.toString(), BigDecimal.valueOf(t.amount()), t.resource(),
                    receipt.committedAt(), "Native trade quantity; authority=" + receipt.authorityDomain()
                    + "; record=" + t.recordId() + "; first observed completed sequence=" + receipt.sequence()));
        }
        return List.copyOf(result);
    }
    private static void side(Map<String,UUID> actors, Map<String,UUID> nations, UUID actor, UUID nation) {
        if (actor == null) return;
        String playerKey = "player:" + actor;
        if (actors.containsKey(playerKey) && !Objects.equals(nations.get(playerKey), nation)) nations.put(playerKey, null);
        else if (!actors.containsKey(playerKey)) nations.put(playerKey, nation);
        actors.putIfAbsent(playerKey, actor);
        if (nation != null) {
            String nationKey = "nation:" + nation;
            actors.putIfAbsent(nationKey, actor); nations.put(nationKey, nation);
        }
    }
    private static void put(Map<String,String> facts, String key, UUID value) { if (value != null) facts.put(key, value.toString()); }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
