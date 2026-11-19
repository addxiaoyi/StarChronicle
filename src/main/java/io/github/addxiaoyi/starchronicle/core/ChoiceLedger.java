package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.PlayerContext;
import io.github.addxiaoyi.starchronicle.config.Condition;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition;
import io.github.addxiaoyi.starchronicle.config.StoryDependencies;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Append-only personal ballots and collective resolutions in the enclosing engine transaction.
 * Historical projections include only completed instances and only explicitly requested facts.
 * Their latest value follows committed log sequence, never inferred event or completion time. */
public final class ChoiceLedger {
    private ChoiceLedger(){}

    public static void createSchema(Connection connection)throws SQLException {
        inTransaction(connection);
        try(var s=connection.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS story_choice_log("+
                "seq INTEGER PRIMARY KEY AUTOINCREMENT,id TEXT NOT NULL UNIQUE,"+
                "instance_id TEXT NOT NULL REFERENCES instances(id),source_event_id TEXT NOT NULL REFERENCES events(id),"+
                "story_id TEXT NOT NULL,story_version TEXT NOT NULL,node_id TEXT NOT NULL,"+
                "kind TEXT NOT NULL CHECK(kind IN ('BALLOT','RESOLUTION')),actor_key TEXT NOT NULL,"+
                "player_id TEXT,nation_id TEXT NOT NULL,scope_key TEXT NOT NULL,revision INTEGER NOT NULL CHECK(revision>0),"+
                "choice_id TEXT NOT NULL,chosen_at TEXT,committed_by TEXT,"+
                "provenance TEXT NOT NULL CHECK(provenance IN ('LIVE','LEGACY_INSTANCE')),"+
                "CHECK((kind='BALLOT' AND player_id IS NOT NULL) OR (kind='RESOLUTION' AND player_id IS NULL)),"+
                "CHECK((provenance='LIVE' AND chosen_at IS NOT NULL AND committed_by IS NOT NULL) OR (provenance='LEGACY_INSTANCE' AND chosen_at IS NULL AND committed_by IS NULL)),"+
                "UNIQUE(instance_id,node_id,kind,actor_key,revision))");
            s.execute("CREATE INDEX IF NOT EXISTS story_choice_player ON story_choice_log(player_id,story_id,story_version,node_id,kind,seq DESC)");
            s.execute("CREATE INDEX IF NOT EXISTS story_choice_scope ON story_choice_log(scope_key,story_id,story_version,node_id,kind,seq DESC)");
        }
    }

    /** Imports only surviving legacy snapshots. Original ballot timestamps and intermediate
     * revotes were not stored and are deliberately left unknown. Call inside the schema upgrade. */
    public static int migrateSnapshots(Connection connection)throws SQLException {
        inTransaction(connection);List<StoryInstance> snapshots=new ArrayList<>();
        try(var s=connection.createStatement();var r=s.executeQuery("SELECT id,story,scope,completed,json FROM instances")) {
            while(r.next()) {
                StoryInstance instance=SqliteStore.decode(r.getString("json"),StoryInstance.class);
                metadata(instance,r.getString("id"),r.getString("story"),r.getString("scope"),r.getInt("completed")!=0);
                snapshots.add(instance);
            }
        }
        snapshots.sort(Comparator.comparing(StoryInstance::updatedAt).thenComparing(StoryInstance::id));
        int inserted=0;
        for(StoryInstance instance:snapshots) {
            // A migration is atomic. Any prior row means this instance was already imported or
            // is live-owned; never manufacture a legacy copy of a subsequent live operation.
            try(var s=connection.prepareStatement("SELECT 1 FROM story_choice_log WHERE instance_id=? LIMIT 1")) {
                s.setString(1,instance.id());try(var r=s.executeQuery()){if(r.next())continue;}
            }
            for(var choice:new TreeMap<>(instance.choices()).entrySet()) {
                String key=choice.getKey();int slash=key.indexOf('/');String node=slash<0?key:key.substring(0,slash);
                validateChoice(instance,node,choice.getValue());String kind,actor;UUID player=null;String nation="";
                if(slash<0) {kind="RESOLUTION";actor=instance.scopeKey();}
                else {
                    if(slash==key.length()-1||key.indexOf('/',slash+1)>=0)throw new IllegalStateException("Malformed legacy personal choice key: "+key);
                    try{player=UUID.fromString(key.substring(slash+1));}catch(IllegalArgumentException error){throw new IllegalStateException("Malformed legacy choice actor",error);}
                    if(!instance.members().containsKey(player))throw new IllegalStateException("Legacy choice actor was not an instance member");
                    kind="BALLOT";actor="player:"+player;nation=instance.members().get(player);
                }
                String id=RuleEngine.stable("legacy-choice",instance.id(),node,kind,actor);
                insert(connection,id,instance,node,choice.getValue(),kind,actor,player,nation,1,null,null,"LEGACY_INSTANCE");inserted++;
            }
        }
        return inserted;
    }

