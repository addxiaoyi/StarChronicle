package io.github.addxiaoyi.starchronicle.integration;

import io.github.addxiaoyi.starchronicle.api.quest.*;
import java.lang.reflect.Method;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Reflection adapter for StarCore's protocol-1 durable QuestEvidenceService.
 *
 * <p>The driver must call this provider outside every Chronicle SQL lock. The native
 * service schedules admission on its own Paper owner; lookup is its thread-safe canonical
 * query. This adapter never accesses Bukkit, waits for an owner future, completes a quest,
 * invokes a reward, or adopts a completed-ever/unbound native quest.</p>
 *
 * <p>The provider ID denotes a persisted native storage authority, not an installation path
 * or a process. StarCore must retain storageAuthorityId together with its acceptance and
 * completion store. It must not reuse that UUID for a replacement/empty store. This adapter
 * binds the first valid UUID and declines a different domain for its entire lifetime;
 * the host separately owns registration epochs and stale callback rejection.</p>
 */
public final class StarCoreQuestEvidenceProvider implements QuestEvidenceProvider {
    public static final String ISSUER="starcore.quest.evidence.v1";
    private final Supplier<Object> services;
    private final boolean folia;
    private final Clock clock;
    private volatile String authorityId;

    public StarCoreQuestEvidenceProvider(StarCoreBridge bridge,boolean folia){
        this(serviceSupplier(bridge),folia,Clock.systemUTC());
    }
    private static Supplier<Object> serviceSupplier(StarCoreBridge bridge){
        Objects.requireNonNull(bridge);
        return ()->bridge.available()?bridge.service("quest.evidence.QuestEvidenceService"):null;
    }
    /** Test seam: the object must implement the actual native public protocol, not a
     * Chronicle DTO facade. No native class is loaded when the service is absent. */
    StarCoreQuestEvidenceProvider(Supplier<Object> services,boolean folia,Clock clock){
        this.services=Objects.requireNonNull(services);this.folia=folia;this.clock=Objects.requireNonNull(clock);
    }
    public boolean available(){try{return nativeAccess()!=null;}catch(ReflectiveOperationException|RuntimeException|LinkageError unavailable){return false;}}
    @Override public String providerId(){
        String bound=authorityId;
        if(bound==null){if(!available())throw new IllegalStateException("Native quest storage authority is unavailable");bound=authorityId;}
        if(bound==null)throw new IllegalStateException("Native quest storage authority is unbound");
        return ISSUER+":"+bound;
    }
    @Override public CompletionStage<QuestObservation> lookup(QuestRequest request){
        return canonicalLookup(request,false);
    }
    @Override public CompletionStage<QuestObservation> lookupForRetry(QuestRequest request){
        return canonicalLookup(request,true);
    }
    private CompletionStage<QuestObservation> canonicalLookup(QuestRequest request,boolean forRetry){
        if(!valid(request))return done(QuestObservation.Status.UNKNOWN,null,null,"Invalid native quest binding");
        try{
            Native access=nativeAccess();
            if(access==null)return done(QuestObservation.Status.UNSUPPORTED,null,null,"Native durable quest evidence is unavailable");
            if(forRetry) {
                Method capability;
                try {capability=access.service().getClass().getMethod("durableTerminalStates");}
                catch(NoSuchMethodException older){return done(QuestObservation.Status.UNSUPPORTED,null,null,"Native provider cannot confirm a durable terminal run for retry");}
                if(capability.getReturnType()!=boolean.class||!Boolean.TRUE.equals(capability.invoke(access.service())))
                    return done(QuestObservation.Status.UNSUPPORTED,null,null,"Native provider cannot confirm a durable terminal run for retry");
            }
            Object snapshot=access.lookup().invoke(access.service(),request.bindingId());
            if(!current(access))return done(QuestObservation.Status.UNKNOWN,null,null,"Native quest authority changed during lookup");
            return CompletableFuture.completedFuture(snapshot==null?observation(QuestObservation.Status.NOT_STARTED,null,null,"Canonical native binding is absent"):map(request,snapshot));
        }catch(ReflectiveOperationException|RuntimeException|LinkageError failure){return done(QuestObservation.Status.UNKNOWN,null,null,"Native canonical quest lookup failed");}
    }
    @Override public CompletionStage<QuestObservation> ensureStarted(QuestRequest request){
        if(!valid(request))return done(QuestObservation.Status.UNKNOWN,null,null,"Invalid native quest binding");
        final Native access;
        try{
            access=nativeAccess();
            if(access==null)return done(QuestObservation.Status.UNSUPPORTED,null,null,"Native durable quest admission is unavailable");
            Object value=access.begin().invoke(access.service(),request.playerId(),request.questId(),request.bindingId(),request.expectedNationId(),request.mode()==QuestRequest.Mode.NATION);
            if(!(value instanceof CompletionStage<?> admission))return done(QuestObservation.Status.UNKNOWN,null,null,"Native admission returned no asynchronous acknowledgement");
            return admission.handle((answer,error)->{
                if(error!=null)return observation(QuestObservation.Status.UNKNOWN,null,null,"Native admission acknowledgement failed; canonical lookup is required");
                try{
                    if(answer==null||!current(access))return observation(QuestObservation.Status.UNKNOWN,null,null,"Native admission authority or acknowledgement is unavailable");
                    String status=string(answer,"status",false);
                    if("UNAVAILABLE".equals(status))return observation(QuestObservation.Status.UNSUPPORTED,null,null,"Native Paper admission owner is unavailable");
                    if("CONFLICT".equals(status))return observation(QuestObservation.Status.CONFLICT,null,null,"Native binding belongs to a different request");
                    if(!Set.of("ADMITTED","IDEMPOTENT","REJECTED","UNKNOWN").contains(status))return observation(QuestObservation.Status.UNKNOWN,null,null,"Unrecognized native admission status");
                    // Admission payloads are hints. Read the canonical store after its ack;
                    // do not promote a response-carried snapshot or generic evidence field.
                    Object canonical=access.lookup().invoke(access.service(),request.bindingId());
                    if(!current(access))return observation(QuestObservation.Status.UNKNOWN,null,null,"Native authority changed before canonical admission read-back");
                    if(canonical==null)return observation("REJECTED".equals(status)?QuestObservation.Status.NOT_STARTED:QuestObservation.Status.UNKNOWN,null,null,"REJECTED".equals(status)?"Native admission definitely did not accept a run":"Admission has no canonical receipt; keep the original binding");
                    QuestObservation mapped=map(request,canonical);
                    if("REJECTED".equals(status)&&mapped.status()!=QuestObservation.Status.NOT_STARTED)return observation(QuestObservation.Status.UNKNOWN,mapped.acceptedRun(),null,"Admission rejection contradicts canonical native state");
                    if("UNKNOWN".equals(status)&&mapped.status()!=QuestObservation.Status.COMMITTED)return observation(QuestObservation.Status.UNKNOWN,mapped.acceptedRun(),null,"Native admission remains uncertain; do not admit another run");
                    return mapped;
                }catch(ReflectiveOperationException|RuntimeException|LinkageError failure){return observation(QuestObservation.Status.UNKNOWN,null,null,"Native admission could not be canonically verified");}
            });
        }catch(ReflectiveOperationException|RuntimeException|LinkageError failure){return done(QuestObservation.Status.UNKNOWN,null,null,"Native admission invocation is uncertain; canonical lookup is required");}
    }

