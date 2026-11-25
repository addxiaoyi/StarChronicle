package smoke;

import dev.starcore.starcore.module.nation.NationService;
import dev.starcore.starcore.module.resource.NationTradeService;
import dev.starcore.starcore.module.resource.NationResourceTransactionService;
import dev.starcore.starcore.module.resource.ResourceService;
import dev.starcore.starcore.module.treasury.TreasuryService;
import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.api.contribution.ContributionType;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;

/** Observes actual player sale commands. Seeds and ownership transfer are explicit public APIs. */
public final class NationTradePaperFixture extends ProductionStackPaperFixture {
    private static final String SOURCE="StarCore.NationTradeService";
    private ChronicleService api(){return Objects.requireNonNull(getServer().getServicesManager().load(ChronicleService.class));}
    private static Map<String,Object> map(Object...pairs){Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)result.put(pairs[i].toString(),pairs[i+1]);return result;}
    private UUID offerId(String key){return UUID.fromString(getConfig().getString("sale.offers."+key));}
    private Map<String,Object> offer(NationTradeService.Offer o){return map("offerId",o.offerId(),"sellerId",o.sellerId(),"buyerId",o.buyerId(),
        "sellerNationId",o.sellerNationId().toString(),"buyerNationId",o.buyerNationId().toString(),"resourceId",o.resourceId(),"amount",o.amount(),"unitPriceMinor",o.unitPriceMinor(),
        "resourceDomain",o.resourceDomain(),"treasuryDomain",o.treasuryDomain(),"createdAt",o.createdAt().toString(),"expiresAt",o.expiresAt().toString());}
    private Map<String,Object> receipt(NationTradeService.SettlementReceipt r){return map("authorityDomain",r.authorityDomain(),"sequence",r.sequence(),"offer",offer(r.offer()),
        "resourceTransferSequence",r.resourceTransferSequence(),"committedAt",r.committedAt().toString());}
    private CompletionStage<?> bind(){return main(()->{
        var nativeApi=service(NationTradeService.class);var treasury=service(TreasuryService.class);var resource=service(ResourceService.class);
        for(String who:List.of("A","B","C","D"))require(player(who).hasPermission("starcore.nationtrade.use"),"Native nationtrade permission unavailable");
        require(treasury.authorityDomainId()!=null,"Native treasury domain missing");
        log("SALE_BOUND",map("resourceDomain",service(NationResourceTransactionService.class).authorityDomainId(),"treasuryDomain",treasury.authorityDomainId(),
            "domain",nativeApi.settlementAuthorityDomain().map(UUID::toString).orElse(""),"nonOp",true,"actualService",nativeApi.getClass().getName()));return null;
    });}
    private CompletionStage<?> capture(String key,String who,long amount,long price){return main(()->{
        var nativeApi=service(NationTradeService.class);
        var offers=nativeApi.offers(uuid(who),100).stream().filter(v->v.status()==NationTradeService.OfferStatus.OPEN&&v.offer().sellerId().equals(uuid(who))
            &&v.offer().buyerId().equals(uuid("B"))&&v.offer().amount()==amount&&v.offer().unitPriceMinor()==price).toList();
        require(offers.size()==1,"Expected unique actual client-created offer: "+key);
        var view=offers.getFirst();var o=view.offer();
        require(o.sellerNationId().equals(country("A"))&&o.buyerNationId().equals(country("B"))&&o.resourceId().equals("agricultural"),"Native offer has wrong source subjects");
        require(nativeApi.settlement(o.offerId()).isEmpty(),"Unaccepted offer already has assets receipt");
        getConfig().set("sale.offers."+key,o.offerId().toString());saveConfig();
        log("SALE_OFFER",map("key",key,"who",who,"offer",offer(o),"status",view.status()));return null;
    });}
    private CompletionStage<?> status(String key,String expected){return main(()->{
        var nativeApi=service(NationTradeService.class);var view=nativeApi.offer(offerId(key)).orElseThrow();
        require(view.status().name().equals(expected),"Offer state differs: "+key);
        log("SALE_STATUS",map("key",key,"offer",offer(view.offer()),"status",view.status(),"settled",nativeApi.settlement(view.offer().offerId()).isPresent()));return null;
    });}
    private CompletionStage<?> successor(){return main(()->{
        var nations=service(NationService.class);
        require(nations.transferOwnership(country("A"),uuid("A"),player("D").getUniqueId(),player("D").getName()),"Actual founder handover failed");
        require(nations.nationById(country("A")).orElseThrow().isFounder(uuid("D")),"Successor founder not authoritative");
        log("SALE_SUCCESSOR",map("nation",country("A").toString(),"previous",uuid("A"),"current",uuid("D"),"publicNativeOperation",true));return null;
    });}
    private CompletionStage<?> check(int expected){CompletableFuture<Void> done=new CompletableFuture<>();checkPoll(expected,System.nanoTime()+TimeUnit.SECONDS.toNanos(45),done);return done;}
    private void checkPoll(int expected,long deadline,CompletableFuture<Void> done){
        api().history(null,null,0,100).thenCompose(events->api().contributions(null,ContributionType.TRADE,0,100).thenCompose(contributions->main(()->{
            try{
                var source=events.stream().filter(e->e.source().equals(SOURCE)).sorted(Comparator.comparingLong(e->Long.parseLong(e.facts().get("source.sequence")))).toList();
                if((source.size()!=expected||contributions.size()!=expected*4)&&System.nanoTime()<deadline){
                    log("SALE_QUERY_RETRY",map("expected",expected,"events",source.size(),"contributions",contributions.size()));
                    getServer().getScheduler().runTaskLater(this,()->checkPoll(expected,deadline,done),10);return null;
                }
                require(source.size()==expected&&contributions.size()==expected*4,"Exact history/contribution count differs");
                var nativeApi=service(NationTradeService.class);var domain=nativeApi.settlementAuthorityDomain();
                List<NationTradeService.SettlementReceipt> receipts=domain.isEmpty()?List.of():nativeApi.settlementsAfter(domain.orElseThrow(),0,100);
                require(receipts.size()==expected,"Native settlement count differs");
                long quantity=receipts.stream().mapToLong(r->r.offer().amount()).sum();
                BigDecimal value=receipts.stream().map(r->r.offer().totalValue()).reduce(BigDecimal.ZERO,BigDecimal::add);
                var treasury=service(TreasuryService.class);var resources=service(ResourceService.class);
                require(resources.amount(country("A"),"agricultural")==100-quantity&&resources.amount(country("B"),"agricultural")==100+quantity,"Inventory failed conservation");
                require(treasury.balance(country("A")).compareTo(new BigDecimal("1000000").add(value))==0
                    &&treasury.balance(country("B")).compareTo(new BigDecimal("1000000").subtract(value))==0,"Treasury failed conservation");
                for(int i=0;i<receipts.size();i++){
                    var receipt=receipts.get(i);var event=source.get(i);
                    require(event.id().equals("starcore:nation-trade:"+domain.orElseThrow()+":"+receipt.sequence())&&event.participants().equals(Set.of(receipt.offer().sellerId(),receipt.offer().buyerId())),"Historical actors changed");
                    require(contributions.stream().filter(c->c.request().sourceEventId().equals(event.id())).count()==4,"Missing exact source contribution views");
                }
                log("SALE_CHECK",map("expected",expected,"domain",domain.map(UUID::toString).orElse(""),"native",receipts.stream().map(this::receipt).toList(),
                    "events",source,"contributions",contributions,"assets",map("quantity",quantity,"value",value.toPlainString(),
                        "sellerResources",resources.amount(country("A"),"agricultural"),"buyerResources",resources.amount(country("B"),"agricultural"),
                        "sellerTreasury",treasury.balance(country("A")).toPlainString(),"buyerTreasury",treasury.balance(country("B")).toPlainString()),
                    "founder",service(NationService.class).nationById(country("A")).orElseThrow().founderId()));
                done.complete(null);
            }catch(Throwable error){done.completeExceptionally(error);}return null;
        }))).exceptionally(error->{done.completeExceptionally(error);return null;});
    }
    private CompletionStage<?> offline(boolean buffered){
        if(!buffered)return check(1).thenCompose(ignored->main(()->{
            require(getServer().getOnlinePlayers().isEmpty(),"Backlog was not consumed on an empty server");
            log("SALE_OFFLINE_CONSUMED",map("onlinePlayers",0,"settlements",1,"contributions",4));return null;
        }));
        return api().history(null,null,0,100).thenCompose(events->api().contributions(null,ContributionType.TRADE,0,100).thenCompose(contributions->main(()->{
            var nativeApi=service(NationTradeService.class);var domain=nativeApi.settlementAuthorityDomain().orElseThrow();
            require(nativeApi.settlementsAfter(domain,0,100).size()==1,"Missing actual native buffered sale");
            require(events.stream().noneMatch(e->e.source().equals(SOURCE))&&contributions.isEmpty(),"Consumer already advanced before stopped handoff");
            log("SALE_OFFLINE_BUFFERED",map("domain",domain,"settlements",1,"events",0,"contributions",0));return null;
        })));
    }
    @Override protected CompletionStage<?> extraAction(String action,String[] args){return switch(action){
        case "sale-offline-buffered"->offline(true);case "sale-offline-consumed"->offline(false);
        case "sale-bind"->bind();case "sale-capture"->capture(args[1],args[2],Long.parseLong(args[3]),Long.parseLong(args[4]));
        case "sale-status"->status(args[1],args[2]);case "sale-successor"->successor();case "sale-check"->check(Integer.parseInt(args[1]));
        default->super.extraAction(action,args);
    };}
}
