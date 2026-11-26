package smoke;

import dev.starcore.starcore.foundation.economy.EconomyService;
import dev.starcore.starcore.foundation.economy.transaction.TransactionFabric;
import dev.starcore.starcore.foundation.economy.transaction.EconomyTransactionState;
import dev.starcore.starcore.module.nation.NationService;
import dev.starcore.starcore.module.nation.model.NationId;
import dev.starcore.starcore.module.resource.*;
import dev.starcore.starcore.module.resource.model.*;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.api.contribution.*;
import io.github.addxiaoyi.starchronicle.api.trade.TradeHistoryReceipt;
import java.util.concurrent.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.bukkit.command.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Native settlements and public Chronicle net contribution acceptance on isolated Paper.
 * Status corrections use the native admin history API; they do not claim actual asset refunds.
 * Actors are offline fixture accounts; this does not certify a player command/UI flow. */
public final class TradeContributionPaperFixture extends JavaPlugin implements CommandExecutor {
    private Path run;
    private TradeHistoryService history;
    private PlayerTradeService trades;
    private MarketOrderBookService market;
    private ResourceService resources;
    private EconomyService economy;
    private TransactionFabric fabric;
    private ChronicleService api;
    private UUID buyer, seller;
    private NationId buyerNation, sellerNation;
    private int boot;
    private static final String RESOURCE="industrial";

