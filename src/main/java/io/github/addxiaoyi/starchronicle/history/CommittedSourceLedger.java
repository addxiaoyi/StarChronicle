package io.github.addxiaoyi.starchronicle.history;

import io.github.addxiaoyi.starchronicle.core.RuleEngine;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.sql.*;
import java.util.*;

/** The cursor and each receipt must commit in the same transaction as their local effects. */
public final class CommittedSourceLedger {
    private CommittedSourceLedger() {}
    public record Cursor(String domain,long sequence) {}
    public static void createSchema(Connection c)throws SQLException {
        try(var s=c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS committed_source_cursors(source TEXT PRIMARY KEY,domain TEXT NOT NULL,sequence INTEGER NOT NULL CHECK(sequence>=0))");
            s.execute("CREATE TABLE IF NOT EXISTS committed_source_receipts(source TEXT NOT NULL,domain TEXT NOT NULL,sequence INTEGER NOT NULL,operation_id TEXT NOT NULL,hash TEXT NOT NULL,json TEXT NOT NULL,PRIMARY KEY(source,domain,sequence),UNIQUE(source,domain,operation_id))");
        }
    }
    public static Optional<Cursor> cursor(Connection c,String source)throws SQLException {
        try(var s=c.prepareStatement("SELECT domain,sequence FROM committed_source_cursors WHERE source=?")) {
            s.setString(1,source);try(var r=s.executeQuery()){return r.next()?Optional.of(new Cursor(r.getString(1),r.getLong(2))):Optional.empty();}
        }
    }
    public static long begin(Connection c,CommittedSourcePage page)throws SQLException {
        if(c.getAutoCommit())throw new IllegalStateException("Committed source requires enclosing transaction");
        var old=cursor(c,page.source());
        if(old.isPresent()&&!old.get().domain().equals(page.domain()))throw new IllegalStateException("Source storage domain changed; restore matching native and Chronicle backups");
        long head=old.map(Cursor::sequence).orElse(0L);
        if(page.after()>head)throw new IllegalStateException("Source page skipped uncommitted receipts");
        if(old.isEmpty())try(var s=c.prepareStatement("INSERT INTO committed_source_cursors(source,domain,sequence) VALUES(?,?,0)")) {
            s.setString(1,page.source());s.setString(2,page.domain());s.executeUpdate();
        }
        return head;
    }
    public static boolean record(Connection c,CommittedSourcePage page,CommittedSourcePage.Entry entry,long head)throws SQLException {
        var e=entry.event();
        // Sets have no stable iteration order across JVMs; canonicalize before comparing receipts.
        var canonical=new TreeMap<String,Object>();
        canonical.put("sequence",entry.sequence());canonical.put("operation",entry.operationId());canonical.put("baseline",entry.baseline());
        canonical.put("event",List.of(e.id(),e.type(),e.occurredAt().toString(),new TreeSet<>(e.participants()),new TreeSet<>(e.nations()),new TreeMap<>(e.facts()),e.source(),e.evidence()));
        canonical.put("trade",entry.trade());
        String hash=RuleEngine.stable(SqliteStore.json(canonical));
        if(entry.sequence()<=head) {
            try(var s=c.prepareStatement("SELECT hash FROM committed_source_receipts WHERE source=? AND domain=? AND sequence=?")) {
                s.setString(1,page.source());s.setString(2,page.domain());s.setLong(3,entry.sequence());
                try(var r=s.executeQuery()) {if(!r.next()||!hash.equals(r.getString(1)))throw new IllegalStateException("Native receipt changed or local receipt is missing");}
            }
            return false;
        }
        if(entry.sequence()!=Math.addExact(head,1))throw new IllegalStateException("Native receipt sequence has a gap");
        try(var s=c.prepareStatement("INSERT INTO committed_source_receipts(source,domain,sequence,operation_id,hash,json) VALUES(?,?,?,?,?,?)")) {
            s.setString(1,page.source());s.setString(2,page.domain());s.setLong(3,entry.sequence());s.setString(4,entry.operationId());s.setString(5,hash);s.setString(6,SqliteStore.json(entry));s.executeUpdate();
        }
        try(var s=c.prepareStatement("UPDATE committed_source_cursors SET sequence=? WHERE source=? AND domain=? AND sequence=?")) {
            s.setLong(1,entry.sequence());s.setString(2,page.source());s.setString(3,page.domain());s.setLong(4,head);
            if(s.executeUpdate()!=1)throw new IllegalStateException("Committed source cursor changed concurrently");
        }
        return true;
    }
}
