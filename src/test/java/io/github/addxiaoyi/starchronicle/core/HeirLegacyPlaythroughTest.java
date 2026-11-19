package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.config.*;
import io.github.addxiaoyi.starchronicle.history.EventCausality;
import io.github.addxiaoyi.starchronicle.npc.*;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Plays the actual packaged epic, archive follow-up and default NPC dialogue together. */
class HeirLegacyPlaythroughTest {
    @TempDir Path directory;
    private static final UUID A=UUID.fromString("00000000-0000-0000-0000-000000000001"),
            B=UUID.fromString("00000000-0000-0000-0000-000000000002"),
            C=UUID.fromString("00000000-0000-0000-0000-000000000003"),
            D=UUID.fromString("00000000-0000-0000-0000-000000000004"),
            CITIZEN=UUID.fromString("00000000-0000-0000-0000-000000000005"),
            SPECTATOR=UUID.fromString("00000000-0000-0000-0000-000000000006");
    private static final Instant NOW=Instant.parse("2026-11-20T09:00:00Z");
    private static final Map<UUID,String> NATIONS=Map.of(A,"river",B,"hill",C,"river",D,"hill",CITIZEN,"river",SPECTATOR,"coast");
    private static final String PERSONAL="past.player.choice.last-heir/1/council",WORLD="past.resolution.world.last-heir/1/council",
            FINAL="past.resolution.world.last-heir/1/preparations";
    private SqliteStore store;
    private RuleEngine engine;
    private NpcLedger npcs;
    private NpcCatalog catalog;
    private List<StoryDefinition> definitions;

