import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.config.*;
import io.github.addxiaoyi.starchronicle.core.*;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static io.github.addxiaoyi.starchronicle.config.StoryDefinition.*;

/** Frozen-JAR directory capacity check using real canonical evidence and SQLite; no server. */
public final class DefinitionCapacityProbe {
    static final UUID PLAYER=new UUID(0,1);
    static final Instant TIME=Instant.parse("2026-11-26T00:00:00Z");
    static final Clock CLOCK=Clock.fixed(TIME,ZoneOffset.UTC);
    static final PlayerContext CONTEXT=new PlayerContext(PLAYER,"",Map.of("player.ready","true"),Set.of());
    static String version(int n){return String.format(Locale.ROOT,"v%06d",n);}
    static Condition past(int n){return new Condition(null,null,null,"past.player.choice.retired/"+version(n)+"/node0","eq","yes");}
    static StoryDefinition current(int count) {
        var condition=new Condition(null,List.of(new Condition(null,null,null,"player.ready","eq","true"),past(0),past(count-1)),null,null,null,null);
        return new StoryDefinition("enabled","1","Current story",Scope.PLAYER,List.of("ENABLED"),condition,"vote",0,
                Map.of("vote",new Node("Current vote",null,null,1,Map.of("yes",new Choice("Yes","end",null,null,List.of())),List.of(),false),
                        "end",new Node("Complete",null,null,1,Map.of(),List.of(),true)));
    }
    static StoryDefinition retired(int n) {
        Map<String,Node> nodes=new LinkedHashMap<>();
        for(int k=0;k<4;k++) {
            String prefix="Version "+n+" node "+k+" ";String text=prefix+"x".repeat(4096-prefix.length());
            nodes.put("node"+k,new Node(text,null,null,1,k==3?Map.of():Map.of("yes",new Choice("Continue","node"+(k+1),null,null,List.of())),List.of(),k==3));
        }
        return new StoryDefinition("retired",version(n),"Historical version "+n,Scope.PLAYER,List.of("RETIRED"),null,"node0",0,nodes);
    }
    public static void main(String[] args)throws Exception {
        Path database=Path.of(args[1]);int count=Integer.parseInt(args[2]);
        if(args[0].equals("seed")){seed(database,count);return;}
        Map<String,Object> report=new LinkedHashMap<>();long started=System.nanoTime();
        try(var store=new SqliteStore(database)) {
            report.put("store_open_millis",millis(started));
            var definitions=List.of(current(count));started=System.nanoTime();
            var engine=new RuleEngine(store,definitions,CLOCK);report.put("engine_load_millis",millis(started));
            started=System.nanoTime();engine.validate(definitions);report.put("validate_millis",millis(started));
            started=System.nanoTime();engine.reload(definitions);report.put("reload_millis",millis(started));
            var page=engine.storyPage(CONTEXT,null,10);
            require(page.instances().size()==1&&page.nextCursor()==null,"Exactly one existing active story");
            var saved=engine.instance(PLAYER,page.instances().getFirst().id()).orElseThrow();
            require(saved.definition().equals(current(count))&&!saved.completed(),"Pinned current definition preserved");
            report.put("visible_story_ids",page.instances().stream().map(i->i.definition().id()).toList());
            if(args[0].equals("selected")) {
                var reader=DefinitionCatalog.class.getMethod("readReferenced",java.sql.Connection.class,Collection.class);
                var directory=store.transaction(c->(DefinitionCatalog)reader.invoke(null,c,definitions));
                require(directory.keys().equals(Set.of(new DefinitionCatalog.Key("enabled","1"),new DefinitionCatalog.Key("retired",version(0)),new DefinitionCatalog.Key("retired",version(count-1)))),"Only current and exact directly referenced versions retained");
                require(directory.require("retired",version(0)).equals(retired(0)),"V1 reference resolves unchanged");
                require(directory.require("retired",version(count-1)).equals(retired(count-1)),"V2 reference resolves unchanged");
                report.put("selected_keys",directory.keys().stream().map(Object::toString).toList());
                report.put("selected_evidence_rows",directory.evidenceEntries().size());
            }
            System.gc();report.put("heap_used_after_gc_bytes",ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
            // Keep the engine alive through the heap measurement; do not measure an already-dead runtime.
            require(engine.storyPage(CONTEXT,null,10).instances().size()==1,"Runtime remains usable");
        }
        report.put("historical_versions",count);report.put("max_heap_bytes",Runtime.getRuntime().maxMemory());
        report.put("peak_heap_pools",ManagementFactory.getMemoryPoolMXBeans().stream().filter(p->p.getType()==java.lang.management.MemoryType.HEAP).collect(java.util.stream.Collectors.toMap(p->p.getName(),p->p.getPeakUsage().getUsed())));
        report.put("database_bytes",Files.size(database));report.put("status","PASS");
        Files.writeString(Path.of(args[3]),SqliteStore.json(report));System.out.println(SqliteStore.json(report));
    }
    static long millis(long start){return (System.nanoTime()-start)/1_000_000L;}
    static void seed(Path database,int count)throws Exception {
        require(!Files.exists(database),"Fresh fixture only");
        try(var store=new SqliteStore(database)) {
            store.transaction(c->{
                try(var raw=c.prepareStatement("INSERT INTO story_definitions(story,version,fingerprint,json,provenance,first_seen) VALUES(?,?,?,?,?,?)");
                    var format=c.prepareStatement("INSERT INTO story_definition_formats VALUES(?,?,?,?)")) {
                    for(int n=0;n<count;n++) {
                        var story=retired(n);boolean legacy=n%5==0;
                        String payload=legacy?DefinitionCanonicalCodec.encodeV1(story):DefinitionCanonicalCodec.encodeV2(story);
                        String hash=DefinitionCanonicalCodec.sha256(payload);
                        raw.setString(1,story.id());raw.setString(2,story.version());raw.setString(3,hash);raw.setString(4,payload);raw.setString(5,"CAPACITY_FIXTURE");raw.setString(6,TIME.plusSeconds(n).toString());raw.executeUpdate();
                        format.setString(1,story.id());format.setString(2,story.version());format.setString(3,hash);format.setInt(4,legacy?1:2);format.executeUpdate();
                    }
                }
                DefinitionCatalog.migrateCanonicalV2(c);
                // Seed the current definition directly: running the old engine here would itself
                // reproduce its capacity limit before we can freeze the shared baseline database.
                var story=current(count);String payload=DefinitionCanonicalCodec.encodeV2(story),hash=DefinitionCanonicalCodec.sha256(payload);
                try(var raw=c.prepareStatement("INSERT INTO story_definitions(story,version,fingerprint,json,provenance,first_seen) VALUES(?,?,?,?,?,?)");
                    var format=c.prepareStatement("INSERT INTO story_definition_formats VALUES(?,?,?,2)")) {
                    raw.setString(1,story.id());raw.setString(2,story.version());raw.setString(3,hash);raw.setString(4,payload);raw.setString(5,"ACCEPTED_CONFIGURATION");raw.setString(6,TIME.toString());raw.executeUpdate();
                    format.setString(1,story.id());format.setString(2,story.version());format.setString(3,hash);format.executeUpdate();
                }
                var event=new WorldEvent("source","ENABLED",TIME,Set.of(PLAYER),Set.of(),Map.of(),"DefinitionCapacityFixture","Pinned fixture");String json=SqliteStore.json(event);
                try(var eventInsert=c.prepareStatement("INSERT INTO events VALUES(?,?,?,?,?)");var actor=c.prepareStatement("INSERT INTO event_players VALUES(?,?)")) {
                    eventInsert.setString(1,event.id());eventInsert.setString(2,event.type());eventInsert.setString(3,TIME.toString());eventInsert.setString(4,DefinitionCanonicalCodec.sha256(json));eventInsert.setString(5,json);eventInsert.executeUpdate();
                    actor.setString(1,event.id());actor.setString(2,PLAYER.toString());actor.executeUpdate();
                }
                var instance=new StoryInstance("active",story,"player:"+PLAYER,event.id(),"vote",Map.of(PLAYER,""),Map.of(),Map.of(),TIME,TIME,false);
                try(var insert=c.prepareStatement("INSERT INTO instances VALUES(?,?,?,?,?,?)")) {
                    insert.setString(1,instance.id());insert.setString(2,story.id());insert.setString(3,instance.scopeKey());insert.setInt(4,0);insert.setString(5,TIME.toString());insert.setString(6,SqliteStore.json(instance));insert.executeUpdate();
                }
                return null;
            });
        }
        System.out.println("SEEDED historical_versions="+count+" nodes_each=4 text_chars_each=4096");
    }
    static void require(boolean value,String label){if(!value)throw new IllegalStateException(label);}
}
