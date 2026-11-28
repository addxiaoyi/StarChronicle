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

class ProductionContributionFlowTest {
    @TempDir Path temp;
    final String domain=UUID.randomUUID().toString(),nation=UUID.randomUUID().toString();final UUID actor=UUID.randomUUID();
    final Instant at=Instant.parse("2026-11-25T12:00:00Z");
    SqliteStore store;RuleEngine engine;ContributionLedger ledger;
    @BeforeEach void open(){store=new SqliteStore(temp.resolve("production.db"));engine=new RuleEngine(store,List.of(),Clock.fixed(at,ZoneOffset.UTC));ledger=new ContributionLedger(store,Clock.fixed(at,ZoneOffset.UTC));}
    @AfterEach void close(){engine.close();}
    CommittedSourcePage page(BigDecimal amount) {
        var event=new WorldEvent("production:event:1","CIVILIZATION_PRODUCTION_COMMITTED",at,Set.of(actor),Set.of(nation),Map.of("production.output.industrial",amount.toPlainString()),"native","durable national conversion");
        var entries=List.of("player:"+actor,"nation:"+nation).stream().map(subject->new ContributionRequest("production:one:industrial",ContributionType.CIVILIZATION,subject,event.id(),actor,nation,amount,"resource.industrial",at,"native committed output")).toList();
        return new CommittedSourcePage("native.production",domain,0,List.of(new CommittedSourcePage.Entry(1,"operation:one",event,false,null,entries)));
    }
    @Test void replayAndRestartKeepOneHistoryAndBothContributionSubjects() {
        assertEquals(1,engine.acceptCommittedSource(page(BigDecimal.TEN),List.of()));engine.close();open();
        assertEquals(0,engine.acceptCommittedSource(page(BigDecimal.TEN),List.of()));
        assertEquals(1,engine.history(nation,null,0,10).size());assertEquals(2,ledger.list(null,null,0,10).size());
        assertEquals(0,BigDecimal.TEN.compareTo(ledger.total("player:"+actor,ContributionType.CIVILIZATION,"resource.industrial")));
        assertEquals(0,BigDecimal.TEN.compareTo(ledger.total("nation:"+nation,ContributionType.CIVILIZATION,"resource.industrial")));
    }
    @Test void ledgerFailureRollsBackHistoryCursorAndEveryProjection() {
        store.transaction(c->{try(var s=c.createStatement()){s.execute("CREATE TRIGGER reject_production BEFORE INSERT ON contributions BEGIN SELECT RAISE(ABORT,'injected failure'); END");}return null;});
        assertThrows(IllegalStateException.class,()->engine.acceptCommittedSource(page(BigDecimal.TEN),List.of()));
        assertTrue(engine.committedSourceCursor("native.production").isEmpty());assertTrue(engine.history(null,null,0,10).isEmpty());assertTrue(ledger.list(null,null,0,10).isEmpty());
        store.transaction(c->{try(var s=c.createStatement()){s.execute("DROP TRIGGER reject_production");}return null;});
        assertEquals(1,engine.acceptCommittedSource(page(BigDecimal.TEN),List.of()));
    }
    @Test void changedProjectionAndForgedActorCannotAdvanceCursor() {
        var original=page(BigDecimal.TEN);engine.acceptCommittedSource(original,List.of());
        assertThrows(IllegalStateException.class,()->engine.acceptCommittedSource(page(BigDecimal.ONE),List.of()));
        var e=original.entries().getFirst();var r=e.contributions().getFirst();var forged=new ContributionRequest(r.operationId(),r.type(),r.subjectId(),r.sourceEventId(),UUID.randomUUID(),r.nationId(),r.amount(),r.unit(),r.committedAt(),r.evidence());
        var bad=new CommittedSourcePage(original.source(),domain,0,List.of(new CommittedSourcePage.Entry(1,e.operationId(),e.event(),false,null,List.of(forged))));
        assertThrows(IllegalArgumentException.class,()->engine.acceptCommittedSource(bad,List.of()));assertEquals(1,engine.committedSourceCursor("native.production").orElseThrow().sequence());
    }
    @Test void oldSourceHashesRemainValidWithoutAnEmptyNewField() {
        var event=new WorldEvent("old:1","TECHNOLOGY_UNLOCKED",at,Set.of(),Set.of(nation),Map.of(),"native","old receipt");
        var entry=new CommittedSourcePage.Entry(1,"old-operation",event,false,null);
        var p=new CommittedSourcePage("old.source",domain,0,List.of(entry));engine.acceptCommittedSource(p,List.of());
        var canonical=new TreeMap<String,Object>();canonical.put("sequence",1L);canonical.put("operation","old-operation");canonical.put("baseline",false);
        canonical.put("event",List.of(event.id(),event.type(),at.toString(),new TreeSet<>(event.participants()),new TreeSet<>(event.nations()),new TreeMap<>(event.facts()),event.source(),event.evidence()));canonical.put("trade",null);
        String oldHash=RuleEngine.stable(SqliteStore.json(canonical));
        store.transaction(c->{try(var s=c.prepareStatement("UPDATE committed_source_receipts SET hash=? WHERE source='old.source'")){s.setString(1,oldHash);s.executeUpdate();}return null;});
        assertEquals(0,engine.acceptCommittedSource(p,List.of()));
    }
}
