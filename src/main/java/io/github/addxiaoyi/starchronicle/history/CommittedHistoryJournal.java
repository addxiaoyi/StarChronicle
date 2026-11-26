package io.github.addxiaoyi.starchronicle.history;

import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import io.github.addxiaoyi.starchronicle.api.trade.TradeHistoryReceipt;
import io.github.addxiaoyi.starchronicle.api.resource.ResourceHistoryReceipt;
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
    public record Entry(long sequence, WorldEvent event, TradeHistoryReceipt tradeReceipt, ResourceHistoryReceipt resourceReceipt) {
        public Entry(long sequence, WorldEvent event, TradeHistoryReceipt tradeReceipt) { this(sequence, event, tradeReceipt, null); }
        public Entry(long sequence, WorldEvent event) { this(sequence, event, null); }
        public Entry {
            if (sequence < 1) throw new IllegalArgumentException("Nonpositive history sequence");
            Objects.requireNonNull(event);
            if (resourceReceipt != null && (tradeReceipt != null || resourceReceipt.sequence() != sequence
                    || !ResourceHistoryProjection.event(resourceReceipt).equals(event)))
                throw new IllegalArgumentException("Resource receipt does not match its history event");
            if (tradeReceipt != null && (tradeReceipt.sequence() != sequence || !TradeHistoryProjection.event(tradeReceipt).equals(event)))
                throw new IllegalArgumentException("Trade receipt does not match its history event");
        }
    }
    public record Pending(WorldEvent event, boolean activate, TradeHistoryReceipt tradeReceipt, ResourceHistoryReceipt resourceReceipt) {
        public Pending(WorldEvent event, boolean activate, TradeHistoryReceipt tradeReceipt) { this(event, activate, tradeReceipt, null); }
        public Pending(WorldEvent event, boolean activate) { this(event, activate, null); }
    }
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
                s.execute("CREATE TABLE IF NOT EXISTS committed_trade_ingress(event_id TEXT PRIMARY KEY REFERENCES committed_history_ingress(id),json TEXT NOT NULL)");
                s.execute("CREATE TABLE IF NOT EXISTS committed_resource_ingress(event_id TEXT PRIMARY KEY REFERENCES committed_history_ingress(id),json TEXT NOT NULL)");
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
        stage(namespace,domain,after,page,page.size()<PAGE_SIZE);
    }
    /** A source page may include a verified anchor outside these new entries. Its original
     * fullness decides whether initial catch-up is complete, not the count after removing it. */
    public void stage(String namespace, UUID domain, long after, List<Entry> page, boolean atHead) {
        namespace(namespace); Objects.requireNonNull(domain);
        List<Entry> entries = List.copyOf(page);
        if (after < 0 || entries.size() > PAGE_SIZE) throw new IllegalArgumentException("Invalid history page");
        long sequence = after;
        Set<String> ids = new HashSet<>();
        for (Entry entry : entries) {
            if (entry.sequence() != Math.addExact(sequence, 1)) throw new IllegalArgumentException("History sequence gap or reordering");
            if (!ids.add(entry.event().id())) throw new IllegalArgumentException("Repeated history operation ID");
            sequence = entry.sequence();
            if (ResourceHistoryProjection.NAMESPACE.equals(namespace) && entry.resourceReceipt() == null)
                throw new IllegalArgumentException("Resource history requires its complete receipt");
            if (entry.resourceReceipt() != null && (!ResourceHistoryProjection.NAMESPACE.equals(namespace)
                    || !entry.resourceReceipt().authorityDomain().equals(domain)))
                throw new IllegalArgumentException("Resource receipt authority or namespace mismatch");
            if (TradeHistoryProjection.NAMESPACE.equals(namespace) && entry.tradeReceipt() == null)
                throw new IllegalArgumentException("Trade history requires its complete receipt");
            if (entry.tradeReceipt() != null && (!TradeHistoryProjection.NAMESPACE.equals(namespace)
                    || !entry.tradeReceipt().authorityDomain().equals(domain)))
                throw new IllegalArgumentException("Trade receipt authority or namespace mismatch");
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
                s.setBoolean(4, activate || atHead); s.executeUpdate();
            }
            for (Entry entry : entries) try (var s = c.prepareStatement("INSERT INTO committed_history_ingress(id,namespace,sequence,status,activate,json) VALUES(?,?,?,'PENDING',?,?)")) {
                s.setString(1, entry.event().id()); s.setString(2, namespace); s.setLong(3, entry.sequence()); s.setBoolean(4, activate); s.setString(5, json(entry.event())); s.executeUpdate();
                if (entry.resourceReceipt() != null) try (var payload = c.prepareStatement("INSERT INTO committed_resource_ingress(event_id,json) VALUES(?,?)")) {
                    payload.setString(1, entry.event().id()); payload.setString(2, json(entry.resourceReceipt())); payload.executeUpdate();
                }
                if (entry.tradeReceipt() != null) try (var payload = c.prepareStatement("INSERT INTO committed_trade_ingress(event_id,json) VALUES(?,?)")) {
                    payload.setString(1, entry.event().id()); payload.setString(2, json(entry.tradeReceipt())); payload.executeUpdate();
                }
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
        return pending(null,limit);
    }
    public List<Pending> pending(String namespace,int limit) {
        if(namespace!=null)namespace(namespace);
        if (limit < 1 || limit > PAGE_SIZE) throw new IllegalArgumentException("History batch must be 1..128");
        return store.transaction(c -> {
            List<Pending> result = new ArrayList<>();
            try (var s = c.prepareStatement("SELECT h.json,h.activate,t.json,r.json FROM committed_history_ingress h LEFT JOIN committed_trade_ingress t ON t.event_id=h.id LEFT JOIN committed_resource_ingress r ON r.event_id=h.id WHERE h.status='PENDING'"+(namespace==null?"":" AND h.namespace=?")+" ORDER BY ROW_NUMBER() OVER(PARTITION BY h.namespace ORDER BY h.sequence),h.namespace LIMIT ?")) {
                if(namespace!=null)s.setString(1,namespace);
                s.setInt(namespace==null?1:2, limit);
                try (var r = s.executeQuery()) { while (r.next()) result.add(new Pending(decode(r.getString(1), WorldEvent.class), r.getBoolean(2), r.getString(3)==null?null:decode(r.getString(3),TradeHistoryReceipt.class),r.getString(4)==null?null:decode(r.getString(4),ResourceHistoryReceipt.class))); }
            }
            return List.copyOf(result);
        });
    }
    public boolean acknowledge(String eventId) {
        return store.transaction(c -> {
            try (var s = c.prepareStatement("UPDATE committed_history_ingress SET status='ACKED' WHERE id=? AND status='PENDING' AND EXISTS(SELECT 1 FROM events WHERE events.id=committed_history_ingress.id) "
                    + "AND (namespace<>'starcore.trade' OR EXISTS(SELECT 1 FROM trade_history_receipts t JOIN committed_trade_ingress i ON i.event_id=t.event_id WHERE t.event_id=committed_history_ingress.id AND t.json=i.json)) "
                    + "AND (namespace<>'starcore.resources' OR EXISTS(SELECT 1 FROM resource_history_receipts r JOIN committed_resource_ingress i ON i.event_id=r.event_id WHERE r.event_id=committed_history_ingress.id AND r.json=i.json))")) {
                s.setString(1, eventId); return s.executeUpdate() == 1;
            }
        });
    }
    /** Re-read the last receipt even at an idle head: a rolled-back source must not look empty. */
    public void verifyAnchor(String namespace,Entry anchor) {
        namespace(namespace);Objects.requireNonNull(anchor);
        store.transaction(c->{
            try(var s=c.prepareStatement("SELECT h.json,t.json,r.json FROM committed_history_ingress h LEFT JOIN committed_trade_ingress t ON t.event_id=h.id LEFT JOIN committed_resource_ingress r ON r.event_id=h.id WHERE h.namespace=? AND h.sequence=?")) {
                s.setString(1,namespace);s.setLong(2,anchor.sequence());
                try(var rows=s.executeQuery()) {
                    if(!rows.next()||!decode(rows.getString(1),WorldEvent.class).equals(anchor.event())
                            ||!Objects.equals(rows.getString(2)==null?null:decode(rows.getString(2),TradeHistoryReceipt.class),anchor.tradeReceipt())
                            ||!Objects.equals(rows.getString(3)==null?null:decode(rows.getString(3),ResourceHistoryReceipt.class),anchor.resourceReceipt()))
                        throw new IllegalStateException("Committed source changed its acknowledged receipt");
                }
            }
            return null;
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
