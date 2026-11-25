package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition.*;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Protocol defenses at the trusted authority extension boundary. A closed real SQLite
 * connection is an intentional tripwire: a rejected callback must fail before any SQL,
 * rather than merely rolling back a statement that should never have been attempted. */
class NationalRuleEngineAuthorityBoundaryTest {
    @TempDir Path directory;
    static final UUID PLAYER=UUID.fromString("00000000-0000-4000-8000-000000000071"),OTHER=UUID.fromString("00000000-0000-4000-8000-000000000072");
    static final String NATION="aaaaaaaa-1111-4111-8111-111111111111",OTHER_NATION="bbbbbbbb-2222-4222-8222-222222222222";
    static final Instant NOW=Instant.parse("2026-11-20T16:17:18Z");
    static final PlayerContext CONTEXT=new PlayerContext(PLAYER,NATION,Map.of(),Set.of("starchronicle.use"));
    SqliteStore store;RuleEngine engine;BoundaryAuthority authority;String instanceId;boolean closed;

    @AfterEach void close(){if(store!=null&&!closed)store.close();}

    @Test void stateNullViewIsACallbackFailureAndCannotDowngradeToReadonly()throws Exception {
        open(Mode.NULL_VIEW,2);var before=databaseState();
        NullPointerException failure=assertThrows(NullPointerException.class,()->engine.nationalState(instanceId,CONTEXT));
        assertEquals("Missing national authority view",failure.getMessage());assertEquals(1,authority.calls.get());
        assertEquals(before,databaseState());
    }

    @Test void stateWrongActorIdentityIsNotConvertedIntoAReadonlyDenial()throws Exception {
        open(Mode.WRONG_ACTOR,2);var before=databaseState();
        SecurityException failure=assertThrows(SecurityException.class,()->engine.nationalState(instanceId,CONTEXT));
        assertEquals("National authority identity mismatch",failure.getMessage());assertEquals(1,authority.calls.get());
        assertEquals(before,databaseState());
    }

    @Test void stateWrongNationIdentityIsNotConvertedIntoAReadonlyDenial()throws Exception {
        open(Mode.WRONG_NATION,2);var before=databaseState();
        SecurityException failure=assertThrows(SecurityException.class,()->engine.nationalState(instanceId,CONTEXT));
        assertEquals("National authority identity mismatch",failure.getMessage());assertEquals(1,authority.calls.get());
        assertEquals(before,databaseState());
    }

    @Test void stateNullViewFailureCannotBeHiddenByAProviderSwallowingItsCallbackException()throws Exception {
        open(Mode.SWALLOW_NULL_VIEW,2);var before=databaseState();
        IllegalStateException failure=assertThrows(IllegalStateException.class,()->engine.nationalState(instanceId,CONTEXT));
        assertEquals("National authority suppressed a failed callback",failure.getMessage());
        assertInstanceOf(NullPointerException.class,failure.getCause());assertSame(authority.swallowed.get(),failure.getCause());
        assertEquals(before,databaseState());
    }

    @Test void crossThreadCallbackIsRejectedBeforeViewAccessOrSqlEvenIfTheProviderWaitsForIt()throws Exception {
        open(Mode.CROSS_THREAD,2);var before=databaseState();closeSqlTripwire();
        SecurityException failure=assertThrows(SecurityException.class,()->engine.nationalVote(instanceId,PLAYER,"vote",0,"yes",CONTEXT));
        assertTrue(failure.getMessage().contains("synchronous, single-use and on its calling thread"));
        assertEquals(0,authority.viewReads.get(),"protocol rejection must precede even the first identity getter");
        assertNotEquals(Thread.currentThread(),authority.callbackThread.get());assertEquals(before,databaseState());
    }

    @Test void asynchronousCallbackAfterProviderReturnCannotReachTheClosedSqlConnection()throws Exception {
        open(Mode.DEFERRED,2);var before=databaseState();
        SecurityException failure=assertThrows(SecurityException.class,()->engine.nationalVote(instanceId,PLAYER,"vote",0,"yes",CONTEXT));
        assertEquals("National authority did not provide a representative callback",failure.getMessage());
        assertNotNull(authority.saved.get());assertEquals(0,authority.viewReads.get());closeSqlTripwire();
        try(var pool=Executors.newSingleThreadExecutor()) {
            ExecutionException rejected=assertThrows(ExecutionException.class,()->pool.submit(()->authority.invokeSaved()).get(3,TimeUnit.SECONDS));
            assertInstanceOf(SecurityException.class,rejected.getCause());
            assertTrue(rejected.getCause().getMessage().contains("synchronous, single-use"));
        }
        assertEquals(0,authority.viewReads.get());assertEquals(before,databaseState());
    }

