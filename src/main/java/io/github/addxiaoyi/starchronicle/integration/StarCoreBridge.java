package io.github.addxiaoyi.starchronicle.integration;

import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.narrative.NativeSceneJournal;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

/** Public-accessor bridge for audited StarCore f769e627. No commands or direct model writes. */
public final class StarCoreBridge implements CoreGateway, AutoCloseable {
    /** Supplies the exact entity whose scheduler owns this callback; UUID lookup is not a lease. */
    @FunctionalInterface public interface PlayerOwnerScheduler {
        void execute(UUID id,Consumer<Player> operation,Runnable retired);
    }
    private static final String BASE = "dev.starcore.starcore.";
    private static final List<String> ROLES = List.of("SOLDIER", "GENERAL", "MERCHANT", "SCHOLAR", "EXPLORER", "DIPLOMAT", "MAYOR", "MONARCH");
    private final JavaPlugin plugin;
    private final PlayerOwnerScheduler playerScheduler;
    private final boolean folia;
    private final Map<UUID,PlayerContext> contexts = new ConcurrentHashMap<>();
    private final List<Runnable> unsubscribers = new CopyOnWriteArrayList<>();
    private final Map<String,Object> installedScripts = new ConcurrentHashMap<>();
    private final Map<String,String> installedFingerprints=new ConcurrentHashMap<>();
    private final Set<String> recoverySafeScripts=ConcurrentHashMap.newKeySet();
    private final Listener bukkitListener = new Listener() {};
    private final Plugin core;
    private final NativeSceneAdapter nativeScenes;
    private final NativeSceneRecoveryAdapter nativeRecovery;
    private volatile boolean closed;
    private volatile Consumer<WorldEvent> receiver = event -> {};
    private volatile BiFunction<String,Map<String,String>,Map<String,String>> observer;

    public StarCoreBridge(JavaPlugin plugin, PlayerOwnerScheduler playerScheduler, boolean folia) {
        this.plugin=Objects.requireNonNull(plugin); this.playerScheduler=Objects.requireNonNull(playerScheduler); this.folia=folia;
        this.core=plugin.getServer().getPluginManager().getPlugin("StarCore");
        this.nativeScenes=new NativeSceneAdapter(this::engine,id->safe(loader(),"load",id),(id,script)->installedScripts.get(id)==script,folia);
        this.nativeRecovery=new NativeSceneRecoveryAdapter(this::engine,this::ownedScript,this::sceneFingerprint,this::managedScene,nativeScenes);
        if(folia) plugin.getLogger().warning("StarCore f769e627 NarrativeEngine uses Bukkit's main-thread scheduler; narrative.start is disabled on Folia. Chronicle state/history remain available.");
    }

    public boolean available() { return !closed && core!=null && core.isEnabled() && contextObject()!=null; }
    /** Runs a callback with the exact currently-owned Player entity. UUID lookup is not an ownership lease. */
    public void ownedPlayer(UUID id,Consumer<Player> operation,Runnable retired) {
        if(closed){retired.run();return;}
        playerScheduler.execute(id,operation,retired);
    }
    /** Schedules read-only native service access on the global region scheduler. */
    public void global(Runnable operation,Runnable unavailable) {
        Objects.requireNonNull(operation);Objects.requireNonNull(unavailable);
        if(closed){unavailable.run();return;}
        try {
            Bukkit.getGlobalRegionScheduler().execute(plugin,()->{
                if(closed)unavailable.run(); else operation.run();
            });
        } catch(RuntimeException error) { unavailable.run(); }
    }
    ClassLoader coreLoader() { return core==null?null:core.getClass().getClassLoader(); }
    private Object contextObject() { return safe(core,"context"); }
    Object service(String type) {
        if(core==null || !core.isEnabled()) return null;
        try { return unwrap(call(safe(contextObject(),"serviceRegistry"),"find",Class.forName(BASE+type,true,core.getClass().getClassLoader()))); }
        catch(ReflectiveOperationException | RuntimeException ex) { return null; }
    }
    private Object engine() { return service("module.narrative.engine.NarrativeEngine"); }
    private Object loader() {
        Object manager=safe(contextObject(),"moduleManager");
        for(Object module:collection(safe(manager,"modules"))) {
            if(module.getClass().getName().equals(BASE+"module.narrative.NarrativeModule")) return safe(module,"scriptLoader");
        }
        return null;
    }
    @Override public boolean hasScript(String id) { return validId(id) && safe(loader(),"load",id)!=null; }
    @Override public boolean hasQuest(String id) { return validId(id) && safe(service("quest.QuestService"),"getQuest",id)!=null; }

