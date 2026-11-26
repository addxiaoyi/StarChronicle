package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.trade.TradeHistoryReceipt;
import io.github.addxiaoyi.starchronicle.api.contribution.*;
import io.github.addxiaoyi.starchronicle.history.TradeHistoryProjection;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static io.github.addxiaoyi.starchronicle.persistence.SqliteStore.*;

/** Local receipt, original contribution and status revisions share the event transaction. */
public final class TradeContributionLedger {
    private TradeContributionLedger() {}
    public static void createSchema(Connection c) throws SQLException {
        try (var s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS trade_history_receipts(event_id TEXT PRIMARY KEY REFERENCES events(id),"
                    + "authority_domain TEXT NOT NULL,sequence INTEGER NOT NULL CHECK(sequence>0),record_id TEXT NOT NULL,"
                    + "json TEXT NOT NULL,UNIQUE(authority_domain,sequence))");
            s.execute("CREATE INDEX IF NOT EXISTS trade_history_record ON trade_history_receipts(authority_domain,record_id,sequence DESC)");
        }
    }

    static void record(Connection c, TradeHistoryReceipt receipt, Instant recordedAt) throws SQLException {
        if (c.getAutoCommit()) throw new IllegalStateException("Trade contribution requires an enclosing transaction");
        String event = TradeHistoryProjection.eventId(receipt);
        var old = receipt(c, event);
        if (old.isPresent()) {
            if (!old.get().equals(receipt)) throw new IllegalArgumentException("Trade receipt identity collision");
            return;
        }
        try (var s = c.prepareStatement("SELECT json FROM trade_history_receipts WHERE authority_domain=? AND record_id=? ORDER BY sequence DESC LIMIT 1")) {
            s.setString(1, receipt.authorityDomain().toString()); s.setString(2, receipt.trade().recordId().toString());
            try (var r = s.executeQuery()) { if (r.next()) {
                var previous = decode(r.getString(1), TradeHistoryReceipt.class);
                if (receipt.sequence() <= previous.sequence() || receipt.previousStatus() != previous.trade().status()
                        || !receipt.trade().identity().equals(previous.trade().identity()))
                    throw new IllegalArgumentException("Trade history reordered or changed its historical identity");
            } }
        }
        try (var s = c.prepareStatement("INSERT INTO trade_history_receipts(event_id,authority_domain,sequence,record_id,json) VALUES(?,?,?,?,?)")) {
            s.setString(1, event); s.setString(2, receipt.authorityDomain().toString()); s.setLong(3, receipt.sequence());
            s.setString(4, receipt.trade().recordId().toString()); s.setString(5, json(receipt)); s.executeUpdate();
        }
        Instant at = recordedAt.isBefore(receipt.committedAt()) ? receipt.committedAt() : recordedAt;
        if (receipt.trade().status() == TradeHistoryReceipt.Status.COMPLETED) {
            for (var request : TradeHistoryProjection.contributions(receipt)) {
                var existing = ContributionLedger.find(c, request.operationId(), request.type(), request.subjectId());
                if (existing.isEmpty()) ContributionLedger.record(c, request, at);
                else {
                    var original = existing.get().request();
                    if (!original.actorId().equals(request.actorId()) || !Objects.equals(original.nationId(), request.nationId())
                            || original.amount().compareTo(request.amount()) != 0 || !original.unit().equals(request.unit()))
                        throw new IllegalStateException("Trade contribution already bound to different evidence");
                }
            }
        }
        // A refund of an old pre-journal trade has no positive contribution to reverse.
        // Record its full history but never create a synthetic negative balance.
        var status = switch (receipt.trade().status()) {
            case COMPLETED -> ContributionRevision.Status.ACTIVE; case DISPUTED -> ContributionRevision.Status.SUSPENDED;
            case REFUNDED -> ContributionRevision.Status.REFUNDED; case CANCELLED -> ContributionRevision.Status.CANCELLED;
        };
        try (var s = c.prepareStatement("INSERT INTO contribution_revisions(contribution_id,source_event_id,status,committed_at,recorded_at) "
                + "SELECT id,?,?,?,? FROM contributions WHERE operation_id=? AND type='TRADE'")) {
            s.setString(1, event); s.setString(2, status.name()); s.setString(3, receipt.committedAt().toString());
            s.setString(4, at.toString()); s.setString(5, TradeHistoryProjection.operation(receipt)); s.executeUpdate();
        }
    }

    public static Optional<TradeHistoryReceipt> receipt(Connection c, String eventId) throws SQLException {
        ContributionRequest.token(eventId, "eventId", 256);
        try (var s = c.prepareStatement("SELECT json FROM trade_history_receipts WHERE event_id=?")) {
            s.setString(1, eventId);
            try (var r = s.executeQuery()) { return r.next() ? Optional.of(decode(r.getString(1), TradeHistoryReceipt.class)) : Optional.empty(); }
        }
    }
}
