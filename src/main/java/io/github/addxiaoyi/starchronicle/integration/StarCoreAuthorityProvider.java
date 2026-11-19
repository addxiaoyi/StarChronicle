package io.github.addxiaoyi.starchronicle.integration;

import io.github.addxiaoyi.starchronicle.api.AuthorityProvider;
import io.github.addxiaoyi.starchronicle.api.CoreGateway.DeliveryResult;
import io.github.addxiaoyi.starchronicle.api.Delivery;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import static io.github.addxiaoyi.starchronicle.integration.StarCoreBridge.call;

/** Audited, deliberately narrow national authority adapter. Only an alliance invitation is
 * supported; the recipient must still accept it through StarCore. Call on the actor's owning
 * entity thread, as StarCoreBridge does. No commands or direct model writes are used.
 *
 * <p>StarCore has no outcome-ID-aware cross-store transaction. Chronicle's durable outbox MUST
 * claim an attempt before calling this adapter and MUST NOT retry UNKNOWN/abandoned attempts.
 * In-process duplicate calls are coalesced, but this class cannot promise exactly-once mutation
 * across a process crash. APPLIED means the native synchronous service returned success;
 * UNKNOWN means it was invoked without a conclusive result and requires reconciliation.</p> */
public final class StarCoreAuthorityProvider implements AuthorityProvider {
    public static final String TARGET="diplomacy.invite";
    public static final String PERMISSION="starcore.nation.ally.add";
    private final Function<UUID,Player> players;
    private final Supplier<Authority> authorities;
    private final ConcurrentMap<String,Attempt> attempts=new ConcurrentHashMap<>();
    private final int receiptLimit;

    public StarCoreAuthorityProvider(StarCoreBridge bridge) {
        this(Bukkit::getPlayer,()->NativeAuthority.find(Objects.requireNonNull(bridge)));
    }
    StarCoreAuthorityProvider(Function<UUID,Player> players,Supplier<Authority> authorities) {
        this(players,authorities,100_000);
    }
    StarCoreAuthorityProvider(Function<UUID,Player> players,Supplier<Authority> authorities,int receiptLimit) {
        this.players=Objects.requireNonNull(players);this.authorities=Objects.requireNonNull(authorities);
        if(receiptLimit<1)throw new IllegalArgumentException("Receipt capacity must be positive");this.receiptLimit=receiptLimit;
    }

    /** Capability check only; membership, permissions and native service preconditions are
     * checked again for each attempted outcome. Custom authority providers take precedence. */
    public boolean available() {
        try {return authorities.get()!=null;}catch(RuntimeException ex){return false;}
    }

    @Override public CompletionStage<DeliveryResult> apply(Delivery delivery) {
        if(!valid(delivery))return CompletableFuture.completedFuture(DeliveryResult.REJECTED);
        Attempt attempt=new Attempt(delivery,new CompletableFuture<>());
        synchronized(attempts) {
            Attempt previous=attempts.get(delivery.outcomeId());
            if(previous!=null)return previous.delivery().equals(delivery)?previous.result():CompletableFuture.completedFuture(DeliveryResult.REJECTED);
            // Never evict uncertain/in-flight receipts: eviction would permit a repeated native
            // mutation. At capacity reject new outcomes until a controlled plugin restart.
            if(attempts.size()>=receiptLimit)return CompletableFuture.completedFuture(DeliveryResult.REJECTED);
            attempts.put(delivery.outcomeId(),attempt);
        }
        DeliveryResult result=DeliveryResult.REJECTED;
        try {result=perform(delivery);}catch(ReflectiveOperationException | RuntimeException ex){/* No mutation was invoked. */}
        attempt.result().complete(result);return attempt.result();
    }

    private DeliveryResult perform(Delivery delivery)throws ReflectiveOperationException {
        Player player=players.apply(delivery.playerId());
        if(player==null||!player.isOnline()||!delivery.playerId().equals(player.getUniqueId())
                ||!player.hasPermission("starchronicle.use")||!player.hasPermission(PERMISSION))return DeliveryResult.REJECTED;
        Authority authority=authorities.get();if(authority==null)return DeliveryResult.REJECTED;
        // NationModule's create/join/leave/disband mutations use this same monitor. Do not trust
        // the plot instance's cached nationality, founder field or player role for authorization.
        synchronized(authority.nationLock()) {
            if(!delivery.nationId().equals(authority.nationOf(delivery.playerId()))
                    ||!authority.nationExists(delivery.nationId())||!authority.nationExists(delivery.value())
                    ||!authority.isFounder(delivery.playerId(),delivery.nationId())
                    ||!authority.nationalPermission(player))return DeliveryResult.REJECTED;
            // Keep this marker immediately at the only mutation boundary. Reflection, a lost
            // result, a persistence exception or malformed result afterwards are all UNKNOWN.
            try {return authority.invite(delivery.nationId(),delivery.value())?DeliveryResult.APPLIED:DeliveryResult.REJECTED;}
            catch(ReflectiveOperationException | RuntimeException ex){return DeliveryResult.UNKNOWN;}
        }
    }

