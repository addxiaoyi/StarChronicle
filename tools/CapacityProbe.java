import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.core.*;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Real packaged schema migration and queries under the runner's fixed heap, without Bukkit. */
public final class CapacityProbe {
    public static void main(String[] args)throws Exception {
        Path path=Path.of(args[0]),report=Path.of(args[1]);int expected=Integer.parseInt(args[2]);
        long start=System.nanoTime();Map<String,Object> result=new LinkedHashMap<>();
        try(var store=new SqliteStore(path)) {
            result.put("open_millis",(System.nanoTime()-start)/1_000_000L);
            var engine=new RuleEngine(store,List.of(),Clock.systemUTC());UUID player=new UUID(0,1);
            var context=new PlayerContext(player,"",Map.of(),Set.of("starchronicle.use","capacity.choice"));
            var owned=engine.instances(player);int expectedOwned=(expected+999)/1000;
            require(owned.size()==expectedOwned,"Complete member query");
            require(engine.instances(player,0,5).equals(owned.subList(0,Math.min(5,owned.size()))),"Member page order");
            require(engine.instance(new UUID(0,Long.MAX_VALUE),owned.getFirst().id()).isEmpty(),"Private lookup");
            Set<String> visibleIds=new TreeSet<>();owned.forEach(i->visibleIds.add(i.id()));engine.availableInstances(context).forEach(i->visibleIds.add(i.id()));
            Set<String> paged=new TreeSet<>();String cursor=null;int pages=0;
            do{var page=engine.storyPage(context,cursor,17);require(page.instances().size()<=17,"Page size");for(var i:page.instances())require(paged.add(i.id()),"Duplicate page item");cursor=page.nextCursor();require(++pages<10000,"Cursor progress");}while(cursor!=null);
            require(paged.equals(visibleIds),"Complete visible pages");
            var permissions=engine.permissionNodes(List.of());require(permissions.equals(Set.of("capacity.choice")),"Pinned permissions");
            List<Long> latencies=new ArrayList<>();
            for(int n=0;n<200;n++){long tick=System.nanoTime();engine.storyPage(context,null,17);latencies.add((System.nanoTime()-tick)/1000);}
            Collections.sort(latencies);result.put("first_page_200_calls_micros",Map.of("p50",latencies.get(100),"p95",latencies.get(190),"max",latencies.get(199)));
            result.put("visible",visibleIds.size());result.put("member",owned.size());result.put("pages",pages);
            store.transaction(c->{
                try(var s=c.createStatement()) {
                    for(String table:List.of("instances","events","outcomes","story_triggers","story_choice_log","story_definitions","instance_members"))
                        try(var r=s.executeQuery("SELECT COUNT(*) FROM "+table)){r.next();result.put(table,r.getLong(1));}
                    try(var r=s.executeQuery("PRAGMA user_version")){r.next();require(r.getInt(1)==8,"Schema version");}
                    try(var r=s.executeQuery("PRAGMA integrity_check")){r.next();require("ok".equals(r.getString(1)),"Integrity");}
                    try(var r=s.executeQuery("PRAGMA foreign_key_check")){require(!r.next(),"Foreign keys");}
                    try(var r=s.executeQuery("SELECT COUNT(*) FROM sqlite_temp_master WHERE name='choice_snapshot_order'")){r.next();require(r.getInt(1)==0,"Temporary migration cleanup");}
                    List<String> plan=new ArrayList<>();
                    try(var r=s.executeQuery("EXPLAIN QUERY PLAN SELECT i.json FROM instance_members m JOIN instances i ON i.id=m.instance_id WHERE m.player_id='"+player+"' ORDER BY i.updated DESC,i.id LIMIT 5")){while(r.next())plan.add(r.getString(4));}
                    require(plan.stream().anyMatch(p->p.contains("member_instances")),"Membership index is actually used");result.put("member_query_plan",plan);
                    List<String> activePlan=new ArrayList<>();
                    try(var r=s.executeQuery("EXPLAIN QUERY PLAN SELECT json FROM instances WHERE completed=0 AND id>'instance-00001000' ORDER BY id LIMIT 64")){while(r.next())activePlan.add(r.getString(4));}
                    require(activePlan.stream().anyMatch(p->p.contains("instance_active_id"))&&activePlan.stream().noneMatch(p->p.contains("TEMP B-TREE")),"Active batch must seek without re-sorting all active graphs");result.put("active_batch_query_plan",activePlan);
                }return null;
            });
            require(((Number)result.get("instances")).intValue()==expected,"Instance count");require(((Number)result.get("instance_members")).intValue()==expected,"Projection count");
        }
        result.put("max_heap_bytes",Runtime.getRuntime().maxMemory());
        result.put("peak_heap_pools",ManagementFactory.getMemoryPoolMXBeans().stream().filter(p->p.getType()==java.lang.management.MemoryType.HEAP).collect(java.util.stream.Collectors.toMap(p->p.getName(),p->p.getPeakUsage().getUsed())));
        result.put("elapsed_millis",(System.nanoTime()-start)/1_000_000L);result.put("database_bytes",Files.size(path));result.put("status","PASS");
        Files.writeString(report,SqliteStore.json(result));System.out.println(SqliteStore.json(result));
    }
    static void require(boolean value,String label){if(!value)throw new IllegalStateException(label);}
}