    /** Install a namespaced StarCore scene through its existing public ScriptLoader. */
    public boolean installScript(Path file) {
        if(closed) return false;
        try {
            String fingerprint=CoreContentInstaller.fingerprint(file);
            Object loader=loader(), script=call(loader,"loadFromFile",file);
            String id=string(call(script,"getId"));
            if(!id.startsWith("chronicle_") || !validId(id)) throw new IllegalArgumentException("Chronicle scene IDs must use chronicle_ prefix");
            Object current=safe(loader,"load",id),owned=installedScripts.get(id);
            if(current!=null&&current!=owned)throw new IllegalArgumentException("Script ID already owned by another plugin: "+id);
            boolean recoverable=CoreContentInstaller.recoverySafe(file,id);
            if(!fingerprint.equals(CoreContentInstaller.fingerprint(file)))throw new IllegalStateException("Native scene changed during registration");
            call(loader,"register",script);installedScripts.put(id,script);installedFingerprints.put(id,fingerprint);if(recoverable)recoverySafeScripts.add(id);else recoverySafeScripts.remove(id);return true;
        } catch(ReflectiveOperationException | java.io.IOException | RuntimeException ex) { plugin.getLogger().log(Level.WARNING,"Cannot install StarCore scene "+file,ex); return false; }
    }

    @Override public PlayerContext context(UUID id) {
        return contexts.getOrDefault(id,new PlayerContext(id,"",Map.of(),Set.of()));
    }
    public Optional<NativeScene> scene(UUID player)throws ReflectiveOperationException{return closed?Optional.empty():nativeScenes.view(player);}
    public Optional<NativeScene> sceneAction(Player player,String script,String node,int choice)throws ReflectiveOperationException{if(closed)throw new IllegalStateException("Bridge is stopping");return nativeScenes.act(player,script,node,choice);}
    private Object ownedScript(String id){Object owned=installedScripts.get(id);return owned!=null&&safe(loader(),"load",id)==owned?owned:null;}
    public String sceneFingerprint(String id){return !closed&&ownedScript(id)!=null?installedFingerprints.get(id):null;}
    public boolean managedScene(String id){return !closed&&!folia&&recoverySafeScripts.contains(id)&&ownedScript(id)!=null;}
    public NativeSceneRecoveryAdapter.Result resumeScene(Player player,NativeSceneJournal.Request request,NativeSceneJournal.Checkpoint checkpoint){return nativeRecovery.startOrResume(player,request,checkpoint);}
    public NativeSceneRecoveryAdapter.Result managedSceneAction(Player player,String script,String node,int choice){return nativeRecovery.act(player,script,node,choice);}

