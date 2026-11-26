package smoke;

import dev.starcore.starcore.module.diplomacy.DiplomacyService;
import dev.starcore.starcore.module.dungeon.*;
import dev.starcore.starcore.module.nation.NationService;
import dev.starcore.starcore.module.nation.model.NationId;
import dev.starcore.starcore.module.resource.NationResourceTransactionService;
import dev.starcore.starcore.module.treasury.TreasuryService;
import dev.starcore.starcore.module.war.WarService;
import dev.starcore.starcore.quest.QuestService;
import io.github.addxiaoyi.starchronicle.api.ChronicleService;
import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import io.github.addxiaoyi.starchronicle.core.MemberReviewLedger;
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
 * Never writes a database, changes quest progress, fabricates completion records,
 * replaces providers or edits native model state. Test funds/ingredients are explicit. */
public final class EpicCampaignPaperFixture extends JavaPlugin implements CommandExecutor {
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
    private static void require(boolean ok,String message) { if(!ok)throw new IllegalStateException(message); }
    private static String hash(Path path)throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); }
    private synchronized void log(String kind,Object data) {
        String line=SqliteStore.json(Map.of("time",Instant.now().toString(),"boot",boot,"kind",kind,"data",data));
        getLogger().info("EPIC_"+kind+" "+SqliteStore.json(data));
        try { Files.writeString(run.resolve("evidence/epic-fixture.jsonl"),line+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND); }
        catch(Exception error) { getLogger().severe("Evidence write failed: "+error); }
    }
    private void fail(String action,Throwable error) {
        while(error.getCause()!=null)error=error.getCause();
        log("FAILED",Map.of("action",action,"error",error.toString()));error.printStackTrace();
    }
    private <T>T service(Class<T> type)throws Exception {
        return type.cast(((Optional<?>)registry.getClass().getMethod("find",Class.class).invoke(registry,type)).orElseThrow());
    }
    private <T>CompletionStage<T> main(Callable<T> action) {
        CompletableFuture<T> result=new CompletableFuture<>();
        Runnable task=()->{try {result.complete(action.call());}catch(Throwable error){result.completeExceptionally(error);}};
        if(Bukkit.isPrimaryThread())task.run();else getServer().getScheduler().runTask(this,task);
        return result;
    }
    private UUID uuid(String who) {
        require(List.of("A","B","C","D").contains(who),"Expected A|B|C|D");
        return UUID.nameUUIDFromBytes(("OfflinePlayer:Epic"+who).getBytes(StandardCharsets.UTF_8));
    }
    private Player player(String who) {
        Player p=getServer().getPlayerExact("Epic"+who);
        require(p!=null&&p.isOnline()&&p.getUniqueId().equals(uuid(who))&&!p.isOp(),"Need actual non-OP client "+who);
        return p;
    }
    private NationId country(String who) {return NationId.fromString(getConfig().getString("nations."+who));}
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
    private String memberInstance(){return Objects.requireNonNull(getConfig().getString("membership-instance"));}
    private CompletionStage<Void> rejected(CompletionStage<?> action,String reason){return action.handle((value,error)->{
        require(error!=null,reason);log("EXPECTED_REJECTION",Map.of("reason",reason));return null;
    });}
    private CompletionStage<?> moveMember(String from,String to){return main(()->{
        var nations=service(NationService.class);
        require(nations.removeMember(country(from),uuid("D")),"Native member removal rejected");
        require(nations.addMember(country(to),uuid("D"),player("D").getName()),"Native member admission rejected");
        log("MEMBER_NATIVE_MOVE",Map.of("player",uuid("D"),"from",country(from),"to",country(to)));return null;
    });}
    private CompletionStage<?> membershipFirst(){
        var event=new WorldEvent("acceptance:membership","MEMBERSHIP_ACCEPTANCE",Instant.now(),Set.of(uuid("B"),uuid("D")),
                Set.of(country("A").toString(),country("B").toString()),Map.of("time_basis","ACCEPTANCE_TEST"),"AcceptanceFixture","Explicit isolated test story; membership changes use native services");
        return api.submit(event).thenCompose(report->api.instances(uuid("D"))).thenCompose(items->{
            var member=items.stream().filter(i->i.definition().id().equals("membership-acceptance")).findFirst().orElseThrow();
            return main(()->{getConfig().set("membership-instance",member.id());saveConfig();log("MEMBER_INSTANCE",Map.of("id",member.id()));return null;});
        }).thenCompose(x->api.choose(memberInstance(),uuid("D"),"vote","yes"))
          .thenCompose(x->moveMember("A","B"))
          .thenCompose(x->api.memberReview(memberInstance(),uuid("D")))
          .thenCompose(review->{require(review.orElseThrow().status()==MemberReviewLedger.Status.PENDING,"Drift must require review");
              return rejected(api.revalidateMember(memberInstance(),uuid("D")),"changed nationality cannot approve original membership");})
          .thenCompose(x->api.instances(uuid("D"))).thenCompose(items->{
              require(items.stream().filter(i->i.id().equals(memberInstance())).findFirst().orElseThrow().votes().isEmpty(),"Drifted personal vote still counted");
              return moveMember("B","A");})
          .thenCompose(x->api.revalidateMember(memberInstance(),uuid("D")))
          .thenCompose(x->api.exitMember(memberInstance(),uuid("D"),"vote"))
          .thenCompose(x->api.instances(uuid("D"))).thenApply(items->{
              require(items.stream().noneMatch(i->i.id().equals(memberInstance())),"Polling silently rejoined exited member");
              log("MEMBER_EXITED",Map.of("instance",memberInstance(),"ordinaryMember",uuid("D"),"nativeAuthority",true));return null;
          });
    }
    private CompletionStage<?> membershipSecond(){return api.instances(uuid("D")).thenCompose(items->{
        require(items.stream().noneMatch(i->i.id().equals(memberInstance())),"Restart silently rejoined exited member");
        return api.memberReview(memberInstance(),uuid("D"));
    }).thenCompose(review->{require(review.orElseThrow().status()==MemberReviewLedger.Status.EXITED,"Exit review not durable");return api.join(memberInstance(),uuid("D"));})
      .thenApply(report->{log("MEMBER_REJOINED",Map.of("instance",memberInstance(),"explicit",true));return report;});}
    private CompletionStage<?> membershipFinish(){return api.choose(memberInstance(),uuid("D"),"vote","yes")
        .thenCompose(x->api.choose(memberInstance(),uuid("B"),"vote","yes"))
        .thenCompose(x->rejected(api.exitMember(memberInstance(),uuid("D"),"done"),"completed history cannot be rewritten by exit"))
        .thenCompose(x->membershipCheck());}
    private CompletionStage<?> membershipCheck(){return api.instances(uuid("D")).thenApply(items->{
        var member=items.stream().filter(i->i.id().equals(memberInstance())).findFirst().orElseThrow();
        require(member.completed()&&member.members().containsKey(uuid("D")),"Completed membership missing after restart");
        log("MEMBER_COMPLETED",member);return null;
    });}
    private CompletionStage<?> contributionsCheck(){return api.contributions(null,null,0,100).thenApply(entries->{
        require(entries.size()==8,"Expected eight projections from four native aid receipts, got "+entries.size());
        for(String who:List.of("A","B"))for(String subject:List.of("player:"+uuid(who),"nation:"+country(who))) {
            var rows=entries.stream().filter(e->e.request().subjectId().equals(subject)).toList();
            require(rows.size()==2&&rows.stream().allMatch(e->e.request().amount().compareTo(BigDecimal.TEN)==0),"Incorrect contribution subject or amount: "+subject);
            require(rows.stream().map(e->e.request().unit()).collect(java.util.stream.Collectors.toSet()).equals(Set.of("treasury","resource.agricultural")),"Contribution units mixed");
        }
        log("CONTRIBUTIONS_CHECKED",entries);return null;
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
    private CompletionStage<?> contributions() {
        return api.contributions(null,null,0,100).thenCompose(entries->{
            require(entries.size()==8,"Two treasury and two resource transfers must yield eight attributed contributions");
            require(entries.stream().map(e->e.request().sourceEventId()).distinct().count()==4,"Expected four canonical aid events");
            log("CONTRIBUTIONS",entries);
            CompletionStage<?> chain=CompletableFuture.completedFuture(null);
            for(String who:List.of("A","B"))for(String unit:List.of("treasury","agricultural"))
                chain=chain.thenCompose(x->api.contributionTotal("player:"+uuid(who),io.github.addxiaoyi.starchronicle.api.contribution.ContributionType.RESOURCE_AID,unit))
                        .thenApply(total->{require(total.compareTo(BigDecimal.TEN)==0,"Incorrect contribution total");return null;});
            return chain;
        });
    }
    private CompletionStage<?> ledgerRejected(CompletionStage<?> work,String reason) {
        return work.handle((value,error)->{require(error!=null,"Expected rejection: "+reason);
            log("MEMBER_REVIEW_REJECTED",Map.of("reason",reason,"error",error.toString()));return null;});
    }
    private CompletionStage<?> ledgerMoveMember(String from,String to) {return main(()->{
        var nations=service(NationService.class);
        require(nations.removeMember(country(from),uuid("D")),"Native member removal rejected");
        require(nations.addMember(country(to),uuid("D"),player("D").getName()),"Native member admission rejected");
        log("MEMBER_NATIVE_MOVED",Map.of("player",uuid("D"),"from",country(from),"to",country(to)));return null;
    });}
    private CompletionStage<?> memberExit() {
        return ledgerMoveMember("A","B")
                .thenCompose(x->ledgerRejected(api.revalidateMember(instance,uuid("D")),"changed nation"))
                .thenCompose(x->ledgerMoveMember("B","A"))
                .thenCompose(x->main(()->{permissions.get(uuid("D")).setPermission("starchronicle.use",false);player("D").recalculatePermissions();return null;}))
                .thenCompose(x->ledgerRejected(api.revalidateMember(instance,uuid("D")),"missing permission"))
                .thenCompose(x->main(()->{permissions.get(uuid("D")).setPermission("starchronicle.use",true);player("D").recalculatePermissions();return null;}))
                .thenCompose(x->api.revalidateMember(instance,uuid("D")))
                .thenCompose(x->api.memberReviews(uuid("D"),100)).thenApply(reviews->{
                    require(reviews.stream().anyMatch(r->r.instanceId().equals(instance)&&r.status().name().equals("APPROVED")&&r.reason().contains("StarCore.NationModule.membership")),"Ordinary member requires real authority proof");
                    log("MEMBER_REVALIDATED",reviews);return null;
                }).thenCompose(x->api.exitMember(instance,uuid("D")))
                .thenCompose(x->api.instances(uuid("D"))).thenApply(items->{
                    require(items.stream().noneMatch(i->i.id().equals(instance)),"Background resume restored an exited member");
                    log("MEMBER_EXITED",Map.of("player",uuid("D"),"instance",instance));return null;
                });
    }
    private CompletionStage<?> memberRejoin() {
        return api.memberReviews(uuid("D"),100).thenCompose(reviews->{
            require(reviews.stream().anyMatch(r->r.instanceId().equals(instance)&&r.status().name().equals("EXITED")),"Exit was not durable across restart");
            return api.instances(uuid("D"));
        }).thenCompose(items->{
            require(items.stream().noneMatch(i->i.id().equals(instance)),"Restart resume restored exited member");
            return api.join(instance,uuid("D"));
        }).thenCompose(x->api.instances(uuid("D"))).thenApply(items->{
            require(items.stream().anyMatch(i->i.id().equals(instance)),"Explicit native-checked rejoin did not succeed");
            log("MEMBER_REJOINED",Map.of("player",uuid("D"),"instance",instance,"afterRestart",boot>1));return null;
        });
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        if(!(sender instanceof ConsoleCommandSender)||args.length==0)return false;
        String action=args[0];if(!busy.compareAndSet(false,true)){fail(action,new IllegalStateException("Previous action is still pending"));return true;}
        try {
            CompletionStage<?> work=switch(action) {
                case "seed"->seed();case "open"->open();case "task-ready"->taskReady(args[1]);case "task-complete"->taskComplete(args[1]);
                case "preparations"->preparations();case "advance"->advanceStory();case "dungeon"->dungeonEnter(args[1]);
                case "found"->founding();case "declare"->declareWar();case "end-war"->endWar();
                case "checkpoint"->captureFinal();case "final-evidence"->finalEvidence();case "settle"->settle();
                case "check"->check();case "replay"->replayRejection();case "state"->campaign().thenApply(s->{log("STATE",s);return s;});
                case "membership-first"->membershipFirst();case "membership-second"->membershipSecond();
                case "membership-finish"->membershipFinish();case "membership-check"->membershipCheck();case "contributions-check"->contributionsCheck();
                case "contributions"->contributions();case "member-exit"->memberExit();case "member-rejoin"->memberRejoin();
                default->throw new IllegalArgumentException("Unknown action: "+action);
            };
            work.whenComplete((value,error)->{busy.set(false);if(error!=null)fail(action,error);else log("ACTION_DONE",Map.of("action",action));});
        } catch(Throwable error) {busy.set(false);fail(action,error);}return true;
    }
}
