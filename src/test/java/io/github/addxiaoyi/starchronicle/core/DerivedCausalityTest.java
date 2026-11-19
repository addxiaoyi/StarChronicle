package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition.*;
import io.github.addxiaoyi.starchronicle.history.EventCausality;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Actual RuleEngine/SQLite integration: pinned configurations cannot bypass causal guards. */
class DerivedCausalityTest {
    @TempDir Path directory;
    private static final UUID PLAYER=UUID.fromString("00000000-0000-0000-0000-000000000089");
    private static final Clock CLOCK=Clock.fixed(Instant.parse("2026-11-20T00:00:00Z"),ZoneOffset.UTC);
    private static final PlayerContext CONTEXT=new PlayerContext(PLAYER,"",Map.of(),Set.of());
    private Path database(){return directory.resolve("derived.db");}
    private static WorldEvent source(String id,String type,Map<String,String> facts){return new WorldEvent(id,type,CLOCK.instant(),Set.of(PLAYER),Set.of(),facts,"DerivedCausalityTest","committed external source="+id);}
    private static StoryDefinition story(String id,String version,String trigger,String emitted,boolean automatic,int honor){
        var effects=List.of(new Effect("honor","honor","player",Integer.toString(honor),null),new Effect("history","history",emitted,"True committed narrative result",null));
        Map<String,Node> nodes=automatic?Map.of("end",new Node("Done",null,null,1,Map.of(),effects,true)):
                Map.of("vote",new Node("Choose",null,null,1,Map.of("yes",new Choice("Yes","end",null,null,List.of())),List.of(),false),"end",new Node("Done",null,null,1,Map.of(),effects,true));
        return new StoryDefinition(id,version,id,Scope.PLAYER,List.of(trigger),null,automatic?"end":"vote",0,nodes);
    }
    private static StoryDefinition a1(){return story("chapter-a","1","ROOT","A_DONE",false,5);}
    private static StoryDefinition a2(){return story("chapter-a","2","B_DONE","A_DONE",false,5);}
    private static StoryDefinition b1(){return story("chapter-b","1","A_DONE","B_DONE",false,5);}
    private static StoryInstance instance(RuleEngine engine,String story){return engine.instances(PLAYER).stream().filter(i->i.definition().id().equals(story)).findFirst().orElseThrow();}
    private static WorldEvent history(RuleEngine engine,String type){return engine.history(null,null,0,100).stream().filter(e->e.type().equals(type)).findFirst().orElseThrow();}
    private static long count(SqliteStore store,String table){return store.transaction(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM "+table)){r.next();return r.getLong(1);}});}
    private static StoryInstance preparePinnedCycle(RuleEngine engine){
        engine.accept(source("cycle-root","ROOT",Map.of()),List.of(CONTEXT));StoryInstance a=instance(engine,"chapter-a");
        engine.reload(List.of(b1()));engine.choose(a.id(),PLAYER,"vote","yes",CONTEXT);
        assertEquals(1,engine.pendingTriggerCount());assertTrue(engine.activeInstances().isEmpty(),"Derived history queues the follow-up instead of recursively starting it");
        engine.reload(List.of(a2()));engine.resumeTriggers(List.of(CONTEXT));StoryInstance b=instance(engine,"chapter-b");assertEquals("1",b.definition().version());return b;
    }

    @Test void runtimeStoryPathBlocksARepeatedIdAcrossPinnedVersionsButRetainsTheTrueResult(){
        String resultId;long outcomes;
        try(var store=new SqliteStore(database())){
            var engine=new RuleEngine(store,List.of(a1()),CLOCK);StoryInstance b=preparePinnedCycle(engine);
            engine.choose(b.id(),PLAYER,"vote","yes",CONTEXT);WorldEvent result=history(engine,"B_DONE");resultId=result.id();
            assertEquals(new EventCausality.Trace(history(engine,"A_DONE").id(),"cycle-root",2,List.of("chapter-a","chapter-b")),engine.causality(resultId));
            assertEquals(List.of(new EventCausality.Block(resultId,"chapter-a","2","REPEATED_STORY_IN_CAUSAL_CHAIN")),engine.triggerBlocks(10));
            assertEquals(3,engine.history(null,null,0,100).size());assertEquals(2,count(store,"instances"));assertEquals(0,engine.pendingTriggerCount());assertTrue(engine.activeInstances().isEmpty());
            assertEquals("1",instance(engine,"chapter-a").definition().version());assertTrue(instance(engine,"chapter-b").completed());assertEquals("10",engine.facts(PLAYER).get("player.honor"));outcomes=count(store,"outcomes");
            for(int i=0;i<4;i++){engine.resumeTriggers(List.of(CONTEXT));assertTrue(engine.accept(source("cycle-root","ROOT",Map.of()),List.of(CONTEXT)).duplicate());}
            assertThrows(IllegalStateException.class,()->engine.choose(b.id(),PLAYER,"vote","yes",CONTEXT));assertEquals(outcomes,count(store,"outcomes"));assertEquals("10",engine.facts(PLAYER).get("player.honor"));assertEquals(1,engine.triggerBlocks(10).size());
        }
        try(var store=new SqliteStore(database())){
            var engine=new RuleEngine(store,List.of(a2()),CLOCK);engine.resumeTriggers(List.of(CONTEXT));assertEquals(resultId,history(engine,"B_DONE").id());assertEquals(List.of("chapter-a","chapter-b"),engine.causality(resultId).stories());assertEquals(1,engine.triggerBlocks(10).size());assertEquals(2,count(store,"instances"));assertEquals(outcomes,count(store,"outcomes"));assertEquals("10",engine.facts(PLAYER).get("player.honor"));
        }
    }

    @Test void aThirtySixStoryDagStopsAtDepth32WithoutRecursivelyAcceptingOrLosingTheFinalHistory(){
        List<StoryDefinition> definitions=new ArrayList<>();for(int i=0;i<EventCausality.MAX_DEPTH+4;i++)definitions.add(story("chapter-"+i,"1","CHAIN_"+i,"CHAIN_"+(i+1),true,1));
        String boundaryId;
        try(var store=new SqliteStore(database())){
            var engine=new RuleEngine(store,definitions,CLOCK);WorldEvent root=source("depth-root","CHAIN_0",Map.of());engine.accept(root,List.of(CONTEXT));
            assertEquals(1,count(store,"instances"));assertEquals(2,engine.history(null,null,0,100).size());assertEquals(1,engine.pendingTriggerCount(),"The first accept must not recursively exhaust the follow-up chain");
            for(int completed=2;completed<=EventCausality.MAX_DEPTH;completed++){
                engine.resumeTriggers(List.of(CONTEXT));assertEquals(completed,count(store,"instances"));assertEquals(completed+1,engine.history(null,null,0,100).size());assertEquals(Integer.toString(completed),engine.facts(PLAYER).get("player.honor"));
                assertEquals(completed==EventCausality.MAX_DEPTH?0:1,engine.pendingTriggerCount());
            }
            WorldEvent boundary=history(engine,"CHAIN_32");boundaryId=boundary.id();var trace=engine.causality(boundaryId);
            List<String> path=new ArrayList<>();for(int i=0;i<EventCausality.MAX_DEPTH;i++)path.add("chapter-"+i);
            assertEquals(EventCausality.MAX_DEPTH,trace.depth());assertEquals(path,trace.stories());assertEquals("depth-root",trace.rootId());assertEquals(history(engine,"CHAIN_31").id(),trace.parentId());
            assertEquals(List.of(new EventCausality.Block(boundaryId,"chapter-32","1","MAX_CAUSAL_DEPTH")),engine.triggerBlocks(10));assertEquals(33,count(store,"events"));assertEquals(32,count(store,"instances"));assertTrue(engine.activeInstances().isEmpty());
            long receipts=count(store,"outcomes");for(int i=0;i<5;i++){engine.resumeTriggers(List.of(CONTEXT));assertTrue(engine.accept(root,List.of(CONTEXT)).duplicate());}
            assertEquals(receipts,count(store,"outcomes"));assertEquals("32",engine.facts(PLAYER).get("player.honor"));assertFalse(engine.instances(PLAYER).stream().anyMatch(i->i.definition().id().equals("chapter-32")));
        }
        try(var store=new SqliteStore(database())){var engine=new RuleEngine(store,definitions,CLOCK);engine.resumeTriggers(List.of(CONTEXT));assertEquals(boundaryId,history(engine,"CHAIN_32").id());assertEquals(32,engine.causality(boundaryId).depth());assertEquals(1,engine.triggerBlocks(10).size());assertEquals("32",engine.facts(PLAYER).get("player.honor"));assertEquals(0,engine.pendingTriggerCount());}
    }

    @Test void forgedExternalFactMetadataCannotChooseItsParentRootDepthOrStoryPath(){
        Map<String,String> forged=Map.of("causal.parent","missing-parent","causal.root","missing-root","causal.depth","32","causal.stories","trusted-story","parentId","missing-parent","rootId","missing-root","depth","32","story_path","[\"trusted-story\"]");
        WorldEvent external=source("untrusted-facts-root","ROOT",forged);
        try(var store=new SqliteStore(database())){
            var engine=new RuleEngine(store,List.of(story("trusted-story","1","ROOT","RESULT",true,1)),CLOCK);engine.accept(external,List.of(CONTEXT));
            assertEquals(EventCausality.root(external.id()),engine.causality(external.id()));WorldEvent result=history(engine,"RESULT");
            assertEquals(new EventCausality.Trace(external.id(),external.id(),1,List.of("trusted-story")),engine.causality(result.id()));assertEquals(forged,history(engine,"ROOT").facts());assertEquals("1",engine.facts(PLAYER).get("player.honor"));assertTrue(engine.triggerBlocks(10).isEmpty());
        }
    }

    @Test void failedBlockStorageRollsBackTheEndingRewardHistoryTraceAndBallotTogether(){
        try(var store=new SqliteStore(database())){
            var engine=new RuleEngine(store,List.of(a1()),CLOCK);StoryInstance b=preparePinnedCycle(engine);long ballots=count(store,"story_choice_log"),receipts=count(store,"outcomes");
            store.transaction(c->{try(var s=c.createStatement()){s.execute("CREATE TRIGGER reject_causal_block BEFORE INSERT ON story_trigger_blocks BEGIN SELECT RAISE(ABORT,'simulated causal diagnostic storage failure'); END");}return null;});
            assertThrows(IllegalStateException.class,()->engine.choose(b.id(),PLAYER,"vote","yes",CONTEXT));
            assertFalse(instance(engine,"chapter-b").completed());assertEquals("vote",instance(engine,"chapter-b").node());assertEquals("5",engine.facts(PLAYER).get("player.honor"));assertEquals(2,count(store,"events"));assertEquals(2,count(store,"event_causes"));assertEquals(ballots,count(store,"story_choice_log"));assertEquals(receipts,count(store,"outcomes"));assertTrue(engine.triggerBlocks(10).isEmpty());
            store.transaction(c->{try(var s=c.createStatement()){s.execute("DROP TRIGGER reject_causal_block");}return null;});engine.choose(b.id(),PLAYER,"vote","yes",CONTEXT);
            assertEquals("10",engine.facts(PLAYER).get("player.honor"));assertEquals(3,count(store,"events"));assertEquals(3,count(store,"event_causes"));assertEquals(1,engine.triggerBlocks(10).size());assertTrue(instance(engine,"chapter-b").completed());
        }
    }
}
