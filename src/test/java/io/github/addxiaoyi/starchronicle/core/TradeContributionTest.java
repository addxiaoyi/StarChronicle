package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.api.contribution.*;
import io.github.addxiaoyi.starchronicle.api.trade.TradeHistoryReceipt;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition.*;
import io.github.addxiaoyi.starchronicle.history.*;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static io.github.addxiaoyi.starchronicle.api.trade.TradeHistoryReceipt.Status.*;
import static io.github.addxiaoyi.starchronicle.history.TradeHistoryFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class TradeContributionTest {
    @TempDir Path directory;
    Path db() { return directory.resolve("trades.db"); }
    static final String SUBJECT = "player:" + SELLER;
    static final PlayerContext CONTEXT = new PlayerContext(SELLER, "new-nation-after-trade", Map.of(), Set.of());
    RuleEngine engine(SqliteStore store) { return new RuleEngine(store, List.of(story()), Clock.fixed(TIME.minusSeconds(1000), ZoneOffset.UTC)); }
    StoryDefinition story() {
        return new StoryDefinition("trade-test", "1", "Trade", Scope.PLAYER, List.of("TRADE_COMPLETED"), null, "done", 0,
                Map.of("done", new Node("Done", null, null, 1, Map.of(), List.of(new Effect("honor", "honor", "player", "3", null)), true)));
    }
    void commit(RuleEngine engine, TradeHistoryReceipt receipt) { engine.acceptCommittedHistory(pending(receipt, true), List.of(CONTEXT)); }
    void total(RuleEngine engine, long expected) { assertEquals(0, BigDecimal.valueOf(expected).compareTo(engine.contributionTotal(SUBJECT, ContributionType.TRADE, "agricultural"))); }
    void sql(SqliteStore store, String sql) { store.transaction(c -> { try (var s = c.createStatement()) { s.execute(sql); } return null; }); }
    long count(SqliteStore store, String table) { return store.transaction(c -> { try(var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM "+table)){return r.getLong(1);} }); }

    @Test void disputesRefundsAndCancellationsChangeNetTotalsWithoutDeletingOriginalEvidence() {
        try (var store = new SqliteStore(db())) {
            var engine = engine(store);
            commit(engine, receipt(1, null, COMPLETED)); total(engine, 7);
            var original = engine.contributions(SUBJECT, ContributionType.TRADE, 0, 10).getFirst();
            assertEquals(SELLER_NATION.toString(), original.request().nationId(), "Current context must never rewrite historical nation");
            commit(engine, receipt(2, COMPLETED, DISPUTED)); total(engine, 0);
            assertEquals(ContributionRevision.Status.SUSPENDED, engine.contributionBalances(SUBJECT, null, 0, 10).getFirst().status());
            commit(engine, receipt(3, DISPUTED, REFUNDED)); total(engine, 0);
            commit(engine, receipt(4, REFUNDED, CANCELLED)); total(engine, 0);
            assertEquals(List.of(original), engine.contributions(SUBJECT, ContributionType.TRADE, 0, 10));
            assertEquals(4, engine.contributions(null, ContributionType.TRADE, 0, 100).size());
            assertEquals(List.of(ContributionRevision.Status.CANCELLED, ContributionRevision.Status.REFUNDED,
                    ContributionRevision.Status.SUSPENDED, ContributionRevision.Status.ACTIVE),
                    engine.contributionRevisions(original.id(), 0, 100).stream().map(ContributionRevision::status).toList());
            assertEquals(4, count(store, "trade_history_receipts"));
        }
        try (var store = new SqliteStore(db())) { var engine = engine(store); total(engine, 0); commit(engine, receipt(1, null, COMPLETED)); total(engine, 0); }
    }

    @Test void resumedCompletionAndNoteUpdatesReuseOriginalPositiveEntry() {
        try (var store = new SqliteStore(db())) {
            var engine = engine(store);
            commit(engine, receipt(1, null, COMPLETED));
            var original = engine.contributions(SUBJECT, null, 0, 10).getFirst();
            commit(engine, receipt(2, COMPLETED, DISPUTED));
            commit(engine, receipt(3, DISPUTED, COMPLETED)); total(engine, 7);
            var changedNotes = trade(COMPLETED, RECORD, SELLER, BUYER, SELLER_NATION, BUYER_NATION, "agricultural", 7, "review completed");
            commit(engine, receipt(4, COMPLETED, changedNotes)); total(engine, 7);
            assertEquals(List.of(original), engine.contributions(SUBJECT, null, 0, 10));
            assertEquals("3", engine.facts(SELLER).get("player.honor"), "Restoration/notes must not repeat initial-completion rewards");
            assertEquals(4, engine.contributionRevisions(original.id(), 0, 100).size());
            assertEquals(1, engine.contributionRevisions(original.id(), 3, 1).size());
            assertTrue(engine.contributionRevisions(original.id(), 4, 1).isEmpty());
        }
    }

    @Test void firstSeenOldRefundHasNoSyntheticPositiveOrNegativeContribution() {
        try (var store = new SqliteStore(db())) {
            var engine = engine(store); commit(engine, receipt(1, COMPLETED, REFUNDED)); total(engine, 0);
            assertTrue(engine.contributions(null, null, 0, 100).isEmpty());
            assertEquals(receipt(1, COMPLETED, REFUNDED), engine.tradeHistoryReceipt(TradeHistoryProjection.eventId(receipt(1, COMPLETED, REFUNDED))).orElseThrow());
            commit(engine, receipt(2, REFUNDED, DISPUTED)); total(engine, 0);
            commit(engine, receipt(3, DISPUTED, COMPLETED)); total(engine, 7);
        }
    }

    @Test void archiveBuildsNetLedgerWithoutStoryEffectsAndCannotBeReactivatedLater() {
        try (var store = new SqliteStore(db())) {
            var engine = engine(store);
            for (var r : List.of(receipt(1, null, COMPLETED), receipt(2, COMPLETED, REFUNDED)))
                engine.acceptCommittedHistory(pending(r, false), List.of(CONTEXT));
            total(engine, 0); assertTrue(engine.instances(SELLER).isEmpty()); assertTrue(engine.facts(SELLER).isEmpty());
            assertTrue(engine.acceptCommittedHistory(pending(receipt(1, null, COMPLETED), true), List.of(CONTEXT)).duplicate());
            engine.resumeTriggers(List.of(CONTEXT)); assertTrue(engine.instances(SELLER).isEmpty());
            total(engine, 0); assertEquals(8, count(store, "contribution_revisions"));
        }
    }

    @Test void contributionInsertFailureRollsBackHistoryStoriesAndReceiptThenRetriesOnce() {
        try (var store = new SqliteStore(db())) {
            var engine = engine(store);
            sql(store, "CREATE TRIGGER fail_trade BEFORE INSERT ON contributions BEGIN SELECT RAISE(ABORT,'contribution fault'); END");
            assertThrows(IllegalStateException.class, () -> commit(engine, receipt(1, null, COMPLETED)));
            assertTrue(engine.history(null, null, 0, 100).isEmpty()); assertTrue(engine.facts(SELLER).isEmpty());
            assertTrue(engine.instances(SELLER).isEmpty()); assertEquals(0, count(store, "trade_history_receipts"));
            assertEquals(0, count(store, "story_triggers"));
            sql(store, "DROP TRIGGER fail_trade"); commit(engine, receipt(1, null, COMPLETED)); total(engine, 7);
            commit(engine, receipt(1, null, COMPLETED)); total(engine, 7); assertEquals("3", engine.facts(SELLER).get("player.honor"));
        }
    }

    @Test void revisionInsertFailureRollsBackRefundAndKeepsOriginalBalanceUntilRetry() {
        try (var store = new SqliteStore(db())) {
            var engine = engine(store); commit(engine, receipt(1, null, COMPLETED));
            sql(store, "CREATE TRIGGER fail_revision BEFORE INSERT ON contribution_revisions BEGIN SELECT RAISE(ABORT,'revision fault'); END");
            assertThrows(IllegalStateException.class, () -> commit(engine, receipt(2, COMPLETED, REFUNDED))); total(engine, 7);
            assertTrue(engine.tradeHistoryReceipt(TradeHistoryProjection.eventId(receipt(2, COMPLETED, REFUNDED))).isEmpty());
            assertEquals(1, count(store, "trade_history_receipts"));
            sql(store, "DROP TRIGGER fail_revision"); commit(engine, receipt(2, COMPLETED, REFUNDED)); total(engine, 0);
        }
    }

    @Test void identityOrPreviousStatusChangeCannotPartiallyCommit() {
        try (var store = new SqliteStore(db())) {
            var engine = engine(store); commit(engine, receipt(1, null, COMPLETED));
            var altered = trade(REFUNDED, RECORD, SELLER, BUYER, UUID.randomUUID(), BUYER_NATION, "agricultural", 7, "changed country");
            assertThrows(IllegalArgumentException.class, () -> commit(engine, receipt(2, COMPLETED, altered)));
            assertThrows(IllegalArgumentException.class, () -> commit(engine, receipt(2, DISPUTED, REFUNDED)));
            assertEquals(1, count(store, "trade_history_receipts")); total(engine, 7);
            var good = receipt(2, COMPLETED, REFUNDED);
            var forged = new CommittedHistoryJournal.Pending(TradeHistoryProjection.event(receipt(2, DISPUTED, REFUNDED)), true, good);
            assertThrows(IllegalArgumentException.class, () -> engine.acceptCommittedHistory(forged, List.of()));
        }
    }

    @Test void longQuantityResourceAndNotesAreLosslessAndRefundable() {
        String unit = "r".repeat(64), notes = "完整备注".repeat(2000);
        var t = trade(COMPLETED, RECORD, SELLER, BUYER, SELLER_NATION, BUYER_NATION, unit, Long.MAX_VALUE, notes);
        var receipt = receipt(1, null, t);
        try (var store = new SqliteStore(db())) {
            var engine = engine(store); commit(engine, receipt);
            assertEquals(0, BigDecimal.valueOf(Long.MAX_VALUE).compareTo(engine.contributionTotal(SUBJECT, ContributionType.TRADE, unit)));
            assertEquals(receipt, engine.tradeHistoryReceipt(TradeHistoryProjection.eventId(receipt)).orElseThrow());
            assertFalse(TradeHistoryProjection.event(receipt).facts().containsKey("trade.notes"));
            assertEquals(Integer.toString(notes.length()), TradeHistoryProjection.event(receipt).facts().get("trade.notes_length"));
            var refund = receipt(2, COMPLETED, trade(REFUNDED, RECORD, SELLER, BUYER, SELLER_NATION, BUYER_NATION, unit, Long.MAX_VALUE, notes));
            commit(engine, refund); assertEquals(BigDecimal.ZERO, engine.contributionTotal(SUBJECT, ContributionType.TRADE, unit));
            assertThrows(IllegalArgumentException.class, () -> ContributionRequest.amount(BigDecimal.valueOf(Long.MAX_VALUE)), "Aid monetary limits remain unchanged");
        }
    }

    @Test void sameNationCountsOnceAndQuickSellUsesSellerDespiteLegacyBuyOrderType() {
        try (var store = new SqliteStore(db())) {
            var engine = engine(store);
            commit(engine, receipt(1, null, trade(COMPLETED, RECORD, SELLER, BUYER, SELLER_NATION, SELLER_NATION, "agricultural", 7, "intranation")));
            assertEquals(3, engine.contributions(null, ContributionType.TRADE, 0, 100).size());
            assertEquals(0, BigDecimal.valueOf(7).compareTo(engine.contributionTotal("nation:" + SELLER_NATION, ContributionType.TRADE, "agricultural")));
            commit(engine, receipt(2, null, trade(COMPLETED, UUID.randomUUID(), SELLER, null, SELLER_NATION, null, "agricultural", 2, "quick sell")));
            total(engine, 9); assertEquals(5, engine.contributions(null, ContributionType.TRADE, 0, 100).size());
        }
    }

    @Test void nationOnlyHistoryNeverInventsFoundersOrPersonalContributions() {
        try (var store = new SqliteStore(db())) {
            var engine = engine(store);
            var receipt = receipt(1, null, trade(COMPLETED, RECORD, null, null, SELLER_NATION, BUYER_NATION, "agricultural", 7, null));
            engine.acceptCommittedHistory(pending(receipt, true), List.of());
            assertTrue(engine.contributions(null, null, 0, 100).isEmpty());
            var event = TradeHistoryProjection.event(receipt); assertTrue(event.participants().isEmpty());
            assertEquals(Set.of(SELLER_NATION.toString(), BUYER_NATION.toString()), event.nations());
            assertEquals("NO_NATIVE_PLAYER_ACTOR", event.facts().get("contribution.omission"));
        }
    }

    @Test void stagedPayloadSurvivesRestartAndAckRequiresContributionCommit() {
        var receipt = receipt(1, null, COMPLETED);
        try (var store = new SqliteStore(db())) {
            var journal = new CommittedHistoryJournal(store);
            journal.stage(TradeHistoryProjection.NAMESPACE, DOMAIN, 0, List.of());
            journal.stage(TradeHistoryProjection.NAMESPACE, DOMAIN, 0, List.of(entry(receipt)));
            engine(store).archiveCommittedHistory(TradeHistoryProjection.event(receipt));
            assertFalse(journal.acknowledge(TradeHistoryProjection.eventId(receipt)), "A history row alone cannot acknowledge contributions");
        }
        try (var store = new SqliteStore(db())) {
            var journal = new CommittedHistoryJournal(store); var engine = engine(store);
            assertEquals(receipt, journal.pending(128).getFirst().tradeReceipt());
            engine.acceptCommittedHistory(journal.pending(128).getFirst(), List.of(CONTEXT)); total(engine, 7);
            // Simulate death after consumer commit but before ingress ACK.
        }
        try (var store = new SqliteStore(db())) {
            var journal = new CommittedHistoryJournal(store); var engine = engine(store);
            assertTrue(engine.acceptCommittedHistory(journal.pending(128).getFirst(), List.of(CONTEXT)).duplicate());
            assertTrue(journal.acknowledge(TradeHistoryProjection.eventId(receipt))); total(engine, 7);
            assertEquals(4, count(store, "contribution_revisions")); assertTrue(journal.pending(128).isEmpty());
            assertThrows(IllegalArgumentException.class, () -> journal.stage(TradeHistoryProjection.NAMESPACE, DOMAIN, 1,
                    List.of(new CommittedHistoryJournal.Entry(2, TradeHistoryProjection.event(receipt(2, COMPLETED, REFUNDED))))));
        }
    }

    @Test void oldSchemaContributionsKeepTheirNetValueAndUpgradePreventsOldReaderRollback() {
        String before;
        try (var store = new SqliteStore(db())) {
            var engine = engine(store); var event = TradeHistoryProjection.event(receipt(1, null, COMPLETED));
            engine.archiveCommittedHistory(event);
            new ContributionLedger(store, Clock.fixed(TIME, ZoneOffset.UTC)).record(new ContributionRequest("old-operation", ContributionType.TRADE,
                    SUBJECT, event.id(), SELLER, SELLER_NATION.toString(), BigDecimal.valueOf(9), "agricultural", TIME, "legacy receipt"));
            before = SqliteStore.json(engine.contributions(null, null, 0, 100));
            sql(store, "DROP TABLE contribution_revisions"); sql(store, "DROP TABLE trade_history_receipts"); sql(store, "PRAGMA user_version=7");
        }
        try (var store = new SqliteStore(db())) {
            var engine = engine(store); total(engine, 9);
            assertEquals(before, SqliteStore.json(engine.contributions(null, null, 0, 100)));
            assertEquals(ContributionRevision.Status.ACTIVE, engine.contributionBalances(SUBJECT, null, 0, 10).getFirst().status());
            int version = store.transaction(c -> { try (var s = c.createStatement(); var r = s.executeQuery("PRAGMA user_version")) { return r.getInt(1); } });
            assertEquals(8, version);
        }
    }
}
