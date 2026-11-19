package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Country-owned ballots, append-only actions and immutable quorum evidence. Every method
 * uses the caller's existing SQL transaction. Mutations additionally require a live native
 * authority View spanning that transaction (native monitor -> Chronicle store). This class
 * never advances instances, writes personal BALLOTs, executes effects or commits on its own. */
public final class NationalVoteLedger {
    private NationalVoteLedger(){}
    public record CountedBallot(String nationId,long revision,String choiceId,UUID actorId){}
    public record Resolution(String id,String instanceId,String nodeId,String choiceId,int quorum,
                             UUID actorId,Instant checkedAt,List<CountedBallot> counted,String authorityEvidence) {
        public Resolution {counted=List.copyOf(counted);}
    }
    public record Snapshot(List<NationalVoteState.Ballot> ballots,Map<String,Long> counts,Optional<Resolution> resolution) {
        public Snapshot {ballots=List.copyOf(ballots);counts=Collections.unmodifiableMap(new LinkedHashMap<>(counts));resolution=Objects.requireNonNull(resolution);}
    }
    public record Mutation(boolean changed,Snapshot snapshot,Optional<Resolution> resolution){}
    private record Evidence(UUID actor,String nation,Instant checkedAt,String value){}
    private record Stored(NationalVoteState.Ballot ballot,long seq){}

    public static void createSchema(Connection c)throws SQLException {
        transaction(c);try(var s=c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS story_nation_vote_log("+
                "seq INTEGER PRIMARY KEY AUTOINCREMENT,id TEXT NOT NULL UNIQUE,"+
                "instance_id TEXT NOT NULL REFERENCES instances(id),source_event_id TEXT NOT NULL REFERENCES events(id),"+
                "story_id TEXT NOT NULL,story_version TEXT NOT NULL,node_id TEXT NOT NULL,nation_id TEXT NOT NULL,"+
                "revision INTEGER NOT NULL CHECK(revision>0),action TEXT NOT NULL CHECK(action IN ('CAST','CHANGE','WITHDRAW','INVALIDATE')),"+
                "status TEXT NOT NULL CHECK(status IN ('ACTIVE','WITHDRAWN','INVALIDATED')),choice_id TEXT,"+
                "actor_id TEXT NOT NULL,actor_nation_id TEXT NOT NULL,changed_at TEXT NOT NULL,checked_at TEXT NOT NULL,authority_evidence TEXT NOT NULL,"+
                "CHECK((status='ACTIVE' AND choice_id IS NOT NULL AND action IN ('CAST','CHANGE')) OR (status='WITHDRAWN' AND choice_id IS NULL AND action='WITHDRAW') OR (status='INVALIDATED' AND choice_id IS NULL AND action='INVALIDATE')),"+
                "UNIQUE(instance_id,node_id,nation_id,revision))");
            s.execute("CREATE INDEX IF NOT EXISTS story_nation_vote_history ON story_nation_vote_log(nation_id,story_id,story_version,node_id,seq DESC)");
            s.execute("CREATE TABLE IF NOT EXISTS story_nation_votes("+
                "instance_id TEXT NOT NULL REFERENCES instances(id),node_id TEXT NOT NULL,nation_id TEXT NOT NULL,"+
                "revision INTEGER NOT NULL CHECK(revision>0),status TEXT NOT NULL CHECK(status IN ('ACTIVE','WITHDRAWN','INVALIDATED')),"+
                "choice_id TEXT,actor_id TEXT NOT NULL,checked_at TEXT NOT NULL,authority_evidence TEXT NOT NULL,"+
                "log_seq INTEGER NOT NULL UNIQUE REFERENCES story_nation_vote_log(seq),"+
                "CHECK((status='ACTIVE' AND choice_id IS NOT NULL) OR (status IN ('WITHDRAWN','INVALIDATED') AND choice_id IS NULL)),"+
                "PRIMARY KEY(instance_id,node_id,nation_id))");
            s.execute("CREATE TABLE IF NOT EXISTS story_nation_actor_bindings("+
                "instance_id TEXT NOT NULL REFERENCES instances(id),node_id TEXT NOT NULL,actor_id TEXT NOT NULL,nation_id TEXT NOT NULL,"+
                "bound_at TEXT NOT NULL,authority_evidence TEXT NOT NULL,PRIMARY KEY(instance_id,node_id,actor_id))");
            s.execute("CREATE TABLE IF NOT EXISTS story_nation_resolutions("+
                "id TEXT PRIMARY KEY,instance_id TEXT NOT NULL REFERENCES instances(id),source_event_id TEXT NOT NULL REFERENCES events(id),"+
                "story_id TEXT NOT NULL,story_version TEXT NOT NULL,node_id TEXT NOT NULL,scope_key TEXT NOT NULL,"+
                "choice_id TEXT NOT NULL,quorum INTEGER NOT NULL CHECK(quorum>0),actor_id TEXT NOT NULL,checked_at TEXT NOT NULL,authority_evidence TEXT NOT NULL,json TEXT NOT NULL,"+
                "UNIQUE(instance_id,node_id))");
        }
    }

