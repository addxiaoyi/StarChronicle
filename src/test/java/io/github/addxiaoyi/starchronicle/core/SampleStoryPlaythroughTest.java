package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.PlayerContext;
import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import io.github.addxiaoyi.starchronicle.config.ConfigLoader;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Walks every shipped root-to-ending route through real SQLite, including restart and replay. */
class SampleStoryPlaythroughTest {
    @TempDir Path directory;
    private static final UUID A=UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID B=UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Instant NOW=Instant.parse("2026-11-19T12:00:00Z");
    private record Step(String node, String choice) {}
    private record Route(StoryDefinition story, List<Step> steps, String terminal) {}

    @TestFactory Stream<DynamicTest> allShippedBranchRoutesSurviveRestartAndReplay() throws Exception {
        Path storiesDirectory=Files.createDirectory(directory.resolve("stories"));
        for(String name:List.of("last-heir.yml","siege-embers.yml","silk-road-scholar.yml")) {
            try(var stream=getClass().getResourceAsStream("/stories/"+name)) {
                assertNotNull(stream);Files.copy(stream,storiesDirectory.resolve(name));
            }
        }
        List<Route> routes=new ArrayList<>();
        for(var story:ConfigLoader.load(storiesDirectory,ignored->false,ignored->false))
            routes(story,story.start(),List.of(),routes);
        assertEquals(22,routes.size(),"Every council stance and every ending must remain covered");
        List<DynamicTest> tests=new ArrayList<>();
        for(int index=0;index<routes.size();index++) {
            Route route=routes.get(index);
            Path database=directory.resolve("playthrough-"+index+".db");
            String display=route.story().id()+" / "+route.steps().stream().map(Step::choice).toList();
            tests.add(DynamicTest.dynamicTest(display,()->play(route,database)));
        }
        return tests.stream();
    }

    private static void routes(StoryDefinition story,String node,List<Step> steps,List<Route> result) {
        if(story.nodes().get(node).terminal()) {result.add(new Route(story,List.copyOf(steps),node));return;}
        for(var choice:story.nodes().get(node).choices().entrySet()) {
            var next=new ArrayList<>(steps);next.add(new Step(node,choice.getKey()));
            routes(story,choice.getValue().next(),next,result);
        }
    }

