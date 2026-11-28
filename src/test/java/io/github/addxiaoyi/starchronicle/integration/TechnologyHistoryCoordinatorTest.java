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
import static io.github.addxiaoyi.starchronicle.integration.StarCoreTechnologyHistoryTest.*;
import static org.junit.jupiter.api.Assertions.*;

class TechnologyHistoryCoordinatorTest {
    @TempDir Path directory;
    private final Authority source=new Authority();
    private static final String NS=StarCoreTechnologyHistory.NAMESPACE;
    private Receipt receipt(long n,String change) {
        return new Receipt(DOMAIN,n,new UUID(0,n),NATION,"agriculture","primitive",change,TIME.minusSeconds(n));
    }
    private RuleEngine engine(SqliteStore store){
        var story=new StoryDefinition("merged-technology","1","Technology",Scope.NATION,
                List.of("TECHNOLOGY_BREAKTHROUGH","TECHNOLOGY_UNLOCKED","TECHNOLOGY_REVOKED"),null,"done",0,
                Map.of("done",new Node("Done",null,null,1,Map.of(),List.of(),true)));
        return new RuleEngine(store,List.of(story),Clock.systemUTC());
    }
    private void legacy(RuleEngine engine)throws Exception {
        var page=new CommittedSourceCollector(key->source).collect(CommittedSourceCollector.Source.TECHNOLOGY,engine.committedSourceCursor(NS)).orElseThrow();
        engine.acceptCommittedSource(page,List.of());
    }
    @Test void installedLegacyLedgerKeepsOriginalEventsAcrossRestart()throws Exception {
        Path database=directory.resolve("legacy.db");source.receipts.add(receipt(1,"UNLOCKED"));
        String first;
        try(var store=new SqliteStore(database)) {
            var engine=engine(store);legacy(engine);first=SqliteStore.json(engine.history(null,null,0,10));
            var journal=new CommittedHistoryJournal(store);var coordinator=new TechnologyHistoryCoordinator(engine,key->source,journal);
            assertTrue(coordinator.usesCommittedHistory());assertEquals(0,coordinator.poll(List.of()));
            assertEquals(first,SqliteStore.json(engine.history(null,null,0,10)));assertTrue(journal.cursor(NS).isEmpty());
        }
        try(var store=new SqliteStore(database)) {
            var engine=engine(store);var journal=new CommittedHistoryJournal(store);source.receipts.add(receipt(2,"REVOKED"));
            assertEquals(1,new TechnologyHistoryCoordinator(engine,key->source,journal).poll(List.of()));
            assertEquals(2,engine.history(null,null,0,10).size());assertEquals(2,engine.committedSourceCursor(NS).orElseThrow().sequence());
            assertTrue(journal.cursor(NS).isEmpty());
            assertTrue(engine.history(null,null,0,10).stream().anyMatch(e->e.type().equals("TECHNOLOGY_UNLOCKED")));
        }
    }
    @Test void freshInstallationArchivesInitialPageAndActivatesOnlyLaterCommits()throws Exception {
        source.receipts.add(receipt(1,"UNLOCKED"));
        try(var store=new SqliteStore(directory.resolve("fresh.db"))) {
            var engine=engine(store);var journal=new CommittedHistoryJournal(store);var coordinator=new TechnologyHistoryCoordinator(engine,key->source,journal);
            assertEquals(1,coordinator.poll(List.of()));assertTrue(engine.committedSourceCursor(NS).isEmpty());
            assertEquals(0,engine.pendingTriggerCount());source.receipts.add(receipt(2,"REVOKED"));
            assertEquals(1,coordinator.poll(List.of()));assertEquals(1,engine.pendingTriggerCount());
            assertEquals(0,coordinator.poll(List.of()));assertEquals(2,engine.history(null,null,0,10).size());
        }
    }
    @Test void existingJournalPendingBeforeUpgradeIsConsumedOnce()throws Exception {
        source.receipts.add(receipt(1,"UNLOCKED"));Path db=directory.resolve("pending.db");
        try(var store=new SqliteStore(db)) {
            engine(store);var journal=new CommittedHistoryJournal(store);new StarCoreTechnologyHistory(()->source,journal).scan();
            assertEquals(1,journal.pending(128).size());
        }
        try(var store=new SqliteStore(db)) {
            var engine=engine(store);var journal=new CommittedHistoryJournal(store);var coordinator=new TechnologyHistoryCoordinator(engine,key->source,journal);
            assertEquals(1,coordinator.poll(List.of()));assertEquals(0,coordinator.poll(List.of()));
            assertTrue(engine.committedSourceCursor(NS).isEmpty());assertEquals(1,engine.history(null,null,0,10).size());
        }
    }
    @Test void coexistenceIsIsolatedWithoutDeletingEitherLedger()throws Exception {
        source.receipts.add(receipt(1,"UNLOCKED"));
        try(var store=new SqliteStore(directory.resolve("conflict.db"))) {
            var engine=engine(store);legacy(engine);var journal=new CommittedHistoryJournal(store);
            new StarCoreTechnologyHistory(()->source,journal).scan();
            var coordinator=new TechnologyHistoryCoordinator(engine,key->source,journal);
            assertTrue(coordinator.usesCommittedHistory());assertThrows(IllegalStateException.class,()->coordinator.poll(List.of()));
            assertEquals(1,engine.history(null,null,0,10).size());assertEquals(1,journal.pending(128).size());
            assertEquals(1,engine.committedSourceCursor(NS).orElseThrow().sequence());
        }
    }
    @Test void competingLegacyWriterAfterStartCannotIntroduceSecondJournal()throws Exception {
        source.receipts.add(receipt(1,"UNLOCKED"));
        try(var store=new SqliteStore(directory.resolve("competing.db"))) {
            var engine=engine(store);var journal=new CommittedHistoryJournal(store);var coordinator=new TechnologyHistoryCoordinator(engine,key->source,journal);
            legacy(engine);assertThrows(IllegalStateException.class,()->coordinator.poll(List.of()));
            assertTrue(journal.cursor(NS).isEmpty());assertEquals(1,engine.history(null,null,0,10).size());
        }
    }
    @Test void unavailablePinnedSourceKeepsPendingAndSuppressesObservedFallback()throws Exception {
        source.receipts.add(receipt(1,"UNLOCKED"));AtomicReference<Object> provider=new AtomicReference<>(source);
        try(var store=new SqliteStore(directory.resolve("unavailable.db"))) {
            var engine=engine(store);var journal=new CommittedHistoryJournal(store);new StarCoreTechnologyHistory(provider::get,journal).scan();
            var coordinator=new TechnologyHistoryCoordinator(engine,key->provider.get(),journal);provider.set(null);
            assertTrue(coordinator.usesCommittedHistory());assertThrows(IllegalStateException.class,()->coordinator.poll(List.of()));
            assertEquals(1,journal.pending(128).size());assertTrue(engine.history(null,null,0,10).isEmpty());
            provider.set(source);assertEquals(1,coordinator.poll(List.of()));
        }
    }
    @Test void fullAnchorPageCannotPrematurelyEndArchiveCatchup()throws Exception {
        for(int i=1;i<=257;i++)source.receipts.add(receipt(i,"UNLOCKED"));
        try(var store=new SqliteStore(directory.resolve("pages.db"))) {
            var engine=engine(store);var journal=new CommittedHistoryJournal(store);var coordinator=new TechnologyHistoryCoordinator(engine,key->source,journal);
            assertEquals(128,coordinator.poll(List.of()));assertFalse(journal.cursor(NS).orElseThrow().live());
            assertEquals(127,coordinator.poll(List.of()));assertFalse(journal.cursor(NS).orElseThrow().live());
            assertEquals(2,coordinator.poll(List.of()));assertTrue(journal.cursor(NS).orElseThrow().live());
            assertEquals(0,engine.pendingTriggerCount());source.receipts.add(receipt(258,"REVOKED"));
            assertEquals(1,coordinator.poll(List.of()));assertEquals(1,engine.pendingTriggerCount());
        }
    }
    @Test void silentSourceRollbackAndChangedHeadNeverLookLikeIdleSuccess()throws Exception {
        source.receipts.add(receipt(1,"UNLOCKED"));
        try(var store=new SqliteStore(directory.resolve("anchor.db"))) {
            var engine=engine(store);var journal=new CommittedHistoryJournal(store);var coordinator=new TechnologyHistoryCoordinator(engine,key->source,journal);
            coordinator.poll(List.of());var original=engine.history(null,null,0,10);
            source.invalid=List.of();assertThrows(IllegalStateException.class,()->coordinator.poll(List.of()));
            source.invalid=null;source.receipts.set(0,receipt(1,"REVOKED"));
            assertThrows(IllegalStateException.class,()->coordinator.poll(List.of()));
            assertEquals(original,engine.history(null,null,0,10));assertEquals(1,journal.cursor(NS).orElseThrow().sequence());
            assertTrue(journal.pending(128).isEmpty());
            source.receipts.set(0,receipt(1,"UNLOCKED"));assertEquals(0,coordinator.poll(List.of()));
        }
    }
}
