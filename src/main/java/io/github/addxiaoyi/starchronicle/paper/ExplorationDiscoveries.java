package io.github.addxiaoyi.starchronicle.paper;

import io.github.addxiaoyi.starchronicle.api.PlayerContext;
import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;

/** Observes committed player location on the entity scheduler after a move/teleport has returned. */
public final class ExplorationDiscoveries implements Listener,AutoCloseable {
    private final List<ExplorationRegion> regions;
    private final BiConsumer<UUID,Consumer<Player>> owner;
    private final Function<Player,PlayerContext> context;
    private final Predicate<WorldEvent> ingress;
    private final java.util.Map<UUID,Set<String>> observed=new ConcurrentHashMap<>();
    private final Clock clock;
    private volatile boolean closed;
    public ExplorationDiscoveries(List<ExplorationRegion> regions,BiConsumer<UUID,Consumer<Player>> owner,Function<Player,PlayerContext> context,Predicate<WorldEvent> ingress,Clock clock) {
        this.regions=List.copyOf(regions);this.owner=owner;this.context=context;this.ingress=ingress;this.clock=clock;
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void moved(PlayerMoveEvent event) {
        if(event instanceof PlayerTeleportEvent||event.isCancelled()||event.getTo()==null)return;
        Location from=event.getFrom(),to=event.getTo();
        if(from.getWorld().getUID().equals(to.getWorld().getUID())&&from.getBlockX()==to.getBlockX()&&from.getBlockY()==to.getBlockY()&&from.getBlockZ()==to.getBlockZ())return;
        schedule(event.getPlayer().getUniqueId(),to);
    }
    // Teleports have a separate Bukkit HandlerList and must be subscribed explicitly.
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void teleported(PlayerTeleportEvent event) {if(!event.isCancelled()&&event.getTo()!=null)schedule(event.getPlayer().getUniqueId(),event.getTo());}
    @EventHandler(priority=EventPriority.MONITOR) public void joined(PlayerJoinEvent event) {schedule(event.getPlayer().getUniqueId(),event.getPlayer().getLocation());}
    @EventHandler(priority=EventPriority.MONITOR) public void respawned(PlayerRespawnEvent event) {schedule(event.getPlayer().getUniqueId(),event.getRespawnLocation());}
    @EventHandler(priority=EventPriority.MONITOR) public void changedWorld(PlayerChangedWorldEvent event) {schedule(event.getPlayer().getUniqueId(),event.getPlayer().getLocation());}
    @EventHandler public void quit(PlayerQuitEvent event) {observed.remove(event.getPlayer().getUniqueId());}
    private void schedule(UUID player,Location destination) {
        if(closed||regions.isEmpty()||destination.getWorld()==null)return;
        UUID world=destination.getWorld().getUID();int x=destination.getBlockX(),y=destination.getBlockY(),z=destination.getBlockZ();
        Set<String> seen=observed.getOrDefault(player,Set.of());
        if(regions.stream().noneMatch(region->!seen.contains(region.scope())&&region.contains(world,x,y,z)))return;
        owner.accept(player,this::observe);
    }
    /** Also called by the normal entity-owned context poll. Folia teleportAsync may not
     * publish PlayerTeleportEvent, so confirmed current positions remain authoritative. */
    public void observe(Player player) {
        if(closed||!player.isOnline())return;
        Location location=player.getLocation();UUID world=location.getWorld().getUID();int x=location.getBlockX(),y=location.getBlockY(),z=location.getBlockZ();
        Set<String> seen=observed.computeIfAbsent(player.getUniqueId(),ignored->ConcurrentHashMap.newKeySet());
        List<ExplorationRegion> arrived=regions.stream().filter(region->!seen.contains(region.scope())&&region.contains(world,x,y,z)).toList();
        if(arrived.isEmpty())return;
        PlayerContext snapshot=context.apply(player);
        if(!player.getUniqueId().equals(snapshot.playerId()))throw new IllegalStateException("Exploration context belongs to another player");
        for(ExplorationRegion region:arrived)if(ingress.test(ConfiguredRegionFirstVisit.event(snapshot.playerId(),snapshot.nationId(),region,x,y,z,clock.instant())))seen.add(region.scope());
    }
    @Override public void close(){closed=true;observed.clear();HandlerList.unregisterAll(this);}
}