    /** Capture current trusted facts on the player's owning entity thread. */
    public void refreshContext(Player player, Collection<String> requestedPermissions) {
        captureContext(player,requestedPermissions);
    }
    /** Return this capture directly; the UUID cache may already belong to another login on return. */
    public PlayerContext captureContext(Player player, Collection<String> requestedPermissions) {
        Map<String,String> facts=new HashMap<>(); Set<String> permissions=new HashSet<>();
        for(String node:requestedPermissions) if(node!=null && !node.isBlank() && player.hasPermission(node)) permissions.add(node);
        facts.put("player.name",player.getName()); facts.put("player.world",player.getWorld().getName());
        Object nations=service("module.nation.NationService"); Object nation=unwrap(safe(nations,"nationOf",player.getUniqueId()));
        String nationId="";
        if(nation!=null) {
            Object id=safe(nation,"id"); nationId=string(id); facts.put("nation.id",nationId); put(facts,"nation.name",safe(nation,"name"));
            if(Boolean.TRUE.equals(safe(nations,"isFounder",player.getUniqueId(),id))) { facts.put("player.role","monarch"); facts.put("player.identity","MONARCH"); }
            for(Object member:collection(safe(nation,"members"))) if(player.getUniqueId().equals(safe(member,"playerId"))) {
                String rank=string(safe(member,"rank")); facts.put("player.rank",rank);
                String normalized=rank.toUpperCase(Locale.ROOT); if(ROLES.contains(normalized) && !facts.containsKey("player.role")) { facts.put("player.role",normalized.toLowerCase(Locale.ROOT)); facts.put("player.identity",normalized); }
            }
            Object treasury=service("module.treasury.TreasuryService"); put(facts,"nation.treasury",safe(treasury,"balance",id));
            facts.putAll(TechnologyFacts.current(this,id));
            Object wars=service("module.war.WarService"); Object active=safe(wars,"activeWarsOf",id);
            if(active instanceof Collection<?> c) { facts.put("nation.war",Boolean.toString(!c.isEmpty())); facts.put("war",Boolean.toString(!c.isEmpty())); }
            Object tech=service("module.technology.TechnologyService"); Object unlocked=safe(tech,"unlockedTechnologies",id);
            if(unlocked instanceof Collection<?> c) { facts.put("nation.technology.count",Integer.toString(c.size())); for(Object key:c) facts.put("nation.technology."+key,"true"); }
            Object resources=safe(service("module.resource.ResourceService"),"stockpile",id);
            if(resources instanceof Map<?,?> map) map.forEach((k,v)->{put(facts,"nation.resource."+k,v);put(facts,"resource."+k,v);});
            Object diplomacy=service("module.diplomacy.DiplomacyRelationService"); if(diplomacy==null) diplomacy=service("module.diplomacy.DiplomacyService");
            for(Object relation:collection(safe(diplomacy,"relationsOf",id))) put(facts,"diplomacy."+safe(relation,"target"),safe(relation,"relation"));
        }
        // Eight explicit permission-backed identities are an administrator-controlled fallback.
        if(!facts.containsKey("player.role")) for(String role:ROLES) if(player.hasPermission("starchronicle.identity."+role.toLowerCase(Locale.ROOT))) {
            facts.put("player.role",role.toLowerCase(Locale.ROOT)); facts.put("player.identity",role); break;
        }
        PlayerContext base=new PlayerContext(player.getUniqueId(),nationId,facts,permissions);
        for(RegisteredServiceProvider<ContextContributor> registration:plugin.getServer().getServicesManager().getRegistrations(ContextContributor.class)) {
            try {
                Map<String,String> extra=registration.getProvider().contribute(player.getUniqueId(),base);
                if(extra!=null) extra.forEach((key,value)-> {
                    if(key!=null && value!=null && key.length()<=128 && value.length()<=4096 && (key.startsWith("player.") || key.startsWith("region.") || key.equals("tech.stage"))) facts.putIfAbsent(key,key.equals("player.role")?value.toLowerCase(Locale.ROOT):value);
                });
            } catch(RuntimeException ex) { plugin.getLogger().log(Level.WARNING,"Context contributor failed",ex); }
        }
        PlayerContext captured=new PlayerContext(player.getUniqueId(),nationId,facts,permissions);
        contexts.put(player.getUniqueId(),captured);
        return captured;
    }

