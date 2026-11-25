package io.github.addxiaoyi.starchronicle.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.addxiaoyi.starchronicle.api.epic.EpicCampaignPlan;
import io.github.addxiaoyi.starchronicle.api.epic.EpicPhasePolicy;
import io.github.addxiaoyi.starchronicle.api.epic.EpicRequirement;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Contract checks for the optional national strategy content package. */
class LastHeirStrategyContentTest {
    private static final Path ROOT = Path.of("examples/national-epic/last-heir-strategy");

    private Map<String, StoryDefinition> stories() throws Exception {
        return ConfigLoader.load(ROOT.resolve("stories"), ignored -> false,
                        id -> id.equals("chronicle_heir_records"))
                .stream().collect(Collectors.toMap(StoryDefinition::id, story -> story));
    }

    private EpicCampaignPlan plan() throws Exception {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        return mapper.readValue(ROOT.resolve("epic-campaigns/heir-strategy.yml").toFile(), EpicCampaignPlan.class);
    }

    private JsonNode routePlan() throws Exception {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        return mapper.readTree(ROOT.resolve("route-bindings.yml").toFile());
    }

    @Test void strategyAndAftermathLoadAsSeparateStrictVersions() throws Exception {
        var loaded = stories();
        assertEquals(Set.of("last-heir-strategy", "last-heir-strategy-aftermath"), loaded.keySet());
        var story = loaded.get("last-heir-strategy");
        assertEquals(StoryDefinition.Scope.WORLD, story.scope());
        assertEquals(StoryDefinition.VoteUnit.NATION, story.voteUnit());
        assertEquals(List.of("EPIC_SIGNUP_COMMITTED"), story.triggers());
        assertTrue(story.condition().test(Map.of("event.epic.signup_campaign", "heir-strategy")));
        assertFalse(story.condition().test(Map.of("event.epic.signup_campaign", "other-campaign")));
        assertThrows(IllegalArgumentException.class,
                () -> ConfigLoader.load(ROOT.resolve("stories"), ignored -> false, ignored -> false));
    }

    @Test void investigationUsesARealNationTaskGateAndNotAStoryClaim() throws Exception {
        var story = stories().get("last-heir-strategy");
        var gates = story.nodes().get("investigation").effects().stream()
                .filter(effect -> effect.type().equals("quest.await")).toList();
        assertEquals(1, gates.size());
        assertEquals("chronicle_heir_records", gates.getFirst().target());
        assertEquals("NATION", gates.getFirst().value());
        assertTrue(story.nodes().values().stream().flatMap(node -> node.choices().values().stream())
                .noneMatch(choice -> choice.effects().stream().anyMatch(effect -> effect.type().equals("quest.await"))));
    }

    @Test void versionPinnedPlanCarriesNativePreparationReceipts() throws Exception {
        EpicCampaignPlan plan = plan();
        assertEquals("heir-strategy", plan.campaignId());
        assertEquals("last-heir-strategy", plan.storyId());
        assertEquals("1", plan.storyVersion());
        assertEquals("EPIC_SIGNUP_COMMITTED", plan.signupSource());
        assertEquals("epic.signup_campaign", plan.signupFact());
        assertEquals("heir-strategy", plan.signupValue());
        assertEquals(List.of("heir-records", "heir-supplies", "heir-treaty", "heir-strategy-route"),
                plan.phases().stream().map(EpicPhasePolicy::phaseId).toList());
        assertEquals(EpicPhasePolicy.Kind.FINAL_ROUTE, plan.finalRoute().kind());
        assertTrue(plan.finalRoute().requirements().isEmpty());

        var allRequirements = plan.phases().stream().flatMap(phase -> phase.requirements().stream()).toList();
        assertTrue(allRequirements.contains(new EpicRequirement(EpicRequirement.Type.QUEST_GATE, "chronicle_heir_records", "investigation/records-evidence")));
        assertTrue(allRequirements.contains(new EpicRequirement(EpicRequirement.Type.TREASURY_AID, "RELIEF", "10.00")));
        assertTrue(allRequirements.contains(new EpicRequirement(EpicRequirement.Type.RESOURCE_AID, "RELIEF", "agricultural:10")));
        assertTrue(allRequirements.contains(new EpicRequirement(EpicRequirement.Type.DIPLOMACY_INVITE, "RELIEF", "ALLIANCE_INVITE_V1")));
        assertTrue(allRequirements.contains(new EpicRequirement(EpicRequirement.Type.DIPLOMACY_ACCEPT, "ANY", "ALLIANCE_ACCEPT_V1")));
        assertEquals(Set.of(
                        new EpicRequirement(EpicRequirement.Type.NATION_FOUNDING, "ANY", "NATION_FOUNDING_V1"),
                        new EpicRequirement(EpicRequirement.Type.WAR_COMMIT, "ANY", "WAR_DECLARATION_V1"),
                        new EpicRequirement(EpicRequirement.Type.FINAL_BATTLE, "ANY", "WAR_OUTCOME_V1")),
                plan.requirementsForRoute("WAR"));
        assertEquals(Set.of(new EpicRequirement(EpicRequirement.Type.DUNGEON_COMPLETION,
                        "COOPERATIVE_RELIC", "DUNGEON_RUN_V1")), plan.requirementsForRoute("DUNGEON"));
    }

