package io.github.addxiaoyi.starchronicle.integration;

import dev.starcore.starcore.core.storage.AbstractModuleStateStorage;
import dev.starcore.starcore.module.nation.model.NationId;
import dev.starcore.starcore.module.resource.*;
import dev.starcore.starcore.module.resource.model.ResourceQuota;
import dev.starcore.starcore.module.treasury.*;
import io.github.addxiaoyi.starchronicle.core.RuleEngine;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.logging.Logger;
import javax.sql.DataSource;

/** Packaged native trade recovery and committed Chronicle ingestion, no Minecraft. */
public final class TradeExecutionProbe {
    static final NationId IMPORTER=new NationId(new UUID(0,1)),EXPORTER=new NationId(new UUID(0,2));
    static final class Fault { int commits; boolean unreadable; }
    public static void main(String[] args)throws Exception {
        Path output=Path.of(args[0]);String phase=args[1];
        var moneyFault=new Fault();var resourceFault=new Fault();
        Object moneyStorage=storage("treasury.SqlTreasuryStateStorage",source(output.resolve("treasury.db"),moneyFault));
        Object resourceStorage=storage("resource.SqlResourceStateStorage",source(output.resolve("resources.db"),resourceFault));
        var money=new TreasuryModule();set(money,"stateStorage",moneyStorage);invoke(money,"loadState");
        var resources=new ResourceModule();set(resources,"stateStorage",resourceStorage);
        var trades=new SimpleResourceTradeService(resources,money);set(resources,"tradeService",trades);invoke(resources,"loadState");
        try {
            if(phase.equals("first")) {
                check(money.tryDeposit(IMPORTER,new BigDecimal("100.00")),"seed importer");
                check(money.tryDeposit(EXPORTER,new BigDecimal("10.00")),"seed exporter");
                var agreement=trades.createTradeAgreement(EXPORTER,IMPORTER,"industrial",2,2.0,3600);
                Files.writeString(output.resolve("agreement.txt"),agreement.agreementId().toString());
                Files.writeString(output.resolve("domains.txt"),resources.authorityDomainId()+"\n"+money.authorityDomainId());
                trades.createQuota(EXPORTER,"industrial",10,3600,ResourceQuota.QuotaType.EXPORT);
                trades.createQuota(IMPORTER,"industrial",10,3600,ResourceQuota.QuotaType.IMPORT);
                trades.createTradeRoute(EXPORTER,IMPORTER,"contract route");
                check(resources.grant(EXPORTER,"industrial",6),"seed inventory");
                moneyFault.commits=1;
            }
            UUID agreement=UUID.fromString(Files.readString(output.resolve("agreement.txt")));
            if(phase.equals("second"))resourceFault.commits=2; // PAID checkpoint, then atomic inventory transfer.
            var request=new ResourceTradeService.ExecutionRequest("packaged-trade",agreement,null);
            var result=trades.executeTradeAgreement(request);
            if(phase.equals("first")) {
                check(result.status()==ResourceTradeService.ExecutionStatus.UNKNOWN,"unknown treasury commit");
                check(money.authorityDomainId()==null,"treasury quarantine");
                check(trades.tradeExecution(request.executionId()).orElseThrow().state()==TradeExecution.State.PREPARED,"durable prepared intent");
                check(resources.amount(EXPORTER,"industrial")==6&&resources.amount(IMPORTER,"industrial")==0,"no early delivery");
                moneyFault.unreadable=false;invoke(money,"flushState");
            } else if(phase.equals("second")) {
                check(result.status()==ResourceTradeService.ExecutionStatus.UNKNOWN,"unknown resource commit");
                check(resources.authorityDomainId().isBlank(),"resource quarantine");
                resourceFault.unreadable=false;
                try{trades.tradeExecution(request.executionId());throw new AssertionError("quarantine query returned data");}
                catch(IllegalStateException expected){}
                try{invoke(resources,"flushState");throw new AssertionError("quarantine flush overwrote commit");}
                catch(IllegalStateException expected){}
            } else if(phase.equals("third")||phase.equals("fourth")) {
                check(result.status()==ResourceTradeService.ExecutionStatus.COMPLETED,"completion after restart");
                check(trades.retryTradeExecution(request.executionId(),null).status()==ResourceTradeService.ExecutionStatus.COMPLETED,"same ID replay");
                check(trades.executeTradeAgreement(new ResourceTradeService.ExecutionRequest(request.executionId(),UUID.randomUUID(),null)).status()==ResourceTradeService.ExecutionStatus.REJECTED,"conflicting ID");
                check(resources.amount(EXPORTER,"industrial")==4&&resources.amount(IMPORTER,"industrial")==2,"inventory once");
                check(money.balance(IMPORTER).equals(new BigDecimal("96.00"))&&money.balance(EXPORTER).equals(new BigDecimal("14.00")),"money once");
                check(trades.getQuotas(EXPORTER).iterator().next().currentAmount()==2&&trades.getQuotas(IMPORTER).iterator().next().currentAmount()==2,"quota once");
                check(trades.getTradeRoutes(EXPORTER).iterator().next().totalTradeVolume()==2,"route once");
                check((resources.authorityDomainId()+"\n"+money.authorityDomainId()).equals(Files.readString(output.resolve("domains.txt"))),"stable domains");
                try(var engine=new RuleEngine(new SqliteStore(output.resolve("chronicle.db")),List.of(),java.time.Clock.systemUTC())) {
                    var collector=new CommittedSourceCollector(type->resources);
                    var source=CommittedSourceCollector.Source.RESOURCES;
                    int added=engine.acceptCommittedSource(collector.collect(source,engine.committedSourceCursor(source.id)).orElseThrow(),List.of());
                    check(added==(phase.equals("third")?1:0),"durable history cursor");
                    check(engine.acceptCommittedSource(collector.collect(source,engine.committedSourceCursor(source.id)).orElseThrow(),List.of())==0,"no duplicate history");
                    check(engine.history(null,null,0,100).size()==1,"one national transfer event");
                }
            } else throw new IllegalArgumentException(phase);
            System.out.println("TRADE_EXECUTION_CONTRACT_PASS phase="+phase);
        } finally {
            ((AbstractModuleStateStorage)moneyStorage).shutdown();
            ((AbstractModuleStateStorage)resourceStorage).shutdown();
            AbstractModuleStateStorage.shutdownNamespace("test");
        }
    }
    static Object storage(String name,DataSource source)throws Exception {
        var constructor=Class.forName("dev.starcore.starcore.module."+name).getDeclaredConstructor(DataSource.class,Properties.class,Logger.class);
        constructor.setAccessible(true);return constructor.newInstance(source,new Properties(),Logger.getLogger("trade-contract"));
    }
    static DataSource source(Path file,Fault fault) {
        return (DataSource)Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class<?>[]{DataSource.class},(proxy,method,args)->{
            if(!method.getName().equals("getConnection"))throw new UnsupportedOperationException(method.getName());
            if(fault.unreadable)throw new SQLException("injected outage");
            Connection actual=DriverManager.getConnection("jdbc:sqlite:"+file.toAbsolutePath());
            return Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
                Object result;try{result=m.invoke(actual,a);}catch(InvocationTargetException error){throw error.getCause();}
                if(m.getName().equals("commit")&&fault.commits>0&&--fault.commits==0){fault.unreadable=true;throw new SQLException("committed but reply lost");}
                return result;
            });
        });
    }
    static void set(Object object,String name,Object value)throws Exception{var field=object.getClass().getDeclaredField(name);field.setAccessible(true);field.set(object,value);}
    static void invoke(Object object,String name)throws Exception{var method=object.getClass().getDeclaredMethod(name);method.setAccessible(true);try{method.invoke(object);}catch(InvocationTargetException error){if(error.getCause() instanceof RuntimeException failure)throw failure;throw error;}}
    static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