    private static boolean valid(Delivery delivery) {
        return delivery!=null&&delivery.playerId()!=null&&stableId(delivery.outcomeId())&&stableId(delivery.instanceId())
                &&"authority".equals(delivery.type())&&TARGET.equals(delivery.target())&&PERMISSION.equals(delivery.permission())
                &&canonicalUuid(delivery.nationId())&&canonicalUuid(delivery.value())&&!delivery.nationId().equals(delivery.value());
    }
    private static boolean stableId(String value){return value!=null&&value.matches("[A-Za-z0-9_.:-]{1,256}");}
    private static boolean canonicalUuid(String value){try{return value!=null&&UUID.fromString(value).toString().equals(value);}catch(IllegalArgumentException ex){return false;}}
    private record Attempt(Delivery delivery,CompletableFuture<DeliveryResult> result){}

    /** Independent test seam. Implementations must read committed native authority; this is not
     * a Bukkit service extension point and cannot be populated from story facts. */
    interface Authority {
        Object nationLock();
        String nationOf(UUID actor)throws ReflectiveOperationException;
        boolean nationExists(String nation)throws ReflectiveOperationException;
        boolean isFounder(UUID actor,String nation)throws ReflectiveOperationException;
        boolean nationalPermission(Player actor)throws ReflectiveOperationException;
        boolean invite(String own,String target)throws ReflectiveOperationException;
    }

    private static final class NativeAuthority implements Authority {
        private static final String BASE="dev.starcore.starcore.";
        private final Object nations,diplomacy;
        private final ClassLoader loader;
        private NativeAuthority(Object nations,Object diplomacy){this.nations=nations;this.diplomacy=diplomacy;loader=nations.getClass().getClassLoader();}
        static Authority find(StarCoreBridge bridge) {
            Object nations=bridge.service("module.nation.NationService"),diplomacy=bridge.service("module.diplomacy.DiplomacyService");
            if(nations==null||diplomacy==null||!nations.getClass().getName().equals(BASE+"module.nation.NationModule")
                    ||!diplomacy.getClass().getName().equals(BASE+"module.diplomacy.DiplomacyModule"))return null;
            return new NativeAuthority(nations,diplomacy);
        }
        @Override public Object nationLock(){return nations;}
        private Object nationId(String id)throws ReflectiveOperationException {
            return Class.forName(BASE+"module.nation.model.NationId",true,loader).getMethod("fromString",String.class).invoke(null,id);
        }
        @Override public String nationOf(UUID actor)throws ReflectiveOperationException {
            Object nation=unwrap(call(nations,"nationOf",actor));return nation==null?"":Objects.toString(call(nation,"id"),"");
        }
        @Override public boolean nationExists(String nation)throws ReflectiveOperationException{return unwrap(call(nations,"nationById",nationId(nation)))!=null;}
        @Override public boolean isFounder(UUID actor,String nation)throws ReflectiveOperationException{return Boolean.TRUE.equals(call(nations,"isFounder",actor,nationId(nation)));}
        @Override public boolean nationalPermission(Player actor)throws ReflectiveOperationException {
            Class<?> permissionType=Class.forName(BASE+"nation.permission.NationPermission",true,loader);
            Class<?> levelType=Class.forName(BASE+"nation.permission.PermissionLevel",true,loader);
            Object permission=permissionType.getMethod("valueOf",String.class).invoke(null,"ALLY_ADD");
            Object founder=levelType.getMethod("valueOf",String.class).invoke(null,"FOUNDER");
            Object checker=Class.forName(BASE+"nation.permission.NationPermissionChecker",true,loader).getConstructor().newInstance();
            // First release deliberately allows only verified founders. Never infer leader level
            // from localized/custom rank names, and do not use Nation.hasPermission(member,...).
            return Boolean.TRUE.equals(call(checker,"hasPermission",actor,permission,founder,null,true));
        }
        @Override public boolean invite(String own,String target)throws ReflectiveOperationException {
            Object result=call(diplomacy,"sendAllianceInvite",nationId(own),nationId(target));
            Object success=call(result,"success");if(!(success instanceof Boolean value))throw new IllegalStateException("Missing native invitation result");return value;
        }
        private static Object unwrap(Object value){return value instanceof Optional<?> optional?optional.orElse(null):value;}
    }
}
