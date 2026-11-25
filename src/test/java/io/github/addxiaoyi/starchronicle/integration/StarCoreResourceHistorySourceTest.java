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

class StarCoreResourceHistorySourceTest {
    @TempDir Path directory;
    final UUID domain=UUID.randomUUID(),payer=UUID.randomUUID(),receiver=UUID.randomUUID();
    public record Receipt(String authorityDomainId,long sequence,String transactionId,UUID payerNationId,UUID receiverNationId,Map<String,Long> resources,String category,String description,Instant appliedAt){}
    public static class Source {
        Optional<UUID> domain;List<Receipt> rows;int reads;boolean changeAfterRead;RuntimeException failure;
        Source(UUID domain,List<Receipt> rows){this.domain=Optional.of(domain);this.rows=rows;}
        public Optional<UUID> transferHistoryAuthorityDomainId(){return domain;}
        public List<Receipt> transferReceiptsAfter(long after,int limit){
            assertEquals(100,limit);reads++;if(failure!=null)throw failure;
            if(changeAfterRead)domain=Optional.of(UUID.randomUUID());
            return rows.stream().filter(r->r.sequence()>after).toList();
        }
    }
    Receipt receipt(long sequence){return new Receipt(domain.toString(),sequence,"transfer:"+sequence,payer,receiver,Map.of("mineral",3L,"energy",7L),"OTHER","native request",Instant.EPOCH.plusSeconds(sequence));}
    void consume(CommittedHistoryJournal journal,RuleEngine engine){
        for(var event:journal.pending(StarCoreResourceHistorySource.SOURCE,100)){engine.accept(event,List.of());journal.acknowledge(event.id());}
    }

