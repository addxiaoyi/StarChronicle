package io.github.addxiaoyi.starchronicle.integration;

import io.github.addxiaoyi.starchronicle.api.contribution.*;
import io.github.addxiaoyi.starchronicle.api.trade.TradeHistoryReceipt;
import io.github.addxiaoyi.starchronicle.core.RuleEngine;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition.*;
import io.github.addxiaoyi.starchronicle.history.*;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static io.github.addxiaoyi.starchronicle.integration.StarCoreTradeHistoryTest.*;
import static io.github.addxiaoyi.starchronicle.history.TradeHistoryFixtures.*;
import static io.github.addxiaoyi.starchronicle.api.trade.TradeHistoryReceipt.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class TradeHistoryCoordinatorTest {
    @TempDir Path directory;
    private static final String NS=StarCoreTradeHistory.NAMESPACE;
    private static final String SUBJECT="player:"+SELLER;
    private final DualAuthority source=new DualAuthority();
    public record LegacyChange(UUID authorityDomainId,long sequence,UUID operationId,Instant committedAt,boolean baseline,NativeTrade trade) {}
    public static class DualAuthority {
        final Authority current=new Authority();
        public Optional<UUID> historyAuthorityDomainId(){return Optional.of(current.historyAuthorityDomainId());}
        public UUID tradeHistoryAuthorityDomainId(){return current.historyAuthorityDomainId();}
        public List<?> tradeChangesAfter(long after,int limit){return current.tradeChangesAfter(after,limit);}
        public List<LegacyChange> committedChangesAfter(long after,int limit){
            return current.changes.stream().filter(r->r.sequence()>after).limit(limit).map(r->new LegacyChange(
                    r.authorityDomainId(),r.sequence(),new UUID(0,r.sequence()),r.committedAt(),false,r.record())).toList();
        }
        void add(TradeHistoryReceipt receipt){current.add(receipt);}
    }
    private RuleEngine engine(SqliteStore store){
        var story=new StoryDefinition("merged-trades","1","Trades",Scope.NATION,
                List.of("TRADE_COMPLETED","TRADE_REFUNDED","TRADE_DISPUTED"),null,"done",0,
                Map.of("done",new Node("Done",null,null,1,Map.of(),List.of(),true)));
        return new RuleEngine(store,List.of(story),Clock.fixed(TIME.plusSeconds(1000),ZoneOffset.UTC));
    }
    private void legacy(RuleEngine engine)throws Exception {
        engine.acceptCommittedSource(new CommittedSourceCollector(key->source).collect(CommittedSourceCollector.Source.TRADE,
                engine.committedSourceCursor(NS)).orElseThrow(),List.of());
    }
    private void sql(SqliteStore store,String text){store.transaction(c->{try(var s=c.createStatement()){s.execute(text);}return null;});}
    private String rows(SqliteStore store,String table){return store.transaction(c->{
        List<String> values=new ArrayList<>();try(var s=c.createStatement();var r=s.executeQuery("SELECT * FROM "+table+" ORDER BY 1,2")){
            while(r.next()){List<String> row=new ArrayList<>();for(int i=1;i<=r.getMetaData().getColumnCount();i++)row.add(r.getString(i));values.add(row.toString());}
        }return values.toString();});}

    @Test void installedLegacyRefundKeepsOriginalRowsAndUsesOriginalCursorAfterRestart()throws Exception {
        Path db=directory.resolve("legacy.db");source.add(receipt(1,null,COMPLETED));source.add(receipt(2,COMPLETED,REFUNDED));
        Map<String,String> originals=new LinkedHashMap<>();
        try(var store=new SqliteStore(db)){
            var engine=engine(store);legacy(engine);sql(store,"DELETE FROM contribution_revisions");
            for(String table:List.of("contributions","committed_source_cursors","committed_source_receipts","native_trade_states","native_trade_contributions","events"))originals.put(table,rows(store,table));
        }
        try(var store=new SqliteStore(db)){
            var engine=engine(store);var journal=new CommittedHistoryJournal(store);
            var coordinator=new TradeHistoryCoordinator(engine,key->source,journal);
            assertEquals(0,coordinator.poll(List.of()));assertTrue(journal.cursor(NS).isEmpty());
            originals.forEach((table,original)->assertEquals(original,rows(store,table),table));
            var balance=engine.contributionBalances(SUBJECT,null,0,10).getFirst();
            assertEquals(ContributionRevision.Status.REFUNDED,balance.status());assertEquals(BigDecimal.ZERO,balance.netAmount());
            assertEquals(TIME.minusSeconds(2),balance.statusCommittedAt());
            assertTrue(engine.contributionRevisions(balance.entry().id(),0,100).isEmpty());
            source.add(receipt(3,REFUNDED,COMPLETED));assertEquals(1,coordinator.poll(List.of()));
            assertEquals(0,BigDecimal.valueOf(7).compareTo(engine.contributionTotal(SUBJECT,ContributionType.TRADE,"resource.agricultural")));
            assertEquals(1,engine.contributionRevisions(balance.entry().id(),0,100).size());
            assertEquals(originals.get("contributions"),rows(store,"contributions"));
        }
    }
    @Test void freshStoreUsesRenamedDomainArchivesThenActivatesNewRefund()throws Exception {
        source.add(receipt(1,null,COMPLETED));
        try(var store=new SqliteStore(directory.resolve("fresh.db"))){
            var engine=engine(store);var journal=new CommittedHistoryJournal(store);var coordinator=new TradeHistoryCoordinator(engine,key->source,journal);
            assertEquals(1,coordinator.poll(List.of()));assertTrue(engine.committedSourceCursor(NS).isEmpty());assertEquals(0,engine.pendingTriggerCount());
            assertEquals(4,engine.contributions(null,null,0,100).size());
            source.add(receipt(2,COMPLETED,REFUNDED));assertEquals(1,coordinator.poll(List.of()));assertTrue(engine.pendingTriggerCount()>0);
            assertEquals(ContributionRevision.Status.REFUNDED,engine.contributionBalances(SUBJECT,null,0,10).getFirst().status());
            assertEquals(0,coordinator.poll(List.of()));assertEquals(2,engine.history(null,null,0,100).size());
        }
    }
    @Test void pendingUpstreamReceiptSurvivesRestartAndDoesNotReadTheLegacyStream()throws Exception {
        source.add(receipt(1,null,COMPLETED));Path db=directory.resolve("pending.db");
        try(var store=new SqliteStore(db)){engine(store);new StarCoreTradeHistory(()->source,new CommittedHistoryJournal(store)).scan();}
        try(var store=new SqliteStore(db)){
            var engine=engine(store);var journal=new CommittedHistoryJournal(store);var coordinator=new TradeHistoryCoordinator(engine,key->source,journal);
            assertEquals(1,coordinator.poll(List.of()));assertEquals(0,coordinator.poll(List.of()));
            assertTrue(engine.committedSourceCursor(NS).isEmpty());assertTrue(journal.pending(NS,128).isEmpty());assertEquals(4,engine.contributions(null,null,0,100).size());
        }
    }
    @Test void conflictingLedgerIsIsolatedWithoutDeletingEitherRepresentation()throws Exception {
        source.add(receipt(1,null,COMPLETED));
        try(var store=new SqliteStore(directory.resolve("conflict.db"))){
            var engine=engine(store);legacy(engine);var journal=new CommittedHistoryJournal(store);new StarCoreTradeHistory(()->source,journal).scan();
            var original=rows(store,"contributions");var coordinator=new TradeHistoryCoordinator(engine,key->source,journal);
            assertThrows(IllegalStateException.class,()->coordinator.poll(List.of()));assertEquals(original,rows(store,"contributions"));
            assertEquals(1,journal.pending(NS,128).size());assertEquals(1,engine.committedSourceCursor(NS).orElseThrow().sequence());
        }
    }
    @Test void competingLegacyWriterAfterSelectionCannotCreateSecondReader()throws Exception {
        source.add(receipt(1,null,COMPLETED));
        try(var store=new SqliteStore(directory.resolve("competing.db"))){
            var engine=engine(store);var journal=new CommittedHistoryJournal(store);var coordinator=new TradeHistoryCoordinator(engine,key->source,journal);
            legacy(engine);assertThrows(IllegalStateException.class,()->coordinator.poll(List.of()));assertTrue(journal.cursor(NS).isEmpty());
        }
    }
    @Test void silentRollbackOrChangedFullReceiptCannotLookLikeIdleHead()throws Exception {
        source.add(receipt(1,null,COMPLETED));
        try(var store=new SqliteStore(directory.resolve("anchor.db"))){
            var engine=engine(store);var journal=new CommittedHistoryJournal(store);var coordinator=new TradeHistoryCoordinator(engine,key->source,journal);coordinator.poll(List.of());
            source.current.invalid=List.of();assertThrows(IllegalStateException.class,()->coordinator.poll(List.of()));source.current.invalid=null;
            source.current.changes.set(0,nativeReceipt(receipt(1,null,trade(COMPLETED,RECORD,SELLER,BUYER,SELLER_NATION,BUYER_NATION,"agricultural",7,"different note"))));
            assertThrows(IllegalStateException.class,()->coordinator.poll(List.of()));assertEquals(1,journal.cursor(NS).orElseThrow().sequence());assertEquals(1,engine.history(null,null,0,100).size());
        }
    }
    @Test void fullPageIncludingAnchorDoesNotPrematurelyEnableStoryCatchup()throws Exception {
        for(int i=1;i<=257;i++)source.add(receipt(i,null,trade(COMPLETED,new UUID(0,i),SELLER,BUYER,SELLER_NATION,BUYER_NATION,"agricultural",1,null)));
        try(var store=new SqliteStore(directory.resolve("pages.db"))){
            var engine=engine(store);var journal=new CommittedHistoryJournal(store);var coordinator=new TradeHistoryCoordinator(engine,key->source,journal);
            assertEquals(128,coordinator.poll(List.of()));assertFalse(journal.cursor(NS).orElseThrow().live());
            assertEquals(127,coordinator.poll(List.of()));assertFalse(journal.cursor(NS).orElseThrow().live());
            assertEquals(2,coordinator.poll(List.of()));assertTrue(journal.cursor(NS).orElseThrow().live());assertEquals(0,engine.pendingTriggerCount());
            assertEquals(0,coordinator.poll(List.of()));
        }
    }
    @Test void unavailableSourceKeepsItsDurablePendingReceiptUntilRecovery()throws Exception {
        source.add(receipt(1,null,COMPLETED));AtomicReference<Object> provider=new AtomicReference<>(source);
        try(var store=new SqliteStore(directory.resolve("missing.db"))){
            var engine=engine(store);var journal=new CommittedHistoryJournal(store);new StarCoreTradeHistory(provider::get,journal).scan();
            var coordinator=new TradeHistoryCoordinator(engine,key->provider.get(),journal);provider.set(null);
            assertThrows(IllegalStateException.class,()->coordinator.poll(List.of()));assertEquals(1,journal.pending(NS,128).size());
            provider.set(source);assertEquals(1,coordinator.poll(List.of()));
        }
    }
    @Test void legacyRevisionFailureRollsBackCursorStateAndHistoryTogether()throws Exception {
        source.add(receipt(1,null,COMPLETED));
        try(var store=new SqliteStore(directory.resolve("rollback.db"))){
            var engine=engine(store);legacy(engine);var journal=new CommittedHistoryJournal(store);var coordinator=new TradeHistoryCoordinator(engine,key->source,journal);
            var original=rows(store,"native_trade_states");source.add(receipt(2,COMPLETED,REFUNDED));
            sql(store,"CREATE TRIGGER reject_revision BEFORE INSERT ON contribution_revisions BEGIN SELECT RAISE(ABORT,'legacy revision failure'); END");
            assertThrows(IllegalStateException.class,()->coordinator.poll(List.of()));assertEquals(original,rows(store,"native_trade_states"));assertEquals(1,engine.committedSourceCursor(NS).orElseThrow().sequence());
            sql(store,"DROP TRIGGER reject_revision");assertEquals(1,coordinator.poll(List.of()));assertEquals(0,coordinator.poll(List.of()));
            assertEquals(BigDecimal.ZERO,engine.contributionBalances(SUBJECT,null,0,10).getFirst().netAmount());
        }
    }
}
