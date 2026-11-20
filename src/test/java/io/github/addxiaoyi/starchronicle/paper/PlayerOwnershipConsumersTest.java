package io.github.addxiaoyi.starchronicle.paper;

import io.github.addxiaoyi.starchronicle.api.PlayerContext;
import io.github.addxiaoyi.starchronicle.integration.StarCoreBridge;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** A reconnect may replace the registry after the scheduler's lease guard has passed.
 * Consumers must retain that scheduler's Player instead of resolving the UUID again. */
class PlayerOwnershipConsumersTest {
    private final UUID id=UUID.randomUUID();
    private final StarChroniclePlugin plugin=mock(StarChroniclePlugin.class,CALLS_REAL_METHODS);
    private final RegionTasks tasks=mock(RegionTasks.class);
    private final StarCoreBridge gateway=mock(StarCoreBridge.class);
    private final Player captured=mock(Player.class),replacement=mock(Player.class);
    private final AtomicReference<Player> registry=new AtomicReference<>(captured);
    private final Queue<Runnable> scheduled=new ArrayDeque<>();
    private final Set<String> permissions=Set.of("starchronicle.use");

    private void field(String name,Object value)throws Exception {
        Field field=StarChroniclePlugin.class.getDeclaredField(name);field.setAccessible(true);field.set(plugin,value);
    }
    @BeforeEach void setup()throws Exception {
        field("running",new AtomicBoolean(true));field("tasks",tasks);field("gateway",gateway);field("permissions",permissions);
        when(captured.isOnline()).thenReturn(true);when(captured.hasPermission("starchronicle.use")).thenReturn(true);
        when(tasks.current(id)).thenAnswer(ignored->registry.get());
        // Simulate replacement immediately after the owner lease check and before the consumer body.
        doAnswer(invocation->{Consumer<Player> action=invocation.getArgument(1);scheduled.add(()->{registry.set(replacement);action.accept(captured);});return null;})
            .when(tasks).playerOwned(eq(id),any(),any(Runnable.class));
        // Keep the old callback form executable so this regression fails if a consumer reverts to it.
        doAnswer(invocation->{Runnable action=invocation.getArgument(1);scheduled.add(()->{registry.set(replacement);action.run();});return null;})
            .when(tasks).player(eq(id),any(Runnable.class),any(Runnable.class));
        doAnswer(invocation->{Runnable action=invocation.getArgument(1);scheduled.add(()->{registry.set(replacement);action.run();});return null;})
            .when(tasks).player(eq(id),any(Runnable.class));
    }
    private void runOwner(){assertEquals(1,scheduled.size());scheduled.remove().run();assertSame(replacement,registry.get());}
    private void noReplacementAccess(){verify(tasks,never()).current(id);verifyNoInteractions(replacement);}

    @Test void presentationUsesTheCapturedEntityAfterRegistryReplacement() {
        AtomicReference<Player> displayed=new AtomicReference<>();plugin.present(id,displayed::set);runOwner();
        assertSame(captured,displayed.get());verify(gateway).refreshContext(captured,permissions);noReplacementAccess();
    }
    @Test void snapshotReadsContextFromTheCapturedEntityAfterRegistryReplacement()throws Exception {
        PlayerContext context=new PlayerContext(id,"country",Map.of(),permissions);when(gateway.context(id)).thenReturn(context);
        Method snapshot=StarChroniclePlugin.class.getDeclaredMethod("snapshot",UUID.class);snapshot.setAccessible(true);
        CompletionStage<?> pending=(CompletionStage<?>)snapshot.invoke(plugin,id);runOwner();
        assertSame(context,pending.toCompletableFuture().join());verify(gateway).refreshContext(captured,permissions);noReplacementAccess();
    }
    @Test void playerQueryAuthorizesAndExecutesOnlyTheCapturedEntity()throws Exception {
        Class<?> workType=Arrays.stream(StarChroniclePlugin.class.getDeclaredClasses()).filter(type->type.getSimpleName().equals("PlayerWork")).findFirst().orElseThrow();
        AtomicReference<Player> invoked=new AtomicReference<>();
        Object work=Proxy.newProxyInstance(workType.getClassLoader(),new Class<?>[]{workType},(proxy,method,args)->{invoked.set((Player)args[0]);return "owned-result";});
        Method query=StarChroniclePlugin.class.getDeclaredMethod("playerQuery",UUID.class,workType);query.setAccessible(true);
        CompletionStage<?> pending=(CompletionStage<?>)query.invoke(plugin,id,work);runOwner();
        assertEquals("owned-result",pending.toCompletableFuture().join());assertSame(captured,invoked.get());
        verify(captured).hasPermission("starchronicle.use");noReplacementAccess();
    }
}