    @Override public void onEnable() {
        try {
            run=getDataFolder().toPath().toAbsolutePath().normalize().getParent().getParent();
            require(run.getFileName().toString().startsWith("paper-trade-contribution-"),"Isolated directory required");
            require(hash(run.resolve("plugins/StarChronicle.jar")).equals(getConfig().getString("product-sha")),"Chronicle hash drift");
            require(hash(run.resolve("plugins/StarCore.jar")).equals(getConfig().getString("native-sha")),"Native hash drift");
            Plugin core=Objects.requireNonNull(getServer().getPluginManager().getPlugin("StarCore"));
            Object context=core.getClass().getMethod("context").invoke(core);
            Object registry=context.getClass().getMethod("serviceRegistry").invoke(context);
            history=service(registry,TradeHistoryService.class);trades=service(registry,PlayerTradeService.class);
            market=service(registry,MarketOrderBookService.class);resources=service(registry,ResourceService.class);
            economy=service(registry,EconomyService.class);fabric=service(registry,TransactionFabric.class);
            api=Objects.requireNonNull(getServer().getServicesManager().load(ChronicleService.class));
            buyer=UUID.nameUUIDFromBytes("TradeHistoryBuyer".getBytes(StandardCharsets.UTF_8));
            seller=UUID.nameUUIDFromBytes("TradeHistorySeller".getBytes(StandardCharsets.UTF_8));
            boot=getConfig().getInt("boot")+1;getConfig().set("boot",boot);
            if(boot==1) {
                require(history.tradeChangesAfter(0,128).isEmpty(),"Fresh source required");
                NationService nations=service(registry,NationService.class);
                buyerNation=nations.createNation(buyer,"TradeBuyer","TradeBuyerNation").id();
                sellerNation=nations.createNation(seller,"TradeSeller","TradeSellerNation").id();
                getConfig().set("buyer-nation",buyerNation.toString());getConfig().set("seller-nation",sellerNation.toString());
                economy.createAccount(buyer);economy.createAccount(seller);
                require(economy.setBalance(buyer,new BigDecimal("100000.00")),"Buyer seed failed");
                require(economy.setBalance(seller,new BigDecimal("10000.00")),"Seller seed failed");
                require(resources.grant(sellerNation,RESOURCE,1000),"Seller resource seed failed");
                getConfig().set("opening",SqliteStore.json(balances()));
            } else {
                buyerNation=NationId.fromString(getConfig().getString("buyer-nation"));
                sellerNation=NationId.fromString(getConfig().getString("seller-nation"));
            }
            if(boot==1)initial();
            saveConfig();Objects.requireNonNull(getCommand("tradeverify")).setExecutor(this);
            log("READY",Map.of("domain",history.historyAuthorityDomainId().toString(),"buyer",buyer.toString(),"seller",seller.toString(),
                "buyerNation",buyerNation.toString(),"sellerNation",sellerNation.toString(),"balances",balances(),"opening",getConfig().getString("opening")));
        } catch(Throwable error){fail(error);getServer().getPluginManager().disablePlugin(this);}
    }
    private static <T>T service(Object registry,Class<T> type)throws Exception {
        return type.cast(((Optional<?>)registry.getClass().getMethod("find",Class.class).invoke(registry,type)).orElseThrow());
    }
    private Map<String,Object> balances() {
        return Map.of("buyerMoney",economy.getBalance(buyer).toPlainString(),"sellerMoney",economy.getBalance(seller).toPlainString(),
            "buyerResource",resources.amount(buyerNation,RESOURCE),"sellerResource",resources.amount(sellerNation,RESOURCE));
    }
    private TradeRecord direct(long amount) {
        var offer=trades.createTradeOffer(buyer,buyerNation,seller,sellerNation,RESOURCE,amount,2.0,300).orElseThrow();
        var result=trades.acceptTradeResult(seller,offer.offerId());
        require(result.status()==PlayerTradeService.ActionStatus.APPLIED&&result.record()!=null,"Native direct trade rejected: "+result);
        var before=balances();
        var replay=trades.acceptTradeResult(seller,offer.offerId());
        require(replay.status()==PlayerTradeService.ActionStatus.APPLIED&&replay.record().recordId().equals(result.record().recordId()),"Receipt replay failed");
        require(before.equals(balances()),"Replay mutated assets");
        require(trades.acceptTradeResult(UUID.randomUUID(),offer.offerId()).status()==PlayerTradeService.ActionStatus.UNAUTHORIZED,"Receipt replay leaked to unrelated actor");
        return result.record();
    }
    private void initial() {
        require(boot==1&&history.tradeChangesAfter(0,128).isEmpty(),"Initial sequence must be fresh");
        getConfig().set("first-record",direct(5).recordId().toString());
        var sold=trades.quickSellResult(buyer,buyerNation,RESOURCE,2);
        require(sold.status()==PlayerTradeService.ActionStatus.APPLIED&&sold.record()!=null,"Native quick sell rejected: "+sold);
        market.submitBuyOrder(buyer,buyerNation,RESOURCE,3,2.0,Instant.now().plusSeconds(300));
        market.submitSellOrder(seller,sellerNation,RESOURCE,3,2.0,Instant.now().plusSeconds(300));
        require(market.matchOrders(RESOURCE)==1,"Native market did not match exactly once");
        require(market.matchOrders(RESOURCE)==0,"Native market repeated settlement");
        getConfig().set("market-record",history.tradeChangesAfter(0,128).get(2).record().recordId().toString());
    }
    private List<TradeHistoryChange> changes() {
        List<TradeHistoryChange> records=new ArrayList<>();long after=0;
        while(true) {
            var page=history.tradeChangesAfter(after,128);records.addAll(page);
            require(records.size()<=256,"Scenario exceeded bounded source limit");
            if(page.size()<128)return List.copyOf(records);
            after=page.getLast().sequence();
        }
    }
    private CompletionStage<?> check(int expected) {
        var records=changes();require(records.size()==expected,"Unexpected native count");
        var assets=balances();
        Map<UUID,TradeRecord> latest=new HashMap<>();Set<UUID> completed=new HashSet<>();
        for(var r:records) {latest.put(r.record().recordId(),r.record());if(r.record().status()==TradeRecord.TradeRecordStatus.COMPLETED)completed.add(r.record().recordId());}
        Map<String,Long> totals=new TreeMap<>();
        for(var r:latest.values())if(r.status()==TradeRecord.TradeRecordStatus.COMPLETED) {
            Set<String> subjects=new HashSet<>();
            r.buyerPlayerId().ifPresent(p->{subjects.add("player:"+p);r.buyerNationId().ifPresent(n->subjects.add("nation:"+n));});
            r.sellerPlayerId().ifPresent(p->{subjects.add("player:"+p);r.sellerNationId().ifPresent(n->subjects.add("nation:"+n));});
            for(String subject:subjects)totals.merge(subject,r.amount(),Long::sum);
        }
        Map<String,CompletableFuture<BigDecimal>> publicTotals=new TreeMap<>();
        for(String subject:List.of("player:"+buyer,"player:"+seller,"nation:"+buyerNation,"nation:"+sellerNation))
            publicTotals.put(subject,api.contributionTotal(subject,ContributionType.TRADE,RESOURCE).toCompletableFuture());
        List<CompletableFuture<List<WorldEvent>>> pages=new ArrayList<>();
        for(int i=0;i<3;i++)pages.add(api.history(null,null,i,100).toCompletableFuture());
        List<CompletableFuture<List<ContributionBalance>>> balances=new ArrayList<>();
        for(int i=0;i<7;i++)balances.add(api.contributionBalances(null,ContributionType.TRADE,i,100).toCompletableFuture());
        List<CompletableFuture<Optional<TradeHistoryReceipt>>> receipts=new ArrayList<>();
        for(var r:records)receipts.add(api.tradeHistoryReceipt("starcore:trade-change:"+r.authorityDomainId()+":"+r.sequence()).toCompletableFuture());
        List<CompletableFuture<?>> futures=new ArrayList<>();futures.addAll(pages);futures.addAll(publicTotals.values());futures.addAll(balances);futures.addAll(receipts);
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).thenCompose(ignored->{
            var events=pages.stream().flatMap(p->p.join().stream()).filter(e->"starcore.trade".equals(e.facts().get("history.namespace"))).toList();
            require(events.size()==expected,"Unexpected Chronicle trade count="+events.size());
            for(var r:records) {
                var e=events.stream().filter(v->v.id().equals("starcore:trade-change:"+r.authorityDomainId()+":"+r.sequence())).findFirst().orElseThrow();
                require(e.occurredAt().equals(r.occurredAt()),"Lost occurredAt nanos");
                require(e.facts().get("history.committed_at").equals(r.committedAt().toString()),"Lost committedAt nanos");
                require(e.facts().get("trade.executed_at").equals(r.record().executedAt().toString()),"Lost execution time");
            }
            Map<String,String> actualTotals=new TreeMap<>();
            publicTotals.forEach((subject,value)->{
                require(value.join().compareTo(BigDecimal.valueOf(totals.getOrDefault(subject,0L)))==0,"Public net total mismatch: "+subject);
                actualTotals.put(subject,value.join().toPlainString());
            });
            var entries=balances.stream().flatMap(p->p.join().stream()).toList();
            var allReceipts=receipts.stream().map(p->p.join().orElseThrow()).toList();
            List<CompletableFuture<List<ContributionRevision>>> revisions=new ArrayList<>();
            for(var e:entries)revisions.add(api.contributionRevisions(e.entry().id(),0,100).toCompletableFuture());
            return CompletableFuture.allOf(revisions.toArray(CompletableFuture[]::new)).thenApply(done->{
                var trail=revisions.stream().flatMap(p->p.join().stream()).toList();
                log("CHECK",Map.of("count",expected,"settlements",completed.size(),"balances",assets,"native",records.stream().map(this::change).toList(),
                    "events",events,"receipts",allReceipts,"contributions",entries,"revisions",trail,"totals",actualTotals));
                return expected;
            });
        });
    }
    private void revise(String key,TradeRecord.TradeRecordStatus status) {
        UUID id=UUID.fromString(getConfig().getString(key));
        var before=balances();
        history.updateStatus(id,status,"Isolated administrative history correction: "+status+"; no asset refund asserted");
        require(before.equals(balances()),"History correction moved assets");
        log("REVISED",Map.of("record",id.toString(),"status",status.name(),"balances",before));
    }
    private Map<String,Object> change(TradeHistoryChange change) {
        var record=change.record();Map<String,Object> out=new LinkedHashMap<>();
        out.put("domain",change.authorityDomainId().toString());out.put("sequence",change.sequence());
        out.put("committedAt",change.committedAt().toString());out.put("occurredAt",change.occurredAt().toString());out.put("previousStatus",change.previousStatus());
        Map<String,Object> r=new LinkedHashMap<>();out.put("record",r);
        r.put("id",record.recordId().toString());r.put("type",record.orderType().name());r.put("source",record.orderSource().name());
        r.put("buyer",record.buyerPlayerId().map(Object::toString).orElse(null));r.put("seller",record.sellerPlayerId().map(Object::toString).orElse(null));
        r.put("buyerNation",record.buyerNationId().map(Object::toString).orElse(null));r.put("sellerNation",record.sellerNationId().map(Object::toString).orElse(null));
        r.put("resource",record.resourceId());r.put("amount",record.amount());r.put("price",Double.toString(record.pricePerUnit()));r.put("tax",Double.toString(record.taxAmount()));
        r.put("executedAt",record.executedAt().toString());r.put("status",record.status().name());r.put("notes",record.notes().orElse(null));
        r.put("buyOrder",record.buyOrderId().map(Object::toString).orElse(null));r.put("sellOrder",record.sellOrderId().map(Object::toString).orElse(null));r.put("version",1);
        return out;
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        if(!(sender instanceof ConsoleCommandSender))return false;
        try {
            require(getServer().getOnlinePlayers().isEmpty(),"Fixture requires offline accounts");
            switch(args[0]) {
                case "burst" -> {
                    require(boot==1,"Burst on first boot only");
                    var completed=new java.util.concurrent.atomic.AtomicInteger();
                    getServer().getGlobalRegionScheduler().runAtFixedRate(this,task->{
                        try {direct(1);if(completed.incrementAndGet()==130){task.cancel();log("DONE",Map.of("action","burst"));}}
                        catch(Throwable error){task.cancel();fail(error);}
                    },1,1);
                    return true;
                }
                case "dispute" -> revise("first-record",TradeRecord.TradeRecordStatus.DISPUTED);
                case "restore" -> revise("first-record",TradeRecord.TradeRecordStatus.COMPLETED);
                case "refund" -> revise("first-record",TradeRecord.TradeRecordStatus.REFUNDED);
                case "notes" -> revise("market-record",TradeRecord.TradeRecordStatus.COMPLETED);
                case "cancel" -> revise("market-record",TradeRecord.TradeRecordStatus.CANCELLED);
                case "direct" -> direct(1);
                case "check" -> {
                    check(Integer.parseInt(args[1])).whenComplete((value,error)->{if(error!=null)fail(error);else log("DONE",Map.of("action","check","count",value));});
                    return true;
                }
                default -> throw new IllegalArgumentException("Unknown fixture action");
            }
            log("DONE",Map.of("action",args[0]));
        } catch(Throwable error){fail(error);}
        return true;
    }
    private void fail(Throwable error){while(error.getCause()!=null)error=error.getCause();log("FAILED",Map.of("error",error.toString()));error.printStackTrace();}
    private synchronized void log(String kind,Object data) {
        getLogger().info("TRADE_CONTRIB_"+kind+" "+SqliteStore.json(data));
        try{Files.writeString(run.resolve("evidence/fixture.jsonl"),SqliteStore.json(Map.of("time",Instant.now().toString(),"boot",boot,"kind",kind,"data",data))+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
        catch(Exception error){throw new IllegalStateException(error);}
    }
    private static String hash(Path file)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));}
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
    @Override public void onDisable(){if(run!=null)log("CLOSED",Map.of("normalDisable",true));}
}
