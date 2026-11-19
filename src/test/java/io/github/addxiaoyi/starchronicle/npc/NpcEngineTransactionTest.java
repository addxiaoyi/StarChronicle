package io.github.addxiaoyi.starchronicle.npc;

import io.github.addxiaoyi.starchronicle.api.PlayerContext;
import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition;
import io.github.addxiaoyi.starchronicle.core.RuleEngine;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real engine and NPC ledger on one connection, including injected SQL failures. */
class NpcEngineTransactionTest {
    @TempDir Path directory;
    private static final UUID A=UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID B=UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Instant NOW=Instant.parse("2026-11-19T10:00:00Z");
    private SqliteStore store;
    private NpcLedger npcs;
    private RuleEngine engine;

    @BeforeEach void open() throws Exception {
        Path file=directory.resolve("npcs.yml");
        try(var input=getClass().getResourceAsStream("/npcs.yml")){assertNotNull(input);Files.copy(input,file);}
        store=new SqliteStore(directory.resolve("shared.db"));
        npcs=new NpcLedger(store,NpcCatalog.load(file));
    }
    @AfterEach void close(){if(engine!=null)engine.close();else if(store!=null)store.close();}
    private static PlayerContext context(UUID player){return new PlayerContext(player,"river",Map.of("player.role","scholar"),Set.of());}
    private static WorldEvent event(String id,String type,UUID...players){return new WorldEvent(id,type,NOW,Set.of(players),Set.of("river"),Map.of(),"NpcEngineTransactionTest","committed="+id);}
    private static StoryDefinition.Effect honor(){return new StoryDefinition.Effect("honor","honor","player","5",null);}
    private void startEngine(StoryDefinition definition){engine=new RuleEngine(store,List.of(definition),Clock.fixed(NOW,ZoneOffset.UTC),npcs);}
    private static StoryDefinition immediate(String type,List<StoryDefinition.Effect> effects){
        return new StoryDefinition("atomic-start","1","原子启动",StoryDefinition.Scope.WORLD,List.of(type),null,"end",0,
                Map.of("end",new StoryDefinition.Node("已记录",null,null,1,Map.of(),effects,true)));
    }
    private static StoryDefinition choice(List<StoryDefinition.Effect> effects){
        return new StoryDefinition("atomic-choice","1","原子结局",StoryDefinition.Scope.WORLD,List.of("CUSTOM_SOURCE"),null,"start",0,
                Map.of("start",new StoryDefinition.Node("共同选择",null,null,1,Map.of("support",new StoryDefinition.Choice("支持","end",null,null,List.of())),List.of(),false),
                        "end",new StoryDefinition.Node("共同结局",null,null,1,Map.of(),effects,true)));
    }
    private void failHonorWrites(){store.transaction(c->{try(var s=c.createStatement()){s.execute("CREATE TRIGGER fail_honor BEFORE INSERT ON facts WHEN NEW.key='player.honor' BEGIN SELECT RAISE(ABORT,'injected honor failure'); END");}return null;});}
    private void allowHonorWrites(){store.transaction(c->{try(var s=c.createStatement()){s.execute("DROP TRIGGER fail_honor");}return null;});}
    private int count(String table){return store.transaction(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM "+table)){assertTrue(r.next());return r.getInt(1);}});}
    private NpcView heir(UUID player){return npcs.view(player,"ancient-heir",context(player),engine.worldFacts());}

    @Test void acceptRollsBackCanonicalHistoryNpcReactionAndTerminalRewardTogether(){
        startEngine(immediate("HEIR_RESTORATION_SUPPORTED",List.of(honor())));
        WorldEvent source=event("atomic-source","HEIR_RESTORATION_SUPPORTED",A,B);failHonorWrites();
        assertThrows(IllegalStateException.class,()->engine.accept(source,List.of(context(A),context(B))));
        assertEquals(0,count("events"));assertEquals(0,count("event_players"));assertEquals(0,count("event_nations"));
        assertEquals(0,count("instances"));assertEquals(0,count("outcomes"));assertEquals(0,count("facts"));
        assertEquals(0,heir(A).reputation());assertEquals(0,heir(A).growth());
        allowHonorWrites();String instance=engine.accept(source,List.of(context(A),context(B))).instanceIds().getFirst();
        assertTrue(engine.instances(A).getFirst().completed());assertEquals(instance,engine.instances(B).getFirst().id());
        for(UUID player:List.of(A,B)){
            assertEquals("5",engine.facts(player).get("player.honor"));assertEquals(15,heir(player).reputation());assertEquals(12,heir(player).trust());assertEquals(20,heir(player).growth());
        }
        int receipts=count("outcomes");assertTrue(engine.accept(source,List.of(context(B),context(A))).duplicate());
        assertEquals(receipts,count("outcomes"));assertEquals(1,engine.history(null,null,0,20).size());assertEquals("5",engine.facts(A).get("player.honor"));
    }

    @Test void generatedHistoryAndNpcReactionRollbackWithFailedChoiceThenRetryExactlyOnce(){
        var history=new StoryDefinition.Effect("restoration-history","history","HEIR_RESTORATION_SUPPORTED","共同支持复国",null);
        startEngine(choice(List.of(history,honor())));
        WorldEvent source=event("choice-source","CUSTOM_SOURCE",A,B);
        String instance=engine.accept(source,List.of(context(A),context(B))).instanceIds().getFirst();failHonorWrites();
        assertThrows(IllegalStateException.class,()->engine.choose(instance,A,"start","support",context(A)));
        assertEquals(1,count("events"));assertEquals(0,count("outcomes"));assertEquals(0,count("facts"));
        var unchanged=engine.instances(A).getFirst();assertEquals("start",unchanged.node());assertFalse(unchanged.completed());assertTrue(unchanged.choices().isEmpty());assertTrue(unchanged.votes().isEmpty());
        assertEquals(0,heir(A).reputation());assertEquals(0,heir(B).growth());
        allowHonorWrites();engine.choose(instance,A,"start","support",context(A));
        assertEquals(2,engine.history(null,null,0,20).size());assertTrue(engine.instances(B).getFirst().completed());
        for(UUID player:List.of(A,B)){assertEquals(15,heir(player).reputation());assertEquals(20,heir(player).growth());assertEquals("5",engine.facts(player).get("player.honor"));}
        int receipts=count("outcomes");assertThrows(IllegalStateException.class,()->engine.choose(instance,A,"start","support",context(A)));
        assertEquals(receipts,count("outcomes"));assertEquals(2,count("events"));assertEquals(20,heir(A).growth());
    }

    @Test void explicitNpcEffectsAndGlobalPromotionDoNotCommitBeforeLaterRewardFailure(){
        var trust=new StoryDefinition.Effect("trust","trust","ancient-heir","7",null);
        var growth=new StoryDefinition.Effect("growth","growth","ancient-heir","20",null);
        startEngine(choice(List.of(trust,growth,honor())));
        String instance=engine.accept(event("effect-source","CUSTOM_SOURCE",A,B),List.of(context(A),context(B))).instanceIds().getFirst();
        failHonorWrites();assertThrows(IllegalStateException.class,()->engine.choose(instance,A,"support",context(A)));
        assertEquals(0,heir(A).trust());assertEquals(0,heir(B).trust());assertEquals(0,heir(A).growth());assertEquals("claimant",heir(A).position());assertEquals(0,count("outcomes"));
        allowHonorWrites();engine.choose(instance,B,"support",context(B));
        assertEquals(7,heir(A).trust());assertEquals(7,heir(B).trust());assertEquals(20,heir(A).growth());assertEquals("council-speaker",heir(B).position());
        assertEquals(1,engine.history(null,null,0,20).size());
    }
}