    @Test void routeChoicesFailClosedWithoutTrustedReadinessAndKeepOutcomesSeparate() throws Exception {
        var story = stories().get("last-heir-strategy");
        var route = story.nodes().get("route");
        assertEquals(Set.of("WAR", "DUNGEON"), route.choices().keySet());
        assertFalse(route.choices().get("WAR").condition().test(Map.of()));
        assertFalse(route.choices().get("DUNGEON").condition().test(Map.of()));
        assertTrue(route.choices().get("WAR").condition().test(Map.of(
                "epic.preparations.ready", "true", "epic.route.war.open", "true")));
        assertTrue(route.choices().get("DUNGEON").condition().test(Map.of(
                "epic.preparations.ready", "true", "epic.route.dungeon.open", "true")));
        assertEquals("war-await", route.choices().get("WAR").next());
        assertEquals("dungeon-await", route.choices().get("DUNGEON").next());
        assertEquals("settled", story.nodes().get("war-await").choices().get("settle").next());
        assertEquals("settled", story.nodes().get("dungeon-await").choices().get("settle").next());
        assertEquals("true", story.nodes().get("war-await").choices().get("settle").condition().value());
        assertEquals("epic.final.war_committed", story.nodes().get("war-await").choices().get("settle").condition().fact());
        assertEquals("epic.final.dungeon_committed", story.nodes().get("dungeon-await").choices().get("settle").condition().fact());
    }

    @Test void contentEffectsCannotDirectlyMutateNationalAuthority() throws Exception {
        for (var story : stories().values()) for (var node : story.nodes().values()) {
            for (var effect : node.effects()) assertTrue(Set.of("quest.await", "history", "memory", "title").contains(effect.type()));
            for (var choice : node.choices().values()) for (var effect : choice.effects())
                assertTrue(Set.of("history", "memory", "title").contains(effect.type()));
        }
    }

    @Test void postApprovalRoutesRequireDifferentNativeSettlementProofs() throws Exception {
        JsonNode routes = routePlan().path("routes");
        assertTrue(routes.has("WAR"));
        assertTrue(routes.has("DUNGEON"));
        assertEquals(List.of("NATION_FOUNDING", "WAR_COMMIT", "FINAL_BATTLE"),
                iterable(routes.path("WAR").path("requirements")).stream()
                        .map(item -> item.path("type").asText()).toList());
        assertEquals("DUNGEON_COMPLETION", routes.path("DUNGEON").path("requirements").get(0).path("type").asText());
        assertEquals("DUNGEON_RUN_V1", routes.path("DUNGEON").path("requirements").get(0).path("nativeReference").asText());
        assertEquals("keep-pending", routePlan().path("receiptRules").path("unknownPolicy").asText());
        assertEquals("explicit-reconciliation-only", routePlan().path("receiptRules").path("retryPolicy").asText());
    }

    private static List<JsonNode> iterable(JsonNode array) {
        java.util.ArrayList<JsonNode> values = new java.util.ArrayList<>();
        array.forEach(values::add);
        return List.copyOf(values);
    }
}
