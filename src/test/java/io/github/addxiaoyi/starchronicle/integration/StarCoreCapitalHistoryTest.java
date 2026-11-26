package io.github.addxiaoyi.starchronicle.integration;

import io.github.addxiaoyi.starchronicle.core.RuleEngine;
import io.github.addxiaoyi.starchronicle.history.CommittedHistoryJournal;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class StarCoreCapitalHistoryTest {
    @TempDir Path directory;
    static final UUID DOMAIN = new UUID(0, 3), NATION = new UUID(0, 7), ACTOR = new UUID(0, 8), CITY_A = new UUID(0, 9), CITY_B = new UUID(0, 10);
    static final Instant TIME = Instant.parse("2026-11-26T00:00:00.123456789Z");
    Path db() { return directory.resolve("capital.db"); }
    public record Designation(long sequence, UUID operationId, UUID nationId, UUID previousCityId,
                              UUID cityId, UUID actorId, Instant committedAt) {}
    public record Receipt(UUID authorityDomainId, Designation designation) {}
    public static class Authority {
        UUID domain = DOMAIN;
        List<Receipt> receipts = new ArrayList<>();
        RuntimeException failure;
        List<?> invalid;
        public Optional<UUID> capitalHistoryAuthorityDomainId() { if (failure != null) throw failure; return Optional.ofNullable(domain); }
        public List<?> capitalChangesAfter(long after, int limit) {
            if (failure != null) throw failure;
            if (invalid != null) return invalid;
            if (after > receipts.size()) throw new IllegalStateException("Source backup rolled back");
            return receipts.stream().filter(r -> r.designation().sequence() > after).limit(limit).toList();
        }
    }
    Receipt receipt(long sequence) {
        return new Receipt(DOMAIN, new Designation(sequence, new UUID(0, sequence), NATION,
                sequence == 1 ? null : (sequence % 2 == 0 ? CITY_A : CITY_B),
                sequence % 2 == 1 ? CITY_A : CITY_B, ACTOR, TIME.minusSeconds(sequence)));
    }
    void consume(SqliteStore store, CommittedHistoryJournal journal) {
        var engine = new RuleEngine(store, List.of(), Clock.systemUTC());
        for (var pending : journal.pending(128)) {
            if (pending.activate()) engine.accept(pending.event(), List.of()); else engine.archiveCommittedHistory(pending.event());
            assertTrue(journal.acknowledge(pending.event().id()));
        }
    }
    @Test void designateMoveAndReturnBetweenPollsPreserveOriginalActorAndExactCommitTime() {
        Authority source = new Authority();
        try (var store = new SqliteStore(db())) {
            var journal = new CommittedHistoryJournal(store); var history = new StarCoreCapitalHistory(() -> source, journal);
            assertTrue(history.usesCommittedHistory()); assertEquals(0, history.scan());
            source.receipts.addAll(List.of(receipt(1), receipt(2), receipt(3)));
            assertEquals(3, history.scan());
            var events = journal.pending(128).stream().map(CommittedHistoryJournal.Pending::event).toList();
            assertEquals(List.of("CAPITAL_DESIGNATED","CAPITAL_MOVED","CAPITAL_MOVED"), events.stream().map(e -> e.type()).toList());
            assertEquals(3, events.stream().map(e -> e.id()).distinct().count());
            assertFalse(events.getFirst().facts().containsKey("capital.previous"));
            assertEquals(CITY_A.toString(), events.get(1).facts().get("capital.previous"));
            assertEquals(CITY_A.toString(), events.get(2).facts().get("city.id"));
            assertEquals(TIME.minusSeconds(1), events.getFirst().occurredAt());
            assertTrue(events.stream().allMatch(e -> e.participants().equals(Set.of(ACTOR)) && e.nations().equals(Set.of(NATION.toString()))));
            assertTrue(events.stream().allMatch(e -> e.facts().get("time_basis").equals("COMMITTED")));
            consume(store, journal); assertEquals(0, history.scan());
        }
        try (var store = new SqliteStore(db())) {
            var journal = new CommittedHistoryJournal(store); var history = new StarCoreCapitalHistory(() -> source, journal);
            assertEquals(0, history.scan());
            source.receipts.add(receipt(4)); assertEquals(1, history.scan());
            assertTrue(journal.pending(128).getFirst().activate());
        }
    }
    @Test void missingLegacyCapabilityLeavesObservationModeButPinnedDowngradeFailsClosed() {
        AtomicReference<Object> source = new AtomicReference<>(new Object());
        try (var store = new SqliteStore(db())) {
            var journal = new CommittedHistoryJournal(store); var history = new StarCoreCapitalHistory(source::get, journal);
            assertFalse(history.usesCommittedHistory()); assertEquals(0, history.scan());
            source.set(new Authority()); history.scan(); source.set(null);
            assertTrue(history.usesCommittedHistory()); assertThrows(IllegalStateException.class, history::scan);
        }
        try (var store = new SqliteStore(db())) {
            source.set(new Object()); var history = new StarCoreCapitalHistory(source::get, new CommittedHistoryJournal(store));
            assertTrue(history.usesCommittedHistory()); assertThrows(IllegalStateException.class, history::scan);
        }
    }
    @Test void unsupportedEmptyCapabilityIsLegacyOnlyUntilARealDomainWasSeen() {
        Authority source = new Authority(); source.domain = null;
        try (var store = new SqliteStore(db())) {
            var journal = new CommittedHistoryJournal(store); var history = new StarCoreCapitalHistory(() -> source, journal);
            assertFalse(history.usesCommittedHistory()); assertEquals(0, history.scan()); assertTrue(journal.cursor(StarCoreCapitalHistory.NAMESPACE).isEmpty());
            source.domain = DOMAIN; history.scan(); source.domain = null;
            assertTrue(history.usesCommittedHistory()); assertThrows(IllegalStateException.class, history::scan);
        }
    }
    @Test void changedDomainAndRolledBackBackupNeverResetAnExistingCursor() {
        Authority source = new Authority(); source.receipts.add(receipt(1));
        try (var store = new SqliteStore(db())) {
            var journal = new CommittedHistoryJournal(store); var history = new StarCoreCapitalHistory(() -> source, journal);
            history.scan(); consume(store, journal);
            source.domain = UUID.randomUUID(); assertThrows(IllegalStateException.class, history::scan);
            source.domain = DOMAIN; source.receipts.clear(); assertThrows(IllegalStateException.class, history::scan);
            assertEquals(1, journal.cursor(StarCoreCapitalHistory.NAMESPACE).orElseThrow().sequence());
        }
    }
    @Test void brokenCapabilitySuppressesFallbackWithoutPinningOrConsuming() {
        Authority source = new Authority(); source.failure = new IllegalStateException("missing history file");
        try (var store = new SqliteStore(db())) {
            var journal = new CommittedHistoryJournal(store); var history = new StarCoreCapitalHistory(() -> source, journal);
            assertTrue(history.usesCommittedHistory()); assertThrows(IllegalStateException.class, history::scan);
            assertTrue(journal.cursor(StarCoreCapitalHistory.NAMESPACE).isEmpty()); assertTrue(journal.pending(128).isEmpty());
        }
    }
    @Test void malformedOrReorderedPageIsRejectedBeforeAnyCursorOrEventCommit() {
        Authority source = new Authority();
        try (var store = new SqliteStore(db())) {
            var journal = new CommittedHistoryJournal(store); var history = new StarCoreCapitalHistory(() -> source, journal);
            for (List<?> invalid : List.of(List.of(receipt(1), receipt(3)),
                    List.of(new Receipt(UUID.randomUUID(), receipt(1).designation())),
                    List.of(new Receipt(DOMAIN, new Designation(1,new UUID(0,1),NATION,null,CITY_A,ACTOR,null))),
                    List.of(new Receipt(DOMAIN, new Designation(1,new UUID(0,1),NATION,null,CITY_A,null,TIME))),
                    List.of(new Receipt(DOMAIN, new Designation(1,new UUID(0,1),NATION,CITY_A,CITY_A,ACTOR,TIME))),
                    List.of(new Receipt(DOMAIN, new Designation(1,new UUID(0,1),null,null,CITY_A,ACTOR,TIME))),
                    Collections.nCopies(129, receipt(1)))) {
                source.invalid = invalid;
                assertThrows(IllegalArgumentException.class, history::scan);
                assertTrue(journal.cursor(StarCoreCapitalHistory.NAMESPACE).isEmpty()); assertTrue(journal.pending(128).isEmpty());
            }
        }
    }
    @Test void initialArchiveCatchesUpInAtMost128RowsPerPollAndThenActivatesNewCommits() {
        Authority source = new Authority(); for (int i=1;i<=130;i++) source.receipts.add(receipt(i));
        try (var store = new SqliteStore(db())) {
            var journal = new CommittedHistoryJournal(store); var history = new StarCoreCapitalHistory(() -> source, journal);
            assertEquals(128, history.scan()); assertEquals(0, history.scan()); consume(store, journal);
            assertEquals(2, history.scan()); assertTrue(journal.pending(128).stream().noneMatch(CommittedHistoryJournal.Pending::activate)); consume(store, journal);
            source.receipts.add(receipt(131)); assertEquals(1, history.scan()); assertTrue(journal.pending(128).getFirst().activate());
        }
    }
    @Test void ingressFailureRetainsSourceCursorAndPendingEventsAcrossRestart() {
        Authority source = new Authority(); source.receipts.add(receipt(1));
        try (var store = new SqliteStore(db())) {
            var journal = new CommittedHistoryJournal(store); var history = new StarCoreCapitalHistory(() -> source, journal);
            store.transaction(c -> { try (var s = c.createStatement()) {
                s.execute("CREATE TRIGGER block_capital BEFORE INSERT ON committed_history_ingress BEGIN SELECT RAISE(ABORT,'capital ingress failure'); END");
            } return null; });
            assertThrows(RuntimeException.class, history::scan);
            assertTrue(journal.cursor(StarCoreCapitalHistory.NAMESPACE).isEmpty());
            assertTrue(journal.pending(128).isEmpty());
            store.transaction(c -> { try (var s = c.createStatement()) { s.execute("DROP TRIGGER block_capital"); } return null; });
            assertEquals(1, history.scan()); // Leave the successfully staged page pending at shutdown.
        }
        try (var store = new SqliteStore(db())) {
            var journal = new CommittedHistoryJournal(store); var history = new StarCoreCapitalHistory(() -> source, journal);
            assertEquals(0, history.scan()); assertFalse(journal.pending(128).getFirst().activate());
            consume(store, journal); assertEquals(0, history.scan());
            source.receipts.add(receipt(2)); assertEquals(1, history.scan());
            assertTrue(journal.pending(128).getFirst().activate());
        }
    }

}
