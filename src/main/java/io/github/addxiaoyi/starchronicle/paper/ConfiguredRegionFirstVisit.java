package io.github.addxiaoyi.starchronicle.paper;

import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Creates immutable event evidence from one owner-thread player arrival snapshot. */
public final class ConfiguredRegionFirstVisit {
    private ConfiguredRegionFirstVisit() {}
    public static WorldEvent event(UUID player,String nation,ExplorationRegion region,int x,int y,int z,Instant observedAt) {
        if(player==null||region==null||observedAt==null)throw new IllegalArgumentException("First-visit snapshot is incomplete");
        if(x<region.minX()||x>region.maxX()||y<region.minY()||y>region.maxY()||z<region.minZ()||z>region.maxZ())
            throw new IllegalArgumentException("First-visit coordinates are outside the configured region");
        return new WorldEvent("exploration:first-visit:"+player+":"+region.scope(),"EXPLORATION_DISCOVERY",observedAt,Set.of(player),nation==null||nation.isBlank()?Set.of():Set.of(nation),
            Map.of("time_basis","OBSERVED","discovery.kind","FIRST_VISIT","region.id",region.id(),"region.scope",region.scope(),"region.world",region.worldId().toString(),"region.x",Integer.toString(x),"region.y",Integer.toString(y),"region.z",Integer.toString(z)),
            "StarChronicle.ConfiguredRegionFirstVisit","Configured cuboid first-entry observation; player="+player+"; region="+region.id()+"; scope="+region.scope()+"; world="+region.worldId()+"; block="+x+","+y+","+z);
    }
}
