package dev.starcore.starcore.module.resource;

import dev.starcore.starcore.core.storage.AbstractModuleStateStorage;
import dev.starcore.starcore.module.resource.ProcessingService.*;
import java.lang.management.ManagementFactory;
import java.lang.reflect.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.logging.Logger;
import org.sqlite.SQLiteDataSource;

/** Actual module migration, steady inventory writes and exact old-ID replay; no server. */
public final class FactoryArchiveProbe {
    public static void main(String[] args)throws Exception {
        String phase=args[0];Path directory=Path.of(args[1]);int count=Integer.parseInt(args[2]);
        var db=new SQLiteDataSource();db.setUrl("jdbc:sqlite:"+directory.resolve("factory.db"));
        var storage=new SqlResourceStateStorage(db,new Properties(),Logger.getLogger("factory-archive"));
        var clock=Clock.fixed(FactoryRecoveryProbe.TIME.plusSeconds(count*3L+100),ZoneOffset.UTC);
        try {
            var allocation=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();allocation.setThreadAllocatedMemoryEnabled(true);
            long thread=Thread.currentThread().threadId(),start=System.nanoTime();
            var module=new ResourceModule(storage,clock);long loadMicros=(System.nanoTime()-start)/1000;
            require(!module.authorityDomainId().isBlank(),"Restored authority");
            var processing=new SimpleProcessingService(module,new SimpleResourcePriceService());
            Map<String,Object> report=new LinkedHashMap<>();
            if(phase.equals("run")) {
                long before=allocation.getThreadAllocatedBytes(thread);start=System.nanoTime();
                require(module.grant(FactoryRecoveryProbe.NATION,"industrial",1),"First grant and possible archive migration");
                report.put("first_grant_micros",(System.nanoTime()-start)/1000);report.put("first_grant_thread_allocated_bytes",allocation.getThreadAllocatedBytes(thread)-before);
                List<Long> times=new ArrayList<>();before=allocation.getThreadAllocatedBytes(thread);
                for(int i=0;i<20;i++){start=System.nanoTime();require(module.grant(FactoryRecoveryProbe.NATION,"industrial",1),"Steady grant");times.add((System.nanoTime()-start)/1000);}
                Collections.sort(times);report.put("steady_grants",20);report.put("steady_grant_p50_micros",times.get(10));report.put("steady_grant_thread_allocated_bytes",allocation.getThreadAllocatedBytes(thread)-before);
            }else require(phase.equals("restart"),"Known phase");
            for(int n:new int[]{0,count/2,count-1}) {
                var expected=FactoryRecoveryProbe.run(n).complete(FactoryRecoveryProbe.TIME.plusSeconds(n*3L+2),n+1);
                require(expected.equals(processing.factoryRun(expected.runId()).orElseThrow()),"Exact archived history query");
                require(processing.completeFactoryRun(expected.runId(),expected.nationId(),null).status()==FactoryStatus.ALREADY_COMPLETED,"Archived complete replay");
                require(processing.startFactoryRun(new FactoryRequest(expected.runId(),expected.factoryId(),expected.nationId(),null,expected.recipeId(),expected.batches())).status()==FactoryStatus.ALREADY_COMPLETED,"Archived start replay without recipes");
            }
            require(processing.activeFactoryRun(FactoryRecoveryProbe.FACTORY).orElseThrow().equals(FactoryRecoveryProbe.run(count)),"Original active proof retained");
            require(module.amount(FactoryRecoveryProbe.NATION,"industrial")==count*2L+21,"Only grants affect stockpile");
            require(storage.latestProductionSequence()==count,"Replay has no new receipt");
            var field=ResourceModule.class.getDeclaredField("factoryRuns");field.setAccessible(true);
            int live=((Map<?,?>)field.get(module)).size();
            report.put("retained_run_objects",live);report.put("module_load_micros",loadMicros);
            System.gc();report.put("module_live_heap_bytes",ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
            var p=storage.load();String version=p.getProperty(FactoryRunStore.PREFIX+"version");
            require(live==(version.equals("3")?1:count+1),"Only active rows remain in archived runtime");
            report.put("format",version);report.put("state_properties",p.size());report.put("completed",count);report.put("active",1);
            report.put("max_heap_bytes",Runtime.getRuntime().maxMemory());report.put("status","PASS");
            String json=new com.google.gson.Gson().toJson(report);Files.writeString(Path.of(args[3]),json+"\n");System.out.println(json);
        }finally{AbstractModuleStateStorage.shutdownNamespace("test");}
    }
    static void require(boolean value,String label){if(!value)throw new IllegalStateException(label);}
}
