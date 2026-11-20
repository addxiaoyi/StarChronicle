package io.github.addxiaoyi.starchronicle.memory;

import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.config.ConfigLoader;
import io.github.addxiaoyi.starchronicle.core.*;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Parses and plays both branches of the actual delivered files, using a canonical first-visit fixture. */
class MemoryRecipeExampleTest {
    @TempDir Path directory;
    @ParameterizedTest @ValueSource(strings={"testify","read"})
    void completeOptionalLibraryStoryUsesRealMemorySignalAndStableEnding(String choice)throws Exception {
        var disabled=MemoryRecipeCatalog.load(Path.of("examples/memory-recipes/memory-recipes.yml"));assertEquals(2,disabled.recipes().size());assertTrue(disabled.recipes().stream().noneMatch(MemoryRecipeCatalog.Recipe::enabled));
        var definition=disabled.recipes().getFirst();var enabled=new MemoryRecipeCatalog.Recipe(definition.id(),definition.version(),true,definition.sources(),definition.eventTypes(),definition.requiredFacts(),definition.regionId(),definition.title(),definition.text());
        Path stories=directory.resolve("stories");Files.createDirectories(stories);Files.copy(Path.of("examples/memory-recipes/caravan-library.yml"),stories.resolve("caravan-library.yml"));var rules=ConfigLoader.load(stories,id->false,id->false);
        UUID actor=new UUID(0,61);PlayerContext context=new PlayerContext(actor,"",Map.of(),Set.of());Instant observed=Instant.parse("2026-11-20T09:00:00Z");
        WorldEvent arrival=new WorldEvent("canonical-first-visit","EXPLORATION_DISCOVERY",observed,Set.of(actor),Set.of(),Map.of("region.id","old-caravan-road","discovery.kind","FIRST_VISIT","time_basis","OBSERVED"),"StarChronicle.ConfiguredRegionFirstVisit","Canonical first-visit test fixture; actual physical ingress is separately verified");
        Path db=directory.resolve("world.db");String instance;
        try(var store=new SqliteStore(db)) {
            var engine=new RuleEngine(store,rules,Clock.fixed(observed.plusSeconds(1),ZoneOffset.UTC),null,NationalVoteAuthority.denying(),new MemoryRecipeCatalog(List.of(enabled)));engine.accept(arrival,List.of());engine.resumeTriggers(List.of(context));instance=engine.instances(actor).getFirst().id();engine.choose(instance,actor,"archive",choice,context);
            assertTrue(engine.instances(actor).getFirst().completed());assertEquals(choice.equals("testify")?"3":"0",engine.facts(actor).getOrDefault("player.honor","0"));assertEquals(1,engine.memoryEntries(null,0,10).size());
        }
        try(var store=new SqliteStore(db)) {
            var engine=new RuleEngine(store,rules,Clock.fixed(observed.plusSeconds(50),ZoneOffset.UTC),null,NationalVoteAuthority.denying(),new MemoryRecipeCatalog(List.of(enabled)));assertTrue(engine.accept(arrival,List.of(context)).duplicate());assertEquals(instance,engine.instances(actor).getFirst().id());assertThrows(IllegalStateException.class,()->engine.choose(instance,actor,"archive",choice,context));
            String ending=choice.equals("testify")?"CARAVAN_ARCHIVE_TESTIMONY":"CARAVAN_ARCHIVE_READ";assertEquals(1,engine.history(null,actor,0,100).stream().filter(event->event.type().equals(ending)).count());assertEquals(choice.equals("testify")?"3":"0",engine.facts(actor).getOrDefault("player.honor","0"));
        }
    }
}
