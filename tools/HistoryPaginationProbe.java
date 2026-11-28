import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.core.*;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;

/** Compare offset and seek reads on the same frozen evidence, without a Minecraft runtime. */
public final class HistoryPaginationProbe {
    static final UUID PLAYER=new UUID(0,1),OTHER=new UUID(0,2);
    static final Instant TIME=Instant.parse("2026-11-26T00:00:00Z");
    static final int SIZE=45;
    record Scope(String name,String nation,UUID player) {}
    record Page(List<WorldEvent> events,String cursor) {}
    static final List<Scope> SCOPES=List.of(new Scope("world",null,null),new Scope("nation","north",null),new Scope("player",null,PLAYER),new Scope("intersection","north",PLAYER));
    public static void main(String[] args)throws Exception {
        Path database=Path.of(args[1]);int count=Integer.parseInt(args[2]);
        if(args[0].equals("seed")){seed(database,count);return;}
        boolean selected=args[0].equals("selected");
        Method reader=RuleEngine.class.getMethod(selected?"historyPage":"history",String.class,UUID.class,selected?String.class:int.class,int.class);
        Map<String,Object> report=new LinkedHashMap<>(),scopes=new LinkedHashMap<>();long start=System.nanoTime();
        try(var store=new SqliteStore(database)) {
            var engine=new RuleEngine(store,List.of(),Clock.fixed(TIME,ZoneOffset.UTC));report.put("open_millis",(System.nanoTime()-start)/1_000_000L);
            for(var scope:SCOPES) {
                int matches=store.transaction(c->{try(var s=c.prepareStatement("SELECT COUNT(*) FROM events e WHERE 1=1"+filter(scope))){bind(s,scope);try(var r=s.executeQuery()){require(r.next(),"Count row");return r.getInt(1);}}});
                int page=(matches*3/4)/SIZE;
                var expected=store.transaction(c->expected(c,scope,page*SIZE,SIZE));
                var following=store.transaction(c->expected(c,scope,(page+1)*SIZE,SIZE));
                String anchor=store.transaction(c->expected(c,scope,page*SIZE-1,1).getFirst().id());
                var actual=read(reader,selected,engine,scope,page,anchor);
                require(actual.events().equals(expected),"Exact deep page "+scope.name());
                var next=read(reader,selected,engine,scope,page+1,actual.cursor());require(next.events().equals(following),"Exact following page "+scope.name());
                Map<String,Object> result=new LinkedHashMap<>();result.put("matching_events",matches);result.put("page_number_zero_based",page);result.put("before_event_id",anchor);
                result.put("events",actual.events().stream().map(WorldEvent::id).toList());result.put("following_events",next.events().stream().map(WorldEvent::id).toList());
                result.put("deep_page_micros",measure(25,()->require(read(reader,selected,engine,scope,page,anchor).events().equals(expected),"Stable deep page")));
                var plan=store.transaction(c->plan(c,scope,anchor,selected,page));result.put("query_plan",plan);
                if(selected&&scope.name().equals("world"))require(plan.stream().anyMatch(line->line.contains("SEARCH")&&line.contains("history_cursor"))&&plan.stream().noneMatch(line->line.contains("TEMP B-TREE")),"Index seek without sort");
                scopes.put(scope.name(),result);
            }
        }
        report.put("mode",selected?"cursor":"offset");report.put("scopes",scopes);report.put("events",count);report.put("max_heap_bytes",Runtime.getRuntime().maxMemory());
        report.put("database_bytes",Files.size(database));report.put("status","PASS");Files.writeString(Path.of(args[3]),SqliteStore.json(report));System.out.println(SqliteStore.json(report));
    }
    @SuppressWarnings("unchecked") static Page read(Method reader,boolean selected,RuleEngine engine,Scope scope,int page,String anchor)throws Exception {
        Object value=reader.invoke(engine,scope.nation(),scope.player(),selected?anchor:page,SIZE);
        if(selected)return new Page((List<WorldEvent>)value.getClass().getMethod("events").invoke(value),(String)value.getClass().getMethod("nextCursor").invoke(value));
        var events=(List<WorldEvent>)value;return new Page(events,events.size()==SIZE?events.getLast().id():null);
    }
    static String filter(Scope scope){return (scope.nation()==null?"":" AND EXISTS(SELECT 1 FROM event_nations n WHERE n.event_id=e.id AND n.nation=?)")+(scope.player()==null?"":" AND EXISTS(SELECT 1 FROM event_players p WHERE p.event_id=e.id AND p.player=?)");}
    static int bind(PreparedStatement s,Scope scope)throws SQLException{int p=1;if(scope.nation()!=null)s.setString(p++,scope.nation());if(scope.player()!=null)s.setString(p++,scope.player().toString());return p;}
    static List<WorldEvent> expected(Connection c,Scope scope,int offset,int size)throws SQLException {
        List<WorldEvent> events=new ArrayList<>();try(var s=c.prepareStatement("SELECT e.json FROM events e WHERE 1=1"+filter(scope)+" ORDER BY e.time DESC,e.id DESC LIMIT ? OFFSET ?")) {
            int p=bind(s,scope);s.setInt(p++,size);s.setInt(p,offset);try(var r=s.executeQuery()){while(r.next())events.add(SqliteStore.decode(r.getString(1),WorldEvent.class));}
        }return events;
    }
    static List<String> plan(Connection c,Scope scope,String anchor,boolean selected,int page)throws SQLException {
        String time;try(var s=c.prepareStatement("SELECT time FROM events WHERE id=?")){s.setString(1,anchor);try(var r=s.executeQuery()){require(r.next(),"Existing anchor");time=r.getString(1);}}
        var lines=new ArrayList<String>();try(var s=c.prepareStatement("EXPLAIN QUERY PLAN SELECT e.json FROM events e WHERE 1=1"+filter(scope)+(selected?" AND (e.time,e.id)<(?,?)":"")+" ORDER BY e.time DESC,e.id DESC LIMIT ?"+(selected?"":" OFFSET ?"))) {
            int p=bind(s,scope);if(selected){s.setString(p++,time);s.setString(p++,anchor);}s.setInt(p++,selected?SIZE+1:SIZE);if(!selected)s.setInt(p,page*SIZE);
            try(var r=s.executeQuery()){while(r.next())lines.add(r.getString(4));}
        }return lines;
    }
    interface Checked{void run()throws Exception;}
    static Map<String,Long> measure(int repeats,Checked call)throws Exception {
        call.run();call.run();List<Long> values=new ArrayList<>();for(int n=0;n<repeats;n++){long start=System.nanoTime();call.run();values.add((System.nanoTime()-start)/1000);}
        Collections.sort(values);return Map.of("samples",(long)repeats,"p50",values.get(repeats/2),"p95",values.get((int)(repeats*.95)),"max",values.getLast());
    }
    static void seed(Path database,int count)throws Exception {
        require(!Files.exists(database),"Fresh fixture only");var format=new DateTimeFormatterBuilder().appendInstant(9).toFormatter();
        try(var store=new SqliteStore(database)) {
            store.transaction(c->{try(var event=c.prepareStatement("INSERT INTO events VALUES(?,?,?,?,?)");var actor=c.prepareStatement("INSERT INTO event_players VALUES(?,?)");var nation=c.prepareStatement("INSERT INTO event_nations VALUES(?,?)")) {
                for(int n=0;n<count;n++) {
                    String id=String.format(Locale.ROOT,"e%06d",n),country=n%3==0?"north":"south";UUID player=n%2==0?PLAYER:OTHER;
                    var record=new WorldEvent(id,"COMMITTED",TIME.plusSeconds(n/8),Set.of(player),Set.of(country),Map.of(),"PaginationFixture","Immutable evidence "+n);String json=SqliteStore.json(record);
                    event.setString(1,id);event.setString(2,record.type());event.setString(3,format.format(record.occurredAt()));event.setString(4,DefinitionCanonicalCodec.sha256(json));event.setString(5,json);event.executeUpdate();
                    actor.setString(1,id);actor.setString(2,player.toString());actor.executeUpdate();nation.setString(1,id);nation.setString(2,country);nation.executeUpdate();
                }
            }return null;});
        }
        System.out.println("SEEDED events="+count+" tied_times=8 events_per_time");
    }
    static void require(boolean value,String label){if(!value)throw new IllegalStateException(label);}
}
