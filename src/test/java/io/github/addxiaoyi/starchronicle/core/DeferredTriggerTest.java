package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.config.*;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition.*;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** File-backed regression contracts for triggers recorded while their audience is unavailable. */
class DeferredTriggerTest {
    @TempDir Path directory;
    final UUID a=UUID.fromString("00000000-0000-0000-0000-000000000001"),b=UUID.fromString("00000000-0000-0000-0000-000000000002"),outsider=UUID.fromString("00000000-0000-0000-0000-000000000003");
    final MutableClock clock=new MutableClock();
    Path database(){return directory.resolve("deferred.db");}
    PlayerContext context(UUID player,String nation){return new PlayerContext(player,nation,Map.of(),Set.of());}
    PlayerContext score(UUID player,String nation,int score){return new PlayerContext(player,nation,Map.of("player.score",Integer.toString(score)),Set.of());}
    WorldEvent event(String id,Set<UUID> players,Set<String> nations,Map<String,String> facts){return new WorldEvent(id,"TRIGGER",Instant.parse("2026-11-20T00:00:00Z"),players,nations,facts,"DeferredTriggerTest","committed operation="+id);}
    StoryDefinition story(String id,Scope scope,Condition condition,boolean terminal,long cooldown) {
        var effects=List.of(new Effect("honor","honor","player","5",null),new Effect("history","history","CAMPAIGN_RESOLVED","Recorded campaign resolution",null));
        Map<String,Node> nodes=terminal?Map.of("end",new Node("End",null,null,1,Map.of(),effects,true)):
            Map.of("start",new Node("Shared start",null,null,2,Map.of("agree",new Choice("Agree","end",null,null,List.of())),List.of(),false),"end",new Node("End",null,null,1,Map.of(),effects,true));
        return new StoryDefinition(id,"1",id,scope,List.of("TRIGGER"),condition,terminal?"end":"start",cooldown,nodes);
    }
    long count(SqliteStore store,String table){return store.transaction(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM "+table)){r.next();return r.getLong(1);}});}

    @Test void offlineWorldTriggerSurvivesRestartThenTwoPlayersJoinTheSameInstance() {
        StoryDefinition rule=story("world",Scope.WORLD,null,false,0);WorldEvent source=event("offline-world",Set.of(a,b),Set.of("river","hill"),Map.of());
        try(var store=new SqliteStore(database())){var engine=new RuleEngine(store,List.of(rule),clock);assertTrue(engine.accept(source,List.of()).instanceIds().isEmpty());assertEquals(1,engine.pendingTriggerCount());assertTrue(engine.activeInstances().isEmpty());}
        String instance;
        try(var store=new SqliteStore(database())){var engine=new RuleEngine(store,List.of(rule),clock);instance=engine.resumeTriggers(List.of(context(a,"river"))).instanceIds().getFirst();assertEquals(Set.of(a),engine.instances(a).getFirst().members().keySet());assertEquals(0,engine.pendingTriggerCount());}
        try(var store=new SqliteStore(database())){var engine=new RuleEngine(store,List.of(rule),clock);engine.resumeTriggers(List.of(context(b,"hill")));assertEquals(instance,engine.instances(b).getFirst().id());assertEquals(Set.of(a,b),engine.instances(b).getFirst().members().keySet());assertEquals(1,count(store,"instances"));
            engine.choose(instance,a,"start","agree",context(a,"river"));engine.choose(instance,b,"start","agree",context(b,"hill"));assertEquals("5",engine.facts(a).get("player.honor"));assertEquals("5",engine.facts(b).get("player.honor"));}
    }
    @Test void oneNationComingOnlineDoesNotConsumeTheOtherNationsDeferredTrigger() {
        StoryDefinition rule=story("national",Scope.NATION,null,false,0);WorldEvent source=event("two-nations",Set.of(),Set.of("river","hill"),Map.of());
        try(var store=new SqliteStore(database())){var engine=new RuleEngine(store,List.of(rule),clock);engine.accept(source,List.of());assertEquals(2,engine.pendingTriggerCount());engine.resumeTriggers(List.of(context(a,"river")));assertEquals(1,engine.pendingTriggerCount());assertEquals("nation:river",engine.instances(a).getFirst().scopeKey());}
        try(var store=new SqliteStore(database())){var engine=new RuleEngine(store,List.of(rule),clock);engine.resumeTriggers(List.of(context(b,"hill")));assertEquals(0,engine.pendingTriggerCount());assertEquals("nation:hill",engine.instances(b).getFirst().scopeKey());assertNotEquals(engine.instances(a).getFirst().id(),engine.instances(b).getFirst().id());}
    }
    @Test void explicitlyEvidencedOfflinePlayerReceivesOwnTriggerAndOutsiderDoesNot() {
        StoryDefinition rule=story("personal",Scope.PLAYER,null,true,0);WorldEvent source=event("offline-player",Set.of(a,b),Set.of(),Map.of());
        try(var store=new SqliteStore(database())){var engine=new RuleEngine(store,List.of(rule),clock);engine.accept(source,List.of());assertEquals(2,engine.pendingTriggerCount());engine.resumeTriggers(List.of(context(outsider,"river")));assertTrue(engine.instances(outsider).isEmpty());assertEquals(2,engine.pendingTriggerCount());engine.resumeTriggers(List.of(context(a,"river")));assertEquals(1,engine.pendingTriggerCount());assertEquals("5",engine.facts(a).get("player.honor"));}
        try(var store=new SqliteStore(database())){var engine=new RuleEngine(store,List.of(rule),clock);engine.resumeTriggers(List.of(context(b,"hill")));assertEquals("5",engine.facts(b).get("player.honor"));assertNotEquals(engine.instances(a).getFirst().id(),engine.instances(b).getFirst().id());assertEquals(0,engine.pendingTriggerCount());}
    }
    @Test void conditionsMayBecomeEligibleLaterWithoutResubmittingOrForgingSourceEvidence() {
        Condition condition=new Condition(null,null,null,"player.score","gte","10");StoryDefinition rule=story("later",Scope.WORLD,condition,false,0);WorldEvent source=event("wait-for-score",Set.of(a),Set.of("river"),Map.of());
        try(var store=new SqliteStore(database())){var engine=new RuleEngine(store,List.of(rule),clock);engine.accept(source,List.of(score(a,"river",0)));assertEquals(1,engine.pendingTriggerCount());assertTrue(engine.activeInstances().isEmpty());engine.resumeTriggers(List.of(score(a,"river",9)));assertEquals(1,engine.pendingTriggerCount());}
        try(var store=new SqliteStore(database())){var engine=new RuleEngine(store,List.of(rule),clock);engine.resumeTriggers(List.of(score(a,"river",10)));assertEquals(source.id(),engine.instances(a).getFirst().sourceEventId());assertEquals(0,engine.pendingTriggerCount());assertEquals(source,engine.history(null,null,0,10).getFirst());}
    }
    @Test void duplicateSourceCanDeliverUnfinishedTriggerButNeverReplayHistoryOrRewards() {
        Condition condition=new Condition(null,null,null,"player.score","gte","10");StoryDefinition rule=story("replay",Scope.PLAYER,condition,true,0);WorldEvent source=event("stable-source",Set.of(a),Set.of(),Map.of());
        try(var store=new SqliteStore(database())){var engine=new RuleEngine(store,List.of(rule),clock);engine.accept(source,List.of(score(a,"",0)));assertEquals(1,engine.pendingTriggerCount());}
        try(var store=new SqliteStore(database())){var engine=new RuleEngine(store,List.of(rule),clock);var delivered=engine.accept(source,List.of(score(a,"",10)));assertTrue(delivered.duplicate());assertEquals(1,delivered.instanceIds().size());assertEquals("5",engine.facts(a).get("player.honor"));assertEquals(2,engine.history(null,null,0,10).size());long receipts=count(store,"outcomes");
            for(int i=0;i<5;i++){assertTrue(engine.accept(source,List.of(score(a,"",10))).duplicate());engine.resumeTriggers(List.of(score(a,"",10)));}assertEquals(receipts,count(store,"outcomes"));assertEquals("5",engine.facts(a).get("player.honor"));assertEquals(1,count(store,"instances"));assertEquals(2,engine.history(null,null,0,10).size());}
    }
    @Test void removedOrReloadedRuleDoesNotReplaceThePinnedUnfinishedTriggerDefinition() {
        StoryDefinition original=story("pinned",Scope.WORLD,null,true,0);WorldEvent source=event("before-reload",Set.of(a),Set.of(),Map.of());
        try(var store=new SqliteStore(database())){var engine=new RuleEngine(store,List.of(original),clock);engine.accept(source,List.of());StoryDefinition changed=new StoryDefinition(original.id(),"2","Changed",Scope.WORLD,List.of("TRIGGER"),null,"end",0,Map.of("end",new Node("Changed",null,null,1,Map.of(),List.of(new Effect("honor","honor","player","500",null)),true)));engine.reload(List.of(changed));assertEquals(List.of(original),engine.pendingDefinitions());engine.reload(List.of());}
        try(var store=new SqliteStore(database())){var engine=new RuleEngine(store,List.of(),clock);assertEquals(List.of(original),engine.pendingDefinitions());engine.resumeTriggers(List.of(context(a,"")));assertEquals("1",engine.instances(a).getFirst().definition().version());assertEquals("5",engine.facts(a).get("player.honor"));assertTrue(engine.pendingDefinitions().isEmpty());}
    }
    @Test void pendingDefinitionRetainsOldPermissionRequirementsForRuntimeSnapshotCollection() {
        var node=new Node("Permission-gated",null,null,1,Map.of(),List.of(new Effect("honor","honor","player","5","old.permission")),true);StoryDefinition rule=new StoryDefinition("old-permission","1","Old permission",Scope.PLAYER,List.of("TRIGGER"),null,"end",0,Map.of("end",node));
        try(var store=new SqliteStore(database())){var engine=new RuleEngine(store,List.of(rule),clock);engine.accept(event("permission-source",Set.of(a),Set.of(),Map.of()),List.of());engine.reload(List.of());assertEquals("old.permission",engine.pendingDefinitions().getFirst().nodes().get("end").effects().getFirst().permission());engine.resumeTriggers(List.of(context(a,"")));assertEquals(1,engine.pendingTriggerCount());
            engine.resumeTriggers(List.of(new PlayerContext(a,"",Map.of(),Set.of("old.permission"))));assertEquals("5",engine.facts(a).get("player.honor"));}
    }
    @Test void failedInitialQueueWriteRollsBackEventPendingAndReceiptsTogether() {
        StoryDefinition rule=story("atomic",Scope.WORLD,null,false,0);WorldEvent source=event("failed-queue",Set.of(a),Set.of(),Map.of());
        try(var store=new SqliteStore(database())){var engine=new RuleEngine(store,List.of(rule),clock);store.transaction(c->{try(var s=c.createStatement()){s.execute("CREATE TRIGGER reject_trigger BEFORE INSERT ON story_triggers BEGIN SELECT RAISE(ABORT,'simulated queue storage failure'); END");}return null;});
            assertThrows(IllegalStateException.class,()->engine.accept(source,List.of()));assertTrue(engine.history(null,null,0,10).isEmpty());assertEquals(0,engine.pendingTriggerCount());assertEquals(0,count(store,"story_trigger_receipts"));
            store.transaction(c->{try(var s=c.createStatement()){s.execute("DROP TRIGGER reject_trigger");}return null;});assertFalse(engine.accept(source,List.of()).duplicate());assertEquals(1,engine.pendingTriggerCount());assertEquals(1,engine.history(null,null,0,10).size());}
    }
    @Test void nationAndOfflineActorSeedsCannotReopenSameCompletedScope() {
        StoryDefinition rule=story("one-national-result",Scope.NATION,null,true,0);WorldEvent source=event("two-seeds-one-scope",Set.of(a,b),Set.of(),Map.of());
        // With no explicit source country, actual online B seeds river and actor:B. Both
        // seeds must share a scope receipt even when the first seed completes immediately.
        try(var store=new SqliteStore(database())){var engine=new RuleEngine(store,List.of(rule),clock);engine.accept(source,List.of(context(b,"river")));assertEquals("5",engine.facts(b).get("player.honor"));assertEquals(1,engine.pendingTriggerCount(),"Only offline actor A's seed should remain");assertEquals(1,count(store,"instances"));}
        try(var store=new SqliteStore(database())){var engine=new RuleEngine(store,List.of(rule),clock);engine.resumeTriggers(List.of(context(a,"river")));assertEquals(0,engine.pendingTriggerCount());assertEquals(1,count(store,"instances"));assertTrue(engine.instances(a).isEmpty());assertFalse(engine.facts(a).containsKey("player.honor"));assertEquals(2,engine.history(null,null,0,10).size());}
    }
    @Test void evidencedParticipantCannotCarryExplicitSourceNationStoryIntoAnotherNation() {
        StoryDefinition rule=story("national-isolation",Scope.NATION,null,false,0);WorldEvent source=event("river-source",Set.of(a),Set.of("river"),Map.of());
        try(var store=new SqliteStore(database())){var engine=new RuleEngine(store,List.of(rule),clock);engine.accept(source,List.of(context(a,"hill")));assertEquals(1,engine.pendingTriggerCount());assertTrue(engine.instances(a).isEmpty());engine.resumeTriggers(List.of(context(a,"hill")));assertTrue(engine.activeInstances().isEmpty());}
        try(var store=new SqliteStore(database())){var engine=new RuleEngine(store,List.of(rule),clock);engine.resumeTriggers(List.of(context(b,"river")));assertEquals("nation:river",engine.instances(b).getFirst().scopeKey());assertEquals(0,engine.pendingTriggerCount());engine.resumeTriggers(List.of(context(a,"hill")));assertTrue(engine.instances(a).isEmpty());assertEquals(1,count(store,"instances"));assertEquals(Set.of(a),engine.history(null,null,0,10).getFirst().participants());}
    }
    @Test void moreThanOneBatchOfIneligiblePendingTriggersDoesNotStarveEligibleLastRow() {
        Condition condition=new Condition(null,null,null,"event.eligible","eq","yes");StoryDefinition rule=story("fair",Scope.WORLD,condition,false,0);
        List<String> ids=new ArrayList<>();for(int i=0;i<131;i++)ids.add("fair-event-"+i);
        String last=ids.stream().max(Comparator.comparing(id->RuleEngine.stable(RuleEngine.stable(id,rule.id(),rule.version()),"world"))).orElseThrow();
        try(var store=new SqliteStore(database())){var engine=new RuleEngine(store,List.of(rule),clock);for(String id:ids)engine.accept(event(id,Set.of(),Set.of(),Map.of("audience","GLOBAL","eligible",id.equals(last)?"yes":"no")),List.of());assertEquals(131,engine.pendingTriggerCount());
            engine.resumeTriggers(List.of(context(a,"river")));assertTrue(engine.activeInstances().isEmpty(),"Eligible trigger was intentionally ordered beyond the first 100 rows");engine.resumeTriggers(List.of(context(a,"river")));assertEquals(1,engine.activeInstances().size());assertEquals(last,engine.activeInstances().getFirst().sourceEventId());}
    }
    @Test void globalAudienceCanEnrollWitnessesWithoutFabricatingHistoricalParticipants() {
        StoryDefinition rule=story("broadcast",Scope.WORLD,null,false,0);WorldEvent source=event("global-source",Set.of(),Set.of(),Map.of("audience","GLOBAL"));
        try(var store=new SqliteStore(database())){var engine=new RuleEngine(store,List.of(rule),clock);engine.accept(source,List.of());engine.resumeTriggers(List.of(context(a,"river")));engine.resumeTriggers(List.of(context(b,"hill")));StoryInstance campaign=engine.instances(a).getFirst();assertEquals(Set.of(a,b),campaign.members().keySet());WorldEvent stored=engine.history(null,null,0,10).getFirst();assertTrue(stored.participants().isEmpty());assertTrue(stored.nations().isEmpty());assertEquals(source,stored);
            engine.choose(campaign.id(),a,"start","agree",context(a,"river"));engine.choose(campaign.id(),b,"start","agree",context(b,"hill"));WorldEvent result=engine.history(null,null,0,10).stream().filter(e->e.type().equals("CAMPAIGN_RESOLVED")).findFirst().orElseThrow();assertEquals(Set.of(a,b),result.participants());assertEquals(Set.of("river","hill"),result.nations());}
    }
    @Test void nonGlobalSourceStillRejectsUnrelatedAudienceInsteadOfForgingAdmission() {
        StoryDefinition rule=story("restricted",Scope.WORLD,null,false,0);WorldEvent source=event("restricted-source",Set.of(a),Set.of(),Map.of());
        try(var store=new SqliteStore(database())){var engine=new RuleEngine(store,List.of(rule),clock);assertThrows(IllegalArgumentException.class,()->engine.accept(source,List.of(context(outsider,"river"))));assertTrue(engine.history(null,null,0,10).isEmpty());engine.accept(source,List.of());engine.resumeTriggers(List.of(context(outsider,"river")));assertEquals(1,engine.pendingTriggerCount());assertTrue(engine.instances(outsider).isEmpty());}
    }
    @Test void cooldownBlocksButDoesNotConsumeAPendingTriggerUntilItCanActuallyStart() {
        StoryDefinition rule=story("cooling",Scope.PLAYER,null,true,3600);WorldEvent first=event("cooldown-first",Set.of(a),Set.of(),Map.of()),second=event("cooldown-second",Set.of(a),Set.of(),Map.of());
        try(var store=new SqliteStore(database())){var engine=new RuleEngine(store,List.of(rule),clock);engine.accept(first,List.of(context(a,"")));assertEquals("5",engine.facts(a).get("player.honor"));engine.accept(second,List.of());engine.resumeTriggers(List.of(context(a,"")));assertEquals(1,engine.pendingTriggerCount(),"A cooldown is not a completed trigger delivery");assertEquals(1,count(store,"instances"));clock.advance(Duration.ofHours(2));engine.resumeTriggers(List.of(context(a,"")));assertEquals(0,engine.pendingTriggerCount());assertEquals(2,count(store,"instances"));assertEquals("10",engine.facts(a).get("player.honor"));}
    }
    @Test void repeatedGlobalNationWildcardDeliveryUsesScopeReceiptsAfterCompletion() {
        StoryDefinition rule=story("national-broadcast",Scope.NATION,null,true,0);WorldEvent source=event("global-national-source",Set.of(),Set.of(),Map.of("audience","GLOBAL"));
        try(var store=new SqliteStore(database())){var engine=new RuleEngine(store,List.of(rule),clock);engine.accept(source,List.of());engine.resumeTriggers(List.of(context(a,"river")));long receipts=count(store,"outcomes");for(int i=0;i<4;i++)engine.resumeTriggers(List.of(context(a,"river"),context(b,"river")));assertEquals(1,count(store,"instances"));assertEquals("5",engine.facts(a).get("player.honor"));assertFalse(engine.facts(b).containsKey("player.honor"));assertEquals(receipts,count(store,"outcomes"));
            engine.resumeTriggers(List.of(context(outsider,"hill")));assertEquals(2,count(store,"instances"));assertEquals("5",engine.facts(outsider).get("player.honor"));assertEquals(3,engine.history(null,null,0,10).size());}
    }
    @Test void globalHistoryAudienceDoesNotWidenAnExplicitNationalStoryScope() {
        StoryDefinition template=story("river-only-broadcast",Scope.NATION,null,false,0);
        StoryDefinition rule=new StoryDefinition(template.id(),template.version(),template.title(),Scope.NATION,List.of("WORLD_EVENT"),
            template.condition(),template.start(),template.cooldownSeconds(),template.nodes());
        WorldEvent source=new WorldEvent("global-river-world-event","WORLD_EVENT",clock.instant(),Set.of(),Set.of("river"),
            Map.of("audience","GLOBAL"),"DeferredTriggerTest","committed operation=river-national-event; broadcast=global");
        PlayerContext beta=context(outsider,"beta");
        try(var store=new SqliteStore(database())) {
            var engine=new RuleEngine(store,List.of(rule),clock);
            ProcessReport accepted=assertDoesNotThrow(()->engine.accept(source,List.of(beta)));
            assertFalse(accepted.duplicate());assertTrue(accepted.instanceIds().isEmpty());
            assertEquals(source,engine.history(null,null,0,10).getFirst(),"A foreign witness may receive the GLOBAL history broadcast");
            assertEquals(1,engine.pendingTriggerCount(),"An explicit river source must not also create an unrestricted nations wildcard");
            assertTrue(engine.instances(outsider).isEmpty());assertEquals(0,count(store,"instances"));
        }
        try(var store=new SqliteStore(database())) {
            var engine=new RuleEngine(store,List.of(rule),clock);
            assertTrue(engine.resumeTriggers(List.of(beta)).instanceIds().isEmpty());assertEquals(1,engine.pendingTriggerCount());
            engine.resumeTriggers(List.of(context(a,"river")));StoryInstance river=engine.instances(a).getFirst();
            assertEquals("nation:river",river.scopeKey());assertEquals(source.id(),river.sourceEventId());assertEquals(0,engine.pendingTriggerCount());
            engine.resumeTriggers(List.of(beta,context(b,"river")));
            assertEquals(Set.of(a,b),engine.instances(a).getFirst().members().keySet());assertTrue(engine.instances(outsider).isEmpty());
            assertTrue(engine.accept(source,List.of(beta)).duplicate());assertEquals(1,count(store,"instances"));
            assertEquals(List.of(source),engine.history(null,null,0,10));assertTrue(engine.history(null,null,0,10).getFirst().participants().isEmpty());
        }
    }
    @Test void offlineNationalTechnologyObservationStartsShippedScholarStoryOnceForEachLaterCitizen() throws Exception {
        Path definitions=Files.createDirectory(directory.resolve("shipped-stories"));
        try(var input=getClass().getResourceAsStream("/stories/silk-road-scholar.yml")) {
            assertNotNull(input);Files.copy(input,definitions.resolve("silk-road-scholar.yml"));
        }
        StoryDefinition rule=ConfigLoader.load(definitions,ignored->false,ignored->false).getFirst();
        WorldEvent source=new WorldEvent("observed-tech-unlock","TECHNOLOGY_BREAKTHROUGH",clock.instant(),Set.of(),Set.of("river"),
            Map.of("technology","navigation","time_basis","OBSERVED"),"StarCoreSnapshot","before=locked; after=unlocked; observation=17");
        PlayerContext scholarA=new PlayerContext(a,"river",Map.of("player.role","scholar","tech.stage","3"),Set.of());
        PlayerContext scholarB=new PlayerContext(b,"river",scholarA.facts(),Set.of());
        PlayerContext foreignScholar=new PlayerContext(outsider,"hill",scholarA.facts(),Set.of());
        try(var store=new SqliteStore(database())) {
            var engine=new RuleEngine(store,List.of(rule),clock);engine.accept(source,List.of());
            assertEquals(1,engine.pendingTriggerCount(),"An empty observed participant set must retain the country's future PLAYER audience");
            assertTrue(engine.activeInstances().isEmpty());assertEquals(source,engine.history(null,null,0,10).getFirst());
        }
        String firstInstance;
        try(var store=new SqliteStore(database())) {
            var engine=new RuleEngine(store,List.of(rule),clock);engine.resumeTriggers(List.of(foreignScholar));
            assertTrue(engine.instances(outsider).isEmpty());assertEquals(0,count(store,"instances"));
            engine.resumeTriggers(List.of(scholarA));firstInstance=engine.instances(a).getFirst().id();
            assertEquals("player:"+a,engine.instances(a).getFirst().scopeKey());
            engine.choose(firstInstance,a,"discovery","research",scholarA);engine.choose(firstInstance,a,"research","share",scholarA);
            assertEquals("12",engine.facts(a).get("player.honor"));
            assertEquals(1,engine.pendingTriggerCount(),"One citizen cannot consume another citizen's national audience seed");
        }
        try(var store=new SqliteStore(database())) {
            var engine=new RuleEngine(store,List.of(rule),clock);engine.resumeTriggers(List.of(scholarB,scholarA,foreignScholar));
            String secondInstance=engine.instances(b).getFirst().id();assertNotEquals(firstInstance,secondInstance);
            assertEquals("player:"+b,engine.instances(b).getFirst().scopeKey());
            engine.choose(secondInstance,b,"discovery","research",scholarB);engine.choose(secondInstance,b,"research","share",scholarB);
            long outcomes=count(store,"outcomes");
            for(int i=0;i<3;i++) {
                engine.resumeTriggers(List.of(scholarA,scholarB,foreignScholar));
                assertTrue(engine.accept(source,List.of(scholarA,scholarB)).duplicate());
            }
            assertEquals(2,count(store,"instances"));assertEquals(outcomes,count(store,"outcomes"));
            assertEquals("12",engine.facts(a).get("player.honor"));assertEquals("12",engine.facts(b).get("player.honor"));
            assertTrue(engine.instances(outsider).isEmpty());assertFalse(engine.facts(outsider).containsKey("player.honor"));
            List<WorldEvent> history=engine.history(null,null,0,10);assertEquals(3,history.size());
            WorldEvent observed=history.stream().filter(entry->entry.id().equals(source.id())).findFirst().orElseThrow();
            assertEquals(source,observed);assertTrue(observed.participants().isEmpty());assertEquals(Set.of("river"),observed.nations());
        }
    }
    static final class MutableClock extends Clock {
        private Instant current=Instant.parse("2026-11-20T00:00:00Z");void advance(Duration duration){current=current.plus(duration);}
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId ignored){return this;}public Instant instant(){return current;}
    }
}
