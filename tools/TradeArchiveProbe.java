package dev.starcore.starcore.module.resource;

import dev.starcore.starcore.core.storage.AbstractModuleStateStorage;
import dev.starcore.starcore.module.nation.model.NationId;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;
import org.sqlite.SQLiteDataSource;

/** Immutable old V1 fixture, packaged module saves/queries/restarts, no server process. */
public final class TradeArchiveProbe {
    static final NationId EXPORTER = new NationId(new UUID(0,1)), IMPORTER = new NationId(new UUID(0,2));
    static final String DOMAIN = new UUID(0,30).toString(), TREASURY = new UUID(0,31).toString();
    static final Instant TIME = Instant.parse("2026-11-26T00:00:00Z");
    public static void main(String[] args) throws Exception {
        Path folder = Path.of(args[1]); int count = Integer.parseInt(args[2]);
        var db = new SQLiteDataSource(); db.setUrl("jdbc:sqlite:" + folder.resolve("trade.db"));
        Properties legacy = new Properties();
        if (args[0].equals("seed")) {
            legacy = ResourceStateCodec.toProperties(Map.of(EXPORTER,Map.of("industrial",100L),IMPORTER,Map.of("industrial",count*2L)));
            legacy.setProperty("resource.authority-domain",DOMAIN);
            Map<String,TradeExecution> values = new LinkedHashMap<>();
            for (int i=1;i<=count;i++) {
                var value = execution("trade-"+i,TradeExecution.State.COMPLETED,i); values.put(value.executionId(),value);
                ResourceTransferReceiptStore.append(legacy,receipt(value,i));
            }
            var paid = execution("paid",TradeExecution.State.PAID,0); values.put("paid",paid);
            ResourceTransferReceiptStore.append(legacy,receipt(paid,count+1));
            values.put("waiting",execution("waiting",TradeExecution.State.AWAITING_APPROVAL,0));
            TradeExecutionStore.write(legacy,values);
            FactoryRunStore.write(legacy,Map.of(),Map.of());
            try(var out=Files.newOutputStream(folder.resolve("trade.properties"),StandardOpenOption.CREATE_NEW)) { legacy.store(out,"Old V1 executions"); }
        }
        var storage = new SqlResourceStateStorage(db,legacy,Logger.getLogger("trade-archive"));
        try {
            if(args[0].equals("seed")) { storage.load(); System.out.println("SEED PASS"); return; }
            var module = new ResourceModule(); var service = new SimpleResourceTradeService(module,null);
            set(module,"stateStorage",storage); set(module,"tradeService",service);
            long start = System.nanoTime(); invoke(module,"loadState"); long loadMicros=(System.nanoTime()-start)/1000;
            require(DOMAIN.equals(module.authorityDomainId()),"Authority restored");
            int before=resident(module);
            start=System.nanoTime(); invoke(module,"flushState"); long migrateMicros=(System.nanoTime()-start)/1000;
            int after=resident(module);
            var snapshot=storage.load(); String version=snapshot.getProperty(TradeExecutionStore.PREFIX+"version");
            require(before==(args[0].equals("restart")?2:count+2),"All old IDs initially retained");
            require(after==(version.equals("2")?2:count+2),"Terminal retention mode");
            start=System.nanoTime(); for(int i=0;i<20;i++) invoke(module,"flushState");
            long saveMicros=(System.nanoTime()-start)/1000;
            List<TradeExecution> expected=new ArrayList<>();
            for(int i=1;i<=count;i++) expected.add(execution("trade-"+i,TradeExecution.State.COMPLETED,i));
            expected.add(execution("paid",TradeExecution.State.PAID,0)); expected.add(execution("waiting",TradeExecution.State.AWAITING_APPROVAL,0));
            expected.sort(Comparator.comparing(TradeExecution::createdAt).reversed().thenComparing(TradeExecution::executionId));
            for(int offset:List.of(0,250,count/2,count-7,count,count+2,Integer.MAX_VALUE))
                require(expected.stream().skip(offset).limit(10).toList().equals(service.tradeExecutions(IMPORTER,offset,10)),"Exact merged deep page");
            for(String id:List.of("trade-1","trade-"+(count/2),"trade-"+count,"paid","waiting"))
                require(expected.stream().filter(e->e.executionId().equals(id)).findFirst().equals(service.tradeExecution(id)),"Exact original proof");
            require(service.tradeExecution("missing").isEmpty(),"Absent ID stays absent");
            require(module.amount(EXPORTER,"industrial")==100&&module.amount(IMPORTER,"industrial")==count*2L,"Inventories unchanged");
            var report="{\"status\":\"PASS\",\"mode\":\""+args[0]+"\",\"version\":\""+version+"\",\"completed\":"+count
                +",\"resident_before\":"+before+",\"resident_after\":"+after+",\"snapshot_rows\":"+snapshot.size()
                +",\"load_micros\":"+loadMicros+",\"first_save_micros\":"+migrateMicros+",\"twenty_saves_micros\":"+saveMicros
                +",\"max_heap_bytes\":"+Runtime.getRuntime().maxMemory()+"}";
            Files.writeString(Path.of(args[3]),report+"\n"); System.out.println(report);
        } finally {storage.shutdown();AbstractModuleStateStorage.shutdownNamespace("test");}
    }
    static TradeExecution execution(String id,TradeExecution.State state,long sequence) {
        return new TradeExecution(id,new UUID(0,10),DOMAIN,TREASURY,EXPORTER,IMPORTER,"industrial",2,2.5,new BigDecimal("5.00"),TIME,TIME.plusSeconds(3600),null,null,true,state,List.of(),null,sequence,state.terminal()?TIME.plusSeconds(1):null);
    }
    static NationResourceTransactionService.TransferReceipt receipt(TradeExecution value,long sequence) {
        var request=NativeTradeExecution.delivery(value);
        return new NationResourceTransactionService.TransferReceipt(request.transactionId(),DOMAIN,sequence,EXPORTER,IMPORTER,request.resources(),request.category(),request.description(),TIME.plusSeconds(1));
    }
    static int resident(ResourceModule module)throws Exception { var f=ResourceModule.class.getDeclaredField("tradeExecutions");f.setAccessible(true);return ((Map<?,?>)f.get(module)).size(); }
    static void set(Object object,String name,Object value)throws Exception {var f=object.getClass().getDeclaredField(name);f.setAccessible(true);f.set(object,value);}
    static void invoke(Object object,String name)throws Exception {var m=object.getClass().getDeclaredMethod(name);m.setAccessible(true);try{m.invoke(object);}catch(InvocationTargetException error){if(error.getCause() instanceof RuntimeException failure)throw failure;throw error;}}
    static void require(boolean value,String message) {if(!value)throw new AssertionError(message);}
}
