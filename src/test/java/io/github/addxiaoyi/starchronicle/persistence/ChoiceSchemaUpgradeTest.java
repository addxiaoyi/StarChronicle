package io.github.addxiaoyi.starchronicle.persistence;

import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition.*;
import io.github.addxiaoyi.starchronicle.core.ChoiceLedger;
import io.github.addxiaoyi.starchronicle.core.RuleEngine;
import io.github.addxiaoyi.starchronicle.core.StoryInstance;
import io.github.addxiaoyi.starchronicle.narrative.NativeSceneJournal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Starts with literal V2 DDL, then exercises the actual SqliteStore constructor upgrade.
 * No current store is created or relabeled to manufacture an old database. */
class ChoiceSchemaUpgradeTest {
    @TempDir Path directory;
    private static final UUID A=UUID.fromString("00000000-0000-0000-0000-000000000001"),
            B=UUID.fromString("00000000-0000-0000-0000-000000000002"),
            C=UUID.fromString("00000000-0000-0000-0000-000000000003"),
            D=UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final Instant TIME=Instant.parse("2026-11-19T10:11:12.123456789Z");
    private static final String PERSONAL="past.player.choice.legacy-heir/1/council",WORLD="past.resolution.world.legacy-heir/1/council",
            NATIONAL="past.resolution.nation.legacy-heir/1/council";
    private static final Map<String,String> TABLE_ORDER=Map.of(
            "events","id","event_nations","event_id,nation","event_players","event_id,player",
            "instances","id","outcomes","id","facts","owner,key","story_triggers","id",
            "story_trigger_receipts","trigger_key,scope","native_scene_journal","id");
    /** Copied from the released SCHEMA_VERSION=2 SqliteStore, including all original indexes. */
    static final List<String> V2_DDL=List.of(
            "CREATE TABLE IF NOT EXISTS events(id TEXT PRIMARY KEY,type TEXT NOT NULL,time TEXT NOT NULL,hash TEXT NOT NULL,json TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS event_nations(event_id TEXT REFERENCES events(id),nation TEXT NOT NULL,PRIMARY KEY(event_id,nation))",
            "CREATE TABLE IF NOT EXISTS event_players(event_id TEXT REFERENCES events(id),player TEXT NOT NULL,PRIMARY KEY(event_id,player))",
            "CREATE INDEX IF NOT EXISTS history_time ON events(time DESC,id)",
            "CREATE INDEX IF NOT EXISTS nation_history ON event_nations(nation,event_id)",
            "CREATE INDEX IF NOT EXISTS player_history ON event_players(player,event_id)",
            "CREATE TABLE IF NOT EXISTS instances(id TEXT PRIMARY KEY,story TEXT NOT NULL,scope TEXT NOT NULL,completed INTEGER NOT NULL,updated TEXT NOT NULL,json TEXT NOT NULL)",
            "CREATE INDEX IF NOT EXISTS active_instances ON instances(story,scope,completed)",
            "CREATE TABLE IF NOT EXISTS outcomes(id TEXT PRIMARY KEY,status TEXT NOT NULL,json TEXT NOT NULL)",
            "CREATE INDEX IF NOT EXISTS outcome_delivery_status ON outcomes(status,id)",
            "CREATE TABLE IF NOT EXISTS facts(owner TEXT NOT NULL,key TEXT NOT NULL,value TEXT NOT NULL,PRIMARY KEY(owner,key))",
            "CREATE TABLE IF NOT EXISTS story_triggers(id TEXT PRIMARY KEY,trigger_key TEXT NOT NULL,event_id TEXT NOT NULL REFERENCES events(id),scope TEXT NOT NULL,story_json TEXT NOT NULL,checked INTEGER NOT NULL DEFAULT 0)",
            "CREATE INDEX IF NOT EXISTS story_trigger_scope ON story_triggers(scope,checked,id)",
            "CREATE INDEX IF NOT EXISTS story_trigger_event ON story_triggers(event_id,id)",
            "CREATE TABLE IF NOT EXISTS story_trigger_receipts(trigger_key TEXT NOT NULL,scope TEXT NOT NULL,PRIMARY KEY(trigger_key,scope))",
            "CREATE TABLE IF NOT EXISTS native_scene_journal(id TEXT PRIMARY KEY,player TEXT NOT NULL,status TEXT NOT NULL CHECK(status IN ('READY','RUNNING','UNKNOWN','COMPLETED')),revision INTEGER NOT NULL,json TEXT NOT NULL)",
            "CREATE INDEX IF NOT EXISTS native_scene_player_status ON native_scene_journal(player,status,id)");
    private Path database(){return directory.resolve("literal-v2.db");}
    private Connection open()throws Exception{Class.forName("org.sqlite.JDBC");Connection c=DriverManager.getConnection("jdbc:sqlite:"+database().toAbsolutePath());try(var s=c.createStatement()){s.execute("PRAGMA foreign_keys=ON");}return c;}
    private static StoryDefinition definition(){
        var choices=Map.of("support",new Choice("Support","end",null,null,List.of()),"oppose",new Choice("Oppose","end",null,null,List.of()));
        return new StoryDefinition("legacy-heir","1","古都议会 — exact legacy text",Scope.WORLD,List.of("LEGACY_WORLD_EVENT"),null,"council",0,
                Map.of("council",new Node("Council",null,null,2,choices,List.of(),false),"end",new Node("Complete",null,null,1,Map.of(),List.of(),true)));
    }
    private static StoryInstance completed(){return new StoryInstance("legacy:completed",definition(),"world","legacy:world-source","end",Map.of(A,"river",B,"hill",C,"river",D,"hill"),Map.of(),Map.of("council","support","council/"+A,"support","council/"+B,"oppose","council/"+C,"support"),TIME.minusSeconds(60),TIME,true);}
    private static StoryInstance active(){return new StoryInstance("legacy:active",definition(),"world","legacy:active-source","council",Map.of(A,"river",B,"hill",C,"river",D,"hill"),Map.of(A,"oppose"),Map.of("council/"+A,"oppose"),TIME.plusSeconds(1),TIME.plusSeconds(2),false);}
    private static String pretty(Object value)throws Exception{return SqliteStore.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value)+"\n";}
    private static String fingerprint(WorldEvent event){
        Map<String,Object> canonical=new TreeMap<>();canonical.put("id",event.id());canonical.put("type",event.type());canonical.put("time",event.occurredAt().toString());canonical.put("players",event.participants().stream().map(UUID::toString).sorted().toList());canonical.put("nations",new TreeSet<>(event.nations()));canonical.put("facts",new TreeMap<>(event.facts()));canonical.put("source",event.source());canonical.put("evidence",event.evidence());return RuleEngine.stable(SqliteStore.json(canonical));
    }
    private static void insertEvent(Connection c,WorldEvent event)throws Exception{
        try(var s=c.prepareStatement("INSERT INTO events VALUES(?,?,?,?,?)")){s.setString(1,event.id());s.setString(2,event.type());s.setString(3,event.occurredAt().toString());s.setString(4,fingerprint(event));s.setString(5,pretty(event));s.executeUpdate();}
        try(var s=c.prepareStatement("INSERT INTO event_nations VALUES(?,?)")){for(String nation:event.nations()){s.setString(1,event.id());s.setString(2,nation);s.executeUpdate();}}
        try(var s=c.prepareStatement("INSERT INTO event_players VALUES(?,?)")){for(UUID player:event.participants()){s.setString(1,event.id());s.setString(2,player.toString());s.executeUpdate();}}
    }
    private static void insertInstance(Connection c,StoryInstance instance,String json)throws SQLException{
        try(var s=c.prepareStatement("INSERT INTO instances VALUES(?,?,?,?,?,?)")){s.setString(1,instance.id());s.setString(2,instance.definition().id());s.setString(3,instance.scopeKey());s.setInt(4,instance.completed()?1:0);s.setString(5,instance.updatedAt().toString());s.setString(6,json);s.executeUpdate();}
    }
    private void createLiteralV2()throws Exception{
        try(var c=open();var s=c.createStatement()){
            s.execute("PRAGMA journal_mode=WAL");s.execute("PRAGMA synchronous=FULL");c.setAutoCommit(false);
            for(String ddl:V2_DDL)s.execute(ddl);
            insertEvent(c,new WorldEvent("legacy:world-source","LEGACY_WORLD_EVENT",TIME,Set.of(A,B,C,D),Set.of("river","hill"),Map.of("evidence-note","原始 \"quoted\" testimony"),"V2.PublicAuthority","Original operation=77; retain exact historical actors and timestamp"));
            insertEvent(c,new WorldEvent("legacy:active-source","LEGACY_WORLD_EVENT",TIME.plusSeconds(1),Set.of(A,B,C,D),Set.of("river","hill"),Map.of("state","unfinished"),"V2.PublicAuthority","Original operation=78"));
            insertInstance(c,completed(),pretty(completed()));insertInstance(c,active(),pretty(active()));
            s.execute("INSERT INTO facts VALUES('WORLD','region.old-capital','siege remembered')");s.execute("INSERT INTO facts VALUES('"+A+"','player.honor','15')");
            for(String status:List.of("DONE","PENDING","UNKNOWN","RUNNING")){
                String id="legacy:"+status.toLowerCase(Locale.ROOT);
                Delivery receipt=new Delivery(id,completed().id(),"authority","diplomacy.invite","hill",A,"river","starcore.nation.ally.add");
                try(var insert=c.prepareStatement("INSERT INTO outcomes VALUES(?,?,?)")){insert.setString(1,id);insert.setString(2,status);insert.setString(3,pretty(receipt));insert.executeUpdate();}
            }
            var request=new NativeSceneJournal.Request("legacy:native",completed().id(),A,"river","chronicle_last_heir","c".repeat(64),"arrival","starchronicle.use");
            try(var insert=c.prepareStatement("INSERT INTO outcomes VALUES(?,?,?)")){insert.setString(1,request.outcomeId());insert.setString(2,"DONE");insert.setString(3,pretty(new Delivery(request.outcomeId(),completed().id(),"narrative.start",request.scriptId(),request.initialNode(),A,"river",request.permission())));insert.executeUpdate();}
            var view=new NativeScene("chronicle_last_heir","evidence","旧都原生证词","Original native evidence text",List.of());
            var checkpoint=new NativeSceneJournal.Checkpoint("evidence",Map.of("chronicle_heir_score",7L),Set.of("chronicle_heir_trusted"),Set.of("chronicle_heir_testimony"),view);
            var entry=new NativeSceneJournal.Entry(request,NativeSceneJournal.Status.READY,7,checkpoint,TIME,"V2 owned checkpoint — preserve raw JSON");
            try(var insert=c.prepareStatement("INSERT INTO native_scene_journal VALUES(?,?,?,?,?)")){insert.setString(1,request.outcomeId());insert.setString(2,A.toString());insert.setString(3,"READY");insert.setLong(4,7);insert.setString(5,pretty(entry));insert.executeUpdate();}
            String triggerKey=RuleEngine.stable("legacy:world-source",definition().id(),definition().version());
            try(var insert=c.prepareStatement("INSERT INTO story_triggers VALUES(?,?,?,?,?,?)")){insert.setString(1,"legacy:pending-trigger");insert.setString(2,triggerKey);insert.setString(3,"legacy:world-source");insert.setString(4,"world");insert.setString(5,pretty(definition()));insert.setInt(6,17);insert.executeUpdate();}
            try(var insert=c.prepareStatement("INSERT INTO story_trigger_receipts VALUES(?,?)")){insert.setString(1,RuleEngine.stable("legacy:active-source",definition().id(),definition().version()));insert.setString(2,"world");insert.executeUpdate();}
            s.execute("PRAGMA user_version=2");assertForeignKeys(c);c.commit();
        }
    }
    private static List<List<String>> rows(Connection c,String sql)throws SQLException{
        List<List<String>> rows=new ArrayList<>();try(var s=c.createStatement();var r=s.executeQuery(sql)){int width=r.getMetaData().getColumnCount();while(r.next()){List<String> row=new ArrayList<>();for(int i=1;i<=width;i++)row.add(r.getString(i));rows.add(row);}}return rows;
    }
    private static Map<String,List<List<String>>> snapshot(Connection c)throws SQLException{
        Map<String,List<List<String>>> result=new TreeMap<>();for(var table:TABLE_ORDER.entrySet())result.put(table.getKey(),rows(c,"SELECT * FROM "+table.getKey()+" ORDER BY "+table.getValue()));return result;
    }
    private static Map<String,List<List<String>>> recoveredStatuses(Map<String,List<List<String>>> source){
        Map<String,List<List<String>>> result=new TreeMap<>();source.forEach((table,rows)->{List<List<String>> copy=new ArrayList<>();for(var original:rows){var row=new ArrayList<>(original);if(table.equals("outcomes")&&row.get(1).equals("RUNNING"))row.set(1,"UNKNOWN");copy.add(row);}result.put(table,copy);});return result;
    }
    private static Map<String,String> schema(Connection c)throws SQLException{Map<String,String> result=new TreeMap<>();for(var row:rows(c,"SELECT name,sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY name"))result.put(row.get(0),row.get(1));return result;}
    private static String scalar(Connection c,String sql)throws SQLException{try(var s=c.createStatement();var r=s.executeQuery(sql)){return r.next()?r.getString(1):null;}}
    private static void assertForeignKeys(Connection c)throws SQLException{assertTrue(rows(c,"PRAGMA foreign_key_check").isEmpty(),"Literal V2 sources and new choice rows must have real, valid foreign keys");}
    private static void assertPayloadBytes(Map<String,List<List<String>>> expected,Map<String,List<List<String>>> actual){
        for(var location:Map.of("events",4,"instances",5,"outcomes",2,"native_scene_journal",4,"story_triggers",4).entrySet())
            for(int i=0;i<expected.get(location.getKey()).size();i++)assertArrayEquals(expected.get(location.getKey()).get(i).get(location.getValue()).getBytes(StandardCharsets.UTF_8),actual.get(location.getKey()).get(i).get(location.getValue()).getBytes(StandardCharsets.UTF_8),location.getKey()+" payload row="+i);
    }
    private static void assertMigratedChoiceMeaning(Connection c)throws SQLException{
        assertEquals("5",scalar(c,"SELECT COUNT(*) FROM story_choice_log"));
        assertEquals("0",scalar(c,"SELECT COUNT(*) FROM story_choice_log WHERE provenance<>'LEGACY_INSTANCE' OR chosen_at IS NOT NULL OR committed_by IS NOT NULL OR revision<>1"));
        assertEquals(Map.of(PERSONAL,"support",WORLD,"support"),ChoiceLedger.read(c,A,"different-current-nation",Set.of(PERSONAL,WORLD,NATIONAL)));
        assertEquals(Map.of(PERSONAL,"oppose",WORLD,"support"),ChoiceLedger.read(c,B,"river",Set.of(PERSONAL,WORLD,NATIONAL)));
        assertEquals(Map.of(PERSONAL,"support",WORLD,"support"),ChoiceLedger.read(c,C,"river",Set.of(PERSONAL,WORLD)));
        var unvoted=ChoiceLedger.read(c,D,"hill",Set.of(PERSONAL,WORLD));assertEquals(Map.of(WORLD,"support"),unvoted);assertNull(unvoted.get(PERSONAL));assertFalse(unvoted.containsKey(PERSONAL));
        assertEquals("hill",scalar(c,"SELECT nation_id FROM story_choice_log WHERE instance_id='legacy:completed' AND player_id='"+B+"'"));
        assertEquals("oppose",scalar(c,"SELECT choice_id FROM story_choice_log WHERE instance_id='legacy:active'"));
        assertEquals("0",scalar(c,"SELECT COUNT(*) FROM story_choice_log WHERE player_id='"+D+"'"));
        assertEquals("legacy:completed,legacy:completed,legacy:completed,legacy:completed,legacy:active",scalar(c,"SELECT group_concat(instance_id,',') FROM (SELECT instance_id FROM story_choice_log ORDER BY seq)"));
        assertEquals(RuleEngine.stable("legacy-choice",completed().id(),"council","BALLOT","player:"+A),scalar(c,"SELECT id FROM story_choice_log WHERE instance_id='legacy:completed' AND player_id='"+A+"'"));
    }

    @Test void productionConstructorUpgradesLiteralV2WithoutRewritingAnyHistoricalPayloadOrReimportingIt()throws Exception{
        createLiteralV2();Map<String,List<List<String>>> original;Map<String,String> originalSchema;
        try(var c=open()){assertEquals("2",scalar(c,"PRAGMA user_version"));assertEquals("0",scalar(c,"SELECT COUNT(*) FROM sqlite_master WHERE name IN ('story_choice_log','event_causes','story_trigger_blocks')"));original=snapshot(c);originalSchema=schema(c);}
        List<List<String>> firstLog;
        try(var store=new SqliteStore(database())){
            firstLog=store.transaction(c->{assertEquals(Integer.toString(SqliteStore.SCHEMA_VERSION),scalar(c,"PRAGMA user_version"));assertEquals(7,SqliteStore.SCHEMA_VERSION);var upgraded=snapshot(c);assertEquals(recoveredStatuses(original),upgraded);assertPayloadBytes(original,upgraded);var upgradedSchema=schema(c);originalSchema.forEach((name,sql)->assertEquals(sql,upgradedSchema.get(name),name));assertMigratedChoiceMeaning(c);assertForeignKeys(c);return rows(c,"SELECT * FROM story_choice_log ORDER BY seq");});
        }
        try(var store=new SqliteStore(database())){
            store.transaction(c->{assertEquals(Integer.toString(SqliteStore.SCHEMA_VERSION),scalar(c,"PRAGMA user_version"));assertEquals(firstLog,rows(c,"SELECT * FROM story_choice_log ORDER BY seq"));assertEquals(recoveredStatuses(original),snapshot(c));assertPayloadBytes(original,snapshot(c));assertMigratedChoiceMeaning(c);assertForeignKeys(c);return null;});
        }
    }

    @ParameterizedTest @ValueSource(strings={"node","choice"})
    void constructorFailureRollsBackAllUpgradeDdlVersionAndPartialImportsThenRepairRetriesOnce(String corruption)throws Exception{
        createLiteralV2();String pristineJson;Map<String,List<List<String>>> damaged;Map<String,String> originalSchema;
        try(var c=open()){
            pristineJson=scalar(c,"SELECT json FROM instances WHERE id='legacy:completed'");StoryInstance before=completed();Map<String,String> choices=new TreeMap<>(before.choices());
            if(corruption.equals("node"))choices.put("zz-missing-node","support");else choices.put("council/"+D,"missing-choice");
            assertEquals(5,choices.size());assertEquals(corruption.equals("node")?"zz-missing-node":"council/"+D,new ArrayList<>(choices.keySet()).getLast(),"The corrupt entry sorts after four valid imports, exercising a mid-migration rollback");
            var bad=new StoryInstance(before.id(),before.definition(),before.scopeKey(),before.sourceEventId(),before.node(),before.members(),before.votes(),choices,before.startedAt(),before.updatedAt(),before.completed());
            try(var s=c.prepareStatement("UPDATE instances SET json=? WHERE id=?")){s.setString(1,pretty(bad));s.setString(2,before.id());s.executeUpdate();}
            damaged=snapshot(c);originalSchema=schema(c);assertEquals("2",scalar(c,"PRAGMA user_version"));
        }
        var failure=assertThrows(IllegalStateException.class,()->{try(var ignored=new SqliteStore(database())){fail("Invalid legacy choice should abort constructor migration");}});
        assertNotNull(failure.getCause());assertTrue(failure.getCause().getMessage().contains("Choice does not exist in the pinned definition"));
        try(var c=open()){
            assertEquals("2",scalar(c,"PRAGMA user_version"));assertEquals(originalSchema,schema(c),"All new tables and indexes must roll back, not merely their rows");assertEquals("0",scalar(c,"SELECT COUNT(*) FROM sqlite_master WHERE name IN ('story_choice_log','story_choice_player','story_choice_scope','event_causes','event_causal_root','story_trigger_blocks','story_definitions','instance_origin')"));
            assertEquals(damaged,snapshot(c));assertPayloadBytes(damaged,snapshot(c));assertEquals("RUNNING",scalar(c,"SELECT status FROM outcomes WHERE id='legacy:running'"));assertForeignKeys(c);
            try(var s=c.prepareStatement("UPDATE instances SET json=? WHERE id='legacy:completed'")){s.setString(1,pristineJson);assertEquals(1,s.executeUpdate());}
        }
        List<List<String>> migrated;
        try(var store=new SqliteStore(database())){migrated=store.transaction(c->{assertEquals(Integer.toString(SqliteStore.SCHEMA_VERSION),scalar(c,"PRAGMA user_version"));assertEquals(pristineJson,scalar(c,"SELECT json FROM instances WHERE id='legacy:completed'"));assertMigratedChoiceMeaning(c);assertEquals("UNKNOWN",scalar(c,"SELECT status FROM outcomes WHERE id='legacy:running'"));assertForeignKeys(c);return rows(c,"SELECT * FROM story_choice_log ORDER BY seq");});}
        try(var store=new SqliteStore(database())){store.transaction(c->{assertEquals(migrated,rows(c,"SELECT * FROM story_choice_log ORDER BY seq"));assertMigratedChoiceMeaning(c);assertForeignKeys(c);return null;});}
    }
}
