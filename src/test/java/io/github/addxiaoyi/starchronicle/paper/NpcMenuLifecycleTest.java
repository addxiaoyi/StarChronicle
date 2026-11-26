package io.github.addxiaoyi.starchronicle.paper;

import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.npc.NpcView;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.entity.Player;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Delayed query results must request the guarded final display, including the unbound fallback. */
class NpcMenuLifecycleTest {
    @ParameterizedTest @ValueSource(booleans={false,true})
    void npcQueryCompletionUsesTheOriginalObservationForSuccessAndFailure(boolean failure) {
        StarChroniclePlugin plugin=mock(StarChroniclePlugin.class);ChronicleService service=mock(ChronicleService.class);
        when(plugin.service()).thenReturn(service);Player player=mock(Player.class);UUID id=UUID.randomUUID();when(player.getUniqueId()).thenReturn(id);
        AtomicBoolean active=new AtomicBoolean(true);var observed=new NpcHooks.Interaction(player,"citizens:id",Instant.EPOCH,active::get);
        var pending=new CompletableFuture<NpcView>();when(service.npc(id,"veteran")).thenReturn(pending);
        new Menus(plugin).npc(observed,"veteran");active.set(false);
        if(failure)pending.completeExceptionally(new IllegalStateException("offline"));
        else pending.complete(new NpcView("veteran","Veteran","history","neutral","veteran",0,0,0,0,"intro","hello",Map.of()));
        verify(plugin).present(eq(player),same(observed.current()),any());
        verify(plugin,never()).present(any(UUID.class),any());verify(plugin,never()).failure(any(),any());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void unboundStoryQueryCompletionUsesTheOriginalObservationForSuccessAndFailure(boolean failure) {
        StarChroniclePlugin plugin=mock(StarChroniclePlugin.class);ChronicleService service=mock(ChronicleService.class);
        when(plugin.service()).thenReturn(service);Player player=mock(Player.class);UUID id=UUID.randomUUID();when(player.getUniqueId()).thenReturn(id);
        AtomicBoolean active=new AtomicBoolean(true);var observed=new NpcHooks.Interaction(player,"citizens:unbound",Instant.EPOCH,active::get);
        var pending=new CompletableFuture<StoryPage>();when(service.storyPage(id,null,45)).thenReturn(pending);
        new Menus(plugin).stories(observed);active.set(false);
        if(failure)pending.completeExceptionally(new IllegalStateException("offline"));else pending.complete(new StoryPage(List.of(),null));
        verify(plugin).present(eq(player),same(observed.current()),any());
        verify(plugin,never()).present(any(UUID.class),any());verify(plugin,never()).failure(any(),any());
    }
}
