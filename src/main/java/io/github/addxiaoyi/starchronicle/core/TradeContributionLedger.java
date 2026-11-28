package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.contribution.*;
import io.github.addxiaoyi.starchronicle.history.CommittedSourcePage;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import io.github.addxiaoyi.starchronicle.api.trade.TradeHistoryReceipt;
import io.github.addxiaoyi.starchronicle.history.TradeHistoryProjection;
import static io.github.addxiaoyi.starchronicle.persistence.SqliteStore.*;

/** Append-only contribution evidence with a separate current native status projection. */
public final class TradeContributionLedger {
    private TradeContributionLedger() {}
    public static void createSchema(Connection c)throws SQLException {
        try(var s=c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS native_trade_states(source TEXT NOT NULL,domain TEXT NOT NULL,record_id TEXT NOT NULL,terms_hash TEXT NOT NULL,status TEXT NOT NULL,sequence INTEGER NOT NULL,credited INTEGER NOT NULL,suppress_credit INTEGER NOT NULL,PRIMARY KEY(source,domain,record_id))");
            s.execute("CREATE TABLE IF NOT EXISTS native_trade_contributions(contribution_id TEXT PRIMARY KEY REFERENCES contributions(id),source TEXT NOT NULL,domain TEXT NOT NULL,record_id TEXT NOT NULL,FOREIGN KEY(source,domain,record_id) REFERENCES native_trade_states(source,domain,record_id))");
            s.execute("CREATE TABLE IF NOT EXISTS trade_history_receipts(event_id TEXT PRIMARY KEY REFERENCES events(id),"
                    + "authority_domain TEXT NOT NULL,sequence INTEGER NOT NULL CHECK(sequence>0),record_id TEXT NOT NULL,"
                    + "json TEXT NOT NULL,UNIQUE(authority_domain,sequence))");
            s.execute("CREATE INDEX IF NOT EXISTS trade_history_record ON trade_history_receipts(authority_domain,record_id,sequence DESC)");
        }
    }
    public static void apply(Connection c,CommittedSourcePage page,CommittedSourcePage.Entry entry,Instant recordedAt)throws SQLException {
        var trade=entry.trade();if(trade==null)return;
        boolean exists=false,credited=false,suppress=false;
        try(var s=c.prepareStatement("SELECT terms_hash,sequence,credited,suppress_credit FROM native_trade_states WHERE source=? AND domain=? AND record_id=?")) {
            s.setString(1,page.source());s.setString(2,page.domain());s.setString(3,trade.recordId());
            try(var r=s.executeQuery()) {if(r.next()) {
                exists=true;
                if(!r.getString(1).equals(trade.termsHash())||entry.sequence()<=r.getLong(2))throw new IllegalStateException("Trade terms changed or status moved backwards");
                credited=r.getInt(3)!=0;suppress=r.getInt(4)!=0;
            }}
        }
        if(entry.baseline()) {
            if(exists)throw new IllegalStateException("Trade baseline appeared after a prior transition");
            // Migration cannot prove whether a disputed/refunded trade was credited in old history.
            suppress=true;
        }
        boolean credit=!entry.baseline()&&!suppress&&!credited&&trade.status().equals("COMPLETED");
        try(var s=c.prepareStatement("INSERT INTO native_trade_states(source,domain,record_id,terms_hash,status,sequence,credited,suppress_credit) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(source,domain,record_id) DO UPDATE SET status=excluded.status,sequence=excluded.sequence,credited=excluded.credited,suppress_credit=excluded.suppress_credit")) {
            s.setString(1,page.source());s.setString(2,page.domain());s.setString(3,trade.recordId());s.setString(4,trade.termsHash());s.setString(5,trade.status());s.setLong(6,entry.sequence());s.setInt(7,credited||credit?1:0);s.setInt(8,suppress?1:0);s.executeUpdate();
        }
        if(credit)for(var request:trade.contributions()) {
            if(request.type()!=ContributionType.TRADE)throw new IllegalArgumentException("Non-trade contribution in trade journal");
            var result=ContributionLedger.record(c,request,recordedAt);
            try(var s=c.prepareStatement("INSERT INTO native_trade_contributions(contribution_id,source,domain,record_id) VALUES(?,?,?,?)")) {
                s.setString(1,result.entry().id());s.setString(2,page.source());s.setString(3,page.domain());s.setString(4,trade.recordId());s.executeUpdate();
            }
        }
        if(!entry.baseline())try(var s=c.prepareStatement("INSERT INTO contribution_revisions(contribution_id,source_event_id,status,committed_at,recorded_at) "
                + "SELECT contribution_id,?,?,?,? FROM native_trade_contributions WHERE source=? AND domain=? AND record_id=?")) {
            s.setString(1,entry.event().id());s.setString(2,status(trade.status()).name());
            s.setString(3,entry.event().occurredAt().toString());s.setString(4,recordedAt.toString());
            s.setString(5,page.source());s.setString(6,page.domain());s.setString(7,trade.recordId());s.executeUpdate();
        }
    }

    /** An installed legacy row may predate the revisions table. Read its actual last receipt;
     * never reinterpret its original contribution timestamp as the time of a later refund. */
    static ContributionBalance legacyBalance(Connection c,ContributionEntry entry)throws SQLException {
        try(var s=c.prepareStatement("SELECT r.json,s.status,s.record_id FROM native_trade_contributions t JOIN native_trade_states s "
                + "ON s.source=t.source AND s.domain=t.domain AND s.record_id=t.record_id LEFT JOIN committed_source_receipts r "
                + "ON r.source=s.source AND r.domain=s.domain AND r.sequence=s.sequence WHERE t.contribution_id=?")) {
            s.setString(1,entry.id());
            try(var rows=s.executeQuery()) {
                if(rows.next()) {
                    if(rows.getString(1)==null)throw new IllegalStateException("Legacy trade state has no committed receipt");
                    var receipt=decode(rows.getString(1),CommittedSourcePage.Entry.class);
                    if(receipt.trade()==null||!receipt.trade().recordId().equals(rows.getString(3))
                            ||!receipt.trade().status().equals(rows.getString(2)))
                        throw new IllegalStateException("Legacy trade receipt conflicts with its state");
                    return new ContributionBalance(entry,status(rows.getString(2)),receipt.event().id(),receipt.event().occurredAt());
                }
            }
        }
        return new ContributionBalance(entry,status(entry.sourceStatus()),entry.request().sourceEventId(),entry.request().committedAt());
    }

    private static ContributionRevision.Status status(String value) {
        return switch(value) {
            case "COMMITTED","COMPLETED" -> ContributionRevision.Status.ACTIVE;
            case "DISPUTED" -> ContributionRevision.Status.SUSPENDED;
            case "REFUNDED" -> ContributionRevision.Status.REFUNDED;
            case "CANCELLED" -> ContributionRevision.Status.CANCELLED;
            default -> throw new IllegalArgumentException("Unknown trade contribution status");
        };
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
