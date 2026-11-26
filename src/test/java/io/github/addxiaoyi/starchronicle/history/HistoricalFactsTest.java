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

    @Test void oneParameterizedIndexQueryReadsAllRequestedCounts() {
        try(var store=new SqliteStore(temp.resolve("query.db"))) {
            seed(store);AtomicInteger queries=new AtomicInteger();List<String> sql=new ArrayList<>();
            Map<String,String> facts=store.transaction(connection->{
                Connection counted=(Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                    if(method.getName().equals("prepareStatement")){queries.incrementAndGet();sql.add((String)args[0]);}
                    try{return method.invoke(connection,args);}catch(InvocationTargetException error){throw error.getCause();}
                });
                return HistoricalFacts.read(counted,A,"north",Set.of("chronicle.history.world.X","chronicle.history.nation.X","chronicle.history.player.X"));
            });
            assertEquals(1,queries.get());assertTrue(sql.getFirst().contains("history_event_counts"));
            assertTrue(sql.getFirst().contains("scope='nation' AND subject=?"));assertTrue(sql.getFirst().contains("scope='player' AND subject=?"));
            assertFalse(sql.getFirst().contains("COUNT("));assertFalse(sql.getFirst().contains("JOIN"));
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

    @Test void readsSeeTheCurrentTransactionAndSurviveReopenWithoutCopiedFacts() {
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

    @Test void selectiveProjectionAgreesWithCompleteViewForEveryRequestedScopeAndAlias() {
        try(var store=new SqliteStore(temp.resolve("selective.db"))) {
            seed(store);
            Set<String> keys=Set.of("history.WAR","history.MISSING","history.player.WAR","chronicle.history.world.WAR",
                    "chronicle.history.world.MISSING","chronicle.history.nation.WAR","chronicle.history.nation.TECH",
                    "chronicle.history.player.WAR","chronicle.history.player.TECH","past.player.choice.chapter/1/vote","ordinary");
            for(UUID player:Arrays.asList(A,B,C,null))for(String nation:Arrays.asList("north","south","missing",null," ")) {
                var expected=new HashMap<>(store.transaction(c->HistoricalFacts.read(c,player,nation,keys)));
                expected.keySet().retainAll(keys);
                var selected=store.transaction(c->HistoricalFacts.readRequested(c,player,nation,keys));
                assertEquals(expected,selected,"player="+player+", nation="+nation);
                assertThrows(UnsupportedOperationException.class,()->selected.put("history.WAR","999"));
            }
        }
    }

    @Test void unrelatedOrUnknownScopeReferencesNeverReadTheHistoryTables() throws Exception {
        Connection neverRead=(Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},
                (proxy,method,args)->{throw new AssertionError("Unnecessary SQL: "+method.getName());});
        assertTrue(HistoricalFacts.readRequested(neverRead,A,"north",Set.of("player.role","past.player.choice.chapter/1/vote")).isEmpty());
        assertTrue(HistoricalFacts.readRequested(neverRead,A,"north",null).isEmpty());
        assertTrue(HistoricalFacts.readRequested(neverRead,null," ",Set.of("chronicle.history.player.WAR","chronicle.history.nation.WAR")).isEmpty());
        assertThrows(IllegalArgumentException.class,()->HistoricalFacts.readRequested(neverRead,null,null,Set.of("chronicle.history.typo.WAR")));
    }

    @Test void selectiveTypesRemainLiteralAndLegacyDotsNeverBecomeAScope() {
        try(var store=new SqliteStore(temp.resolve("selective-literals.db"))) {
            String type="旧都' OR 1=1 --.player.WAR";
            store.transaction(c->{insert(c,"one",type,Set.of(A),Set.of("north' OR 1=1 --"));insert(c,"two","player.WAR",Set.of(B),Set.of("south"));return null;});
            Set<String> wanted=Set.of("history."+type,"chronicle.history.world."+type,"chronicle.history.player."+type,
                    "chronicle.history.nation."+type,"history.player.WAR","chronicle.history.player.WAR");
            var facts=store.transaction(c->HistoricalFacts.readRequested(c,A,"north' OR 1=1 --",wanted));
            assertEquals("1",facts.get("history."+type));assertEquals("1",facts.get("chronicle.history.world."+type));
            assertEquals("1",facts.get("chronicle.history.player."+type));assertEquals("1",facts.get("chronicle.history.nation."+type));
            assertEquals("1",facts.get("history.player.WAR"));assertEquals("0",facts.get("chronicle.history.player.WAR"));
        }
    }

    @Test void selectiveCountsReadUncommittedEventsAndRollbackWithoutStaleCaching() {
        Path file=temp.resolve("selective-transaction.db");Set<String> keys=Set.of("history.WAR","chronicle.history.world.WAR","chronicle.history.player.WAR");
        try(var store=new SqliteStore(file)) {
            assertThrows(IllegalStateException.class,()->store.transaction(c->{
                insert(c,"rolled-back","WAR",Set.of(A),Set.of("north"));
                assertEquals("1",HistoricalFacts.readRequested(c,A,"north",keys).get("chronicle.history.player.WAR"));
                assertFalse(c.getAutoCommit());throw new IllegalStateException("rollback");
            }));
            var absent=store.transaction(c->HistoricalFacts.readRequested(c,A,"north",keys));
            assertFalse(absent.containsKey("history.WAR"));assertEquals("0",absent.get("chronicle.history.player.WAR"));
            store.transaction(c->{insert(c,"committed","WAR",Set.of(A),Set.of("north"));return null;});
        }
        try(var store=new SqliteStore(file)) {
            assertEquals("1",store.transaction(c->HistoricalFacts.readRequested(c,A,"north",keys)).get("chronicle.history.player.WAR"));
        }
    }

    @Test void manyRequestedTypesUseBoundedParametersAndDoNotOmitAnyBoundaryType() {
        try(var store=new SqliteStore(temp.resolve("many-types.db"))) {
            store.transaction(c->{for(int i=0;i<300;i++)insert(c,"type-"+i,"T"+i,Set.of(A),Set.of("north"));return null;});
            Set<String> wanted=new HashSet<>();
            for(int i=0;i<301;i++)for(String prefix:List.of("history.","chronicle.history.world.","chronicle.history.player.","chronicle.history.nation."))wanted.add(prefix+"T"+i);
            var expected=new HashMap<>(store.transaction(c->HistoricalFacts.read(c,A,"north",wanted)));expected.keySet().retainAll(wanted);
            assertEquals(expected,store.transaction(c->HistoricalFacts.readRequested(c,A,"north",wanted)));
            assertEquals(1203,expected.size());
        }
    }

    @Test void worldTypeFilterUsesTheCoveringIndexAndRecreatesItOnReopen() {
        Path file=temp.resolve("indexed.db");
        try(var store=new SqliteStore(file)) {
            seed(store);
            store.transaction(c->{try(var s=c.createStatement()){s.execute("DROP INDEX history_type");}return null;});
        }
        try(var store=new SqliteStore(file)) {
            store.transaction(c->{try(var s=c.prepareStatement("EXPLAIN QUERY PLAN SELECT type,COUNT(*) FROM events WHERE type IN (?,?) GROUP BY type")) {
                s.setString(1,"WAR");s.setString(2,"TECH");List<String> plan=new ArrayList<>();
                try(var r=s.executeQuery()){while(r.next())plan.add(r.getString(4));}
                assertTrue(plan.stream().anyMatch(p->p.contains("history_type")&&p.contains("SEARCH")),plan.toString());
                assertFalse(plan.stream().anyMatch(p->p.contains("TEMP B-TREE")),plan.toString());
            }return null;});
            store.transaction(c->{try(var s=c.prepareStatement("EXPLAIN QUERY PLAN SELECT type,total FROM history_event_counts WHERE scope=? AND subject=? AND type IN (?,?)")) {
                s.setString(1,"world");s.setString(2,"");s.setString(3,"WAR");s.setString(4,"TECH");List<String> plan=new ArrayList<>();
                try(var r=s.executeQuery()){while(r.next())plan.add(r.getString(4));}
                assertTrue(plan.stream().anyMatch(p->p.contains("history_event_counts")&&p.contains("SEARCH")),plan.toString());
                assertFalse(plan.stream().anyMatch(p->p.contains("SCAN")||p.contains("TEMP B-TREE")),plan.toString());
            }return null;});
            assertEquals(Map.of("history.WAR","2"),store.transaction(c->HistoricalFacts.readRequested(c,null,null,Set.of("history.WAR"))));
        }
    }

    @Test void completeAndSelectiveViewsReadOnlyCountRowsEvenForRepeatedTypes() {
        try(var store=new SqliteStore(temp.resolve("count-rows.db"))) {
            store.transaction(c->{for(int i=0;i<12;i++)insert(c,"repeat-"+i,"WAR",Set.of(A),Set.of("north"));return null;});
            List<String> queries=new ArrayList<>();
            store.transaction(connection->{
                Connection indexed=(Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                    if(method.getName().equals("prepareStatement")) {
                        String sql=(String)args[0];queries.add(sql);
                        assertTrue(sql.contains("history_event_counts"),sql);assertFalse(sql.matches("(?s).*\\b(events|event_nations|event_players)\\b.*"),sql);
                    }
                    try{return method.invoke(connection,args);}catch(InvocationTargetException error){throw error.getCause();}
                });
                Set<String> wanted=Set.of("history.WAR","chronicle.history.nation.WAR","chronicle.history.player.WAR");
                assertEquals("12",HistoricalFacts.read(indexed,A,"north",wanted).get("history.WAR"));
                assertEquals(Map.of("history.WAR","12","chronicle.history.nation.WAR","12","chronicle.history.player.WAR","12"),HistoricalFacts.readRequested(indexed,A,"north",wanted));
                return null;
            });
            assertEquals(4,queries.size());
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
