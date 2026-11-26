package io.github.addxiaoyi.starchronicle.paper;

import java.util.*;
import java.util.function.Supplier;
import java.util.logging.Logger;
import org.bukkit.Server;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.mockito.MockedStatic;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises registered Bukkit callbacks, including callbacks retained across unregister/reload. */
class NpcHooksTest {
    private final JavaPlugin owner = mock(JavaPlugin.class);
    private final PluginManager manager = mock(PluginManager.class);
    private final Player player = mock(Player.class);
    private final UUID playerId = UUID.randomUUID();
    private final Map<String, Plugin> providers = new HashMap<>();
    private final List<RegisteredListener> registrations = new ArrayList<>();
    private final List<String> interactions = new ArrayList<>();
    private NpcHooks hooks;
    private MockedStatic<Bukkit> bukkit;

    @BeforeEach void setup() {
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
        Server server = mock(Server.class);
        when(owner.getServer()).thenReturn(server);
        when(owner.isEnabled()).thenReturn(true);
        when(owner.getLogger()).thenReturn(Logger.getLogger("NpcHooksTest"));
        when(server.getPluginManager()).thenReturn(manager);
        when(manager.getPlugin(anyString())).thenAnswer(i -> providers.get(i.getArgument(0)));
        when(player.getUniqueId()).thenReturn(playerId);
        doAnswer(i -> {
            Class<? extends Event> type = i.getArgument(0);
            RegisteredListener registered = new RegisteredListener(i.getArgument(1), i.getArgument(3),
                    i.getArgument(2), i.getArgument(4), i.getArgument(5));
            ((HandlerList) type.getMethod("getHandlerList").invoke(null)).register(registered);
            registrations.add(registered);
            return null;
        }).when(manager).registerEvent(any(), any(), any(), any(), eq(owner), anyBoolean());
        hooks = new NpcHooks(owner, (id, binding) -> interactions.add(id + "/" + binding));
    }
    @AfterEach void cleanup() { try { hooks.close(); } finally { bukkit.close(); } }
    private Plugin provider(String name) {
        Plugin provider = mock(Plugin.class);
        when(provider.getName()).thenReturn(name);
        when(provider.isEnabled()).thenReturn(true);
        providers.put(name, provider);
        return provider;
    }
    public static final class Handle {
        private final Supplier<Object> id;
        Handle(Supplier<Object> id) { this.id = id; }
        public Handle getData() { return this; }
        public Object getId() { return id.get(); }
        public Object getUniqueId() { return id.get(); }
    }
    private Event event(String name, Player actor, Object id) { return event(name, actor, () -> id); }
    private Event event(String name, Player actor, Supplier<Object> id) {
        Handle handle = new Handle(id);
        return switch (name) {
            case "FancyNpcs" -> new de.oliver.fancynpcs.api.events.NpcInteractEvent(actor, handle);
            case "Citizens" -> new net.citizensnpcs.api.event.NPCRightClickEvent(actor, handle);
            case "Adyeshach" -> new ink.ptms.adyeshach.core.event.AdyeshachEntityInteractEvent(actor, handle);
            default -> throw new IllegalArgumentException(name);
        };
    }
    private RegisteredListener start(String name) { provider(name); hooks.start(); return registrations.getLast(); }
    private void call(RegisteredListener listener, String name) throws Exception { listener.callEvent(event(name, player, "stable-id")); }

