package io.github.addxiaoyi.starchronicle.integration;

import io.github.addxiaoyi.starchronicle.api.NativeScene;
import io.github.addxiaoyi.starchronicle.narrative.NativeSceneJournal.Checkpoint;
import io.github.addxiaoyi.starchronicle.narrative.NativeSceneJournal.Request;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;
import org.bukkit.entity.Player;
import static io.github.addxiaoyi.starchronicle.integration.StarCoreBridge.call;

/** Owning-entity-thread adapter for an already admitted, durable journal attempt. It never
 * opens storage, interprets effects, manufactures a native engine or treats saveState(void)
 * as a commit acknowledgement. The caller must bind the supplied checkpoint to its Request.
 * Actual start/advance/select calls remain inside the real StarCore NarrativeEngine.
 *
 * <p>Re-entry is allowed only for the exact registered fingerprint and an independently
 * audited idempotent owned script. A normal quit can leave StarCore's routing map populated
 * while its real session is absent; starting at the verified checkpoint node rebuilds both.
 * The resumed node's safe set-like effects execute natively before the owned state is merged.</p> */
public final class NativeSceneRecoveryAdapter {
    public enum Status { READY, COMPLETED, NOT_INVOKED, UNKNOWN }
    public record Result(Status status,Checkpoint checkpoint,String reason) {
        public Result {
            Objects.requireNonNull(status);Objects.requireNonNull(reason);
            if((status==Status.READY)!=(checkpoint!=null))throw new IllegalArgumentException("Only READY results contain a native checkpoint");
            if(reason.isBlank()||reason.length()>2048)throw new IllegalArgumentException("Native result reason exceeds bounds");
        }
    }
    private final Supplier<Object> engines;
    private final Function<String,Object> ownedScripts;
    private final Function<String,String> fingerprints;
    private final Function<String,Boolean> safeResume;
    private final NativeSceneAdapter scenes;

    public NativeSceneRecoveryAdapter(Supplier<Object> engines,Function<String,Object> ownedScripts,
                                      Function<String,String> fingerprints,Function<String,Boolean> safeResume,
                                      NativeSceneAdapter scenes) {
        this.engines=Objects.requireNonNull(engines);this.ownedScripts=Objects.requireNonNull(ownedScripts);
        this.fingerprints=Objects.requireNonNull(fingerprints);this.safeResume=Objects.requireNonNull(safeResume);this.scenes=Objects.requireNonNull(scenes);
    }

    /** Detached metadata only. Call on this player's owning entity thread; unrelated native
     * variables, flags, evidence and seen endings are neither captured nor cleared. */
    public Checkpoint capture(UUID playerId,String scriptId)throws ReflectiveOperationException {
        Objects.requireNonNull(playerId);
        if(!scenes.allowsNativeAccess())throw new SecurityException("Native recovery is unavailable on this platform");
        String prefix=prefix(scriptId);Object script=requireScript(scriptId),engine=requireEngine();
        Object session=call(engine,"getSession",playerId);
        if(session==null||!Boolean.TRUE.equals(call(session,"isActive"))||!scriptId.equals(call(session,"scriptId")))throw new IllegalStateException("No matching active owned native session");
        String node=Objects.toString(call(session,"currentNodeId"),"");requireNode(script,node);
        NativeScene view=scenes.view(playerId).orElseThrow(()->new IllegalStateException("Native session has no owned presentation"));
        if(!scriptId.equals(view.scriptId())||!node.equals(view.nodeId()))throw new IllegalStateException("Native session changed during capture");
        Object state=call(engine,"getState",playerId);requireStateOwner(state,playerId);
        Map<String,Object> variables=new TreeMap<>();
        for(var entry:variables(state).entrySet())if(entry.getKey() instanceof String key&&key.startsWith(prefix))variables.put(key,entry.getValue());
        Set<String> flags=ownedKeys(call(state,"getFlags"),prefix),evidence=ownedKeys(call(state,"getEvidence"),prefix);
        if(ownedScripts.apply(scriptId)!=script||engines.get()!=engine)throw new IllegalStateException("Native ownership or engine changed during capture");
        return new Checkpoint(node,variables,flags,evidence,view);
    }

