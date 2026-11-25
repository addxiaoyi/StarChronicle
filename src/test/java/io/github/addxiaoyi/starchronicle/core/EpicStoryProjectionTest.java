package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.PlayerContext;
import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import io.github.addxiaoyi.starchronicle.api.epic.*;
import io.github.addxiaoyi.starchronicle.config.Condition;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class EpicStoryProjectionTest {
    private static final Instant NOW = Instant.parse("2026-11-25T00:00:00Z");
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final String NA = "00000000-0000-0000-0000-000000000011";
    private static final String NB = "00000000-0000-0000-0000-000000000012";
    private static final String RELIEF = "00000000-0000-0000-0000-000000000019";
    private static final String DOMAIN = "00000000-0000-0000-0000-000000000099";
    private static final String HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    @TempDir Path temp;
    private String campaignInstance;

    @Test void nationalStoryReadsOnlyFrozenSeatFactsAndWaitsForFinalNativeReceipt() throws Exception {
        try (var store = new SqliteStore(temp.resolve("epic-story.db"))) {
            var ledger = new EpicCampaignLedger(store);
            ledger.open(plan(), "signup", RELIEF, NOW);
            var signup = ledger.enroll("signup", NA, A, 0, NOW.plusSeconds(1));
            signup = ledger.enroll("signup", NB, B, signup.revision(), NOW.plusSeconds(2));
            ledger.seal("signup", signup.revision(), A, NOW.plusSeconds(3));

            var story = story();
            var engine = new RuleEngine(store, List.of(story,aftermath()), Clock.fixed(NOW, ZoneOffset.UTC), null,
                    io.github.addxiaoyi.starchronicle.api.NationalVoteAuthority.denying(),
                    io.github.addxiaoyi.starchronicle.memory.MemoryRecipeCatalog.empty(), ledger);
            try {
                var event = new WorldEvent("epic-signup-event", "EPIC_SIGNUP_COMMITTED", NOW.plusSeconds(4),
                        Set.of(A, B), Set.of(NA, NB), Map.of("epic.signup_campaign", "heir-strategy"),
                        "StarChronicle", "sealed roster=" + signup.sealedEventId());
                var a = context(A, NA);
                var b = context(B, NB);
                String instance = engine.accept(event, List.of(a, b)).instanceIds().getFirst();
                campaignInstance=instance;
                ledger.start(instance, "signup", "epic-signup-event", NOW.plusSeconds(4));
                assertTrue(engine.availableChoices(instance, a).isEmpty(), "unverified context facts cannot open the route");

                for (var seat : List.of(Map.entry(A, NA), Map.entry(B, NB))) {
                    var operation = operation("records-" + seat.getValue(), "records",
                            EpicOperation.Type.QUEST_GATE, seat.getKey(), seat.getValue(), "chronicle_heir_records", "investigation/records-evidence");
                    ledger.prepare(operation);
                    ledger.observe(operation, new EpicObservation(EpicObservation.Status.COMMITTED, receipt(operation), "task receipt"),
                            "starcore.quest.evidence.v1", DOMAIN, NOW.plusSeconds(6));
                }
                var state = ledger.state(instance);
                state = ledger.routeVote(instance, NA, state.revision(), "WAR", NOW.plusSeconds(7));
                state = ledger.routeVote(instance, NB, state.revision(), "WAR", NOW.plusSeconds(8));
                assertEquals(Set.of("WAR"), engine.availableChoices(instance, a).keySet());
                engine.choose(instance, A, "WAR", a);
                assertTrue(engine.availableChoices(instance, a).isEmpty(), "route approval alone is not a native settlement");

                var war = operation("war", "heir-route", EpicOperation.Type.WAR_COMMIT,
                        A, NA, NB, "WAR_DECLARATION_V1");
                ledger.prepare(war);
                ledger.observe(war, new EpicObservation(EpicObservation.Status.COMMITTED, receipt(war), "war receipt"),
                        "starcore.epic", DOMAIN, NOW.plusSeconds(10));
                assertEquals(Set.of("settle"), engine.availableChoices(instance, a).keySet());
                engine.choose(instance, A, "settle", a);
                assertTrue(engine.instances(A).stream().filter(i -> i.id().equals(instance)).findFirst().orElseThrow().completed());
                var settlements=engine.history(null,A,0,100).stream().filter(e->e.type().equals("HEIR_STRATEGY_SETTLED")).toList();
                assertEquals(1,settlements.size());
                var settlement=settlements.getFirst();
                assertEquals("true",settlement.facts().get("epic.final.canonical"));
                assertEquals(instance,settlement.facts().get("epic.instance"));
                assertEquals("epic-signup-event",engine.causality(settlement.id()).parentId());
                assertEquals(List.of("last-heir-strategy"),engine.causality(settlement.id()).stories());
                engine.resumeTriggers(List.of(a,b));
                assertEquals(1,engine.instances(A).stream().filter(i->i.definition().id().equals("last-heir-strategy-aftermath")).count());
            } finally {
                engine.close();
            }
        }
        try(var store=new SqliteStore(temp.resolve("epic-story.db"))) {
            var ledger=new EpicCampaignLedger(store);
            try(var engine=new RuleEngine(store,List.of(story(),aftermath()),Clock.fixed(NOW.plusSeconds(40),ZoneOffset.UTC),null,
                    io.github.addxiaoyi.starchronicle.api.NationalVoteAuthority.denying(),
                    io.github.addxiaoyi.starchronicle.memory.MemoryRecipeCatalog.empty(),ledger)) {
                engine.resumeTriggers(List.of(context(A,NA),context(B,NB)));
                assertEquals(1,engine.history(null,A,0,100).stream().filter(e->e.type().equals("HEIR_STRATEGY_SETTLED")).count());
                assertEquals(1,engine.instances(A).stream().filter(i->i.definition().id().equals("last-heir-strategy-aftermath")).count());
            }
        }
    }

    private static StoryDefinition aftermath()throws Exception {
        return io.github.addxiaoyi.starchronicle.config.ConfigLoader.load(Path.of("examples/national-epic/last-heir-strategy/stories"),id->false,id->true)
                .stream().filter(story->story.id().equals("last-heir-strategy-aftermath")).findFirst().orElseThrow();
    }

    @Test void externalEventsCannotClaimCanonicalCompletion() {
        try(var store=new SqliteStore(temp.resolve("forged.db"));var engine=new RuleEngine(store,List.of(story()),Clock.fixed(NOW,ZoneOffset.UTC))) {
            var forged=new WorldEvent("forged","HEIR_STRATEGY_SETTLED",NOW,Set.of(A),Set.of(NA),
                    Map.of("epic.final.canonical","true"),"Untrusted","text is not a receipt");
            assertThrows(SecurityException.class,()->engine.accept(forged,List.of(context(A,NA))));
            assertTrue(engine.history(null,A,0,100).isEmpty());
        }
    }

    private static EpicCampaignPlan plan() {
        return new EpicCampaignPlan("heir-strategy", "last-heir-strategy", "1", "EPIC_SIGNUP_COMMITTED",
                "epic.signup_campaign", "heir-strategy", "epic.relief_nation", List.of(
                new EpicPhasePolicy("records", EpicPhasePolicy.Kind.PREPARATION,
                        Set.of(new EpicRequirement(EpicRequirement.Type.QUEST_GATE, "chronicle_heir_records", "investigation/records-evidence"))),
                new EpicPhasePolicy("heir-route", EpicPhasePolicy.Kind.FINAL_ROUTE, Set.of())),
                Map.of("WAR", Set.of(new EpicRequirement(EpicRequirement.Type.WAR_COMMIT, "ANY", "WAR_DECLARATION_V1"))));
    }

    private static StoryDefinition story() {
        Condition warOpen = new Condition(null, null, null, "epic.route.war.open", "eq", "true");
        Condition dungeonOpen = new Condition(null, null, null, "epic.route.dungeon.open", "eq", "true");
        Condition warComplete = new Condition(null, null, null, "epic.final.war_committed", "eq", "true");
        var routes = new LinkedHashMap<String, StoryDefinition.Choice>();
        routes.put("WAR", new StoryDefinition.Choice("WAR", "war-await", warOpen, null, List.of()));
        routes.put("DUNGEON", new StoryDefinition.Choice("DUNGEON", "dungeon-await", dungeonOpen, null, List.of()));
        var warAwait = new StoryDefinition.Node("await war", null, null, 1,
                Map.of("settle", new StoryDefinition.Choice("settle", "settled", warComplete, null, List.of())), List.of(), false);
        var dungeonAwait = new StoryDefinition.Node("await dungeon", null, null, 1,
                Map.of("settle",new StoryDefinition.Choice("settle","settled",
                        new Condition(null,null,null,"epic.final.dungeon_committed","eq","true"),null,List.of())),List.of(),false);
        var settled = new StoryDefinition.Node("settled", null, null, 1, Map.of(),
                List.of(new StoryDefinition.Effect("settled-history","history","HEIR_STRATEGY_SETTLED","Verified settlement",null)), true);
        return new StoryDefinition("last-heir-strategy", "1", "Heir", StoryDefinition.Scope.WORLD,
                List.of("EPIC_SIGNUP_COMMITTED"), new Condition(null, null, null,
                "event.epic.signup_campaign", "eq", "heir-strategy"), "route", 0,
                Map.of("route", new StoryDefinition.Node("route", null, null, 1, routes, List.of(), false),
                        "war-await", warAwait, "dungeon-await", dungeonAwait, "settled", settled));
    }

    private static PlayerContext context(UUID player, String nation) {
        return new PlayerContext(player, nation,
                Map.of("epic.route.war.open", "true", "epic.final.war_committed", "true"),
                Set.of("starchronicle.use"));
    }

    private EpicOperation operation(String id, String phase, EpicOperation.Type type,
                                           UUID actor, String nation, String target, String nativeReference) {
        return new EpicOperation(id, "heir-strategy", campaignInstance, "epic-signup-event", phase, type,
                actor, nation, target, nativeReference, HASH, "starchronicle.use", NOW.plusSeconds(5));
    }

    private static EpicReceipt receipt(EpicOperation operation) {
        String issuer = operation.type() == EpicOperation.Type.QUEST_GATE
                ? "starcore.quest.evidence.v1" : "starcore.epic";
        return new EpicReceipt(issuer, DOMAIN, operation.operationId(), operation.campaignId(), operation.instanceId(),
                operation.sourceEventId(), operation.phaseId(), operation.type(), operation.actorId(), operation.nationId(),
                operation.target(), operation.nativeReference(), operation.definitionFingerprint(), operation.permission(),
                "native:" + operation.operationId(), NOW.plusSeconds(6), "committed native receipt");
    }
}