    @Test void aPreviouslySuccessfulCallbackCannotBeReusedAfterItsAuthorityScopeExpires()throws Exception {
        open(Mode.NORMAL,2);engine.nationalVote(instanceId,PLAYER,"vote",0,"yes",CONTEXT);
        assertEquals(1,number("story_nation_vote_log"));var before=databaseState();int reads=authority.viewReads.get();
        assertTrue(reads>0);closeSqlTripwire();
        SecurityException failure=assertThrows(SecurityException.class,()->authority.invokeSaved());
        assertTrue(failure.getMessage().contains("synchronous, single-use"));
        assertEquals(reads,authority.viewReads.get(),"expired callback must not consult its formerly valid native view");
        assertEquals(before,databaseState());
    }

    @Test void swallowedRealSqlOperationFailureStillThrowsAndRollsBackAllNationalEvidence()throws Exception {
        open(Mode.SWALLOW_OPERATION_FAILURE,1);
        store.transaction(connection->{
            try(var statement=connection.createStatement()) {
                statement.execute("CREATE TRIGGER reject_authority_boundary_resolution BEFORE INSERT ON story_choice_log WHEN NEW.kind='RESOLUTION' BEGIN SELECT RAISE(ABORT,'forced authority boundary operation failure'); END");
            }
            return null;
        });
        var before=databaseState();
        IllegalStateException failure=assertThrows(IllegalStateException.class,()->engine.nationalVote(instanceId,PLAYER,"vote",0,"yes",CONTEXT));
        assertEquals("National authority suppressed a failed callback",failure.getMessage());
        assertNotNull(authority.swallowed.get());assertSame(authority.swallowed.get(),failure.getCause());
        assertInstanceOf(IllegalStateException.class,failure.getCause());
        assertEquals(before,databaseState());assertEquals(0,number("story_nation_vote_log"));
        assertEquals(0,number("story_nation_votes"));assertEquals(0,number("story_nation_resolutions"));
        assertFalse(engine.instances(PLAYER).getFirst().completed());
    }

    @Test void genuinePreCallbackDenialStillAllowsOnlyTheExistingReadonlyState()throws Exception {
        open(Mode.DENY,2);var before=databaseState();
        var state=engine.nationalState(instanceId,CONTEXT);
        assertFalse(state.representative());assertFalse(state.canVote());assertFalse(state.canWithdraw());
        assertTrue(state.ballots().isEmpty());assertEquals(0,authority.viewReads.get());assertEquals(before,databaseState());
        assertThrows(SecurityException.class,()->engine.nationalVote(instanceId,PLAYER,"vote",0,"yes",CONTEXT));
        assertEquals(before,databaseState());
    }

