package io.github.addxiaoyi.starchronicle.paper;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Optional interaction-only adapters; owns no third-party NPCs and never spawns/deletes them. */
public final class NpcHooks implements Listener, AutoCloseable {
    /** Player is a captured scheduler identity, never a handle to resolve again by UUID. */
    public record Interaction(Player player, String npcId, Instant observedAt, BooleanSupplier current) {
        public Interaction {
            Objects.requireNonNull(player); Objects.requireNonNull(npcId);
            Objects.requireNonNull(observedAt); Objects.requireNonNull(current);
        }
    }
    private static final class Hook {
        final String name;
        final Plugin provider;
        final Listener listener = new Listener() {};
        volatile boolean active;
        Hook(String name, Plugin provider) { this.name = name; this.provider = provider; }
    }
    private final JavaPlugin plugin;
    private final Consumer<Interaction> interaction;
    private final Map<String, Hook> hooks = new HashMap<>();
    private boolean started;
    private volatile boolean closed;
    public NpcHooks(JavaPlugin plugin, Consumer<Interaction> interaction) {
        this.plugin = Objects.requireNonNull(plugin); this.interaction = Objects.requireNonNull(interaction);
    }
    /** Compatibility callback; production uses the observation overload to retain the player session. */
    public NpcHooks(JavaPlugin plugin, BiConsumer<UUID, String> interaction) {
        this(plugin, observed -> interaction.accept(observed.player().getUniqueId(), observed.npcId()));
    }
    public synchronized void start() {
        if (closed || started) return;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        started = true;
        for (String name : List.of("FancyNpcs", "Citizens", "Adyeshach")) install(name);
    }
    @EventHandler public synchronized void enabled(PluginEnableEvent event) {
        if (!closed && started && plugin.getServer().getPluginManager().getPlugin(event.getPlugin().getName()) == event.getPlugin())
            install(event.getPlugin().getName());
    }
    @EventHandler public synchronized void disabled(PluginDisableEvent event) {
        Hook hook = hooks.get(event.getPlugin().getName());
        if (hook != null && hook.provider == event.getPlugin()) uninstall(hook);
    }
    private void install(String name) {
        String eventName = switch (name) {
            case "FancyNpcs" -> "de.oliver.fancynpcs.api.events.NpcInteractEvent";
            case "Citizens" -> "net.citizensnpcs.api.event.NPCRightClickEvent";
            case "Adyeshach" -> "ink.ptms.adyeshach.core.event.AdyeshachEntityInteractEvent";
            default -> null;
        };
        if (eventName == null) return;
        Plugin provider = plugin.getServer().getPluginManager().getPlugin(name);
        Hook old = hooks.get(name);
        if (old != null) {
            if (old.provider == provider && provider.isEnabled()) return;
            uninstall(old);
        }
        if (provider == null || !provider.isEnabled()) return;
        Hook hook = new Hook(name, provider);
        try {
            Class<? extends Event> type = Class.forName(eventName, true, provider.getClass().getClassLoader()).asSubclass(Event.class);
            Method player = type.getMethod(name.equals("Citizens") ? "getClicker" : "getPlayer");
            Method npc = type.getMethod(name.equals("Citizens") ? "getNPC" : name.equals("Adyeshach") ? "getEntity" : "getNpc");
            plugin.getServer().getPluginManager().registerEvent(type, hook.listener, EventPriority.MONITOR,
                    (ignored, event) -> accept(hook, type, player, npc, event), plugin, true);
            hooks.put(name, hook); hook.active = true;
            plugin.getLogger().info("Enabled optional NPC interaction bridge: " + name);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
            uninstall(hook);
            plugin.getLogger().warning("NPC bridge unavailable for " + name + ": " + ex.getClass().getSimpleName());
        }
    }
    private synchronized boolean current(Hook hook) {
        return !closed && hook.active && hooks.get(hook.name) == hook && plugin.isEnabled()
                && hook.provider.isEnabled() && plugin.getServer().getPluginManager().getPlugin(hook.name) == hook.provider;
    }
    private void accept(Hook hook, Class<? extends Event> type, Method player, Method npc, Event event) {
        if (!type.isInstance(event) || event instanceof Cancellable cancelled && cancelled.isCancelled() || !current(hook)) return;
        Instant observedAt = Instant.now();
        try {
            if (!(player.invoke(event) instanceof Player actor)) return;
            Object handle = npc.invoke(event);
            Object id;
            if (hook.name.equals("FancyNpcs")) {
                Object data = handle.getClass().getMethod("getData").invoke(handle);
                id = data.getClass().getMethod("getId").invoke(data);
            } else id = handle.getClass().getMethod("getUniqueId").invoke(handle);
            String stable = id instanceof UUID uuid ? uuid.toString() : id instanceof String value ? value : null;
            if (stable == null || stable.isBlank() || stable.length() > 256)
                throw new IllegalArgumentException("NPC identity must be a nonblank stable String or UUID of at most 256 characters");
            if (!current(hook) || event instanceof Cancellable cancelled && cancelled.isCancelled()) return;
            // The lease reads only volatile lifecycle flags when later checked by the state worker.
            interaction.accept(new Interaction(actor, hook.name.toLowerCase(Locale.ROOT) + ":" + stable,
                    observedAt, () -> hook.active && !closed));
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
            plugin.getLogger().warning(hook.name + " NPC interaction rejected: " + ex.getClass().getSimpleName());
        }
    }
    private void uninstall(Hook hook) {
        hook.active = false;
        hooks.remove(hook.name, hook);
        HandlerList.unregisterAll(hook.listener);
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        for (Hook hook : List.copyOf(hooks.values())) uninstall(hook);
        HandlerList.unregisterAll(this);
    }
}
