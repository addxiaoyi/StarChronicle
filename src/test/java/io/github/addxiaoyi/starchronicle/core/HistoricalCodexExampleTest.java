package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.config.*;
import io.github.addxiaoyi.starchronicle.npc.*;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Executes the checked-in operator example against the actual packaged last-heir/1 graph. */
class HistoricalCodexExampleTest {
    @TempDir Path directory;
    private static final UUID A=new UUID(0,1),B=new UUID(0,2),C=new UUID(0,3),D=new UUID(0,4),CITIZEN=new UUID(0,5),SPECTATOR=new UUID(0,6);
    private static final Map<UUID,String> NATIONS=Map.of(A,"river",B,"hill",C,"river",D,"hill",CITIZEN,"river",SPECTATOR,"coast");
    private static final Instant NOW=Instant.parse("2026-11-19T20:00:00Z");
    private static final String PERSONAL="past.player.choice.last-heir/1/council",WORLD="past.resolution.world.last-heir/1/council";
    private StoryDefinition parent;
    private NpcCatalog npcs;
    private SqliteStore store;
    private RuleEngine engine;

    @BeforeEach void loadActualFiles() throws Exception {
        Path original=Files.createDirectory(directory.resolve("parent"));
        try(var input=getClass().getResourceAsStream("/stories/last-heir.yml")){assertNotNull(input);Files.copy(input,original.resolve("last-heir.yml"));}
        parent=ConfigLoader.load(original,id->false,id->false).getFirst();assertEquals("last-heir",parent.id());assertEquals("1",parent.version());
        Path npcFile=directory.resolve("npcs.yml");try(var input=getClass().getResourceAsStream("/npcs.yml")){assertNotNull(input);Files.copy(input,npcFile);}
        npcs=NpcCatalog.load(npcFile);open(NOW,List.of(parent));
    }
    @AfterEach void close(){if(engine!=null)engine.close();else if(store!=null)store.close();}

    @Test void actualExampleRequiresTheAcceptedRetiredGraphButTheGraphAloneDoesNotInventAPersonalVote() throws Exception {
        Path examples=exampleDirectory();
        assertThrows(IllegalArgumentException.class,()->ConfigLoader.load(examples,id->false,id->false));
        StoryDefinition codex=ConfigLoader.load(examples,id->false,id->false,catalog()).getFirst();
        assertEquals("historical-codex",codex.id());assertEquals("1",codex.version());assertEquals(StoryDefinition.Scope.PLAYER,codex.scope());
        assertEquals(Set.of("desk","preserved","annotated"),codex.nodes().keySet());
        assertEquals(Set.of("preserve","annotate"),codex.nodes().get("desk").choices().keySet());
        assertEquals(new StoryDependencies.Reference(StoryDependencies.Kind.PLAYER_CHOICE,"last-heir","1","council"),StoryDependencies.parse(codex.condition().fact()).orElseThrow());
        assertEquals(1,count("story_definitions"));engine.validate(List.of(codex));assertEquals(1,count("story_definitions"));
        engine.reload(List.of(codex));assertEquals(2,count("story_definitions"));
        engine.accept(new WorldEvent("codex-without-old-choice","HEIR_RESTORATION_SUPPORTED",NOW,Set.of(A),Set.of("river"),Map.of("test-only","condition probe"),"HistoricalCodexExampleTest","Explicit test-only probe; accepting a definition is not a personal historical ballot"),List.of(context(A)));
        engine.resumeTriggers(List.of(context(A),context(CITIZEN),context(SPECTATOR)));
        assertTrue(stories(A,"historical-codex").isEmpty());assertTrue(engine.recollections(context(A),Set.of(PERSONAL)).isEmpty());
        assertFalse(engine.facts(A).containsKey("player.honor"));assertEquals(0,count("story_choice_log"));
    }

