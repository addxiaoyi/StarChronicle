package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.PlayerContext;
import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import io.github.addxiaoyi.starchronicle.config.Condition;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Acceptance, reload and restart use real engine transactions and a persistent version catalog. */
class RetiredStoryRuntimeTest {
    @TempDir Path directory;
    private static final UUID A=UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID B=UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID C=UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID D=UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final Instant NOW=Instant.parse("2026-11-19T20:00:00Z");
    private static final String A1_CHOICE="past.player.choice.a/1/legacy";
    private static final List<String> TABLES=List.of("story_definitions","events","event_players","event_nations","instances","outcomes","facts","story_triggers","story_trigger_receipts","event_causes","story_choice_log","story_trigger_blocks","native_scene_journal");
    private SqliteStore store;
    private RuleEngine engine;

    @AfterEach void close(){if(engine!=null)engine.close();else if(store!=null)store.close();}

    @Test void acceptedConfigurationIsArchivedBeforeAnyEventTriggerInstanceOrChoiceExists() {
        StoryDefinition a1=a1();open(a1);
        assertEquals(Set.of(new DefinitionCatalog.Key("a","1")),catalog().keys());
        assertEquals(a1,catalog().require("a","1"));
        var entry=catalog().entries().get(new DefinitionCatalog.Key("a","1")).getFirst();
        assertEquals("ACCEPTED_CONFIGURATION",entry.provenance());assertFalse(entry.firstSeen().isBlank());
        for(String table:List.of("events","instances","story_triggers","story_choice_log","outcomes"))assertEquals(0,count(table),table);
        Map<String,List<List<String>>> before=snapshot();engine.reload(List.of(a1));assertEquals(before,snapshot(),"Accepting identical content must preserve its first provenance and timestamp");
        reopen();
        assertEquals(a1,catalog().require("a","1"));assertEquals(1,count("story_definitions"));
        assertTrue(engine.accept(event("retired-before-first-event","A1_ROOT",A),List.of(context(A))).instanceIds().isEmpty());
        assertTrue(engine.instances(A).isEmpty());
    }

    @Test void validateOfA2AndA1DependentBIsReadOnlyAndDoesNotPublishEitherCandidate() {
        open(a1());Map<String,List<List<String>>> before=snapshot();
        assertDoesNotThrow(()->engine.validate(List.of(a2(),followup("B_ROOT"))));
        assertEquals(before,snapshot());assertEquals(Set.of(new DefinitionCatalog.Key("a","1")),catalog().keys());
        assertThrows(IllegalArgumentException.class,()->catalog().require("a","2"));
        assertThrows(IllegalArgumentException.class,()->catalog().require("b","1"));
        assertTrue(engine.accept(event("not-published-a2","A2_ROOT",B),List.of(context(B))).instanceIds().isEmpty());
        assertTrue(engine.accept(event("not-published-b","B_ROOT",B),List.of(context(B))).instanceIds().isEmpty());
        assertTrue(engine.instances(B).isEmpty());
        StoryInstance old=start(event("still-enabled-a1","A1_ROOT",A),A,"a","1");
        assertEquals("legacy",old.node());assertEquals(a1(),old.definition());
    }

