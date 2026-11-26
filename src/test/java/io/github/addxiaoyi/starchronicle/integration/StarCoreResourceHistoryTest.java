package io.github.addxiaoyi.starchronicle.integration;

import io.github.addxiaoyi.starchronicle.api.resource.ResourceHistoryReceipt;
import io.github.addxiaoyi.starchronicle.core.RuleEngine;
import io.github.addxiaoyi.starchronicle.history.CommittedHistoryJournal;
import io.github.addxiaoyi.starchronicle.history.ResourceHistoryProjection;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class StarCoreResourceHistoryTest {
    @TempDir Path directory;
    static final UUID DOMAIN = new UUID(0, 3), NATION = new UUID(0, 7), ACTOR = new UUID(0, 8);
    static final Instant TIME = Instant.parse("2026-11-26T00:00:00.123456789Z");
    Path db() { return directory.resolve("resource.db"); }
    public record Balance(UUID nationId, String resourceType, long before, long after) {}
    public record Receipt(UUID authorityDomainId, long sequence, UUID operationId, ResourceHistoryReceipt.Kind kind,
                          String reference, String category, String description, UUID actorId,
                          Instant committedAt, List<Balance> balances) {}
    public static class Authority {
        UUID domain = DOMAIN;
        List<Receipt> receipts = new ArrayList<>();
        RuntimeException failure;
        List<?> invalid;
        public Optional<UUID> historyAuthorityDomainId() { if (failure != null) throw failure; return Optional.ofNullable(domain); }
        public List<?> resourceChangesAfter(long after, int limit) {
            if (failure != null) throw failure;
            if (invalid != null) return invalid;
            if (after > receipts.size()) throw new IllegalStateException("Source backup rolled back");
            return receipts.stream().filter(r -> r.sequence() > after).limit(limit).toList();
        }
    }
    Receipt receipt(long sequence) {
        return new Receipt(DOMAIN, sequence, new UUID(0, sequence), ResourceHistoryReceipt.Kind.GRANT,
                "operation:" + sequence, "OTHER", "保留原文 💎", null, TIME.minusSeconds(sequence),
                List.of(new Balance(NATION, "mineral", sequence - 1, sequence)));
    }
    void consume(SqliteStore store, CommittedHistoryJournal journal) {
        var engine = new RuleEngine(store, List.of(), Clock.systemUTC());
        for (var pending : journal.pending(128)) {
            engine.acceptCommittedHistory(pending, List.of());
            assertEquals(pending.resourceReceipt(), engine.resourceHistoryReceipt(pending.event().id()).orElseThrow());
            assertTrue(journal.acknowledge(pending.event().id()));
        }
    }
    long count(SqliteStore store, String table) {
        return store.transaction(c -> { try(var s = c.createStatement(); var r = s.executeQuery("SELECT COUNT(*) FROM " + table)) { assertTrue(r.next()); return r.getLong(1); } });
    }
    @Test void balanceChangesPreserveFullReceiptExactTimeAndNoInferredActorsOrContributions() {
        Authority source = new Authority();
        try (var store = new SqliteStore(db())) {
            var journal = new CommittedHistoryJournal(store); var history = new StarCoreResourceHistory(() -> source, journal);
            assertEquals(0, history.scan());
            source.receipts.add(receipt(1));
            source.receipts.add(new Receipt(DOMAIN, 2, new UUID(0, 2), ResourceHistoryReceipt.Kind.TRANSFER,
                    "aid:already-counted", "OTHER", "transfer", null, TIME,
                    List.of(new Balance(NATION,"mineral",1,0), new Balance(new UUID(0,9),"mineral",0,1))));
            assertEquals(2, history.scan());
            var pending = journal.pending(128);
            assertTrue(pending.stream().allMatch(p -> p.activate() && p.event().participants().isEmpty()));
            assertEquals(TIME.minusSeconds(1), pending.getFirst().event().occurredAt());
            assertEquals(2, pending.getLast().resourceReceipt().balances().size());
            assertEquals(Set.of(NATION.toString(),new UUID(0,9).toString()), pending.getLast().event().nations());
            consume(store, journal);
            assertEquals(2, count(store, "resource_history_receipts"));
            assertEquals(0, count(store, "contributions"));
            assertEquals(0, history.scan());
        }
        try (var store = new SqliteStore(db())) {
            var journal = new CommittedHistoryJournal(store); var history = new StarCoreResourceHistory(() -> source, journal);
            assertEquals(0, history.scan());
            var engine = new RuleEngine(store, List.of(), Clock.systemUTC());
            var expected = StarCoreResourceHistory.project(receipt(1), DOMAIN, 1).resourceReceipt();
            assertEquals(expected, engine.resourceHistoryReceipt(ResourceHistoryProjection.eventId(expected)).orElseThrow());
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    @Test void explicitActorIsRetainedButStillDoesNotInventProduction() throws Exception {
        var raw = new Receipt(DOMAIN,1,new UUID(0,1),ResourceHistoryReceipt.Kind.GRANT,"", "", "", ACTOR, TIME,
                List.of(new Balance(NATION,"industrial",1,2)));
        var entry = StarCoreResourceHistory.project(raw, DOMAIN, 1);
        assertEquals(Set.of(ACTOR), entry.event().participants());
        assertEquals("BALANCE_CHANGE_IS_NOT_PRODUCTION_OR_NEW_AID_OR_TRADE", entry.event().facts().get("contribution.omission"));
        assertEquals(ACTOR, entry.resourceReceipt().actorId());
    }
    @Test void catchesUpIn128RowsThenActivatesNewCommitsAndRecoversPendingAfterRestart() {
        Authority source = new Authority(); for(int i=1;i<=131;i++) source.receipts.add(receipt(i));
        try (var store = new SqliteStore(db())) {
            var journal = new CommittedHistoryJournal(store); var history = new StarCoreResourceHistory(() -> source, journal);
            assertEquals(128, history.scan()); assertEquals(0, history.scan());
            assertTrue(journal.pending(128).stream().noneMatch(CommittedHistoryJournal.Pending::activate));
            consume(store, journal); assertEquals(3, history.scan());
        }
        try (var store = new SqliteStore(db())) {
            var journal = new CommittedHistoryJournal(store); var history = new StarCoreResourceHistory(() -> source, journal);
            assertEquals(0, history.scan()); assertEquals(3, journal.pending(128).size());
            consume(store, journal); assertEquals(0, history.scan());
            source.receipts.add(receipt(132)); assertEquals(1, history.scan());
            assertTrue(journal.pending(128).getFirst().activate()); consume(store, journal);
            assertEquals(132, count(store, "resource_history_receipts")); assertEquals(0, count(store, "contributions"));
        }
    }
    @Test void largeReceiptRetainsAllBalancesWithoutExceedingEventFactLimits() throws Exception {
        List<Balance> balances = new ArrayList<>();
        for(int i=0;i<128;i++) balances.add(new Balance(new UUID(0,i+100),"mineral",0,Long.MAX_VALUE));
        var raw = new Receipt(DOMAIN,1,new UUID(0,1),ResourceHistoryReceipt.Kind.GRANT,"x".repeat(4096),"OTHER","",null,TIME,balances);
        var entry = StarCoreResourceHistory.project(raw, DOMAIN, 1);
        try(var store = new SqliteStore(db())) {
            var journal = new CommittedHistoryJournal(store);
            journal.stage(StarCoreResourceHistory.NAMESPACE,DOMAIN,0,List.of(entry)); consume(store,journal);
            var restored = new RuleEngine(store,List.of(),Clock.systemUTC()).resourceHistoryReceipt(entry.event().id()).orElseThrow();
            assertEquals(entry.resourceReceipt(),restored); assertEquals(128,restored.balances().size());
        }
    }
    @Test void missingLegacyCapabilityIsOptionalButPinnedDowngradeAndDomainRollbackFail() {
        AtomicReference<Object> source = new AtomicReference<>(new Object());
        try(var store = new SqliteStore(db())) {
            var journal = new CommittedHistoryJournal(store); var history = new StarCoreResourceHistory(source::get,journal);
            assertEquals(0,history.scan()); assertTrue(journal.cursor(StarCoreResourceHistory.NAMESPACE).isEmpty());
            Authority authority = new Authority(); authority.receipts.add(receipt(1)); source.set(authority);
            history.scan(); consume(store,journal);
            authority.domain=UUID.randomUUID(); assertThrows(IllegalStateException.class,history::scan);
            authority.domain=DOMAIN; authority.receipts.clear(); assertThrows(IllegalStateException.class,history::scan);
            source.set(null); assertThrows(IllegalStateException.class,history::scan);
            assertEquals(1,journal.cursor(StarCoreResourceHistory.NAMESPACE).orElseThrow().sequence());
        }
        try(var store = new SqliteStore(db())) {
            source.set(new Object());
            assertThrows(IllegalStateException.class,new StarCoreResourceHistory(source::get,new CommittedHistoryJournal(store))::scan);
        }
    }
    @Test void malformedBalancesAndPagesNeverAdvanceCursor() {
        Authority source = new Authority();
        List<List<Balance>> invalidRows = List.of(List.of(),List.of(new Balance(NATION,"mineral",0,0)),
                List.of(new Balance(null,"mineral",0,1)),List.of(new Balance(NATION,"mineral",-1,1)),
                List.of(new Balance(NATION,"invalid:type",0,1)),List.of(new Balance(NATION,"mineral",2,1)),
                Collections.nCopies(2,new Balance(NATION,"mineral",0,1)),Collections.nCopies(129,new Balance(NATION,"mineral",0,1)));
        try(var store = new SqliteStore(db())) {
            var journal = new CommittedHistoryJournal(store); var history = new StarCoreResourceHistory(() -> source,journal);
            for(var rows:invalidRows) {
                source.invalid=List.of(new Receipt(DOMAIN,1,new UUID(0,1),ResourceHistoryReceipt.Kind.GRANT,"","","",null,TIME,rows));
                assertThrows(IllegalArgumentException.class,history::scan);
                assertTrue(journal.cursor(StarCoreResourceHistory.NAMESPACE).isEmpty()); assertTrue(journal.pending(128).isEmpty());
            }
            for(var page:List.of(List.of(receipt(1),receipt(3)),Collections.nCopies(129,receipt(1)),
                    List.of(new Receipt(UUID.randomUUID(),1,new UUID(0,1),ResourceHistoryReceipt.Kind.GRANT,"","","",null,TIME,receipt(1).balances())))) {
                source.invalid=page; assertThrows(IllegalArgumentException.class,history::scan);
                assertTrue(journal.cursor(StarCoreResourceHistory.NAMESPACE).isEmpty());
            }
        }
    }
    @Test void failureDuringIngressRollsBackFullPayloadAndCursorTogether() {
        Authority source = new Authority(); source.receipts.add(receipt(1));
        try(var store = new SqliteStore(db())) {
            var journal = new CommittedHistoryJournal(store); var history = new StarCoreResourceHistory(() -> source,journal);
            store.transaction(c -> { try(var s=c.createStatement()) { s.execute("CREATE TRIGGER reject_resource_ingress BEFORE INSERT ON committed_resource_ingress BEGIN SELECT RAISE(ABORT,'fault'); END"); } return null; });
            assertThrows(RuntimeException.class,history::scan);
            assertTrue(journal.cursor(StarCoreResourceHistory.NAMESPACE).isEmpty()); assertTrue(journal.pending(128).isEmpty());
            assertEquals(0,count(store,"committed_resource_ingress"));
        }
    }
    @Test void failedReceiptCommitDoesNotLeaveEventOrAcknowledgeAndRestartRetriesOnce() {
        Authority source = new Authority(); source.receipts.add(receipt(1));
        String eventId;
        try(var store = new SqliteStore(db())) {
            var journal = new CommittedHistoryJournal(store); new StarCoreResourceHistory(() -> source,journal).scan();
            var pending = journal.pending(128).getFirst(); eventId=pending.event().id();
            var engine = new RuleEngine(store,List.of(),Clock.systemUTC());
            store.transaction(c -> { try(var s=c.createStatement()) { s.execute("CREATE TRIGGER reject_resource_receipt BEFORE INSERT ON resource_history_receipts BEGIN SELECT RAISE(ABORT,'fault'); END"); } return null; });
            assertThrows(RuntimeException.class,()->engine.acceptCommittedHistory(pending,List.of()));
            assertEquals(0,count(store,"events")); assertEquals(0,count(store,"resource_history_receipts"));
            assertFalse(journal.acknowledge(eventId));
            store.transaction(c -> { try(var s=c.createStatement()) { s.execute("DROP TRIGGER reject_resource_receipt"); } return null; });
        }
        try(var store = new SqliteStore(db())) {
            var journal = new CommittedHistoryJournal(store); consume(store,journal);
            assertEquals(1,count(store,"events")); assertEquals(1,count(store,"resource_history_receipts"));
            assertFalse(journal.acknowledge(eventId)); assertEquals(0,new StarCoreResourceHistory(() -> source,journal).scan());
        }
    }
    @Test void eventAloneCannotAcknowledgeResourceHistoryAndReceiptCollisionsFail() throws Exception {
        var entry = StarCoreResourceHistory.project(receipt(1),DOMAIN,1);
        try(var store = new SqliteStore(db())) {
            var journal = new CommittedHistoryJournal(store); journal.stage(StarCoreResourceHistory.NAMESPACE,DOMAIN,0,List.of(entry));
            var engine = new RuleEngine(store,List.of(),Clock.systemUTC());
            engine.archiveCommittedHistory(entry.event());
            assertFalse(journal.acknowledge(entry.event().id()));
            assertThrows(IllegalArgumentException.class,()->engine.acceptCommittedHistory(new CommittedHistoryJournal.Pending(entry.event(),false),List.of()));
            var pending=journal.pending(128).getFirst(); engine.acceptCommittedHistory(pending,List.of());
            assertTrue(journal.acknowledge(entry.event().id()));
            engine.acceptCommittedHistory(pending,List.of());
            var conflicting=StarCoreResourceHistory.project(new Receipt(DOMAIN,2,receipt(1).operationId(),ResourceHistoryReceipt.Kind.GRANT,
                    "changed","","",null,TIME,List.of(new Balance(NATION,"mineral",1,2))),DOMAIN,2);
            assertThrows(IllegalArgumentException.class,()->engine.acceptCommittedHistory(new CommittedHistoryJournal.Pending(conflicting.event(),false,null,conflicting.resourceReceipt()),List.of()));
            assertEquals(entry.resourceReceipt(),engine.resourceHistoryReceipt(entry.event().id()).orElseThrow());
            assertEquals(1,count(store,"resource_history_receipts"));
        }
    }
    @Test void transferConservationAndBoundsAreValidated() {
        assertThrows(IllegalArgumentException.class,()->new ResourceHistoryReceipt(DOMAIN,1,new UUID(0,1),ResourceHistoryReceipt.Kind.TRANSFER,
                "","","",null,TIME,List.of(new ResourceHistoryReceipt.BalanceChange(NATION,"mineral",1,0))));
        assertThrows(IllegalArgumentException.class,()->new ResourceHistoryReceipt(DOMAIN,1,new UUID(0,1),ResourceHistoryReceipt.Kind.GRANT,
                "x".repeat(4097),"","",null,TIME,List.of(new ResourceHistoryReceipt.BalanceChange(NATION,"mineral",0,1))));
    }
}
