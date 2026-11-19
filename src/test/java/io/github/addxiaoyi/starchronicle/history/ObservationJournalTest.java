package io.github.addxiaoyi.starchronicle.history;

import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import io.github.addxiaoyi.starchronicle.core.RuleEngine;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import io.github.addxiaoyi.starchronicle.history.ObservationSnapshot.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real file-backed SQLite tests: reopen the database at each relevant crash boundary. */
class ObservationJournalTest {
    @TempDir Path directory;
    final Clock clock=Clock.fixed(Instant.parse("2026-11-19T15:01:02.123456789Z"),ZoneOffset.UTC);
    Path database(){return directory.resolve("history.db");}
    ObservationSnapshot nations(String...ids){Map<String,Entry> entries=new HashMap<>();for(String id:ids)entries.put(id,new Entry(id,"PRESENT",Map.of()));return snapshot(Kind.NATIONS,entries,true,true);}
    ObservationSnapshot capitals(String capital){return snapshot(Kind.CAPITALS,Map.of("nation-a",new Entry("nation-a",capital,Map.of())),true,true);}
    ObservationSnapshot technologies(boolean unlocked){return snapshot(Kind.TECHNOLOGIES,unlocked?Map.of("nation-a/steam",new Entry("nation-a","UNLOCKED",Map.of("technology","steam","tech.stage","2"))):Map.of(),true,true);}
    ObservationSnapshot snapshot(Kind kind,Map<String,Entry> entries,boolean complete,boolean allowEmpty){return new ObservationSnapshot("test."+kind.name().toLowerCase(Locale.ROOT),"Trusted test committed authority",kind,complete,allowEmpty,entries,"Detached complete authority scope; no exact mutation timestamp");}