    @Override public CompletionStage<DeliveryResult> deliver(Delivery delivery) {
        if(closed || delivery==null || delivery.playerId()==null) return CompletableFuture.completedFuture(DeliveryResult.REJECTED);
        CompletableFuture<DeliveryResult> result=new CompletableFuture<>();
        try { playerScheduler.execute(delivery.playerId(),player-> {
            if(closed || player==null || !delivery.playerId().equals(player.getUniqueId()) || !player.isOnline() || !player.hasPermission("starchronicle.use") || (delivery.permission()!=null && !delivery.permission().isBlank() && !player.hasPermission(delivery.permission()))) { result.complete(DeliveryResult.REJECTED); return; }
            try {
                Object nation=unwrap(safe(service("module.nation.NationService"),"nationOf",delivery.playerId()));
                if(delivery.nationId()!=null && !delivery.nationId().isBlank() && !delivery.nationId().equals(string(safe(nation,"id")))) { result.complete(DeliveryResult.REJECTED); return; }
                switch(delivery.type()) {
                    case "narrative.start" -> {
                        if(folia || !hasScript(delivery.target())) { result.complete(DeliveryResult.REJECTED); return; }
                        Object engine=engine();
                        if(Boolean.TRUE.equals(safe(engine,"hasActiveSession",delivery.playerId()))) { result.complete(DeliveryResult.REJECTED); return; }
                        Object script=call(engine,"startScript",player,delivery.target(),blankNull(delivery.value()));
                        result.complete(script!=null?DeliveryResult.APPLIED:DeliveryResult.REJECTED);
                    }
                    case "quest.accept" -> {
                        if(!hasQuest(delivery.target())) { result.complete(DeliveryResult.REJECTED); return; }
                        if(folia && !collection(safe(safe(service("quest.QuestService"),"getQuest",delivery.target()),"getStartCommands")).isEmpty()) { result.complete(DeliveryResult.REJECTED); return; }
                        String accepted=string(call(service("quest.QuestService"),"acceptQuest",player,delivery.target()));
                        result.complete(Set.of("SUCCESS","ALREADY_ACTIVE").contains(accepted)?DeliveryResult.APPLIED:DeliveryResult.REJECTED);
                    }
                    default -> {
                        if(!delivery.type().equals("authority") && !delivery.type().startsWith("authority.")) { result.complete(DeliveryResult.REJECTED); return; }
                        AuthorityProvider provider=plugin.getServer().getServicesManager().load(AuthorityProvider.class);
                        if(provider==null) { result.complete(DeliveryResult.REJECTED); return; }
                        CompletionStage<DeliveryResult> applied=provider.apply(delivery);
                        if(applied==null) { result.complete(DeliveryResult.UNKNOWN); return; }
                        applied.whenComplete((value,error)->result.complete(error==null && value!=null?value:DeliveryResult.UNKNOWN));
                    }
                }
            } catch(ReflectiveOperationException | RuntimeException ex) { plugin.getLogger().log(Level.WARNING,"StarCore delivery outcome uncertain: "+delivery.outcomeId(),ex); result.complete(DeliveryResult.UNKNOWN); }
        },()->result.complete(DeliveryResult.REJECTED)); } catch(RuntimeException ex) { result.complete(DeliveryResult.REJECTED); }
        return result;
    }

    /** Subscribe to exact concrete upstream event classes; UUID-backed records remain replayable. */
    public synchronized void start(Consumer<WorldEvent> receiver) {
        if(closed) throw new IllegalStateException("Bridge closed");
        if(!unsubscribers.isEmpty()) throw new IllegalStateException("Bridge already started");
        this.receiver=Objects.requireNonNull(receiver);
        String[] types={"module.war.event.WarStartedEvent","module.war.event.WarEndedEvent","module.season.NationClaimedEvent","module.split.event.SplitRequestApprovedEvent","module.merge.event.NationsMergedEvent","technology.event.ResearchCompleteEvent"};
        Object bus=safe(contextObject(),"eventBus");
        if(core==null) return;
        for(String type:types) try {
            Class<?> eventClass=Class.forName(BASE+type,true,core.getClass().getClassLoader());
            Object unsubscribe=call(bus,"subscribe",eventClass,(Consumer<Object>)this::receive);
            if(unsubscribe instanceof Runnable r) unsubscribers.add(r);
            if(Event.class.isAssignableFrom(eventClass)) {
                @SuppressWarnings("unchecked") Class<? extends Event> bukkitType=(Class<? extends Event>)eventClass;
                plugin.getServer().getPluginManager().registerEvent(bukkitType,bukkitListener,EventPriority.MONITOR,(listener,event)->receive(event),plugin,true);
            }
        } catch(ReflectiveOperationException | RuntimeException ex) { plugin.getLogger().log(Level.FINE,"Upstream capability unavailable: "+type,ex); }
    }