    @Test void reloadArchivesAndPublishesA2AndBWhileRestartingOnlyBDoesNotEnableEitherRetiredA() {
        open(a1());StoryInstance old=start(event("a1-completed-source","A1_ROOT",A),A,"a","1");
        choose(old,A,"accept");assertEquals("5",engine.facts(A).get("player.honor"));
        StoryDefinition sequel=followup("B_ROOT");engine.reload(List.of(a2(),sequel));
        Set<DefinitionCatalog.Key> keys=Set.of(new DefinitionCatalog.Key("a","1"),new DefinitionCatalog.Key("a","2"),new DefinitionCatalog.Key("b","1"));
        assertEquals(keys,catalog().keys());assertEquals(a2(),catalog().require("a","2"));assertEquals(sequel,catalog().require("b","1"));
        StoryInstance modern=start(event("a2-new-source","A2_ROOT",B),B,"a","2");assertEquals("modern",modern.node());choose(modern,B,"approve");
        StoryInstance next=start(event("b-new-source","B_ROOT",A),A,"b","1");choose(next,A,"record");
        assertEquals(Map.of(A1_CHOICE,"accept"),engine.recollections(context(A),Set.of(A1_CHOICE,"past.player.choice.a/2/modern")));
        assertEquals("7",engine.facts(A).get("player.honor"));assertEquals("50",engine.facts(B).get("player.honor"));
        Map<String,List<List<String>>> before=snapshot();reopen(sequel);
        assertEquals(before,snapshot(),"Restarting with only the sequel must not rewrite accepted definitions or completed results");
        assertEquals(keys,catalog().keys());assertEquals(2,instances("a").size());assertEquals(1,instances("b").size());
        assertTrue(engine.accept(event("retired-v1-new-source","A1_ROOT",C),List.of(context(C))).instanceIds().isEmpty());
        assertTrue(engine.accept(event("retired-v2-new-source","A2_ROOT",D),List.of(context(D))).instanceIds().isEmpty());
        engine.resumeTriggers(List.of(context(A),context(B),context(C),context(D)));
        assertEquals(2,instances("a").size());assertTrue(engine.instances(C).isEmpty());assertTrue(engine.instances(D).isEmpty());
        StoryInstance resumedSequel=start(event("b-after-restart","B_ROOT",A),A,"b","1");
        assertEquals(sequel,resumedSequel.definition());assertEquals("archive",resumedSequel.node());
    }

    @Test void validateAndReloadRejectSameVersionNativeBindingChangesWithoutChangingCatalogOrRuntime() {
        StoryDefinition bound=programs(a1(),"native.old","quest.old");open(bound);
        Map<String,List<List<String>>> before=snapshot();
        for(StoryDefinition changed:List.of(programs(a1(),"native.changed","quest.old"),programs(a1(),"native.old","quest.changed"),a1())) {
            assertThrows(IllegalArgumentException.class,()->engine.validate(List.of(changed)));
            assertEquals(before,snapshot());
            assertThrows(IllegalArgumentException.class,()->engine.reload(List.of(changed)));
            assertEquals(before,snapshot());assertEquals(bound,catalog().require("a","1"));
        }
        StoryInstance stillBound=start(event("old-binding-still-live","A1_ROOT",A),A,"a","1");
        assertEquals(bound,stillBound.definition());
        assertEquals(Set.of("native.old","quest.old"),engine.pending().stream().map(delivery->delivery.target()).collect(java.util.stream.Collectors.toSet()));
        assertTrue(engine.pending().stream().noneMatch(delivery->delivery.target().contains("changed")));
    }

    @Test void aLateArchiveInsertFailureRollsBackTheWholeReloadAndKeepsTheOldRuntimeUntilRetryCommits() {
        open(a1());StoryDefinition sequel=followup("B_ROOT");
        sql("CREATE TRIGGER reject_second_reload_definition BEFORE INSERT ON story_definitions WHEN NEW.story='b' BEGIN SELECT RAISE(ABORT,'injected definition archive failure'); END");
        Map<String,List<List<String>>> before=snapshot();
        assertThrows(IllegalStateException.class,()->engine.reload(List.of(a2(),sequel)));
        assertEquals(before,snapshot(),"A2 was inserted before B failed and must be rolled back with every other row");
        assertEquals(Set.of(new DefinitionCatalog.Key("a","1")),catalog().keys());
        StoryInstance old=start(event("old-runtime-after-rollback","A1_ROOT",A),A,"a","1");assertEquals(a1(),old.definition());
        assertTrue(engine.accept(event("new-runtime-not-published","A2_ROOT",B),List.of(context(B))).instanceIds().isEmpty());
        assertTrue(engine.accept(event("sequel-not-published","B_ROOT",C),List.of(context(C))).instanceIds().isEmpty());
        assertTrue(engine.instances(B).isEmpty());assertTrue(engine.instances(C).isEmpty());
        sql("DROP TRIGGER reject_second_reload_definition");engine.reload(List.of(a2(),sequel));
        assertEquals(3,count("story_definitions"));
        StoryInstance modern=start(event("new-runtime-after-commit","A2_ROOT",B),B,"a","2");assertEquals(a2(),modern.definition());
        assertTrue(engine.accept(event("old-runtime-now-retired","A1_ROOT",D),List.of(context(D))).instanceIds().isEmpty());
        assertEquals(a1(),engine.instances(A).getFirst().definition(),"Publishing A2 must not rewrite the surviving active A1 snapshot");
    }

