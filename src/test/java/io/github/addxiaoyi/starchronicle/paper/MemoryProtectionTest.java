package io.github.addxiaoyi.starchronicle.paper;

import java.util.HashSet;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MemoryProtectionTest {
    private final PluginManager plugins=mock(PluginManager.class);
    private final Plugin worldGuard=mock(Plugin.class);
    private final Player player=mock(Player.class);
    private final World world=mock(World.class);
    private final Location location=new Location(world,-0.2,70.7,-16.1);
    private void enabled(){when(plugins.getPlugin("WorldGuard")).thenReturn(worldGuard);when(worldGuard.isEnabled()).thenReturn(true);}

    @Test void optionalAbsentProviderDoesNotPreventStandalonePlacement(){
        new MemoryProtection(plugins,(p,a,v)->{fail("Absent provider cannot be queried");return false;}).requireCreate(player,location);
    }
    @Test void installedButDisabledProviderFailsClosed(){
        when(plugins.getPlugin("WorldGuard")).thenReturn(worldGuard);
        assertThrows(IllegalArgumentException.class,()->new MemoryProtection(plugins,(p,a,v)->true).requireCreate(player,location));
    }
    @Test void permissionCoversAdjacentAndOverheadColumnsAtNegativeChunkEdge(){
        enabled();
        new MemoryProtection(plugins,(p,a,volume)->{
            assertSame(worldGuard,p);assertSame(player,a);assertEquals(36,new HashSet<>(volume).size());
            assertTrue(volume.contains(new Location(world,-2,73,-18)));
            assertTrue(volume.contains(new Location(world,0,70,-16)));
            assertTrue(volume.stream().allMatch(l->l.getWorld()==world));return true;
        }).requireCreate(player,location);
        verify(world,never()).getChunkAt(anyInt(),anyInt());
    }
    @Test void chronicleAdministratorDoesNotBypassProviderDenial(){
        enabled();when(player.hasPermission("starchronicle.admin")).thenReturn(true);
        assertThrows(IllegalArgumentException.class,()->new MemoryProtection(plugins,(p,a,v)->false).requireCreate(player,location));
    }
    @Test void incompatibleOrFailingProviderCannotAuthorizePlacement(){
        enabled();
        assertThrows(IllegalArgumentException.class,()->new MemoryProtection(plugins,(p,a,v)->{throw new NoSuchMethodException("changed API");}).requireCreate(player,location));
        assertThrows(IllegalArgumentException.class,()->new MemoryProtection(plugins,(p,a,v)->{throw new IllegalStateException("region storage unavailable");}).requireCreate(player,location));
    }
}
