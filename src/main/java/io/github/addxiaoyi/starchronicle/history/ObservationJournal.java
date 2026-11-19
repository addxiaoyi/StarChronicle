package io.github.addxiaoyi.starchronicle.history;

import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static io.github.addxiaoyi.starchronicle.persistence.SqliteStore.*;

/** Transactional ingress journal for authorities without committed event publishers.
 * Baseline advancement and every derived event are committed together. Submit pending events to
 * RuleEngine.accept OUTSIDE this transaction and acknowledge only after that call commits.
 * A crash after accept and before acknowledge deliberately redelivers the same immutable event. */
public final class ObservationJournal {
    private final SqliteStore store;
    private final Clock clock;
    private final Runnable beforeCommit;
    public record Result(boolean accepted,boolean baselineCreated,long sequence,int queued,String reason) {}
    public ObservationJournal(SqliteStore store,Clock clock) { this(store,clock,()->{}); }
    // Test-only crash injection at the actual write transaction boundary; not an exposed API.
    ObservationJournal(SqliteStore store,Clock clock,Runnable beforeCommit) {
        this.store=Objects.requireNonNull(store);this.clock=Objects.requireNonNull(clock);this.beforeCommit=Objects.requireNonNull(beforeCommit);
        store.transaction(c->{try(var s=c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS observation_baselines(namespace TEXT PRIMARY KEY,kind TEXT NOT NULL,source TEXT NOT NULL,sequence INTEGER NOT NULL,observed_at TEXT NOT NULL,json TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS observation_ingress(id TEXT PRIMARY KEY,namespace TEXT NOT NULL,sequence INTEGER NOT NULL,status TEXT NOT NULL CHECK(status IN ('PENDING','ACKED')),json TEXT NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS observation_pending ON observation_ingress(status,namespace,sequence,id)");
        }return null;});
    }

    public Result observe(ObservationSnapshot snapshot) {
        Objects.requireNonNull(snapshot);
        if(!snapshot.complete())return new Result(false,false,-1,0,"Incomplete authority read: "+snapshot.evidence());
        if(snapshot.entries().isEmpty()&&!snapshot.allowEmpty())return new Result(false,false,-1,0,"Empty snapshot lacks an explicit completeness assertion");
        Instant now=clock.instant().truncatedTo(ChronoUnit.MILLIS);
        return store.transaction(c->{
            ObservationSnapshot old=null;long sequence=0;Instant previousTime=null;
            try(var s=c.prepareStatement("SELECT kind,source,sequence,observed_at,json FROM observation_baselines WHERE namespace=?")) {
                s.setString(1,snapshot.namespace());try(var r=s.executeQuery()){if(r.next()) {
                    if(!r.getString(1).equals(snapshot.kind().name())||!r.getString(2).equals(snapshot.source()))throw new IllegalArgumentException("Observation namespace authority changed: "+snapshot.namespace());
                    sequence=r.getLong(3);previousTime=Instant.parse(r.getString(4));old=decode(r.getString(5),ObservationSnapshot.class);
                }}
            }
            if(old!=null&&old.entries().equals(snapshot.entries()))return new Result(true,false,sequence,0,"Snapshot unchanged");
            boolean first=old==null;
            if(!first)sequence=Math.addExact(sequence,1);
            List<WorldEvent> events=first?List.of():changes(old,snapshot,sequence,previousTime,now);
            for(WorldEvent event:events)try(var s=c.prepareStatement("INSERT INTO observation_ingress(id,namespace,sequence,status,json) VALUES(?,?,?,'PENDING',?)")) {
                s.setString(1,event.id());s.setString(2,snapshot.namespace());s.setLong(3,sequence);s.setString(4,json(event));s.executeUpdate();
            }
            try(var s=c.prepareStatement("INSERT INTO observation_baselines(namespace,kind,source,sequence,observed_at,json) VALUES(?,?,?,?,?,?) ON CONFLICT(namespace) DO UPDATE SET sequence=excluded.sequence,observed_at=excluded.observed_at,json=excluded.json")) {
                s.setString(1,snapshot.namespace());s.setString(2,snapshot.kind().name());s.setString(3,snapshot.source());s.setLong(4,sequence);s.setString(5,now.toString());s.setString(6,json(snapshot));s.executeUpdate();
            }
            beforeCommit.run();
            return new Result(true,first,sequence,events.size(),first?"Initial authority baseline; no historical changes invented":"Changes and new baseline committed atomically");
        });
    }

    private static List<WorldEvent> changes(ObservationSnapshot before,ObservationSnapshot after,long sequence,Instant previousTime,Instant now) {
        Set<String> keys=new TreeSet<>();keys.addAll(before.entries().keySet());keys.addAll(after.entries().keySet());
        List<WorldEvent> result=new ArrayList<>();
        for(String key:keys) {
            var old=before.entries().get(key);var current=after.entries().get(key);
            if(Objects.equals(old,current))continue;
            String type=null;
            switch(after.kind()) {
                case NATIONS -> {if(old!=null&&current==null)type="NATION_DESTROYED";}
                case CAPITALS -> {if(old!=null&&current!=null&&!old.value().equals(current.value()))type="CAPITAL_MOVED";}
                case TECHNOLOGIES -> {if(old==null&&current!=null)type="TECHNOLOGY_BREAKTHROUGH";}
            }
            if(type==null)continue;
            var entity=current==null?old:current;
            if(old!=null&&current!=null&&!old.nationId().equals(current.nationId()))throw new IllegalArgumentException("Snapshot property changed nation identity: "+key);
            String oldValue=old==null?"<absent>":old.value(), newValue=current==null?"<absent>":current.value();
            Map<String,String> facts=new HashMap<>(entity.facts());
            facts.put("time_basis","OBSERVED");facts.put("observation.namespace",after.namespace());facts.put("observation.sequence",Long.toString(sequence));facts.put("observation.key",key);
            facts.put("before",oldValue);facts.put("after",newValue);facts.put("nation.id",entity.nationId());
            facts.put("title",switch(type){case "NATION_DESTROYED"->"国家消失（权威快照观察）";case "CAPITAL_MOVED"->"首都变更（权威快照观察）";default->"科技解锁（权威快照观察）";});
            String id="observation:"+hash(after.namespace()).substring(0,16)+":"+sequence+":"+hash(type,key,oldValue,newValue);
            String evidence="source="+after.source()+"; complete authority snapshot; namespace="+after.namespace()+"; sequence="+sequence+"; key="+key+"; before="+oldValue+"; after="+newValue+"; observationInterval=["+previousTime+","+now+"]; exact mutation time and actor unknown; "+after.evidence();
            result.add(new WorldEvent(id,type,now,Set.of(),Set.of(entity.nationId()),facts,after.source(),evidence));
        }
        return List.copyOf(result);
    }

    /** Detached immutable records; fetching never marks them sent or advances a delivery cursor. */
    public List<WorldEvent> pending(int limit) {
        if(limit<1||limit>1000)throw new IllegalArgumentException("Ingress batch must be 1..1000");
        return store.transaction(c->{List<WorldEvent> result=new ArrayList<>();try(var s=c.prepareStatement("SELECT json FROM observation_ingress WHERE status='PENDING' ORDER BY namespace,sequence,id LIMIT ?")) {
            s.setInt(1,limit);try(var r=s.executeQuery()){while(r.next())result.add(decode(r.getString(1),WorldEvent.class));}
        }return List.copyOf(result);});
    }
    /** Call only after the authoritative consumer's successful durable commit (duplicates count). */
    public boolean acknowledge(String eventId) {
        return store.transaction(c->{try(var s=c.prepareStatement("UPDATE observation_ingress SET status='ACKED' WHERE id=? AND status='PENDING'")) {s.setString(1,eventId);return s.executeUpdate()==1;}});
    }
    public long pendingCount() {return store.transaction(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM observation_ingress WHERE status='PENDING'")){if(r.next())return r.getLong(1);throw new IllegalStateException("Missing ingress count");}});}
    private static String hash(String...values) {
        try {MessageDigest digest=MessageDigest.getInstance("SHA-256");for(String value:values){byte[] b=value.getBytes(StandardCharsets.UTF_8);digest.update(Integer.toString(b.length).getBytes(StandardCharsets.US_ASCII));digest.update((byte)':');digest.update(b);}return HexFormat.of().formatHex(digest.digest());}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
