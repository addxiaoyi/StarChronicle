package io.github.addxiaoyi.starchronicle.api.trade;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Lossless native journal evidence. No current player/nation lookup is involved. */
public record TradeHistoryReceipt(UUID authorityDomain, long sequence, Instant committedAt,
                                  Instant occurredAt, Status previousStatus, Trade trade) {
    public enum Status { COMPLETED, DISPUTED, REFUNDED, CANCELLED }

    public TradeHistoryReceipt {
        Objects.requireNonNull(authorityDomain); Objects.requireNonNull(committedAt);
        Objects.requireNonNull(occurredAt); Objects.requireNonNull(trade);
        if (sequence < 1) throw new IllegalArgumentException("Invalid trade history sequence");
    }

    public record Trade(UUID recordId, String orderType, String orderSource,
                        UUID sellerPlayer, UUID buyerPlayer, UUID sellerNation, UUID buyerNation,
                        String resource, long amount, double pricePerUnit, double totalValue,
                        double taxAmount, double netValue, Instant executedAt,
                        UUID buyOrder, UUID sellOrder, Status status, String notes) {
        public Trade {
            Objects.requireNonNull(recordId); Objects.requireNonNull(executedAt); Objects.requireNonNull(status);
            if (!java.util.Set.of("BUY", "SELL").contains(orderType)
                    || !java.util.Set.of("PLAYER", "NATION", "MARKET", "EVENT").contains(orderSource))
                throw new IllegalArgumentException("Invalid trade order kind");
            if (resource == null || !resource.matches("[a-z0-9_.:-]{1,64}") || amount <= 0
                    || !Double.isFinite(pricePerUnit) || pricePerUnit <= 0
                    || !Double.isFinite(totalValue) || Double.compare(totalValue, amount * pricePerUnit) != 0
                    || !Double.isFinite(taxAmount) || taxAmount < 0 || taxAmount > totalValue
                    || !Double.isFinite(netValue) || Double.compare(netValue, totalValue - taxAmount) != 0)
                throw new IllegalArgumentException("Invalid trade amounts or resource");
        }

        /** Native status/notes may change; all economic and historical identity fields are fixed. */
        public Trade identity() {
            return new Trade(recordId, orderType, orderSource, sellerPlayer, buyerPlayer, sellerNation,
                    buyerNation, resource, amount, pricePerUnit, totalValue, taxAmount, netValue,
                    executedAt, buyOrder, sellOrder, Status.COMPLETED, null);
        }
    }
}
