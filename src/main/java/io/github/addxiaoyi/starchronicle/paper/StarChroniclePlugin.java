package io.github.addxiaoyi.starchronicle.paper;

import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.api.epic.*;
import io.github.addxiaoyi.starchronicle.api.quest.*;
import io.github.addxiaoyi.starchronicle.integration.StarCoreQuestEvidenceProvider;
import io.github.addxiaoyi.starchronicle.config.*;
import io.github.addxiaoyi.starchronicle.core.*;
import io.github.addxiaoyi.starchronicle.integration.StarCoreBridge;
import io.github.addxiaoyi.starchronicle.integration.SnapshotCollector;
import io.github.addxiaoyi.starchronicle.integration.CommittedSourceCollector;
import io.github.addxiaoyi.starchronicle.integration.StarCoreAuthorityProvider;
import io.github.addxiaoyi.starchronicle.integration.StarCoreTreasuryAidProvider;
import io.github.addxiaoyi.starchronicle.integration.StarCoreEpicOperationProvider;
import io.github.addxiaoyi.starchronicle.integration.StarCoreTreasuryEpicProvider;
import io.github.addxiaoyi.starchronicle.integration.StarCoreQuestGateEpicProvider;
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
    private boolean standaloneMembership;
    private NationalAidService nationalAid;
    private EpicCampaignPlanCatalog epicPlanCatalog=EpicCampaignPlanCatalog.empty();
    private EpicCampaignLedger epicCampaigns;
    private ContributionLedger contributions;
    private volatile List<EpicOperationProvider> epicOperationProviders=List.of();
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
            boolean epicExample=getConfig().getBoolean("install-epic-strategy-example",false);
            if(epicExample) {
                if(!gateway.hasQuest("chronicle_heir_records"))throw new IllegalStateException("Epic strategy example requires chronicle_heir_records; enable install-native-example-quests on the audited Paper candidate first");
                for(String name:List.of("last-heir-strategy.yml","last-heir-strategy-aftermath.yml"))
                    if(!Files.exists(stories.resolve(name)))saveResource("stories/"+name,false);
            }
            if(!Files.exists(getDataFolder().toPath().resolve("npcs.yml")))saveResource("npcs.yml",false);
            NpcCatalog catalog=NpcCatalog.load(getDataFolder().toPath().resolve("npcs.yml"));
            Path epicCampaignDirectory=getDataFolder().toPath().resolve("epic-campaigns");Files.createDirectories(epicCampaignDirectory);
            Path bundledEpicPlan=epicCampaignDirectory.resolve("heir-strategy.yml");
            if(epicExample&&!Files.exists(bundledEpicPlan))saveResource("epic-campaigns/heir-strategy.yml",false);
            epicPlanCatalog=EpicCampaignPlanCatalog.load(epicCampaignDirectory);
            String memoryFile=getConfig().getString("memory-recipes-file","memory-recipes.yml");
            if(memoryFile==null||!memoryFile.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,79}\\.(yml|yaml|json)"))throw new IllegalArgumentException("Memory recipes require a local YAML/JSON filename");
            memoryRecipesFile=getDataFolder().toPath().resolve(memoryFile);
            if(memoryFile.equals("memory-recipes.yml")&&!Files.exists(memoryRecipesFile))saveResource("memory-recipes.yml",false);
            MemoryRecipeCatalog memoryRecipes=MemoryRecipeCatalog.load(memoryRecipesFile);
            store=new SqliteStore(getDataFolder().toPath().resolve("chronicle.db"));
            epicCampaigns=new EpicCampaignLedger(store);
            contributions=new ContributionLedger(store,Clock.systemUTC());
            firstVisits=new FirstVisitJournal(store);
            explorationRegions=ExplorationRegion.load(getConfig());
            firstVisits.registerRegions(explorationRegions.stream().collect(java.util.stream.Collectors.toMap(ExplorationRegion::id,ExplorationRegion::scope)));
            definitions=coreContent.bind(ConfigLoader.loadWithoutDirectoryValidation(stories,gateway::hasScript,gateway::hasQuest));
            validateEpicPlans(definitions,epicPlanCatalog);
            npcLedger=new NpcLedger(store,catalog);
            StarCoreNationalVoteAuthority builtinNationalAuthority=new StarCoreNationalVoteAuthority(gateway);
            standaloneMembership=!gateway.available();
            nationalVoteAuthority=builtinNationalAuthority;
            // The stable proxy reads only a volatile trusted provider. Registry/Bukkit
            // access stays outside the provider's native authority monitor and SQL lock.
            NationalVoteAuthority nationalAuthorityProxy=new NationalVoteAuthority(){
                @Override public <T>T withRepresentative(UUID actor,String nation,Authorized<T> operation)throws Exception {
                    return nationalVoteAuthority.withRepresentative(actor,nation,operation);
                }
            };
            MemberAuthority nativeMembers=new io.github.addxiaoyi.starchronicle.integration.StarCoreMemberAuthority(gateway);
            MemberAuthority memberAuthority=new MemberAuthority(){
                public <T>T withMember(UUID actor,String nation,Authorized<T> operation)throws Exception {
                    if(standaloneMembership&&!gateway.available()) {
                        if(!nation.isEmpty())throw new SecurityException("Standalone mode has no native nation authority");
                        return operation.run(new Membership(actor,"","Chronicle.standalone","Standalone no-nation membership",Instant.now()));
                    }
                    return nativeMembers.withMember(actor,nation,operation);
                }
            };
            engine=new RuleEngine(store,definitions,Clock.systemUTC(),npcLedger,nationalAuthorityProxy,memoryRecipes,epicCampaigns,memberAuthority);
            try {
                nationalAid=new NationalAidService(store,engine,new StarCoreTreasuryAidProvider(gateway),Clock.systemUTC(),command->{
                    ThreadPoolExecutor state=worker;
                    if(!running.get()||state==null)throw new RejectedExecutionException("Chronicle state worker is unavailable");
                    state.execute(command);
                });
                getLogger().info("Native StarCore treasury aid is enabled");
            } catch (RuntimeException unavailable) {
                nationalAid=null;
                getLogger().warning("Native StarCore treasury aid is disabled: "+unavailable.getMessage());
            }
            nativeJournal=new NativeSceneJournal(store,Clock.systemUTC());
            memoryLedger=new MemoryLedger(store);
            observations=new ObservationJournal(store,Clock.systemUTC());
            snapshotCollector=new SnapshotCollector(gateway,()->getServer().getServicesManager().getRegistrations(SnapshotProvider.class).stream().map(r->r.getProvider()).toList());
            textService=new TextService(Duration.ofMillis(Math.max(1,Math.min(30000,getConfig().getLong("text-timeout-ms",1500)))));
            permissions=permissionNodes(definitions);
            worker=new StateWorker(getConfig().getInt("worker-queue-capacity",2048),()->{try{explorationIngress.drain(firstVisits::observe,Integer.MAX_VALUE);}catch(RuntimeException error){getLogger().severe("Uncommitted exploration arrivals could not be saved on shutdown: "+error.getMessage());}finally{engine.close();}});
            running.set(true);
            questGates=new QuestGateDriver(engine,new QuestGateDriver.StateDispatcher(){
                @Override public <T>CompletionStage<T> submit(Callable<T> operation){return query(operation);}
            },this::nationalSnapshot,Clock.systemUTC(),Duration.ofSeconds(10),8,message->getLogger().warning(message));
            service=new Service();
            getServer().getServicesManager().register(ChronicleService.class,service,this,ServicePriority.Normal);
            var builtinAuthority=new StarCoreAuthorityProvider(gateway);
            if(builtinAuthority.available())getServer().getServicesManager().register(AuthorityProvider.class,builtinAuthority,this,ServicePriority.Lowest);
            menus=new Menus(this);getServer().getPluginManager().registerEvents(menus,this);getServer().getPluginManager().registerEvents(this,this);
            getServer().getServicesManager().register(NationalVoteAuthority.class,builtinNationalAuthority,this,ServicePriority.Lowest);
            refreshNationalVoteAuthority(null);
            if(builtinQuestEvidence.available())getServer().getServicesManager().register(QuestEvidenceProvider.class,builtinQuestEvidence,this,ServicePriority.Lowest);
            refreshQuestEvidenceProviders(null);
            registerBuiltinEpicProviders(builtinQuestEvidence,folia);
            PluginCommand command=Objects.requireNonNull(getCommand("chronicle"));command.setExecutor(this);command.setTabCompleter(this);
            gateway.start(event->service.submit(event).exceptionally(error->{getLogger().warning("Source event was not accepted: "+error.getMessage());return null;}));
            npcs=new NpcHooks(this,observed->npcInteraction(observed));if(getConfig().getBoolean("npc-hooks",true))npcs.start();
            memorySites=new MemorySites(this,(id,eventId)->query(()->memoryLedger.source(eventId)).whenComplete((event,error)->{if(error!=null)failure(id,error);else menus.event(id,event);}),observed->npcInteraction(observed));
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
    private void refreshEpicOperationProviders(RegisteredServiceProvider<?> excluded) {
        Map<String,List<EpicOperationProvider>> grouped=new TreeMap<>();
        for(var registration:getServer().getServicesManager().getRegistrations(EpicOperationProvider.class)) {
            EpicOperationProvider provider=registration.getProvider();
            if(excluded!=null&&provider==excluded.getProvider())continue;
            try {
                String id=provider.providerId(),domain=provider.authorityDomain();
                if(id==null||!id.matches("[A-Za-z0-9_.:/-]{1,256}")||domain==null||!UUID.fromString(domain).toString().equals(domain))continue;
                grouped.computeIfAbsent(id,k->new ArrayList<>()).add(provider);
            } catch(RuntimeException invalid) { getLogger().warning("Epic provider excluded: "+invalid.getMessage()); }
        }
        List<EpicOperationProvider> accepted=new ArrayList<>();
        for(var entry:grouped.entrySet()) {
            if(entry.getValue().size()!=1) { getLogger().warning("Duplicate epic provider ID quarantined: "+entry.getKey());continue; }
            accepted.add(entry.getValue().getFirst());
        }
        epicOperationProviders=List.copyOf(accepted);
    }
    @EventHandler public void epicProviderRegistered(ServiceRegisterEvent event) {
        if(running.get()&&event.getProvider().getService()==EpicOperationProvider.class)refreshEpicOperationProviders(null);
    }
    @EventHandler public void epicProviderUnregistered(ServiceUnregisterEvent event) {
        if(event.getProvider().getService()==EpicOperationProvider.class)refreshEpicOperationProviders(event.getProvider());
    }
    private void registerBuiltinEpicProviders(StarCoreQuestEvidenceProvider builtinQuestEvidence,boolean folia) {
        if(!folia) {
            for(var type:List.of(EpicOperation.Type.RESOURCE_AID,EpicOperation.Type.DIPLOMACY_INVITE,
                    EpicOperation.Type.NATION_FOUNDING,EpicOperation.Type.WAR_COMMIT,EpicOperation.Type.DUNGEON_COMPLETION))try {
                var nativeProvider=new StarCoreEpicOperationProvider(gateway,type);
                if(nativeProvider.available())getServer().getServicesManager().register(EpicOperationProvider.class,nativeProvider,this,ServicePriority.Lowest);
            } catch(RuntimeException unavailable) { getLogger().warning("Native strategic operations are disabled: "+unavailable.getMessage()); }
            try {
                var treasuryProvider=new StarCoreTreasuryEpicProvider(new StarCoreTreasuryAidProvider(gateway));
                getServer().getServicesManager().register(EpicOperationProvider.class,treasuryProvider,this,ServicePriority.Lowest);
            } catch(RuntimeException unavailable) { getLogger().warning("Treasury epic operations are disabled: "+unavailable.getMessage()); }
        }
        if(builtinQuestEvidence.available())try {
            var questProvider=new StarCoreQuestGateEpicProvider(builtinQuestEvidence.providerId(),
                    operation->query(()->engine.epicQuestEvidence(operation,builtinQuestEvidence.providerId(),
                            epicAuthorityDomain(builtinQuestEvidence.providerId()))));
            getServer().getServicesManager().register(EpicOperationProvider.class,questProvider,this,ServicePriority.Lowest);
        } catch(RuntimeException unavailable) { getLogger().warning("Quest epic evidence is disabled: "+unavailable.getMessage()); }
        refreshEpicOperationProviders(null);
    }
    private static String epicAuthorityDomain(String providerId) {
        int separator=providerId.lastIndexOf(':');
        if(separator<0)throw new IllegalArgumentException("Epic provider ID has no authority domain");
        String domain=providerId.substring(separator+1);
        if(!UUID.fromString(domain).toString().equals(domain))throw new IllegalArgumentException("Epic provider domain is not canonical");
        return domain;
    }
    private EpicOperationProvider epicProvider(String providerId,String domain) {
        return epicOperationProviders.stream().filter(provider->provider.providerId().equals(providerId)
                        &&provider.authorityDomain().equals(domain)).findFirst()
                .orElseThrow(()->new IllegalStateException("Pinned epic provider is unavailable: "+providerId+"/"+domain));
    }
    private EpicOperationProvider epicProvider(String providerId) {
        return epicOperationProviders.stream().filter(provider->provider.providerId().equals(providerId)).findFirst()
                .orElseThrow(()->new IllegalArgumentException("Unknown epic provider: "+providerId));
    }
    private static void validateEpicPlans(List<StoryDefinition> definitions,EpicCampaignPlanCatalog catalog) {
        for(EpicCampaignPlan plan:catalog.plans()) {
            StoryDefinition story=definitions.stream().filter(candidate->candidate.id().equals(plan.storyId())
                    &&candidate.version().equals(plan.storyVersion())).findFirst()
                    .orElseThrow(()->new IllegalArgumentException("Epic plan has no matching story version: "+plan.campaignId()));
            if(!story.triggers().contains(plan.signupSource())||story.scope()!=StoryDefinition.Scope.WORLD
                    ||story.voteUnit()!=StoryDefinition.VoteUnit.NATION)
                throw new IllegalArgumentException("Epic signup story must be WORLD scope with a national ballot: "+plan.storyId());
        }
    }
    private void pollSources() {
        snapshots().thenCompose(contexts->query(()->{
            nativeJournal.isolateOrphans(nativeFlights.players());
            gateway.scanCommittedSources();
            engine.recoverEpicContributions(50);
            var committed=new CommittedSourceCollector(gateway);
            for(var source:CommittedSourceCollector.Source.values())try {
                var page=committed.collect(source,engine.committedSourceCursor(source.id));
                if(page.isPresent())engine.acceptCommittedSource(page.get(),contexts);
            } catch(ReflectiveOperationException|RuntimeException failure) {
                getLogger().warning("Committed history cursor retained for "+source.id+": "+root(failure).getMessage());
            }
            for(var snapshot:snapshotCollector.collect()) {
                observations.observe(snapshot);
                if(snapshot.namespace().equals("starcore.nations") && snapshot.complete()
                        && (snapshot.allowEmpty() || !snapshot.entries().isEmpty()))
                    engine.observeMissingNations(snapshot.entries().keySet());
            }
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
    private Set<String> permissionNodes(List<StoryDefinition> configured) {
        Set<String> nodes=new HashSet<>(Set.of("starchronicle.use","starchronicle.admin",io.github.addxiaoyi.starchronicle.api.aid.AidRequest.PERMISSION,
                "starcore.resource","starcore.nation.ally.add","starcore.nation.create","starcore.nation.war.declare"));
        nodes.addAll(engine.permissionNodes(configured));return Set.copyOf(nodes);
    }
    /** The consumer runs only on the entity scheduler, and receives no offline player. */
    void present(UUID id,Consumer<Player> display) {if(!running.get())return;tasks.playerOwned(id,p->{if(running.get()&&p.isOnline()){gateway.refreshContext(p,permissions);display.accept(p);}},()->{});}
    /** Observation-driven views must survive both the query wait and the final display queue. */
    void present(Player expected,BooleanSupplier current,Consumer<Player> display) {
        if(!running.get()||!current.getAsBoolean())return;
        tasks.playerOwned(expected.getUniqueId(),p->{
            if(p!=expected||!running.get()||!current.getAsBoolean()||!p.isOnline()||!p.hasPermission("starchronicle.use"))return;
            gateway.refreshContext(p,permissions);
            if(running.get()&&current.getAsBoolean())display.accept(p);
        },()->{});
    }
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
        return snapshot(id,null,()->true);
    }
    private CompletionStage<PlayerContext> snapshot(UUID id,Player expected,BooleanSupplier current) {
        CompletableFuture<PlayerContext> result=new CompletableFuture<>();
        if(!running.get()){result.completeExceptionally(new IllegalStateException("Plugin is stopping"));return result;}
        try{tasks.playerOwned(id,p->{
            if(result.isDone())return;
            if(expected!=null&&p!=expected||!current.getAsBoolean()){result.completeExceptionally(new CancellationException("Interaction session or provider changed"));return;}
            if(!running.get()){result.completeExceptionally(new IllegalStateException("Plugin is stopping"));return;}
            if(!p.isOnline()){result.completeExceptionally(new IllegalStateException("Player is offline"));return;}
            try{
                PlayerContext captured;
                if(expected!=null)captured=gateway.captureContext(p,permissions);
                else {gateway.refreshContext(p,permissions);captured=gateway.context(id);}
                if(exploration!=null)exploration.observe(p);result.complete(captured);
            }catch(Throwable error){result.completeExceptionally(error);}
        },()->result.completeExceptionally(new IllegalStateException("Player scheduler retired")));}catch(RuntimeException scheduling){result.completeExceptionally(scheduling);}
        return result.orTimeout(5,TimeUnit.SECONDS);
    }
    private CompletionStage<List<PlayerContext>> snapshots() {
        return snapshotsExcept(null);
    }
    private CompletionStage<List<PlayerContext>> snapshotsExcept(UUID excluded) {
        List<CompletableFuture<PlayerContext>> all=tasks.onlineIds().stream().filter(id->!id.equals(excluded)).map(id->snapshot(id).exceptionally(error->null).toCompletableFuture()).toList();
        return CompletableFuture.allOf(all.toArray(CompletableFuture[]::new)).thenApply(ignored->all.stream().map(CompletableFuture::join).filter(Objects::nonNull).toList());
    }
    private CompletionStage<PlayerContext> nationalSnapshot(UUID player){return snapshot(player).thenApply(context->{
        if(!context.permitted("starchronicle.use"))throw new SecurityException("缺少编年史使用权限");
        return context;
    });}
    private <T>T withEpicRepresentative(PlayerContext context,Callable<T> operation)throws Exception {
        return withEpicAuthority(context,view->operation.call());
    }
    private <T>T withEpicAuthority(PlayerContext context,NationalVoteAuthority.Authorized<T> operation)throws Exception {
        if(!context.permitted("starchronicle.use"))throw new SecurityException("缺少编年史使用权限");
        Thread owner=Thread.currentThread();AtomicBoolean active=new AtomicBoolean(true),invoked=new AtomicBoolean();
        Object[] result={null};Throwable[] failure={null};
        try {
            nationalVoteAuthority.withRepresentative(context.playerId(),context.nationId(),view->{
                if(!active.get()||Thread.currentThread()!=owner||!invoked.compareAndSet(false,true))
                    throw new SecurityException("National authority callback must be synchronous and single-use");
                try {
                    if(!context.playerId().equals(view.actorId())||!context.nationId().equals(view.nationId()))
                        throw new SecurityException("Native national authority identity changed");
                    T value=operation.run(view);result[0]=value;return value;
                } catch(Exception|Error error) { failure[0]=error;throw error; }
            });
            if(failure[0]!=null)throw new IllegalStateException("National authority suppressed a failed callback",failure[0]);
            if(!invoked.get())throw new SecurityException("National authority did not invoke the authorization callback");
            @SuppressWarnings("unchecked") T value=(T)result[0];return value;
        } finally { active.set(false); }
    }
    private static String epicPermission(EpicOperation.Type type) {
        return switch(type) {
            case TREASURY_AID -> io.github.addxiaoyi.starchronicle.api.aid.AidRequest.PERMISSION;
            case RESOURCE_AID -> "starcore.resource";
            case DIPLOMACY_INVITE,DIPLOMACY_ACCEPT -> "starcore.nation.ally.add";
            case NATION_FOUNDING -> "starcore.nation.create";
            case WAR_COMMIT -> "starcore.nation.war.declare";
            case FINAL_BATTLE -> "starchronicle.use";
            case QUEST_GATE,DUNGEON_COMPLETION -> "starchronicle.use";
        };
    }
    private boolean epicMember(EpicSignup signup,PlayerContext actor,boolean allowRelief) {
        if(signup.seats().values().stream().anyMatch(seat->seat.status()==EpicSignup.SeatStatus.ACTIVE
                &&seat.nationId().equals(actor.nationId())))return true;
        return allowRelief&&signup.reliefNationId().equals(actor.nationId());
    }
    private CompletionStage<EpicCampaignState> launchEpicCampaign(EpicSignup signup) {
        Set<UUID> participants=new TreeSet<>();Set<String> nations=new TreeSet<>();
        signup.activeSeats().forEach(seat->{participants.add(seat.representativeId());nations.add(seat.nationId());});
        Map<String,String> facts=Map.of(signup.plan().signupFact(),signup.plan().signupValue(),
                signup.plan().reliefNationFact(),signup.reliefNationId(),"epic.signup_session",signup.sessionId());
        WorldEvent event=new WorldEvent(signup.sealedEventId(),signup.plan().signupSource(),signup.updatedAt(),
                participants,nations,facts,"StarChronicle.EpicSignup","sealed session="+signup.sessionId()+"; nations="+nations.size());
        return service.submit(event).thenCompose(report->query(()->{
            Set<String> proposed=new HashSet<>(report.instanceIds());
            StoryInstance instance=engine.activeInstances().stream()
                    .filter(candidate->candidate.sourceEventId().equals(event.id())
                            &&candidate.definition().id().equals(signup.plan().storyId())
                            &&candidate.definition().version().equals(signup.plan().storyVersion())
                            &&(proposed.isEmpty()||proposed.contains(candidate.id())))
                    .findFirst().orElseGet(()->engine.activeInstances().stream()
                            .filter(candidate->candidate.sourceEventId().equals(event.id())
                                    &&candidate.definition().id().equals(signup.plan().storyId())
                                    &&candidate.definition().version().equals(signup.plan().storyVersion()))
                            .findFirst().orElseThrow(()->new IllegalStateException("Epic signup event created no matching story instance")));
            return epicCampaigns.start(instance.id(),signup.sessionId(),event.id(),signup.updatedAt());
        }));
    }
    private CompletionStage<EpicOperationState> runEpicOperation(String operationId,UUID actorId,long expectedRevision,boolean reconcile) {
        return nationalSnapshot(actorId).thenCompose(context->query(()->{
            EpicOperationState state=epicCampaigns.operation(operationId);
            if(state==null)throw new IllegalArgumentException("Unknown epic operation: "+operationId);
            if(state.revision()!=expectedRevision)throw new IllegalStateException("Epic operation revision changed; refresh status");
            boolean founding=state.operation().type()==EpicOperation.Type.NATION_FOUNDING;
            boolean foundingIdentity=founding&&(context.nationId().isBlank()
                    ||reconcile&&state.operation().target().equals(context.nationId()));
            if(!state.operation().actorId().equals(actorId)||!foundingIdentity&&!state.operation().nationId().equals(context.nationId()))
                throw new SecurityException("Epic operation belongs to another current actor or nation");
            if(!context.permitted(state.operation().permission()))throw new SecurityException("Missing native operation permission: "+state.operation().permission());
            if(!reconcile&&state.status()!=EpicOperationState.Status.PREPARED)
                throw new IllegalStateException("Only a prepared epic operation may be applied; UNKNOWN requires reconcile");
            if(reconcile&&state.status()!=EpicOperationState.Status.UNKNOWN&&state.status()!=EpicOperationState.Status.PREPARED)
                throw new IllegalStateException("Only PREPARED or UNKNOWN epic operations may be reconciled");
            EpicCampaignLedger.ProviderPin pin=epicCampaigns.providerPin(operationId);
            if(pin==null)throw new IllegalStateException("Epic operation has no durable provider pin");
            EpicOperationProvider provider=epicProvider(pin.providerId(),pin.authorityDomain());
            Callable<Void> reserve=()->{
                if(!reconcile)engine.validateEpicContributionIntent(state.operation());
                if(!reconcile)epicCampaigns.claimAttempt(operationId,expectedRevision,Instant.now());
                return null;
            };
            try { if(foundingIdentity)reserve.call();else withEpicRepresentative(context,reserve); }
            catch(Exception unavailable) { throw new SecurityException("Current national authority is unavailable",unavailable); }
            return new EpicProviderCall(state.operation(),pin,provider,epicOperationProviders);
        })).thenCompose(call->{
            CompletionStage<EpicObservation> nativeCall;
            try {
                requireEpicLease(call);
                nativeCall=reconcile?call.provider().lookup(call.operation()):call.provider().apply(call.operation(),()->epicLeaseCurrent(call));
                if(nativeCall==null)throw new IllegalStateException("Epic provider returned no completion stage");
            }
            catch(Throwable failure) { nativeCall=CompletableFuture.completedFuture(EpicObservation.unknown("Epic provider call failed: "+root(failure).getMessage())); }
            return nativeCall.toCompletableFuture().orTimeout(30,TimeUnit.SECONDS)
                    .handle((observation,error)->error==null&&observation!=null?observation:
                    EpicObservation.unknown("Epic provider returned no reliable observation: "+(error==null?"empty response":root(error).getMessage())))
                    .thenCompose(observation->query(()->{
                        requireEpicLease(call);
                        return engine.observeEpicOperation(call.operation(),observation,
                            call.pin().providerId(),call.pin().authorityDomain());
                    }));
        });
    }
    private record EpicProviderCall(EpicOperation operation,EpicCampaignLedger.ProviderPin pin,EpicOperationProvider provider,
                                    List<EpicOperationProvider> registry) {}
    private void requireEpicLease(EpicProviderCall call) {
        if(!epicLeaseCurrent(call))
            throw new IllegalStateException("Epic provider lease expired; the operation requires reconciliation");
    }
    private boolean epicLeaseCurrent(EpicProviderCall call) {
        try { return running.get()&&epicOperationProviders==call.registry()
                &&epicProvider(call.pin().providerId(),call.pin().authorityDomain())==call.provider(); }
        catch(RuntimeException unavailable) { return false; }
    }
    private static boolean epicOperationMember(EpicSignup signup,EpicCampaignState campaign,PlayerContext context,
                                               EpicOperation.Type type) {
        if(signup.activeSeats().stream().anyMatch(seat->seat.nationId().equals(context.nationId())))return true;
        if(type==EpicOperation.Type.DIPLOMACY_ACCEPT&&signup.reliefNationId().equals(context.nationId()))return true;
        return (type==EpicOperation.Type.WAR_COMMIT||type==EpicOperation.Type.FINAL_BATTLE)
                &&campaign.operations().values().stream().anyMatch(state->state.status()==EpicOperationState.Status.COMMITTED
                &&state.operation().type()==EpicOperation.Type.NATION_FOUNDING
                &&state.operation().target().equals(context.nationId())
                &&state.operation().actorId().equals(context.playerId()));
    }
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
    private void npcInteraction(NpcHooks.Interaction observed) {
        UUID id=observed.player().getUniqueId();
        observeNpcInteraction(observed).whenComplete((logical,error)->{
            if(!running.get()||!observed.current().getAsBoolean())return;
            tasks.playerOwned(id,player->{
                if(player!=observed.player()||!player.isOnline()||!player.hasPermission("starchronicle.use")||!observed.current().getAsBoolean())return;
                if(error!=null){if(!(root(error) instanceof CancellationException)){
                    getLogger().warning("NPC interaction failed: "+root(error).getClass().getSimpleName());
                    player.sendMessage(Component.text("[编年史] NPC 交互未完成，请重试。"));
                }}
                else if(logical==null)menus.stories(observed);else menus.npc(observed,logical);
            },()->{});
        });
    }
    /** An observed interaction is admitted only while its provider lease and captured player are valid. */
    CompletionStage<String> observeNpcInteraction(NpcHooks.Interaction observed) {
        UUID player=observed.player().getUniqueId();
        String npcId=observed.npcId();Instant observedAt=observed.observedAt();
        BooleanSupplier current=observed.current();
        if(!running.get()||!current.getAsBoolean())return CompletableFuture.failedFuture(new CancellationException("NPC bridge is no longer active"));
        String logical=npcLedger.catalog().profiles().containsKey(npcId)?npcId:npcLedger.catalog().resolveExternal(npcId).orElse(null);
        return snapshot(player,observed.player(),current).thenCompose(context->{
            if(!context.permitted("starchronicle.use"))return CompletableFuture.failedFuture(new SecurityException("缺少编年史使用权限"));
            if(logical==null)return CompletableFuture.completedFuture(null);
            WorldEvent event=new WorldEvent("npc:"+UUID.randomUUID(),"NPC_INTERACT",observedAt,Set.of(player),context.nationId().isBlank()?Set.of():Set.of(context.nationId()),Map.of("npc.id",logical,"npc.binding",npcId,"time_basis","OBSERVED"),"NPC_BRIDGE","Observed uncancelled interaction; player="+player+"; npc="+logical+"; binding="+npcId);
            // Preserve the original actor context; an audience refresh must not substitute a reconnect.
            return snapshotsExcept(player).thenCompose(contexts->query(()->{
                if(!running.get()||!current.getAsBoolean())throw new CancellationException("NPC bridge changed before admission");
                List<PlayerContext> captured=new ArrayList<>(contexts);captured.add(context);
                engine.accept(event,audience(event,captured));deliverPending();return logical;
            }));
        });
    }
    private final class Service implements ChronicleService {
        @Override public CompletionStage<ProcessReport> submit(WorldEvent event){Objects.requireNonNull(event);return snapshots().thenCompose(contexts->query(()->{ProcessReport result=engine.accept(event,audience(event,contexts));deliverPending();return result;}));}
        @Override public CompletionStage<ProcessReport> submitContributions(WorldEvent event,List<io.github.addxiaoyi.starchronicle.api.contribution.ContributionRequest> entries){
            Objects.requireNonNull(event);var frozen=List.copyOf(entries);
            return snapshots().thenCompose(contexts->query(()->{var report=engine.acceptContributions(event,frozen,audience(event,contexts));deliverPending();return report;}));
        }
        @Override public CompletionStage<List<io.github.addxiaoyi.starchronicle.api.contribution.ContributionEntry>> contributions(String subject,io.github.addxiaoyi.starchronicle.api.contribution.ContributionType type,int page,int size){return query(()->engine.contributions(subject,type,page,size));}
        @Override public CompletionStage<List<EpicCampaignPlan>> epicCampaignPlans(){return query(()->epicPlanCatalog.plans());}
        @Override public CompletionStage<EpicSignup> openEpicSignup(String planId,String sessionId,String reliefNationId,UUID actorId){
            return snapshot(actorId).thenCompose(context->query(()->{
                if(!context.permitted("starchronicle.admin"))throw new SecurityException("Opening an epic signup requires administrator permission");
                EpicCampaignPlan plan=epicPlanCatalog.plan(planId);
                return epicCampaigns.open(plan,sessionId,reliefNationId,Instant.now());
            }));
        }
        @Override public CompletionStage<EpicSignup> epicSignup(String sessionId){return query(()->{
            EpicSignup signup=epicCampaigns.signup(sessionId);if(signup==null)throw new IllegalArgumentException("Unknown epic signup: "+sessionId);return signup;
        });}
        @Override public CompletionStage<EpicSignup> enrollEpicSignup(String sessionId,UUID actorId,long expectedRevision){
            return nationalSnapshot(actorId).thenCompose(context->query(()->withEpicRepresentative(context,()->
                    epicCampaigns.enroll(sessionId,context.nationId(),actorId,expectedRevision,Instant.now()))));
        }
        @Override public CompletionStage<EpicSignup> withdrawEpicSignup(String sessionId,UUID actorId,long expectedSeatRevision,long expectedSignupRevision){
            return nationalSnapshot(actorId).thenCompose(context->query(()->withEpicRepresentative(context,()->
                    epicCampaigns.withdraw(sessionId,context.nationId(),actorId,expectedSeatRevision,expectedSignupRevision,Instant.now()))));
        }
        @Override public CompletionStage<EpicCampaignState> sealEpicSignup(String sessionId,UUID actorId,long expectedRevision){
            return nationalSnapshot(actorId).thenCompose(context->query(()->withEpicAuthority(context,view->{
                    EpicSignup signup=epicCampaigns.signup(sessionId);
                    if(signup==null||!epicMember(signup,context,false))throw new SecurityException("Only an enrolled nation may seal the roster");
                    if(!view.nationExists(signup.reliefNationId()))throw new IllegalStateException("Relief nation no longer exists");
                    for(var seat:signup.activeSeats())if(!view.nationExists(seat.nationId()))
                        throw new IllegalStateException("Enrolled nation no longer exists: "+seat.nationId());
                    return epicCampaigns.seal(sessionId,expectedRevision,actorId,Instant.now());
                })))
                    .thenCompose(StarChroniclePlugin.this::launchEpicCampaign);
        }
        @Override public CompletionStage<EpicCampaignState> epicCampaign(String instanceId,UUID actorId){
            return snapshot(actorId).thenCompose(context->query(()->{
                EpicCampaignState campaign=epicCampaigns.state(instanceId);
                EpicSignup signup=epicCampaigns.signup(campaign.sessionId());
                if(!epicMember(signup,context,true)&&!campaign.operations().values().stream().anyMatch(state->
                        state.operation().actorId().equals(actorId)))throw new SecurityException("Campaign status is limited to its frozen participants");
                return campaign;
            }));
        }
        @Override public CompletionStage<EpicCampaignState> voteEpicRoute(String instanceId,UUID actorId,long expectedRevision,String route){
            return nationalSnapshot(actorId).thenCompose(context->query(()->{
                EpicCampaignState campaign=epicCampaigns.state(instanceId);
                EpicSignup signup=epicCampaigns.signup(campaign.sessionId());
                if(!epicMember(signup,context,false))throw new SecurityException("Only a frozen participating nation may vote on the epic route");
                return withEpicRepresentative(context,()->epicCampaigns.castRouteVote(instanceId,context.nationId(),expectedRevision,route,Instant.now()));
            }));
        }
        @Override public CompletionStage<EpicOperationState> prepareEpicOperation(String instanceId,UUID actorId,
                    EpicOperation.Type type,String target,String nativeReference,String providerId){
            Objects.requireNonNull(type);Objects.requireNonNull(target);Objects.requireNonNull(nativeReference);Objects.requireNonNull(providerId);
            return nationalSnapshot(actorId).thenCompose(context->query(()->{
                EpicCampaignState campaign=epicCampaigns.state(instanceId);
                EpicSignup signup=epicCampaigns.signup(campaign.sessionId());
                if(!epicOperationMember(signup,campaign,context,type))throw new SecurityException("Current nation is outside the frozen campaign roles");
                String permission=epicPermission(type);
                if(!context.permitted(permission))throw new SecurityException("Missing native operation permission: "+permission);
                EpicOperationProvider provider=epicProvider(providerId);
                Instant requested=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
                if(type==EpicOperation.Type.FINAL_BATTLE||type==EpicOperation.Type.DUNGEON_COMPLETION)
                    requested=campaign.updatedAt(); // Final evidence must postdate the frozen route approval.
                if(type==EpicOperation.Type.QUEST_GATE)requested=engine.epicQuestGateOpenedAt(instanceId,nativeReference,actorId,context.nationId(),target)
                        .orElseThrow(()->new IllegalStateException("The matching tracked national task gate has not opened"));
                String action=type.name()+":"+target+":"+nativeReference;
                String operationId=EpicContract.operationId(campaign.plan().campaignId(),instanceId,campaign.phaseId(),action,context.nationId());
                EpicOperationState existing=epicCampaigns.operation(operationId);
                if(existing!=null)requested=existing.operation().requestedAt();
                StoryInstance epicStory=engine.activeInstances().stream().filter(candidate->candidate.id().equals(instanceId))
                        .findFirst().orElseThrow(()->new IllegalArgumentException("Epic story instance is not active"));
                EpicOperation operation=new EpicOperation(operationId,campaign.plan().campaignId(),instanceId,
                        epicStory.sourceEventId(),campaign.phaseId(),type,actorId,context.nationId(),target,nativeReference,
                        DefinitionCanonicalCodec.fingerprintV2(epicStory.definition()),
                        permission,requested);
                EpicContributions.quantities(operation);
                if(!provider.supports(operation))throw new IllegalArgumentException("Epic provider does not support this operation or its native capability is unavailable");
                engine.validateEpicContributionIntent(operation);
                return withEpicRepresentative(context,()->epicCampaigns.prepare(operation,provider.providerId(),provider.authorityDomain()));
            }));
        }
        @Override public CompletionStage<EpicOperationState> epicOperation(String operationId,UUID actorId){
            return snapshot(actorId).thenCompose(context->query(()->{
                EpicOperationState state=epicCampaigns.operation(operationId);
                if(state==null)throw new IllegalArgumentException("Unknown epic operation: "+operationId);
                EpicCampaignState campaign=epicCampaigns.state(state.operation().instanceId());
                EpicSignup signup=epicCampaigns.signup(campaign.sessionId());
                if(!epicMember(signup,context,true)&&!state.operation().actorId().equals(actorId))
                    throw new SecurityException("Operation status is limited to its campaign participants");
                return state;
            }));
        }
        @Override public CompletionStage<EpicOperationState> applyEpicOperation(String operationId,UUID actorId,long expectedRevision){
            return runEpicOperation(operationId,actorId,expectedRevision,false);
        }
        @Override public CompletionStage<EpicOperationState> reconcileEpicOperation(String operationId,UUID actorId,long expectedRevision){
            return runEpicOperation(operationId,actorId,expectedRevision,true);
        }
        @Override public CompletionStage<List<String>> epicProviders(){return query(()->epicOperationProviders.stream()
                .map(EpicOperationProvider::providerId).distinct().sorted().toList());}
        @Override public CompletionStage<NationalAidService.Result> submitAid(io.github.addxiaoyi.starchronicle.api.aid.AidRequest request){
            if(request==null)return CompletableFuture.failedFuture(new NullPointerException("request"));
            NationalAidService aid=nationalAid;if(aid==null)return CompletableFuture.failedFuture(new IllegalStateException("National aid is disabled"));
            return snapshot(request.actorId()).thenCombine(snapshots(),(actor,contexts)->aid.submit(request,actor,contexts)).thenCompose(Function.identity());
        }
        @Override public CompletionStage<NationalAidService.Result> reconcileAid(io.github.addxiaoyi.starchronicle.api.aid.AidRequest request){
            if(request==null)return CompletableFuture.failedFuture(new NullPointerException("request"));
            NationalAidService aid=nationalAid;if(aid==null)return CompletableFuture.failedFuture(new IllegalStateException("National aid is disabled"));
            return snapshot(request.actorId()).thenCombine(snapshots(),(actor,contexts)->aid.reconcile(request,actor,contexts)).thenCompose(Function.identity());
        }
        @Override public CompletionStage<HistoricalEvaluationReport> evaluateHistorical(String source,String story,String version){return query(()->{var report=engine.evaluateHistorical(source,story,version);permissions=permissionNodes(definitions);return report;});}
        @Override public CompletionStage<ProcessReport> choose(String id,UUID player,String choice){return snapshot(player).thenCompose(context->query(()->{ProcessReport result=engine.choose(id,player,choice,context);deliverPending();return result;}));}
        @Override public CompletionStage<ProcessReport> choose(String id,UUID player,String expected,String choice){return snapshot(player).thenCompose(context->query(()->{ProcessReport result=engine.choose(id,player,expected,choice,context);deliverPending();return result;}));}
        @Override public CompletionStage<ProcessReport> nationalVote(String id,UUID player,String expected,long revision,String choice){return nationalSnapshot(player).thenCompose(context->query(()->{ProcessReport result=engine.nationalVote(id,player,expected,revision,choice,context);deliverPending();return result;}));}
        @Override public CompletionStage<ProcessReport> withdrawNationalVote(String id,UUID player,String expected,long revision){return nationalSnapshot(player).thenCompose(context->query(()->{ProcessReport result=engine.withdrawNationalVote(id,player,expected,revision,context);deliverPending();return result;}));}
        @Override public CompletionStage<QuestGateState> questGateState(String id,UUID player){return nationalSnapshot(player).thenCompose(context->query(()->engine.questGateSnapshot(id,context)));}
        @Override public CompletionStage<QuestGateState.GateSummary> reconcileQuestGate(String gateId,UUID player,int attempt){return questGates.recover(gateId,player,attempt,false).thenApply(QuestGateState::summary);}
        @Override public CompletionStage<QuestGateState.GateSummary> retryQuestGate(String gateId,UUID player,int attempt){return questGates.recover(gateId,player,attempt,true).thenApply(QuestGateState::summary);}
        @Override public CompletionStage<NationalVoteState> nationalState(String id,UUID player){return nationalSnapshot(player).thenCompose(context->query(()->{NationalVoteState result=engine.nationalState(id,context);deliverPending();return result;}));}
        @Override public CompletionStage<ProcessReport> join(String id,UUID player){return nationalSnapshot(player).thenCompose(context->query(()->engine.join(id,context)));}
        @Override public CompletionStage<List<MemberReviewLedger.Review>> memberReviews(UUID player,int limit){return nationalSnapshot(player).thenCompose(context->query(()->{engine.observeMembers(List.of(context));return engine.memberReviews(player,limit);}));}
        @Override public CompletionStage<Optional<MemberReviewLedger.Review>> memberReview(String id,UUID player){return nationalSnapshot(player).thenCompose(context->query(()->engine.memberReview(id,context)));}
        @Override public CompletionStage<ProcessReport> revalidateMember(String id,UUID player){return nationalSnapshot(player).thenCompose(context->query(()->engine.revalidateMember(id,context)));}
        @Override public CompletionStage<ProcessReport> exitMember(String id,UUID player,String expectedNode){
            if(expectedNode==null||expectedNode.isBlank())return CompletableFuture.failedFuture(new IllegalArgumentException("退出操作需要当前节点"));
            return nationalSnapshot(player).thenCompose(context->query(()->engine.exitMember(id,expectedNode,context)));
        }
        @Override public CompletionStage<ProcessReport> exitMember(String id,UUID player){return nationalSnapshot(player).thenCompose(context->query(()->engine.exitMember(id,context)));}
        @Override public CompletionStage<java.math.BigDecimal> contributionTotal(String subject,io.github.addxiaoyi.starchronicle.api.contribution.ContributionType type,String unit){return query(()->engine.contributionTotal(subject,type,unit));}
        @Override public CompletionStage<List<StoryInstance>> instances(UUID player){return snapshot(player).thenCompose(context->query(()->{engine.resumeTriggers(List.of(context));return engine.instances(player);}));}
        @Override public CompletionStage<List<StoryInstance>> instances(UUID player,int page,int size){return snapshot(player).thenCompose(context->query(()->{engine.resumeTriggers(List.of(context));return engine.instances(player,page,size);}));}
        @Override public CompletionStage<Optional<StoryInstance>> instance(UUID player,String id){return snapshot(player).thenCompose(context->query(()->{engine.resumeTriggers(List.of(context));return engine.instance(player,id);}));}
        @Override public CompletionStage<StoryPage> storyPage(UUID player,String afterId,int size){return snapshot(player).thenCompose(context->query(()->{engine.resumeTriggers(List.of(context));return engine.storyPage(context,afterId,size);}));}
        @Override public CompletionStage<List<StoryInstance>> activeInstances(){return query(engine::activeInstances);}
        @Override public CompletionStage<List<StoryInstance>> availableInstances(UUID player){return snapshot(player).thenCompose(context->query(()->engine.availableInstances(context)));}
        @Override public CompletionStage<Map<String,StoryDefinition.Choice>> availableChoices(String id,UUID player){return snapshot(player).thenCompose(context->query(()->engine.availableChoices(id,context)));}
        @Override public CompletionStage<List<WorldEvent>> history(String nation,UUID player,int page,int size){return query(()->engine.history(nation,player,page,size));}
        @Override public CompletionStage<HistoryPage> historyPage(String nation,UUID player,String beforeEventId,int size){return query(()->engine.historyPage(nation,player,beforeEventId,size));}
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
        if(args.length>0 && args[0].equalsIgnoreCase("epic")) {
            if(!(sender instanceof Player player)){sender.sendMessage("国家史诗操作需要在线国家代表；管理员也可在游戏内开设报名。");return true;}
            tasks.track(player);gateway.refreshContext(player,permissions);epic(player.getUniqueId(),args);return true;
        }
        if(args.length>0 && Set.of("validate","reload","status","uncertain","ack","native-ack","blocked","evaluate","demo").contains(args[0].toLowerCase(Locale.ROOT)))return admin(sender,args);
        if(!(sender instanceof Player p)){sender.sendMessage("玩家命令: /chronicle [history|stories|join|choose|national|vote|withdraw|epic]；控制台: /chronicle status|validate|uncertain");return true;}
        UUID id=p.getUniqueId();tasks.track(p);gateway.refreshContext(p,permissions);
        try {
            String action=args.length==0?"stories":args[0].toLowerCase(Locale.ROOT);
            switch(action) {
                case "stories"->menus.stories(id);
                case "quest-reconcile","quest-retry"->{
                    if(args.length!=3)throw new IllegalArgumentException("用法: /chronicle "+action+" <门槛ID> <当前轮次>");
                    int attempt=Integer.parseInt(args[2]);if(attempt<1)throw new IllegalArgumentException("轮次从 1 开始");
                    var recovery=action.equals("quest-retry")?service.retryQuestGate(args[1],id,attempt):service.reconcileQuestGate(args[1],id,attempt);
                    recovery.whenComplete((gate,error)->{if(error!=null)failure(id,error);else message(id,"任务轮次="+gate.attempt()+" 状态="+Menus.questGateStatus(gate.status())+"；"+gate.message());});
                }
                case "scene"->menus.scene(id);
                case "npc"->{if(args.length!=2)throw new IllegalArgumentException("用法: /chronicle npc <逻辑NPC ID>");menus.npc(id,args[1]);}
                case "fate"->menus.fate(id);
                case "contributions"->{
                    boolean named=args.length>1&&Set.of("player","nation").contains(args[1].toLowerCase(Locale.ROOT));
                    if(args.length>3||args.length==3&&!named)throw new IllegalArgumentException("用法: /chronicle contributions [player|nation] [页]");
                    int page=args.length==3?Integer.parseInt(args[2])-1:args.length==2&&!named?Integer.parseInt(args[1])-1:0;if(page<0)throw new IllegalArgumentException("页码从 1 开始");
                    boolean nation=args.length>1&&args[1].equalsIgnoreCase("nation");
                    nationalSnapshot(id).thenCompose(context->{
                        if(nation&&context.nationId().isBlank())throw new IllegalArgumentException("当前没有国家");
                        return service.contributions(nation?"nation:"+context.nationId():"player:"+id,null,page,10);
                    }).whenComplete((entries,error)->{if(error!=null)failure(id,error);else message(id,entries.isEmpty()?"此页暂无已核验贡献。":entries.stream().map(e->e.request().type()+" · "+e.request().amount().toPlainString()+" "+e.request().unit()+" · "+e.sourceStatus()+" · "+e.request().committedAt()+" · 来源 "+e.request().sourceEventId()).collect(java.util.stream.Collectors.joining("\n")));});
                }
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
                case "join"->{if(args.length!=2)throw new IllegalArgumentException("用法: /chronicle join <实例ID>");service.join(args[1],id).whenComplete((report,error)->{if(error!=null)failure(id,error);else{message(id,report.message());menus.stories(id);}});}
                case "membership"->{if(args.length!=2)throw new IllegalArgumentException("用法: /chronicle membership <实例ID>");menus.membership(id,args[1]);}
                case "reviews"->{if(args.length!=1)throw new IllegalArgumentException("用法: /chronicle reviews");service.memberReviews(id,50).whenComplete((reviews,error)->{
                    if(error!=null)failure(id,error);else message(id,reviews.isEmpty()?"暂无成员复核记录。":reviews.stream().map(r->r.instanceId()+" · "+r.status()+" · "+r.reason()).collect(java.util.stream.Collectors.joining("\n")));
                });}
                case "revalidate","review"->{if(args.length!=2)throw new IllegalArgumentException("用法: /chronicle revalidate <实例ID>");service.revalidateMember(args[1],id).whenComplete((report,error)->{if(error!=null)failure(id,error);else message(id,report.message());});}
                case "leave"->{if(args.length!=2&&args.length!=3)throw new IllegalArgumentException("用法: /chronicle leave <实例ID> [当前节点ID]");(args.length==3?service.exitMember(args[1],id,args[2]):service.exitMember(args[1],id)).whenComplete((report,error)->{if(error!=null)failure(id,error);else{message(id,report.message());menus.stories(id);}});}
                case "choose"->{if(args.length!=4)throw new IllegalArgumentException("用法: /chronicle choose <实例ID> <当前节点ID> <选项ID>");service.choose(args[1],id,args[2],args[3]).whenComplete((report,error)->{if(error!=null)failure(id,error);else{message(id,report.message());menus.stories(id);}});}
                case "national"->{if(args.length!=2)throw new IllegalArgumentException("用法: /chronicle national <实例ID>");menus.national(id,args[1]);}
                case "vote"->{if(args.length!=5)throw new IllegalArgumentException("用法: /chronicle vote <实例ID> <当前节点ID> <国家票修订号> <选项ID>");long revision=nationalRevision(args[3]);nationalResponse(id,args[1],service.nationalVote(args[1],id,args[2],revision,args[4]));}
                case "withdraw"->{if(args.length!=4)throw new IllegalArgumentException("用法: /chronicle withdraw <实例ID> <当前节点ID> <国家票修订号>");long revision=nationalRevision(args[3]);nationalResponse(id,args[1],service.withdrawNationalVote(args[1],id,args[2],revision));}
                default->message(id,"/chronicle stories | history [nation 国家ID|player UUID/me] [页] | join 实例ID | review 实例ID | leave 实例ID | reviews | contributions [页] | choose 实例ID 节点ID 选项ID | national 实例ID | vote 实例ID 节点ID 修订号 选项ID | withdraw 实例ID 节点ID 修订号 | epic");
            }
        } catch(IllegalArgumentException error){message(id,error.getMessage());}
        return true;
    }
    private static long nationalRevision(String value){long revision=Long.parseLong(value);if(revision<0)throw new IllegalArgumentException("国家票修订号不能为负数；请刷新国家投票界面。");return revision;}
    private void epic(UUID actor,String[] args) {
        if(args.length<2){message(actor,epicUsage());return;}
        try {
            switch(args[1].toLowerCase(Locale.ROOT)) {
                case "founding-reference" -> {
                    if(args.length<3)throw new IllegalArgumentException("用法: /chronicle epic founding-reference <新国名>");
                    message(actor,EpicContract.foundingReference(String.join(" ",Arrays.copyOfRange(args,2,args.length))));
                }
                case "plans" -> service.epicCampaignPlans().whenComplete((plans,error)->{
                    if(error!=null)failure(actor,error);else message(actor,plans.isEmpty()?"没有已加载的国家史诗计划。":plans.stream()
                            .map(plan->plan.campaignId()+" -> "+plan.storyId()+"/"+plan.storyVersion()).collect(java.util.stream.Collectors.joining("\n")));
                });
                case "providers" -> service.epicProviders().whenComplete((providers,error)->{
                    if(error!=null)failure(actor,error);else message(actor,providers.isEmpty()?"没有可用的原生史诗 provider。":String.join("\n",providers));
                });
                case "open" -> {
                    if(args.length!=5)throw new IllegalArgumentException("用法: /chronicle epic open <会期ID> <计划ID> <救济国UUID>");
                    service.openEpicSignup(args[3],args[2],args[4],actor).whenComplete((signup,error)->epicSignupResponse(actor,signup,error));
                }
                case "signup" -> {
                    if(args.length!=3)throw new IllegalArgumentException("用法: /chronicle epic signup <会期ID>");
                    service.epicSignup(args[2]).whenComplete((signup,error)->{
                        if(error!=null)failure(actor,error);else message(actor,epicSignupSummary(signup));
                    });
                }
                case "enroll" -> {
                    if(args.length!=4)throw new IllegalArgumentException("用法: /chronicle epic enroll <会期ID> <会期修订号>");
                    service.enrollEpicSignup(args[2],actor,epicRevision(args[3])).whenComplete((signup,error)->epicSignupResponse(actor,signup,error));
                }
                case "withdraw-signup" -> {
                    if(args.length!=5)throw new IllegalArgumentException("用法: /chronicle epic withdraw-signup <会期ID> <席位修订号> <会期修订号>");
                    service.withdrawEpicSignup(args[2],actor,epicRevision(args[3]),epicRevision(args[4])).whenComplete((signup,error)->epicSignupResponse(actor,signup,error));
                }
                case "seal" -> {
                    if(args.length!=4)throw new IllegalArgumentException("用法: /chronicle epic seal <会期ID> <会期修订号>");
                    service.sealEpicSignup(args[2],actor,epicRevision(args[3])).whenComplete((campaign,error)->{
                        if(error!=null)failure(actor,error);else message(actor,epicCampaignSummary(campaign));
                    });
                }
                case "status" -> {
                    if(args.length!=3)throw new IllegalArgumentException("用法: /chronicle epic status <实例ID>");
                    service.epicCampaign(args[2],actor).whenComplete((campaign,error)->{
                        if(error!=null)failure(actor,error);else message(actor,epicCampaignSummary(campaign));
                    });
                }
                case "route" -> {
                    if(args.length!=5)throw new IllegalArgumentException("用法: /chronicle epic route <实例ID> <战役修订号> <WAR|DUNGEON>");
                    service.voteEpicRoute(args[2],actor,epicRevision(args[3]),args[4].toUpperCase(Locale.ROOT)).whenComplete((campaign,error)->{
                        if(error!=null)failure(actor,error);else message(actor,epicCampaignSummary(campaign));
                    });
                }
                case "prepare" -> {
                    if(args.length!=7)throw new IllegalArgumentException("用法: /chronicle epic prepare <实例ID> <类型> <目标> <原生参数> <providerID>");
                    EpicOperation.Type type=EpicOperation.Type.valueOf(args[3].toUpperCase(Locale.ROOT));
                    service.prepareEpicOperation(args[2],actor,type,args[4],args[5],args[6]).whenComplete((state,error)->{
                        if(error!=null)failure(actor,error);else message(actor,epicOperationSummary(state)+"；按修订号执行 apply 或只读 reconcile。");
                    });
                }
                case "apply","reconcile" -> {
                    if(args.length!=4)throw new IllegalArgumentException("用法: /chronicle epic apply|reconcile <操作ID> <操作修订号>");
                    long revision=epicRevision(args[3]);
                    CompletionStage<EpicOperationState> result=args[1].equalsIgnoreCase("apply")
                            ?service.applyEpicOperation(args[2],actor,revision)
                            :service.reconcileEpicOperation(args[2],actor,revision);
                    result.whenComplete((state,error)->{
                        if(error!=null)failure(actor,error);else message(actor,epicOperationSummary(state));
                    });
                }
                default -> message(actor,epicUsage());
            }
        } catch(IllegalArgumentException error) { message(actor,error.getMessage()); }
    }
    private static long epicRevision(String value) {
        long revision=Long.parseLong(value);if(revision<0)throw new IllegalArgumentException("史诗修订号不能为负数；请刷新状态后重试。");return revision;
    }
    private static String epicUsage() {
        return "/chronicle epic plans|providers|open <会期> <计划> <救济国UUID>|signup <会期>|enroll <会期> <修订号>|withdraw-signup <会期> <席位修订号> <会期修订号>|seal <会期> <修订号>|status <实例>|route <实例> <战役修订号> <WAR|DUNGEON>|prepare <实例> <类型> <目标> <原生参数> <providerID>|apply|reconcile <操作ID> <修订号>|founding-reference <新国名>";
    }
    private void epicSignupResponse(UUID actor,EpicSignup signup,Throwable error) {
        if(error!=null)failure(actor,error);else message(actor,epicSignupSummary(signup));
    }
    private static String epicSignupSummary(EpicSignup signup) {
        String seats=signup.seats().values().stream().map(seat->seat.nationId()+"="+seat.status()+"/seat-rev:"+seat.revision())
                .collect(java.util.stream.Collectors.joining(", "));
        return "会期="+signup.sessionId()+" plan="+signup.plan().campaignId()+" relief="+signup.reliefNationId()
                +" status="+signup.status()+" revision="+signup.revision()+" seats=["+seats+"]"
                +(signup.sealedEventId()==null?"":" source="+signup.sealedEventId());
    }
    private static String epicCampaignSummary(EpicCampaignState campaign) {
        String operations=campaign.operations().values().stream().map(StarChroniclePlugin::epicOperationSummary)
                .collect(java.util.stream.Collectors.joining("\n"));
        return "战役="+campaign.instanceId()+" status="+campaign.status()+" phase="+campaign.phaseId()
                +" route="+Objects.toString(campaign.route(),"-")+" revision="+campaign.revision()
                +" routeVotes="+campaign.routeVotes().values().stream().map(vote->vote.nationId()+"="+vote.choice()).collect(java.util.stream.Collectors.joining(","))
                +(operations.isBlank()?"":"\n"+operations);
    }
    private static String epicOperationSummary(EpicOperationState state) {
        String receipt=state.receipt()==null?"":" nativeReceipt="+state.receipt().nativeReceiptId()+" at="+state.receipt().appliedAt();
        return "operation="+state.operation().operationId()+" type="+state.operation().type()+" nation="
                +state.operation().nationId()+" status="+state.status()+" revision="+state.revision()+receipt
                +(state.detail().isBlank()?"":" detail="+state.detail());
    }
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
                Location l=player.getLocation();if(l.getBlockY()+3>=l.getWorld().getMaxHeight()||l.getBlockY()<l.getWorld().getMinHeight())throw new IllegalArgumentException("当前位置没有足够的纪念展示高度");
                new MemoryProtection(getServer().getPluginManager()).requireCreate(player,l);
                MemorySite site=new MemorySite(args[2],l.getWorld().getUID(),l.getBlockX(),l.getBlockY(),l.getBlockZ(),"世界历史 · "+args[2],args[3],npc,true);
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
            case "validate"->query(()->{var valid=coreContent.bind(ConfigLoader.loadWithoutDirectoryValidation(getDataFolder().toPath().resolve("stories"),gateway::hasScript,gateway::hasQuest));var plans=EpicCampaignPlanCatalog.load(getDataFolder().toPath().resolve("epic-campaigns"));validateEpicPlans(valid,plans);engine.validate(valid,MemoryRecipeCatalog.load(memoryRecipesFile));return valid;}).whenComplete((valid,error)->reply.accept(error==null?"验证通过: "+valid.size()+" 条剧情、世界记忆配方及史诗计划（未更改运行配置或版本目录）":"验证失败: "+root(error).getMessage()));
            case "reload"->query(()->{List<StoryDefinition> valid=coreContent.bind(ConfigLoader.loadWithoutDirectoryValidation(getDataFolder().toPath().resolve("stories"),gateway::hasScript,gateway::hasQuest));var plans=EpicCampaignPlanCatalog.load(getDataFolder().toPath().resolve("epic-campaigns"));validateEpicPlans(valid,plans);Set<String> nextPermissions=permissionNodes(valid);engine.reload(valid,MemoryRecipeCatalog.load(memoryRecipesFile));epicPlanCatalog=plans;permissions=nextPermissions;definitions=valid;return valid.size();}).whenComplete((count,error)->reply.accept(error==null?"已重载 "+count+" 条剧情、世界记忆配方及史诗计划；已有会期保留原计划，已有实例保留原定义与权限需求。":"重载失败，原定义保留: "+root(error).getMessage()));
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
        if(args.length==1)return List.of("stories","scene","history","book","npc","fate","past","memory","join","membership","reviews","revalidate","review","leave","contributions","quest-reconcile","quest-retry","choose","national","vote","withdraw","epic","status","validate","reload","uncertain","ack","native-ack","blocked","evaluate","demo").stream().filter(s->s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if(args.length==2&&args[0].equalsIgnoreCase("epic"))return List.of("plans","providers","open","signup","enroll","withdraw-signup","seal","status","route","prepare","apply","reconcile","founding-reference").stream().filter(s->s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if(args.length==4&&args[0].equalsIgnoreCase("epic")&&args[1].equalsIgnoreCase("prepare"))return Arrays.stream(EpicOperation.Type.values()).map(Enum::name).filter(s->s.startsWith(args[3].toUpperCase(Locale.ROOT))).toList();
        if(args.length==5&&args[0].equalsIgnoreCase("epic")&&args[1].equalsIgnoreCase("route"))return List.of("WAR","DUNGEON").stream().filter(s->s.startsWith(args[4].toUpperCase(Locale.ROOT))).toList();
        if(args.length==7&&args[0].equalsIgnoreCase("epic")&&args[1].equalsIgnoreCase("prepare"))return epicOperationProviders.stream().map(EpicOperationProvider::providerId).distinct().filter(s->s.startsWith(args[6])).sorted().toList();
        if(args.length==2&&args[0].equalsIgnoreCase("npc"))return npcLedger.catalog().profiles().keySet().stream().sorted().toList();
        if(args.length==2&&args[0].equalsIgnoreCase("history"))return List.of("nation","player");
        if(args.length==2&&args[0].equalsIgnoreCase("contributions"))return List.of("player","nation").stream().filter(s->s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if(args.length==2&&args[0].equalsIgnoreCase("demo")&&sender.hasPermission("starchronicle.admin"))return definitions.stream().map(StoryDefinition::id).filter(s->s.startsWith(args[1])).toList();
        return List.of();
    }
    @Override public void onDisable() {
        explorationIngress.seal();
        nationalVoteAuthority=NationalVoteAuthority.denying();
        epicOperationProviders=List.of();
        if(questGates!=null)questGates.close();
        if(!running.getAndSet(false)){if(worker==null){if(engine!=null)engine.close();else if(store!=null)store.close();}if(textService!=null)textService.close();if(coreContent!=null)coreContent.close();if(gateway!=null)gateway.close();return;}
        if(poll!=null)poll.cancel();if(exploration!=null)exploration.close();if(npcs!=null)npcs.close();if(memorySites!=null)memorySites.close();if(textService!=null)textService.close();if(coreContent!=null)coreContent.close();if(gateway!=null)gateway.close();
        getServer().getServicesManager().unregisterAll(this);HandlerList.unregisterAll((Listener)this);if(menus!=null)HandlerList.unregisterAll(menus);
        // terminated() closes storage after every queued write, even when the bounded queue was full.
        if(worker!=null){worker.shutdown();try{if(!worker.awaitTermination(10,TimeUnit.SECONDS))getLogger().warning("State worker still draining; storage will close at termination; do not reload plugin");}catch(InterruptedException error){Thread.currentThread().interrupt();}}
        if(tasks!=null)tasks.close();nativeFlights.clear();
    }
}
