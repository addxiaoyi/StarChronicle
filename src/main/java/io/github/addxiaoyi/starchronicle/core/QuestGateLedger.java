package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.PlayerContext;
import io.github.addxiaoyi.starchronicle.api.quest.*;
import io.github.addxiaoyi.starchronicle.api.quest.QuestObservation.Status;
import io.github.addxiaoyi.starchronicle.api.quest.QuestRequest.Mode;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Durable native-task evidence gates. Every method uses the caller's transaction and never
 * calls a provider, commits, advances a story, executes an effect, or manufactures an event.
 * Provider calls must occur outside the transaction, after pinProvider has committed. */
public final class QuestGateLedger {
    private QuestGateLedger() {}

    public record Gate(String id, String instanceId, String sourceEventId, String storyId,
                       String storyVersion, String storyFingerprint, String scopeKey, String nodeId,
                       String effectId, UUID playerId, String questId, Mode mode, String expectedNationId,
                       Instant openedAt, String providerId, Instant providerPinnedAt, Status status, AcceptedRun acceptedRun,
                       CommittedCompletion completion, Instant updatedAt, Instant lastCheckedAt,
                       long revision, String message, boolean active) {
        public QuestRequest request() { return new QuestRequest(id,playerId,questId,openedAt,expectedNationId,mode); }
        public boolean completed() { return status==Status.COMMITTED; }
    }
    public record Mutation(boolean changed, Gate gate, boolean completionNewlyRecorded) {}
    public record View(boolean ready, List<Gate> gates, List<String> blockedEffectIds) {
        public View { gates=List.copyOf(gates);blockedEffectIds=List.copyOf(blockedEffectIds); }
    }

    public static void createSchema(Connection c)throws SQLException {
        transaction(c);
        try(var s=c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS quest_gates("+
                "id TEXT PRIMARY KEY,instance_id TEXT NOT NULL REFERENCES instances(id),"+
                "source_event_id TEXT NOT NULL REFERENCES events(id),story_id TEXT NOT NULL,story_version TEXT NOT NULL,"+
                "story_fingerprint TEXT NOT NULL,scope_key TEXT NOT NULL,node_id TEXT NOT NULL,effect_id TEXT NOT NULL,"+
                "player_id TEXT NOT NULL,quest_id TEXT NOT NULL,mode TEXT NOT NULL CHECK(mode IN ('PLAYER','NATION')),"+
                "expected_nation_id TEXT NOT NULL,opened_at INTEGER NOT NULL,provider_id TEXT,provider_pinned_at INTEGER,"+
                "status TEXT NOT NULL CHECK(status IN ('UNSUPPORTED','NOT_STARTED','ADMITTING','ACTIVE','COMMITTED','ABANDONED','FAILED','CONFLICT','UNKNOWN')),"+
                "accepted_json TEXT,completion_json TEXT,updated_at INTEGER NOT NULL,last_checked_at INTEGER NOT NULL,"+
                "revision INTEGER NOT NULL CHECK(revision>0),message TEXT NOT NULL,"+
                "CHECK(updated_at>=opened_at AND last_checked_at>=opened_at),"+
                "CHECK((provider_id IS NULL AND provider_pinned_at IS NULL) OR (provider_id IS NOT NULL AND provider_pinned_at IS NOT NULL AND provider_pinned_at>=opened_at AND provider_pinned_at<=updated_at)),"+
                "CHECK(provider_id IS NOT NULL OR accepted_json IS NULL),"+
                "CHECK((status='COMMITTED' AND accepted_json IS NOT NULL AND completion_json IS NOT NULL AND provider_id IS NOT NULL) OR (status<>'COMMITTED' AND completion_json IS NULL)),"+
                "UNIQUE(instance_id,node_id,effect_id,player_id))");
            s.execute("CREATE INDEX IF NOT EXISTS quest_gate_pending ON quest_gates(status,last_checked_at,updated_at,id)");
            s.execute("CREATE INDEX IF NOT EXISTS quest_gate_instance ON quest_gates(instance_id,node_id,effect_id,player_id)");
            s.execute("CREATE TABLE IF NOT EXISTS quest_gate_completions("+
                "issuer TEXT NOT NULL,completion_id TEXT NOT NULL,run_id TEXT NOT NULL,gate_id TEXT NOT NULL UNIQUE REFERENCES quest_gates(id),"+
                "provider_id TEXT NOT NULL,proof_hash TEXT NOT NULL,proof_json TEXT NOT NULL,received_at INTEGER NOT NULL,"+
                "PRIMARY KEY(issuer,completion_id),UNIQUE(issuer,run_id))");
        }
    }

