package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class HistoryPaginationTest {
    @TempDir Path directory;
    static final UUID A=new UUID(0,1),B=new UUID(0,2);
    static final Instant TIME=Instant.parse("2026-11-26T00:00:00Z");
    static RuleEngine engine(SqliteStore store){return new RuleEngine(store,List.of(),Clock.fixed(TIME,ZoneOffset.UTC));}
    static WorldEvent event(String id,long nanos,Set<UUID> players,Set<String> nations){return new WorldEvent(id,"SAVED",TIME.plusNanos(nanos),players,nations,Map.of(),"committed","Evidence "+id);}
    static void add(RuleEngine engine,String id,long nanos,Set<UUID> players,Set<String> nations){engine.accept(event(id,nanos,players,nations),List.of());}
    static List<String> ids(List<WorldEvent> events){return events.stream().map(WorldEvent::id).toList();}
    static List<WorldEvent> pages(RuleEngine engine,String nation,UUID player,int size) {
        List<WorldEvent> result=new ArrayList<>();Set<String> seen=new HashSet<>();String cursor=null;
        do{var page=engine.historyPage(nation,player,cursor,size);for(var event:page.events())assertTrue(seen.add(event.id()));result.addAll(page.events());cursor=page.nextCursor();assertTrue(seen.size()<1000);}while(cursor!=null);
        return result;
    }

    @Test void tiedTimestampsUnicodeIdsAndNanosecondsPageExactlyInStoredOrder() {
        try(var store=new SqliteStore(directory.resolve("ties.db"))) {
            var engine=engine(store);
            for(String id:List.of("a","z","'quoted'","汉字","\uE000","\uD800\uDC00"))add(engine,id,0,Set.of(A),Set.of("north"));
            add(engine,"nano",1,Set.of(A),Set.of("north"));add(engine,"later",1_000_000_000,Set.of(A),Set.of("north"));
            var expected=engine.history(null,null,0,100);
            assertEquals(List.of("later","nano","\uD800\uDC00","\uE000","汉字","z","a","'quoted'"),ids(expected));
            for(int size:List.of(1,2,3,8,100))assertEquals(expected,pages(engine,null,null,size));
            assertEquals(expected,pages(engine,"north",A,2));
        }
    }

    @Test void filtersUseRecordedParticipantsAndCursorMustBelongToTheSameScope() {
        try(var store=new SqliteStore(directory.resolve("scopes.db"))) {
            var engine=engine(store);
            add(engine,"a-north",1,Set.of(A),Set.of("north"));add(engine,"b-north",2,Set.of(B),Set.of("north"));
            add(engine,"a-south",3,Set.of(A),Set.of("south"));add(engine,"both",4,Set.of(A,B),Set.of("north","south"));
            add(engine,"system",5,Set.of(),Set.of());
            assertEquals(List.of("both","b-north","a-north"),ids(pages(engine,"north",null,1)));
            assertEquals(List.of("both","a-south","a-north"),ids(pages(engine,null,A,1)));
            assertEquals(List.of("both","a-north"),ids(pages(engine,"north",A,1)));
            assertTrue(engine.historyPage("missing",null,null,10).events().isEmpty());
            assertThrows(IllegalArgumentException.class,()->engine.historyPage("south",A,"a-north",10));
            assertThrows(IllegalArgumentException.class,()->engine.historyPage("north",A,"b-north",10));
            assertThrows(IllegalArgumentException.class,()->engine.historyPage(null,A,"system",10));
        }
    }

    @Test void newArrivalsDoNotShiftAnExistingCursorAndOlderLateEvidenceCanAppear() {
        try(var store=new SqliteStore(directory.resolve("arrivals.db"))) {
            var engine=engine(store);for(int n=1;n<=4;n++)add(engine,"old-"+n,n*10,Set.of(),Set.of());
            var first=engine.historyPage(null,null,null,2);assertEquals(List.of("old-4","old-3"),ids(first.events()));
            add(engine,"newest",100,Set.of(),Set.of());add(engine,"late-older",25,Set.of(),Set.of());
            var second=engine.historyPage(null,null,first.nextCursor(),2);assertEquals(List.of("late-older","old-2"),ids(second.events()));
            var last=engine.historyPage(null,null,second.nextCursor(),2);assertEquals(List.of("old-1"),ids(last.events()));assertNull(last.nextCursor());
            assertEquals("newest",engine.historyPage(null,null,null,2).events().getFirst().id());
        }
    }

    @Test void invalidOrMissingCursorsFailWithoutReturningTheHeadOrWritingEvidence() {
        try(var store=new SqliteStore(directory.resolve("invalid.db"))) {
            var engine=engine(store);add(engine,"saved",0,Set.of(),Set.of());var before=engine.history(null,null,0,10);
            for(String cursor:List.of("missing"," ","x".repeat(257),"' OR 1=1 --"))assertThrows(IllegalArgumentException.class,()->engine.historyPage(null,null,cursor,10));
            for(int size:List.of(-1,0,101,Integer.MAX_VALUE))assertThrows(IllegalArgumentException.class,()->engine.historyPage(null,null,null,size));
            assertEquals(before,engine.history(null,null,0,10));
        }
    }

    @Test void emptyExactFullAndShortFinalPagesHaveNoSpuriousCursorAndAreImmutable() {
        try(var store=new SqliteStore(directory.resolve("end.db"))) {
            var engine=engine(store);var empty=engine.historyPage(null,null,null,1);assertTrue(empty.events().isEmpty());assertNull(empty.nextCursor());
            for(int n=0;n<4;n++)add(engine,"e"+n,n,Set.of(),Set.of());
            var first=engine.historyPage(null,null,null,2);assertEquals("e2",first.nextCursor());
            var last=engine.historyPage(null,null,first.nextCursor(),2);assertEquals(2,last.events().size());assertNull(last.nextCursor());
            assertThrows(UnsupportedOperationException.class,()->last.events().clear());
            var all=engine.historyPage(null,null,null,100);assertEquals(4,all.events().size());assertNull(all.nextCursor());
        }
    }

    @Test void reopeningRepairsOnlyTheDerivedIndexAndUsesActualLegacyStoredTimeBytes() {
        Path file=directory.resolve("legacy.db");List<String> before;List<WorldEvent> expected;
        try(var store=new SqliteStore(file)) {
            var engine=engine(store);add(engine,"whole",0,Set.of(),Set.of());add(engine,"fraction",123_000_000,Set.of(),Set.of());add(engine,"other",1_000_000_000,Set.of(),Set.of());
            store.transaction(c->{try(var s=c.createStatement()){s.execute("UPDATE events SET time='2026-11-26T00:00:00Z' WHERE id='whole'");s.execute("UPDATE events SET time='2026-11-26T00:00:00.123Z' WHERE id='fraction'");s.execute("DROP INDEX history_cursor");}return null;});
            before=rows(store);expected=engine.history(null,null,0,100);
        }
        try(var store=new SqliteStore(file)) {
            var engine=engine(store);assertEquals(expected,pages(engine,null,null,1));assertEquals(before,rows(store));
            store.transaction(c->{try(var s=c.createStatement();var r=s.executeQuery("EXPLAIN QUERY PLAN SELECT e.json FROM events e WHERE (e.time,e.id)<('2026-11-26T00:00:00Z','whole') ORDER BY e.time DESC,e.id DESC LIMIT 2")) {
                List<String> plan=new ArrayList<>();while(r.next())plan.add(r.getString(4));
                assertTrue(plan.stream().anyMatch(line->line.contains("SEARCH")&&line.contains("history_cursor")),plan.toString());assertTrue(plan.stream().noneMatch(line->line.contains("TEMP B-TREE")),plan.toString());
            }return null;});
        }
    }

    @Test void lookaheadAndUnrelatedScopesDoNotDecodeOutsideTheRequestedPage() {
        try(var store=new SqliteStore(directory.resolve("decode.db"))) {
            var engine=engine(store);for(int n=0;n<3;n++)add(engine,"e"+n,n,Set.of(),n==0?Set.of("south"):Set.of("north"));
            store.transaction(c->{try(var s=c.createStatement()){s.execute("UPDATE events SET json='{}' WHERE id='e0'");}return null;});
            var page=engine.historyPage(null,null,null,2);assertEquals(List.of("e2","e1"),ids(page.events()));assertEquals("e1",page.nextCursor());
            assertThrows(IllegalStateException.class,()->engine.historyPage(null,null,page.nextCursor(),2));
            assertEquals(List.of("e2","e1"),ids(pages(engine,"north",null,1)));
        }
    }
    static List<String> rows(SqliteStore store){return store.transaction(c->{List<String> rows=new ArrayList<>();try(var s=c.createStatement();var r=s.executeQuery("SELECT id,type,time,hash,json FROM events ORDER BY id")){while(r.next())for(int i=1;i<=5;i++)rows.add(r.getString(i));}return rows;});}
}
