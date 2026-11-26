package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.resource.ResourceHistoryReceipt;
import io.github.addxiaoyi.starchronicle.history.ResourceHistoryProjection;
import java.sql.*;
import java.util.*;
import static io.github.addxiaoyi.starchronicle.persistence.SqliteStore.*;

/** Full receipt and historical event commit together. This ledger never mutates assets. */
public final class ResourceHistoryLedger {
    private ResourceHistoryLedger() {}
    public static void createSchema(Connection c) throws SQLException {
        try (var s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS resource_history_receipts(event_id TEXT PRIMARY KEY REFERENCES events(id),"
                    + "authority_domain TEXT NOT NULL,sequence INTEGER NOT NULL CHECK(sequence>0),operation_id TEXT NOT NULL,"
                    + "json TEXT NOT NULL,UNIQUE(authority_domain,sequence),UNIQUE(authority_domain,operation_id))");
        }
    }
    static void record(Connection c, ResourceHistoryReceipt receipt) throws SQLException {
        if (c.getAutoCommit()) throw new IllegalStateException("Resource history requires an enclosing transaction");
        String event = ResourceHistoryProjection.eventId(receipt);
        var old = receipt(c, event);
        if (old.isPresent()) {
            if (!old.get().equals(receipt)) throw new IllegalArgumentException("Resource receipt identity collision");
            return;
        }
        try (var s = c.prepareStatement("INSERT INTO resource_history_receipts(event_id,authority_domain,sequence,operation_id,json) VALUES(?,?,?,?,?)")) {
            s.setString(1, event); s.setString(2, receipt.authorityDomain().toString()); s.setLong(3, receipt.sequence());
            s.setString(4, receipt.operationId().toString()); s.setString(5, json(receipt)); s.executeUpdate();
        }
    }
    public static Optional<ResourceHistoryReceipt> receipt(Connection c, String eventId) throws SQLException {
        if (eventId == null || eventId.isBlank() || eventId.length() > 256) throw new IllegalArgumentException("Invalid resource event ID");
        try (var s = c.prepareStatement("SELECT json FROM resource_history_receipts WHERE event_id=?")) {
            s.setString(1, eventId);
            try (var r = s.executeQuery()) { return r.next() ? Optional.of(decode(r.getString(1), ResourceHistoryReceipt.class)) : Optional.empty(); }
        }
    }
}
