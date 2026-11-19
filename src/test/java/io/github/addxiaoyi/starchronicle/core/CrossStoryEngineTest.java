package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.config.*;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition.*;
import io.github.addxiaoyi.starchronicle.npc.*;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real engine/database contracts for completed choices, derived inboxes and local atomicity. */
class CrossStoryEngineTest {
    @TempDir Path directory;
    static final UUID A=UUID.fromString("00000000-0000-0000-0000-000000000001"),B=UUID.fromString("00000000-0000-0000-0000-000000000002"),C=UUID.fromString("00000000-0000-0000-0000-000000000003"),D=UUID.fromString("00000000-0000-0000-0000-000000000004");
    static final Instant NOW=Instant.parse("2026-11-20T08:00:00Z");
    static final String PERSONAL="past.player.choice.chapter-a/1/vote",WORLD="past.resolution.world.chapter-a/1/vote",NATION="past.resolution.nation.chapter-a/1/vote";
    SqliteStore store;RuleEngine engine;NpcLedger npcs;List<StoryDefinition> definitions;boolean withNpcs;
    @AfterEach void close(){if(engine!=null)engine.close();else if(store!=null)store.close();}
    PlayerContext context(UUID player,String nation){return new PlayerContext(player,nation,Map.of(),Set.of());}
    PlayerContext context(UUID player,String nation,Map<String,String> facts){return new PlayerContext(player,nation,facts,Set.of());}
    WorldEvent event(String id,String type,Set<UUID> players,Set<String> nations){return new WorldEvent(id,type,NOW,players,nations,Map.of(),"CrossStoryEngineTest","committed source="+id);}
    Condition equal(String fact,String value){return new Condition(null,null,null,fact,"eq",value);}
    Condition all(Condition...conditions){return new Condition(List.of(conditions),null,null,null,null,null);}
    StoryDefinition chapter(Scope scope,int quorum,String version) {
        Map<String,Choice> choices=new LinkedHashMap<>();
        for(String choice:List.of("yes","no")) {
            // A later inbox failure must roll back already-applied honor/memory and the NPC
            // reaction of this choice-level historical effect, not just a terminal-node effect.
            List<Effect> effects=List.of(new Effect("chapter-honor","honor","player","5",null),
                new Effect("chapter-memory","memory","cross-story.route",choice,null),
                new Effect("chapter-result","history","A_DONE","Council route="+choice,null));
            choices.put(choice,new Choice(choice,"end",null,null,effects));
        }
        return new StoryDefinition("chapter-a",version,"Chapter A",scope,List.of("ROOT"),null,"vote",0,
            Map.of("vote",new Node("Council",null,null,quorum,choices,List.of(),false),"end",new Node("Completed",null,null,1,Map.of(),List.of(),true)));
    }
    StoryDefinition followup(String id,Condition condition) {
        return new StoryDefinition(id,"1",id,Scope.PLAYER,List.of("A_DONE"),condition,"end",0,
            Map.of("end",new Node("Follow-up completed",null,null,1,Map.of(),List.of(new Effect("followup-honor","honor","player","7",null)),true)));
    }
    NpcCatalog npcCatalog() {
        var profile=new NpcCatalog.Profile("Archivist","Witness of the actual council choice","library","scribe",List.of(),List.of(),
            List.of(new NpcCatalog.Dialogue("approved",10,equal(PERSONAL,"yes"),"Your recorded vote was yes."),new NpcCatalog.Dialogue("ordinary",0,null,"The archive is open.")),
            List.of(new NpcCatalog.Reaction("remember-own-choice",List.of("A_DONE"),equal(PERSONAL,"yes"),7,3,0,2,null)));
        return NpcCatalog.of(Map.of("archivist",profile));
    }
    void start(boolean useNpcs,StoryDefinition...rules) {
        definitions=List.of(rules);withNpcs=useNpcs;
        for(var rule:definitions)ConfigLoader.validate(rule,ignored->false,ignored->false);
        assertDoesNotThrow(()->ConfigLoader.validateAll(definitions),"Fixtures must be an acyclic, fully valid story directory");
        open();
    }
    void open(){store=new SqliteStore(directory.resolve("cross-story.db"));npcs=withNpcs?new NpcLedger(store,npcCatalog()):null;engine=new RuleEngine(store,definitions,Clock.fixed(NOW,ZoneOffset.UTC),npcs);}
    void reopen(){engine.close();engine=null;store=null;open();}
    StoryInstance story(UUID player,String id){return engine.instances(player).stream().filter(i->i.definition().id().equals(id)).findFirst().orElseThrow();}
    List<StoryInstance> stories(UUID player,String id){return engine.instances(player).stream().filter(i->i.definition().id().equals(id)).toList();}
    long count(String table){return store.transaction(connection->{try(var s=connection.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM "+table)){assertTrue(r.next());return r.getLong(1);}});}
    Map<String,Long> counts(){Map<String,Long> result=new TreeMap<>();for(String table:List.of("events","event_players","event_nations","instances","outcomes","facts","story_triggers","story_trigger_receipts","event_causes","story_trigger_blocks","story_choice_log"))result.put(table,count(table));return result;}
    Map<String,String> past(UUID player,String nation,String...refs){return store.transaction(connection->ChoiceLedger.read(connection,player,nation,List.of(refs)));}
    void sql(String text){store.transaction(connection->{try(var s=connection.createStatement()){s.execute(text);}return null;});}
    void choose(String instance,UUID player,String nation,String choice){engine.choose(instance,player,"vote",choice,context(player,nation));}
    NpcView archivist(UUID player,String nation){return npcs.view(player,"archivist",context(player,nation),engine.worldFacts());}

    @Test void offlineRootAndDerivedFollowupSurviveRestartThenConsumeBothPersonalAndWorldPastFactsOnce() {
        start(false,chapter(Scope.WORLD,1,"1"),followup("personal-and-world",all(equal(PERSONAL,"yes"),equal(WORLD,"yes"))));
        WorldEvent root=event("offline-root","ROOT",Set.of(A),Set.of("river"));
        assertTrue(engine.accept(root,List.of()).instanceIds().isEmpty());assertEquals(1,engine.pendingTriggerCount());reopen();
        engine.resumeTriggers(List.of(context(A,"river")));String chapter=story(A,"chapter-a").id();choose(chapter,A,"river","yes");
        assertTrue(story(A,"chapter-a").completed());assertEquals("5",engine.facts(A).get("player.honor"));assertEquals(2,count("story_choice_log"));
        WorldEvent derived=engine.history(null,null,0,10).stream().filter(e->e.type().equals("A_DONE")).findFirst().orElseThrow();
        assertEquals(Set.of(A),derived.participants());assertTrue(engine.pendingTriggerCount()>0);assertTrue(stories(A,"personal-and-world").isEmpty());
        long pending=engine.pendingTriggerCount();assertTrue(engine.resumeTriggers(List.of()).instanceIds().isEmpty());assertEquals(pending,engine.pendingTriggerCount());reopen();
        engine.resumeTriggers(List.of(context(A,"river")));assertEquals(1,stories(A,"personal-and-world").size());assertEquals("12",engine.facts(A).get("player.honor"));
        engine.resumeTriggers(List.of(context(D,"river")));assertTrue(stories(D,"personal-and-world").isEmpty(),"A later citizen has no fabricated personal vote");
        long log=count("story_choice_log"),receipts=count("outcomes");
        for(int i=0;i<3;i++){assertTrue(engine.accept(root,List.of(context(A,"river"))).duplicate());assertTrue(engine.accept(derived,List.of(context(A,"river"))).duplicate());engine.resumeTriggers(List.of(context(A,"river")));}
        assertThrows(IllegalStateException.class,()->choose(chapter,A,"river","yes"));assertEquals(log,count("story_choice_log"));assertEquals(receipts,count("outcomes"));
        assertEquals(2,count("events"));assertEquals("12",engine.facts(A).get("player.honor"));assertEquals(1,stories(A,"personal-and-world").size());
    }

    @Test void losingBallotDoesNotBecomeTheWorldDecisionAndAnUnvotedMemberHasOnlyTheSharedFollowup() {
        start(false,chapter(Scope.WORLD,2,"1"),followup("personal-next",equal(PERSONAL,"yes")),followup("world-next",equal(WORLD,"yes")));
        WorldEvent root=event("mixed-council","ROOT",Set.of(A,B,C,D),Set.of("river","hill"));List<PlayerContext> audience=List.of(context(A,"river"),context(B,"hill"),context(C,"river"),context(D,"hill"));
        String id=engine.accept(root,audience).instanceIds().getFirst();choose(id,B,"hill","no");choose(id,A,"river","yes");choose(id,C,"river","yes");
        assertEquals(Map.of(PERSONAL,"no",WORLD,"yes"),past(B,"hill",PERSONAL,WORLD));assertEquals(Map.of(WORLD,"yes"),past(D,"hill",PERSONAL,WORLD));
        assertEquals("no",story(B,"chapter-a").choices().get("vote/"+B));assertEquals("yes",story(B,"chapter-a").choices().get("vote"));
        reopen();engine.resumeTriggers(audience);
        for(UUID player:List.of(A,C)){assertEquals(1,stories(player,"personal-next").size());assertEquals("19",engine.facts(player).get("player.honor"));}
        for(UUID player:List.of(B,D)){assertTrue(stories(player,"personal-next").isEmpty());assertEquals(1,stories(player,"world-next").size());assertEquals("12",engine.facts(player).get("player.honor"));}
        assertEquals(4,count("story_choice_log"));Map<String,Long> before=counts();for(int i=0;i<3;i++)engine.resumeTriggers(audience);assertEquals(before,counts());
    }

    @Test void canonicalPastOverridesWrongValuesAndDeletesFabricatedPersonalFactsWhenNoVoteExists() {
        start(false,chapter(Scope.WORLD,1,"1"),followup("guarded",all(equal(PERSONAL,"yes"),equal(WORLD,"yes"))));
        String id=engine.accept(event("injection-root","ROOT",Set.of(A),Set.of("river")),List.of(context(A,"river"))).instanceIds().getFirst();choose(id,A,"river","yes");
        sql("INSERT INTO facts VALUES('WORLD','"+PERSONAL+"','yes')");
        PlayerContext forged=context(D,"river",Map.of(PERSONAL,"yes",WORLD,"yes"));engine.resumeTriggers(List.of(forged));
        assertTrue(stories(D,"guarded").isEmpty());assertFalse(engine.facts(D).containsKey("player.honor"));
        PlayerContext liesAboutActualVote=context(A,"river",Map.of(PERSONAL,"no",WORLD,"no"));engine.resumeTriggers(List.of(liesAboutActualVote));
        assertEquals(1,stories(A,"guarded").size());assertEquals("12",engine.facts(A).get("player.honor"));
    }

    @Test void nationalResolutionUsesEachCurrentNationAndCannotBorrowAnotherCountrysYesDecision() {
        start(false,chapter(Scope.NATION,1,"1"),followup("national-next",equal(NATION,"yes")));
        WorldEvent root=event("two-countries","ROOT",Set.of(A,B),Set.of("river","hill"));
        engine.accept(root,List.of(context(A,"river")));choose(story(A,"chapter-a").id(),A,"river","yes");
        // This independently evidenced A_DONE makes hill an eligible audience but cannot
        // supply hill with river's actual national decision.
        WorldEvent probe=event("national-probe","A_DONE",Set.of(B),Set.of("hill"));engine.accept(probe,List.of(context(B,"hill")));
        assertTrue(stories(B,"national-next").isEmpty());engine.resumeTriggers(List.of(context(A,"river"),context(B,"hill")));
        assertEquals(1,stories(A,"national-next").size());assertTrue(stories(B,"national-next").isEmpty());
        choose(story(B,"chapter-a").id(),B,"hill","no");engine.resumeTriggers(List.of(context(A,"river"),context(B,"hill")));
        assertEquals(Map.of(NATION,"yes"),past(A,"river",NATION));assertEquals(Map.of(NATION,"no"),past(B,"hill",NATION));
        assertTrue(stories(B,"national-next").isEmpty());assertEquals("5",engine.facts(B).get("player.honor"));assertEquals("12",engine.facts(A).get("player.honor"));
        assertEquals(4,count("story_choice_log"));
    }

    @Test void aCompletedOldVersionCannotSatisfyTheNewVersionsFollowupEvenWithInjectedPastFacts() {
        start(false,chapter(Scope.WORLD,1,"1"));
        String first=engine.accept(event("version-one","ROOT",Set.of(A),Set.of("river")),List.of(context(A,"river"))).instanceIds().getFirst();choose(first,A,"river","yes");
        String v2="past.player.choice.chapter-a/2/vote";List<StoryDefinition> next=List.of(chapter(Scope.WORLD,1,"2"),followup("version-two-next",equal(v2,"yes")));
        assertDoesNotThrow(()->ConfigLoader.validateAll(next));engine.reload(next);definitions=next;
        engine.accept(event("before-version-two","A_DONE",Set.of(A),Set.of("river")),List.of(context(A,"river",Map.of(v2,"yes"))));assertTrue(stories(A,"version-two-next").isEmpty());
        String second=engine.accept(event("version-two","ROOT",Set.of(A),Set.of("river")),List.of(context(A,"river"))).instanceIds().getFirst();choose(second,A,"river","no");
        reopen();engine.resumeTriggers(List.of(context(A,"river",Map.of(v2,"yes"))));assertTrue(stories(A,"version-two-next").isEmpty());
        assertEquals(Map.of(PERSONAL,"yes",v2,"no"),past(A,"river",PERSONAL,v2));assertEquals(4,count("story_choice_log"));assertEquals("10",engine.facts(A).get("player.honor"));
    }

    @Test void choiceLevelHistoryNpcReactionSeesTheNewlyCompletedPersonalVoteInTheSameTransaction() {
        start(true,chapter(Scope.WORLD,1,"1"),followup("npc-next",equal(PERSONAL,"yes")));
        String id=engine.accept(event("npc-ending","ROOT",Set.of(A,B),Set.of("river")),List.of(context(A,"river"),context(B,"river"))).instanceIds().getFirst();
        assertEquals(0,archivist(A,"river").reputation());choose(id,A,"river","yes");
        assertTrue(story(A,"chapter-a").completed());assertEquals(7,archivist(A,"river").reputation());assertEquals(3,archivist(A,"river").trust());
        assertEquals(2,archivist(A,"river").growth());assertEquals(0,archivist(B,"river").reputation());
        assertEquals("approved",archivist(A,"river").dialogueId());assertEquals("ordinary",archivist(B,"river").dialogueId());
        reopen();assertEquals(7,archivist(A,"river").reputation());assertEquals(2,archivist(B,"river").growth());
    }

    @Test void failedResolutionLogInsertRollsBackTheNewBallotAndEveryEngineSideEffectThenRetriesOnce() {
        start(false,chapter(Scope.WORLD,2,"1"),followup("after-log-retry",equal(PERSONAL,"yes")));
        String id=engine.accept(event("log-atomic","ROOT",Set.of(A,B),Set.of("river")),List.of(context(A,"river"),context(B,"river"))).instanceIds().getFirst();choose(id,A,"river","yes");
        StoryInstance before=story(A,"chapter-a");Map<String,Long> beforeCounts=counts();
        sql("CREATE TRIGGER reject_resolution BEFORE INSERT ON story_choice_log WHEN NEW.kind='RESOLUTION' BEGIN SELECT RAISE(ABORT,'injected choice log failure'); END");
        assertThrows(IllegalStateException.class,()->choose(id,B,"river","yes"));assertEquals(before,story(A,"chapter-a"));assertEquals(beforeCounts,counts());
        assertEquals(Map.of(A,"yes"),story(A,"chapter-a").votes());assertFalse(engine.facts(A).containsKey("player.honor"));assertTrue(engine.worldFacts().isEmpty());
        sql("DROP TRIGGER reject_resolution");choose(id,B,"river","yes");assertTrue(story(A,"chapter-a").completed());assertEquals(3,count("story_choice_log"));
        engine.resumeTriggers(List.of(context(A,"river"),context(B,"river")));assertEquals("12",engine.facts(A).get("player.honor"));assertEquals("12",engine.facts(B).get("player.honor"));
        Map<String,Long> committed=counts();assertThrows(IllegalStateException.class,()->choose(id,B,"river","yes"));assertEquals(committed,counts());
    }

    @Test void failedDerivedInboxInsertRollsBackCompletionHonorChoicesNpcMemoryHistoryAndCausality() {
        start(true,chapter(Scope.WORLD,1,"1"),followup("after-inbox-retry",all(equal(PERSONAL,"yes"),equal(WORLD,"yes"))));
        String id=engine.accept(event("inbox-atomic","ROOT",Set.of(A,B),Set.of("river")),List.of(context(A,"river"),context(B,"river"))).instanceIds().getFirst();
        StoryInstance before=story(A,"chapter-a");Map<String,Long> beforeCounts=counts();
        sql("CREATE TRIGGER reject_derived_inbox BEFORE INSERT ON story_triggers WHEN (SELECT type FROM events WHERE id=NEW.event_id)='A_DONE' BEGIN SELECT RAISE(ABORT,'injected derived inbox failure'); END");
        assertThrows(IllegalStateException.class,()->choose(id,A,"river","yes"));assertEquals(before,story(A,"chapter-a"));assertEquals(beforeCounts,counts());
        assertTrue(story(A,"chapter-a").votes().isEmpty());assertTrue(story(A,"chapter-a").choices().isEmpty());assertFalse(story(A,"chapter-a").completed());
        assertTrue(engine.facts(A).isEmpty());assertTrue(engine.worldFacts().isEmpty());assertEquals(0,archivist(A,"river").reputation());assertEquals(0,archivist(B,"river").growth());
        assertTrue(past(A,"river",PERSONAL,WORLD).isEmpty());assertEquals(1,engine.history(null,null,0,10).size());
        sql("DROP TRIGGER reject_derived_inbox");choose(id,A,"river","yes");
        assertTrue(story(A,"chapter-a").completed());assertEquals(2,count("story_choice_log"));assertEquals(2,count("events"));assertEquals(2,count("event_causes"));
        assertEquals("5",engine.facts(A).get("player.honor"));assertEquals("yes",engine.worldFacts().get("region.cross-story.route"));
        assertEquals(7,archivist(A,"river").reputation());assertEquals(2,archivist(B,"river").growth());assertTrue(engine.pendingTriggerCount()>0);
    }
}
