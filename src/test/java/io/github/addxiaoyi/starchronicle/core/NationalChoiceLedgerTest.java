package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.config.*;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition.*;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real file-backed national history and shared-resolution boundaries. National actions
 * enter through the real ledger API; tests never fabricate its tables or frozen proof. */
class NationalChoiceLedgerTest {
    @TempDir Path directory;
    final ChoiceLedgerTest fixture=new ChoiceLedgerTest(){
        @Override void seed(Connection connection,StoryInstance instance)throws SQLException {
            super.seed(connection,instance);
            var source=new WorldEvent(instance.sourceEventId(),"TEST",now,instance.members().keySet(),Set.copyOf(instance.members().values()),Map.of(),"NationalChoiceLedgerTest","Test-only canonical national electorate");
            try(var statement=connection.prepareStatement("UPDATE events SET hash=?,json=? WHERE id=?")) {
                statement.setString(1,RuleEngine.stable(source.id()));statement.setString(2,SqliteStore.json(source));statement.setString(3,source.id());statement.executeUpdate();
            }
        }
    };
    static final String RIVER="aaaaaaaa-1111-4111-8111-111111111111",HILL="bbbbbbbb-2222-4222-8222-222222222222",COAST="cccccccc-3333-4333-8333-333333333333";
    static final String NATIONAL="past.nation.choice.heir/1/council",PERSONAL="past.player.choice.heir/1/council",WORLD="past.resolution.world.heir/1/council",NATION="past.resolution.nation.heir/1/council";
    final Set<String> allNations=Set.of(RIVER,HILL,COAST);

    @Test void nationalActionsCannotEnterEitherLegacyPlayerWritePathOrSnapshotMigration()throws Exception {
        try(var connection=fresh()) {
            var before=instance("national-no-personal",Scope.WORLD,1,"1");fixture.seed(connection,before);
            var actor=actor(before,fixture.a);
            assertThrows(IllegalStateException.class,()->ChoiceLedger.recordBallot(connection,before,actor,"council","yes",fixture.now));
            assertThrows(IllegalStateException.class,()->ChoiceLedger.recordResolution(connection,before,actor,"council","yes",fixture.now));
            fixture.save(connection,fixture.state(before,Map.of(),Map.of("council","yes","council/"+fixture.a,"yes"),true,fixture.now));
            assertEquals(0,ChoiceLedger.migrateSnapshots(connection));
            assertEquals(0,fixture.count(connection,""));
            assertTrue(ChoiceLedger.read(connection,fixture.a,RIVER,Set.of(PERSONAL,WORLD,NATIONAL)).isEmpty());
        }
    }

    @Test void aFrozenNationalQuorumWritesOneSharedResolutionAndNoPersonalBallotAcrossReopen()throws Exception {
        try(var connection=fresh()) {
            var before=instance("national-shared",Scope.WORLD,2,"1");fixture.seed(connection,before);
            cast(connection,before,fixture.a,"yes",0,fixture.now,allNations);
            assertThrows(IllegalStateException.class,()->ChoiceLedger.recordNationalResolution(connection,before,actor(before,fixture.a),"council","yes",fixture.now));
            cast(connection,before,fixture.b,"yes",0,fixture.now.plusSeconds(1),allNations);
            assertTrue(ChoiceLedger.recordNationalResolution(connection,before,actor(before,fixture.b),"council","yes",fixture.now.plusSeconds(1)));
            assertFalse(ChoiceLedger.recordNationalResolution(connection,before,actor(before,fixture.b),"council","yes",fixture.now.plusSeconds(2)));
            assertThrows(IllegalStateException.class,()->ChoiceLedger.recordNationalResolution(connection,before,actor(before,fixture.b),"council","no",fixture.now.plusSeconds(2)));
            assertEquals(1,fixture.count(connection," WHERE kind='RESOLUTION'"));assertEquals(0,fixture.count(connection," WHERE kind='BALLOT'"));
            assertTrue(ChoiceLedger.read(connection,fixture.a,RIVER,Set.of(NATIONAL,WORLD)).isEmpty(),"active instances never project past history");
            complete(connection,before,"yes",fixture.now.plusSeconds(3));connection.commit();
        }
        try(var connection=reopen()) {
            assertEquals(Map.of(NATIONAL,"yes",WORLD,"yes"),ChoiceLedger.read(connection,fixture.unvoted,RIVER,Set.of(NATIONAL,PERSONAL,WORLD,NATION)));
            assertEquals(Map.of(NATIONAL,"yes",WORLD,"yes"),ChoiceLedger.read(connection,fixture.a,HILL,Set.of(NATIONAL,PERSONAL,WORLD)));
            assertEquals(Map.of(WORLD,"yes"),ChoiceLedger.read(connection,fixture.a,COAST,Set.of(NATIONAL,PERSONAL,WORLD)),"the nonvoting country gets no national stance");
            assertEquals(Map.of(WORLD,"yes"),ChoiceLedger.read(connection,null,"",Set.of(NATIONAL,PERSONAL,WORLD)));
        }
    }

