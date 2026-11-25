package io.github.addxiaoyi.starchronicle.history;

import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import io.github.addxiaoyi.starchronicle.core.RuleEngine;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CommittedHistoryJournalTest {
    @TempDir Path directory;
    final UUID domain=UUID.randomUUID();
    final String source="test.trade";
    Path database(){return directory.resolve("journal.db");}
    CommittedHistoryJournal.Entry entry(long sequence){return new CommittedHistoryJournal.Entry(sequence,
            new WorldEvent("source:"+domain+":"+sequence,"TRADE_HISTORY_RECORDED",Instant.EPOCH.plusSeconds(sequence),Set.of(),Set.of(),Map.of(),source,"committed source sequence "+sequence));}

    @Test void cursorAndPendingSurviveRestartAndAckOnlyAfterEngineCommit(){
        WorldEvent event=entry(1).event();
        try(var store=new SqliteStore(database())){
            var journal=new CommittedHistoryJournal(store);journal.accept(source,domain,0,List.of(entry(1)));
            var engine=new RuleEngine(store,List.of(),Clock.systemUTC());
            assertFalse(engine.accept(event,List.of()).duplicate());
            // Crash between consumer commit and acknowledge: the exact immutable event must replay.
        }
        try(var store=new SqliteStore(database())){
            var journal=new CommittedHistoryJournal(store);var engine=new RuleEngine(store,List.of(),Clock.systemUTC());
            assertEquals(1,journal.cursor(source).orElseThrow().sequence());assertEquals(List.of(event),journal.pending(100));
            assertTrue(engine.accept(event,List.of()).duplicate());assertTrue(journal.acknowledge(event.id()));
            assertFalse(journal.acknowledge(event.id()));assertTrue(journal.pending(100).isEmpty());
            assertEquals(1,engine.history(null,null,0,100).size());
        }
    }

    @Test void failedPageRollsBackCursorAndEveryInboxRow(){
        try(var store=new SqliteStore(database())){
            var journal=new CommittedHistoryJournal(store);journal.accept(source,domain,0,List.of());
            var crashing=new CommittedHistoryJournal(store,()->{throw new IllegalStateException("disk failure");});
            assertThrows(IllegalStateException.class,()->crashing.accept(source,domain,0,List.of(entry(1),entry(2))));
            assertEquals(0,journal.cursor(source).orElseThrow().sequence());assertTrue(journal.pending(100).isEmpty());
            journal.accept(source,domain,0,List.of(entry(1),entry(2)));
            assertEquals(2,journal.pending(100).size());
        }
    }

    @Test void emptyFirstPageStillPinsTheDomainAndNeverSilentlyRebinds(){
        try(var store=new SqliteStore(database())){
            var journal=new CommittedHistoryJournal(store);journal.accept(source,domain,0,List.of());
        }
        try(var store=new SqliteStore(database())){
            var journal=new CommittedHistoryJournal(store);
            assertThrows(IllegalStateException.class,()->journal.accept(source,UUID.randomUUID(),0,List.of(entry(1))));
            assertEquals(domain,journal.cursor(source).orElseThrow().domain());
        }
    }

    @Test void gapsReorderStaleCursorAndDuplicateEventIdsCannotAdvanceSource(){
        try(var store=new SqliteStore(database())){
            var journal=new CommittedHistoryJournal(store);
            assertThrows(IllegalArgumentException.class,()->journal.accept(source,domain,0,List.of(entry(2))));
            assertThrows(IllegalArgumentException.class,()->journal.accept(source,domain,0,List.of(entry(1),new CommittedHistoryJournal.Entry(2,entry(1).event()))));
            assertTrue(journal.cursor(source).isEmpty());
            journal.accept(source,domain,0,List.of(entry(1)));
            assertThrows(IllegalStateException.class,()->journal.accept(source,domain,0,List.of(entry(1))));
            assertThrows(RuntimeException.class,()->journal.accept(source,domain,1,List.of(new CommittedHistoryJournal.Entry(2,entry(1).event()))));
            assertEquals(1,journal.cursor(source).orElseThrow().sequence());assertEquals(1,journal.pending(100).size());
        }
    }
}
