package io.github.addxiaoyi.starchronicle.narrative;

import io.github.addxiaoyi.starchronicle.api.NativeScene;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static io.github.addxiaoyi.starchronicle.persistence.SqliteStore.*;

/** Durable recovery metadata for owned native scenes, never a second script interpreter.
 * The adapter claims BEFORE any native mutation, captures native state on the owning entity
 * thread, and commits a checkpoint AFTER a conclusive read-back. An uncertain action is never
 * automatically retried. Upstream saveState(void) is not a durable success acknowledgement. */
public final class NativeSceneJournal {
    public enum Status { READY, RUNNING, UNKNOWN, COMPLETED }
    public record Request(String outcomeId,String instanceId,UUID playerId,String nationId,
                          String scriptId,String scriptFingerprint,String initialNode,String permission) {
        public Request(String outcomeId,String instanceId,UUID playerId,String nationId,String scriptId,String fingerprint){this(outcomeId,instanceId,playerId,nationId,scriptId,fingerprint,"","");}
        public Request(String outcomeId,String instanceId,UUID playerId,String nationId,String scriptId,String fingerprint,String initialNode){this(outcomeId,instanceId,playerId,nationId,scriptId,fingerprint,initialNode,"");}
        public Request {
            id(outcomeId,"outcome ID");id(instanceId,"instance ID");Objects.requireNonNull(playerId);
            if(nationId==null||nationId.length()>256)throw new IllegalArgumentException("Invalid native scene nation");
            id(scriptId,"script ID");if(!scriptId.startsWith("chronicle_"))throw new IllegalArgumentException("Only owned chronicle_ scenes can be journaled");
            if(scriptFingerprint==null||!scriptFingerprint.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Native scene requires its exact SHA-256 content fingerprint");
            initialNode=Objects.requireNonNullElse(initialNode,"");if(!initialNode.isEmpty())id(initialNode,"initial node");
            permission=Objects.requireNonNullElse(permission,"");if(permission.length()>256||permission.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid native scene permission");
        }
    }
    /** Only owned scalar state is eligible for automatic merge back into the actual engine.
     * Never reset foreign native state. A checkpoint view is presentation/evidence only. */
    public record Checkpoint(String nodeId,Map<String,Object> variables,Set<String> flags,
                             Set<String> evidence,NativeScene view) {
        public Checkpoint {
            id(nodeId,"node ID");Objects.requireNonNull(view);
            if(!nodeId.equals(view.nodeId())||view.scriptId()==null||!view.scriptId().startsWith("chronicle_"))throw new IllegalArgumentException("Native checkpoint/view identity mismatch");
            if(view.title()==null||view.title().length()>256||view.text()==null||view.text().length()>16384||view.choices().size()>36)throw new IllegalArgumentException("Native checkpoint view exceeds bounds");
            int choiceIndex=1;for(var choice:view.choices())if(choice.index()!=choiceIndex++||choice.text()==null||choice.text().length()>4096)throw new IllegalArgumentException("Invalid native checkpoint choice projection");
            Map<String,Object> detached=new TreeMap<>();
            if(variables!=null)variables.forEach((key,value)->{
                ownedKey(key);Object scalar;
                if(value instanceof String text){if(text.length()>4096)throw new IllegalArgumentException("Native scalar text exceeds bounds");scalar=text;}
                else if(value instanceof Boolean)scalar=value;
                else if(value instanceof Byte||value instanceof Short||value instanceof Integer||value instanceof Long)scalar=((Number)value).longValue();
                else if(value instanceof Float||value instanceof Double){double number=((Number)value).doubleValue();if(!Double.isFinite(number))throw new IllegalArgumentException("Native scalar must be finite");scalar=number;}
                else throw new IllegalArgumentException("Only detached native scalar variables can be checkpointed");
                detached.put(key,scalar);
            });
            if(detached.size()>128)throw new IllegalArgumentException("Too many owned native scalar variables");variables=Collections.unmodifiableMap(detached);
            flags=ownedSet(flags);evidence=ownedSet(evidence);
        }
    }
    public record Entry(Request request,Status status,long revision,Checkpoint checkpoint,
                        Instant updatedAt,String reason) {}
    /** Revision token is a compare-and-set lease, not an authorization token. */
    public record Attempt(String outcomeId,long revision,Request request,Checkpoint checkpoint) {}
    private final SqliteStore store;
    private final Clock clock;

    public NativeSceneJournal(SqliteStore store,Clock clock) {
        this.store=Objects.requireNonNull(store);this.clock=Objects.requireNonNull(clock);
        store.transaction(connection->{
            List<Entry> abandoned=new ArrayList<>();
            try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT json FROM native_scene_journal WHERE status='RUNNING'")){while(rows.next())abandoned.add(decode(rows.getString(1),Entry.class));}
            for(Entry old:abandoned)save(connection,new Entry(old.request(),Status.UNKNOWN,old.revision(),old.checkpoint(),now(),"Process ended during an admitted native call; reconcile before any replay"));
            return null;
        });
    }

    /** Stable delivery IDs coalesce. Different content under an existing ID is rejected. */
    public Entry prepare(Request request) {
        Objects.requireNonNull(request);
        return store.transaction(connection->{
            Entry existing=find(connection,request.outcomeId());
            if(existing!=null){if(!existing.request().equals(request))throw new IllegalArgumentException("Native scene request ID collision");return existing;}
            Entry ready=new Entry(request,Status.READY,0,null,now(),"Awaiting a verified native start; no native mutation admitted");save(connection,ready);return ready;
        });
    }

    /** Use only when the exact registered script object/fingerprint and safe owned-reentry
     * capability were checked. Busy/offline BEFORE native invocation stays READY via notInvoked.
     * Other running/unknown calls for this player block admission rather than overwrite state. */
    public Optional<Attempt> claim(String outcomeId,long expectedRevision,String currentFingerprint) {
        return store.transaction(connection->{
            Entry current=find(connection,outcomeId);
            if(current==null||current.status()!=Status.READY||current.revision()!=expectedRevision||!current.request().scriptFingerprint().equals(currentFingerprint))return Optional.empty();
            try(var statement=connection.prepareStatement("SELECT 1 FROM native_scene_journal WHERE player=? AND (status IN ('RUNNING','UNKNOWN') OR (id<>? AND status='READY' AND json_extract(json,'$.checkpoint') IS NOT NULL)) LIMIT 1")) {
                statement.setString(1,current.request().playerId().toString());statement.setString(2,outcomeId);try(var rows=statement.executeQuery()){if(rows.next())return Optional.empty();}
            }
            long revision=Math.addExact(current.revision(),1);Entry admitted=new Entry(current.request(),Status.RUNNING,revision,current.checkpoint(),now(),"Native call admitted; outcome not yet checkpointed");save(connection,admitted);
            return Optional.of(new Attempt(outcomeId,revision,current.request(),current.checkpoint()));
        });
    }

    /** Conclusive native read-back after a successful start/action/reattachment. No effects are
     * executed here. The adapter must verify native identity and ownership before calling. */
    public boolean checkpoint(Attempt attempt,Checkpoint checkpoint) {
        Objects.requireNonNull(checkpoint);
        if(!attempt.request().scriptId().equals(checkpoint.view().scriptId()))throw new IllegalArgumentException("Native script differs from admitted request");
        return finish(attempt,Status.READY,checkpoint,"Native state and owned presentation checkpoint committed");
    }
    /** Only after verifying the native session actually completed, not merely view()==empty
     * because of a missing loader, ownership mismatch, disconnect or read failure. */
    public boolean completed(Attempt attempt){return finish(attempt,Status.COMPLETED,attempt.checkpoint(),"Native completion conclusively verified");}
    public boolean unknown(Attempt attempt,String reason){text(reason);return finish(attempt,Status.UNKNOWN,attempt.checkpoint(),reason);}
    /** Caller asserts that the native mutation was NOT invoked. Never use after timeout,
     * reflection invocation failure or an ambiguous native response. */
    public boolean notInvoked(Attempt attempt,String reason){text(reason);return finish(attempt,Status.READY,attempt.checkpoint(),reason);}

    private boolean finish(Attempt attempt,Status status,Checkpoint checkpoint,String reason) {
        Objects.requireNonNull(attempt);Objects.requireNonNull(attempt.request());
        return store.transaction(connection->{
            Entry current=find(connection,attempt.outcomeId());
            if(current==null||current.status()!=Status.RUNNING||current.revision()!=attempt.revision()||!current.request().equals(attempt.request()))return false;
            save(connection,new Entry(current.request(),status,current.revision(),checkpoint,now(),reason));return true;
        });
    }
    public Optional<Entry> find(String outcomeId){return store.transaction(connection->Optional.ofNullable(find(connection,outcomeId)));}
    public List<Entry> ready(UUID player,int limit){return entries(player,Status.READY,limit);}
    public List<Entry> resumable(UUID player,int limit){
        Objects.requireNonNull(player);if(limit<1||limit>1000)throw new IllegalArgumentException("Native scene batch must be 1..1000");
        return store.transaction(c->{List<Entry> result=new ArrayList<>();try(var s=c.prepareStatement("SELECT json FROM native_scene_journal WHERE player=? AND status='READY' AND json_extract(json,'$.checkpoint') IS NOT NULL ORDER BY id LIMIT ?")){s.setString(1,player.toString());s.setInt(2,limit);try(var r=s.executeQuery()){while(r.next())result.add(decode(r.getString(1),Entry.class));}}return List.copyOf(result);});
    }
    public List<Entry> unknown(UUID player,int limit){return entries(player,Status.UNKNOWN,limit);}
    public List<Entry> unknown(int limit){
        if(limit<1||limit>1000)throw new IllegalArgumentException("Native scene batch must be 1..1000");
        return store.transaction(c->{List<Entry> entries=new ArrayList<>();try(var s=c.prepareStatement("SELECT json FROM native_scene_journal WHERE status='UNKNOWN' ORDER BY id LIMIT ?")){s.setInt(1,limit);try(var r=s.executeQuery()){while(r.next())entries.add(decode(r.getString(1),Entry.class));}}return List.copyOf(entries);});
    }
    /** Administrative reconciliation only. No restart, effects or rewards are performed. */
    public boolean acknowledgeCompleted(String id){return store.transaction(c->{Entry current=find(c,id);if(current==null||current.status()!=Status.UNKNOWN)return false;save(c,new Entry(current.request(),Status.COMPLETED,Math.addExact(current.revision(),1),current.checkpoint(),now(),"Operator independently confirmed the scene is complete; no native call replayed"));return true;});}
    public boolean quarantineReady(String id,long expectedRevision,String reason){text(reason);return store.transaction(c->{Entry current=find(c,id);if(current==null||current.status()!=Status.READY||current.revision()!=expectedRevision)return false;save(c,new Entry(current.request(),Status.UNKNOWN,Math.addExact(current.revision(),1),current.checkpoint(),now(),reason));return true;});}
    /** Worker-only reconciliation when a result could not be committed (for example queue
     * rejection). Active entity attempts stay untouched; abandoned leases never retry. */
    public int isolateOrphans(Set<UUID> inFlightPlayers){
        Set<UUID> live=Set.copyOf(inFlightPlayers);
        return store.transaction(c->{List<Entry> orphaned=new ArrayList<>();try(var s=c.createStatement();var r=s.executeQuery("SELECT json FROM native_scene_journal WHERE status='RUNNING'")){while(r.next()){Entry entry=decode(r.getString(1),Entry.class);if(!live.contains(entry.request().playerId()))orphaned.add(entry);}}
            for(Entry old:orphaned)save(c,new Entry(old.request(),Status.UNKNOWN,old.revision(),old.checkpoint(),now(),"Native result lost its worker continuation; isolated without replay"));return orphaned.size();});
    }
    private List<Entry> entries(UUID player,Status status,int limit) {
        Objects.requireNonNull(player);if(limit<1||limit>1000)throw new IllegalArgumentException("Native scene batch must be 1..1000");
        return store.transaction(connection->{List<Entry> result=new ArrayList<>();try(var statement=connection.prepareStatement("SELECT json FROM native_scene_journal WHERE player=? AND status=? ORDER BY id LIMIT ?")) {
            statement.setString(1,player.toString());statement.setString(2,status.name());statement.setInt(3,limit);try(var rows=statement.executeQuery()){while(rows.next())result.add(decode(rows.getString(1),Entry.class));}
        }return List.copyOf(result);});
    }
    private static Entry find(Connection connection,String outcomeId)throws SQLException {
        try(var statement=connection.prepareStatement("SELECT json FROM native_scene_journal WHERE id=?")){statement.setString(1,outcomeId);try(var rows=statement.executeQuery()){return rows.next()?decode(rows.getString(1),Entry.class):null;}}
    }
    private static void save(Connection connection,Entry entry)throws SQLException {
        try(var statement=connection.prepareStatement("INSERT INTO native_scene_journal(id,player,status,revision,json) VALUES(?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET status=excluded.status,revision=excluded.revision,json=excluded.json")) {
            statement.setString(1,entry.request().outcomeId());statement.setString(2,entry.request().playerId().toString());statement.setString(3,entry.status().name());statement.setLong(4,entry.revision());statement.setString(5,json(entry));statement.executeUpdate();
        }
    }
    private Instant now(){return clock.instant().truncatedTo(ChronoUnit.MILLIS);}
    private static void id(String value,String label){if(value==null||!value.matches("[A-Za-z0-9_.:-]{1,256}"))throw new IllegalArgumentException("Invalid native scene "+label);}
    private static void ownedKey(String value){if(value==null||!value.matches("chronicle_[A-Za-z0-9_.:-]{1,118}"))throw new IllegalArgumentException("Native recovery state key must use chronicle_ namespace");}
    private static Set<String> ownedSet(Set<String> values){if(values==null)return Set.of();if(values.size()>256)throw new IllegalArgumentException("Too many owned native state keys");values.forEach(NativeSceneJournal::ownedKey);return Set.copyOf(values);}
    private static void text(String value){if(value==null||value.isBlank()||value.length()>2048)throw new IllegalArgumentException("Native recovery reason must be 1..2048 characters");}
}
