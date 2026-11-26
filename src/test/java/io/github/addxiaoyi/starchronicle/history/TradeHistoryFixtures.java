package io.github.addxiaoyi.starchronicle.history;

import io.github.addxiaoyi.starchronicle.api.trade.TradeHistoryReceipt;
import io.github.addxiaoyi.starchronicle.api.trade.TradeHistoryReceipt.*;
import java.time.Instant;
import java.util.*;

public final class TradeHistoryFixtures {
    public static final UUID DOMAIN = new UUID(0, 10), RECORD = new UUID(0, 20);
    public static final UUID SELLER = new UUID(0, 30), BUYER = new UUID(0, 31);
    public static final UUID SELLER_NATION = new UUID(0, 40), BUYER_NATION = new UUID(0, 41);
    public static final Instant TIME = Instant.parse("2026-11-26T02:01:00.123456789Z");
    public static Trade trade(Status status) { return trade(status, RECORD, SELLER, BUYER, SELLER_NATION, BUYER_NATION, "agricultural", 7, "original"); }
    public static Trade trade(Status status, UUID id, UUID seller, UUID buyer, UUID sellerNation, UUID buyerNation, String resource, long amount, String notes) {
        double total = amount * 2.1;
        return new Trade(id, "BUY", "PLAYER", seller, buyer, sellerNation, buyerNation, resource, amount, 2.1, total, 0.1, total - 0.1,
                TIME.minusSeconds(100), new UUID(0, 50), new UUID(0, 51), status, notes);
    }
    public static TradeHistoryReceipt receipt(long sequence, Status previous, Status status) { return receipt(sequence, previous, trade(status)); }
    public static TradeHistoryReceipt receipt(long sequence, Status previous, Trade trade) {
        // Commit clocks are deliberately not monotonic; sequence alone defines source order.
        return new TradeHistoryReceipt(DOMAIN, sequence, TIME.minusSeconds(sequence), TIME.minusSeconds(sequence + 10), previous, trade);
    }
    public static CommittedHistoryJournal.Entry entry(TradeHistoryReceipt receipt) {
        return new CommittedHistoryJournal.Entry(receipt.sequence(), TradeHistoryProjection.event(receipt), receipt);
    }
    public static CommittedHistoryJournal.Pending pending(TradeHistoryReceipt receipt, boolean activate) {
        return new CommittedHistoryJournal.Pending(TradeHistoryProjection.event(receipt), activate, receipt);
    }
}
