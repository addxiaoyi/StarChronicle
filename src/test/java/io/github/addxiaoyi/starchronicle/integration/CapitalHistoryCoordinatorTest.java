package io.github.addxiaoyi.starchronicle.integration;

import io.github.addxiaoyi.starchronicle.core.RuleEngine;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition.*;
import io.github.addxiaoyi.starchronicle.history.*;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static io.github.addxiaoyi.starchronicle.integration.StarCoreCapitalHistoryTest.*;
import static org.junit.jupiter.api.Assertions.*;

class CapitalHistoryCoordinatorTest {
    @TempDir Path directory;
    private static final String NS=StarCoreCapitalHistory.NAMESPACE, LEGACY=CommittedSourceCollector.Source.CAPITAL.id;
    private static final UUID NATION_DOMAIN=new UUID(0,42);
    public static class BothSources extends Authority {
        public UUID authorityDomainId(){return NATION_DOMAIN;}
        public List<Designation> capitalDesignationsAfter(long after,int limit){
            if(failure!=null)throw failure;
            return receipts.stream().map(Receipt::designation).filter(r->r.sequence()>after).limit(limit).toList();
        }
    }
    private final BothSources source=new BothSources();
    private Receipt receipt(long sequence){return new StarCoreCapitalHistoryTest().receipt(sequence);}
    private RuleEngine engine(SqliteStore store){
        var story=new StoryDefinition("merged-capital","1","Capital",Scope.NATION,
                List.of("CAPITAL_DESIGNATED","CAPITAL_MOVED"),null,"done",0,
                Map.of("done",new Node("Done",null,null,1,Map.of(),List.of(),true)));
        return new RuleEngine(store,List.of(story),Clock.systemUTC());
    }
    private void legacy(RuleEngine engine)throws Exception {
        var page=new CommittedSourceCollector(key->source).collect(CommittedSourceCollector.Source.CAPITAL,engine.committedSourceCursor(LEGACY)).orElseThrow();
        engine.acceptCommittedSource(page,List.of());
    }
    @Test void installedLegacyLedgerKeepsNationDomainAndOriginalEventsAcrossRestart()throws Exception {
        Path database=directory.resolve("legacy.db");source.receipts.add(receipt(1));
        String first;
        try(var store=new SqliteStore(database)) {
            var engine=engine(store);legacy(engine);first=SqliteStore.json(engine.history(null,null,0,10));
            assertEquals(1,engine.pendingTriggerCount()); // Preserve the installed legacy activation contract.
            var journal=new CommittedHistoryJournal(store);var coordinator=new CapitalHistoryCoordinator(engine,key->source,journal);
            assertTrue(coordinator.usesCommittedHistory());assertEquals(0,coordinator.poll(List.of()));
            assertEquals(first,SqliteStore.json(engine.history(null,null,0,10)));assertTrue(journal.cursor(NS).isEmpty());
        }
        try(var store=new SqliteStore(database)) {
            var engine=engine(store);var journal=new CommittedHistoryJournal(store);source.receipts.add(receipt(2));
            assertEquals(1,new CapitalHistoryCoordinator(engine,key->source,journal).poll(List.of()));
            assertEquals(2,engine.history(null,null,0,10).size());assertEquals(2,engine.committedSourceCursor(LEGACY).orElseThrow().sequence());
            assertEquals(NATION_DOMAIN.toString(),engine.committedSourceCursor(LEGACY).orElseThrow().domain());
            assertTrue(journal.cursor(NS).isEmpty());
            var original=engine.history(null,null,0,10).stream().filter(e->e.type().equals("CAPITAL_DESIGNATED")).findFirst().orElseThrow();
            assertEquals(first,SqliteStore.json(List.of(original)));
            assertTrue(original.id().startsWith("starcore:committed:capital:"));
            assertEquals(CITY_A.toString(),original.facts().get("capital.city_id"));
            assertEquals(Set.of(ACTOR),original.participants());assertEquals(TIME.minusSeconds(1),original.occurredAt());
            assertEquals(2,engine.pendingTriggerCount());
        }
    }
    @Test void freshInstallationArchivesInitialPageAndActivatesOnlyLaterCommits()throws Exception {
        source.receipts.add(receipt(1));
        try(var store=new SqliteStore(directory.resolve("fresh.db"))) {
            var engine=engine(store);var journal=new CommittedHistoryJournal(store);var coordinator=new CapitalHistoryCoordinator(engine,key->source,journal);
            assertEquals(1,coordinator.poll(List.of()));assertTrue(engine.committedSourceCursor(LEGACY).isEmpty());
            assertEquals(DOMAIN,journal.cursor(NS).orElseThrow().authorityDomain());
            assertEquals(0,engine.pendingTriggerCount());source.receipts.add(receipt(2));
            assertEquals(1,coordinator.poll(List.of()));assertEquals(1,engine.pendingTriggerCount());
            assertEquals(0,coordinator.poll(List.of()));assertEquals(2,engine.history(null,null,0,10).size());
        }
    }
    @Test void existingJournalPendingBeforeUpgradeIsConsumedOnce()throws Exception {
        source.receipts.add(receipt(1));Path db=directory.resolve("pending.db");
        try(var store=new SqliteStore(db)) {
            engine(store);var journal=new CommittedHistoryJournal(store);new StarCoreCapitalHistory(()->source,journal).scan();
            assertEquals(1,journal.pending(128).size());
        }
        try(var store=new SqliteStore(db)) {
            var engine=engine(store);var journal=new CommittedHistoryJournal(store);var coordinator=new CapitalHistoryCoordinator(engine,key->source,journal);
            assertEquals(1,coordinator.poll(List.of()));assertEquals(0,coordinator.poll(List.of()));
            assertTrue(engine.committedSourceCursor(LEGACY).isEmpty());assertEquals(1,engine.history(null,null,0,10).size());
        }
    }
    @Test void coexistenceIsIsolatedWithoutDeletingEitherLedger()throws Exception {
        source.receipts.add(receipt(1));
        try(var store=new SqliteStore(directory.resolve("conflict.db"))) {
            var engine=engine(store);legacy(engine);var journal=new CommittedHistoryJournal(store);
            new StarCoreCapitalHistory(()->source,journal).scan();
            var coordinator=new CapitalHistoryCoordinator(engine,key->source,journal);
            assertTrue(coordinator.usesCommittedHistory());assertThrows(IllegalStateException.class,()->coordinator.poll(List.of()));
            assertEquals(1,engine.history(null,null,0,10).size());assertEquals(1,journal.pending(NS,128).size());
            assertEquals(1,engine.committedSourceCursor(LEGACY).orElseThrow().sequence());
        }
    }
    @Test void competingLegacyWriterAfterConstructionIsRejected()throws Exception {
        source.receipts.add(receipt(1));
        try(var store=new SqliteStore(directory.resolve("competing.db"))) {
            var engine=engine(store);var journal=new CommittedHistoryJournal(store);var coordinator=new CapitalHistoryCoordinator(engine,key->source,journal);
            legacy(engine);assertThrows(IllegalStateException.class,()->coordinator.poll(List.of()));
            assertTrue(journal.cursor(NS).isEmpty());assertEquals(1,engine.history(null,null,0,10).size());
        }
    }
    @Test void unavailablePinnedSourceKeepsPendingAndSuppressesObservedFallback()throws Exception {
        source.receipts.add(receipt(1));AtomicReference<Object> provider=new AtomicReference<>(source);
        try(var store=new SqliteStore(directory.resolve("unavailable.db"))) {
            var engine=engine(store);var journal=new CommittedHistoryJournal(store);new StarCoreCapitalHistory(provider::get,journal).scan();
            var coordinator=new CapitalHistoryCoordinator(engine,key->provider.get(),journal);provider.set(null);
            assertTrue(coordinator.usesCommittedHistory());assertThrows(IllegalStateException.class,()->coordinator.poll(List.of()));
            assertEquals(1,journal.pending(NS,128).size());assertTrue(engine.history(null,null,0,10).isEmpty());
            provider.set(source);assertEquals(1,coordinator.poll(List.of()));
        }
    }
    @Test void fullAnchorPageCannotPrematurelyEndArchiveCatchup()throws Exception {
        for(int i=1;i<=257;i++)source.receipts.add(receipt(i));
        try(var store=new SqliteStore(directory.resolve("pages.db"))) {
            var engine=engine(store);var journal=new CommittedHistoryJournal(store);var coordinator=new CapitalHistoryCoordinator(engine,key->source,journal);
            assertEquals(128,coordinator.poll(List.of()));assertFalse(journal.cursor(NS).orElseThrow().live());
            assertEquals(127,coordinator.poll(List.of()));assertFalse(journal.cursor(NS).orElseThrow().live());
            assertEquals(2,coordinator.poll(List.of()));assertTrue(journal.cursor(NS).orElseThrow().live());
            assertEquals(0,engine.pendingTriggerCount());source.receipts.add(receipt(258));
            assertEquals(1,coordinator.poll(List.of()));assertEquals(1,engine.pendingTriggerCount());
        }
    }
    @Test void silentSourceRollbackAndChangedActorOrTimeNeverLookLikeIdleSuccess()throws Exception {
        source.receipts.add(receipt(1));
        try(var store=new SqliteStore(directory.resolve("anchor.db"))) {
            var engine=engine(store);var journal=new CommittedHistoryJournal(store);var coordinator=new CapitalHistoryCoordinator(engine,key->source,journal);
            coordinator.poll(List.of());var original=engine.history(null,null,0,10);
            source.invalid=List.of();assertThrows(IllegalStateException.class,()->coordinator.poll(List.of()));source.invalid=null;
            var old=receipt(1).designation();
            for(var changed:List.of(new Designation(1,old.operationId(),NATION,null,CITY_A,new UUID(0,90),old.committedAt()),
                    new Designation(1,old.operationId(),NATION,null,CITY_A,ACTOR,old.committedAt().plusNanos(1)))) {
                source.receipts.set(0,new Receipt(DOMAIN,changed));assertThrows(IllegalStateException.class,()->coordinator.poll(List.of()));
                assertEquals(original,engine.history(null,null,0,10));assertEquals(1,journal.cursor(NS).orElseThrow().sequence());
                assertTrue(journal.pending(NS,128).isEmpty());
            }
            source.receipts.set(0,receipt(1));assertEquals(0,coordinator.poll(List.of()));
        }
    }
    @Test void otherNamespacePendingIsNotConsumedByCapitalReader()throws Exception {
        source.receipts.add(receipt(1));
        try(var store=new SqliteStore(directory.resolve("isolation.db"))) {
            var engine=engine(store);var journal=new CommittedHistoryJournal(store);
            journal.stage("other.source",DOMAIN,0,List.of(StarCoreCapitalHistory.project(receipt(20),DOMAIN,20)
                    .event()).stream().map(event->new CommittedHistoryJournal.Entry(1,event)).toList());
            assertEquals(1,new CapitalHistoryCoordinator(engine,key->source,journal).poll(List.of()));
            assertEquals(1,journal.pending("other.source",128).size());assertEquals(1,engine.history(null,null,0,10).size());
        }
    }
}