    @Test void currentRealCountryControlsNationalHistoryIndependentlyOfWhichCitizenQueriesIt()throws Exception {
        try(var connection=fresh()) {
            var before=instance("national-opposition",Scope.WORLD,2,"1");fixture.seed(connection,before);
            cast(connection,before,fixture.a,"no",0,fixture.now,allNations);
            cast(connection,before,fixture.b,"yes",0,fixture.now.plusSeconds(1),allNations);
            cast(connection,before,fixture.c,"yes",0,fixture.now.plusSeconds(2),allNations);
            ChoiceLedger.recordNationalResolution(connection,before,actor(before,fixture.c),"council","yes",fixture.now.plusSeconds(2));
            complete(connection,before,"yes",fixture.now.plusSeconds(3));
            assertEquals(Map.of(NATIONAL,"no",WORLD,"yes"),ChoiceLedger.read(connection,fixture.unvoted,RIVER,Set.of(NATIONAL,PERSONAL,WORLD)));
            assertEquals(Map.of(NATIONAL,"yes",WORLD,"yes"),ChoiceLedger.read(connection,fixture.a,HILL,Set.of(NATIONAL,PERSONAL,WORLD)),"a citizen moving countries reads the current country's stance");
            assertEquals(Map.of(NATIONAL,"yes"),ChoiceLedger.read(connection,fixture.b,COAST,Set.of(NATIONAL)));
        }
    }

    @Test void aWithdrawalInTheNewestCompletedRunSuppressesOlderSupportWithoutFallback()throws Exception {
        try(var connection=fresh()) {
            var old=instance("old-support",Scope.WORLD,1,"1");fixture.seed(connection,old);
            cast(connection,old,fixture.a,"yes",0,fixture.now,allNations);ChoiceLedger.recordNationalResolution(connection,old,actor(old,fixture.a),"council","yes",fixture.now);complete(connection,old,"yes",fixture.now);
            var newer=instance("new-withdraw",Scope.WORLD,2,"1");fixture.seed(connection,newer);
            cast(connection,newer,fixture.a,"yes",0,fixture.now.plusSeconds(10),allNations);
            withdraw(connection,newer,fixture.a,1,fixture.now.plusSeconds(11),allNations);
            cast(connection,newer,fixture.b,"no",0,fixture.now.plusSeconds(12),allNations);
            cast(connection,newer,fixture.c,"no",0,fixture.now.plusSeconds(13),allNations);
            ChoiceLedger.recordNationalResolution(connection,newer,actor(newer,fixture.c),"council","no",fixture.now.plusSeconds(13));complete(connection,newer,"no",fixture.now.plusSeconds(14));
            assertEquals(Map.of(WORLD,"no"),ChoiceLedger.read(connection,fixture.a,RIVER,Set.of(NATIONAL,WORLD,PERSONAL)));
            connection.commit();
        }
        try(var connection=reopen()){assertTrue(ChoiceLedger.read(connection,fixture.unvoted,RIVER,Set.of(NATIONAL,PERSONAL)).isEmpty());}
    }

