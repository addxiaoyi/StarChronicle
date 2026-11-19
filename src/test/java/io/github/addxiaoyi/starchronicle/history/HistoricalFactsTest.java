package io.github.addxiaoyi.starchronicle.history;

import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import io.github.addxiaoyi.starchronicle.config.Condition;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class HistoricalFactsTest {
    @TempDir Path temp;
    private static final UUID A=UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID B=UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID C=UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Test void worldNationAndPlayerCountsAreIndependentWithoutJoinMultiplication() {
        try(var store=new SqliteStore(temp.resolve("history.db"))) {
            seed(store);
            Map<String,String> facts=store.transaction(c->HistoricalFacts.read(c,A,"north",Set.of("chronicle.history.nation.TECH","chronicle.history.player.TECH")));
            assertEquals("2",facts.get("history.WAR"));assertEquals("2",facts.get("history.TECH"));
            assertEquals("2",facts.get("chronicle.history.world.WAR"));assertEquals("2",facts.get("chronicle.history.world.TECH"));
            assertEquals("2",facts.get("chronicle.history.nation.WAR"));assertEquals("0",facts.get("chronicle.history.nation.TECH"));
            assertEquals("2",facts.get("chronicle.history.player.WAR"));assertEquals("0",facts.get("chronicle.history.player.TECH"));
            Map<String,String> other=store.transaction(c->HistoricalFacts.read(c,B,"south",Set.of()));
            assertEquals("1",other.get("chronicle.history.nation.WAR"));assertEquals("1",other.get("chronicle.history.nation.TECH"));
            assertEquals("1",other.get("chronicle.history.player.WAR"));assertEquals("1",other.get("chronicle.history.player.TECH"));
            assertTrue(facts.keySet().stream().allMatch(key->key.startsWith("history.")||key.startsWith("chronicle.history.")));
            assertThrows(UnsupportedOperationException.class,()->facts.put("history.WAR","999"));
        }
    }

    @Test void explicitScopedMissingTypesAreZeroButLegacyMissingTypeStaysAbsent() {
        try(var store=new SqliteStore(temp.resolve("empty.db"))) {
            Set<String> wanted=Set.of("history.NONE","chronicle.history.world.NONE","chronicle.history.nation.NONE","chronicle.history.player.NONE");
            Map<String,String> facts=store.transaction(c->HistoricalFacts.read(c,A,"north",wanted));
            assertEquals(Map.of("chronicle.history.world.NONE","0","chronicle.history.nation.NONE","0","chronicle.history.player.NONE","0"),facts);
            assertFalse(facts.containsKey("history.NONE"));
            assertTrue(store.transaction(c->HistoricalFacts.read(c,A,"north",Set.of())).isEmpty());
        }
    }

    @Test void unknownSubjectsAreOmittedWhileKnownSubjectsWithNoRowsAreZero() {
        try(var store=new SqliteStore(temp.resolve("subjects.db"))) {
            seed(store);
            Set<String> wanted=Set.of("chronicle.history.world.WAR","chronicle.history.nation.WAR","chronicle.history.player.WAR");
            Map<String,String> unknown=store.transaction(c->HistoricalFacts.read(c,null,null,wanted));
            assertEquals("2",unknown.get("chronicle.history.world.WAR"));
            assertFalse(unknown.containsKey("chronicle.history.nation.WAR"));assertFalse(unknown.containsKey("chronicle.history.player.WAR"));
            Map<String,String> blank=store.transaction(c->HistoricalFacts.read(c,null,"  ",wanted));
            assertEquals(unknown,blank);
            UUID unseen=UUID.fromString("00000000-0000-0000-0000-000000000099");
            Map<String,String> known=store.transaction(c->HistoricalFacts.read(c,unseen,"unknown-but-known-id",wanted));
            assertEquals("0",known.get("chronicle.history.nation.WAR"));assertEquals("0",known.get("chronicle.history.player.WAR"));
        }
    }

    @Test void oneParameterizedGroupingQueryReadsAllRequestedCounts() {
        try(var store=new SqliteStore(temp.resolve("query.db"))) {
            seed(store);AtomicInteger queries=new AtomicInteger();List<String> sql=new ArrayList<>();
            Map<String,String> facts=store.transaction(connection->{
                Connection counted=(Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                    if(method.getName().equals("prepareStatement")){queries.incrementAndGet();sql.add((String)args[0]);}
                    try{return method.invoke(connection,args);}catch(InvocationTargetException error){throw error.getCause();}
                });
                return HistoricalFacts.read(counted,A,"north",Set.of("chronicle.history.world.X","chronicle.history.nation.X","chronicle.history.player.X"));
            });
            assertEquals(1,queries.get());assertTrue(sql.getFirst().contains("WHERE n.nation=?"));assertTrue(sql.getFirst().contains("WHERE p.player=?"));
            assertFalse(sql.getFirst().contains("north"));assertFalse(sql.getFirst().contains(A.toString()));
            assertEquals("2",facts.get("chronicle.history.player.WAR"));assertEquals("0",facts.get("chronicle.history.player.X"));
        }
    }

    @Test void nationAndTypeTextNeverBecomesSqlAndCopiedFactsAreNotRead() {
        try(var store=new SqliteStore(temp.resolve("untrusted.db"))) {
            seed(store);
            store.transaction(c->{try(var s=c.createStatement()){s.executeUpdate("INSERT INTO facts VALUES('WORLD','history.world.WAR','999')");}return null;});
            String weird="chronicle.history.world.WAR' OR 1=1 --";
            Map<String,String> facts=store.transaction(c->HistoricalFacts.read(c,A,"north' OR 1=1 --",Set.of(weird,"chronicle.history.nation.WAR")));
            assertEquals("2",facts.get("chronicle.history.world.WAR"));assertEquals("0",facts.get("chronicle.history.nation.WAR"));assertEquals("0",facts.get(weird));
            assertEquals("2",facts.get("chronicle.history.player.WAR"));
        }
    }

    @Test void canonicalScopesDoNotShadowAnyExistingLegacyEventType() {
        try(var store=new SqliteStore(temp.resolve("reserved.db"))) {
            store.transaction(c->{insert(c,"one","player.WAR",Set.of(A),Set.of("north"));insert(c,"two","nation.WAR",Set.of(A),Set.of("north"));insert(c,"three","world.WAR",Set.of(A),Set.of("north"));return null;});
            Map<String,String> facts=store.transaction(c->HistoricalFacts.read(c,null,null,Set.of("chronicle.history.player.WAR","chronicle.history.nation.WAR")));
            assertFalse(facts.containsKey("chronicle.history.player.WAR"));assertFalse(facts.containsKey("chronicle.history.nation.WAR"));
            assertEquals("1",facts.get("chronicle.history.world.player.WAR"));assertEquals("1",facts.get("chronicle.history.world.nation.WAR"));
            assertEquals("1",facts.get("history.player.WAR"));assertEquals("1",facts.get("history.nation.WAR"));assertEquals("1",facts.get("history.world.WAR"));
            assertEquals("1",facts.get("chronicle.history.world.world.WAR"));
        }
    }

    @Test void readsSeeTheCurrentTransactionAndSurviveReopenWithoutMaterializedCounts() {
        Path file=temp.resolve("restart.db");
        try(var store=new SqliteStore(file)) {
            store.transaction(c->{insert(c,"one","WAR",Set.of(A),Set.of("north"));assertEquals("1",HistoricalFacts.read(c,A,"north",Set.of()).get("chronicle.history.player.WAR"));assertFalse(c.getAutoCommit());return null;});
        }
        try(var store=new SqliteStore(file)) {
            assertEquals("1",store.transaction(c->HistoricalFacts.read(c,A,"north",Set.of())).get("chronicle.history.nation.WAR"));
            assertEquals(0,store.transaction(c->{try(var s=c.createStatement();var rows=s.executeQuery("SELECT COUNT(*) FROM facts")){rows.next();return rows.getInt(1);}}).intValue());
        }
    }

    @Test void requestedWalksEveryBooleanBranchAndIgnoresOtherNamespaces() {
        Condition condition=new Condition(List.of(atom("chronicle.history.world.A"),new Condition(null,List.of(atom("chronicle.history.nation.B"),atom("player.role"),new Condition(null,null,atom("chronicle.history.player.C"),null,null,null)),null,null,null,null)),null,null,null,null,null);
        Set<String> requested=HistoricalFacts.requested(null,condition,atom("history.LEGACY"),atom("past.player.story.choice"));
        assertEquals(Set.of("chronicle.history.world.A","chronicle.history.nation.B","chronicle.history.player.C","history.LEGACY"),requested);
        assertTrue(HistoricalFacts.requested((Condition[])null).isEmpty());assertTrue(HistoricalFacts.requested().isEmpty());
        assertThrows(UnsupportedOperationException.class,()->requested.add("chronicle.history.world.D"));
    }

    @Test void referenceValidationPreservesArbitraryLegacyTypesAndRejectsMalformedCanonicalKeys() {
        assertTrue(HistoricalFacts.accepts("history.nation.X"));assertTrue(HistoricalFacts.accepts("history.旧都 战事.v1"));
        assertTrue(HistoricalFacts.accepts("chronicle.history.world.旧都 战事.v1"));
        assertTrue(HistoricalFacts.accepts("chronicle.history.player."+"x".repeat(128)));
        assertFalse(HistoricalFacts.accepts("past.player.choice.chapter/1/vote"));assertFalse(HistoricalFacts.accepts(null));
        for(String invalid:List.of("history.","history.  ","history."+"x".repeat(129),"chronicle.history.nations.X","chronicle.history.player","chronicle.history.world.","chronicle.history.nation."+"x".repeat(129))) {
            assertThrows(IllegalArgumentException.class,()->HistoricalFacts.accepts(invalid),invalid);
            assertThrows(IllegalArgumentException.class,()->HistoricalFacts.requested(atom(invalid)),invalid);
        }
        try(var store=new SqliteStore(temp.resolve("invalid.db"))) {
            assertThrows(IllegalArgumentException.class,()->store.transaction(c->HistoricalFacts.read(c,A,"north",Set.of("chronicle.history.natoin.WAR"))));
        }
    }

    private static Condition atom(String fact){return new Condition(null,null,null,fact,"gte","1");}
    private static void seed(SqliteStore store){store.transaction(c->{
        insert(c,"one","WAR",Set.of(A,B),Set.of("north","south"));
        insert(c,"two","WAR",Set.of(A),Set.of("north"));
        insert(c,"three","TECH",Set.of(B),Set.of("south"));
        insert(c,"four","TECH",Set.of(C),Set.of("elsewhere"));return null;
    });}
    private static void insert(Connection c,String id,String type,Set<UUID> players,Set<String> nations)throws Exception {
        WorldEvent event=new WorldEvent(id,type,Instant.parse("2026-11-20T00:00:00Z"),players,nations,Map.of(),"test","test record "+id);
        try(var s=c.prepareStatement("INSERT INTO events VALUES(?,?,?,?,?)")){s.setString(1,id);s.setString(2,type);s.setString(3,event.occurredAt().toString());s.setString(4,"test-"+id);s.setString(5,SqliteStore.json(event));s.executeUpdate();}
        try(var s=c.prepareStatement("INSERT INTO event_players VALUES(?,?)")){for(UUID player:players){s.setString(1,id);s.setString(2,player.toString());s.addBatch();}s.executeBatch();}
        try(var s=c.prepareStatement("INSERT INTO event_nations VALUES(?,?)")){for(String nation:nations){s.setString(1,id);s.setString(2,nation);s.addBatch();}s.executeBatch();}
    }
}