    public static Mutation cast(Connection c,StoryInstance before,PlayerContext actor,NationalVoteAuthority.View authority,
                                String choice,long expectedRevision,Instant time)throws Exception {
        return mutate(c,before,actor,authority,choice,expectedRevision,time,false);
    }
    public static Mutation withdraw(Connection c,StoryInstance before,PlayerContext actor,NationalVoteAuthority.View authority,
                                    long expectedRevision,Instant time)throws Exception {
        return mutate(c,before,actor,authority,null,expectedRevision,time,true);
    }
    private static Mutation mutate(Connection c,StoryInstance before,PlayerContext actor,NationalVoteAuthority.View authority,
                                   String choice,long expectedRevision,Instant time,boolean withdrawing)throws Exception {
        transaction(c);Objects.requireNonNull(before);Objects.requireNonNull(actor);Objects.requireNonNull(authority);Objects.requireNonNull(time);
        StoryInstance stored=stored(c,before,true);String node=before.node();StoryDefinition.Node definition=node(before,node);
        if(stored.completed()||definition.terminal())throw new IllegalStateException("National ballot requires an active nonterminal node");
        if(resolution(c,before,node).isPresent())throw new IllegalStateException("National resolution is already frozen");
        if(!withdrawing&&(choice==null||!definition.choices().containsKey(choice)))throw new IllegalArgumentException("Choice does not exist in the pinned national node");
        if(!before.contains(actor.playerId())||!Objects.equals(before.members().get(actor.playerId()),actor.nationId()))throw new SecurityException("National representative membership or original nationality changed");
        if(!actor.permitted("starchronicle.use")||!canonicalNation(actor.nationId()))throw new SecurityException("A permitted current native nation member is required");
        if(before.definition().scope()==StoryDefinition.Scope.NATION&&!before.scopeKey().equals("nation:"+actor.nationId()))throw new SecurityException("Representative belongs to another instance nation");
        Evidence evidence=evidence(authority,actor,time);eligibleSource(c,before,actor.nationId());checkBinding(c,before,node,actor.playerId(),actor.nationId());
        List<Stored> current=ballots(c,before,node);Stored own=current.stream().filter(v->v.ballot().nationId().equals(actor.nationId())).findFirst().orElse(null);
        if(expectedRevision<0||(own==null?0:own.ballot().revision())!=expectedRevision)throw new IllegalStateException("National ballot revision changed; refresh before submitting");
        // Read the whole current projection under the live native monitor before writing.
        // An exception is propagated, never relabeled as a dissolved country.
        Map<String,Boolean> exists=new TreeMap<>();exists.put(actor.nationId(),authority.nationExists(actor.nationId()));
        if(!exists.get(actor.nationId()))throw new SecurityException("The representative's native nation no longer exists");
        for(Stored vote:current)if(!exists.containsKey(vote.ballot().nationId()))exists.put(vote.ballot().nationId(),authority.nationExists(vote.ballot().nationId()));
        // Recheck scoped View liveness immediately before the first SQL mutation.
        if(!actor.playerId().equals(authority.actorId())||!actor.nationId().equals(authority.nationId()))throw new SecurityException("Native authority changed");
        boolean changed=false;
        for(Stored vote:current)if(vote.ballot().status().equals("ACTIVE")&&!exists.get(vote.ballot().nationId())) {
            write(c,before,node,vote,vote.ballot().nationId(),null,"INVALIDATED","INVALIDATE",evidence,time);changed=true;
        }
        boolean same=withdrawing?own==null||!own.ballot().status().equals("ACTIVE"):
            own!=null&&own.ballot().status().equals("ACTIVE")&&Objects.equals(choice,own.ballot().choiceId());
        if(!same) {
            bind(c,before,node,evidence,time);
            write(c,before,node,own,actor.nationId(),withdrawing?null:choice,withdrawing?"WITHDRAWN":"ACTIVE",withdrawing?"WITHDRAW":own!=null&&own.ballot().status().equals("ACTIVE")?"CHANGE":"CAST",evidence,time);
            changed=true;
        }
        List<Stored> after=ballots(c,before,node);Optional<Resolution> frozen=Optional.empty();
        if(!withdrawing) {
            List<CountedBallot> counted=after.stream().map(Stored::ballot).filter(v->v.status().equals("ACTIVE")&&Objects.equals(v.choiceId(),choice))
                .map(v->new CountedBallot(v.nationId(),v.revision(),v.choiceId(),v.actorId())).toList();
            if(counted.size()>=definition.quorum()) {
                Resolution result=new Resolution(resolutionId(before,node),before.id(),node,choice,definition.quorum(),actor.playerId(),evidence.checkedAt(),counted,evidence.value());
                freeze(c,before,result);verifyResolution(c,before,result);frozen=Optional.of(result);changed=true;
            }
        }
        Snapshot snapshot=snapshot(c,before,node);return new Mutation(changed,snapshot,frozen);
    }