    /** The persistence worker must already have claimed the Request. Before-call failures
     * return NOT_INVOKED; every inconclusive result after native start is UNKNOWN. */
    public Result startOrResume(Player player,Request request,Checkpoint checkpoint) {
        boolean invoked=false;
        try {
            if(!scenes.allowsNativeAccess())return notInvoked("Native recovery is unavailable on this platform");
            Objects.requireNonNull(player);Objects.requireNonNull(request);requirePlayer(player,request.playerId());
            String id=request.scriptId();prefix(id);Object script=requireScript(id),engine=requireEngine();
            requireFingerprint(request);validateCheckpoint(request,checkpoint,script);
            Object session=call(engine,"getSession",request.playerId());
            if(session!=null) {
                if(!Boolean.TRUE.equals(call(session,"isActive")))return notInvoked("A native session exists without a conclusive active node");
                if(!id.equals(call(session,"scriptId")))return notInvoked("Another native script is active");
                if(checkpoint==null)return notInvoked("An active native session cannot be adopted by a new outcome");
                return ready(capture(request.playerId(),id),"Existing matching native session captured without re-entry");
            }
            // Native quit removes the real session but can retain the routing Boolean. An
            // audited owned entry may rebuild that stale route without replacing any session.
            if(checkpoint==null&&Boolean.TRUE.equals(call(engine,"hasActiveSession",request.playerId()))&&!Boolean.TRUE.equals(safeResume.apply(id)))return notInvoked("Stale native routing requires an audited safe owned entry");
            String node;
            if(checkpoint==null) {
                node=request.initialNode().isBlank()?null:request.initialNode();
                if(node==null) {
                    Object start=call(script,"getStartNode");
                    if(start==null)return notInvoked("Owned native script has no declared start node");
                    requireNode(script,Objects.toString(call(start,"getId"),""));
                } else requireNode(script,node);
            } else node=checkpoint.nodeId();
            // No reflection read, supplier or validation sits between this marker and start.
            invoked=true;
            Object started=call(engine,"startScript",player,id,node);
            if(started!=script)return unknown("Native start did not return the verified owned script");
            if(checkpoint!=null)mergeOwned(engine,request.playerId(),id,checkpoint);
            return readBack(engine,request.playerId(),id);
        }catch(ReflectiveOperationException|RuntimeException failure){return failure(invoked,failure);}
    }

    /** Uses the presenter's native condition/choice validation and its exact invocation marker.
     * An empty view proves completion only when session AND routing are both absent. */
    public Result act(Player player,String expectedScript,String expectedNode,int choice) {
        AtomicBoolean invoked=new AtomicBoolean();
        try {
            if(!scenes.allowsNativeAccess())return notInvoked("Native recovery is unavailable on this platform");
            Objects.requireNonNull(player);requirePlayer(player,player.getUniqueId());prefix(expectedScript);requireScript(expectedScript);
            scenes.act(player,expectedScript,expectedNode,choice,()->invoked.set(true));
            return readBack(requireEngine(),player.getUniqueId(),expectedScript);
        }catch(ReflectiveOperationException|RuntimeException failure){return failure(invoked.get(),failure);}
    }