    /** Opens one stable binding per pinned member and await effect. Must follow instance save. */
    public static List<Gate> open(Connection c,StoryInstance current,Instant now)throws SQLException {
        transaction(c);storedCurrent(c,current);Instant at=millis(now);
        if(current.completed())return gates(c,current.id(),current.node());
        if(at.isBefore(millis(current.startedAt()))||at.isBefore(millis(current.updatedAt())))throw new IllegalArgumentException("Gate cannot open before its stored instance/node state");
        var effects=awaits(current,current.node());String fingerprint=DefinitionCanonicalCodec.fingerprintV2(current.definition());
        for(var effect:effects)for(UUID player:new TreeSet<>(current.members().keySet())) {
            String id=RuleEngine.stable("quest-gate",current.id(),current.node(),effect.id(),player.toString());
            try(var s=c.prepareStatement("INSERT OR IGNORE INTO quest_gates(id,instance_id,source_event_id,story_id,story_version,story_fingerprint,scope_key,node_id,effect_id,player_id,quest_id,mode,expected_nation_id,opened_at,status,updated_at,last_checked_at,revision,message) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,'NOT_STARTED',?,?,1,'')")) {
                int n=0;s.setString(++n,id);s.setString(++n,current.id());s.setString(++n,current.sourceEventId());s.setString(++n,current.definition().id());s.setString(++n,current.definition().version());s.setString(++n,fingerprint);s.setString(++n,current.scopeKey());s.setString(++n,current.node());s.setString(++n,effect.id());s.setString(++n,player.toString());s.setString(++n,effect.target());s.setString(++n,effect.value());s.setString(++n,current.members().get(player));s.setLong(++n,at.toEpochMilli());s.setLong(++n,at.toEpochMilli());s.setLong(++n,at.toEpochMilli());s.executeUpdate();
            }
        }
        return gates(c,current.id(),current.node());
    }

    public static List<Gate> gates(Connection c,String instanceId,String nodeId)throws SQLException {
        transaction(c);List<String> ids=new ArrayList<>();
        try(var s=c.prepareStatement("SELECT id FROM quest_gates WHERE instance_id=? AND node_id=? ORDER BY effect_id,player_id")) {
            s.setString(1,instanceId);s.setString(2,nodeId);try(var r=s.executeQuery()){while(r.next())ids.add(r.getString(1));}
        }
        List<Gate> result=new ArrayList<>();for(String id:ids)result.add(load(c,id));return List.copyOf(result);
    }

    /** Read one persisted binding with the same instance, source and receipt validation. */
    public static Gate find(Connection c,String gateId)throws SQLException { transaction(c);return load(c,gateId); }

    /** Filter before LIMIT: an offline unpinned owner cannot starve a live admission. Pinned
     * owners may be offline because lookup is read-only and can unblock their fellow citizens. */
    public static List<Gate> pending(Connection c,Set<UUID> onlineOwners,int limit)throws SQLException {
        transaction(c);Objects.requireNonNull(onlineOwners);if(limit<1)return List.of();
        List<String> online=onlineOwners.stream().map(UUID::toString).sorted().toList();List<String> ids=new ArrayList<>();
        String sql="SELECT g.id FROM quest_gates g JOIN instances i ON i.id=g.instance_id WHERE g.status<>'COMMITTED' AND i.completed=0 AND json_extract(i.json,'$.completed')=0 AND json_extract(i.json,'$.node')=g.node_id AND (g.provider_id IS NOT NULL OR g.player_id IN (SELECT value FROM json_each(?))) ORDER BY g.last_checked_at,g.updated_at,g.id LIMIT ?";
        try(var s=c.prepareStatement(sql)){s.setString(1,SqliteStore.json(online));s.setInt(2,limit);try(var r=s.executeQuery()){while(r.next())ids.add(r.getString(1));}}
        List<Gate> result=new ArrayList<>();for(String id:ids){Gate gate=load(c,id);if(gate.active()&&!gate.completed())result.add(gate);}return List.copyOf(result);
    }

