package io.github.addxiaoyi.starchronicle.integration;

import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import io.github.addxiaoyi.starchronicle.api.contribution.ContributionType;
import io.github.addxiaoyi.starchronicle.core.RuleEngine;
import io.github.addxiaoyi.starchronicle.history.CommittedHistoryJournal;
import io.github.addxiaoyi.starchronicle.history.NationTradeEvidence;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class StarCoreNationTradeSourceTest {
    @TempDir Path directory;
    final UUID domain=UUID.randomUUID(),resourceDomain=UUID.randomUUID(),treasuryDomain=UUID.randomUUID();
    final UUID seller=UUID.randomUUID(),buyer=UUID.randomUUID(),sellerNation=UUID.randomUUID(),buyerNation=UUID.randomUUID();
    final Instant now=Instant.parse("2026-11-25T18:00:00Z");
    public record Offer(UUID offerId,UUID sellerId,UUID buyerId,UUID sellerNationId,UUID buyerNationId,String resourceId,long amount,long unitPriceMinor,
                        String resourceDomain,String treasuryDomain,Instant createdAt,Instant expiresAt){}
    public record Receipt(UUID authorityDomain,long sequence,Offer offer,long resourceTransferSequence,Instant committedAt){}
    public static class Source {
        Optional<UUID> domain;List<Receipt> rows;int reads;boolean change;RuntimeException failure;
        Source(UUID domain,List<Receipt> rows){this.domain=Optional.of(domain);this.rows=rows;}
        public Optional<UUID> settlementAuthorityDomain(){return domain;}
        public List<Receipt> settlementsAfter(UUID expected,long after,int limit){
            assertEquals(domain.orElseThrow(),expected);assertEquals(100,limit);reads++;
            if(failure!=null)throw failure;if(change)domain=Optional.of(UUID.randomUUID());
            return rows.stream().filter(r->r.sequence()>after).toList();
        }
    }
    Receipt receipt(long sequence){return new Receipt(domain,sequence,new Offer(UUID.randomUUID(),seller,buyer,sellerNation,buyerNation,"mineral",20,250,
            resourceDomain.toString(),treasuryDomain.toString(),now,now.plusSeconds(300)),sequence+3,now.plusSeconds(sequence));}
    WorldEvent event(Receipt receipt)throws Exception{return StarCoreNationTradeSource.convert(domain,receipt).event();}
    RuleEngine engine(SqliteStore store){return new RuleEngine(store,List.of(),Clock.fixed(now.minusSeconds(1),ZoneOffset.UTC));}
    void consume(CommittedHistoryJournal journal,RuleEngine engine){
        for(WorldEvent e:journal.pending(StarCoreNationTradeSource.SOURCE,100)){engine.commitNationalTrade(e,List.of());journal.acknowledge(e.id());}
    }

    @Test void canonicalTradeEvidencePreservesBothOriginalActorsAndNativeAssetDomains()throws Exception{
        Receipt receipt=receipt(1);WorldEvent e=event(receipt);var p=NationTradeEvidence.fromEvent(e);
        assertEquals(Set.of(seller,buyer),e.participants());assertEquals(Set.of(sellerNation.toString(),buyerNation.toString()),e.nations());
        assertEquals("NATIONAL_TRADE_COMMITTED",e.type());assertEquals("NATIVE_SQL_TRANSACTION",e.facts().get("trade.asset_atomicity"));
        assertEquals(new BigDecimal("50.00"),p.totalValue());assertEquals(receipt.offer().offerId(),p.offerId());
        assertEquals(resourceDomain,p.resourceDomain());assertEquals(treasuryDomain,p.treasuryDomain());assertEquals(4,p.resourceSequence());
    }

    @Test void emptyServerCommitAndReplayAcrossRestartKeepFourViewsOfOneSettlement()throws Exception{
        var source=new Source(domain,List.of(receipt(1)));var adapter=new StarCoreNationTradeSource(()->source);
        Path path=directory.resolve("restart.db");WorldEvent e;
        try(var store=new SqliteStore(path)){
            var journal=new CommittedHistoryJournal(store);var engine=engine(store);
            assertTrue(adapter.poll(journal));e=journal.pending(StarCoreNationTradeSource.SOURCE,1).getFirst();
            assertFalse(engine.commitNationalTrade(e,List.of()).duplicate());assertFalse(adapter.poll(journal));
            var entries=engine.contributions(null,ContributionType.TRADE,0,100);assertEquals(4,entries.size());
            for(var entry:entries){
                var r=entry.request();assertEquals(e.id(),r.sourceEventId());assertEquals(e.occurredAt(),entry.recordedAt());
                if(r.actorId().equals(seller)){assertEquals(sellerNation.toString(),r.nationId());assertEquals("trade.resource:mineral",r.unit());assertEquals(0,new BigDecimal("20").compareTo(r.amount()));}
                else {assertEquals(buyer,r.actorId());assertEquals(buyerNation.toString(),r.nationId());assertEquals("trade.treasury",r.unit());assertEquals(0,new BigDecimal("50").compareTo(r.amount()));}
            }
        }
        try(var store=new SqliteStore(path)){
            var journal=new CommittedHistoryJournal(store);var engine=engine(store);
            assertTrue(engine.commitNationalTrade(e,List.of()).duplicate());consume(journal,engine);assertTrue(adapter.poll(journal));
            assertEquals(1,engine.history(null,null,0,100).size());assertEquals(4,engine.contributions(null,ContributionType.TRADE,0,100).size());
            assertEquals(1,journal.cursor(StarCoreNationTradeSource.SOURCE).orElseThrow().sequence());
        }
    }

    @Test void eventTextAloneCannotCreateATradeContribution()throws Exception{
        try(var store=new SqliteStore(directory.resolve("forged.db"))){
            new CommittedHistoryJournal(store);var engine=engine(store);WorldEvent e=event(receipt(1));
            assertThrows(SecurityException.class,()->engine.commitNationalTrade(e,List.of()));
            assertTrue(engine.history(null,null,0,100).isEmpty());assertTrue(engine.contributions(null,null,0,100).isEmpty());
            engine.accept(e,List.of());assertTrue(engine.contributions(null,null,0,100).isEmpty());
            assertThrows(SecurityException.class,()->engine.commitNationalTrade(e,List.of()));
        }
    }

    @Test void otherHistoryNamespaceCannotSupplyAtomicTradeProof()throws Exception{
        try(var store=new SqliteStore(directory.resolve("wrong-source.db"))){
            var journal=new CommittedHistoryJournal(store);var engine=engine(store);WorldEvent e=event(receipt(1));
            journal.accept(StarCoreTradeHistorySource.SOURCE,domain,0,List.of(new CommittedHistoryJournal.Entry(1,e)));
            assertThrows(SecurityException.class,()->engine.commitNationalTrade(e,List.of()));
            assertTrue(engine.contributions(null,null,0,100).isEmpty());
        }
    }

    @Test void contributionFailureRollsBackHistoryAndAllViewsButRetainsNativeInbox()throws Exception{
        try(var store=new SqliteStore(directory.resolve("rollback.db"))){
            var journal=new CommittedHistoryJournal(store);var engine=engine(store);
            var source=new Source(domain,List.of(receipt(1)));
            assertTrue(new StarCoreNationTradeSource(()->source).poll(journal));
            WorldEvent e=journal.pending(StarCoreNationTradeSource.SOURCE,1).getFirst();
            store.transaction(c->{try(var s=c.createStatement()){
                s.execute("CREATE TRIGGER fail_trade_contribution BEFORE INSERT ON contributions WHEN NEW.unit='trade.treasury' BEGIN SELECT RAISE(ABORT,'injected contribution failure'); END");
            }return null;});
            assertThrows(RuntimeException.class,()->engine.commitNationalTrade(e,List.of()));
            assertTrue(engine.history(null,null,0,100).isEmpty());assertTrue(engine.contributions(null,null,0,100).isEmpty());
            assertEquals(List.of(e),journal.pending(StarCoreNationTradeSource.SOURCE,100));
            store.transaction(c->{try(var s=c.createStatement()){s.execute("DROP TRIGGER fail_trade_contribution");}return null;});
            consume(journal,engine);assertEquals(4,engine.contributions(null,null,0,100).size());
        }
    }

    @ParameterizedTest @ValueSource(strings={"trade.total_value","trade.seller_player","trade.resource_domain","source.sequence","trade.asset_atomicity"})
    void alteredProjectionDoesNotGetContributionsEvenWhenStoredInInbox(String key)throws Exception{
        try(var store=new SqliteStore(directory.resolve("altered.db"))){
            var journal=new CommittedHistoryJournal(store);var engine=engine(store);WorldEvent good=event(receipt(1));
            var facts=new HashMap<>(good.facts());facts.put(key,key.equals("trade.total_value")?"999.00":key.equals("source.sequence")?"2":UUID.randomUUID().toString());
            WorldEvent altered=new WorldEvent(good.id(),good.type(),good.occurredAt(),good.participants(),good.nations(),facts,good.source(),good.evidence());
            journal.accept(StarCoreNationTradeSource.SOURCE,domain,0,List.of(new CommittedHistoryJournal.Entry(1,altered)));
            assertThrows(RuntimeException.class,()->engine.commitNationalTrade(altered,List.of()));assertTrue(engine.contributions(null,null,0,100).isEmpty());
        }
    }

    @Test void oldProviderAndMissingCapabilityDoNotBindAnInventedDomain(){
        try(var store=new SqliteStore(directory.resolve("capability.db"))){
            var journal=new CommittedHistoryJournal(store);assertFalse(new StarCoreNationTradeSource(()->new Object()).poll(journal));
            var source=new Source(domain,List.of());source.domain=Optional.empty();assertFalse(new StarCoreNationTradeSource(()->source).poll(journal));
            assertEquals(0,source.reads);assertTrue(journal.cursor(StarCoreNationTradeSource.SOURCE).isEmpty());
        }
    }

    @Test void providerOrDomainSwitchDuringReadNeverAdvancesTheCursor(){
        try(var store=new SqliteStore(directory.resolve("switched.db"))){
            var journal=new CommittedHistoryJournal(store);var source=new Source(domain,List.of());source.change=true;
            assertThrows(IllegalStateException.class,()->new StarCoreNationTradeSource(()->source).poll(journal));
            source.change=false;int[] calls={0};assertThrows(IllegalStateException.class,()->new StarCoreNationTradeSource(()->++calls[0]==1?source:new Object()).poll(journal));
            assertTrue(journal.cursor(StarCoreNationTradeSource.SOURCE).isEmpty());
        }
    }

    @Test void sequenceGapOrNativeFailureNeverMovesTheLocalCursor(){
        try(var store=new SqliteStore(directory.resolve("gap.db"))){
            var journal=new CommittedHistoryJournal(store);var source=new Source(domain,List.of(receipt(2)));
            assertThrows(IllegalArgumentException.class,()->new StarCoreNationTradeSource(()->source).poll(journal));
            source.failure=new IllegalStateException("native history lost its tail");
            assertThrows(IllegalStateException.class,()->new StarCoreNationTradeSource(()->source).poll(journal));
            assertTrue(journal.cursor(StarCoreNationTradeSource.SOURCE).isEmpty());assertTrue(journal.pending(100).isEmpty());
        }
    }

    @Test void repeatedOfferUnderAnotherSequenceCannotCountTurnoverTwice()throws Exception{
        Receipt one=receipt(1),two=new Receipt(domain,2,one.offer(),8,now.plusSeconds(2));
        try(var store=new SqliteStore(directory.resolve("rebound.db"))){
            var journal=new CommittedHistoryJournal(store);var engine=engine(store);
            var source=new Source(domain,List.of(one,two));
            assertTrue(new StarCoreNationTradeSource(()->source).poll(journal));
            var pending=journal.pending(StarCoreNationTradeSource.SOURCE,100);
            engine.commitNationalTrade(pending.get(0),List.of());
            assertThrows(IllegalStateException.class,()->engine.commitNationalTrade(pending.get(1),List.of()));
            assertEquals(4,engine.contributions(null,null,0,100).size());assertEquals(1,engine.history(null,null,0,100).size());
        }
    }

    @Test void emptyBoundDomainCannotBeReplacedWithoutReconciliation(){
        try(var store=new SqliteStore(directory.resolve("rebind.db"))){
            var journal=new CommittedHistoryJournal(store);var source=new Source(domain,List.of());var adapter=new StarCoreNationTradeSource(()->source);
            assertTrue(adapter.poll(journal));source.domain=Optional.of(UUID.randomUUID());
            assertThrows(IllegalStateException.class,()->adapter.poll(journal));assertEquals(domain,journal.cursor(StarCoreNationTradeSource.SOURCE).orElseThrow().domain());
        }
    }
}
