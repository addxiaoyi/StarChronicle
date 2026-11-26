package io.github.addxiaoyi.starchronicle.integration;

import io.github.addxiaoyi.starchronicle.api.processing.ProcessingHistoryReceipt;
import io.github.addxiaoyi.starchronicle.api.contribution.*;
import io.github.addxiaoyi.starchronicle.core.*;
import io.github.addxiaoyi.starchronicle.history.*;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class StarCoreProcessingHistoryTest {
    @TempDir Path directory;
    static final UUID DOMAIN = new UUID(0,3), NATION = new UUID(0,7), ACTOR = new UUID(0,8);
    static final Instant TIME = Instant.parse("2026-11-26T01:02:03.123456789Z");
    public record NativeRun(UUID runId, UUID factoryId, UUID nationId, UUID actorId, String recipeId, int batches,
                            Map<String,Long> inputs, Map<String,Long> outputs, Instant startedAt, Instant readyAt, Instant completedAt) {}
    public record NativeReceipt(UUID authorityDomainId, long sequence, ProcessingHistoryReceipt.Kind kind, NativeRun run, Instant committedAt) {}
    public static class Authority {
        UUID domain = DOMAIN; List<NativeReceipt> receipts = new ArrayList<>(); List<?> invalid;
        public Optional<UUID> processingHistoryAuthorityDomainId() { return Optional.ofNullable(domain); }
        public List<?> processingChangesAfter(long after, int limit) {
            if (invalid != null) return invalid;
            if (after > receipts.size()) throw new IllegalStateException("Source backup rolled back");
            return receipts.stream().filter(r -> r.sequence() > after).limit(limit).toList();
        }
    }
    Path db() { return directory.resolve("processing.db"); }
    NativeReceipt receipt(long seq, long id, ProcessingHistoryReceipt.Kind kind, UUID actor, long output) {
        boolean automatic = kind == ProcessingHistoryReceipt.Kind.AUTOMATIC;
        Instant ready = automatic ? TIME : TIME.plusSeconds(5), done = kind == ProcessingHistoryReceipt.Kind.STARTED ? null : ready.plusSeconds(1);
        var run = new NativeRun(new UUID(0,id), automatic ? null : new UUID(1,id), NATION, actor, "native-smelt", 2,
                Map.of("mineral",6L,"energy",1L), Map.of("industrial",output,"chemical",2L), TIME, ready, done);
        return new NativeReceipt(DOMAIN, seq, kind, run, done == null ? TIME : done);
    }
    NativeReceipt start(long seq, long id) { return receipt(seq,id,ProcessingHistoryReceipt.Kind.STARTED,ACTOR,4); }
    NativeReceipt done(long seq, long id) { return receipt(seq,id,ProcessingHistoryReceipt.Kind.COMPLETED,ACTOR,4); }
    RuleEngine engine(SqliteStore store) { return new RuleEngine(store,List.of(),Clock.fixed(TIME.plusSeconds(10),ZoneOffset.UTC)); }
    void consume(SqliteStore store, CommittedHistoryJournal journal) {
        for (var p : journal.pending(128)) {
            var engine = engine(store); engine.acceptCommittedHistory(p,List.of());
            assertEquals(p.processingReceipt(),engine.processingHistoryReceipt(p.event().id()).orElseThrow());
            assertTrue(journal.acknowledge(p.event().id()));
        }
    }
    long count(SqliteStore store,String table) {
        return store.transaction(c -> { try (var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM "+table)) { assertTrue(r.next());return r.getLong(1); } });
    }
    void sql(SqliteStore store,String sql) { store.transaction(c -> { try(var s=c.createStatement()){s.execute(sql);}return null; }); }
    BigDecimal total(SqliteStore store,String subject,String unit) {
        return new ContributionLedger(store,Clock.systemUTC()).total(subject,ContributionType.CIVILIZATION,unit);
    }
    @Test void completeFrozenRunProducesExactOriginalPlayerAndNationContributionsOnce() {
        var source=new Authority();
        try(var store=new SqliteStore(db())) {
            var journal=new CommittedHistoryJournal(store);var history=new StarCoreProcessingHistory(()->source,journal);
            assertEquals(0,history.scan());source.receipts.add(start(1,1));history.scan();consume(store,journal);
            assertEquals(0,count(store,"contributions"));source.receipts.add(done(2,1));history.scan();
            var p=journal.pending(128).getFirst();assertTrue(p.activate());assertEquals(Set.of(ACTOR),p.event().participants());
            engine(store).acceptCommittedHistory(p,List.of());engine(store).acceptCommittedHistory(p,List.of());assertTrue(journal.acknowledge(p.event().id()));
            assertEquals(4,count(store,"contributions"));
            assertEquals(new BigDecimal("4"),total(store,"player:"+ACTOR,"industrial"));
            assertEquals(new BigDecimal("4"),total(store,"nation:"+NATION,"industrial"));
            assertEquals(new BigDecimal("2"),total(store,"player:"+ACTOR,"chemical"));
            assertEquals(TIME,p.processingReceipt().run().startedAt());
            assertEquals(Map.of("energy",1L,"mineral",6L),p.processingReceipt().run().inputs());
        }
        try(var store=new SqliteStore(db())) {
            assertEquals(0,new StarCoreProcessingHistory(()->source,new CommittedHistoryJournal(store)).scan());
            assertEquals(new BigDecimal("4"),total(store,"player:"+ACTOR,"industrial"));assertEquals(4,count(store,"contributions"));
        }
    }
    @Test void archiveCatchupCrosses128BoundaryAndRestartsWithCompleteEvidenceAndNoLiveReplay() {
        var source=new Authority();for(int i=1;i<=66;i++){source.receipts.add(start(i*2-1,i));source.receipts.add(done(i*2,i));}
        try(var store=new SqliteStore(db())) {
            var journal=new CommittedHistoryJournal(store);var history=new StarCoreProcessingHistory(()->source,journal);
            assertEquals(128,history.scan());assertTrue(journal.pending(128).stream().noneMatch(CommittedHistoryJournal.Pending::activate));
            consume(store,journal);assertEquals(4,history.scan());assertEquals(0,history.scan());
        }
        try(var store=new SqliteStore(db())) {
            var journal=new CommittedHistoryJournal(store);assertTrue(journal.pending(128).stream().noneMatch(CommittedHistoryJournal.Pending::activate));consume(store,journal);
            assertEquals(132,count(store,"processing_history_receipts"));assertEquals(264,count(store,"contributions"));
            assertEquals(new BigDecimal("264"),total(store,"nation:"+NATION,"industrial"));
            source.receipts.add(start(133,67));var history=new StarCoreProcessingHistory(()->source,journal);assertEquals(1,history.scan());
            assertTrue(journal.pending(128).getFirst().activate());consume(store,journal);
        }
    }
    @Test void systemAndUnattributedRunsNeverInventPersonalOrNationalContribution() {
        var source=new Authority();source.receipts.add(receipt(1,1,ProcessingHistoryReceipt.Kind.AUTOMATIC,null,4));
        source.receipts.add(receipt(2,2,ProcessingHistoryReceipt.Kind.STARTED,null,4));source.receipts.add(receipt(3,2,ProcessingHistoryReceipt.Kind.COMPLETED,null,4));
        try(var store=new SqliteStore(db())) {
            var journal=new CommittedHistoryJournal(store);new StarCoreProcessingHistory(()->source,journal).scan();consume(store,journal);
            assertEquals(3,count(store,"processing_history_receipts"));assertEquals(0,count(store,"contributions"));
        }
    }
    @Test void signedLongOutputIsPreservedWithoutSplittingOrCappingContributions() {
        var source=new Authority();source.receipts.add(receipt(1,1,ProcessingHistoryReceipt.Kind.STARTED,ACTOR,Long.MAX_VALUE));
        source.receipts.add(receipt(2,1,ProcessingHistoryReceipt.Kind.COMPLETED,ACTOR,Long.MAX_VALUE));
        try(var store=new SqliteStore(db())) {
            var journal=new CommittedHistoryJournal(store);new StarCoreProcessingHistory(()->source,journal).scan();consume(store,journal);
            assertEquals(BigDecimal.valueOf(Long.MAX_VALUE),total(store,"player:"+ACTOR,"industrial"));assertEquals(4,count(store,"contributions"));
        }
    }
    @Test void changedActorRecipeOutputsOrMissingStartRejectCompletionWithoutAdvancingContribution() throws Exception {
        try(var store=new SqliteStore(db())) {
            var engine=engine(store);var start=StarCoreProcessingHistory.project(start(1,1),DOMAIN,1);
            engine.acceptCommittedHistory(pending(start),List.of());
            var done=done(2,1);var r=done.run();
            List<NativeRun> invalid=List.of(
                    new NativeRun(r.runId(),r.factoryId(),r.nationId(),UUID.randomUUID(),r.recipeId(),r.batches(),r.inputs(),r.outputs(),r.startedAt(),r.readyAt(),r.completedAt()),
                    new NativeRun(r.runId(),r.factoryId(),r.nationId(),r.actorId(),"new-recipe",r.batches(),r.inputs(),r.outputs(),r.startedAt(),r.readyAt(),r.completedAt()),
                    new NativeRun(r.runId(),r.factoryId(),r.nationId(),r.actorId(),r.recipeId(),r.batches(),r.inputs(),Map.of("industrial",40L),r.startedAt(),r.readyAt(),r.completedAt()),
                    new NativeRun(UUID.randomUUID(),r.factoryId(),r.nationId(),r.actorId(),r.recipeId(),r.batches(),r.inputs(),r.outputs(),r.startedAt(),r.readyAt(),r.completedAt()));
            for(var bad:invalid) {
                var entry=StarCoreProcessingHistory.project(new NativeReceipt(DOMAIN,2,done.kind(),bad,done.committedAt()),DOMAIN,2);
                assertThrows(IllegalArgumentException.class,()->engine.acceptCommittedHistory(pending(entry),List.of()));
            }
            assertEquals(1,count(store,"events"));assertEquals(1,count(store,"processing_history_receipts"));assertEquals(0,count(store,"contributions"));
        }
    }
    @Test void ingressFailureRollsBackSourceCursorAndFullReceiptTogether() {
        var source=new Authority();source.receipts.add(start(1,1));
        try(var store=new SqliteStore(db())) {
            var journal=new CommittedHistoryJournal(store);var history=new StarCoreProcessingHistory(()->source,journal);
            sql(store,"CREATE TRIGGER reject_ingress BEFORE INSERT ON committed_processing_ingress BEGIN SELECT RAISE(ABORT,'fault'); END");
            assertThrows(RuntimeException.class,history::scan);assertTrue(journal.cursor(ProcessingHistoryProjection.NAMESPACE).isEmpty());
            assertEquals(0,count(store,"committed_history_ingress"));assertEquals(0,count(store,"committed_processing_ingress"));
        }
    }
    @Test void contributionFailureRollsBackEventFullReceiptAndLedgerThenRestartConsumesOnce() {
        var source=new Authority();source.receipts.add(start(1,1));source.receipts.add(done(2,1));
        String event;
        try(var store=new SqliteStore(db())) {
            var journal=new CommittedHistoryJournal(store);new StarCoreProcessingHistory(()->source,journal).scan();
            var rows=journal.pending(128);engine(store).acceptCommittedHistory(rows.getFirst(),List.of());journal.acknowledge(rows.getFirst().event().id());
            var p=rows.getLast();event=p.event().id();
            sql(store,"CREATE TRIGGER reject_contribution BEFORE INSERT ON contributions WHEN NEW.subject_id LIKE 'nation:%' BEGIN SELECT RAISE(ABORT,'fault'); END");
            assertThrows(RuntimeException.class,()->engine(store).acceptCommittedHistory(p,List.of()));
            assertEquals(1,count(store,"events"));assertEquals(1,count(store,"processing_history_receipts"));assertEquals(0,count(store,"contributions"));
            assertFalse(journal.acknowledge(event));sql(store,"DROP TRIGGER reject_contribution");
        }
        try(var store=new SqliteStore(db())) {
            var journal=new CommittedHistoryJournal(store);consume(store,journal);assertEquals(4,count(store,"contributions"));
            assertEquals(2,count(store,"events"));assertFalse(journal.acknowledge(event));
        }
    }
    @Test void eventAloneCannotAckAndCollidingRunIdentityCannotReplaceFullEvidence() throws Exception {
        var entry=StarCoreProcessingHistory.project(start(1,1),DOMAIN,1);
        try(var store=new SqliteStore(db())) {
            var journal=new CommittedHistoryJournal(store);journal.stage(ProcessingHistoryProjection.NAMESPACE,DOMAIN,0,List.of(entry));
            var engine=engine(store);engine.archiveCommittedHistory(entry.event());assertFalse(journal.acknowledge(entry.event().id()));
            assertThrows(IllegalArgumentException.class,()->engine.acceptCommittedHistory(new CommittedHistoryJournal.Pending(entry.event(),false),List.of()));
            consume(store,journal);
            var collision=StarCoreProcessingHistory.project(receipt(2,1,ProcessingHistoryReceipt.Kind.STARTED,ACTOR,999),DOMAIN,2);
            assertThrows(IllegalArgumentException.class,()->engine.acceptCommittedHistory(pending(collision),List.of()));
            assertEquals(entry.processingReceipt(),engine.processingHistoryReceipt(entry.event().id()).orElseThrow());
        }
    }
    @Test void domainChangesRollbackMissingAndDowngradedAuthoritiesKeepPinnedCursor() {
        AtomicReference<Object> source=new AtomicReference<>(new Object());
        try(var store=new SqliteStore(db())) {
            var journal=new CommittedHistoryJournal(store);var history=new StarCoreProcessingHistory(source::get,journal);
            assertEquals(0,history.scan());var authority=new Authority();authority.receipts.add(start(1,1));source.set(authority);
            history.scan();consume(store,journal);authority.domain=UUID.randomUUID();assertThrows(IllegalStateException.class,history::scan);
            authority.domain=DOMAIN;authority.receipts.clear();assertThrows(IllegalStateException.class,history::scan);
            source.set(null);assertThrows(IllegalStateException.class,history::scan);source.set(new Object());assertThrows(IllegalStateException.class,history::scan);
            assertEquals(1,journal.cursor(ProcessingHistoryProjection.NAMESPACE).orElseThrow().sequence());
        }
    }
    @Test void malformedPageOrFrozenRunNeverPinsACursor() {
        var source=new Authority();var valid=start(1,1);var r=valid.run();
        var bad=new NativeRun(r.runId(),r.factoryId(),r.nationId(),r.actorId(),r.recipeId(),r.batches(),Map.of("energy",0L),r.outputs(),r.startedAt(),r.readyAt(),null);
        try(var store=new SqliteStore(db())) {
            var journal=new CommittedHistoryJournal(store);var history=new StarCoreProcessingHistory(()->source,journal);
            for(var invalid:List.of(List.of(start(2,1)),Collections.nCopies(129,valid),
                    List.of(new NativeReceipt(UUID.randomUUID(),1,valid.kind(),r,valid.committedAt())),
                    List.of(new NativeReceipt(DOMAIN,1,valid.kind(),bad,valid.committedAt())))) {
                source.invalid=invalid;assertThrows(IllegalArgumentException.class,history::scan);
                assertTrue(journal.cursor(ProcessingHistoryProjection.NAMESPACE).isEmpty());assertTrue(journal.pending(128).isEmpty());
            }
        }
    }
    @Test void ordinaryResourceDeltaForTheSameRunCannotDoubleCountCivilization() throws Exception {
        try(var store=new SqliteStore(db())) {
            var engine=engine(store);
            engine.acceptCommittedHistory(pending(StarCoreProcessingHistory.project(start(1,1),DOMAIN,1)),List.of());
            engine.acceptCommittedHistory(pending(StarCoreProcessingHistory.project(done(2,1),DOMAIN,2)),List.of());
            var raw=new StarCoreResourceHistoryTest.Receipt(DOMAIN,1,UUID.randomUUID(),io.github.addxiaoyi.starchronicle.api.resource.ResourceHistoryReceipt.Kind.ADJUSTMENT,
                    new UUID(0,1).toString(),"PROCESSING_COMPLETED","native-smelt",ACTOR,TIME.plusSeconds(6),
                    List.of(new StarCoreResourceHistoryTest.Balance(NATION,"industrial",0,4)));
            var resource=StarCoreResourceHistory.project(raw,DOMAIN,1);
            engine.acceptCommittedHistory(new CommittedHistoryJournal.Pending(resource.event(),false,null,resource.resourceReceipt()),List.of());
            assertEquals(4,count(store,"contributions"));assertEquals(new BigDecimal("4"),total(store,"player:"+ACTOR,"industrial"));
        }
    }
    private static CommittedHistoryJournal.Pending pending(CommittedHistoryJournal.Entry e) {
        return new CommittedHistoryJournal.Pending(e.event(),false,null,null,e.processingReceipt());
    }
}
