package io.github.addxiaoyi.starchronicle.narrative;

import io.github.addxiaoyi.starchronicle.api.NativeScene;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import io.github.addxiaoyi.starchronicle.narrative.NativeSceneJournal.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real SQLite restart and invocation-window tests. No native interpreter or reward mocks. */
class NativeSceneJournalTest {
    @TempDir Path directory;
    final Clock clock=Clock.fixed(Instant.parse("2026-11-20T01:15:17.123456789Z"),ZoneOffset.UTC);
    final UUID player=UUID.randomUUID();
    static final String SCRIPT="chronicle_last_heir",HASH="a".repeat(64);
    Path database(){return directory.resolve("native.db");}
    Request request(String id){return new Request(id,"instance-1",player,"nation-1",SCRIPT,HASH);}
    Checkpoint checkpoint(String node){return new Checkpoint(node,Map.of("chronicle_trust",7L,"chronicle_testimony","checked","chronicle_ratio",0.25),Set.of("chronicle_heir_evidence_requested"),Set.of("chronicle_heir_testimony"),new NativeScene(SCRIPT,node,"Owned scene","Verified native text",List.of()));}
    Attempt claim(NativeSceneJournal journal,String id){Entry ready=journal.find(id).orElseThrow();return journal.claim(id,ready.revision(),HASH).orElseThrow();}

