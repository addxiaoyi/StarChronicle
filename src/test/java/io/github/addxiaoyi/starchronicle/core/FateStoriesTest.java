package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.PlayerContext;
import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import io.github.addxiaoyi.starchronicle.config.ConfigLoader;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class FateStoriesTest {
    @TempDir Path directory;
    private static final List<String> ROLES=List.of("soldier","general","merchant","scholar","explorer","diplomat","mayor","monarch");
    private static final Instant NOW=Instant.parse("2026-11-19T00:00:00Z");
    private static final UUID PLAYER=UUID.fromString("00000000-0000-0000-0000-000000000001");

    private List<StoryDefinition> definitions() throws Exception {
        Path stories=Files.createDirectories(directory.resolve("fates"));
        for(String role:ROLES)try(var input=getClass().getResourceAsStream("/stories/fate-"+role+".yml")) {
            assertNotNull(input);Files.copy(input,stories.resolve("fate-"+role+".yml"));
        }
        return ConfigLoader.load(stories,ignored->false,ignored->false);
    }
    private static PlayerContext context(UUID player,String role){return new PlayerContext(player,"river",Map.of("player.role",role),Set.of());}
    private static WorldEvent event(String id,Set<UUID> participants){return new WorldEvent(id,"PLAYER_JOIN",NOW,participants,Set.of("river"),Map.of("time_basis","OBSERVED"),"PlayerLifecycle","Daily observed join; date=2026-11-19");}
    private static RuleEngine engine(Path database,List<StoryDefinition> definitions){return new RuleEngine(new SqliteStore(database),definitions,Clock.fixed(NOW,ZoneOffset.UTC));}

    @Test void eightTrustedRolesReceiveOnlyTheirOwnPersonalFate() throws Exception {
        var definitions=definitions();assertEquals(8,definitions.size());
        List<PlayerContext> contexts=new ArrayList<>();
        for(int index=0;index<ROLES.size();index++)contexts.add(context(new UUID(0,index+1),ROLES.get(index)));
        Set<UUID> players=new HashSet<>();contexts.forEach(context->players.add(context.playerId()));
        try(var engine=engine(directory.resolve("roles.db"),definitions)) {
            assertEquals(8,engine.accept(event("join:all-roles",players),contexts).instanceIds().size());
            for(var context:contexts) {
                assertEquals(1,engine.instances(context.playerId()).size());
                assertEquals("fate-"+context.facts().get("player.role"),engine.instances(context.playerId()).getFirst().definition().id());
                assertEquals(StoryDefinition.Scope.PLAYER,engine.instances(context.playerId()).getFirst().definition().scope());
                assertEquals(1,engine.availableInstances(context).size());
            }
        }
    }
    @Test void eventMetadataCannotGrantAnIdentityOrInventAnUnconfiguredRole() throws Exception {
        var definitions=definitions();
        try(var engine=engine(directory.resolve("identity.db"),definitions)) {
            var spoofed=new WorldEvent("spoofed-role","PLAYER_JOIN",NOW,Set.of(PLAYER),Set.of("river"),
                    Map.of("player.role","monarch","role","general"),"TrustedProducer","Metadata is not authoritative identity");
            assertTrue(engine.accept(spoofed,List.of(new PlayerContext(PLAYER,"river",Map.of(),Set.of()))).instanceIds().isEmpty());
            assertTrue(engine.accept(event("unknown-role",Set.of(PLAYER)),List.of(context(PLAYER,"admin"))).instanceIds().isEmpty());
        }
    }
    @TestFactory Stream<DynamicTest> allSixteenFateEndingsPersistAndReplayWithoutDuplicateRewards() throws Exception {
        var definitions=definitions();List<DynamicTest> tests=new ArrayList<>();int count=0;
        for(var story:definitions)for(var choice:story.nodes().get(story.start()).choices().entrySet()) {
            var routeChoice=choice;Path database=directory.resolve("fate-route-"+(count++)+".db");
            tests.add(DynamicTest.dynamicTest(story.id()+" / "+choice.getKey(),()->play(story,routeChoice,database)));
        }
        assertEquals(16,tests.size());return tests.stream();
    }
    private void play(StoryDefinition story,Map.Entry<String,StoryDefinition.Choice> choice,Path database) {
        String role=story.id().substring("fate-".length());var context=context(PLAYER,role);
        var source=event("join:"+role+":2026-11-19",Set.of(PLAYER));String instanceId;
        try(var engine=engine(database,List.of(story))) {
            instanceId=engine.accept(source,List.of(context)).instanceIds().getFirst();
            assertEquals(story.start(),engine.instances(PLAYER).getFirst().node());
        }
        Map<String,String> expected=new HashMap<>();var ending=story.nodes().get(choice.getValue().next());assertTrue(ending.terminal());
        for(var effect:ending.effects())switch(effect.type()) {
            case "honor"->expected.put("player.honor",effect.value());
            case "title"->expected.put("title."+effect.target(),effect.value());
            case "reputation"->expected.put("npc."+effect.target()+".reputation",effect.value());
            case "history"->{ }
            default->fail("Fate contains unsupported default effect "+effect.type());
        }
        try(var engine=engine(database,List.of(story))) {
            assertTrue(engine.availableChoices(instanceId,context).containsKey(choice.getKey()));
            engine.choose(instanceId,PLAYER,story.start(),choice.getKey(),context);
            assertTrue(engine.instances(PLAYER).getFirst().completed());assertEquals(expected,engine.facts(PLAYER));
            assertEquals(2,engine.history("river",PLAYER,0,100).size());assertTrue(engine.pending().isEmpty());
        }
        try(var recovered=engine(database,List.of(story))) {
            assertEquals(expected,recovered.facts(PLAYER));assertTrue(recovered.accept(source,List.of(context)).duplicate());
            assertEquals(2,recovered.history(null,PLAYER,0,100).size());
            assertThrows(IllegalStateException.class,()->recovered.choose(instanceId,PLAYER,story.start(),choice.getKey(),context));
            assertEquals(expected,recovered.facts(PLAYER));
            assertTrue(recovered.accept(event("join:"+role+":new",Set.of(PLAYER)),List.of(context)).instanceIds().isEmpty(),"Cooldown cannot be bypassed by a new source ID");
        }
    }
}
