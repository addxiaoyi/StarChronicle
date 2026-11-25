package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition.*;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MemberReviewWorkflowTest {
    @TempDir Path directory;
    final UUID a=UUID.randomUUID(),b=UUID.randomUUID();
    final String own=UUID.randomUUID().toString(),other=UUID.randomUUID().toString();
    final Instant now=Instant.parse("2026-11-25T14:00:00Z");
    SqliteStore store;RuleEngine engine;String id;
    final Map<UUID,String> nativeNations=new HashMap<>();
    boolean unavailable;
    NationalVoteAuthority.Authorized<?> saved;
    boolean defer;
    final NationalVoteAuthority authority=new NationalVoteAuthority(){
        @Override public <T>T withRepresentative(UUID actor,String nation,Authorized<T> action){throw new SecurityException("Not a national representative");}
        @Override public <T>T withMembership(UUID actor,String nation,Authorized<T> action)throws Exception {
            assertFalse(Thread.holdsLock(store),"Acquire native identity before local storage");
            saved=action;
            if(defer)return null;
            if(unavailable||!nation.equals(nativeNations.get(actor)))throw new SecurityException("Native membership unavailable or changed");
            return action.run(new View(){
                public UUID actorId(){return actor;}
                public String nationId(){return nation;}
                public Instant checkedAt(){return now;}
                public String issuer(){return "test.native-membership";}
                public String evidence(){return "actor="+actor+"; nation="+nation;}
                public boolean nationExists(String value){return nativeNations.containsValue(value);}
            });
        }
    };
    PlayerContext context(UUID player,String nation){return new PlayerContext(player,nation,Map.of(),Set.of("starchronicle.use"));}
    StoryDefinition definition(){return new StoryDefinition("members","1","Member workflow",Scope.WORLD,List.of("ROOT"),null,"vote",0,Map.of(
            "vote",new Node("Vote",null,null,2,Map.of("yes",new Choice("Yes","end",null,null,List.of())),List.of(),false),
            "end",new Node("End",null,null,1,Map.of(),List.of(),true)));}
    void open(){store=new SqliteStore(directory.resolve("members.db"));engine=new RuleEngine(store,List.of(definition()),Clock.fixed(now,ZoneOffset.UTC),null,authority);}
    @BeforeEach void start(){
        nativeNations.put(a,own);nativeNations.put(b,other);open();
        id=engine.accept(new WorldEvent("member-source","ROOT",now,Set.of(a,b),Set.of(own,other),Map.of(),"fixture","durable source"),List.of(context(a,own),context(b,other))).instanceIds().getFirst();
    }
    @AfterEach void close(){if(engine!=null)engine.close();}
    long count(String table){return store.transaction(c->{try(var s=c.createStatement();var r=s.executeQuery("select count(*) from "+table)){return r.getLong(1);}});}
    StoryInstance instance(){return engine.activeInstances().stream().filter(i->i.id().equals(id)).findFirst().orElseThrow();}

    @Test void driftRequiresExplicitNativeRevalidationAndNeverCountsTheOldPersonalVote(){
        engine.choose(id,a,"vote","yes",context(a,own));
        nativeNations.put(a,other);
        engine.observeMemberNations(List.of(context(a,other)));
        assertEquals("PENDING",engine.memberState(id,context(a,other)).status());
        assertFalse(instance().votes().containsKey(a));assertEquals(1,count("story_choice_log"));
        assertThrows(SecurityException.class,()->engine.revalidateMember(id,"vote",context(a,other)));
        nativeNations.put(a,own);
        assertTrue(engine.availableChoices(id,context(a,own)).isEmpty());
        engine.revalidateMember(id,"vote",context(a,own));
        assertEquals("APPROVED",engine.memberState(id,context(a,own)).status());
        assertEquals(2,count("story_member_review_log"));
        engine.choose(id,b,"vote","yes",context(b,other));
        assertFalse(instance().completed(),"Old A ballot must not count after revalidation");
        engine.choose(id,a,"vote","yes",context(a,own));
        assertTrue(engine.instances(a).getFirst().completed());
        assertEquals(4,count("story_choice_log"),"Original ballot, other member's vote, explicit recast and resolution are all retained");
    }

    @Test void exitSurvivesAutomaticRecoveryAndRestartAndRequiresExplicitOriginalNationRejoin(){
        engine.choose(id,a,"vote","yes",context(a,own));
        engine.exitMember(id,"vote",context(a,own));
        assertFalse(instance().contains(a));assertEquals(1,count("story_choice_log"));
        for(int repeat=0;repeat<3;repeat++)engine.resumeTriggers(List.of(context(a,own),context(b,other)));
        assertFalse(instance().contains(a));
        engine.close();engine=null;open();
        engine.resumeTriggers(List.of(context(a,own)));
        assertEquals("EXITED",engine.memberState(id,context(a,own)).status());assertFalse(instance().contains(a));
        nativeNations.put(a,other);
        assertThrows(SecurityException.class,()->engine.join(id,context(a,other)));
        nativeNations.put(a,own);unavailable=true;
        assertThrows(SecurityException.class,()->engine.join(id,context(a,own)));
        unavailable=false;engine.join(id,context(a,own));
        assertTrue(instance().contains(a));assertTrue(instance().votes().isEmpty());
        assertEquals("APPROVED",engine.memberState(id,context(a,own)).status());
        assertEquals(2,count("story_member_review_log"));assertEquals(1,count("story_choice_log"));
    }

    @Test void completedHistoryAndStaleNodeCannotBeMutated(){
        assertThrows(IllegalStateException.class,()->engine.exitMember(id,"old-node",context(a,own)));
        assertThrows(IllegalStateException.class,()->engine.revalidateMember(id,"old-node",context(a,own)));
        assertEquals(0,count("story_member_review_log"));
        engine.choose(id,a,"vote","yes",context(a,own));engine.choose(id,b,"vote","yes",context(b,other));
        assertThrows(IllegalStateException.class,()->engine.exitMember(id,"end",context(a,own)));
        assertThrows(IllegalStateException.class,()->engine.revalidateMember(id,"end",context(a,own)));
        assertEquals("ACTIVE",engine.memberState(id,context(a,other)).status());assertEquals(0,count("story_member_review_log"));
    }

    @Test void exitedPersonalStoryCanOnlyBeRejoinedByItsAuthoritativelyVerifiedOwner(){
        var personal=new StoryDefinition("personal-members","1","Personal membership",Scope.PLAYER,List.of("PERSONAL"),null,"vote",0,definition().nodes());
        try(var localStore=new SqliteStore(directory.resolve("personal.db"));
            var local=new RuleEngine(localStore,List.of(personal),Clock.fixed(now,ZoneOffset.UTC),null,authority)){
            String personalId=local.accept(new WorldEvent("personal-source","PERSONAL",now,Set.of(a),Set.of(own),Map.of(),"fixture","source"),List.of(context(a,own))).instanceIds().getFirst();
            local.exitMember(personalId,"vote",context(a,own));
            local.resumeTriggers(List.of(context(a,own),context(b,other)));
            assertEquals("EXITED",local.memberState(personalId,context(a,own)).status());
            assertThrows(SecurityException.class,()->local.join(personalId,context(b,other)));
            unavailable=true;assertThrows(SecurityException.class,()->local.join(personalId,context(a,own)));
            unavailable=false;local.join(personalId,context(a,own));
            assertEquals("APPROVED",local.memberState(personalId,context(a,own)).status());
            assertEquals(Set.of(a),local.instances(a).getFirst().members().keySet());
        }
    }

    @Test void missingNationBlocksMemberAndRepeatedPollsDoNotGrowTheAuditLog(){
        engine.choose(id,a,"vote","yes",context(a,own));
        for(int repeat=0;repeat<3;repeat++)engine.observeMissingNations(Set.of(other));
        assertEquals("PENDING",engine.memberState(id,context(a,own)).status());
        assertEquals(1,count("story_member_review_log"));assertFalse(instance().votes().containsKey(a));
        nativeNations.remove(a);
        assertThrows(SecurityException.class,()->engine.revalidateMember(id,"vote",context(a,own)));
        assertEquals(1,count("story_member_review_log"));
    }

    @Test void reviewAuditAndMembershipRollbackTogetherOnSqlFailure(){
        store.transaction(c->{try(var s=c.createStatement()){s.execute("CREATE TRIGGER reject_member_audit BEFORE INSERT ON story_member_review_log BEGIN SELECT RAISE(ABORT,'disk failure'); END");}return null;});
        assertThrows(RuntimeException.class,()->engine.exitMember(id,"vote",context(a,own)));
        assertTrue(instance().contains(a));assertEquals(0,count("story_member_reviews"));
    }

    @Test void missingOriginalNationCannotEraseObservedNewNationOrGrowAuditOnEveryPoll(){
        engine.choose(id,a,"vote","yes",context(a,own));
        nativeNations.put(a,other);
        for(int repeat=0;repeat<5;repeat++){
            engine.observeMemberNations(List.of(context(a,other)));
            engine.observeMissingNations(Set.of(other));
        }
        var review=engine.memberReviews(a,10).getFirst();
        assertEquals(MemberReviewLedger.Status.PENDING,review.status());
        assertEquals(own,review.originalNationId());assertEquals(other,review.observedNationId());
        assertEquals(1,count("story_member_review_log"));assertFalse(instance().votes().containsKey(a));
        assertThrows(SecurityException.class,()->engine.revalidateMember(id,"vote",context(a,other)));
    }

    @Test void missingPermissionOrAnotherPlayerCannotExitOrReview(){
        PlayerContext forbidden=new PlayerContext(a,own,Map.of(),Set.of());
        assertThrows(SecurityException.class,()->engine.exitMember(id,"vote",forbidden));
        assertThrows(SecurityException.class,()->engine.revalidateMember(id,"vote",forbidden));
        assertThrows(SecurityException.class,()->engine.memberState(id,context(UUID.randomUUID(),own)));
        assertEquals(0,count("story_member_review_log"));
    }

    @Test void exitAfterChangingNationRetainsBothOriginalAttributionAndObservedIdentity(){
        engine.choose(id,a,"vote","yes",context(a,own));
        nativeNations.put(a,other);
        engine.exitMember(id,"vote",context(a,other));
        var review=engine.memberReviews(a,10).getFirst();
        assertEquals(MemberReviewLedger.Status.EXITED,review.status());
        assertEquals(own,review.originalNationId());assertEquals(other,review.observedNationId());
        assertFalse(instance().contains(a));assertEquals(1,count("story_choice_log"));
        assertThrows(SecurityException.class,()->engine.join(id,context(a,other)));
        nativeNations.put(a,own);engine.join(id,context(a,own));
        assertEquals(own,engine.memberReviews(a,10).getFirst().originalNationId());
    }

    @Test void deferredMembershipAuthorityCannotCommitAfterItsCallbackScopeEnds()throws Exception {
        engine.observeMemberNations(List.of(context(a,other)));defer=true;
        assertThrows(SecurityException.class,()->engine.revalidateMember(id,"vote",context(a,own)));
        assertNotNull(saved);
        assertThrows(SecurityException.class,()->saved.run(null));
        assertEquals("PENDING",engine.memberState(id,context(a,own)).status());assertEquals(1,count("story_member_review_log"));
    }
}
