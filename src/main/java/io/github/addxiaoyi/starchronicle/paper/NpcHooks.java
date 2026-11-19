package io.github.addxiaoyi.starchronicle.paper;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Optional interaction-only adapters; owns no third-party NPCs and never spawns/deletes them. */
public final class NpcHooks implements Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final BiConsumer<UUID,String> interaction;
    private final List<Hook> hooks = new ArrayList<>();
    private record Hook(String provider, Listener listener) {}
    public NpcHooks(JavaPlugin plugin, BiConsumer<UUID,String> interaction) { this.plugin=plugin; this.interaction=interaction; }
    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        for (String name : List.of("FancyNpcs", "Citizens", "Adyeshach")) install(name);
    }
    @EventHandler public void enabled(PluginEnableEvent event) { install(event.getPlugin().getName()); }
    @EventHandler public void disabled(PluginDisableEvent event) { uninstall(event.getPlugin().getName()); }
    @SuppressWarnings("unchecked") private void install(String name) {
        if (hooks.stream().anyMatch(h -> h.provider().equals(name))) return;
        Plugin provider=plugin.getServer().getPluginManager().getPlugin(name);
        if(provider==null || !provider.isEnabled()) return;
        String eventName=switch(name) {
            case "FancyNpcs" -> "de.oliver.fancynpcs.api.events.NpcInteractEvent";
            case "Citizens" -> "net.citizensnpcs.api.event.NPCRightClickEvent";
            case "Adyeshach" -> "ink.ptms.adyeshach.core.event.AdyeshachEntityInteractEvent";
            default -> null;
        };
        if(eventName==null) return;
        try {
            Class<? extends Event> type=(Class<? extends Event>)Class.forName(eventName,true,provider.getClass().getClassLoader()).asSubclass(Event.class);
            Method player=type.getMethod(name.equals("Citizens")?"getClicker":"getPlayer");
            Method npc=type.getMethod(name.equals("Citizens")?"getNPC":name.equals("Adyeshach")?"getEntity":"getNpc");
            Listener listener=new Listener() {};
            plugin.getServer().getPluginManager().registerEvent(type,listener,EventPriority.MONITOR,(ignored,event)-> {
                try {
                    Player p=(Player)player.invoke(event);
                    Object handle=npc.invoke(event); Object id;
                    if(name.equals("FancyNpcs")) { Object data=handle.getClass().getMethod("getData").invoke(handle); id=data.getClass().getMethod("getId").invoke(data); }
                    else id=handle.getClass().getMethod(name.equals("Citizens")?"getUniqueId":"getUniqueId").invoke(handle);
                    interaction.accept(p.getUniqueId(),name.toLowerCase(java.util.Locale.ROOT)+":"+id);
                } catch(ReflectiveOperationException|RuntimeException ex) {
                    plugin.getLogger().warning(name+" NPC interaction rejected: "+ex.getClass().getSimpleName());
                }
            },plugin,true);
            hooks.add(new Hook(name,listener));
            plugin.getLogger().info("Enabled optional NPC interaction bridge: "+name);
        } catch(ReflectiveOperationException|LinkageError ex) {
            plugin.getLogger().warning("NPC bridge unavailable for "+name+": "+ex.getMessage());
        }
    }
    private void uninstall(String provider) { hooks.removeIf(h->{if(h.provider().equals(provider)){HandlerList.unregisterAll(h.listener());return true;}return false;}); }
    @Override public void close() { hooks.forEach(h->HandlerList.unregisterAll(h.listener()));hooks.clear();HandlerList.unregisterAll(this); }
}
