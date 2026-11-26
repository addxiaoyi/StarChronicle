package io.github.addxiaoyi.starchronicle.paper;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

/** Optional WorldGuard authorization, evaluated on the creating player's owning thread. */
final class MemoryProtection {
    @FunctionalInterface interface Access {
        boolean allows(Plugin provider, Player player, List<Location> volume) throws ReflectiveOperationException;
    }
    private final PluginManager plugins;
    private final Access access;

    MemoryProtection(PluginManager plugins) { this(plugins, MemoryProtection::worldGuard); }
    MemoryProtection(PluginManager plugins, Access access) { this.plugins=plugins; this.access=access; }

    void requireCreate(Player player, Location location) {
        Plugin provider=plugins.getPlugin("WorldGuard");
        if(provider==null)return;
        if(!provider.isEnabled())throw new IllegalArgumentException("领地保护服务暂不可用，无法创建纪念点。");
        // The interaction box extends 0.1 block beyond its centre column on each side.
        // Check its whole occupied volume, including the text above it, without loading chunks.
        List<Location> volume=new ArrayList<>();
        for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)for(int y=0;y<=3;y++)
            volume.add(new Location(location.getWorld(),location.getBlockX()+x,location.getBlockY()+y,location.getBlockZ()+z));
        final boolean allowed;
        try { allowed=access.allows(provider,player,List.copyOf(volume)); }
        catch(ReflectiveOperationException|RuntimeException|LinkageError unavailable) {
            throw new IllegalArgumentException("无法核验领地建造权限，纪念点未创建。",unavailable);
        }
        if(!allowed)throw new IllegalArgumentException("WorldGuard 不允许在此纪念点占用的范围内建造。");
    }

    private static boolean worldGuard(Plugin provider, Player player, List<Location> volume) throws ReflectiveOperationException {
        ClassLoader loader=provider.getClass().getClassLoader();
        Class<?> wg=Class.forName("com.sk89q.worldguard.WorldGuard",true,loader);
        Class<?> bukkit=Class.forName("com.sk89q.worldguard.bukkit.WorldGuardPlugin",true,loader);
        Class<?> adapter=Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter",true,loader);
        Class<?> localPlayer=Class.forName("com.sk89q.worldguard.LocalPlayer",true,loader);
        Class<?> nativeWorld=Class.forName("com.sk89q.worldedit.world.World",true,loader);
        Class<?> nativeLocation=Class.forName("com.sk89q.worldedit.util.Location",true,loader);
        Class<?> flag=Class.forName("com.sk89q.worldguard.protection.flags.StateFlag",true,loader);
        Object api=wg.getMethod("getInstance").invoke(null);
        var getPlatform=wg.getMethod("getPlatform");
        Object platform=getPlatform.invoke(api);
        Class<?> platformType=getPlatform.getReturnType();
        Object actor=bukkit.getMethod("wrapPlayer",Player.class).invoke(provider,player);
        Object world=adapter.getMethod("adapt",World.class).invoke(null,volume.getFirst().getWorld());
        var getSessions=platformType.getMethod("getSessionManager");
        Object sessions=getSessions.invoke(platform);
        if(Boolean.TRUE.equals(getSessions.getReturnType().getMethod("hasBypass",localPlayer,nativeWorld).invoke(sessions,actor,world)))return true;
        var getRegions=platformType.getMethod("getRegionContainer");
        Object regions=getRegions.invoke(platform);
        var createQuery=getRegions.getReturnType().getMethod("createQuery");
        Object query=createQuery.invoke(regions);
        Object flags=Array.newInstance(flag,0);
        var testBuild=createQuery.getReturnType().getMethod("testBuild",nativeLocation,localPlayer,flags.getClass());
        var adapt=adapter.getMethod("adapt",Location.class);
        for(Location point:volume)
            if(!Boolean.TRUE.equals(testBuild.invoke(query,adapt.invoke(null,point),actor,flags)))return false;
        return true;
    }
}
