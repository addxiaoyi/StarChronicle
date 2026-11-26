package io.github.addxiaoyi.starchronicle.persistence;

import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.config.*;
import io.github.addxiaoyi.starchronicle.core.*;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SqliteStoreTest {
    @TempDir Path directory;
    @Test void schemaEightUpgradePreservesHistoricalBytesAndAddsReceiptCommitBoundary() throws Exception {
        Path path=directory.resolve("schema-eight.db");
        String historical;
        try(var store=new SqliteStore(path)) {
            var engine=new RuleEngine(store,List.of(),Clock.systemUTC());
            engine.archiveCommittedHistory(new WorldEvent("legacy:1","LEGACY",Instant.parse("2026-11-26T00:00:00.123456789Z"),
                    Set.of(),Set.of("native-nation"),Map.of("old","preserve"),"fixture","committed before resource history"));
            historical=store.transaction(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT json FROM events WHERE id='legacy:1'")){return r.getString(1);}});
            store.transaction(c->{try(var s=c.createStatement()){s.execute("DROP TABLE resource_history_receipts");s.execute("PRAGMA user_version=8");}return null;});
        }
        try(var store=new SqliteStore(path)) {
            store.transaction(c->{try(var s=c.createStatement()){
                try(var r=s.executeQuery("PRAGMA user_version")){assertEquals(10,r.getInt(1));}
                try(var r=s.executeQuery("SELECT json FROM events WHERE id='legacy:1'")){assertEquals(historical,r.getString(1));}
                try(var r=s.executeQuery("SELECT COUNT(*) FROM resource_history_receipts")){assertEquals(0,r.getInt(1));}
                try(var r=s.executeQuery("PRAGMA foreign_key_check")){assertFalse(r.next());}
            }return null;});
        }
    }
    @Test void failedTransactionRollsBackAnAlreadyWrittenFact() {
        try(var store=new SqliteStore(directory.resolve("rollback.db"))) {
            assertThrows(IllegalStateException.class,()->store.transaction(c->{
                try(var s=c.createStatement()){s.executeUpdate("INSERT INTO facts VALUES('player','honor','50')");}
                throw new IllegalStateException("simulated failure after SQL write");
            }));
            int count=store.transaction(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM facts")){return r.getInt(1);}});
            assertEquals(0,count);
            assertEquals("ok",store.transaction(c->"ok"));
        }
    }
    @Test void foreignKeyViolationCannotCreateOrphanHistoricalEvidence() {
        try(var store=new SqliteStore(directory.resolve("foreign-key.db"))) {
            assertThrows(IllegalStateException.class,()->store.transaction(c->{try(var s=c.createStatement()){s.executeUpdate("INSERT INTO event_nations VALUES('missing-event','nation')");}return null;}));
            int count=store.transaction(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM event_nations")){return r.getInt(1);}});assertEquals(0,count);
        }
    }
    @Test void newerDatabaseSchemaIsRejectedWithoutOverwritingVersion() throws Exception {
        Path path=directory.resolve("future.db");
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+path);var s=c.createStatement()){s.execute("PRAGMA user_version=999");}
        assertThrows(IllegalStateException.class,()->new SqliteStore(path));
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+path);var s=c.createStatement();var r=s.executeQuery("PRAGMA user_version")){assertEquals(999,r.getInt(1));}
    }
    @Test void sqliteAbortedTransactionPreservesOriginalCauseAndRetiresUncertainConnection() {
        Path path=directory.resolve("native-rollback.db");
        try(var store=new SqliteStore(path)) {
            store.transaction(c->{try(var s=c.createStatement()){s.execute("CREATE TRIGGER abort_fact BEFORE INSERT ON facts BEGIN SELECT RAISE(ROLLBACK,'original-storage-error'); END");}return null;});
            var error=assertThrows(IllegalStateException.class,()->store.transaction(c->{try(var s=c.createStatement()){s.execute("INSERT INTO facts VALUES('p','key','value')");}return null;}));
            assertTrue(error.getCause().getMessage().contains("original-storage-error"));
            assertTrue(error.getCause().getSuppressed().length>0);
            assertThrows(IllegalStateException.class,()->store.transaction(c->"must reopen"));
        }
        try(var reopened=new SqliteStore(path)) {
            assertEquals(0,reopened.transaction(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM facts")){return r.getInt(1);}}).intValue());
        }
    }
    @Test void failedRewardRollsBackReceiptAndNodeThenCanBeRetriedSafely() {
        UUID player=UUID.randomUUID();var context=new PlayerContext(player,"nation",Map.of(),Set.of());
        var reward=new StoryDefinition.Effect("honor","honor","player","10",null);
        var definition=new StoryDefinition("rollback","1","rollback",StoryDefinition.Scope.PLAYER,List.of("TEST"),null,"start",0,Map.of(
                "start",new StoryDefinition.Node("start",null,null,1,Map.of("finish",new StoryDefinition.Choice("finish","end",null,null,List.of())),List.of(),false),
                "end",new StoryDefinition.Node("end",null,null,1,Map.of(),List.of(reward),true)));
        SqliteStore store=new SqliteStore(directory.resolve("reward.db"));
        try(var engine=new RuleEngine(store,List.of(definition),Clock.systemUTC())) {
            String id=engine.accept(new WorldEvent("test:1","TEST",Instant.now(),Set.of(player),Set.of("nation"),Map.of(),"test","committed fixture"),List.of(context)).instanceIds().getFirst();
            store.transaction(c->{try(var s=c.prepareStatement("INSERT INTO facts VALUES(?,?,?)")){s.setString(1,player.toString());s.setString(2,"player.honor");s.setString(3,Long.toString(Long.MAX_VALUE));s.executeUpdate();}return null;});
            assertThrows(ArithmeticException.class,()->engine.choose(id,player,"start","finish",context));
            assertEquals("start",engine.instances(player).getFirst().node());assertEquals(Long.toString(Long.MAX_VALUE),engine.facts(player).get("player.honor"));
            store.transaction(c->{try(var s=c.createStatement()){s.executeUpdate("UPDATE facts SET value='0' WHERE key='player.honor'");}return null;});
            engine.choose(id,player,"start","finish",context);assertTrue(engine.instances(player).getFirst().completed());assertEquals("10",engine.facts(player).get("player.honor"));
        }
    }
}
