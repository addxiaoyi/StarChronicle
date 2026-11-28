package io.github.addxiaoyi.starchronicle.integration;

import io.github.addxiaoyi.starchronicle.api.NationalVoteAuthority;
import java.time.*;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.function.Supplier;
import static io.github.addxiaoyi.starchronicle.integration.StarCoreBridge.call;

/** Read-only bridge for the audited NationModule implementation. Its ownership/member/
 * dissolution mutators use the module monitor. founderId/isFounder denote the CURRENT holder,
 * not an immutable historical founder. No Bukkit entity, guessed rank, OP, role, ordinary
 * Nation.hasPermission result or unrelated national permission is an authority credential. */
public final class StarCoreNationalVoteAuthority implements NationalVoteAuthority {
    private final Supplier<NativeAccess> nativeAccess;
    private final Clock clock;
    public StarCoreNationalVoteAuthority(StarCoreBridge bridge){this(()->Native.find(Objects.requireNonNull(bridge)),Clock.systemUTC());}
    StarCoreNationalVoteAuthority(Supplier<NativeAccess> access,Clock clock){this.nativeAccess=Objects.requireNonNull(access);this.clock=Objects.requireNonNull(clock);}
    public boolean available(){try{return nativeAccess.get()!=null;}catch(RuntimeException failure){return false;}}
    static NativeAccess nativeAccess(StarCoreBridge bridge) { return Native.find(bridge); }

    /** Ordinary member revalidation uses the same native mutation monitor, without granting a national seat. */
    public <T> T withMember(UUID actor, String expectedNation, java.util.concurrent.Callable<T> operation) throws Exception {
        Objects.requireNonNull(actor); Objects.requireNonNull(expectedNation); Objects.requireNonNull(operation);
        if (!expectedNation.isEmpty() && !canonicalUuid(expectedNation)) throw new SecurityException("Invalid native nation ID");
        NativeAccess access = nativeAccess.get();
        if (access == null) throw new SecurityException("Audited native membership authority is unavailable");
        synchronized (Objects.requireNonNull(access.monitor())) {
            if (!expectedNation.equals(access.nationOf(actor)) || (!expectedNation.isEmpty() && !access.exists(expectedNation)))
                throw new SecurityException("Current native membership changed; refresh before revalidating");
            return operation.call();
        }
    }

    @Override public <T>T withRepresentative(UUID actor,String expectedNation,Authorized<T> operation)throws Exception {
        Objects.requireNonNull(actor);Objects.requireNonNull(operation);
        if(!canonicalUuid(expectedNation))throw new SecurityException("A current native nation is required");
        NativeAccess access=nativeAccess.get();if(access==null)throw new SecurityException("Audited native national authority is unavailable");
        Object monitor=Objects.requireNonNull(access.monitor(),"Missing native nation monitor");
        synchronized(monitor) {
            if(!expectedNation.equals(access.nationOf(actor))||!access.exists(expectedNation)||!access.currentHolder(actor,expectedNation))
                throw new SecurityException("Only the nation's current authoritative holder may cast its ballot");
            ScopedView view=new ScopedView(access,monitor,actor,expectedNation,clock.instant());
            try{return operation.run(view);}finally{view.active=false;}
        }
    }
    private static boolean canonicalUuid(String value){try{return value!=null&&UUID.fromString(value).toString().equals(value);}catch(IllegalArgumentException failure){return false;}}
    private static final class ScopedView implements View {
        private final NativeAccess access;private final Object monitor;private final Thread owner=Thread.currentThread();
        private final UUID actor;private final String nation;private final Instant checked;private volatile boolean active=true;
        private ScopedView(NativeAccess access,Object monitor,UUID actor,String nation,Instant checked){this.access=access;this.monitor=monitor;this.actor=actor;this.nation=nation;this.checked=checked;}
        private void check(){if(!active||Thread.currentThread()!=owner||!Thread.holdsLock(monitor))throw new IllegalStateException("National authority view is outside its synchronous locked callback");}
        @Override public UUID actorId(){check();return actor;}
        @Override public String nationId(){check();return nation;}
        @Override public Instant checkedAt(){check();return checked;}
        @Override public String issuer(){check();return "StarCore.NationModule.current-holder";}
        @Override public String evidence(){check();return "nationOf="+nation+"; nationById=present; isFounder(currentHolder)=true; actor="+actor+"; checkedAt="+checked;}
        @Override public boolean nationExists(String id)throws Exception {check();if(!canonicalUuid(id))throw new IllegalArgumentException("Invalid native nation ID");return access.exists(id);}
    }

    /** Test seam for committed native reads; production accepts only the exact audited class. */
    interface NativeAccess {
        Object monitor();
        String nationOf(UUID actor)throws Exception;
        boolean exists(String nation)throws Exception;
        boolean currentHolder(UUID actor,String nation)throws Exception;
    }
    private static final class Native implements NativeAccess {
        private static final String BASE="dev.starcore.starcore.";
        private final Object nations;private final ClassLoader loader;
        private Native(Object nations){this.nations=nations;loader=nations.getClass().getClassLoader();}
        static NativeAccess find(StarCoreBridge bridge){
            Object service=bridge.service("module.nation.NationService");
            if(service==null||!service.getClass().getName().equals(BASE+"module.nation.NationModule"))return null;
            try {
                Class<?> type=service.getClass(),id=Class.forName(BASE+"module.nation.model.NationId",false,type.getClassLoader());
                if(type.getMethod("nationOf",UUID.class).getReturnType()!=Optional.class
                        ||type.getMethod("nationById",id).getReturnType()!=Optional.class
                        ||type.getMethod("isFounder",UUID.class,id).getReturnType()!=boolean.class)return null;
                Set<String> expected=Set.of("createNation","createCityState","splitNation","addMember","removeMember","transferOwnership","disbandNation"),observed=new HashSet<>();
                for(var method:type.getDeclaredMethods())if(expected.contains(method.getName())&&!method.isSynthetic()) {
                    if(!Modifier.isSynchronized(method.getModifiers()))return null;
                    observed.add(method.getName());
                }
                // A changed native locking contract is unavailable, never assumed compatible.
                if(!observed.containsAll(expected))return null;
                return new Native(service);
            }catch(ReflectiveOperationException|RuntimeException|LinkageError unavailable){return null;}
        }
        @Override public Object monitor(){return nations;}
        private Object nationId(String id)throws ReflectiveOperationException {
            return Class.forName(BASE+"module.nation.model.NationId",true,loader).getMethod("fromString",String.class).invoke(null,id);
        }
        private static Optional<?> optional(Object value){if(!(value instanceof Optional<?> result))throw new IllegalStateException("Malformed native national authority response");return result;}
        @Override public String nationOf(UUID actor)throws ReflectiveOperationException {
            Optional<?> result=optional(call(nations,"nationOf",actor));if(result.isEmpty())return "";
            String id=Objects.toString(call(result.get(),"id"),"");if(!canonicalUuid(id))throw new IllegalStateException("Malformed native membership nation ID");return id;
        }
        @Override public boolean exists(String nation)throws ReflectiveOperationException {
            Optional<?> result=optional(call(nations,"nationById",nationId(nation)));if(result.isEmpty())return false;
            if(!nation.equals(Objects.toString(call(result.get(),"id"),"")))throw new IllegalStateException("Native nation lookup returned another nation");return true;
        }
        @Override public boolean currentHolder(UUID actor,String nation)throws ReflectiveOperationException {
            Object result=call(nations,"isFounder",actor,nationId(nation));if(!(result instanceof Boolean value))throw new IllegalStateException("Malformed native current-holder response");return value;
        }
    }
}
