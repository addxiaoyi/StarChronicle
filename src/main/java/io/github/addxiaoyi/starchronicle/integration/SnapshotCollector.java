package io.github.addxiaoyi.starchronicle.integration;

import io.github.addxiaoyi.starchronicle.api.SnapshotProvider;
import io.github.addxiaoyi.starchronicle.history.ObservationSnapshot;
import io.github.addxiaoyi.starchronicle.history.ObservationSnapshot.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Supplier;
import static io.github.addxiaoyi.starchronicle.integration.StarCoreBridge.call;

/** Detached committed-source scans. Native write monitors are held while copying publicly
 * readable state, so unlock/revoke persistence failures cannot leak an intermediate value.
 * No Bukkit player, world, Location or mutable capital-cache getters are accessed. */
public final class SnapshotCollector {
    private static final String NATION_SOURCE="StarCore.NationModule.nations";
    private static final String TECH_SOURCE="StarCore.TechnologyModule.unlockedTechnologies";
    private static final String CAPITAL_SOURCE="StarCore.capital-authority-unavailable";
    private final StarCoreBridge bridge;
    private final Supplier<? extends Collection<SnapshotProvider>> providers;
    public SnapshotCollector(StarCoreBridge bridge) { this(bridge,List::of); }
    public SnapshotCollector(StarCoreBridge bridge,Supplier<? extends Collection<SnapshotProvider>> providers) {
        this.bridge=Objects.requireNonNull(bridge);this.providers=Objects.requireNonNull(providers);
    }

    /** One batch may include independent unavailable scopes: never collapse read failures into
     * an apparently complete empty collection. Journal each scope separately on the IO worker. */
    public List<ObservationSnapshot> collect() {
        List<ObservationSnapshot> result=new ArrayList<>();Map<String,Object> ids=new TreeMap<>();
        Object nations=bridge.service("module.nation.NationService");
        if(nations!=null&&nations.getClass().getName().equals("dev.starcore.starcore.module.nation.NationModule")) {
            try {
                Map<String,Entry> entries=new TreeMap<>();
                synchronized(nations) {
                    for(Object nation:requireCollection(call(nations,"nations"))) {
                        Object id=call(nation,"id");String key=nonblank(id);
                        if(ids.put(key,id)!=null)throw new IllegalStateException("Duplicate native nation ID");
                        entries.put(key,new Entry(key,"PRESENT",Map.of()));
                    }
                }
                result.add(new ObservationSnapshot("starcore.nations",NATION_SOURCE,Kind.NATIONS,true,true,entries,"Native NationModule.nations() list copied under its mutation monitor; absence means no longer present, not a known destruction cause"));
            } catch(ReflectiveOperationException | RuntimeException ex) {
                ids.clear();result.add(unavailable("starcore.nations",NATION_SOURCE,Kind.NATIONS,ex));
            }
        } else result.add(ObservationSnapshot.unavailable("starcore.nations",NATION_SOURCE,Kind.NATIONS,"Missing audited native NationModule; custom authorities require SnapshotProvider"));

        boolean nationScanComplete=result.getFirst().complete();
        Object technology=bridge.service("module.technology.TechnologyService");
        if (bridge.committedTechnologyHistory()) {
            result.add(ObservationSnapshot.unavailable("starcore.technologies",TECH_SOURCE,Kind.TECHNOLOGIES,"Durable native technology log owns this scope; retain any legacy baseline without generating duplicate observations"));
        } else if(nationScanComplete&&technology!=null&&technology.getClass().getName().equals("dev.starcore.starcore.module.technology.TechnologyModule")) {
            try {
                Map<String,Entry> entries=new TreeMap<>();
                synchronized(technology) {
                    for(var nation:ids.entrySet())for(Object keyValue:requireCollection(call(technology,"unlockedTechnologies",nation.getValue()))) {
                        String key=nonblank(keyValue);Map<String,String> facts=new HashMap<>();facts.put("technology",key);
                        Object definition=unwrap(call(technology,"getDefinition",key));
                        if(definition!=null) {
                            Object era=call(definition,"era");if(era!=null&&!era.toString().isBlank()) {
                                facts.put("tech.era",era.toString());Integer stage=eraOrder(technology.getClass().getClassLoader(),era.toString());if(stage!=null)facts.put("tech.stage",Integer.toString(stage));
                            }
                        }
                        entries.put(nation.getKey()+"/"+key,new Entry(nation.getKey(),"UNLOCKED",facts));
                    }
                }
                result.add(new ObservationSnapshot("starcore.technologies",TECH_SOURCE,Kind.TECHNOLOGIES,true,true,entries,"Native TechnologyModule unlockedTechnologies() copied under the same monitor as synchronized unlock/revoke and synchronous persistence; a newly observed unlock need not be a research completion"));
            } catch(ReflectiveOperationException | RuntimeException ex) {result.add(unavailable("starcore.technologies",TECH_SOURCE,Kind.TECHNOLOGIES,ex));}
        } else result.add(ObservationSnapshot.unavailable("starcore.technologies",TECH_SOURCE,Kind.TECHNOLOGIES,"Missing complete native nation scope or audited native TechnologyModule; no partial technology baseline is advanced"));

        // The registered module.city.CityService exposes no designated-capital property. Legacy
        // city.CityManager.getNationCapital chooses the current highest-level city, and Nation's
        // capitalLocation is recomputed cache data. Neither proves a committed capital migration.
        result.add(ObservationSnapshot.unavailable("starcore.capitals",CAPITAL_SOURCE,Kind.CAPITALS,bridge.committedCapitalHistory()
                ? "Durable native capital log owns this scope; retain legacy observations without generating duplicate snapshots"
                : "No supported native capital history; register a trusted CAPITALS SnapshotProvider rather than guessing from location caches or city levels"));
        Collection<SnapshotProvider> extensions=providers.get();
        if(extensions!=null)for(SnapshotProvider provider:extensions) {
            Collection<ObservationSnapshot> snapshots=provider.snapshots();
            if(snapshots!=null)for(ObservationSnapshot snapshot:snapshots) {
                if(snapshot.namespace().startsWith("starcore."))throw new IllegalArgumentException("SnapshotProvider cannot impersonate the built-in StarCore namespace");
                if(result.stream().anyMatch(existing->existing.namespace().equals(snapshot.namespace())))throw new IllegalArgumentException("Duplicate snapshot authority namespace: "+snapshot.namespace());
                result.add(snapshot);
            }
        }
        return List.copyOf(result);
    }

    private static ObservationSnapshot unavailable(String namespace,String source,Kind kind,Exception ex) {
        return ObservationSnapshot.unavailable(namespace,source,kind,"Authority scan failed: "+ex.getClass().getSimpleName()+"; previous baseline must be retained");
    }
    private static Collection<?> requireCollection(Object value) {
        if(!(value instanceof Collection<?> values))throw new IllegalArgumentException("Authority omitted its full collection");return new ArrayList<>(values);
    }
    private static Object unwrap(Object value) {return value instanceof Optional<?> optional?optional.orElse(null):value;}
    private static String nonblank(Object value) {if(value==null||value.toString().isBlank())throw new IllegalArgumentException("Authority omitted a stable entity ID");return value.toString();}
    private static Integer eraOrder(ClassLoader loader,String era) {
        try {Class<?> type=Class.forName("dev.starcore.starcore.technology.TechnologyEra",true,loader);Object value=type.getMethod("fromId",String.class).invoke(null,era);return value==null?null:((Number)call(value,"getOrder")).intValue();}
        catch(ReflectiveOperationException | RuntimeException ex) {return null;}
    }
}
