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

class StarCoreCapitalHistorySourceTest {
    @TempDir Path directory;
    final UUID domain=UUID.randomUUID(),nation=UUID.randomUUID(),actor=UUID.randomUUID(),cityA=UUID.randomUUID(),cityB=UUID.randomUUID();
    public record Receipt(long sequence,UUID operationId,UUID nationId,UUID previousCityId,UUID cityId,UUID actorId,Instant committedAt){}
    public record Page(UUID authorityDomainId,long latestSequence,List<Receipt> entries){}
    public static class Source {
        UUID domain;long head;List<Receipt> rows;int reads;boolean supported=true;RuntimeException failure;
        Source(UUID domain,List<Receipt> rows){this.domain=domain;this.rows=rows;this.head=rows.size();}
        public Optional<Page> capitalHistoryAfter(long after,int limit){
            assertEquals(100,limit);reads++;if(failure!=null)throw failure;if(!supported)return Optional.empty();
            return Optional.of(new Page(domain,head,rows.stream().filter(r->r.sequence()>after).limit(limit).toList()));
        }
    }
    Receipt receipt(long sequence){return new Receipt(sequence,new UUID(1L,sequence),nation,sequence==1?null:(sequence%2==0?cityA:cityB),sequence%2==1?cityA:cityB,actor,Instant.EPOCH.plusSeconds(sequence));}
    void consume(CommittedHistoryJournal journal,RuleEngine engine){
        for(var event:journal.pending(StarCoreCapitalHistorySource.SOURCE,100)){engine.accept(event,List.of());journal.acknowledge(event.id());}
    }

