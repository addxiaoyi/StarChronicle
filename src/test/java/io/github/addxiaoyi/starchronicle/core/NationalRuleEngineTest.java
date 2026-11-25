package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.config.*;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition.*;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** End-to-end engine tests use real SQLite and a synchronized native-authority contract.
 * They verify the transition, frozen quorum, history and reward transaction together. */
class NationalRuleEngineTest {
    @TempDir Path directory;
    final UUID a=UUID.fromString("00000000-0000-0000-0000-000000000101"),
        b=UUID.fromString("00000000-0000-0000-0000-000000000102"),
        successor=UUID.fromString("00000000-0000-0000-0000-000000000103"),
        citizen=UUID.fromString("00000000-0000-0000-0000-000000000104");
    final String alpha="00000000-0000-0000-0000-000000000201",beta="00000000-0000-0000-0000-000000000202";
    final Instant now=Instant.parse("2026-11-20T12:00:00Z");
    final Clock clock=Clock.fixed(now,ZoneOffset.UTC);
    SqliteStore store;RuleEngine engine;NativeAuthority authority;StoryDefinition definition;

    PlayerContext context(UUID player,String nation){return context(player,nation,Map.of());}
    PlayerContext context(UUID player,String nation,Map<String,String> facts){return new PlayerContext(player,nation,facts,Set.of("starchronicle.use","chronicle.choose"));}
    List<Effect> rewards(){return List.of(new Effect("honor","honor","player","10",null),new Effect("history","history","COUNCIL_DECIDED","Council decision",null),new Effect("quest","quest.accept","existing-quest","accept",null));}
    StoryDefinition definition(int quorum,Condition condition,boolean stages){
        Map<String,Node> nodes=new LinkedHashMap<>();
        nodes.put("vote",new Node("Council",null,null,quorum,Map.of("yes",new Choice("Support",stages?"second":"end",condition,"chronicle.choose",List.of()),"no",new Choice("Oppose","end",null,null,List.of())),List.of(),false));
        if(stages)nodes.put("second",new Node("Second council",null,null,quorum,Map.of("yes",new Choice("Confirm","end",null,null,List.of())),List.of(),false));
        nodes.put("end",new Node("End",null,null,1,Map.of(),rewards(),true));
        return new StoryDefinition("council","1","Council",Scope.WORLD,List.of("ROOT"),null,"vote",0,nodes,VoteUnit.NATION);
    }
    String open(int quorum){return open(definition(quorum,null,false),true);}
    String open(StoryDefinition story,boolean electorate){
        definition=story;store=new SqliteStore(directory.resolve("engine.db"));authority=new NativeAuthority();
        authority.holders.put(alpha,a);authority.holders.put(beta,b);authority.nations.addAll(Set.of(alpha,beta));
        engine=new RuleEngine(store,List.of(story),clock,null,authority);
        var source=new WorldEvent("council:source","ROOT",now,Set.of(a,b,successor,citizen),electorate?Set.of(alpha,beta):Set.of(),Map.of(),"NationalRuleEngineTest","Committed source electorate");
        return engine.accept(source,List.of(context(a,alpha),context(b,beta),context(successor,alpha),context(citizen,alpha))).instanceIds().getFirst();
    }
    void reopen(){engine.close();store=new SqliteStore(directory.resolve("engine.db"));engine=new RuleEngine(store,List.of(definition),clock,null,authority);}
    long count(String table){return store.transaction(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM "+table)){r.next();return r.getLong(1);}});}
    void execute(String sql){store.transaction(c->{try(var s=c.createStatement()){s.execute(sql);}return null;});}
    StoryInstance instance(){return engine.instances(a).getFirst();}
    @AfterEach void close(){if(engine!=null)engine.close();}

    class NativeAuthority implements NationalVoteAuthority {
        final Map<String,UUID> holders=new HashMap<>();final Set<String> nations=new HashSet<>();String unavailableNation;
        @Override public synchronized <T>T withRepresentative(UUID actor,String nation,Authorized<T> operation)throws Exception {
            assertFalse(Thread.holdsLock(store),"Native authority must be acquired before Chronicle storage");
            if(!nations.contains(nation)||!actor.equals(holders.get(nation)))throw new SecurityException("Not a current native holder");
            return operation.run(new View(){
                public UUID actorId(){return actor;}public String nationId(){return nation;}public Instant checkedAt(){return now;}
                public String issuer(){return "native-test";}public String evidence(){return "Current native holder under synchronized monitor";}
                public boolean nationExists(String id){assertTrue(Thread.holdsLock(NativeAuthority.this));assertTrue(Thread.holdsLock(store));if(id.equals(unavailableNation))throw new IllegalStateException("Native lookup unavailable");return nations.contains(id);}
            });
        }
    }

    @Test void oneNationRemainsOneSeatAfterSuccessionAndOnlyTheCurrentHolderMayRevise(){
        String id=open(2);engine.nationalVote(id,a,"vote",0,"yes",context(a,alpha));
        assertEquals(Map.of("yes",1L),engine.nationalState(id,context(citizen,alpha)).counts());
        assertFalse(engine.nationalState(id,context(citizen,alpha)).representative());
        assertTrue(engine.availableChoices(id,context(citizen,alpha)).isEmpty());
        authority.holders.put(alpha,successor);
        assertThrows(SecurityException.class,()->engine.nationalVote(id,a,"vote",1,"no",context(a,alpha)));
        assertThrows(IllegalStateException.class,()->engine.nationalVote(id,successor,"vote",0,"yes",context(successor,alpha)));
        engine.nationalVote(id,successor,"vote",1,"no",context(successor,alpha));
        assertEquals(Map.of("no",1L),engine.nationalState(id,context(successor,alpha)).counts());
        assertEquals(2,engine.nationalState(id,context(successor,alpha)).viewerRevision());assertFalse(instance().completed());
        assertTrue(instance().votes().isEmpty());assertTrue(instance().choices().isEmpty());assertEquals(0,count("story_choice_log"));
    }
    @Test void matchingNoopDoesNotAppendButAnOldRevisionCannotReplayIt(){
        String id=open(3);engine.nationalVote(id,a,"vote",0,"yes",context(a,alpha));
        assertTrue(engine.nationalVote(id,a,"vote",1,"yes",context(a,alpha)).duplicate());
        assertThrows(IllegalStateException.class,()->engine.nationalVote(id,a,"vote",0,"yes",context(a,alpha)));
        assertEquals(1,count("story_nation_vote_log"));assertEquals(1,engine.nationalState(id,context(a,alpha)).viewerRevision());
    }
    @Test void withdrawAndRevotePersistTombstonesAndCasAcrossRestart(){
        String id=open(3);engine.nationalVote(id,a,"vote",0,"yes",context(a,alpha));
        engine.withdrawNationalVote(id,a,"vote",1,context(a,alpha));reopen();
        var state=engine.nationalState(id,context(a,alpha));assertEquals(2,state.viewerRevision());assertTrue(state.counts().isEmpty());assertFalse(state.canWithdraw());
        assertEquals("WITHDRAWN",state.ballots().getFirst().status());
        assertTrue(engine.withdrawNationalVote(id,a,"vote",2,context(a,alpha)).duplicate());
        assertThrows(IllegalStateException.class,()->engine.nationalVote(id,a,"vote",1,"yes",context(a,alpha)));
        engine.nationalVote(id,a,"vote",2,"no",context(a,alpha));assertEquals(3,engine.nationalState(id,context(a,alpha)).viewerRevision());assertEquals(3,count("story_nation_vote_log"));
    }
    @Test void withdrawalDoesNotRequireTheOldBranchConditionToRemainTrue(){
        var condition=new Condition(null,null,null,"tech.stage","gte","2");
        String id=open(definition(2,condition,false),true);
        engine.nationalVote(id,a,"vote",0,"yes",context(a,alpha,Map.of("tech.stage","2")));
        engine.withdrawNationalVote(id,a,"vote",1,context(a,alpha,Map.of("tech.stage","1")));
        assertTrue(engine.nationalState(id,context(a,alpha)).counts().isEmpty());
    }
    @Test void frozenResolutionHistoryAndRewardsCommitOnceWithoutPersonalBallots(){
        String id=open(2);engine.nationalVote(id,a,"vote",0,"yes",context(a,alpha));
        assertTrue(engine.recollections(context(a,alpha),List.of("past.nation.choice.council/1/vote")).isEmpty());
        engine.nationalVote(id,b,"vote",0,"yes",context(b,beta));
        assertTrue(instance().completed());assertEquals(Map.of("vote","yes"),instance().choices());assertTrue(instance().votes().isEmpty());
        assertEquals(1,count("story_choice_log"));assertEquals(1,count("story_nation_resolutions"));
        assertEquals("yes",engine.recollections(context(citizen,alpha),List.of("past.nation.choice.council/1/vote")).get("past.nation.choice.council/1/vote"));
        assertTrue(engine.recollections(context(a,alpha),List.of("past.player.choice.council/1/vote")).isEmpty());
        assertEquals("10",engine.facts(a).get("player.honor"));assertEquals(4,engine.pending().size());assertEquals(2,engine.history(null,null,0,20).size());
        reopen();engine.reload(List.of());authority.nations.clear();
        var frozen=engine.nationalState(id,context(citizen,alpha));assertTrue(frozen.completed());assertEquals("vote",frozen.nodeId());assertEquals("yes",frozen.resolution());assertEquals(Map.of("yes",2L),frozen.counts());
        assertThrows(SecurityException.class,()->engine.nationalVote(id,a,"vote",1,"yes",context(a,alpha)));
        assertEquals("10",engine.facts(a).get("player.honor"));assertEquals(4,engine.pending().size());assertEquals(2,count("story_nation_vote_log"));
    }
    @Test void extinctCountryIsInvalidatedBeforeTheNextQuorumWithoutReattribution(){
        String id=open(2);engine.nationalVote(id,a,"vote",0,"yes",context(a,alpha));authority.nations.remove(alpha);
        engine.nationalVote(id,b,"vote",0,"yes",context(b,beta));
        var state=engine.nationalState(id,context(b,beta));assertFalse(state.completed());assertEquals(Map.of("yes",1L),state.counts());
        var invalid=state.ballots().stream().filter(v->v.nationId().equals(alpha)).findFirst().orElseThrow();assertEquals("INVALIDATED",invalid.status());assertEquals(2,invalid.revision());assertEquals(b,invalid.actorId());
        assertEquals(0,count("story_nation_resolutions"));assertEquals(0,count("story_choice_log"));
    }
    @Test void nativeLookupFailureDoesNotMasqueradeAsDissolutionOrAppendAnotherVote(){
        String id=open(2);engine.nationalVote(id,a,"vote",0,"yes",context(a,alpha));authority.unavailableNation=alpha;
        assertThrows(IllegalStateException.class,()->engine.nationalVote(id,b,"vote",0,"yes",context(b,beta)));
        assertEquals(1,count("story_nation_vote_log"));assertEquals(0,count("story_nation_resolutions"));assertFalse(instance().completed());
    }
    @Test void localEffectFailureRollsBackTheVoteFrozenQuorumHistoryAndTransition(){
        String id=open(2);engine.nationalVote(id,a,"vote",0,"yes",context(a,alpha));
        execute("INSERT INTO facts VALUES('"+a+"','player.honor','"+Long.MAX_VALUE+"')");
        assertThrows(ArithmeticException.class,()->engine.nationalVote(id,b,"vote",0,"yes",context(b,beta)));
        assertFalse(instance().completed());assertTrue(instance().choices().isEmpty());assertEquals(1,count("story_nation_vote_log"));assertEquals(0,count("story_nation_resolutions"));assertEquals(0,count("story_choice_log"));assertEquals(1,engine.history(null,null,0,20).size());assertTrue(engine.pending().isEmpty());
        execute("UPDATE facts SET value='0' WHERE owner='"+a+"' AND key='player.honor'");
        engine.nationalVote(id,b,"vote",0,"yes",context(b,beta));assertTrue(instance().completed());assertEquals("10",engine.facts(a).get("player.honor"));
    }
    @Test void staleNodeCannotCastAnIdenticallyNamedLaterChoice(){
        String id=open(definition(1,null,true),true);engine.nationalVote(id,a,"vote",0,"yes",context(a,alpha));
        assertEquals("second",instance().node());
        assertThrows(IllegalStateException.class,()->engine.nationalVote(id,a,"vote",0,"yes",context(a,alpha)));
        assertThrows(IllegalStateException.class,()->engine.withdrawNationalVote(id,a,"vote",1,context(a,alpha)));
        engine.nationalVote(id,a,"second",0,"yes",context(a,alpha));assertTrue(instance().completed());assertEquals(2,count("story_nation_resolutions"));
    }
    @Test void participantListAloneDoesNotInventNationalElectorate(){
        String id=open(definition(2,null,false),false);
        assertFalse(engine.nationalState(id,context(a,alpha)).canVote());assertTrue(engine.availableChoices(id,context(a,alpha)).isEmpty());
        assertThrows(SecurityException.class,()->engine.nationalVote(id,a,"vote",0,"yes",context(a,alpha)));assertEquals(0,count("story_nation_vote_log"));
    }
    @Test void defaultAuthorityAndIdentityFactsNeverGrantRepresentativePowers(){
        String id=open(2);engine=new RuleEngine(store,List.of(definition),clock);
        var admin=new PlayerContext(a,alpha,Map.of("player.role","monarch","nation.rank","Founder"),Set.of("*","starchronicle.use"));
        assertFalse(engine.nationalState(id,admin).representative());assertTrue(engine.availableChoices(id,admin).isEmpty());
        assertThrows(SecurityException.class,()->engine.nationalVote(id,a,"vote",0,"yes",admin));
        assertThrows(SecurityException.class,()->engine.choose(id,a,"vote","yes",admin));assertEquals(0,count("story_nation_vote_log"));
    }
    @Test void freshUseMembershipAndOriginalNationalityAreRequired(){
        String id=open(2);var denied=new PlayerContext(a,alpha,Map.of(),Set.of());
        assertThrows(SecurityException.class,()->engine.nationalVote(id,a,"vote",0,"yes",denied));
        assertThrows(SecurityException.class,()->engine.nationalState(id,denied));
        assertThrows(SecurityException.class,()->engine.nationalVote(id,b,"vote",0,"yes",context(a,alpha)));
        assertThrows(IllegalArgumentException.class,()->engine.nationalVote(id,a,"vote",-1,"yes",context(a,alpha)));
        authority.holders.put(beta,a);
        assertThrows(SecurityException.class,()->engine.nationalVote(id,a,"vote",0,"yes",context(a,beta)));
        assertFalse(engine.nationalState(id,context(a,beta)).canVote());assertEquals(0,count("story_nation_vote_log"));
    }
    @Test void projectionCorruptionInsideTheAuthorityCallbackIsNotSilentlyDowngraded(){
        String id=open(2);engine.nationalVote(id,a,"vote",0,"yes",context(a,alpha));execute("UPDATE story_nation_votes SET revision=99");
        assertThrows(IllegalStateException.class,()->engine.nationalState(id,context(a,alpha)));
    }
    @Test void concurrentSubmissionsOfOneRevisionAppendOnlyOneNationalAction()throws Exception {
        String id=open(3);
        try(var pool=Executors.newFixedThreadPool(6)){
            List<Callable<Boolean>> requests=new ArrayList<>();for(int n=0;n<24;n++)requests.add(()->{try{engine.nationalVote(id,a,"vote",0,"yes",context(a,alpha));return true;}catch(IllegalStateException stale){return false;}});
            int successes=0;for(var result:pool.invokeAll(requests))if(result.get())successes++;
            assertEquals(1,successes);
        }
        assertEquals(1,count("story_nation_vote_log"));assertEquals(Map.of("yes",1L),engine.nationalState(id,context(a,alpha)).counts());
    }
}
