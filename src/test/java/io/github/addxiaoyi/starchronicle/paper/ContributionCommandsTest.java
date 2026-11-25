package io.github.addxiaoyi.starchronicle.paper;

import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.integration.StarCoreBridge;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ContributionCommandsTest {
    private final StarChroniclePlugin plugin=mock(StarChroniclePlugin.class,CALLS_REAL_METHODS);
    private final ChronicleService service=mock(ChronicleService.class);
    private final StarCoreBridge gateway=mock(StarCoreBridge.class);
    private final RegionTasks tasks=mock(RegionTasks.class);
    private final Player player=mock(Player.class);
    private final Command command=mock(Command.class);
    private final UUID actor=UUID.randomUUID();
    private final String nation=UUID.randomUUID().toString();
    private void field(String name,Object value)throws Exception {
        var f=StarChroniclePlugin.class.getDeclaredField(name);f.setAccessible(true);f.set(plugin,value);
    }
    @BeforeEach void setup()throws Exception {
        field("service",service);field("gateway",gateway);field("tasks",tasks);field("permissions",Set.of("starchronicle.use"));
        field("running",new java.util.concurrent.atomic.AtomicBoolean(true));
        when(player.getUniqueId()).thenReturn(actor);when(player.isOnline()).thenReturn(true);
        when(player.hasPermission("starchronicle.use")).thenReturn(true);
        when(gateway.context(actor)).thenReturn(new PlayerContext(actor,nation,Map.of(),Set.of("starchronicle.use")));
        doAnswer(call->{call.getArgument(1,java.util.function.Consumer.class).accept(player);return null;})
                .when(tasks).playerOwned(eq(actor),any(),any());
        when(service.contributions(anyString(),isNull(),anyInt(),eq(10))).thenReturn(CompletableFuture.completedFuture(List.of()));
        doNothing().when(plugin).message(any(),anyString());doNothing().when(plugin).failure(any(),any());
    }
    private boolean run(String...args){return plugin.onCommand(player,command,"chronicle",args);}

    @Test void defaultQueryUsesSenderAndBoundedPageWithoutMutation() {
        assertTrue(run("contributions","2"));
        verify(service).contributions("player:"+actor,null,1,10);verifyNoMoreInteractions(service);
        verify(plugin).message(actor,"本页暂无已核验贡献。");
    }
    @Test void currentNationIsCollectedFromTheOwnedPlayer() {
        assertTrue(run("contributions","nation","me"));
        verify(tasks).playerOwned(eq(actor),any(),any());
        verify(service).contributions("nation:"+nation,null,0,10);verifyNoMoreInteractions(service);
    }
    @Test void nationlessPlayerDoesNotFallBackToAllNations() {
        when(gateway.context(actor)).thenReturn(new PlayerContext(actor,"",Map.of(),Set.of("starchronicle.use")));
        assertTrue(run("contributions","nation","me"));
        verifyNoInteractions(service);verify(plugin).failure(eq(actor),any());
    }
    @Test void noUsePermissionCannotReadAnotherPlayersLedger() {
        when(player.hasPermission("starchronicle.use")).thenReturn(false);
        assertTrue(run("contributions","player",UUID.randomUUID().toString()));
        verifyNoInteractions(service,gateway,tasks);verify(player).sendMessage("没有权限。");
    }
    @Test void invalidPageOrExtraArgumentsNeverReachTheService() {
        for(String page:List.of("0","-1","2147483648","NaN"))assertTrue(run("contributions",page));
        assertTrue(run("contributions","player","me","1","extra"));
        assertTrue(run("contributions","nation","untrusted-nation"));
        verifyNoInteractions(service);
    }
}
