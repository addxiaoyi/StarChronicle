package io.github.addxiaoyi.starchronicle.integration;

import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import io.github.addxiaoyi.starchronicle.core.RuleEngine;
import io.github.addxiaoyi.starchronicle.history.CommittedHistoryJournal;
import io.github.addxiaoyi.starchronicle.history.PlayerTradeEvidence;
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

class StarCorePlayerTradeSourceTest {
    @TempDir Path directory;
    final UUID domain=UUID.randomUUID(),resourceDomain=UUID.randomUUID(),treasuryDomain=UUID.randomUUID();
    final UUID seller=UUID.randomUUID(),buyer=UUID.randomUUID(),sellerNation=UUID.randomUUID(),buyerNation=UUID.randomUUID();
    final Instant now=Instant.parse("2026-11-26T00:00:00Z");
    public record Terms(UUID id,UUID seller,UUID buyer,UUID sellerNation,UUID buyerNation,UUID resourceDomain,UUID treasuryDomain,
                        String provider,String resource,long amount,BigDecimal unitPrice,long feeMinor,long tariffMinor,Instant createdAt,Instant expiresAt){}
    public record Settlement(Terms terms,String state,Instant acceptedAt,Instant settledAt){}
    public record Receipt(UUID authorityDomain,long sequence,Settlement settlement){}
    public static class Source {
        Optional<UUID> domain;List<Receipt> rows;int reads;boolean change;RuntimeException failure;
        Source(UUID domain,List<Receipt> rows){this.domain=Optional.of(domain);this.rows=rows;}
        public Optional<UUID> authorityDomain(){return domain;}
        public List<Receipt> settlementsAfter(UUID expected,long after,int limit){
            assertEquals(domain.orElseThrow(),expected);assertEquals(100,limit);reads++;
            if(failure!=null)throw failure;if(change)domain=Optional.of(UUID.randomUUID());
            return rows.stream().filter(r->r.sequence()>after).limit(limit).toList();
        }
    }
    Receipt receipt(long sequence){return receipt(sequence,false);}
    Receipt receipt(long sequence,boolean sameNation){
        var t=new Terms(UUID.randomUUID(),seller,buyer,sellerNation,sameNation?sellerNation:buyerNation,resourceDomain,treasuryDomain,
                "vault:Essentials:2.22.0","mineral",20,new BigDecimal("2.5"),100,sameNation?0:200,now,now.plusSeconds(300));
        return new Receipt(domain,sequence,new Settlement(t,"SETTLED",now.plusSeconds(1),now.plusSeconds(600)));
    }
    WorldEvent event(Receipt receipt)throws Exception{return StarCorePlayerTradeSource.convert(domain,receipt).event();}
    RuleEngine engine(SqliteStore store){return new RuleEngine(store,List.of(),Clock.fixed(now.minusSeconds(1),ZoneOffset.UTC));}
    void consume(CommittedHistoryJournal journal,RuleEngine engine){
        for(WorldEvent e:journal.pending(StarCorePlayerTradeSource.SOURCE,100)){engine.commitPlayerTrade(e,List.of());journal.acknowledge(e.id());}
    }

