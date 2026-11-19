package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Campaign node IDs and native script node IDs belong to different graphs. */
class NativeSceneDeliveryTest {
    @TempDir Path directory;
    @Test void explicitNativeStartEffectKeepsItsDeclaredNativeNodeOverride(){
        UUID player=UUID.randomUUID();Instant time=Instant.parse("2026-11-19T10:00:00Z");
        var effect=new StoryDefinition.Effect("explicit-scene","narrative.start","chronicle_last_heir","evidence",null);
        var story=new StoryDefinition("explicit-scene","1","显式原生节点",StoryDefinition.Scope.PLAYER,List.of("TEST_EVENT"),null,"campaign-council",0,
                Map.of("campaign-council",new StoryDefinition.Node("议会",null,null,1,Map.of(),List.of(effect),true)));
        try(var engine=new RuleEngine(new SqliteStore(directory.resolve("explicit.db")),List.of(story),Clock.fixed(time,ZoneOffset.UTC))){
            engine.accept(new WorldEvent("explicit-source","TEST_EVENT",time,Set.of(player),Set.of(),Map.of(),"NativeSceneDeliveryTest","fixture"),List.of(new PlayerContext(player,"",Map.of(),Set.of())));
            assertEquals("evidence",engine.pending().getFirst().value());
        }
    }
}
