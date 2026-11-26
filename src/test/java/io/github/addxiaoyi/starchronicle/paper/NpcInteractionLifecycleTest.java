package io.github.addxiaoyi.starchronicle.paper;

import io.github.addxiaoyi.starchronicle.api.PlayerContext;
import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import io.github.addxiaoyi.starchronicle.core.RuleEngine;
import io.github.addxiaoyi.starchronicle.integration.StarCoreBridge;
import io.github.addxiaoyi.starchronicle.npc.*;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real RegionTasks plus queued entity/state callbacks exercise the production ingress boundary. */
class NpcInteractionLifecycleTest {
    private final StarChroniclePlugin plugin = mock(StarChroniclePlugin.class, CALLS_REAL_METHODS);
    private final RegionTasks tasks = new RegionTasks(plugin);
    private final StarCoreBridge gateway = mock(StarCoreBridge.class);
    private final RuleEngine engine = mock(RuleEngine.class);
    private final ThreadPoolExecutor worker = mock(ThreadPoolExecutor.class);
    private final Player actor = mock(Player.class), replacement = mock(Player.class);
    private final EntityScheduler scheduler = mock(EntityScheduler.class);
    private final UUID id = UUID.randomUUID();
    private final AtomicBoolean active = new AtomicBoolean(true), running = new AtomicBoolean(true);
    private final Queue<Runnable> entities = new ArrayDeque<>(), states = new ArrayDeque<>();
    private final Instant observedAt = Instant.parse("2026-11-25T01:02:03.123Z");
    private final Set<String> permissions = Set.of("starchronicle.use");
    private PlayerContext context;

    private void field(String name, Object value) throws Exception {
        Field field = StarChroniclePlugin.class.getDeclaredField(name); field.setAccessible(true); field.set(plugin, value);
    }
    @BeforeEach void setup() throws Exception {
        field("running", running); field("tasks", tasks); field("gateway", gateway);
        field("engine", engine); field("worker", worker); field("permissions", permissions);
        NpcLedger ledger = mock(NpcLedger.class);
        NpcCatalog catalog = NpcCatalog.of(Map.of("veteran", new NpcCatalog.Profile("老兵", "曾经的见证者", "neutral", "veteran",
                List.of(), List.of(new NpcCatalog.Binding("Citizens", "stable-id")), List.of(), List.of())));
        when(ledger.catalog()).thenReturn(catalog); field("npcLedger", ledger);
        when(actor.getUniqueId()).thenReturn(id); when(replacement.getUniqueId()).thenReturn(id);
        when(actor.isOnline()).thenReturn(true); when(actor.getScheduler()).thenReturn(scheduler);
        when(scheduler.execute(eq(plugin), any(), any(), eq(1L))).thenAnswer(i -> { entities.add(i.getArgument(1)); return true; });
        doAnswer(i -> { states.add(i.getArgument(0)); return null; }).when(worker).execute(any(Runnable.class));
        context = new PlayerContext(id, "observed-nation", Map.of(), permissions);
        when(gateway.context(id)).thenAnswer(i -> context);
        when(gateway.captureContext(actor,permissions)).thenAnswer(i -> context); tasks.track(actor);
    }
    private NpcHooks.Interaction observation() { return new NpcHooks.Interaction(actor, "citizens:stable-id", observedAt, active::get); }
    private CompletionStage<String> observe() { return plugin.observeNpcInteraction(observation()); }
    private void owner() { assertEquals(1, entities.size()); entities.remove().run(); }
    private void state() { assertEquals(1, states.size()); states.remove().run(); }
    private void fails(CompletionStage<?> pending) { assertThrows(RuntimeException.class, () -> pending.toCompletableFuture().join()); }
    private void noHistory() { verifyNoInteractions(engine); assertTrue(states.isEmpty()); }

