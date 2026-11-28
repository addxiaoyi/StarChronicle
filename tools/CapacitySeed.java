import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.config.*;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition.*;
import io.github.addxiaoyi.starchronicle.core.*;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Compile/run against the frozen schema-7 JAR. V2 uses literal released DDL; no relabeling. */
public final class CapacitySeed {
    static final UUID PLAYER=new UUID(0,1);
    static final Instant TIME=Instant.parse("2026-11-25T12:00:00Z");
    public static void main(String[] args)throws Exception {
        if(SqliteStore.SCHEMA_VERSION!=7)throw new IllegalStateException("Seed requires the frozen schema-7 artifact");
        Path path=Path.of(args[0]);int version=Integer.parseInt(args[1]),count=Integer.parseInt(args[2]);
        if(Files.exists(path))throw new IllegalStateException("Refuse to replace an existing fixture");
        Class.forName("org.sqlite.JDBC");
        if(version==7)try(var store=new SqliteStore(path)){store.transaction(c->{seed(c,count,false);return null;});}
        else if(version==2)try(var c=DriverManager.getConnection("jdbc:sqlite:"+path.toAbsolutePath());var s=c.createStatement()) {
            c.setAutoCommit(false);
            for(String ddl:List.of(
                    "CREATE TABLE events(id TEXT PRIMARY KEY,type TEXT NOT NULL,time TEXT NOT NULL,hash TEXT NOT NULL,json TEXT NOT NULL)",
                    "CREATE TABLE event_nations(event_id TEXT REFERENCES events(id),nation TEXT NOT NULL,PRIMARY KEY(event_id,nation))",
                    "CREATE TABLE event_players(event_id TEXT REFERENCES events(id),player TEXT NOT NULL,PRIMARY KEY(event_id,player))",
                    "CREATE INDEX history_time ON events(time DESC,id)","CREATE INDEX nation_history ON event_nations(nation,event_id)","CREATE INDEX player_history ON event_players(player,event_id)",
                    "CREATE TABLE instances(id TEXT PRIMARY KEY,story TEXT NOT NULL,scope TEXT NOT NULL,completed INTEGER NOT NULL,updated TEXT NOT NULL,json TEXT NOT NULL)",
                    "CREATE INDEX active_instances ON instances(story,scope,completed)",
                    "CREATE TABLE outcomes(id TEXT PRIMARY KEY,status TEXT NOT NULL,json TEXT NOT NULL)","CREATE INDEX outcome_delivery_status ON outcomes(status,id)",
                    "CREATE TABLE facts(owner TEXT NOT NULL,key TEXT NOT NULL,value TEXT NOT NULL,PRIMARY KEY(owner,key))",
                    "CREATE TABLE story_triggers(id TEXT PRIMARY KEY,trigger_key TEXT NOT NULL,event_id TEXT NOT NULL REFERENCES events(id),scope TEXT NOT NULL,story_json TEXT NOT NULL,checked INTEGER NOT NULL DEFAULT 0)",
                    "CREATE INDEX story_trigger_scope ON story_triggers(scope,checked,id)","CREATE INDEX story_trigger_event ON story_triggers(event_id,id)",
                    "CREATE TABLE story_trigger_receipts(trigger_key TEXT NOT NULL,scope TEXT NOT NULL,PRIMARY KEY(trigger_key,scope))",
                    "CREATE TABLE native_scene_journal(id TEXT PRIMARY KEY,player TEXT NOT NULL,status TEXT NOT NULL CHECK(status IN ('READY','RUNNING','UNKNOWN','COMPLETED')),revision INTEGER NOT NULL,json TEXT NOT NULL)",
                    "CREATE INDEX native_scene_player_status ON native_scene_journal(player,status,id)"))s.execute(ddl);
            seed(c,count,true);s.execute("PRAGMA user_version=2");c.commit();
        }else throw new IllegalArgumentException("Version must be 2 or 7");
        System.out.println("SEEDED schema="+version+" instances="+count+" file="+path.getFileName());
    }
    static StoryDefinition definition(Scope scope) {
        String text="Historical narrative fixture. ".repeat(120);
        return new StoryDefinition("capacity-"+scope.name().toLowerCase(Locale.ROOT),"1","Capacity fixture",scope,List.of("CAPACITY"),null,"vote",0,
                Map.of("vote",new Node(text,null,null,1,Map.of("yes",new Choice("Yes","end",null,"capacity.choice",List.of())),List.of(),false),
                        "end",new Node(text,null,null,1,Map.of(),List.of(),true)));
    }
    static void seed(Connection c,int count,boolean legacy)throws Exception {
        var personal=definition(Scope.PLAYER);var world=definition(Scope.WORLD);
        if(!legacy)DefinitionCatalog.archive(c,List.of(personal,world),"CAPACITY_FIXTURE");
        try(var event=c.prepareStatement("INSERT INTO events VALUES(?,?,?,?,?)");var player=c.prepareStatement("INSERT INTO event_players VALUES(?,?)");
            var insert=c.prepareStatement("INSERT INTO instances VALUES(?,?,?,?,?,?)");var update=c.prepareStatement("UPDATE instances SET completed=?,json=? WHERE id=?");
            var trigger=c.prepareStatement("INSERT INTO story_triggers VALUES(?,?,?,?,?,0)");var outcome=c.prepareStatement("INSERT INTO outcomes VALUES(?,?,?)")) {
            for(int n=0;n<count;n++) {
                UUID actor=n%1000==0?PLAYER:new UUID(0,10L+n);String id=String.format(Locale.ROOT,"instance-%08d",n),source="event-"+id;
                var definition=n%100==0?world:personal;String scope=definition.scope()==Scope.WORLD?"world":"player:"+actor;
                Instant updated=TIME.plusSeconds(n%37).plusNanos(n%3==0?0:n%3==1?1:1001);boolean complete=n%4!=0;
                var evidence=new WorldEvent(source,"CAPACITY",updated,Set.of(actor),Set.of(),Map.of(),"CapacityFixture","Seeded immutable source "+n);
                event.setString(1,source);event.setString(2,evidence.type());event.setString(3,updated.toString());event.setString(4,DefinitionCanonicalCodec.sha256(SqliteStore.json(evidence)));event.setString(5,SqliteStore.json(evidence));event.executeUpdate();
                player.setString(1,source);player.setString(2,actor.toString());player.executeUpdate();
                var initial=new StoryInstance(id,definition,scope,source,"vote",Map.of(actor,""),Map.of(),Map.of(),TIME,updated,false);
                var choices=complete?Map.of("vote/"+actor,"yes","vote","yes"):Map.of("vote/"+actor,"yes");
                var saved=new StoryInstance(id,definition,scope,source,complete?"end":"vote",initial.members(),complete?Map.of():Map.of(actor,"yes"),choices,TIME,updated,complete);
                insert.setString(1,id);insert.setString(2,definition.id());insert.setString(3,scope);insert.setInt(4,legacy&&complete?1:0);insert.setString(5,updated.toString());insert.setString(6,payload(legacy?saved:initial,legacy));insert.executeUpdate();
                if(!legacy) {
                    var context=new PlayerContext(actor,"",Map.of(),Set.of("capacity.choice"));
                    ChoiceLedger.recordBallot(c,initial,context,"vote","yes",updated);
                    if(complete)ChoiceLedger.recordResolution(c,initial,context,"vote","yes",updated);
                    update.setInt(1,complete?1:0);update.setString(2,payload(saved,false));update.setString(3,id);update.executeUpdate();
                }
                if(n%3==0){trigger.setString(1,"trigger-"+id);trigger.setString(2,"key-"+id);trigger.setString(3,source);trigger.setString(4,"players:*");trigger.setString(5,legacy?DefinitionCanonicalCodec.encodeV1(personal):SqliteStore.json(personal));trigger.executeUpdate();}
                if(complete){var receipt=new Delivery("outcome-"+id,id,"authority","fixture",source,actor,"",null);outcome.setString(1,receipt.outcomeId());outcome.setString(2,"DONE");outcome.setString(3,SqliteStore.json(receipt));outcome.executeUpdate();}
            }
        }
    }
    static String payload(StoryInstance instance,boolean legacy){String json=SqliteStore.json(instance);return legacy?json.replace(",\"voteUnit\":\"PLAYER\"","").replace("\"voteUnit\":\"PLAYER\",",""):json;}
}
