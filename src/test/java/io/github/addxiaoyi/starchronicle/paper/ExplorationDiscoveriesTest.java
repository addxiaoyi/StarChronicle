package io.github.addxiaoyi.starchronicle.paper;

import io.github.addxiaoyi.starchronicle.api.PlayerContext;
import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExplorationDiscoveriesTest {
    private final UUID playerId=new UUID(0,2),worldId=new UUID(0,1);
    private final Queue<Consumer<Player>> callbacks=new ArrayDeque<>();
    private final List<WorldEvent> captured=new ArrayList<>();
    private Player player;private World world;private ExplorationDiscoveries listener;
    private Location inside(){return new Location(world,5.75,65,5.25);}
    private Location outside(){return new Location(world,22,65,22);}
    @BeforeEach void setup() {
        player=mock(Player.class);world=mock(World.class);when(world.getUID()).thenReturn(worldId);when(player.getUniqueId()).thenReturn(playerId);when(player.isOnline()).thenReturn(true);when(player.getLocation()).thenReturn(inside());
        var region=new ExplorationRegion("road",worldId,0,60,0,10,80,10,null);
        listener=new ExplorationDiscoveries(List.of(region),(id,action)->{assertEquals(playerId,id);callbacks.add(action);},p->new PlayerContext(playerId,"real-country",Map.of(),Set.of()),event->{captured.add(event);return true;},Clock.fixed(Instant.parse("2026-11-20T00:00:00Z"),ZoneOffset.UTC));
    }
    private void flush(){while(!callbacks.isEmpty())callbacks.remove().accept(player);}
    @Test void moveDefersEveryLocationAndContextReadToEntityOwnerAndDeduplicatesInSession() {
        var moved=new PlayerMoveEvent(player,outside(),inside());listener.moved(moved);
        verify(player,never()).getLocation();assertTrue(captured.isEmpty());flush();
        assertEquals(1,captured.size());var event=captured.getFirst();assertEquals(Set.of("real-country"),event.nations());assertEquals("5",event.facts().get("region.x"));
        listener.moved(moved);flush();assertEquals(1,captured.size());
    }
    @Test void teleportAndJoinUseActualPostEventPositionAndIgnoreCancelledOrRetiredPlayer() {
        var cancelled=new PlayerTeleportEvent(player,outside(),inside());cancelled.setCancelled(true);listener.teleported(cancelled);assertTrue(callbacks.isEmpty());
        var teleport=new PlayerTeleportEvent(player,outside(),inside());listener.teleported(teleport);when(player.getLocation()).thenReturn(outside());flush();assertTrue(captured.isEmpty());
        when(player.getLocation()).thenReturn(inside());listener.teleported(teleport);when(player.isOnline()).thenReturn(false);flush();assertTrue(captured.isEmpty());
        when(player.isOnline()).thenReturn(true);listener.joined(new PlayerJoinEvent(player,net.kyori.adventure.text.Component.empty()));flush();assertEquals(1,captured.size());
    }
    @Test void deniedBufferAdmissionCanRetryOnNextMovementAndPureRotationIsIgnored() {
        final boolean[] allow={false};
        var region=new ExplorationRegion("road",worldId,0,60,0,10,80,10,null);
        listener=new ExplorationDiscoveries(List.of(region),(id,action)->callbacks.add(action),p->new PlayerContext(playerId,"",Map.of(),Set.of()),event->{if(!allow[0])return false;captured.add(event);return true;},Clock.systemUTC());
        listener.moved(new PlayerMoveEvent(player,inside(),new Location(world,5.75,65,5.25,90,30)));assertTrue(callbacks.isEmpty());
        listener.moved(new PlayerMoveEvent(player,outside(),inside()));flush();assertTrue(captured.isEmpty());allow[0]=true;
        listener.moved(new PlayerMoveEvent(player,inside(),new Location(world,6,65,5)));flush();assertEquals(1,captured.size());
    }
    @Test void pendingCallbacksCannotCaptureAfterClose() {
        listener.moved(new PlayerMoveEvent(player,outside(),inside()));listener.close();flush();assertTrue(captured.isEmpty());
    }
    @Test void entityOwnedLocationPollDiscoversAnEventlessFoliaTeleportExactlyOnce() {
        when(player.getLocation()).thenReturn(outside());listener.observe(player);assertTrue(captured.isEmpty());
        // Folia teleportAsync does not emit PlayerTeleportEvent on the tested platform.
        when(player.getLocation()).thenReturn(inside());listener.observe(player);listener.observe(player);
        assertEquals(1,captured.size());assertEquals("5",captured.getFirst().facts().get("region.x"));
        listener.close();listener.observe(player);assertEquals(1,captured.size());
    }
}
