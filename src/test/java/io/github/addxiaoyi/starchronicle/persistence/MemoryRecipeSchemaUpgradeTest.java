package io.github.addxiaoyi.starchronicle.persistence;

import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import io.github.addxiaoyi.starchronicle.history.EventCausality;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Literal verified f2a4344 V6 DDL. Never creates the new store and relabels it as old. */
class MemoryRecipeSchemaUpgradeTest {
    @TempDir Path directory;
    private Path db(){return directory.resolve("released-v6.db");}
    private Connection open()throws Exception{Class.forName("org.sqlite.JDBC");return DriverManager.getConnection("jdbc:sqlite:"+db().toAbsolutePath());}
    private static String scalar(Connection c,String sql)throws SQLException{try(var s=c.createStatement();var r=s.executeQuery(sql)){return r.next()?r.getString(1):null;}}
    private static int columns(Connection c)throws SQLException{try(var s=c.createStatement();var r=s.executeQuery("PRAGMA table_info(event_causes)")){int count=0;while(r.next())count++;return count;}}
    private static final String LEGACY_STORY_PATH="[ \"legacy-story\" ]";
    private void createReleasedV6()throws Exception {
        String ddl;try(var resource=getClass().getResourceAsStream("/schema/f2a4344-schema6.sql")){assertNotNull(resource);ddl=new String(resource.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
        try(var c=open();var s=c.createStatement()) {
            c.setAutoCommit(false);for(String line:ddl.lines().toList())if(!line.isBlank()&&!line.startsWith("--"))s.execute(line);
            WorldEvent root=new WorldEvent("legacy-root","BATTLE_ENDED",Instant.parse("2026-11-19T12:00:00Z"),Set.of(new UUID(0,19)),Set.of("native-nation"),Map.of("war.id","actual-war"),"OriginalAuthority","Original committed evidence");
            WorldEvent child=new WorldEvent("legacy-child","OLD_STORY_RESULT",root.occurredAt().plusSeconds(1),root.participants(),root.nations(),Map.of("story","legacy-story"),"StarChronicle","Original derived result");
            for(var event:List.of(root,child))try(var put=c.prepareStatement("INSERT INTO events VALUES(?,?,?,?,?)")){put.setString(1,event.id());put.setString(2,event.type());put.setString(3,event.occurredAt().toString());put.setString(4,"retained-old-hash-"+event.id());put.setString(5,SqliteStore.json(event));put.executeUpdate();}
            s.execute("INSERT INTO event_causes VALUES('legacy-root',NULL,'legacy-root',0,'[]')");
            try(var put=c.prepareStatement("INSERT INTO event_causes VALUES('legacy-child','legacy-root','legacy-root',1,?)")){put.setString(1,LEGACY_STORY_PATH);put.executeUpdate();}
            s.execute("INSERT INTO outcomes VALUES('old-reward','DONE','{\"retained\":true}')");s.execute("INSERT INTO facts VALUES('WORLD','region.old-city','old memory')");
            assertEquals("6",scalar(c,"PRAGMA user_version"));assertEquals(5,columns(c));c.commit();
        }
    }
    @Test void schemaSevenAddsEmptyRecipePathWithoutRewritingReleasedEvidence()throws Exception {
        createReleasedV6();String root,child;
        try(var c=open()){root=scalar(c,"SELECT json FROM events WHERE id='legacy-root'");child=scalar(c,"SELECT json FROM events WHERE id='legacy-child'");}
        for(int reopen=0;reopen<2;reopen++)try(var store=new SqliteStore(db())) {
            store.transaction(c->{assertEquals("7",scalar(c,"PRAGMA user_version"));assertEquals(6,columns(c));assertEquals(root,scalar(c,"SELECT json FROM events WHERE id='legacy-root'"));assertEquals(child,scalar(c,"SELECT json FROM events WHERE id='legacy-child'"));assertEquals(LEGACY_STORY_PATH,scalar(c,"SELECT story_path FROM event_causes WHERE event_id='legacy-child'"));assertEquals("[]",scalar(c,"SELECT recipe_path FROM event_causes WHERE event_id='legacy-child'"));assertEquals(new EventCausality.Trace("legacy-root","legacy-root",1,List.of("legacy-story")),EventCausality.read(c,"legacy-child"));assertEquals("DONE",scalar(c,"SELECT status FROM outcomes WHERE id='old-reward'"));assertEquals("old memory",scalar(c,"SELECT value FROM facts WHERE key='region.old-city'"));assertEquals("0",scalar(c,"SELECT COUNT(*) FROM memory_entries"));assertEquals("0",scalar(c,"SELECT COUNT(*) FROM memory_recipe_definitions"));assertNull(scalar(c,"PRAGMA foreign_key_check"));return null;});
        }
    }
    @Test void migrationFailureRollsBackTheAlterAndAllNewTables()throws Exception {
        createReleasedV6();String original;
        try(var c=open();var s=c.createStatement()){original=scalar(c,"SELECT json FROM events WHERE id='legacy-root'");s.execute("CREATE VIEW memory_entries AS SELECT '' AS region_id,'' AS recorded_at,'' AS id");}
        assertThrows(IllegalStateException.class,()->new SqliteStore(db()));
        try(var c=open()){assertEquals("6",scalar(c,"PRAGMA user_version"));assertEquals(5,columns(c));assertEquals(original,scalar(c,"SELECT json FROM events WHERE id='legacy-root'"));assertEquals(LEGACY_STORY_PATH,scalar(c,"SELECT story_path FROM event_causes WHERE event_id='legacy-child'"));assertEquals("0",scalar(c,"SELECT COUNT(*) FROM sqlite_master WHERE name='memory_recipe_definitions'"));}
    }
}