    /** Pin and commit before the first native call; UNKNOWN never authorizes backend failover. */
    public static Gate pinProvider(Connection c,String gateId,String providerId,Instant now)throws SQLException {
        transaction(c);text(providerId,"provider");Gate gate=load(c,gateId);
        if(gate.providerId()!=null&&!gate.providerId().equals(providerId))throw new IllegalStateException("Quest gate provider is already pinned");
        if(!gate.active()||gate.completed()||gate.providerId()!=null)return gate;
        Instant at=receiptTime(gate,now);if(at.isBefore(gate.updatedAt()))throw new IllegalArgumentException("Provider pin predates the gate state");
        try(var s=c.prepareStatement("UPDATE quest_gates SET provider_id=?,provider_pinned_at=?,updated_at=?,revision=revision+1 WHERE id=? AND provider_id IS NULL")) {
            s.setString(1,providerId);s.setLong(2,at.toEpochMilli());s.setLong(3,at.toEpochMilli());s.setString(4,gate.id());if(s.executeUpdate()!=1)throw new IllegalStateException("Quest provider pin raced");
        }
        return load(c,gate.id());
    }

    /** Show unavailable capability without inventing a provider pin or erasing native acceptance. */
    public static Mutation unavailable(Connection c,String gateId,String message,Instant now)throws SQLException {
        transaction(c);Gate gate=load(c,gateId);if(!gate.active()||gate.completed())return unchanged(gate);
        Instant at=receiptTime(gate,now);if(at.isBefore(gate.lastCheckedAt()))return unchanged(gate);
        String diagnostic=message==null?"":message;
        if(gate.status()==Status.UNSUPPORTED&&at.equals(gate.lastCheckedAt())&&diagnostic.equals(gate.message()))return unchanged(gate);
        update(c,gate,Status.UNSUPPORTED,gate.acceptedRun(),null,diagnostic,at);return new Mutation(true,load(c,gate.id()),false);
    }

    /** Validate trusted canonical evidence and consume both native completion and run once.
     * The immutable source insert and completed gate update share this caller transaction. */
    public static Mutation observe(Connection c,String gateId,String providerId,QuestObservation observation,Instant receivedAt)throws SQLException {
        transaction(c);Gate gate=load(c,gateId);text(providerId,"provider");
        if(!providerId.equals(gate.providerId()))throw new IllegalStateException("Quest observation is not from the pinned provider");
        if(!gate.active())return unchanged(gate);
        Objects.requireNonNull(observation,"observation");Objects.requireNonNull(observation.status(),"observation status");
        if(gate.completed()) {
            if(observation.status()==Status.COMMITTED) {
                if(!Objects.equals(gate.completion(),observation.completion())||!Objects.equals(gate.acceptedRun(),observation.acceptedRun()))throw new IllegalStateException("Committed quest evidence cannot be rewritten");
                Instant replayReceipt=receiptTime(gate,receivedAt),replayObserved=millis(observation.observedAt());
                if(replayObserved.isAfter(replayReceipt))throw new IllegalArgumentException("Quest observation is later than receipt");
                validateCompletion(gate,observation.completion(),replayObserved);
            }
            return unchanged(gate);
        }
        Instant received=receiptTime(gate,receivedAt),observed=millis(observation.observedAt());
        if(observed.isBefore(gate.openedAt())||observed.isAfter(received))throw new IllegalArgumentException("Quest observation time is outside the gate receipt window");
        if(observation.status()!=Status.COMMITTED&&observation.completion()!=null)throw new IllegalArgumentException("Only a committed observation may carry a receipt");
        AcceptedRun accepted=observation.acceptedRun();
        if(accepted!=null)validateAccepted(gate,accepted,observed);
        if(gate.acceptedRun()!=null&&accepted!=null&&!gate.acceptedRun().equals(accepted))throw new IllegalStateException("Native quest acceptance is immutable");
        if(observation.status()==Status.ACTIVE&&accepted==null)throw new IllegalArgumentException("Active quest requires a canonical acceptance");
        if(observation.status()==Status.COMMITTED) {
            CommittedCompletion completion=Objects.requireNonNull(observation.completion(),"committed receipt");
            if(accepted==null||!accepted.equals(completion.acceptedRun()))throw new IllegalStateException("Completion changed canonical acceptance");
            validateCompletion(gate,completion,observed);boolean fresh=consume(c,gate,completion,received);
            update(c,gate,Status.COMMITTED,accepted,completion,observation.message(),later(received,gate.lastCheckedAt()));
            return new Mutation(true,load(c,gate.id()),fresh);
        }
        // A later uncertain diagnostic cannot suppress an older, stronger canonical receipt.
        // It may suppress an old noncommitted status but never its already pinned identity.
        if(observed.isBefore(gate.lastCheckedAt()))return unchanged(gate);
        AcceptedRun keep=accepted==null?gate.acceptedRun():accepted;String message=observation.message()==null?"":observation.message();
        if(observation.status()==gate.status()&&Objects.equals(keep,gate.acceptedRun())&&received.equals(gate.lastCheckedAt())&&message.equals(gate.message()))return unchanged(gate);
        update(c,gate,observation.status(),keep,null,message,received);return new Mutation(true,load(c,gate.id()),false);
    }