    @Test void invalidatedNationInNewestCompletedRunSuppressesOldSupportAndRetainsWinningWorldDecision()throws Exception {
        try(var connection=fresh()) {
            var old=instance("old-valid",Scope.WORLD,1,"1");fixture.seed(connection,old);cast(connection,old,fixture.a,"yes",0,fixture.now,allNations);
            ChoiceLedger.recordNationalResolution(connection,old,actor(old,fixture.a),"council","yes",fixture.now);complete(connection,old,"yes",fixture.now);
            var newer=instance("new-invalidated",Scope.WORLD,2,"1");fixture.seed(connection,newer);
            cast(connection,newer,fixture.a,"yes",0,fixture.now.plusSeconds(10),allNations);
            Set<String> survivors=Set.of(HILL,COAST);
            cast(connection,newer,fixture.b,"no",0,fixture.now.plusSeconds(11),survivors);
            cast(connection,newer,fixture.c,"no",0,fixture.now.plusSeconds(12),survivors);
            ChoiceLedger.recordNationalResolution(connection,newer,actor(newer,fixture.c),"council","no",fixture.now.plusSeconds(12));complete(connection,newer,"no",fixture.now.plusSeconds(13));
            assertEquals(Map.of(WORLD,"no"),ChoiceLedger.read(connection,fixture.unvoted,RIVER,Set.of(NATIONAL,WORLD)));
            assertEquals(Map.of(NATIONAL,"no",WORLD,"no"),ChoiceLedger.read(connection,fixture.a,HILL,Set.of(NATIONAL,WORLD)));
        }
    }

    @Test void nationScopedCollectiveResolutionDoesNotBecomeAWorldDecisionOrAPersonalBallot()throws Exception {
        try(var connection=fresh()) {
            var before=instance("one-nation",Scope.NATION,1,"1");fixture.seed(connection,before);
            cast(connection,before,fixture.a,"yes",0,fixture.now,allNations);ChoiceLedger.recordNationalResolution(connection,before,actor(before,fixture.a),"council","yes",fixture.now);complete(connection,before,"yes",fixture.now);
            assertEquals(Map.of(NATIONAL,"yes",NATION,"yes"),ChoiceLedger.read(connection,fixture.unvoted,RIVER,Set.of(NATIONAL,PERSONAL,NATION,WORLD)));
            assertTrue(ChoiceLedger.read(connection,fixture.a,HILL,Set.of(NATIONAL,PERSONAL,NATION,WORLD)).isEmpty());
        }
    }

    @Test void wrongVersionAndUncompletedNewerRunsNeverConcealTheExactCompletedVersion()throws Exception {
        try(var connection=fresh()) {
            var old=instance("exact-old",Scope.WORLD,1,"1");fixture.seed(connection,old);cast(connection,old,fixture.a,"yes",0,fixture.now,allNations);
            ChoiceLedger.recordNationalResolution(connection,old,actor(old,fixture.a),"council","yes",fixture.now);complete(connection,old,"yes",fixture.now);
            var active=instance("active-newer",Scope.WORLD,2,"1");fixture.seed(connection,active);cast(connection,active,fixture.a,"no",0,fixture.now.plusSeconds(10),allNations);withdraw(connection,active,fixture.a,1,fixture.now.plusSeconds(11),allNations);
            var version2=instance("other-version",Scope.WORLD,1,"2");fixture.seed(connection,version2);cast(connection,version2,fixture.a,"no",0,fixture.now.plusSeconds(12),allNations);
            ChoiceLedger.recordNationalResolution(connection,version2,actor(version2,fixture.a),"council","no",fixture.now.plusSeconds(12));complete(connection,version2,"no",fixture.now.plusSeconds(13));
            assertEquals(Map.of(NATIONAL,"yes","past.nation.choice.heir/2/council","no"),ChoiceLedger.read(connection,fixture.a,RIVER,Set.of(NATIONAL,"past.nation.choice.heir/2/council","past.nation.choice.heir/3/council")));
        }
    }

    @Test void frozenNationalProjectionRechecksStoredBeforeCurrentIdentityAndExactFreezeActorTime()throws Exception {
        try(var connection=fresh()) {
            var before=instance("projection-guards",Scope.WORLD,1,"1");fixture.seed(connection,before);cast(connection,before,fixture.a,"yes",0,fixture.now,allNations);
            assertThrows(SecurityException.class,()->ChoiceLedger.recordNationalResolution(connection,before,new PlayerContext(fixture.a,HILL,Map.of(),Set.of("starchronicle.use")),"council","yes",fixture.now));
            assertThrows(IllegalStateException.class,()->ChoiceLedger.recordNationalResolution(connection,before,actor(before,fixture.b),"council","yes",fixture.now));
            assertThrows(IllegalStateException.class,()->ChoiceLedger.recordNationalResolution(connection,before,actor(before,fixture.a),"council","yes",fixture.now.minusSeconds(1)));
            var stale=fixture.state(before,Map.of(),Map.of("council","no"),false,fixture.now.plusSeconds(2));
            assertThrows(IllegalStateException.class,()->ChoiceLedger.recordNationalResolution(connection,stale,actor(before,fixture.a),"council","yes",fixture.now.plusSeconds(3)));
            assertEquals(0,fixture.count(connection,""));
        }
    }

