package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.PlayerContext;
import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import io.github.addxiaoyi.starchronicle.config.Condition;
import io.github.addxiaoyi.starchronicle.config.ConfigLoader;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Public integration projection contracts exercised through the real engine and SQLite store. */
class RecollectionsTest {
    @TempDir Path directory;
    private static final UUID A=UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID B=UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID C=UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final Instant NOW=Instant.parse("2026-11-20T12:00:00Z");
    private static final String PERSONAL="past.player.choice.recollection-chapter/1/vote";
    private static final String WORLD_RESOLUTION="past.resolution.world.recollection-chapter/1/vote";
    private SqliteStore store;
    private RuleEngine engine;

    @AfterEach void close(){if(engine!=null)engine.close();else if(store!=null)store.close();}

    @Test void oneTwoNationEventCountsOncePerWorldNationAndEvidencedPlayerIncludingOnReplay() {
        open();
        var event=event("shared-war","WAR",Set.of(A,B),Set.of("north","south"));
        List<PlayerContext> audience=List.of(context(A,"north"),context(B,"south"),context(C,"north"));
        assertFalse(engine.accept(event,audience).duplicate());
        Set<String> refs=Set.of("history.WAR","chronicle.history.world.WAR","chronicle.history.nation.WAR","chronicle.history.player.WAR");
        Map<String,String> expected=Map.of("history.WAR","1","chronicle.history.world.WAR","1","chronicle.history.nation.WAR","1","chronicle.history.player.WAR","1");
        assertEquals(expected,engine.recollections(context(A,"north"),refs));
        assertEquals(expected,engine.recollections(context(B,"south"),refs));
        assertTrue(engine.accept(event,audience).duplicate());
        assertEquals(expected,engine.recollections(context(A,"north"),refs));
        assertEquals(1,engine.history(null,null,0,10).size());
    }

    @Test void onlineNationalAudienceDoesNotBecomeAnActorInPersonalHistory() {
        open();
        engine.accept(event("national-announcement","ANNOUNCEMENT",Set.of(A),Set.of("north")),List.of(context(A,"north"),context(B,"north")));
        Set<String> refs=Set.of("chronicle.history.world.ANNOUNCEMENT","chronicle.history.nation.ANNOUNCEMENT","chronicle.history.player.ANNOUNCEMENT");
        assertEquals(Map.of("chronicle.history.world.ANNOUNCEMENT","1","chronicle.history.nation.ANNOUNCEMENT","1","chronicle.history.player.ANNOUNCEMENT","0"),engine.recollections(context(B,"north"),refs));
        assertEquals("1",engine.recollections(context(A,"north"),refs).get("chronicle.history.player.ANNOUNCEMENT"));
        assertTrue(engine.history(null,B,0,10).isEmpty());
        assertEquals(Set.of(A),engine.history(null,null,0,10).getFirst().participants());
    }

    @Test void nationalHistoryUsesCurrentNationalityWhilePersonalHistoryKeepsItsOriginalActor() {
        open();
        engine.accept(event("north-battle","BATTLE",Set.of(A),Set.of("north")),List.of(context(A,"north")));
        engine.accept(event("south-battle-1","BATTLE",Set.of(B),Set.of("south")),List.of(context(B,"south")));
        engine.accept(event("south-battle-2","BATTLE",Set.of(B),Set.of("south")),List.of(context(B,"south")));
        Set<String> refs=Set.of("chronicle.history.nation.BATTLE","chronicle.history.player.BATTLE");
        assertEquals(Map.of("chronicle.history.nation.BATTLE","1","chronicle.history.player.BATTLE","1"),engine.recollections(context(A,"north"),refs));
        assertEquals(Map.of("chronicle.history.nation.BATTLE","2","chronicle.history.player.BATTLE","1"),engine.recollections(context(A,"south"),refs));
        assertEquals(Map.of("chronicle.history.nation.BATTLE","1","chronicle.history.player.BATTLE","0"),engine.recollections(context(C,"north"),refs));
    }

    @Test void requestedMissingScopedTypesAreZeroOnlyForKnownSubjectsAndLegacyMissingStaysAbsent() {
        open();
        Set<String> refs=Set.of("history.MISSING","chronicle.history.world.MISSING","chronicle.history.nation.MISSING","chronicle.history.player.MISSING");
        assertEquals(Map.of("chronicle.history.world.MISSING","0","chronicle.history.nation.MISSING","0","chronicle.history.player.MISSING","0"),engine.recollections(context(A,"known-nation"),refs));
        Map<String,String> unknown=Map.of("chronicle.history.world.MISSING","0","chronicle.history.player.MISSING","0");
        assertEquals(unknown,engine.recollections(context(A,""),refs));
        assertEquals(unknown,engine.recollections(context(A,null),refs));
        assertEquals(unknown,engine.recollections(context(A,"  "),refs));
        assertFalse(engine.recollections(context(A,"known-nation"),refs).containsKey("history.MISSING"));
    }

