package io.github.addxiaoyi.starchronicle.integration;

import io.github.addxiaoyi.starchronicle.history.CommittedHistoryJournal;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class StarCoreTradeHistorySourceTest {
    @TempDir Path directory;
    final UUID domain=UUID.randomUUID(),player=UUID.randomUUID(),nation=UUID.randomUUID(),recordId=UUID.randomUUID();
    public record NativeRecord(UUID recordId,String orderType,String orderSource,Optional<UUID> sellerPlayerId,Optional<UUID> buyerPlayerId,
            Optional<UUID> sellerNationId,Optional<UUID> buyerNationId,String resourceId,long amount,double pricePerUnit,double totalValue,double taxAmount,double netValue,
            Instant executedAt,Optional<UUID> buyOrderId,Optional<UUID> sellOrderId,String status,Optional<String> notes){}
    public record FundedRecord(UUID recordId,String orderType,String orderSource,Optional<UUID> sellerPlayerId,Optional<UUID> buyerPlayerId,
            Optional<UUID> sellerNationId,Optional<UUID> buyerNationId,String resourceId,long amount,double pricePerUnit,double totalValue,double taxAmount,double netValue,
            Instant executedAt,Optional<UUID> buyOrderId,Optional<UUID> sellOrderId,String status,Optional<String> notes,Object buyerFunded){
        public Object buyerFunded(){if(buyerFunded instanceof RuntimeException failure)throw failure;return buyerFunded;}
    }
    public record Change(UUID authorityDomain,long sequence,String kind,Instant recordedAt,Object record){}
    public static class Source {
        UUID domain;List<Change> rows;int reads;
        Source(UUID domain,List<Change> rows){this.domain=domain;this.rows=rows;}
        public UUID historyAuthorityDomain(){return domain;}
        public List<Change> changesAfter(UUID expected,long after,int limit){assertEquals(domain,expected);assertEquals(100,limit);reads++;return rows.stream().filter(r->r.sequence()>after).toList();}
    }
    NativeRecord record(String status,boolean actor){return new NativeRecord(recordId,"BUY","EVENT",Optional.empty(),actor?Optional.of(player):Optional.empty(),Optional.empty(),Optional.of(nation),
            "agricultural",Long.MAX_VALUE,0.5,Long.MAX_VALUE*0.5,0,Long.MAX_VALUE*0.5,Instant.parse("2020-01-01T00:00:00Z"),Optional.empty(),Optional.empty(),status,Optional.of("x".repeat(10000)));}
    Change change(long sequence,String kind,String status,boolean actor){return new Change(domain,sequence,kind,Instant.EPOCH.plusSeconds(sequence),record(status,actor));}
    NativeRecord sale(double price,double total,double tax,double net){return new NativeRecord(recordId,"BUY","PLAYER",Optional.of(UUID.fromString("00000000-0000-0000-0000-000000000001")),Optional.of(player),Optional.of(nation),Optional.of(nation),
            "agricultural",20,price,total,tax,net,Instant.EPOCH,Optional.empty(),Optional.empty(),"COMPLETED",Optional.empty());}
    FundedRecord funding(NativeRecord r,Object value){return new FundedRecord(r.recordId(),r.orderType(),r.orderSource(),r.sellerPlayerId(),r.buyerPlayerId(),r.sellerNationId(),r.buyerNationId(),r.resourceId(),r.amount(),r.pricePerUnit(),r.totalValue(),r.taxAmount(),r.netValue(),r.executedAt(),r.buyOrderId(),r.sellOrderId(),r.status(),r.notes(),value);}
    Change withRecord(Object record){return new Change(domain,1,"UPSERT",Instant.EPOCH,record);}

    @Test void absentAndFalseFundingCapabilityPreserveExactLegacyEvent()throws Exception{
        var legacy=sale(2.5,50,3,47);
        var before=StarCoreTradeHistorySource.convert(domain,withRecord(legacy)).event();
        var after=StarCoreTradeHistorySource.convert(domain,withRecord(funding(legacy,false))).event();
        assertEquals(before,after);assertFalse(after.facts().containsKey("trade.tax_payer"));
        assertEquals("47.0",after.facts().get("trade.net_value"));
    }
    @Test void buyerPaysExtraTaxWithoutUpgradingHistoryToSettlementEvidence()throws Exception{
        var event=StarCoreTradeHistorySource.convert(domain,withRecord(funding(sale(2.5,50,3,50),true))).event();
        assertEquals("BUYER",event.facts().get("trade.tax_payer"));
        assertEquals("50.0",event.facts().get("trade.net_value"));assertEquals("3.0",event.facts().get("trade.tax_amount"));
        assertEquals("TRADE_HISTORY_RECORDED",event.type());assertEquals("StarCore.TradeHistoryService",event.source());
        assertEquals("NOT_PROVEN_BY_HISTORY_JOURNAL",event.facts().get("trade.asset_atomicity"));
        assertFalse(event.facts().containsKey("trade.wallet_evidence"));
    }
    @Test void fundingFlagCannotHideIncorrectNetOrMalformedAmounts(){
        for(var bad:List.of(sale(2.5,50,3,47),sale(2.5,50,-1,50),sale(2.5,50,51,50),sale(2.5,50,Double.NaN,50),sale(2.5,50,3,Double.POSITIVE_INFINITY),sale(0,50,3,50),sale(2.5,0,0,0)))
            assertThrows(IllegalArgumentException.class,()->StarCoreTradeHistorySource.convert(domain,withRecord(funding(bad,true))));
        assertThrows(IllegalArgumentException.class,()->StarCoreTradeHistorySource.convert(domain,withRecord(sale(2.5,50,3,50))));
        assertThrows(IllegalArgumentException.class,()->StarCoreTradeHistorySource.convert(domain,withRecord(funding(sale(2.5,50,3,50),false))));
    }
    @Test void buyerFundedGrossUsesNativeCentRounding()throws Exception{
        assertEquals("50.01",StarCoreTradeHistorySource.convert(domain,withRecord(funding(sale(2.50025,50.01,3,50.01),true))).event().facts().get("trade.total_value"));
        assertThrows(IllegalArgumentException.class,()->StarCoreTradeHistorySource.convert(domain,withRecord(funding(sale(2.50025,50.005,3,50.005),true))));
    }
    @Test void malformedFundingCapabilityIsNotTreatedAsAnOldProvider(){
        for(Object value:Arrays.asList(null,"true",1))
            assertThrows(IllegalArgumentException.class,()->StarCoreTradeHistorySource.convert(domain,withRecord(funding(sale(2.5,50,3,47),value))));
    }
    @Test void brokenAdvertisedFundingCapabilityCannotCommitPartOfPage(){
        var valid=withRecord(funding(sale(2.5,50,3,50),true));
        var broken=new Change(domain,2,"UPSERT",Instant.EPOCH,funding(sale(2.5,50,3,47),new IllegalStateException("unavailable")));
        try(var store=new SqliteStore(directory.resolve("funding.db"))){
            var journal=new CommittedHistoryJournal(store);var adapter=new StarCoreTradeHistorySource(()->new Source(domain,List.of(valid,broken)));
            assertThrows(IllegalStateException.class,()->adapter.poll(journal));
            assertTrue(journal.cursor(StarCoreTradeHistorySource.SOURCE).isEmpty());assertTrue(journal.pending(100).isEmpty());
        }
    }

    @Test void projectsJournalSemanticsWithoutInventingContributionActorOrRefundCompletion()throws Exception{
        var event=StarCoreTradeHistorySource.convert(domain,change(1,"UPSERT","REFUNDED",false)).event();
        assertTrue(event.participants().isEmpty());assertEquals(Set.of(nation.toString()),event.nations());
        assertEquals("TRADE_HISTORY_RECORDED",event.type());assertEquals("REFUNDED",event.facts().get("trade.status"));
        assertEquals("NOT_PROVEN_BY_HISTORY_JOURNAL",event.facts().get("trade.asset_atomicity"));
        assertEquals("JOURNAL_RECORDED",event.facts().get("time_basis"));assertEquals(Instant.EPOCH.plusSeconds(1),event.occurredAt());
        assertEquals(Long.toString(Long.MAX_VALUE),event.facts().get("trade.amount"));
        assertEquals("10000",event.facts().get("trade.notes_length"));assertFalse(event.facts().containsKey("trade.notes"));
        assertEquals("2020-01-01T00:00:00Z",event.facts().get("trade.executed_at"));
        assertTrue(event.evidence().contains("does not prove refund completion"));
    }
    @Test void baselineDeletionAndActorSnapshotsStayDistinct()throws Exception{
        var baseline=StarCoreTradeHistorySource.convert(domain,change(1,"BASELINE","COMPLETED",true)).event();
        var deleted=StarCoreTradeHistorySource.convert(domain,change(2,"DELETE","COMPLETED",true)).event();
        assertEquals("TRADE_HISTORY_BASELINE",baseline.type());assertEquals("TRADE_HISTORY_REMOVED",deleted.type());
        assertEquals(Set.of(player),baseline.participants());assertNotEquals(baseline.id(),deleted.id());
        assertEquals(baseline,StarCoreTradeHistorySource.convert(domain,change(1,"BASELINE","COMPLETED",true)).event());
    }
    @Test void backlogPreventsFetchingMoreAndRestartUsesDurableCursor(){
        var source=new Source(domain,List.of(change(1,"UPSERT","COMPLETED",true),change(2,"UPSERT","DISPUTED",true)));
        var adapter=new StarCoreTradeHistorySource(()->source);
        Path path=directory.resolve("history.db");
        try(var store=new SqliteStore(path)){
            var journal=new CommittedHistoryJournal(store);assertTrue(adapter.poll(journal));assertEquals(2,journal.pending(100).size());
            assertFalse(adapter.poll(journal));assertEquals(1,source.reads);
            journal.pending(100).forEach(event->journal.acknowledge(event.id()));
        }
        try(var store=new SqliteStore(path)){
            var journal=new CommittedHistoryJournal(store);assertTrue(adapter.poll(journal));assertTrue(journal.pending(100).isEmpty());
            assertEquals(2,journal.cursor(StarCoreTradeHistorySource.SOURCE).orElseThrow().sequence());
        }
    }
    @Test void changedDomainOrProviderCannotCommitPage(){
        var source=new Source(domain,List.of(change(1,"UPSERT","COMPLETED",true)));
        try(var store=new SqliteStore(directory.resolve("changed.db"))){
            var journal=new CommittedHistoryJournal(store);journal.accept(StarCoreTradeHistorySource.SOURCE,UUID.randomUUID(),0,List.of());
            assertThrows(IllegalStateException.class,()->new StarCoreTradeHistorySource(()->source).poll(journal));assertEquals(0,source.reads);
        }
        try(var store=new SqliteStore(directory.resolve("provider.db"))){
            var journal=new CommittedHistoryJournal(store);int[] calls={0};
            assertThrows(IllegalStateException.class,()->new StarCoreTradeHistorySource(()->++calls[0]==1?source:new Object()).poll(journal));
            assertTrue(journal.cursor(StarCoreTradeHistorySource.SOURCE).isEmpty());
        }
    }
    @Test void unsupportedProviderAndMalformedPageNeverInventCursor(){
        try(var store=new SqliteStore(directory.resolve("invalid.db"))){
            var journal=new CommittedHistoryJournal(store);
            assertFalse(new StarCoreTradeHistorySource(()->new Object()).poll(journal));
            var source=new Source(domain,List.of(change(2,"UPSERT","COMPLETED",true)));
            assertThrows(IllegalArgumentException.class,()->new StarCoreTradeHistorySource(()->source).poll(journal));
            assertTrue(journal.cursor(StarCoreTradeHistorySource.SOURCE).isEmpty());
        }
    }
}
