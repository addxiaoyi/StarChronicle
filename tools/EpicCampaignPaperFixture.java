package smoke;

import dev.starcore.starcore.module.diplomacy.DiplomacyService;
import dev.starcore.starcore.module.dungeon.*;
import dev.starcore.starcore.module.nation.NationService;
import dev.starcore.starcore.module.nation.model.NationId;
import dev.starcore.starcore.module.resource.NationResourceTransactionService;
import dev.starcore.starcore.module.resource.ResourceService;
import dev.starcore.starcore.module.resource.TradeHistoryService;
import dev.starcore.starcore.module.resource.model.TradeOrder;
import dev.starcore.starcore.module.resource.model.TradeRecord;
import dev.starcore.starcore.module.treasury.TreasuryService;
import dev.starcore.starcore.module.technology.TechnologyService;
import dev.starcore.starcore.module.technology.TechnologyChangeReceipt;
import dev.starcore.starcore.module.war.WarService;
import dev.starcore.starcore.quest.QuestService;
import io.github.addxiaoyi.starchronicle.api.ChronicleService;
import io.github.addxiaoyi.starchronicle.api.epic.*;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Isolated acceptance: actual clients, public services and bundled story/plan.
 * Never writes a database, changes quest progress, fabricates activity completion records,
 * replaces providers or edits native model state. Test funds/ingredients are explicit.
 * Trade scenarios explicitly exercise history-store commits, without claiming asset movement. */
public class EpicCampaignPaperFixture extends JavaPlugin implements CommandExecutor {
    private static final String QUEST="chronicle_heir_records";
    private final Map<UUID,PermissionAttachment> permissions=new HashMap<>();
    private final AtomicBoolean busy=new AtomicBoolean();
    private ChronicleService api; private Plugin core; private Object registry; private Path run;
    private String route,instance; private int boot;