    @Test void pendingAndActiveA1SnapshotsStayPinnedAcrossA2ReloadEmptyRetirementAndRestart() {
        StoryDefinition legacy=a1();open(legacy);
        StoryInstance active=start(event("old-active","A1_ROOT",A),A,"a","1");
        WorldEvent deferred=event("old-pending","A1_ROOT",B);engine.accept(deferred,List.of());
        assertEquals(1,engine.pendingTriggerCount());assertEquals(List.of(legacy),engine.pendingDefinitions());
        engine.reload(List.of(a2()));assertEquals(legacy,engine.instances(A).getFirst().definition());
        assertEquals(List.of(legacy),engine.pendingDefinitions());reopen();
        assertEquals(2,count("story_definitions"));assertEquals(legacy,engine.instances(A).getFirst().definition());
        assertEquals(Set.of("accept"),engine.availableChoices(active.id(),context(A)).keySet());
        choose(active,A,"accept");engine.resumeTriggers(List.of(context(B)));
        StoryInstance recovered=engine.instances(B).getFirst();assertEquals(legacy,recovered.definition());assertEquals("legacy",recovered.node());
        choose(recovered,B,"accept");
        assertEquals("5",engine.facts(A).get("player.honor"));assertEquals("5",engine.facts(B).get("player.honor"));
        assertEquals(0,engine.pendingTriggerCount());assertEquals(2,count("instances"));
        assertTrue(engine.accept(event("empty-runtime-does-not-enable-archive","A1_ROOT",C),List.of(context(C))).instanceIds().isEmpty());
        engine.reload(List.of(a2()));StoryInstance modern=start(event("new-modern-after-pin-recovery","A2_ROOT",C),C,"a","2");
        assertEquals(Set.of("approve"),engine.availableChoices(modern.id(),context(C)).keySet());choose(modern,C,"approve");
        assertEquals("50",engine.facts(C).get("player.honor"));assertEquals("5",engine.facts(A).get("player.honor"));
    }

    @Test void retiringDefinitionsAndPublishingNewConsumersNeverBackfillPreviouslySavedHistory() {
        open(a1());engine.reload(List.of());
        WorldEvent historical=event("history-before-consumer","A1_ROOT",A);engine.accept(historical,List.of(context(A)));
        assertEquals(1,count("events"));assertEquals(0,engine.pendingTriggerCount());assertTrue(engine.instances(A).isEmpty());
        StoryDefinition current=followup("A1_ROOT");engine.validate(List.of(current));assertEquals(1,count("story_definitions"));
        engine.reload(List.of(current));assertEquals(2,count("story_definitions"));
        assertEquals(0,engine.pendingTriggerCount());assertTrue(engine.instances(A).isEmpty());
        reopen(current);engine.resumeTriggers(List.of(context(A)));
        assertEquals(0,engine.pendingTriggerCount());assertTrue(engine.accept(historical,List.of(context(A))).duplicate());
        assertEquals(0,engine.pendingTriggerCount());assertTrue(engine.instances(A).isEmpty());
        engine.accept(event("new-current-source","A1_ROOT",A),List.of(context(A)));
        assertEquals(1,engine.pendingTriggerCount(),"Only a newly accepted source queues the now-enabled sequel; its missing personal A1 choice is still unknown");
        assertEquals(List.of(current),engine.pendingDefinitions());assertTrue(engine.instances(A).isEmpty());
        assertEquals(2,count("events"));assertEquals(a1(),catalog().require("a","1"));
    }