    /** Historical/presentation reads never call native services or invalidate ballots. */
    public static Snapshot snapshot(Connection c,StoryInstance instance,String node)throws SQLException {
        transaction(c);stored(c,instance,false);node(instance,node);List<NationalVoteState.Ballot> values=ballots(c,instance,node).stream().map(Stored::ballot).toList();Map<String,Long> counts=new TreeMap<>();
        for(var vote:values)if(vote.status().equals("ACTIVE"))counts.merge(vote.choiceId(),1L,Math::addExact);
        Optional<Resolution> frozen=resolution(c,instance,node);if(frozen.isPresent())verifyResolution(c,instance,frozen.get());
        return new Snapshot(values,counts,frozen);
    }
    public static Optional<Resolution> resolution(Connection c,StoryInstance instance,String node)throws SQLException {
        transaction(c);stored(c,instance,false);node(instance,node);
        try(var s=c.prepareStatement("SELECT * FROM story_nation_resolutions WHERE instance_id=? AND node_id=?")) {
            s.setString(1,instance.id());s.setString(2,node);try(var r=s.executeQuery()) {
                if(!r.next())return Optional.empty();Resolution result=SqliteStore.decode(r.getString("json"),Resolution.class);
                if(!resolutionId(instance,node).equals(result.id())||!result.id().equals(r.getString("id"))||!instance.id().equals(result.instanceId())||!node.equals(result.nodeId())
                    ||!instance.sourceEventId().equals(r.getString("source_event_id"))||!instance.definition().id().equals(r.getString("story_id"))||!instance.definition().version().equals(r.getString("story_version"))
                    ||!instance.scopeKey().equals(r.getString("scope_key"))||!result.choiceId().equals(r.getString("choice_id"))||result.quorum()!=r.getInt("quorum")
                    ||!result.actorId().toString().equals(r.getString("actor_id"))||!result.checkedAt().equals(Instant.parse(r.getString("checked_at")))||!result.authorityEvidence().equals(r.getString("authority_evidence")))
                    throw new IllegalStateException("Stored national resolution metadata is inconsistent");
                validateResolution(instance,node,result);return Optional.of(result);
            }
        }
    }

