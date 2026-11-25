package io.github.addxiaoyi.starchronicle.history;

import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.util.*;
import static io.github.addxiaoyi.starchronicle.persistence.SqliteStore.*;

/** Source cursor and immutable local inbox commit together; acknowledgement follows engine commit. */
public final class CommittedHistoryJournal {
    public record Cursor(String source,UUID domain,long sequence) {}
    public record Entry(long sequence,WorldEvent event) {
        public Entry {if(sequence<1)throw new IllegalArgumentException("Positive source sequence required");Objects.requireNonNull(event);}
    }
    private final SqliteStore store;
    private final Runnable beforeCommit;
    public CommittedHistoryJournal(SqliteStore store){this(store,()->{});}
    CommittedHistoryJournal(SqliteStore store,Runnable beforeCommit){
        this.store=Objects.requireNonNull(store);this.beforeCommit=Objects.requireNonNull(beforeCommit);
        store.transaction(c->{try(var s=c.createStatement()){
            s.execute("CREATE TABLE IF NOT EXISTS committed_history_sources(source TEXT PRIMARY KEY,authority_domain TEXT NOT NULL,sequence INTEGER NOT NULL CHECK(sequence>=0))");
            s.execute("CREATE TABLE IF NOT EXISTS committed_history_ingress(source TEXT NOT NULL REFERENCES committed_history_sources(source),sequence INTEGER NOT NULL,event_id TEXT NOT NULL UNIQUE,status TEXT NOT NULL CHECK(status IN ('PENDING','ACKED')),json TEXT NOT NULL,PRIMARY KEY(source,sequence))");
            s.execute("CREATE INDEX IF NOT EXISTS committed_history_pending ON committed_history_ingress(status,source,sequence)");
        }return null;});
    }
    public Optional<Cursor> cursor(String source){
        requireSource(source);
        return store.transaction(c->{try(var s=c.prepareStatement("SELECT authority_domain,sequence FROM committed_history_sources WHERE source=?")){
            s.setString(1,source);try(var r=s.executeQuery()){return r.next()?Optional.of(new Cursor(source,UUID.fromString(r.getString(1)),r.getLong(2))):Optional.empty();}
        }});
    }
    public long accept(String source,UUID domain,long after,List<Entry> entries){
        requireSource(source);Objects.requireNonNull(domain);List<Entry> batch=List.copyOf(entries);
        if(after<0||batch.size()>100)throw new IllegalArgumentException("Invalid source cursor or page size");
        long next=after;
        Set<String> ids=new HashSet<>();
        for(Entry entry:batch){next=Math.addExact(next,1);if(entry.sequence()!=next||!ids.add(entry.event().id()))throw new IllegalArgumentException("Noncontiguous or duplicate source page");}
        long end=next;
        return store.transaction(c->{
            boolean exists;
            try(var s=c.prepareStatement("SELECT authority_domain,sequence FROM committed_history_sources WHERE source=?")){
                s.setString(1,source);try(var r=s.executeQuery()){
                    exists=r.next();
                    if(exists&&(!r.getString(1).equals(domain.toString())||r.getLong(2)!=after))throw new IllegalStateException("Committed history source domain/cursor changed; explicit reconciliation required");
                    if(!exists&&after!=0)throw new IllegalStateException("New source must start at sequence zero");
                }
            }
            if(!exists)try(var s=c.prepareStatement("INSERT INTO committed_history_sources(source,authority_domain,sequence) VALUES(?,?,0)")){
                s.setString(1,source);s.setString(2,domain.toString());s.executeUpdate();
            }
            for(Entry entry:batch)try(var s=c.prepareStatement("INSERT INTO committed_history_ingress(source,sequence,event_id,status,json) VALUES(?,?,?,'PENDING',?)")){
                s.setString(1,source);s.setLong(2,entry.sequence());s.setString(3,entry.event().id());s.setString(4,json(entry.event()));s.executeUpdate();
            }
            try(var s=c.prepareStatement("UPDATE committed_history_sources SET sequence=? WHERE source=?")){s.setLong(1,end);s.setString(2,source);s.executeUpdate();}
            beforeCommit.run();return end;
        });
    }
    public List<WorldEvent> pending(int limit){
        if(limit<1||limit>100)throw new IllegalArgumentException("Pending page must be 1..100");
        return store.transaction(c->{List<WorldEvent> result=new ArrayList<>();try(var s=c.prepareStatement("SELECT json FROM committed_history_ingress WHERE status='PENDING' ORDER BY source,sequence LIMIT ?")){
            s.setInt(1,limit);try(var r=s.executeQuery()){while(r.next())result.add(decode(r.getString(1),WorldEvent.class));}
        }return List.copyOf(result);});
    }
    /** Invoke only after RuleEngine.accept durably committed (a verified duplicate also counts). */
    public boolean acknowledge(String eventId){
        return store.transaction(c->{try(var s=c.prepareStatement("UPDATE committed_history_ingress SET status='ACKED' WHERE event_id=? AND status='PENDING'")){
            s.setString(1,eventId);return s.executeUpdate()==1;
        }});
    }
    private static void requireSource(String source){if(source==null||!source.matches("[a-z0-9][a-z0-9.:-]{0,127}"))throw new IllegalArgumentException("Invalid committed source ID");}
}