    private void receive(Object event) {
        if(closed || Boolean.TRUE.equals(safe(event,"isCancelled"))) return;
        try {
            String type=event.getClass().getSimpleName();
            if(type.equals("WarStartedEvent") || type.equals("WarEndedEvent")) { emitWar(safe(event,"getWar")); return; }
            if(type.equals("NationClaimedEvent")) {
                String id=string(call(event,"claimId")), nation=string(call(event,"nationId"));
                // Upstream claim record has no occurredAt. Clearly label observation time.
                emit("starcore:claim:"+id,"TERRITORY_CLAIMED",Instant.now(),uuids(call(event,"playerId")),strings(nation),Map.of("title","国家领土扩张","claimedChunks",string(call(event,"claimedChunks")),"world",string(call(event,"worldName")),"time_basis","OBSERVED"),"NationClaimedEvent claimId="+id+"; event has no timestamp; occurredAt is observation time");
            } else if(type.equals("SplitRequestApprovedEvent")) {
                String id=string(call(event,"getRequestId")); Object source=call(event,"getSourceNation"), target=call(event,"getNewNation");
                emit("starcore:split:"+id,"NATION_SPLIT",Instant.now(),uuids(call(event,"getApproverId")),strings(call(source,"id"),call(target,"id")),Map.of("title","国家分裂","transferredChunks",string(call(event,"getTransferredChunks")),"time_basis","OBSERVED"),"SplitRequestApprovedEvent requestId="+id+"; committed approval; occurredAt is observation time");
            } else if(type.equals("NationsMergedEvent")) {
                Object merged=call(event,"getResult"); String id=string(call(merged,"id")); Set<String> nations=new HashSet<>(strings(collection(call(merged,"mergedNationIds")).toArray())); nations.add(string(call(merged,"resultNationId")));
                emit("starcore:merge:"+id,"NATIONS_MERGED",instant(call(merged,"mergedAt")),uuids(call(merged,"initiatorId")),nations,Map.of("title","诸国统一","resultNation",string(call(merged,"resultNationId"))),"NationsMergedEvent MergeResult.id="+id+"; mergedAt from persisted result");
            } else if(type.equals("ResearchCompleteEvent")) {
                String id=string(call(event,"researchId")), nation=string(call(event,"nationId")), tech=string(call(event,"techId"));
                Object nationId=nationId(nation); Object technologies=service("module.technology.TechnologyService");
                if(nationId==null || !Boolean.TRUE.equals(safe(technologies,"hasTechnology",nationId,tech))) return;
                Map<String,String> facts=new HashMap<>(Map.of("title","科技突破","technology",tech)); Object era=call(event,"era"); put(facts,"tech.era",era); put(facts,"tech.stage",safe(era,"getOrder"));
                emit("starcore:research:"+id,"TECHNOLOGY_BREAKTHROUGH",Instant.ofEpochMilli(((Number)call(event,"completeTime")).longValue()),uuids(call(event,"researcherId")),strings(nation),facts,"ResearchCompleteEvent researchId="+id+"; unlock verified through TechnologyService");
            }
        } catch(ReflectiveOperationException | RuntimeException ex) { plugin.getLogger().log(Level.WARNING,"Rejected invalid upstream history evidence",ex); }
    }

