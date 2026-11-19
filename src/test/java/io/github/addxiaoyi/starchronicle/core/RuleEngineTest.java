package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.config.*;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class RuleEngineTest {
    @TempDir Path dir;
    RuleEngine engine;
    final UUID a=UUID.fromString("00000000-0000-0000-0000-000000000001"),b=UUID.fromString("00000000-0000-0000-0000-000000000002");
    final Instant now=Instant.parse("2026-11-19T10:00:00Z");
    PlayerContext ctx(UUID player,String nation){return new PlayerContext(player,nation,Map.of("player.role","scholar","tech.stage","2"),Set.of("chronicle.choose"));}
    StoryDefinition story(StoryDefinition.Scope scope,int quorum,List<StoryDefinition.Effect> rewards) {
        var end=new StoryDefinition.Node("结束",null,null,1,Map.of(),rewards,true);
        var other=new StoryDefinition.Node("另一结局",null,null,1,Map.of(),List.of(),true);
        Map<String,StoryDefinition.Choice> choices=new LinkedHashMap<>();
        choices.put("yes",new StoryDefinition.Choice("支持","end",null,"chronicle.choose",List.of()));
        choices.put("no",new StoryDefinition.Choice("反对","other",null,null,List.of()));
        return new StoryDefinition("test","v1","测试",scope,List.of("WAR_ENDED"),null,"start",3600,Map.of("start",new StoryDefinition.Node("选择",null,null,quorum,choices,List.of(),false),"end",end,"other",other));
    }
    List<StoryDefinition.Effect> rewards(){return List.of(new StoryDefinition.Effect("honor","honor","player","10",null),new StoryDefinition.Effect("title","title","peacekeeper","和平使者",null),new StoryDefinition.Effect("rep","reputation","veteran","5",null),new StoryDefinition.Effect("memory","memory","city.memory","memorial",null),new StoryDefinition.Effect("history","history","PEACE_REMEMBERED","历史铭记了和平",null));}
    void open(StoryDefinition definition){engine=new RuleEngine(new SqliteStore(dir.resolve("chronicle.db")),List.of(definition),Clock.fixed(now,ZoneOffset.UTC));}
    WorldEvent event(String id,UUID... players){return new WorldEvent(id,"WAR_ENDED",now,Set.of(players),Set.of("alpha","beta"),Map.of("war","war-1"),"StarCore","war=war-1; state=ENDED");}
    String start(StoryDefinition.Scope scope,int quorum){open(story(scope,quorum,rewards()));return engine.accept(event("source-1",a,b),List.of(ctx(a,"alpha"),ctx(b,"beta"))).instanceIds().getFirst();}
    @AfterEach void close(){if(engine!=null)engine.close();}

    @Test void eventRedeliveryAndLocalRewardsAreIdempotentAcrossRestart(){
        String id=start(StoryDefinition.Scope.WORLD,1);
        assertTrue(engine.accept(event("source-1",b,a),List.of(ctx(b,"beta"),ctx(a,"alpha"))).duplicate());
        engine.choose(id,a,"yes",ctx(a,"alpha"));
        assertEquals("10",engine.facts(a).get("player.honor"));
        engine.close();engine=null;open(story(StoryDefinition.Scope.WORLD,1,rewards()));
        assertTrue(engine.accept(event("source-1",a,b),List.of(ctx(a,"alpha"),ctx(b,"beta"))).duplicate());
        assertThrows(IllegalStateException.class,()->engine.choose(id,a,"yes",ctx(a,"alpha")));
        assertEquals("10",engine.facts(a).get("player.honor"));
        assertEquals("5",engine.facts(b).get("npc.veteran.reputation"));
        assertEquals(2,engine.history(null,null,0,100).size());
    }
    @Test void conflictingSourceEvidenceFailsClosed(){start(StoryDefinition.Scope.WORLD,1);var changed=new WorldEvent("source-1","WAR_ENDED",now,Set.of(a,b),Set.of("alpha"),Map.of(),"Other","different");assertThrows(IllegalArgumentException.class,()->engine.accept(changed,List.of(ctx(a,"alpha"))));assertEquals(1,engine.history(null,null,0,20).size());}
    @Test void quorumVotesPersistAndCanBeChangedBeforeResolution(){
        String id=start(StoryDefinition.Scope.WORLD,2);
        engine.choose(id,a,"yes",ctx(a,"alpha"));engine.choose(id,b,"no",ctx(b,"beta"));
        assertFalse(engine.instances(a).getFirst().completed());assertTrue(engine.facts(a).isEmpty());
        engine.close();engine=null;open(story(StoryDefinition.Scope.WORLD,2,rewards()));
        engine.choose(id,b,"yes",ctx(b,"beta"));assertTrue(engine.instances(a).getFirst().completed());assertEquals("10",engine.facts(a).get("player.honor"));
    }
    @Test void concurrentSharedVotesProduceOneOutcome() throws Exception {
        String id=start(StoryDefinition.Scope.WORLD,2);
        try(var pool=Executors.newFixedThreadPool(8)) {
            List<Callable<ProcessReport>> calls=new ArrayList<>();
            for(int i=0;i<40;i++){UUID p=i%2==0?a:b;calls.add(()->{try{return engine.choose(id,p,"yes",ctx(p,p.equals(a)?"alpha":"beta"));}catch(IllegalStateException e){return null;}});}
            for(var result:pool.invokeAll(calls)) result.get();
        }
        assertEquals("10",engine.facts(a).get("player.honor"));assertEquals("10",engine.facts(b).get("player.honor"));assertEquals(2,engine.history(null,null,0,20).size());
    }
    @Test void nationAndPlayerScopesStayIsolated(){start(StoryDefinition.Scope.NATION,1);assertEquals(2,engine.activeInstances().size());String aId=engine.instances(a).getFirst().id();assertThrows(SecurityException.class,()->engine.join(aId,ctx(b,"beta")));engine.close();engine=new RuleEngine(new SqliteStore(dir.resolve("player.db")),List.of(story(StoryDefinition.Scope.PLAYER,1,rewards())),Clock.fixed(now,ZoneOffset.UTC));engine.accept(event("personal",a,b),List.of(ctx(a,"alpha"),ctx(b,"beta")));assertEquals(2,engine.activeInstances().size());String own=engine.instances(a).getFirst().id();assertThrows(SecurityException.class,()->engine.join(own,ctx(b,"beta")));assertEquals(1,engine.availableInstances(ctx(a,"alpha")).size());}
    @Test void explicitJoinChecksEligibilityAndPinnedNationality(){String id=start(StoryDefinition.Scope.WORLD,2);UUID third=UUID.randomUUID();engine.join(id,ctx(third,"gamma"));assertEquals(3,engine.instances(third).getFirst().members().size());assertThrows(SecurityException.class,()->engine.choose(id,a,"yes",ctx(a,"gamma")));}
    @Test void choiceRequiresActorMembershipAndFreshPermission(){String id=start(StoryDefinition.Scope.WORLD,1);assertThrows(SecurityException.class,()->engine.choose(id,UUID.randomUUID(),"yes",ctx(UUID.randomUUID(),"alpha")));assertThrows(SecurityException.class,()->engine.choose(id,a,"yes",new PlayerContext(a,"alpha",Map.of(),Set.of())));assertFalse(engine.instances(a).getFirst().completed());}
    @Test void contextOutsideSourceAudienceCannotInjectMembership(){open(story(StoryDefinition.Scope.WORLD,1,rewards()));assertThrows(IllegalArgumentException.class,()->engine.accept(event("x",a),List.of(ctx(b,"gamma"))));assertTrue(engine.history(null,null,0,20).isEmpty());}
    @Test void pinnedStorySurvivesConfigurationRemoval(){String id=start(StoryDefinition.Scope.WORLD,1);engine.reload(List.of());engine.choose(id,a,"yes",ctx(a,"alpha"));assertEquals("10",engine.facts(a).get("player.honor"));}
    @Test void cooldownPreventsNewInstanceOnNewSource(){String id=start(StoryDefinition.Scope.WORLD,1);engine.choose(id,a,"yes",ctx(a,"alpha"));assertTrue(engine.accept(event("source-2",a),List.of(ctx(a,"alpha"))).instanceIds().isEmpty());assertEquals(3,engine.history(null,null,0,20).size());}
    @Test void historySupportsNationBiographyPaginationAndEvidence(){start(StoryDefinition.Scope.WORLD,1);assertEquals(1,engine.history("alpha",a,0,1).size());assertTrue(engine.history("missing",null,0,20).isEmpty());assertTrue(engine.history(null,null,1,1).isEmpty());assertTrue(engine.history(null,a,0,20).getFirst().evidence().contains("war-1"));assertThrows(IllegalArgumentException.class,()->engine.history(null,null,-1,20));}
    @Test void unknownDeliveryIsNotAutomaticallyReplayedAfterCrash(){
        var original=story(StoryDefinition.Scope.WORLD,1,List.of(new StoryDefinition.Effect("quest","quest.accept","existing-quest","accept",null)));
        open(original);
        String id=engine.accept(event("q",a),List.of(ctx(a,"alpha"))).instanceIds().getFirst();engine.choose(id,a,"yes",ctx(a,"alpha"));
        Delivery d=engine.pending().getFirst();assertTrue(engine.claimDelivery(d.outcomeId()));assertFalse(engine.claimDelivery(d.outcomeId()));engine.close();engine=null;
        // Reopen the same accepted version; removing its quest would be a different graph.
        open(original);assertTrue(engine.pending().isEmpty());assertEquals(1,engine.uncertain().size());assertTrue(engine.acknowledgeUnknown(d.outcomeId()));assertTrue(engine.uncertain().isEmpty());
    }
    @Test void externalDeliveryFinishesOnceAndRejectionNeverRetries(){
        open(story(StoryDefinition.Scope.WORLD,1,List.of(new StoryDefinition.Effect("script","narrative.start","s1","start",null))));String id=engine.accept(event("q",a),List.of(ctx(a,"alpha"))).instanceIds().getFirst();engine.choose(id,a,"yes",ctx(a,"alpha"));Delivery d=engine.pending().getFirst();engine.claimDelivery(d.outcomeId());engine.finishDelivery(d.outcomeId(),CoreGateway.DeliveryResult.REJECTED);assertTrue(engine.pending().isEmpty());assertFalse(engine.claimDelivery(d.outcomeId()));
    }
    @Test void authoritativeEffectsNeedPermissionWithoutDroppingHistory(){
        var end=story(StoryDefinition.Scope.WORLD,1,List.of(new StoryDefinition.Effect("a","authority","diplomacy","peace","nation.diplomacy")));
        open(end);String id=engine.accept(event("auth",a),List.of(ctx(a,"alpha"))).instanceIds().getFirst();assertThrows(SecurityException.class,()->engine.choose(id,a,"yes",ctx(a,"alpha")));assertEquals(1,engine.history(null,null,0,20).size());assertTrue(engine.pending().isEmpty());
    }
    @Test void initialObservationIsBaselineNotFabricatedHistory(){open(story(StoryDefinition.Scope.WORLD,1,rewards()));assertNull(engine.observe("nations",Map.of("a","capital-old")));assertEquals(Map.of("a","capital-old"),engine.observe("nations",Map.of("a","capital-new")));assertTrue(engine.history(null,null,0,20).isEmpty());}
    @Test void historyFactsCanTriggerWithoutRepeatingSource(){
        var original=story(StoryDefinition.Scope.WORLD,1,rewards());var gated=new StoryDefinition(original.id(),original.version(),original.title(),original.scope(),original.triggers(),new Condition(null,null,null,"history.WAR_ENDED","gte","1"),original.start(),original.cooldownSeconds(),original.nodes());open(gated);assertEquals(1,engine.accept(event("history",a),List.of(ctx(a,"alpha"))).instanceIds().size());
    }
    @Test void staleNodeTokenCannotAdvanceAnotherStageWithSameChoiceId(){
        var choice=new StoryDefinition.Choice("继续","middle",null,null,List.of());
        var middleChoice=new StoryDefinition.Choice("继续","end",null,null,List.of());
        var def=new StoryDefinition("stale","1","stale",StoryDefinition.Scope.PLAYER,List.of("WAR_ENDED"),null,"start",0,Map.of("start",new StoryDefinition.Node("start",null,null,1,Map.of("same",choice),List.of(),false),"middle",new StoryDefinition.Node("middle",null,null,1,Map.of("same",middleChoice),List.of(),false),"end",new StoryDefinition.Node("end",null,null,1,Map.of(),rewards(),true)));
        open(def);String id=engine.accept(event("stale",a),List.of(ctx(a,"alpha"))).instanceIds().getFirst();
        engine.choose(id,a,"start","same",ctx(a,"alpha"));
        assertThrows(IllegalStateException.class,()->engine.choose(id,a,"start","same",ctx(a,"alpha")));
        assertEquals("middle",engine.instances(a).getFirst().node());assertTrue(engine.facts(a).isEmpty());
        engine.choose(id,a,"middle","same",ctx(a,"alpha"));assertEquals("10",engine.facts(a).get("player.honor"));
    }
    @Test void fractionalHistoryTimestampsSortChronologically(){
        open(story(StoryDefinition.Scope.PLAYER,1,rewards()));engine.accept(event("at-zero"),List.of());
        engine.accept(new WorldEvent("at-half","WAR_ENDED",now.plusMillis(500),Set.of(),Set.of(),Map.of(),"StarCore","ended record"),List.of());
        assertEquals("at-half",engine.history(null,null,0,20).getFirst().id());
    }
    @Test void privateInstancesAndRoleRestrictedChoicesAreNotExposed(){
        var original=story(StoryDefinition.Scope.NATION,1,rewards());
        var gated=new StoryDefinition.Choice("学者专属","end",new Condition(null,null,null,"player.role","eq","scholar"),null,List.of());
        Map<String,StoryDefinition.Node> nodes=new HashMap<>(original.nodes());nodes.put("start",new StoryDefinition.Node("s",null,null,1,Map.of("scholar",gated,"no",new StoryDefinition.Choice("other","other",null,null,List.of())),List.of(),false));
        open(new StoryDefinition(original.id(),original.version(),original.title(),original.scope(),original.triggers(),original.condition(),original.start(),0,nodes));
        engine.accept(event("view",a),List.of(ctx(a,"alpha")));String id=engine.instances(a).getFirst().id();
        assertTrue(engine.availableInstances(ctx(b,"beta")).isEmpty());
        assertFalse(engine.availableChoices(id,new PlayerContext(a,"alpha",Map.of("player.role","soldier"),Set.of())).containsKey("scholar"));
    }
    @Test void authorityOutcomesCarryTheCommittingActorOncePerNation(){
        open(story(StoryDefinition.Scope.WORLD,2,List.of(new StoryDefinition.Effect("auth","authority","treaty","peace","nation.diplomacy"))));
        var ca=new PlayerContext(a,"alpha",Map.of(),Set.of("chronicle.choose","nation.diplomacy"));
        var cb=new PlayerContext(b,"alpha",Map.of(),Set.of("chronicle.choose","nation.diplomacy"));
        String id=engine.accept(event("auth-shared",a,b),List.of(ca,cb)).instanceIds().getFirst();engine.choose(id,a,"yes",ca);engine.choose(id,b,"yes",cb);
        assertEquals(1,engine.pending().size());assertEquals(b,engine.pending().getFirst().playerId());assertEquals("alpha",engine.pending().getFirst().nationId());
    }
    @Test void nationalAudienceDoesNotRewriteHistoricalParticipants(){
        open(story(StoryDefinition.Scope.NATION,1,rewards()));WorldEvent source=event("national-audience");
        assertEquals(2,engine.accept(source,List.of(ctx(a,"alpha"),ctx(b,"beta"))).instanceIds().size());
        assertTrue(engine.history(null,null,0,20).getFirst().participants().isEmpty());assertTrue(engine.history(null,a,0,20).isEmpty());
    }
    @Test void explicitlyObservedTimeKeepsFirstObservationAcrossRedelivery(){
        open(story(StoryDefinition.Scope.PLAYER,1,rewards()));
        WorldEvent first=new WorldEvent("claim:1","TERRITORY_CLAIMED",now,Set.of(a),Set.of("alpha"),Map.of("time_basis","OBSERVED"),"StarCore","claimId=1; observation time");
        WorldEvent later=new WorldEvent(first.id(),first.type(),now.plusSeconds(50),first.participants(),first.nations(),first.facts(),first.source(),first.evidence());
        engine.accept(first,List.of());assertTrue(engine.accept(later,List.of()).duplicate());assertEquals(now,engine.history(null,null,0,20).getFirst().occurredAt());
    }
    @Test void offlineBacklogCannotStarveAnOnlineOutboxRecipient(){
        open(story(StoryDefinition.Scope.WORLD,1,List.of(new StoryDefinition.Effect("q","quest.accept","q","accept",null))));
        Set<UUID> players=new LinkedHashSet<>();List<PlayerContext> contexts=new ArrayList<>();
        for(int n=0;n<160;n++){UUID player=new UUID(0,n+100);players.add(player);contexts.add(ctx(player,"alpha"));}
        String id=engine.accept(new WorldEvent("backlog","WAR_ENDED",now,players,Set.of("alpha"),Map.of(),"StarCore","war record"),contexts).instanceIds().getFirst();
        UUID actor=players.iterator().next();engine.choose(id,actor,"yes",ctx(actor,"alpha"));assertEquals(100,engine.pending().size());
        UUID online=players.stream().filter(player->engine.pending().stream().noneMatch(d->d.playerId().equals(player))).findFirst().orElseThrow();
        assertEquals(1,engine.pending(Set.of(online)).size());assertEquals(online,engine.pending(Set.of(online)).getFirst().playerId());assertTrue(engine.pending(Set.of()).isEmpty());
    }
}
