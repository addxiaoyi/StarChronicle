package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.contribution.ContributionEntry;
import io.github.addxiaoyi.starchronicle.api.contribution.ContributionRequest;
import io.github.addxiaoyi.starchronicle.api.contribution.ContributionType;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

import static io.github.addxiaoyi.starchronicle.persistence.SqliteStore.decode;
import static io.github.addxiaoyi.starchronicle.persistence.SqliteStore.json;

/**
 * Append-only evidence ledger for resource aid, trade and civilization work.
 * It records a committed source operation; it never performs the external work
 * and never grants a reward merely because a row was requested.
 */
public final class ContributionLedger {
    public record Result(boolean fresh, ContributionEntry entry) {
        public Result { Objects.requireNonNull(entry, "entry"); }
    }

    private final SqliteStore store;
    private final Clock clock;

    public ContributionLedger(SqliteStore store, Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.clock = Objects.requireNonNull(clock);
        store.transaction(c -> { createSchema(c); return null; });
    }

    public Result record(ContributionRequest request) {
        Objects.requireNonNull(request);
        return store.transaction(c -> record(c, request, clock.instant()));
    }

    public Optional<ContributionEntry> find(String operationId, ContributionType type, String subjectId) {
        ContributionRequest.token(operationId, "operationId", 256);
        ContributionRequest.token(subjectId, "subjectId", 256);
        Objects.requireNonNull(type, "type");
        return store.transaction(c -> find(c, operationId, type, subjectId));
    }

    public BigDecimal total(String subjectId, ContributionType type, String unit) {
        ContributionRequest.token(subjectId, "subjectId", 256);
        ContributionRequest.token(unit, "unit", 32);
        Objects.requireNonNull(type, "type");
        return store.transaction(c -> total(c, subjectId, type, unit));
    }

    public List<ContributionEntry> list(String subjectId, ContributionType type, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Invalid contribution page");
        if (subjectId != null) ContributionRequest.token(subjectId, "subjectId", 256);
        return store.transaction(c -> list(c, subjectId, type, page, size));
    }

    public static void createSchema(Connection c) throws SQLException {
        inTransaction(c);
        try (var s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS contributions(" +
                    "id TEXT PRIMARY KEY,operation_id TEXT NOT NULL,type TEXT NOT NULL CHECK(type IN ('RESOURCE_AID','TRADE','CIVILIZATION'))," +
                    "subject_id TEXT NOT NULL,source_event_id TEXT NOT NULL REFERENCES events(id),actor_id TEXT NOT NULL," +
                    "nation_id TEXT,amount TEXT NOT NULL,unit TEXT NOT NULL,committed_at TEXT NOT NULL,evidence TEXT NOT NULL," +
                    "request_json TEXT NOT NULL,recorded_at TEXT NOT NULL,UNIQUE(operation_id,type,subject_id))");
            s.execute("CREATE INDEX IF NOT EXISTS contributions_subject ON contributions(subject_id,type,unit,committed_at,id)");
            s.execute("CREATE INDEX IF NOT EXISTS contributions_source ON contributions(source_event_id,id)");
            s.execute("CREATE TABLE IF NOT EXISTS contribution_receipts(receipt_key TEXT PRIMARY KEY," +
                    "contribution_id TEXT NOT NULL UNIQUE REFERENCES contributions(id))");
        }
    }

    /** Share one native receipt across adapters without rewriting the first committed evidence row. */
    public static Result recordNative(Connection c, String domain, String nativeReceiptId,
                                      ContributionRequest request, String legacyOperationId, Instant recordedAt) throws SQLException {
        Objects.requireNonNull(domain); Objects.requireNonNull(nativeReceiptId);
        String key = RuleEngine.stable("contribution-receipt", domain, nativeReceiptId,
                request.type().name(), request.unit(), request.subjectId());
        try (var s = c.prepareStatement("SELECT c.id,c.request_json,c.recorded_at FROM contribution_receipts r JOIN contributions c ON c.id=r.contribution_id WHERE r.receipt_key=?")) {
            s.setString(1,key);
            try (var r=s.executeQuery()) {
                if(r.next()) {
                    var old=new ContributionEntry(r.getString(1),decode(r.getString(2),ContributionRequest.class),Instant.parse(r.getString(3)));
                    requireSameNativeValue(old.request(),request);
                    return new Result(false,old);
                }
            }
        }
        var legacy=legacyOperationId==null?Optional.<ContributionEntry>empty():find(c,legacyOperationId,request.type(),request.subjectId());
        Result result;
        if(legacy.isPresent()) {requireSameNativeValue(legacy.get().request(),request);result=new Result(false,legacy.get());}
        else result=record(c,request,recordedAt);
        try(var s=c.prepareStatement("INSERT INTO contribution_receipts(receipt_key,contribution_id) VALUES(?,?)")) {
            s.setString(1,key);s.setString(2,result.entry().id());s.executeUpdate();
        }
        return result;
    }

    private static void requireSameNativeValue(ContributionRequest old,ContributionRequest current) {
        if(old.type()!=current.type() || !old.subjectId().equals(current.subjectId())
                || !old.actorId().equals(current.actorId()) || !Objects.equals(old.nationId(),current.nationId())
                || old.amount().compareTo(current.amount())!=0 || !old.unit().equals(current.unit())
                || !old.committedAt().equals(current.committedAt()))
            throw new IllegalStateException("Native contribution receipt conflicts with its recorded subject or value");
    }