    private Result readBack(Object engine,UUID player,String expectedScript)throws ReflectiveOperationException {
        Optional<NativeScene> view=scenes.view(player);
        if(view.isPresent()) {
            if(!expectedScript.equals(view.get().scriptId()))return unknown("A different native script appeared after invocation");
            return ready(capture(player,expectedScript),"Conclusive native session checkpoint captured");
        }
        Object session=call(engine,"getSession",player),routing=call(engine,"hasActiveSession",player);
        return session==null&&Boolean.FALSE.equals(routing)?new Result(Status.COMPLETED,null,"Native session and active routing are both absent")
                :unknown("Native presentation disappeared without conclusive session completion");
    }
    private void validateCheckpoint(Request request,Checkpoint checkpoint,Object script)throws ReflectiveOperationException {
        if(checkpoint==null)return;
        if(!Boolean.TRUE.equals(safeResume.apply(request.scriptId())))throw new IllegalStateException("Owned script is not audited for safe native re-entry");
        if(!request.scriptId().equals(checkpoint.view().scriptId()))throw new IllegalArgumentException("Native checkpoint belongs to another script");
        requireNode(script,checkpoint.nodeId());String prefix=prefix(request.scriptId());
        for(String key:checkpoint.variables().keySet())requirePrefix(key,prefix);
        for(String key:checkpoint.flags())requirePrefix(key,prefix);
        for(String key:checkpoint.evidence())requirePrefix(key,prefix);
    }
    private void requireFingerprint(Request request){if(!request.scriptFingerprint().equals(fingerprints.apply(request.scriptId())))throw new IllegalStateException("Registered native script fingerprint changed");}
    private Object requireScript(String id)throws ReflectiveOperationException {
        Object script=ownedScripts.apply(id);if(script==null||!id.equals(call(script,"getId")))throw new IllegalStateException("Native script is unavailable or no longer owned");return script;
    }
    private Object requireEngine(){return Objects.requireNonNull(engines.get(),"Native engine unavailable");}
    private static void requirePlayer(Player player,UUID expected){if(!player.isOnline()||!expected.equals(player.getUniqueId())||!player.hasPermission("starchronicle.use"))throw new SecurityException("Player is offline, mismatched or lacks native scene permission");}
    private static void requireNode(Object script,String node)throws ReflectiveOperationException {
        if(node==null||node.isBlank())throw new IllegalArgumentException("Missing native checkpoint node");Object value=call(script,"findNode",node);
        if(value instanceof Optional<?> optional)value=optional.orElse(null);if(value==null)throw new IllegalStateException("Native checkpoint node no longer exists");
    }
    private static void requireStateOwner(Object state,UUID player)throws ReflectiveOperationException {if(state==null||!player.equals(call(state,"getPlayerId")))throw new SecurityException("Native state belongs to another player");}
    private static Map<?,?> variables(Object state)throws ReflectiveOperationException {Object value=call(state,"getVariables");if(!(value instanceof Map<?,?> map))throw new IllegalStateException("Native variable snapshot is unavailable");return map;}
    private static Set<String> ownedKeys(Object value,String prefix) {
        if(!(value instanceof Collection<?> collection))throw new IllegalStateException("Native key snapshot is unavailable");Set<String> result=new TreeSet<>();
        for(Object entry:collection)if(entry instanceof String key&&key.startsWith(prefix))result.add(key);return result;
    }
    private static void mergeOwned(Object engine,UUID player,String script,Checkpoint checkpoint)throws ReflectiveOperationException {
        Object state=call(engine,"getState",player);requireStateOwner(state,player);String prefix=prefix(script);
        for(Object key:new ArrayList<>(variables(state).keySet()))if(key instanceof String text&&text.startsWith(prefix))call(state,"removeVariable",text);
        for(var entry:checkpoint.variables().entrySet())call(state,"setVariable",entry.getKey(),entry.getValue());
        for(String key:ownedKeys(call(state,"getFlags"),prefix))call(state,"unsetFlag",key);
        for(String key:checkpoint.flags())call(state,"setFlag",key);
        for(String key:ownedKeys(call(state,"getEvidence"),prefix))call(state,"removeEvidence",key);
        for(String key:checkpoint.evidence())call(state,"addEvidence",key);
    }
    private static String prefix(String script) {
        if(script==null)throw new IllegalArgumentException("Missing owned native script");
        return switch(script){case "chronicle_last_heir"->"chronicle_heir_";case "chronicle_siege_embers"->"chronicle_siege_";case "chronicle_silk_road"->"chronicle_caravan_";default->throw new IllegalArgumentException("No audited state prefix for this native script");};
    }
    private static void requirePrefix(String key,String prefix){if(!key.startsWith(prefix))throw new IllegalArgumentException("Native checkpoint contains another script's state prefix");}
    private static Result ready(Checkpoint checkpoint,String reason){return new Result(Status.READY,checkpoint,reason);}
    private static Result notInvoked(String reason){return new Result(Status.NOT_INVOKED,null,reason);}
    private static Result unknown(String reason){return new Result(Status.UNKNOWN,null,reason);}
    private static Result failure(boolean invoked,Exception failure){String reason=(invoked?"After":"Before")+" native invocation: "+failure.getClass().getSimpleName()+": "+Objects.toString(failure.getMessage(),"no detail");if(reason.length()>2048)reason=reason.substring(0,2048);return invoked?unknown(reason):notInvoked(reason);}
}
