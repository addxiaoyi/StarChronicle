package io.github.addxiaoyi.starchronicle.paper;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import java.util.UUID;
import java.util.concurrent.atomic.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RegionTasksTest {
    @Test void reconnectCannotRunReplacementEntityOnRetiredPlayersScheduler(){
        JavaPlugin plugin=mock(JavaPlugin.class);RegionTasks tasks=new RegionTasks(plugin);Player old=mock(Player.class),replacement=mock(Player.class);EntityScheduler scheduler=mock(EntityScheduler.class);UUID id=UUID.randomUUID();
        when(old.getUniqueId()).thenReturn(id);when(old.getScheduler()).thenReturn(scheduler);when(replacement.getUniqueId()).thenReturn(id);
        AtomicReference<Runnable> queued=new AtomicReference<>();when(scheduler.execute(eq(plugin),any(),any(),eq(1L))).thenAnswer(i->{queued.set(i.getArgument(1));return true;});
        tasks.track(old);AtomicInteger retired=new AtomicInteger();tasks.playerOwned(id,entity->fail("old lease must not read a replacement entity"),retired::incrementAndGet);
        tasks.track(replacement);tasks.untrack(old);assertSame(replacement,tasks.current(id));queued.get().run();assertEquals(1,retired.get());verify(replacement,never()).getScheduler();
    }
    @Test void offlinePlayerNeverFallsBackToGlobalEntityMutation(){RegionTasks tasks=new RegionTasks(mock(JavaPlugin.class));AtomicInteger mutated=new AtomicInteger();tasks.player(UUID.randomUUID(),mutated::incrementAndGet);assertEquals(0,mutated.get());}
    @Test void entityMutationRunsOnlyThroughItsScheduler(){JavaPlugin plugin=mock(JavaPlugin.class);RegionTasks tasks=new RegionTasks(plugin);Player player=mock(Player.class);EntityScheduler scheduler=mock(EntityScheduler.class);UUID id=UUID.randomUUID();when(player.getUniqueId()).thenReturn(id);when(player.getScheduler()).thenReturn(scheduler);AtomicReference<Runnable> queued=new AtomicReference<>();when(scheduler.execute(eq(plugin),any(),any(),eq(1L))).thenAnswer(i->{queued.set(i.getArgument(1));return true;});tasks.track(player);AtomicInteger mutated=new AtomicInteger();tasks.player(id,mutated::incrementAndGet);assertEquals(0,mutated.get());assertNotNull(queued.get());queued.get().run();assertEquals(1,mutated.get());}
    @Test void rejectedSchedulingCompletesRetirementOnce(){JavaPlugin plugin=mock(JavaPlugin.class);RegionTasks tasks=new RegionTasks(plugin);Player player=mock(Player.class);EntityScheduler scheduler=mock(EntityScheduler.class);UUID id=UUID.randomUUID();when(player.getUniqueId()).thenReturn(id);when(player.getScheduler()).thenReturn(scheduler);when(scheduler.execute(eq(plugin),any(),any(),eq(1L))).thenAnswer(i->{((Runnable)i.getArgument(2)).run();return false;});tasks.track(player);AtomicInteger retired=new AtomicInteger();tasks.player(id,()->fail("retired entity action ran"),retired::incrementAndGet);assertEquals(1,retired.get());}
    @Test void closedRegistryRejectsNewEntityWork(){RegionTasks tasks=new RegionTasks(mock(JavaPlugin.class));tasks.close();AtomicInteger retired=new AtomicInteger();tasks.player(UUID.randomUUID(),()->fail("stopped entity action ran"),retired::incrementAndGet);assertEquals(1,retired.get());assertTrue(tasks.onlineIds().isEmpty());}
    @Test void closingAfterEnqueueRetiresWorkInsteadOfMutatingAfterShutdown(){
        JavaPlugin plugin=mock(JavaPlugin.class);RegionTasks tasks=new RegionTasks(plugin);Player player=mock(Player.class);EntityScheduler scheduler=mock(EntityScheduler.class);UUID id=UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);when(player.getScheduler()).thenReturn(scheduler);AtomicReference<Runnable> queued=new AtomicReference<>();
        when(scheduler.execute(eq(plugin),any(),any(),eq(1L))).thenAnswer(i->{queued.set(i.getArgument(1));return true;});tasks.track(player);AtomicInteger retired=new AtomicInteger();
        tasks.player(id,()->fail("queued action ran after close"),retired::incrementAndGet);tasks.close();queued.get().run();assertEquals(1,retired.get());
    }
}
