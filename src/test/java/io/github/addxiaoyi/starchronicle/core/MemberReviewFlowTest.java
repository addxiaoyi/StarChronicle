package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.config.*;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MemberReviewFlowTest {
    @TempDir Path dir;
    final UUID a=UUID.randomUUID(), b=UUID.randomUUID(), c=UUID.randomUUID();
    final Instant now=Instant.parse("2026-11-25T09:00:00Z");
    SqliteStore store;
    RuleEngine engine;
    StoryDefinition definition;
    PlayerContext ctx(UUID player,String nation){return new PlayerContext(player,nation,Map.of(),Set.of("starchronicle.use","node.enter"));}
    void open(){store=new SqliteStore(dir.resolve("members.db"));engine=new RuleEngine(store,List.of(definition),Clock.fixed(now,ZoneOffset.UTC));}
    String start(StoryDefinition.Scope scope,int quorum){
        definition=new StoryDefinition("review","1","Review",scope,List.of("TEST"),null,"vote",0,Map.of(
                "vote",new StoryDefinition.Node("Vote",null,null,quorum,Map.of("yes",new StoryDefinition.Choice("Yes","done",null,null,List.of())),
                        List.of(new StoryDefinition.Effect("honor","honor","player","1","node.enter")),false),
                "done",new StoryDefinition.Node("Done",null,null,1,Map.of(),List.of(),true)));
        open();return engine.accept(new WorldEvent("source","TEST",now,Set.of(a,b,c),Set.of("alpha","beta"),Map.of(),"test","committed fixture"),
                scope==StoryDefinition.Scope.PLAYER?List.of(ctx(a,"alpha")):List.of(ctx(a,"alpha"),ctx(b,"beta"),ctx(c,"alpha"))).instanceIds().getFirst();
    }
    void restart(){engine.close();open();}
    long count(String table){return store.transaction(conn->{try(var s=conn.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM "+table)){r.next();return r.getLong(1);}});}
    @AfterEach void close(){if(engine!=null)engine.close();}

    @Test void exitSurvivesRestartAndPollingUntilExplicitJoinWithoutRepeatedRewards(){
        String id=start(StoryDefinition.Scope.WORLD,3);engine.choose(id,a,"vote","yes",ctx(a,"alpha"));
        engine.exitMember(id,"vote",ctx(a,"gamma"));restart();
        for(int n=0;n<3;n++)engine.resumeTriggers(List.of(ctx(a,"gamma")));
        assertTrue(engine.instances(a).isEmpty());assertEquals(MemberReviewLedger.Status.EXITED,engine.memberReview(id,ctx(a,"gamma")).orElseThrow().status());
        assertEquals(1,count("story_choice_log"));assertEquals("1",engine.facts(a).get("player.honor"));
        engine.join(id,ctx(a,"gamma"));
        assertEquals("gamma",engine.instances(a).getFirst().members().get(a));
        var review=engine.memberReview(id,ctx(a,"gamma")).orElseThrow();assertEquals("gamma",review.originalNationId());
        assertEquals(MemberReviewLedger.Status.APPROVED,review.status());assertEquals(2,count("story_member_review_log"));
        assertEquals("1",engine.facts(a).get("player.honor"));
    }

    @Test void observedDriftRemovesOnlyLiveBallotAndRequiresExplicitRevalidationAfterReturn(){
        String id=start(StoryDefinition.Scope.WORLD,2);engine.choose(id,a,"vote","yes",ctx(a,"alpha"));
        engine.observeMembers(List.of(ctx(a,"gamma")));engine.observeMembers(List.of(ctx(a,"gamma")));
        assertEquals(1,count("story_member_review_log"));assertEquals(1,count("story_choice_log"));
        engine.choose(id,b,"vote","yes",ctx(b,"beta"));assertFalse(engine.instances(b).getFirst().completed());
        assertThrows(SecurityException.class,()->engine.revalidateMember(id,ctx(a,"gamma")));
        restart();assertTrue(engine.availableChoices(id,ctx(a,"alpha")).isEmpty());
        assertThrows(SecurityException.class,()->engine.join(id,ctx(a,"alpha")));
        engine.revalidateMember(id,ctx(a,"alpha"));assertFalse(engine.instances(a).getFirst().votes().containsKey(a));
        engine.choose(id,a,"vote","yes",ctx(a,"alpha"));assertTrue(engine.instances(a).getFirst().completed());
    }

    @Test void completedMembershipAndStaleExitCannotRewriteHistory(){
        String id=start(StoryDefinition.Scope.WORLD,1);
        assertThrows(IllegalStateException.class,()->engine.exitMember(id,"old-node",ctx(a,"alpha")));
        engine.choose(id,a,"vote","yes",ctx(a,"alpha"));StoryInstance before=engine.instances(a).getFirst();
        assertThrows(IllegalStateException.class,()->engine.exitMember(id,"done",ctx(a,"alpha")));
        engine.observeMembers(List.of(ctx(a,"gamma")));assertEquals(before,engine.instances(a).getFirst());
        assertEquals(0,count("story_member_review_log"));
    }

    @Test void missingNationInvalidatesBallotAndRevalidationRechecksPinnedNodePermission(){
        String id=start(StoryDefinition.Scope.WORLD,2);engine.choose(id,a,"vote","yes",ctx(a,"alpha"));
        engine.observeMissingNations(Set.of("beta"));
        assertFalse(engine.instances(a).getFirst().votes().containsKey(a));
        assertEquals(MemberReviewLedger.Status.PENDING,engine.memberReview(id,ctx(a,"alpha")).orElseThrow().status());
        assertThrows(SecurityException.class,()->engine.revalidateMember(id,new PlayerContext(a,"alpha",Map.of(),Set.of("starchronicle.use"))));
        engine.revalidateMember(id,ctx(a,"alpha"));assertEquals(MemberReviewLedger.Status.APPROVED,engine.memberReview(id,ctx(a,"alpha")).orElseThrow().status());
    }

    @Test void personalOwnerCanExplicitlyRejoinButAnotherPlayerCannot(){
        String id=start(StoryDefinition.Scope.PLAYER,1);engine.exitMember(id,"vote",ctx(a,"alpha"));
        assertThrows(SecurityException.class,()->engine.join(id,ctx(b,"alpha")));
        assertThrows(SecurityException.class,()->engine.memberReview(id,ctx(b,"alpha")));
        engine.join(id,ctx(a,"beta"));assertEquals(Set.of(a),engine.instances(a).getFirst().members().keySet());
    }
}