    public static boolean ready(Connection c,StoryInstance current,PlayerContext actor)throws SQLException { return view(c,current,actor).ready(); }

    /** This is task readiness only. It grants no national vote or state-change authority. */
    public static View view(Connection c,StoryInstance current,PlayerContext actor)throws SQLException {
        transaction(c);storedCurrent(c,current);Objects.requireNonNull(actor);List<Gate> gates=gates(c,current.id(),current.node());List<String> blocked=new ArrayList<>();
        boolean member=current.contains(actor.playerId());String nation=current.members().get(actor.playerId());
        for(var effect:awaits(current,current.node())) {
            Mode mode=Mode.valueOf(effect.value());boolean ok=false;
            if(member&&!current.completed()) {
                if(mode==Mode.PLAYER)ok=gates.stream().anyMatch(g->g.effectId().equals(effect.id())&&g.playerId().equals(actor.playerId())&&g.completed());
                else if(nation!=null&&!nation.isBlank()&&nation.equals(actor.nationId()))
                    ok=gates.stream().anyMatch(g->g.effectId().equals(effect.id())&&nation.equals(g.expectedNationId())&&g.completed());
            }
            if(!ok)blocked.add(effect.id());
        }
        return new View(member&&!current.completed()&&blocked.isEmpty(),gates,blocked);
    }

    private static Gate load(Connection c,String id)throws SQLException {
        Gate gate;
        try(var s=c.prepareStatement("SELECT * FROM quest_gates WHERE id=?")) {
            s.setString(1,id);try(var r=s.executeQuery()) {
                if(!r.next())throw new IllegalArgumentException("Quest gate does not exist: "+id);
                String accepted=r.getString("accepted_json"),completion=r.getString("completion_json");
                Instant pinnedAt=r.getObject("provider_pinned_at")==null?null:Instant.ofEpochMilli(r.getLong("provider_pinned_at"));
                gate=new Gate(r.getString("id"),r.getString("instance_id"),r.getString("source_event_id"),r.getString("story_id"),r.getString("story_version"),r.getString("story_fingerprint"),r.getString("scope_key"),r.getString("node_id"),r.getString("effect_id"),UUID.fromString(r.getString("player_id")),r.getString("quest_id"),Mode.valueOf(r.getString("mode")),r.getString("expected_nation_id"),Instant.ofEpochMilli(r.getLong("opened_at")),r.getString("provider_id"),pinnedAt,Status.valueOf(r.getString("status")),accepted==null?null:SqliteStore.decode(accepted,AcceptedRun.class),completion==null?null:SqliteStore.decode(completion,CommittedCompletion.class),Instant.ofEpochMilli(r.getLong("updated_at")),Instant.ofEpochMilli(r.getLong("last_checked_at")),r.getLong("revision"),r.getString("message"),false);
            }
        }
        StoryInstance instance=stored(c,gate.instanceId());validateIdentity(gate,instance);
        boolean active=!instance.completed()&&instance.node().equals(gate.nodeId());
        if(gate.providerId()!=null)text(gate.providerId(),"stored provider");
        if(gate.acceptedRun()!=null){if(gate.providerId()==null)throw new IllegalStateException("Unpinned quest gate has acceptance");validateAccepted(gate,gate.acceptedRun(),gate.lastCheckedAt());}
        if(gate.completed()) {
            if(gate.completion()==null||!Objects.equals(gate.acceptedRun(),gate.completion().acceptedRun()))throw new IllegalStateException("Stored completion changed acceptance");
            validateCompletion(gate,gate.completion(),gate.lastCheckedAt());verifyConsumed(c,gate);
        } else if(gate.completion()!=null)throw new IllegalStateException("Incomplete gate contains a completion");
        return new Gate(gate.id(),gate.instanceId(),gate.sourceEventId(),gate.storyId(),gate.storyVersion(),gate.storyFingerprint(),gate.scopeKey(),gate.nodeId(),gate.effectId(),gate.playerId(),gate.questId(),gate.mode(),gate.expectedNationId(),gate.openedAt(),gate.providerId(),gate.providerPinnedAt(),gate.status(),gate.acceptedRun(),gate.completion(),gate.updatedAt(),gate.lastCheckedAt(),gate.revision(),gate.message(),active);
    }

