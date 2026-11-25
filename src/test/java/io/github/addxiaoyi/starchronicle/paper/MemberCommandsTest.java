package io.github.addxiaoyi.starchronicle.paper;

import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.core.ProcessReport;
import io.github.addxiaoyi.starchronicle.integration.StarCoreBridge;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MemberCommandsTest {
    final StarChroniclePlugin plugin=mock(StarChroniclePlugin.class,CALLS_REAL_METHODS);
    final ChronicleService service=mock(ChronicleService.class);
    final StarCoreBridge gateway=mock(StarCoreBridge.class);
    final RegionTasks tasks=mock(RegionTasks.class);
    final Player player=mock(Player.class);
    final UUID actor=UUID.randomUUID();
    @BeforeEach void setup()throws Exception {
        for(var entry:Map.of("service",service,"gateway",gateway,"tasks",tasks,"permissions",Set.of("starchronicle.use")).entrySet()) {
            var f=StarChroniclePlugin.class.getDeclaredField(entry.getKey());f.setAccessible(true);f.set(plugin,entry.getValue());
        }
        when(player.getUniqueId()).thenReturn(actor);when(player.hasPermission("starchronicle.use")).thenReturn(true);
        doNothing().when(plugin).message(any(),anyString());doNothing().when(plugin).failure(any(),any());
    }
    void command(String...args){assertTrue(plugin.onCommand(player,mock(Command.class),"chronicle",args));}
    @Test void exitKeepsNodeAndUsesOnlyTheSenderIdentity(){
        when(service.exitMember("instance",actor,"vote")).thenReturn(CompletableFuture.completedFuture(new ProcessReport(false,List.of("instance"),"exited")));
        command("member","exit","instance","vote");
        verify(service).exitMember("instance",actor,"vote");verifyNoMoreInteractions(service);verify(plugin).message(actor,"exited");
    }
    @Test void revalidationFailureIsReportedWithoutJoinOrPermissionFallback(){
        when(service.revalidateMember("instance",actor,"vote")).thenReturn(CompletableFuture.failedFuture(new SecurityException("current native membership changed")));
        command("member","revalidate","instance","vote");
        verify(service).revalidateMember("instance",actor,"vote");verifyNoMoreInteractions(service);verify(plugin).failure(eq(actor),any());
    }
    @Test void statusExplainsReviewWithoutExposingAdapterInternals(){
        when(service.memberState("instance",actor)).thenReturn(CompletableFuture.completedFuture(new MemberState("instance",actor,"vote",false,"old","new","PENDING","adapter internal evidence")));
        command("member","status","instance");
        verify(plugin).message(eq(actor),argThat(text->text.contains("待复核")&&text.contains("重新核验")&&!text.contains("adapter")));
    }
    @Test void missingNodeExtraArgsAndPermissionDenialDoNotReachService(){
        command("member","exit","instance");command("member","revalidate","instance","vote","other-player");
        when(player.hasPermission("starchronicle.use")).thenReturn(false);command("member","list");verifyNoInteractions(service);
    }
}
