package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.config.*;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition.*;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** File-backed contracts independent of SqliteStore's current schema version or engine wiring. */
class ChoiceLedgerTest {
    @TempDir Path directory;
    final UUID a=UUID.fromString("00000000-0000-0000-0000-000000000001"),b=UUID.fromString("00000000-0000-0000-0000-000000000002"),c=UUID.fromString("00000000-0000-0000-0000-000000000003"),unvoted=UUID.fromString("00000000-0000-0000-0000-000000000004");
    final Instant now=Instant.parse("2026-11-20T00:00:00Z");
    static final String PERSONAL="past.player.choice.heir/1/council",WORLD="past.resolution.world.heir/1/council",NATION="past.resolution.nation.heir/1/council";
    Path database(){return directory.resolve("choices.db");}
    PlayerContext actor(UUID player,String nation){return new PlayerContext(player,nation,Map.of(),Set.of());}
    StoryInstance instance(String id,Scope scope,Map<UUID,String> members,int quorum,String version) {
        Map<String,Node> nodes=Map.of("council",new Node("Vote",null,null,quorum,Map.of("yes",new Choice("Yes","done",null,null,List.of()),"no",new Choice("No","done",null,null,List.of())),List.of(),false),"done",new Node("Done",null,null,1,Map.of(),List.of(),true));
        StoryDefinition definition=new StoryDefinition("heir",version,"Heir",scope,List.of("TEST"),null,"council",0,nodes);
        String key=switch(scope){case WORLD->"world";case NATION->"nation:"+members.values().iterator().next();case PLAYER->"player:"+members.keySet().iterator().next();};
        return new StoryInstance(id,definition,key,"source:"+id,"council",members,Map.of(),Map.of(),now,now,false);
    }
    StoryInstance state(StoryInstance i,Map<UUID,String> votes,Map<String,String> choices,boolean completed,Instant updated) {
        return new StoryInstance(i.id(),i.definition(),i.scopeKey(),i.sourceEventId(),completed?"done":i.node(),i.members(),votes,choices,i.startedAt(),updated,completed);
    }
    Connection open()throws Exception {
        Class.forName("org.sqlite.JDBC");Connection connection=DriverManager.getConnection("jdbc:sqlite:"+database().toAbsolutePath());
        try(var s=connection.createStatement()){s.execute("PRAGMA foreign_keys=ON");s.execute("PRAGMA busy_timeout=5000");}
        return connection;
    }
    void createV2(Connection connection)throws SQLException {
        try(var s=connection.createStatement()) {
            s.execute("CREATE TABLE events(id TEXT PRIMARY KEY,type TEXT NOT NULL,time TEXT NOT NULL,hash TEXT NOT NULL,json TEXT NOT NULL)");
            s.execute("CREATE TABLE event_nations(event_id TEXT REFERENCES events(id),nation TEXT NOT NULL,PRIMARY KEY(event_id,nation))");
            s.execute("CREATE TABLE event_players(event_id TEXT REFERENCES events(id),player TEXT NOT NULL,PRIMARY KEY(event_id,player))");
            s.execute("CREATE TABLE instances(id TEXT PRIMARY KEY,story TEXT NOT NULL,scope TEXT NOT NULL,completed INTEGER NOT NULL,updated TEXT NOT NULL,json TEXT NOT NULL)");
            s.execute("CREATE TABLE outcomes(id TEXT PRIMARY KEY,status TEXT NOT NULL,json TEXT NOT NULL)");
            s.execute("CREATE TABLE facts(owner TEXT NOT NULL,key TEXT NOT NULL,value TEXT NOT NULL,PRIMARY KEY(owner,key))");
            s.execute("CREATE TABLE story_triggers(id TEXT PRIMARY KEY,trigger_key TEXT NOT NULL,event_id TEXT NOT NULL REFERENCES events(id),scope TEXT NOT NULL,story_json TEXT NOT NULL,checked INTEGER NOT NULL DEFAULT 0)");
            s.execute("CREATE TABLE story_trigger_receipts(trigger_key TEXT NOT NULL,scope TEXT NOT NULL,PRIMARY KEY(trigger_key,scope))");
            s.execute("CREATE TABLE native_scene_journal(id TEXT PRIMARY KEY,player TEXT NOT NULL,status TEXT NOT NULL,revision INTEGER NOT NULL,json TEXT NOT NULL)");
            s.execute("PRAGMA user_version=2");
        }
    }
    Connection fresh()throws Exception {Connection connection=open();createV2(connection);connection.setAutoCommit(false);ChoiceLedger.createSchema(connection);return connection;}
    void seed(Connection connection,StoryInstance instance)throws SQLException {
        WorldEvent source=new WorldEvent(instance.sourceEventId(),"TEST",now,Set.of(),Set.of(),Map.of(),"ChoiceLedgerTest","committed source="+instance.sourceEventId());
        try(var s=connection.prepareStatement("INSERT OR IGNORE INTO events VALUES(?,?,?,?,?)")){s.setString(1,source.id());s.setString(2,source.type());s.setString(3,now.toString());s.setString(4,RuleEngine.stable(source.id()));s.setString(5,SqliteStore.json(source));s.executeUpdate();}
        save(connection,instance);
    }
    void save(Connection connection,StoryInstance instance)throws SQLException {
        try(var s=connection.prepareStatement("INSERT INTO instances VALUES(?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET completed=excluded.completed,updated=excluded.updated,json=excluded.json")) {
            s.setString(1,instance.id());s.setString(2,instance.definition().id());s.setString(3,instance.scopeKey());s.setInt(4,instance.completed()?1:0);s.setString(5,instance.updatedAt().toString());s.setString(6,SqliteStore.json(instance));s.executeUpdate();
        }
    }
    long count(Connection connection,String where)throws SQLException {try(var s=connection.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM story_choice_log"+where)){r.next();return r.getLong(1);}}
    String scalar(Connection connection,String sql)throws SQLException {try(var s=connection.createStatement();var r=s.executeQuery(sql)){return r.next()?r.getString(1):null;}}
    void resolve(Connection connection,StoryInstance instance,UUID player,String nation,String choice,Instant time)throws SQLException {
        seed(connection,instance);ChoiceLedger.recordBallot(connection,instance,actor(player,nation),"council",choice,time);
        ChoiceLedger.recordResolution(connection,instance,actor(player,nation),"council",choice,time);
        save(connection,state(instance,Map.of(),Map.of("council/"+player,choice,"council",choice),true,time));
    }

    @Test void requestedTraversesCompositesAndRejectsUnknownReservedNamespaces() {
        Condition personal=new Condition(null,null,null,PERSONAL,"eq","yes"),national=new Condition(null,null,null,NATION,"exists",null);
        Condition group=new Condition(List.of(personal,new Condition(null,List.of(national,new Condition(null,null,personal,null,null,null)),null,null,null,null)),null,null,null,null,null);
        assertEquals(Set.of(PERSONAL,NATION),ChoiceLedger.requested(group,null,new Condition(null,null,null,"history.TEST","gt","0")));
        assertThrows(UnsupportedOperationException.class,()->ChoiceLedger.requested(personal).clear());
        assertThrows(IllegalArgumentException.class,()->ChoiceLedger.requested(new Condition(null,null,null,"past.unknown.heir/1/council","eq","yes")));
    }
    @Test void allDatabaseEntryPointsRequireTheCallersTransaction()throws Exception {
        try(var connection=open()) {
            createV2(connection);var i=instance("owned",Scope.WORLD,Map.of(a,"river"),1,"1");
            assertThrows(IllegalStateException.class,()->ChoiceLedger.createSchema(connection));assertThrows(IllegalStateException.class,()->ChoiceLedger.migrateSnapshots(connection));
            assertThrows(IllegalStateException.class,()->ChoiceLedger.recordBallot(connection,i,actor(a,"river"),"council","yes",now));
            assertThrows(IllegalStateException.class,()->ChoiceLedger.recordResolution(connection,i,actor(a,"river"),"council","yes",now));
            assertThrows(IllegalStateException.class,()->ChoiceLedger.read(connection,a,"river",Set.of(PERSONAL)));
        }
    }
    @Test void identicalBallotIsIdempotentAndARevoteAppendsItsOwnStableRevisionAcrossRestart()throws Exception {
        var i=instance("revote",Scope.WORLD,Map.of(a,"river",b,"hill"),2,"1");
        try(var connection=fresh()) {
            seed(connection,i);assertTrue(ChoiceLedger.recordBallot(connection,i,actor(a,"river"),"council","yes",now));
            assertFalse(ChoiceLedger.recordBallot(connection,i,actor(a,"river"),"council","yes",now.plusSeconds(10)));assertEquals(1,count(connection,""));
            var voted=state(i,Map.of(a,"yes"),Map.of("council/"+a,"yes"),false,now.plusSeconds(1));save(connection,voted);
            assertTrue(ChoiceLedger.recordBallot(connection,voted,actor(a,"river"),"council","no",now.plusSeconds(2)));
            save(connection,state(voted,Map.of(a,"no"),Map.of("council/"+a,"no"),false,now.plusSeconds(2)));
            assertEquals("1,2",scalar(connection,"SELECT group_concat(revision,',') FROM (SELECT revision FROM story_choice_log ORDER BY seq)"));
            assertEquals(RuleEngine.stable("choice-log",i.id(),"council","BALLOT","player:"+a,"2"),scalar(connection,"SELECT id FROM story_choice_log WHERE revision=2"));
            assertTrue(ChoiceLedger.read(connection,a,"river",Set.of(PERSONAL)).isEmpty());connection.commit();
        }
        try(var connection=open()) {
            connection.setAutoCommit(false);ChoiceLedger.createSchema(connection);assertEquals(2,count(connection,""));
            save(connection,state(i,Map.of(),Map.of("council/"+a,"no"),true,now.plusSeconds(3)));
            assertEquals(Map.of(PERSONAL,"no"),ChoiceLedger.read(connection,a,"another-country",Set.of(PERSONAL)));
            assertEquals("river",scalar(connection,"SELECT nation_id FROM story_choice_log WHERE revision=2"));connection.commit();
        }
    }
    @Test void personalLosingVoteAndCollectiveResolutionRemainDistinctAndUnvotedMemberGetsNoBallot()throws Exception {
        var initial=instance("mixed-votes",Scope.WORLD,Map.of(a,"river",b,"hill",c,"river",unvoted,"hill"),2,"1");
        try(var connection=fresh()) {
            seed(connection,initial);assertThrows(IllegalStateException.class,()->ChoiceLedger.recordResolution(connection,initial,actor(a,"river"),"council","yes",now));
            ChoiceLedger.recordBallot(connection,initial,actor(b,"hill"),"council","no",now);
            var one=state(initial,Map.of(b,"no"),Map.of("council/"+b,"no"),false,now.plusSeconds(1));save(connection,one);
            ChoiceLedger.recordBallot(connection,one,actor(a,"river"),"council","yes",now.plusSeconds(1));
            var two=state(one,Map.of(a,"yes",b,"no"),Map.of("council/"+a,"yes","council/"+b,"no"),false,now.plusSeconds(2));save(connection,two);
            ChoiceLedger.recordBallot(connection,two,actor(c,"river"),"council","yes",now.plusSeconds(2));ChoiceLedger.recordResolution(connection,two,actor(c,"river"),"council","yes",now.plusSeconds(2));
            assertTrue(ChoiceLedger.read(connection,b,"hill",Set.of(PERSONAL,WORLD)).isEmpty(),"An unresolved instance is never past history");
            save(connection,state(two,Map.of(),Map.of("council/"+a,"yes","council/"+b,"no","council/"+c,"yes","council","yes"),true,now.plusSeconds(3)));
            assertEquals(Map.of(PERSONAL,"no",WORLD,"yes"),ChoiceLedger.read(connection,b,"river",Set.of(PERSONAL,WORLD)));
            assertEquals(Map.of(WORLD,"yes"),ChoiceLedger.read(connection,unvoted,"hill",Set.of(PERSONAL,WORLD)));
            assertEquals("hill",scalar(connection,"SELECT nation_id FROM story_choice_log WHERE player_id='"+b+"'"));
            assertEquals("1",scalar(connection,"SELECT revision FROM story_choice_log WHERE kind='RESOLUTION'"));
        }
    }
    @Test void worldAndNationResolutionFactsRequireTheirExactScope()throws Exception {
        try(var connection=fresh()) {
            resolve(connection,instance("river",Scope.NATION,Map.of(a,"river"),1,"1"),a,"river","yes",now);
            resolve(connection,instance("hill",Scope.NATION,Map.of(b,"hill"),1,"1"),b,"hill","no",now.plusSeconds(1));
            assertEquals(Map.of(NATION,"yes"),ChoiceLedger.read(connection,a,"river",Set.of(NATION,WORLD)));
            assertEquals(Map.of(NATION,"no"),ChoiceLedger.read(connection,b,"hill",Set.of(NATION,WORLD)));
            resolve(connection,instance("global",Scope.WORLD,Map.of(a,"river"),1,"1"),a,"river","no",now.plusSeconds(2));
            assertEquals(Map.of(NATION,"yes",WORLD,"no"),ChoiceLedger.read(connection,a,"river",Set.of(NATION,WORLD)));
            assertEquals(Map.of(WORLD,"no"),ChoiceLedger.read(connection,null,"",Set.of(PERSONAL,NATION,WORLD)));
            resolve(connection,instance("individual",Scope.PLAYER,Map.of(a,"river"),1,"1"),a,"river","yes",now.plusSeconds(3));
            assertEquals(Map.of(WORLD,"no",NATION,"yes"),ChoiceLedger.read(connection,a,"river",Set.of(NATION,WORLD)),"PLAYER resolution must not replace either shared scope");
        }
    }
    @Test void projectionsReadOnlyRequestedVersionedReferencesAndReturnAnImmutableMap()throws Exception {
        try(var connection=fresh()) {
            resolve(connection,instance("v1",Scope.WORLD,Map.of(a,"river"),1,"1"),a,"river","yes",now);
            resolve(connection,instance("v2",Scope.WORLD,Map.of(a,"river"),1,"2"),a,"river","no",now.plusSeconds(1));
            String otherVersion="past.player.choice.heir/2/council",missingVersion="past.player.choice.heir/3/council";
            Map<String,String> facts=ChoiceLedger.read(connection,a,"river",List.of(otherVersion,missingVersion,"player.role"));
            assertEquals(Map.of(otherVersion,"no"),facts);assertFalse(facts.containsKey(PERSONAL));assertThrows(UnsupportedOperationException.class,()->facts.clear());
            assertEquals(Map.of(PERSONAL,"yes"),ChoiceLedger.read(connection,a,"river",Set.of(PERSONAL)));
            assertThrows(IllegalArgumentException.class,()->ChoiceLedger.read(connection,a,"river",Set.of("past.player.choice.heir/1")));
        }
    }
    @Test void liveWritesRejectUnknownActorChangedNationStaleBeforeAndWrongNodeWithoutPartialRows()throws Exception {
        var i=instance("verified",Scope.WORLD,Map.of(a,"river"),1,"1");
        try(var connection=fresh()) {
            seed(connection,i);assertThrows(SecurityException.class,()->ChoiceLedger.recordBallot(connection,i,actor(b,"river"),"council","yes",now));
            assertThrows(SecurityException.class,()->ChoiceLedger.recordBallot(connection,i,actor(a,"hill"),"council","yes",now));
            assertThrows(IllegalStateException.class,()->ChoiceLedger.recordBallot(connection,i,actor(a,"river"),"done","yes",now));
            assertThrows(IllegalArgumentException.class,()->ChoiceLedger.recordBallot(connection,i,actor(a,"river"),"council","missing",now));
            save(connection,state(i,Map.of(),Map.of(),false,now.plusSeconds(1)));
            assertThrows(IllegalStateException.class,()->ChoiceLedger.recordBallot(connection,i,actor(a,"river"),"council","yes",now));assertEquals(0,count(connection,""));
        }
    }
    @Test void sharedResolutionIsIdempotentAndCannotBeRewritten()throws Exception {
        var i=instance("immutable-result",Scope.WORLD,Map.of(a,"river"),1,"1");
        try(var connection=fresh()) {
            seed(connection,i);assertTrue(ChoiceLedger.recordResolution(connection,i,actor(a,"river"),"council","yes",now));
            assertFalse(ChoiceLedger.recordResolution(connection,i,actor(a,"river"),"council","yes",now.plusSeconds(99)));
            assertThrows(IllegalStateException.class,()->ChoiceLedger.recordResolution(connection,i,actor(a,"river"),"council","no",now));
            assertEquals(1,count(connection,""));assertEquals(now.toString(),scalar(connection,"SELECT chosen_at FROM story_choice_log"));
        }
    }
    @Test void failedTransactionRollsBackChoiceInstanceAndLocalOutcomeTogether()throws Exception {
        var i=instance("rollback",Scope.WORLD,Map.of(a,"river"),1,"1");
        try(var connection=fresh()) {
            seed(connection,i);connection.commit();
            ChoiceLedger.recordBallot(connection,i,actor(a,"river"),"council","yes",now);ChoiceLedger.recordResolution(connection,i,actor(a,"river"),"council","yes",now);
            save(connection,state(i,Map.of(),Map.of("council/"+a,"yes","council","yes"),true,now.plusSeconds(1)));
            try(var s=connection.createStatement()){s.execute("INSERT INTO outcomes VALUES('local-result','DONE','{}')");s.execute("INSERT INTO facts VALUES('WORLD','region.city','remembered')");}
            connection.rollback();assertEquals(0,count(connection,""));assertEquals("0",scalar(connection,"SELECT completed FROM instances WHERE id='rollback'"));
            assertEquals("0",scalar(connection,"SELECT COUNT(*) FROM outcomes"));assertEquals("0",scalar(connection,"SELECT COUNT(*) FROM facts"));
            assertTrue(ChoiceLedger.read(connection,a,"river",Set.of(PERSONAL,WORLD)).isEmpty());
        }
    }
    @Test void latestCompletedProjectionUsesLogSequenceNotClockOrCompletionTimestamp()throws Exception {
        try(var connection=fresh()) {
            resolve(connection,instance("logged-first",Scope.WORLD,Map.of(a,"river"),1,"1"),a,"river","yes",now.plusSeconds(100));
            resolve(connection,instance("logged-later",Scope.WORLD,Map.of(a,"river"),1,"1"),a,"river","no",now.minusSeconds(100));
            var active=instance("not-yet-completed",Scope.WORLD,Map.of(a,"river"),1,"1");seed(connection,active);
            ChoiceLedger.recordBallot(connection,active,actor(a,"river"),"council","yes",now.plusSeconds(200));ChoiceLedger.recordResolution(connection,active,actor(a,"river"),"council","yes",now.plusSeconds(200));
            assertEquals(Map.of(PERSONAL,"no",WORLD,"no"),ChoiceLedger.read(connection,a,"river",Set.of(PERSONAL,WORLD)));
            save(connection,state(active,Map.of(),Map.of("council/"+a,"yes","council","yes"),true,now));
            assertEquals(Map.of(PERSONAL,"yes",WORLD,"yes"),ChoiceLedger.read(connection,a,"river",Set.of(PERSONAL,WORLD)));
        }
    }
    @Test void literalV2SnapshotsMigrateWithUnknownTimesAndRemainIdempotentAcrossTwoOpens()throws Exception {
        var old=instance("older",Scope.WORLD,Map.of(a,"river"),1,"1");var newer=instance("newer",Scope.WORLD,Map.of(b,"hill"),1,"1");var active=instance("active",Scope.WORLD,Map.of(a,"river"),1,"1");
        String original;
        try(var connection=open()) {
            createV2(connection);connection.setAutoCommit(false);
            seed(connection,state(old,Map.of(),Map.of("council/"+a,"yes","council","yes"),true,now));
            seed(connection,state(newer,Map.of(),Map.of("council/"+b,"no","council","no"),true,now.plusSeconds(1)));
            seed(connection,state(active,Map.of(a,"no"),Map.of("council/"+a,"no"),false,now.plusSeconds(2)));
            original=scalar(connection,"SELECT json FROM instances WHERE id='older'");connection.commit();
        }
        try(var connection=open()) {
            connection.setAutoCommit(false);assertEquals("2",scalar(connection,"PRAGMA user_version"));ChoiceLedger.createSchema(connection);
            assertEquals(5,ChoiceLedger.migrateSnapshots(connection));assertEquals(0,count(connection," WHERE provenance<>'LEGACY_INSTANCE' OR chosen_at IS NOT NULL OR committed_by IS NOT NULL"));
            assertEquals(original,scalar(connection,"SELECT json FROM instances WHERE id='older'"));
            assertEquals("older,older,newer,newer,active",scalar(connection,"SELECT group_concat(instance_id,',') FROM (SELECT instance_id FROM story_choice_log ORDER BY seq)"));
            assertEquals(Map.of(PERSONAL,"yes",WORLD,"no"),ChoiceLedger.read(connection,a,"river",Set.of(PERSONAL,WORLD)));
            try(var s=connection.createStatement()){s.execute("PRAGMA user_version=3");}connection.commit();
        }
        try(var connection=open()) {
            connection.setAutoCommit(false);ChoiceLedger.createSchema(connection);assertEquals(0,ChoiceLedger.migrateSnapshots(connection));assertEquals(5,count(connection,""));
            resolve(connection,instance("new-live",Scope.WORLD,Map.of(a,"river"),1,"1"),a,"river","no",now.plusSeconds(3));
            assertEquals(0,ChoiceLedger.migrateSnapshots(connection));assertEquals(7,count(connection,""));assertEquals(5,count(connection," WHERE provenance='LEGACY_INSTANCE'"));
            assertEquals(Map.of(PERSONAL,"no",WORLD,"no"),ChoiceLedger.read(connection,a,"river",Set.of(PERSONAL,WORLD)));connection.commit();
        }
    }
    @Test void migrationFailureRollsBackSchemaAndRowsAndRetryPreservesDeterministicIds()throws Exception {
        var old=instance("migration-atomic",Scope.WORLD,Map.of(a,"river"),1,"1");
        try(var connection=open()) {
            createV2(connection);connection.setAutoCommit(false);seed(connection,state(old,Map.of(),Map.of("council/"+a,"yes","council","yes"),true,now));connection.commit();
            ChoiceLedger.createSchema(connection);
            try(var s=connection.createStatement()){s.execute("CREATE TRIGGER stop_legacy_ballot BEFORE INSERT ON story_choice_log WHEN NEW.kind='BALLOT' BEGIN SELECT RAISE(ABORT,'simulated migration failure'); END");}
            assertThrows(SQLException.class,()->ChoiceLedger.migrateSnapshots(connection));connection.rollback();
            assertEquals("0",scalar(connection,"SELECT COUNT(*) FROM sqlite_master WHERE name='story_choice_log'"));assertEquals("2",scalar(connection,"PRAGMA user_version"));
            ChoiceLedger.createSchema(connection);assertEquals(2,ChoiceLedger.migrateSnapshots(connection));
            assertEquals(RuleEngine.stable("legacy-choice",old.id(),"council","BALLOT","player:"+a),scalar(connection,"SELECT id FROM story_choice_log WHERE kind='BALLOT'"));
            assertEquals(0,ChoiceLedger.migrateSnapshots(connection));assertEquals(2,count(connection,""));connection.commit();
        }
    }
    @Test void choiceRowsRequireBothStoredInstanceAndOriginalSourceEvidence()throws Exception {
        var i=instance("foreign-key",Scope.WORLD,Map.of(a,"river"),1,"1");
        try(var connection=fresh()) {
            assertThrows(IllegalStateException.class,()->ChoiceLedger.recordBallot(connection,i,actor(a,"river"),"council","yes",now));
            seed(connection,i);try(var s=connection.createStatement()){s.execute("DELETE FROM events WHERE id='source:foreign-key'");}
            assertThrows(SQLException.class,()->ChoiceLedger.recordBallot(connection,i,actor(a,"river"),"council","yes",now));assertEquals(0,count(connection,""));
        }
    }
}
