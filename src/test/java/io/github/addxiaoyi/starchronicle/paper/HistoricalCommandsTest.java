package io.github.addxiaoyi.starchronicle.paper;

import io.github.addxiaoyi.starchronicle.api.*;
import java.util.concurrent.CompletableFuture;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The public command is an authorization boundary, not an arbitrary event/identity API. */
class HistoricalCommandsTest {
    private final StarChroniclePlugin plugin=mock(StarChroniclePlugin.class,CALLS_REAL_METHODS);
    private final ChronicleService service=mock(ChronicleService.class);
    private final CommandSender console=mock(CommandSender.class);
    private final Command command=mock(Command.class);

    private void setup(boolean use,boolean admin)throws Exception {
        when(console.hasPermission("starchronicle.use")).thenReturn(use);
        when(console.hasPermission("starchronicle.admin")).thenReturn(admin);
        var field=StarChroniclePlugin.class.getDeclaredField("service");field.setAccessible(true);field.set(plugin,service);
        RegionTasks tasks=mock(RegionTasks.class);
        doAnswer(invocation->{invocation.getArgument(0,Runnable.class).run();return null;}).when(tasks).global(any(Runnable.class));
        field=StarChroniclePlugin.class.getDeclaredField("tasks");field.setAccessible(true);field.set(plugin,tasks);
    }
    private boolean evaluate(String...args){return plugin.onCommand(console,command,"chronicle",args);}

    @Test void ordinaryReaderCannotEvaluateEvenWithValidLookingArguments()throws Exception {
        setup(true,false);assertTrue(evaluate("evaluate","saved:canonical","new-chapter","2"));
        verify(console).sendMessage("没有管理权限。");verifyNoInteractions(service);
    }
    @Test void missingBasicPermissionCannotReachAdministration()throws Exception {
        setup(false,true);assertTrue(evaluate("evaluate","saved:canonical","new-chapter","2"));
        verify(console).sendMessage("没有权限。");verifyNoInteractions(service);
    }
    @Test void malformedAdminRequestDoesNotGuessVersionOrCreateSource()throws Exception {
        setup(true,true);assertTrue(evaluate("evaluate","saved:canonical","new-chapter"));
        verify(console).sendMessage(contains("精确版本"));verifyNoInteractions(service);
    }
    @Test void authorizedRequestPassesOnlyExactSavedSourceAndVersionAndReportsRegistration()throws Exception {
        setup(true,true);
        when(service.evaluateHistorical("saved:canonical","new-chapter","2")).thenReturn(CompletableFuture.completedFuture(
                new HistoricalEvaluationReport("saved:canonical","new-chapter","2",HistoricalEvaluationReport.Status.QUEUED,1,0,0,"仅登记，未执行")));
        assertTrue(evaluate("evaluate","saved:canonical","new-chapter","2"));
        verify(service).evaluateHistorical("saved:canonical","new-chapter","2");verifyNoMoreInteractions(service);
        verify(console).sendMessage(contains("QUEUED"));verify(console).sendMessage(contains("未执行"));
    }
    @Test void rejectionIsReportedWithoutClaimingExecutionOrResubmitting()throws Exception {
        setup(true,true);
        when(service.evaluateHistorical(anyString(),anyString(),anyString())).thenReturn(CompletableFuture.failedFuture(new IllegalArgumentException("No saved canonical source")));
        assertTrue(evaluate("evaluate","missing-source","new-chapter","2"));
        verify(service).evaluateHistorical("missing-source","new-chapter","2");verifyNoMoreInteractions(service);
        verify(console).sendMessage(contains("历史评估未登记: No saved canonical source"));
    }
}
