import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.config.*;
import io.github.addxiaoyi.starchronicle.core.*;
import io.github.addxiaoyi.starchronicle.history.HistoricalFacts;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import static io.github.addxiaoyi.starchronicle.config.StoryDefinition.*;

/** Frozen-JAR history query comparison; real SQLite and RuleEngine, no Minecraft. */
public final class HistoryQueryProbe {
    static final UUID PLAYER=new UUID(0,1), OTHER=new UUID(0,2);
    static final Instant TIME=Instant.parse("2026-11-26T00:00:00Z");
    static final Set<String> KEYS=Set.of("history.RARE","history.MISSING","chronicle.history.world.RARE",
            "chronicle.history.player.RARE","chronicle.history.nation.RARE","chronicle.history.world.MISSING",
            "chronicle.history.player.MISSING","chronicle.history.nation.MISSING");
    public static void main(String[] args)throws Exception {
        Path database=Path.of(args[1]);int count=Integer.parseInt(args[2]);
        if(args[0].equals("seed")){seed(database,count);return;}
        boolean selective=args[0].equals("selected");
        Method reader=HistoricalFacts.class.getMethod(selective?"readRequested":"read",Connection.class,UUID.class,String.class,Collection.class);
        Map<String,Object> report=new LinkedHashMap<>();long opened=System.nanoTime();
        try(var store=new SqliteStore(database)) {
            var engine=new RuleEngine(store,List.of(),Clock.fixed(TIME,ZoneOffset.UTC));
            report.put("open_millis",(System.nanoTime()-opened)/1_000_000L);
            Map<String,String> expected=new TreeMap<>();long world=0,player=0,nation=0;
            for(int n=0;n<count;n++)if(n%499==0){world++;if(n%2==0)player++;if(n%3==0)nation++;}
            expected.put("history.RARE",Long.toString(world));expected.put("chronicle.history.world.RARE",Long.toString(world));
            expected.put("chronicle.history.player.RARE",Long.toString(player));expected.put("chronicle.history.nation.RARE",Long.toString(nation));
            for(String scope:List.of("world","player","nation"))expected.put("chronicle.history."+scope+".MISSING","0");
            var actual=store.transaction(c->read(reader,c,PLAYER,"north",KEYS));require(actual.equals(expected),"Exact scoped counts and missing aliases");report.put("counts",actual);
            var unknown=store.transaction(c->read(reader,c,null," ",KEYS));
            require(unknown.equals(Map.of("history.RARE",Long.toString(world),"chronicle.history.world.RARE",Long.toString(world),"chronicle.history.world.MISSING","0")),"Unknown subjects never become zero");
            report.put("unknown_subject_counts",unknown);
            var context=new PlayerContext(PLAYER,"north",Map.of("player.role","historian"),Set.of());
            var unknownContext=new PlayerContext(PLAYER,"",Map.of("player.role","historian"),Set.of());
            Set<String> expectedIds=new TreeSet<>(),unknownIds=new TreeSet<>();
            for(int n=0;n<24;n++){if(n%6!=4)expectedIds.add(id(n));if(n%6<2)unknownIds.add(id(n));}
            require(pageIds(engine,context).equals(expectedIds),"All conditional pages");
            require(pageIds(engine,unknownContext).equals(unknownIds),"Unknown nation conditional pages");
            report.put("visible_ids",expectedIds);report.put("unknown_nation_visible_ids",unknownIds);
            report.put("first_page_micros",measure(25,()->require(engine.storyPage(context,null,7).instances().size()==7,"First page")));
            report.put("requested_counts_micros",measure(25,()->require(store.transaction(c->read(reader,c,PLAYER,"north",KEYS)).equals(expected),"Counts stable")));
            report.put("ordinary_facts_micros",measure(25,()->require(store.transaction(c->read(reader,c,PLAYER,"north",Set.of("player.role"))).isEmpty(),"No history reference")));
            store.transaction(c->{
                report.put("world_query_plan",plan(c,"SELECT type,COUNT(*) FROM events WHERE type IN ('RARE') GROUP BY type"));
                report.put("player_query_plan",plan(c,"SELECT e.type,COUNT(*) FROM event_players p JOIN events e ON e.id=p.event_id WHERE p.player='"+PLAYER+"' AND e.type IN ('RARE') GROUP BY e.type"));
                if(selective)require(((List<?>)report.get("world_query_plan")).stream().anyMatch(p->p.toString().contains("SEARCH")&&p.toString().contains("history_type")),"World indexed search");
                return null;
            });
        }
        report.put("projection",selective?"requested":"complete-filtered");report.put("events",count);
        report.put("max_heap_bytes",Runtime.getRuntime().maxMemory());report.put("database_bytes",Files.size(database));
        report.put("peak_heap_pools",ManagementFactory.getMemoryPoolMXBeans().stream().filter(p->p.getType()==java.lang.management.MemoryType.HEAP).collect(java.util.stream.Collectors.toMap(p->p.getName(),p->p.getPeakUsage().getUsed())));
        report.put("status","PASS");Files.writeString(Path.of(args[3]),SqliteStore.json(report));System.out.println(SqliteStore.json(report));
    }
    @SuppressWarnings("unchecked") static Map<String,String> read(Method reader,Connection c,UUID player,String nation,Set<String> keys)throws Exception {
        Map<String,String> result=new TreeMap<>((Map<String,String>)reader.invoke(null,c,player,nation,keys));result.keySet().retainAll(keys);return result;
    }
    static Set<String> pageIds(RuleEngine engine,PlayerContext context) {
        Set<String> result=new TreeSet<>();String cursor=null;int pages=0;
        do{var page=engine.storyPage(context,cursor,7);for(var item:page.instances())require(result.add(item.id()),"No duplicate page items");cursor=page.nextCursor();require(++pages<10,"Cursor progress");}while(cursor!=null);return result;
    }
    interface Checked {void run()throws Exception;}
    static Map<String,Long> measure(int repeats,Checked call)throws Exception {
        call.run();call.run();List<Long> values=new ArrayList<>();
        for(int n=0;n<repeats;n++){long start=System.nanoTime();call.run();values.add((System.nanoTime()-start)/1000);}
        Collections.sort(values);return Map.of("samples",(long)repeats,"p50",values.get(repeats/2),"p95",values.get((int)(repeats*.95)),"max",values.getLast());
    }
    static List<String> plan(Connection c,String query)throws SQLException {List<String> result=new ArrayList<>();try(var s=c.createStatement();var r=s.executeQuery("EXPLAIN QUERY PLAN "+query)){while(r.next())result.add(r.getString(4));}return result;}
    static Condition eq(String key,String value){return new Condition(null,null,null,key,"eq",value);}
    static Condition positive(String key){return new Condition(null,null,null,key,"gte","1");}
    static Condition all(Condition... values){return new Condition(List.of(values),null,null,null,null,null);}
    static Condition not(Condition value){return new Condition(null,null,value,null,null,null);}
    static String id(int n){return String.format(Locale.ROOT,"history-story-%02d",n);}
    static void seed(Path database,int count)throws Exception {
        require(!Files.exists(database),"Fresh fixture only");
        List<Condition> conditions=List.of(eq("player.role","historian"),positive("history.RARE"),
                all(positive("chronicle.history.world.RARE"),positive("chronicle.history.player.RARE"),positive("chronicle.history.nation.RARE")),
                all(eq("chronicle.history.world.MISSING","0"),eq("chronicle.history.player.MISSING","0"),eq("chronicle.history.nation.MISSING","0"),not(new Condition(null,null,null,"history.MISSING","exists",null))),
                new Condition(null,null,null,"history.MISSING","ne","0"),not(positive("chronicle.history.nation.MISSING")));
        List<StoryDefinition> definitions=new ArrayList<>();
        for(int n=0;n<conditions.size();n++)definitions.add(new StoryDefinition("history-condition-"+n,"1","History condition "+n,Scope.WORLD,List.of("RARE"),conditions.get(n),"vote",0,
                Map.of("vote",new Node("Historical evidence",null,null,2,Map.of("yes",new Choice("Continue","end",null,null,List.of())),List.of(),false),"end",new Node("Complete",null,null,1,Map.of(),List.of(),true))));
        try(var store=new SqliteStore(database)) {
            store.transaction(c->{
                DefinitionCatalog.archive(c,definitions,"HISTORY_QUERY_FIXTURE");
                try(var event=c.prepareStatement("INSERT INTO events VALUES(?,?,?,?,?)");var actor=c.prepareStatement("INSERT INTO event_players VALUES(?,?)");var nation=c.prepareStatement("INSERT INTO event_nations VALUES(?,?)")) {
                    for(int n=0;n<count;n++) {
                        String source="event-"+n,type=n%499==0?"RARE":"NOISE."+(n%601),country=n%3==0?"north":"south";UUID player=n%2==0?PLAYER:OTHER;
                        var record=new WorldEvent(source,type,TIME.plusSeconds(n),Set.of(player),Set.of(country),Map.of(),"HistoryQueryFixture","Immutable source "+n);String json=SqliteStore.json(record);
                        event.setString(1,source);event.setString(2,type);event.setString(3,record.occurredAt().toString());event.setString(4,DefinitionCanonicalCodec.sha256(json));event.setString(5,json);event.executeUpdate();
                        actor.setString(1,source);actor.setString(2,player.toString());actor.executeUpdate();nation.setString(1,source);nation.setString(2,country);nation.executeUpdate();
                    }
                }
                try(var insert=c.prepareStatement("INSERT INTO instances VALUES(?,?,?,?,?,?)")) {
                    for(int n=0;n<24;n++) {
                        var definition=definitions.get(n%6);var instance=new StoryInstance(id(n),definition,"world","event-0","vote",Map.of(OTHER,"south"),Map.of(),Map.of(),TIME,TIME,false);
                        insert.setString(1,id(n));insert.setString(2,definition.id());insert.setString(3,"world");insert.setInt(4,0);insert.setString(5,TIME.toString());insert.setString(6,SqliteStore.json(instance));insert.executeUpdate();
                    }
                }return null;
            });
        }
        System.out.println("SEEDED events="+count+" instances=24");
    }
    static void require(boolean value,String label){if(!value)throw new IllegalStateException(label);}
}