    private record Native(Object service,Method lookup,Method begin,String storageAuthority){}
    private Native nativeAccess()throws ReflectiveOperationException{
        if(folia)return null;
        Object service=services.get();if(service==null)return null;
        Class<?> type=service.getClass();Method protocol=type.getMethod("protocolVersion"),available=type.getMethod("available"),storage=type.getMethod("storageAuthorityId");
        Method lookup=type.getMethod("lookup",String.class),begin=type.getMethod("beginTracked",UUID.class,String.class,String.class,String.class,boolean.class);
        if(protocol.getReturnType()!=int.class||available.getReturnType()!=boolean.class||storage.getReturnType()!=String.class||!CompletionStage.class.isAssignableFrom(begin.getReturnType()))return null;
        if(!Integer.valueOf(1).equals(protocol.invoke(service))||!Boolean.TRUE.equals(available.invoke(service)))return null;
        Object identity=storage.invoke(service);if(!(identity instanceof String domain)||!canonicalUuid(domain))return null;
        synchronized(this){if(authorityId==null)authorityId=domain;else if(!authorityId.equals(domain))return null;}
        return new Native(service,lookup,begin,domain);
    }
    private boolean current(Native captured)throws ReflectiveOperationException{
        Native current=nativeAccess();return current!=null&&current.service()==captured.service()&&current.storageAuthority().equals(captured.storageAuthority());
    }
    private QuestObservation map(QuestRequest request,Object snapshot){
        AcceptedRun accepted=null;
        try{
            if(!request.bindingId().equals(string(snapshot,"bindingId",false))||!request.playerId().equals(uuid(snapshot,"playerId"))||!request.questId().equals(string(snapshot,"questId",false)))return observation(QuestObservation.Status.CONFLICT,null,null,"Canonical native quest identity differs from the pinned request");
            if(!ISSUER.equals(string(snapshot,"issuer",false)))throw new IllegalArgumentException("Unexpected native issuer");
            String status=string(snapshot,"status",false);long started=number(snapshot,"startedAt");
            String acceptedEvidence=string(snapshot,"acceptedEvidence",true);
            if("REJECTED".equals(status)){
                boolean unapplied=started==0&&blank(acceptedEvidence)&&blank(string(snapshot,"completionId",true))&&number(snapshot,"completedAt")==0&&blank(string(snapshot,"completionEvidence",true))&&number(snapshot,"committedAt")==0;
                return observation(unapplied?QuestObservation.Status.NOT_STARTED:QuestObservation.Status.UNKNOWN,null,null,unapplied?"Canonical native rejection has no accepted run":"Rejected native state carries contradictory transition evidence");
            }
            if(started==0){
                if(!blank(acceptedEvidence))throw new IllegalArgumentException("Unstarted native state has acceptance evidence");
                return observation("ADMITTING".equals(status)?QuestObservation.Status.ADMITTING:QuestObservation.Status.UNKNOWN,null,null,"Native binding has no durable accepted run yet");
            }
            Instant observed=now(),startedAt=positive(started);String run=string(snapshot,"runId",false),fingerprint=string(snapshot,"definitionFingerprint",false);
            if(!canonicalUuid(run)||!fingerprint.matches("[0-9a-f]{64}")||blank(acceptedEvidence)||startedAt.isBefore(millis(request.gateOpenedAt()))||startedAt.isAfter(observed))throw new IllegalArgumentException("Invalid canonical acceptance");
            NationProof acceptedNation=nation(snapshot,"accepted",acceptedEvidence,millis(request.gateOpenedAt()),startedAt);
            requireSameNation(request,acceptedNation);
            accepted=new AcceptedRun(request.bindingId(),run,request.playerId(),request.questId(),fingerprint,startedAt,acceptedNation,ISSUER,acceptedEvidence);
            if("ACTIVE".equals(status))return new QuestObservation(QuestObservation.Status.ACTIVE,accepted,null,now(),message(snapshot));
            if("ABANDONED".equals(status))return new QuestObservation(QuestObservation.Status.ABANDONED,accepted,null,now(),message(snapshot));
            if("FAILED".equals(status))return new QuestObservation(QuestObservation.Status.FAILED,accepted,null,now(),message(snapshot));
            if(!"COMPLETED".equals(status))return observation(QuestObservation.Status.UNKNOWN,accepted,null,"Native completion is not durably committed");
            String completionId=string(snapshot,"completionId",false),completionEvidence=string(snapshot,"completionEvidence",false);
            Instant completedAt=positive(number(snapshot,"completedAt")),committedAt=positive(number(snapshot,"committedAt"));
            if(!canonicalUuid(completionId)||completedAt.isBefore(startedAt)||committedAt.isBefore(completedAt)||committedAt.isAfter(now()))throw new IllegalArgumentException("Invalid native completion chronology");
            NationProof completedNation=nation(snapshot,"completed",completionEvidence,startedAt,completedAt);requireSameNation(request,completedNation);
            CommittedCompletion completion=new CommittedCompletion(request.bindingId(),run,completionId,request.playerId(),request.questId(),fingerprint,startedAt,completedAt,committedAt,acceptedNation,completedNation,ISSUER,acceptedEvidence,completionEvidence);
            return new QuestObservation(QuestObservation.Status.COMMITTED,accepted,completion,now(),message(snapshot));
        }catch(ReflectiveOperationException|RuntimeException|LinkageError invalid){return observation(QuestObservation.Status.UNKNOWN,accepted,null,"Canonical native evidence is incomplete or inconsistent");}
    }
    private NationProof nation(Object snapshot,String prefix,String evidence,Instant earliest,Instant latest)throws ReflectiveOperationException{
        NationProof.State state=NationProof.State.valueOf(string(snapshot,prefix+"NationState",false));String nation=string(snapshot,prefix+"Nation",true);Instant checked=positive(number(snapshot,prefix+"NationCheckedAt"));
        if(checked.isBefore(earliest)||checked.isAfter(latest))throw new IllegalArgumentException("Native nation proof has invalid transition time");
        if(state==NationProof.State.KNOWN_NATION){if(!canonicalUuid(nation))throw new IllegalArgumentException("Invalid native nation ID");}
        else if(!blank(nation))throw new IllegalArgumentException("Unknown/no-country proof names a nation");
        return new NationProof(state,blank(nation)?null:nation,checked,ISSUER,evidence);
    }
    private static void requireSameNation(QuestRequest request,NationProof proof){
        if(request.mode()==QuestRequest.Mode.NATION&&(proof.state()!=NationProof.State.KNOWN_NATION||!Objects.equals(request.expectedNationId(),proof.nationId())))throw new IllegalArgumentException("National quest changed its native country");
    }
    private static String string(Object target,String name,boolean nullable)throws ReflectiveOperationException{
        Object value=target.getClass().getMethod(name).invoke(target);
        if(value==null&&nullable)return null;
        if(!(value instanceof String text)||(!nullable&&blank(text))||text.length()>16384||text.chars().anyMatch(c->c==0))throw new IllegalArgumentException("Malformed native string field");
        return text;
    }
    private static UUID uuid(Object target,String name)throws ReflectiveOperationException{Object value=target.getClass().getMethod(name).invoke(target);if(!(value instanceof UUID id))throw new IllegalArgumentException("Malformed native player UUID");return id;}
    private static long number(Object target,String name)throws ReflectiveOperationException{Object value=target.getClass().getMethod(name).invoke(target);if(!(value instanceof Long number))throw new IllegalArgumentException("Native timestamp is not epoch milliseconds");return number;}
    private static boolean canonicalUuid(String value){try{return value!=null&&UUID.fromString(value).toString().equals(value);}catch(IllegalArgumentException invalid){return false;}}
    private static boolean blank(String value){return value==null||value.isBlank();}
    private static Instant positive(long millis){if(millis<=0)throw new IllegalArgumentException("Missing native epoch-millisecond timestamp");return Instant.ofEpochMilli(millis);}
    private static Instant millis(Instant instant){return Instant.ofEpochMilli(Objects.requireNonNull(instant).toEpochMilli());}
    private Instant now(){return Instant.ofEpochMilli(clock.millis());}
    private QuestObservation observation(QuestObservation.Status status,AcceptedRun accepted,CommittedCompletion completion,String message){return new QuestObservation(status,accepted,completion,now(),message);}
    private CompletionStage<QuestObservation> done(QuestObservation.Status status,AcceptedRun accepted,CommittedCompletion completion,String message){return CompletableFuture.completedFuture(observation(status,accepted,completion,message));}
    private static String message(Object snapshot)throws ReflectiveOperationException{String message=string(snapshot,"message",true);if(message==null)return "Native canonical quest evidence";return message.length()>2048?message.substring(0,2048):message;}
    private static boolean valid(QuestRequest request){return request!=null&&request.playerId()!=null&&request.mode()!=null&&request.gateOpenedAt()!=null&&request.bindingId()!=null&&request.bindingId().matches("[A-Za-z0-9_.:-]{1,160}")&&!blank(request.questId())&&request.questId().length()<=256&&(request.mode()!=QuestRequest.Mode.NATION||!blank(request.expectedNationId()));}
}