    private void open(StoryDefinition...enabled){store=new SqliteStore(directory.resolve("retired-runtime.db"));engine=new RuleEngine(store,List.of(enabled),Clock.fixed(NOW,ZoneOffset.UTC));}
    private void reopen(StoryDefinition...enabled){engine.close();engine=null;store=null;open(enabled);}
    private DefinitionCatalog catalog(){return store.transaction(DefinitionCatalog::read);}
    private long count(String table){return store.transaction(connection->{try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT COUNT(*) FROM "+table)){assertTrue(rows.next());return rows.getLong(1);}});}
    private List<StoryInstance> instances(String story){return store.transaction(connection->{List<StoryInstance> result=new ArrayList<>();try(var statement=connection.prepareStatement("SELECT json FROM instances WHERE story=? ORDER BY id")){statement.setString(1,story);try(var rows=statement.executeQuery()){while(rows.next())result.add(SqliteStore.decode(rows.getString(1),StoryInstance.class));}}return List.copyOf(result);});}
    private StoryInstance start(WorldEvent source,UUID player,String story,String version) {
        ProcessReport report=engine.accept(source,List.of(context(player)));assertFalse(report.duplicate());
        return engine.instances(player).stream().filter(instance->instance.definition().id().equals(story)&&instance.definition().version().equals(version)&&instance.sourceEventId().equals(source.id())).findFirst().orElseThrow();
    }
    private void choose(StoryInstance instance,UUID player,String choice){engine.choose(instance.id(),player,instance.node(),choice,context(player));assertTrue(engine.instances(player).stream().anyMatch(current->current.id().equals(instance.id())&&current.completed()));}
    private void sql(String text){store.transaction(connection->{try(var statement=connection.createStatement()){statement.execute(text);}return null;});}
    private Map<String,List<List<String>>> snapshot() {
        return store.transaction(connection->{
            Map<String,List<List<String>>> result=new TreeMap<>();
            for(String table:TABLES) {
                List<List<String>> values=new ArrayList<>();
                try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT * FROM "+table)) {
                    int columns=rows.getMetaData().getColumnCount();
                    while(rows.next()){List<String> row=new ArrayList<>();for(int column=1;column<=columns;column++)row.add(rows.getString(column));values.add(Collections.unmodifiableList(row));}
                }
                values.sort(Comparator.comparing(SqliteStore::json));result.put(table,List.copyOf(values));
            }
            return Collections.unmodifiableMap(result);
        });
    }
    private static StoryDefinition a1(){return story("a","1","A1_ROOT","legacy","accept",5,"A1_DONE",null);}
    private static StoryDefinition a2(){return story("a","2","A2_ROOT","modern","approve",50,"A2_DONE",null);}
    private static StoryDefinition followup(String trigger){return story("b","1",trigger,"archive","record",2,null,new Condition(null,null,null,A1_CHOICE,"eq","accept"));}
    private static StoryDefinition story(String id,String version,String trigger,String start,String choice,int honor,String resultType,Condition condition) {
        List<StoryDefinition.Effect> effects=new ArrayList<>();effects.add(new StoryDefinition.Effect("honor","honor","player",Integer.toString(honor),null));
        if(resultType!=null)effects.add(new StoryDefinition.Effect("history","history",resultType,"Committed result from "+id+"/"+version,null));
        return new StoryDefinition(id,version,id+" version "+version,StoryDefinition.Scope.PLAYER,List.of(trigger),condition,start,0,Map.of(start,new StoryDefinition.Node("Choose "+version,null,null,1,Map.of(choice,new StoryDefinition.Choice("Continue","end",null,null,List.of())),List.of(),false),"end",new StoryDefinition.Node("Completed "+version,null,null,1,Map.of(),effects,true)));
    }
    private static StoryDefinition programs(StoryDefinition source,String script,String quest) {
        Map<String,StoryDefinition.Node> nodes=new LinkedHashMap<>(source.nodes());var old=nodes.get(source.start());
        nodes.put(source.start(),new StoryDefinition.Node(old.text(),script,quest,old.quorum(),old.choices(),old.effects(),old.terminal()));
        return new StoryDefinition(source.id(),source.version(),source.title(),source.scope(),source.triggers(),source.condition(),source.start(),source.cooldownSeconds(),nodes);
    }
    private static PlayerContext context(UUID player){return new PlayerContext(player,"",Map.of(),Set.of());}
    private static WorldEvent event(String id,String type,UUID player){return new WorldEvent(id,type,NOW,Set.of(player),Set.of(),Map.of(),"RetiredStoryRuntimeTest","Committed test source="+id);}
}