    @Override public void onEnable() {
        try {
            run=getDataFolder().toPath().toAbsolutePath().normalize().getParent().getParent();
            String product=getConfig().getString("expected-sha","");
            require(run.getFileName().toString().startsWith("paper-epic-"+product.substring(0,8)+"-")
                    &&Files.isRegularFile(run.resolve("preparation.json")),"Only a prepared isolated run is allowed");
            require(hash(run.resolve("plugins/StarChronicle.jar")).equals(product),"Chronicle SHA drift");
            require(hash(run.resolve("plugins/StarCore.jar")).equals(getConfig().getString("expected-starcore-sha")),"StarCore SHA drift");
            core=Objects.requireNonNull(getServer().getPluginManager().getPlugin("StarCore"));
            require(core.isEnabled(),"StarCore is not enabled");
            registry=core.getClass().getMethod("context").invoke(core);
            registry=registry.getClass().getMethod("serviceRegistry").invoke(registry);
            api=Objects.requireNonNull(getServer().getServicesManager().load(ChronicleService.class));
            route=getConfig().getString("route");instance=getConfig().getString("instance","");
            boot=getConfig().getInt("boot",0)+1;getConfig().set("boot",boot);saveConfig();
            Objects.requireNonNull(getCommand("epicverify")).setExecutor(this);
            log("READY",Map.of("product",product,"native",hash(run.resolve("plugins/StarCore.jar")),"route",route));
        } catch(Throwable error) { fail("enable",error);getServer().getPluginManager().disablePlugin(this); }
    }
    @Override public void onDisable() { if(run!=null)log("CLOSED",Map.of("normalDisable",true)); }
    protected static void require(boolean ok,String message) { if(!ok)throw new IllegalStateException(message); }
    private static String hash(Path path)throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); }
    protected synchronized void log(String kind,Object data) {
        String line=SqliteStore.json(Map.of("time",Instant.now().toString(),"boot",boot,"kind",kind,"data",data));
        getLogger().info("EPIC_"+kind+" "+SqliteStore.json(data));
        try { Files.writeString(run.resolve("evidence/epic-fixture.jsonl"),line+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND); }
        catch(Exception error) { getLogger().severe("Evidence write failed: "+error); }
    }
    private void fail(String action,Throwable error) {
        while(error.getCause()!=null)error=error.getCause();
        log("FAILED",Map.of("action",action,"error",error.toString()));error.printStackTrace();
    }
    protected <T>T service(Class<T> type)throws Exception {
        return type.cast(((Optional<?>)registry.getClass().getMethod("find",Class.class).invoke(registry,type)).orElseThrow());
    }
    protected <T>CompletionStage<T> main(Callable<T> action) {
        CompletableFuture<T> result=new CompletableFuture<>();
        Runnable task=()->{try {result.complete(action.call());}catch(Throwable error){result.completeExceptionally(error);}};
        if(Bukkit.isPrimaryThread())task.run();else getServer().getScheduler().runTask(this,task);
        return result;
    }
    protected UUID uuid(String who) {
        require(List.of("A","B","C","D").contains(who),"Expected A|B|C|D");
        return UUID.nameUUIDFromBytes(("OfflinePlayer:Epic"+who).getBytes(StandardCharsets.UTF_8));
    }
    protected Player player(String who) {
        Player p=getServer().getPlayerExact("Epic"+who);
        require(p!=null&&p.isOnline()&&p.getUniqueId().equals(uuid(who))&&!p.isOp(),"Need actual non-OP client "+who);
        return p;
    }
    protected NationId country(String who) {return NationId.fromString(getConfig().getString("nations."+who));}
    private void grant(String who) {
        Player p=player(who);var a=p.addAttachment(this);permissions.put(p.getUniqueId(),a);
        for(String permission:List.of("starchronicle.use","starcore.questgui","starcore.resource","starcore.nation.create",
                "starcore.nation.bank.withdraw","starcore.nation.ally.add","starcore.nation.war.declare"))a.setPermission(permission,true);
        a.setPermission("starchronicle.admin",false);a.setPermission("starcore.quest.admin",false);p.recalculatePermissions();
        p.setGameMode(GameMode.SURVIVAL);p.setFoodLevel(20);
    }
    private CompletionStage<?> seed() {return main(()->{
        for(String who:List.of("A","B","C","D"))grant(who);
        if(!getConfig().getBoolean("seeded",false)) {
            NationService nations=service(NationService.class);TreasuryService treasury=service(TreasuryService.class);
            var resources=service(NationResourceTransactionService.class);
            for(String who:List.of("A","B","C")) {
                Player p=player(who);require(nations.nationOf(p.getUniqueId()).isEmpty(),"Fresh identity required");
                var nation=nations.createNation(p.getUniqueId(),p.getName(),"EpicTest"+who);
                getConfig().set("nations."+who,nation.id().toString());
                treasury.deposit(nation.id(),new BigDecimal("1000000.00"),"Isolated epic test initial funds");
                require(resources.grant(new NationResourceTransactionService.TransactionRequest("epic-fixture-seed-"+who,nation.id(),
                        Map.of("agricultural",100L),NationResourceTransactionService.Category.OTHER,"Isolated epic test initial resources")).applied(),"Native resource seed rejected");
            }
            require(nations.addMember(country("A"),uuid("D"),player("D").getName()),"Native successor membership rejected");
            getConfig().set("seeded",true);saveConfig();
            World world=getServer().getWorlds().getFirst();int floor=world.getHighestBlockYAt(0,0);
            world.getBlockAt(1,floor+1,0).setType(Material.CRAFTING_TABLE);
            for(String who:List.of("A","B")) {
                Player p=player(who);p.teleport(new Location(world,0.5,floor+1,1.5));
                require(p.getInventory().addItem(new ItemStack(Material.SUGAR_CANE,3)).isEmpty(),"Ingredient inventory full");
            }
        }
        log("SEEDED",Map.of("A",country("A").toString(),"B",country("B").toString(),"C",country("C").toString(),"testFunds",true,"nonOp",true));
        return null;
    });}
    private CompletionStage<?> open() {
        require(instance.isEmpty(),"Campaign already exists");
        permissions.get(uuid("A")).setPermission("starchronicle.admin",true);player("A").recalculatePermissions();
        return api.openEpicSignup("heir-strategy","epic-acceptance",country("C").toString(),uuid("A"))
                .thenCompose(s->api.enrollEpicSignup(s.sessionId(),uuid("A"),s.revision()))
                .thenCompose(s->api.enrollEpicSignup(s.sessionId(),uuid("B"),s.revision()))
                .thenCompose(s->api.sealEpicSignup(s.sessionId(),uuid("A"),s.revision()))
                .thenCompose(s->main(()->{instance=s.instanceId();getConfig().set("instance",instance);saveConfig();
                    permissions.get(uuid("A")).setPermission("starchronicle.admin",false);player("A").recalculatePermissions();
                    log("OPENED",s);return s;}));
    }
    private CompletionStage<?> taskReady(String who) {
        CompletableFuture<Void> result=new CompletableFuture<>();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        new org.bukkit.scheduler.BukkitRunnable() {
            @Override public void run() {
                try {
                    var quest=service(QuestService.class).getPlayerQuest(player(who).getUniqueId()).getActiveQuest(QUEST);
                    if(quest!=null) {
                        require(!quest.isAllObjectivesCompleted(),"Task must be accepted before actual crafting");
                        log("NATIVE_TASK_READY",Map.of("player",who,"quest",QUEST,"progressEdited",false));
                        cancel();result.complete(null);
                    } else if(System.nanoTime()>=deadline) {
                        throw new IllegalStateException("Native task admission did not complete for "+who);
                    }
                } catch(Throwable error) {cancel();result.completeExceptionally(error);}
            }
        }.runTaskTimer(this,1L,2L);
        return result;
    }
    private CompletionStage<?> taskComplete(String who) {return main(()->{
        QuestService quests=service(QuestService.class);Player p=player(who);
        var quest=quests.getPlayerQuest(p.getUniqueId()).getActiveQuest(QUEST);
        require(quest!=null&&quest.isAllObjectivesCompleted(),"Actual craft objective has not completed");
        log("NATIVE_TASK_SUBMITTED",Map.of("player",who,"result",quests.completeQuest(p,QUEST).toString(),"progressEdited",false));return null;
    });}
    private CompletionStage<EpicCampaignState> campaign() {return api.epicCampaign(instance,uuid("A"));}
    private CompletionStage<EpicOperationState> operation(String who,EpicOperation.Type type,String target,String reference,String provider,boolean lookup) {
        return api.prepareEpicOperation(instance,uuid(who),type,target,reference,provider).thenCompose(s->{
            if(s.status()==EpicOperationState.Status.COMMITTED)return CompletableFuture.completedFuture(s);
            return lookup||s.status()==EpicOperationState.Status.UNKNOWN
                    ?api.reconcileEpicOperation(s.operation().operationId(),uuid(who),s.revision())
                    :api.applyEpicOperation(s.operation().operationId(),uuid(who),s.revision());
        }).thenApply(s->{require(s.status()==EpicOperationState.Status.COMMITTED,"Native operation not committed: "+s);
            log("OPERATION_COMMITTED",s);return s;});
    }
    private CompletionStage<?> preparations() {
        CompletionStage<?> chain=api.epicProviders().thenApply(ids->{log("PROVIDERS",ids);return ids;});
        for(String who:List.of("A","B"))chain=chain.thenCompose(x->api.questGateState(instance,uuid(who))).thenCompose(view->{
            var gate=view.gates().stream().filter(g->g.playerId().equals(uuid(who))&&g.questId().equals(QUEST)).findFirst().orElseThrow();
            return operation(who,EpicOperation.Type.QUEST_GATE,QUEST,"investigation/records-evidence",gate.providerId(),true);
        });
        for(String who:List.of("A","B")) {
            chain=chain.thenCompose(x->operation(who,EpicOperation.Type.TREASURY_AID,country("C").toString(),"10.00","starcore.treasury",false));
            chain=chain.thenCompose(x->operation(who,EpicOperation.Type.RESOURCE_AID,country("C").toString(),"agricultural:10","starcore.epic.resource_aid",false));
        }
        for(String who:List.of("A","B"))chain=chain.thenCompose(x->operation(who,EpicOperation.Type.DIPLOMACY_INVITE,
                country("C").toString(),"ALLIANCE_INVITE_V1","starcore.epic.diplomacy",false))
                .thenCompose(invite->operation("C",EpicOperation.Type.DIPLOMACY_ACCEPT,country(who).toString(),invite.receipt().nativeReceiptId(),"starcore.epic.diplomacy",false));
        return chain.thenCompose(x->campaign()).thenApply(s->{require(s.status()==EpicCampaignState.Status.ROUTE_VOTING,"Preparations must enable route vote");log("PREPARATIONS_READY",s);return s;});
    }
    private CompletionStage<?> vote(String who,String node,String choice) {
        return api.nationalState(instance,uuid(who)).thenCompose(s->{require(s.nodeId().equals(node),"Expected story node "+node+", got "+s.nodeId());
            return api.nationalVote(instance,uuid(who),node,s.viewerRevision(),choice);});
    }
    private CompletionStage<?> advanceStory() {
        CompletionStage<?> chain=CompletableFuture.completedFuture(null);
        for(String[] pair:List.of(new String[]{"investigation","submit-records"},new String[]{"council","support"},new String[]{"preparations","ready"}))
            for(String who:List.of("A","B"))chain=chain.thenCompose(x->vote(who,pair[0],pair[1]));
        for(String who:List.of("A","B"))chain=chain.thenCompose(x->campaign()).thenCompose(s->api.voteEpicRoute(instance,uuid(who),s.revision(),route));
        for(String who:List.of("A","B"))chain=chain.thenCompose(x->vote(who,"route",route));
        return chain.thenCompose(x->campaign()).thenApply(s->{require(s.status()==EpicCampaignState.Status.ROUTE_APPROVED,"Route not approved");log("ROUTE_APPROVED",s);return s;});
    }
    private CompletionStage<?> dungeonEnter(String who) {return main(()->{
        require(route.equals("DUNGEON"),"Wrong route");
        require(service(NationService.class).nationOf(uuid(who)).orElseThrow().id().equals(country(who)),"Current native country changed");
        require(service(DungeonService.class).nationEnterDungeon(country(who),"COOPERATIVE_RELIC",List.of(player(who))),"Native national entry rejected");
        log("DUNGEON_ENTERED",Map.of("player",who,"automaticNativeSurvivalTimer",true));return null;
    });}
    private CompletionStage<?> founding() {
        require(route.equals("WAR"),"Wrong route");String target=UUID.randomUUID().toString();
        return api.prepareEpicOperation(instance,uuid("A"),EpicOperation.Type.NATION_FOUNDING,target,
                EpicContract.foundingReference("复兴帝国"),"starcore.epic.nation_founding")
                .thenCompose(s->main(()->{
                    var nations=service(NationService.class);
                    require(nations.transferOwnership(country("A"),uuid("A"),uuid("D"),player("D").getName()),"Native succession rejected");
                    require(nations.leaveNation(uuid("A")),"Native departure rejected");
                    getConfig().set("restored",target);getConfig().set("foundingOperation",s.operation().operationId());saveConfig();return s;
                })).thenCompose(s->api.applyEpicOperation(s.operation().operationId(),uuid("A"),s.revision()))
                .thenCompose(s->main(()->{require(s.status()==EpicOperationState.Status.COMMITTED,"Founding did not commit: "+s);
                    require(service(NationService.class).isFounder(uuid("A"),NationId.fromString(target)),"Native founder missing");
                    service(TreasuryService.class).deposit(NationId.fromString(target),new BigDecimal("1000000.00"),"Isolated war fixture initial funds");
                    log("FOUNDING_COMMITTED",s);return s;}));
    }
    private CompletionStage<?> declareWar() {
        return operation("A",EpicOperation.Type.WAR_COMMIT,country("B").toString(),"WAR_DECLARATION_V1","starcore.epic.war",false)
                .thenCompose(s->main(()->{getConfig().set("warId",s.receipt().nativeReceiptId());saveConfig();return s;}));
    }
    private CompletionStage<?> endWar() {return main(()->{
        WarService wars=service(WarService.class);NationId restored=NationId.fromString(getConfig().getString("restored"));
        require(wars.endWar(country("B"),restored,country("B")),"Native principal peace rejected");
        return wars;
    }).thenCompose(wars->{
        CompletableFuture<Void> result=new CompletableFuture<>();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        new org.bukkit.scheduler.BukkitRunnable() {
            @Override public void run() {
                try {
                    var committed=wars.warCompletion(UUID.fromString(getConfig().getString("warId")));
                    if(committed.isPresent()) {
                        var receipt=committed.orElseThrow();
                        log("NATIVE_WAR_ENDED",Map.of("warId",receipt.warId(),"endedAt",receipt.endedAt(),"domain",receipt.authorityDomainId(),"ending","native principal peace; no victory claim"));
                        cancel();result.complete(null);
                    } else if(System.nanoTime()>=deadline) {
                        throw new IllegalStateException("Native peace has no durable completion receipt");
                    }
                } catch(Throwable error) {cancel();result.completeExceptionally(error);}
            }
        }.runTaskTimer(this,1L,2L);
        return result;
    });}
    private CompletionStage<?> captureFinal() {return main(()->{
        if(route.equals("DUNGEON"))for(String who:List.of("A","B")) {
            var dungeons=service(DungeonService.class);
            var receipt=dungeons.getPlayerHistory(uuid(who)).stream().filter(r->r.dungeonId().equals("COOPERATIVE_RELIC")&&r.isSuccess()
                    &&country(who).value().equals(r.nationId())).findFirst().orElseThrow(()->new IllegalStateException("No successful national dungeon receipt"));
            require(dungeons.getCompletionRecord(receipt.recordId()).orElseThrow().equals(receipt),"Native record not durable");
            getConfig().set("records."+who,receipt.recordId().toString());
            log("NATIVE_DUNGEON_COMPLETED",Map.of("player",who,"recordId",receipt.recordId(),"nation",receipt.nationId(),"completedAt",receipt.completedAt(),"domain",dungeons.authorityDomainId()));
        }
        else require(service(WarService.class).warCompletion(UUID.fromString(getConfig().getString("warId"))).isPresent(),"War not durably ended");
        saveConfig();return null;
    }).thenCompose(x->campaign()).thenApply(s->{require(s.status()==EpicCampaignState.Status.ROUTE_APPROVED,"Campaign must still await final evidence");log("RESTART_CHECKPOINT",s);return s;});}
    private CompletionStage<?> finalEvidence() {
        CompletionStage<?> chain=CompletableFuture.completedFuture(null);
        if(route.equals("DUNGEON"))for(String who:List.of("A","B"))chain=chain.thenCompose(x->operation(who,EpicOperation.Type.DUNGEON_COMPLETION,
                "COOPERATIVE_RELIC",getConfig().getString("records."+who),"starcore.epic.dungeon_completion",true));
        else chain=chain.thenCompose(x->operation("A",EpicOperation.Type.FINAL_BATTLE,country("B").toString(),getConfig().getString("warId"),"starcore.epic.war",true));
        return chain.thenCompose(x->campaign()).thenApply(s->{require(s.status()==EpicCampaignState.Status.COMPLETE,"Campaign incomplete");log("FINAL_EVIDENCE_COMMITTED",s);return s;});
    }
    private CompletionStage<?> settle() {return vote("B",route.equals("WAR")?"war-await":"dungeon-await","settle");}
    private CompletionStage<?> check() {
        return campaign().thenCompose(s->{require(s.status()==EpicCampaignState.Status.COMPLETE,"Incomplete campaign");
            require(s.operations().size()==(route.equals("WAR")?13:12),"Unexpected operation cardinality");
            require(s.operations().values().stream().allMatch(o->o.status()==EpicOperationState.Status.COMMITTED),"Uncommitted operation remains");
            return api.instances(uuid("B"));}).thenCompose(items->{
                require(items.stream().anyMatch(i->i.id().equals(instance)&&i.completed()),"Story not completed");
                require(items.stream().filter(i->i.definition().id().equals("last-heir-strategy-aftermath")).count()==1,"Expected exactly one actual aftermath instance for B");
                return api.history(null,uuid("B"),0,100);
            }).thenApply(events->{var settlements=events.stream().filter(e->e.type().equals("HEIR_STRATEGY_SETTLED")).toList();
                require(settlements.size()==1,"Settlement must occur once");
                require("true".equals(settlements.getFirst().facts().get("epic.final.canonical")),"Missing canonical settlement");
                log("ACCEPTANCE_PASS",Map.of("route",route,"instance",instance,"settlement",settlements.getFirst(),"restarted",boot>1));return null;});
    }
    private CompletionStage<?> replayRejection() {
        return campaign().thenCompose(s->{var done=s.operations().values().stream().filter(o->o.operation().type()==EpicOperation.Type.RESOURCE_AID).findFirst().orElseThrow();
            return api.applyEpicOperation(done.operation().operationId(),done.operation().actorId(),done.revision()).handle((value,error)->{
                require(error!=null,"Committed operation was reapplied");log("REPLAY_REJECTED",Map.of("operation",done.operation().operationId(),"error",error.toString()));return null;});});
    }
    private CompletionStage<?> contributionCheck() {
        return api.contributions(null,null,0,100).thenApply(entries->{
            require(entries.size()==8,"Expected treasury and resource projections for two actors and two nations");
            require(entries.stream().map(e->e.id()).distinct().count()==8,"Contribution identity reused");
            for(String who:List.of("A","B"))for(String subject:List.of("player:"+uuid(who),"nation:"+country(who))) {
                var own=entries.stream().filter(e->e.request().subjectId().equals(subject)).toList();
                require(own.size()==2,"Expected two contribution units per source subject");
                for(String unit:List.of("treasury","resource.agricultural")) {
                    var entry=own.stream().filter(e->e.request().unit().equals(unit)).findFirst().orElseThrow();
                    require(entry.request().actorId().equals(uuid(who))&&entry.request().nationId().equals(country(who).toString()),"Contribution attribution changed");
                    require(entry.request().amount().compareTo(BigDecimal.TEN)==0,"Contribution amount differs");
                }
            }
            log("CONTRIBUTIONS_PASS",Map.of("entries",entries,"boot",boot));return null;
        });
    }
    private String memberInstance(){return Objects.requireNonNull(getConfig().getString("member-instance"),"Member fixture not opened");}
    private CompletionStage<?> memberOpen(){
        require(getConfig().getString("member-instance")==null,"Member story already opened");
        var event=new io.github.addxiaoyi.starchronicle.api.WorldEvent("fixture:member-review","MEMBER_REVIEW_FIXTURE",Instant.now(),
                Set.of(uuid("B"),uuid("D")),Set.of(country("A").toString(),country("B").toString()),Map.of(),"isolated-member-fixture","Explicit test story source; no native activity claimed");
        return api.submit(event).thenCompose(report->main(()->{
            require(report.instanceIds().size()==1,"Expected one membership story");String id=report.instanceIds().getFirst();
            getConfig().set("member-instance",id);saveConfig();log("MEMBER_OPENED",Map.of("instance",id));return id;
        }));
    }
    private CompletionStage<?> memberMove(String destination){return main(()->{
        require(Set.of("A","B").contains(destination),"Only fixture nations A or B");
        NationService nations=service(NationService.class);UUID actor=uuid("D");
        var previous=nations.nationOf(actor).orElseThrow().id();var next=country(destination);
        require(!previous.equals(next)&&!nations.isFounder(actor,previous),"Ordinary member required");
        require(nations.removeMember(previous,actor),"Native member removal failed");
        require(nations.addMember(next,actor,player("D").getName()),"Native member admission failed");
        require(nations.nationOf(actor).orElseThrow().id().equals(next),"Native membership did not change");
        log("MEMBER_NATIVE_MOVE",Map.of("actor",actor,"before",previous.toString(),"after",next.toString(),"currentHolder",nations.isFounder(actor,next)));
        return null;
    });}
    private CompletionStage<?> memberCheck(String status,String node){
        String id=memberInstance();
        return api.memberState(id,uuid("D")).thenCompose(state->api.instances(uuid("D")).thenCompose(own->api.activeInstances().thenCompose(active->{
            var story=java.util.stream.Stream.concat(own.stream(),active.stream()).filter(i->i.id().equals(id)).findFirst().orElseThrow();
            require(state.status().equals(status)&&state.nodeId().equals(node),"Unexpected member status/node: "+state);
            require(story.contains(uuid("D"))!=status.equals("EXITED"),"Membership projection differs from story");
            require(state.originalNationId().equals(country("A").toString()),"Original nation changed");
            if(status.equals("PENDING")||status.equals("EXITED"))require(!story.votes().containsKey(uuid("D")),"Inactive member still has an active personal vote");
            require(story.completed()==node.equals("end"),"Story completion differs");
            return api.memberReviews(uuid("D"),50).thenApply(reviews->{log("MEMBER_CHECK",Map.of("state",state,"story",story,"reviews",reviews));return null;});
        })));
    }
    private UUID tradeId(String name){return UUID.nameUUIDFromBytes(("isolated-history-store-"+name).getBytes(StandardCharsets.UTF_8));}
    private CompletionStage<?> tradeWrite(String name){return main(()->{
        require(Set.of("first","late").contains(name),"Expected first or late record");
        var history=service(TradeHistoryService.class);
        var record=new TradeRecord(tradeId(name),TradeOrder.OrderType.BUY,TradeOrder.OrderSource.PLAYER,
                uuid("A"),uuid("B"),country("A"),country("B"),"agricultural",2,5.0,0.5,
                Instant.parse(name.equals("first")?"2020-01-01T00:00:00Z":"2019-01-01T00:00:00Z"),null,null,
                "Isolated history-store fixture; no asset movement claimed");
        history.saveRecord(record);
        log("TRADE_WRITE",Map.of("name",name,"id",record.recordId(),"executedAt",record.executedAt(),"domain",history.historyAuthorityDomain(),"assetMovement",false));
        return null;
    });}
    private CompletionStage<?> tradeDispute(){return main(()->{
        service(TradeHistoryService.class).updateStatus(tradeId("first"),TradeRecord.TradeRecordStatus.DISPUTED,"Isolated history-store status update");
        log("TRADE_DISPUTED",Map.of("id",tradeId("first")));return null;
    });}
    private CompletionStage<?> tradeClean(){return main(()->{
        var history=service(TradeHistoryService.class);require(history.deleteOldRecords(Instant.parse("2021-01-01T00:00:00Z"))==2,"Expected exactly two history rows removed");
        log("TRADE_CLEANED",Map.of("removed",2,"refundClaimed",false));return null;
    });}
    private CompletionStage<?> tradeCheck(int expected){
        CompletableFuture<Void> result=new CompletableFuture<>();
        tradeCheckPoll(expected,System.nanoTime()+TimeUnit.SECONDS.toNanos(30),result);return result;
    }
    private void tradeCheckPoll(int expected,long deadline,CompletableFuture<Void> result){
        api.history(null,null,0,100).thenCompose(events->api.contributions(null,null,0,100).thenCompose(contributions->main(()->{
            try {
                var history=service(TradeHistoryService.class);UUID domain=history.historyAuthorityDomain();
                var changes=history.changesAfter(domain,0,100);
                var source=events.stream().filter(e->e.source().equals("StarCore.TradeHistoryService")).toList();
                if(source.size()<expected&&System.nanoTime()<deadline){
                    getServer().getScheduler().runTaskLater(this,()->tradeCheckPoll(expected,deadline,result),2L);return null;
                }
                require(changes.size()==expected&&source.size()==expected,"Native/Chronicle trade history count mismatch");
                require(contributions.isEmpty(),"History-store fixture cannot create contributions");
                for(var change:changes)require(source.stream().anyMatch(e->e.id().equals("starcore:trade-history:"+domain+":"+change.sequence())),"Missing source sequence");
                log("TRADE_CHECK",Map.of("domain",domain,"expected",expected,"events",source,"contributions",contributions.size(),
                        "native",changes.stream().map(c->Map.of("sequence",c.sequence(),"kind",c.kind(),"recordedAt",c.recordedAt(),"record",c.record().recordId(),"status",c.record().status(),"executedAt",c.record().executedAt())).toList()));
                result.complete(null);
            }catch(Throwable error){result.completeExceptionally(error);}return null;
        }))).exceptionally(error->{result.completeExceptionally(error);return null;});
    }
    private String technologyKey(){return Objects.requireNonNull(getConfig().getString("technology-key"),"Technology fixture not bound");}
    private List<Map<String,Object>> technologyReceipts(List<TechnologyChangeReceipt> changes){
        return changes.stream().map(r->Map.<String,Object>of("domain",r.authorityDomainId().toString(),"sequence",r.sequence(),"operation",r.operationId().toString(),
                "nation",r.nationId().toString(),"technology",r.technologyKey(),"era",r.era(),"change",r.change().name(),"committedAt",r.committedAt().toString())).toList();
    }
    private CompletionStage<?> technologyBind(){return main(()->{
        var service=service(TechnologyService.class);
        if(getConfig().getString("technology-key")==null){
            require(service.technologyChangesAfter(0,100).isEmpty(),"Technology fixture must start with an empty journal");
            String key=service.availableTechnologies().stream().sorted().findFirst().orElseThrow();
            require(!service.hasTechnology(country("A"),key),"Technology fixture must start locked");
            getConfig().set("technology-key",key);saveConfig();
        }
        log("TECH_BOUND",Map.of("domain",service.historyAuthorityDomainId().orElseThrow(),"nation",country("A").toString(),"technology",technologyKey()));return null;
    });}
    private CompletionStage<?> technologyCycle(){return main(()->{
        var service=service(TechnologyService.class);String key=technologyKey();var nation=country("A");
        require(service.technologyChangesAfter(0,100).isEmpty()&&!service.hasTechnology(nation,key),"Technology cycle requires original locked state");
        require(service.unlock(nation,key),"Native unlock failed");
        require(service.revoke(nation,key),"Native revoke failed");
        require(service.unlock(nation,key),"Native second unlock failed");
        log("TECH_CYCLE",Map.of("receipts",technologyReceipts(service.technologyChangesAfter(0,100)),"unlocked",service.hasTechnology(nation,key),"researchCompletionClaimed",false));return null;
    });}
    private CompletionStage<?> technologyRepeat(){return main(()->{
        var service=service(TechnologyService.class);var before=service.technologyChangesAfter(0,100);
        require(before.size()==3&&service.unlock(country("A"),technologyKey()),"Expected already unlocked technology");
        require(before.equals(service.technologyChangesAfter(0,100)),"Repeated native unlock created a history revision");
        log("TECH_REPEAT",Map.of("changes",3,"researchCompletionClaimed",false));return null;
    });}
    private CompletionStage<?> technologyRevoke(){return main(()->{
        var service=service(TechnologyService.class);require(service.revoke(country("A"),technologyKey()),"Restarted native revoke failed");
        log("TECH_REVOKED",Map.of("receipts",technologyReceipts(service.technologyChangesAfter(0,100)),"unlocked",service.hasTechnology(country("A"),technologyKey())));return null;
    });}
    private CompletionStage<?> technologyCheck(int expected){
        CompletableFuture<Void> result=new CompletableFuture<>();technologyCheckPoll(expected,System.nanoTime()+TimeUnit.SECONDS.toNanos(30),result);return result;
    }
    private void technologyCheckPoll(int expected,long deadline,CompletableFuture<Void> result){
        api.history(null,null,0,100).thenCompose(events->api.contributions(null,null,0,100).thenCompose(contributions->main(()->{
            try {
                var service=service(TechnologyService.class);var changes=service.technologyChangesAfter(0,100);
                var source=events.stream().filter(e->e.source().equals("StarCore.TechnologyService")).toList();
                if(source.size()<expected&&System.nanoTime()<deadline){getServer().getScheduler().runTaskLater(this,()->technologyCheckPoll(expected,deadline,result),2L);return null;}
                require(changes.size()==expected&&source.size()==expected,"Technology committed history count mismatch");
                require(contributions.isEmpty()&&source.stream().allMatch(e->e.participants().isEmpty()),"Technology fixture cannot invent contributors or researchers");
                log("TECH_CHECK",Map.of("domain",service.historyAuthorityDomainId().orElseThrow(),"expected",expected,"events",source,
                        "native",technologyReceipts(changes),"contributions",0,"unlocked",service.hasTechnology(country("A"),technologyKey())));result.complete(null);
            }catch(Throwable error){result.completeExceptionally(error);}return null;
        }))).exceptionally(error->{result.completeExceptionally(error);return null;});
    }
    private Map<String,Map<String,Long>> resourceStocks()throws Exception {
        Map<String,Map<String,Long>> result=new TreeMap<>();var resources=service(ResourceService.class);
        for(String who:List.of("A","B","C")){
            Map<String,Long> positive=new TreeMap<>();resources.stockpile(country(who)).forEach((key,value)->{if(value>0)positive.put(key,value);});result.put(who,positive);
        }
        return result;
    }
    private List<Map<String,Object>> resourceReceipts(List<NationResourceTransactionService.TransferReceipt> receipts){
        return receipts.stream().map(r->Map.<String,Object>of("domain",r.authorityDomainId(),"sequence",r.sequence(),"transaction",r.transactionId(),
                "payer",r.payerNationId().toString(),"receiver",r.receiverNationId().toString(),"resources",r.resources(),"category",r.category().name(),"description",r.description(),"committedAt",r.appliedAt().toString())).toList();
    }
    private NationResourceTransactionService.TransferRequest resourceRequest(String name){
        return switch(name){
            case "first"->new NationResourceTransactionService.TransferRequest("isolated-resource-first",country("A"),country("B"),Map.of("agricultural",7L,"mineral",3L),NationResourceTransactionService.Category.OTHER,"Isolated first stockpile transfer");
            case "second"->new NationResourceTransactionService.TransferRequest("isolated-resource-second",country("B"),country("C"),Map.of("agricultural",4L,"mineral",2L),NationResourceTransactionService.Category.OTHER,"Isolated second stockpile transfer");
            case "restart"->new NationResourceTransactionService.TransferRequest("isolated-resource-restart",country("C"),country("A"),Map.of("agricultural",2L,"mineral",1L),NationResourceTransactionService.Category.COMPENSATION_REFUND,"Isolated restarted stockpile transfer");
            default->throw new IllegalArgumentException(name);
        };
    }
    private CompletionStage<?> resourceBind(){return main(()->{
        var resources=service(NationResourceTransactionService.class);
        if(!getConfig().getBoolean("resource-bound",false)){
            require(resources.transferReceiptsAfter(0,100).isEmpty(),"Resource fixture needs an empty history");
            require(resources.grant(new NationResourceTransactionService.TransactionRequest("isolated-resource-seed",country("A"),Map.of("mineral",50L,"energy",25L),NationResourceTransactionService.Category.OTHER,"Explicit isolated test stockpiles")).applied(),"Resource fixture seed failed");
            getConfig().set("resource-bound",true);saveConfig();
        }
        log("RESOURCE_BOUND",Map.of("domain",resources.transferHistoryAuthorityDomainId().orElseThrow(),"A",country("A").toString(),"B",country("B").toString(),"C",country("C").toString()));return null;
    });}
    private CompletionStage<?> resourceBatch(){return main(()->{
        var resources=service(NationResourceTransactionService.class);require(resources.transferReceiptsAfter(0,100).isEmpty(),"Batch must be new");
        var first=resources.transfer(resourceRequest("first"));var second=resources.transfer(resourceRequest("second"));
        require(first.status()==NationResourceTransactionService.TransferStatus.APPLIED&&second.status()==NationResourceTransactionService.TransferStatus.APPLIED,"Native resource batch failed");
        log("RESOURCE_BATCH",Map.of("results",List.of(first,second),"receipts",resourceReceipts(resources.transferReceiptsAfter(0,100)),"stocks",resourceStocks()));return null;
    });}
    private CompletionStage<?> resourceRejections(){return main(()->{
        var resources=service(NationResourceTransactionService.class);var before=resources.transferReceiptsAfter(0,100);var stocks=resourceStocks();var first=resourceRequest("first");
        var repeated=resources.transfer(first);
        var conflict=resources.transfer(new NationResourceTransactionService.TransferRequest(first.transactionId(),first.payerNationId(),first.receiverNationId(),Map.of("agricultural",8L),first.category(),first.description()));
        var insufficient=resources.transfer(new NationResourceTransactionService.TransferRequest("isolated-resource-insufficient",country("A"),country("B"),Map.of("agricultural",Long.MAX_VALUE),NationResourceTransactionService.Category.OTHER,"Must not commit"));
        require(repeated.status()==NationResourceTransactionService.TransferStatus.ALREADY_APPLIED&&conflict.status()==NationResourceTransactionService.TransferStatus.REJECTED&&insufficient.status()==NationResourceTransactionService.TransferStatus.INSUFFICIENT_RESOURCES,"Native duplicate/conflict/insufficient semantics differ");
        require(before.equals(resources.transferReceiptsAfter(0,100))&&stocks.equals(resourceStocks())&&resources.transferReceipt("isolated-resource-insufficient").isEmpty(),"Rejected/repeated transfer changed state");
        log("RESOURCE_REJECTIONS",Map.of("statuses",List.of(repeated.status().name(),conflict.status().name(),insufficient.status().name()),"receipts",resourceReceipts(before),"stocks",stocks));return null;
    });}
    private CompletionStage<?> resourceRestart(){return main(()->{
        var resources=service(NationResourceTransactionService.class);require(resources.transferReceiptsAfter(0,100).size()==2,"Restart must retain the batch");
        var result=resources.transfer(resourceRequest("restart"));require(result.status()==NationResourceTransactionService.TransferStatus.APPLIED,"Restarted resource transfer failed");
        log("RESOURCE_RESTART",Map.of("result",result,"receipts",resourceReceipts(resources.transferReceiptsAfter(0,100)),"stocks",resourceStocks()));return null;
    });}
    private CompletionStage<?> resourceCheck(int expected){
        CompletableFuture<Void> result=new CompletableFuture<>();resourceCheckPoll(expected,System.nanoTime()+TimeUnit.SECONDS.toNanos(30),result);return result;
    }
    private void resourceCheckPoll(int expected,long deadline,CompletableFuture<Void> result){
        api.history(null,null,0,100).thenCompose(events->api.contributions(null,null,0,100).thenCompose(contributions->main(()->{
            try {
                var service=service(NationResourceTransactionService.class);var receipts=service.transferReceiptsAfter(0,100);
                var source=events.stream().filter(e->e.source().equals("StarCore.NationResourceTransactionService")).toList();
                if(source.size()<expected&&System.nanoTime()<deadline){getServer().getScheduler().runTaskLater(this,()->resourceCheckPoll(expected,deadline,result),2L);return null;}
                require(receipts.size()==expected&&source.size()==expected,"Resource committed history count mismatch");
                require(contributions.isEmpty()&&source.stream().allMatch(e->e.participants().isEmpty()),"Resource history cannot invent contributors");
                log("RESOURCE_CHECK",Map.of("domain",service.transferHistoryAuthorityDomainId().orElseThrow(),"expected",expected,"events",source,"native",resourceReceipts(receipts),"stocks",resourceStocks(),"contributions",0));result.complete(null);
            }catch(Throwable error){result.completeExceptionally(error);}return null;
        }))).exceptionally(error->{result.completeExceptionally(error);return null;});
    }
    protected CompletionStage<?> extraAction(String action,String[] args) {throw new IllegalArgumentException("Unknown action: "+action);}
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        if(!(sender instanceof ConsoleCommandSender)||args.length==0)return false;
        String action=args[0];if(!busy.compareAndSet(false,true)){fail(action,new IllegalStateException("Previous action is still pending"));return true;}
        try {
            CompletionStage<?> work=switch(action) {
                case "resource-bind"->resourceBind();case "resource-batch"->resourceBatch();case "resource-rejections"->resourceRejections();case "resource-restart"->resourceRestart();case "resource-check"->resourceCheck(Integer.parseInt(args[1]));
                case "trade-write"->tradeWrite(args[1]);case "trade-dispute"->tradeDispute();case "trade-clean"->tradeClean();case "trade-check"->tradeCheck(Integer.parseInt(args[1]));
                case "tech-bind"->technologyBind();case "tech-cycle"->technologyCycle();case "tech-repeat"->technologyRepeat();case "tech-revoke"->technologyRevoke();case "tech-check"->technologyCheck(Integer.parseInt(args[1]));
                case "members-open"->memberOpen();case "members-move"->memberMove(args[1]);case "members-check"->memberCheck(args[1],args[2]);
                case "seed"->seed();case "open"->open();case "task-ready"->taskReady(args[1]);case "task-complete"->taskComplete(args[1]);
                case "preparations"->preparations();case "advance"->advanceStory();case "dungeon"->dungeonEnter(args[1]);
                case "found"->founding();case "declare"->declareWar();case "end-war"->endWar();
                case "checkpoint"->captureFinal();case "final-evidence"->finalEvidence();case "settle"->settle();
                case "check"->check();case "contributions"->contributionCheck();case "replay"->replayRejection();case "state"->campaign().thenApply(s->{log("STATE",s);return s;});
                default->extraAction(action,args);
            };
            work.whenComplete((value,error)->{busy.set(false);if(error!=null)fail(action,error);else log("ACTION_DONE",Map.of("action",action));});
        } catch(Throwable error) {busy.set(false);fail(action,error);}return true;
    }
}
