package io.github.addxiaoyi.starchronicle.paper;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Every entity operation is dispatched to its owning scheduler. No global-thread player mutations. */
public final class RegionTasks {
    private final JavaPlugin plugin;
    private final ConcurrentMap<UUID, Player> players = new ConcurrentHashMap<>();
    private final AtomicBoolean stopped = new AtomicBoolean();
    public RegionTasks(JavaPlugin plugin) { this.plugin = plugin; }
    public void track(Player player) { players.put(player.getUniqueId(), player); }
    public void untrack(UUID id) { players.remove(id); }
    public boolean online(UUID id) { return players.containsKey(id); }
    public java.util.Set<UUID> onlineIds() { return java.util.Set.copyOf(players.keySet()); }
    public void global(Runnable action) {
        if (!stopped.get()) Bukkit.getGlobalRegionScheduler().execute(plugin, ()->{if(!stopped.get())action.run();});
    }
    public void player(UUID id, Runnable action) { player(id, action, () -> {}); }
    public void player(UUID id, Runnable action, Runnable retired) {
        if (stopped.get()) { retired.run(); return; }
        Player player = players.get(id);
        if (player == null) { retired.run(); return; }
        java.util.concurrent.atomic.AtomicBoolean invoked=new java.util.concurrent.atomic.AtomicBoolean();
        Runnable once=()->{if(invoked.compareAndSet(false,true))retired.run();};
        if (!player.getScheduler().execute(plugin, ()->{if(stopped.get())once.run();else action.run();}, once, 1L)) once.run();
    }
    /** Only call inside the matching player scheduler. */
    public Player current(UUID id) { return players.get(id); }
    public void close() { stopped.set(true); players.clear(); }
}
