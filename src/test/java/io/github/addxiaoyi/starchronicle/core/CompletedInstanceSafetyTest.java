package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.PlayerContext;
import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition.*;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Residual legacy inbox rows must never turn a completed historical instance into a new run.
 * The fixture deliberately has no scope receipt, a state accepted by the V2/V3 schema.
 * It is a regression fixture, not a claim that the archived real V2 server had this shape. */
class CompletedInstanceSafetyTest {
    @TempDir Path directory;
    private static final UUID A=UUID.fromString("00000000-0000-0000-0000-000000000001"),
            B=UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Instant THEN=Instant.parse("2026-11-19T10:11:12.123456789Z"),
            NOW=THEN.plusSeconds(86400);
    private static final Clock CLOCK=Clock.fixed(NOW,ZoneOffset.UTC);
    private static final PlayerContext CONTEXT=new PlayerContext(A,"river",Map.of(),Set.of());
    private static final String PERSONAL="past.player.choice.completed-safety/1/council",
            WORLD="past.resolution.world.completed-safety/1/council";
    /** Released V2 schema, created directly without opening or relabeling a current store. */
    private static final List<String> V2_DDL=List.of(
            "CREATE TABLE events(id TEXT PRIMARY KEY,type TEXT NOT NULL,time TEXT NOT NULL,hash TEXT NOT NULL,json TEXT NOT NULL)",
            "CREATE TABLE event_nations(event_id TEXT REFERENCES events(id),nation TEXT NOT NULL,PRIMARY KEY(event_id,nation))",
            "CREATE TABLE event_players(event_id TEXT REFERENCES events(id),player TEXT NOT NULL,PRIMARY KEY(event_id,player))",
            "CREATE INDEX history_time ON events(time DESC,id)",
            "CREATE INDEX nation_history ON event_nations(nation,event_id)",
            "CREATE INDEX player_history ON event_players(player,event_id)",
            "CREATE TABLE instances(id TEXT PRIMARY KEY,story TEXT NOT NULL,scope TEXT NOT NULL,completed INTEGER NOT NULL,updated TEXT NOT NULL,json TEXT NOT NULL)",
            "CREATE INDEX active_instances ON instances(story,scope,completed)",
            "CREATE TABLE outcomes(id TEXT PRIMARY KEY,status TEXT NOT NULL,json TEXT NOT NULL)",
            "CREATE INDEX outcome_delivery_status ON outcomes(status,id)",
            "CREATE TABLE facts(owner TEXT NOT NULL,key TEXT NOT NULL,value TEXT NOT NULL,PRIMARY KEY(owner,key))",
            "CREATE TABLE story_triggers(id TEXT PRIMARY KEY,trigger_key TEXT NOT NULL,event_id TEXT NOT NULL REFERENCES events(id),scope TEXT NOT NULL,story_json TEXT NOT NULL,checked INTEGER NOT NULL DEFAULT 0)",
            "CREATE INDEX story_trigger_scope ON story_triggers(scope,checked,id)",
            "CREATE INDEX story_trigger_event ON story_triggers(event_id,id)",
            "CREATE TABLE story_trigger_receipts(trigger_key TEXT NOT NULL,scope TEXT NOT NULL,PRIMARY KEY(trigger_key,scope))",
            "CREATE TABLE native_scene_journal(id TEXT PRIMARY KEY,player TEXT NOT NULL,status TEXT NOT NULL CHECK(status IN ('READY','RUNNING','UNKNOWN','COMPLETED')),revision INTEGER NOT NULL,json TEXT NOT NULL)",
            "CREATE INDEX native_scene_player_status ON native_scene_journal(player,status,id)");

