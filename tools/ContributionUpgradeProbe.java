import io.github.addxiaoyi.starchronicle.core.RuleEngine;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;

/** Run against an explicit copy of a stopped, previously verified schema-7 database. */
public final class ContributionUpgradeProbe {
    private static void require(boolean condition,String message) {if(!condition)throw new IllegalStateException(message);}
    private static Map<String,List<String>> preserved(SqliteStore store) {
        return store.transaction(c->{
            Map<String,List<String>> snapshot=new TreeMap<>();
            for(String table:List.of("epic_operations","epic_operation_providers","instances","outcomes","quest_gates","quest_gate_completions","story_choice_log")) {
                var rows=new ArrayList<String>();
                try(var s=c.createStatement();var r=s.executeQuery("SELECT * FROM "+table)){
                    int columns=r.getMetaData().getColumnCount();
                    while(r.next()){var row=new ArrayList<String>();for(int i=1;i<=columns;i++)row.add(r.getString(i));rows.add(SqliteStore.json(row));}
                }
                Collections.sort(rows);snapshot.put(table,List.copyOf(rows));
            }
            return snapshot;
        });
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("stopped original.db new-copy.db");
        Path source=Path.of(args[0]),copy=Path.of(args[1]);
        if(Files.exists(copy)||!Files.isRegularFile(source)||Files.isSymbolicLink(source))throw new IllegalArgumentException("Require existing regular source and new copy path");
        if(Files.exists(Path.of(source+"-wal"))&&Files.size(Path.of(source+"-wal"))>0)throw new IllegalStateException("Source has an uncheckpointed WAL");
        Files.createDirectories(copy.toAbsolutePath().getParent());Files.copy(source,copy);
        Map<String,List<String>> before;
        try(var store=new SqliteStore(copy)) {
            before=preserved(store);
            var engine=new RuleEngine(store,List.of(),Clock.systemUTC());
            require(engine.contributions(null,null,0,100).isEmpty(),"Source is not the old pre-projection checkpoint");
            require(engine.recoverEpicContributions(1)==1,"First bounded batch must recover exactly one transfer");
            require(engine.contributions(null,null,0,100).size()==2,"First transfer needs two attributions");
            require(before.equals(preserved(store)),"Recovery changed original operations, instances, outcomes or task/choice evidence");
        }
        try(var store=new SqliteStore(copy)) {
            var engine=new RuleEngine(store,List.of(),Clock.systemUTC());
            require(engine.recoverEpicContributions(2)==2,"Second batch must remain bounded");
            require(engine.recoverEpicContributions(2)==1,"Final transfer missing");
            require(engine.recoverEpicContributions(2)==0,"Recovery replay duplicated a transfer");
            require(engine.contributions(null,null,0,100).size()==8,"Recovered contribution count differs");
            require(before.equals(preserved(store)),"Reopen changed original historical state");
            System.out.println(SqliteStore.json(Map.of("status","PASS","copy",copy.toString(),"recoveredOperations",4,"contributions",8,"batches",List.of(1,2,1,0),"reopened",true,"nativeCalls",0,"historicalTablesUnchanged",before.keySet())));
        }
    }
}
