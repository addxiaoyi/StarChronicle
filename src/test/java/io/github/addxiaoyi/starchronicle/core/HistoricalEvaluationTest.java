package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.config.Condition;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition.*;
import io.github.addxiaoyi.starchronicle.history.EventCausality;
import io.github.addxiaoyi.starchronicle.npc.NpcCatalog;
import io.github.addxiaoyi.starchronicle.npc.NpcLedger;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static io.github.addxiaoyi.starchronicle.api.HistoricalEvaluationReport.Status.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit historical registration uses one canonical source and one enabled exact version.
 * QUEUED is durable eligibility work, never proof that a past actor received a new reward. */
class HistoricalEvaluationTest {
    @TempDir Path directory;
    private static final UUID A=UUID.fromString("00000000-0000-0000-0000-000000000061"),
            B=UUID.fromString("00000000-0000-0000-0000-000000000062"),
            OUTSIDER=UUID.fromString("00000000-0000-0000-0000-000000000063");
    private static final Instant SOURCE_TIME=Instant.parse("2026-11-18T10:11:12.123456789Z");
    private final MutableClock clock=new MutableClock();
    private Path database(){return directory.resolve("historical-evaluation.db");}
    private static PlayerContext context(UUID player,String nation){return new PlayerContext(player,nation,Map.of(),Set.of());}
    private static PlayerContext a(){return context(A,"river");}
    private static WorldEvent source(String id){return source(id,"ROOT",Set.of(A),Set.of("river"),Map.of("record","original \"quoted\" evidence"));}
    private static WorldEvent source(String id,String type,Set<UUID> actors,Set<String> nations,Map<String,String> facts){
        return new WorldEvent(id,type,SOURCE_TIME,actors,nations,facts,"CommittedHistoricalAuthority",
                "Original receipt="+id+"; preserve actors, nations, timestamp and exact evidence");
    }
    private static StoryDefinition rule(String id,String version){return rule(id,version,"ROOT",Scope.WORLD,null,null,0,true);}
    private static StoryDefinition rule(String id,String version,String trigger,Scope scope,Condition condition,String permission,long cooldown,boolean terminal){
        var effects=List.of(new Effect("honor","honor","player","7",permission),
                new Effect("history","history","RESULT_"+id,"A newly committed story result",null));
        Map<String,Node> nodes=terminal?Map.of("end",new Node("New historical perspective",null,null,1,Map.of(),effects,true)):
                Map.of("vote",new Node("Present-day choice",null,null,1,Map.of("yes",new Choice("Yes","end",null,null,List.of())),List.of(),false),
                        "end",new Node("New ending",null,null,1,Map.of(),effects,true));
        return new StoryDefinition(id,version,id+" version "+version,scope,List.of(trigger),condition,terminal?"end":"vote",cooldown,nodes);
    }
    private static long count(SqliteStore store,String table){return store.transaction(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM "+table)){r.next();return r.getLong(1);}});}
    private static List<List<String>> rows(Connection c,String sql)throws Exception{
        List<List<String>> result=new ArrayList<>();try(var s=c.createStatement();var r=s.executeQuery(sql)){
            int width=r.getMetaData().getColumnCount();while(r.next()){List<String> row=new ArrayList<>();for(int i=1;i<=width;i++)row.add(r.getString(i));result.add(row);}
        }
        result.sort(Comparator.comparing(Object::toString));return result;
    }
    private static Map<String,List<List<String>>> snapshot(SqliteStore store,String...excluded){return store.transaction(c->{
        Set<String> ignored=Set.of(excluded);Map<String,List<List<String>>> result=new TreeMap<>();List<String> tables=new ArrayList<>();
        try(var s=c.createStatement();var r=s.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")){while(r.next())tables.add(r.getString(1));}
        for(String table:tables)if(!ignored.contains(table))result.put(table,rows(c,"SELECT * FROM \""+table+"\""));return result;
    });}
    private static List<String> sourceRow(SqliteStore store,String id){return store.transaction(c->{try(var s=c.prepareStatement("SELECT id,type,time,hash,json FROM events WHERE id=?")){
        s.setString(1,id);try(var r=s.executeQuery()){assertTrue(r.next());return List.of(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5));}
    }});}
    private static String rawInstance(SqliteStore store,String id){return store.transaction(c->{try(var s=c.prepareStatement("SELECT json FROM instances WHERE id=?")){
        s.setString(1,id);try(var r=s.executeQuery()){assertTrue(r.next());return r.getString(1);}
    }});}
    private static String key(WorldEvent source,StoryDefinition rule){return RuleEngine.stable(source.id(),rule.id(),rule.version());}
    private static boolean receipted(SqliteStore store,WorldEvent source,StoryDefinition rule,String scope){return store.transaction(c->{
        try(var s=c.prepareStatement("SELECT 1 FROM story_trigger_receipts WHERE trigger_key=? AND scope=?")){
            s.setString(1,key(source,rule));s.setString(2,scope);try(var r=s.executeQuery()){return r.next();}
        }
    });}
    private static StoryInstance instance(RuleEngine engine,String story){return engine.instances(A).stream().filter(i->i.definition().id().equals(story)).findFirst().orElseThrow();}
    private static void sql(SqliteStore store,String sql){store.transaction(c->{try(var s=c.createStatement()){s.execute(sql);}return null;});}

    @Test void installedRulesRequireExplicitRegistrationAndOnlyTheNamedPinnedStoryExecutesLater(){
        WorldEvent historical=source("history:before-content");StoryDefinition selected=rule("selected","1"),other=rule("unselected","1");
        try(var store=new SqliteStore(database())){
            var engine=new RuleEngine(store,List.of(),clock);engine.accept(historical,List.of(a()));
            List<String> canonical=sourceRow(store,historical.id());
            engine.reload(List.of(selected,other));
            assertTrue(engine.accept(historical,List.of(a())).duplicate());engine.resumeTriggers(List.of(a()));
            assertEquals(0,engine.pendingTriggerCount());assertTrue(engine.instances(A).isEmpty());
            var before=snapshot(store,"story_triggers");
            var report=engine.evaluateHistorical(historical.id(),selected.id(),selected.version());
            assertEquals(QUEUED,report.status());assertEquals(1,report.newSeeds());assertEquals(0,report.matchingInstances());assertEquals(0,report.completedInstances());
            assertEquals(before,snapshot(store,"story_triggers"),"Registration must not execute effects or rewrite canonical history");
            assertEquals(List.of(selected),engine.pendingDefinitions());assertTrue(engine.instances(A).isEmpty());
            engine.reload(List.of()); // Retirement does not replace the already pinned registration.
            assertThrows(IllegalArgumentException.class,()->engine.evaluateHistorical(historical.id(),selected.id(),selected.version()));
            engine.resumeTriggers(List.of(a()));
            assertEquals(1,engine.instances(A).size());assertEquals(selected,instance(engine,"selected").definition());
            assertEquals("7",engine.facts(A).get("player.honor"));assertEquals(canonical,sourceRow(store,historical.id()));
            WorldEvent result=engine.history(null,null,0,10).stream().filter(e->e.type().equals("RESULT_selected")).findFirst().orElseThrow();
            assertEquals(new EventCausality.Trace(historical.id(),historical.id(),1,List.of("selected")),engine.causality(result.id()));
            engine.reload(List.of(selected,other));var done=snapshot(store,"story_triggers");
            var repeated=engine.evaluateHistorical(historical.id(),selected.id(),"1");
            assertEquals(ALREADY_REGISTERED,repeated.status());assertEquals(0,repeated.newSeeds());assertEquals(1,repeated.matchingInstances());assertEquals(1,repeated.completedInstances());
            for(int i=0;i<3;i++)engine.resumeTriggers(List.of(a()));
            assertEquals(done,snapshot(store,"story_triggers"));assertEquals(1,engine.instances(A).size());
        }
    }

    @Test void missingSourceDisabledStoryWrongExactVersionAndTypeAreRejectedWithoutWrites(){
        WorldEvent historical=source("history:validation");StoryDefinition valid=rule("valid","1"),wrongType=rule("wrong-type","1","OTHER",Scope.WORLD,null,null,0,true);
        try(var store=new SqliteStore(database())){
            var engine=new RuleEngine(store,List.of(),clock);engine.accept(historical,List.of());engine.reload(List.of(valid,wrongType));
            var before=snapshot(store);
            assertThrows(IllegalArgumentException.class,()->engine.evaluateHistorical("missing-source",valid.id(),"1"));
            assertThrows(IllegalArgumentException.class,()->engine.evaluateHistorical(historical.id(),"disabled","1"));
            assertThrows(IllegalArgumentException.class,()->engine.evaluateHistorical(historical.id(),valid.id(),"2"));
            assertThrows(IllegalArgumentException.class,()->engine.evaluateHistorical(historical.id(),wrongType.id(),"1"));
            assertThrows(NullPointerException.class,()->engine.evaluateHistorical(null,valid.id(),"1"));
            assertEquals(before,snapshot(store));
        }
    }

    @ParameterizedTest @EnumSource(value=Scope.class,names={"PLAYER","NATION"})
    void anonymousNonGlobalSourceDoesNotInventRecipients(Scope scope){
        WorldEvent anonymous=source("history:anonymous","ROOT",Set.of(),Set.of(),Map.of());
        StoryDefinition rule=rule("anonymous-"+scope.name().toLowerCase(Locale.ROOT),"1","ROOT",scope,null,null,0,true);
        try(var store=new SqliteStore(database())){
            var engine=new RuleEngine(store,List.of(),clock);engine.accept(anonymous,List.of());engine.reload(List.of(rule));
            var before=snapshot(store);var report=engine.evaluateHistorical(anonymous.id(),rule.id(),"1");
            assertEquals(NO_SOURCE_AUDIENCE,report.status());assertEquals(0,report.newSeeds());assertEquals(before,snapshot(store));
            engine.resumeTriggers(List.of(a(),context(B,"hill")));
            assertTrue(engine.instances(A).isEmpty());assertTrue(engine.instances(B).isEmpty());assertTrue(engine.facts(A).isEmpty());
        }
    }

    @Test void offlineConditionsAndEffectPermissionWaitForFreshContextWithoutConsumingTheRegistration(){
        Condition score=new Condition(null,null,null,"player.score","gte","10");
        StoryDefinition rule=rule("fresh-eligibility","1","ROOT",Scope.WORLD,score,"story.allowed",0,true);WorldEvent historical=source("history:fresh-context");
        try(var store=new SqliteStore(database())){
            var engine=new RuleEngine(store,List.of(),clock);engine.accept(historical,List.of());engine.reload(List.of(rule));
            assertEquals(QUEUED,engine.evaluateHistorical(historical.id(),rule.id(),"1").status());
            engine.resumeTriggers(List.of());
            engine.resumeTriggers(List.of(new PlayerContext(A,"river",Map.of("player.score","9"),Set.of("story.allowed"))));
            engine.resumeTriggers(List.of(new PlayerContext(A,"river",Map.of("player.score","10"),Set.of())));
            assertEquals(1,engine.pendingTriggerCount());assertFalse(receipted(store,historical,rule,"world"));
            assertTrue(engine.instances(A).isEmpty());assertFalse(engine.facts(A).containsKey("player.honor"));
            engine.resumeTriggers(List.of(new PlayerContext(A,"river",Map.of("player.score","10"),Set.of("story.allowed"))));
            assertEquals(0,engine.pendingTriggerCount());assertTrue(receipted(store,historical,rule,"world"));assertEquals("7",engine.facts(A).get("player.honor"));
        }
    }

    @Test void historicalRegistrationHonorsCurrentCooldownAndKeepsWaitingWork(){
        StoryDefinition rule=rule("cooldown-evaluation","1","ROOT",Scope.WORLD,null,null,3600,true);
        WorldEvent first=source("history:cooldown-first"),second=source("history:cooldown-second");
        try(var store=new SqliteStore(database())){
            var engine=new RuleEngine(store,List.of(),clock);engine.accept(first,List.of());engine.accept(second,List.of());engine.reload(List.of(rule));
            engine.evaluateHistorical(first.id(),rule.id(),"1");engine.resumeTriggers(List.of(a()));assertEquals("7",engine.facts(A).get("player.honor"));
            engine.evaluateHistorical(second.id(),rule.id(),"1");engine.resumeTriggers(List.of(a()));
            assertEquals(1,engine.pendingTriggerCount());assertEquals(1,engine.instances(A).size());assertFalse(receipted(store,second,rule,"world"));
            clock.advance(Duration.ofHours(2));engine.resumeTriggers(List.of(a()));
            assertEquals(0,engine.pendingTriggerCount());assertEquals(2,engine.instances(A).size());assertEquals("14",engine.facts(A).get("player.honor"));
        }
    }

    @Test void concurrentRegistrationAndReopenUseOneNaturalKeyThenOneExecution()throws Exception{
        StoryDefinition rule=rule("concurrent-evaluation","1");WorldEvent historical=source("history:concurrent");List<String> canonical;
        try(var store=new SqliteStore(database())){
            var engine=new RuleEngine(store,List.of(),clock);engine.accept(historical,List.of());canonical=sourceRow(store,historical.id());engine.reload(List.of(rule));
            List<HistoricalEvaluationReport> reports=new ArrayList<>();
            try(var pool=Executors.newFixedThreadPool(4)){
                List<Callable<HistoricalEvaluationReport>> calls=new ArrayList<>();for(int i=0;i<16;i++)calls.add(()->engine.evaluateHistorical(historical.id(),rule.id(),"1"));
                for(var future:pool.invokeAll(calls))reports.add(future.get(10,TimeUnit.SECONDS));
            }
            assertEquals(1,reports.stream().filter(r->r.status()==QUEUED).count());assertEquals(1,reports.stream().mapToInt(HistoricalEvaluationReport::newSeeds).sum());
            assertEquals(1,engine.pendingTriggerCount());assertTrue(engine.instances(A).isEmpty());assertEquals(canonical,sourceRow(store,historical.id()));
        }
        try(var store=new SqliteStore(database())){
            var engine=new RuleEngine(store,List.of(rule),clock);
            assertEquals(ALREADY_REGISTERED,engine.evaluateHistorical(historical.id(),rule.id(),"1").status());
            engine.resumeTriggers(List.of(a()));var done=snapshot(store,"story_triggers");
            for(int i=0;i<8;i++){assertEquals(ALREADY_REGISTERED,engine.evaluateHistorical(historical.id(),rule.id(),"1").status());engine.resumeTriggers(List.of(a()));}
            assertEquals(done,snapshot(store,"story_triggers"));assertEquals(1,engine.instances(A).size());assertEquals("7",engine.facts(A).get("player.honor"));assertEquals(canonical,sourceRow(store,historical.id()));
        }
    }

    @Test void completedInstanceWithoutScopeReceiptIsReportedAndProtectedDuringExplicitRegistration(){
        StoryDefinition rule=rule("completed-evaluation","1");WorldEvent historical=source("history:completed");
        try(var store=new SqliteStore(database())){
            var engine=new RuleEngine(store,List.of(rule),clock);String id=engine.accept(historical,List.of(a())).instanceIds().getFirst();
            sql(store,"DELETE FROM story_trigger_receipts");String oldJson=rawInstance(store,id);var before=snapshot(store,"story_triggers","story_trigger_receipts");
            var report=engine.evaluateHistorical(historical.id(),rule.id(),"1");
            assertEquals(QUEUED,report.status());assertEquals(1,report.matchingInstances());assertEquals(1,report.completedInstances());
            assertEquals(before,snapshot(store,"story_triggers","story_trigger_receipts"));engine.resumeTriggers(List.of(a()));
            assertEquals(oldJson,rawInstance(store,id));assertEquals(before,snapshot(store,"story_triggers","story_trigger_receipts"));
            assertTrue(receipted(store,historical,rule,"world"));assertEquals(1,engine.instances(A).size());assertEquals("7",engine.facts(A).get("player.honor"));
        }
    }

    @Test void explicitNewVersionWaitsAcrossRestartForTheOldPinnedRunToComplete(){
        StoryDefinition oldRule=rule("version-wait","1","ROOT",Scope.WORLD,null,null,0,false),newRule=rule("version-wait","2","ROOT",Scope.WORLD,null,null,0,false);
        WorldEvent first=source("history:old-active"),historical=source("history:new-version");String oldId,oldJson;
        try(var store=new SqliteStore(database())){
            var engine=new RuleEngine(store,List.of(oldRule),clock);oldId=engine.accept(first,List.of(a())).instanceIds().getFirst();oldJson=rawInstance(store,oldId);
            engine.reload(List.of());engine.accept(historical,List.of());engine.reload(List.of(newRule));
            assertEquals(QUEUED,engine.evaluateHistorical(historical.id(),newRule.id(),"2").status());engine.resumeTriggers(List.of(a()));
            assertFalse(receipted(store,historical,newRule,"world"));assertEquals(1,engine.pendingTriggerCount());assertEquals(oldJson,rawInstance(store,oldId));
        }
        try(var store=new SqliteStore(database())){
            var engine=new RuleEngine(store,List.of(newRule),clock);engine.resumeTriggers(List.of(a()));
            assertFalse(receipted(store,historical,newRule,"world"));assertEquals(oldJson,rawInstance(store,oldId));
            engine.choose(oldId,A,"vote","yes",a());engine.resumeTriggers(List.of(a()));
            StoryInstance fresh=engine.instances(A).stream().filter(i->i.definition().version().equals("2")).findFirst().orElseThrow();
            assertEquals(historical.id(),fresh.sourceEventId());assertFalse(fresh.completed());assertTrue(receipted(store,historical,newRule,"world"));assertEquals(0,engine.pendingTriggerCount());
        }
    }

    @Test void partialMultiSeedQueueFailureRollsBackEveryNewRowAndCanRetryOnce(){
        StoryDefinition rule=rule("atomic-evaluation","1","ROOT",Scope.PLAYER,null,null,0,true);
        WorldEvent historical=source("history:atomic","ROOT",Set.of(A,B),Set.of("river","hill"),Map.of());
        try(var store=new SqliteStore(database())){
            var engine=new RuleEngine(store,List.of(),clock);engine.accept(historical,List.of());engine.reload(List.of(rule));
            sql(store,"CREATE TRIGGER reject_evaluation_seed BEFORE INSERT ON story_triggers WHEN NEW.scope='player:"+B+"' BEGIN SELECT RAISE(ABORT,'simulated queue disk failure'); END");
            var before=snapshot(store);assertThrows(IllegalStateException.class,()->engine.evaluateHistorical(historical.id(),rule.id(),"1"));
            assertEquals(before,snapshot(store));assertEquals(0,engine.pendingTriggerCount());
            sql(store,"DROP TRIGGER reject_evaluation_seed");var report=engine.evaluateHistorical(historical.id(),rule.id(),"1");
            assertEquals(QUEUED,report.status());assertEquals(4,report.newSeeds());
            engine.resumeTriggers(List.of(a()));engine.resumeTriggers(List.of(context(B,"hill")));
            assertEquals("7",engine.facts(A).get("player.honor"));assertEquals("7",engine.facts(B).get("player.honor"));assertEquals(2,count(store,"instances"));
        }
    }

    @Test void repeatedStoryInCanonicalCausalPathIsBlockedAcrossVersionsWithoutResubmittingHistory(){
        StoryDefinition original=rule("causal-repeat","1");
        // The new current graph is statically acyclic. Only the saved causal path
        // reveals that this same story ID already produced the historical source.
        StoryDefinition later=new StoryDefinition("causal-repeat","2","Later version",Scope.WORLD,List.of("RESULT_causal-repeat"),null,"end",0,
                Map.of("end",new Node("Later ending",null,null,1,Map.of(),List.of(new Effect("later-history","history","DIFFERENT_LATER_RESULT","Must remain unexecuted",null)),true)));
        WorldEvent root=source("history:causal-root");
        try(var store=new SqliteStore(database())){
            var engine=new RuleEngine(store,List.of(original),clock);engine.accept(root,List.of(a()));
            WorldEvent result=engine.history(null,null,0,10).stream().filter(e->e.type().equals("RESULT_causal-repeat")).findFirst().orElseThrow();
            engine.reload(List.of(later));var before=snapshot(store,"story_trigger_blocks");
            for(int i=0;i<3;i++){
                var report=engine.evaluateHistorical(result.id(),later.id(),"2");assertEquals(BLOCKED_CAUSAL,report.status());assertEquals(0,report.newSeeds());assertEquals("REPEATED_STORY_IN_CAUSAL_CHAIN",report.message());
            }
            assertEquals(before,snapshot(store,"story_trigger_blocks"));assertEquals(1,engine.triggerBlocks(10).size());assertEquals(0,engine.pendingTriggerCount());
            assertEquals(new EventCausality.Trace(root.id(),root.id(),1,List.of(original.id())),engine.causality(result.id()));assertEquals("7",engine.facts(A).get("player.honor"));
        }
    }

    @Test void explicitEvaluationAtCanonicalDepthLimitKeepsTheTrueHistoryAndWritesOneDiagnostic(){
        List<StoryDefinition> chain=new ArrayList<>();
        for(int i=0;i<EventCausality.MAX_DEPTH;i++){
            String id="depth-evaluation-"+i;
            chain.add(new StoryDefinition(id,"1",id,Scope.WORLD,List.of("CHAIN_"+i),null,"end",0,
                    Map.of("end",new Node("True chain event",null,null,1,Map.of(),
                            List.of(new Effect("history","history","CHAIN_"+(i+1),"Committed chain link",null)),true))));
        }
        try(var store=new SqliteStore(database())){
            var engine=new RuleEngine(store,chain,clock);engine.accept(source("history:depth-root","CHAIN_0",Set.of(A),Set.of("river"),Map.of()),List.of(a()));
            for(int i=1;i<EventCausality.MAX_DEPTH;i++)engine.resumeTriggers(List.of(a()));
            WorldEvent boundary=engine.history(null,null,0,100).stream().filter(e->e.type().equals("CHAIN_32")).findFirst().orElseThrow();
            assertEquals(32,engine.causality(boundary.id()).depth());
            StoryDefinition later=rule("beyond-depth","1","CHAIN_32",Scope.WORLD,null,null,0,true);engine.reload(List.of(later));
            var before=snapshot(store,"story_trigger_blocks");var report=engine.evaluateHistorical(boundary.id(),later.id(),"1");
            assertEquals(BLOCKED_CAUSAL,report.status());assertEquals("MAX_CAUSAL_DEPTH",report.message());assertEquals(0,report.newSeeds());
            assertEquals(before,snapshot(store,"story_trigger_blocks"));assertEquals(33,count(store,"events"));assertEquals(32,count(store,"instances"));assertEquals(1,engine.triggerBlocks(10).size());
        }
    }

    @Test void registrationDoesNotReplaySourceNpcReactionOrChangeAnyCanonicalEventBytes(){
        var catalog=NpcCatalog.of(Map.of("archivist",new NpcCatalog.Profile("Archivist","Knows real events","archive","reader",List.of(),List.of(),
                List.of(new NpcCatalog.Dialogue("default",0,null,"Recorded history")),
                List.of(new NpcCatalog.Reaction("root-memory",List.of("ROOT"),null,5,2,0,3,null)))));
        WorldEvent historical=source("history:npc-reaction");StoryDefinition later=rule("npc-historical-view","1");
        try(var store=new SqliteStore(database())){
            var ledger=new NpcLedger(store,catalog);var engine=new RuleEngine(store,List.of(),clock,ledger);
            engine.accept(historical,List.of(a()));var beforeView=ledger.view(A,"archivist",a(),Map.of());
            assertEquals(5,beforeView.reputation());assertEquals(2,beforeView.trust());assertEquals(3,beforeView.growth());
            List<String> canonical=sourceRow(store,historical.id());engine.reload(List.of(later));var before=snapshot(store,"story_triggers");
            for(int i=0;i<5;i++)engine.evaluateHistorical(historical.id(),later.id(),"1");
            assertEquals(before,snapshot(store,"story_triggers"));assertEquals(canonical,sourceRow(store,historical.id()));
            engine.resumeTriggers(List.of(a()));engine.accept(historical,List.of(a()));
            var afterView=ledger.view(A,"archivist",a(),Map.of());
            assertEquals(beforeView.reputation(),afterView.reputation());assertEquals(beforeView.trust(),afterView.trust());assertEquals(beforeView.growth(),afterView.growth());
            assertEquals(canonical,sourceRow(store,historical.id()));assertEquals("7",engine.facts(A).get("player.honor"));
        }
    }

    @Test void canonicalNationAudienceCanWitnessNewContentWithoutBecomingOriginalHistoricalActors(){
        WorldEvent historical=source("history:national-witness");StoryDefinition later=rule("national-witness","1","ROOT",Scope.PLAYER,null,null,0,true);
        try(var store=new SqliteStore(database())){
            var engine=new RuleEngine(store,List.of(),clock);engine.accept(historical,List.of());engine.reload(List.of(later));List<String> canonical=sourceRow(store,historical.id());
            assertEquals(2,engine.evaluateHistorical(historical.id(),later.id(),"1").newSeeds());
            engine.resumeTriggers(List.of(context(OUTSIDER,"hill")));assertTrue(engine.instances(OUTSIDER).isEmpty());
            engine.resumeTriggers(List.of(context(B,"river")));assertEquals(1,engine.instances(B).size());assertEquals("7",engine.facts(B).get("player.honor"));
            assertEquals(canonical,sourceRow(store,historical.id()));assertEquals(Set.of(A),engine.history(null,null,0,10).stream().filter(e->e.id().equals(historical.id())).findFirst().orElseThrow().participants());
            assertTrue(engine.instances(A).isEmpty(),"An offline historical actor has not yet received the new story");
            engine.resumeTriggers(List.of(a()));assertEquals(1,engine.instances(A).size());assertEquals("7",engine.facts(A).get("player.honor"));
        }
    }

    @ParameterizedTest @EnumSource(value=Scope.class,names={"PLAYER","NATION"})
    void explicitGlobalAudienceUsesActualScopeReceiptsWithoutAddingSourceActors(Scope scope){
        WorldEvent historical=source("history:global","ROOT",Set.of(),Set.of(),Map.of("audience","GLOBAL"));
        StoryDefinition later=rule("global-"+scope.name().toLowerCase(Locale.ROOT),"1","ROOT",scope,null,null,0,true);
        try(var store=new SqliteStore(database())){
            var engine=new RuleEngine(store,List.of(),clock);engine.accept(historical,List.of());engine.reload(List.of(later));List<String> canonical=sourceRow(store,historical.id());
            assertEquals(1,engine.evaluateHistorical(historical.id(),later.id(),"1").newSeeds());
            for(int i=0;i<3;i++)engine.resumeTriggers(List.of(a(),context(B,"hill")));
            assertEquals(2,count(store,"instances"));assertEquals("7",engine.facts(A).get("player.honor"));assertEquals("7",engine.facts(B).get("player.honor"));
            assertEquals(canonical,sourceRow(store,historical.id()));
            assertTrue(receipted(store,historical,later,scope==Scope.PLAYER?"player:"+A:"nation:river"));
            assertTrue(receipted(store,historical,later,scope==Scope.PLAYER?"player:"+B:"nation:hill"));
        }
    }

    @Test void pinnedNaturalKeyWithAnotherDefinitionIsRejectedWithoutMutatingEitherSnapshot(){
        StoryDefinition current=rule("pinned-evaluation","1"),different=rule("pinned-evaluation","1","ROOT",Scope.WORLD,null,"legacy.permission",0,true);
        WorldEvent historical=source("history:pinned-conflict");
        try(var store=new SqliteStore(database())){
            var engine=new RuleEngine(store,List.of(),clock);engine.accept(historical,List.of());engine.reload(List.of(current));
            store.transaction(c->{try(var s=c.prepareStatement("INSERT INTO story_triggers(id,trigger_key,event_id,scope,story_json) VALUES(?,?,?,?,?)")){
                s.setString(1,RuleEngine.stable(key(historical,current),"world"));s.setString(2,key(historical,current));s.setString(3,historical.id());s.setString(4,"world");s.setString(5,SqliteStore.json(different));s.executeUpdate();}return null;});
            var before=snapshot(store);assertThrows(IllegalArgumentException.class,()->engine.evaluateHistorical(historical.id(),current.id(),"1"));
            assertEquals(before,snapshot(store));assertEquals(List.of(different),engine.pendingDefinitions());
        }
    }

    @Test void legacySourceWithoutStoredCausalityRemainsAnUninventedRootUntilANewResultCommits(){
        WorldEvent historical=source("history:legacy-no-trace");StoryDefinition later=rule("legacy-root-view","1");
        try(var store=new SqliteStore(database())){
            var engine=new RuleEngine(store,List.of(),clock);engine.accept(historical,List.of());
            sql(store,"DELETE FROM event_causes");List<String> canonical=sourceRow(store,historical.id());engine.reload(List.of(later));
            engine.evaluateHistorical(historical.id(),later.id(),"1");assertEquals(0,count(store,"event_causes"));
            engine.resumeTriggers(List.of(a()));assertEquals(1,count(store,"event_causes"));assertEquals(canonical,sourceRow(store,historical.id()));
            WorldEvent result=engine.history(null,null,0,10).stream().filter(e->e.type().equals("RESULT_legacy-root-view")).findFirst().orElseThrow();
            assertEquals(new EventCausality.Trace(historical.id(),historical.id(),1,List.of(later.id())),engine.causality(result.id()));
            assertEquals(EventCausality.root(historical.id()),engine.causality(historical.id()));
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now=Instant.parse("2026-11-20T00:00:00Z");
        void advance(Duration duration){now=now.plus(duration);}
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId ignored){return this;}
        @Override public Instant instant(){return now;}
    }
}
