package smoke;

import dev.starcore.starcore.foundation.economy.*;
import dev.starcore.starcore.module.resource.*;
import dev.starcore.starcore.module.treasury.*;
import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.api.contribution.ContributionType;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;

/** Test-only pause around the real selected wallet call; no product fault hook or synthetic payment. */
public final class PlayerTradeAuditPaperFixture extends ProductionStackPaperFixture {
    private int calls, target;
    private String window;
    private EconomyService delegate;
    private PlayerTradeRecoveryService recovery() throws Exception {return service(PlayerTradeRecoveryService.class);}
    private UUID offerId(){return UUID.fromString(getConfig().getString("audit.offer"));}
    private int leg(){return getConfig().getInt("audit.leg");}
    private BigDecimal sellerInitial(){return leg()==3?((com.earth2me.essentials.Essentials)getServer().getPluginManager().getPlugin("Essentials")).getSettings().getMaxMoney():new BigDecimal("10.00");}
    private CompletionStage<?> bind(){return main(()->{
        for(String who:List.of("A","B","C","D")){
            require(!player(who).isOp(),"Actual non-OP players required");
            player(who).addAttachment(this,"starcore.trade.use",true);
        }
        require(service(PlayerTradeService.class).recoveryService().orElseThrow()==recovery(),"Wrong command recovery service");
        delegate=service(EconomyService.class);
        if(!getConfig().getBoolean("audit.seeded",false)){
            require(delegate.setBalance(uuid("A"),new BigDecimal("1000.00")),"Buyer seed rejected");
            require(delegate.setBalance(uuid("B"),sellerInitial()),"Seller seed rejected");
            service(TaxationService.class).setTaxConfig(country("A"),TaxationService.TaxType.TARIFF,
                new TaxationService.TaxConfig(TaxationService.TaxType.TARIFF,true,BigDecimal.ZERO,new BigDecimal("4"),BigDecimal.ZERO,1440,false));
            getConfig().set("audit.seeded",true);saveConfig();
        }
        var monitored=(EconomyService)Proxy.newProxyInstance(EconomyService.class.getClassLoader(),new Class<?>[]{EconomyService.class},(proxy,method,args)->{
            if(!method.getName().equals("executeWalletOperation"))return invoke(method,args);
            int call=++calls;var operation=(WalletOperation)args[0];
            log("AUDIT_WALLET_ENTER",Map.of("call",call,"operation",operation));
            if(call==target&&window.equals("before"))pause(call,"NOT_INVOKED");
            Object outcome=invoke(method,args);
            log("AUDIT_WALLET_RETURN",Map.of("call",call,"operation",operation,"outcome",outcome));
            if(leg()==3&&call==2)require(outcome==WalletOperation.Outcome.REJECTED,"Real Essentials maximum must reject seller credit");
            if(call==target&&window.equals("after")){
                require(outcome==WalletOperation.Outcome.ACKNOWLEDGED,"Interrupted target must actually be acknowledged");
                pause(call,outcome.toString());
            }
            return outcome;
        });
        Field field=recovery().getClass().getDeclaredField("wallet");field.setAccessible(true);field.set(recovery(),monitored);
        log("AUDIT_BOUND",Map.of("provider",delegate.walletProviderIdentity(),"leg",leg(),"nonOp",true,"sellerInitial",sellerInitial().toPlainString()));return null;
    });}
    private Object invoke(Method method,Object[] args)throws Throwable{
        try{return method.invoke(delegate,args);}catch(InvocationTargetException error){throw error.getCause();}
    }
    private void pause(int call,String outcome)throws InterruptedException{
        log("AUDIT_PAUSED",Map.of("call",call,"window",window,"outcome",outcome,"offer",offerId()));
        new CountDownLatch(1).await();
        throw new AssertionError("Paused fixture must be terminated externally");
    }
    private CompletionStage<?> capture(){return main(()->{
        var offers=service(PlayerTradeService.class).getSentOffers(uuid("A")).stream().filter(o->o.isPending()).toList();
        require(offers.size()==1,"Expected exactly one real offered trade");
        UUID id=offers.getFirst().offerId();getConfig().set("audit.offer",id.toString());saveConfig();
        log("AUDIT_OFFER",Map.of("id",id,"terms",recovery().inspect(uuid("A"),id).orElseThrow().terms()));return null;
    });}
    private CompletionStage<?> arm(){return main(()->{
        require(calls==0&&target==0,"Already armed or invoked");target=leg();window=getConfig().getString("audit.window");
        log("AUDIT_ARMED",Map.of("leg",target,"window",window,"offer",offerId()));return null;
    });}
    private CompletionStage<?> check(String phase){
        var api=Objects.requireNonNull(getServer().getServicesManager().load(ChronicleService.class));
        return api.history(null,null,0,100).thenCompose(events->api.contributions(null,ContributionType.TRADE,0,100).thenCompose(contributions->main(()->{
            var audit=recovery().audit(uuid("B"),offerId()).orElseThrow();var current=audit.current();
            require(recovery().audit(uuid("A"),offerId()).orElseThrow().equals(audit),"Participants see different evidence");
            for(String who:List.of("C","D"))require(recovery().audit(uuid(who),offerId()).isEmpty(),"Foreign audit leaked");
            boolean seeded=phase.equals("seed");String expected=seeded?"OFFERED":leg()==1?"DEBIT_INTENT":leg()==2?"CREDIT_INTENT":"REFUND_INTENT";
            require(current.state().name().equals(expected),"Unexpected durable state: "+current.state());
            require(calls==0,"Inspection/replay invoked wallet");
            BigDecimal buyer=new BigDecimal("1000.00"),seller=sellerInitial();
            if(!seeded&&!(leg()==1&&getConfig().getString("audit.window").equals("before"))){
                if(leg()!=3)buyer=new BigDecimal("947.00");
                if(leg()==2)seller=new BigDecimal("60.00");
            }
            require(delegate.getBalance(uuid("A")).compareTo(buyer)==0&&delegate.getBalance(uuid("B")).compareTo(seller)==0,"Restored actual wallet mismatch");
            var resources=service(ResourceService.class);var treasury=service(TreasuryService.class);
            require(resources.amount(country("A"),"agricultural")==100&&resources.amount(country("B"),"agricultural")== (seeded?100:80),"Escrow was lost or delivered");
            require(treasury.balance(country("A")).compareTo(new BigDecimal("1000000.00"))==0,"Unsettled tax was credited");
            require(recovery().settlementsAfter(audit.authorityDomain(),0,100).isEmpty(),"Uncertain trade settled");
            require(events.stream().noneMatch(e->e.source().equals("StarCore.PlayerTradeRecoveryService")||e.source().equals("StarCore.TradeHistoryService")),"Uncertain trade entered history");
            require(contributions.isEmpty(),"Uncertain trade generated contribution");
            if(!seeded){
                var call=audit.calls().get(leg()-1);
                require(call.evidence()==PlayerTradeAudit.Evidence.INTENT_WITHOUT_RESULT&&call.result()==null,"Missing result was misclassified");
                require(audit.nextAction()==PlayerTradeAudit.NextAction.PROVIDER_EVIDENCE_REQUIRED,"Uncertain trade was unlocked");
            }
            log("PLAYER_TRADE_AUDIT",Map.of("phase",phase,"audit",audit,"calls",audit.calls(),"nextAction",audit.nextAction(),"walletCalls",calls,"wallet",Map.of("A",buyer.toPlainString(),"B",seller.toPlainString()),"sellerStock",seeded?100:80,"buyerStock",100,"settlements",0));
            return null;
        })));
    }
    @Override protected CompletionStage<?> extraAction(String action,String[] args){return switch(action){
        case "audit-bind"->bind();case "audit-capture"->capture();case "audit-arm"->arm();case "audit-check"->check(args[1]);
        default->super.extraAction(action,args);
    };}
}
