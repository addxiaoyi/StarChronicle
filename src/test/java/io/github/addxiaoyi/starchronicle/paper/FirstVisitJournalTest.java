package io.github.addxiaoyi.starchronicle.paper;

import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import io.github.addxiaoyi.starchronicle.core.RuleEngine;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import io.github.addxiaoyi.starchronicle.history.FirstVisitJournal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FirstVisitJournalTest {
    @TempDir Path directory;
    private final UUID player=UUID.fromString("08ffd450-5001-3023-a480-057fef707037");
    private final ExplorationRegion region=new ExplorationRegion("ancient-road",UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),-10,50,-10,10,90,10,null);
    private Path database(){return directory.resolve("chronicle.db");}
    private WorldEvent event(Instant time,int x){return ConfiguredRegionFirstVisit.event(player,"nation-a",region,x,65,2,time);}

    @Test void firstObservedPayloadIsImmutableAndRedeliveredAcrossCrashBeforeAck() {
        WorldEvent original=event(Instant.parse("2026-11-20T00:00:00Z"),1);
        try(var store=new SqliteStore(database())) {
            var journal=new FirstVisitJournal(store);journal.registerRegions(java.util.Map.of(region.id(),region.scope()));
            assertEquals(original,journal.observe(original).orElseThrow());
            assertEquals(original,journal.observe(event(original.occurredAt().plusSeconds(30),9)).orElseThrow());
            assertEquals(List.of(original),journal.pending(10));
            var engine=new RuleEngine(store,List.of(),Clock.systemUTC());
            assertFalse(engine.accept(original,List.of()).duplicate());
            // A crash after engine commit and before source acknowledgement deliberately replays the same bytes.
        }
        try(var store=new SqliteStore(database())) {
            var journal=new FirstVisitJournal(store);var engine=new RuleEngine(store,List.of(),Clock.systemUTC());
            assertEquals(List.of(original),journal.pending(10));
            assertTrue(engine.accept(journal.pending(10).getFirst(),List.of()).duplicate());
            assertTrue(journal.acknowledge(original.id()));assertTrue(journal.pending(10).isEmpty());
            assertTrue(journal.observe(event(original.occurredAt().plusSeconds(60),4)).isEmpty());
            assertEquals(1,engine.history(null,null,0,10).size());
        }
    }

    @Test void stableRegionIdCannotBeReusedForDifferentWorldOrBounds() {
        try(var store=new SqliteStore(database())) {
            var journal=new FirstVisitJournal(store);journal.registerRegions(java.util.Map.of(region.id(),region.scope()));
            var moved=new ExplorationRegion("ancient-road",region.worldId(),-9,50,-10,10,90,10,null);
            assertNotEquals(region.scope(),moved.scope());
            assertThrows(IllegalStateException.class,()->journal.registerRegions(java.util.Map.of(moved.id(),moved.scope())));
        }
    }

    @Test void committedDiscoveryTriggersRealConfiguredStoryAndReplayDoesNotRewardTwice() throws Exception {
        Path stories=directory.resolve("stories");java.nio.file.Files.createDirectories(stories);
        try(var resource=getClass().getResourceAsStream("/stories/silk-road-scholar.yml")) {assertNotNull(resource);java.nio.file.Files.copy(resource,stories.resolve("silk-road-scholar.yml"));}
        var definitions=io.github.addxiaoyi.starchronicle.config.ConfigLoader.load(stories,id->true,id->true);
        var actor=new io.github.addxiaoyi.starchronicle.api.PlayerContext(player,"nation-a",java.util.Map.of("player.role","explorer","tech.stage","2"),java.util.Set.of());
        WorldEvent original=event(Instant.parse("2026-11-20T00:00:00Z"),1);
        try(var store=new SqliteStore(database())) {
            var journal=new FirstVisitJournal(store);journal.registerRegions(java.util.Map.of(region.id(),region.scope()));journal.observe(original);
            var engine=new RuleEngine(store,definitions,Clock.systemUTC());
            String instance=engine.accept(journal.pending(10).getFirst(),List.of(actor)).instanceIds().getFirst();
            engine.choose(instance,player,"discovery","map",actor);engine.choose(instance,player,"mapping","share",actor);
            assertEquals("12",engine.facts(player).get("player.honor"));
        }
        try(var store=new SqliteStore(database())) {
            var journal=new FirstVisitJournal(store);var engine=new RuleEngine(store,definitions,Clock.systemUTC());
            assertTrue(engine.accept(journal.pending(10).getFirst(),List.of(actor)).duplicate());journal.acknowledge(original.id());
            assertEquals("12",engine.facts(player).get("player.honor"));assertTrue(journal.pending(10).isEmpty());
            assertEquals(1,engine.history(null,player,0,100).stream().filter(item->item.type().equals("CARAVAN_ROUTE_SURVEYED")).count());
        }
    }

    @Test void invalidParticipantShapeAndRegionCoordinatesAreRejected() {
        var valid=event(Instant.parse("2026-11-20T00:00:00Z"),1);
        var outsider=ConfiguredRegionFirstVisit.event(player,"",region,10,90,10,valid.occurredAt());
        assertTrue(outsider.nations().isEmpty());
        assertThrows(IllegalArgumentException.class,()->ConfiguredRegionFirstVisit.event(player,"nation-a",region,11,65,0,valid.occurredAt()));
        assertThrows(IllegalArgumentException.class,()->new ExplorationRegion("Bad Name",region.worldId(),0,0,0,1,1,1,null));
        assertThrows(IllegalArgumentException.class,()->new ExplorationRegion("reversed",region.worldId(),2,0,0,1,1,1,null));
        try(var store=new SqliteStore(database())) {
            var journal=new FirstVisitJournal(store);assertThrows(IllegalStateException.class,()->journal.observe(valid));
            journal.registerRegions(java.util.Map.of(region.id(),region.scope()));
            var invalid=new WorldEvent(valid.id(),valid.type(),valid.occurredAt(),java.util.Set.of(player,UUID.randomUUID()),valid.nations(),valid.facts(),valid.source(),valid.evidence());
            assertThrows(IllegalArgumentException.class,()->journal.observe(invalid));assertTrue(journal.pending(10).isEmpty());
        }
    }
}