    @Test void explicitCanonicalEndingPlaysBothExampleBranchesWithoutRewritingOldVotesOrEnrollingNonVoters() throws Exception {
        WorldEvent discovery=new WorldEvent("actual-parent-discovery","ANCIENT_HEIR_DISCOVERED",NOW,Set.of(A,B,C,D),Set.of("river","hill"),Map.of("test-only","actual resource playthrough"),"HistoricalCodexExampleTest","Four evidenced test actors play the actual packaged epic; no national mutation is asserted");
        String epic=engine.accept(discovery,List.of(context(A),context(B),context(C),context(D))).instanceIds().getFirst();
        vote(epic,A,"summons","convene");vote(epic,B,"summons","convene");
        vote(epic,D,"council","oppose");vote(epic,A,"council","support");vote(epic,B,"council","support");
        vote(epic,A,"preparations","restoration");vote(epic,B,"preparations","restoration");
        assertTrue(stories(A,"last-heir").getFirst().completed());
        WorldEvent ending=engine.history(null,null,0,100).stream().filter(event->event.type().equals("HEIR_RESTORATION_SUPPORTED")).findFirst().orElseThrow();
        assertEquals(Set.of(A,B,C,D),ending.participants());assertEquals("oppose",past(D).get(PERSONAL));assertEquals("support",past(D).get(WORLD));
        assertNull(past(C).get(PERSONAL));assertNull(past(CITIZEN).get(PERSONAL));assertNull(past(SPECTATOR).get(PERSONAL));
        List<String> originalVotes=oldChoiceRows();assertEquals(10,originalVotes.size());
        engine.reload(List.of());reopen(NOW.plusSeconds(60),List.of());
        StoryDefinition codex=ConfigLoader.load(exampleDirectory(),id->false,id->false,catalog()).getFirst();
        engine.reload(List.of(codex));assertEquals(parent,catalog().require("last-heir","1"));
        assertEquals(0,engine.pendingTriggerCount());engine.resumeTriggers(readers());
        assertTrue(stories(A,"historical-codex").isEmpty(),"Installing the example does not silently scan or replay an older ending");
        HistoricalEvaluationReport evaluation=engine.evaluateHistorical(ending.id(),codex.id(),codex.version());
        assertEquals(HistoricalEvaluationReport.Status.QUEUED,evaluation.status());assertTrue(engine.pendingTriggerCount()>0);
        assertTrue(stories(A,"historical-codex").isEmpty(),"Explicit evaluation registers the canonical inbox rather than executing it recursively");
        engine.resumeTriggers(readers());
        StoryInstance a=onlyCodex(A),d=onlyCodex(D);assertNotEquals(a.id(),d.id());assertEquals(ending.id(),a.sourceEventId());assertEquals(ending.id(),d.sourceEventId());
        assertEquals("desk",a.node());assertEquals("desk",d.node());
        for(UUID viewer:List.of(C,CITIZEN,SPECTATOR)){assertTrue(stories(viewer,"historical-codex").isEmpty());assertNull(past(viewer).get(PERSONAL));}
        assertTrue(stories(B,"historical-codex").isEmpty(),"The other qualified voter is offline in this explicit recovery cohort");
        vote(a.id(),A,"desk","preserve");vote(d.id(),D,"desk","annotate");
        assertEquals("preserved",onlyCodex(A).node());assertEquals("annotated",onlyCodex(D).node());
        assertTrue(onlyCodex(A).completed());assertTrue(onlyCodex(D).completed());
        assertEquals("17",engine.facts(A).get("player.honor"));assertEquals("17",engine.facts(D).get("player.honor"));
        assertEquals("旧史保管人",engine.facts(A).get("title.codex-preserver"));assertEquals("旧史批注者",engine.facts(D).get("title.codex-commentator"));
        assertFalse(engine.facts(A).containsKey("title.codex-commentator"));assertFalse(engine.facts(D).containsKey("title.codex-preserver"));
        assertEquals(originalVotes,oldChoiceRows());assertEquals("support",past(A).get(PERSONAL));assertEquals("oppose",past(D).get(PERSONAL));
        assertEquals(1,engine.history(null,null,0,100).stream().filter(event->event.type().equals("HISTORICAL_CODEX_PRESERVED")).count());
        assertEquals(1,engine.history(null,null,0,100).stream().filter(event->event.type().equals("HISTORICAL_CODEX_ANNOTATED")).count());
        Map<String,Long> committed=counts();reopen(NOW.plus(Duration.ofDays(9)),List.of(codex));
        for(int replay=0;replay<3;replay++) {
            assertEquals(HistoricalEvaluationReport.Status.ALREADY_REGISTERED,engine.evaluateHistorical(ending.id(),codex.id(),codex.version()).status());
            assertTrue(engine.accept(discovery,List.of(context(A),context(B),context(C),context(D))).duplicate());engine.resumeTriggers(readers());
        }
        assertEquals(committed,counts());assertEquals(originalVotes,oldChoiceRows());
        assertEquals("17",engine.facts(A).get("player.honor"));assertEquals("17",engine.facts(D).get("player.honor"));
        for(UUID viewer:List.of(C,CITIZEN,SPECTATOR)){assertTrue(stories(viewer,"historical-codex").isEmpty());assertNull(past(viewer).get(PERSONAL));}
    }

