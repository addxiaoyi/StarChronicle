package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition.*;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real file SQLite transactions, scoped native-authority fixture and restart/concurrency
 * contracts. Only instances/source evidence are seeded; country ballot/log/freeze rows are
 * created through the production ledger API except explicit corruption/failure tests. */
class NationalVoteLedgerTest {
    @TempDir Path directory;
    private static final UUID A=UUID.fromString("00000000-0000-4000-8000-000000000061"),SUCCESSOR=UUID.fromString("00000000-0000-4000-8000-000000000062"),
        B=UUID.fromString("00000000-0000-4000-8000-000000000063"),C=UUID.fromString("00000000-0000-4000-8000-000000000064"),D=UUID.fromString("00000000-0000-4000-8000-000000000065");
    private static final String RIVER="aaaaaaaa-1111-4111-8111-111111111111",HILL="bbbbbbbb-2222-4222-8222-222222222222",COAST="cccccccc-3333-4333-8333-333333333333",FOREIGN="dddddddd-4444-4444-8444-444444444444";
    private static final Set<String> SOURCE_NATIONS=Set.of(RIVER,HILL,COAST);
    private static final Instant NOW=Instant.parse("2026-11-20T12:34:56.123456789Z");
    private SqliteStore store;private TestAuthority authority;
    @BeforeEach void setup(){store=new SqliteStore(path());authority=new TestAuthority();}
    @AfterEach void close(){if(store!=null)store.close();}
    private Path path(){return directory.resolve("national-ledger.db");}
    private PlayerContext actor(UUID id,String nation){return new PlayerContext(id,nation,Map.of("player.role","monarch","player.rank","Founder"),Set.of("starchronicle.use"));}
    private PlayerContext actor(UUID id){return actor(id,id.equals(B)?HILL:id.equals(C)?COAST:id.equals(D)?FOREIGN:RIVER);}
    private StoryInstance seed(String id,int quorum){return seed(id,quorum,Scope.WORLD,SOURCE_NATIONS,false,VoteUnit.NATION);}
    private StoryInstance seed(String id,int quorum,Scope scope,Set<String> nations,boolean global,VoteUnit unit) {
        Map<UUID,String> members=scope==Scope.NATION?Map.of(A,RIVER,SUCCESSOR,RIVER):Map.of(A,RIVER,SUCCESSOR,RIVER,B,HILL,C,COAST,D,FOREIGN);
        var definition=new StoryDefinition("national-council","2","A real national council",scope,List.of("ROOT"),null,"council",0,
            Map.of("council",new Node("Choose",null,null,quorum,Map.of("yes",new Choice("Support","done",null,null,List.of()),"no",new Choice("Oppose","done",null,null,List.of())),List.of(),false),"done",new Node("Complete",null,null,1,Map.of(),List.of(),true)),unit);
        String scopeKey=scope==Scope.NATION?"nation:"+RIVER:scope==Scope.PLAYER?"player:"+A:"world";
        StoryInstance before=new StoryInstance(id,definition,scopeKey,"source:"+id,"council",members,Map.of(),Map.of(),NOW,NOW,false);
        WorldEvent source=new WorldEvent(before.sourceEventId(),"ROOT",NOW,members.keySet(),nations,global?Map.of("audience","GLOBAL"):Map.of(),"NationalVoteLedgerTest","Committed source operation="+id);
        store.transaction(c->{try(var s=c.prepareStatement("INSERT INTO events VALUES(?,?,?,?,?)")){s.setString(1,source.id());s.setString(2,source.type());s.setString(3,NOW.toString());s.setString(4,eventHash(source));s.setString(5,SqliteStore.json(source));s.executeUpdate();}insert(c,before);return null;});return before;
    }
    private static String eventHash(WorldEvent e){Map<String,Object> values=new TreeMap<>();values.put("id",e.id());values.put("type",e.type());values.put("time",e.occurredAt().toString());values.put("players",e.participants().stream().map(UUID::toString).sorted().toList());values.put("nations",new TreeSet<>(e.nations()));values.put("facts",new TreeMap<>(e.facts()));values.put("source",e.source());values.put("evidence",e.evidence());return RuleEngine.stable(SqliteStore.json(values));}
    private static void insert(Connection c,StoryInstance value)throws SQLException {try(var s=c.prepareStatement("INSERT INTO instances VALUES(?,?,?,?,?,?)")){s.setString(1,value.id());s.setString(2,value.definition().id());s.setString(3,value.scopeKey());s.setInt(4,value.completed()?1:0);s.setString(5,value.updatedAt().toString());s.setString(6,SqliteStore.json(value));s.executeUpdate();}}
    private void replace(StoryInstance value){store.transaction(c->{try(var s=c.prepareStatement("UPDATE instances SET completed=?,updated=?,json=? WHERE id=?")){s.setInt(1,value.completed()?1:0);s.setString(2,value.updatedAt().toString());s.setString(3,SqliteStore.json(value));s.setString(4,value.id());assertEquals(1,s.executeUpdate());}return null;});}
    private StoryInstance completed(StoryInstance value){var done=new StoryInstance(value.id(),value.definition(),value.scopeKey(),value.sourceEventId(),"done",value.members(),Map.of(),Map.of("council","yes"),value.startedAt(),NOW.plusSeconds(1),true);replace(done);return done;}
    private NationalVoteLedger.Mutation cast(StoryInstance before,UUID who,String choice,long revision)throws Exception {return cast(before,actor(who),choice,revision);}
    private NationalVoteLedger.Mutation cast(StoryInstance before,PlayerContext actor,String choice,long revision)throws Exception {return authority.withRepresentative(actor.playerId(),actor.nationId(),view->store.transaction(c->NationalVoteLedger.cast(c,before,actor,view,choice,revision,NOW)));}
    private NationalVoteLedger.Mutation withdraw(StoryInstance before,UUID who,long revision)throws Exception {var actor=actor(who);return authority.withRepresentative(who,actor.nationId(),view->store.transaction(c->NationalVoteLedger.withdraw(c,before,actor,view,revision,NOW)));}
    private NationalVoteLedger.Snapshot snapshot(StoryInstance before){return store.transaction(c->NationalVoteLedger.snapshot(c,before,"council"));}
    private long count(String table){return store.transaction(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM "+table)){r.next();return r.getLong(1);}});}
    private String scalar(String sql){return store.transaction(c->{try(var s=c.createStatement();var r=s.executeQuery(sql)){return r.next()?r.getString(1):null;}});}
    private Map<String,List<List<String>>> data(){return store.transaction(c->{Map<String,List<List<String>>> all=new TreeMap<>();for(String table:List.of("story_nation_votes","story_nation_vote_log","story_nation_actor_bindings","story_nation_resolutions")){List<List<String>> rows=new ArrayList<>();try(var s=c.createStatement();var r=s.executeQuery("SELECT * FROM "+table+" ORDER BY 1,2,3")){while(r.next()){List<String> row=new ArrayList<>();for(int i=1;i<=r.getMetaData().getColumnCount();i++)row.add(r.getString(i));rows.add(row);}}all.put(table,rows);}return all;});}
    private static boolean causeIs(Throwable error,Throwable expected){for(Throwable at=error;at!=null;at=at.getCause())if(at==expected)return true;return false;}

    @Test void aCountryHasOneSeatAndARealSuccessorCanChangeItWithoutRewritingTheOldHolderAction()throws Exception {
        var before=seed("one-seat",3);var first=cast(before,A,"yes",0);assertTrue(first.changed());assertEquals(Map.of("yes",1L),first.snapshot().counts());assertEquals(1,count("story_nation_votes"));
        var unchanged=data();assertFalse(cast(before,A,"yes",1).changed());assertEquals(unchanged,data());
        assertThrows(SecurityException.class,()->cast(before,SUCCESSOR,"yes",1));authority.transfer(RIVER,SUCCESSOR);
        var changed=cast(before,SUCCESSOR,"no",1);assertEquals(Map.of("no",1L),changed.snapshot().counts());assertEquals(2,changed.snapshot().ballots().getFirst().revision());assertEquals(1,count("story_nation_votes"));assertEquals(2,count("story_nation_vote_log"));
        assertEquals(A.toString(),scalar("SELECT actor_id FROM story_nation_vote_log ORDER BY seq LIMIT 1"));assertEquals(SUCCESSOR.toString(),scalar("SELECT actor_id FROM story_nation_votes"));assertEquals(2,count("story_nation_actor_bindings"));
    }

    @Test void strictRevisionCasRejectsStaleNewVoteRevoteAndWithdrawalWithoutAnyExtraRows()throws Exception {
        var before=seed("cas",3);assertThrows(IllegalStateException.class,()->cast(before,A,"yes",1));assertEquals(0,count("story_nation_actor_bindings"));cast(before,A,"yes",0);var original=data();
        assertThrows(IllegalStateException.class,()->cast(before,A,"no",0));assertThrows(IllegalStateException.class,()->withdraw(before,A,0));assertThrows(IllegalStateException.class,()->cast(before,A,"yes",-1));assertEquals(original,data());
    }

    @Test void withdrawingAndRecastingAppendRevisionsWhileTheTombstoneClearsItsChoiceAndNeverUnbindsTheActor()throws Exception {
        var before=seed("withdraw",3);cast(before,A,"yes",0);var removed=withdraw(before,A,1);assertTrue(removed.changed());var ballot=removed.snapshot().ballots().getFirst();assertEquals("WITHDRAWN",ballot.status());assertNull(ballot.choiceId());assertEquals(2,ballot.revision());assertTrue(removed.snapshot().counts().isEmpty());assertEquals(1,count("story_nation_actor_bindings"));
        var tombstone=data();assertFalse(withdraw(before,A,2).changed());assertEquals(tombstone,data());assertEquals(Map.of("no",1L),cast(before,A,"no",2).snapshot().counts());assertEquals(3,snapshot(before).ballots().getFirst().revision());assertEquals(3,count("story_nation_vote_log"));
    }

    @Test void withdrawingAnAbsentSeatIsANoopButStillRequiresTheNativeMemberAndRevisionZero()throws Exception {
        var before=seed("empty-withdraw",3);assertFalse(withdraw(before,A,0).changed());assertEquals(0,count("story_nation_vote_log"));assertEquals(0,count("story_nation_actor_bindings"));assertThrows(IllegalStateException.class,()->withdraw(before,A,1));
    }

    @Test void membershipAndNativeIdentityCannotBeReplacedByRoleRankOpOrAnOrdinaryPermission()throws Exception {
        var before=seed("authorization",3);var elevated=new PlayerContext(A,RIVER,Map.of("player.role","monarch","player.rank","Founder","player.op","true"),Set.of("*","starchronicle.admin"));
        assertThrows(SecurityException.class,()->cast(before,elevated,"yes",0));assertThrows(SecurityException.class,()->cast(before,SUCCESSOR,"yes",0));
        authority.transfer(FOREIGN,A);assertThrows(SecurityException.class,()->cast(before,actor(A,FOREIGN),"yes",0));assertEquals(0,count("story_nation_vote_log"));
        var outsider=UUID.fromString("00000000-0000-4000-8000-000000000099");authority.transfer(RIVER,outsider);assertThrows(SecurityException.class,()->cast(before,actor(outsider,RIVER),"yes",0));
    }

    @Test void theActorNodeBindingSurvivesWithdrawalAndRejectsCountryHoppingEvenIfAStoredMembershipIsLaterReviewed()throws Exception {
        var before=seed("binding",4);cast(before,A,"yes",0);withdraw(before,A,1);Map<UUID,String> reviewedMembers=new HashMap<>(before.members());reviewedMembers.put(A,HILL);
        var reviewed=new StoryInstance(before.id(),before.definition(),before.scopeKey(),before.sourceEventId(),before.node(),reviewedMembers,before.votes(),before.choices(),before.startedAt(),NOW.plusSeconds(1),false);replace(reviewed);authority.transfer(HILL,A);
        assertThrows(SecurityException.class,()->cast(reviewed,actor(A,HILL),"yes",0));assertEquals(1,count("story_nation_votes"));assertEquals("WITHDRAWN",snapshot(reviewed).ballots().getFirst().status());assertEquals(2,count("story_nation_vote_log"));
    }

    @Test void explicitSourceNationsRestrictSeatsEvenForGlobalBroadcastsAndWitnessMembership()throws Exception {
        var specific=seed("specific",3);assertThrows(SecurityException.class,()->cast(specific,D,"yes",0));
        var globalButRestricted=seed("global-restricted",3,Scope.WORLD,Set.of(RIVER),true,VoteUnit.NATION);assertThrows(SecurityException.class,()->cast(globalButRestricted,B,"yes",0));assertEquals(0,count("story_nation_votes"));
    }

    @Test void onlyExplicitGlobalWithoutSourceNationsOpensCurrentNativeSeatsAndPersonalActorsDoNotInventThem()throws Exception {
        var personalSource=seed("personal-source",3,Scope.WORLD,Set.of(),false,VoteUnit.NATION);assertThrows(SecurityException.class,()->cast(personalSource,A,"yes",0));
        var global=seed("open-global",3,Scope.WORLD,Set.of(),true,VoteUnit.NATION);assertEquals(Map.of("yes",1L),cast(global,D,"yes",0).snapshot().counts());
    }

    @Test void aNativeConfirmedMissingCountryIsInvalidatedByTheNextValidSubmissionAndDoesNotCount()throws Exception {
        var before=seed("invalidation",3);cast(before,A,"yes",0);authority.remove(RIVER);var result=cast(before,B,"no",0);
        var gone=result.snapshot().ballots().stream().filter(v->v.nationId().equals(RIVER)).findFirst().orElseThrow();assertEquals("INVALIDATED",gone.status());assertNull(gone.choiceId());assertEquals(2,gone.revision());assertEquals(B,gone.actorId(),"the log attributes the actual checker, never impersonates the vanished country's representative");
        assertEquals(Map.of("no",1L),result.snapshot().counts());assertEquals(3,count("story_nation_vote_log"));assertTrue(gone.authorityEvidence().contains(HILL));var recorded=data();authority.remove(HILL);assertEquals(Map.of("no",1L),snapshot(before).counts());assertEquals(recorded,data(),"historical snapshots never run a fresh native invalidation");
    }

    @Test void aNativeReadExceptionNeverMeansDissolutionAndRollsBackAllProjectionsAndLogs()throws Exception {
        var before=seed("native-error",4);cast(before,A,"yes",0);cast(before,C,"no",0);authority.remove(RIVER);IOException unavailable=new IOException("native authority storage unavailable");authority.failNation=COAST;authority.readFailure=unavailable;var original=data();
        var error=assertThrows(IllegalStateException.class,()->cast(before,B,"no",0));assertTrue(causeIs(error,unavailable));assertEquals(original,data());assertEquals("ACTIVE",snapshot(before).ballots().getFirst().status());
    }

    @Test void reachingQuorumFreezesOneSortedUniqueNationalRevisionSetWithTheLegacyCollectiveResolutionId()throws Exception {
        var before=seed("freeze",2);cast(before,B,"yes",0);var result=cast(before,A,"yes",0);var frozen=result.resolution().orElseThrow();
        assertEquals(RuleEngine.stable("choice-log",before.id(),"council","RESOLUTION","world","1"),frozen.id());assertEquals(2,frozen.quorum());assertEquals(List.of(RIVER,HILL),frozen.counted().stream().map(NationalVoteLedger.CountedBallot::nationId).toList());assertEquals(A,frozen.actorId());assertEquals(NOW,frozen.checkedAt());
        assertEquals(List.of(1L,1L),frozen.counted().stream().map(NationalVoteLedger.CountedBallot::revision).toList());store.transaction(c->{NationalVoteLedger.verifyResolution(c,before,frozen);return null;});
        assertEquals(1,count("story_nation_resolutions"));assertEquals(0,count("story_choice_log"));assertEquals(0,count("outcomes"));assertEquals(before,store.transaction(c->{try(var s=c.prepareStatement("SELECT json FROM instances WHERE id=?")){s.setString(1,before.id());try(var r=s.executeQuery()){r.next();return SqliteStore.decode(r.getString(1),StoryInstance.class);}}}));
    }

    @Test void frozenAndCompletedNodesRejectChangesAndInvalidationEvenAfterFutureCountryDisappearance()throws Exception {
        var before=seed("immutable",2);cast(before,A,"yes",0);var frozen=cast(before,B,"yes",0).resolution().orElseThrow();var original=data();authority.remove(RIVER);
        assertThrows(IllegalStateException.class,()->cast(before,C,"no",0));assertThrows(IllegalStateException.class,()->withdraw(before,B,1));assertEquals(original,data());assertEquals(frozen,snapshot(before).resolution().orElseThrow());
        var done=completed(before);assertThrows(IllegalStateException.class,()->cast(done,C,"yes",0));assertEquals(original,data());store.transaction(c->{NationalVoteLedger.verifyResolution(c,done,frozen);return null;});
    }

    @Test void aNationScopedCampaignHasOnlyItsOneCountrySeatAndCannotBecomeAWorldOrPlayerVote()throws Exception {
        var before=seed("national-scope",1,Scope.NATION,Set.of(RIVER),false,VoteUnit.NATION);var frozen=cast(before,A,"yes",0).resolution().orElseThrow();assertEquals(1,frozen.counted().size());assertEquals(RIVER,frozen.counted().getFirst().nationId());assertEquals(RuleEngine.stable("choice-log",before.id(),"council","RESOLUTION","nation:"+RIVER,"1"),frozen.id());
        var individual=seed("wrong-unit",3,Scope.WORLD,SOURCE_NATIONS,false,VoteUnit.PLAYER);assertThrows(IllegalStateException.class,()->cast(individual,A,"yes",0));var playerScope=seed("wrong-scope",1,Scope.PLAYER,Set.of(RIVER),false,VoteUnit.NATION);assertThrows(IllegalStateException.class,()->cast(playerScope,A,"yes",0));
    }

    @Test void exactStoredBeforeNodeAndAuthorityIdentityAreRequiredBeforeAnyWrite()throws Exception {
        var before=seed("stale",3);var stale=new StoryInstance(before.id(),before.definition(),before.scopeKey(),before.sourceEventId(),before.node(),before.members(),Map.of(),Map.of(),before.startedAt(),NOW.plusSeconds(3),false);assertThrows(IllegalStateException.class,()->cast(stale,A,"yes",0));
        authority.withRepresentative(B,HILL,view->{assertThrows(SecurityException.class,()->store.transaction(c->NationalVoteLedger.cast(c,before,actor(A),view,"yes",0,NOW)));return null;});
        var escaped=authority.withRepresentative(A,RIVER,view->view);assertThrows(IllegalStateException.class,()->store.transaction(c->NationalVoteLedger.cast(c,before,actor(A),escaped,"yes",0,NOW)));assertEquals(0,count("story_nation_votes"));
        assertThrows(IllegalArgumentException.class,()->cast(before,A,"missing",0));assertThrows(IllegalArgumentException.class,()->store.transaction(c->NationalVoteLedger.snapshot(c,before,"missing")));
    }

    @Test void aProjectionInsertFailureRollsBackEarlierInvalidationsBindingsLogsAndTheSequence()throws Exception {
        var before=seed("projection-fault",3);cast(before,A,"yes",0);var original=data();String seq=scalar("SELECT seq FROM sqlite_sequence WHERE name='story_nation_vote_log'");authority.remove(RIVER);
        store.transaction(c->{try(var s=c.createStatement()){s.execute("CREATE TRIGGER fail_hill_projection BEFORE INSERT ON story_nation_votes WHEN NEW.nation_id='"+HILL+"' BEGIN SELECT RAISE(ABORT,'projection failure'); END");}return null;});
        assertThrows(IllegalStateException.class,()->cast(before,B,"no",0));assertEquals(original,data());assertEquals(seq,scalar("SELECT seq FROM sqlite_sequence WHERE name='story_nation_vote_log'"));assertEquals("ACTIVE",snapshot(before).ballots().getFirst().status());
    }

    @Test void freezeFailureAndCallerFailureRollBackTheWholeNationalTransition()throws Exception {
        var before=seed("freeze-fault",2);cast(before,A,"yes",0);var original=data();store.transaction(c->{try(var s=c.createStatement()){s.execute("CREATE TRIGGER fail_national_resolution BEFORE INSERT ON story_nation_resolutions BEGIN SELECT RAISE(ABORT,'freeze failure'); END");}return null;});
        assertThrows(IllegalStateException.class,()->cast(before,B,"yes",0));assertEquals(original,data());store.transaction(c->{try(var s=c.createStatement()){s.execute("DROP TRIGGER fail_national_resolution");}return null;});
        assertThrows(IllegalStateException.class,()->authority.withRepresentative(B,HILL,view->store.transaction(c->{NationalVoteLedger.cast(c,before,actor(B),view,"yes",0,NOW);throw new IllegalStateException("later shared-resolution/effect failure");})));assertEquals(original,data());
    }

    @Test void concurrentNationalCallsSerializeToExactlyOneFrozenDecisionAndNoPersonalBallots()throws Exception {
        var before=seed("concurrent",2);try(var executor=Executors.newFixedThreadPool(6)){List<Callable<Boolean>> calls=new ArrayList<>();for(int i=0;i<45;i++){UUID player=List.of(A,B,C).get(i%3);calls.add(()->{try{return cast(before,player,"yes",0).changed();}catch(IllegalStateException staleOrFrozen){return false;}});}for(var result:executor.invokeAll(calls))result.get(5,TimeUnit.SECONDS);}
        assertEquals(2,count("story_nation_votes"));assertEquals(2,count("story_nation_vote_log"));assertEquals(1,count("story_nation_resolutions"));assertEquals(2,snapshot(before).resolution().orElseThrow().counted().size());assertEquals(0,count("story_choice_log"));
    }

    @Test void pendingRevisionAndBindingThenFrozenProofSurviveTwoRealStoreReopens()throws Exception {
        var before=seed("restart",3);cast(before,A,"yes",0);withdraw(before,A,1);var first=data();store.close();store=new SqliteStore(path());assertEquals(first,data());assertFalse(withdraw(before,A,2).changed());
        cast(before,A,"yes",2);cast(before,B,"yes",0);var frozen=cast(before,C,"yes",0).resolution().orElseThrow();assertEquals(3,frozen.counted().stream().filter(v->v.nationId().equals(RIVER)).findFirst().orElseThrow().revision());var second=data();var done=completed(before);store.close();store=new SqliteStore(path());assertEquals(second,data());assertEquals(frozen,snapshot(done).resolution().orElseThrow());
        store.transaction(c->{NationalVoteLedger.verifyResolution(c,done,frozen);assertEquals(Optional.of("yes"),NationalVoteLedger.readHistorical(c,RIVER,"national-council","2","council"));assertTrue(NationalVoteLedger.readHistorical(c,RIVER,"national-council","1","council").isEmpty());try(var s=c.createStatement();var r=s.executeQuery("PRAGMA foreign_key_check")){assertFalse(r.next());}return null;});
    }

    @Test void frozenProofVerificationRejectsDuplicateCountriesOrAChangedProjectionRevision()throws Exception {
        var before=seed("evidence",2);cast(before,A,"yes",0);var frozen=cast(before,B,"yes",0).resolution().orElseThrow();var duplicate=new NationalVoteLedger.Resolution(frozen.id(),frozen.instanceId(),frozen.nodeId(),frozen.choiceId(),frozen.quorum(),frozen.actorId(),frozen.checkedAt(),List.of(frozen.counted().getFirst(),frozen.counted().getFirst()),frozen.authorityEvidence());
        assertThrows(IllegalStateException.class,()->store.transaction(c->{NationalVoteLedger.verifyResolution(c,before,duplicate);return null;}));store.transaction(c->{try(var s=c.createStatement()){s.execute("UPDATE story_nation_votes SET revision=revision+1 WHERE nation_id='"+RIVER+"'");}return null;});
        assertThrows(IllegalStateException.class,()->store.transaction(c->{NationalVoteLedger.verifyResolution(c,before,frozen);return null;}));
    }

    @Test void snapshotCannotDisplayCountsThatDifferFromFrozenProofEvenIfProjectionAndLogAreTamperedTogether()throws Exception {
        var before=seed("snapshot-proof",2);cast(before,A,"yes",0);var frozen=cast(before,B,"yes",0).resolution().orElseThrow();
        store.transaction(c->{try(var s=c.createStatement()){s.execute("UPDATE story_nation_votes SET choice_id='no' WHERE nation_id='"+RIVER+"'");s.execute("UPDATE story_nation_vote_log SET choice_id='no' WHERE nation_id='"+RIVER+"'");}return null;});
        assertEquals(frozen,store.transaction(c->NationalVoteLedger.resolution(c,before,"council")).orElseThrow(),"frozen evidence itself stays immutable");
        assertThrows(IllegalStateException.class,()->snapshot(before),"presentation must verify counted revisions against frozen evidence rather than showing divergent counts");
    }

    @Test void everyLedgerEntryRequiresAnExplicitEnclosingSqlTransaction()throws Exception {
        var before=seed("transaction",3);try(var c=DriverManager.getConnection("jdbc:sqlite:"+path())) {assertTrue(c.getAutoCommit());assertThrows(IllegalStateException.class,()->NationalVoteLedger.createSchema(c));assertThrows(IllegalStateException.class,()->NationalVoteLedger.snapshot(c,before,"council"));assertThrows(IllegalStateException.class,()->NationalVoteLedger.resolution(c,before,"council"));assertThrows(IllegalStateException.class,()->NationalVoteLedger.readHistorical(c,RIVER,"national-council","2","council"));authority.withRepresentative(A,RIVER,view->{assertThrows(IllegalStateException.class,()->NationalVoteLedger.cast(c,before,actor(A),view,"yes",0,NOW));assertThrows(IllegalStateException.class,()->NationalVoteLedger.withdraw(c,before,actor(A),view,0,NOW));return null;});}
    }

    /** Test-only committed authority with the same synchronous scoped View contract as the
     * native bridge. It deliberately has no Player/facts/rank/permission-based credential. */
    private static final class TestAuthority implements NationalVoteAuthority {
        final Object lock=new Object();final Map<UUID,String> memberships=new HashMap<>();final Map<String,UUID> holders=new HashMap<>();final Set<String> nations=new HashSet<>();String failNation;Exception readFailure;
        TestAuthority(){memberships.putAll(Map.of(A,RIVER,SUCCESSOR,RIVER,B,HILL,C,COAST,D,FOREIGN));holders.putAll(Map.of(RIVER,A,HILL,B,COAST,C,FOREIGN,D));nations.addAll(List.of(RIVER,HILL,COAST,FOREIGN));}
        void transfer(String nation,UUID who){synchronized(lock){holders.put(nation,who);memberships.put(who,nation);}}
        void remove(String nation){synchronized(lock){nations.remove(nation);}}
        @Override public <T>T withRepresentative(UUID actor,String nation,Authorized<T> operation)throws Exception {
            synchronized(lock){if(!nation.equals(memberships.get(actor))||!actor.equals(holders.get(nation))||!nations.contains(nation))throw new SecurityException("Not the current test authority holder");Thread owner=Thread.currentThread();AtomicBoolean active=new AtomicBoolean(true);
                View view=new View(){void check(){if(!active.get()||Thread.currentThread()!=owner||!Thread.holdsLock(lock))throw new IllegalStateException("Expired test native authority view");}
                    public UUID actorId(){check();return actor;}public String nationId(){check();return nation;}public Instant checkedAt(){check();return NOW;}public String issuer(){check();return "NationalVoteLedgerTest.native-holder";}public String evidence(){check();return "committed membership/holder="+actor+"; nation="+nation;}
                    public boolean nationExists(String id)throws Exception {check();if(id.equals(failNation))throw readFailure;return nations.contains(id);}};
                try{return operation.run(view);}finally{active.set(false);}
            }
        }
    }
}