    @Test void observationKeepsOriginalTimeBindingAndActorContextUntilWorkerAdmission() {
        PlayerContext frozen = context;
        var pending = observe(); verifyNoInteractions(gateway, engine); owner(); verifyNoInteractions(engine);
        context = new PlayerContext(id, "later-nation", Map.of(), permissions);
        state(); assertEquals("veteran", pending.toCompletableFuture().join());
        var event = ArgumentCaptor.forClass(WorldEvent.class);
        verify(engine).accept(event.capture(), eq(List.of(frozen)));
        assertEquals(observedAt, event.getValue().occurredAt());
        assertEquals(Set.of("observed-nation"), event.getValue().nations());
        assertEquals(Set.of(id), event.getValue().participants());
        assertEquals("citizens:stable-id", event.getValue().facts().get("npc.binding"));
        assertEquals("veteran", event.getValue().facts().get("npc.id"));
        assertEquals("OBSERVED", event.getValue().facts().get("time_basis"));
        verify(gateway, times(1)).captureContext(actor, permissions);
        verify(gateway, never()).context(id);
    }
    @Test void replacementAlreadyOnlineCannotSupplyContextForOldInteraction() {
        EntityScheduler next = mock(EntityScheduler.class); when(replacement.getScheduler()).thenReturn(next);
        when(next.execute(eq(plugin), any(), any(), eq(1L))).thenAnswer(i -> { entities.add(i.getArgument(1)); return true; });
        tasks.track(replacement); var pending = observe(); owner(); fails(pending);
        verifyNoInteractions(gateway); verify(replacement, never()).isOnline(); noHistory();
    }
    @Test void reconnectBetweenEnqueueAndOwnerCallbackRetiresOldObservation() {
        var pending = observe(); tasks.track(replacement); tasks.untrack(actor); owner(); fails(pending);
        verifyNoInteractions(gateway); verify(replacement, never()).getScheduler(); noHistory();
    }
    @Test void providerDisabledBeforeObservationDoesNotScheduleAnything() {
        active.set(false); fails(observe()); assertTrue(entities.isEmpty()); verifyNoInteractions(gateway); noHistory();
    }
    @Test void providerDisabledWhileWaitingForOwnerDoesNotReadPlayerContext() {
        var pending = observe(); active.set(false); owner(); fails(pending); verifyNoInteractions(gateway); noHistory();
    }
    @Test void providerDisabledWhileWaitingForWorkerCannotWriteHistory() {
        var pending = observe(); owner(); active.set(false); state(); fails(pending); noHistory();
    }
    @Test void shutdownWhileWaitingForWorkerCannotWriteHistory() {
        var pending = observe(); owner(); running.set(false); state(); fails(pending); noHistory();
    }
    @Test void disabledUsePermissionBlocksHistoryEvenWithAdminPermission() {
        context = new PlayerContext(id, "nation", Map.of(), Set.of("starchronicle.admin"));
        var pending = observe(); owner(); fails(pending); noHistory();
    }
    @Test void offlineActorCannotCreateHistory() {
        when(actor.isOnline()).thenReturn(false); var pending = observe(); owner(); fails(pending);
        verifyNoInteractions(gateway); noHistory();
    }
    @Test void unboundNpcStillRequiresOwnerAuthorizationAndCreatesNoHistory() {
        var pending = plugin.observeNpcInteraction(new NpcHooks.Interaction(actor, "citizens:unbound", observedAt, active::get));
        assertFalse(pending.toCompletableFuture().isDone()); owner(); assertNull(pending.toCompletableFuture().join()); noHistory();
    }
    @Test void ownedMemoryNpcUsesSameAdmissionAndPermissionChecks() {
        var pending = plugin.observeNpcInteraction(new NpcHooks.Interaction(actor, "veteran", observedAt, active::get));
        owner(); state(); assertEquals("veteran", pending.toCompletableFuture().join());
        verify(engine).accept(argThat(e -> "veteran".equals(e.facts().get("npc.binding"))), eq(List.of(context)));
    }
    @Test void reconnectAfterSnapshotPreservesEvidenceAndDoesNotRefreshReplacement() {
        PlayerContext frozen = context; var pending = observe(); owner(); tasks.track(replacement);
        context = new PlayerContext(id, "replacement-nation", Map.of(), permissions);
        state(); assertEquals("veteran", pending.toCompletableFuture().join());
        verify(engine).accept(any(), eq(List.of(frozen))); verify(replacement, never()).getScheduler();
        verify(gateway, never()).refreshContext(eq(replacement), any());
    }
    @Test void otherAudienceContextDoesNotReplaceTheObservedParticipant() {
        Player other = mock(Player.class); UUID otherId = UUID.randomUUID(); EntityScheduler otherScheduler = mock(EntityScheduler.class);
        when(other.getUniqueId()).thenReturn(otherId); when(other.isOnline()).thenReturn(true); when(other.getScheduler()).thenReturn(otherScheduler);
        when(otherScheduler.execute(eq(plugin), any(), any(), eq(1L))).thenAnswer(i -> { entities.add(i.getArgument(1)); return true; });
        PlayerContext spectator = new PlayerContext(otherId, "observed-nation", Map.of(), permissions);
        when(gateway.context(otherId)).thenReturn(spectator); tasks.track(other);
        var pending = observe(); owner(); assertTrue(states.isEmpty()); owner(); state();
        assertEquals("veteran", pending.toCompletableFuture().join());
        verify(engine).accept(any(), eq(List.of(spectator, context)));
    }
    @Test void contextLookupFailureDoesNotPublishAnEvent() {
        when(gateway.captureContext(actor,permissions)).thenThrow(new IllegalStateException("authority unavailable"));
        var pending = observe(); owner(); fails(pending); noHistory();
    }
    @Test void fullWorkerQueueDoesNotRunHistoryOnEntityThread() {
        doThrow(new RejectedExecutionException("full")).when(worker).execute(any());
        var pending = observe(); owner(); fails(pending); noHistory();
    }
    @Test void sharedUuidCacheCannotSubstituteAnotherLoginForTheDirectCapture() {
        PlayerContext original=context;
        when(gateway.context(id)).thenReturn(new PlayerContext(id,"replacement",Map.of(),permissions));
        var pending=observe();owner();state();assertEquals("veteran",pending.toCompletableFuture().join());
        verify(engine).accept(argThat(event->event.nations().equals(Set.of(original.nationId()))),eq(List.of(original)));
        verify(gateway,never()).context(id);
    }
    @Test void observedViewCannotDisplayToAnAlreadyReconnectedPlayer() {
        EntityScheduler next=mock(EntityScheduler.class);when(replacement.getScheduler()).thenReturn(next);
        when(next.execute(eq(plugin),any(),any(),eq(1L))).thenAnswer(i->{entities.add(i.getArgument(1));return true;});
        tasks.track(replacement);plugin.present(actor,active::get,p->fail("replacement displayed old interaction"));owner();
        verifyNoInteractions(gateway);verify(replacement,never()).isOnline();
    }
    @Test void observedViewRejectsProviderDisableDuringFinalDisplayQueue() {
        plugin.present(actor,active::get,p->fail("disabled provider displayed"));active.set(false);owner();verifyNoInteractions(gateway);
    }
    @Test void observedViewRechecksPermissionOnTheCapturedEntity() {
        plugin.present(actor,active::get,p->fail("permission denied"));owner();verifyNoInteractions(gateway);
    }
    @Test void observedViewCanDisplayWhenPlayerAndProviderAreStillCurrent() {
        when(actor.hasPermission("starchronicle.use")).thenReturn(true);
        List<Player> shown=new ArrayList<>();plugin.present(actor,active::get,shown::add);owner();
        assertEquals(List.of(actor),shown);verify(gateway).refreshContext(actor,permissions);
    }
    @Test void observedViewRechecksProviderAfterContextContributorsReturn() {
        when(actor.hasPermission("starchronicle.use")).thenReturn(true);
        doAnswer(i->{active.set(false);return null;}).when(gateway).refreshContext(actor,permissions);
        plugin.present(actor,active::get,p->fail("provider was disabled by a context callback"));owner();
    }
}
