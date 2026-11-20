package io.github.addxiaoyi.starchronicle.paper;

import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.api.quest.*;
import io.github.addxiaoyi.starchronicle.integration.StarCoreQuestEvidenceProvider;
import io.github.addxiaoyi.starchronicle.config.*;
import io.github.addxiaoyi.starchronicle.core.*;
import io.github.addxiaoyi.starchronicle.integration.StarCoreBridge;
import io.github.addxiaoyi.starchronicle.integration.SnapshotCollector;
import io.github.addxiaoyi.starchronicle.integration.StarCoreAuthorityProvider;
import io.github.addxiaoyi.starchronicle.integration.StarCoreNationalVoteAuthority;
import io.github.addxiaoyi.starchronicle.integration.CoreContentInstaller;
import io.github.addxiaoyi.starchronicle.integration.NativeSceneRecoveryAdapter;
import io.github.addxiaoyi.starchronicle.narrative.NativeSceneJournal;
import io.github.addxiaoyi.starchronicle.history.ObservationJournal;
import io.github.addxiaoyi.starchronicle.history.FirstVisitJournal;
import io.github.addxiaoyi.starchronicle.history.FirstVisitBuffer;
import io.github.addxiaoyi.starchronicle.memory.*;
import io.github.addxiaoyi.starchronicle.npc.*;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/** Paper/Folia entry point. Bukkit objects never enter the single-worker persistence engine. */
public final class StarChroniclePlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private final AtomicBoolean running=new AtomicBoolean();
    private ThreadPoolExecutor worker;
    private RegionTasks tasks;
    private StarCoreBridge gateway;
    private RuleEngine engine;
    private Menus menus;
    private NpcHooks npcs;
    private SqliteStore store;
    private NpcLedger npcLedger;
    private MemoryLedger memoryLedger;
    private Path memoryRecipesFile;
    private MemorySites memorySites;
    private ObservationJournal observations;
    private FirstVisitJournal firstVisits;
    private List<ExplorationRegion> explorationRegions=List.of();
    private final FirstVisitBuffer explorationIngress=new FirstVisitBuffer(2048);
    private ExplorationDiscoveries exploration;
    private SnapshotCollector snapshotCollector;
    private TextService textService;
    private CoreContentInstaller coreContent;
    private NativeSceneJournal nativeJournal;
    private final NativeSceneFlights nativeFlights=new NativeSceneFlights();
    private ScheduledTask poll;
    private QuestGateDriver questGates;
    private volatile Set<String> permissions=Set.of();
    private ChronicleService service;
    private volatile List<StoryDefinition> definitions=List.of();
    private volatile NationalVoteAuthority nationalVoteAuthority=NationalVoteAuthority.denying();
    @Override public void onEnable() {
        try {
            saveDefaultConfig();
            tasks=new RegionTasks(this);
            boolean folia;
            try {Class.forName("io.papermc.paper.threadedregions.RegionizedServer");folia=true;}catch(ClassNotFoundException ignored){folia=false;}
            gateway=new StarCoreBridge(this,(id,action,retired)->tasks.playerOwned(id,action,retired),folia);
            if(getConfig().getBoolean("require-starcore",true) && !gateway.available()) throw new IllegalStateException("StarCore services unavailable; install the audited StarCore build, or explicitly set require-starcore:false for standalone rule/history mode");
            if(!gateway.available())getLogger().warning("STANDALONE MODE: no authoritative StarCore identity, task, narrative or nation mutations are available");
            var builtinQuestEvidence=new StarCoreQuestEvidenceProvider(gateway,folia);
            boolean nativeExamples=getConfig().getBoolean("install-native-example-quests",false);
            if(nativeExamples&&!builtinQuestEvidence.available())throw new IllegalStateException("Native example quests require the durable StarCore quest-evidence protocol on Paper");
            coreContent=new CoreContentInstaller(gateway,getDataFolder().toPath());
            var installed=coreContent.install(nativeExamples);getLogger().info("Native supplemental scene registration: "+installed.scenes()+"; native quests: "+installed.quests());
            if(nativeExamples&&installed.quests().values().stream().anyMatch(status->!status.startsWith("INSTALLED")))throw new IllegalStateException("Native example quest ownership/registration failed: "+installed.quests());
            Path stories=getDataFolder().toPath().resolve("stories");Files.createDirectories(stories);
            if(getConfig().getBoolean("install-default-stories",true))for(String name:List.of("last-heir.yml","siege-embers.yml","silk-road-scholar.yml","heir-legacy.yml","integration-example.json.disabled","fate-soldier.yml","fate-general.yml","fate-merchant.yml","fate-scholar.yml","fate-explorer.yml","fate-diplomat.yml","fate-mayor.yml","fate-monarch.yml")) if(!Files.exists(stories.resolve(name)))saveResource("stories/"+name,false);
            if(!Files.exists(getDataFolder().toPath().resolve("npcs.yml")))saveResource("npcs.yml",false);
            NpcCatalog catalog=NpcCatalog.load(getDataFolder().toPath().resolve("npcs.yml"));
            String memoryFile=getConfig().getString("memory-recipes-file","memory-recipes.yml");
            if(memoryFile==null||!memoryFile.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,79}\\.(yml|yaml|json)"))throw new IllegalArgumentException("Memory recipes require a local YAML/JSON filename");
            memoryRecipesFile=getDataFolder().toPath().resolve(memoryFile);
            if(memoryFile.equals("memory-recipes.yml")&&!Files.exists(memoryRecipesFile))saveResource("memory-recipes.yml",false);
            MemoryRecipeCatalog memoryRecipes=MemoryRecipeCatalog.load(memoryRecipesFile);
            store=new SqliteStore(getDataFolder().toPath().resolve("chronicle.db"));
            firstVisits=new FirstVisitJournal(store);
            explorationRegions=ExplorationRegion.load(getConfig());
            firstVisits.registerRegions(explorationRegions.stream().collect(java.util.stream.Collectors.toMap(ExplorationRegion::id,ExplorationRegion::scope)));
            definitions=coreContent.bind(ConfigLoader.loadWithoutDirectoryValidation(stories,gateway::hasScript,gateway::hasQuest));
            npcLedger=new NpcLedger(store,catalog);
            NationalVoteAuthority builtinNationalAuthority=new StarCoreNationalVoteAuthority(gateway);
            nationalVoteAuthority=builtinNationalAuthority;
            // The stable proxy reads only a volatile trusted provider. Registry/Bukkit
            // access stays outside the provider's native authority monitor and SQL lock.
            NationalVoteAuthority nationalAuthorityProxy=new NationalVoteAuthority(){
                @Override public <T>T withRepresentative(UUID actor,String nation,Authorized<T> operation)throws Exception {
                    return nationalVoteAuthority.withRepresentative(actor,nation,operation);
                }
            };
            engine=new RuleEngine(store,definitions,Clock.systemUTC(),npcLedger,nationalAuthorityProxy,memoryRecipes);
            nativeJournal=new NativeSceneJournal(store,Clock.systemUTC());
            memoryLedger=new MemoryLedger(store);
            observations=new ObservationJournal(store,Clock.systemUTC());
            snapshotCollector=new SnapshotCollector(gateway,()->getServer().getServicesManager().getRegistrations(SnapshotProvider.class).stream().map(r->r.getProvider()).toList());
            textService=new TextService(Duration.ofMillis(Math.max(1,Math.min(30000,getConfig().getLong("text-timeout-ms",1500)))));
            permissions=permissionNodes(withPendingDefinitions(definitions),engine.activeInstances());
            worker=new StateWorker(getConfig().getInt("worker-queue-capacity",2048),()->{try{explorationIngress.drain(firstVisits::observe,Integer.MAX_VALUE);}catch(RuntimeException error){getLogger().severe("Uncommitted exploration arrivals could not be saved on shutdown: "+error.getMessage());}finally{engine.close();}});
            running.set(true);
            questGates=new QuestGateDriver(engine,new QuestGateDriver.StateDispatcher(){
                @Override public <T>CompletionStage<T> submit(Callable<T> operation){return query(operation);}
            },this::snapshot,Clock.systemUTC(),Duration.ofSeconds(10),8,message->getLogger().warning(message));
            service=new Service();
            getServer().getServicesManager().register(ChronicleService.class,service,this,ServicePriority.Normal);
            var builtinAuthority=new StarCoreAuthorityProvider(gateway);
            if(builtinAuthority.available())getServer().getServicesManager().register(AuthorityProvider.class,builtinAuthority,this,ServicePriority.Lowest);
            menus=new Menus(this);getServer().getPluginManager().registerEvents(menus,this);getServer().getPluginManager().registerEvents(this,this);
            getServer().getServicesManager().register(NationalVoteAuthority.class,builtinNationalAuthority,this,ServicePriority.Lowest);
            refreshNationalVoteAuthority(null);
            if(builtinQuestEvidence.available())getServer().getServicesManager().register(QuestEvidenceProvider.class,builtinQuestEvidence,this,ServicePriority.Lowest);
            refreshQuestEvidenceProviders(null);
            PluginCommand command=Objects.requireNonNull(getCommand("chronicle"));command.setExecutor(this);command.setTabCompleter(this);
            gateway.start(event->service.submit(event).exceptionally(error->{getLogger().warning("Source event was not accepted: "+error.getMessage());return null;}));
            npcs=new NpcHooks(this,this::npcInteraction);if(getConfig().getBoolean("npc-hooks",true))npcs.start();
            memorySites=new MemorySites(this,(id,eventId)->query(()->memoryLedger.source(eventId)).whenComplete((event,error)->{if(error!=null)failure(id,error);else menus.event(id,event);}),this::npcInteraction);
            getServer().getPluginManager().registerEvents(memorySites,this);memorySites.refresh(memoryLedger.sites());
            exploration=new ExplorationDiscoveries(explorationRegions,(id,action)->tasks.playerOwned(id,p->{if(running.get())action.accept(p);},()->{}),p->{gateway.refreshContext(p,permissions);return gateway.context(p.getUniqueId());},this::captureExploration,Clock.systemUTC());
            getServer().getPluginManager().registerEvents(exploration,this);
            tasks.global(()->getServer().getOnlinePlayers().forEach(p->{tasks.track(p);tasks.playerOwned(p.getUniqueId(),owned->gateway.refreshContext(owned,permissions),()->{});}));
            long ticks=Math.max(20,getConfig().getLong("poll-ticks",100));
            poll=getServer().getGlobalRegionScheduler().runAtFixedRate(this,ignored->pollSources(),20,ticks);
            getLogger().info("Loaded "+definitions.size()+" validated stories; durable history/outbox ready");
            if(!explorationRegions.isEmpty())getLogger().info("Configured first-visit exploration regions: "+explorationRegions.size());
        } catch(Exception|LinkageError error) {
            getLogger().severe("StarChronicle startup failed: "+error.getMessage());error.printStackTrace();getServer().getPluginManager().disablePlugin(this);
        }
    }
    public ChronicleService service(){return service;}
    public CoreGateway gateway(){return gateway;}
    /** Service lifecycle events select trusted plugin providers outside native authority
     * callbacks. An unregistered provider is removed immediately, including when the host
     * fires its event before removing the registration from the registry collection. */
    private void refreshNationalVoteAuthority(RegisteredServiceProvider<?> excluded) {
        nationalVoteAuthority=getServer().getServicesManager().getRegistrations(NationalVoteAuthority.class).stream()
                .filter(registration->excluded==null||registration.getProvider()!=excluded.getProvider())
                .max(Comparator.<RegisteredServiceProvider<NationalVoteAuthority>>comparingInt(registration->registration.getPriority().ordinal())
                        .thenComparingInt(registration->registration.getPlugin()==this?0:1))
                .map(RegisteredServiceProvider::getProvider).orElseGet(NationalVoteAuthority::denying);
    }
    @EventHandler public void nationalAuthorityRegistered(ServiceRegisterEvent event) {
        if(running.get()&&event.getProvider().getService()==NationalVoteAuthority.class)refreshNationalVoteAuthority(null);
    }
    @EventHandler public void nationalAuthorityUnregistered(ServiceUnregisterEvent event) {
        if(event.getProvider().getService()==NationalVoteAuthority.class){
            // Fail closed before any registry lookup; a removed extension is never kept
            // as the delegate while another provider is being selected.
            nationalVoteAuthority=NationalVoteAuthority.denying();
            if(running.get())refreshNationalVoteAuthority(event.getProvider());
        }
    }
    /** Cache trusted service leases outside storage; unregister immediately invalidates replies. */
    private void refreshQuestEvidenceProviders(RegisteredServiceProvider<?> excluded) {
        if(questGates==null)return;
        var providers=getServer().getServicesManager().getRegistrations(QuestEvidenceProvider.class).stream()
            .filter(registration->excluded==null||registration.getProvider()!=excluded.getProvider())
            .sorted(Comparator.<RegisteredServiceProvider<QuestEvidenceProvider>>comparingInt(r->r.getPriority().ordinal()).reversed()
                .thenComparing(r->r.getPlugin().getName()))
            .map(RegisteredServiceProvider::getProvider).toList();
        questGates.providers(providers);
    }
    @EventHandler public void questEvidenceRegistered(ServiceRegisterEvent event){
        if(running.get()&&event.getProvider().getService()==QuestEvidenceProvider.class)refreshQuestEvidenceProviders(null);
    }
    @EventHandler public void questEvidenceUnregistered(ServiceUnregisterEvent event){
        if(event.getProvider().getService()==QuestEvidenceProvider.class&&questGates!=null){
            questGates.providers(List.of());if(running.get())refreshQuestEvidenceProviders(event.getProvider());
        }
    }
    private void pollSources() {
        snapshots().thenCompose(contexts->query(()->{
            nativeJournal.isolateOrphans(nativeFlights.players());
            gateway.scanCommittedSources();
            for(var snapshot:snapshotCollector.collect())observations.observe(snapshot);
            explorationIngress.drain(firstVisits::observe,100);
            for(WorldEvent event:observations.pending(100)) {
                engine.accept(event,audience(event,contexts));
                observations.acknowledge(event.id());
            }
            for(WorldEvent event:firstVisits.pending(100)) {
                engine.accept(event,audience(event,contexts));
                firstVisits.acknowledge(event.id());
            }
            engine.resumeTriggers(contexts);
            deliverPending();for(PlayerContext context:contexts)resumeNativeOnWorker(context.playerId());
            if(questGates!=null)questGates.poll(tasks.onlineIds()).exceptionally(error->{if(running.get())getLogger().warning("Quest evidence scan retained: "+root(error).getMessage());return null;});
            return null;
        })).exceptionally(error->{if(running.get())getLogger().warning("History scan retained pending evidence: "+root(error).getMessage());return null;});
    }
    private static List<PlayerContext> audience(WorldEvent event,List<PlayerContext> contexts){return contexts.stream().filter(c->event.globalAudience()||event.participants().contains(c.playerId())||(!c.nationId().isBlank()&&event.nations().contains(c.nationId()))).toList();}
    private List<StoryDefinition> withPendingDefinitions(List<StoryDefinition> configured){List<StoryDefinition> all=new ArrayList<>(configured);all.addAll(engine.pendingDefinitions());return all;}
    private static Set<String> permissionNodes(List<StoryDefinition> configured,List<StoryInstance> pinned) {
        Set<String> nodes=new HashSet<>(Set.of("starchronicle.use","starchronicle.admin"));
        List<StoryDefinition> all=new ArrayList<>(configured);pinned.forEach(i->all.add(i.definition()));
        for(StoryDefinition d:all)for(StoryDefinition.Node n:d.nodes().values()) {
            for(StoryDefinition.Effect e:n.effects())if(e.permission()!=null&&!e.permission().isBlank())nodes.add(e.permission());
            for(StoryDefinition.Choice c:n.choices().values()) {if(c.permission()!=null&&!c.permission().isBlank())nodes.add(c.permission());for(StoryDefinition.Effect e:c.effects())if(e.permission()!=null&&!e.permission().isBlank())nodes.add(e.permission());}
        }
        return Set.copyOf(nodes);
    }
    /** The consumer runs only on the entity scheduler, and receives no offline player. */
    void present(UUID id,Consumer<Player> display) {if(!running.get())return;tasks.playerOwned(id,p->{if(running.get()&&p.isOnline()){gateway.refreshContext(p,permissions);display.accept(p);}},()->{});}
    void message(UUID id,String text){present(id,p->p.sendMessage(Component.text("[编年史] "+text)));}
    void failure(UUID id,Throwable error){getLogger().warning("Player operation failed: "+root(error).getMessage());message(id,"操作未完成："+Objects.toString(root(error).getMessage(),"内部错误"));}
    private static Throwable root(Throwable error){while(error.getCause()!=null&&(error instanceof CompletionException||error instanceof ExecutionException))error=error.getCause();return error;}
    private <T> CompletableFuture<T> query(Callable<T> work) {
        CompletableFuture<T> future=new CompletableFuture<>();
        if(!running.get()){future.completeExceptionally(new IllegalStateException("Plugin is stopping"));return future;}
        try {worker.execute(()->{try{future.complete(work.call());}catch(Throwable error){future.completeExceptionally(error);}});}catch(RejectedExecutionException full){future.completeExceptionally(new IllegalStateException("Narrative worker queue is full; retry later",full));}
        return future;
    }
    private CompletionStage<PlayerContext> snapshot(UUID id) {
        CompletableFuture<PlayerContext> result=new CompletableFuture<>();
        if(!running.get()){result.completeExceptionally(new IllegalStateException("Plugin is stopping"));return result;}
        try{tasks.playerOwned(id,p->{
            if(result.isDone())return;
            if(!running.get()){result.completeExceptionally(new IllegalStateException("Plugin is stopping"));return;}
            if(!p.isOnline()){result.completeExceptionally(new IllegalStateException("Player is offline"));return;}
            try{gateway.refreshContext(p,permissions);if(exploration!=null)exploration.observe(p);result.complete(gateway.context(id));}catch(Throwable error){result.completeExceptionally(error);}
        },()->result.completeExceptionally(new IllegalStateException("Player scheduler retired")));}catch(RuntimeException scheduling){result.completeExceptionally(scheduling);}
        return result.orTimeout(5,TimeUnit.SECONDS);
    }
    private CompletionStage<List<PlayerContext>> snapshots() {
        List<CompletableFuture<PlayerContext>> all=tasks.onlineIds().stream().map(id->snapshot(id).exceptionally(error->null).toCompletableFuture()).toList();
        return CompletableFuture.allOf(all.toArray(CompletableFuture[]::new)).thenApply(ignored->all.stream().map(CompletableFuture::join).filter(Objects::nonNull).toList());
    }
    private CompletionStage<PlayerContext> nationalSnapshot(UUID player){return snapshot(player).thenApply(context->{
        if(!context.permitted("starchronicle.use"))throw new SecurityException("缺少编年史使用权限");
        return context;
    });}
    @FunctionalInterface private interface PlayerWork<T>{T run(Player player)throws Exception;}
    private <T> CompletionStage<T> playerQuery(UUID id,PlayerWork<T> work) {
        return playerQuery(id,work,new InvocationGate());
    }
    private <T> CompletionStage<T> playerQuery(UUID id,PlayerWork<T> work,InvocationGate gate) {
        CompletableFuture<T> result=new CompletableFuture<>();
        if(!running.get())return CompletableFuture.failedFuture(new IllegalStateException("Plugin is stopping"));
        try{tasks.playerOwned(id,player->{
            if(result.isDone())return;
            if(!running.get()){result.completeExceptionally(new IllegalStateException("Plugin is stopping"));return;}
            if(!player.isOnline()||!player.hasPermission("starchronicle.use")){result.completeExceptionally(new SecurityException("Player unavailable or unauthorized"));return;}
            if(!gate.enter())return;
            try{result.complete(work.run(player));}catch(Throwable error){result.completeExceptionally(error);}
        },()->result.completeExceptionally(new IllegalStateException("Player scheduler retired")));}catch(RuntimeException scheduling){result.completeExceptionally(scheduling);}
        result.whenComplete((ignored,error)->{if(error!=null)gate.cancelBeforeEntry();});
        return result.orTimeout(5,TimeUnit.SECONDS);
    }
    private void deliverPending() {
        if(!running.get())return;
        int count=0;
        for(Delivery d:engine.pending(tasks.onlineIds())) {
            if(!running.get())break;
            if(d.playerId()!=null&&!tasks.online(d.playerId()))continue;
            if(count++>=100)break;
            if(d.type().equals("narrative.start")&&(gateway.managedScene(d.target())||nativeJournal.find(d.outcomeId()).isPresent())) {
                try {
                    var existing=nativeJournal.find(d.outcomeId()).orElse(null);
                    if(existing!=null&&!existing.request().scriptFingerprint().equals(gateway.sceneFingerprint(d.target()))) {
                        nativeJournal.quarantineReady(existing.request().outcomeId(),existing.revision(),"Registered script content/ownership changed; no replacement version was invoked");
                        if(engine.claimDelivery(d.outcomeId()))engine.finishDelivery(d.outcomeId(),CoreGateway.DeliveryResult.UNKNOWN);continue;
                    }
                    var request=new NativeSceneJournal.Request(d.outcomeId(),d.instanceId(),d.playerId(),Objects.requireNonNullElse(d.nationId(),""),d.target(),gateway.sceneFingerprint(d.target()),d.value(),d.permission());
                    var entry=nativeJournal.prepare(request);
                    if(!engine.claimDelivery(d.outcomeId()))continue;
                    if(entry.status()==NativeSceneJournal.Status.COMPLETED||entry.status()==NativeSceneJournal.Status.READY&&entry.checkpoint()!=null)engine.finishDelivery(d.outcomeId(),CoreGateway.DeliveryResult.APPLIED);
                    else if(entry.status()==NativeSceneJournal.Status.UNKNOWN)engine.finishDelivery(d.outcomeId(),CoreGateway.DeliveryResult.UNKNOWN);
                    else runNative(entry,d,null).exceptionally(error->{getLogger().fine("Owned native scene waiting: "+root(error).getMessage());return Optional.empty();});
                }catch(RuntimeException error){getLogger().warning("Native scene request retained for review: "+d.outcomeId()+"; "+error.getMessage());}
                continue;
            }
            if(!engine.claimDelivery(d.outcomeId()))continue;
            try {
                gateway.deliver(d).toCompletableFuture().orTimeout(30,TimeUnit.SECONDS).whenComplete((result,error)->{
                    if(running.get())query(()->{engine.finishDelivery(d.outcomeId(),error==null&&result!=null?result:CoreGateway.DeliveryResult.UNKNOWN);return null;});
                });
            } catch(Throwable error) {engine.finishDelivery(d.outcomeId(),CoreGateway.DeliveryResult.UNKNOWN);getLogger().warning("External effect uncertain: "+d.outcomeId());}
        }
    }
    private record NativeAction(String script,String node,int choice){}
    /** Called on the state worker. A native call is leased durably before dispatching to its
     * entity; its actual read-back is checkpointed before exposing success to the player. */
    private CompletionStage<Optional<NativeScene>> runNative(NativeSceneJournal.Entry entry,Delivery initial,NativeAction action) {
        UUID player=entry.request().playerId();String outcome=entry.request().outcomeId();
        if(!entry.request().scriptFingerprint().equals(gateway.sceneFingerprint(entry.request().scriptId()))){nativeJournal.quarantineReady(outcome,entry.revision(),"Registered script changed before recovery; no replacement version was invoked");if(initial!=null)engine.finishDelivery(initial.outcomeId(),CoreGateway.DeliveryResult.UNKNOWN);return CompletableFuture.failedFuture(new IllegalStateException("原生场景脚本版本或所有权已变化，已隔离并等待审核："+outcome));}
        var admitted=nativeFlights.acquire(player);
        if(admitted.isEmpty()){if(initial!=null)engine.notInvoked(initial.outcomeId());return CompletableFuture.failedFuture(new IllegalStateException("原生场景操作正在进行，请稍后刷新"));}
        var lease=admitted.get();
        Optional<NativeSceneJournal.Attempt> claimed;
        try{claimed=nativeJournal.claim(outcome,entry.revision(),gateway.sceneFingerprint(entry.request().scriptId()));}
        catch(RuntimeException error){nativeFlights.fail(lease,error);if(initial!=null)engine.notInvoked(initial.outcomeId());return lease.stage();}
        if(claimed.isEmpty()){nativeFlights.fail(lease,new IllegalStateException("原生场景被未知结果或脚本版本变化隔离，请联系管理员"));if(initial!=null)engine.notInvoked(initial.outcomeId());return lease.stage();}
        var attempt=claimed.get();InvocationGate gate=new InvocationGate();
        playerQuery(player,p->{
            if(!attempt.request().permission().isBlank()&&!p.hasPermission(attempt.request().permission()))return new NativeSceneRecoveryAdapter.Result(NativeSceneRecoveryAdapter.Status.NOT_INVOKED,null,"原生场景权限已变化，暂缓执行");
            gateway.refreshContext(p,permissions);
            if(!attempt.request().nationId().isBlank()&&!attempt.request().nationId().equals(gateway.context(player).nationId()))return new NativeSceneRecoveryAdapter.Result(NativeSceneRecoveryAdapter.Status.NOT_INVOKED,null,"国籍已变化，原生场景资格需要审核");
            return action==null?gateway.resumeScene(p,attempt.request(),attempt.checkpoint()):gateway.managedSceneAction(p,action.script(),action.node(),action.choice());
        },gate).whenComplete((response,error)->{
            boolean definitelyNotInvoked=error!=null&&gate.cancelBeforeEntry();
            query(()->{
                NativeSceneRecoveryAdapter.Result result=response;
                if(error!=null)result=new NativeSceneRecoveryAdapter.Result(definitelyNotInvoked?NativeSceneRecoveryAdapter.Status.NOT_INVOKED:NativeSceneRecoveryAdapter.Status.UNKNOWN,null,"Entity operation "+(definitelyNotInvoked?"was not invoked: ":"may have executed: ")+root(error).getClass().getSimpleName());
                if(result==null)result=new NativeSceneRecoveryAdapter.Result(NativeSceneRecoveryAdapter.Status.UNKNOWN,null,"Native adapter returned no conclusive response");
                boolean saved;Optional<NativeScene> next=Optional.empty();
                switch(result.status()) {
                    case READY->{saved=nativeJournal.checkpoint(attempt,result.checkpoint());next=Optional.of(result.checkpoint().view());if(saved){if(initial!=null)engine.finishDelivery(initial.outcomeId(),CoreGateway.DeliveryResult.APPLIED);else confirmNativeStart(outcome);}}
                    case COMPLETED->{saved=nativeJournal.completed(attempt);if(saved){if(initial!=null)engine.finishDelivery(initial.outcomeId(),CoreGateway.DeliveryResult.APPLIED);else confirmNativeStart(outcome);}}
                    case NOT_INVOKED->{saved=nativeJournal.notInvoked(attempt,result.reason());if(saved&&initial!=null)engine.notInvoked(initial.outcomeId());}
                    case UNKNOWN->{saved=nativeJournal.unknown(attempt,result.reason());if(saved&&initial!=null)engine.finishDelivery(initial.outcomeId(),CoreGateway.DeliveryResult.UNKNOWN);}
                    default->throw new IllegalStateException("Unknown native result");
                }
                if(!saved)throw new IllegalStateException("Native operation lease changed before checkpoint commit");
                if(result.status()==NativeSceneRecoveryAdapter.Status.UNKNOWN)throw new IllegalStateException("原生场景结果不确定，已隔离且不会自动重放："+outcome);
                if(result.status()==NativeSceneRecoveryAdapter.Status.NOT_INVOKED&&action!=null)throw new IllegalStateException(result.reason());
                return next;
            }).whenComplete((next,saveError)->{if(saveError==null)nativeFlights.complete(lease,next);else nativeFlights.fail(lease,saveError);});
        });
        return lease.stage();
    }
    /** A committed native checkpoint proves the initial start was actually observed. */
    private void confirmNativeStart(String outcome){if(engine.claimDelivery(outcome))engine.finishDelivery(outcome,CoreGateway.DeliveryResult.APPLIED);engine.acknowledgeUnknown(outcome);}
    private CompletionStage<Optional<NativeScene>> resumeNativeOnWorker(UUID player) {
        var current=nativeFlights.current(player);
        if(current.isPresent())return current.get();
        if(!nativeJournal.unknown(player,1).isEmpty())return CompletableFuture.failedFuture(new IllegalStateException("原生场景结果不确定，请由管理员先对账"));
        var ready=nativeJournal.resumable(player,100).stream().findFirst();
        if(ready.isPresent())return runNative(ready.get(),null,null);
        return playerQuery(player,p->{
            var scene=gateway.scene(player);
            if(scene.isPresent()&&gateway.managedScene(scene.get().scriptId()))throw new IllegalStateException("此原生场景没有可用的持久化操作令牌，请通过正式剧情触发或联系管理员");
            return scene;
        });
    }
    private CompletionStage<Optional<NativeScene>> nativeActionOnWorker(UUID player,String script,String node,int choice) {
        // Background capture and GUI reads share the same committed result. A click waits
        // for that result, then returns to the worker and revalidates its original node token.
        // An uncertain/failed operation propagates its failure; it is never replayed here.
        if(nativeFlights.current(player).isPresent())return nativeFlights.afterCurrent(player,()->query(()->nativeActionOnWorker(player,script,node,choice)).thenCompose(Function.identity()));
        var entry=nativeJournal.resumable(player,100).stream().filter(e->e.request().scriptId().equals(script)).findFirst();
        if(entry.isPresent())return runNative(entry.get(),null,new NativeAction(script,node,choice));
        if(!nativeJournal.unknown(player,1).isEmpty())return CompletableFuture.failedFuture(new IllegalStateException("原生场景结果不确定，请由管理员先对账"));
        if(gateway.managedScene(script))return CompletableFuture.failedFuture(new IllegalStateException("此原生场景没有可用的持久化操作令牌，请刷新或通过正式剧情触发"));
        return playerQuery(player,p->gateway.sceneAction(p,script,node,choice));
    }
    @EventHandler public void joined(PlayerJoinEvent event) {
        Player p=event.getPlayer();tasks.track(p);gateway.refreshContext(p,permissions);
        PlayerContext context=gateway.context(p.getUniqueId());
        String day=LocalDate.now(ZoneOffset.UTC).toString();
        // One observed arrival per UTC day. Neither identity nor nation is accepted from command arguments.
        WorldEvent arrival=new WorldEvent("chronicle:arrival:"+p.getUniqueId()+":"+day,"PLAYER_JOIN",Instant.now(),Set.of(p.getUniqueId()),Set.of(),Map.of("time_basis","OBSERVED","day",day),"StarChronicle.PlayerJoinEvent","First observed player arrival for UTC day="+day+"; player="+p.getUniqueId());
        query(()->{engine.accept(arrival,List.of(context));engine.resumeTriggers(List.of(context));deliverPending();resumeNativeOnWorker(context.playerId()).exceptionally(error->{getLogger().fine("Native scene recovery waiting: "+root(error).getMessage());return Optional.empty();});return null;}).exceptionally(error->{failure(p.getUniqueId(),error);return null;});
    }
    private boolean captureExploration(WorldEvent event) {
        if(!running.get())return false;
        FirstVisitBuffer.Admission admission=explorationIngress.offer(event);
        if(admission==FirstVisitBuffer.Admission.FULL){getLogger().warning("Exploration capture buffer full; arrival was not admitted: "+event.id());return false;}
        if(admission==FirstVisitBuffer.Admission.ADDED)query(()->explorationIngress.drain(firstVisits::observe,100)).exceptionally(error->{if(running.get())getLogger().warning("Exploration arrivals remain buffered pending persistence: "+root(error).getMessage());return 0;});
        return true;
    }
    @EventHandler public void quit(PlayerQuitEvent event) {tasks.untrack(event.getPlayer());}
    private void npcInteraction(UUID player,String npcId) {
        String logical=npcLedger.catalog().profiles().containsKey(npcId)?npcId:npcLedger.catalog().resolveExternal(npcId).orElse(null);
        if(logical==null){menus.stories(player,0);return;}
        snapshot(player).thenCompose(context->{
            WorldEvent event=new WorldEvent("npc:"+UUID.randomUUID(),"NPC_INTERACT",Instant.now(),Set.of(player),context.nationId().isBlank()?Set.of():Set.of(context.nationId()),Map.of("npc.id",logical,"npc.binding",npcId),"NPC_BRIDGE","Observed uncancelled interaction; player="+player+"; npc="+logical+"; binding="+npcId);
            return service.submit(event);
        }).whenComplete((report,error)->{if(error!=null)failure(player,error);else menus.npc(player,logical);});
    }
    private final class Service implements ChronicleService {
        @Override public CompletionStage<ProcessReport> submit(WorldEvent event){Objects.requireNonNull(event);return snapshots().thenCompose(contexts->query(()->{ProcessReport result=engine.accept(event,audience(event,contexts));deliverPending();return result;}));}
        @Override public CompletionStage<HistoricalEvaluationReport> evaluateHistorical(String source,String story,String version){return query(()->{var report=engine.evaluateHistorical(source,story,version);permissions=permissionNodes(withPendingDefinitions(definitions),engine.activeInstances());return report;});}
        @Override public CompletionStage<ProcessReport> choose(String id,UUID player,String choice){return snapshot(player).thenCompose(context->query(()->{ProcessReport result=engine.choose(id,player,choice,context);deliverPending();return result;}));}
        @Override public CompletionStage<ProcessReport> choose(String id,UUID player,String expected,String choice){return snapshot(player).thenCompose(context->query(()->{ProcessReport result=engine.choose(id,player,expected,choice,context);deliverPending();return result;}));}
        @Override public CompletionStage<ProcessReport> nationalVote(String id,UUID player,String expected,long revision,String choice){return nationalSnapshot(player).thenCompose(context->query(()->{ProcessReport result=engine.nationalVote(id,player,expected,revision,choice,context);deliverPending();return result;}));}
        @Override public CompletionStage<ProcessReport> withdrawNationalVote(String id,UUID player,String expected,long revision){return nationalSnapshot(player).thenCompose(context->query(()->{ProcessReport result=engine.withdrawNationalVote(id,player,expected,revision,context);deliverPending();return result;}));}
        @Override public CompletionStage<QuestGateState> questGateState(String id,UUID player){return nationalSnapshot(player).thenCompose(context->query(()->engine.questGateSnapshot(id,context)));}
        @Override public CompletionStage<NationalVoteState> nationalState(String id,UUID player){return nationalSnapshot(player).thenCompose(context->query(()->{NationalVoteState result=engine.nationalState(id,context);deliverPending();return result;}));}
        @Override public CompletionStage<ProcessReport> join(String id,UUID player){return snapshot(player).thenCompose(context->query(()->engine.join(id,context)));}
        @Override public CompletionStage<List<StoryInstance>> instances(UUID player){return snapshot(player).thenCompose(context->query(()->{engine.resumeTriggers(List.of(context));return engine.instances(player);}));}
        @Override public CompletionStage<List<StoryInstance>> activeInstances(){return query(engine::activeInstances);}
        @Override public CompletionStage<List<StoryInstance>> availableInstances(UUID player){return snapshot(player).thenCompose(context->query(()->engine.availableInstances(context)));}
        @Override public CompletionStage<Map<String,StoryDefinition.Choice>> availableChoices(String id,UUID player){return snapshot(player).thenCompose(context->query(()->engine.availableChoices(id,context)));}
        @Override public CompletionStage<List<WorldEvent>> history(String nation,UUID player,int page,int size){return query(()->engine.history(nation,player,page,size));}
        @Override public CompletionStage<List<MemoryEntry>> memoryEntries(String region,int page,int size){return query(()->engine.memoryEntries(region,page,size));}
        @Override public CompletionStage<Map<String,String>> recollections(UUID player,Collection<String> references){List<String> refs=List.copyOf(references);return snapshot(player).thenCompose(context->query(()->engine.recollections(context,refs)));}
        @Override public CompletionStage<NpcView> npc(UUID player,String npc){return snapshot(player).thenCompose(context->query(()->npcLedger.view(player,npc,context,engine.worldFacts())));}
        @Override public CompletionStage<Map<String,String>> fate(UUID player){return snapshot(player).thenCompose(context->query(()->{Map<String,String> facts=new TreeMap<>(engine.facts(player));facts.putAll(npcLedger.facts(player));facts.put("player.role",context.facts().getOrDefault("player.role","unassigned"));return Map.copyOf(facts);}));}
        @Override public CompletionStage<String> describe(String template,Map<String,String> facts){var registered=getServer().getServicesManager().getRegistration(TextProvider.class);return textService.describe(registered==null?null:registered.getProvider(),template,facts);}
        @Override public CompletionStage<Optional<NativeScene>> scene(UUID player){return query(()->resumeNativeOnWorker(player)).thenCompose(Function.identity());}
        @Override public CompletionStage<Optional<NativeScene>> sceneAction(UUID player,String script,String node,int choice){return query(()->nativeActionOnWorker(player,script,node,choice)).thenCompose(Function.identity());}
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        if(!sender.hasPermission("starchronicle.use")){sender.sendMessage("没有权限。");return true;}
        if(args.length>0 && args[0].equalsIgnoreCase("memory"))return memory(sender,args);
        if(args.length>0 && Set.of("validate","reload","status","uncertain","ack","native-ack","blocked","evaluate","demo").contains(args[0].toLowerCase(Locale.ROOT)))return admin(sender,args);
        if(!(sender instanceof Player p)){sender.sendMessage("玩家命令: /chronicle [history|stories|join|choose|national|vote|withdraw]；控制台: /chronicle status|validate|uncertain");return true;}
        UUID id=p.getUniqueId();tasks.track(p);gateway.refreshContext(p,permissions);
        try {
            String action=args.length==0?"stories":args[0].toLowerCase(Locale.ROOT);
            switch(action) {
                case "stories"->menus.stories(id,0);
                case "scene"->menus.scene(id);
                case "npc"->{if(args.length!=2)throw new IllegalArgumentException("用法: /chronicle npc <逻辑NPC ID>");menus.npc(id,args[1]);}
                case "fate"->menus.fate(id);
                case "past"->{
                    if(args.length!=2)throw new IllegalArgumentException("用法: /chronicle past <剧情ID/版本/节点ID>");
                    service.recollections(id,List.of("past.player.choice."+args[1],"past.resolution.world."+args[1],"past.resolution.nation."+args[1],"past.nation.choice."+args[1])).whenComplete((facts,error)->{
                        if(error!=null)failure(id,error);else message(id,facts.isEmpty()?"暂无已完成剧情中的可核验抉择。":new TreeMap<>(facts).entrySet().stream().map(e->e.getKey()+" = "+e.getValue()).collect(java.util.stream.Collectors.joining("\n")));
                    });
                }
                case "book"->{int volume=args.length>1?Integer.parseInt(args[1])-1:0;if(volume<0)throw new IllegalArgumentException("卷数从1开始");service.history(null,null,volume,1).whenComplete((events,error)->{if(error!=null)failure(id,error);else menus.book(id,events);});}
                case "history"->{String nation=null;UUID person=null;int page=0;
                    if(args.length>=3&&args[1].equalsIgnoreCase("nation")){nation=args[2];if(args.length>3)page=Integer.parseInt(args[3])-1;}
                    else if(args.length>=3&&args[1].equalsIgnoreCase("player")){person=args[2].equalsIgnoreCase("me")?id:UUID.fromString(args[2]);if(args.length>3)page=Integer.parseInt(args[3])-1;}
                    else if(args.length>1)page=Integer.parseInt(args[1])-1;
                    menus.history(id,nation,person,page);
                }
                case "join"->{if(args.length!=2)throw new IllegalArgumentException("用法: /chronicle join <实例ID>");service.join(args[1],id).whenComplete((report,error)->{if(error!=null)failure(id,error);else{message(id,report.message());menus.stories(id,0);}});}
                case "choose"->{if(args.length!=4)throw new IllegalArgumentException("用法: /chronicle choose <实例ID> <当前节点ID> <选项ID>");service.choose(args[1],id,args[2],args[3]).whenComplete((report,error)->{if(error!=null)failure(id,error);else{message(id,report.message());menus.stories(id,0);}});}
                case "national"->{if(args.length!=2)throw new IllegalArgumentException("用法: /chronicle national <实例ID>");menus.national(id,args[1]);}
                case "vote"->{if(args.length!=5)throw new IllegalArgumentException("用法: /chronicle vote <实例ID> <当前节点ID> <国家票修订号> <选项ID>");long revision=nationalRevision(args[3]);nationalResponse(id,args[1],service.nationalVote(args[1],id,args[2],revision,args[4]));}
                case "withdraw"->{if(args.length!=4)throw new IllegalArgumentException("用法: /chronicle withdraw <实例ID> <当前节点ID> <国家票修订号>");long revision=nationalRevision(args[3]);nationalResponse(id,args[1],service.withdrawNationalVote(args[1],id,args[2],revision));}
                default->message(id,"/chronicle stories | history [nation 国家ID|player UUID/me] [页] | join 实例ID | choose 实例ID 节点ID 选项ID | national 实例ID | vote 实例ID 节点ID 修订号 选项ID | withdraw 实例ID 节点ID 修订号");
            }
        } catch(IllegalArgumentException error){message(id,error.getMessage());}
        return true;
    }
    private static long nationalRevision(String value){long revision=Long.parseLong(value);if(revision<0)throw new IllegalArgumentException("国家票修订号不能为负数；请刷新国家投票界面。");return revision;}
    private void nationalResponse(UUID viewer,String instance,CompletionStage<ProcessReport> operation){operation.whenComplete((report,error)->{
        if(error!=null)failure(viewer,error);else message(viewer,report.message());
        menus.national(viewer,instance); // a rejected stale token must obtain a fresh page
    });}
    private boolean memory(CommandSender sender,String[] args) {
        if(!sender.hasPermission("starchronicle.admin")){sender.sendMessage("没有管理权限。");return true;}
        if(!(sender instanceof Player player)){sender.sendMessage("请在拟设置纪念点的位置执行玩家命令。");return true;}
        UUID viewer=player.getUniqueId();tasks.track(player);
        try {
            if(args.length==2&&args[1].equalsIgnoreCase("list")) {
                query(memoryLedger::sites).whenComplete((sites,error)->{if(error!=null)failure(viewer,error);else message(viewer,sites.isEmpty()?"暂无纪念点":sites.stream().map(s->s.id()+" "+(s.enabled()?"启用":"已移除")+" 来源="+s.eventId()).collect(java.util.stream.Collectors.joining("\n")));});
            } else if(args.length==3&&args[1].equalsIgnoreCase("remove")) {
                query(()->{memoryLedger.disable(args[2]);memorySites.refresh(memoryLedger.sites());return null;}).whenComplete((ignored,error)->{if(error!=null)failure(viewer,error);else message(viewer,"纪念点已停用；未加载区块中的所属实体将在区块加载时清理。");});
            } else if(args.length>=4&&args.length<=5&&args[1].equalsIgnoreCase("create")) {
                String npc=args.length==5?args[4]:"";if(!npc.isEmpty())npcLedger.catalog().require(npc);
                Location l=player.getLocation();if(l.getBlockY()+3>=l.getWorld().getMaxHeight()||l.getBlockY()<l.getWorld().getMinHeight())throw new IllegalArgumentException("当前位置没有足够的纪念展示高度");MemorySite site=new MemorySite(args[2],l.getWorld().getUID(),l.getBlockX(),l.getBlockY(),l.getBlockZ(),"世界历史 · "+args[2],args[3],npc,true);
                query(()->{memoryLedger.create(site);memorySites.refresh(memoryLedger.sites());return null;}).whenComplete((ignored,error)->{if(error!=null)failure(viewer,error);else message(viewer,"纪念点已保存；当前区块加载时显示，点击可读真实历史。来源="+site.eventId());});
            } else message(viewer,"/chronicle memory create <点ID> <真实事件ID> [逻辑NPC ID] | memory remove <点ID> | memory list");
        }catch(IllegalArgumentException error){message(viewer,error.getMessage());}
        return true;
    }
    private boolean admin(CommandSender sender,String[] args) {
        if(!sender.hasPermission("starchronicle.admin")){sender.sendMessage("没有管理权限。");return true;}
        UUID player=sender instanceof Player p?p.getUniqueId():null;
        Consumer<String> reply=text->{if(player!=null)message(player,text);else tasks.global(()->sender.sendMessage(text));};
        switch(args[0].toLowerCase(Locale.ROOT)) {
            case "status"->reply.accept("StarCore="+gateway.available()+", stories="+definitions.size()+", workerQueue="+worker.getQueue().size());
            case "validate"->query(()->{var valid=coreContent.bind(ConfigLoader.loadWithoutDirectoryValidation(getDataFolder().toPath().resolve("stories"),gateway::hasScript,gateway::hasQuest));engine.validate(valid,MemoryRecipeCatalog.load(memoryRecipesFile));return valid;}).whenComplete((valid,error)->reply.accept(error==null?"验证通过: "+valid.size()+" 条剧情及世界记忆配方（未更改运行配置或版本目录）":"验证失败: "+root(error).getMessage()));
            case "reload"->query(()->{List<StoryDefinition> valid=coreContent.bind(ConfigLoader.loadWithoutDirectoryValidation(getDataFolder().toPath().resolve("stories"),gateway::hasScript,gateway::hasQuest));Set<String> nextPermissions=permissionNodes(withPendingDefinitions(valid),engine.activeInstances());engine.reload(valid,MemoryRecipeCatalog.load(memoryRecipesFile));permissions=nextPermissions;definitions=valid;return valid.size();}).whenComplete((count,error)->reply.accept(error==null?"已重载 "+count+" 条剧情及世界记忆配方；已有实例保留原定义与权限需求。":"重载失败，原定义保留: "+root(error).getMessage()));
            case "evaluate"->{
                if(args.length!=4){reply.accept("用法: /chronicle evaluate <已保存事件ID> <剧情ID> <精确版本>；仅登记，不立即执行");break;}
                service.evaluateHistorical(args[1],args[2],args[3]).whenComplete((report,error)->reply.accept(error==null?report.status()+"；新增受众种子="+report.newSeeds()+"；既有同源实例="+report.matchingInstances()+"（已完成="+report.completedInstances()+"）；"+report.message():"历史评估未登记: "+root(error).getMessage()));
            }
            case "uncertain"->query(()->{List<String> lines=new ArrayList<>();var items=engine.uncertain();var scenes=nativeJournal.unknown(100);lines.add("UNKNOWN外部效果="+items.size()+"，原生场景="+scenes.size()+"；请先对账，不会自动重放。");items.stream().limit(20).forEach(d->lines.add(d.outcomeId()+" "+d.type()+" "+d.target()));scenes.stream().limit(20).forEach(e->lines.add("native "+e.request().outcomeId()+" player="+e.request().playerId()+" "+e.reason()));return lines;}).whenComplete((lines,error)->{if(error!=null)reply.accept(root(error).getMessage());else lines.forEach(reply);});
            case "blocked"->query(()->{List<String> lines=new ArrayList<>();engine.triggerBlocks(100).forEach(item->lines.add("source="+item.eventId()+" story="+item.storyId()+"/"+item.version()+" reason="+item.reason()));engine.memoryRecipeBlocks(100).forEach(item->lines.add("source="+item.eventId()+" recipe="+item.recipeId()+"/"+item.version()+" reason="+item.reason()));return lines;}).whenComplete((items,error)->{if(error!=null)reply.accept(root(error).getMessage());else if(items.isEmpty())reply.accept("暂无因果循环或深度限制拦截。");else items.forEach(reply);});
            case "native-ack"->{if(args.length!=3||!args[2].equals("CONFIRM_SCENE_COMPLETED")){reply.accept("独立确认原生场景已结束/放弃后: /chronicle native-ack <outcomeId> CONFIRM_SCENE_COMPLETED");break;}query(()->{boolean done=nativeJournal.acknowledgeCompleted(args[1]);if(done)confirmNativeStart(args[1]);return done;}).whenComplete((ok,error)->reply.accept(error==null?"原生隔离关闭结果: "+ok+"；未重放任何原生操作。":root(error).getMessage()));}
            case "ack"->{if(args.length!=3||!args[2].equals("CONFIRM_APPLIED")){reply.accept("对账确认已实际应用后: /chronicle ack <outcomeId> CONFIRM_APPLIED");break;}query(()->engine.acknowledgeUnknown(args[1])).whenComplete((ok,error)->reply.accept(error==null?"确认结果: "+ok:root(error).getMessage()));}
            case "demo"->{
                if(!getConfig().getBoolean("admin-demo-enabled",false)){reply.accept("演示事件默认禁用。仅在测试服设置 admin-demo-enabled:true。");break;}
                if(player==null||args.length!=2){reply.accept("测试服玩家用法: /chronicle demo <剧情ID>");break;}
                StoryDefinition d=definitions.stream().filter(story->story.id().equals(args[1])).findFirst().orElse(null);
                if(d==null){reply.accept("未知剧情ID");break;}
                snapshot(player).thenCompose(context->service.submit(new WorldEvent("admin-demo:"+UUID.randomUUID(),d.triggers().getFirst(),Instant.now(),Set.of(player),context.nationId().isBlank()?Set.of():Set.of(context.nationId()),Map.of("region.id","old-capital","region.state","ruins","trade.value","100000","technology.stage","2"),"ADMIN_TEST","Explicit test-mode event by operator "+player))).whenComplete((report,error)->reply.accept(error==null?"测试事件已记录: "+report.message():root(error).getMessage()));
            }
            default->reply.accept("未知管理命令");
        }
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args) {
        if(args.length==1)return List.of("stories","scene","history","book","npc","fate","past","memory","join","choose","national","vote","withdraw","status","validate","reload","uncertain","ack","native-ack","blocked","evaluate","demo").stream().filter(s->s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if(args.length==2&&args[0].equalsIgnoreCase("npc"))return npcLedger.catalog().profiles().keySet().stream().sorted().toList();
        if(args.length==2&&args[0].equalsIgnoreCase("history"))return List.of("nation","player");
        if(args.length==2&&args[0].equalsIgnoreCase("demo")&&sender.hasPermission("starchronicle.admin"))return definitions.stream().map(StoryDefinition::id).filter(s->s.startsWith(args[1])).toList();
        return List.of();
    }
    @Override public void onDisable() {
        explorationIngress.seal();
        nationalVoteAuthority=NationalVoteAuthority.denying();
        if(questGates!=null)questGates.close();
        if(!running.getAndSet(false)){if(worker==null){if(engine!=null)engine.close();else if(store!=null)store.close();}if(textService!=null)textService.close();if(coreContent!=null)coreContent.close();if(gateway!=null)gateway.close();return;}
        if(poll!=null)poll.cancel();if(exploration!=null)exploration.close();if(npcs!=null)npcs.close();if(memorySites!=null)memorySites.close();if(textService!=null)textService.close();if(coreContent!=null)coreContent.close();if(gateway!=null)gateway.close();
        getServer().getServicesManager().unregisterAll(this);HandlerList.unregisterAll((Listener)this);if(menus!=null)HandlerList.unregisterAll(menus);
        // terminated() closes storage after every queued write, even when the bounded queue was full.
        if(worker!=null){worker.shutdown();try{if(!worker.awaitTermination(10,TimeUnit.SECONDS))getLogger().warning("State worker still draining; storage will close at termination; do not reload plugin");}catch(InterruptedException error){Thread.currentThread().interrupt();}}
        if(tasks!=null)tasks.close();nativeFlights.clear();
    }
}
