package io.github.addxiaoyi.starchronicle.integration;

import io.github.addxiaoyi.starchronicle.api.trade.TradeHistoryReceipt;
import io.github.addxiaoyi.starchronicle.api.trade.TradeHistoryReceipt.*;
import io.github.addxiaoyi.starchronicle.core.RuleEngine;
import io.github.addxiaoyi.starchronicle.history.*;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static io.github.addxiaoyi.starchronicle.api.trade.TradeHistoryReceipt.Status.*;
import static io.github.addxiaoyi.starchronicle.history.TradeHistoryFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class StarCoreTradeHistoryTest {
    @TempDir Path directory;
    Path db() { return directory.resolve("source.db"); }
    public record NativeChange(UUID authorityDomainId, long sequence, Instant committedAt, Instant occurredAt, Status previousStatus, NativeTrade record) {}
    public record NativeTrade(Trade trade) {
        public UUID recordId() { return trade.recordId(); }
        public String orderType() { return trade.orderType(); }
        public String orderSource() { return trade.orderSource(); }
        public Optional<UUID> sellerPlayerId() { return Optional.ofNullable(trade.sellerPlayer()); }
        public Optional<UUID> buyerPlayerId() { return Optional.ofNullable(trade.buyerPlayer()); }
        public Optional<UUID> sellerNationId() { return Optional.ofNullable(trade.sellerNation()); }
        public Optional<UUID> buyerNationId() { return Optional.ofNullable(trade.buyerNation()); }
        public String resourceId() { return trade.resource(); }
        public long amount() { return trade.amount(); }
        public double pricePerUnit() { return trade.pricePerUnit(); }
        public double totalValue() { return trade.totalValue(); }
        public double taxAmount() { return trade.taxAmount(); }
        public double netValue() { return trade.netValue(); }
        public Instant executedAt() { return trade.executedAt(); }
        public Optional<UUID> buyOrderId() { return Optional.ofNullable(trade.buyOrder()); }
        public Optional<UUID> sellOrderId() { return Optional.ofNullable(trade.sellOrder()); }
        public Status status() { return trade.status(); }
        public Optional<String> notes() { return Optional.ofNullable(trade.notes()); }
    }
    public static class Authority {
        UUID domain = DOMAIN;
        List<NativeChange> changes = new ArrayList<>();
        RuntimeException failure;
        List<?> invalid;
        Runnable duringRead = () -> {};
        int lastLimit;
        public UUID historyAuthorityDomainId() { if (failure != null) throw failure; return domain; }
        public List<?> tradeChangesAfter(long after, int limit) {
            lastLimit = limit;
            if (after > changes.size()) throw new IllegalStateException("Source backup rolled back");
            duringRead.run();
            return invalid != null ? invalid : changes.stream().filter(r -> r.sequence() > after).limit(limit).toList();
        }
        void add(TradeHistoryReceipt receipt) { changes.add(nativeReceipt(receipt)); }
    }
    static NativeChange nativeReceipt(TradeHistoryReceipt r) { return new NativeChange(r.authorityDomain(), r.sequence(), r.committedAt(), r.occurredAt(), r.previousStatus(), new NativeTrade(r.trade())); }
    void consume(SqliteStore store, CommittedHistoryJournal journal) {
        var engine = new RuleEngine(store, List.of(), Clock.systemUTC());
        for (var pending : journal.pending(128)) {
            engine.acceptCommittedHistory(pending, List.of()); assertTrue(journal.acknowledge(pending.event().id()));
        }
    }

    @Test void continuousSequencePreservesLateExecutionRefundAndExactSourceTimes() {
        var source = new Authority();
        try (var store = new SqliteStore(db())) {
            var journal = new CommittedHistoryJournal(store); var history = new StarCoreTradeHistory(() -> source, journal);
            assertEquals(0, history.scan());
            source.add(receipt(1, null, COMPLETED)); source.add(receipt(2, COMPLETED, DISPUTED)); source.add(receipt(3, DISPUTED, REFUNDED));
            assertEquals(3, history.scan()); var pending = journal.pending(128);
            assertEquals(List.of("TRADE_COMPLETED", "TRADE_DISPUTED", "TRADE_REFUNDED"), pending.stream().map(p -> p.event().type()).toList());
            assertEquals(3, pending.stream().map(p -> p.event().id()).distinct().count());
            assertEquals(receipt(1, null, COMPLETED), pending.getFirst().tradeReceipt());
            assertEquals(TIME.minusSeconds(11), pending.getFirst().event().occurredAt());
            assertEquals(TIME.minusSeconds(1).toString(), pending.getFirst().event().facts().get("history.committed_at"));
            assertEquals(Set.of(SELLER, BUYER), pending.getFirst().event().participants());
            assertTrue(pending.stream().allMatch(CommittedHistoryJournal.Pending::activate));
            consume(store, journal); assertEquals(0, history.scan());
        }
    }

    @Test void initialTwoPageArchiveAndOfflineChangesResumeAsLiveWithoutSkippingAnySequence() {
        var source = new Authority();
        for (int i = 1; i <= 130; i++) source.add(receipt(i, null, trade(COMPLETED, new UUID(0, 1000 + i), SELLER, BUYER, SELLER_NATION, BUYER_NATION, "agricultural", 1, null)));
        try (var store = new SqliteStore(db())) {
            var journal = new CommittedHistoryJournal(store); var history = new StarCoreTradeHistory(() -> source, journal);
            assertEquals(128, history.scan()); assertEquals(128, source.lastLimit);
            assertTrue(journal.pending(128).stream().noneMatch(CommittedHistoryJournal.Pending::activate));
            assertEquals(0, history.scan()); consume(store, journal);
        }
        try (var store = new SqliteStore(db())) {
            var journal = new CommittedHistoryJournal(store); var history = new StarCoreTradeHistory(() -> source, journal);
            assertEquals(2, history.scan()); assertTrue(journal.pending(128).stream().noneMatch(CommittedHistoryJournal.Pending::activate)); consume(store, journal);
        }
        source.add(receipt(131, null, COMPLETED));
        try (var store = new SqliteStore(db())) {
            var journal = new CommittedHistoryJournal(store); var history = new StarCoreTradeHistory(() -> source, journal);
            assertEquals(1, history.scan()); assertTrue(journal.pending(128).getFirst().activate()); consume(store, journal);
            assertEquals(0, history.scan()); assertEquals(131, journal.cursor(StarCoreTradeHistory.NAMESPACE).orElseThrow().sequence());
        }
    }

    @Test void legacyOrAbsentSourceIsIgnoredUntilPinnedThenDowngradeFailsClosed() {
        AtomicReference<Object> source = new AtomicReference<>(new Object());
        try (var store = new SqliteStore(db())) {
            var journal = new CommittedHistoryJournal(store); var history = new StarCoreTradeHistory(source::get, journal);
            assertEquals(0, history.scan()); assertTrue(journal.cursor(StarCoreTradeHistory.NAMESPACE).isEmpty());
            var unsupported = new Authority(); unsupported.failure = new UnsupportedOperationException("legacy default"); source.set(unsupported);
            assertEquals(0, history.scan());
            source.set(new Authority()); assertEquals(0, history.scan());
            source.set(null); assertThrows(IllegalStateException.class, history::scan);
        }
        try (var store = new SqliteStore(db())) {
            source.set(new Object()); var history = new StarCoreTradeHistory(source::get, new CommittedHistoryJournal(store));
            assertThrows(IllegalStateException.class, history::scan);
        }
    }

    @Test void changedDomainRollbackAndSourceFailureLeavePinnedCursorUntouched() {
        var source = new Authority(); source.add(receipt(1, null, COMPLETED));
        try (var store = new SqliteStore(db())) {
            var journal = new CommittedHistoryJournal(store); var history = new StarCoreTradeHistory(() -> source, journal);
            history.scan(); consume(store, journal);
            source.domain = UUID.randomUUID(); assertThrows(IllegalStateException.class, history::scan);
            source.domain = DOMAIN; source.failure = new IllegalStateException("SQL unavailable"); assertThrows(IllegalStateException.class, history::scan);
            source.failure = null; source.changes.clear(); assertThrows(IllegalStateException.class, history::scan);
            assertEquals(1, journal.cursor(StarCoreTradeHistory.NAMESPACE).orElseThrow().sequence());
            assertTrue(journal.pending(128).isEmpty());
        }
    }

    @Test void malformedPageAndDomainSwitchDuringReadCannotStagePartialHistory() {
        var source = new Authority(); NativeChange first = nativeReceipt(receipt(1, null, COMPLETED));
        try (var store = new SqliteStore(db())) {
            var journal = new CommittedHistoryJournal(store); var history = new StarCoreTradeHistory(() -> source, journal);
            for (var page : List.of(List.of(first, nativeReceipt(receipt(3, COMPLETED, REFUNDED))),
                    List.of(new NativeChange(UUID.randomUUID(), 1, TIME, TIME, null, first.record())),
                    List.of(new NativeChange(DOMAIN, 1, null, TIME, null, first.record())),
                    List.of(new NativeChange(DOMAIN, 1, TIME, null, null, first.record())), Collections.nCopies(129, first))) {
                source.invalid = page; assertThrows(IllegalArgumentException.class, history::scan);
                assertTrue(journal.cursor(StarCoreTradeHistory.NAMESPACE).isEmpty()); assertTrue(journal.pending(128).isEmpty());
            }
            source.invalid = List.of(first); source.duringRead = () -> source.domain = UUID.randomUUID();
            assertThrows(IllegalStateException.class, history::scan); assertTrue(journal.cursor(StarCoreTradeHistory.NAMESPACE).isEmpty());
        }
    }

    @Test void stageFailureRollsBackFullReceiptAndCursorThenSourceCanBeReadAgain() {
        var source = new Authority(); source.add(receipt(1, null, COMPLETED));
        try (var store = new SqliteStore(db())) {
            var journal = new CommittedHistoryJournal(store); var history = new StarCoreTradeHistory(() -> source, journal);
            store.transaction(c -> { try(var s=c.createStatement()){s.execute("CREATE TRIGGER fail_payload BEFORE INSERT ON committed_trade_ingress BEGIN SELECT RAISE(ABORT,'payload fault'); END");} return null; });
            assertThrows(IllegalStateException.class, history::scan); assertTrue(journal.cursor(StarCoreTradeHistory.NAMESPACE).isEmpty()); assertTrue(journal.pending(128).isEmpty());
            store.transaction(c -> { try(var s=c.createStatement()){s.execute("DROP TRIGGER fail_payload");} return null; });
            assertEquals(1, history.scan()); consume(store, journal); assertEquals(0, history.scan());
        }
    }

    @Test void allNativeOrderSourcesAndLongNotesAreAcceptedWithoutGuessingAnActor() throws Exception {
        var base = trade(COMPLETED, RECORD, null, null, SELLER_NATION, BUYER_NATION, "r".repeat(64), Long.MAX_VALUE, "n".repeat(5000));
        for (String source : List.of("PLAYER", "NATION", "MARKET", "EVENT")) {
            var trade = new Trade(base.recordId(), "SELL", source, null, null, base.sellerNation(), base.buyerNation(), base.resource(), base.amount(), base.pricePerUnit(),
                    base.totalValue(), base.taxAmount(), base.netValue(), base.executedAt(), null, null, COMPLETED, base.notes());
            var receipt = receipt(1, null, trade);
            var entry = StarCoreTradeHistory.project(nativeReceipt(receipt), DOMAIN, 1);
            assertEquals(receipt, entry.tradeReceipt()); assertTrue(entry.event().participants().isEmpty());
        }
    }
}
