package io.github.addxiaoyi.starchronicle.integration;

import io.github.addxiaoyi.starchronicle.api.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Contract/failure-boundary tests. Live upstream execution is covered by the server smoke run. */
class StarCoreBridgeTest {
    JavaPlugin plugin; Server server; ServicesManager services; Player player; UUID playerId;
    @BeforeEach void setup() {
        plugin=mock(JavaPlugin.class); server=mock(Server.class); services=mock(ServicesManager.class);
        PluginManager plugins=mock(PluginManager.class); player=mock(Player.class); playerId=UUID.randomUUID(); World world=mock(World.class);
        when(plugin.getServer()).thenReturn(server); when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        when(server.getPluginManager()).thenReturn(plugins); when(server.getServicesManager()).thenReturn(services);
        when(services.getRegistrations(ContextContributor.class)).thenReturn(List.of());
        when(player.getUniqueId()).thenReturn(playerId); when(player.getName()).thenReturn("Witness"); when(player.getWorld()).thenReturn(world); when(world.getName()).thenReturn("world");
        when(player.isOnline()).thenReturn(true); when(player.hasPermission("starchronicle.use")).thenReturn(true);
    }
    StarCoreBridge bridge() { return new StarCoreBridge(plugin,(id,operation,retired)->operation.accept(player),false); }