    @Test void legacyEventTypesNamedAfterScopesRetainTheirExactGlobalMeaning() {
        open();
        engine.accept(event("legacy-nation","nation.X",Set.of(A),Set.of("north")),List.of(context(A,"north")));
        engine.accept(event("legacy-world","world.X",Set.of(B),Set.of("south")),List.of(context(B,"south")));
        engine.accept(event("legacy-text","旧都 战事.v1",Set.of(A),Set.of("north")),List.of(context(A,"north")));
        Set<String> refs=Set.of("history.nation.X","history.world.X","history.旧都 战事.v1","chronicle.history.world.nation.X","chronicle.history.world.world.X","chronicle.history.nation.X");
        assertEquals(Map.of("history.nation.X","1","history.world.X","1","history.旧都 战事.v1","1","chronicle.history.world.nation.X","1","chronicle.history.world.world.X","1","chronicle.history.nation.X","0"),engine.recollections(context(C,"north"),refs));
        assertEquals("1",engine.recollections(context(C,""),Set.of("history.nation.X")).get("history.nation.X"));
    }

    @Test void forgedContextAndCopiedFactsCannotReplaceCanonicalHistoryOrCompletedChoiceEvidence() {
        open();
        complete(A,"north","yes","choice-source");
        Map<String,String> forged=Map.of("history.BEGIN","999","chronicle.history.world.BEGIN","999","chronicle.history.nation.BEGIN","999","chronicle.history.player.BEGIN","999",PERSONAL,"no",WORLD_RESOLUTION,"yes");
        store.transaction(connection->{
            try(var statement=connection.prepareStatement("INSERT INTO facts(owner,key,value) VALUES(?,?,?)")) {
                for(String owner:List.of("WORLD",B.toString()))for(var fact:forged.entrySet()) {
                    statement.setString(1,owner);statement.setString(2,fact.getKey());statement.setString(3,fact.getValue());statement.addBatch();
                }
                statement.executeBatch();
            }
            return null;
        });
        Set<String> refs=forged.keySet();
        assertEquals(Map.of("history.BEGIN","1","chronicle.history.world.BEGIN","1","chronicle.history.nation.BEGIN","1","chronicle.history.player.BEGIN","1",PERSONAL,"yes"),engine.recollections(context(A,"north",forged),refs));
        var other=engine.recollections(context(B,"north",Map.of(PERSONAL,"yes",WORLD_RESOLUTION,"yes","chronicle.history.player.BEGIN","999")),refs);
        assertEquals("0",other.get("chronicle.history.player.BEGIN"));
        assertFalse(other.containsKey(PERSONAL));assertFalse(other.containsKey(WORLD_RESOLUTION));
    }

    @Test void returnsOnlyRequestedFactsAndAnImmutableDetachedResult() {
        open();
        engine.accept(event("first-war","WAR",Set.of(A),Set.of("north")),List.of(context(A,"north")));
        Map<String,String> snapshot=engine.recollections(context(A,"north"),List.of("chronicle.history.world.WAR","chronicle.history.world.WAR"));
        assertEquals(Map.of("chronicle.history.world.WAR","1"),snapshot);
        assertTrue(engine.recollections(context(A,"north"),List.of()).isEmpty());
        assertThrows(UnsupportedOperationException.class,()->snapshot.put("history.WAR","999"));
        engine.accept(event("second-war","WAR",Set.of(A),Set.of("north")),List.of(context(A,"north")));
        assertEquals(Map.of("chronicle.history.world.WAR","1"),snapshot);
        assertEquals(Map.of("chronicle.history.world.WAR","2"),engine.recollections(context(A,"north"),snapshot.keySet()));
    }

    @Test void completedPersonalChoicesRemainWithTheirOriginalPlayersAcrossNationChangeAndRestart() {
        open();
        complete(A,"north","yes","a-choice");complete(B,"north","no","b-choice");
        assertEquals(Map.of(PERSONAL,"yes"),engine.recollections(context(A,"south"),Set.of(PERSONAL)));
        assertEquals(Map.of(PERSONAL,"no"),engine.recollections(context(B,"south"),Set.of(PERSONAL)));
        assertTrue(engine.recollections(context(C,"north",Map.of(PERSONAL,"yes")),Set.of(PERSONAL)).isEmpty());
        engine.close();engine=null;store=null;open();
        assertEquals(Map.of(PERSONAL,"yes"),engine.recollections(context(A,"south"),Set.of(PERSONAL)));
        assertEquals(Map.of(PERSONAL,"no"),engine.recollections(context(B,""),Set.of(PERSONAL)));
        assertTrue(engine.recollections(context(C,"south",Map.of(PERSONAL,"yes")),Set.of(PERSONAL)).isEmpty());
    }

