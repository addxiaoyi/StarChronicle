package io.github.addxiaoyi.starchronicle.integration;

import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import io.github.addxiaoyi.starchronicle.core.RuleEngine;
import io.github.addxiaoyi.starchronicle.history.CommittedHistoryJournal;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class StarCoreTechnologyHistorySourceTest {
    @TempDir Path directory;
    final UUID domain=UUID.randomUUID(),nation=UUID.randomUUID();
    public record Receipt(UUID authorityDomainId,long sequence,UUID operationId,UUID nationId,String technologyKey,String era,String change,Instant committedAt){}
    public static class Source {
        Optional<UUID> domain;List<Receipt> rows;int reads;boolean changeAfterRead;
        Source(UUID domain,List<Receipt> rows){this.domain=Optional.of(domain);this.rows=rows;}
        public Optional<UUID> historyAuthorityDomainId(){return domain;}
        public List<Receipt> technologyChangesAfter(long after,int limit){
            assertEquals(100,limit);reads++;
            if(changeAfterRead)domain=Optional.of(UUID.randomUUID());
            return rows.stream().filter(r->r.sequence()>after).toList();
        }
    }
    Receipt receipt(long sequence,String change){return new Receipt(domain,sequence,UUID.randomUUID(),nation,"mining","stone",change,Instant.EPOCH.plusSeconds(sequence));}
    void consume(CommittedHistoryJournal journal,RuleEngine engine){
        for(var event:journal.pending(StarCoreTechnologyHistorySource.SOURCE,100)){engine.accept(event,List.of());journal.acknowledge(event.id());}
    }

    @Test void unlockAndRevokeBetweenPollsRemainIndependentFactsWithNoInventedResearcher()throws Exception{
        var unlock=StarCoreTechnologyHistorySource.convert(domain,receipt(1,"UNLOCKED")).event();
        var revoke=StarCoreTechnologyHistorySource.convert(domain,receipt(2,"REVOKED")).event();
        assertEquals("TECHNOLOGY_UNLOCKED",unlock.type());assertEquals("TECHNOLOGY_REVOKED",revoke.type());
        assertTrue(unlock.participants().isEmpty());assertEquals(Set.of(nation.toString()),unlock.nations());
        assertEquals("NOT_RECORDED",unlock.facts().get("tech.researcher"));
        assertEquals("NOT_PROVEN_BY_UNLOCK",unlock.facts().get("tech.research_completion"));
        assertEquals("COMMITTED",unlock.facts().get("time_basis"));assertEquals(Instant.EPOCH.plusSeconds(1),unlock.occurredAt());
        assertNotEquals(unlock.id(),revoke.id());assertFalse(unlock.facts().containsKey("tech.stage"));
    }
    @Test void acceptedPageSurvivesRestartAndReplaysAfterConsumerCommitBeforeAcknowledgement(){
        var source=new Source(domain,List.of(receipt(1,"UNLOCKED"),receipt(2,"REVOKED")));
        var adapter=new StarCoreTechnologyHistorySource(()->source);Path path=directory.resolve("restart.db");
        WorldEvent first;
        try(var store=new SqliteStore(path)){
            var journal=new CommittedHistoryJournal(store);var engine=new RuleEngine(store,List.of(),Clock.systemUTC());
            assertTrue(adapter.poll(journal));first=journal.pending(StarCoreTechnologyHistorySource.SOURCE,1).getFirst();
            assertFalse(engine.accept(first,List.of()).duplicate());
            assertFalse(adapter.poll(journal));assertEquals(1,source.reads);
        }
        try(var store=new SqliteStore(path)){
            var journal=new CommittedHistoryJournal(store);var engine=new RuleEngine(store,List.of(),Clock.systemUTC());
            assertTrue(engine.accept(first,List.of()).duplicate());consume(journal,engine);assertTrue(adapter.poll(journal));
            assertEquals(2,engine.history(null,null,0,100).size());assertEquals(2,journal.cursor(StarCoreTechnologyHistorySource.SOURCE).orElseThrow().sequence());
            assertTrue(engine.contributions(null,null,0,100).isEmpty());
        }
    }
    @Test void unrelatedRetainedTradeFailureDoesNotBlockTechnologyAndEachSourceHasItsOwnBackpressure(){
        try(var store=new SqliteStore(directory.resolve("independent.db"))){
            var journal=new CommittedHistoryJournal(store);
            var failed=new WorldEvent("trade:unresolved","TRADE_HISTORY_RECORDED",Instant.EPOCH,Set.of(),Set.of(),Map.of(),"native","retained failure");
            journal.accept(StarCoreTradeHistorySource.SOURCE,UUID.randomUUID(),0,List.of(new CommittedHistoryJournal.Entry(1,failed)));
            var source=new Source(domain,List.of(receipt(1,"UNLOCKED")));var adapter=new StarCoreTechnologyHistorySource(()->source);
            assertTrue(adapter.poll(journal));assertFalse(adapter.poll(journal));
            consume(journal,new RuleEngine(store,List.of(),Clock.systemUTC()));assertTrue(adapter.poll(journal));
            assertEquals(List.of(failed),journal.pending(StarCoreTradeHistorySource.SOURCE,100));
            assertTrue(journal.pending(StarCoreTechnologyHistorySource.SOURCE,100).isEmpty());
        }
    }
    @Test void emptyCapabilityDoesNotInventBaselineAndEmptyPagePinsDomain(){
        try(var store=new SqliteStore(directory.resolve("baseline.db"))){
            var journal=new CommittedHistoryJournal(store);var source=new Source(domain,List.of());
            source.domain=Optional.empty();var adapter=new StarCoreTechnologyHistorySource(()->source);
            assertFalse(adapter.poll(journal));assertEquals(0,source.reads);assertTrue(journal.cursor(StarCoreTechnologyHistorySource.SOURCE).isEmpty());
            source.domain=Optional.of(domain);assertTrue(adapter.poll(journal));assertTrue(journal.pending(100).isEmpty());
            assertEquals(0,journal.cursor(StarCoreTechnologyHistorySource.SOURCE).orElseThrow().sequence());
            source.domain=Optional.of(UUID.randomUUID());assertThrows(IllegalStateException.class,()->adapter.poll(journal));
        }
    }
    @Test void domainChangingDuringEmptyReadOrReplacedProviderCannotBindCursor(){
        try(var store=new SqliteStore(directory.resolve("domain.db"))){
            var journal=new CommittedHistoryJournal(store);var source=new Source(domain,List.of());source.changeAfterRead=true;
            assertThrows(IllegalStateException.class,()->new StarCoreTechnologyHistorySource(()->source).poll(journal));
            assertTrue(journal.cursor(StarCoreTechnologyHistorySource.SOURCE).isEmpty());
            source.changeAfterRead=false;int[] reads={0};
            assertThrows(IllegalStateException.class,()->new StarCoreTechnologyHistorySource(()->++reads[0]==1?source:new Object()).poll(journal));
            assertTrue(journal.cursor(StarCoreTechnologyHistorySource.SOURCE).isEmpty());
        }
    }
    @Test void oldProviderGapsAndWrongReceiptDomainsNeverAdvanceCursor(){
        try(var store=new SqliteStore(directory.resolve("malformed.db"))){
            var journal=new CommittedHistoryJournal(store);assertFalse(new StarCoreTechnologyHistorySource(()->new Object()).poll(journal));
            var source=new Source(domain,List.of(receipt(2,"UNLOCKED")));var adapter=new StarCoreTechnologyHistorySource(()->source);
            assertThrows(IllegalArgumentException.class,()->adapter.poll(journal));
            source.rows=List.of(new Receipt(UUID.randomUUID(),1,UUID.randomUUID(),nation,"mining","stone","UNLOCKED",Instant.EPOCH));
            assertThrows(IllegalArgumentException.class,()->adapter.poll(journal));
            source.rows=List.of(new Receipt(domain,1,UUID.randomUUID(),nation,"mining","stone","RESEARCH_COMPLETED",Instant.EPOCH));
            assertThrows(IllegalArgumentException.class,()->adapter.poll(journal));
            assertTrue(journal.cursor(StarCoreTechnologyHistorySource.SOURCE).isEmpty());assertTrue(journal.pending(100).isEmpty());
        }
    }
}
