package io.github.addxiaoyi.starchronicle.memory;

import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import io.github.addxiaoyi.starchronicle.core.RuleEngine;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MemoryLedgerTest {
    @TempDir Path temp;
    private static final WorldEvent EVENT=new WorldEvent("battle:one","BATTLE_ENDED",Instant.EPOCH,Set.of(),Set.of("nation"),Map.of(),"TEST","Committed battle evidence");
    private static MemorySite site(String event){return new MemorySite("battle-library",UUID.nameUUIDFromBytes(new byte[]{1}),17,70,-1,"战役纪念馆",event,"siege-veteran",true);}
    @Test void realSourceRequiredAndDuplicateSiteCannotMoveOldEntities() {
        try(var store=new SqliteStore(temp.resolve("world.db"))){
            var ledger=new MemoryLedger(store);
            assertThrows(IllegalStateException.class,()->ledger.create(site("invented")));
            var engine=new RuleEngine(store,List.of(),Clock.systemUTC());engine.accept(EVENT,List.of());
            assertEquals(site(EVENT.id()),ledger.create(site(EVENT.id())));
            assertThrows(IllegalStateException.class,()->ledger.create(site(EVENT.id())));
            assertEquals(EVENT,ledger.source(EVENT.id()));
        }
    }
    @Test void placementAndDeletionTombstoneSurviveRestart() {
        Path db=temp.resolve("world.db");
        try(var store=new SqliteStore(db)){new RuleEngine(store,List.of(),Clock.systemUTC()).accept(EVENT,List.of());new MemoryLedger(store).create(site(EVENT.id()));}
        try(var store=new SqliteStore(db)){var ledger=new MemoryLedger(store);assertEquals(List.of(site(EVENT.id())),ledger.sites());assertFalse(ledger.disable("battle-library").enabled());}
        try(var store=new SqliteStore(db)){var restored=new MemoryLedger(store).sites();assertEquals(1,restored.size());assertFalse(restored.getFirst().enabled());assertEquals(EVENT.id(),restored.getFirst().eventId());}
    }
    @Test void coordinatesAndIdentifiersAreBounded() {
        assertEquals(1,site("a").chunkX());assertEquals(-1,site("a").chunkZ());
        assertThrows(IllegalArgumentException.class,()->new MemorySite("../x",UUID.randomUUID(),0,0,0,"x","e","",true));
        assertThrows(IllegalArgumentException.class,()->new MemorySite("x",UUID.randomUUID(),Integer.MIN_VALUE,0,0,"x","e","",true));
    }
    @Test void bookPreservesFullEvidenceAndSourceIdsAsLiteralText() {
        WorldEvent event=new WorldEvent("<click:run_command:'/op'>","BATTLE_ENDED",Instant.EPOCH,Set.of(),Set.of("nation"),Map.of("time_basis","OBSERVED"),"TEST","汉字🙂".repeat(100));
        List<String> pages=HistoryBook.pages(List.of(event));String joined=String.join("",pages).replace("\n","");
        assertTrue(joined.contains(event.id()));assertTrue(joined.contains(event.evidence()));assertTrue(joined.contains("OBSERVED"));assertTrue(joined.contains("TEST"));
        assertTrue(pages.size()<=100);assertTrue(pages.stream().allMatch(p->p.lines().count()<=11));
        assertThrows(IllegalArgumentException.class,()->HistoryBook.pages(Collections.nCopies(6,event)));
    }
    @Test void emptyLibraryStillProducesReadableBook(){assertEquals(2,HistoryBook.pages(List.of()).size());}
}