    private void play(Route route,Path database) throws Exception {
        StoryDefinition story=route.story();
        String role="scholar";
        if(route.steps().stream().anyMatch(s->s.choice().equals("diplomacy")))role="monarch";
        if(story.id().equals("silk-road-scholar"))role=switch(route.steps().getFirst().choice()) {
            case "map"->"explorer";case "trade"->"merchant";default->"scholar";
        };
        Map<String,String> current=Map.of("player.role",role,"tech.stage","3","nation.war","false",
                "nation.treasury","2000","resource.wood","256");
        List<PlayerContext> contexts=story.scope()==StoryDefinition.Scope.PLAYER
                ?List.of(new PlayerContext(A,"river",current,Set.of()))
                :List.of(new PlayerContext(A,"river",current,Set.of()),
                    new PlayerContext(B,story.scope()==StoryDefinition.Scope.WORLD?"mountain":"river",current,Set.of()));
        Set<UUID> players=new HashSet<>();Set<String> nations=new HashSet<>();
        contexts.forEach(c->{players.add(c.playerId());nations.add(c.nationId());});
        WorldEvent event=new WorldEvent("sample:"+story.id(),story.triggers().getFirst(),NOW,players,nations,
                Map.of("battle_kind","siege","discovery","ancient-caravanserai"),"SampleTest",
                "committed-test-record="+story.id());
        Map<String,String> expectedPersonal=new HashMap<>();Map<String,String> expectedMemory=new HashMap<>();
        List<String> expectedHistory=new ArrayList<>();
        accumulate(story.nodes().get(story.start()).effects(),expectedPersonal,expectedMemory,expectedHistory);
        RuleEngine engine=open(database,story);
        try {
            ProcessReport accepted=engine.accept(event,contexts);
            assertFalse(accepted.duplicate());assertEquals(1,accepted.instanceIds().size());
            String instanceId=accepted.instanceIds().getFirst();
            assertEquals(players,engine.instances(A).getFirst().members().keySet());
            for(Step step:route.steps()) {
                assertEquals(step.node(),engine.instances(A).getFirst().node());
                for(int participant=0;participant<contexts.size();participant++) {
                    // quorum 1 resolves immediately; quorum 2 must survive a mid-vote restart.
                    if(participant>=story.nodes().get(step.node()).quorum())break;
                    PlayerContext actor=contexts.get(participant);
                    assertTrue(engine.availableChoices(instanceId,actor).containsKey(step.choice()));
                    engine.choose(instanceId,actor.playerId(),step.node(),step.choice(),actor);
                    if(story.nodes().get(step.node()).quorum()>participant+1) {
                        assertTrue(engine.choose(instanceId,actor.playerId(),step.node(),step.choice(),actor).duplicate());
                        assertEquals(step.node(),engine.instances(A).getFirst().node());
                        engine.close();engine=open(database,story);
                        assertEquals(step.choice(),engine.instances(A).getFirst().votes().get(actor.playerId()));
                    }
                }
                var choice=story.nodes().get(step.node()).choices().get(step.choice());
                accumulate(choice.effects(),expectedPersonal,expectedMemory,expectedHistory);
                accumulate(story.nodes().get(choice.next()).effects(),expectedPersonal,expectedMemory,expectedHistory);
                // Each stage is recovered from disk, rather than relying on live Java record references.
                engine.close();engine=open(database,story);
                assertEquals(choice.next(),engine.instances(A).getFirst().node());
            }
            StoryInstance completed=engine.instances(A).getFirst();
            assertTrue(completed.completed());assertEquals(route.terminal(),completed.node());
            assertTrue(engine.activeInstances().isEmpty());
            assertTrue(engine.pending().isEmpty(),"Default stories must never enqueue fictional Core calls");
            for(var player:players)assertEquals(expectedPersonal,engine.facts(player));
            assertEquals(expectedMemory,readWorldMemory(database));
            List<WorldEvent> history=engine.history(null,null,0,100);
            assertEquals(expectedHistory.size()+1,history.size());
            var actualTypes=history.stream().filter(e->e.source().equals("StarChronicle")).map(WorldEvent::type).sorted().toList();
            assertEquals(expectedHistory.stream().sorted().toList(),actualTypes);
            for(var entry:history) {
                assertEquals(players,entry.participants());assertEquals(nations,entry.nations());
                assertFalse(entry.evidence().isBlank());
                if(entry.source().equals("StarChronicle"))assertTrue(entry.evidence().contains("source="+event.id()));
            }
            assertEquals(history.size(),engine.history("river",A,0,100).size());
            assertTrue(engine.accept(event,contexts).duplicate());
            engine.close();engine=open(database,story);
            assertTrue(engine.accept(event,contexts).duplicate());
            RuleEngine recovered=engine;
            var last=route.steps().getLast();
            assertThrows(IllegalStateException.class,()->recovered.choose(instanceId,A,last.node(),last.choice(),contexts.getFirst()));
            for(var player:players)assertEquals(expectedPersonal,engine.facts(player),"Replay must not repeat rewards");
            assertEquals(expectedMemory,readWorldMemory(database));
            assertEquals(history,engine.history(null,null,0,100),"Replay must not duplicate or rewrite history");
        } finally {engine.close();}
    }

    private static RuleEngine open(Path database,StoryDefinition story) {
        return new RuleEngine(new SqliteStore(database),List.of(story),Clock.fixed(NOW,ZoneOffset.UTC));
    }
    private static void accumulate(List<StoryDefinition.Effect> effects,Map<String,String> personal,
                                   Map<String,String> memory,List<String> history) {
        for(var effect:effects)switch(effect.type()) {
            case "honor"->increment(personal,"player.honor",effect.value());
            case "reputation"->increment(personal,"npc."+effect.target()+".reputation",effect.value());
            case "title"->personal.put("title."+effect.target(),effect.value());
            case "memory"->memory.put("region."+effect.target(),effect.value());
            case "history"->history.add(effect.target());
            default->fail("Unexpected nonlocal default effect "+effect.type());
        }
    }
    private static void increment(Map<String,String> values,String key,String delta) {
        values.put(key,Long.toString(Long.parseLong(values.getOrDefault(key,"0"))+Long.parseLong(delta)));
    }
    private static Map<String,String> readWorldMemory(Path database) throws Exception {
        Map<String,String> memory=new HashMap<>();
        try(var connection=DriverManager.getConnection("jdbc:sqlite:"+database.toAbsolutePath());
            var statement=connection.prepareStatement("SELECT key,value FROM facts WHERE owner='WORLD'");
            var result=statement.executeQuery()) {
            while(result.next())memory.put(result.getString(1),result.getString(2));
        }
        return memory;
    }
}
