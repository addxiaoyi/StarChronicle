package dev.starcore.starcore.module.treasury;

import dev.starcore.starcore.core.storage.AbstractModuleStateStorage;
import dev.starcore.starcore.module.nation.model.NationId;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;
import org.sqlite.SQLiteDataSource;

/** Compiled against the frozen legacy JAR; compares original receipts and low-heap restart. */
public final class TreasuryArchiveProbe {
    static final NationId PAYER=new NationId(new UUID(0,1)),RECEIVER=new NationId(new UUID(0,2));
    static final String DOMAIN=new UUID(0,30).toString();
    public static void main(String[] args)throws Exception {
        String phase=args[0];Path folder=Path.of(args[1]);int count=Integer.parseInt(args[2]);
        var source=new SQLiteDataSource();source.setUrl("jdbc:sqlite:"+folder.resolve("treasury.db"));
        Properties original=new Properties();
        if(phase.equals("seed")) {
            var spending=new LinkedHashMap<String,NationSpendingService.SpendingReceipt>();
            var credit=new LinkedHashMap<String,NationCreditService.CreditReceipt>();
            var transfer=new LinkedHashMap<String,NationTransferService.TransferReceipt>();
            for(int i=0;i<count;i++) {
                String id="receipt-"+i;
                spending.put(id,new NationSpendingService.SpendingReceipt(id,PAYER,new BigDecimal("2.00"),NationSpendingService.SpendingCategory.RESEARCH,"历史支出\n=\\ "+i,Instant.EPOCH,i%3==0));
                credit.put(id,new NationCreditService.CreditReceipt(id,PAYER,new BigDecimal("3.00"),NationCreditService.CreditCategory.OTHER,"原收入 "+i,Instant.EPOCH));
                transfer.put(id,new NationTransferService.TransferReceipt(id,PAYER,RECEIVER,BigDecimal.ONE,NationTransferService.TransferCategory.OTHER,"原转账 "+i,Instant.EPOCH));
            }
            original=TreasuryStateCodec.toProperties(new TreasuryStateCodec.TreasuryState(DOMAIN,
                Map.of(PAYER,new BigDecimal("100.00"),RECEIVER,new BigDecimal("10.00")),spending,credit,transfer));
            try(var out=Files.newOutputStream(folder.resolve("treasury.properties"),StandardOpenOption.CREATE_NEW)){original.store(out,"Frozen legacy treasury receipts");}
        }
        var storage=new SqlTreasuryStateStorage(source,original,Logger.getLogger("treasury-capacity"));
        try {
            if(phase.equals("seed")){storage.load();System.out.println("SEED PASS");return;}
            var module=new TreasuryModule();var field=TreasuryModule.class.getDeclaredField("stateStorage");field.setAccessible(true);field.set(module,storage);
            long start=System.nanoTime();invoke(module,"loadState");long load=(System.nanoTime()-start)/1000;
            int before=resident(module);require(before==(phase.equals("restart")?0:count*3),"Initial resident receipts");
            start=System.nanoTime();invoke(module,"flushState");long first=(System.nanoTime()-start)/1000;
            int after=resident(module);var snapshot=storage.load();boolean archived=snapshot.containsKey("receipt-archive.version");
            require(after==(archived?0:count*3),"Only pending receipts resident");
            start=System.nanoTime();for(int i=0;i<20;i++)invoke(module,"flushState");long twenty=(System.nanoTime()-start)/1000;
            for(String id:List.of("receipt-0","receipt-1","receipt-"+(count/2),"receipt-"+(count-1))) {
                require(module.spendingReceipt(id).isPresent(),"Spending retained");require(module.creditReceipt(id).isPresent(),"Credit retained");require(module.transferReceipt(id).isPresent(),"Transfer retained");
            }
            require(module.transfer(new NationTransferService.TransferRequest("receipt-1",PAYER,RECEIVER,BigDecimal.ONE,NationTransferService.TransferCategory.OTHER,"原转账 1")).status()==NationTransferService.TransferStatus.ALREADY_APPLIED,"Original ID replay");
            require(module.credit(new NationCreditService.CreditRequest("receipt-1",PAYER,new BigDecimal("3.00"),NationCreditService.CreditCategory.OTHER,"原收入 1")).status()==NationCreditService.CreditStatus.ALREADY_APPLIED,"Original credit replay");
            require(module.balance(PAYER).equals(new BigDecimal("100.00"))&&module.balance(RECEIVER).equals(new BigDecimal("10.00")),"Balances unchanged");
            require(module.spendingReceipt("missing").isEmpty()&&module.creditReceipt("missing").isEmpty()&&module.transferReceipt("missing").isEmpty(),"Missing IDs absent");
            var report="{\"status\":\"PASS\",\"mode\":\""+phase+"\",\"archived\":"+archived+",\"receipts\":"+(count*3)
                +",\"resident_before\":"+before+",\"resident_after\":"+after+",\"snapshot_rows\":"+snapshot.size()
                +",\"load_micros\":"+load+",\"first_save_micros\":"+first+",\"twenty_saves_micros\":"+twenty+",\"max_heap_bytes\":"+Runtime.getRuntime().maxMemory()+"}";
            Files.writeString(Path.of(args[3]),report+"\n");System.out.println(report);
        }finally{storage.shutdown();AbstractModuleStateStorage.shutdownNamespace("test");}
    }
    static int resident(TreasuryModule module)throws Exception {int total=0;for(String name:List.of("spendingReceipts","creditReceipts","transferReceipts")){var f=TreasuryModule.class.getDeclaredField(name);f.setAccessible(true);total+=((Map<?,?>)f.get(module)).size();}return total;}
    static void invoke(TreasuryModule module,String name)throws Exception{var m=TreasuryModule.class.getDeclaredMethod(name);m.setAccessible(true);try{m.invoke(module);}catch(InvocationTargetException error){if(error.getCause() instanceof RuntimeException failure)throw failure;throw error;}}
    static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