    private void open(Instant time,List<StoryDefinition> enabled){store=new SqliteStore(directory.resolve("codex.db"));engine=new RuleEngine(store,enabled,Clock.fixed(time,ZoneOffset.UTC),new NpcLedger(store,npcs));}
    private void reopen(Instant time,List<StoryDefinition> enabled){engine.close();engine=null;store=null;open(time,enabled);}
    private Path exampleDirectory() throws Exception {
        Path target=directory.resolve("codex-example");Files.createDirectories(target);
        Path actual=Path.of("docs","examples","historical-codex.yml");assertTrue(Files.isRegularFile(actual),"The actual delivered YAML example must exist");
        Files.copy(actual,target.resolve("historical-codex.yml"),java.nio.file.StandardCopyOption.REPLACE_EXISTING);return target;
    }
    private DefinitionCatalog catalog(){return store.transaction(DefinitionCatalog::read);}
    private static PlayerContext context(UUID player){return new PlayerContext(player,NATIONS.get(player),Map.of(),Set.of());}
    private static List<PlayerContext> readers(){return List.of(context(A),context(D),context(C),context(CITIZEN),context(SPECTATOR));}
    private void vote(String instance,UUID player,String node,String choice){engine.choose(instance,player,node,choice,context(player));}
    private List<StoryInstance> stories(UUID player,String id){return engine.instances(player).stream().filter(instance->instance.definition().id().equals(id)).toList();}
    private StoryInstance onlyCodex(UUID player){List<StoryInstance> stories=stories(player,"historical-codex");assertEquals(1,stories.size());return stories.getFirst();}
    private Map<String,String> past(UUID player){return engine.recollections(context(player),Set.of(PERSONAL,WORLD));}
    private long count(String table){return store.transaction(connection->{try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT COUNT(*) FROM "+table)){assertTrue(rows.next());return rows.getLong(1);}});}
    private Map<String,Long> counts(){Map<String,Long> counts=new TreeMap<>();for(String table:List.of("events","instances","outcomes","facts","story_choice_log","story_definitions","story_triggers","story_trigger_receipts","event_causes","story_trigger_blocks"))counts.put(table,count(table));return counts;}
    private List<String> oldChoiceRows(){return store.transaction(connection->{List<String> rows=new ArrayList<>();try(var statement=connection.createStatement();var result=statement.executeQuery("SELECT seq,id,instance_id,source_event_id,story_id,story_version,node_id,kind,actor_key,player_id,nation_id,scope_key,revision,choice_id,chosen_at,committed_by,provenance FROM story_choice_log WHERE story_id='last-heir' AND story_version='1' ORDER BY seq")){while(result.next()){List<String> row=new ArrayList<>();for(int column=1;column<=17;column++)row.add(result.getString(column));rows.add(SqliteStore.json(row));}}return List.copyOf(rows);});}
}