    @Test void nationalResolutionProjectionRequiresAnExplicitChronicleTransaction()throws Exception {
        try(var connection=fresh()) {
            var before=instance("transaction",Scope.WORLD,1,"1");fixture.seed(connection,before);connection.commit();connection.setAutoCommit(true);
            assertThrows(IllegalStateException.class,()->ChoiceLedger.recordNationalResolution(connection,before,actor(before,fixture.a),"council","yes",fixture.now));
            assertThrows(IllegalStateException.class,()->ChoiceLedger.read(connection,fixture.a,RIVER,Set.of(NATIONAL)));
        }
    }

    @Test void playerVotingCannotUseTheNationalProjectionAndCorruptUnitCannotInjectAPersonalPastFact()throws Exception {
        try(var connection=fresh()) {
            var playerInstance=fixture.instance("personal-only",Scope.WORLD,Map.of(fixture.a,RIVER),1,"1");fixture.seed(connection,playerInstance);
            var player=actor(playerInstance,fixture.a);
            assertThrows(IllegalStateException.class,()->ChoiceLedger.recordNationalResolution(connection,playerInstance,player,"council","yes",fixture.now));
            ChoiceLedger.recordBallot(connection,playerInstance,player,"council","yes",fixture.now);
            ChoiceLedger.recordResolution(connection,playerInstance,player,"council","yes",fixture.now);
            var definition=playerInstance.definition();
            var corruptedDefinition=new StoryDefinition(definition.id(),definition.version(),definition.title(),definition.scope(),definition.triggers(),definition.condition(),definition.start(),definition.cooldownSeconds(),definition.nodes(),VoteUnit.NATION);
            var corrupted=new StoryInstance(playerInstance.id(),corruptedDefinition,playerInstance.scopeKey(),playerInstance.sourceEventId(),"done",playerInstance.members(),Map.of(),Map.of("council","yes"),playerInstance.startedAt(),fixture.now,true);
            fixture.save(connection,corrupted);
            assertThrows(IllegalStateException.class,()->ChoiceLedger.read(connection,fixture.a,RIVER,Set.of(PERSONAL)),"a persisted national action cannot be relabeled as a citizen's personal ballot");
        }
    }

    @Test void repeatedFrozenProjectionRejectsACorruptExistingSharedResolutionInsteadOfSilentlyAcceptingIt()throws Exception {
        try(var connection=fresh()) {
            var before=instance("corrupt-shared-proof",Scope.WORLD,1,"1");fixture.seed(connection,before);
            cast(connection,before,fixture.a,"yes",0,fixture.now,allNations);
            assertTrue(ChoiceLedger.recordNationalResolution(connection,before,actor(before,fixture.a),"council","yes",fixture.now));
            try(var statement=connection.prepareStatement("UPDATE story_choice_log SET scope_key=? WHERE instance_id=? AND kind='RESOLUTION'")) {
                statement.setString(1,"nation:"+HILL);statement.setString(2,before.id());assertEquals(1,statement.executeUpdate());
            }
            assertThrows(IllegalStateException.class,()->ChoiceLedger.recordNationalResolution(connection,before,actor(before,fixture.a),"council","yes",fixture.now.plusSeconds(1)));
            assertEquals(1,fixture.count(connection,""));
        }
    }