    /** Record before saving the vote/moved instance. An identical final ballot is a no-op;
     * changing it appends a revision without erasing the previous committed evidence. */
    public static boolean recordBallot(Connection connection,StoryInstance before,PlayerContext actor,String node,String choice,Instant time)throws SQLException {
        live(connection,before,actor,node,choice,time);long revision=1;
        try(var s=connection.prepareStatement("SELECT choice_id,revision FROM story_choice_log WHERE instance_id=? AND node_id=? AND kind='BALLOT' AND actor_key=? ORDER BY seq DESC LIMIT 1")) {
            s.setString(1,before.id());s.setString(2,node);s.setString(3,"player:"+actor.playerId());
            try(var r=s.executeQuery()){if(r.next()){if(choice.equals(r.getString("choice_id")))return false;revision=Math.addExact(r.getLong("revision"),1);}}
        }
        String actorKey="player:"+actor.playerId(),id=RuleEngine.stable("choice-log",before.id(),node,"BALLOT",actorKey,Long.toString(revision));
        insert(connection,id,before,node,choice,"BALLOT",actorKey,actor.playerId(),actor.nationId(),revision,time,actor.playerId(),"LIVE");return true;
    }

    /** One immutable shared resolution per instance/node. The current actor's pending vote is
     * included when checking quorum; membership/nationality are checked against the stored before. */
    public static boolean recordResolution(Connection connection,StoryInstance before,PlayerContext actor,String node,String choice,Instant time)throws SQLException {
        live(connection,before,actor,node,choice,time);
        Map<UUID,String> votes=new HashMap<>(before.votes());votes.put(actor.playerId(),choice);
        for(var vote:votes.entrySet())if(!before.contains(vote.getKey())||!before.definition().nodes().get(node).choices().containsKey(vote.getValue()))
            throw new IllegalStateException("Resolution contains an invalid stored ballot");
        if(votes.values().stream().filter(choice::equals).count()<before.definition().nodes().get(node).quorum())throw new IllegalStateException("Resolution has not reached quorum");
        try(var s=connection.prepareStatement("SELECT choice_id FROM story_choice_log WHERE instance_id=? AND node_id=? AND kind='RESOLUTION' LIMIT 1")) {
            s.setString(1,before.id());s.setString(2,node);try(var r=s.executeQuery()){if(r.next()){if(choice.equals(r.getString(1)))return false;throw new IllegalStateException("Collective resolution cannot be rewritten");}}
        }
        String id=RuleEngine.stable("choice-log",before.id(),node,"RESOLUTION",before.scopeKey(),"1");
        insert(connection,id,before,node,choice,"RESOLUTION",before.scopeKey(),null,actor.nationId(),1,time,actor.playerId(),"LIVE");return true;
    }

    /** Personal ballots are attributed to the original voter even after a change of nationality.
     * World/national collective facts require the exact corresponding instance scope. */
    public static Map<String,String> read(Connection connection,UUID player,String nation,Collection<String> references)throws SQLException {
        inTransaction(connection);Objects.requireNonNull(references);Map<String,String> result=new LinkedHashMap<>();
        for(String fact:new TreeSet<>(references)) {
            var parsed=StoryDependencies.parse(fact);if(parsed.isEmpty())continue;var ref=parsed.get();
            boolean personal=ref.kind()==StoryDependencies.Kind.PLAYER_CHOICE;
            String subject;
            if(personal){if(player==null)continue;subject=player.toString();}
            else if(ref.kind()==StoryDependencies.Kind.WORLD_RESOLUTION)subject="world";
            else {if(nation==null||nation.isBlank())continue;subject="nation:"+nation;}
            String sql="SELECT l.choice_id FROM story_choice_log l JOIN instances i ON i.id=l.instance_id WHERE i.completed=1 AND l.story_id=? AND l.story_version=? AND l.node_id=? AND l.kind=? AND "+(personal?"l.player_id=?":"l.scope_key=?")+" ORDER BY l.seq DESC LIMIT 1";
            try(var s=connection.prepareStatement(sql)) {
                s.setString(1,ref.storyId());s.setString(2,ref.version());s.setString(3,ref.nodeId());s.setString(4,personal?"BALLOT":"RESOLUTION");s.setString(5,subject);
                try(var r=s.executeQuery()){if(r.next())result.put(fact,r.getString(1));}
            }
        }
        return Collections.unmodifiableMap(result);
    }

