package io.github.addxiaoyi.starchronicle.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Content regression tests: packaged examples must remain playable without fictional Core IDs. */
class SampleStoryContractTest {
    private static final List<String> SAMPLES = List.of("last-heir.yml", "siege-embers.yml", "silk-road-scholar.yml");
    @TempDir Path directory;

    private List<StoryDefinition> loadSamples() throws IOException {
        for (String name : SAMPLES) copyResource(name, name);
        return ConfigLoader.load(directory, ignored -> false, ignored -> false);
    }

    private void copyResource(String name, String destination) throws IOException {
        try (var stream = getClass().getResourceAsStream("/stories/" + name)) {
            assertNotNull(stream, "Missing packaged story " + name);
            Files.copy(stream, directory.resolve(destination));
        }
    }

    @Test void allThreePackagedStoriesPassTheProductionValidator() throws IOException {
        var stories = loadSamples();
        assertEquals(Set.of("last-heir", "siege-embers", "silk-road-scholar"),
                stories.stream().map(StoryDefinition::id).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of(StoryDefinition.Scope.PLAYER, StoryDefinition.Scope.NATION, StoryDefinition.Scope.WORLD),
                stories.stream().map(StoryDefinition::scope).collect(java.util.stream.Collectors.toSet()));
        for (var story : stories) {
            assertTrue(story.nodes().values().stream().filter(StoryDefinition.Node::terminal).count() >= 2,
                    story.id() + " must have multiple complete outcomes");
        }
    }

    @Test void defaultsContainOnlyLocalEffectsWithUniqueStableIds() throws IOException {
        for (var story : loadSamples()) {
            var ids = new HashSet<String>();
            for (var node : story.nodes().values()) {
                assertNull(node.script(), "Default content cannot require a fictional Core script");
                assertNull(node.quest(), "Default content cannot require a fictional Core quest");
                var effects = new ArrayList<>(node.effects());
                node.choices().values().forEach(choice -> effects.addAll(choice.effects()));
                for (var effect : effects) {
                    assertTrue(Set.of("honor", "title", "reputation", "memory", "history").contains(effect.type()));
                    assertTrue(ids.add(effect.id()), "Repeated stable effect ID in " + story.id() + ": " + effect.id());
                }
            }
        }
    }

    @Test void nationalEpicRequiresDistinctParticipantsAtEveryDecision() throws IOException {
        var epic = loadSamples().stream().filter(s -> s.id().equals("last-heir")).findFirst().orElseThrow();
        assertTrue(epic.triggers().contains("NATIONS_MERGED"));
        assertTrue(epic.nodes().values().stream().filter(n -> !n.terminal()).allMatch(n -> n.quorum() >= 2));
        assertEquals(Set.of("support", "oppose", "aid", "ruins", "diplomacy"), epic.nodes().get("council").choices().keySet());
    }

    @Test void scholarStoryUsesTrustedIdentityAndTechnologyFacts() throws IOException {
        var scholar = loadSamples().stream().filter(s -> s.id().equals("silk-road-scholar")).findFirst().orElseThrow();
        assertTrue(scholar.triggers().contains("TECHNOLOGY_BREAKTHROUGH"));
        assertTrue(scholar.condition().test(Map.of("player.role", "scholar", "tech.stage", "2")));
        assertTrue(scholar.condition().test(Map.of("player.role", "explorer", "tech.stage", "3")));
        assertFalse(scholar.condition().test(Map.of("player.role", "soldier", "tech.stage", "3")));
        assertFalse(scholar.condition().test(Map.of("player.role", "scholar")));
        assertFalse(scholar.nodes().get("discovery").choices().get("trade").condition()
                .test(Map.of("player.role", "merchant", "nation.war", "true")));
    }

    @Test void disabledIntegrationExampleIsNotLoadedByDefault() throws IOException {
        var stories = loadSamples();
        copyResource("integration-example.json.disabled", "integration-example.json.disabled");
        assertEquals(stories.size(), ConfigLoader.load(directory, ignored -> false, ignored -> false).size());
    }

    @Test void integrationExampleRequiresRealRegisteredScriptAndQuest() throws IOException {
        copyResource("integration-example.json.disabled", "integration-example.json");
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(directory, ignored -> false, ignored -> false));
        var integration = ConfigLoader.load(directory, ignored -> true, ignored -> true).getFirst();
        assertEquals("integration-example", integration.id());
        var effect = integration.nodes().get("briefing").choices().get("petition").effects().getFirst();
        assertEquals("authority", effect.type());
        assertFalse(effect.permission().isBlank());
    }
}
