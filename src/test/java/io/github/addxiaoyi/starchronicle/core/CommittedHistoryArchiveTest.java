package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition.*;
import io.github.addxiaoyi.starchronicle.memory.MemoryRecipeCatalog;
import io.github.addxiaoyi.starchronicle.npc.*;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CommittedHistoryArchiveTest {
    @TempDir Path directory;
    static final UUID PLAYER = new UUID(0, 21);
    static final PlayerContext CONTEXT = new PlayerContext(PLAYER,"nation",Map.of(),Set.of());
    static final Instant TIME = Instant.parse("2026-11-26T00:00:00.123456789Z");
    WorldEvent event(String id) { return new WorldEvent(id,"TECHNOLOGY_BREAKTHROUGH",TIME,Set.of(PLAYER),Set.of("nation"),Map.of("time_basis","COMMITTED","region.id","archive"),"StarCore","native commit="+id); }
    StoryDefinition story() {
        return new StoryDefinition("technology-test","1","Technology",Scope.PLAYER,List.of("TECHNOLOGY_BREAKTHROUGH"),null,"done",0,
                Map.of("done",new Node("Done",null,null,1,Map.of(),List.of(new Effect("honor","honor","player","3",null)),true)));
    }
    MemoryRecipeCatalog recipes() {
        return new MemoryRecipeCatalog(List.of(new MemoryRecipeCatalog.Recipe("tech","1",true,List.of("StarCore"),List.of("TECHNOLOGY_BREAKTHROUGH"),Map.of("region.id","archive"),"archive","Technology","Committed technology")));
    }
    NpcLedger npcs(SqliteStore store) {
        var profile = new NpcCatalog.Profile("Scholar","Studies technology","archive","scholar",List.of(),List.of(),List.of(),
                List.of(new NpcCatalog.Reaction("tech",List.of("TECHNOLOGY_BREAKTHROUGH"),null,2,1,0,1,null)));
        return new NpcLedger(store,NpcCatalog.of(Map.of("scholar",profile)));
    }
    @Test void initialArchiveNeverReplaysNpcMemoryStoryOrRewardsEvenAfterRestartAndOrdinaryAccept() {
        Path database = directory.resolve("archive.db");
        for (int boot=0;boot<2;boot++) try (var store = new SqliteStore(database)) {
            var npcs = npcs(store); var engine = new RuleEngine(store,List.of(story()),Clock.systemUTC(),npcs,NationalVoteAuthority.denying(),recipes());
            assertEquals(boot>0, engine.archiveCommittedHistory(event("old")).duplicate());
            assertTrue(engine.accept(event("old"),List.of(CONTEXT)).duplicate()); engine.resumeTriggers(List.of(CONTEXT));
            assertEquals(List.of(event("old")),engine.history(null,null,0,10));
            assertTrue(engine.instances(PLAYER).isEmpty()); assertTrue(engine.facts(PLAYER).isEmpty());
            assertTrue(engine.memoryEntries(null,0,10).isEmpty()); assertEquals(0,npcs.view(PLAYER,"scholar",CONTEXT,engine.worldFacts()).growth());
            if (boot==1) {
                engine.accept(event("new"),List.of(CONTEXT));
                assertEquals("3",engine.facts(PLAYER).get("player.honor")); assertEquals(1,engine.memoryEntries(null,0,10).size());
                assertEquals(1,npcs.view(PLAYER,"scholar",CONTEXT,engine.worldFacts()).growth());
            }
        }
    }
    @Test void archivalCollisionAndDatabaseFailureCannotLeavePartialHistory() {
        try (var store = new SqliteStore(directory.resolve("failure.db"))) {
            var engine = new RuleEngine(store,List.of(),Clock.systemUTC());
            store.transaction(c -> { try(var s=c.createStatement()){s.execute("CREATE TRIGGER fail_archive BEFORE INSERT ON event_causes BEGIN SELECT RAISE(ABORT,'fail archive'); END");} return null; });
            assertThrows(IllegalStateException.class,()->engine.archiveCommittedHistory(event("old"))); assertTrue(engine.history(null,null,0,10).isEmpty());
            store.transaction(c -> { try(var s=c.createStatement()){s.execute("DROP TRIGGER fail_archive");} return null; });
            engine.archiveCommittedHistory(event("old"));
            var changed = new WorldEvent("old","TECHNOLOGY_REVOKED",TIME,Set.of(),Set.of("nation"),Map.of(),"StarCore","different evidence");
            assertThrows(IllegalArgumentException.class,()->engine.archiveCommittedHistory(changed)); assertEquals(List.of(event("old")),engine.history(null,null,0,10));
        }
    }
}