    @BeforeEach void loadPackagedContent()throws Exception{
        Path stories=Files.createDirectory(directory.resolve("stories"));
        for(String name:List.of("last-heir.yml","heir-legacy.yml"))
            try(var resource=getClass().getResourceAsStream("/stories/"+name)){assertNotNull(resource,name);Files.copy(resource,stories.resolve(name));}
        Path npcFile=directory.resolve("npcs.yml");
        try(var resource=getClass().getResourceAsStream("/npcs.yml")){assertNotNull(resource);Files.copy(resource,npcFile);}
        definitions=ConfigLoader.load(stories,ignored->false,ignored->false);catalog=NpcCatalog.load(npcFile);
        assertEquals(Set.of("last-heir","heir-legacy"),definitions.stream().map(StoryDefinition::id).collect(java.util.stream.Collectors.toSet()));
        open(NOW);
    }
    private void open(Instant now){store=new SqliteStore(directory.resolve("heir-legacy.db"));npcs=new NpcLedger(store,catalog);engine=new RuleEngine(store,definitions,Clock.fixed(now,ZoneOffset.UTC),npcs);}
    private void reopen(Instant now){engine.close();engine=null;store=null;open(now);}
    @AfterEach void close(){if(engine!=null)engine.close();else if(store!=null)store.close();}
    private static PlayerContext context(UUID player){return new PlayerContext(player,NATIONS.get(player),Map.of("player.name",player.toString()),Set.of());}
    private static List<PlayerContext> participants(){return List.of(context(A),context(B),context(C),context(D));}
    private static List<PlayerContext> online(){return List.of(context(A),context(B),context(C),context(D),context(CITIZEN),context(SPECTATOR));}
    private static WorldEvent discovery(){return new WorldEvent("shipped-heir:discovery","ANCIENT_HEIR_DISCOVERED",NOW,Set.of(A,B,C,D),Set.of("river","hill"),Map.of("test-only","actual packaged content playthrough"),"HeirLegacyPlaythroughTest","Explicit test signal with four evidenced actors; no actual national mutation is claimed");}
    private List<StoryInstance> stories(UUID player,String id){return engine.instances(player).stream().filter(i->i.definition().id().equals(id)).toList();}
    private StoryInstance story(UUID player,String id){var stories=stories(player,id);assertEquals(1,stories.size(),player+" / "+id);return stories.getFirst();}
    private void choose(String instance,UUID player,String node,String choice){engine.choose(instance,player,node,choice,context(player));}
    private Map<String,String> past(UUID player){return store.transaction(c->ChoiceLedger.read(c,player,NATIONS.get(player),Set.of(PERSONAL,WORLD,FINAL)));}
    private NpcView heir(UUID player){return npcs.view(player,"ancient-heir",context(player),engine.worldFacts());}
    private WorldEvent history(String type){var events=engine.history(null,null,0,100).stream().filter(e->e.type().equals(type)).toList();assertEquals(1,events.size(),type);return events.getFirst();}
    private long count(String table){return store.transaction(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM "+table)){assertTrue(r.next());return r.getLong(1);}});}
    private Map<String,Long> counts(){Map<String,Long> result=new TreeMap<>();for(String table:List.of("events","event_players","event_nations","instances","outcomes","facts","story_triggers","story_trigger_receipts","story_choice_log","event_causes","story_trigger_blocks"))result.put(table,count(table));return result;}

    @Test void originalVotesDriveFourDurablePersonalArchivesWithoutTurningWorldAgreementIntoPersonalConsent(){
        WorldEvent source=discovery();String epic=engine.accept(source,participants()).instanceIds().getFirst();
        assertEquals(Set.of(A,B,C,D),story(A,"last-heir").members().keySet());
        choose(epic,A,"summons","convene");choose(epic,B,"summons","convene");
        choose(epic,D,"council","oppose");choose(epic,A,"council","support");choose(epic,B,"council","support");
        choose(epic,A,"preparations","restoration");choose(epic,B,"preparations","restoration");
        assertTrue(story(A,"last-heir").completed());assertEquals("oppose",story(D,"last-heir").choices().get("council/"+D));
        assertEquals("support",story(D,"last-heir").choices().get("council"));assertNull(story(C,"last-heir").choices().get("council/"+C));
        WorldEvent ending=history("HEIR_RESTORATION_SUPPORTED");
        assertEquals(Set.of(A,B,C,D),ending.participants());assertEquals(Set.of("river","hill"),ending.nations());
        assertEquals(new EventCausality.Trace(source.id(),source.id(),1,List.of("last-heir")),engine.causality(ending.id()));
        assertTrue(engine.history(null,null,0,100).stream().noneMatch(e->e.type().equals("RESTORATION_OPPOSED")),"One losing ballot is not an enacted collective opposition result");
        for(UUID player:List.of(A,B,C,D)){
            assertTrue(stories(player,"heir-legacy").isEmpty(),"The ending queues the archive; it does not recursively enter it");
            assertEquals("15",engine.facts(player).get("player.honor"));
        }
        assertTrue(engine.pendingTriggerCount()>0);

        // Reopen before any follow-up drain and never resubmit the ending event.
        reopen(NOW.plus(Duration.ofDays(1)));engine.resumeTriggers(online());
        Set<String> archiveIds=new HashSet<>();
        for(UUID player:List.of(A,B,C,D)){
            var archive=story(player,"heir-legacy");archiveIds.add(archive.id());assertEquals(StoryDefinition.Scope.PLAYER,archive.definition().scope());assertEquals("player:"+player,archive.scopeKey());assertEquals(Set.of(player),archive.members().keySet());assertEquals(ending.id(),archive.sourceEventId());assertEquals("archive",archive.node());assertFalse(archive.completed());
        }
        assertEquals(4,archiveIds.size());assertEquals(5,count("instances"));
        for(UUID witness:List.of(CITIZEN,SPECTATOR)){
            assertEquals("support",past(witness).get(WORLD),"The shared decision is readable globally, including by non-participants");
            assertEquals("restoration",past(witness).get(FINAL));assertNull(past(witness).get(PERSONAL));
            assertTrue(stories(witness,"heir-legacy").isEmpty(),"A world fact alone does not establish personal participation in the ending");
            assertTrue(stories(witness,"last-heir").isEmpty());assertFalse(engine.facts(witness).containsKey("player.honor"));
        }
        assertEquals(Map.of(PERSONAL,"support",WORLD,"support",FINAL,"restoration"),past(A));
        assertEquals(Map.of(PERSONAL,"oppose",WORLD,"support",FINAL,"restoration"),past(D));
        assertEquals(Map.of(WORLD,"support",FINAL,"restoration"),past(C));assertNull(past(C).get(PERSONAL));
        assertEquals("personal-dissent",heir(D).dialogueId());assertEquals("trusted-restoration",heir(A).dialogueId());
        assertEquals(25,heir(D).reputation());assertEquals(12,heir(D).trust());assertEquals(20,heir(D).growth());

        Map<String,Long> beforeInvalidChoices=counts();
        for(UUID voter:List.of(A,B,D))assertThrows(SecurityException.class,()->choose(story(voter,"heir-legacy").id(),voter,"archive","witness"));
        assertThrows(SecurityException.class,()->choose(story(C,"heir-legacy").id(),C,"archive","testimony"));
        assertEquals(beforeInvalidChoices,counts(),"Invalid personal-history branches must not append ballots, rewards or history");

        String aArchive=story(A,"heir-legacy").id(),cArchive=story(C,"heir-legacy").id(),dArchive=story(D,"heir-legacy").id();
        choose(aArchive,A,"archive","testimony");choose(cArchive,C,"archive","witness");choose(dArchive,D,"archive","reconcile");
        assertEquals("personal",story(A,"heir-legacy").node());assertEquals("witnessed",story(C,"heir-legacy").node());assertEquals("reconciled",story(D,"heir-legacy").node());
        assertEquals("议会证言保存者",engine.facts(A).get("title.imperial-testimony-keeper"));
        assertEquals("旧都议会见证者",engine.facts(C).get("title.imperial-history-witness"));
        assertEquals("旧都档案校读者",engine.facts(D).get("title.imperial-archive-reader"));
        Map<UUID,String> produced=Map.of(A,"HEIR_PERSONAL_TESTIMONY",C,"HEIR_WITNESS_ACCOUNT",D,"HEIR_RECORDS_RECONCILED");
        for(var outcome:produced.entrySet()){
            UUID player=outcome.getKey();assertTrue(story(player,"heir-legacy").completed());assertEquals("17",engine.facts(player).get("player.honor"));WorldEvent personal=history(outcome.getValue());
            assertEquals(Set.of(player),personal.participants());assertEquals(Set.of(NATIONS.get(player)),personal.nations());
            assertTrue(personal.evidence().contains("source="+ending.id()));
            assertEquals(new EventCausality.Trace(ending.id(),source.id(),2,List.of("last-heir","heir-legacy")),engine.causality(personal.id()));
        }
        assertEquals("15",engine.facts(B).get("player.honor"));assertFalse(story(B,"heir-legacy").completed());assertEquals("archive",story(B,"heir-legacy").node());assertEquals(6,count("events"));
        assertEquals("personal-dissent",heir(D).dialogueId());assertEquals("trusted-restoration",heir(A).dialogueId());assertTrue(engine.triggerBlocks(10).isEmpty());

        Map<String,Long> committed=counts();List<WorldEvent> committedHistory=engine.history(null,null,0,100);
        // Expire both shipped cooldowns: retained source receipts, not a cooldown, prevent replay.
        reopen(NOW.plus(Duration.ofDays(9)));
        for(int i=0;i<3;i++){
            assertTrue(engine.accept(source,participants()).duplicate());engine.resumeTriggers(online());
            assertThrows(IllegalStateException.class,()->choose(aArchive,A,"archive","testimony"));assertThrows(IllegalStateException.class,()->choose(cArchive,C,"archive","witness"));assertThrows(IllegalStateException.class,()->choose(dArchive,D,"archive","reconcile"));
        }
        assertEquals(committed,counts());assertEquals(committedHistory,engine.history(null,null,0,100));
        for(UUID player:List.of(A,C,D))assertEquals("17",engine.facts(player).get("player.honor"));
        assertEquals("15",engine.facts(B).get("player.honor"));assertEquals(4,new HashSet<>(List.of(story(A,"heir-legacy").id(),story(B,"heir-legacy").id(),story(C,"heir-legacy").id(),story(D,"heir-legacy").id())).size());
        assertTrue(stories(CITIZEN,"heir-legacy").isEmpty());assertTrue(stories(SPECTATOR,"heir-legacy").isEmpty());assertEquals("oppose",past(D).get(PERSONAL));assertNull(past(C).get(PERSONAL));assertEquals("personal-dissent",heir(D).dialogueId());
    }
}
