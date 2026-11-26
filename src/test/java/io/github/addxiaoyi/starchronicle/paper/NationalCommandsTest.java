package io.github.addxiaoyi.starchronicle.paper;

import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.core.ProcessReport;
import io.github.addxiaoyi.starchronicle.integration.StarCoreBridge;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Commands take CAS tokens, never a claimed nationality, rank, holder UUID or OP grant. */
class NationalCommandsTest {
    private final StarChroniclePlugin plugin=mock(StarChroniclePlugin.class,CALLS_REAL_METHODS);
    private final ChronicleService service=mock(ChronicleService.class);
    private final RegionTasks tasks=mock(RegionTasks.class);
    private final StarCoreBridge gateway=mock(StarCoreBridge.class);
    private final Menus menus=mock(Menus.class);
    private final Player player=mock(Player.class);
    private final Command command=mock(Command.class);
    private final UUID actor=UUID.randomUUID();
    private void field(String name,Object value)throws Exception{Field field=StarChroniclePlugin.class.getDeclaredField(name);field.setAccessible(true);field.set(plugin,value);}
    @BeforeEach void setup()throws Exception{
        field("service",service);field("tasks",tasks);field("gateway",gateway);field("menus",menus);field("permissions",Set.of("starchronicle.use"));
        when(player.getUniqueId()).thenReturn(actor);when(player.hasPermission("starchronicle.use")).thenReturn(true);
        doNothing().when(plugin).message(any(UUID.class),anyString());doNothing().when(plugin).failure(any(UUID.class),any(Throwable.class));
    }
    private boolean command(String...args){return plugin.onCommand(player,command,"chronicle",args);}
    @Test void votePassesOnlyActorAndExactNodeRevisionChoice(){
        when(service.nationalVote("instance",actor,"council",9,"aid")).thenReturn(CompletableFuture.completedFuture(new ProcessReport(false,List.of(),"saved")));
        assertTrue(command("vote","instance","council","9","aid"));verify(service).nationalVote("instance",actor,"council",9,"aid");verifyNoMoreInteractions(service);verify(menus).national(actor,"instance");
    }
    @Test void withdrawalUsesExplicitTokenAndRefreshesAfterStaleRejection(){
        when(service.withdrawNationalVote("instance",actor,"old-node",4)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("stale")));
        assertTrue(command("withdraw","instance","old-node","4"));verify(service).withdrawNationalVote("instance",actor,"old-node",4);verifyNoMoreInteractions(service);verify(plugin).failure(eq(actor),any(Throwable.class));verify(menus).national(actor,"instance");
    }
    @Test void nationalCommandOpensOnlyTheActorsJoinedInstanceView(){assertTrue(command("national","instance"));verify(menus).national(actor,"instance");verifyNoInteractions(service);}
    @Test void negativeOverflowAndNonNumericRevisionsAreRejectedBeforeAnyService(){
        for(String revision:List.of("-1","9223372036854775808","nation-a"))assertTrue(command("vote","instance","council",revision,"aid"));
        verifyNoInteractions(service,menus);
    }
    @Test void claimedNationalityOrHolderArgumentsCannotBeAccepted(){
        assertTrue(command("vote","instance","council","0","aid","claimed-nation"));assertTrue(command("withdraw","instance","council","0","claimed-holder"));verifyNoInteractions(service,menus);
    }
    @Test void opWithoutBasicUsePermissionCannotReachNationalCommands(){
        when(player.isOp()).thenReturn(true);when(player.hasPermission("starchronicle.use")).thenReturn(false);assertTrue(command("vote","instance","council","0","aid"));verify(player).sendMessage("没有权限。");verifyNoInteractions(service,menus,gateway,tasks);
    }
    @Test void consoleCannotSupplyAPlayerOrNationForNationalCommands(){
        CommandSender console=mock(CommandSender.class);when(console.hasPermission("starchronicle.use")).thenReturn(true);assertTrue(plugin.onCommand(console,command,"chronicle",new String[]{"vote","instance","council","0","aid"}));verify(console).sendMessage(contains("玩家命令"));verifyNoInteractions(service,menus);
    }
    @Test void tabCompletionIncludesNationalRoutesWithoutInventingTokens(){
        assertEquals(List.of("national"),plugin.onTabComplete(player,command,"chronicle",new String[]{"nation"}));assertEquals(List.of("vote"),plugin.onTabComplete(player,command,"chronicle",new String[]{"vo"}));assertEquals(List.of(),plugin.onTabComplete(player,command,"chronicle",new String[]{"vote","instance","council",""}));
    }
    @Test void pastRequestsNationalChoiceAlongsideTheExistingThreeNamespaces(){
        String reference="council/1/mandate";Map<String,String> facts=Map.of("past.nation.choice."+reference,"coordinate");when(service.recollections(eq(actor),anyCollection())).thenReturn(CompletableFuture.completedFuture(facts));
        assertTrue(command("past",reference));verify(service).recollections(actor,List.of("past.player.choice."+reference,"past.resolution.world."+reference,"past.resolution.nation."+reference,"past.nation.choice."+reference));verify(plugin).message(actor,"past.nation.choice."+reference+" = coordinate");verifyNoMoreInteractions(service);verifyNoInteractions(menus);
    }
    @Test void membershipCommandsTakeOnlyTheCurrentPlayerAndRejectClaimedNationality(){
        when(service.exitMember("instance",actor,"node")).thenReturn(CompletableFuture.completedFuture(new ProcessReport(false,List.of(),"left")));
        when(service.exitMember("instance",actor)).thenReturn(CompletableFuture.completedFuture(new ProcessReport(false,List.of(),"left")));
        when(service.revalidateMember("instance",actor)).thenReturn(CompletableFuture.completedFuture(new ProcessReport(false,List.of(),"reviewed")));
        assertTrue(command("leave","instance","node"));assertTrue(command("revalidate","instance"));
        verify(service).exitMember("instance",actor,"node");verify(service).revalidateMember("instance",actor);
        assertTrue(command("leave","instance"));verify(service).exitMember("instance",actor);
        assertTrue(command("revalidate","instance","claimed-nation"));assertTrue(command("leave","instance","node","claimed-nation"));verifyNoMoreInteractions(service);
    }
}
