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

class InstanceCapacityTest {
    @TempDir Path directory;
    final UUID a=UUID.fromString("00000000-0000-0000-0000-000000000001"),b=UUID.fromString("00000000-0000-0000-0000-000000000002");
    final Instant now=Instant.parse("2026-11-25T12:00:00Z");
    Path database(){return directory.resolve("capacity.db");}
    PlayerContext context(UUID player,String nation){return new PlayerContext(player,nation,Map.of(),Set.of("starchronicle.use"));}
    StoryDefinition definition(Scope scope,Condition condition,String permission) {
        var vote=new Node("Vote",null,null,2,Map.of("yes",new Choice("Yes","end",null,permission,List.of())),List.of(),false);
        return new StoryDefinition("story","1","Story",scope,List.of("ROOT"),condition,"vote",0,Map.of("vote",vote,"end",new Node("End",null,null,1,Map.of(),List.of(),true)));
    }
    StoryInstance instance(String id,StoryDefinition definition,String scope,Map<UUID,String> members,boolean complete,Instant updated) {
        return new StoryInstance(id,definition,scope,"source","vote",members,Map.of(),Map.of(),now,updated,complete);
    }
    void put(Connection c,StoryInstance i)throws SQLException {
        try(var s=c.prepareStatement("INSERT INTO instances VALUES(?,?,?,?,?,?)")){s.setString(1,i.id());s.setString(2,i.definition().id());s.setString(3,i.scopeKey());s.setInt(4,i.completed()?1:0);s.setString(5,i.updatedAt().toString());s.setString(6,SqliteStore.json(i));s.executeUpdate();}
    }
    void replace(Connection c,StoryInstance i)throws SQLException {
        try(var s=c.prepareStatement("UPDATE instances SET json=? WHERE id=?")){s.setString(1,SqliteStore.json(i));s.setString(2,i.id());s.executeUpdate();}
    }
    void source(RuleEngine engine){engine.accept(new WorldEvent("source","ROOT",now,Set.of(a,b),Set.of("river","hill"),Map.of(),"CapacityTest","Original source"),List.of());}
    RuleEngine engine(SqliteStore store){return new RuleEngine(store,List.of(),Clock.fixed(now,ZoneOffset.UTC));}
    List<String> ids(List<StoryInstance> rows){return rows.stream().map(StoryInstance::id).toList();}
    String scalar(SqliteStore store,String sql){return store.transaction(c->{try(var s=c.createStatement();var r=s.executeQuery(sql)){r.next();return r.getString(1);}});}

    @Test void playerQueriesNeverDecodeUnrelatedPrivateGraphsAndKeepCompletedMembership() {
        try(var store=new SqliteStore(database())) {
            var engine=engine(store);source(engine);
            store.transaction(c->{
                put(c,instance("own",definition(Scope.PLAYER,null,null),"player:"+a,Map.of(a,"river"),true,now));
                put(c,instance("foreign",definition(Scope.PLAYER,null,null),"player:"+b,Map.of(b,"hill"),false,now));
                try(var s=c.createStatement()){s.execute("UPDATE instances SET json=json_set(json,'$.definition.nodes','corrupt-unrelated-graph') WHERE id='foreign'");}
                return null;
            });
            assertEquals(List.of("own"),ids(engine.instances(a)));assertEquals(List.of("own"),ids(engine.instances(a,0,10)));
            assertEquals(List.of("own"),ids(engine.storyPage(context(a,"river"),null,10).instances()));
            assertTrue(engine.availableInstances(context(a,"river")).isEmpty());
            assertTrue(engine.instance(b,"own").isEmpty());assertTrue(engine.instance(a,"own").orElseThrow().completed());
            assertDoesNotThrow(()->engine.observeMembers(List.of(context(a,"changed"))));
            assertThrows(IllegalStateException.class,engine::activeInstances,"The unrelated JSON must really be undecodable");
        }
    }

