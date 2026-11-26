package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition;
import io.github.addxiaoyi.starchronicle.memory.MemoryRecipeCatalog;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MemberReviewTest {
    static final UUID A=UUID.randomUUID(),B=UUID.randomUUID();
    static final Instant NOW=Instant.parse("2026-11-26T00:00:00Z");
    @TempDir Path temp;
    SqliteStore store; RuleEngine engine; String instance;
    String current="river"; boolean available=true;
    PlayerContext context(String nation){return new PlayerContext(A,nation,Map.of(),Set.of("starchronicle.use"));}
    MemberAuthority authority(){return new MemberAuthority(){
        public <T>T withMember(UUID player,String expected,Authorized<T> action)throws Exception {
            if(!available||!expected.equals(current))throw new SecurityException("native state changed");
            return action.run(new Membership(player,current,"Native.membership","locked member="+player,NOW));
        }
    };}
    StoryDefinition story(){
        return new StoryDefinition("review","1","Member review",StoryDefinition.Scope.WORLD,List.of("COMMITTED"),null,"council",0,
                Map.of("council",new StoryDefinition.Node("Council",null,null,2,Map.of("yes",new StoryDefinition.Choice("Yes","end",null,null,List.of())),List.of(),false),
                        "end",new StoryDefinition.Node("End",null,null,1,Map.of(),List.of(),true)));
    }
    void reopen(MemberAuthority authority){store=new SqliteStore(temp.resolve("members.db"));engine=new RuleEngine(store,List.of(story()),Clock.fixed(NOW,ZoneOffset.UTC),null,NationalVoteAuthority.denying(),MemoryRecipeCatalog.empty(),null,authority);}
    @BeforeEach void setup(){reopen(authority());instance=engine.accept(new WorldEvent("source","COMMITTED",NOW,Set.of(A,B),Set.of("river","hill"),Map.of(),"native","committed source"),
            List.of(context("river"),new PlayerContext(B,"hill",Map.of(),Set.of("starchronicle.use")))).instanceIds().getFirst();}
    @AfterEach void close(){store.close();}
    @Test void changedNationStaysPendingUntilOriginalNativeMembershipIsExplicitlyRevalidatedAcrossRestart(){
        current="hill";
        assertThrows(SecurityException.class,()->engine.choose(instance,A,"yes",context("hill")));
        assertEquals(MemberReviewLedger.Status.PENDING,engine.memberReviews(A,10).getFirst().status());
        assertThrows(SecurityException.class,()->engine.revalidateMember(instance,context("river")),"stale snapshot cannot approve");
        assertThrows(SecurityException.class,()->engine.revalidateMember(instance,context("hill")),"new nation cannot replace original membership");
        current="river";store.close();reopen(authority());
        assertTrue(engine.availableChoices(instance,context("river")).isEmpty());
        engine.revalidateMember(instance,context("river"));assertFalse(engine.availableChoices(instance,context("river")).isEmpty());
        assertTrue(engine.memberReviews(A,10).getFirst().reason().contains("Native.membership"));
    }
    @Test void exitSurvivesBackgroundResumeAndRestartAndRejoinPreservesHistoricalChoice(){
        engine.choose(instance,A,"yes",context("river"));
        var choices=engine.instances(A).getFirst().choices();assertFalse(choices.isEmpty());
        engine.exitMember(instance,context("river"));
        engine.resumeTriggers(List.of(context("river")));
        assertFalse(engine.activeInstances().getFirst().contains(A));
        store.close();reopen(authority());engine.resumeTriggers(List.of(context("river")));
        assertEquals(MemberReviewLedger.Status.EXITED,engine.memberReviews(A,10).getFirst().status());
        assertFalse(engine.activeInstances().getFirst().contains(A));
        assertThrows(SecurityException.class,()->engine.join(instance,context("hill")));
        engine.join(instance,context("river"));assertTrue(engine.activeInstances().getFirst().contains(A));
        assertEquals(choices,engine.instances(A).getFirst().choices());
    }
    @Test void missingAuthorityUsePermissionAndWrongCallbackIdentityCannotApprove(){
        available=false;assertThrows(SecurityException.class,()->engine.revalidateMember(instance,context("river")));
        available=true;assertThrows(SecurityException.class,()->engine.revalidateMember(instance,new PlayerContext(A,"river",Map.of(),Set.of())));
        store.close();reopen(new MemberAuthority(){public <T>T withMember(UUID player,String nation,Authorized<T> action)throws Exception{
            return action.run(new Membership(B,nation,"wrong","wrong player",NOW));
        }});
        assertThrows(SecurityException.class,()->engine.revalidateMember(instance,context("river")));
        assertTrue(engine.memberReviews(A,10).isEmpty());
    }
    @Test void callbackThatNeverRunsOrRunsAfterReturningCannotApprove(){
        var deferred=new java.util.concurrent.atomic.AtomicReference<MemberAuthority.Authorized<?>>();
        store.close();reopen(new MemberAuthority(){public <T>T withMember(UUID player,String nation,Authorized<T> action){deferred.set(action);return null;}});
        assertThrows(SecurityException.class,()->engine.revalidateMember(instance,context("river")));
        assertThrows(SecurityException.class,()->deferred.get().run(new MemberAuthority.Membership(A,"river","late","late callback",NOW)));
        assertTrue(engine.memberReviews(A,10).isEmpty());
    }
    @Test void completedInstanceMembershipAndHistoricalBallotsCannotBeEdited(){
        engine.choose(instance,A,"yes",context("river"));engine.choose(instance,B,"yes",new PlayerContext(B,"hill",Map.of(),Set.of("starchronicle.use")));
        assertThrows(IllegalStateException.class,()->engine.exitMember(instance,context("river")));
        assertThrows(IllegalStateException.class,()->engine.revalidateMember(instance,context("river")));
        assertTrue(engine.instances(A).getFirst().contains(A));
    }
}
