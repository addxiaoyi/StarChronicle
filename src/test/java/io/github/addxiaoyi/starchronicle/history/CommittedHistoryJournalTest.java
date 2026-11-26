package io.github.addxiaoyi.starchronicle.history;

import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.core.RuleEngine;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CommittedHistoryJournalTest {
    @TempDir Path directory;
    static final String SOURCE = "test.technology";
    static final UUID DOMAIN = new UUID(0, 10);
    static final Instant TIME = Instant.parse("2026-11-26T00:00:00.123456789Z");
    Path db() { return directory.resolve("history.db"); }
    CommittedHistoryJournal.Entry entry(long sequence) {
        return new CommittedHistoryJournal.Entry(sequence, new WorldEvent("commit:" + sequence, "TECHNOLOGY_BREAKTHROUGH",
                TIME.minusSeconds(sequence), Set.of(), Set.of("nation"), Map.of("time_basis", "COMMITTED"), "Authority", "operation=" + sequence));
    }
    RuleEngine engine(SqliteStore store) { return new RuleEngine(store, List.of(), Clock.systemUTC()); }
    void drain(CommittedHistoryJournal journal, RuleEngine engine) {
        for (var pending : journal.pending(128)) {
            if (pending.activate()) engine.accept(pending.event(), List.of());
            else engine.archiveCommittedHistory(pending.event());
            assertTrue(journal.acknowledge(pending.event().id()));
        }
    }
    @Test void cursorAndPendingSurviveDeathBeforeConsumerWithExactNanosecondTime() {
        try (var store = new SqliteStore(db())) {
            var journal = new CommittedHistoryJournal(store);
            journal.stage(SOURCE, DOMAIN, 0, List.of());
            journal.stage(SOURCE, DOMAIN, 0, List.of(entry(1), entry(2)));
        }
        try (var store = new SqliteStore(db())) {
            var journal = new CommittedHistoryJournal(store);
            assertEquals(2, journal.cursor(SOURCE).orElseThrow().sequence());
            assertEquals(List.of(entry(1).event(), entry(2).event()), journal.pending(128).stream().map(CommittedHistoryJournal.Pending::event).toList());
            assertTrue(journal.pending(128).stream().allMatch(CommittedHistoryJournal.Pending::activate));
            assertFalse(journal.acknowledge(entry(1).event().id()), "Never acknowledge an uncommitted consumer event");
            drain(journal, engine(store));
            assertFalse(journal.hasPending(SOURCE));
        }
    }
    @Test void deathAfterConsumerCommitBeforeAckRedeliversOneHistoryRow() {
        try (var store = new SqliteStore(db())) {
            var journal = new CommittedHistoryJournal(store);
            journal.stage(SOURCE, DOMAIN, 0, List.of());
            journal.stage(SOURCE, DOMAIN, 0, List.of(entry(1)));
            engine(store).accept(entry(1).event(), List.of());
        }
        try (var store = new SqliteStore(db())) {
            var journal = new CommittedHistoryJournal(store); var engine = engine(store);
            assertTrue(engine.accept(journal.pending(128).getFirst().event(), List.of()).duplicate());
            assertTrue(journal.acknowledge(entry(1).event().id()));
            assertEquals(1, engine.history(null, null, 0, 10).size());
        }
    }
    @Test void failedPageTransactionRollsBackCursorPinAndAllIngress() {
        try (var store = new SqliteStore(db())) {
            var journal = new CommittedHistoryJournal(store, () -> { throw new AssertionError("power loss"); });
            assertThrows(AssertionError.class, () -> journal.stage(SOURCE, DOMAIN, 0, List.of(entry(1), entry(2))));
            assertTrue(journal.cursor(SOURCE).isEmpty()); assertTrue(journal.pending(128).isEmpty());
        }
        try (var store = new SqliteStore(db())) {
            var journal = new CommittedHistoryJournal(store);
            journal.stage(SOURCE, DOMAIN, 0, List.of(entry(1), entry(2)));
            assertEquals(2, journal.pending(128).size());
        }
    }
    @Test void firstCatchupSpansBoundedPagesAndResumesWithoutActivatingOldEvents() {
        try (var store = new SqliteStore(db())) {
            var journal = new CommittedHistoryJournal(store);
            journal.stage(SOURCE, DOMAIN, 0, LongStream.rangeClosed(1,128).mapToObj(this::entry).toList());
            assertFalse(journal.cursor(SOURCE).orElseThrow().live());
            assertFalse(journal.pending(128).getFirst().activate());
            assertThrows(IllegalStateException.class, () -> journal.stage(SOURCE, DOMAIN, 128, List.of(entry(129))));
            drain(journal, engine(store));
        }
        try (var store = new SqliteStore(db())) {
            var journal = new CommittedHistoryJournal(store);
            journal.stage(SOURCE, DOMAIN, 128, List.of(entry(129)));
            assertTrue(journal.cursor(SOURCE).orElseThrow().live());
            assertFalse(journal.pending(128).getFirst().activate());
            drain(journal, engine(store));
            journal.stage(SOURCE, DOMAIN, 129, List.of(entry(130)));
            assertTrue(journal.pending(128).getFirst().activate());
        }
    }
    @Test void differentAuthorityStaleCursorGapsAndOversizedPagesNeverAdvance() {
        try (var store = new SqliteStore(db())) {
            var journal = new CommittedHistoryJournal(store);
            journal.stage(SOURCE, DOMAIN, 0, List.of(entry(1))); drain(journal, engine(store));
            assertThrows(IllegalStateException.class, () -> journal.stage(SOURCE, UUID.randomUUID(), 1, List.of()));
            assertThrows(IllegalStateException.class, () -> journal.stage(SOURCE, DOMAIN, 0, List.of(entry(1))));
            assertThrows(IllegalArgumentException.class, () -> journal.stage(SOURCE, DOMAIN, 1, List.of(entry(3))));
            assertThrows(IllegalArgumentException.class, () -> journal.stage(SOURCE, DOMAIN, 1, List.of(entry(2), entry(2))));
            assertThrows(IllegalArgumentException.class, () -> journal.stage(SOURCE, DOMAIN, 1, LongStream.rangeClosed(2,130).mapToObj(this::entry).toList()));
            assertEquals(1, journal.cursor(SOURCE).orElseThrow().sequence()); assertTrue(journal.pending(128).isEmpty());
        }
    }
    @Test void reusedOperationAtAnotherSequenceRollsBackEntirePage() {
        try (var store = new SqliteStore(db())) {
            var journal = new CommittedHistoryJournal(store);
            journal.stage(SOURCE, DOMAIN, 0, List.of(entry(1))); drain(journal, engine(store));
            assertThrows(IllegalStateException.class, () -> journal.stage(SOURCE, DOMAIN, 1,
                    List.of(entry(2), new CommittedHistoryJournal.Entry(3, entry(1).event()))));
            assertEquals(1, journal.cursor(SOURCE).orElseThrow().sequence()); assertTrue(journal.pending(128).isEmpty());
        }
    }
    @Test void emptyPagePinsAuthorityAndActivationAcrossRestart() {
        try (var store = new SqliteStore(db())) { new CommittedHistoryJournal(store).stage(SOURCE, DOMAIN, 0, List.of()); }
        try (var store = new SqliteStore(db())) {
            var journal = new CommittedHistoryJournal(store);
            assertEquals(new CommittedHistoryJournal.Cursor(DOMAIN, 0, true), journal.cursor(SOURCE).orElseThrow());
            assertThrows(IllegalStateException.class, () -> journal.stage(SOURCE, UUID.randomUUID(), 0, List.of()));
            assertThrows(IllegalArgumentException.class, () -> journal.pending(129));
            assertThrows(IllegalArgumentException.class, () -> journal.cursor(""));
        }
    }
}