    private void open(Mode mode,int quorum) {
        store=new SqliteStore(database());authority=new BoundaryAuthority(mode);
        var definition=new StoryDefinition("authority-boundary","1","Authority boundary",Scope.WORLD,List.of("ROOT"),null,"vote",0,Map.of(
                "vote",new Node("Council",null,null,quorum,Map.of("yes",new Choice("Support","end",null,null,List.of())),List.of(),false),
                "end",new Node("End",null,null,1,Map.of(),List.of(new Effect("honor","honor","player","3",null)),true)),VoteUnit.NATION);
        engine=new RuleEngine(store,List.of(definition),Clock.fixed(NOW,ZoneOffset.UTC),null,authority);
        var source=new WorldEvent("authority-boundary:source","ROOT",NOW,Set.of(PLAYER),Set.of(NATION),Map.of(),"NationalRuleEngineAuthorityBoundaryTest","Test-only committed native electorate");
        instanceId=engine.accept(source,List.of(CONTEXT)).instanceIds().getFirst();
    }
    private Path database(){return directory.resolve("authority-boundary.db");}
    private void closeSqlTripwire(){store.close();closed=true;}
    private long number(String table)throws Exception {
        try(var connection=DriverManager.getConnection("jdbc:sqlite:"+database().toAbsolutePath());var statement=connection.createStatement();var rows=statement.executeQuery("SELECT COUNT(*) FROM "+table)) {rows.next();return rows.getLong(1);}
    }
    /** Independent actual readback remains available after closing the engine's connection. */
    private Map<String,List<String>> databaseState()throws Exception {
        Map<String,List<String>> result=new TreeMap<>();
        try(var connection=DriverManager.getConnection("jdbc:sqlite:"+database().toAbsolutePath())) {
            try(var statement=connection.createStatement()){statement.execute("PRAGMA query_only=ON");}
            for(String table:List.of("instances","facts","outcomes","story_choice_log","story_nation_vote_log","story_nation_votes","story_nation_actor_bindings","story_nation_resolutions")) {
                List<String> values=new ArrayList<>();
                try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT * FROM "+table)) {
                    var columns=rows.getMetaData();
                    while(rows.next()) {
                        List<String> row=new ArrayList<>();for(int index=1;index<=columns.getColumnCount();index++)row.add(columns.getColumnName(index)+"="+Objects.toString(rows.getString(index),"<NULL>"));
                        values.add(row.toString());
                    }
                }
                Collections.sort(values);result.put(table,List.copyOf(values));
            }
        }
        return Collections.unmodifiableMap(result);
    }
    private enum Mode { NORMAL,NULL_VIEW,WRONG_ACTOR,WRONG_NATION,SWALLOW_NULL_VIEW,CROSS_THREAD,DEFERRED,SWALLOW_OPERATION_FAILURE,DENY }
    private final class BoundaryAuthority implements NationalVoteAuthority {
        final Mode mode;final AtomicInteger calls=new AtomicInteger(),viewReads=new AtomicInteger();
        final AtomicReference<Authorized<?>> saved=new AtomicReference<>();final AtomicReference<Throwable> swallowed=new AtomicReference<>();
        final AtomicReference<Thread> callbackThread=new AtomicReference<>();
        BoundaryAuthority(Mode mode){this.mode=mode;}
        @Override public <T>T withRepresentative(UUID actor,String nation,Authorized<T> operation)throws Exception {
            calls.incrementAndGet();assertFalse(Thread.holdsLock(store),"native authority must precede storage");saved.set(operation);
            if(mode==Mode.DENY)throw new SecurityException("Actual native holder denied before callback");
            if(mode==Mode.DEFERRED)return null;
            if(mode==Mode.CROSS_THREAD) {
                try(var pool=Executors.newSingleThreadExecutor()) {
                    try {return pool.submit(()->{callbackThread.set(Thread.currentThread());return operation.run(view());}).get(3,TimeUnit.SECONDS);}
                    catch(ExecutionException failure){if(failure.getCause() instanceof Exception cause)throw cause;if(failure.getCause() instanceof Error cause)throw cause;throw new AssertionError(failure.getCause());}
                }
            }
            try {return operation.run(mode==Mode.NULL_VIEW||mode==Mode.SWALLOW_NULL_VIEW?null:view());}
            catch(Exception|Error failure) {
                if(mode!=Mode.SWALLOW_OPERATION_FAILURE&&mode!=Mode.SWALLOW_NULL_VIEW)throw failure;
                swallowed.set(failure);return null;
            }
        }
        Object invokeSaved()throws Exception{return Objects.requireNonNull(saved.get()).run(view());}
        View view(){return new View(){
            public UUID actorId(){viewReads.incrementAndGet();return mode==Mode.WRONG_ACTOR?OTHER:PLAYER;}
            public String nationId(){viewReads.incrementAndGet();return mode==Mode.WRONG_NATION?OTHER_NATION:NATION;}
            public Instant checkedAt(){viewReads.incrementAndGet();return NOW;}
            public String issuer(){viewReads.incrementAndGet();return "authority-boundary-test";}
            public String evidence(){viewReads.incrementAndGet();return "Test-only live holder contract";}
            public boolean nationExists(String id){viewReads.incrementAndGet();return NATION.equals(id);}
        };}
    }
}
