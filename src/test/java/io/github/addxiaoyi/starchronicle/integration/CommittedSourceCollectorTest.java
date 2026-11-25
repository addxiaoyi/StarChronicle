package io.github.addxiaoyi.starchronicle.integration;

import io.github.addxiaoyi.starchronicle.history.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CommittedSourceCollectorTest {
    static final UUID DOMAIN=UUID.randomUUID(),SELLER=UUID.randomUUID(),BUYER=UUID.randomUUID(),NATION=UUID.randomUUID(),OTHER_NATION=UUID.randomUUID();
    static final Instant NOW=Instant.parse("2026-11-25T00:00:00.123456789Z");
    public record Trade(UUID recordId,Optional<UUID> sellerPlayerId,Optional<UUID> buyerPlayerId,Optional<UUID> sellerNationId,Optional<UUID> buyerNationId,String status) {
        public String resourceId(){return "industrial";}public long amount(){return 20;}public double pricePerUnit(){return 3.5;}public double taxAmount(){return 0.5;}
        public Instant executedAt(){return NOW.minusSeconds(50);}public String orderType(){return "BUY";}public String orderSource(){return "MARKET";}
        public Optional<UUID> buyOrderId(){return Optional.empty();}public Optional<UUID> sellOrderId(){return Optional.empty();}public Optional<String> notes(){return Optional.of("交易|notes");}
    }
    public record Change(UUID authorityDomainId,long sequence,UUID operationId,Trade trade,Instant committedAt,boolean baseline) {}
    public static class TradeService {
        List<Change> records=new ArrayList<>();long after=-1;
        public Optional<UUID> historyAuthorityDomainId(){return Optional.of(DOMAIN);}
        public List<Change> committedChangesAfter(long after,int limit){this.after=after;return records.stream().filter(c->c.sequence()>after).limit(limit).toList();}
    }
    Trade trade(UUID seller,UUID buyer){return new Trade(UUID.randomUUID(),Optional.ofNullable(seller),Optional.ofNullable(buyer),Optional.of(NATION),Optional.of(OTHER_NATION),"COMPLETED");}
    @Test void readsBothTradeActorsAndImmutableTermsIncludingAReplayedAnchor() throws Exception {
        var nativeService=new TradeService();var trade=trade(SELLER,BUYER);
        nativeService.records.add(new Change(DOMAIN,1,UUID.randomUUID(),trade,NOW,false));
        nativeService.records.add(new Change(DOMAIN,2,UUID.randomUUID(),new Trade(trade.recordId(),trade.sellerPlayerId(),trade.buyerPlayerId(),trade.sellerNationId(),trade.buyerNationId(),"REFUNDED"),NOW.plusSeconds(1),false));
        var page=new CommittedSourceCollector(type->nativeService).collect(CommittedSourceCollector.Source.TRADE,Optional.of(new CommittedSourceLedger.Cursor(DOMAIN.toString(),1))).orElseThrow();
        assertEquals(0,nativeService.after);assertEquals(2,page.entries().size());
        assertEquals(4,page.entries().getFirst().trade().contributions().size());assertEquals(Set.of(SELLER,BUYER),page.entries().getFirst().event().participants());
        assertEquals(page.entries().getFirst().trade().termsHash(),page.entries().getLast().trade().termsHash());
        assertEquals(NOW,page.entries().getFirst().event().occurredAt());assertTrue(page.entries().getLast().trade().contributions().isEmpty());
    }
    @Test void selfTradeAndActorlessNationalTradeAreHistoryWithoutCredit() throws Exception {
        for(var trade:List.of(trade(SELLER,SELLER),trade(null,null))) {
            var service=new TradeService();service.records.add(new Change(DOMAIN,1,UUID.randomUUID(),trade,NOW,false));
            var entry=new CommittedSourceCollector(type->service).collect(CommittedSourceCollector.Source.TRADE,Optional.empty()).orElseThrow().entries().getFirst();
            assertTrue(entry.trade().contributions().isEmpty());assertEquals("NO_DISTINCT_ACTOR",entry.event().facts().get("trade.contribution"));
        }
    }
    @Test void baselineLabelsOldRowsAndDoesNotAwardThem() throws Exception {
        var service=new TradeService();service.records.add(new Change(DOMAIN,1,UUID.randomUUID(),trade(SELLER,BUYER),NOW,true));
        var entry=new CommittedSourceCollector(type->service).collect(CommittedSourceCollector.Source.TRADE,Optional.empty()).orElseThrow().entries().getFirst();
        assertTrue(entry.baseline());assertEquals("TRADE_BASELINE",entry.event().type());assertTrue(entry.trade().contributions().isEmpty());
    }
    @Test void missingMethodsDegradeButLostAnchorWrongDomainAndGapsFailClosed() throws Exception {
        assertTrue(new CommittedSourceCollector(type->new Object()).collect(CommittedSourceCollector.Source.TRADE,Optional.empty()).isEmpty());
        var service=new TradeService();var collector=new CommittedSourceCollector(type->service);
        assertThrows(IllegalStateException.class,()->collector.collect(CommittedSourceCollector.Source.TRADE,Optional.of(new CommittedSourceLedger.Cursor(DOMAIN.toString(),1))));
        assertThrows(IllegalStateException.class,()->collector.collect(CommittedSourceCollector.Source.TRADE,Optional.of(new CommittedSourceLedger.Cursor(UUID.randomUUID().toString(),0))));
        service.records.add(new Change(UUID.randomUUID(),1,UUID.randomUUID(),trade(SELLER,BUYER),NOW,false));
        assertThrows(IllegalStateException.class,()->collector.collect(CommittedSourceCollector.Source.TRADE,Optional.empty()));
        service.records=List.of(new Change(DOMAIN,2,UUID.randomUUID(),trade(SELLER,BUYER),NOW,false));
        assertThrows(IllegalArgumentException.class,()->collector.collect(CommittedSourceCollector.Source.TRADE,Optional.empty()));
    }
    public record Tech(UUID authorityDomainId,long sequence,UUID operationId,UUID nationId,String technologyKey,String era,String change,Instant committedAt) {}
    public static class TechnologyService {
        public Optional<UUID> historyAuthorityDomainId(){return Optional.of(DOMAIN);}
        public List<Tech> technologyChangesAfter(long after,int limit){return List.of(new Tech(DOMAIN,1,new UUID(0,1),NATION,"railway","industrial","UNLOCKED",NOW),new Tech(DOMAIN,2,new UUID(0,2),NATION,"railway","industrial","REVOKED",NOW.plusSeconds(1)));}
    }
    @Test void technologyUnlockAndRevokeBothKeepRealTimesAndNoFabricatedResearcher() throws Exception {
        var page=new CommittedSourceCollector(type->new TechnologyService()).collect(CommittedSourceCollector.Source.TECHNOLOGY,Optional.empty()).orElseThrow();
        assertEquals(List.of("TECHNOLOGY_UNLOCKED","TECHNOLOGY_REVOKED"),page.entries().stream().map(e->e.event().type()).toList());
        assertTrue(page.entries().stream().allMatch(e->e.event().participants().isEmpty()&&e.trade()==null));
    }
    public record Capital(long sequence,UUID operationId,UUID nationId,UUID previousCityId,UUID cityId,UUID actorId,Instant committedAt) {}
    public static class NationService {
        public String authorityDomainId(){return DOMAIN.toString();}
        public List<Capital> capitalDesignationsAfter(long after,int limit){return List.of(new Capital(1,new UUID(0,1),NATION,null,new UUID(0,11),SELLER,NOW),new Capital(2,new UUID(0,2),NATION,new UUID(0,11),new UUID(0,12),SELLER,NOW.plusSeconds(1)));}
    }
    @Test void initialCapitalDesignationIsDistinctFromMigration() throws Exception {
        var page=new CommittedSourceCollector(type->new NationService()).collect(CommittedSourceCollector.Source.CAPITAL,Optional.empty()).orElseThrow();
        assertEquals(List.of("CAPITAL_DESIGNATED","CAPITAL_MOVED"),page.entries().stream().map(e->e.event().type()).toList());
        assertEquals(Set.of(SELLER),page.entries().getLast().event().participants());
    }
    public record Transfer(String authorityDomainId,long sequence,String transactionId,UUID payerNationId,UUID receiverNationId,Map<String,Long> resources,String category,String description,Instant appliedAt) {}
    public static class ResourceService {
        public String authorityDomainId(){return DOMAIN.toString();}
        public List<Transfer> transferReceiptsAfter(long after,int limit){return List.of(new Transfer(DOMAIN.toString(),1,"operation/native/1",NATION,OTHER_NATION,Map.of("agricultural",10L),"OTHER","Aid delivery",NOW));}
    }
    @Test void resourceTransferKeepsExactUnitsAndDoesNotGuessAnActor() throws Exception {
        var entry=new CommittedSourceCollector(type->new ResourceService()).collect(CommittedSourceCollector.Source.RESOURCES,Optional.empty()).orElseThrow().entries().getFirst();
        assertEquals("10",entry.event().facts().get("resource.amount.agricultural"));assertTrue(entry.event().participants().isEmpty());assertNull(entry.trade());
    }

    public record Production(String operationId,String authorityDomainId,long sequence,UUID nationId,UUID actorId,String recipeId,int batches,Map<String,Long> inputs,Map<String,Long> outputs,Instant committedAt) {}
    public static class ProductionService {
        List<Production> receipts=new ArrayList<>();
        public Optional<String> productionAuthorityDomainId(){return Optional.of(DOMAIN.toString());}
        public List<Production> productionReceiptsAfter(long after,int limit){return receipts.stream().filter(r->r.sequence()>after).limit(limit).toList();}
    }
    Production production(UUID actor,Map<String,Long> outputs){return new Production("national-batch",DOMAIN.toString(),1,NATION,actor,"national_industry",10,Map.of("mineral",30L,"energy",10L),outputs,NOW);}
    @Test void nationalConversionRetainsInputsAndCreditsEachOutputToExactActorAndNation()throws Exception {
        var service=new ProductionService();service.receipts.add(production(SELLER,Map.of("industrial",20L,"chemical",10L)));
        var entry=new CommittedSourceCollector(type->service).collect(CommittedSourceCollector.Source.PRODUCTION,Optional.empty()).orElseThrow().entries().getFirst();
        assertEquals("CIVILIZATION_PRODUCTION_COMMITTED",entry.event().type());assertEquals("30",entry.event().facts().get("production.input.mineral"));
        assertEquals(4,entry.contributions().size());assertEquals(Set.of(SELLER),entry.event().participants());
        assertEquals(2,entry.contributions().stream().map(r->r.operationId()).distinct().count());
        assertTrue(entry.contributions().stream().allMatch(r->r.type()==io.github.addxiaoyi.starchronicle.api.contribution.ContributionType.CIVILIZATION&&r.committedAt().equals(NOW)));
    }
    @Test void systemProductionAndOversizedOutputRetainHistoryWithoutFabricatedCredit()throws Exception {
        for(var receipt:List.of(production(null,Map.of("industrial",20L)),production(SELLER,Map.of("industrial",Long.MAX_VALUE)))) {
            var service=new ProductionService();service.receipts.add(receipt);
            var entry=new CommittedSourceCollector(type->service).collect(CommittedSourceCollector.Source.PRODUCTION,Optional.empty()).orElseThrow().entries().getFirst();
            assertTrue(entry.contributions().isEmpty());assertEquals(receipt.actorId()==null?Set.of():Set.of(SELLER),entry.event().participants());
        }
    }
    @Test void productionRejectsInvalidQuantityAndDomainAndKeepsResumeAnchor()throws Exception {
        var service=new ProductionService();service.receipts.add(production(SELLER,Map.of("industrial",0L)));
        var collector=new CommittedSourceCollector(type->service);
        assertThrows(IllegalArgumentException.class,()->collector.collect(CommittedSourceCollector.Source.PRODUCTION,Optional.empty()));
        service.receipts=List.of(production(SELLER,Map.of("industrial",20L)));
        assertThrows(IllegalStateException.class,()->collector.collect(CommittedSourceCollector.Source.PRODUCTION,Optional.of(new CommittedSourceLedger.Cursor(UUID.randomUUID().toString(),1))));
        assertEquals(1,collector.collect(CommittedSourceCollector.Source.PRODUCTION,Optional.of(new CommittedSourceLedger.Cursor(DOMAIN.toString(),1))).orElseThrow().entries().size());
    }
}
