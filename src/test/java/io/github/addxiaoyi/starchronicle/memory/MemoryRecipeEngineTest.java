package io.github.addxiaoyi.starchronicle.memory;

import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition.*;
import io.github.addxiaoyi.starchronicle.core.*;
import io.github.addxiaoyi.starchronicle.history.EventCausality;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** File-backed SQLite transactions and real inbox activation; no fake memory or completion flags. */
class MemoryRecipeEngineTest {
    @TempDir Path directory;
    private static final UUID ACTOR=new UUID(0,41),CITIZEN=new UUID(0,42);
    private static final Instant SOURCE_TIME=Instant.parse("2026-11-19T12:34:56.123456789Z"),REGISTERED=Instant.parse("2026-11-20T08:00:00Z");
    private static final Clock CLOCK=Clock.fixed(REGISTERED,ZoneOffset.UTC);
    private static final PlayerContext CONTEXT=new PlayerContext(ACTOR,"river",Map.of(),Set.of());
    private Path db(){return directory.resolve("memory.db");}
    private static WorldEvent source(String id){return new WorldEvent(id,"DISCOVERED",SOURCE_TIME,Set.of(ACTOR),Set.of("river"),Map.of("region.id","old-road"),"Trusted","Committed real arrival="+id);}
    private static MemoryRecipeCatalog.Recipe recipe(String id,String version,List<String> sources,List<String> types){return new MemoryRecipeCatalog.Recipe(id,version,true,sources,types,Map.of("region.id","old-road"),"old-road","旧路档案","This locality has a recorded history source.");}
    private static MemoryRecipeCatalog catalog(){return new MemoryRecipeCatalog(List.of(recipe("road","1",List.of("Trusted"),List.of("DISCOVERED"))));}
    private static StoryDefinition follow(String version,String emitted) {
        return new StoryDefinition("memory-follow",version,"档案后续",Scope.PLAYER,List.of("WORLD_MEMORY_RECORDED"),null,"vote",0,
            Map.of("vote",new Node("Read the real archive",null,null,1,Map.of("yes",new Choice("Archive testimony","done",null,null,List.of())),List.of(),false),
                "done",new Node("Recorded",null,null,1,Map.of(),List.of(new Effect("honor","honor","player","4",null),new Effect("history","history",emitted,"Recorded witness testimony",null)),true)));
    }
    private static RuleEngine engine(SqliteStore store,List<StoryDefinition> stories,MemoryRecipeCatalog recipes){return new RuleEngine(store,stories,CLOCK,null,NationalVoteAuthority.denying(),recipes);}
    private static long count(SqliteStore store,String table){return store.transaction(c->{try(var s=c.createStatement();var rows=s.executeQuery("SELECT COUNT(*) FROM "+table)){rows.next();return rows.getLong(1);}});}
    private static StoryInstance instance(RuleEngine engine){return engine.instances(ACTOR).getFirst();}
    @Test void rejectingNestedRuleWorkCannotRollbackOrResetTheOuterConnectionOrBudget()throws Exception {
        try(var store=new SqliteStore(db())) {
            var engine=engine(store,List.of(),catalog());var method=RuleEngine.class.getDeclaredMethod("transaction",SqliteStore.Work.class);method.setAccessible(true);
            var field=RuleEngine.class.getDeclaredField("memoryBudget");field.setAccessible(true);ThreadLocal<?> budget=(ThreadLocal<?>)field.get(engine);
            SqliteStore.Work<Void> outer=c->{
                Object originalBudget=budget.get();assertNotNull(originalBudget);
                try(var statement=c.createStatement()){statement.execute("INSERT INTO facts VALUES('WORLD','guard.before','retained until outer rollback')");}
                assertThrows(IllegalStateException.class,engine::worldFacts);
                assertFalse(c.getAutoCommit(),"Rejected nested work must never touch outer transaction mode");assertSame(originalBudget,budget.get());
                try(var statement=c.createStatement();var rows=statement.executeQuery("SELECT COUNT(*) FROM facts WHERE key='guard.before'")){assertTrue(rows.next());assertEquals(1,rows.getInt(1));}
                try(var statement=c.createStatement()){statement.execute("INSERT INTO facts VALUES('WORLD','guard.after','also rolled back')");}
                throw new IllegalStateException("Deliberately roll back the complete outer operation");
            };
            var failed=assertThrows(java.lang.reflect.InvocationTargetException.class,()->method.invoke(engine,outer));assertInstanceOf(IllegalStateException.class,failed.getCause());assertNull(budget.get());assertTrue(engine.worldFacts().isEmpty());
            engine.accept(source("after-rollback"),List.of());assertEquals(1,engine.memoryEntries(null,0,10).size());
        }
    }
    @Test void nobodyOnlineStillRegistersMemoryAndQueuesTheRealLaterAudience() {
        try(var store=new SqliteStore(db())) {
            var engine=engine(store,List.of(follow("1","TESTIMONY_RECORDED")),catalog());engine.accept(source("arrival"),List.of());
            assertEquals(1,engine.memoryEntries(null,0,100).size());assertEquals(2,count(store,"events"));assertEquals(0,count(store,"instances"));assertTrue(engine.pendingTriggerCount()>0);
            MemoryEntry entry=engine.memoryEntries("old-road",0,10).getFirst();assertEquals(SOURCE_TIME,entry.sourceOccurredAt());assertEquals(REGISTERED,entry.recordedAt());
            WorldEvent signal=engine.history(null,null,0,100).stream().filter(e->e.type().equals("WORLD_MEMORY_RECORDED")).findFirst().orElseThrow();
            assertEquals(entry.signalEventId(),signal.id());assertEquals(REGISTERED,signal.occurredAt());assertEquals(SOURCE_TIME.toString(),signal.facts().get("source.occurred_at"));assertEquals(Set.of(ACTOR),signal.participants());assertEquals(Set.of("river"),signal.nations());assertFalse(signal.globalAudience());
            assertEquals(new EventCausality.Trace("arrival","arrival",1,List.of(),List.of("road")),engine.causality(signal.id()));
            engine.resumeTriggers(List.of(new PlayerContext(CITIZEN,"other-country",Map.of(),Set.of())));assertEquals(0,count(store,"instances"));
            engine.resumeTriggers(List.of(CONTEXT));String id=instance(engine).id();engine.choose(id,ACTOR,"vote","yes",CONTEXT);assertEquals("4",engine.facts(ACTOR).get("player.honor"));
            assertEquals(1,engine.memoryEntries(null,0,10).size());assertEquals(entry.id(),engine.worldFacts().get("region.old-road.memory.road.entry"));
            WorldEvent result=engine.history(null,null,0,100).stream().filter(e->e.type().equals("TESTIMONY_RECORDED")).findFirst().orElseThrow();assertEquals("old-road",result.facts().get("region.id"));assertEquals(signal.id(),result.facts().get("region.source_event"));
            assertEquals(List.of("road"),engine.causality(result.id()).recipes());assertEquals(List.of("memory-follow"),engine.causality(result.id()).stories());
        }
    }
    @Test void existingNpcReactionsShareTheRegistrationTransactionAndDoNotApplyTwice() {
        var profile=new io.github.addxiaoyi.starchronicle.npc.NpcCatalog.Profile("Librarian","Keeps evidenced local archives","library","scribe",List.of(),List.of(),List.of(),
            List.of(new io.github.addxiaoyi.starchronicle.npc.NpcCatalog.Reaction("archive",List.of("WORLD_MEMORY_RECORDED"),null,2,1,0,1,null)));
        try(var store=new SqliteStore(db())) {
            var npcs=new io.github.addxiaoyi.starchronicle.npc.NpcLedger(store,io.github.addxiaoyi.starchronicle.npc.NpcCatalog.of(Map.of("librarian",profile)));
            var engine=new RuleEngine(store,List.of(),CLOCK,npcs,NationalVoteAuthority.denying(),catalog());
            store.transaction(c->{try(var s=c.createStatement()){s.execute("CREATE TRIGGER fail_memory_cause BEFORE INSERT ON event_causes WHEN NEW.depth>0 BEGIN SELECT RAISE(ABORT,'fail after NPC reaction'); END");}return null;});
            assertThrows(IllegalStateException.class,()->engine.accept(source("arrival"),List.of()));assertEquals(0,npcs.view(ACTOR,"librarian",CONTEXT,engine.worldFacts()).growth());assertEquals(0,count(store,"outcomes"));
            store.transaction(c->{try(var s=c.createStatement()){s.execute("DROP TRIGGER fail_memory_cause");}return null;});engine.accept(source("arrival"),List.of());engine.accept(source("arrival"),List.of());
            var view=npcs.view(ACTOR,"librarian",CONTEXT,engine.worldFacts());assertEquals(1,view.growth());assertEquals(2,view.reputation());assertEquals(1,view.trust());assertEquals(1,count(store,"outcomes"));
        }
    }
    @Test void replayAndReopenRetainOneCanonicalRegistrationTimeSignalAndFacts() {
        MemoryEntry entry;List<WorldEvent> history;
        try(var store=new SqliteStore(db())){var engine=engine(store,List.of(),catalog());engine.accept(source("arrival"),List.of());entry=engine.memoryEntries(null,0,10).getFirst();history=engine.history(null,null,0,100);assertTrue(engine.accept(source("arrival"),List.of()).duplicate());}
        try(var store=new SqliteStore(db())){var engine=new RuleEngine(store,List.of(),Clock.offset(CLOCK,Duration.ofDays(3)),null,NationalVoteAuthority.denying(),catalog());assertTrue(engine.accept(source("arrival"),List.of()).duplicate());assertEquals(List.of(entry),engine.memoryEntries(null,0,10));assertEquals(history,engine.history(null,null,0,100));assertEquals(1,count(store,"memory_entries"));assertEquals(0,count(store,"outcomes"));}
    }
    @Test void collidingSourceIsRejectedWithoutReplacingTheMemory() {
        try(var store=new SqliteStore(db())){var engine=engine(store,List.of(),catalog());engine.accept(source("arrival"),List.of());MemoryEntry original=engine.memoryEntries(null,0,10).getFirst();WorldEvent old=source("arrival");var collision=new WorldEvent(old.id(),old.type(),old.occurredAt(),old.participants(),old.nations(),Map.of("region.id","another-place"),old.source(),old.evidence());assertThrows(IllegalArgumentException.class,()->engine.accept(collision,List.of()));assertEquals(List.of(original),engine.memoryEntries(null,0,10));assertEquals(2,count(store,"events"));}
    }
    @ParameterizedTest @ValueSource(strings={"memory_entries","facts","event_causes","story_triggers","signal"})
    void sourceMemoryFactsSignalCausalityAndInboxRollBackTogether(String point) {
        try(var store=new SqliteStore(db())) {
            var engine=engine(store,List.of(follow("1","DONE")),catalog());
            String table=point.equals("signal")?"events":point,when=point.equals("signal")?" WHEN NEW.type='WORLD_MEMORY_RECORDED'":point.equals("event_causes")?" WHEN NEW.depth>0":"";
            store.transaction(c->{try(var s=c.createStatement()){s.execute("CREATE TRIGGER reject_memory BEFORE INSERT ON "+table+when+" BEGIN SELECT RAISE(ABORT,'injected registration failure'); END");}return null;});
            assertThrows(IllegalStateException.class,()->engine.accept(source("arrival"),List.of()));
            for(String name:List.of("events","event_nations","event_players","event_causes","memory_entries","facts","story_triggers","outcomes"))assertEquals(0,count(store,name),name);
            assertEquals(1,count(store,"memory_recipe_definitions"));
            store.transaction(c->{try(var s=c.createStatement()){s.execute("DROP TRIGGER reject_memory");}return null;});engine.accept(source("arrival"),List.of());assertEquals(1,engine.memoryEntries(null,0,10).size());
        }
    }
    @Test void validateAndFailedReloadNeverArchiveOrPublishPartialConfiguration() {
        try(var store=new SqliteStore(db())) {
            var old=recipe("road","1",List.of("Trusted"),List.of("DISCOVERED"));var engine=engine(store,List.of(follow("1","DONE")),new MemoryRecipeCatalog(List.of(old)));
            var future=recipe("road","2",List.of("Trusted"),List.of("DISCOVERED"));engine.validate(List.of(follow("2","DONE_V2")),new MemoryRecipeCatalog(List.of(future)));assertEquals(1,count(store,"memory_recipe_definitions"));assertEquals(1,count(store,"story_definitions"));
            var conflict=new MemoryRecipeCatalog.Recipe("road","1",true,old.sources(),old.eventTypes(),old.requiredFacts(),old.regionId(),"Changed title",old.text());
            assertThrows(IllegalArgumentException.class,()->engine.reload(List.of(follow("2","DONE_V2")),new MemoryRecipeCatalog(List.of(conflict))));assertEquals(1,count(store,"story_definitions"));assertEquals(1,count(store,"memory_recipe_definitions"));
            engine.accept(source("first"),List.of());assertEquals("1",engine.memoryEntries(null,0,10).getFirst().recipeVersion());
            engine.reload(List.of(follow("2","DONE_V2")),new MemoryRecipeCatalog(List.of(future)));assertTrue(engine.accept(source("first"),List.of()).duplicate());assertEquals(1,engine.memoryEntries(null,0,10).size());engine.accept(source("second"),List.of());assertEquals(2,engine.memoryEntries(null,0,10).size());assertEquals(Set.of("1","2"),new HashSet<>(engine.memoryEntries(null,0,10).stream().map(MemoryEntry::recipeVersion).toList()));
        }
    }
    @Test void disabledAndReenabledCatalogNeverRetroactivelyEvaluatesAnOldSource() {
        try(var store=new SqliteStore(db())) {
            var enabled=catalog().recipes().getFirst();var disabled=new MemoryRecipeCatalog.Recipe(enabled.id(),enabled.version(),false,enabled.sources(),enabled.eventTypes(),enabled.requiredFacts(),enabled.regionId(),enabled.title(),enabled.text());
            var engine=engine(store,List.of(),new MemoryRecipeCatalog(List.of(disabled)));engine.accept(source("while-disabled"),List.of());assertTrue(engine.memoryEntries(null,0,10).isEmpty());
            engine.reload(List.of(),catalog());assertTrue(engine.accept(source("while-disabled"),List.of()).duplicate());assertTrue(engine.memoryEntries(null,0,10).isEmpty());engine.accept(source("after-enable"),List.of());assertEquals(1,engine.memoryEntries(null,0,10).size());assertEquals(1,count(store,"memory_recipe_definitions"));
            assertThrows(IllegalArgumentException.class,()->engine.memoryEntries("../city",0,10));assertThrows(IllegalArgumentException.class,()->engine.memoryEntries(null,-1,10));assertThrows(IllegalArgumentException.class,()->engine.memoryEntries(null,0,101));
        }
    }
    @Test void missingLocalityAndWrongAuthorityRemainHistoryWithoutInventingCityMemory() {
        try(var store=new SqliteStore(db())) {
            var engine=engine(store,List.of(),catalog());WorldEvent old=source("arrival");
            engine.accept(new WorldEvent("missing",old.type(),old.occurredAt(),old.participants(),old.nations(),Map.of("war.id","actual-war"),old.source(),old.evidence()),List.of());
            engine.accept(new WorldEvent("wrong-authority",old.type(),old.occurredAt(),old.participants(),old.nations(),old.facts(),"ADMIN_TEST",old.evidence()),List.of());
            assertEquals(2,count(store,"events"));assertTrue(engine.memoryEntries(null,0,10).isEmpty());assertTrue(engine.worldFacts().isEmpty());
        }
    }
    @Test void actualRecipeStoryRecipeCycleIsBlockedAcrossVersionsAndTrueEndingIsRetained() {
        var first=recipe("road","1",List.of("Trusted","StarChronicle"),List.of("DISCOVERED","TESTIMONY_RECORDED"));
        var second=recipe("road","2",first.sources(),first.eventTypes());
        try(var store=new SqliteStore(db())) {
            var engine=engine(store,List.of(follow("1","TESTIMONY_RECORDED")),new MemoryRecipeCatalog(List.of(first)));engine.accept(source("arrival"),List.of(CONTEXT));engine.resumeTriggers(List.of(CONTEXT));StoryInstance active=instance(engine);
            engine.reload(List.of(follow("1","TESTIMONY_RECORDED")),new MemoryRecipeCatalog(List.of(second)));engine.choose(active.id(),ACTOR,"vote","yes",CONTEXT);
            assertEquals(1,engine.memoryEntries(null,0,10).size());assertEquals(3,count(store,"events"));assertEquals("4",engine.facts(ACTOR).get("player.honor"));
            var blocked=engine.memoryRecipeBlocks(10);assertEquals(1,blocked.size());assertEquals("road",blocked.getFirst().recipeId());assertEquals("2",blocked.getFirst().version());assertEquals("REPEATED_RECIPE_IN_CAUSAL_CHAIN",blocked.getFirst().reason());
            WorldEvent ending=engine.history(null,null,0,100).stream().filter(e->e.type().equals("TESTIMONY_RECORDED")).findFirst().orElseThrow();assertTrue(second.matches(ending),"The repeat must be blocked by causal evidence, not silently skipped by missing locality");assertEquals(2,engine.causality(ending.id()).depth());assertEquals(List.of("road"),engine.causality(ending.id()).recipes());assertEquals(List.of("memory-follow"),engine.causality(ending.id()).stories());
        }
    }
    @Test void broadMutualFeedbackHasOneBudgetForTheWholeTransactionAndCannotReplayTheRemainder() {
        List<MemoryRecipeCatalog.Recipe> recipes=new ArrayList<>();for(int n=0;n<6;n++)recipes.add(recipe("fan"+n,"1",List.of("Trusted","StarChronicle.MemoryRecipes"),List.of("DISCOVERED","WORLD_MEMORY_RECORDED")));
        try(var store=new SqliteStore(db())) {
            var engine=engine(store,List.of(),new MemoryRecipeCatalog(recipes));engine.accept(source("fanout"),List.of());
            assertEquals(RuleEngine.MAX_MEMORY_SIGNALS_PER_TRANSACTION,count(store,"memory_entries"));assertEquals(1+RuleEngine.MAX_MEMORY_SIGNALS_PER_TRANSACTION,count(store,"events"));
            assertTrue(engine.memoryRecipeBlocks(1000).stream().anyMatch(block->block.reason().equals("MAX_MEMORY_SIGNALS_PER_TRANSACTION")),"The global budget must be reached while exact source/locality still match");
            long blocks=count(store,"memory_recipe_blocks");assertTrue(engine.accept(source("fanout"),List.of()).duplicate());assertEquals(blocks,count(store,"memory_recipe_blocks"));assertEquals(RuleEngine.MAX_MEMORY_SIGNALS_PER_TRANSACTION,count(store,"memory_entries"));
        }
    }
    @Test void automaticRecipeChainStopsAtCombinedDepth32WithoutFabricatingActors() {
        List<MemoryRecipeCatalog.Recipe> recipes=new ArrayList<>();for(int n=0;n<36;n++)recipes.add(new MemoryRecipeCatalog.Recipe("r"+n,"1",true,List.of(n==0?"Trusted":"StarChronicle.MemoryRecipes"),List.of(n==0?"DISCOVERED":"WORLD_MEMORY_RECORDED"),n==0?Map.of("region.id","old-road"):Map.of("region.id","old-road","memory.recipe","r"+(n-1)),"old-road","Archive "+n,"Evidenced locality"));
        try(var store=new SqliteStore(db())) {var engine=engine(store,List.of(),new MemoryRecipeCatalog(recipes));engine.accept(source("chain"),List.of());assertEquals(32,count(store,"memory_entries"));assertEquals(33,count(store,"events"));var block=engine.memoryRecipeBlocks(10).getFirst();assertEquals("r32",block.recipeId());assertEquals("MAX_CAUSAL_DEPTH",block.reason());assertEquals(32,engine.causality(block.eventId()).depth());assertTrue(engine.history(null,null,0,100).stream().allMatch(event->event.participants().equals(Set.of(ACTOR))&&event.nations().equals(Set.of("river"))));}
    }
}
