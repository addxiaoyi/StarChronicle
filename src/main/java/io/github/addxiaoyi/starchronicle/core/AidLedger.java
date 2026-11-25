package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.aid.AidReceipt;
import io.github.addxiaoyi.starchronicle.api.aid.AidRequest;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import static io.github.addxiaoyi.starchronicle.persistence.SqliteStore.decode;
import static io.github.addxiaoyi.starchronicle.persistence.SqliteStore.json;

/** Durable local state for one native national-aid attempt.  This table is an
 * intent/reconciliation ledger; it never claims authority over StarCore money. */
public final class AidLedger {
    public enum Status { READY, ADMITTED, UNKNOWN, COMMITTED, REJECTED }

    public record Row(String issuer, String authorityDomain, String transactionId,
                      Status status, AidRequest request, AidReceipt receipt,
                      String eventId, String detail, Instant updatedAt) {
        public Row {
            Objects.requireNonNull(status);
            Objects.requireNonNull(request);
            Objects.requireNonNull(updatedAt);
            detail = detail == null ? "" : detail;
            if (detail.length() > 2048) detail = detail.substring(0, 2048);
        }
    }

    private AidLedger() {}

    public static void createSchema(Connection c) throws SQLException {
        try (var s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS aid_attempts(" +
                    "issuer TEXT NOT NULL,authority_domain TEXT NOT NULL,transaction_id TEXT NOT NULL," +
                    "status TEXT NOT NULL CHECK(status IN ('READY','ADMITTED','UNKNOWN','COMMITTED','REJECTED'))," +
                    "request_json TEXT NOT NULL,receipt_json TEXT,event_id TEXT,detail TEXT NOT NULL," +
                    "updated TEXT NOT NULL,PRIMARY KEY(issuer,transaction_id))");
            s.execute("CREATE INDEX IF NOT EXISTS aid_attempt_status ON aid_attempts(status,updated,issuer,transaction_id)");
        }
    }

    public static Optional<Row> find(Connection c, String issuer, String transactionId) throws SQLException {
        try (var s = c.prepareStatement("SELECT issuer,authority_domain,transaction_id,status,request_json,receipt_json,event_id,detail,updated FROM aid_attempts WHERE issuer=? AND transaction_id=?")) {
            s.setString(1, issuer);
            s.setString(2, transactionId);
            try (var r = s.executeQuery()) {
                if (!r.next()) return Optional.empty();
                return Optional.of(read(r));
            }
        }
    }

    /** Creates an intent and marks it admitted in one local transaction.  A
     * second caller never receives permission to invoke the native provider. */
    public static boolean admit(Connection c, AidRequest request, String issuer,
                                String authorityDomain, Instant now) throws SQLException {
        String detail = "Native call admitted; provider invocation is outside the Chronicle transaction";
        try (var s = c.prepareStatement("INSERT INTO aid_attempts(issuer,authority_domain,transaction_id,status,request_json,receipt_json,event_id,detail,updated) VALUES(?,?,?,'READY',?,NULL,NULL,?,?)")) {
            s.setString(1, issuer);
            s.setString(2, authorityDomain);
            s.setString(3, request.outcomeId());
            s.setString(4, json(request));
            s.setString(5, detail);
            s.setString(6, now.toString());
            if (s.executeUpdate() != 1) return false;
        }
        try (var s = c.prepareStatement("UPDATE aid_attempts SET status='ADMITTED',updated=?,detail=? WHERE issuer=? AND transaction_id=? AND status='READY'")) {
            s.setString(1, now.toString());
            s.setString(2, detail);
            s.setString(3, issuer);
            s.setString(4, request.outcomeId());
            if (s.executeUpdate() != 1) throw new IllegalStateException("Aid admission was lost");
        }
        return true;
    }

    public static void finish(Connection c, String issuer, String authorityDomain,
                               String transactionId, Status status, AidReceipt receipt,
                               String eventId, String detail, Instant now) throws SQLException {
        if (status == Status.COMMITTED && receipt == null) throw new IllegalArgumentException("Committed aid requires a native receipt");
        try (var s = c.prepareStatement("UPDATE aid_attempts SET status=?,receipt_json=?,event_id=?,detail=?,updated=? WHERE issuer=? AND authority_domain=? AND transaction_id=? AND status IN ('ADMITTED','UNKNOWN','READY')")) {
            s.setString(1, status.name());
            s.setString(2, receipt == null ? null : json(receipt));
            s.setString(3, eventId);
            s.setString(4, detail == null ? "" : detail);
            s.setString(5, now.toString());
            s.setString(6, issuer);
            s.setString(7, authorityDomain);
            s.setString(8, transactionId);
            if (s.executeUpdate() != 1) throw new IllegalStateException("Aid attempt is no longer admissible: " + transactionId);
        }
    }

    /** Used only inside the same RuleEngine transaction as history/inbox writes. */
    static void commit(Connection c, AidRequest request, AidReceipt receipt,
                       String issuer, String authorityDomain, String eventId,
                       Instant now) throws SQLException {
        Optional<Row> row = find(c, issuer, request.outcomeId());
        if (row.isEmpty()) throw new IllegalStateException("Aid intent is missing: " + request.outcomeId());
        Row current = row.get();
        if (!current.authorityDomain().equals(authorityDomain) || !json(current.request()).equals(json(request)))
            throw new IllegalStateException("Aid intent changed while awaiting receipt: " + request.outcomeId());
        if (current.status() == Status.COMMITTED) {
            if (!Objects.equals(current.eventId(), eventId)) throw new IllegalStateException("Aid event identity changed");
            return;
        }
        if (current.status() != Status.ADMITTED && current.status() != Status.UNKNOWN && current.status() != Status.READY)
            throw new IllegalStateException("Aid intent is not committable: " + current.status());
        finish(c, issuer, authorityDomain, request.outcomeId(), Status.COMMITTED, receipt, eventId,
                "Native receipt matched; contribution and Chronicle event committed atomically", now);
    }

    private static Row read(java.sql.ResultSet r) throws SQLException {
        String receipt = r.getString(6);
        return new Row(r.getString(1), r.getString(2), r.getString(3), Status.valueOf(r.getString(4)),
                decode(r.getString(5), AidRequest.class), receipt == null ? null : decode(receipt, AidReceipt.class),
                r.getString(7), r.getString(8), Instant.parse(r.getString(9)));
    }
}
