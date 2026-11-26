package smoke;

import dev.starcore.starcore.module.nation.NationService;
import dev.starcore.starcore.module.nation.model.NationId;
import dev.starcore.starcore.module.resource.*;
import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.api.resource.ResourceHistoryReceipt;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.command.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Real native resource APIs and offline nations, without source database mutations. */
public final class ResourceHistoryPaperFixture extends JavaPlugin implements CommandExecutor {
    private Path run;
    private ResourceService resources;
    private ResourceHistoryService history;
    private NationResourceTransactionService transactions;
    private ChronicleService api;
    private NationId payer, receiver;
    private int boot, sequence;
    private boolean busy;

    @Override public void onEnable() {
        try {
            run=getDataFolder().toPath().toAbsolutePath().normalize().getParent().getParent();
            require(run.getFileName().toString().startsWith("paper-resource-"),"Isolated directory required");
            require(hash(run.resolve("plugins/StarChronicle.jar")).equals(getConfig().getString("product-sha")),"Chronicle hash drift");
            require(hash(run.resolve("plugins/StarCore.jar")).equals(getConfig().getString("native-sha")),"Native hash drift");
            Plugin core=Objects.requireNonNull(getServer().getPluginManager().getPlugin("StarCore"));
            Object context=core.getClass().getMethod("context").invoke(core);
            Object registry=context.getClass().getMethod("serviceRegistry").invoke(context);
            resources=service(registry,ResourceService.class);history=service(registry,ResourceHistoryService.class);
            transactions=service(registry,NationResourceTransactionService.class);
            var nations=service(registry,NationService.class);
            api=Objects.requireNonNull(getServer().getServicesManager().load(ChronicleService.class));
            boot=getConfig().getInt("boot")+1;getConfig().set("boot",boot);
            if(boot==1) {
                require(history.resourceChangesAfter(0,128).isEmpty(),"Fresh source log required");
                payer=nations.createNation(id("ResourcePayer"),"ResourcePayer","ResourcePayer").id();
                receiver=nations.createNation(id("ResourceReceiver"),"ResourceReceiver","ResourceReceiver").id();
                getConfig().set("payer",payer.toString());getConfig().set("receiver",receiver.toString());
                require(resources.grant(payer,"mineral",10000),"Initial mineral grant failed");
                require(resources.grant(payer,"energy",10000),"Initial energy grant failed");
                require(resources.grant(receiver,"mineral",10000),"Receiver grant failed");
                sequence=3;sourceLog();
            } else {
                payer=NationId.fromString(getConfig().getString("payer"));receiver=NationId.fromString(getConfig().getString("receiver"));
                sequence=receipts().size();
                require(nations.nationById(payer).isPresent()&&nations.nationById(receiver).isPresent(),"Native nations did not survive restart");
            }
            saveConfig();Objects.requireNonNull(getCommand("resourceverify")).setExecutor(this);
            log("READY",Map.of("domain",history.historyAuthorityDomainId().orElseThrow(),"payer",payer.toString(),"receiver",receiver.toString()));
            rejections();
        } catch(Throwable error) {fail(error);getServer().getPluginManager().disablePlugin(this);}
    }
    private static UUID id(String name){return UUID.nameUUIDFromBytes(("OfflinePlayer:"+name).getBytes(StandardCharsets.UTF_8));}
    private static <T>T service(Object registry,Class<T> type)throws Exception {
        return type.cast(((Optional<?>)registry.getClass().getMethod("find",Class.class).invoke(registry,type)).orElseThrow());
    }
    private List<ResourceChangeReceipt> receipts() {
        List<ResourceChangeReceipt> result=new ArrayList<>();long after=0;
        while(true) {
            var page=history.resourceChangesAfter(after,128);result.addAll(page);
            require(result.size()<=256,"Fixture source exceeded bounded scenario");
            if(page.size()<128)return List.copyOf(result);
            after=page.getLast().sequence();
        }
    }
    private NationResourceTransactionService.TransactionRequest request(int step, boolean grant) {
        return new NationResourceTransactionService.TransactionRequest("resource-fixture:transaction:"+step,
                grant?receiver:payer,Map.of("energy",grant?3L:2L),NationResourceTransactionService.Category.OTHER,"Fixture native transaction");
    }
    private NationResourceTransactionService.TransferRequest transfer(int step) {
        return new NationResourceTransactionService.TransferRequest("resource-fixture:transfer:"+step,payer,receiver,
                Map.of("mineral",1L,"energy",1L),NationResourceTransactionService.Category.OTHER,"Fixture native transfer");
    }
    private void change() {
        require(getServer().getOnlinePlayers().isEmpty(),"Fixture requires offline identities only");
        int next=sequence+1;
        switch(next%5) {
            case 0 -> require(resources.grant(payer,"mineral",2),"Grant failed");
            case 1 -> require(resources.consume(payer,"mineral",1),"Consume failed");
            case 2 -> require(transactions.grant(request(next,true)).status()==NationResourceTransactionService.Status.APPLIED,"Transaction grant failed");
            case 3 -> require(transactions.consume(request(next,false)).status()==NationResourceTransactionService.Status.APPLIED,"Transaction consume failed");
            case 4 -> require(transactions.transfer(transfer(next)).status()==NationResourceTransactionService.TransferStatus.APPLIED,"Transfer failed");
        }
        sequence=next;
    }
    private ResourceHistoryReceipt convert(ResourceChangeReceipt r) {
        return new ResourceHistoryReceipt(r.authorityDomainId(),r.sequence(),r.operationId(),ResourceHistoryReceipt.Kind.valueOf(r.kind().name()),
                r.reference(),r.category(),r.description(),r.actorId(),r.committedAt(),r.balances().stream().map(b ->
                new ResourceHistoryReceipt.BalanceChange(UUID.fromString(b.nationId().toString()),b.resourceType(),b.before(),b.after())).toList());
    }
    private void sourceLog() {
        var records=receipts();require(records.size()==sequence,"Native sequence differs from calls");
        log("NATIVE",Map.of("count",records.size(),"records",records.stream().map(this::convert).toList(),
                "payer",resources.stockpile(payer),"receiver",resources.stockpile(receiver)));
    }
    private void mutate(int remaining,String action) {
        try {
            int count=Math.min(5,remaining);for(int i=0;i<count;i++)change();
            if(remaining>count)getServer().getScheduler().runTaskLater(this,()->mutate(remaining-count,action),1);
            else {busy=false;sourceLog();log("DONE",Map.of("action",action));}
        }catch(Throwable error){busy=false;fail(error);}
    }
    private void rejections() {
        var before=receipts();
        require(!resources.grant(payer,"unknown",10),"Unknown resource accepted");
        require(!resources.consume(receiver,"strategic",1),"Insufficient inventory accepted");
        require(!resources.grant(payer,"mineral",Long.MAX_VALUE),"Unsafe grant accepted");
        int replays=0;
        for(var r:before) {
            if(r.kind()==ResourceChangeReceipt.Kind.TRANSFER) {
                require(transactions.transfer(transfer(Math.toIntExact(r.sequence()))).status()==NationResourceTransactionService.TransferStatus.ALREADY_APPLIED,"Transfer replay changed inventory");replays++;
            } else if(r.kind()==ResourceChangeReceipt.Kind.TRANSACTION_GRANT||r.kind()==ResourceChangeReceipt.Kind.TRANSACTION_CONSUME) {
                boolean grant=r.kind()==ResourceChangeReceipt.Kind.TRANSACTION_GRANT;var q=request(Math.toIntExact(r.sequence()),grant);
                var result=grant?transactions.grant(q):transactions.consume(q);
                require(result.status()==NationResourceTransactionService.Status.ALREADY_APPLIED,"Transaction replay changed inventory");replays++;
            }
        }
        require(before.equals(receipts()),"Rejected or retried operations added source history");
        log("REPLAY",Map.of("count",before.size(),"replays",replays,"rejections",3));
    }
    private CompletionStage<?> check(int count) {
        var records=receipts();require(records.size()==count,"Unexpected native source count");
        return api.history(null,null,0,100).thenCombine(api.history(null,null,1,100),(first,second)->{
            List<WorldEvent> all=new ArrayList<>(first);all.addAll(second);
            var events=all.stream().filter(e->e.facts().getOrDefault("history.namespace","").equals("starcore.resources")).toList();
            require(events.size()==count,"Unexpected committed history count="+events.size());return events;
        }).thenCompose(events->{
            CompletionStage<List<ResourceHistoryReceipt>> chain=CompletableFuture.completedFuture(new ArrayList<>());
            for(var nativeReceipt:records) {
                var expected=convert(nativeReceipt);String eventId="starcore:resource:"+expected.authorityDomain()+":"+expected.operationId();
                var event=events.stream().filter(e->e.id().equals(eventId)).findFirst().orElseThrow();
                require(event.occurredAt().equals(expected.committedAt())&&event.participants().isEmpty(),"Timestamp or actor attribution changed");
                require(event.type().equals("RESOURCE_BALANCE_CHANGED"),"Wrong event kind");
                require(event.facts().get("history.sequence").equals(Long.toString(expected.sequence())),"Wrong source sequence");
                chain=chain.thenCompose(receipts->api.resourceHistoryReceipt(eventId).thenApply(found->{
                    require(found.orElseThrow().equals(expected),"Public full resource receipt differs from native");receipts.add(found.orElseThrow());return receipts;
                }));
            }
            return chain.thenApply(receipts->{log("CHECK",Map.of("count",count,"events",events,"receipts",receipts));return count;});
        });
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        if(!(sender instanceof ConsoleCommandSender))return false;
        try {
            require(!busy,"Mutation already active");
            switch(args[0]) {
                case "check" -> check(Integer.parseInt(args[1])).whenComplete((value,error)->{if(error!=null)fail(error);else log("DONE",Map.of("action","check","count",value));});
                case "burst" -> {int count=Integer.parseInt(args[1]);require(count>=1&&count<=130,"Invalid count");busy=true;mutate(count,"burst");}
                case "offline" -> {
                    require(getServer().getOnlinePlayers().isEmpty(),"Offline maintenance required");
                    getServer().getPluginManager().disablePlugin(Objects.requireNonNull(getServer().getPluginManager().getPlugin("StarChronicle")));
                    busy=true;mutate(130,"offline");
                }
                default -> throw new IllegalArgumentException("Unknown fixture command");
            }
        } catch(Throwable error) {fail(error);}
        return true;
    }
    private void fail(Throwable error) {while(error.getCause()!=null)error=error.getCause();log("FAILED",Map.of("error",error.toString()));error.printStackTrace();}
    private synchronized void log(String kind,Object data) {
        getLogger().info("RESOURCE_"+kind+" "+SqliteStore.json(data));
        try {Files.writeString(run.resolve("evidence/fixture.jsonl"),SqliteStore.json(Map.of("time",Instant.now().toString(),"boot",boot,"kind",kind,"data",data))+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
        catch(Exception error){throw new IllegalStateException(error);}
    }
    private static String hash(Path file)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));}
    private static void require(boolean value,String reason){if(!value)throw new IllegalStateException(reason);}
    @Override public void onDisable(){if(run!=null)log("CLOSED",Map.of("normalDisable",true));}
}
