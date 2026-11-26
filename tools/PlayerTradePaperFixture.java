package smoke;

import dev.starcore.starcore.foundation.economy.EconomyService;
import dev.starcore.starcore.module.nation.NationService;
import dev.starcore.starcore.module.resource.*;
import dev.starcore.starcore.module.treasury.*;
import dev.starcore.starcore.module.business.BusinessService;
import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.api.contribution.ContributionType;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Real non-OP /trade commands; the fixture seeds funds and observes authoritative services. */
public final class PlayerTradePaperFixture extends ProductionStackPaperFixture {
    private static final String SOURCE="StarCore.PlayerTradeRecoveryService";
    private ChronicleService api(){return Objects.requireNonNull(getServer().getServicesManager().load(ChronicleService.class));}
    private static Map<String,Object> map(Object...pairs){var result=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)result.put(pairs[i].toString(),pairs[i+1]);return result;}
    private PlayerTradeRecoveryService recovery() throws Exception{return service(PlayerTradeRecoveryService.class);}
    private UUID offerId(String key){return UUID.fromString(getConfig().getString("playertrade.offers."+key));}
    private Map<String,Object> snapshot(PlayerTradeRecoveryService.Snapshot s){var t=s.terms();return map("offerId",t.id(),"buyer",t.buyer(),"seller",t.seller(),
        "buyerNation",t.buyerNation().toString(),"sellerNation",t.sellerNation().toString(),"resourceDomain",t.resourceDomain(),"treasuryDomain",t.treasuryDomain(),
        "provider",t.provider(),"resource",t.resource(),"amount",t.amount(),"unitPrice",t.unitPrice().toPlainString(),"gross",t.gross().toPlainString(),
        "feeMinor",t.feeMinor(),"tariffMinor",t.tariffMinor(),"total",t.total().toPlainString(),"createdAt",t.createdAt().toString(),"expiresAt",t.expiresAt().toString(),
        "state",s.state(),"version",s.version(),"acceptedAt",s.acceptedAt()==null?null:s.acceptedAt().toString(),"settledAt",s.settledAt()==null?null:s.settledAt().toString());}
    private void tariff(String rate) throws Exception{service(TaxationService.class).setTaxConfig(country("A"),TaxationService.TaxType.TARIFF,
        new TaxationService.TaxConfig(TaxationService.TaxType.TARIFF,true,BigDecimal.ZERO,new BigDecimal(rate),BigDecimal.ZERO,1440,false));}
    private CompletionStage<?> bind(){return main(()->{
        for(String who:List.of("A","B","C","D")){require(!player(who).isOp(),"Require actual non-OP players");player(who).addAttachment(this,"starcore.trade.use",true);}
        require(service(PlayerTradeService.class).recoveryService().orElseThrow()==recovery(),"Public and command recovery differ");
        if(!getConfig().getBoolean("playertrade.seeded",false)){
            var wallet=service(EconomyService.class);
            require(wallet.setBalance(uuid("A"),new BigDecimal("1000.00")),"Buyer seed failed");
            for(String who:List.of("B","D"))require(wallet.setBalance(uuid(who),new BigDecimal("10.00")),"Seller seed failed");
            tariff("4");getConfig().set("playertrade.seeded",true);saveConfig();
        }
        log("PLAYER_TRADE_BOUND",map("provider",service(EconomyService.class).walletProviderIdentity(),"domain",recovery().authorityDomain().map(UUID::toString).orElse(""),"nonOp",true));return null;
    });}
    private CompletionStage<?> capture(String key,String seller,long amount){return main(()->{
        var offers=service(PlayerTradeService.class).getSentOffers(uuid("A")).stream().filter(o->o.isPending()&&o.targetId().equals(uuid(seller))&&o.amount()==amount).toList();
        require(offers.size()==1,"Unique actual player offer missing: "+key);UUID id=offers.getFirst().offerId();
        var s=recovery().inspect(uuid("A"),id).orElseThrow();require(s.state()==PlayerTradeRecoveryService.State.OFFERED,"Unaccepted offer changed");
        require(s.terms().buyerNation().equals(country("A"))&&s.terms().sellerNation().equals(country("B")),"Wrong original nations");
        getConfig().set("playertrade.offers."+key,id.toString());saveConfig();log("PLAYER_TRADE_OFFER",map("key",key,"offer",snapshot(s)));return null;
    });}
    private CompletionStage<?> status(String key,String expected){return main(()->{
        var s=recovery().inspect(uuid("A"),offerId(key)).orElseThrow();require(s.state().name().equals(expected),"Wrong state for "+key);
        require(recovery().inspect(uuid("C"),offerId(key)).isEmpty(),"Nonparticipant read leaked");
        log("PLAYER_TRADE_STATUS",map("key",key,"offer",snapshot(s),"foreignHidden",true));return null;
    });}
    private CompletionStage<?> changeTaxes(){return main(()->{tariff("40");log("PLAYER_TRADE_TAX_CHANGED",map("tariffPercent",40));return null;});}
    private CompletionStage<?> successor(){return main(()->{
        var nations=service(NationService.class);
        require(nations.removeMember(country("A"),uuid("D"))&&nations.addMember(country("B"),uuid("D"),player("D").getName()),"Successor membership move failed");
        require(nations.transferOwnership(country("B"),uuid("B"),uuid("D"),player("D").getName()),"Seller founder handover failed");
        log("PLAYER_TRADE_SUCCESSOR",map("nation",country("B").toString(),"previous",uuid("B"),"current",uuid("D")));return null;
    });}
    private CompletionStage<?> check(int expected){var done=new CompletableFuture<Void>();poll(expected,System.nanoTime()+TimeUnit.SECONDS.toNanos(45),done);return done;}
    private void poll(int expected,long deadline,CompletableFuture<Void> done){
        api().history(null,null,0,100).thenCompose(events->api().contributions(null,ContributionType.TRADE,0,100).thenCompose(all->main(()->{
            try{
                var source=events.stream().filter(e->e.source().equals(SOURCE)).sorted(Comparator.comparingLong(e->Long.parseLong(e.facts().get("source.sequence")))).toList();
                var historyEvents=events.stream().filter(e->e.source().equals("StarCore.TradeHistoryService")).toList();
                var contributions=all.stream().filter(c->c.request().sourceEventId().startsWith("starcore:player-trade:")).toList();
                if((source.size()!=expected||historyEvents.size()!=expected||contributions.size()!=expected*4)&&System.nanoTime()<deadline){getServer().getScheduler().runTaskLater(this,()->poll(expected,deadline,done),10);return null;}
                require(source.size()==expected&&historyEvents.size()==expected&&contributions.size()==expected*4,"Wrong history or contribution count");
                require(all.stream().noneMatch(c->c.request().sourceEventId().startsWith("starcore:trade-history:")),"Generic trade history generated extra contributions");
                var domain=recovery().authorityDomain();var receipts=domain.isEmpty()?List.<PlayerTradeRecoveryService.Receipt>of():recovery().settlementsAfter(domain.orElseThrow(),0,100);
                require(receipts.size()==expected,"Native receipt count differs");
                long quantity=0;BigDecimal tax=BigDecimal.ZERO,total=BigDecimal.ZERO;var incomes=new HashMap<UUID,BigDecimal>();var serialized=new ArrayList<Map<String,Object>>();
                for(int i=0;i<receipts.size();i++){
                    var r=receipts.get(i);var s=r.settlement();var t=s.terms();var event=source.get(i);quantity+=t.amount();tax=tax.add(t.tax());total=total.add(t.total());incomes.merge(t.seller(),t.gross(),BigDecimal::add);
                    require(event.id().equals("starcore:player-trade:"+domain.orElseThrow()+":"+r.sequence())&&event.participants().equals(Set.of(t.seller(),t.buyer())),"Original source actors changed");
                    require(event.facts().get("trade.wallet_evidence").equals("PROVIDER_ACKNOWLEDGED")&&event.facts().get("trade.asset_atomicity").equals("NATIVE_DELIVERY_AND_TAXES"),"External durability overstated");
                    require(contributions.stream().filter(c->c.request().sourceEventId().equals(event.id())).count()==4,"Missing source contribution view");
                    var history=service(TradeHistoryService.class).getRecord(t.id()).orElseThrow();
                    require(history.buyerFunded()&&history.netValue()==t.gross().doubleValue()&&history.executedAt().equals(s.settledAt()),"History projection changed net or timestamp");
                    require(historyEvents.stream().filter(e->t.id().toString().equals(e.facts().get("trade.record_id"))&&"BUYER".equals(e.facts().get("trade.tax_payer"))&&"NOT_PROVEN_BY_HISTORY_JOURNAL".equals(e.facts().get("trade.asset_atomicity"))).count()==1,"Generic history lost buyer-funded semantics");
                    for(boolean buyer:new boolean[]{true,false}){
                        var type=buyer?BusinessService.BusinessTransactionType.PURCHASE:BusinessService.BusinessTransactionType.SALE;
                        UUID businessId=UUID.nameUUIDFromBytes(("player-trade:"+t.id()+":"+type.name()).getBytes(StandardCharsets.UTF_8));
                        require(service(BusinessService.class).getPlayerTransactions(buyer?t.buyer():t.seller(),100).stream().filter(b->b.transactionId().equals(businessId)&&b.amount().compareTo(t.gross())==0).count()==1,"Business projection missing or duplicated");
                    }
                    var current=recovery().inspect(uuid("A"),t.id()).orElseThrow();require(current.state()==PlayerTradeRecoveryService.State.PROJECTED,"Projection outbox incomplete");
                    serialized.add(map("sequence",r.sequence(),"domain",r.authorityDomain(),"settlement",snapshot(s)));
                }
                var wallet=service(EconomyService.class);var essentials=(com.earth2me.essentials.Essentials)getServer().getPluginManager().getPlugin("Essentials");var balances=new TreeMap<String,String>();
                for(String who:List.of("A","B","D")){
                    var wanted=who.equals("A")?new BigDecimal("1000.00").subtract(total):new BigDecimal("10.00").add(incomes.getOrDefault(uuid(who),BigDecimal.ZERO));
                    require(wallet.getBalance(uuid(who)).compareTo(wanted)==0&&essentials.getUser(uuid(who)).getMoney().compareTo(wanted)==0,"Actual Essentials balance differs: "+who);balances.put(who,wanted.toPlainString());
                }
                var resource=service(ResourceService.class);var treasury=service(TreasuryService.class);
                require(resource.amount(country("A"),"agricultural")==100+quantity&&resource.amount(country("B"),"agricultural")==100-quantity,"Inventory conservation failed");
                require(treasury.balance(country("A")).compareTo(new BigDecimal("1000000.00").add(tax))==0&&treasury.balance(country("B")).compareTo(new BigDecimal("1000000.00"))==0,"Native taxes differ");
                require(service(TradeTaxService.class).getTaxHistory(country("A"),100).size()==expected&&service(TaxationService.class).getTaxHistory(country("A"),TaxationService.TaxType.TARIFF,100).size()==expected,"Fee/tariff display projections missing");
                log("PLAYER_TRADE_CHECK",map("expected",expected,"receipts",serialized,"events",source,"historyEvents",historyEvents,"contributions",contributions,"wallet",balances,"quantity",quantity,"total",total.toPlainString(),"tax",tax.toPlainString(),"buyerResources",100+quantity,"sellerResources",100-quantity,"buyerTreasury",treasury.balance(country("A")).toPlainString(),"originalSellerFounder",service(NationService.class).nationById(country("B")).orElseThrow().founderId()));
                done.complete(null);
            }catch(Throwable failure){done.completeExceptionally(failure);}return null;
        }))).exceptionally(failure->{done.completeExceptionally(failure);return null;});
    }
    @Override protected CompletionStage<?> extraAction(String action,String[] args){return switch(action){
        case "player-trade-bind"->bind();case "player-trade-capture"->capture(args[1],args[2],Long.parseLong(args[3]));
        case "player-trade-status"->status(args[1],args[2]);case "player-trade-taxes"->changeTaxes();case "player-trade-successor"->successor();
        case "player-trade-check"->check(Integer.parseInt(args[1]));default->super.extraAction(action,args);
    };}
}
