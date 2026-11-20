package io.github.addxiaoyi.starchronicle.persistence;

import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.config.*;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition.*;
import io.github.addxiaoyi.starchronicle.core.*;
import io.github.addxiaoyi.starchronicle.narrative.NativeSceneJournal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Literal released V3 DDL, never a current database relabeled to manufacture a legacy fixture. */
class DefinitionSchemaUpgradeTest {
    @TempDir Path directory;
    static final UUID PLAYER=UUID.fromString("00000000-0000-0000-0000-000000000031");
    static final Instant TIME=Instant.parse("2026-11-19T12:34:56.123456789Z");
    static final Map<String,String> ORDER=Map.ofEntries(Map.entry("events","id"),Map.entry("event_nations","event_id,nation"),Map.entry("event_players","event_id,player"),Map.entry("instances","id"),Map.entry("outcomes","id"),Map.entry("facts","owner,key"),Map.entry("story_triggers","id"),Map.entry("story_trigger_receipts","trigger_key,scope"),Map.entry("native_scene_journal","id"),Map.entry("story_choice_log","seq"),Map.entry("event_causes","event_id"),Map.entry("story_trigger_blocks","id"));
    static final List<String> V3_DDL=List.of(
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
        "CREATE INDEX native_scene_player_status ON native_scene_journal(player,status,id)",
        "CREATE TABLE story_choice_log(seq INTEGER PRIMARY KEY AUTOINCREMENT,id TEXT NOT NULL UNIQUE,instance_id TEXT NOT NULL REFERENCES instances(id),source_event_id TEXT NOT NULL REFERENCES events(id),story_id TEXT NOT NULL,story_version TEXT NOT NULL,node_id TEXT NOT NULL,kind TEXT NOT NULL CHECK(kind IN ('BALLOT','RESOLUTION')),actor_key TEXT NOT NULL,player_id TEXT,nation_id TEXT NOT NULL,scope_key TEXT NOT NULL,revision INTEGER NOT NULL CHECK(revision>0),choice_id TEXT NOT NULL,chosen_at TEXT,committed_by TEXT,provenance TEXT NOT NULL CHECK(provenance IN ('LIVE','LEGACY_INSTANCE')),CHECK((kind='BALLOT' AND player_id IS NOT NULL) OR (kind='RESOLUTION' AND player_id IS NULL)),CHECK((provenance='LIVE' AND chosen_at IS NOT NULL AND committed_by IS NOT NULL) OR (provenance='LEGACY_INSTANCE' AND chosen_at IS NULL AND committed_by IS NULL)),UNIQUE(instance_id,node_id,kind,actor_key,revision))",
        "CREATE INDEX story_choice_player ON story_choice_log(player_id,story_id,story_version,node_id,kind,seq DESC)",
        "CREATE INDEX story_choice_scope ON story_choice_log(scope_key,story_id,story_version,node_id,kind,seq DESC)",
        "CREATE TABLE event_causes(event_id TEXT PRIMARY KEY REFERENCES events(id),parent_id TEXT REFERENCES events(id),root_id TEXT NOT NULL REFERENCES events(id),depth INTEGER NOT NULL CHECK(depth>=0),story_path TEXT NOT NULL,CHECK((depth=0 AND parent_id IS NULL) OR (depth>0 AND parent_id IS NOT NULL)))",
        "CREATE INDEX event_causal_root ON event_causes(root_id,depth,event_id)",
        "CREATE TABLE story_trigger_blocks(id TEXT PRIMARY KEY,event_id TEXT NOT NULL REFERENCES events(id),story TEXT NOT NULL,version TEXT NOT NULL,reason TEXT NOT NULL)"
    );
    Path database(){return directory.resolve("literal-v3.db");}
    Connection open()throws Exception {Class.forName("org.sqlite.JDBC");Connection connection=DriverManager.getConnection("jdbc:sqlite:"+database().toAbsolutePath());try(var s=connection.createStatement()){s.execute("PRAGMA foreign_keys=ON");}return connection;}
    StoryDefinition definition(String id,String version,String script) {
        return new StoryDefinition(id,version,"Original archive — retain \"quoted\" text",Scope.WORLD,List.of("ROOT"),null,"vote",0,
            Map.of("vote",new Node("Original vote",script,null,1,Map.of("yes",new Choice("Yes","end",null,null,List.of()),"no",new Choice("No","end",null,null,List.of())),List.of(),false),"end",new Node("End",null,null,1,Map.of(),List.of(),true)));
    }
    StoryDefinition retired(){return definition("retired-a","1",null);}
    StoryDefinition bound(){return definition("retired-a","1","chronicle_last_heir");}
    StoryDefinition pending(){return definition("retired-pending","7",null);}
    StoryInstance completed(){return new StoryInstance("old:completed",retired(),"world","original:root","end",Map.of(PLAYER,"river"),Map.of(),Map.of("vote/"+PLAYER,"yes","vote","yes"),TIME.minusSeconds(30),TIME,true);}
    StoryInstance active(){return new StoryInstance("old:active",bound(),"world","original:root","vote",Map.of(PLAYER,"river"),Map.of(),Map.of(),TIME,TIME.plusSeconds(1),false);}
    String pretty(Object value)throws Exception{return SqliteStore.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value)+"\n";}
    String fingerprint(WorldEvent event) {
        Map<String,Object> canonical=new TreeMap<>();canonical.put("id",event.id());canonical.put("type",event.type());canonical.put("time",event.occurredAt().toString());canonical.put("players",event.participants().stream().map(UUID::toString).sorted().toList());canonical.put("nations",new TreeSet<>(event.nations()));canonical.put("facts",new TreeMap<>(event.facts()));canonical.put("source",event.source());canonical.put("evidence",event.evidence());return RuleEngine.stable(SqliteStore.json(canonical));
    }
    void putInstance(Connection connection,StoryInstance instance)throws Exception {
        try(var s=connection.prepareStatement("INSERT INTO instances VALUES(?,?,?,?,?,?)")){s.setString(1,instance.id());s.setString(2,instance.definition().id());s.setString(3,instance.scopeKey());s.setInt(4,instance.completed()?1:0);s.setString(5,instance.updatedAt().toString());s.setString(6,pretty(instance));s.executeUpdate();}
    }
    void createV3()throws Exception {
        try(var connection=open();var s=connection.createStatement()) {
            connection.setAutoCommit(false);for(String ddl:V3_DDL)s.execute(ddl);
            WorldEvent event=new WorldEvent("original:root","ROOT",TIME,Set.of(PLAYER),Set.of("river"),Map.of("note","unaltered"),"OriginalAuthority","committed receipt=31");
            try(var p=connection.prepareStatement("INSERT INTO events VALUES(?,?,?,?,?)")){p.setString(1,event.id());p.setString(2,event.type());p.setString(3,TIME.toString());p.setString(4,fingerprint(event));p.setString(5,pretty(event));p.executeUpdate();}
            s.execute("INSERT INTO event_nations VALUES('original:root','river')");s.execute("INSERT INTO event_players VALUES('original:root','"+PLAYER+"')");
            putInstance(connection,completed());putInstance(connection,active());
            try(var p=connection.prepareStatement("INSERT INTO story_triggers VALUES(?,?,?,?,?,?)")){p.setString(1,"old:pending");p.setString(2,"old:trigger-key");p.setString(3,event.id());p.setString(4,"world");p.setString(5,pretty(pending()));p.setInt(6,17);p.executeUpdate();}
            s.execute("INSERT INTO story_trigger_receipts VALUES('original-scope-receipt','world')");
            s.execute("INSERT INTO facts VALUES('WORLD','region.old-city','remembered')");s.execute("INSERT INTO facts VALUES('"+PLAYER+"','player.honor','19')");
            for(String status:List.of("DONE","PENDING","REJECTED","UNKNOWN","RUNNING"))try(var p=connection.prepareStatement("INSERT INTO outcomes VALUES(?,?,?)")){p.setString(1,"original:"+status);p.setString(2,status);p.setString(3,pretty(new Delivery("original:"+status,active().id(),"quest.accept","original-quest","",PLAYER,"river",null)));p.executeUpdate();}
            var nativeRequest=new NativeSceneJournal.Request("original-native",active().id(),PLAYER,"river","chronicle_last_heir","a".repeat(64));
            var nativeEntry=new NativeSceneJournal.Entry(nativeRequest,NativeSceneJournal.Status.READY,0,null,TIME,"Original request not yet invoked");
            try(var p=connection.prepareStatement("INSERT INTO native_scene_journal VALUES(?,?,?,?,?)")){p.setString(1,nativeRequest.outcomeId());p.setString(2,PLAYER.toString());p.setString(3,"READY");p.setInt(4,0);p.setString(5,pretty(nativeEntry));p.executeUpdate();}
            s.execute("INSERT INTO event_causes VALUES('original:root',NULL,'original:root',0,'[]')");
            s.execute("INSERT INTO story_trigger_blocks VALUES('original:block','original:root','unavailable','1','original historical block')");
            for(String kind:List.of("BALLOT","RESOLUTION"))try(var p=connection.prepareStatement("INSERT INTO story_choice_log(id,instance_id,source_event_id,story_id,story_version,node_id,kind,actor_key,player_id,nation_id,scope_key,revision,choice_id,chosen_at,committed_by,provenance) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                p.setString(1,RuleEngine.stable("choice-log",completed().id(),"vote",kind,kind.equals("BALLOT")?"player:"+PLAYER:"world","1"));p.setString(2,completed().id());p.setString(3,event.id());p.setString(4,retired().id());p.setString(5,"1");p.setString(6,"vote");p.setString(7,kind);p.setString(8,kind.equals("BALLOT")?"player:"+PLAYER:"world");p.setString(9,kind.equals("BALLOT")?PLAYER.toString():null);p.setString(10,"river");p.setString(11,"world");p.setInt(12,1);p.setString(13,"yes");p.setString(14,TIME.toString());p.setString(15,PLAYER.toString());p.setString(16,"LIVE");p.executeUpdate();
            }
            s.execute("PRAGMA user_version=3");assertTrue(rows(connection,"PRAGMA foreign_key_check").isEmpty());connection.commit();
        }
    }
    static List<List<String>> rows(Connection connection,String query)throws SQLException {
        List<List<String>> result=new ArrayList<>();try(var s=connection.createStatement();var r=s.executeQuery(query)){int width=r.getMetaData().getColumnCount();while(r.next()){List<String> row=new ArrayList<>();for(int i=1;i<=width;i++)row.add(r.getString(i));result.add(row);}}return result;
    }
    static String scalar(Connection connection,String query)throws SQLException {try(var s=connection.createStatement();var r=s.executeQuery(query)){return r.next()?r.getString(1):null;}}
    static Map<String,List<List<String>>> snapshot(Connection connection)throws SQLException {Map<String,List<List<String>>> result=new TreeMap<>();for(var order:ORDER.entrySet())result.put(order.getKey(),rows(connection,"SELECT * FROM "+order.getKey()+" ORDER BY "+order.getValue()));return result;}
    static Map<String,String> schema(Connection connection)throws SQLException {Map<String,String> result=new TreeMap<>();for(var row:rows(connection,"SELECT name,sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY name"))result.put(row.get(0),row.get(1));return result;}
    static Map<String,List<List<String>>> recovered(Map<String,List<List<String>>> source) {
        Map<String,List<List<String>>> result=new TreeMap<>();source.forEach((table,oldRows)->{List<List<String>> copy=new ArrayList<>();for(var old:oldRows){List<String> row=new ArrayList<>(old);if(table.equals("outcomes")&&row.get(1).equals("RUNNING"))row.set(1,"UNKNOWN");copy.add(row);}result.put(table,copy);});return result;
    }
    static void assertPayloadBytes(Map<String,List<List<String>>> expected,Map<String,List<List<String>>> actual) {
        for(var column:Map.of("events",4,"instances",5,"outcomes",2,"story_triggers",4,"native_scene_journal",4).entrySet())for(int i=0;i<expected.get(column.getKey()).size();i++)
            assertArrayEquals(expected.get(column.getKey()).get(i).get(column.getValue()).getBytes(StandardCharsets.UTF_8),actual.get(column.getKey()).get(i).get(column.getValue()).getBytes(StandardCharsets.UTF_8),column.getKey()+" row="+i);
    }

    @Test void productionUpgradeArchivesActualInstanceAndPendingDefinitionsWithoutRewritingV3Data()throws Exception {
        createV3();Map<String,List<List<String>>> old;Map<String,String> oldSchema;
        try(var connection=open()){assertEquals("3",scalar(connection,"PRAGMA user_version"));old=snapshot(connection);oldSchema=schema(connection);}
        List<List<String>> firstArchive;
        try(var store=new SqliteStore(database())) {
            firstArchive=store.transaction(connection->{
                assertEquals(Integer.toString(SqliteStore.SCHEMA_VERSION),scalar(connection,"PRAGMA user_version"));assertEquals(6,SqliteStore.SCHEMA_VERSION);assertEquals(recovered(old),snapshot(connection));assertPayloadBytes(old,snapshot(connection));oldSchema.forEach((name,ddl)->{try{assertEquals(ddl,schema(connection).get(name),name);}catch(SQLException error){throw new IllegalStateException(error);}});
                var catalog=DefinitionCatalog.read(connection);assertEquals(2,catalog.keys().size());assertEquals(3,Integer.parseInt(scalar(connection,"SELECT COUNT(*) FROM story_definitions")));
                assertThrows(IllegalArgumentException.class,()->catalog.require("retired-a","1"));assertEquals(pending(),catalog.require("retired-pending","7"));
                assertEquals(Set.of("LEGACY_INSTANCE old:active","LEGACY_INSTANCE old:completed","PENDING_TRIGGER old:pending"),new HashSet<>(rows(connection,"SELECT provenance FROM story_definitions").stream().map(row->row.getFirst()).toList()));
                assertTrue(rows(connection,"PRAGMA foreign_key_check").isEmpty());return rows(connection,"SELECT * FROM story_definitions ORDER BY story,version,fingerprint");
            });
        }
        try(var store=new SqliteStore(database())) {store.transaction(connection->{assertEquals(firstArchive,rows(connection,"SELECT * FROM story_definitions ORDER BY story,version,fingerprint"));assertEquals(recovered(old),snapshot(connection));assertEquals(0,DefinitionCatalog.migrateSnapshots(connection));return null;});}
    }
    @Test void originTupleQueryUsesTheExpressionIndexAndKeepsOldInstanceJsonBytes()throws Exception {
        createV3();String original;
        try(var connection=open()){original=scalar(connection,"SELECT json FROM instances WHERE id='old:completed'");}
        try(var store=new SqliteStore(database())) {store.transaction(connection->{
            assertEquals("instance_origin",scalar(connection,"SELECT name FROM sqlite_master WHERE type='index' AND name='instance_origin'"));List<String> plan=new ArrayList<>();
            try(var query=connection.prepareStatement("EXPLAIN QUERY PLAN SELECT id FROM instances WHERE story=? AND scope=? AND json_extract(json,'$.definition.version')=? AND json_extract(json,'$.sourceEventId')=?")) {
                query.setString(1,"retired-a");query.setString(2,"world");query.setString(3,"1");query.setString(4,"original:root");try(var result=query.executeQuery()){while(result.next())plan.add(result.getString("detail"));}
            }
            assertTrue(plan.stream().anyMatch(detail->detail.contains("instance_origin")),plan.toString());assertEquals(original,scalar(connection,"SELECT json FROM instances WHERE id='old:completed'"));return null;
        });}
    }
    @Test void malformedLegacyJsonFailsClosedAndRollsBackIndexVersionAndRecoveryChanges()throws Exception {
        createV3();Map<String,String> oldSchema;
        try(var connection=open();var s=connection.createStatement()){s.execute("UPDATE instances SET json='{broken' WHERE id='old:active'");oldSchema=schema(connection);}
        assertThrows(IllegalStateException.class,()->{try(var ignored=new SqliteStore(database())){fail("Malformed legacy JSON must not enter the origin index");}});
        try(var connection=open()) {assertEquals("3",scalar(connection,"PRAGMA user_version"));assertEquals(oldSchema,schema(connection));assertEquals("{broken",scalar(connection,"SELECT json FROM instances WHERE id='old:active'"));assertEquals("RUNNING",scalar(connection,"SELECT status FROM outcomes WHERE id='original:RUNNING'"));assertEquals("0",scalar(connection,"SELECT COUNT(*) FROM sqlite_master WHERE name IN ('instance_origin','story_definitions')"));}
    }
    @Test void invalidLaterSnapshotRollsBackEarlierArchiveImportsAndEveryNewSchemaObjectThenRepairRetriesOnce()throws Exception {
        createV3();String original;Map<String,List<List<String>>> damaged;Map<String,String> oldSchema;
        try(var connection=open()) {
            original=scalar(connection,"SELECT story_json FROM story_triggers WHERE id='old:pending'");var template=pending();
            var invalid=new StoryDefinition(template.id(),template.version(),template.title(),template.scope(),template.triggers(),template.condition(),template.start(),0,Map.of("vote",new Node("Broken",null,null,1,Map.of("yes",new Choice("Missing","missing",null,null,List.of())),List.of(),false)));
            try(var update=connection.prepareStatement("UPDATE story_triggers SET story_json=? WHERE id='old:pending'")){update.setString(1,pretty(invalid));update.executeUpdate();}damaged=snapshot(connection);oldSchema=schema(connection);
        }
        assertThrows(IllegalStateException.class,()->{try(var ignored=new SqliteStore(database())){fail("Invalid pending definition must abort archive migration");}});
        try(var connection=open()) {
            assertEquals("3",scalar(connection,"PRAGMA user_version"));assertEquals(oldSchema,schema(connection));assertEquals(damaged,snapshot(connection));assertEquals("0",scalar(connection,"SELECT COUNT(*) FROM sqlite_master WHERE name IN ('instance_origin','story_definitions')"));
            try(var update=connection.prepareStatement("UPDATE story_triggers SET story_json=? WHERE id='old:pending'")){update.setString(1,original);update.executeUpdate();}
        }
        List<List<String>> archive;
        try(var store=new SqliteStore(database())) {archive=store.transaction(connection->{assertEquals(Integer.toString(SqliteStore.SCHEMA_VERSION),scalar(connection,"PRAGMA user_version"));assertEquals(original,scalar(connection,"SELECT story_json FROM story_triggers WHERE id='old:pending'"));return rows(connection,"SELECT * FROM story_definitions ORDER BY story,version,fingerprint");});}
        try(var store=new SqliteStore(database())) {store.transaction(connection->{assertEquals(archive,rows(connection,"SELECT * FROM story_definitions ORDER BY story,version,fingerprint"));assertEquals(3,archive.size());return null;});}
    }
}