    /** Must be called inside the same transaction as the source event commit. */
    public static Result record(Connection c, ContributionRequest request, Instant recordedAt) throws SQLException {
        inTransaction(c);
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(recordedAt, "recordedAt");
        if (recordedAt.isBefore(request.committedAt())) throw new IllegalArgumentException("Contribution recordedAt predates committedAt");
        if (!sourceExists(c, request.sourceEventId())) throw new IllegalArgumentException("Contribution source event is not committed: " + request.sourceEventId());
        Optional<ContributionEntry> old = find(c, request.operationId(), request.type(), request.subjectId());
        if (old.isPresent()) {
            if (!old.get().request().identity().equals(request.identity())) throw new IllegalStateException("Contribution operation is already bound to different evidence");
            return new Result(false, old.get());
        }
        String id = stableId(request.operationId(), request.type(), request.subjectId());
        try (var s = c.prepareStatement("INSERT INTO contributions(id,operation_id,type,subject_id,source_event_id,actor_id,nation_id,amount,unit,committed_at,evidence,request_json,recorded_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            int n = 0;
            s.setString(++n, id); s.setString(++n, request.operationId()); s.setString(++n, request.type().name());
            s.setString(++n, request.subjectId()); s.setString(++n, request.sourceEventId()); s.setString(++n, request.actorId().toString());
            s.setString(++n, request.nationId()); s.setString(++n, request.amount().toPlainString()); s.setString(++n, request.unit());
            s.setString(++n, request.committedAt().toString()); s.setString(++n, request.evidence()); s.setString(++n, json(request)); s.setString(++n, recordedAt.toString());
            if (s.executeUpdate() != 1) throw new IllegalStateException("Contribution insert was lost");
        }
        return new Result(true, find(c, request.operationId(), request.type(), request.subjectId()).orElseThrow());
    }

    public static Optional<ContributionEntry> find(Connection c, String operationId, ContributionType type, String subjectId) throws SQLException {
        inTransaction(c);
        try (var s = c.prepareStatement("SELECT c.id,c.request_json,c.recorded_at,COALESCE(s.status,'COMMITTED') FROM contributions c LEFT JOIN native_trade_contributions t ON t.contribution_id=c.id LEFT JOIN native_trade_states s ON s.source=t.source AND s.domain=t.domain AND s.record_id=t.record_id WHERE c.operation_id=? AND c.type=? AND c.subject_id=?")) {
            s.setString(1, operationId); s.setString(2, type.name()); s.setString(3, subjectId);
            try (var r = s.executeQuery()) {
                if (!r.next()) return Optional.empty();
                return Optional.of(new ContributionEntry(r.getString(1), decode(r.getString(2), ContributionRequest.class), Instant.parse(r.getString(3)),r.getString(4)));
            }
        }
    }

    public static BigDecimal total(Connection c, String subjectId, ContributionType type, String unit) throws SQLException {
        inTransaction(c);
        try (var s = c.prepareStatement("SELECT c.amount FROM contributions c LEFT JOIN native_trade_contributions t ON t.contribution_id=c.id LEFT JOIN native_trade_states s ON s.source=t.source AND s.domain=t.domain AND s.record_id=t.record_id WHERE c.subject_id=? AND c.type=? AND c.unit=? AND (t.contribution_id IS NULL OR s.status='COMPLETED') ORDER BY c.id")) {
            s.setString(1, subjectId); s.setString(2, type.name()); s.setString(3, unit);
            BigDecimal total = BigDecimal.ZERO;
            try (var r = s.executeQuery()) { while (r.next()) total = total.add(new BigDecimal(r.getString(1))); }
            return total.stripTrailingZeros();
        }
    }

    public static List<ContributionEntry> list(Connection c, String subjectId, ContributionType type, int page, int size) throws SQLException {
        inTransaction(c);
        StringBuilder sql = new StringBuilder("SELECT c.id,c.request_json,c.recorded_at,COALESCE(s.status,'COMMITTED') FROM contributions c LEFT JOIN native_trade_contributions t ON t.contribution_id=c.id LEFT JOIN native_trade_states s ON s.source=t.source AND s.domain=t.domain AND s.record_id=t.record_id");
        List<Object> args = new ArrayList<>();
        if (subjectId != null || type != null) {
            sql.append(" WHERE ");
            if (subjectId != null) { sql.append("c.subject_id=?"); args.add(subjectId); }
            if (subjectId != null && type != null) sql.append(" AND ");
            if (type != null) { sql.append("c.type=?"); args.add(type.name()); }
        }
        sql.append(" ORDER BY c.recorded_at DESC,c.id LIMIT ? OFFSET ?"); args.add(size); args.add(Math.multiplyExact(page, size));
        try (var s = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < args.size(); i++) s.setObject(i + 1, args.get(i));
            List<ContributionEntry> result = new ArrayList<>();
            try (var r = s.executeQuery()) { while (r.next()) result.add(new ContributionEntry(r.getString(1), decode(r.getString(2), ContributionRequest.class), Instant.parse(r.getString(3)),r.getString(4))); }
            return List.copyOf(result);
        }
    }

    private static boolean sourceExists(Connection c, String sourceEventId) throws SQLException {
        try (var s = c.prepareStatement("SELECT 1 FROM events WHERE id=?")) { s.setString(1, sourceEventId); try (var r = s.executeQuery()) { return r.next(); } }
    }

    private static String stableId(String operationId, ContributionType type, String subjectId) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest((operationId + "|" + type.name() + "|" + subjectId).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder("chronicle:contribution:"); for (byte b : bytes) out.append(String.format("%02x", b)); return out.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static void inTransaction(Connection c) throws SQLException { if (c.getAutoCommit()) throw new IllegalStateException("Contribution ledger requires an enclosing transaction"); }
}
