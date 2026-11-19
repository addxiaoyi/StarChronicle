package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.PlayerContext;
import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class OrchestrationRegressionTest {
    @TempDir Path directory;
    private static final UUID A=UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID B=UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static PlayerContext context(UUID player) {return new PlayerContext(player,"river",Map.of(),Set.of());}
    private static StoryDefinition.Node ending() {return new StoryDefinition.Node("end",null,null,1,Map.of(),
            List.of(new StoryDefinition.Effect("honor","honor","player","5",null)),true);}
    private static WorldEvent event(UUID... players) {return new WorldEvent("audit:source","AUDIT",Instant.parse("2026-11-19T00:00:00Z"),Set.of(players),Set.of("river"),Map.of(),"RegressionTest","committed-record=source");}

    @Test void sharedTerminalStartKeepsEverySourceParticipant() {
        var definition=new StoryDefinition("terminal-start","1","terminal",StoryDefinition.Scope.WORLD,
                List.of("AUDIT"),null,"end",0,Map.of("end",ending()));
        try(var engine=new RuleEngine(new SqliteStore(directory.resolve("terminal.db")),List.of(definition),Clock.systemUTC())) {
            engine.accept(event(A,B),List.of(context(A),context(B)));
            assertEquals(1,engine.instances(A).size(),"First participant cannot disappear when a second participant is processed");
            assertEquals(Set.of(A,B),engine.instances(A).getFirst().members().keySet());
            assertEquals("5",engine.facts(A).get("player.honor"));assertEquals("5",engine.facts(B).get("player.honor"));
        }
    }

    @Test void acceptedDefinitionCannotBeChangedThroughTheCallersMutableMap() {
        var nodes=new HashMap<String,StoryDefinition.Node>();
        nodes.put("start",new StoryDefinition.Node("original",null,null,1,
                Map.of("continue",new StoryDefinition.Choice("continue","end",null,null,List.of())),List.of(),false));
        nodes.put("end",ending());
        var definition=new StoryDefinition("immutable","1","immutable",StoryDefinition.Scope.PLAYER,
                new ArrayList<>(List.of("AUDIT")),null,"start",0,nodes);
        try(var engine=new RuleEngine(new SqliteStore(directory.resolve("pinned.db")),List.of(definition),Clock.systemUTC())) {
            String id=engine.accept(event(A),List.of(context(A))).instanceIds().getFirst();
            nodes.put("end",new StoryDefinition.Node("tampered",null,null,1,Map.of(),
                    List.of(new StoryDefinition.Effect("honor","honor","player","500",null)),true));
            engine.choose(id,A,"start","continue",context(A));
            assertEquals("5",engine.facts(A).get("player.honor"),"Pinned graph must ignore later external mutations");
            assertEquals("end",engine.instances(A).getFirst().definition().nodes().get("end").text());
        }
    }
}
