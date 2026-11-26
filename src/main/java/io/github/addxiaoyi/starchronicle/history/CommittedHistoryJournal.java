package io.github.addxiaoyi.starchronicle.history;

import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import static io.github.addxiaoyi.starchronicle.persistence.SqliteStore.*;

/** Durable ingress for an authority's contiguous commit log. Cursor and page commit together.
 * Deliver outside this transaction, then acknowledge only after RuleEngine.accept commits. */
public final class CommittedHistoryJournal {
    public static final int PAGE_SIZE = 128;
    public record Cursor(UUID authorityDomain, long sequence, boolean live) {
        public Cursor {
            Objects.requireNonNull(authorityDomain);
            if (sequence < 0) throw new IllegalArgumentException("Negative history cursor");
        }
    }
    public record Entry(long sequence, WorldEvent event) {
        public Entry {
            if (sequence < 1) throw new IllegalArgumentException("Nonpositive history sequence");
            Objects.requireNonNull(event);
        }
    }
    public record Pending(WorldEvent event, boolean activate) {}
    private final SqliteStore store;
    private final Runnable beforeCommit;

    public CommittedHistoryJournal(SqliteStore store) { this(store, () -> {}); }
    CommittedHistoryJournal(SqliteStore store, Runnable beforeCommit) {
        this.store = Objects.requireNonNull(store);
        this.beforeCommit = Objects.requireNonNull(beforeCommit);
        store.transaction(c -> {
            try (var s = c.createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS committed_history_sources(namespace TEXT PRIMARY KEY,authority_domain TEXT NOT NULL,sequence INTEGER NOT NULL CHECK(sequence>=0),live INTEGER NOT NULL CHECK(live IN (0,1)))");
                s.execute("CREATE TABLE IF NOT EXISTS committed_history_ingress(id TEXT PRIMARY KEY,namespace TEXT NOT NULL REFERENCES committed_history_sources(namespace),sequence INTEGER NOT NULL CHECK(sequence>0),status TEXT NOT NULL CHECK(status IN ('PENDING','ACKED')),activate INTEGER NOT NULL CHECK(activate IN (0,1)),json TEXT NOT NULL,UNIQUE(namespace,sequence))");
                s.execute("CREATE INDEX IF NOT EXISTS committed_history_pending ON committed_history_ingress(status,namespace,sequence)");
            }
            return null;
        });
    }

    public Optional<Cursor> cursor(String namespace) {
        namespace(namespace);
        return store.transaction(c -> cursor(c, namespace));
    }

    /** Only a successfully read and validated source page may pin an authority, including an
     * empty initial page. A short page must mean the source's head at the time of the read.
     * Initial catch-up is archive-only; later commits activate stories, including after restart.
     * Never silently reset a cursor when a different database is installed. */
    public void stage(String namespace, UUID domain, long after, List<Entry> page) {
        namespace(namespace); Objects.requireNonNull(domain);
        List<Entry> entries = List.copyOf(page);
        if (after < 0 || entries.size() > PAGE_SIZE) throw new IllegalArgumentException("Invalid history page");
        long sequence = after;
        Set<String> ids = new HashSet<>();
        for (Entry entry : entries) {
            if (entry.sequence() != Math.addExact(sequence, 1)) throw new IllegalArgumentException("History sequence gap or reordering");
            if (!ids.add(entry.event().id())) throw new IllegalArgumentException("Repeated history operation ID");
            sequence = entry.sequence();
        }
        long last = sequence;
        store.transaction(c -> {
            Optional<Cursor> old = cursor(c, namespace);
            if (old.isPresent() && (!old.get().authorityDomain().equals(domain) || old.get().sequence() != after))
                throw new IllegalStateException("History authority or cursor changed: " + namespace);
            if (old.isEmpty() && after != 0) throw new IllegalStateException("First history page must start at zero");
            if (pending(c, namespace)) throw new IllegalStateException("Deliver pending history before reading another page");
            boolean activate = old.map(Cursor::live).orElse(false);
            try (var s = c.prepareStatement("INSERT INTO committed_history_sources(namespace,authority_domain,sequence,live) VALUES(?,?,?,?) ON CONFLICT(namespace) DO UPDATE SET sequence=excluded.sequence,live=excluded.live")) {
                s.setString(1, namespace); s.setString(2, domain.toString()); s.setLong(3, last);
                s.setBoolean(4, activate || entries.size() < PAGE_SIZE); s.executeUpdate();
            }
            for (Entry entry : entries) try (var s = c.prepareStatement("INSERT INTO committed_history_ingress(id,namespace,sequence,status,activate,json) VALUES(?,?,?,'PENDING',?,?)")) {
                s.setString(1, entry.event().id()); s.setString(2, namespace); s.setLong(3, entry.sequence()); s.setBoolean(4, activate); s.setString(5, json(entry.event())); s.executeUpdate();
            }
            beforeCommit.run();
            return null;
        });
    }

    public boolean hasPending(String namespace) {
        namespace(namespace);
        return store.transaction(c -> pending(c, namespace));
    }
    public List<Pending> pending(int limit) {
        if (limit < 1 || limit > PAGE_SIZE) throw new IllegalArgumentException("History batch must be 1..128");
        return store.transaction(c -> {
            List<Pending> result = new ArrayList<>();
            try (var s = c.prepareStatement("SELECT json,activate FROM committed_history_ingress WHERE status='PENDING' ORDER BY namespace,sequence LIMIT ?")) {
                s.setInt(1, limit);
                try (var r = s.executeQuery()) { while (r.next()) result.add(new Pending(decode(r.getString(1), WorldEvent.class), r.getBoolean(2))); }
            }
            return List.copyOf(result);
        });
    }
    public boolean acknowledge(String eventId) {
        return store.transaction(c -> {
            try (var s = c.prepareStatement("UPDATE committed_history_ingress SET status='ACKED' WHERE id=? AND status='PENDING' AND EXISTS(SELECT 1 FROM events WHERE events.id=committed_history_ingress.id)")) {
                s.setString(1, eventId); return s.executeUpdate() == 1;
            }
        });
    }
    private static Optional<Cursor> cursor(Connection c, String namespace) throws SQLException {
        try (var s = c.prepareStatement("SELECT authority_domain,sequence,live FROM committed_history_sources WHERE namespace=?")) {
            s.setString(1, namespace);
            try (var r = s.executeQuery()) { return r.next() ? Optional.of(new Cursor(UUID.fromString(r.getString(1)), r.getLong(2), r.getBoolean(3))) : Optional.empty(); }
        }
    }
    private static boolean pending(Connection c, String namespace) throws SQLException {
        try (var s = c.prepareStatement("SELECT 1 FROM committed_history_ingress WHERE namespace=? AND status='PENDING' LIMIT 1")) {
            s.setString(1, namespace); try (var r = s.executeQuery()) { return r.next(); }
        }
    }
    private static void namespace(String value) {
        if (value == null || !value.matches("[a-zA-Z0-9_.:-]{1,128}")) throw new IllegalArgumentException("Invalid history namespace");
    }
}
