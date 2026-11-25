package dev.starcore.starcore.module.resource;

import dev.starcore.starcore.core.storage.AbstractModuleStateStorage;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import org.sqlite.SQLiteDataSource;

/** Measures actual recovery allocation, retaining the whole loaded input and every returned run. */
public final class FactoryCanonicalMemoryProbe {
    public static void main(String[] args)throws Exception {
        String mode=args[0];Path directory=Path.of(args[1]);int count=Integer.parseInt(args[2]);
        var database=new SQLiteDataSource();database.setUrl("jdbc:sqlite:"+directory.resolve("factory.db"));
        var file=new FactoryRecoveryProbe.FileStore(directory.resolve("factory.properties"));
        ResourceStateStorage storage=mode.equals("file")?file:new SqlResourceStateStorage(database,new Properties(),Logger.getLogger("canonical-memory"));
        var allocation=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        require(allocation.isThreadAllocatedMemorySupported(),"JDK allocation counter required");
        allocation.setThreadAllocatedMemoryEnabled(true);long thread=Thread.currentThread().threadId();
        try {
            var p=storage.load();var snapshot=ResourceStateCodec.snapshotFromProperties(p,ResourceModule.RESOURCE_TYPES);
            System.gc();long inputLive=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
            long before=allocation.getThreadAllocatedBytes(thread),start=System.nanoTime();
            var runs=FactoryRunStore.read(p,snapshot.factories(),ResourceModule.RESOURCE_TYPES,storage);
            long micros=(System.nanoTime()-start)/1000,allocated=allocation.getThreadAllocatedBytes(thread)-before;
            System.gc();long recoveredLive=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
            require(runs.size()==count+1,"All retry keys retained");
            for(int n=0;n<count;n++)require(FactoryRecoveryProbe.run(n).complete(FactoryRecoveryProbe.TIME.plusSeconds(n*3L+2),n+1).equals(runs.get("batch-"+n)),"Completed run evidence");
            require(FactoryRecoveryProbe.run(count).equals(runs.get("batch-"+count)),"Active run evidence");
            require(snapshot.stockpiles().get(FactoryRecoveryProbe.NATION).get("industrial")==count*2L,"Stockpile unchanged");
            require(!p.stringPropertyNames().isEmpty(),"Input retained across measurement");
            Map<String,Object> result=new LinkedHashMap<>();result.put("status","PASS");result.put("mode",mode);
            result.put("completed",count);result.put("active",1);result.put("format",p.getProperty(FactoryRunStore.PREFIX+"version"));
            result.put("file_loads",file.loads);result.put("restore_micros",micros);result.put("recovery_thread_allocated_bytes",allocated);
            result.put("input_live_heap_bytes",inputLive);result.put("recovered_live_heap_bytes",recoveredLive);result.put("max_heap_bytes",Runtime.getRuntime().maxMemory());
            String json=new com.google.gson.Gson().toJson(result);Files.writeString(Path.of(args[3]),json+"\n");System.out.println(json);
        }finally{AbstractModuleStateStorage.shutdownNamespace("test");}
    }
    static void require(boolean value,String label){if(!value)throw new IllegalStateException(label);}
}