    @Test void requestPreparedBeforeInvocationSurvivesRestartAndCanBeAdmittedOnce() {
        try(var store=new SqliteStore(database())){var journal=new NativeSceneJournal(store,clock);Entry ready=journal.prepare(request("delivery-1"));assertEquals(Status.READY,ready.status());assertEquals(0,ready.revision());assertNull(ready.checkpoint());}
        try(var store=new SqliteStore(database())){var journal=new NativeSceneJournal(store,clock);assertEquals(1,journal.ready(player,10).size());assertTrue(journal.claim("delivery-1",0,HASH).isPresent());assertTrue(journal.claim("delivery-1",0,HASH).isEmpty());}
    }
    @Test void conclusiveCheckpointPreservesNativeNodeViewAndOwnedStateAfterRestart() {
        Checkpoint expected=checkpoint("evidence");
        try(var store=new SqliteStore(database())){var journal=new NativeSceneJournal(store,clock);journal.prepare(request("delivery-1"));assertTrue(journal.checkpoint(claim(journal,"delivery-1"),expected));}
        try(var store=new SqliteStore(database())){var journal=new NativeSceneJournal(store,clock);Entry ready=journal.ready(player,10).getFirst();assertEquals(Status.READY,ready.status());assertEquals(expected,ready.checkpoint());assertEquals(1,ready.revision());assertEquals(Instant.parse("2026-11-20T01:15:17.123Z"),ready.updatedAt());assertTrue(journal.unknown(player,10).isEmpty());}
    }
    @Test void processDeathAfterClaimBeforeAnyConclusiveReadBackBecomesUnknownNotReady() {
        try(var store=new SqliteStore(database())){var journal=new NativeSceneJournal(store,clock);journal.prepare(request("delivery-1"));claim(journal,"delivery-1");}
        try(var store=new SqliteStore(database())){var journal=new NativeSceneJournal(store,clock);assertTrue(journal.ready(player,10).isEmpty());Entry unknown=journal.unknown(player,10).getFirst();assertEquals(Status.UNKNOWN,unknown.status());assertTrue(journal.claim("delivery-1",unknown.revision(),HASH).isEmpty());assertTrue(unknown.reason().contains("Process ended"));}
    }
    @Test void ambiguousActionRetainsLastKnownCheckpointAndNeverAutomaticallyResumesIt() {
        Checkpoint known=checkpoint("arrival");
        try(var store=new SqliteStore(database())){var journal=new NativeSceneJournal(store,clock);journal.prepare(request("delivery-1"));journal.checkpoint(claim(journal,"delivery-1"),known);Attempt action=claim(journal,"delivery-1");assertTrue(journal.unknown(action,"Timeout after native invocation; actual node may differ"));assertFalse(journal.checkpoint(action,checkpoint("evidence")));}
        try(var store=new SqliteStore(database())){var journal=new NativeSceneJournal(store,clock);Entry unknown=journal.unknown(player,10).getFirst();assertEquals(known,unknown.checkpoint());assertEquals(2,unknown.revision());assertTrue(journal.ready(player,10).isEmpty());}
    }
    @Test void completedReceiptSurvivesRestartAndCannotBeRepreparedAsNewScene() {
        try(var store=new SqliteStore(database())){var journal=new NativeSceneJournal(store,clock);Request r=request("delivery-1");journal.prepare(r);assertTrue(journal.completed(claim(journal,"delivery-1")));assertEquals(Status.COMPLETED,journal.prepare(r).status());}
        try(var store=new SqliteStore(database())){var journal=new NativeSceneJournal(store,clock);assertEquals(Status.COMPLETED,journal.find("delivery-1").orElseThrow().status());assertTrue(journal.ready(player,10).isEmpty());assertTrue(journal.claim("delivery-1",1,HASH).isEmpty());}
    }
    @Test void duplicateStableRequestIsIdempotentButPayloadCollisionFailsClosed() {
        try(var store=new SqliteStore(database())){var journal=new NativeSceneJournal(store,clock);Request r=request("delivery-1");Entry first=journal.prepare(r);assertEquals(first,journal.prepare(r));
            assertThrows(IllegalArgumentException.class,()->journal.prepare(new Request(r.outcomeId(),r.instanceId(),r.playerId(),r.nationId(),r.scriptId(),"b".repeat(64))));assertEquals(1,journal.ready(player,10).size());}
    }
    @Test void busyOrOfflineKnownBeforeInvocationReleasesAdmissionWithoutLosingCheckpoint() {
        try(var store=new SqliteStore(database())){var journal=new NativeSceneJournal(store,clock);journal.prepare(request("delivery-1"));Checkpoint known=checkpoint("arrival");journal.checkpoint(claim(journal,"delivery-1"),known);Attempt busy=claim(journal,"delivery-1");assertTrue(journal.notInvoked(busy,"Unrelated native scene active; no call made"));
            Entry ready=journal.find("delivery-1").orElseThrow();assertEquals(Status.READY,ready.status());assertEquals(known,ready.checkpoint());assertEquals(3,claim(journal,"delivery-1").revision());}
    }
    @Test void staleAsynchronousCompletionCannotOverwriteANewerCallLease() {
        try(var store=new SqliteStore(database())){var journal=new NativeSceneJournal(store,clock);journal.prepare(request("delivery-1"));Attempt old=claim(journal,"delivery-1");journal.notInvoked(old,"Entity retired before native call");Attempt newer=claim(journal,"delivery-1");
            assertFalse(journal.completed(old));assertFalse(journal.unknown(old,"Late obsolete callback"));assertTrue(journal.checkpoint(newer,checkpoint("evidence")));assertEquals("evidence",journal.find("delivery-1").orElseThrow().checkpoint().nodeId());}
    }
    @Test void samePlayerHasOnlyOneAdmittedNativeCallAndUnknownBlocksNewSceneStarts() {
        try(var store=new SqliteStore(database())){var journal=new NativeSceneJournal(store,clock);journal.prepare(request("delivery-1"));journal.prepare(request("delivery-2"));Attempt first=claim(journal,"delivery-1");assertTrue(journal.claim("delivery-2",0,HASH).isEmpty());journal.unknown(first,"Native response uncertain");assertTrue(journal.claim("delivery-2",0,HASH).isEmpty());}
    }
    @Test void readyCheckpointKeepsExclusiveSessionOwnershipUntilItsOutcomeConclusivelyCompletes() {
        Checkpoint owned=checkpoint("evidence");
        try(var store=new SqliteStore(database())) {
            var journal=new NativeSceneJournal(store,clock);journal.prepare(request("first-outcome"));journal.prepare(request("second-outcome"));
            assertTrue(journal.checkpoint(claim(journal,"first-outcome"),owned));
            assertTrue(journal.claim("second-outcome",0,HASH).isEmpty(),"A second outcome must not adopt the same player's matching-script session");
            assertEquals(Status.READY,journal.find("first-outcome").orElseThrow().status());
            assertEquals(owned,journal.find("first-outcome").orElseThrow().checkpoint());
            assertNull(journal.find("second-outcome").orElseThrow().checkpoint());
        }
        try(var store=new SqliteStore(database())) {
            var journal=new NativeSceneJournal(store,clock);
            assertTrue(journal.claim("second-outcome",0,HASH).isEmpty(),"Restart must preserve the first outcome's checkpoint ownership");
            Attempt owner=claim(journal,"first-outcome");assertEquals(owned,owner.checkpoint());
            assertTrue(journal.claim("second-outcome",0,HASH).isEmpty());assertTrue(journal.completed(owner));
            Attempt next=journal.claim("second-outcome",0,HASH).orElseThrow();assertNull(next.checkpoint());
            assertEquals(Status.COMPLETED,journal.find("first-outcome").orElseThrow().status());
            assertEquals(Status.RUNNING,journal.find("second-outcome").orElseThrow().status());
            assertFalse(journal.checkpoint(owner,checkpoint("arrival")),"A late old callback cannot revive the completed owner");
        }
    }
    @Test void orphanIsolationPreservesLiveLeaseAndRequiresAdminClosureBeforeAnotherOutcomeCanStart() {
        Checkpoint known=checkpoint("evidence");UUID livePlayer=UUID.randomUUID();
        Request liveRequest=new Request("live-outcome","live-instance",livePlayer,"nation-1",SCRIPT,HASH);
        try(var store=new SqliteStore(database())) {
            var journal=new NativeSceneJournal(store,clock);journal.prepare(request("orphan-outcome"));
            journal.checkpoint(claim(journal,"orphan-outcome"),known);Attempt orphan=claim(journal,"orphan-outcome");
            journal.prepare(request("waiting-outcome"));journal.prepare(liveRequest);
            Attempt live=journal.claim(liveRequest.outcomeId(),0,HASH).orElseThrow();
            assertEquals(1,journal.isolateOrphans(Set.of(livePlayer)));
            Entry isolated=journal.find("orphan-outcome").orElseThrow();
            assertEquals(Status.UNKNOWN,isolated.status());assertEquals(orphan.revision(),isolated.revision());
            assertEquals(known,isolated.checkpoint());assertTrue(isolated.reason().contains("without replay"));
            Entry retained=journal.find(liveRequest.outcomeId()).orElseThrow();
            assertEquals(Status.RUNNING,retained.status());assertEquals(live.revision(),retained.revision());
            assertFalse(journal.completed(orphan));assertFalse(journal.checkpoint(orphan,checkpoint("arrival")));
            assertTrue(journal.claim("orphan-outcome",isolated.revision(),HASH).isEmpty());
            assertTrue(journal.claim("waiting-outcome",0,HASH).isEmpty());
            assertEquals(0,journal.isolateOrphans(Set.of(livePlayer)),"Already isolated work must not be re-admitted or isolated again");
            assertTrue(journal.checkpoint(live,checkpoint("arrival")),"A protected live continuation must retain its valid lease");
            assertTrue(journal.acknowledgeCompleted("orphan-outcome"));
            assertEquals(Status.COMPLETED,journal.find("orphan-outcome").orElseThrow().status());
            assertEquals(known,journal.find("orphan-outcome").orElseThrow().checkpoint());
            assertTrue(journal.claim("waiting-outcome",0,HASH).isPresent());
        }
    }
    @Test void onePlayersUnknownDoesNotBlockADifferentPlayersNativeRecovery() {
        try(var store=new SqliteStore(database())){var journal=new NativeSceneJournal(store,clock);journal.prepare(request("delivery-1"));journal.unknown(claim(journal,"delivery-1"),"Unknown first player call");Request other=new Request("delivery-other","instance-1",UUID.randomUUID(),"",SCRIPT,HASH);journal.prepare(other);assertTrue(journal.claim(other.outcomeId(),0,HASH).isPresent());}
    }
    @Test void scriptContentChangeCannotResumeCheckpointAgainstDifferentGraph() {
        try(var store=new SqliteStore(database())){var journal=new NativeSceneJournal(store,clock);journal.prepare(request("delivery-1"));journal.checkpoint(claim(journal,"delivery-1"),checkpoint("arrival"));assertTrue(journal.claim("delivery-1",1,"b".repeat(64)).isEmpty());assertEquals(Status.READY,journal.find("delivery-1").orElseThrow().status());}
    }
    @Test void checkpointMustBelongToAdmittedOwnedScriptAndMatchingNode() {
        try(var store=new SqliteStore(database())){var journal=new NativeSceneJournal(store,clock);journal.prepare(request("delivery-1"));Attempt first=claim(journal,"delivery-1");Checkpoint different=new Checkpoint("arrival",Map.of(),Set.of(),Set.of(),new NativeScene("chronicle_other","arrival","Other","text",List.of()));assertThrows(IllegalArgumentException.class,()->journal.checkpoint(first,different));assertEquals(Status.RUNNING,journal.find("delivery-1").orElseThrow().status());}
        assertThrows(IllegalArgumentException.class,()->new Checkpoint("arrival",Map.of(),Set.of(),Set.of(),new NativeScene(SCRIPT,"different","Owned","text",List.of())));
    }
    @Test void foreignStateAndNonScalarOrNonFiniteVariablesCannotBeAutomaticallyRestored() {
        NativeScene view=new NativeScene(SCRIPT,"arrival","Owned","text",List.of());
        assertThrows(IllegalArgumentException.class,()->new Checkpoint("arrival",Map.of("external_reward_claimed",true),Set.of(),Set.of(),view));
        assertThrows(IllegalArgumentException.class,()->new Checkpoint("arrival",Map.of(),Set.of("foreign_flag"),Set.of(),view));
        assertThrows(IllegalArgumentException.class,()->new Checkpoint("arrival",Map.of(),Set.of(),Set.of("foreign_evidence"),view));
        assertThrows(IllegalArgumentException.class,()->new Checkpoint("arrival",Map.of("chronicle_nested",Map.of("effect","grant")),Set.of(),Set.of(),view));
        assertThrows(IllegalArgumentException.class,()->new Checkpoint("arrival",Map.of("chronicle_number",Double.NaN),Set.of(),Set.of(),view));
    }
    @Test void detachedCheckpointCollectionsCannotBeMutatedByProviderAfterCommit() {
        var vars=new HashMap<String,Object>();vars.put("chronicle_trust",7);var flags=new HashSet<>(Set.of("chronicle_seen"));Checkpoint cp=new Checkpoint("arrival",vars,flags,Set.of(),new NativeScene(SCRIPT,"arrival","Owned","text",List.of()));vars.put("chronicle_trust",999);flags.clear();
        assertEquals(7L,cp.variables().get("chronicle_trust"));assertEquals(Set.of("chronicle_seen"),cp.flags());assertThrows(UnsupportedOperationException.class,()->cp.variables().clear());assertThrows(UnsupportedOperationException.class,()->cp.flags().clear());
    }
    @Test void failedCheckpointTransactionCannotPublishReadyOrLosePreviousCheckpoint() {
        Checkpoint known=checkpoint("arrival");
        try(var store=new SqliteStore(database())){var journal=new NativeSceneJournal(store,clock);journal.prepare(request("delivery-1"));journal.checkpoint(claim(journal,"delivery-1"),known);Attempt action=claim(journal,"delivery-1");
            store.transaction(c->{try(var s=c.createStatement()){s.execute("CREATE TRIGGER fail_checkpoint BEFORE UPDATE ON native_scene_journal WHEN NEW.status='READY' BEGIN SELECT RAISE(ABORT,'simulated disk failure'); END");}return null;});
            assertThrows(IllegalStateException.class,()->journal.checkpoint(action,checkpoint("evidence")));assertEquals(Status.RUNNING,journal.find("delivery-1").orElseThrow().status());assertEquals(known,journal.find("delivery-1").orElseThrow().checkpoint());}
        try(var store=new SqliteStore(database())){var journal=new NativeSceneJournal(store,clock);assertEquals(Status.UNKNOWN,journal.find("delivery-1").orElseThrow().status());assertEquals(known,journal.find("delivery-1").orElseThrow().checkpoint());}
    }
    @Test void missingIdsInvalidFingerprintAndReadBatchBoundsFailClosed() {
        assertThrows(IllegalArgumentException.class,()->new Request("out","instance",player,"","foreign_script",HASH));assertThrows(IllegalArgumentException.class,()->new Request("out","instance",player,"",SCRIPT,"not-a-digest"));
        try(var store=new SqliteStore(database())){var journal=new NativeSceneJournal(store,clock);assertTrue(journal.find("missing").isEmpty());assertTrue(journal.claim("missing",0,HASH).isEmpty());assertThrows(IllegalArgumentException.class,()->journal.ready(player,0));assertThrows(IllegalArgumentException.class,()->journal.unknown(player,1001));}
    }
    @Test void administrativeUnknownAcknowledgementClosesReceiptWithoutReplayingNativeWork() {
        Checkpoint known=checkpoint("evidence");
        try(var store=new SqliteStore(database())){var journal=new NativeSceneJournal(store,clock);journal.prepare(request("delivery-1"));journal.checkpoint(claim(journal,"delivery-1"),known);Attempt uncertain=claim(journal,"delivery-1");journal.unknown(uncertain,"Native result lost after invocation");journal.prepare(request("delivery-2"));assertTrue(journal.claim("delivery-2",0,HASH).isEmpty());
            assertEquals(1,journal.unknown(10).size());assertTrue(journal.acknowledgeCompleted("delivery-1"));Entry closed=journal.find("delivery-1").orElseThrow();assertEquals(Status.COMPLETED,closed.status());assertEquals(uncertain.revision()+1,closed.revision());assertEquals(known,closed.checkpoint());assertTrue(closed.reason().contains("no native call replayed"));
            assertFalse(journal.acknowledgeCompleted("delivery-1"));assertFalse(journal.checkpoint(uncertain,checkpoint("arrival")));assertTrue(journal.claim("delivery-1",closed.revision(),HASH).isEmpty());assertEquals(Status.COMPLETED,journal.prepare(request("delivery-1")).status());assertTrue(journal.claim("delivery-2",0,HASH).isPresent());
            long outcomes=store.transaction(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM outcomes")){r.next();return r.getLong(1);}});long facts=store.transaction(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM facts")){r.next();return r.getLong(1);}});assertEquals(0L,outcomes);assertEquals(0L,facts);}
        try(var store=new SqliteStore(database())){var journal=new NativeSceneJournal(store,clock);assertEquals(Status.COMPLETED,journal.find("delivery-1").orElseThrow().status());assertEquals(known,journal.find("delivery-1").orElseThrow().checkpoint());assertTrue(journal.ready(player,10).stream().noneMatch(e->e.request().outcomeId().equals("delivery-1")));}
    }
    @Test void adminAcknowledgementCannotConvertReadyRunningOrAlreadyCompletedRecords() {
        try(var store=new SqliteStore(database())){var journal=new NativeSceneJournal(store,clock);assertFalse(journal.acknowledgeCompleted("missing"));journal.prepare(request("delivery-1"));assertFalse(journal.acknowledgeCompleted("delivery-1"));Attempt admitted=claim(journal,"delivery-1");assertFalse(journal.acknowledgeCompleted("delivery-1"));assertEquals(Status.RUNNING,journal.find("delivery-1").orElseThrow().status());journal.completed(admitted);assertFalse(journal.acknowledgeCompleted("delivery-1"));}
    }
    @Test void checkpointRecoveryIsFilteredBeforeLimitSoManyUnstartedRequestsCannotStarveIt() {
        try(var store=new SqliteStore(database())){var journal=new NativeSceneJournal(store,clock);for(int i=0;i<130;i++)journal.prepare(request("000-unstarted-"+i));journal.prepare(request("zzz-resumable"));journal.checkpoint(claim(journal,"zzz-resumable"),checkpoint("evidence"));assertNull(journal.ready(player,1).getFirst().checkpoint());assertEquals("zzz-resumable",journal.resumable(player,1).getFirst().request().outcomeId());}
        try(var store=new SqliteStore(database())){var journal=new NativeSceneJournal(store,clock);assertEquals(131,journal.ready(player,1000).size());List<Entry> resumes=journal.resumable(player,1);assertEquals(1,resumes.size());assertEquals("zzz-resumable",resumes.getFirst().request().outcomeId());assertEquals("evidence",resumes.getFirst().checkpoint().nodeId());assertThrows(IllegalArgumentException.class,()->journal.resumable(player,0));assertThrows(IllegalArgumentException.class,()->journal.resumable(player,1001));}
    }
    @Test void resumableQueryExcludesUnknownCompletedAndOtherPlayersCheckpoints() {
        try(var store=new SqliteStore(database())){var journal=new NativeSceneJournal(store,clock);journal.prepare(request("unknown-checkpoint"));journal.checkpoint(claim(journal,"unknown-checkpoint"),checkpoint("arrival"));journal.unknown(claim(journal,"unknown-checkpoint"),"Unknown action");assertTrue(journal.resumable(player,10).isEmpty());journal.acknowledgeCompleted("unknown-checkpoint");assertTrue(journal.resumable(player,10).isEmpty());
            UUID other=UUID.randomUUID();Request different=new Request("other-player","instance",other,"",SCRIPT,HASH);journal.prepare(different);Attempt admitted=journal.claim(different.outcomeId(),0,HASH).orElseThrow();journal.checkpoint(admitted,checkpoint("evidence"));assertTrue(journal.resumable(player,10).isEmpty());assertEquals(1,journal.resumable(other,10).size());}
    }
    @Test void initialNodeAndPermissionAreDurableAndPartOfStableRequestCollisionChecks() {
        Request full=new Request("full-request","instance-1",player,"nation-1",SCRIPT,HASH,"evidence","starchronicle.scene.heir");
        try(var store=new SqliteStore(database())){var journal=new NativeSceneJournal(store,clock);assertEquals(full,journal.prepare(full).request());assertThrows(IllegalArgumentException.class,()->journal.prepare(new Request(full.outcomeId(),full.instanceId(),player,full.nationId(),SCRIPT,HASH,"arrival",full.permission())));assertThrows(IllegalArgumentException.class,()->journal.prepare(new Request(full.outcomeId(),full.instanceId(),player,full.nationId(),SCRIPT,HASH,full.initialNode(),"different.permission")));}
        try(var store=new SqliteStore(database())){var journal=new NativeSceneJournal(store,clock);Request reloaded=journal.find(full.outcomeId()).orElseThrow().request();assertEquals(full,reloaded);assertEquals("evidence",reloaded.initialNode());assertEquals("starchronicle.scene.heir",reloaded.permission());Attempt admitted=journal.claim(full.outcomeId(),0,HASH).orElseThrow();assertEquals(full,admitted.request());}
    }
    @Test void compatibilityConstructorsPreserveEmptyNodeAndPermissionDefaults() {
        Request legacy=new Request("legacy","instance",player,"",SCRIPT,HASH);assertEquals("",legacy.initialNode());assertEquals("",legacy.permission());
        Request seven=new Request("seven","instance",player,"",SCRIPT,HASH,"arrival");assertEquals("arrival",seven.initialNode());assertEquals("",seven.permission());
        assertThrows(IllegalArgumentException.class,()->new Request("invalid","instance",player,"",SCRIPT,HASH,"arrival","unsafe\npermission"));
    }
    @Test void readyQuarantineUsesRevisionCasAndCannotTouchRunningOrCompletedCalls() {
        try(var store=new SqliteStore(database())){var journal=new NativeSceneJournal(store,clock);journal.prepare(request("delivery-1"));Attempt running=claim(journal,"delivery-1");assertFalse(journal.quarantineReady("delivery-1",running.revision(),"Script changed while call running"));assertEquals(Status.RUNNING,journal.find("delivery-1").orElseThrow().status());Checkpoint known=checkpoint("evidence");journal.checkpoint(running,known);
            assertFalse(journal.quarantineReady("delivery-1",0,"Stale quarantine token"));assertEquals(Status.READY,journal.find("delivery-1").orElseThrow().status());assertTrue(journal.quarantineReady("delivery-1",running.revision(),"Owned script fingerprint or ownership changed"));Entry quarantined=journal.find("delivery-1").orElseThrow();assertEquals(Status.UNKNOWN,quarantined.status());assertEquals(known,quarantined.checkpoint());assertEquals(HASH,quarantined.request().scriptFingerprint());
            journal.prepare(request("delivery-2"));assertTrue(journal.claim("delivery-2",0,HASH).isEmpty());assertTrue(journal.claim("delivery-1",quarantined.revision(),HASH).isEmpty());assertTrue(journal.acknowledgeCompleted("delivery-1"));Entry closed=journal.find("delivery-1").orElseThrow();assertFalse(journal.quarantineReady("delivery-1",closed.revision(),"Cannot reopen confirmed completion"));assertEquals(Status.COMPLETED,journal.prepare(request("delivery-1")).status());assertTrue(journal.claim("delivery-2",0,HASH).isPresent());}
    }
}