    @ParameterizedTest @ValueSource(strings = {"FancyNpcs", "Citizens", "Adyeshach"})
    void onlyUncancelledInteractionUsesStableProviderIdentity(String name) throws Exception {
        RegisteredListener listener = start(name);
        Event cancelled = event(name, player, "blocked"); ((Cancellable) cancelled).setCancelled(true);
        listener.callEvent(cancelled); assertTrue(interactions.isEmpty());
        UUID stable = UUID.randomUUID(); listener.callEvent(event(name, player, stable));
        assertEquals(List.of(playerId + "/" + name.toLowerCase(Locale.ROOT) + ":" + stable), interactions);
        assertEquals(EventPriority.MONITOR, listener.getPriority()); assertTrue(listener.isIgnoringCancelled());
    }
    @ParameterizedTest @ValueSource(strings = {"FancyNpcs", "Citizens", "Adyeshach"})
    void capturedListenerCannotSubmitAfterProviderDisabled(String name) throws Exception {
        RegisteredListener listener = start(name); hooks.disabled(new PluginDisableEvent(providers.get(name)));
        assertEquals(0, event(name, player, "id").getHandlers().getRegisteredListeners().length);
        call(listener, name); assertTrue(interactions.isEmpty());
    }
    @ParameterizedTest @ValueSource(strings = {"FancyNpcs", "Citizens", "Adyeshach"})
    void disablingOldInstanceDoesNotUnregisterReplacement(String name) throws Exception {
        RegisteredListener oldListener = start(name); Plugin old = providers.get(name);
        hooks.disabled(new PluginDisableEvent(old)); Plugin replacement = provider(name);
        hooks.enabled(new PluginEnableEvent(replacement)); RegisteredListener current = registrations.getLast();
        hooks.disabled(new PluginDisableEvent(old)); call(oldListener, name); call(current, name);
        assertEquals(1, interactions.size());
        assertEquals(1, event(name, player, "id").getHandlers().getRegisteredListeners().length);
    }
    @ParameterizedTest @ValueSource(strings = {"FancyNpcs", "Citizens", "Adyeshach"})
    void replacementWithoutDisableInvalidatesOldCallback(String name) throws Exception {
        RegisteredListener old = start(name); Plugin replacement = provider(name);
        call(old, name); assertTrue(interactions.isEmpty());
        hooks.enabled(new PluginEnableEvent(replacement)); call(registrations.getLast(), name);
        assertEquals(1, interactions.size()); assertEquals(2, registrations.size());
    }
    @ParameterizedTest @ValueSource(strings = {"FancyNpcs", "Citizens", "Adyeshach"})
    void closeIsTerminalForCallbacksAndLateEnableNotifications(String name) throws Exception {
        RegisteredListener listener = start(name); hooks.close(); hooks.close();
        call(listener, name); hooks.enabled(new PluginEnableEvent(providers.get(name))); hooks.start();
        assertTrue(interactions.isEmpty()); assertEquals(1, registrations.size());
        assertEquals(0, event(name, player, "id").getHandlers().getRegisteredListeners().length);
    }
    @ParameterizedTest @ValueSource(strings = {"FancyNpcs", "Citizens", "Adyeshach"})
    void repeatedStartAndEnableInstallOneListener(String name) {
        start(name); hooks.start(); hooks.enabled(new PluginEnableEvent(providers.get(name)));
        verify(manager, times(1)).registerEvents(hooks, owner); assertEquals(1, registrations.size());
    }
    @ParameterizedTest @ValueSource(strings = {"FancyNpcs", "Citizens", "Adyeshach"})
    void disabledOwnerOrProviderRejectsEvenBeforeUnregister(String name) throws Exception {
        RegisteredListener listener = start(name); when(providers.get(name).isEnabled()).thenReturn(false);
        call(listener, name); when(providers.get(name).isEnabled()).thenReturn(true); when(owner.isEnabled()).thenReturn(false);
        call(listener, name); assertTrue(interactions.isEmpty());
    }
    @ParameterizedTest @ValueSource(strings = {"FancyNpcs", "Citizens", "Adyeshach"})
    void lifecycleChangeWhileReadingIdentityPreventsAdmission(String name) throws Exception {
        RegisteredListener listener = start(name);
        listener.callEvent(event(name, player, () -> { hooks.disabled(new PluginDisableEvent(providers.get(name))); return "late"; }));
        assertTrue(interactions.isEmpty());
    }
    @ParameterizedTest @ValueSource(strings = {"FancyNpcs", "Citizens", "Adyeshach"})
    void invalidIdentityNeverBecomesAHistoryBinding(String name) throws Exception {
        RegisteredListener listener = start(name);
        for (Object invalid : Arrays.asList(null, "", " ", "x".repeat(257), 17, new Object()))
            listener.callEvent(event(name, player, invalid));
        assertTrue(interactions.isEmpty());
        call(listener, name); assertEquals(1, interactions.size());
    }
    @ParameterizedTest @ValueSource(strings = {"FancyNpcs", "Citizens", "Adyeshach"})
    void missingPlayerAndThrowingProviderDoNotBreakOtherInteractions(String name) throws Exception {
        RegisteredListener listener = start(name);
        listener.callEvent(event(name, null, "id"));
        listener.callEvent(event(name, player, () -> { throw new NoClassDefFoundError("provider reloaded"); }));
        assertTrue(interactions.isEmpty()); call(listener, name); assertEquals(1, interactions.size());
    }
    @Test void notificationBeforeStartDoesNotInstallBridge() {
        hooks.enabled(new PluginEnableEvent(provider("Citizens"))); assertTrue(registrations.isEmpty());
    }
    @Test void absentProvidersAreOptional() { hooks.start(); assertTrue(registrations.isEmpty()); }
    @Test void providerWhoseOwnClassLoaderCannotLoadItsApiDoesNotBorrowAnotherPluginsClasses() {
        ClassLoader unavailable = new ClassLoader(Plugin.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("net.citizensnpcs.")) throw new ClassNotFoundException(name);
                return super.loadClass(name, resolve);
            }
        };
        Plugin isolated = (Plugin) java.lang.reflect.Proxy.newProxyInstance(unavailable, new Class<?>[]{Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> "Citizens";
                    case "isEnabled" -> true;
                    case "toString" -> "Isolated Citizens fixture";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
        providers.put("Citizens", isolated); assertDoesNotThrow(hooks::start); assertTrue(registrations.isEmpty());
        Plugin available = provider("Citizens"); hooks.enabled(new PluginEnableEvent(available)); assertEquals(1, registrations.size());
    }
    @ParameterizedTest @ValueSource(strings = {"FancyNpcs", "Citizens", "Adyeshach"})
    void queuedObservationKeepsPlayerAndExpiresAcrossDisableAndReenable(String name) throws Exception {
        hooks.close(); List<NpcHooks.Interaction> observations = new ArrayList<>();
        hooks = new NpcHooks(owner, observed -> observations.add(observed));
        RegisteredListener listener = start(name); call(listener, name);
        assertEquals(1, observations.size()); var observed = observations.getFirst();
        assertSame(player, observed.player()); assertNotNull(observed.observedAt()); assertTrue(observed.current().getAsBoolean());
        hooks.disabled(new PluginDisableEvent(providers.get(name))); assertFalse(observed.current().getAsBoolean());
        hooks.enabled(new PluginEnableEvent(providers.get(name))); assertFalse(observed.current().getAsBoolean());
        call(registrations.getLast(), name); assertEquals(2, observations.size());
        assertTrue(observations.getLast().current().getAsBoolean()); hooks.close();
        assertFalse(observations.getLast().current().getAsBoolean());
    }
    @Test void registrationFailureUnregistersPartialListenerAndLeavesOtherProvidersAvailable() throws Exception {
        provider("FancyNpcs"); provider("Citizens");
        doAnswer(i -> {
            var listener = new RegisteredListener(i.getArgument(1), i.getArgument(3), i.getArgument(2), i.getArgument(4), i.getArgument(5));
            de.oliver.fancynpcs.api.events.NpcInteractEvent.getHandlerList().register(listener);
            throw new IllegalStateException("provider disabled during registration");
        }).when(manager).registerEvent(eq(de.oliver.fancynpcs.api.events.NpcInteractEvent.class), any(), any(), any(), eq(owner), anyBoolean());
        assertDoesNotThrow(hooks::start); assertEquals(1, registrations.size());
        assertEquals(0, de.oliver.fancynpcs.api.events.NpcInteractEvent.getHandlerList().getRegisteredListeners().length);
        call(registrations.getFirst(), "Citizens"); assertEquals(1, interactions.size());
    }
}
