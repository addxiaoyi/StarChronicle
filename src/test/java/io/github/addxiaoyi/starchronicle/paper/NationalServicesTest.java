package io.github.addxiaoyi.starchronicle.paper;

import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.core.*;
import io.github.addxiaoyi.starchronicle.integration.StarCoreBridge;
import io.github.addxiaoyi.starchronicle.integration.StarCoreNationalVoteAuthority;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The public service takes a new owning-player snapshot before any national SQL work. */
class NationalServicesTest {
    private final UUID actor=UUID.randomUUID();
    private final StarChroniclePlugin plugin=mock(StarChroniclePlugin.class,CALLS_REAL_METHODS);
    private final RegionTasks tasks=mock(RegionTasks.class);
    private final StarCoreBridge gateway=mock(StarCoreBridge.class);
    private final RuleEngine engine=mock(RuleEngine.class);
    private final ThreadPoolExecutor worker=mock(ThreadPoolExecutor.class);
    private final Player player=mock(Player.class);
    private final List<Runnable> playerWork=new ArrayList<>();
    private final List<Runnable> stateWork=new ArrayList<>();
    private ChronicleService service;
    private void field(String name,Object value)throws Exception{Field field=StarChroniclePlugin.class.getDeclaredField(name);field.setAccessible(true);field.set(plugin,value);}
    @BeforeEach void setup()throws Exception{
        field("running",new AtomicBoolean(true));field("tasks",tasks);field("gateway",gateway);field("engine",engine);field("worker",worker);field("permissions",Set.of("starchronicle.use","choice.real"));
        when(tasks.onlineIds()).thenReturn(Set.of(actor));when(player.isOnline()).thenReturn(true);
        doAnswer(invocation->{java.util.function.Consumer<Player> action=invocation.getArgument(1);playerWork.add(()->action.accept(player));return null;}).when(tasks).playerOwned(eq(actor),any(),any(Runnable.class));
        doAnswer(invocation->{stateWork.add(invocation.getArgument(0,Runnable.class));return null;}).when(worker).execute(any(Runnable.class));
        Class<?> type=Arrays.stream(StarChroniclePlugin.class.getDeclaredClasses()).filter(item->item.getSimpleName().equals("Service")).findFirst().orElseThrow();Constructor<?> constructor=type.getDeclaredConstructor(StarChroniclePlugin.class);constructor.setAccessible(true);service=(ChronicleService)constructor.newInstance(plugin);
    }
    private PlayerContext context(String nation,Set<String> permissions){return new PlayerContext(actor,nation,Map.of("player.role","monarch"),permissions);}
    private void snapshot(PlayerContext current){when(gateway.context(actor)).thenReturn(current);assertEquals(1,playerWork.size());playerWork.removeFirst().run();}
    private void runWorker(){assertEquals(1,stateWork.size());stateWork.removeFirst().run();}

