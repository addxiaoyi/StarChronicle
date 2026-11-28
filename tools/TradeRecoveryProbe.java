package dev.starcore.starcore.module.resource;

import dev.starcore.starcore.core.storage.AbstractModuleStateStorage;
import dev.starcore.starcore.module.nation.model.NationId;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.Connection;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.sqlite.SQLiteDataSource;

/** Immutable V1 fixtures, exact packaged file/SQLite recovery, no server process. */
public final class TradeRecoveryProbe {
    static final NationId EXPORTER=new NationId(new UUID(0,1)),IMPORTER=new NationId(new UUID(0,2));
    static final String DOMAIN=new UUID(0,30).toString(),TREASURY=new UUID(0,31).toString();
    static final Instant TIME=Instant.parse("2026-11-26T00:00:00Z");
    static class FileStore implements ResourceStateStorage {
        final Path path;int loads;
        FileStore(Path path){this.path=path;}
        public Properties load(){loads++;return read(path);}
        public void save(Properties p){throw new AssertionError("Recovery must not write");}
    }
    public static void main(String[] args)throws Exception {
        String mode=args[0];Path directory=Path.of(args[1]);int count=Integer.parseInt(args[2]);
        var db=new SQLiteDataSource();db.setUrl("jdbc:sqlite:"+directory.resolve("trade.db"));
        if(mode.equals("seed")) {
            var p=ResourceStateCodec.toProperties(Map.of(EXPORTER,Map.of("industrial",100L),IMPORTER,Map.of("industrial",count*2L)));
            p.setProperty("resource.authority-domain",DOMAIN);Map<String,TradeExecution> executions=new LinkedHashMap<>();
            for(int i=1;i<=count;i++) {
                var value=execution("trade-"+i,TradeExecution.State.COMPLETED,i);executions.put(value.executionId(),value);
                ResourceTransferReceiptStore.append(p,receipt(value,i));
            }
            var paid=execution("paid",TradeExecution.State.PAID,0);executions.put(paid.executionId(),paid);
            ResourceTransferReceiptStore.append(p,receipt(paid,count+1));
            var waiting=execution("waiting",TradeExecution.State.AWAITING_APPROVAL,0);executions.put(waiting.executionId(),waiting);
            TradeExecutionStore.write(p,executions);
            try(var out=Files.newOutputStream(directory.resolve("trade.properties"),StandardOpenOption.CREATE_NEW)){p.store(out,"V1 trade recovery fixture");}
            var storage=new SqlResourceStateStorage(db,p,Logger.getLogger("trade-recovery"));
            try{storage.load();}finally{storage.shutdown();AbstractModuleStateStorage.shutdownNamespace("test");}
            System.out.println("SEEDED completed="+count+" pending=2");return;
        }
        var connections=new AtomicInteger();var selects=new AtomicInteger();var maximum=new AtomicInteger();
        DataSource counted=(DataSource)Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class<?>[]{DataSource.class},(proxy,method,values)->{
            Object result=forward(db,method,values);if(!(result instanceof Connection actual))return result;
            connections.incrementAndGet();
            return Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
                if(m.getName().equals("prepareStatement")&&((String)a[0]).startsWith("SELECT * FROM starcore_resource_transfer_receipts")) {
                    selects.incrementAndGet();maximum.accumulateAndGet((int)((String)a[0]).chars().filter(c->c=='?').count(),Math::max);
                }
                return forward(actual,m,a);
            });
        });
        var file=new FileStore(directory.resolve("trade.properties"));
        ResourceStateStorage storage=mode.equals("file")?file:new SqlResourceStateStorage(counted,new Properties(),Logger.getLogger("trade-recovery"));
        try {
            long start=System.nanoTime();var snapshot=storage.load();int before=connections.get();
            var executions=TradeExecutionStore.read(snapshot,storage);long micros=(System.nanoTime()-start)/1000;
            require(executions.size()==count+2,"All IDs retained");
            for(int i=1;i<=count;i++)require(execution("trade-"+i,TradeExecution.State.COMPLETED,i).equals(executions.get("trade-"+i)),"Frozen completion proof");
            require(execution("paid",TradeExecution.State.PAID,0).equals(executions.get("paid")),"Paid pending survives");
            require(execution("waiting",TradeExecution.State.AWAITING_APPROVAL,0).equals(executions.get("waiting")),"Unapproved stays unapproved");
            var decoded=ResourceStateCodec.snapshotFromProperties(snapshot,ResourceModule.RESOURCE_TYPES);
            require(decoded.stockpiles().get(IMPORTER).get("industrial")==count*2L,"Inventory unchanged");
            String report="{\"status\":\"PASS\",\"mode\":\""+mode+"\",\"completed\":"+count+",\"pending\":2,\"file_loads\":"+file.loads
                +",\"validation_connections\":"+(connections.get()-before)+",\"receipt_selects\":"+selects.get()+",\"max_parameters\":"+maximum.get()
                +",\"restore_micros\":"+micros+",\"max_heap_bytes\":"+Runtime.getRuntime().maxMemory()+"}";
            Files.writeString(Path.of(args[3]),report+"\n");System.out.println(report);
        } finally {AbstractModuleStateStorage.shutdownNamespace("test");}
    }
    static TradeExecution execution(String id,TradeExecution.State state,long sequence) {
        return new TradeExecution(id,new UUID(0,10),DOMAIN,TREASURY,EXPORTER,IMPORTER,"industrial",2,2.5,new BigDecimal("5.00"),TIME,TIME.plusSeconds(3600),null,null,true,state,List.of(),null,sequence,state.terminal()?TIME.plusSeconds(1):null);
    }
    static NationResourceTransactionService.TransferReceipt receipt(TradeExecution execution,long sequence) {
        var request=NativeTradeExecution.delivery(execution);
        return new NationResourceTransactionService.TransferReceipt(request.transactionId(),DOMAIN,sequence,EXPORTER,IMPORTER,request.resources(),request.category(),request.description(),TIME.plusSeconds(1));
    }
    static Properties read(Path path){try(var in=Files.newInputStream(path)){var p=new Properties();p.load(in);return p;}catch(Exception e){throw new IllegalStateException(e);}}
    static Object forward(Object target,Method method,Object[] values)throws Throwable{try{return method.invoke(target,values);}catch(InvocationTargetException error){throw error.getCause();}}
    static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
