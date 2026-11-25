package dev.starcore.starcore.module.resource;

import dev.starcore.starcore.core.storage.AbstractModuleStateStorage;
import dev.starcore.starcore.module.resource.ProcessingService.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.sqlite.SQLiteDataSource;

/** Actual factory start/complete transactions with reverse-sorted IDs and JDBC row counts. */
public final class FactoryStableWriteProbe {
    static final class TestClock extends Clock {
        Instant now;
        TestClock(Instant now){this.now=now;}
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return now;}
    }
    public static void main(String[] args)throws Exception {
        String phase=args[0];Path directory=Path.of(args[1]);int historical=Integer.parseInt(args[2]),operations=Integer.parseInt(args[3]);
        var database=new SQLiteDataSource();database.setUrl("jdbc:sqlite:"+directory.resolve("factory.db"));
        DataSource counted=(DataSource)Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class<?>[]{DataSource.class},(p,m,a)->{
            Object value=ResourceSnapshotWriteProbe.invoke(database,m,a);return value instanceof Connection c?ResourceSnapshotWriteProbe.connection(c):value;
        });
        var storage=new SqlResourceStateStorage(counted,new Properties(),Logger.getLogger("stable-factory"));
        var clock=new TestClock(FactoryRecoveryProbe.TIME.plusSeconds(historical*3L+100));
        try {
            var module=new ResourceModule(storage,clock);require(!module.authorityDomainId().isBlank(),"Authoritative restored module");
            var processing=new SimpleProcessingService(module,new SimpleResourcePriceService());
            Map<String,Object> report=new LinkedHashMap<>();
            if(phase.equals("run")) {
                require(module.amount(FactoryRecoveryProbe.NATION,"industrial")==historical*2L,"Initial inventory");
                reset();long started=System.nanoTime();
                require(processing.completeFactoryRun("batch-"+historical,FactoryRecoveryProbe.NATION,null).status()==FactoryStatus.COMPLETED,"Resume existing V1 active run");
                report.put("upgrade_completion",Map.of("inserted",ResourceSnapshotWriteProbe.INSERTS.get(),"deleted",ResourceSnapshotWriteProbe.DELETES.get(),"micros",(System.nanoTime()-started)/1000));
                require(module.grant(FactoryRecoveryProbe.NATION,"mineral",operations*3L)&&module.grant(FactoryRecoveryProbe.NATION,"energy",operations),"Fund new independent batches");
                processing.initializeDefaultRecipes();reset();
                List<Long> starts=new ArrayList<>(),completions=new ArrayList<>();List<Map<String,Object>> writes=new ArrayList<>();
                for(int n=operations;n>0;n--) {
                    String id=id(n);var request=new FactoryRequest(id,FactoryRecoveryProbe.FACTORY,FactoryRecoveryProbe.NATION,null,"national_industry",1);
                    long inserted=ResourceSnapshotWriteProbe.INSERTS.get(),deleted=ResourceSnapshotWriteProbe.DELETES.get();started=System.nanoTime();
                    require(processing.startFactoryRun(request).status()==FactoryStatus.STARTED,"New descending ID start");starts.add((System.nanoTime()-started)/1000);
                    writes.add(Map.of("phase","start","run",id,"inserted",ResourceSnapshotWriteProbe.INSERTS.get()-inserted,"deleted",ResourceSnapshotWriteProbe.DELETES.get()-deleted));
                    clock.now=clock.now.plusSeconds(2);inserted=ResourceSnapshotWriteProbe.INSERTS.get();deleted=ResourceSnapshotWriteProbe.DELETES.get();started=System.nanoTime();
                    require(processing.completeFactoryRun(id,FactoryRecoveryProbe.NATION,null).status()==FactoryStatus.COMPLETED,"Exact committed output");completions.add((System.nanoTime()-started)/1000);
                    writes.add(Map.of("phase","complete","run",id,"inserted",ResourceSnapshotWriteProbe.INSERTS.get()-inserted,"deleted",ResourceSnapshotWriteProbe.DELETES.get()-deleted));
                }
                report.put("state_rows_inserted",ResourceSnapshotWriteProbe.INSERTS.get());report.put("state_rows_deleted",ResourceSnapshotWriteProbe.DELETES.get());
                report.put("start_micros",metrics(starts));report.put("completion_micros",metrics(completions));report.put("writes",writes);
            }else require(phase.equals("restart"),"Known phase");
            var saved=storage.load();var snapshot=ResourceStateCodec.snapshotFromProperties(saved,ResourceModule.RESOURCE_TYPES);
            var runs=FactoryRunStore.read(saved,snapshot.factories(),ResourceModule.RESOURCE_TYPES,storage);
            require(runs.size()==historical+1+operations,"All retry keys remain");
            for(int n=0;n<historical;n++)require(FactoryRecoveryProbe.run(n).complete(FactoryRecoveryProbe.TIME.plusSeconds(n*3L+2),n+1).equals(runs.get("batch-"+n)),"Historical evidence unchanged");
            require(runs.get("batch-"+historical).completionSequence()==historical+1,"Original active batch completed once");
            for(int n=operations;n>0;n--) {
                var run=runs.get(id(n));require(run.completed()&&run.completionSequence()==historical+operations-n+2,"New sequence and completion");
                require(processing.completeFactoryRun(id(n),FactoryRecoveryProbe.NATION,null).status()==FactoryStatus.ALREADY_COMPLETED,"Restart/replay does not pay again");
            }
            require(module.amount(FactoryRecoveryProbe.NATION,"industrial")==2L*(historical+1+operations),"Final durable output");
            require(module.amount(FactoryRecoveryProbe.NATION,"mineral")==0&&module.amount(FactoryRecoveryProbe.NATION,"energy")==0,"Exact consumed inputs");
            require(storage.latestProductionSequence()==historical+1+operations,"One receipt each");
            report.put("format",saved.getProperty(FactoryRunStore.PREFIX+"version"));report.put("historical_completed",historical);report.put("new_batches",operations);
            report.put("max_heap_bytes",Runtime.getRuntime().maxMemory());report.put("status","PASS");
            // Gson is already a provided runtime library and does not vary with either plugin's shaded mapper.
            String json=new com.google.gson.Gson().toJson(report);Files.writeString(Path.of(args[4]),json+"\n");System.out.println(json);
        }finally{AbstractModuleStateStorage.shutdownNamespace("test");}
    }
    static String id(int n){return String.format(Locale.ROOT,"000-%05d",n);}
    static void reset(){ResourceSnapshotWriteProbe.INSERTS.set(0);ResourceSnapshotWriteProbe.DELETES.set(0);}
    static Map<String,Long> metrics(List<Long> values){Collections.sort(values);return Map.of("samples",(long)values.size(),"p50",values.get(values.size()/2),"p95",values.get((int)(values.size()*.95)),"max",values.getLast());}
    static void require(boolean value,String label){if(!value)throw new IllegalStateException(label);}
}