    @Test void evidenceRetainsExternalAcknowledgementBoundaryAndOriginalFrozenTerms()throws Exception{
        var r=receipt(1);var e=event(r);var p=PlayerTradeEvidence.fromEvent(e);
        assertEquals("PLAYER_TRADE_SETTLED",e.type());assertEquals("PROVIDER_ACKNOWLEDGED",e.facts().get("trade.wallet_evidence"));
        assertEquals("NATIVE_DELIVERY_AND_TAXES",e.facts().get("trade.asset_atomicity"));
        assertEquals(new BigDecimal("50.00"),p.gross());assertEquals(new BigDecimal("53.00"),p.total());
        assertEquals(Set.of(seller,buyer),e.participants());assertEquals(r.settlement().acceptedAt(),p.acceptedAt());
        assertTrue(p.settledAt().isAfter(p.expiresAt()));assertEquals(resourceDomain,p.resourceDomain());
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void offlineRecoveryAndReplayKeepFourViewsEvenForOneNation(boolean sameNation)throws Exception{
        var r=receipt(1,sameNation);var source=new Source(domain,List.of(r));var adapter=new StarCorePlayerTradeSource(()->source);
        var path=directory.resolve("restart.db");WorldEvent e;
        try(var store=new SqliteStore(path)){
            var journal=new CommittedHistoryJournal(store);var engine=engine(store);
            assertTrue(adapter.poll(journal));e=journal.pending(StarCorePlayerTradeSource.SOURCE,1).getFirst();
            assertFalse(engine.commitPlayerTrade(e,List.of()).duplicate());assertFalse(adapter.poll(journal));
            assertEquals(4,engine.contributions(null,null,0,100).size());
            for(var entry:engine.contributions(null,null,0,100)){
                var request=entry.request();assertEquals(e.occurredAt(),entry.recordedAt());
                assertEquals(e.id(),request.sourceEventId());
                if(request.actorId().equals(seller)){assertEquals("trade.player-resource:mineral",request.unit());assertEquals(0,BigDecimal.valueOf(20).compareTo(request.amount()));}
                else{assertEquals(buyer,request.actorId());assertEquals("trade.wallet-acknowledged",request.unit());assertEquals(0,new BigDecimal(sameNation?"51":"53").compareTo(request.amount()));}
            }
        }
        try(var store=new SqliteStore(path)){
            var journal=new CommittedHistoryJournal(store);var engine=engine(store);
            assertTrue(engine.commitPlayerTrade(e,List.of()).duplicate());consume(journal,engine);assertTrue(adapter.poll(journal));
            assertEquals(1,engine.history(null,null,0,100).size());assertEquals(4,engine.contributions(null,null,0,100).size());
            assertEquals(1,journal.cursor(StarCorePlayerTradeSource.SOURCE).orElseThrow().sequence());
        }
    }

    @Test void legacyHistoryAndEventTextCannotMintContributionEvidence()throws Exception{
        try(var store=new SqliteStore(directory.resolve("forged.db"))){
            var journal=new CommittedHistoryJournal(store);var engine=engine(store);var e=event(receipt(1));
            assertThrows(SecurityException.class,()->engine.commitPlayerTrade(e,List.of()));
            journal.accept(StarCoreTradeHistorySource.SOURCE,domain,0,List.of(new CommittedHistoryJournal.Entry(1,e)));
            assertThrows(SecurityException.class,()->engine.commitPlayerTrade(e,List.of()));
            engine.accept(e,List.of());assertTrue(engine.contributions(null,null,0,100).isEmpty());
        }
    }

    @Test void projectionFailureRollsBackEveryContributionAndHistoryButRetainsInbox()throws Exception{
        try(var store=new SqliteStore(directory.resolve("rollback.db"))){
            var journal=new CommittedHistoryJournal(store);var engine=engine(store);
            var source=new Source(domain,List.of(receipt(1)));
            assertTrue(new StarCorePlayerTradeSource(()->source).poll(journal));
            var e=journal.pending(StarCorePlayerTradeSource.SOURCE,1).getFirst();
            store.transaction(c->{try(var s=c.createStatement()){s.execute("CREATE TRIGGER fail_player_trade BEFORE INSERT ON contributions WHEN NEW.unit='trade.wallet-acknowledged' BEGIN SELECT RAISE(ABORT,'injected failure'); END");}return null;});
            assertThrows(RuntimeException.class,()->engine.commitPlayerTrade(e,List.of()));
            assertTrue(engine.history(null,null,0,100).isEmpty());assertTrue(engine.contributions(null,null,0,100).isEmpty());
            assertEquals(List.of(e),journal.pending(StarCorePlayerTradeSource.SOURCE,100));
            store.transaction(c->{try(var s=c.createStatement()){s.execute("DROP TRIGGER fail_player_trade");}return null;});
            consume(journal,engine);assertEquals(4,engine.contributions(null,null,0,100).size());
        }
    }

    @ParameterizedTest @ValueSource(strings={"trade.total","trade.seller_player","trade.wallet_evidence","trade.asset_atomicity","trade.accepted_at","source.sequence","trade.provider"})
    void alteredCanonicalEvidenceCannotCreateContributions(String key)throws Exception{
        try(var store=new SqliteStore(directory.resolve("altered.db"))){
            var journal=new CommittedHistoryJournal(store);var engine=engine(store);var good=event(receipt(1));
            var facts=new HashMap<>(good.facts());
            facts.put(key,key.equals("trade.total")?"999.00":key.equals("source.sequence")?"2":key.equals("trade.accepted_at")?now.plusSeconds(400).toString():UUID.randomUUID().toString());
            var altered=new WorldEvent(good.id(),good.type(),good.occurredAt(),good.participants(),good.nations(),facts,good.source(),good.evidence());
            journal.accept(StarCorePlayerTradeSource.SOURCE,domain,0,List.of(new CommittedHistoryJournal.Entry(1,altered)));
            assertThrows(RuntimeException.class,()->engine.commitPlayerTrade(altered,List.of()));assertTrue(engine.contributions(null,null,0,100).isEmpty());
        }
    }

    @ParameterizedTest @ValueSource(strings={"DEBIT_INTENT","CREDIT_UNKNOWN","REFUND_ACKNOWLEDGED","RELEASED","PROJECTED"})
    void arbitraryStateDoesNotSubstituteForImmutableSettlementReceipt(String state){
        var r=receipt(1);var modified=new Receipt(domain,1,new Settlement(r.settlement().terms(),state,r.settlement().acceptedAt(),r.settlement().settledAt()));
        assertThrows(IllegalArgumentException.class,()->StarCorePlayerTradeSource.convert(domain,modified));
    }

    @Test void providerSwapSequenceGapAndMissingTailNeverAdvanceCursor(){
        try(var store=new SqliteStore(directory.resolve("source.db"))){
            var journal=new CommittedHistoryJournal(store);var source=new Source(domain,List.of(receipt(2)));
            var adapter=new StarCorePlayerTradeSource(()->source);
            assertThrows(IllegalArgumentException.class,()->adapter.poll(journal));
            source.change=true;assertThrows(IllegalStateException.class,()->adapter.poll(journal));source.change=false;
            source.failure=new IllegalStateException("journal incomplete");assertThrows(IllegalStateException.class,()->adapter.poll(journal));
            assertTrue(journal.cursor(StarCorePlayerTradeSource.SOURCE).isEmpty());assertTrue(journal.pending(100).isEmpty());
        }
    }

    @Test void repeatedOfferUnderAnotherSequenceCannotDuplicateEitherSide()throws Exception{
        var first=receipt(1);var second=new Receipt(domain,2,first.settlement());
        try(var store=new SqliteStore(directory.resolve("duplicate.db"))){
            var journal=new CommittedHistoryJournal(store);var engine=engine(store);var source=new Source(domain,List.of(first,second));
            assertTrue(new StarCorePlayerTradeSource(()->source).poll(journal));
            var pending=journal.pending(StarCorePlayerTradeSource.SOURCE,100);
            engine.commitPlayerTrade(pending.getFirst(),List.of());
            assertThrows(IllegalStateException.class,()->engine.commitPlayerTrade(pending.getLast(),List.of()));
            assertEquals(4,engine.contributions(null,null,0,100).size());assertEquals(1,engine.history(null,null,0,100).size());
        }
    }

    @Test void absentCapabilityLeavesNoInventedDomain(){
        try(var store=new SqliteStore(directory.resolve("absent.db"))){
            var journal=new CommittedHistoryJournal(store);
            assertFalse(new StarCorePlayerTradeSource(()->null).poll(journal));assertFalse(new StarCorePlayerTradeSource(Object::new).poll(journal));
            var source=new Source(domain,List.of());source.domain=Optional.empty();assertFalse(new StarCorePlayerTradeSource(()->source).poll(journal));
            assertTrue(journal.cursor(StarCorePlayerTradeSource.SOURCE).isEmpty());
        }
    }
}
