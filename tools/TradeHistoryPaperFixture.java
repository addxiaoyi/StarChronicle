package smoke;

import dev.starcore.starcore.foundation.economy.EconomyService;
import dev.starcore.starcore.foundation.economy.transaction.TransactionFabric;
import dev.starcore.starcore.foundation.economy.transaction.EconomyTransactionState;
import dev.starcore.starcore.module.nation.NationService;
import dev.starcore.starcore.module.nation.model.NationId;
import dev.starcore.starcore.module.resource.*;
import dev.starcore.starcore.module.resource.model.*;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.bukkit.command.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Native service settlement on an isolated Paper server. No fake events or receipt fabrication.
 * Actors are offline fixture accounts; this does not certify a player command/UI flow. */
public final class TradeHistoryPaperFixture extends JavaPlugin implements CommandExecutor {
    private Path run;
    private TradeHistoryService history;
    private PlayerTradeService trades;
    private MarketOrderBookService market;
    private ResourceService resources;
    private EconomyService economy;
    private TransactionFabric fabric;
    private UUID buyer, seller;
    private NationId buyerNation, sellerNation;
    private int boot;
    private static final String RESOURCE="industrial";

    @Override public void onEnable() {
        try {
            run=getDataFolder().toPath().toAbsolutePath().normalize().getParent().getParent();
            require(run.getFileName().toString().startsWith("paper-trade-"),"Isolated directory required");
            require(hash(run.resolve("plugins/StarChronicle.jar")).equals(getConfig().getString("product-sha")),"Chronicle hash drift");
            require(hash(run.resolve("plugins/StarCore.jar")).equals(getConfig().getString("native-sha")),"Native hash drift");
            Plugin core=Objects.requireNonNull(getServer().getPluginManager().getPlugin("StarCore"));
            Object context=core.getClass().getMethod("context").invoke(core);
            Object registry=context.getClass().getMethod("serviceRegistry").invoke(context);
            history=service(registry,TradeHistoryService.class);trades=service(registry,PlayerTradeService.class);
            market=service(registry,MarketOrderBookService.class);resources=service(registry,ResourceService.class);
            economy=service(registry,EconomyService.class);fabric=service(registry,TransactionFabric.class);
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
        direct(5);
        var sold=trades.quickSellResult(buyer,buyerNation,RESOURCE,2);
        require(sold.status()==PlayerTradeService.ActionStatus.APPLIED&&sold.record()!=null,"Native quick sell rejected: "+sold);
        market.submitBuyOrder(buyer,buyerNation,RESOURCE,3,2.0,Instant.now().plusSeconds(300));
        market.submitSellOrder(seller,sellerNation,RESOURCE,3,2.0,Instant.now().plusSeconds(300));
        require(market.matchOrders(RESOURCE)==1,"Native market did not match exactly once");
        require(market.matchOrders(RESOURCE)==0,"Native market repeated settlement");
        check(3);
    }
    private void check(int expected) {
        List<TradeHistoryChange> records=new ArrayList<>();long after=0;
        while(true) {
            var page=history.tradeChangesAfter(after,128);records.addAll(page);
            require(records.size()<=256,"Scenario exceeded bounded source limit");
            if(page.size()<128)break;
            after=page.getLast().sequence();
        }
        require(records.size()==expected,"Unexpected committed source count="+records.size());
        List<TradeSettlementIntent> intents=new ArrayList<>();UUID cursor=null;
        while(true){var page=history.settlementIntentsAfter(cursor,128);intents.addAll(page);if(page.size()<128)break;cursor=page.getLast().operationId();require(intents.size()<=256,"Too many intents");}
        for(int i=0;i<records.size();i++) {
            var receipt=records.get(i);var record=receipt.record();
            require(receipt.sequence()==i+1&&receipt.authorityDomainId().equals(history.historyAuthorityDomainId()),"Sequence or domain drift");
            require(record.status()==TradeRecord.TradeRecordStatus.COMPLETED,"Unexpected source status");
            var intent=intents.stream().filter(t->t.plannedRecord().recordId().equals(record.recordId())).findFirst().orElseThrow();
            var tx=fabric.findByIdempotencyKey(intent.idempotencyKey()).orElseThrow();
            require(tx.state()==EconomyTransactionState.COMMITTED,"Missing native committed transaction");
            require(intent.appliedEvents()==tx.events().size(),"Missing SQL receipt checkpoint");
            require(record.executedAt().equals(tx.updatedAt())&&receipt.occurredAt().equals(tx.updatedAt()),"Committed timestamp lost");
            require(history.getRecord(record.recordId()).orElseThrow().executedAt().equals(record.executedAt()),"Query timestamp precision changed");
        }
        log("CHECK",Map.of("count",expected,"balances",balances(),"records",records.stream().map(this::change).toList(),
            "intents",intents.stream().map(i->Map.of("operation",i.operationId().toString(),"record",i.plannedRecord().recordId().toString(),"appliedEvents",i.appliedEvents(),"context",i.context())).toList()));
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
                case "initial" -> initial();
                case "direct" -> {var before=balances();TradeRecord record=direct(1);log("SETTLED",Map.of("record",record.recordId().toString(),"before",before,"after",balances()));}
                case "burst" -> {require(boot==2,"Burst only on second boot");for(int i=0;i<130;i++)direct(1);}
                case "check" -> check(Integer.parseInt(args[1]));
                default -> throw new IllegalArgumentException("Unknown fixture action");
            }
            log("DONE",Map.of("action",args[0]));
        } catch(Throwable error){fail(error);}
        return true;
    }
    private void fail(Throwable error){while(error.getCause()!=null)error=error.getCause();log("FAILED",Map.of("error",error.toString()));error.printStackTrace();}
    private synchronized void log(String kind,Object data) {
        getLogger().info("TRADE_"+kind+" "+SqliteStore.json(data));
        try{Files.writeString(run.resolve("evidence/fixture.jsonl"),SqliteStore.json(Map.of("time",Instant.now().toString(),"boot",boot,"kind",kind,"data",data))+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
        catch(Exception error){throw new IllegalStateException(error);}
    }
    private static String hash(Path file)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));}
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
    @Override public void onDisable(){if(run!=null)log("CLOSED",Map.of("normalDisable",true));}
}