    /** Collects reserved past references through all/any/not, ignoring ordinary fact namespaces. */
    public static Set<String> requested(Condition... conditions) {
        Objects.requireNonNull(conditions);Set<String> result=new TreeSet<>();
        for(Condition condition:conditions)requested(condition,0,result);return Collections.unmodifiableSet(result);
    }
    private static void requested(Condition condition,int depth,Set<String> result) {
        if(condition==null)return;if(depth>16)throw new IllegalArgumentException("Condition too deep");
        if(StoryDependencies.parse(condition.fact()).isPresent())result.add(condition.fact());
        if(condition.all()!=null)for(Condition child:condition.all())requested(child,depth+1,result);
        if(condition.any()!=null)for(Condition child:condition.any())requested(child,depth+1,result);
        if(condition.not()!=null)requested(condition.not(),depth+1,result);
    }

    private static void live(Connection connection,StoryInstance before,PlayerContext actor,String node,String choice,Instant time)throws SQLException {
        inTransaction(connection);Objects.requireNonNull(before);Objects.requireNonNull(actor);Objects.requireNonNull(time);
        if(before.completed()||!Objects.equals(before.node(),node))throw new IllegalStateException("Choice is not for the active instance node");
        validateChoice(before,node,choice);
        if(!before.contains(actor.playerId()))throw new SecurityException("Choice actor was not an instance member");
        if(!Objects.equals(before.members().get(actor.playerId()),actor.nationId()))throw new SecurityException("Choice actor nationality changed");
        boolean scope=switch(before.definition().scope()) {
            case WORLD->before.scopeKey().equals("world");
            case NATION->!actor.nationId().isBlank()&&before.scopeKey().equals("nation:"+actor.nationId());
            case PLAYER->before.scopeKey().equals("player:"+actor.playerId());
        };
        if(!scope)throw new SecurityException("Choice actor does not belong to the instance scope");
        try(var s=connection.prepareStatement("SELECT id,story,scope,completed,json FROM instances WHERE id=?")) {
            s.setString(1,before.id());try(var r=s.executeQuery()) {
                if(!r.next())throw new IllegalStateException("Choice requires an already stored instance");
                StoryInstance stored=SqliteStore.decode(r.getString("json"),StoryInstance.class);
                metadata(stored,r.getString("id"),r.getString("story"),r.getString("scope"),r.getInt("completed")!=0);
                if(!before.equals(stored))throw new IllegalStateException("Choice before-instance is stale");
            }
        }
    }
    private static void metadata(StoryInstance instance,String id,String story,String scope,boolean completed) {
        if(!instance.id().equals(id)||!instance.definition().id().equals(story)||!instance.scopeKey().equals(scope)||instance.completed()!=completed)
            throw new IllegalStateException("Stored choice instance metadata is inconsistent");
        boolean valid=switch(instance.definition().scope()) {
            case WORLD->scope.equals("world");
            case NATION->scope.startsWith("nation:")&&scope.length()>7&&instance.members().values().stream().allMatch(nation->scope.equals("nation:"+nation));
            case PLAYER->instance.members().size()==1&&scope.equals("player:"+instance.members().keySet().iterator().next());
        };
        if(!valid)throw new IllegalStateException("Stored choice scope does not match its pinned definition");
    }
    private static void validateChoice(StoryInstance instance,String node,String choice) {
        if(node==null||choice==null||!node.matches("[A-Za-z0-9_.-]{1,80}")||!choice.matches("[A-Za-z0-9_.-]{1,80}"))throw new IllegalArgumentException("Invalid choice identity");
        StoryDefinition.Node definition=instance.definition().nodes().get(node);
        if(definition==null||!definition.choices().containsKey(choice))throw new IllegalArgumentException("Choice does not exist in the pinned definition");
    }
    private static void insert(Connection connection,String id,StoryInstance instance,String node,String choice,String kind,String actorKey,UUID player,String nation,long revision,Instant time,UUID committer,String provenance)throws SQLException {
        try(var s=connection.prepareStatement("INSERT INTO story_choice_log(id,instance_id,source_event_id,story_id,story_version,node_id,kind,actor_key,player_id,nation_id,scope_key,revision,choice_id,chosen_at,committed_by,provenance) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            s.setString(1,id);s.setString(2,instance.id());s.setString(3,instance.sourceEventId());s.setString(4,instance.definition().id());s.setString(5,instance.definition().version());s.setString(6,node);s.setString(7,kind);s.setString(8,actorKey);
            s.setString(9,player==null?null:player.toString());s.setString(10,nation);s.setString(11,instance.scopeKey());s.setLong(12,revision);s.setString(13,choice);s.setString(14,time==null?null:time.toString());s.setString(15,committer==null?null:committer.toString());s.setString(16,provenance);s.executeUpdate();
        }
    }
    private static void inTransaction(Connection connection)throws SQLException {
        if(connection==null||connection.getAutoCommit())throw new IllegalStateException("Choice ledger requires the enclosing Chronicle transaction");
    }
}
