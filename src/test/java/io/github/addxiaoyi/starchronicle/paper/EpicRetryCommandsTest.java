package io.github.addxiaoyi.starchronicle.paper;

import io.github.addxiaoyi.starchronicle.api.ChronicleService;
import io.github.addxiaoyi.starchronicle.api.epic.*;
import io.github.addxiaoyi.starchronicle.integration.StarCoreBridge;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EpicRetryCommandsTest {
    private final StarChroniclePlugin plugin=mock(StarChroniclePlugin.class,CALLS_REAL_METHODS);
    private final ChronicleService service=mock(ChronicleService.class);
    private final Player player=mock(Player.class);
    private final Command command=mock(Command.class);
    private final UUID actor=UUID.randomUUID();

    @BeforeEach void setup()throws Exception {
        var field=StarChroniclePlugin.class.getDeclaredField("service");field.setAccessible(true);field.set(plugin,service);
        field=StarChroniclePlugin.class.getDeclaredField("tasks");field.setAccessible(true);field.set(plugin,mock(RegionTasks.class));
        field=StarChroniclePlugin.class.getDeclaredField("gateway");field.setAccessible(true);field.set(plugin,mock(StarCoreBridge.class));
        field=StarChroniclePlugin.class.getDeclaredField("permissions");field.setAccessible(true);field.set(plugin,Set.of("starchronicle.use"));
        when(player.getUniqueId()).thenReturn(actor);when(player.hasPermission("starchronicle.use")).thenReturn(true);
        doNothing().when(plugin).message(any(UUID.class),anyString());doNothing().when(plugin).failure(any(UUID.class),any(Throwable.class));
    }

    @Test void retryPassesOnlyCurrentActorAndExactOldRevisionAndReportsTheNewId() {
        var operation=new EpicOperation("op:new-attempt","heir","instance","source","aid",EpicOperation.Type.RESOURCE_AID,
                actor,UUID.randomUUID().toString(),UUID.randomUUID().toString(),"agricultural:10","a".repeat(64),"starcore.resource",Instant.now());
        var prepared=new EpicOperationState(operation,EpicOperationState.Status.PREPARED,null,"explicit retry",1,Instant.now());
        when(service.retryEpicOperation("op:old",actor,3)).thenReturn(CompletableFuture.completedFuture(prepared));
        assertTrue(plugin.onCommand(player,command,"chronicle",new String[]{"epic","retry","op:old","3"}));
        verify(service).retryEpicOperation("op:old",actor,3);verifyNoMoreInteractions(service);
        verify(plugin).message(eq(actor),contains("operation=op:new-attempt"));verify(plugin).message(eq(actor),contains("显式 apply"));
    }

    @Test void invalidRevisionOrClaimedIdentityDoesNotReachRetryService() {
        for(String revision:List.of("-1","9223372036854775808","nation"))
            assertTrue(plugin.onCommand(player,command,"chronicle",new String[]{"epic","retry","op:old",revision}));
        assertTrue(plugin.onCommand(player,command,"chronicle",new String[]{"epic","retry","op:old","3","claimed-actor"}));
        verifyNoInteractions(service);
    }

    @Test void retryRequiresAPlayerWithBasicUsePermission() {
        var console=mock(CommandSender.class);when(console.hasPermission("starchronicle.use")).thenReturn(true);
        assertTrue(plugin.onCommand(console,command,"chronicle",new String[]{"epic","retry","op:old","3"}));
        when(player.hasPermission("starchronicle.use")).thenReturn(false);
        assertTrue(plugin.onCommand(player,command,"chronicle",new String[]{"epic","retry","op:old","3"}));
        verifyNoInteractions(service);
    }

    @Test void lineageQueryAndCompletionExposeExplicitRetryAndAttemptRoutes() {
        when(service.epicOperationAttempt("op:new",actor)).thenReturn(CompletableFuture.completedFuture(
                new EpicOperationAttempt("op:new","op:root",2,"op:old",null)));
        assertTrue(plugin.onCommand(player,command,"chronicle",new String[]{"epic","attempt","op:new"}));
        verify(service).epicOperationAttempt("op:new",actor);
        verify(plugin).message(eq(actor),contains("root=op:root attempt=2 previous=op:old"));
        assertEquals(List.of("retry"),plugin.onTabComplete(player,command,"chronicle",new String[]{"epic","ret"}));
        assertEquals(List.of("attempt"),plugin.onTabComplete(player,command,"chronicle",new String[]{"epic","att"}));
    }
}
