package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.api.contribution.*;
import io.github.addxiaoyi.starchronicle.history.*;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CommittedSourceFlowTest {
    @TempDir Path temp;
    static final String DOMAIN=UUID.randomUUID().toString(),SOURCE="native.trade",NATION=UUID.randomUUID().toString();
    static final UUID ACTOR=UUID.randomUUID();
    static final Instant NOW=Instant.parse("2026-11-25T12:00:00Z");
    SqliteStore store;RuleEngine engine;ContributionLedger ledger;
    @BeforeEach void open(){store=new SqliteStore(temp.resolve("source.db"));engine=new RuleEngine(store,List.of(),Clock.fixed(NOW.plusSeconds(60),ZoneOffset.UTC));ledger=new ContributionLedger(store,Clock.fixed(NOW.plusSeconds(60),ZoneOffset.UTC));}
    @AfterEach void close(){engine.close();}
    CommittedSourcePage.Entry entry(long seq,String record,String status,boolean baseline) {
        var event=new WorldEvent("trade:event:"+seq,"TRADE_"+status,NOW.plusSeconds(seq),Set.of(ACTOR),Set.of(NATION),Map.of("time_basis","COMMITTED_SOURCE"),"native","native commit "+seq);
        List<ContributionRequest> projections=baseline||!status.equals("COMPLETED")?List.of():List.of(new ContributionRequest("trade:record:"+record,ContributionType.TRADE,"player:"+ACTOR,event.id(),ACTOR,NATION,BigDecimal.TEN,"resource.industrial",event.occurredAt(),"trade="+record));
        return new CommittedSourcePage.Entry(seq,"operation:"+seq,event,baseline,new CommittedSourcePage.Trade(record,RuleEngine.stable(record),status,projections));
    }
    CommittedSourcePage page(long after,CommittedSourcePage.Entry...entries){return new CommittedSourcePage(SOURCE,DOMAIN,after,List.of(entries));}
    long head(){return engine.committedSourceCursor(SOURCE).orElseThrow().sequence();}
    BigDecimal total(){return ledger.total("player:"+ACTOR,ContributionType.TRADE,"resource.industrial");}
    @Test void restartReplayRefundAndRestoredCompletionNeverDuplicateContribution() {
        var completed=entry(1,"one","COMPLETED",false);
        assertEquals(1,engine.acceptCommittedSource(page(0,completed),List.of()));
        engine.close();open();
        assertEquals(0,engine.acceptCommittedSource(page(0,completed),List.of()));
        assertEquals(0,BigDecimal.TEN.compareTo(total()));
        var refunded=entry(2,"one","REFUNDED",false);
        engine.acceptCommittedSource(page(0,completed,refunded),List.of());
        assertEquals(BigDecimal.ZERO,total());
        assertEquals("REFUNDED",ledger.list(null,null,0,10).getFirst().sourceStatus());
        engine.acceptCommittedSource(page(2,entry(3,"one","COMPLETED",false)),List.of());
        assertEquals(1,ledger.list(null,null,0,10).size());
        assertEquals(0,BigDecimal.TEN.compareTo(total()));
        assertEquals(3,engine.history(null,null,0,10).size());assertEquals(3,head());
    }
    @Test void baselineNeverBecomesANewAchievementEvenAfterStatusCycles() {
        engine.acceptCommittedSource(page(0,entry(1,"old","DISPUTED",true)),List.of());
        assertTrue(engine.history(null,null,0,10).isEmpty());assertEquals(1,head());
        engine.acceptCommittedSource(page(1,entry(2,"old","COMPLETED",false)),List.of());
        assertEquals(1,engine.history(null,null,0,10).size());assertTrue(ledger.list(null,null,0,10).isEmpty());
    }
    @Test void failureAfterHistoryWriteRollsBackWholePageIncludingCursorAndContributions() {
        store.transaction(c->{try(var s=c.createStatement()){s.execute("CREATE TRIGGER reject_projection BEFORE INSERT ON native_trade_contributions BEGIN SELECT RAISE(ABORT,'injected projection failure'); END");}return null;});
        assertThrows(IllegalStateException.class,()->engine.acceptCommittedSource(page(0,entry(1,"one","DISPUTED",false),entry(2,"one","COMPLETED",false)),List.of()));
        assertTrue(engine.committedSourceCursor(SOURCE).isEmpty());assertTrue(engine.history(null,null,0,10).isEmpty());assertTrue(ledger.list(null,null,0,10).isEmpty());
        store.transaction(c->{try(var s=c.createStatement()){s.execute("DROP TRIGGER reject_projection");}return null;});
        assertEquals(2,engine.acceptCommittedSource(page(0,entry(1,"one","DISPUTED",false),entry(2,"one","COMPLETED",false)),List.of()));assertEquals(2,head());
    }
    @Test void conflictingReplayDomainAndSequenceAreRejectedWithoutAdvancing() {
        var original=entry(1,"one","COMPLETED",false);engine.acceptCommittedSource(page(0,original),List.of());
        assertThrows(IllegalStateException.class,()->engine.acceptCommittedSource(page(0,entry(1,"one","REFUNDED",false)),List.of()));
        assertThrows(IllegalStateException.class,()->engine.acceptCommittedSource(new CommittedSourcePage(SOURCE,UUID.randomUUID().toString(),1,List.of()),List.of()));
        assertThrows(IllegalStateException.class,()->engine.acceptCommittedSource(page(2,entry(3,"three","COMPLETED",false)),List.of()));
        assertThrows(IllegalArgumentException.class,()->page(0,original,entry(3,"three","COMPLETED",false)));
        assertEquals(1,head());assertEquals(1,ledger.list(null,null,0,10).size());
    }
    @Test void changedTermsOrDuplicateOperationRollsBackTheEventAndCursor() {
        engine.acceptCommittedSource(page(0,entry(1,"one","COMPLETED",false)),List.of());
        var refunded=entry(2,"one","REFUNDED",false);
        var changed=new CommittedSourcePage.Entry(2,refunded.operationId(),refunded.event(),false,new CommittedSourcePage.Trade("one",RuleEngine.stable("changed"),"REFUNDED",List.of()));
        assertThrows(IllegalStateException.class,()->engine.acceptCommittedSource(page(1,changed),List.of()));
        assertThrows(IllegalStateException.class,()->engine.acceptCommittedSource(page(1,new CommittedSourcePage.Entry(2,"operation:1",refunded.event(),false,refunded.trade())),List.of()));
        assertEquals(1,head());assertEquals(1,engine.history(null,null,0,10).size());
    }
    @Test void receiptRetainsActorlessNationalHistoryWithoutInventingContributions() {
        var event=new WorldEvent("tech:one","TECHNOLOGY_REVOKED",NOW,Set.of(),Set.of(NATION),Map.of(),"native","receipt with no researcher");
        engine.acceptCommittedSource(page(0,new CommittedSourcePage.Entry(1,"operation:1",event,false,null)),List.of());
        assertEquals(Set.of(),engine.history(NATION,null,0,10).getFirst().participants());assertEquals(BigDecimal.ZERO,total());
    }
    @Test void sameNationTradeCannotForgeTheContributionActor() {
        var original=entry(1,"one","COMPLETED",false);var request=original.trade().contributions().getFirst();
        var forged=new ContributionRequest(request.operationId(),request.type(),"player:"+UUID.randomUUID(),request.sourceEventId(),request.actorId(),NATION,request.amount(),request.unit(),request.committedAt(),request.evidence());
        var entry=new CommittedSourcePage.Entry(1,original.operationId(),original.event(),false,new CommittedSourcePage.Trade("one",original.trade().termsHash(),"COMPLETED",List.of(forged)));
        assertThrows(IllegalArgumentException.class,()->engine.acceptCommittedSource(page(0,entry),List.of()));assertTrue(engine.committedSourceCursor(SOURCE).isEmpty());
    }
}