    private static void validateIdentity(Gate gate,StoryInstance instance) {
        if(!gate.id().equals(RuleEngine.stable("quest-gate",instance.id(),gate.nodeId(),gate.effectId(),gate.playerId().toString()))
                ||!gate.sourceEventId().equals(instance.sourceEventId())||!gate.storyId().equals(instance.definition().id())||!gate.storyVersion().equals(instance.definition().version())
                ||!gate.storyFingerprint().equals(DefinitionCanonicalCodec.fingerprintV2(instance.definition()))||!gate.scopeKey().equals(instance.scopeKey())
                ||!instance.contains(gate.playerId())||!Objects.equals(gate.expectedNationId(),instance.members().get(gate.playerId()))
                ||gate.openedAt().isBefore(millis(instance.startedAt()))||gate.updatedAt().isBefore(gate.openedAt())||gate.lastCheckedAt().isBefore(gate.openedAt())||gate.revision()<1)
            throw new IllegalStateException("Stored quest gate identity does not match its pinned instance");
        if(gate.providerId()==null?gate.providerPinnedAt()!=null:gate.providerPinnedAt()==null||gate.providerPinnedAt().isBefore(gate.openedAt())||gate.providerPinnedAt().isAfter(gate.updatedAt()))throw new IllegalStateException("Stored quest provider pin time is inconsistent");
        var effect=awaits(instance,gate.nodeId()).stream().filter(e->e.id().equals(gate.effectId())).findFirst().orElseThrow(()->new IllegalStateException("Quest gate effect no longer matches pinned definition"));
        if(!gate.questId().equals(effect.target())||gate.mode()!=Mode.valueOf(effect.value()))throw new IllegalStateException("Stored quest gate target or mode changed");
    }

