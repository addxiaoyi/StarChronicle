package io.github.addxiaoyi.starchronicle.persistence;

import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition.*;
import io.github.addxiaoyi.starchronicle.core.*;
import io.github.addxiaoyi.starchronicle.narrative.NativeSceneJournal;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Creates the actual pre-feature V1 schema directly, never by relabeling a V2 database. */
class SchemaMigrationTest {
    @TempDir Path directory;
    final UUID player=UUID.fromString("00000000-0000-0000-0000-000000000077");
    final Instant time=Instant.parse("2026-11-19T10:11:12.123Z");
    Path database(){return directory.resolve("legacy-v1.db");}
    WorldEvent legacy(){return new WorldEvent("legacy:event","LEGACY",time,Set.of(player),Set.of("river"),Map.of("record","unchanged"),"LegacySource","Original committed receipt=77");}
    StoryDefinition definition(){return new StoryDefinition("migration-story","1","Migration story",Scope.PLAYER,List.of("AFTER_UPGRADE"),null,"end",0,Map.of("end",new Node("End",null,null,1,Map.of(),List.of(new Effect("honor","honor","player","3",null)),true)));}
    String fingerprint(WorldEvent event) {
        Map<String,Object> canonical=new TreeMap<>();canonical.put("id",event.id());canonical.put("type",event.type());canonical.put("time",event.occurredAt().toString());canonical.put("players",event.participants().stream().map(UUID::toString).sorted().toList());canonical.put("nations",new TreeSet<>(event.nations()));canonical.put("facts",new TreeMap<>(event.facts()));canonical.put("source",event.source());canonical.put("evidence",event.evidence());return RuleEngine.stable(SqliteStore.json(canonical));
    }
    void createV1()throws Exception {
        Class.forName("org.sqlite.JDBC");
        try(var connection=DriverManager.getConnection("jdbc:sqlite:"+database().toAbsolutePath());var statement=connection.createStatement()) {
            statement.execute("CREATE TABLE events(id TEXT PRIMARY KEY,type TEXT NOT NULL,time TEXT NOT NULL,hash TEXT NOT NULL,json TEXT NOT NULL)");
            statement.execute("CREATE TABLE event_nations(event_id TEXT REFERENCES events(id),nation TEXT NOT NULL,PRIMARY KEY(event_id,nation))");
            statement.execute("CREATE TABLE event_players(event_id TEXT REFERENCES events(id),player TEXT NOT NULL,PRIMARY KEY(event_id,player))");
            statement.execute("CREATE TABLE instances(id TEXT PRIMARY KEY,story TEXT NOT NULL,scope TEXT NOT NULL,completed INTEGER NOT NULL,updated TEXT NOT NULL,json TEXT NOT NULL)");
            statement.execute("CREATE TABLE outcomes(id TEXT PRIMARY KEY,status TEXT NOT NULL,json TEXT NOT NULL)");
            statement.execute("CREATE TABLE facts(owner TEXT NOT NULL,key TEXT NOT NULL,value TEXT NOT NULL,PRIMARY KEY(owner,key))");
            statement.execute("PRAGMA user_version=1");
            WorldEvent event=legacy();try(var insert=connection.prepareStatement("INSERT INTO events VALUES(?,?,?,?,?)")){insert.setString(1,event.id());insert.setString(2,event.type());insert.setString(3,"2026-11-19T10:11:12.123000000Z");insert.setString(4,fingerprint(event));insert.setString(5,SqliteStore.json(event));insert.executeUpdate();}
            try(var insert=connection.prepareStatement("INSERT INTO event_nations VALUES(?,?)")){insert.setString(1,event.id());insert.setString(2,"river");insert.executeUpdate();}
            try(var insert=connection.prepareStatement("INSERT INTO event_players VALUES(?,?)")){insert.setString(1,event.id());insert.setString(2,player.toString());insert.executeUpdate();}
            try(var insert=connection.prepareStatement("INSERT INTO facts VALUES(?,?,?)")){insert.setString(1,player.toString());insert.setString(2,"player.honor");insert.setString(3,"45");insert.executeUpdate();insert.setString(1,"WORLD");insert.setString(2,"region.legacy-city");insert.setString(3,"remembered");insert.executeUpdate();}
            for(String status:List.of("DONE","PENDING","REJECTED","UNKNOWN","RUNNING"))try(var insert=connection.prepareStatement("INSERT INTO outcomes VALUES(?,?,?)")){Delivery delivery=delivery(status);insert.setString(1,delivery.outcomeId());insert.setString(2,status);insert.setString(3,SqliteStore.json(delivery));insert.executeUpdate();}
        }
    }
    Delivery delivery(String status){return new Delivery("legacy-"+status,"legacy-instance","quest.accept","legacy-quest","context",player,"river",null);}
    String scalar(SqliteStore store,String sql){return store.transaction(c->{try(var statement=c.createStatement();var rows=statement.executeQuery(sql)){rows.next();return rows.getString(1);}});}
    long count(SqliteStore store,String table){return Long.parseLong(scalar(store,"SELECT COUNT(*) FROM "+table));}
    void assertPreserved(SqliteStore store) {
        assertEquals("2",scalar(store,"PRAGMA user_version"));assertEquals(SqliteStore.json(legacy()),scalar(store,"SELECT json FROM events WHERE id='legacy:event'"));assertEquals(fingerprint(legacy()),scalar(store,"SELECT hash FROM events WHERE id='legacy:event'"));assertEquals("2026-11-19T10:11:12.123000000Z",scalar(store,"SELECT time FROM events WHERE id='legacy:event'"));
        assertEquals("45",scalar(store,"SELECT value FROM facts WHERE key='player.honor'"));assertEquals("remembered",scalar(store,"SELECT value FROM facts WHERE owner='WORLD' AND key='region.legacy-city'"));assertEquals("1",scalar(store,"SELECT COUNT(*) FROM event_nations WHERE event_id='legacy:event' AND nation='river'"));assertEquals("1",scalar(store,"SELECT COUNT(*) FROM event_players WHERE event_id='legacy:event' AND player='"+player+"'"));
        for(String status:List.of("DONE","PENDING","REJECTED","UNKNOWN","RUNNING")){assertEquals(SqliteStore.json(delivery(status)),scalar(store,"SELECT json FROM outcomes WHERE id='legacy-"+status+"'"));assertEquals(status.equals("RUNNING")?"UNKNOWN":status,scalar(store,"SELECT status FROM outcomes WHERE id='legacy-"+status+"'"));}
    }

