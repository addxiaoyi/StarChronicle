package io.github.addxiaoyi.starchronicle.persistence;

import io.github.addxiaoyi.starchronicle.history.HistoricalFacts;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class HistoryCountIndexTest {
    @TempDir Path directory;
    private static final UUID PLAYER=UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test void originalInsertValuesAbiMaintainsCountsAndDuplicateRowsNeverIncrement()throws Exception {
        Path file=directory.resolve("legacy-writer.db");
        try(var ignored=new SqliteStore(file)){}
        try(var connection=open(file)) {
            insert(connection,"one","WAR","north",PLAYER.toString());
            insert(connection,"two","WAR","north",PLAYER.toString());
            try(var statement=connection.createStatement()) {
                statement.executeUpdate("INSERT OR IGNORE INTO events VALUES('one','WAR','time','hash','{}')");
                statement.executeUpdate("INSERT OR IGNORE INTO event_nations VALUES('one','north')");
                statement.executeUpdate("INSERT OR IGNORE INTO event_players VALUES('one','"+PLAYER+"')");
            }
            assertCanonicalCounts(connection);
            assertEquals(Map.of("history.WAR","2","chronicle.history.world.WAR","2","chronicle.history.nation.WAR","2","chronicle.history.player.WAR","2"),HistoricalFacts.read(connection,PLAYER,"north",Set.of()));
            List<String> original=originalRows(connection);
            connection.setAutoCommit(false);insert(connection,"rollback","WAR","north",PLAYER.toString());
            assertCanonicalCounts(connection);assertEquals(3,total(connection,"world","","WAR"));
            connection.rollback();connection.setAutoCommit(true);
            assertEquals(original,originalRows(connection));assertCanonicalCounts(connection);
        }
    }

    @Test void updatesDeletesAndOrphanLegacySubjectsMatchCanonicalJoinsAtEveryStep()throws Exception {
        Path file=directory.resolve("mutations.db");
        try(var ignored=new SqliteStore(file)){}
        try(var connection=open(file);var statement=connection.createStatement()) {
            insert(connection,"one","WAR","north",PLAYER.toString());insert(connection,"two","WAR","north","other");
            statement.executeUpdate("UPDATE events SET id=id,type=type");
            statement.executeUpdate("UPDATE event_nations SET nation=nation");
            statement.executeUpdate("UPDATE event_players SET player=player WHERE event_id='missing'");assertCanonicalCounts(connection);
            statement.executeUpdate("UPDATE events SET type='TECH' WHERE id='one'");assertCanonicalCounts(connection);
            statement.executeUpdate("UPDATE event_nations SET nation='south' WHERE event_id='one'");assertCanonicalCounts(connection);
            statement.executeUpdate("UPDATE event_players SET event_id='two',player='moved' WHERE event_id='one'");assertCanonicalCounts(connection);
            statement.executeUpdate("UPDATE events SET id='renamed' WHERE id='two'");assertCanonicalCounts(connection);
            statement.executeUpdate("UPDATE event_nations SET event_id='renamed' WHERE event_id='two'");
            statement.executeUpdate("UPDATE event_players SET event_id='renamed' WHERE event_id='two'");assertCanonicalCounts(connection);
            statement.executeUpdate("DELETE FROM events WHERE id='renamed'");assertCanonicalCounts(connection);
            statement.executeUpdate("INSERT INTO event_nations VALUES('orphan','late')");
            statement.executeUpdate("INSERT INTO event_players VALUES('orphan','late-player')");assertCanonicalCounts(connection);
            statement.executeUpdate("INSERT INTO events VALUES('orphan','LATE','time','hash','{}')");assertCanonicalCounts(connection);
            statement.executeUpdate("DELETE FROM event_nations WHERE event_id='one'");
            statement.executeUpdate("DELETE FROM events WHERE id='one'");assertCanonicalCounts(connection);
            assertEquals(0,total(connection,"world","","WAR"));assertEquals(0,total(connection,"world","","TECH"));
        }
    }

    @Test void reopenRepairsWrongMissingAndExtraCountsAndModifiedTriggersWithoutChangingEvidence()throws Exception {
        Path file=directory.resolve("repair.db");
        try(var store=new SqliteStore(file)){store.transaction(connection->{insert(connection,"one","WAR","north",PLAYER.toString());return null;});}
        List<String> original;
        try(var connection=open(file);var statement=connection.createStatement()) {
            original=originalRows(connection);
            statement.executeUpdate("UPDATE history_event_counts SET total=999 WHERE scope='world'");
            statement.executeUpdate("DELETE FROM history_event_counts WHERE scope='nation'");
            statement.executeUpdate("INSERT INTO history_event_counts VALUES('world','','EXTRA',17)");
            statement.execute("DROP TRIGGER history_counts_event_insert");
            statement.execute("CREATE TRIGGER history_counts_event_insert AFTER INSERT ON events BEGIN SELECT 1; END");
            statement.execute("DROP TRIGGER history_counts_player_update");
        }
        try(var store=new SqliteStore(file)) {
            store.transaction(connection->{
                assertEquals(original,originalRows(connection));assertCanonicalCounts(connection);
                insert(connection,"two","WAR","north",PLAYER.toString());assertCanonicalCounts(connection);
                try(var statement=connection.createStatement();var rows=statement.executeQuery("PRAGMA user_version")){assertTrue(rows.next());assertEquals(11,rows.getInt(1));}
                return null;
            });
        }
    }

    @Test void reopenRebuildsMissingOrMalformedDerivedSchema()throws Exception {
        for(String variant:List.of("missing","malformed","constraints")) {
            Path file=directory.resolve(variant+".db");
            try(var store=new SqliteStore(file)){store.transaction(connection->{insert(connection,"one","旧都.player.WAR","north",PLAYER.toString());return null;});}
            List<String> original;
            try(var connection=open(file);var statement=connection.createStatement()) {
                original=originalRows(connection);statement.execute("DROP TABLE history_event_counts");
                if(variant.equals("malformed"))statement.execute("CREATE TABLE history_event_counts(scope TEXT,subject TEXT,type TEXT,total TEXT)");
                if(variant.equals("constraints"))statement.execute("CREATE TABLE history_event_counts(scope TEXT NOT NULL,subject TEXT NOT NULL,type TEXT NOT NULL,total INTEGER NOT NULL,PRIMARY KEY(scope,subject,type)) WITHOUT ROWID");
            }
            try(var store=new SqliteStore(file)){store.transaction(connection->{assertEquals(original,originalRows(connection));assertCanonicalCounts(connection);return null;});}
        }
    }

    @Test void alreadyCorrectProjectionDoesNotRewriteItsRowsOnOpen()throws Exception {
        Path file=directory.resolve("no-rewrite.db");
        try(var store=new SqliteStore(file)){store.transaction(connection->{insert(connection,"one","WAR","north",PLAYER.toString());return null;});}
        try(var connection=open(file);var statement=connection.createStatement()) {
            statement.execute("CREATE TRIGGER history_test_no_insert BEFORE INSERT ON history_event_counts BEGIN SELECT RAISE(ABORT,'unnecessary insert'); END");
            statement.execute("CREATE TRIGGER history_test_no_delete BEFORE DELETE ON history_event_counts BEGIN SELECT RAISE(ABORT,'unnecessary delete'); END");
        }
        try(var store=new SqliteStore(file)){store.transaction(connection->{assertCanonicalCounts(connection);return null;});}
    }

    @Test void failedRepairRollsBackCountAndTriggerChangesThenAllowsACompleteRetry()throws Exception {
        Path file=directory.resolve("failed-repair.db");
        try(var store=new SqliteStore(file)){store.transaction(connection->{insert(connection,"one","WAR","north",PLAYER.toString());return null;});}
        List<String> original;
        try(var connection=open(file);var statement=connection.createStatement()) {
            original=originalRows(connection);statement.executeUpdate("UPDATE history_event_counts SET total=99 WHERE scope='world'");
            statement.execute("DROP TRIGGER history_counts_player_update");
            statement.execute("CREATE TRIGGER history_test_abort BEFORE INSERT ON history_event_counts BEGIN SELECT RAISE(ABORT,'repair failure'); END");
        }
        assertThrows(IllegalStateException.class,()->new SqliteStore(file));
        try(var connection=open(file);var statement=connection.createStatement()) {
            assertEquals(original,originalRows(connection));assertEquals(99,total(connection,"world","","WAR"));
            try(var rows=statement.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name='history_counts_player_update'")){assertTrue(rows.next());assertEquals(0,rows.getInt(1));}
            statement.execute("DROP TRIGGER history_test_abort");
        }
        try(var store=new SqliteStore(file)){store.transaction(connection->{assertEquals(original,originalRows(connection));assertCanonicalCounts(connection);return null;});}
    }

    @Test void missingCounterRejectsACanonicalMutationWithoutPartiallyChangingTheEvent()throws Exception {
        Path file=directory.resolve("underflow.db");
        try(var store=new SqliteStore(file)) {
            store.transaction(connection->{insert(connection,"one","WAR","north",PLAYER.toString());try(var statement=connection.createStatement()){statement.executeUpdate("DELETE FROM history_event_counts WHERE scope='world'");}return null;});
            assertThrows(IllegalStateException.class,()->store.transaction(connection->{try(var statement=connection.createStatement()){statement.executeUpdate("UPDATE events SET type='TECH' WHERE id='one'");}return null;}));
            store.transaction(connection->{try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT type FROM events WHERE id='one'")){assertTrue(rows.next());assertEquals("WAR",rows.getString(1));}assertEquals(1,total(connection,"nation","north","WAR"));return null;});
        }
    }

    @Test void initializationRequiresTheExistingTransaction()throws Exception {
        try(var connection=DriverManager.getConnection("jdbc:sqlite::memory:")){assertThrows(IllegalStateException.class,()->HistoryCountIndex.initialize(connection));}
    }

    private static Connection open(Path file)throws SQLException{return DriverManager.getConnection("jdbc:sqlite:"+file.toAbsolutePath());}
    private static void insert(Connection connection,String id,String type,String nation,String player)throws SQLException {
        try(var statement=connection.prepareStatement("INSERT INTO events VALUES(?,?,?,?,?)")){statement.setString(1,id);statement.setString(2,type);statement.setString(3,"time");statement.setString(4,"hash-"+id);statement.setString(5,"{\"original\":\""+id+"\"}");statement.executeUpdate();}
        try(var statement=connection.prepareStatement("INSERT INTO event_nations VALUES(?,?)")){statement.setString(1,id);statement.setString(2,nation);statement.executeUpdate();}
        try(var statement=connection.prepareStatement("INSERT INTO event_players VALUES(?,?)")){statement.setString(1,id);statement.setString(2,player);statement.executeUpdate();}
    }
    private static long total(Connection connection,String scope,String subject,String type)throws SQLException {
        try(var statement=connection.prepareStatement("SELECT total FROM history_event_counts WHERE scope=? AND subject=? AND type=?")){statement.setString(1,scope);statement.setString(2,subject);statement.setString(3,type);try(var rows=statement.executeQuery()){return rows.next()?rows.getLong(1):0;}}
    }
    private static void assertCanonicalCounts(Connection connection)throws SQLException {
        String expected="SELECT 'world' AS scope,'' AS subject,type,COUNT(*) AS total FROM events GROUP BY type UNION ALL SELECT 'nation',n.nation,e.type,COUNT(*) FROM event_nations n JOIN events e ON e.id=n.event_id GROUP BY n.nation,e.type UNION ALL SELECT 'player',p.player,e.type,COUNT(*) FROM event_players p JOIN events e ON e.id=p.event_id GROUP BY p.player,e.type";
        assertEquals(rows(connection,"SELECT scope,subject,type,total FROM ("+expected+") ORDER BY scope,subject,type"),rows(connection,"SELECT scope,subject,type,total FROM history_event_counts ORDER BY scope,subject,type"));
    }
    private static List<String> originalRows(Connection connection)throws SQLException {
        List<String> result=new ArrayList<>();
        for(String table:List.of("events","event_nations","event_players"))result.addAll(rows(connection,"SELECT * FROM "+table+" ORDER BY 1,2"));
        return List.copyOf(result);
    }
    private static List<String> rows(Connection connection,String sql)throws SQLException {
        List<String> result=new ArrayList<>();
        try(var statement=connection.createStatement();var rows=statement.executeQuery(sql)){int columns=rows.getMetaData().getColumnCount();while(rows.next()){List<String> values=new ArrayList<>();for(int column=1;column<=columns;column++)values.add(rows.getString(column));result.add(values.toString());}}
        return List.copyOf(result);
    }
}