    /** Prove frozen resolution and counted revisions against the actual projection and its
     * last append-only actions. No native reads, writes, personal choices or effect execution. */
    public static void verifyResolution(Connection c,StoryInstance before,Resolution result)throws SQLException {
        transaction(c);Objects.requireNonNull(result);stored(c,before,false);validateResolution(before,result.nodeId(),result);
        Resolution frozen=resolution(c,before,result.nodeId()).orElseThrow(()->new IllegalStateException("Missing frozen national resolution"));
        if(!frozen.equals(result))throw new IllegalStateException("National resolution differs from frozen evidence");
        List<CountedBallot> actual=ballots(c,before,result.nodeId()).stream().map(Stored::ballot).filter(v->v.status().equals("ACTIVE")&&v.choiceId().equals(result.choiceId()))
            .map(v->new CountedBallot(v.nationId(),v.revision(),v.choiceId(),v.actorId())).toList();
        if(!actual.equals(result.counted()))throw new IllegalStateException("Frozen national quorum differs from ballot revision evidence");
    }

    /** Latest committed national action in completed instances, ordered by this database's
     * append sequence. A latest withdrawal/invalidation clears the fact with no fallback. */
    public static Optional<String> readHistorical(Connection c,String nation,String story,String version,String node)throws SQLException {
        transaction(c);id(story);id(version);id(node);if(!canonicalNation(nation))return Optional.empty();
        String sql="SELECT l.instance_id,l.seq FROM story_nation_vote_log l JOIN instances i ON i.id=l.instance_id WHERE i.completed=1 AND l.nation_id=? AND l.story_id=? AND l.story_version=? AND l.node_id=? ORDER BY l.seq DESC LIMIT 1";
        try(var s=c.prepareStatement(sql)) {
            s.setString(1,nation);s.setString(2,story);s.setString(3,version);s.setString(4,node);try(var r=s.executeQuery()) {
                if(!r.next())return Optional.empty();String instanceId=r.getString(1);long seq=r.getLong(2);StoryInstance instance=load(c,instanceId);
                national(instance);if(!instance.completed()||!instance.definition().id().equals(story)||!instance.definition().version().equals(version))throw new IllegalStateException("National history instance metadata is inconsistent");
                Stored latest=ballots(c,instance,node).stream().filter(v->v.ballot().nationId().equals(nation)).findFirst().orElseThrow(()->new IllegalStateException("Missing national history projection"));
                if(latest.seq()!=seq)throw new IllegalStateException("National history does not point to its latest action");return latest.ballot().status().equals("ACTIVE")?Optional.of(latest.ballot().choiceId()):Optional.empty();
            }
        }
    }