    @Test void missingCoreFailsClosedAndUnknownFactsAreAbsent() {
        StarCoreBridge bridge=bridge(); assertFalse(bridge.available()); assertFalse(bridge.hasScript("missing")); assertFalse(bridge.hasQuest("missing"));
        bridge.refreshContext(player,List.of("allowed","denied"));
        assertEquals("",bridge.context(playerId).nationId());
        assertFalse(bridge.context(playerId).facts().containsKey("nation.war"));
        assertFalse(bridge.context(playerId).permitted("denied"));
        assertTrue(bridge.context(UUID.randomUUID()).facts().isEmpty());
    }
    @ParameterizedTest @ValueSource(strings={"SOLDIER","GENERAL","MERCHANT","SCHOLAR","EXPLORER","DIPLOMAT","MAYOR","MONARCH"})
    void identitiesRequireExplicitGrantedPermission(String role) {
        when(player.hasPermission("starchronicle.identity."+role.toLowerCase(Locale.ROOT))).thenReturn(true);
        StarCoreBridge bridge=bridge(); bridge.refreshContext(player,List.of());
        assertEquals(role.toLowerCase(Locale.ROOT),bridge.context(playerId).facts().get("player.role"));
    }
    @Test void contributorMaySupplyRoleButCannotInjectAuthorityOrPermissions() {
        @SuppressWarnings("unchecked") RegisteredServiceProvider<ContextContributor> registration=mock(RegisteredServiceProvider.class);
        when(registration.getProvider()).thenReturn((id,ctx)->Map.of("player.role","SCHOLAR","tech.stage","RENAISSANCE","nation.id","forged","nation.treasury","999999","permissions","admin"));
        when(services.getRegistrations(ContextContributor.class)).thenReturn(List.of(registration));
        StarCoreBridge bridge=bridge(); bridge.refreshContext(player,List.of()); PlayerContext context=bridge.context(playerId);
        assertEquals("scholar",context.facts().get("player.role")); assertEquals("RENAISSANCE",context.facts().get("tech.stage"));
        assertFalse(context.facts().containsKey("nation.id")); assertFalse(context.facts().containsKey("nation.treasury")); assertFalse(context.permitted("admin"));
    }
    @Test void commandEffectsAreRejectedAndNeverDispatched() {
        try(MockedStatic<Bukkit> bukkit=mockStatic(Bukkit.class)) {
            Delivery delivery=new Delivery("outcome","instance","command","op","Witness",playerId,"","");
            assertEquals(CoreGateway.DeliveryResult.REJECTED,bridge().deliver(delivery).toCompletableFuture().join());
            bukkit.verifyNoInteractions();
        }
    }
    @Test void unavailableAuthorityProviderRejectsBeforeAnyMutation() {
        try(MockedStatic<Bukkit> bukkit=mockStatic(Bukkit.class)) {
            Delivery delivery=new Delivery("outcome","instance","authority.diplomacy","nation","ALLY",playerId,"","");
            assertEquals(CoreGateway.DeliveryResult.REJECTED,bridge().deliver(delivery).toCompletableFuture().join());
            bukkit.verifyNoInteractions();
        }
    }
    @Test void deliveryRechecksLivePermissionInsteadOfCachedPermission() {
        try(MockedStatic<Bukkit> bukkit=mockStatic(Bukkit.class)) {
            Delivery delivery=new Delivery("outcome","instance","authority.treasury","nation","100",playerId,"","treasury.withdraw");
            assertEquals(CoreGateway.DeliveryResult.REJECTED,bridge().deliver(delivery).toCompletableFuture().join());
            verify(services,never()).load(AuthorityProvider.class);
            bukkit.verifyNoInteractions();
        }
    }
    @Test void schedulingFailureReturnsDeterministicRejection() {
        StarCoreBridge bridge=new StarCoreBridge(plugin,(id,operation,retired)->{throw new RejectedExecutionException();},false);
        Delivery delivery=new Delivery("outcome","instance","quest.accept","quest","",playerId,"","");
        assertEquals(CoreGateway.DeliveryResult.REJECTED,bridge.deliver(delivery).toCompletableFuture().join());
    }
    @Test void deliveryKeepsTheCapturedEntityWhenTheUuidRegistryChangesAfterOwnerCheck() {
        AtomicReference<Consumer<Player>> scheduled=new AtomicReference<>();
        StarCoreBridge bridge=new StarCoreBridge(plugin,(id,operation,retired)->scheduled.set(operation),false);
        Delivery delivery=new Delivery("outcome","instance","authority.treasury","nation","100",playerId,"","treasury.withdraw");
        CompletableFuture<CoreGateway.DeliveryResult> pending=bridge.deliver(delivery).toCompletableFuture();
        assertFalse(pending.isDone());Player replacement=mock(Player.class);
        try(MockedStatic<Bukkit> bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(()->Bukkit.getPlayer(playerId)).thenReturn(replacement);
            // The scheduler lease was checked before reconnect replaced Bukkit's UUID mapping.
            scheduled.get().accept(player);
            assertEquals(CoreGateway.DeliveryResult.REJECTED,pending.join());
            verify(player).hasPermission("treasury.withdraw");verifyNoInteractions(replacement);bukkit.verifyNoInteractions();
        }
    }
    @Test void retiredOwnerCompletesAsDefinitelyRejectedWithoutResolvingAnotherSession() {
        StarCoreBridge bridge=new StarCoreBridge(plugin,(id,operation,retired)->retired.run(),false);
        Delivery delivery=new Delivery("outcome","instance","quest.accept","quest","",playerId,"","");
        assertEquals(CoreGateway.DeliveryResult.REJECTED,bridge.deliver(delivery).toCompletableFuture().join());
        verify(player,never()).isOnline();verify(player,never()).hasPermission(anyString());
    }
    @Test void publicReflectionSelectsMatchingOverloadAndPropagatesFailure() throws Exception {
        assertEquals("uuid",StarCoreBridge.call(new PublicFixture(),"read",playerId));
        assertEquals("string",StarCoreBridge.call(new PublicFixture(),"read","id"));
        assertThrows(NoSuchMethodException.class,()->StarCoreBridge.call(new PublicFixture(),"read",17));
    }
    @Test void foundingEventRemainsIdenticalAfterUpstreamMillisecondPersistenceRoundTrip() {
        Instant created=Instant.parse("2026-11-19T13:15:17.052129600Z");
        Instant reloaded=Instant.ofEpochMilli(created.toEpochMilli());
        assertNotEquals(created,reloaded); // Actual precision loss observed in the real Paper restart.
        WorldEvent first=StarCoreBridge.foundedEvent("nation-1",created);
        WorldEvent replay=StarCoreBridge.foundedEvent("nation-1",reloaded);
        assertEquals(first,replay); // Includes occurredAt AND the evidence string used in fingerprints.
        assertEquals(reloaded,first.occurredAt());
        assertTrue(first.evidence().contains("foundedAt="+reloaded));
        assertFalse(first.evidence().contains("052129600"));
    }
    @ParameterizedTest @ValueSource(strings={"WAR_STARTED","WAR_ENDED"})
    void warLifecycleRemainsIdenticalAfterUpstreamMillisecondPersistenceRoundTrip(String type) {
        Instant created=Instant.parse("2026-11-19T13:15:17.052129600Z");
        Instant reloaded=Instant.ofEpochMilli(created.toEpochMilli()); UUID warId=UUID.randomUUID(); Set<String> nations=Set.of("a","b");
        WorldEvent first=StarCoreBridge.warLifecycleEvent(warId,type,created,nations);
        WorldEvent replay=StarCoreBridge.warLifecycleEvent(warId,type,reloaded,nations);
        assertEquals(first,replay); assertEquals(reloaded,first.occurredAt());
        assertFalse(first.evidence().contains("052129600"));
    }
    @Test void commonSourceTimestampBoundaryUsesExactlyUpstreamPersistedPrecision() {
        for(Instant original:List.of(Instant.parse("2026-11-19T13:15:17.999999999Z"),Instant.parse("2026-11-19T13:15:17Z"),Instant.parse("1969-12-31T23:59:59.999999999Z"))) {
            assertEquals(Instant.ofEpochMilli(original.toEpochMilli()),StarCoreBridge.instant(original));
        }
        assertThrows(IllegalArgumentException.class,()->StarCoreBridge.instant(null));
    }
    public static class PublicFixture { public String read(UUID value){return "uuid";} public String read(String value){return "string";} }
}