    private Path database(){return directory.resolve("completed-safety.db");}
    private static StoryDefinition definition(String version){
        return new StoryDefinition("completed-safety",version,"Preserved council "+version,Scope.WORLD,
                List.of("LEGACY_EVENT"),null,"council",0,Map.of(
                "council",new Node("Council","legacy_scene","legacy_quest",1,
                        Map.of("support",new Choice("Support","end",null,null,List.of()),
                                "oppose",new Choice("Oppose","end",null,null,List.of())),
                        List.of(new Effect("new-entry-honor","honor","player","700",null)),false),
                "end",new Node("Recorded ending",null,null,1,Map.of(),
                        List.of(new Effect("end-honor","honor","player","15",null)),true)));
    }
    private static WorldEvent source(String id){return new WorldEvent(id,"LEGACY_EVENT",THEN,
            Set.of(A,B),Set.of("river","hill"),Map.of("receipt","original-77"),
            "CommittedAuthority","Original evidence; never synthesize another source timestamp");}
    private static String stableId(StoryDefinition rule,WorldEvent source){
        return RuleEngine.stable(rule.id(),rule.version(),"world",source.id());
    }
    private static String triggerKey(StoryDefinition rule,WorldEvent source){
        return RuleEngine.stable(source.id(),rule.id(),rule.version());
    }
    private static StoryInstance completed(String id,StoryDefinition rule,WorldEvent source){
        return new StoryInstance(id,rule,"world",source.id(),"end",Map.of(A,"river",B,"hill"),
                Map.of(),Map.of("council","support","council/"+A,"support","council/"+B,"oppose"),
                THEN.minusSeconds(60),THEN,true);
    }
    private static List<List<String>> rows(Connection c,String sql)throws Exception{
        List<List<String>> result=new ArrayList<>();
        try(var s=c.createStatement();var r=s.executeQuery(sql)){
            int width=r.getMetaData().getColumnCount();
            while(r.next()){List<String> row=new ArrayList<>();for(int p=1;p<=width;p++)row.add(r.getString(p));result.add(row);}
        }
        return result;
    }
    private static String rawInstance(SqliteStore store,String id){return store.transaction(c->{
        try(var s=c.prepareStatement("SELECT json FROM instances WHERE id=?")){
            s.setString(1,id);try(var r=s.executeQuery()){assertTrue(r.next());return r.getString(1);}
        }
    });}
    private static long count(SqliteStore store,String table){return store.transaction(c->{
        try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM "+table)){r.next();return r.getLong(1);}
    });}
    private static boolean receipted(SqliteStore store,String key){return store.transaction(c->{
        try(var s=c.prepareStatement("SELECT 1 FROM story_trigger_receipts WHERE trigger_key=? AND scope='world'")){
            s.setString(1,key);try(var r=s.executeQuery()){return r.next();}
        }
    });}
    private static Map<String,List<List<String>>> evidence(SqliteStore store){return store.transaction(c->{
        Map<String,List<List<String>>> result=new TreeMap<>();
        for(var table:Map.of("events","id","event_nations","event_id,nation","event_players","event_id,player",
                "instances","id","outcomes","id","facts","owner,key","story_choice_log","seq",
                "event_causes","event_id","native_scene_journal","id").entrySet())
            result.put(table.getKey(),rows(c,"SELECT * FROM "+table.getKey()+" ORDER BY "+table.getValue()));
        return result;
    });}
    private static void insertLegacyRows(Connection c,StoryDefinition rule,WorldEvent source,String id)throws Exception{
        String pretty=SqliteStore.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(completed(id,rule,source))+"\n";
        try(var s=c.prepareStatement("INSERT INTO instances VALUES(?,?,?,?,?,?)")){
            s.setString(1,id);s.setString(2,rule.id());s.setString(3,"world");s.setInt(4,1);
            s.setString(5,THEN.toString());s.setString(6,pretty);s.executeUpdate();
        }
        try(var s=c.prepareStatement("INSERT INTO facts VALUES(?,?,?)")){
            s.setString(1,A.toString());s.setString(2,"player.honor");s.setString(3,"15");s.executeUpdate();
        }
        try(var s=c.prepareStatement("INSERT INTO outcomes VALUES(?,?,?)")){
            s.setString(1,"legacy:committed-receipt");s.setString(2,"DONE");
            s.setString(3,"{\"evidence\":\"original opaque receipt payload\"}\n");s.executeUpdate();
        }
    }
    private void legacyResidual(StoryDefinition rule,WorldEvent source,String id)throws Exception{
        try(var store=new SqliteStore(database())){
            var engine=new RuleEngine(store,List.of(rule),CLOCK);
            engine.accept(source,List.of()); // Canonical event and the pinned residual inbox, no online audience.
            store.transaction(c->{
                insertLegacyRows(c,rule,source,id);
                ChoiceLedger.migrateSnapshots(c);
                return null;
            });
            assertFalse(receipted(store,triggerKey(rule,source)));
            assertEquals(1,engine.pendingTriggerCount());
        }
    }
    private void literalV2Residual(StoryDefinition pinned,StoryDefinition pending,WorldEvent source,String id)throws Exception{
        Class.forName("org.sqlite.JDBC");
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+database().toAbsolutePath());var statement=c.createStatement()){
            statement.execute("PRAGMA foreign_keys=ON");c.setAutoCommit(false);
            for(String ddl:V2_DDL)statement.execute(ddl);
            Map<String,Object> canonical=new TreeMap<>();
            canonical.put("id",source.id());canonical.put("type",source.type());canonical.put("time",source.occurredAt().toString());
            canonical.put("players",source.participants().stream().map(UUID::toString).sorted().toList());
            canonical.put("nations",new TreeSet<>(source.nations()));canonical.put("facts",new TreeMap<>(source.facts()));
            canonical.put("source",source.source());canonical.put("evidence",source.evidence());
            try(var s=c.prepareStatement("INSERT INTO events VALUES(?,?,?,?,?)")){
                s.setString(1,source.id());s.setString(2,source.type());s.setString(3,THEN.toString());
                s.setString(4,RuleEngine.stable(SqliteStore.json(canonical)));s.setString(5,SqliteStore.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(source)+"\n");s.executeUpdate();
            }
            try(var s=c.prepareStatement("INSERT INTO event_players VALUES(?,?)")){
                for(UUID player:source.participants()){s.setString(1,source.id());s.setString(2,player.toString());s.executeUpdate();}
            }
            try(var s=c.prepareStatement("INSERT INTO event_nations VALUES(?,?)")){
                for(String nation:source.nations()){s.setString(1,source.id());s.setString(2,nation);s.executeUpdate();}
            }
            insertLegacyRows(c,pinned,source,id);
            String key=triggerKey(pending,source);
            try(var s=c.prepareStatement("INSERT INTO story_triggers VALUES(?,?,?,?,?,?)")){
                s.setString(1,RuleEngine.stable(key,"world"));s.setString(2,key);s.setString(3,source.id());
                s.setString(4,"world");s.setString(5,SqliteStore.json(pending));s.setInt(6,17);s.executeUpdate();
            }
            statement.execute("PRAGMA user_version=2");c.commit();
        }
    }

    @ParameterizedTest(name="completed stable ID is preserved when duplicateAccept={0}")
    @ValueSource(booleans={false,true})
    void residualInboxCannotOverwriteCompletedStableInstanceThroughResumeOrDuplicateAccept(boolean duplicateAccept)throws Exception{
        StoryDefinition rule=definition("1");WorldEvent source=source("legacy:completed-source");
        String id=stableId(rule,source);legacyResidual(rule,source,id);
        Map<String,List<List<String>>> before;String originalJson;
        try(var store=new SqliteStore(database())){
            var engine=new RuleEngine(store,List.of(rule),CLOCK);before=evidence(store);originalJson=rawInstance(store,id);
            assertEquals(Map.of(PERSONAL,"support",WORLD,"support"),engine.recollections(CONTEXT,Set.of(PERSONAL,WORLD)));
            if(duplicateAccept)assertTrue(engine.accept(source,List.of(CONTEXT)).duplicate());
            else engine.resumeTriggers(List.of(CONTEXT));
            assertEquals(originalJson,rawInstance(store,id),"Completed JSON including pinned choices and timestamps is immutable");
            assertEquals(before,evidence(store),"History, completed rows, choices, facts and outcome payloads must be byte-preserved");
            assertEquals(1,count(store,"instances"));assertTrue(engine.activeInstances().isEmpty());
            assertEquals(Map.of(PERSONAL,"support",WORLD,"support"),engine.recollections(CONTEXT,Set.of(PERSONAL,WORLD)));
            assertEquals("15",engine.facts(A).get("player.honor"));
        }
        try(var store=new SqliteStore(database())){
            var engine=new RuleEngine(store,List.of(rule),CLOCK);
            for(int attempt=0;attempt<3;attempt++){engine.resumeTriggers(List.of(CONTEXT));engine.accept(source,List.of(CONTEXT));}
            assertEquals(originalJson,rawInstance(store,id));assertEquals(before,evidence(store));
        }
    }

    @Test void residualInboxFindsCompletedSemanticTupleWithNonstandardLegacyIdInsteadOfCreatingStableDuplicate()throws Exception{
        StoryDefinition rule=definition("1");WorldEvent source=source("legacy:nonstandard-source");
        String id="legacy:nonstandard-completed";legacyResidual(rule,source,id);
        try(var store=new SqliteStore(database())){
            var engine=new RuleEngine(store,List.of(rule),CLOCK);var before=evidence(store);
            engine.resumeTriggers(List.of(CONTEXT));
            assertEquals(1,count(store,"instances"),"Semantic source/story/version/scope identity prevents a second ID");
            assertEquals(before,evidence(store));assertTrue(engine.activeInstances().isEmpty());
            assertEquals(id,engine.instances(A).getFirst().id());
        }
    }

    @Test void absentResidualInboxIsNotRecreatedByOrdinaryAcceptOrResume()throws Exception{
        StoryDefinition rule=definition("1");WorldEvent source=source("legacy:no-residual-source");
        String id=stableId(rule,source);legacyResidual(rule,source,id);
        try(var store=new SqliteStore(database())){
            var engine=new RuleEngine(store,List.of(rule),CLOCK);
            store.transaction(c->{try(var s=c.createStatement()){s.executeUpdate("DELETE FROM story_triggers");}return null;});
            var before=evidence(store);
            for(int attempt=0;attempt<3;attempt++){
                assertTrue(engine.accept(source,List.of(CONTEXT)).duplicate());
                engine.resumeTriggers(List.of(CONTEXT));
            }
            assertEquals(0,engine.pendingTriggerCount(),"Normal retries must not reinterpret old history against current definitions");
            assertFalse(receipted(store,triggerKey(rule,source)));assertEquals(before,evidence(store));
        }
    }

    @Test void sameVersionResidualDefinitionWithNewEffectIdsCannotReenterCompletedInstance()throws Exception{
        StoryDefinition original=definition("1");WorldEvent source=source("legacy:changed-definition-source");
        String id=stableId(original,source);
        Map<String,Node> nodes=new LinkedHashMap<>(original.nodes());Node start=nodes.get("council");
        nodes.put("council",new Node("Retrofitted start",start.script(),start.quest(),start.quorum(),start.choices(),
                List.of(new Effect("retrofitted-honor","honor","player","900",null),
                        new Effect("retrofitted-memory","memory","legacy-city","rewritten",null)),false));
        StoryDefinition changed=new StoryDefinition(original.id(),original.version(),"Edited without a version bump",
                original.scope(),original.triggers(),original.condition(),original.start(),original.cooldownSeconds(),nodes);
        // Inject the divergent historical snapshots before any RuleEngine/catalog initialization.
        // This does not ask current catalog reload to accept an illegal same-version edit.
        literalV2Residual(original,changed,source,id);
        try(var store=new SqliteStore(database())){
            var engine=new RuleEngine(store,List.of(),CLOCK);var before=evidence(store);
            var catalogBefore=store.transaction(c->rows(c,"SELECT * FROM story_definitions ORDER BY story,version,fingerprint"));
            assertThrows(IllegalArgumentException.class,()->new RuleEngine(store,List.of(original),CLOCK),
                    "A historical same-version conflict must reject executable publication instead of selecting a preferred graph");
            assertThrows(IllegalArgumentException.class,()->new RuleEngine(store,List.of(changed),CLOCK));
            assertEquals(catalogBefore,store.transaction(c->rows(c,"SELECT * FROM story_definitions ORDER BY story,version,fingerprint")));
            assertEquals(before,evidence(store),"Rejected publication must not rewrite either historical variant");
            engine.resumeTriggers(List.of(CONTEXT));
            assertEquals(before,evidence(store),"New effect IDs cannot make a completed natural identity execute again");
            assertEquals("15",engine.facts(A).get("player.honor"));assertFalse(engine.worldFacts().containsKey("region.legacy-city"));
            assertEquals(original,engine.instances(A).getFirst().definition());
        }
    }

    @ParameterizedTest(name="literal V2 constructor then duplicateAccept={0}")
    @ValueSource(booleans={false,true})
    void productionConstructorUpgradeDoesNotExposeStableCompletedInstanceToResidualReplay(boolean duplicateAccept)throws Exception{
        StoryDefinition rule=definition("1");WorldEvent source=source("literal-v2:completed-source");
        String id=stableId(rule,source);literalV2Residual(rule,rule,source,id);
        Map<String,List<List<String>>> before;
        try(var store=new SqliteStore(database())){
            var engine=new RuleEngine(store,List.of(rule),CLOCK);before=evidence(store);
            assertEquals(3,count(store,"story_choice_log"));assertEquals(0,count(store,"event_causes"));
            assertFalse(receipted(store,triggerKey(rule,source)));
            assertTrue(store.transaction(c->rows(c,"SELECT chosen_at,committed_by,provenance FROM story_choice_log ORDER BY seq"))
                    .stream().allMatch(row->row.get(0)==null&&row.get(1)==null&&row.get(2).equals("LEGACY_INSTANCE")));
            if(duplicateAccept)assertTrue(engine.accept(source,List.of(CONTEXT)).duplicate());else engine.resumeTriggers(List.of(CONTEXT));
            assertEquals(before,evidence(store));assertTrue(engine.instances(A).getFirst().completed());
            assertEquals(Map.of(PERSONAL,"support",WORLD,"support"),engine.recollections(CONTEXT,Set.of(PERSONAL,WORLD)));
        }
        try(var store=new SqliteStore(database())){
            var engine=new RuleEngine(store,List.of(rule),CLOCK);engine.resumeTriggers(List.of(CONTEXT));engine.accept(source,List.of(CONTEXT));
            assertEquals(before,evidence(store));assertEquals(0,count(store,"event_causes"),"Legacy ancestry is not invented during replay protection");
        }
    }

    @Test void concurrentResumeAndDuplicateAcceptAcrossReopenPreserveTheSingleCompletedRun()throws Exception{
        StoryDefinition rule=definition("1");WorldEvent source=source("legacy:concurrent-source");
        String id=stableId(rule,source);legacyResidual(rule,source,id);
        Map<String,List<List<String>>> before;
        try(var store=new SqliteStore(database())){
            var engine=new RuleEngine(store,List.of(rule),CLOCK);before=evidence(store);
            try(var pool=Executors.newFixedThreadPool(4)){
                List<Callable<Void>> calls=new ArrayList<>();
                for(int index=0;index<16;index++){
                    final boolean accept=index%2==0;
                    calls.add(()->{if(accept)engine.accept(source,List.of(CONTEXT));else engine.resumeTriggers(List.of(CONTEXT));return null;});
                }
                for(var future:pool.invokeAll(calls))future.get(10,TimeUnit.SECONDS);
            }
            assertEquals(before,evidence(store));assertEquals(1,count(store,"instances"));assertTrue(engine.activeInstances().isEmpty());
        }
        try(var store=new SqliteStore(database())){
            var engine=new RuleEngine(store,List.of(rule),CLOCK);
            engine.accept(source,List.of(CONTEXT));engine.resumeTriggers(List.of(CONTEXT));
            assertEquals(before,evidence(store));assertTrue(engine.instances(A).getFirst().completed());
        }
    }

    @Test void anotherActiveVersionDoesNotConsumeTheNewVersionsPendingScopeReceipt()throws Exception{
        StoryDefinition oldRule=definition("1"),newRule=definition("2");
        WorldEvent first=source("active:version-one"),later=source("pending:version-two");
        try(var store=new SqliteStore(database())){
            var engine=new RuleEngine(store,List.of(oldRule),CLOCK);
            String activeId=engine.accept(first,List.of(CONTEXT)).instanceIds().getFirst();
            String activeJson=rawInstance(store,activeId);
            engine.reload(List.of(newRule));engine.accept(later,List.of());
            engine.resumeTriggers(List.of(CONTEXT));
            assertFalse(receipted(store,triggerKey(newRule,later)),"Old pinned graph must not consume the new version's request");
            assertEquals(1,engine.pendingTriggerCount());assertEquals(activeJson,rawInstance(store,activeId));
            assertEquals("1",engine.instances(A).getFirst().definition().version());
            engine.choose(activeId,A,"council","support",CONTEXT);
            engine.resumeTriggers(List.of(CONTEXT));
            var fresh=engine.instances(A).stream().filter(i->i.definition().version().equals("2")).findFirst().orElseThrow();
            assertEquals(later.id(),fresh.sourceEventId());assertFalse(fresh.completed());
            assertTrue(receipted(store,triggerKey(newRule,later)));assertEquals(0,engine.pendingTriggerCount());
        }
    }
}