    private static Evidence evidence(NationalVoteAuthority.View authority,PlayerContext actor,Instant time) {
        UUID actual=authority.actorId();String nation=authority.nationId();Instant checked=Objects.requireNonNull(authority.checkedAt());String issuer=authority.issuer(),value=authority.evidence();
        if(!actor.playerId().equals(actual)||!actor.nationId().equals(nation)||checked.isAfter(time))throw new SecurityException("National authority identity/time does not match this action");
        text(issuer,256);text(value,8192);String proof=SqliteStore.json(Map.of("issuer",issuer,"evidence",value,"actor",actual.toString(),"nation",nation,"checkedAt",checked.toString()));return new Evidence(actual,nation,checked,proof);
    }
    private static void eligibleSource(Connection c,StoryInstance instance,String nation)throws SQLException {
        try(var s=c.prepareStatement("SELECT json FROM events WHERE id=?")){s.setString(1,instance.sourceEventId());try(var r=s.executeQuery()) {
            if(!r.next())throw new IllegalStateException("Missing canonical national electorate source");WorldEvent source=SqliteStore.decode(r.getString(1),WorldEvent.class);
            if(!source.id().equals(instance.sourceEventId()))throw new IllegalStateException("Canonical national source identity changed");
            if(!source.nations().isEmpty()?!source.nations().contains(nation):!source.globalAudience())throw new SecurityException("Nation has no seat in this source electorate");
        }}
    }
    private static StoryInstance stored(Connection c,StoryInstance instance,boolean exact)throws SQLException {
        Objects.requireNonNull(instance);StoryInstance stored=load(c,instance.id());national(instance);
        if(!instance.definition().equals(stored.definition())||!instance.scopeKey().equals(stored.scopeKey())||!instance.sourceEventId().equals(stored.sourceEventId())||exact&&!instance.equals(stored))throw new IllegalStateException("National vote before-instance is stale or its pinned identity changed");return stored;
    }
    private static StoryInstance load(Connection c,String instance)throws SQLException {
        try(var s=c.prepareStatement("SELECT id,story,scope,completed,json FROM instances WHERE id=?")){s.setString(1,instance);try(var r=s.executeQuery()) {
            if(!r.next())throw new IllegalStateException("National vote requires a stored instance");StoryInstance value=SqliteStore.decode(r.getString("json"),StoryInstance.class);
            if(!value.id().equals(r.getString("id"))||!value.definition().id().equals(r.getString("story"))||!value.scopeKey().equals(r.getString("scope"))||value.completed()!=(r.getInt("completed")!=0))throw new IllegalStateException("Stored national instance metadata is inconsistent");
            national(value);return value;
        }}
    }
    private static void national(StoryInstance instance) {
        if(instance.definition().voteUnit()!=StoryDefinition.VoteUnit.NATION||instance.definition().scope()==StoryDefinition.Scope.PLAYER)throw new IllegalStateException("A national-vote shared instance is required");
        boolean valid=switch(instance.definition().scope()) {
            case WORLD->instance.scopeKey().equals("world");
            case NATION->instance.scopeKey().startsWith("nation:")&&canonicalNation(instance.scopeKey().substring(7))&&instance.members().values().stream().allMatch(n->instance.scopeKey().equals("nation:"+n));
            case PLAYER->false;
        };
        if(!valid)throw new IllegalStateException("National instance scope is inconsistent");
    }
    private static StoryDefinition.Node node(StoryInstance instance,String node){id(node);StoryDefinition.Node value=instance.definition().nodes().get(node);if(value==null)throw new IllegalArgumentException("Unknown pinned national node");return value;}
    private static void checkBinding(Connection c,StoryInstance instance,String node,UUID actor,String nation)throws SQLException {
        try(var s=c.prepareStatement("SELECT nation_id FROM story_nation_actor_bindings WHERE instance_id=? AND node_id=? AND actor_id=?")){s.setString(1,instance.id());s.setString(2,node);s.setString(3,actor.toString());try(var r=s.executeQuery()){if(r.next()&&!nation.equals(r.getString(1)))throw new SecurityException("This actor is already bound to another nation at this node");}}
    }
    private static void bind(Connection c,StoryInstance instance,String node,Evidence evidence,Instant time)throws SQLException {
        checkBinding(c,instance,node,evidence.actor(),evidence.nation());try(var s=c.prepareStatement("INSERT OR IGNORE INTO story_nation_actor_bindings VALUES(?,?,?,?,?,?)")){s.setString(1,instance.id());s.setString(2,node);s.setString(3,evidence.actor().toString());s.setString(4,evidence.nation());s.setString(5,time.toString());s.setString(6,evidence.value());s.executeUpdate();}
        checkBinding(c,instance,node,evidence.actor(),evidence.nation());
    }
    private static List<Stored> ballots(Connection c,StoryInstance instance,String node)throws SQLException {
        StoryDefinition.Node definition=node(instance,node);List<Stored> result=new ArrayList<>();
        try(var s=c.prepareStatement("SELECT * FROM story_nation_votes WHERE instance_id=? AND node_id=? ORDER BY nation_id")){s.setString(1,instance.id());s.setString(2,node);try(var r=s.executeQuery()){while(r.next()) {
            String nation=r.getString("nation_id"),status=r.getString("status"),choice=r.getString("choice_id");long revision=r.getLong("revision");UUID actor=uuid(r.getString("actor_id"));Instant checked=Instant.parse(r.getString("checked_at"));String proof=r.getString("authority_evidence");
            if(!canonicalNation(nation)||revision<1||!Set.of("ACTIVE","WITHDRAWN","INVALIDATED").contains(status)||(status.equals("ACTIVE")?(choice==null||!definition.choices().containsKey(choice)):choice!=null))throw new IllegalStateException("Invalid national ballot projection");
            text(proof,16384);result.add(new Stored(new NationalVoteState.Ballot(nation,revision,choice,status,actor,checked,proof),r.getLong("log_seq")));if(result.size()>10000)throw new IllegalStateException("Too many national seats");
        }}}
        for(Stored value:result)verifyLog(c,instance,node,value);return List.copyOf(result);
    }
    private static void verifyLog(Connection c,StoryInstance instance,String node,Stored stored)throws SQLException {
        var vote=stored.ballot();try(var s=c.prepareStatement("SELECT * FROM story_nation_vote_log WHERE instance_id=? AND node_id=? AND nation_id=? ORDER BY revision DESC LIMIT 1")) {
            s.setString(1,instance.id());s.setString(2,node);s.setString(3,vote.nationId());try(var r=s.executeQuery()) {
                if(!r.next()||r.getLong("seq")!=stored.seq()||!logId(instance,node,vote.nationId(),vote.revision()).equals(r.getString("id"))||!instance.sourceEventId().equals(r.getString("source_event_id"))
                    ||!instance.definition().id().equals(r.getString("story_id"))||!instance.definition().version().equals(r.getString("story_version"))||r.getLong("revision")!=vote.revision()||!vote.status().equals(r.getString("status"))
                    ||!Objects.equals(vote.choiceId(),r.getString("choice_id"))||!vote.actorId().toString().equals(r.getString("actor_id"))||!vote.checkedAt().equals(Instant.parse(r.getString("checked_at")))||!vote.authorityEvidence().equals(r.getString("authority_evidence")))throw new IllegalStateException("National projection does not match its latest append-only action");
                String action=r.getString("action"),actorNation=r.getString("actor_nation_id");Instant.parse(r.getString("changed_at"));
                boolean valid=switch(vote.status()){case "ACTIVE"->Set.of("CAST","CHANGE").contains(action);case "WITHDRAWN"->action.equals("WITHDRAW");default->action.equals("INVALIDATE");};
                if(!valid||!canonicalNation(actorNation)||!action.equals("INVALIDATE")&&!actorNation.equals(vote.nationId()))throw new IllegalStateException("Invalid national action attribution");
                if(!action.equals("INVALIDATE"))requireBinding(c,instance,node,vote.actorId(),vote.nationId());
            }
        }
    }
    private static void requireBinding(Connection c,StoryInstance instance,String node,UUID actor,String nation)throws SQLException {
        try(var s=c.prepareStatement("SELECT nation_id FROM story_nation_actor_bindings WHERE instance_id=? AND node_id=? AND actor_id=?")){s.setString(1,instance.id());s.setString(2,node);s.setString(3,actor.toString());try(var r=s.executeQuery()){if(!r.next()||!nation.equals(r.getString(1)))throw new IllegalStateException("National action has no matching actor binding");}}
    }
    private static void write(Connection c,StoryInstance instance,String node,Stored previous,String nation,String choice,String status,String action,Evidence evidence,Instant time)throws SQLException {
        long revision=Math.addExact(previous==null?0:previous.ballot().revision(),1);long seq;
        try(var s=c.prepareStatement("INSERT INTO story_nation_vote_log(id,instance_id,source_event_id,story_id,story_version,node_id,nation_id,revision,action,status,choice_id,actor_id,actor_nation_id,changed_at,checked_at,authority_evidence) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            int p=1;s.setString(p++,logId(instance,node,nation,revision));s.setString(p++,instance.id());s.setString(p++,instance.sourceEventId());s.setString(p++,instance.definition().id());s.setString(p++,instance.definition().version());s.setString(p++,node);s.setString(p++,nation);s.setLong(p++,revision);s.setString(p++,action);s.setString(p++,status);s.setString(p++,choice);s.setString(p++,evidence.actor().toString());s.setString(p++,evidence.nation());s.setString(p++,time.toString());s.setString(p++,evidence.checkedAt().toString());s.setString(p,evidence.value());if(s.executeUpdate()!=1)throw new IllegalStateException("National action was not appended");
        }
        try(var s=c.createStatement();var r=s.executeQuery("SELECT last_insert_rowid()")){if(!r.next())throw new IllegalStateException("Missing national action sequence");seq=r.getLong(1);}
        if(previous==null) {
            try(var s=c.prepareStatement("INSERT INTO story_nation_votes VALUES(?,?,?,?,?,?,?,?,?,?)")){s.setString(1,instance.id());s.setString(2,node);s.setString(3,nation);s.setLong(4,revision);s.setString(5,status);s.setString(6,choice);s.setString(7,evidence.actor().toString());s.setString(8,evidence.checkedAt().toString());s.setString(9,evidence.value());s.setLong(10,seq);if(s.executeUpdate()!=1)throw new IllegalStateException("National ballot was not created");}
        }else {
            try(var s=c.prepareStatement("UPDATE story_nation_votes SET revision=?,status=?,choice_id=?,actor_id=?,checked_at=?,authority_evidence=?,log_seq=? WHERE instance_id=? AND node_id=? AND nation_id=? AND revision=? AND log_seq=?")){s.setLong(1,revision);s.setString(2,status);s.setString(3,choice);s.setString(4,evidence.actor().toString());s.setString(5,evidence.checkedAt().toString());s.setString(6,evidence.value());s.setLong(7,seq);s.setString(8,instance.id());s.setString(9,node);s.setString(10,nation);s.setLong(11,previous.ballot().revision());s.setLong(12,previous.seq());if(s.executeUpdate()!=1)throw new IllegalStateException("National ballot changed during compare-and-set");}
        }
    }
    private static void freeze(Connection c,StoryInstance instance,Resolution result)throws SQLException {
        try(var s=c.prepareStatement("INSERT INTO story_nation_resolutions VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)")){int p=1;s.setString(p++,result.id());s.setString(p++,instance.id());s.setString(p++,instance.sourceEventId());s.setString(p++,instance.definition().id());s.setString(p++,instance.definition().version());s.setString(p++,result.nodeId());s.setString(p++,instance.scopeKey());s.setString(p++,result.choiceId());s.setInt(p++,result.quorum());s.setString(p++,result.actorId().toString());s.setString(p++,result.checkedAt().toString());s.setString(p++,result.authorityEvidence());s.setString(p,SqliteStore.json(result));if(s.executeUpdate()!=1)throw new IllegalStateException("National resolution was not frozen");}
    }
    private static void validateResolution(StoryInstance instance,String node,Resolution result) {
        StoryDefinition.Node definition=node(instance,node);Set<String> nations=new HashSet<>();
        if(!result.id().equals(resolutionId(instance,node))||!instance.id().equals(result.instanceId())||!node.equals(result.nodeId())||!definition.choices().containsKey(result.choiceId())||result.quorum()!=definition.quorum()||result.actorId()==null||result.checkedAt()==null||result.counted().size()<result.quorum())throw new IllegalStateException("Invalid frozen national resolution");
        text(result.authorityEvidence(),16384);String prior=null;
        for(CountedBallot vote:result.counted()){if(!canonicalNation(vote.nationId())||vote.revision()<1||vote.actorId()==null||!result.choiceId().equals(vote.choiceId())||!nations.add(vote.nationId())||prior!=null&&prior.compareTo(vote.nationId())>=0)throw new IllegalStateException("Invalid or duplicate counted national ballot");prior=vote.nationId();}
    }
    private static String resolutionId(StoryInstance instance,String node){return RuleEngine.stable("choice-log",instance.id(),node,"RESOLUTION",instance.scopeKey(),"1");}
    private static String logId(StoryInstance instance,String node,String nation,long revision){return RuleEngine.stable("national-vote",instance.id(),node,nation,Long.toString(revision));}
    private static UUID uuid(String value){UUID result=UUID.fromString(value);if(!result.toString().equals(value))throw new IllegalStateException("Noncanonical national action actor");return result;}
    private static boolean canonicalNation(String value){try{return value!=null&&UUID.fromString(value).toString().equals(value);}catch(IllegalArgumentException failure){return false;}}
    private static void id(String value){if(value==null||!value.matches("[A-Za-z0-9_.-]{1,80}"))throw new IllegalArgumentException("Invalid national history identity");}
    private static void text(String value,int max){if(value==null||value.isBlank()||value.length()>max)throw new IllegalStateException("Missing or oversized national authority evidence");}
    private static void transaction(Connection c)throws SQLException{if(c==null||c.getAutoCommit())throw new IllegalStateException("National vote ledger requires the enclosing Chronicle transaction");}
}
