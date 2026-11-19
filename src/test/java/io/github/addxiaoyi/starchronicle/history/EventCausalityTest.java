package io.github.addxiaoyi.starchronicle.history;

import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** File-backed provenance tests use the same transactions and foreign keys as production. */
class EventCausalityTest {
    @TempDir Path directory;
    private Path database(){return directory.resolve("causes.db");}
    private static StoryDefinition story(){return new StoryDefinition("chapter-a","1","A",StoryDefinition.Scope.WORLD,List.of("RESULT"),null,"end",0,Map.of("end",new StoryDefinition.Node("Done",null,null,1,Map.of(),List.of(),true)));}
    private static void event(Connection c,String id)throws SQLException{
        WorldEvent event=new WorldEvent(id,"TEST",Instant.parse("2026-11-20T00:00:00Z"),Set.of(),Set.of(),Map.of(),"EventCausalityTest","committed source="+id);
        try(var s=c.prepareStatement("INSERT INTO events(id,type,time,hash,json) VALUES(?,?,?,?,?)")){
            s.setString(1,id);s.setString(2,event.type());s.setString(3,event.occurredAt().toString());s.setString(4,"fixture-"+id);s.setString(5,SqliteStore.json(event));s.executeUpdate();
        }
    }
    private static long count(Connection c,String table)throws SQLException{try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM "+table)){r.next();return r.getLong(1);}}

    @Test void untracedLegacySourceIsAnHonestRootAndReadingDoesNotInventRows(){
        try(var store=new SqliteStore(database())){
            store.transaction(c->{event(c,"legacy");var trace=EventCausality.read(c,"legacy");assertEquals(EventCausality.root("legacy"),trace);assertNull(trace.parentId());assertEquals(0,trace.depth());assertTrue(trace.stories().isEmpty());assertEquals(0,count(c,"event_causes"));return null;});
        }
        try(var store=new SqliteStore(database())){store.transaction(c->{assertEquals(EventCausality.root("legacy"),EventCausality.read(c,"legacy"));assertEquals(0,count(c,"event_causes"));return null;});}
    }

    @Test void rootAndDerivedTraceAreIdempotentDetachedAndSurviveReopen(){
        EventCausality.Trace expected=new EventCausality.Trace("root","root",1,List.of("chapter-a"));
        try(var store=new SqliteStore(database())){
            store.transaction(c->{event(c,"root");event(c,"child");EventCausality.record(c,"root",EventCausality.root("root"));EventCausality.record(c,"root",EventCausality.root("root"));
                var child=EventCausality.derive(c,"root","chapter-a");assertEquals(expected,child);EventCausality.record(c,"child",child);EventCausality.record(c,"child",child);assertEquals(2,count(c,"event_causes"));assertThrows(UnsupportedOperationException.class,()->child.stories().add("forged"));return null;});
        }
        try(var store=new SqliteStore(database())){store.transaction(c->{assertEquals(EventCausality.root("root"),EventCausality.read(c,"root"));assertEquals(expected,EventCausality.read(c,"child"));return null;});}
    }

    @Test void legacyParentCanProduceTheFirstKnownDerivedTraceWithoutInventingEarlierAncestry(){
        try(var store=new SqliteStore(database())){store.transaction(c->{event(c,"legacy");event(c,"child");var derived=EventCausality.derive(c,"legacy","chapter-a");EventCausality.record(c,"child",derived);assertEquals(new EventCausality.Trace("legacy","legacy",1,List.of("chapter-a")),EventCausality.read(c,"child"));assertEquals(1,count(c,"event_causes"));return null;});}
    }

    @Test void eventParentRootAndBlockReferencesUseRealForeignKeys(){
        try(var store=new SqliteStore(database())){store.transaction(c->{event(c,"root");event(c,"child");EventCausality.record(c,"root",EventCausality.root("root"));
            assertThrows(SQLException.class,()->EventCausality.record(c,"missing-event",EventCausality.root("root")));
            assertThrows(SQLException.class,()->EventCausality.record(c,"child",new EventCausality.Trace("missing-parent","root",1,List.of("chapter-a"))));
            assertThrows(SQLException.class,()->EventCausality.record(c,"child",new EventCausality.Trace("root","missing-root",1,List.of("chapter-a"))));
            assertThrows(SQLException.class,()->EventCausality.block(c,"missing-event",story(),"REPEATED_STORY_IN_CAUSAL_CHAIN"));
            assertEquals(1,count(c,"event_causes"));assertEquals(0,count(c,"story_trigger_blocks"));return null;});}
    }

    @Test void sameEventIdCannotAcquireDifferentCausalEvidence(){
        try(var store=new SqliteStore(database())){store.transaction(c->{event(c,"root");event(c,"child");event(c,"other-parent");var trace=EventCausality.derive(c,"root","chapter-a");EventCausality.record(c,"child",trace);
            var wrongParent=new EventCausality.Trace("other-parent","root",1,List.of("chapter-a"));
            var wrongStory=new EventCausality.Trace("root","root",1,List.of("chapter-b"));
            for(var conflicting:List.of(EventCausality.root("child"),wrongParent,wrongStory)){
                var failure=assertThrows(IllegalArgumentException.class,()->EventCausality.record(c,"child",conflicting));assertTrue(failure.getMessage().contains("Causal event ID collision"));assertEquals(trace,EventCausality.read(c,"child"));
            }
            assertEquals(1,count(c,"event_causes"));return null;});}
    }

    @Test void aFailedOuterTransactionRollsBackHistoryTraceAndDiagnosticTogether(){
        try(var store=new SqliteStore(database())){
            store.transaction(c->{event(c,"root");EventCausality.record(c,"root",EventCausality.root("root"));return null;});
            assertThrows(IllegalStateException.class,()->store.transaction(c->{event(c,"child");EventCausality.record(c,"child",EventCausality.derive(c,"root","chapter-a"));EventCausality.block(c,"child",story(),"REPEATED_STORY_IN_CAUSAL_CHAIN");throw new IllegalStateException("abort enclosing story transition");}));
            store.transaction(c->{assertEquals(1,count(c,"events"));assertEquals(1,count(c,"event_causes"));assertEquals(0,count(c,"story_trigger_blocks"));assertThrows(IllegalArgumentException.class,()->EventCausality.read(c,"child"));return null;});
        }
        try(var store=new SqliteStore(database())){store.transaction(c->{assertEquals(1,count(c,"events"));assertTrue(EventCausality.blocks(c,10).isEmpty());return null;});}
    }

    @Test void blockingFollowUpKeepsTheTrueHistoryAndProducesOneDurableDiagnostic(){
        try(var store=new SqliteStore(database())){store.transaction(c->{event(c,"root");event(c,"result");var trace=EventCausality.derive(c,"root","chapter-a");EventCausality.record(c,"result",trace);
            String reason=EventCausality.blockedReason(trace,story().id());assertEquals("REPEATED_STORY_IN_CAUSAL_CHAIN",reason);
            EventCausality.block(c,"result",story(),reason);EventCausality.block(c,"result",story(),reason);
            assertEquals(2,count(c,"events"));assertEquals(trace,EventCausality.read(c,"result"));assertEquals(List.of(new EventCausality.Block("result","chapter-a","1",reason)),EventCausality.blocks(c,10));return null;});}
        try(var store=new SqliteStore(database())){store.transaction(c->{var blocks=EventCausality.blocks(c,1);assertEquals(1,blocks.size());assertThrows(UnsupportedOperationException.class,()->blocks.clear());assertEquals(2,count(c,"events"));assertThrows(IllegalArgumentException.class,()->EventCausality.blocks(c,0));assertThrows(IllegalArgumentException.class,()->EventCausality.blocks(c,1001));return null;});}
    }

    @Test void missingSourcesAndInvalidTraceShapesFailClosed(){
        try(var store=new SqliteStore(database())){store.transaction(c->{assertThrows(IllegalArgumentException.class,()->EventCausality.read(c,"missing"));assertThrows(IllegalArgumentException.class,()->EventCausality.derive(c,"missing","chapter-a"));return null;});}
        assertThrows(IllegalArgumentException.class,()->new EventCausality.Trace(null,"root",1,List.of("chapter-a")));
        assertThrows(IllegalArgumentException.class,()->new EventCausality.Trace("root","root",0,List.of()));
        assertThrows(IllegalArgumentException.class,()->new EventCausality.Trace("root","root",2,List.of("chapter-a")));
        assertThrows(IllegalArgumentException.class,()->new EventCausality.Trace("root","root",1,List.of("invalid/story")));
    }

    @Test void maximumDepthAndRepeatedStoryAreExplicitlyDifferentBlocks(){
        assertEquals(32,EventCausality.MAX_DEPTH);
        List<String> path=new ArrayList<>();for(int i=0;i<EventCausality.MAX_DEPTH;i++)path.add("chapter-"+i);
        var full=new EventCausality.Trace("parent","root",path.size(),path);assertEquals("MAX_CAUSAL_DEPTH",EventCausality.blockedReason(full,"new-chapter"));
        var shorter=new EventCausality.Trace("parent","root",1,List.of("chapter-a"));assertEquals("REPEATED_STORY_IN_CAUSAL_CHAIN",EventCausality.blockedReason(shorter,"chapter-a"));assertNull(EventCausality.blockedReason(shorter,"chapter-b"));assertNull(EventCausality.blockedReason(EventCausality.root("root"),"chapter-a"));
    }

    @Test void storageApisRequireTheCallersExistingTransaction()throws Exception{
        Class.forName("org.sqlite.JDBC");
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("autocommit.db"))){
            assertTrue(c.getAutoCommit());assertThrows(IllegalStateException.class,()->EventCausality.createSchema(c));assertThrows(IllegalStateException.class,()->EventCausality.read(c,"root"));assertThrows(IllegalStateException.class,()->EventCausality.derive(c,"root","chapter-a"));assertThrows(IllegalStateException.class,()->EventCausality.record(c,"root",EventCausality.root("root")));assertThrows(IllegalStateException.class,()->EventCausality.block(c,"root",story(),"test"));assertThrows(IllegalStateException.class,()->EventCausality.blocks(c,10));
        }
    }
}