    @Test void validatesReferenceCountNullsAndSupportedNamespacesBeforeProjection() {
        open();
        List<String> limit=new ArrayList<>();for(int i=0;i<256;i++)limit.add("chronicle.history.world.MISSING_"+i);
        assertEquals(256,engine.recollections(context(A,"north"),limit).size());
        limit.add("chronicle.history.world.MISSING_256");
        assertThrows(IllegalArgumentException.class,()->engine.recollections(context(A,"north"),limit));
        assertThrows(IllegalArgumentException.class,()->engine.recollections(context(A,"north"),Collections.nCopies(257,"history.WAR")));
        assertThrows(NullPointerException.class,()->engine.recollections(null,List.of()));
        assertThrows(NullPointerException.class,()->engine.recollections(context(A,"north"),null));
        assertThrows(NullPointerException.class,()->engine.recollections(context(A,"north"),Arrays.asList("history.WAR",null)));
        for(String invalid:List.of("player.honor","event.type","choice.vote","history","chronicle.history","world.WAR","")) {
            assertThrows(IllegalArgumentException.class,()->engine.recollections(context(A,"north"),List.of(invalid)),invalid);
        }
    }

    @Test void rejectsMalformedHistoricalScopesTypesAndPastChoiceReferences() {
        open();
        for(String invalid:List.of("history.","history.  ","history."+"x".repeat(129),"chronicle.history.nations.WAR","chronicle.history.World.WAR","chronicle.history.world","chronicle.history.world.","chronicle.history.player.  ","chronicle.history.nation."+"x".repeat(129),"past.player.recollection-chapter/1/vote","past.player.choice.recollection-chapter/1","past.player.choice.recollection-chapter/1/vote/yes","past.player.choice.recollection-chapter//vote","past.resolution.player.recollection-chapter/1/vote","past.player.choice.recollection-chapter/1/bad node")) {
            assertThrows(IllegalArgumentException.class,()->engine.recollections(context(A,"north"),List.of(invalid)),invalid);
        }
        assertTrue(engine.recollections(context(A,"north"),Set.of(PERSONAL,"past.resolution.nation.recollection-chapter/1/vote",WORLD_RESOLUTION)).isEmpty());
    }

    @Test void configValidationRejectsCanonicalScopeTyposWithoutReinterpretingLegalLegacyTypes() {
        for(String invalid:List.of("chronicle.history.nations.WAR","chronicle.history.natoin.WAR","chronicle.history.players.WAR","chronicle.history.World.WAR","chronicle.history.world.","chronicle.history.player","chronicle.history.nation."+"x".repeat(129))) {
            Condition nested=new Condition(List.of(equal("player.role","soldier"),new Condition(null,null,equal(invalid,"1"),null,null,null)),null,null,null,null,null);
            assertThrows(IllegalArgumentException.class,()->ConfigLoader.validate(story(nested),ignored->false,ignored->false),invalid);
        }
        for(String valid:List.of("history.nation.X","history.world.X","history.natoin.WAR","history.旧都 战事.v1","history."+"x".repeat(128),"chronicle.history.world.nation.X","chronicle.history.nation.旧都 战事.v1","chronicle.history.player.X")) {
            assertDoesNotThrow(()->ConfigLoader.validate(story(equal(valid,"1")),ignored->false,ignored->false),valid);
        }
    }

    private void open(){store=new SqliteStore(directory.resolve("recollections.db"));engine=new RuleEngine(store,List.of(story(null)),Clock.fixed(NOW,ZoneOffset.UTC));}
    private void complete(UUID player,String nation,String choice,String source) {
        engine.accept(event(source,"BEGIN",Set.of(player),Set.of(nation)),List.of(context(player,nation)));
        StoryInstance instance=engine.instances(player).stream().filter(value->!value.completed()).findFirst().orElseThrow();
        engine.choose(instance.id(),player,"vote",choice,context(player,nation));
        assertTrue(engine.instances(player).stream().anyMatch(value->value.id().equals(instance.id())&&value.completed()));
    }
    private static StoryDefinition story(Condition condition) {
        var vote=new StoryDefinition.Node("Choose",null,null,1,Map.of("yes",new StoryDefinition.Choice("Yes","end",null,null,List.of()),"no",new StoryDefinition.Choice("No","end",null,null,List.of())),List.of(),false);
        return new StoryDefinition("recollection-chapter","1","Recollection chapter",StoryDefinition.Scope.PLAYER,List.of("BEGIN"),condition,"vote",0,Map.of("vote",vote,"end",new StoryDefinition.Node("End",null,null,1,Map.of(),List.of(),true)));
    }
    private static Condition equal(String fact,String value){return new Condition(null,null,null,fact,"eq",value);}
    private static PlayerContext context(UUID player,String nation){return context(player,nation,Map.of());}
    private static PlayerContext context(UUID player,String nation,Map<String,String> facts){return new PlayerContext(player,nation,facts,Set.of());}
    private static WorldEvent event(String id,String type,Set<UUID> players,Set<String> nations){return new WorldEvent(id,type,NOW,players,nations,Map.of(),"RecollectionsTest","committed source="+id);}
}
