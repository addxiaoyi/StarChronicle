package io.github.addxiaoyi.starchronicle.paper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;
import org.bukkit.configuration.file.FileConfiguration;

/** One explicitly configured world cuboid. Its scope fingerprint changes when its meaning changes. */
public record ExplorationRegion(String id,UUID worldId,int minX,int minY,int minZ,int maxX,int maxY,int maxZ,String scope) {
    private static final Pattern ID=Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    public ExplorationRegion {
        if(id==null||!ID.matcher(id).matches())throw new IllegalArgumentException("Invalid exploration region id");
        if(worldId==null)throw new IllegalArgumentException("Exploration region requires a world UUID");
        if(minX>maxX||minY>maxY||minZ>maxZ)throw new IllegalArgumentException("Exploration region bounds are reversed: "+id);
        if(minX < -29_999_984 || maxX > 29_999_984 || minZ < -29_999_984 || maxZ > 29_999_984 || minY < -2048 || maxY > 2047)
            throw new IllegalArgumentException("Exploration region exceeds supported world coordinate bounds: "+id);
        String expected=fingerprint(id,worldId,minX,minY,minZ,maxX,maxY,maxZ);
        if(scope==null)scope=expected;
        if(!scope.equals(expected))throw new IllegalArgumentException("Exploration scope fingerprint mismatch: "+id);
    }
    public static java.util.List<ExplorationRegion> load(FileConfiguration config) {
        if(!config.getBoolean("exploration.enabled",false))return java.util.List.of();
        Object configured=config.get("exploration.regions");
        if(configured==null)return java.util.List.of();
        if(!(configured instanceof java.util.List<?> sections))throw new IllegalArgumentException("exploration.regions must be a list");
        if(sections.size()>256)throw new IllegalArgumentException("At most 256 exploration regions may be configured");
        java.util.List<ExplorationRegion> result=new java.util.ArrayList<>();java.util.Set<String> ids=new java.util.HashSet<>();
        for(Object item:sections) {
            if(!(item instanceof java.util.Map<?,?> raw))throw new IllegalArgumentException("Each exploration region must be a mapping");
            if(!java.util.Set.of("id","world","min-x","min-y","min-z","max-x","max-y","max-z").equals(raw.keySet()))throw new IllegalArgumentException("Exploration region keys must be id, world and six block bounds");
            Object idValue=raw.get("id"),worldValue=raw.get("world");
            if(!(idValue instanceof String id)||!(worldValue instanceof String world))throw new IllegalArgumentException("Exploration region needs string id and world UUID");
            UUID worldId;try{worldId=UUID.fromString(world);}catch(IllegalArgumentException invalid){throw new IllegalArgumentException("Invalid world UUID for exploration region "+id,invalid);}
            if(!worldId.toString().equalsIgnoreCase(world))throw new IllegalArgumentException("World UUID must use canonical form for exploration region "+id);
            ExplorationRegion region=new ExplorationRegion(id,worldId,integer(raw,"min-x",id),integer(raw,"min-y",id),integer(raw,"min-z",id),integer(raw,"max-x",id),integer(raw,"max-y",id),integer(raw,"max-z",id),null);
            if(!ids.add(region.id()))throw new IllegalArgumentException("Duplicate exploration region id: "+region.id());
            result.add(region);
        }
        return java.util.List.copyOf(result);
    }
    private static int integer(java.util.Map<?,?> map,String key,String id) {
        Object value=map.get(key);
        if(!(value instanceof Number number))throw new IllegalArgumentException("Exploration region "+id+" requires integer "+key);
        double raw=number.doubleValue();if(!Double.isFinite(raw)||raw!=Math.rint(raw)||raw<Integer.MIN_VALUE||raw>Integer.MAX_VALUE)throw new IllegalArgumentException("Exploration region "+id+" has invalid integer "+key);
        return (int)raw;
    }
    public boolean contains(UUID world,int x,int y,int z) {return worldId.equals(world)&&x>=minX&&x<=maxX&&y>=minY&&y<=maxY&&z>=minZ&&z<=maxZ;}
    private static String fingerprint(String id,UUID world,int minX,int minY,int minZ,int maxX,int maxY,int maxZ) {
        String value=String.join("\n",id,world.toString(),Integer.toString(minX),Integer.toString(minY),Integer.toString(minZ),Integer.toString(maxX),Integer.toString(maxY),Integer.toString(maxZ));
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
