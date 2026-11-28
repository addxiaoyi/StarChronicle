package io.github.addxiaoyi.starchronicle.integration;

import io.github.addxiaoyi.starchronicle.api.processing.ProcessingHistoryReceipt;
import io.github.addxiaoyi.starchronicle.api.PlayerContext;
import io.github.addxiaoyi.starchronicle.api.contribution.*;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition.*;
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
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={255,256})
    void anchorPageFullnessKeepsEveryHistoricalCompletionArchiveOnly(int total) {
        var source=new Authority();
        for(int i=1;i<=total;i++)source.receipts.add(receipt(i,i,ProcessingHistoryReceipt.Kind.AUTOMATIC,null,4));
        try(var store=new SqliteStore(db())) {
            var journal=new CommittedHistoryJournal(store);var history=new StarCoreProcessingHistory(()->source,journal);
            assertEquals(128,history.scan());consume(store,journal);
            assertEquals(127,history.scan());assertFalse(journal.cursor(StarCoreProcessingHistory.NAMESPACE).orElseThrow().live());
            assertTrue(journal.pending(128).stream().noneMatch(CommittedHistoryJournal.Pending::activate));consume(store,journal);
            assertEquals(total-255,history.scan());
            assertTrue(journal.pending(128).stream().noneMatch(CommittedHistoryJournal.Pending::activate));consume(store,journal);
            assertTrue(journal.cursor(StarCoreProcessingHistory.NAMESPACE).orElseThrow().live());
            source.receipts.add(start(total+1,total+1));assertEquals(1,history.scan());
            assertTrue(journal.pending(128).getFirst().activate());consume(store,journal);
            assertEquals(total+1,count(store,"processing_history_receipts"));assertEquals(0,count(store,"contributions"));
        }
    }
    @Test void changedIdleAnchorAndRolledBackEmptySourceRetainOriginalCursorAndReceipt() throws Exception {
        var source=new Authority();source.receipts.add(start(1,1));
        try(var store=new SqliteStore(db())) {
            var journal=new CommittedHistoryJournal(store);var history=new StarCoreProcessingHistory(()->source,journal);
            history.scan();consume(store,journal);var cursor=journal.cursor(StarCoreProcessingHistory.NAMESPACE);
            source.invalid=List.of(receipt(1,1,ProcessingHistoryReceipt.Kind.STARTED,ACTOR,999),done(2,1));
            assertThrows(IllegalStateException.class,history::scan);
            source.invalid=List.of();assertThrows(IllegalStateException.class,history::scan);
            assertEquals(cursor,journal.cursor(StarCoreProcessingHistory.NAMESPACE));assertTrue(journal.pending(128).isEmpty());
            assertEquals(1,count(store,"processing_history_receipts"));assertEquals(0,count(store,"contributions"));
        }
    }
    @Test void anchorVerificationIncludesFullProcessingPayloadEvenWhenEventIsUnchanged() throws Exception {
        var source=new Authority();source.receipts.add(start(1,1));
        try(var store=new SqliteStore(db())) {
            var journal=new CommittedHistoryJournal(store);var history=new StarCoreProcessingHistory(()->source,journal);
            history.scan();consume(store,journal);
            var changed=StarCoreProcessingHistory.project(receipt(1,1,ProcessingHistoryReceipt.Kind.STARTED,ACTOR,999),DOMAIN,1);
            store.transaction(c->{try(var s=c.prepareStatement("UPDATE committed_processing_ingress SET json=?")){s.setString(1,SqliteStore.json(changed.processingReceipt()));s.executeUpdate();}return null;});
            assertThrows(IllegalStateException.class,history::scan);
            assertEquals(1,journal.cursor(StarCoreProcessingHistory.NAMESPACE).orElseThrow().sequence());
            assertEquals(0,count(store,"contributions"));
        }
    }
    @Test void processingCoordinatorCannotConsumeOrStarveAnotherNamespace() throws Exception {
        var source=new Authority();source.receipts.add(start(1,1));source.receipts.add(done(2,1));
        try(var store=new SqliteStore(db())) {
            var journal=new CommittedHistoryJournal(store);
            var capital=new StarCoreCapitalHistoryTest();
            var entry=StarCoreCapitalHistory.project(capital.receipt(1),StarCoreCapitalHistoryTest.DOMAIN,1);
            journal.stage(StarCoreCapitalHistory.NAMESPACE,StarCoreCapitalHistoryTest.DOMAIN,0,List.of(entry));
            assertEquals(2,new ProcessingHistoryCoordinator(engine(store),()->source,journal).poll(List.of()));
            assertEquals(1,journal.pending(StarCoreCapitalHistory.NAMESPACE,128).size());
            assertEquals(entry.event(),journal.pending(128).getFirst().event());
            assertTrue(journal.pending(StarCoreProcessingHistory.NAMESPACE,128).isEmpty());
            assertEquals(4,count(store,"contributions"));assertEquals(2,count(store,"processing_history_receipts"));
        }
    }
    @Test void stringIdProductionAndUuidProcessingKeepIndependentAuthoritiesAndOriginalUnitsOnReopen() throws Exception {
        var source=new Authority();source.receipts.add(start(1,1));source.receipts.add(done(2,1));
        var production=new CommittedSourceCollectorTest.ProductionService();
        var oldDomain=CommittedSourceCollectorTest.DOMAIN;
        production.receipts.add(new CommittedSourceCollectorTest.Production("factory:legacy-run",oldDomain.toString(),1,NATION,ACTOR,"native-smelt",2,Map.of("mineral",6L,"energy",1L),Map.of("industrial",4L,"chemical",2L),TIME.plusSeconds(6)));
        String oldReceiptRows;
        try(var store=new SqliteStore(db())) {
            var engine=engine(store);var collector=new CommittedSourceCollector(type->production);
            assertEquals(1,engine.acceptCommittedSource(collector.collect(CommittedSourceCollector.Source.PRODUCTION,Optional.empty()).orElseThrow(),List.of()));
            oldReceiptRows=store.transaction(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT json FROM committed_source_receipts WHERE source='starcore.production'")){assertTrue(r.next());return r.getString(1);}});
            var journal=new CommittedHistoryJournal(store);
            assertEquals(2,new ProcessingHistoryCoordinator(engine,()->source,journal).poll(List.of()));
            assertEquals(8,count(store,"contributions"));
            assertEquals(new BigDecimal("4"),total(store,"player:"+ACTOR,"resource.industrial"));
            assertEquals(new BigDecimal("4"),total(store,"player:"+ACTOR,"industrial"));
        }
        try(var store=new SqliteStore(db())) {
            var engine=engine(store);var collector=new CommittedSourceCollector(type->production);
            var cursor=engine.committedSourceCursor(CommittedSourceCollector.Source.PRODUCTION.id);
            assertEquals(oldDomain.toString(),cursor.orElseThrow().domain());assertEquals(1,cursor.orElseThrow().sequence());
            assertEquals(0,engine.acceptCommittedSource(collector.collect(CommittedSourceCollector.Source.PRODUCTION,cursor).orElseThrow(),List.of()));
            var journal=new CommittedHistoryJournal(store);
            assertEquals(DOMAIN,journal.cursor(StarCoreProcessingHistory.NAMESPACE).orElseThrow().authorityDomain());
            assertEquals(2,journal.cursor(StarCoreProcessingHistory.NAMESPACE).orElseThrow().sequence());
            assertEquals(0,new ProcessingHistoryCoordinator(engine,()->source,journal).poll(List.of()));
            assertEquals(oldReceiptRows,store.transaction(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT json FROM committed_source_receipts WHERE source='starcore.production'")){assertTrue(r.next());return r.getString(1);}}));
            assertEquals(8,count(store,"contributions"));
            assertEquals(new BigDecimal("4"),total(store,"nation:"+NATION,"resource.industrial"));
            assertEquals(new BigDecimal("4"),total(store,"nation:"+NATION,"industrial"));
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void eitherSchemaTenBranchUpgradesWithoutRewritingItsExistingReceiptOrContributionBytes(boolean processingBranch) throws Exception {
        String receiptJson,contributionsJson;
        String receiptTable=processingBranch?"processing_history_receipts":"committed_source_receipts";
        var source=new Authority();source.receipts.add(start(1,1));source.receipts.add(done(2,1));
        var production=new CommittedSourceCollectorTest.ProductionService();
        production.receipts.add(new CommittedSourceCollectorTest.Production("factory:legacy-run",CommittedSourceCollectorTest.DOMAIN.toString(),1,NATION,ACTOR,"native-smelt",2,
                Map.of("mineral",6L),Map.of("industrial",4L),TIME.plusSeconds(6)));
        try(var store=new SqliteStore(db())) {
            var engine=engine(store);
            if(processingBranch) {
                new ProcessingHistoryCoordinator(engine,()->source,new CommittedHistoryJournal(store)).poll(List.of());
                sql(store,"DROP TABLE committed_source_receipts");sql(store,"DROP TABLE committed_source_cursors");
            } else {
                engine.acceptCommittedSource(new CommittedSourceCollector(type->production).collect(CommittedSourceCollector.Source.PRODUCTION,Optional.empty()).orElseThrow(),List.of());
                sql(store,"DROP TABLE processing_history_receipts");
            }
            receiptJson=store.transaction(c->{List<String> rows=new ArrayList<>();try(var s=c.createStatement();var r=s.executeQuery("SELECT json FROM "+receiptTable+" ORDER BY json")){while(r.next())rows.add(r.getString(1));}return SqliteStore.json(rows);});
            contributionsJson=SqliteStore.json(engine.contributions(null,null,0,100));
            sql(store,"PRAGMA user_version=10");
        }
        try(var store=new SqliteStore(db())) {
            assertEquals(11,SqliteStore.SCHEMA_VERSION);
            assertEquals("11",store.transaction(c->{try(var s=c.createStatement();var r=s.executeQuery("PRAGMA user_version")){return r.getString(1);}}));
            assertEquals(receiptJson,store.transaction(c->{List<String> rows=new ArrayList<>();try(var s=c.createStatement();var r=s.executeQuery("SELECT json FROM "+receiptTable+" ORDER BY json")){while(r.next())rows.add(r.getString(1));}return SqliteStore.json(rows);}));
            assertEquals(contributionsJson,SqliteStore.json(engine(store).contributions(null,null,0,100)));
            store.transaction(c->{try(var s=c.createStatement();var r=s.executeQuery("PRAGMA foreign_key_check")){assertFalse(r.next());}return null;});
        }
    }
    RuleEngine storyEngine(SqliteStore store) {
        var story=new StoryDefinition("processing-test","1","Factory",Scope.PLAYER,List.of("PROCESSING_COMPLETED"),null,"done",0,
                Map.of("done",new Node("Done",null,null,1,Map.of(),List.of(new Effect("honor","honor","player","3",null)),true)));
        return new RuleEngine(store,List.of(story),Clock.fixed(TIME.plusSeconds(10),ZoneOffset.UTC));
    }
    @Test void repeatedLiveContributionFailuresRollbackStoryEffectsAndReceiptsThenReopenDeliversOnce() {
        var source=new Authority();var context=new PlayerContext(ACTOR,NATION.toString(),Map.of(),Set.of());
        try(var store=new SqliteStore(db())) {
            var engine=storyEngine(store);var journal=new CommittedHistoryJournal(store);
            var coordinator=new ProcessingHistoryCoordinator(engine,()->source,journal);
            assertEquals(0,coordinator.poll(List.of(context)));source.receipts.add(start(1,1));assertEquals(1,coordinator.poll(List.of(context)));
            sql(store,"CREATE TRIGGER reject_processing_nation BEFORE INSERT ON contributions WHEN NEW.subject_id LIKE 'nation:%' BEGIN SELECT RAISE(ABORT,'fault after player contribution and story effects'); END");
            source.receipts.add(done(2,1));
            for(int attempt=0;attempt<2;attempt++) {
                assertThrows(RuntimeException.class,()->coordinator.poll(List.of(context)));
                assertEquals(1,count(store,"events"));assertEquals(1,count(store,"processing_history_receipts"));
                assertEquals(0,count(store,"contributions"));assertEquals(0,count(store,"instances"));
                assertEquals(0,count(store,"facts"));assertEquals(0,count(store,"story_triggers"));assertEquals(0,count(store,"story_trigger_receipts"));
                assertEquals(1,journal.pending(StarCoreProcessingHistory.NAMESPACE,128).size());
                assertEquals(2,journal.cursor(StarCoreProcessingHistory.NAMESPACE).orElseThrow().sequence());
            }
            sql(store,"DROP TRIGGER reject_processing_nation");
        }
        try(var store=new SqliteStore(db())) {
            var engine=storyEngine(store);var journal=new CommittedHistoryJournal(store);
            var coordinator=new ProcessingHistoryCoordinator(engine,()->source,journal);
            assertEquals(1,coordinator.poll(List.of(context)));assertEquals(0,coordinator.poll(List.of(context)));
            assertEquals(2,count(store,"events"));assertEquals(2,count(store,"processing_history_receipts"));assertEquals(4,count(store,"contributions"));
            assertEquals(1,engine.instances(ACTOR).size());assertEquals("3",engine.facts(ACTOR).get("player.honor"));
            assertTrue(journal.pending(StarCoreProcessingHistory.NAMESPACE,128).isEmpty());
            assertEquals(new BigDecimal("4"),total(store,"player:"+ACTOR,"industrial"));
        }
    }
    @Test void archivedProcessingCannotBeReactivatedButLaterLiveCompletionStillTriggersOnce() throws Exception {
        var source=new Authority();source.receipts.add(start(1,1));source.receipts.add(done(2,1));
        var context=new PlayerContext(ACTOR,NATION.toString(),Map.of(),Set.of());
        try(var store=new SqliteStore(db())) {
            var engine=storyEngine(store);var coordinator=new ProcessingHistoryCoordinator(engine,()->source,new CommittedHistoryJournal(store));
            assertEquals(2,coordinator.poll(List.of(context)));assertTrue(engine.instances(ACTOR).isEmpty());assertTrue(engine.facts(ACTOR).isEmpty());
            var archived=StarCoreProcessingHistory.project(done(2,1),DOMAIN,2);
            assertTrue(engine.acceptCommittedHistory(new CommittedHistoryJournal.Pending(archived.event(),true,null,null,archived.processingReceipt()),List.of(context)).duplicate());
            engine.resumeTriggers(List.of(context));assertTrue(engine.instances(ACTOR).isEmpty());assertTrue(engine.facts(ACTOR).isEmpty());
            assertEquals(4,count(store,"contributions"));
            source.receipts.add(start(3,2));source.receipts.add(done(4,2));
            assertEquals(2,coordinator.poll(List.of(context)));assertEquals(0,coordinator.poll(List.of(context)));
            assertEquals(1,engine.instances(ACTOR).size());assertEquals("3",engine.facts(ACTOR).get("player.honor"));
            assertEquals(8,count(store,"contributions"));
        }
    }
    @Test void frozenStartAndCompletionAcrossInitialPageBoundarySurviveReopenWithoutStoryReplay() {
        var source=new Authority();for(int i=1;i<128;i++)source.receipts.add(receipt(i,i,ProcessingHistoryReceipt.Kind.AUTOMATIC,null,4));
        source.receipts.add(start(128,128));source.receipts.add(done(129,128));
        var context=new PlayerContext(ACTOR,NATION.toString(),Map.of(),Set.of());
        try(var store=new SqliteStore(db())) {
            assertEquals(128,new ProcessingHistoryCoordinator(storyEngine(store),()->source,new CommittedHistoryJournal(store)).poll(List.of(context)));
            assertEquals(128,count(store,"processing_history_receipts"));assertEquals(0,count(store,"contributions"));
        }
        try(var store=new SqliteStore(db())) {
            var engine=storyEngine(store);var journal=new CommittedHistoryJournal(store);
            assertFalse(journal.cursor(StarCoreProcessingHistory.NAMESPACE).orElseThrow().live());
            var coordinator=new ProcessingHistoryCoordinator(engine,()->source,journal);
            assertEquals(1,coordinator.poll(List.of(context)));assertEquals(0,coordinator.poll(List.of(context)));
            assertEquals(129,count(store,"processing_history_receipts"));assertEquals(4,count(store,"contributions"));
            assertTrue(engine.instances(ACTOR).isEmpty());assertTrue(engine.facts(ACTOR).isEmpty());
            assertTrue(journal.cursor(StarCoreProcessingHistory.NAMESPACE).orElseThrow().live());
            source.receipts.add(start(130,130));source.receipts.add(done(131,130));
            assertEquals(2,coordinator.poll(List.of(context)));assertEquals("3",engine.facts(ACTOR).get("player.honor"));
            assertEquals(8,count(store,"contributions"));assertEquals(1,engine.instances(ACTOR).size());
        }
    }
    private static CommittedHistoryJournal.Pending pending(CommittedHistoryJournal.Entry e) {
        return new CommittedHistoryJournal.Pending(e.event(),false,null,null,e.processingReceipt());
    }
}