    @Test void membershipProjectionTracksUpdatesRollbackAndRestartWithoutChangingSnapshotBytes() {
        String original;
        try(var store=new SqliteStore(database())) {
            var engine=engine(store);source(engine);var d=definition(Scope.WORLD,null,null);
            var initial=instance("shared",d,"world",Map.of(a,"river"),false,now);
            store.transaction(c->{put(c,initial);return null;});original=SqliteStore.json(initial);
            var joined=instance("shared",d,"world",Map.of(a,"river",b,"hill"),false,now);
            assertThrows(IllegalStateException.class,()->store.transaction(c->{replace(c,joined);throw new IllegalStateException("transaction aborted");}));
            assertTrue(engine.instances(b).isEmpty());assertEquals(original,scalar(store,"SELECT json FROM instances"));
            store.transaction(c->{replace(c,joined);return null;});assertEquals(List.of("shared"),ids(engine.instances(b)));
            store.transaction(c->{replace(c,initial);return null;});assertTrue(engine.instances(b).isEmpty());
            assertEquals("1",scalar(store,"SELECT COUNT(*) FROM instance_members"));
        }
        try(var store=new SqliteStore(database())) {
            var engine=engine(store);assertEquals(List.of("shared"),ids(engine.instances(a)));assertTrue(engine.instances(b).isEmpty());
            assertEquals(original,scalar(store,"SELECT json FROM instances"));
            assertEquals("1",scalar(store,"PRAGMA temp_store"));
        }
    }

    @Test void projectionWriteFailureRollsBackTheSourceSnapshotToo() {
        try(var store=new SqliteStore(database())) {
            var engine=engine(store);source(engine);
            store.transaction(c->{try(var s=c.createStatement()){s.execute("CREATE TRIGGER deny_projection BEFORE INSERT ON instance_members BEGIN SELECT RAISE(ABORT,'projection unavailable'); END");}return null;});
            assertThrows(IllegalStateException.class,()->store.transaction(c->{put(c,instance("new",definition(Scope.PLAYER,null,null),"player:"+a,Map.of(a,"river"),false,now));return null;}));
            assertEquals("0",scalar(store,"SELECT COUNT(*) FROM instances"));assertEquals("0",scalar(store,"SELECT COUNT(*) FROM instance_members"));
        }
    }

    @Test void filteredPagesHaveContinuationAfterCandidateBudgetAndNeverSkipTheNextVisibleItem() {
        try(var store=new SqliteStore(database())) {
            var engine=engine(store);source(engine);
            StoryDefinition hidden=definition(Scope.WORLD,new Condition(null,null,null,"access","eq","yes"),null),visible=definition(Scope.WORLD,null,null);
            store.transaction(c->{
                for(int n=0;n<270;n++)put(c,instance(String.format(Locale.ROOT,"%04d",n),hidden,"world",Map.of(),false,now));
                for(int n=0;n<3;n++)put(c,instance("z"+n,visible,"world",Map.of(),false,now));
                put(c,instance("private",definition(Scope.PLAYER,null,null),"player:"+b,Map.of(b,"hill"),false,now));
                put(c,instance("completed",visible,"world",Map.of(b,"hill"),true,now));
                return null;
            });
            var first=engine.storyPage(context(a,"river"),null,2);assertTrue(first.instances().isEmpty());assertEquals("0255",first.nextCursor());
            var second=engine.storyPage(context(a,"river"),first.nextCursor(),2);assertEquals(List.of("z0","z1"),ids(second.instances()));assertEquals("z1",second.nextCursor());
            var third=engine.storyPage(context(a,"river"),second.nextCursor(),2);assertEquals(List.of("z2"),ids(third.instances()));assertNull(third.nextCursor());
            assertEquals(List.of("z0","z1","z2"),ids(engine.availableInstances(context(a,"river"))));
        }
    }

    @Test void memberHistoryPaginationIsCompleteAndNationVisibilityUsesCurrentContext() {
        try(var store=new SqliteStore(database())) {
            var engine=engine(store);source(engine);
            store.transaction(c->{
                for(int n=0;n<11;n++)put(c,instance("own-"+n,definition(Scope.NATION,null,null),"nation:river",Map.of(a,"river"),n%2==0,now.plusSeconds(n)));
                put(c,instance("hill",definition(Scope.NATION,null,null),"nation:hill",Map.of(b,"hill"),false,now));return null;
            });
            List<StoryInstance> pages=new ArrayList<>();for(int page=0;page<4;page++)pages.addAll(engine.instances(a,page,3));
            assertEquals(engine.instances(a),pages);assertTrue(engine.instances(a,4,3).isEmpty());
            assertFalse(ids(engine.availableInstances(context(a,"river"))).contains("hill"));
            assertTrue(ids(engine.availableInstances(context(a,"hill"))).contains("hill"));
            assertTrue(ids(engine.availableInstances(context(a,"hill"))).contains("own-1"),"Original membership remains visible for review, without granting authority");
            assertThrows(IllegalArgumentException.class,()->engine.instances(a,-1,3));assertThrows(IllegalArgumentException.class,()->engine.instances(a,0,101));
            assertThrows(IllegalArgumentException.class,()->engine.storyPage(context(a,"river")," ",10));assertThrows(IllegalArgumentException.class,()->engine.storyPage(context(a,"river"),null,0));
        }
    }

