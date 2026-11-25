package dev.starcore.starcore.module.resource;

import dev.starcore.starcore.core.storage.AbstractModuleStateStorage;
import dev.starcore.starcore.module.nation.model.NationId;
import dev.starcore.starcore.module.resource.model.Factory;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;
import org.sqlite.SQLiteDataSource;

/** Exact packaged recovery codec/storage, synthetic immutable batches, no Minecraft. */
public final class FactoryRecoveryProbe {
    static final Instant TIME=Instant.parse("2026-11-26T00:00:00Z");
    static final NationId NATION=new NationId(new UUID(0,1));
    static final UUID FACTORY=new UUID(0,2);
    static final String DOMAIN=new UUID(0,3).toString();
    static class FileStore implements ResourceStateStorage {
        final Path path;int loads;
        FileStore(Path path){this.path=path;}
        public Properties load(){loads++;return read(path);}
        public void save(Properties p){throw new AssertionError("Recovery must not write");}
    }
    public static void main(String[] args)throws Exception {
        Path dir=Path.of(args[1]);int count=Integer.parseInt(args[2]);
        var database=new SQLiteDataSource();database.setUrl("jdbc:sqlite:"+dir.resolve("factory.db"));
        if(args[0].equals("seed")) {
            require(!Files.exists(dir.resolve("factory.properties")),"Fresh fixture only");
            var factory=new Factory(FACTORY,NATION,"Recovery sample",Factory.FactoryType.SMELTER,TIME,1,1,true,"fixed",TIME.plusSeconds(count*3L),1);
            var factories=Map.of(FACTORY,factory);
            var p=ResourceStateCodec.toProperties(Map.of(NATION,Map.of("industrial",count*2L)),Map.of(),factories);
            p.setProperty("resource.authority-domain",DOMAIN);Map<String,FactoryRun> runs=new LinkedHashMap<>();
            for(int n=0;n<count;n++) {
                var run=run(n).complete(TIME.plusSeconds(n*3L+2),n+1);runs.put(run.runId(),run);writeReceipt(p,run.productionReceipt());
            }
            var active=run(count);runs.put(active.runId(),active);FactoryRunStore.write(p,runs,factories);
            try(var out=Files.newOutputStream(dir.resolve("factory.properties"),StandardOpenOption.CREATE_NEW)){p.store(out,"Immutable recovery sample");}
            var storage=new SqlResourceStateStorage(database,new Properties(),Logger.getLogger("factory-recovery"));
            try{storage.load();storage.save(p);}finally{AbstractModuleStateStorage.shutdownNamespace("test");}
            System.out.println("SEEDED completed="+count+" active=1");return;
        }
        FileStore file=new FileStore(dir.resolve("factory.properties"));
        ResourceStateStorage storage=args[0].equals("file")?file:new SqlResourceStateStorage(database,new Properties(),Logger.getLogger("factory-recovery"));
        long start=System.nanoTime();
        try {
            var p=storage.load();var snapshot=ResourceStateCodec.snapshotFromProperties(p,ResourceModule.RESOURCE_TYPES);
            var runs=FactoryRunStore.read(p,snapshot.factories(),ResourceModule.RESOURCE_TYPES,storage);
            long micros=(System.nanoTime()-start)/1000;
            require(runs.size()==count+1,"All retry keys retained");
            for(int n=0;n<count;n++)require(run(n).complete(TIME.plusSeconds(n*3L+2),n+1).equals(runs.get("batch-"+n)),"Completed run evidence");
            require(run(count).equals(runs.get("batch-"+count)),"Active run evidence");
            require(snapshot.stockpiles().get(NATION).get("industrial")==count*2L,"Stockpile unchanged");
            String result="{\"status\":\"PASS\",\"mode\":\""+args[0]+"\",\"completed\":"+count+",\"active\":1,\"file_loads\":"+file.loads+
                ",\"restore_micros\":"+micros+",\"max_heap_bytes\":"+Runtime.getRuntime().maxMemory()+"}";
            Files.writeString(Path.of(args[3]),result+"\n");System.out.println(result);
        } finally {AbstractModuleStateStorage.shutdownNamespace("test");}
    }
    static FactoryRun run(int n){return new FactoryRun("batch-"+n,DOMAIN,FACTORY,NATION,null,"fixed",1,Map.of("mineral",3L),Map.of("industrial",2L),TIME.plusSeconds(n*3L),TIME.plusSeconds(n*3L+1),null,0);}
    static Properties read(Path path){try(var in=Files.newInputStream(path)){var p=new Properties();p.load(in);return p;}catch(Exception error){throw new IllegalStateException(error);}}
    static void writeReceipt(Properties p,ProductionReceipt r){
        String k=ProductionReceiptStore.ROW+r.sequence()+".";
        Map<String,String> fields=Map.of("id",r.operationId(),"domain",r.authorityDomainId(),"nation",r.nationId().toString(),"actor","","recipe",r.recipeId(),"batches",Integer.toString(r.batches()),
            "inputs",ResourceTransferReceiptStore.encodeResources(r.inputs()),"outputs",ResourceTransferReceiptStore.encodeResources(r.outputs()),"at",Long.toString(r.committedAt().toEpochMilli()));
        fields.forEach((key,value)->p.setProperty(k+key,value));ProductionReceiptStore.head(p,r);
    }
    static void require(boolean value,String message){if(!value)throw new IllegalStateException(message);}
}