    private static void validateAccepted(Gate gate,AcceptedRun accepted,Instant observed) {
        text(accepted.bindingId(),"binding");text(accepted.runId(),"run");text(accepted.questId(),"quest");text(accepted.issuer(),"issuer");text(accepted.evidence(),"acceptance evidence");
        if(!gate.id().equals(accepted.bindingId())||!gate.playerId().equals(accepted.playerId())||!gate.questId().equals(accepted.questId()))throw new IllegalArgumentException("Native acceptance is for another gate, owner or task");
        if(accepted.definitionFingerprint()==null||!accepted.definitionFingerprint().matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Native quest definition fingerprint is invalid");
        if(gate.providerPinnedAt()==null)throw new IllegalStateException("Native acceptance requires a previously pinned provider");
        Instant started=millis(accepted.startedAt());if(started.isBefore(gate.providerPinnedAt())||started.isAfter(observed))throw new IllegalArgumentException("Native quest run predates its provider pin or observation");
        validateNation(accepted.acceptedNation(),gate.providerPinnedAt(),started);
        if(gate.mode()==Mode.NATION)requireNation(gate,accepted.acceptedNation());
    }

    private static void validateCompletion(Gate gate,CommittedCompletion completion,Instant observed) {
        validateAccepted(gate,completion.acceptedRun(),observed);text(completion.completionId(),"completion");text(completion.evidence(),"completion evidence");
        Instant started=millis(completion.startedAt()),completed=millis(completion.completedAt()),committed=millis(completion.committedAt());
        if(completed.isBefore(started)||committed.isBefore(completed)||committed.isAfter(observed))throw new IllegalArgumentException("Native completion chronology is invalid");
        validateNation(completion.completedNation(),started,completed);
        if(gate.mode()==Mode.NATION)requireNation(gate,completion.completedNation());
    }

    private static void validateNation(NationProof proof,Instant earliest,Instant latest) {
        Objects.requireNonNull(proof,"nation proof");Objects.requireNonNull(proof.state(),"nation proof state");text(proof.issuer(),"nation proof issuer");text(proof.evidence(),"nation evidence");
        Instant checked=millis(proof.checkedAt());if(checked.isBefore(earliest)||checked.isAfter(latest))throw new IllegalArgumentException("Country evidence time is outside its native transition");
        if(proof.state()==NationProof.State.KNOWN_NATION)text(proof.nationId(),"proven nation");
        else if(proof.nationId()!=null&&!proof.nationId().isBlank())throw new IllegalArgumentException("Unavailable/no-country proof cannot name a country");
    }
    private static void requireNation(Gate gate,NationProof proof) {
        if(gate.expectedNationId().isBlank()||proof.state()!=NationProof.State.KNOWN_NATION||!gate.expectedNationId().equals(proof.nationId()))throw new IllegalArgumentException("National quest requires SAME_NATION acceptance and completion");
    }

    private static boolean consume(Connection c,Gate gate,CommittedCompletion completion,Instant received)throws SQLException {
        String json=SqliteStore.json(completion),hash=RuleEngine.stable("quest-completion-proof",json);
        try(var s=c.prepareStatement("SELECT * FROM quest_gate_completions WHERE issuer=? AND (completion_id=? OR run_id=?)")) {
            s.setString(1,completion.issuer());s.setString(2,completion.completionId());s.setString(3,completion.runId());
            try(var r=s.executeQuery()) {
                if(r.next()) {
                    if(!gate.id().equals(r.getString("gate_id"))||!gate.providerId().equals(r.getString("provider_id"))||!completion.completionId().equals(r.getString("completion_id"))||!completion.runId().equals(r.getString("run_id"))||!hash.equals(r.getString("proof_hash"))||!json.equals(r.getString("proof_json")))throw new IllegalStateException("Native completion or run was already consumed by another gate or proof");
                    if(r.next())throw new IllegalStateException("Native run and completion resolve to different receipts");
                    // Source and gate always commit together. Repairing an orphan source here
                    // would suppress the engine's same-transaction history/inbox insertion.
                    throw new IllegalStateException("Completion source exists for an incomplete gate; atomic evidence is inconsistent");
                }
            }
        }
        try(var s=c.prepareStatement("INSERT INTO quest_gate_completions(issuer,completion_id,run_id,gate_id,provider_id,proof_hash,proof_json,received_at) VALUES(?,?,?,?,?,?,?,?)")) {
            s.setString(1,completion.issuer());s.setString(2,completion.completionId());s.setString(3,completion.runId());s.setString(4,gate.id());s.setString(5,gate.providerId());s.setString(6,hash);s.setString(7,json);s.setLong(8,received.toEpochMilli());s.executeUpdate();return true;
        }
    }
    private static void verifyConsumed(Connection c,Gate gate)throws SQLException {
        String json=SqliteStore.json(gate.completion());
        try(var s=c.prepareStatement("SELECT * FROM quest_gate_completions WHERE gate_id=?")) {
            s.setString(1,gate.id());try(var r=s.executeQuery()) {
                if(!r.next()||!gate.providerId().equals(r.getString("provider_id"))||!gate.completion().issuer().equals(r.getString("issuer"))||!gate.completion().runId().equals(r.getString("run_id"))||!gate.completion().completionId().equals(r.getString("completion_id"))||!json.equals(r.getString("proof_json"))||!RuleEngine.stable("quest-completion-proof",json).equals(r.getString("proof_hash"))||r.getLong("received_at")<millis(gate.completion().committedAt()).toEpochMilli())throw new IllegalStateException("Completed quest gate has no matching immutable source receipt");
            }
        }
    }
    private static void update(Connection c,Gate gate,Status status,AcceptedRun accepted,CommittedCompletion completion,String message,Instant at)throws SQLException {
        try(var s=c.prepareStatement("UPDATE quest_gates SET status=?,accepted_json=?,completion_json=?,updated_at=?,last_checked_at=?,revision=revision+1,message=? WHERE id=? AND revision=?")) {
            s.setString(1,status.name());s.setString(2,accepted==null?null:SqliteStore.json(accepted));s.setString(3,completion==null?null:SqliteStore.json(completion));s.setLong(4,later(at,gate.updatedAt()).toEpochMilli());s.setLong(5,at.toEpochMilli());s.setString(6,message==null?"":message);s.setString(7,gate.id());s.setLong(8,gate.revision());if(s.executeUpdate()!=1)throw new IllegalStateException("Quest gate changed during observation");
        }
    }

    private static List<StoryDefinition.Effect> awaits(StoryInstance instance,String node) {
        var definition=instance.definition().nodes().get(node);if(definition==null)throw new IllegalStateException("Quest gate references a missing pinned node");
        List<StoryDefinition.Effect> result=new ArrayList<>();Set<String> ids=new HashSet<>();
        for(var effect:definition.effects())if("quest.await".equals(effect.type())) {
            text(effect.id(),"effect");text(effect.target(),"quest target");if(!ids.add(effect.id()))throw new IllegalStateException("Duplicate quest await effect identity");
            if(definition.terminal())throw new IllegalStateException("Terminal nodes cannot await quests");Mode.valueOf(effect.value());result.add(effect);
        }
        return List.copyOf(result);
    }
    private static StoryInstance stored(Connection c,String id)throws SQLException {
        try(var s=c.prepareStatement("SELECT id,story,scope,completed,updated,json FROM instances WHERE id=?")) {
            s.setString(1,id);try(var r=s.executeQuery()) {
                if(!r.next())throw new IllegalStateException("Quest gate requires an already stored instance");StoryInstance instance=SqliteStore.decode(r.getString("json"),StoryInstance.class);
                if(!instance.id().equals(r.getString("id"))||!instance.definition().id().equals(r.getString("story"))||!instance.scopeKey().equals(r.getString("scope"))||instance.completed()!=(r.getInt("completed")!=0)||!instance.updatedAt().equals(Instant.parse(r.getString("updated")))||!instance.definition().nodes().containsKey(instance.node()))throw new IllegalStateException("Stored quest instance metadata is inconsistent");
                boolean valid=switch(instance.definition().scope()) {
                    case WORLD->instance.scopeKey().equals("world");
                    case NATION->instance.scopeKey().startsWith("nation:")&&instance.scopeKey().length()>7&&instance.members().values().stream().allMatch(n->instance.scopeKey().equals("nation:"+n));
                    case PLAYER->instance.members().size()==1&&instance.scopeKey().equals("player:"+instance.members().keySet().iterator().next());
                };
                if(!valid)throw new IllegalStateException("Stored quest instance scope is inconsistent");return instance;
            }
        }
    }
    private static void storedCurrent(Connection c,StoryInstance current)throws SQLException { Objects.requireNonNull(current);if(!current.equals(stored(c,current.id())))throw new IllegalStateException("Quest gate current-instance is stale"); }
    private static Mutation unchanged(Gate gate) { return new Mutation(false,gate,false); }
    private static Instant receiptTime(Gate gate,Instant time) { Instant at=millis(time);if(at.isBefore(gate.openedAt()))throw new IllegalArgumentException("Quest receipt predates the gate");return at; }
    private static Instant millis(Instant time) { return Instant.ofEpochMilli(Objects.requireNonNull(time,"time").toEpochMilli()); }
    private static Instant later(Instant a,Instant b) { return a.isBefore(b)?b:a; }
    private static void text(String value,String field) { if(value==null||value.isBlank())throw new IllegalArgumentException("Missing "+field); }
    private static void transaction(Connection c)throws SQLException { if(c==null||c.getAutoCommit())throw new IllegalStateException("Quest gate ledger requires the enclosing Chronicle transaction"); }
}
