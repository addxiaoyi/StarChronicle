package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.processing.ProcessingHistoryReceipt;
import io.github.addxiaoyi.starchronicle.history.ProcessingHistoryProjection;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static io.github.addxiaoyi.starchronicle.persistence.SqliteStore.*;

/** Completed production and its complete native evidence commit in the same event transaction. */
public final class ProcessingContributionLedger {
    private ProcessingContributionLedger() {}
    public static void createSchema(Connection c) throws SQLException {
        try (var s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS processing_history_receipts(event_id TEXT PRIMARY KEY REFERENCES events(id),"
                    + "authority_domain TEXT NOT NULL,sequence INTEGER NOT NULL CHECK(sequence>0),run_id TEXT NOT NULL,kind TEXT NOT NULL,"
                    + "json TEXT NOT NULL,UNIQUE(authority_domain,sequence),UNIQUE(authority_domain,run_id,kind))");
            s.execute("CREATE INDEX IF NOT EXISTS processing_history_run ON processing_history_receipts(authority_domain,run_id,sequence)");
        }
    }
    static void record(Connection c, ProcessingHistoryReceipt r, Instant recordedAt) throws SQLException {
        if (c.getAutoCommit()) throw new IllegalStateException("Processing contribution requires an enclosing transaction");
        String event = ProcessingHistoryProjection.eventId(r); var old = receipt(c, event);
        if (old.isPresent()) {
            if (!old.get().equals(r)) throw new IllegalArgumentException("Processing receipt identity collision");
            return;
        }
        List<ProcessingHistoryReceipt> prior = new ArrayList<>();
        try (var s = c.prepareStatement("SELECT json FROM processing_history_receipts WHERE authority_domain=? AND run_id=? ORDER BY sequence LIMIT 3")) {
            s.setString(1, r.authorityDomain().toString()); s.setString(2, r.run().runId().toString());
            try (var rows = s.executeQuery()) { while (rows.next()) prior.add(decode(rows.getString(1), ProcessingHistoryReceipt.class)); }
        }
        if (r.kind() == ProcessingHistoryReceipt.Kind.COMPLETED) {
            if (prior.size() != 1 || prior.getFirst().kind() != ProcessingHistoryReceipt.Kind.STARTED
                    || !prior.getFirst().run().equals(r.run().started()) || prior.getFirst().sequence() >= r.sequence())
                throw new IllegalArgumentException("Processing completion is missing its exact committed start");
        } else if (!prior.isEmpty()) throw new IllegalArgumentException("Processing run identity was reused");
        try (var s = c.prepareStatement("INSERT INTO processing_history_receipts(event_id,authority_domain,sequence,run_id,kind,json) VALUES(?,?,?,?,?,?)")) {
            s.setString(1, event); s.setString(2, r.authorityDomain().toString()); s.setLong(3, r.sequence());
            s.setString(4, r.run().runId().toString()); s.setString(5, r.kind().name()); s.setString(6, json(r)); s.executeUpdate();
        }
        Instant at = recordedAt.isBefore(r.committedAt()) ? r.committedAt() : recordedAt;
        for (var request : ProcessingHistoryProjection.contributions(r)) ContributionLedger.record(c, request, at);
    }
    public static Optional<ProcessingHistoryReceipt> receipt(Connection c, String eventId) throws SQLException {
        if (eventId == null || eventId.isBlank() || eventId.length() > 256) throw new IllegalArgumentException("Invalid processing event ID");
        try (var s = c.prepareStatement("SELECT json FROM processing_history_receipts WHERE event_id=?")) {
            s.setString(1, eventId);
            try (var r = s.executeQuery()) { return r.next() ? Optional.of(decode(r.getString(1), ProcessingHistoryReceipt.class)) : Optional.empty(); }
        }
    }
}
