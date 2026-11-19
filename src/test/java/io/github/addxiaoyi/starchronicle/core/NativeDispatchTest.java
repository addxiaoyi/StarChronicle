package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class NativeDispatchTest {
    @TempDir Path directory;
    @Test void campaignNodeIdIsNeverPassedAsANativeSceneNodeOverride() {
        var node=new StoryDefinition.Node("Campaign scene","chronicle_native",null,1,Map.of(),List.of(),true);
        var story=new StoryDefinition("campaign","1","Campaign",StoryDefinition.Scope.PLAYER,List.of("SOURCE"),null,"entirely-different-campaign-node",0,Map.of("entirely-different-campaign-node",node));
        UUID player=UUID.randomUUID();var event=new WorldEvent("source","SOURCE",Instant.EPOCH,Set.of(player),Set.of(),Map.of(),"test","source=committed");
        try(var engine=new RuleEngine(new SqliteStore(directory.resolve("world.db")),List.of(story),Clock.systemUTC())) {
            engine.accept(event,List.of(new PlayerContext(player,"",Map.of(),Set.of())));
            Delivery delivery=engine.pending().getFirst();assertEquals("narrative.start",delivery.type());assertEquals("chronicle_native",delivery.target());assertEquals("",delivery.value());
            assertTrue(engine.accept(event,List.of()).duplicate());assertEquals(1,engine.pending().size());
        }
    }
}
