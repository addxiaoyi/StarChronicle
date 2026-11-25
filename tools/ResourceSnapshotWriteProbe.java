package dev.starcore.starcore.module.resource;

import dev.starcore.starcore.core.storage.AbstractModuleStateStorage;
import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.sqlite.SQLiteDataSource;

/** Real ResourceModule grants; count physical state-table rows through JDBC, without SQL triggers. */
public final class ResourceSnapshotWriteProbe {
    static final AtomicLong INSERTS=new AtomicLong(),DELETES=new AtomicLong();
    public static void main(String[] args)throws Exception {
        Path directory=Path.of(args[0]);int runs=Integer.parseInt(args[1]),operations=Integer.parseInt(args[2]);
        var database=new SQLiteDataSource();database.setUrl("jdbc:sqlite:"+directory.resolve("factory.db"));
        DataSource counted=(DataSource)Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class<?>[]{DataSource.class},(p,m,a)->{
            Object value=invoke(database,m,a);return value instanceof Connection c?connection(c):value;
        });
        var storage=new SqlResourceStateStorage(counted,new Properties(),Logger.getLogger("snapshot-write"));
        try {
            var module=new ResourceModule(storage);require(!module.authorityDomainId().isBlank(),"Authoritative restored module");
            require(module.amount(FactoryRecoveryProbe.NATION,"industrial")==runs*2L,"Initial inventory");
            INSERTS.set(0);DELETES.set(0);List<Long> times=new ArrayList<>();long firstInserted=0,firstDeleted=0;
            for(int i=0;i<operations;i++) {
                long start=System.nanoTime();require(module.grant(FactoryRecoveryProbe.NATION,"industrial",1),"Committed grant");times.add((System.nanoTime()-start)/1000);
                if(i==0){firstInserted=INSERTS.get();firstDeleted=DELETES.get();}
            }
            long inserted=INSERTS.get(),deleted=DELETES.get();Collections.sort(times);
            var restarted=new ResourceModule(storage);require(restarted.amount(FactoryRecoveryProbe.NATION,"industrial")==runs*2L+operations,"Durable inventory");
            var p=storage.load();var snapshot=ResourceStateCodec.snapshotFromProperties(p,ResourceModule.RESOURCE_TYPES);
            var restored=FactoryRunStore.read(p,snapshot.factories(),ResourceModule.RESOURCE_TYPES,storage);
            require(restored.size()==runs+1,"All retry keys");
            for(int i=0;i<runs;i++)require(FactoryRecoveryProbe.run(i).complete(FactoryRecoveryProbe.TIME.plusSeconds(i*3L+2),i+1).equals(restored.get("batch-"+i)),"Exact completion evidence");
            require(FactoryRecoveryProbe.run(runs).equals(restored.get("batch-"+runs)),"Active evidence");
            require(storage.latestProductionSequence()==runs,"No fabricated production");
            String result="{\"status\":\"PASS\",\"grants\":"+operations+",\"completed_runs\":"+runs+",\"state_rows_inserted\":"+inserted+",\"state_rows_deleted\":"+deleted+
                ",\"first_grant_inserted\":"+firstInserted+",\"first_grant_deleted\":"+firstDeleted+",\"format\":\""+p.getProperty(FactoryRunStore.PREFIX+"version")+"\",\"p50_micros\":"+times.get(times.size()/2)+",\"p95_micros\":"+times.get((int)(times.size()*.95))+",\"max_micros\":"+times.getLast()+",\"max_heap_bytes\":"+Runtime.getRuntime().maxMemory()+"}";
            Files.writeString(Path.of(args[3]),result+"\n");System.out.println(result);
        } finally {AbstractModuleStateStorage.shutdownNamespace("test");}
    }
    static Connection connection(Connection actual){return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
        Object value=invoke(actual,m,a);
        if(value instanceof PreparedStatement statement)return statement(statement, a[0].toString());
        if(value instanceof Statement statement)return statement(statement,null);
        return value;
    });}
    static Object statement(Statement actual,String prepared){return Proxy.newProxyInstance(Statement.class.getClassLoader(),new Class<?>[]{prepared==null?Statement.class:PreparedStatement.class},(p,m,a)->{
        Object value=invoke(actual,m,a);String sql=prepared==null&&a!=null&&a.length>0&&a[0] instanceof String?a[0].toString():prepared;
        AtomicLong counter=sql!=null&&sql.startsWith("INSERT INTO starcore_resource_state ")?INSERTS:sql!=null&&sql.startsWith("DELETE FROM starcore_resource_state")?DELETES:null;
        if(counter!=null) {
            if(m.getName().equals("executeBatch")){for(int count:(int[])value){require(count>=0,"Driver supplies exact changed-row counts");counter.addAndGet(count);}}
            else if(m.getName().equals("executeUpdate"))counter.addAndGet(((Number)value).longValue());
            else if(m.getName().equals("execute"))counter.addAndGet(Math.max(0,actual.getUpdateCount()));
        }
        return value;
    });}
    static Object invoke(Object target,Method method,Object[] args)throws Throwable {try{return method.invoke(target,args);}catch(InvocationTargetException e){throw e.getCause();}}
    static void require(boolean value,String message){if(!value)throw new IllegalStateException(message);}
}