    @Test void openingV1CreatesNewTablesAndIndexesWithoutChangingLegacyEvidenceFactsOrReceipts()throws Exception {
        createV1();try(var store=new SqliteStore(database())){assertPreserved(store);for(String table:List.of("story_triggers","story_trigger_receipts","native_scene_journal"))assertEquals(0,count(store,table));
            for(String index:List.of("story_trigger_scope","story_trigger_event","native_scene_player_status"))assertEquals(index,scalar(store,"SELECT name FROM sqlite_master WHERE type='index' AND name='"+index+"'"));
            var engine=new RuleEngine(store,List.of(),Clock.systemUTC());assertEquals(List.of(legacy()),engine.history("river",player,0,10));assertTrue(engine.accept(legacy(),List.of(new PlayerContext(player,"river",Map.of(),Set.of()))).duplicate());assertEquals(1,count(store,"events"));assertEquals(5,count(store,"outcomes"));}
        try(var store=new SqliteStore(database())){assertPreserved(store);assertEquals(0,count(store,"story_triggers"));}
    }
    @Test void migratedDatabaseCanPersistBothNewTriggerAndNativeCheckpointThroughAnotherRestart()throws Exception {
        createV1();String nativeId="new-native";WorldEvent source=new WorldEvent("new:event","AFTER_UPGRADE",time.plusSeconds(1),Set.of(player),Set.of("river"),Map.of(),"MigrationTest","New committed receipt=78");String hash="c".repeat(64);
        try(var store=new SqliteStore(database())) {
            var engine=new RuleEngine(store,List.of(definition()),Clock.systemUTC());engine.accept(source,List.of());
            // Explicit participant plus a durable national audience; both survive migration.
            assertEquals(2,engine.pendingTriggerCount());
            var journal=new NativeSceneJournal(store,Clock.systemUTC());
            journal.prepare(new NativeSceneJournal.Request(nativeId,"new-instance",player,"river","chronicle_last_heir",hash,"arrival","scene.permission"));
            var admitted=journal.claim(nativeId,0,hash).orElseThrow();
            journal.checkpoint(admitted,new NativeSceneJournal.Checkpoint("arrival",Map.of("chronicle_heir_progress",1),Set.of("chronicle_heir_seen"),Set.of(),new NativeScene("chronicle_last_heir","arrival","Scene","Text",List.of())));
        }
        try(var store=new SqliteStore(database())) {
            assertPreserved(store);var journal=new NativeSceneJournal(store,Clock.systemUTC());
            assertEquals("arrival",journal.resumable(player,1).getFirst().checkpoint().nodeId());
            assertEquals("scene.permission",journal.find(nativeId).orElseThrow().request().permission());
            var engine=new RuleEngine(store,List.of(),Clock.systemUTC());assertEquals(2,engine.pendingTriggerCount());
            var current=new PlayerContext(player,"river",Map.of(),Set.of());
            engine.resumeTriggers(List.of(current));assertEquals("48",engine.facts(player).get("player.honor"));
            // National audience remains available to other citizens, not as a second reward.
            assertEquals(1,engine.pendingTriggerCount());engine.resumeTriggers(List.of(current));
            assertEquals("48",engine.facts(player).get("player.honor"));
            assertEquals(2,count(store,"events"));assertEquals("remembered",engine.worldFacts().get("region.legacy-city"));
        }
    }
}