    @Test void firstDesignationAndTwoMovesBetweenPollsRetainActualActorTimeAndBothCities()throws Exception {
        var first=StarCoreCapitalHistorySource.convert(domain,receipt(1)).event();
        var outward=StarCoreCapitalHistorySource.convert(domain,receipt(2)).event();
        var returned=StarCoreCapitalHistorySource.convert(domain,receipt(3)).event();
        assertEquals("CAPITAL_DESIGNATED",first.type());assertEquals("NONE",first.facts().get("capital.previous_city"));
        assertEquals("CAPITAL_MOVED",outward.type());assertEquals("CAPITAL_MOVED",returned.type());
        assertEquals(cityA.toString(),outward.facts().get("capital.previous_city"));assertEquals(cityB.toString(),outward.facts().get("capital.city"));
        assertEquals(cityB.toString(),returned.facts().get("capital.previous_city"));assertEquals(cityA.toString(),returned.facts().get("capital.city"));
        for(var event:List.of(first,outward,returned)){
            assertEquals(Set.of(actor),event.participants());assertEquals(Set.of(nation.toString()),event.nations());
            assertEquals("COMMITTED",event.facts().get("time_basis"));
            assertEquals(Instant.EPOCH.plusSeconds(Long.parseLong(event.facts().get("source.sequence"))),event.occurredAt());
        }
    }
    @Test void committedInboxSurvivesRestartAndConsumerCommitBeforeAcknowledgement(){
        var source=new Source(domain,List.of(receipt(1),receipt(2),receipt(3)));var adapter=new StarCoreCapitalHistorySource(()->source);
        Path path=directory.resolve("restart.db");WorldEvent first;
        try(var store=new SqliteStore(path)){
            var journal=new CommittedHistoryJournal(store);var engine=new RuleEngine(store,List.of(),Clock.systemUTC());
            assertTrue(adapter.poll(journal));first=journal.pending(StarCoreCapitalHistorySource.SOURCE,1).getFirst();
            assertFalse(engine.accept(first,List.of()).duplicate());assertFalse(adapter.poll(journal));assertEquals(1,source.reads);
        }
        try(var store=new SqliteStore(path)){
            var journal=new CommittedHistoryJournal(store);var engine=new RuleEngine(store,List.of(),Clock.systemUTC());
            assertTrue(engine.accept(first,List.of()).duplicate());consume(journal,engine);assertTrue(adapter.poll(journal));
            assertEquals(3,engine.history(null,null,0,100).size());assertTrue(engine.contributions(null,null,0,100).isEmpty());
            assertEquals(3L,journal.cursor(StarCoreCapitalHistorySource.SOURCE).orElseThrow().sequence());
        }
    }
    @Test void pagesDrainInOrderAcrossTheHundredRowBoundary(){
        List<Receipt> receipts=new ArrayList<>();for(long i=1;i<=205;i++)receipts.add(receipt(i));
        var source=new Source(domain,receipts);var adapter=new StarCoreCapitalHistorySource(()->source);
        try(var store=new SqliteStore(directory.resolve("pages.db"))){
            var journal=new CommittedHistoryJournal(store);var engine=new RuleEngine(store,List.of(),Clock.systemUTC());
            for(long end:new long[]{100,200,205}){
                assertTrue(adapter.poll(journal));assertEquals(end,journal.cursor(StarCoreCapitalHistorySource.SOURCE).orElseThrow().sequence());
                assertFalse(adapter.poll(journal));consume(journal,engine);
            }
            assertTrue(adapter.poll(journal));assertTrue(journal.pending(100).isEmpty());assertEquals(100,engine.history(null,null,0,100).size());assertEquals(100,engine.history(null,null,1,100).size());assertEquals(5,engine.history(null,null,2,100).size());
        }
    }
    @Test void oldAbsentAndUnsupportedProvidersDoNotCreateAFalseBaseline(){
        try(var store=new SqliteStore(directory.resolve("capability.db"))){
            var journal=new CommittedHistoryJournal(store);
            assertFalse(new StarCoreCapitalHistorySource(()->null).poll(journal));assertFalse(new StarCoreCapitalHistorySource(()->new Object()).poll(journal));
            var source=new Source(domain,List.of());var adapter=new StarCoreCapitalHistorySource(()->source);source.supported=false;
            assertFalse(adapter.poll(journal));source.supported=true;source.failure=new UnsupportedOperationException();assertFalse(adapter.poll(journal));
            assertTrue(journal.cursor(StarCoreCapitalHistorySource.SOURCE).isEmpty());source.failure=null;assertTrue(adapter.poll(journal));
            assertEquals(0L,journal.cursor(StarCoreCapitalHistorySource.SOURCE).orElseThrow().sequence());
        }
    }
    @Test void domainReplacementAndAHeadRollbackCannotMoveTheCursor(){
        try(var store=new SqliteStore(directory.resolve("domain.db"))){
            var journal=new CommittedHistoryJournal(store);var engine=new RuleEngine(store,List.of(),Clock.systemUTC());
            var source=new Source(domain,List.of(receipt(1)));var adapter=new StarCoreCapitalHistorySource(()->source);
            adapter.poll(journal);consume(journal,engine);source.domain=UUID.randomUUID();assertThrows(IllegalStateException.class,()->adapter.poll(journal));
            source.domain=domain;source.head=0;source.rows=List.of();assertThrows(IllegalStateException.class,()->adapter.poll(journal));
            assertEquals(1L,journal.cursor(StarCoreCapitalHistorySource.SOURCE).orElseThrow().sequence());assertTrue(journal.pending(100).isEmpty());
        }
    }
    @Test void incompleteEmptyGapOrMalformedPagesNeverBindTheCursor(){
        try(var store=new SqliteStore(directory.resolve("invalid.db"))){
            var journal=new CommittedHistoryJournal(store);var source=new Source(domain,List.of());source.head=1;var adapter=new StarCoreCapitalHistorySource(()->source);
            assertThrows(IllegalArgumentException.class,()->adapter.poll(journal));
            source.rows=List.of(receipt(2));assertThrows(IllegalArgumentException.class,()->adapter.poll(journal));
            source.rows=List.of(new Receipt(1,UUID.randomUUID(),nation,cityA,cityA,actor,Instant.EPOCH));assertThrows(IllegalArgumentException.class,()->adapter.poll(journal));
            source.rows=List.of(new Receipt(1,UUID.randomUUID(),nation,null,cityA,null,Instant.EPOCH));assertThrows(IllegalArgumentException.class,()->adapter.poll(journal));
            source.failure=new IllegalStateException("native committed tail lost");assertThrows(IllegalStateException.class,()->adapter.poll(journal));
            assertTrue(journal.cursor(StarCoreCapitalHistorySource.SOURCE).isEmpty());assertTrue(journal.pending(100).isEmpty());
        }
    }
    @Test void oversizedSourcePageAndProviderReplacementAreRejected(){
        try(var store=new SqliteStore(directory.resolve("replacement.db"))){
            var journal=new CommittedHistoryJournal(store);var source=new Source(domain,List.of(receipt(1)));int[] calls={0};
            assertThrows(IllegalStateException.class,()->new StarCoreCapitalHistorySource(()->++calls[0]==1?source:new Object()).poll(journal));
            var excessive=new Source(domain,List.of()){
                @Override public Optional<Page> capitalHistoryAfter(long after,int limit){return Optional.of(new Page(domain,101,Collections.nCopies(101,receipt(1))));}
            };
            assertThrows(IllegalArgumentException.class,()->new StarCoreCapitalHistorySource(()->excessive).poll(journal));
            assertTrue(journal.cursor(StarCoreCapitalHistorySource.SOURCE).isEmpty());
        }
    }
    @Test void unrelatedPendingHistoryCannotStarveCapitalCommits(){
        try(var store=new SqliteStore(directory.resolve("sources.db"))){
            var journal=new CommittedHistoryJournal(store);
            var other=new WorldEvent("resource:pending","NATIONAL_RESOURCE_TRANSFER_COMMITTED",Instant.EPOCH,Set.of(),Set.of(),Map.of(),"native","retained");
            journal.accept(StarCoreResourceHistorySource.SOURCE,UUID.randomUUID(),0,List.of(new CommittedHistoryJournal.Entry(1,other)));
            var source=new Source(domain,List.of(receipt(1)));var adapter=new StarCoreCapitalHistorySource(()->source);
            assertTrue(adapter.poll(journal));consume(journal,new RuleEngine(store,List.of(),Clock.systemUTC()));
            assertEquals(List.of(other),journal.pending(StarCoreResourceHistorySource.SOURCE,100));assertTrue(adapter.poll(journal));
        }
    }
}
