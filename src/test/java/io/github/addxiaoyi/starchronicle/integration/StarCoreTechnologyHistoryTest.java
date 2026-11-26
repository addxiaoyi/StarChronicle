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

class StarCoreTechnologyHistoryTest {
    @TempDir Path directory;
    static final UUID DOMAIN = new UUID(0, 3), NATION = new UUID(0, 7);
    static final Instant TIME = Instant.parse("2026-11-26T00:00:00.123456789Z");
    Path db() { return directory.resolve("technology.db"); }
    public record Receipt(UUID authorityDomainId, long sequence, UUID operationId, UUID nationId,
                          String technologyKey, String era, String change, Instant committedAt) {}
    public static class Authority {
        UUID domain = DOMAIN;
        List<Receipt> receipts = new ArrayList<>();
        RuntimeException failure;
        List<?> invalid;
        public Optional<UUID> historyAuthorityDomainId() { if (failure != null) throw failure; return Optional.ofNullable(domain); }
        public List<?> technologyChangesAfter(long after, int limit) {
            if (failure != null) throw failure;
            if (invalid != null) return invalid;
            if (after > receipts.size()) throw new IllegalStateException("Source backup rolled back");
            return receipts.stream().filter(r -> r.sequence() > after).limit(limit).toList();
        }
    }
    Receipt receipt(long sequence, String change) {
        return new Receipt(DOMAIN, sequence, new UUID(0, sequence), NATION, "agriculture", "primitive", change, TIME.minusSeconds(sequence));
    }
    void consume(SqliteStore store, CommittedHistoryJournal journal) {
        var engine = new RuleEngine(store, List.of(), Clock.systemUTC());
        for (var pending : journal.pending(128)) {
            if (pending.activate()) engine.accept(pending.event(), List.of()); else engine.archiveCommittedHistory(pending.event());
            assertTrue(journal.acknowledge(pending.event().id()));
        }
    }
    @Test void unlockRevokeReunlockBetweenPollsRemainDistinctEvenWhenCommitClockMovesBack() {
        Authority source = new Authority();
        try (var store = new SqliteStore(db())) {
            var journal = new CommittedHistoryJournal(store); var history = new StarCoreTechnologyHistory(() -> source, journal);
            assertTrue(history.usesCommittedHistory()); assertEquals(0, history.scan());
            source.receipts.addAll(List.of(receipt(1,"UNLOCKED"), receipt(2,"REVOKED"), receipt(3,"UNLOCKED")));
            assertEquals(3, history.scan());
            var events = journal.pending(128).stream().map(CommittedHistoryJournal.Pending::event).toList();
            assertEquals(List.of("TECHNOLOGY_BREAKTHROUGH","TECHNOLOGY_REVOKED","TECHNOLOGY_BREAKTHROUGH"), events.stream().map(e -> e.type()).toList());
            assertEquals(3, events.stream().map(e -> e.id()).distinct().count());
            assertEquals(TIME.minusSeconds(1), events.getFirst().occurredAt());
            assertTrue(events.stream().allMatch(e -> e.participants().isEmpty() && e.nations().equals(Set.of(NATION.toString()))));
            assertTrue(events.stream().allMatch(e -> e.facts().get("time_basis").equals("COMMITTED")));
            consume(store, journal); assertEquals(0, history.scan());
        }
        try (var store = new SqliteStore(db())) {
            var journal = new CommittedHistoryJournal(store); var history = new StarCoreTechnologyHistory(() -> source, journal);
            assertEquals(0, history.scan());
            source.receipts.add(receipt(4,"REVOKED")); assertEquals(1, history.scan());
            assertTrue(journal.pending(128).getFirst().activate());
        }
    }
    @Test void missingLegacyCapabilityLeavesObservationModeButPinnedDowngradeFailsClosed() {
        AtomicReference<Object> source = new AtomicReference<>(new Object());
        try (var store = new SqliteStore(db())) {
            var journal = new CommittedHistoryJournal(store); var history = new StarCoreTechnologyHistory(source::get, journal);
            assertFalse(history.usesCommittedHistory()); assertEquals(0, history.scan());
            source.set(new Authority()); history.scan(); source.set(null);
            assertTrue(history.usesCommittedHistory()); assertThrows(IllegalStateException.class, history::scan);
        }
        try (var store = new SqliteStore(db())) {
            source.set(new Object()); var history = new StarCoreTechnologyHistory(source::get, new CommittedHistoryJournal(store));
            assertTrue(history.usesCommittedHistory()); assertThrows(IllegalStateException.class, history::scan);
        }
    }
    @Test void unsupportedEmptyCapabilityIsLegacyOnlyUntilARealDomainWasSeen() {
        Authority source = new Authority(); source.domain = null;
        try (var store = new SqliteStore(db())) {
            var journal = new CommittedHistoryJournal(store); var history = new StarCoreTechnologyHistory(() -> source, journal);
            assertFalse(history.usesCommittedHistory()); assertEquals(0, history.scan()); assertTrue(journal.cursor(StarCoreTechnologyHistory.NAMESPACE).isEmpty());
            source.domain = DOMAIN; history.scan(); source.domain = null;
            assertTrue(history.usesCommittedHistory()); assertThrows(IllegalStateException.class, history::scan);
        }
    }
    @Test void changedDomainAndRolledBackBackupNeverResetAnExistingCursor() {
        Authority source = new Authority(); source.receipts.add(receipt(1,"UNLOCKED"));
        try (var store = new SqliteStore(db())) {
            var journal = new CommittedHistoryJournal(store); var history = new StarCoreTechnologyHistory(() -> source, journal);
            history.scan(); consume(store, journal);
            source.domain = UUID.randomUUID(); assertThrows(IllegalStateException.class, history::scan);
            source.domain = DOMAIN; source.receipts.clear(); assertThrows(IllegalStateException.class, history::scan);
            assertEquals(1, journal.cursor(StarCoreTechnologyHistory.NAMESPACE).orElseThrow().sequence());
        }
    }
    @Test void brokenCapabilitySuppressesFallbackWithoutPinningOrConsuming() {
        Authority source = new Authority(); source.failure = new IllegalStateException("missing history file");
        try (var store = new SqliteStore(db())) {
            var journal = new CommittedHistoryJournal(store); var history = new StarCoreTechnologyHistory(() -> source, journal);
            assertTrue(history.usesCommittedHistory()); assertThrows(IllegalStateException.class, history::scan);
            assertTrue(journal.cursor(StarCoreTechnologyHistory.NAMESPACE).isEmpty()); assertTrue(journal.pending(128).isEmpty());
        }
    }
    @Test void malformedOrReorderedPageIsRejectedBeforeAnyCursorOrEventCommit() {
        Authority source = new Authority();
        try (var store = new SqliteStore(db())) {
            var journal = new CommittedHistoryJournal(store); var history = new StarCoreTechnologyHistory(() -> source, journal);
            for (List<?> invalid : List.of(List.of(receipt(1,"UNLOCKED"),receipt(3,"REVOKED")), List.of(receipt(1,"PENDING")),
                    List.of(new Receipt(UUID.randomUUID(),1,new UUID(0,1),NATION,"agriculture","primitive","UNLOCKED",TIME)),
                    List.of(new Receipt(DOMAIN,1,new UUID(0,1),NATION,"agriculture","primitive","UNLOCKED",null)),
                    Collections.nCopies(129, receipt(1,"UNLOCKED")))) {
                source.invalid = invalid;
                assertThrows(IllegalArgumentException.class, history::scan);
                assertTrue(journal.cursor(StarCoreTechnologyHistory.NAMESPACE).isEmpty()); assertTrue(journal.pending(128).isEmpty());
            }
        }
    }
    @Test void initialArchiveCatchesUpInAtMost128RowsPerPollAndThenActivatesNewCommits() {
        Authority source = new Authority(); for (int i=1;i<=130;i++) source.receipts.add(receipt(i,"UNLOCKED"));
        try (var store = new SqliteStore(db())) {
            var journal = new CommittedHistoryJournal(store); var history = new StarCoreTechnologyHistory(() -> source, journal);
            assertEquals(128, history.scan()); assertEquals(0, history.scan()); consume(store, journal);
            assertEquals(2, history.scan()); assertTrue(journal.pending(128).stream().noneMatch(CommittedHistoryJournal.Pending::activate)); consume(store, journal);
            source.receipts.add(receipt(131,"REVOKED")); assertEquals(1, history.scan()); assertTrue(journal.pending(128).getFirst().activate());
        }
    }
}