    /** Hook reserved for transactional observation adapters. No unsafe diff event is emitted. */
    public void setObserver(BiFunction<String,Map<String,String>,Map<String,String>> observer) { this.observer=observer; }

    /** Replay durable entities with stable IDs; safe to run repeatedly on the persistence worker. */
    public void scanCommittedSources() {
        if(!available()) return;
        try {
            for(Object nation:collection(safe(service("module.nation.NationService"),"nations"))) {
                String id=string(call(nation,"id")); Object founded=call(nation,"foundedAt");
                // Current name and current owner can change. The API does not preserve the original
                // founding player's UUID, so do not misattribute a later monarch as the founder.
                if(founded instanceof Instant time) emitEvent(foundedEvent(id,time));
            }
            Object wars=service("module.war.WarService");
            for(Object snapshot:collection(safe(wars,"allWars"))) emitWar(unwrap(safe(wars,"getWar",call(snapshot,"id"))));
        } catch(ReflectiveOperationException | RuntimeException ex) { plugin.getLogger().log(Level.WARNING,"StarCore committed-source scan incomplete; next scan can retry",ex); }
    }

    private void emitWar(Object war) throws ReflectiveOperationException {
        if(war==null) return;
        UUID id=(UUID)call(war,"id"); Object current=unwrap(safe(service("module.war.WarService"),"getWar",id)); if(current==null) return;
        // Mutable allies cannot alter the canonical payload of an already recorded lifecycle ID.
        Set<String> nations=strings(call(current,"aggressor"),call(current,"defender"));
        Object started=call(current,"startedAt"), ended=call(current,"endedAt");
        if(started instanceof Instant time) emitEvent(warLifecycleEvent(id,"WAR_STARTED",time,nations));
        if(ended instanceof Instant time) emitEvent(warLifecycleEvent(id,"WAR_ENDED",time,nations));
        Object outcome=unwrap(safe(service("module.war.WarService"),"outcome",id));
        if(outcome!=null) {
            for(Object battle:collection(safe(outcome,"battles"))) {
                String battleId=string(call(battle,"id")); emit("starcore:battle:"+battleId,"BATTLE_ENDED",instant(call(battle,"occurredAt")),Set.of(),strings(call(battle,"attacker"),call(battle,"defender")),Map.of("title","重要战役","war.id",id.toString(),"outcome",string(call(battle,"outcome"))),"WarService.outcome battle id="+battleId+"; warId="+id);
            }
            for(Object capture:collection(safe(outcome,"territoryCaptures"))) {
                String captureId=string(call(capture,"id")); emit("starcore:capture:"+captureId,"TERRITORY_CHANGED",instant(call(capture,"occurredAt")),Set.of(),strings(call(capture,"previousOwner"),call(capture,"newOwner")),Map.of("title","战争领土变更","war.id",id.toString(),"territory.id",string(call(capture,"territoryId"))),"WarService.outcome territory capture id="+captureId+"; warId="+id);
            }
        }
    }
    private void emit(String id,String type,Instant time,Set<UUID> players,Set<String> nations,Map<String,String> facts,String evidence) {
        emitEvent(new WorldEvent(id,type,instant(time),players,nations,facts,"StarCore",evidence));
    }
    private void emitEvent(WorldEvent event) { if(!closed) receiver.accept(event); }