    @Test void sharedProjectionFailureRollsBackTheNationalActionAndFreezeAndAllowsCleanRevisionZeroRetry()throws Exception {
        var before=instance("atomic-national-projection",Scope.WORLD,1,"1");
        try(var store=new SqliteStore(directory.resolve("atomic-national.db"))) {
            store.transaction(connection->{fixture.seed(connection,before);return null;});
            store.transaction(connection->{
                try(var statement=connection.createStatement()){statement.execute("CREATE TRIGGER reject_shared_national_resolution BEFORE INSERT ON story_choice_log WHEN NEW.kind='RESOLUTION' BEGIN SELECT RAISE(ABORT,'forced shared national resolution failure'); END");}
                return null;
            });
            assertThrows(IllegalStateException.class,()->authority(fixture.now,allNations).withRepresentative(fixture.a,RIVER,view->store.transaction(connection->{
                NationalVoteLedger.cast(connection,before,actor(before,fixture.a),view,"yes",0,fixture.now);
                ChoiceLedger.recordNationalResolution(connection,before,actor(before,fixture.a),"council","yes",fixture.now);
                complete(connection,before,"yes",fixture.now);return null;
            })));
            store.transaction(connection->{
                assertTrue(NationalVoteLedger.snapshot(connection,before,"council").ballots().isEmpty());
                assertTrue(NationalVoteLedger.resolution(connection,before,"council").isEmpty());
                assertEquals(0,fixture.count(connection,""));
                assertEquals("0",fixture.scalar(connection,"SELECT completed FROM instances WHERE id='atomic-national-projection'"));
                try(var statement=connection.createStatement()){statement.execute("DROP TRIGGER reject_shared_national_resolution");}
                return null;
            });
            authority(fixture.now.plusSeconds(1),allNations).withRepresentative(fixture.a,RIVER,view->store.transaction(connection->{
                NationalVoteLedger.cast(connection,before,actor(before,fixture.a),view,"yes",0,fixture.now.plusSeconds(1));
                assertTrue(ChoiceLedger.recordNationalResolution(connection,before,actor(before,fixture.a),"council","yes",fixture.now.plusSeconds(1)));
                complete(connection,before,"yes",fixture.now.plusSeconds(1));return null;
            }));
            store.transaction(connection->{assertEquals(Map.of(NATIONAL,"yes",WORLD,"yes"),ChoiceLedger.read(connection,fixture.unvoted,RIVER,Set.of(NATIONAL,WORLD,PERSONAL)));return null;});
        }
    }

    private Connection fresh()throws Exception {fixture.directory=directory;var connection=fixture.fresh();NationalVoteLedger.createSchema(connection);return connection;}
    private Connection reopen()throws Exception {fixture.directory=directory;var connection=fixture.open();connection.setAutoCommit(false);return connection;}
    private StoryInstance instance(String id,Scope scope,int quorum,String version) {
        Map<UUID,String> members=scope==Scope.NATION?Map.of(fixture.a,RIVER):Map.of(fixture.a,RIVER,fixture.b,HILL,fixture.c,COAST);
        var old=fixture.instance(id,scope,members,quorum,version);var d=old.definition();
        var definition=new StoryDefinition(d.id(),d.version(),d.title(),d.scope(),d.triggers(),d.condition(),d.start(),d.cooldownSeconds(),d.nodes(),VoteUnit.NATION);
        return new StoryInstance(old.id(),definition,old.scopeKey(),old.sourceEventId(),old.node(),old.members(),Map.of(),Map.of(),old.startedAt(),old.updatedAt(),false);
    }
    private PlayerContext actor(StoryInstance instance,UUID player){return new PlayerContext(player,instance.members().get(player),Map.of(),Set.of("starchronicle.use"));}
    private void complete(Connection connection,StoryInstance before,String choice,Instant time)throws SQLException {fixture.save(connection,fixture.state(before,Map.of(),Map.of("council",choice),true,time));}
    private void cast(Connection connection,StoryInstance before,UUID player,String choice,long revision,Instant time,Set<String> exists)throws Exception {
        var actor=actor(before,player);authority(time,exists).withRepresentative(player,actor.nationId(),view->NationalVoteLedger.cast(connection,before,actor,view,choice,revision,time));
    }
    private void withdraw(Connection connection,StoryInstance before,UUID player,long revision,Instant time,Set<String> exists)throws Exception {
        var actor=actor(before,player);authority(time,exists).withRepresentative(player,actor.nationId(),view->NationalVoteLedger.withdraw(connection,before,actor,view,revision,time));
    }
    private static NationalVoteAuthority authority(Instant time,Set<String> exists) {
        return new NationalVoteAuthority(){
            @Override public <T>T withRepresentative(UUID actor,String nation,Authorized<T> operation)throws Exception {
                if(!exists.contains(nation))throw new SecurityException("Test nation is absent");
                return operation.run(new View(){
                    public UUID actorId(){return actor;} public String nationId(){return nation;} public Instant checkedAt(){return time;}
                    public String issuer(){return "NationalChoiceLedgerTest-authoritative-fixture";}
                    public String evidence(){return "Test-only current native holder "+actor+" of "+nation;}
                    public boolean nationExists(String id){return exists.contains(id);}
                });
            }
        };
    }
}