    @Test void firstObservationIsOnlyBaselineAndSurvivesRestart() {
        try(var store=new SqliteStore(database())) {
            var journal=new ObservationJournal(store,clock);var result=journal.observe(nations("a","b"));
            assertTrue(result.baselineCreated());assertEquals(0,result.queued());assertEquals(0,journal.pendingCount());
        }
        try(var store=new SqliteStore(database())) {
            var journal=new ObservationJournal(store,clock);var unchanged=journal.observe(nations("a","b"));
            assertFalse(unchanged.baselineCreated());assertEquals(0,unchanged.sequence());assertTrue(journal.pending(10).isEmpty());
        }
    }
    @Test void crashAfterObservationCommitBeforeDeliveryRetainsOriginalIngressRecord() {
        WorldEvent queued;
        try(var store=new SqliteStore(database())) {
            var journal=new ObservationJournal(store,clock);journal.observe(nations("a","b"));journal.observe(nations("a"));queued=journal.pending(10).getFirst();
        }
        try(var store=new SqliteStore(database())) {
            var journal=new ObservationJournal(store,Clock.offset(clock,Duration.ofDays(1)));
            assertEquals(queued,journal.pending(10).getFirst());assertEquals(1,journal.pendingCount());
            assertEquals(0,journal.observe(nations("a")).queued());assertEquals(1,journal.pendingCount());
        }
    }
    @Test void crashAfterEngineCommitBeforeAckRedeliversWithoutDuplicateHistory() {
        WorldEvent event;
        try(var store=new SqliteStore(database())) {
            var journal=new ObservationJournal(store,clock);journal.observe(nations("a","b"));journal.observe(nations("a"));event=journal.pending(10).getFirst();
            var engine=new RuleEngine(store,List.of(),clock);assertFalse(engine.accept(event,List.of()).duplicate());assertEquals(1,engine.history(null,null,0,10).size());
            // Simulate process death: no acknowledge despite the successful consumer commit.
        }
        try(var store=new SqliteStore(database())) {
            var journal=new ObservationJournal(store,clock);var engine=new RuleEngine(store,List.of(),clock);
            assertEquals(event,journal.pending(10).getFirst());assertTrue(engine.accept(event,List.of()).duplicate());
            assertTrue(journal.acknowledge(event.id()));assertEquals(0,journal.pendingCount());assertEquals(1,engine.history(null,null,0,10).size());
        }
    }
    @Test void acknowledgedIngressDoesNotReturnAfterRestart() {
        String id;
        try(var store=new SqliteStore(database())) {
            var journal=new ObservationJournal(store,clock);journal.observe(nations("a","b"));journal.observe(nations("a"));id=journal.pending(10).getFirst().id();
            assertTrue(journal.acknowledge(id));assertFalse(journal.acknowledge(id));
        }
        try(var store=new SqliteStore(database())) {var journal=new ObservationJournal(store,clock);assertEquals(0,journal.pendingCount());assertFalse(journal.acknowledge(id));}
    }
    @Test void failureBeforeWriteCommitRollsBackBothBaselineAndIngress() {
        try(var store=new SqliteStore(database())) {
            var journal=new ObservationJournal(store,clock);journal.observe(nations("a","b"));
            var crashing=new ObservationJournal(store,clock,()->{throw new IllegalStateException("simulated process failure before commit");});
            assertThrows(IllegalStateException.class,()->crashing.observe(nations("a")));assertEquals(0,journal.pendingCount());
        }
        try(var store=new SqliteStore(database())) {
            var journal=new ObservationJournal(store,clock);var result=journal.observe(nations("a"));
            assertEquals(1,result.sequence());assertEquals(1,result.queued());assertEquals("NATION_DESTROYED",journal.pending(10).getFirst().type());
        }
    }
    @Test void multiEventBatchIsAtomicWhenTransactionFails() {
        try(var store=new SqliteStore(database())) {
            var journal=new ObservationJournal(store,clock);journal.observe(nations("a","b","c"));
            var crashing=new ObservationJournal(store,clock,()->{throw new AssertionError("power loss boundary");});
            assertThrows(AssertionError.class,()->crashing.observe(nations("a")));assertEquals(0,journal.pendingCount());
            assertEquals(2,journal.observe(nations("a")).queued());assertEquals(2,journal.pendingCount());
        }
    }
    @Test void repeatedIdenticalScansNeitherAdvanceSequenceNorDuplicateEvents() {
        try(var store=new SqliteStore(database())) {
            var journal=new ObservationJournal(store,clock);journal.observe(nations("a","b"));journal.observe(nations("a"));
            for(int i=0;i<10;i++){var unchanged=journal.observe(nations("a"));assertEquals(1,unchanged.sequence());assertEquals(0,unchanged.queued());}
            assertEquals(1,journal.pendingCount());
        }
    }
    @Test void capitalABABUsesDistinctDurableSequenceIdsEvenWithSameObservedTime() {
        try(var store=new SqliteStore(database())) {
            var journal=new ObservationJournal(store,clock);journal.observe(capitals("city-a"));journal.observe(capitals("city-b"));journal.observe(capitals("city-a"));journal.observe(capitals("city-b"));
            var events=journal.pending(10);assertEquals(3,events.size());assertEquals(3,events.stream().map(WorldEvent::id).distinct().count());
            assertTrue(events.stream().allMatch(e->e.type().equals("CAPITAL_MOVED")));assertEquals(List.of("1","2","3"),events.stream().map(e->e.facts().get("observation.sequence")).toList());
        }
        try(var store=new SqliteStore(database())) {var journal=new ObservationJournal(store,clock);assertEquals(3,journal.pendingCount());assertEquals(0,journal.observe(capitals("city-b")).queued());}
    }
    @Test void technologyUnlockWorksWithoutEventPublisherAndReunlockGetsNewId() {
        try(var store=new SqliteStore(database())) {
            var journal=new ObservationJournal(store,clock);journal.observe(technologies(false));
            assertEquals(1,journal.observe(technologies(true)).queued());assertEquals(0,journal.observe(technologies(false)).queued());assertEquals(1,journal.observe(technologies(true)).queued());
            var events=journal.pending(10);assertEquals(2,events.size());assertNotEquals(events.get(0).id(),events.get(1).id());
            assertTrue(events.stream().allMatch(e->e.type().equals("TECHNOLOGY_BREAKTHROUGH")));assertEquals("2",events.getFirst().facts().get("tech.stage"));
            var engine=new RuleEngine(store,List.of(),clock);for(var event:events){engine.accept(event,List.of());journal.acknowledge(event.id());}
            assertEquals(2,engine.history("nation-a",null,0,10).size());assertEquals(0,journal.pendingCount());
        }
    }
    @Test void incompleteEmptyReadCannotEraseBaselineOrInventNationDestruction() {
        try(var store=new SqliteStore(database())) {
            var journal=new ObservationJournal(store,clock);journal.observe(nations("a"));
            assertFalse(journal.observe(snapshot(Kind.NATIONS,Map.of(),false,false)).accepted());assertEquals(0,journal.pendingCount());
            assertEquals(0,journal.observe(nations("a")).queued());assertEquals(1,journal.observe(nations()).queued());
        }
    }
    @Test void emptyScanRequiresExplicitCompletenessAssertion() {
        try(var store=new SqliteStore(database())) {
            var journal=new ObservationJournal(store,clock);journal.observe(nations("a"));
            assertFalse(journal.observe(snapshot(Kind.NATIONS,Map.of(),true,false)).accepted());assertEquals(0,journal.pendingCount());
            assertEquals(0,journal.observe(nations("a")).queued());
        }
    }
    @Test void confirmedEmptyCanObserveLastNationDisappearanceWithoutGuessingActorOrCause() {
        try(var store=new SqliteStore(database())) {
            var journal=new ObservationJournal(store,clock);journal.observe(nations("a"));assertEquals(1,journal.observe(nations()).queued());
            WorldEvent event=journal.pending(10).getFirst();assertEquals(Set.of("a"),event.nations());assertTrue(event.participants().isEmpty());
            assertEquals("OBSERVED",event.facts().get("time_basis"));assertEquals("PRESENT",event.facts().get("before"));assertEquals("<absent>",event.facts().get("after"));
            assertEquals(Instant.parse("2026-11-19T15:01:02.123Z"),event.occurredAt());assertTrue(event.evidence().contains("exact mutation time and actor unknown"));
            assertFalse(event.facts().containsKey("cause"));
        }
    }
    @Test void namespaceCannotSilentlySwitchAuthorityOrSnapshotKind() {
        try(var store=new SqliteStore(database())) {
            var journal=new ObservationJournal(store,clock);ObservationSnapshot original=nations("a");journal.observe(original);
            ObservationSnapshot imposter=new ObservationSnapshot(original.namespace(),"Different provider",original.kind(),true,true,Map.of(),"Different authority");
            assertThrows(IllegalArgumentException.class,()->journal.observe(imposter));assertEquals(0,journal.pendingCount());assertEquals(0,journal.observe(original).queued());
        }
    }
    @Test void pendingReadsDoNotAcknowledgeDeliveryAndBatchLimitIsEnforced() {
        try(var store=new SqliteStore(database())) {
            var journal=new ObservationJournal(store,clock);journal.observe(nations("a","b","c"));journal.observe(nations("a"));
            assertEquals(1,journal.pending(1).size());assertEquals(2,journal.pendingCount());assertEquals(2,journal.pending(10).size());
            assertThrows(IllegalArgumentException.class,()->journal.pending(0));assertThrows(IllegalArgumentException.class,()->journal.pending(1001));
        }
    }
}
