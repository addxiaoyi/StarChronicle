package io.github.addxiaoyi.starchronicle.integration;

import java.util.*;
import java.util.function.Function;
import static io.github.addxiaoyi.starchronicle.integration.StarCoreBridge.call;

/** Read-only current era facts. Counts and technology-name ordering are never used as eras. */
public final class TechnologyFacts {
    private TechnologyFacts(){}

    /** Return immutable tech.stage/tech.era from the greatest real unlocked definition era.
     * Missing source, no unlocks, an unknown definition/era or incomplete read omits the facts;
     * absence must not be represented as an invented stone-age stage zero. */
    public static Map<String,String> current(StarCoreBridge bridge,Object nationId) {
        if(bridge==null||nationId==null)return Map.of();
        Object module=bridge.service("module.technology.TechnologyService");
        if(module==null||!module.getClass().getName().equals("dev.starcore.starcore.module.technology.TechnologyModule"))return Map.of();
        return read(module,nationId,id->resolve(module.getClass().getClassLoader(),id));
    }

    static Map<String,String> read(Object module,Object nationId,Function<String,Era> resolver) {
        if(module==null||nationId==null||resolver==null)return Map.of();
        try {
            synchronized(module) {
                Object raw=call(module,"unlockedTechnologies",nationId);if(!(raw instanceof Collection<?> values))return Map.of();
                Era highest=null;
                for(Object value:new ArrayList<>(values)) {
                    if(!(value instanceof String key)||key.isBlank())return Map.of();
                    Object definition=call(module,"getDefinition",key);if(definition instanceof Optional<?> optional)definition=optional.orElse(null);
                    if(definition==null)return Map.of();Object eraId=call(definition,"era");
                    if(!(eraId instanceof String id)||id.isBlank())return Map.of();Era era=resolver.apply(id);
                    if(era==null||era.order()<0||era.id()==null||era.id().isBlank())return Map.of();
                    if(highest==null||era.order()>highest.order())highest=era;
                }
                return highest==null?Map.of():Map.of("tech.stage",Integer.toString(highest.order()),"tech.era",highest.id());
            }
        } catch(ReflectiveOperationException | RuntimeException ex){return Map.of();}
    }
    private static Era resolve(ClassLoader loader,String id) {
        try {
            Class<?> type=Class.forName("dev.starcore.starcore.technology.TechnologyEra",true,loader);
            Object era=type.getMethod("fromId",String.class).invoke(null,id);if(era==null)return null;
            return new Era(Objects.toString(call(era,"getId"),""),((Number)call(era,"getOrder")).intValue());
        } catch(ReflectiveOperationException | RuntimeException ex){return null;}
    }
    record Era(String id,int order){}
}
