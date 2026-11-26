package dev.starcore.starcore.module.treasury;

import dev.starcore.starcore.core.storage.AbstractModuleStateStorage;
import dev.starcore.starcore.module.nation.model.NationId;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import javax.sql.DataSource;

/** Packaged native TreasuryModule, real SQLite, separate JVM restart; no Minecraft. */
public final class TreasuryRecoveryProbe {
    static final NationId PAYER=new NationId(new UUID(0,1)),RECEIVER=new NationId(new UUID(0,2));
    public static void main(String[] args)throws Exception {
        Path directory=Path.of(args[0]);String phase=args[1];
        String url="jdbc:sqlite:"+directory.resolve("treasury.db").toAbsolutePath();
        var armed=new AtomicBoolean();var unreadable=new AtomicBoolean();
        DataSource source=(DataSource)Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class<?>[]{DataSource.class},(proxy,method,values)->{
            if(!method.getName().equals("getConnection"))throw new UnsupportedOperationException(method.getName());
            if(unreadable.get())throw new SQLException("injected recovery outage");
            Connection actual=DriverManager.getConnection(url);
            return Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
                Object result;
                try {result=m.invoke(actual,a);}catch(InvocationTargetException error){throw error.getCause();}
                if(m.getName().equals("commit")&&armed.getAndSet(false)) {
                    unreadable.set(true);throw new SQLException("committed transfer but acknowledgement lost");
                }
                return result;
            });
        });
        var storage=new SqlTreasuryStateStorage(source,new Properties(),Logger.getLogger("treasury-contract"));
        try {
            var module=new TreasuryModule();
            Field field=TreasuryModule.class.getDeclaredField("stateStorage");field.setAccessible(true);field.set(module,storage);
            Method load=TreasuryModule.class.getDeclaredMethod("loadState");load.setAccessible(true);load.invoke(module);
            var request=new NationTransferService.TransferRequest("contract-transfer",PAYER,RECEIVER,new BigDecimal("20.00"),NationTransferService.TransferCategory.OTHER,"restart contract");
            if(phase.equals("first")) {
                Files.writeString(directory.resolve("domain.txt"),module.authorityDomainId());
                check(module.tryDeposit(PAYER,new BigDecimal("100.00")),"payer seed");
                check(module.tryDeposit(RECEIVER,new BigDecimal("10.00")),"receiver seed");
                armed.set(true);
                try {module.transfer(request);throw new AssertionError("uncertain transfer reported definite result");}
                catch(TreasuryTransactionUncertainException error){check(error.transactionId().equals(request.transactionId()),"original uncertain ID");}
                check(module.authorityDomainId()==null,"quarantine domain");
                unreadable.set(false);
                try {module.transferReceipt(request.transactionId());throw new AssertionError("quarantined receipt readable");}
                catch(IllegalStateException expected){}
                Method flush=TreasuryModule.class.getDeclaredMethod("flushState");flush.setAccessible(true);flush.invoke(module);
            } else if(phase.equals("second")||phase.equals("third")) {
                check(module.authorityDomainId().equals(Files.readString(directory.resolve("domain.txt"))),"same domain");
                check(module.transfer(request).status()==NationTransferService.TransferStatus.ALREADY_APPLIED,"original ID restart retry");
                check(module.transferReceipt(request.transactionId()).orElseThrow().amount().equals(new BigDecimal("20.00")),"receipt amount");
                check(module.balance(PAYER).equals(new BigDecimal("80.00")),"payer once");
                check(module.balance(RECEIVER).equals(new BigDecimal("30.00")),"receiver once");
                var wrong=new NationTransferService.TransferRequest(request.transactionId(),PAYER,RECEIVER,new BigDecimal("21.00"),request.category(),request.description());
                check(module.transfer(wrong).status()==NationTransferService.TransferStatus.REJECTED,"same ID different terms rejected");
            } else throw new IllegalArgumentException(phase);
            try(Connection connection=DriverManager.getConnection(url);Statement statement=connection.createStatement();ResultSet result=statement.executeQuery("SELECT property_key,property_value FROM starcore_treasury_state")) {
                var state=new Properties();while(result.next())state.setProperty(result.getString(1),result.getString(2));
                var persisted=state.containsKey("receipt-archive.version")?storage.readState(state):TreasuryStateValidation.read(state);
                if(state.containsKey("receipt-archive.version")) {
                    check(persisted.transferReceipts().isEmpty()&&persisted.creditReceipts().isEmpty(),"no resident durable receipts");
                    check(state.getProperty("receipt-archive.count").equals("3"),"three complete archived receipts");
                } else {
                    check(persisted.transferReceipts().size()==1,"one durable transfer");
                    check(persisted.creditReceipts().size()==2,"internal deposit receipts");
                }
                check(persisted.balances().get(PAYER).equals(new BigDecimal("80.00")),"durable payer");
                check(persisted.balances().get(RECEIVER).equals(new BigDecimal("30.00")),"durable receiver");
            }
            System.out.println("TREASURY_RECOVERY_CONTRACT_PASS phase="+phase);
        } finally {storage.shutdown();AbstractModuleStateStorage.shutdownNamespace("test");}
    }
    static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
