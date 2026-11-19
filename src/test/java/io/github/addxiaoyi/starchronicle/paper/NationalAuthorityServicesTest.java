package io.github.addxiaoyi.starchronicle.paper;

import io.github.addxiaoyi.starchronicle.api.NationalVoteAuthority;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Server;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;
import org.bukkit.plugin.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Bukkit lifecycle selection is separate from all native/SQL authority callbacks. */
class NationalAuthorityServicesTest {
    private final StarChroniclePlugin plugin=mock(StarChroniclePlugin.class,CALLS_REAL_METHODS);
    private final Server server=mock(Server.class);
    private final ServicesManager services=mock(ServicesManager.class);
    private final NationalVoteAuthority builtin=mock(NationalVoteAuthority.class),extension=mock(NationalVoteAuthority.class);
    private Field delegate;
    @BeforeEach void setup()throws Exception{doReturn(server).when(plugin).getServer();when(server.getServicesManager()).thenReturn(services);delegate=StarChroniclePlugin.class.getDeclaredField("nationalVoteAuthority");delegate.setAccessible(true);delegate.set(plugin,builtin);Field running=StarChroniclePlugin.class.getDeclaredField("running");running.setAccessible(true);running.set(plugin,new AtomicBoolean(true));}
    private RegisteredServiceProvider<NationalVoteAuthority> registration(NationalVoteAuthority provider,Plugin owner,ServicePriority priority){return new RegisteredServiceProvider<>(NationalVoteAuthority.class,provider,priority,owner);}
    private ServiceRegisterEvent registered(RegisteredServiceProvider<?> provider){ServiceRegisterEvent event=mock(ServiceRegisterEvent.class);doReturn(provider).when(event).getProvider();return event;}
    private ServiceUnregisterEvent unregistered(RegisteredServiceProvider<?> provider){ServiceUnregisterEvent event=mock(ServiceUnregisterEvent.class);doReturn(provider).when(event).getProvider();return event;}
    @Test void highestTrustedServiceIsCachedWithoutCallingItsAuthority(){
        var low=registration(builtin,plugin,ServicePriority.Lowest);var high=registration(extension,mock(Plugin.class),ServicePriority.High);when(services.getRegistrations(NationalVoteAuthority.class)).thenReturn(List.of(low,high));
        plugin.nationalAuthorityRegistered(registered(high));assertDoesNotThrow(()->assertSame(extension,delegate.get(plugin)));verifyNoInteractions(builtin,extension);
    }
    @Test void unregisterDeniesBeforeRegistryReadAndExcludesStillListedProvider()throws Exception{
        var low=registration(builtin,plugin,ServicePriority.Lowest);var removed=registration(extension,mock(Plugin.class),ServicePriority.High);delegate.set(plugin,extension);
        when(services.getRegistrations(NationalVoteAuthority.class)).thenAnswer(invocation->{assertNotSame(extension,delegate.get(plugin),"old provider must be removed before touching registry");return List.of(low,removed);});
        plugin.nationalAuthorityUnregistered(unregistered(removed));assertSame(builtin,delegate.get(plugin));verifyNoInteractions(builtin,extension);
    }
    @Test void noRemainingServiceFailsClosedRatherThanRetainingRemovedExtension()throws Exception{
        var removed=registration(extension,mock(Plugin.class),ServicePriority.High);delegate.set(plugin,extension);when(services.getRegistrations(NationalVoteAuthority.class)).thenReturn(List.of(removed));
        plugin.nationalAuthorityUnregistered(unregistered(removed));NationalVoteAuthority selected=(NationalVoteAuthority)delegate.get(plugin);assertNotSame(extension,selected);assertThrows(SecurityException.class,()->selected.withRepresentative(UUID.randomUUID(),"nation",view->null));verifyNoInteractions(extension);
    }
    @Test void unrelatedServicesDoNotChangeNationalAuthority()throws Exception{
        var other=new RegisteredServiceProvider<>(Runnable.class,(Runnable)()->{},ServicePriority.High,mock(Plugin.class));plugin.nationalAuthorityRegistered(registered(other));plugin.nationalAuthorityUnregistered(unregistered(other));assertSame(builtin,delegate.get(plugin));verifyNoInteractions(services,builtin,extension);
    }
}