    @Test void committedResourceQuantitiesRemainNationalFactsWithoutInventingAPlayer(){
        try{
            var event=StarCoreResourceHistorySource.convert(domain,receipt(1)).event();
            assertEquals("NATIONAL_RESOURCE_TRANSFER_COMMITTED",event.type());assertTrue(event.participants().isEmpty());
            assertEquals(Set.of(payer.toString(),receiver.toString()),event.nations());
            assertEquals("{\"energy\":7,\"mineral\":3}",event.facts().get("resource.quantities"));
            assertEquals("NOT_RECORDED",event.facts().get("resource.actor"));assertEquals("COMMITTED",event.facts().get("time_basis"));
            assertEquals(payer.toString(),event.facts().get("resource.payer"));assertEquals(receiver.toString(),event.facts().get("resource.receiver"));
            assertEquals(RuleEngine.stable("native request"),event.facts().get("resource.description_sha256"));
            assertEquals(Instant.EPOCH.plusSeconds(1),event.occurredAt());
        }catch(ReflectiveOperationException error){throw new AssertionError(error);}
    }
    @Test void nativeFileIdentifiersAreNotRestrictedToSqlColumnOrAsciiNames() throws Exception {
        String transaction="file-transfer-"+"x".repeat(300);
        var receipt=new Receipt(domain.toString(),1,transaction,payer,receiver,Map.of("矿产",3L),"OTHER","",Instant.EPOCH);
        var event=StarCoreResourceHistorySource.convert(domain,receipt).event();
        assertEquals(transaction,event.facts().get("resource.transaction_id"));
        assertEquals("{\"矿产\":3}",event.facts().get("resource.quantities"));
        assertTrue(event.evidence().contains(RuleEngine.stable(transaction)));
    }
    @Test void persistedPageReplaysAfterConsumerCommitAndBeforeAckWithNoContribution(){
        var source=new Source(domain,List.of(receipt(1),receipt(2)));var adapter=new StarCoreResourceHistorySource(()->source);
        Path path=directory.resolve("restart.db");WorldEvent first;
        try(var store=new SqliteStore(path)){
            var journal=new CommittedHistoryJournal(store);var engine=new RuleEngine(store,List.of(),Clock.systemUTC());
            assertTrue(adapter.poll(journal));first=journal.pending(StarCoreResourceHistorySource.SOURCE,1).getFirst();
            assertFalse(engine.accept(first,List.of()).duplicate());assertFalse(adapter.poll(journal));assertEquals(1,source.reads);
        }
        try(var store=new SqliteStore(path)){
            var journal=new CommittedHistoryJournal(store);var engine=new RuleEngine(store,List.of(),Clock.systemUTC());
            assertTrue(engine.accept(first,List.of()).duplicate());consume(journal,engine);assertTrue(adapter.poll(journal));
            assertEquals(2,engine.history(null,null,0,100).size());assertTrue(engine.contributions(null,null,0,100).isEmpty());
            assertEquals(2,journal.cursor(StarCoreResourceHistorySource.SOURCE).orElseThrow().sequence());
        }
    }
    @Test void oldProvidersAndAbsentCapabilityDoNotCreateAFalseBaseline(){
        try(var store=new SqliteStore(directory.resolve("capability.db"))){
            var journal=new CommittedHistoryJournal(store);assertFalse(new StarCoreResourceHistorySource(()->new Object()).poll(journal));
            var source=new Source(domain,List.of());source.domain=Optional.empty();var adapter=new StarCoreResourceHistorySource(()->source);
            assertFalse(adapter.poll(journal));assertEquals(0,source.reads);assertTrue(journal.cursor(StarCoreResourceHistorySource.SOURCE).isEmpty());
            source.domain=Optional.of(domain);assertTrue(adapter.poll(journal));assertTrue(journal.pending(100).isEmpty());
            source.domain=Optional.of(UUID.randomUUID());assertThrows(IllegalStateException.class,()->adapter.poll(journal));
        }
    }
    @Test void providerOrDomainChangesDuringEmptyPageNeverBindACursor(){
        try(var store=new SqliteStore(directory.resolve("identity.db"))){
            var journal=new CommittedHistoryJournal(store);var source=new Source(domain,List.of());source.changeAfterRead=true;
            assertThrows(IllegalStateException.class,()->new StarCoreResourceHistorySource(()->source).poll(journal));
            source.changeAfterRead=false;int[] calls={0};
            assertThrows(IllegalStateException.class,()->new StarCoreResourceHistorySource(()->++calls[0]==1?source:new Object()).poll(journal));
            assertTrue(journal.cursor(StarCoreResourceHistorySource.SOURCE).isEmpty());
        }
    }
    @Test void nativeHeadFailureOrMissingSequenceNeverAdvancesTheCursor(){
        try(var store=new SqliteStore(directory.resolve("gap.db"))){
            var journal=new CommittedHistoryJournal(store);var source=new Source(domain,List.of(receipt(2)));var adapter=new StarCoreResourceHistorySource(()->source);
            assertThrows(IllegalArgumentException.class,()->adapter.poll(journal));
            source.rows=List.of();source.failure=new IllegalStateException("committed native tail is missing");
            assertThrows(IllegalStateException.class,()->adapter.poll(journal));
            assertTrue(journal.cursor(StarCoreResourceHistorySource.SOURCE).isEmpty());assertTrue(journal.pending(100).isEmpty());
        }
    }
    @Test void malformedReceiptsCannotChangeTheJournal(){
        try(var store=new SqliteStore(directory.resolve("invalid.db"))){
            var journal=new CommittedHistoryJournal(store);var source=new Source(domain,List.of());var adapter=new StarCoreResourceHistorySource(()->source);
            for(var receipt:List.of(
                    new Receipt(UUID.randomUUID().toString(),1,"x",payer,receiver,Map.of("mineral",1L),"OTHER","",Instant.EPOCH),
                    new Receipt(domain.toString(),1,"x",payer,payer,Map.of("mineral",1L),"OTHER","",Instant.EPOCH),
                    new Receipt(domain.toString(),1,"x",payer,receiver,Map.of("mineral",0L),"OTHER","",Instant.EPOCH),
                    new Receipt(domain.toString(),1,"x",payer,receiver,Map.of(),"OTHER","",Instant.EPOCH),
                    new Receipt(domain.toString(),1,"x",payer,receiver,Map.of("mineral",1L),"MINT","",Instant.EPOCH))){
                source.rows=List.of(receipt);assertThrows(IllegalArgumentException.class,()->adapter.poll(journal));
            }
            assertTrue(journal.cursor(StarCoreResourceHistorySource.SOURCE).isEmpty());assertTrue(journal.pending(100).isEmpty());
        }
    }
    @Test void anotherSourcesPendingPageCannotBlockResourceHistory(){
        try(var store=new SqliteStore(directory.resolve("sources.db"))){
            var journal=new CommittedHistoryJournal(store);
            var other=new WorldEvent("technology:pending","TECHNOLOGY_UNLOCKED",Instant.EPOCH,Set.of(),Set.of(),Map.of(),"native","retained");
            journal.accept(StarCoreTechnologyHistorySource.SOURCE,UUID.randomUUID(),0,List.of(new CommittedHistoryJournal.Entry(1,other)));
            Source stable=new Source(domain,List.of(receipt(1)));var adapter=new StarCoreResourceHistorySource(()->stable);
            assertTrue(adapter.poll(journal));assertFalse(adapter.poll(journal));
            consume(journal,new RuleEngine(store,List.of(),Clock.systemUTC()));
            assertEquals(List.of(other),journal.pending(StarCoreTechnologyHistorySource.SOURCE,100));assertTrue(adapter.poll(journal));
        }
    }
}