    @Test void missingProjectionTriggerIsRebuiltFromSnapshotsOnReopen() {
        try(var store=new SqliteStore(database())) {
            source(engine(store));var d=definition(Scope.WORLD,null,null);
            store.transaction(c->{put(c,instance("saved",d,"world",Map.of(a,"river"),false,now));
                try(var s=c.createStatement()){s.execute("DROP TRIGGER instance_members_update");}
                replace(c,instance("saved",d,"world",Map.of(b,"hill"),false,now));return null;});
        }
        try(var store=new SqliteStore(database())) {
            var engine=engine(store);assertTrue(engine.instances(a).isEmpty());assertEquals(List.of("saved"),ids(engine.instances(b)));
            assertEquals("1",scalar(store,"SELECT COUNT(*) FROM sqlite_master WHERE name='instance_members_update'"));
        }
    }

    @Test void permissionsIncludeCurrentPendingAndActiveDefinitionsWithoutCompletedRetiredPermissions() {
        try(var store=new SqliteStore(database())) {
            var engine=engine(store);source(engine);StoryDefinition active=definition(Scope.WORLD,null,"active.permission"),pending=definition(Scope.WORLD,null,"pending.permission");
            store.transaction(c->{put(c,instance("active",active,"world",Map.of(a,"river"),false,now));put(c,instance("done",definition(Scope.WORLD,null,"retired.permission"),"world",Map.of(a,"river"),true,now));
                try(var s=c.prepareStatement("INSERT INTO story_triggers VALUES('pending','key','source','players:*',?,0)")){s.setString(1,SqliteStore.json(pending));s.executeUpdate();}return null;});
            assertEquals(Set.of("configured.permission","active.permission","pending.permission"),engine.permissionNodes(List.of(definition(Scope.PLAYER,null,"configured.permission"))));
            assertEquals("1",scalar(store,"SELECT COUNT(*) FROM story_triggers"));
        }
    }

    @Test void failedProjectionRepairKeepsOriginalRowsAndMissingTriggerForASafeRetry()throws Exception {
        String damaged;
        try(var store=new SqliteStore(database())) {
            source(engine(store));var d=definition(Scope.WORLD,null,null);
            store.transaction(c->{put(c,instance("a-good",d,"world",Map.of(a,"river"),false,now));put(c,instance("z-bad",d,"world",Map.of(b,"hill"),false,now));
                try(var s=c.createStatement()){s.execute("DROP TRIGGER instance_members_update");s.execute("UPDATE instances SET scope='forged' WHERE id='z-bad'");}return null;});
            damaged=scalar(store,"SELECT json FROM instances WHERE id='z-bad'");
        }
        assertThrows(IllegalStateException.class,()->new SqliteStore(database()));
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+database());var s=c.createStatement()) {
            try(var r=s.executeQuery("SELECT COUNT(*) FROM instance_members")){r.next();assertEquals(2,r.getInt(1));}
            try(var r=s.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE name='instance_members_update'")){r.next();assertEquals(0,r.getInt(1));}
            try(var r=s.executeQuery("SELECT json FROM instances WHERE id='z-bad'")){r.next();assertEquals(damaged,r.getString(1));}
            s.execute("UPDATE instances SET scope='world' WHERE id='z-bad'");
        }
        try(var store=new SqliteStore(database())){assertEquals(List.of("z-bad"),ids(engine(store).instances(b)));}
    }

    @Test void unconditionalSharedPagesStillRequireTheirOriginalEvidence() {
        try(var store=new SqliteStore(database())) {
            var engine=engine(store);
            store.transaction(c->{put(c,instance("orphan",definition(Scope.WORLD,null,null),"world",Map.of(b,"hill"),false,now));return null;});
            assertThrows(IllegalStateException.class,()->engine.storyPage(context(a,"river"),null,10));
            assertThrows(IllegalStateException.class,()->engine.availableInstances(context(a,"river")));
        }
    }
}
