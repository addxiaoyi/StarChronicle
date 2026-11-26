package smoke;

import dev.starcore.starcore.module.nation.NationService;
import dev.starcore.starcore.module.nation.model.NationId;
import dev.starcore.starcore.module.resource.*;
import dev.starcore.starcore.module.resource.model.*;
import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.api.processing.ProcessingHistoryReceipt;
import io.github.addxiaoyi.starchronicle.api.resource.ResourceHistoryReceipt;
import io.github.addxiaoyi.starchronicle.api.contribution.*;
import io.github.addxiaoyi.starchronicle.history.ProcessingHistoryProjection;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.command.*;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Real services and real connected player commands; never edits native or Chronicle databases. */
public final class ProcessingHistoryPaperFixture extends JavaPlugin implements CommandExecutor,Listener {
    private Path run; private ResourceService resources; private ResourceHistoryService resourceHistory;
    private ProcessingService processing; private ProcessingHistoryService history; private NationService nations;
    private ChronicleService api; private NationId nation; private int boot; private boolean busy;
    private final UUID a=id("ProduceA"),b=id("ProduceB"),factory=id("FactoryOne"),first=id("ProductionFirst"),longRun=id("ProductionRestart"),last=id("ProductionLast");
    @Override public void onEnable() {
        try {
            run=getDataFolder().toPath().toAbsolutePath().normalize().getParent().getParent();
            require(run.getFileName().toString().startsWith("paper-processing-"),"Isolated directory required");
            require(hash(run.resolve("plugins/StarChronicle.jar")).equals(getConfig().getString("product-sha")),"Chronicle hash drift");
            require(hash(run.resolve("plugins/StarCore.jar")).equals(getConfig().getString("native-sha")),"Native hash drift");
            Plugin core=Objects.requireNonNull(getServer().getPluginManager().getPlugin("StarCore"));
            Object context=core.getClass().getMethod("context").invoke(core),registry=context.getClass().getMethod("serviceRegistry").invoke(context);
            resources=service(registry,ResourceService.class);resourceHistory=service(registry,ResourceHistoryService.class);
            processing=service(registry,ProcessingService.class);history=service(registry,ProcessingHistoryService.class);nations=service(registry,NationService.class);
            api=Objects.requireNonNull(getServer().getServicesManager().load(ChronicleService.class));
            boot=getConfig().getInt("boot")+1;getConfig().set("boot",boot);
            processing.registerRecipe(new ProcessingRecipe("acceptance-smelt","Acceptance smelt",Map.of("mineral",3L),Map.of("industrial",2L,"chemical",1L),2,1,1));
            processing.registerRecipe(new ProcessingRecipe("acceptance-long","Frozen restart recipe",Map.of("mineral",3L),Map.of("industrial",boot==1?2L:999L,"chemical",1L),boot==1?31:3600,1,1));
            if(boot==1) {
                require(history.processingChangesAfter(0,128).isEmpty(),"Fresh processing source required");
                nation=nations.createNation(a,"ProduceA","ProductionNation").id();require(nations.addMember(nation,b,"ProduceB"),"Member setup failed");
                getConfig().set("nation",nation.toString());
                require(resources.grant(nation,"mineral",20000)&&resources.grant(nation,"energy",20000),"Seed failed");
                require(processing.autoProcess(id("Baseline"),nation,"acceptance-smelt",1).isPresent(),"Baseline failed");
            } else {
                nation=NationId.fromString(getConfig().getString("nation"));
                require(processing.getFactory(factory).isPresent(),"Actual ResourceModule enable did not restore factory");
                if(boot==2) {
                    var active=processing.getFactory(factory).orElseThrow().processingRun();
                    require(active!=null&&active.runId().equals(longRun)&&active.actorId().equals(b)&&active.outputs().get("industrial")==2L,"Frozen active run was lost or replaced by new recipe");
                    require(active.readyAt().equals(active.startedAt().plusSeconds(30)),"Frozen duration changed");
                }
            }
            saveConfig();getServer().getPluginManager().registerEvents(this,this);Objects.requireNonNull(getCommand("processingverify")).setExecutor(this);
            Files.writeString(run.resolve("evidence/targets.json"),SqliteStore.json(Map.of("a",a,"b",b,"factory",factory,"first",first,"long",longRun,"last",last,"nation",nation.toString())));
            sourceLog();log("READY",Map.of("domain",history.processingHistoryAuthorityDomainId().orElseThrow(),"nation",nation.toString(),"boot",boot));
        }catch(Throwable error){fail(error);getServer().getPluginManager().disablePlugin(this);}
    }
    @EventHandler public void join(PlayerJoinEvent e) {
        require(Set.of(a,b).contains(e.getPlayer().getUniqueId())&&!e.getPlayer().isOp(),"Unexpected player or operator");
        e.getPlayer().addAttachment(this,"starcore.resource",true);
        log("JOIN",Map.of("player",e.getPlayer().getUniqueId(),"name",e.getPlayer().getName(),"op",e.getPlayer().isOp()));
    }
    @EventHandler(priority=EventPriority.MONITOR) public void command(PlayerCommandPreprocessEvent e) {
        if(e.getMessage().startsWith("/resource factory"))log("PLAYER_COMMAND",Map.of("player",e.getPlayer().getUniqueId(),"command",e.getMessage(),"cancelled",e.isCancelled(),"op",e.getPlayer().isOp()));
    }
    private static UUID id(String s){return UUID.nameUUIDFromBytes(("OfflinePlayer:"+s).getBytes(StandardCharsets.UTF_8));}
    private static <T>T service(Object registry,Class<T> t)throws Exception {return t.cast(((Optional<?>)registry.getClass().getMethod("find",Class.class).invoke(registry,t)).orElseThrow());}
    private List<ProcessingChangeReceipt> receipts() {
        List<ProcessingChangeReceipt> result=new ArrayList<>();long cursor=0;
        while(true){var page=history.processingChangesAfter(cursor,128);result.addAll(page);require(result.size()<512,"Unexpected processing count");if(page.size()<128)return List.copyOf(result);cursor=page.getLast().sequence();}
    }
    private List<ResourceChangeReceipt> resourceReceipts() {
        List<ResourceChangeReceipt> result=new ArrayList<>();long cursor=0;
        while(true){var page=resourceHistory.resourceChangesAfter(cursor,128);result.addAll(page);require(result.size()<512,"Unexpected resource count");if(page.size()<128)return List.copyOf(result);cursor=page.getLast().sequence();}
    }
    private ProcessingHistoryReceipt convert(ProcessingChangeReceipt r) {
        var x=r.run();return new ProcessingHistoryReceipt(r.authorityDomainId(),r.sequence(),ProcessingHistoryReceipt.Kind.valueOf(r.kind().name()),
                new ProcessingHistoryReceipt.Run(x.runId(),x.factoryId(),UUID.fromString(x.nationId().toString()),x.actorId(),x.recipeId(),x.batches(),x.inputs(),x.outputs(),x.startedAt(),x.readyAt(),x.completedAt()),r.committedAt());
    }
    private ResourceHistoryReceipt convert(ResourceChangeReceipt r) {
        return new ResourceHistoryReceipt(r.authorityDomainId(),r.sequence(),r.operationId(),ResourceHistoryReceipt.Kind.valueOf(r.kind().name()),r.reference(),r.category(),r.description(),r.actorId(),r.committedAt(),
                r.balances().stream().map(x->new ResourceHistoryReceipt.BalanceChange(UUID.fromString(x.nationId().toString()),x.resourceType(),x.before(),x.after())).toList());
    }
    private void sourceLog() {
        var records=receipts();Map<String,Object> data=new TreeMap<>();data.put("count",records.size());data.put("records",records.stream().map(this::convert).toList());
        data.put("resources",resourceReceipts().stream().map(this::convert).toList());data.put("stockpile",resources.stockpile(nation));data.put("factory",processing.getFactory(factory).orElse(null));
        data.put("founder",nations.nationById(nation).orElseThrow().founderId());log("NATIVE",data);
    }
    private void check(int count) {
        var records=receipts().stream().map(this::convert).toList();require(records.size()==count,"Unexpected source count="+records.size());
        CompletionStage<List<ProcessingHistoryReceipt>> chain=CompletableFuture.completedFuture(new ArrayList<>());
        for(var r:records)chain=chain.thenCompose(all->api.processingHistoryReceipt(ProcessingHistoryProjection.eventId(r)).thenApply(proof->{require(proof.orElseThrow().equals(r),"Public full receipt mismatch");all.add(proof.orElseThrow());return all;}));
        chain.thenCompose(all->api.contributions(null,ContributionType.CIVILIZATION,0,100).thenApply(contributions->{
            var expected=records.stream().flatMap(r->ProcessingHistoryProjection.contributions(r).stream()).toList();
            require(contributions.size()==expected.size(),"Unexpected contribution count");
            for(var request:expected)require(contributions.stream().anyMatch(c->c.request().equals(request)),"Missing exact original production contribution");
            log("CHECK",Map.of("count",count,"receipts",all,"contributions",contributions));return expected;
        })).whenComplete((expected,error)->{if(error!=null)fail(error);else log("DONE",Map.of("action","check","count",count));});
    }
    private void burst(int i) {
        try {
            for(int j=0;j<5&&i+j<130;j++)require(processing.autoProcess(id("OfflineBatch"+(i+j)),nation,"acceptance-smelt",1).isPresent(),"Offline conversion failed");
            if(i+5<130)getServer().getScheduler().runTaskLater(this,()->burst(i+5),1);
            else {busy=false;sourceLog();log("DONE",Map.of("action","offline"));}
        }catch(Throwable error){busy=false;fail(error);}
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        if(!(sender instanceof ConsoleCommandSender))return false;
        try {
            require(!busy,"Busy");
            switch(args[0]) {
                case "check" -> {sourceLog();check(Integer.parseInt(args[1]));}
                case "handover" -> {UUID from=args[1].equals("B")?a:b,to=args[1].equals("B")?b:a;
                    require(nations.transferOwnership(nation,from,to,args[1].equals("B")?"ProduceB":"ProduceA"),"Real leadership transfer failed");
                    log("HANDOVER",Map.of("from",from,"to",to));log("DONE",Map.of("action","handover"));}
                case "offline" -> {
                    require(getServer().getOnlinePlayers().isEmpty(),"Disconnect players before provider maintenance");
                    var active=processing.getFactory(factory).orElseThrow().processingRun();require(active!=null&&active.runId().equals(longRun),"Restart scenario must retain active run");
                    getServer().getPluginManager().disablePlugin(Objects.requireNonNull(getServer().getPluginManager().getPlugin("StarChronicle")));
                    busy=true;burst(0);
                }
                case "replay" -> {
                    var before=resources.stockpile(nation);var all=receipts();int n=0;
                    for(var r:all)if(r.run().completedAt()!=null){require(processing.completeProcessingRun(r.run().runId()).orElseThrow().equals(r.run()),"Completed run replay changed proof");n++;}
                    require(before.equals(resources.stockpile(nation))&&all.equals(receipts()),"Replay mutated assets/history");
                    sourceLog();log("REPLAY",Map.of("count",all.size(),"replayed",n));log("DONE",Map.of("action","replay"));
                }
                default -> throw new IllegalArgumentException("Unknown fixture command");
            }
        }catch(Throwable error){fail(error);}return true;
    }
    private void fail(Throwable error){while(error.getCause()!=null)error=error.getCause();log("FAILED",Map.of("error",error.toString()));error.printStackTrace();}
    private synchronized void log(String kind,Object data) {
        getLogger().info("PROCESSING_"+kind+" "+SqliteStore.json(data));
        try{Files.writeString(run.resolve("evidence/fixture.jsonl"),SqliteStore.json(Map.of("time",Instant.now().toString(),"boot",boot,"kind",kind,"data",data))+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(Exception error){throw new IllegalStateException(error);}
    }
    private static String hash(Path file)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));}
    private static void require(boolean ok,String reason){if(!ok)throw new IllegalStateException(reason);}
    @Override public void onDisable(){if(run!=null)log("CLOSED",Map.of("normalDisable",true));}
}