    /** Canonicalize before formatting evidence, not just before saving the event timestamp. */
    static WorldEvent foundedEvent(String id,Instant sourceTime) {
        Instant time=instant(sourceTime);
        return new WorldEvent("starcore:nation-founded:"+id,"NATION_FOUNDED",time,Set.of(),strings(id),Map.of("title","国家建立","nation.id",id),"StarCore","NationService.nations authoritative entity id="+id+"; persisted foundedAt="+time+"; original founder not exposed; historical import may predate Chronicle installation");
    }
    static WorldEvent warLifecycleEvent(UUID id,String type,Instant sourceTime,Set<String> nations) {
        if(!type.equals("WAR_STARTED") && !type.equals("WAR_ENDED")) throw new IllegalArgumentException("Unknown war lifecycle type");
        Instant time=instant(sourceTime); boolean started=type.equals("WAR_STARTED");
        return new WorldEvent("starcore:war-"+(started?"started:":"ended:")+id,type,time,Set.of(),nations,Map.of("title",started?"战争开始":"战争结束","war.id",id.toString()),"StarCore","WarService.getWar("+id+")."+(started?"startedAt=":"endedAt=")+time+"; committed authoritative entity");
    }

    @Override public synchronized void close() {
        if(closed) return; closed=true;
        unsubscribers.forEach(r->{try {r.run();} catch(RuntimeException ex) {plugin.getLogger().log(Level.FINE,"Unsubscribe failed",ex);}}); unsubscribers.clear();
        HandlerList.unregisterAll(bukkitListener);
        Object loader=loader();installedScripts.forEach((id,owned)->{if(safe(loader,"load",id)==owned)safe(loader,"unregister",id);});installedScripts.clear();installedFingerprints.clear();recoverySafeScripts.clear();contexts.clear();
    }

    private Object nationId(String id) {
        try { Class<?> cls=Class.forName(BASE+"module.nation.model.NationId",true,core.getClass().getClassLoader()); return cls.getMethod("fromString",String.class).invoke(null,id); }
        catch(ReflectiveOperationException | RuntimeException ex) { return null; }
    }
    static Object call(Object target,String name,Object...args) throws ReflectiveOperationException {
        if(target==null) throw new NoSuchMethodException("Unavailable target for "+name);
        for(Method method:target.getClass().getMethods()) if(method.getName().equals(name) && method.getParameterCount()==args.length) {
            Class<?>[] types=method.getParameterTypes(); boolean matches=true;
            for(int i=0;i<types.length;i++) if(args[i]!=null && !boxed(types[i]).isInstance(args[i])) {matches=false;break;}
            if(matches) return method.invoke(target,args);
        }
        throw new NoSuchMethodException(target.getClass().getName()+"."+name);
    }
    private static Class<?> boxed(Class<?> type) { if(!type.isPrimitive()) return type; if(type==int.class)return Integer.class; if(type==boolean.class)return Boolean.class; if(type==long.class)return Long.class; if(type==double.class)return Double.class; return type; }
    private static Object safe(Object target,String name,Object...args) { try{return call(target,name,args);}catch(ReflectiveOperationException | RuntimeException ex){return null;} }
    private static Object unwrap(Object value) { return value instanceof Optional<?> o?o.orElse(null):value; }
    private static Collection<?> collection(Object value) { return value instanceof Collection<?> c?new ArrayList<>(c):List.of(); }
    private static String string(Object value) { return value==null?"":value.toString(); }
    // StarCore persists these timestamps as epoch-milliseconds. Keep first publication and a
    // post-restart replay identical rather than claiming unsupported nanosecond precision.
    static Instant instant(Object value) { if(value instanceof Instant i)return i.truncatedTo(ChronoUnit.MILLIS); throw new IllegalArgumentException("Missing authoritative timestamp"); }
    private static void put(Map<String,String> map,String key,Object value) { if(value!=null)map.put(key,string(value)); }
    private static String blankNull(String value) { return value==null || value.isBlank()?null:value; }
    private static boolean validId(String id) { return id!=null && id.matches("[A-Za-z0-9_.:-]{1,128}"); }
    private static Set<String> strings(Object...values) { Set<String> result=new HashSet<>(); for(Object value:values) if(value!=null && !string(value).isBlank())result.add(string(value)); return Set.copyOf(result); }
    private static Set<UUID> uuids(Object...values) { Set<UUID> result=new HashSet<>(); for(Object value:values) if(value instanceof UUID id)result.add(id); return Set.copyOf(result); }
}