    @Test void voteKeepsSuppliedCasTokenAndPassesOnlyFreshServerIdentity(){
        PlayerContext current=context("current-native-nation",Set.of("starchronicle.use"));ProcessReport expected=new ProcessReport(false,List.of("instance"),"recorded");
        when(engine.nationalVote("instance",actor,"council",7,"aid",current)).thenReturn(expected);
        CompletionStage<ProcessReport> pending=service.nationalVote("instance",actor,"council",7,"aid");verifyNoInteractions(engine);snapshot(current);verifyNoInteractions(engine);runWorker();assertEquals(expected,pending.toCompletableFuture().join());
        var order=inOrder(gateway,engine);order.verify(gateway).refreshContext(player,Set.of("starchronicle.use","choice.real"));order.verify(gateway).context(actor);order.verify(engine).nationalVote("instance",actor,"council",7,"aid",current);verify(engine).pending(Set.of(actor));
    }
    @Test void withdrawCollectsFreshContextAndDoesNotLookUpALatestRevision(){
        PlayerContext current=context("changed-nation",Set.of("starchronicle.use"));ProcessReport expected=new ProcessReport(false,List.of(),"withdrawn");when(engine.withdrawNationalVote("instance",actor,"old-node",19,current)).thenReturn(expected);
        CompletionStage<ProcessReport> pending=service.withdrawNationalVote("instance",actor,"old-node",19);snapshot(current);runWorker();assertEquals(expected,pending.toCompletableFuture().join());verify(engine).withdrawNationalVote("instance",actor,"old-node",19,current);verify(engine,never()).nationalState(anyString(),any(PlayerContext.class));
    }
    @Test void recordedStateIsReadOnWorkerWithFreshSnapshotEvenForNonRepresentative(){
        PlayerContext current=context("",Set.of("starchronicle.use"));NationalVoteState expected=new NationalVoteState("instance","council",2,"",false,"",0,false,false,false,"spectator",Map.of("aid",1L),List.of());when(engine.nationalState("instance",current)).thenReturn(expected);
        CompletionStage<NationalVoteState> pending=service.nationalState("instance",actor);verifyNoInteractions(engine);snapshot(current);runWorker();assertEquals(expected,pending.toCompletableFuture().join());verify(engine).nationalState("instance",current);verify(engine).pending(Set.of(actor));
    }
    @Test void freshUsePermissionDenialBlocksVoteWithdrawAndReadBeforeWorker(){
        for(int operation=0;operation<3;operation++){
            CompletionStage<?> pending=switch(operation){case 0->service.nationalVote("instance",actor,"council",0,"aid");case 1->service.withdrawNationalVote("instance",actor,"council",0);default->service.nationalState("instance",actor);};
            snapshot(context("nation",Set.of("starchronicle.admin","starchronicle.identity.monarch")));CompletionException error=assertThrows(CompletionException.class,()->pending.toCompletableFuture().join());assertInstanceOf(SecurityException.class,error.getCause());
        }
        verifyNoInteractions(engine,worker);assertTrue(stateWork.isEmpty());
    }
    @Test void backendAuthorityRejectionDoesNotTriggerAnotherVoteOrChooseFallback(){
        PlayerContext current=context("nation",Set.of("starchronicle.use","starchronicle.admin","starchronicle.identity.monarch"));when(engine.nationalVote("instance",actor,"council",5,"aid",current)).thenThrow(new SecurityException("not the actual holder"));
        CompletionStage<ProcessReport> pending=service.nationalVote("instance",actor,"council",5,"aid");snapshot(current);runWorker();CompletionException error=assertThrows(CompletionException.class,()->pending.toCompletableFuture().join());assertInstanceOf(SecurityException.class,error.getCause());verify(engine).nationalVote("instance",actor,"council",5,"aid",current);verifyNoMoreInteractions(engine);
    }
    @Test void offlineSnapshotCannotReachNationalEngine(){
        when(player.isOnline()).thenReturn(false);CompletionStage<?> pending=service.nationalState("instance",actor);assertEquals(1,playerWork.size());playerWork.removeFirst().run();assertThrows(CompletionException.class,()->pending.toCompletableFuture().join());verifyNoInteractions(engine,worker,gateway);
    }

    @Test void membershipReviewUsesNewSnapshotBeforeEnginePerformsNativeMemberApproval() {
        var current=context("current-native-nation",Set.of("starchronicle.use"));
        var expected=new ProcessReport(false,List.of("instance"),"reviewed");
        when(engine.revalidateMember("instance",current)).thenReturn(expected);
        var pending=service.revalidateMember("instance",actor);verifyNoInteractions(engine);
        snapshot(current);verifyNoInteractions(engine);runWorker();
        assertEquals(expected,pending.toCompletableFuture().join());verify(engine).revalidateMember("instance",current);
    }

    @Test void memberExitUsesExplicitNodeAndFreshIdentityEvenAfterNationalityChanged(){
        var current=context("new-nation",Set.of("starchronicle.use"));
        var pending=service.exitMember("instance",actor,"old-node");snapshot(current);runWorker();pending.toCompletableFuture().join();
        verify(engine).exitMember("instance","old-node",current);
        assertThrows(CompletionException.class,()->service.exitMember("instance",actor,"").toCompletableFuture().join());
    }

    @Test void reviewReadRefreshesPendingStateBeforeReadingAndPermissionRevocationBlocksExit(){
        var current=context("new-nation",Set.of("starchronicle.use"));
        when(engine.memberReviews(actor,50)).thenReturn(List.of());var pending=service.memberReviews(actor,50);snapshot(current);runWorker();
        assertEquals(List.of(),pending.toCompletableFuture().join());var order=inOrder(engine);order.verify(engine).observeMembers(List.of(current));order.verify(engine).memberReviews(actor,50);
        var exit=service.exitMember("instance",actor,"node");snapshot(context("new-nation",Set.of()));
        assertThrows(CompletionException.class,()->exit.toCompletableFuture().join());verify(engine,never()).exitMember(anyString(),anyString(),any());
    }
}
