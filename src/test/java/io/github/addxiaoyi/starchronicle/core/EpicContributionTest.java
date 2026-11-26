package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.api.aid.*;
import io.github.addxiaoyi.starchronicle.api.contribution.*;
import io.github.addxiaoyi.starchronicle.api.epic.*;
import io.github.addxiaoyi.starchronicle.memory.MemoryRecipeCatalog;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EpicContributionTest {
    static final Instant NOW=Instant.parse("2026-11-26T00:00:00Z");
    static final UUID A=UUID.randomUUID(),B=UUID.randomUUID();
    static final String NA=UUID.randomUUID().toString(),NB=UUID.randomUUID().toString(),RELIEF=UUID.randomUUID().toString(),DOMAIN=UUID.randomUUID().toString();
    @TempDir Path temp;
    SqliteStore store; RuleEngine engine; EpicCampaignLedger ledger;
    @BeforeEach void open(){openDatabase();}
    void openDatabase(){store=new SqliteStore(temp.resolve("ledger.db"));ledger=new EpicCampaignLedger(store);
        engine=new RuleEngine(store,List.of(),Clock.fixed(NOW,ZoneOffset.UTC),null,NationalVoteAuthority.denying(),MemoryRecipeCatalog.empty(),ledger);}
    @AfterEach void close(){store.close();}
    EpicOperation prepare(EpicOperation.Type type,String reference,String permission) {
        var plan=new EpicCampaignPlan("heir","story","1","EPIC_SIGNUP_COMMITTED","epic.signup_campaign","heir","epic.relief_nation",
                List.of(new EpicPhasePolicy("aid",EpicPhasePolicy.Kind.PREPARATION,Set.of(new EpicRequirement(EpicRequirement.Type.valueOf(type.name()),"RELIEF",reference))),
                        new EpicPhasePolicy("route",EpicPhasePolicy.Kind.FINAL_ROUTE,Set.of())));
        ledger.open(plan,"session",RELIEF,NOW);ledger.enroll("session",NA,A,0,NOW);ledger.enroll("session",NB,B,1,NOW);ledger.seal("session",2,A,NOW);
        ledger.start("instance","session","source",NOW);
        var op=new EpicOperation("aid-op","heir","instance","source","aid",type,A,NA,RELIEF,reference,"a".repeat(64),permission,NOW);
        ledger.prepare(op,"native",DOMAIN);return op;
    }
    EpicObservation receipt(EpicOperation op) {
        return new EpicObservation(EpicObservation.Status.COMMITTED,new EpicReceipt("native",DOMAIN,op.operationId(),op.campaignId(),op.instanceId(),op.sourceEventId(),op.phaseId(),op.type(),op.actorId(),op.nationId(),op.target(),op.nativeReference(),op.definitionFingerprint(),op.permission(),op.operationId(),NOW.plusSeconds(1),"verified receipt"),"committed");
    }
    @Test void committedResourceCreatesIndexedHistoryAndTwoContributionsSurvivingRestartAndReplay() {
        var op=prepare(EpicOperation.Type.RESOURCE_AID,"agricultural:10","starcore.resource");var observation=receipt(op);
        engine.observeEpicOperation(op,observation,"native",DOMAIN);
        assertEquals(2,engine.contributions(null,null,0,100).size());
        assertEquals(new BigDecimal("1E+1"),engine.contributionTotal("player:"+A,ContributionType.RESOURCE_AID,"agricultural"));
        assertEquals(1,engine.history(RELIEF,null,0,100).size());
        assertEquals("COMMITTED_SOURCE",engine.history(null,A,0,100).getFirst().facts().get("time_basis"));
        assertEquals(NOW.plusSeconds(1),engine.contributions(null,null,0,100).getFirst().recordedAt());
        store.close();openDatabase();
        engine.observeEpicOperation(op,observation,"native",DOMAIN);
        assertEquals(0,engine.recoverEpicContributions(1));
        assertEquals(2,engine.contributions(null,null,0,100).size());assertEquals(1,engine.history(null,null,0,100).size());
        assertEquals(1,engine.contributions(null,null,1,1).size());assertTrue(engine.contributions(null,null,2,1).isEmpty());
    }
    @Test void treasuryAidAndEpicEntryPointsCannotCountTheSameTransferTwice() {
        var op=prepare(EpicOperation.Type.TREASURY_AID,"12.50",AidRequest.PERMISSION);
        engine.observeEpicOperation(op,receipt(op),"native",DOMAIN);
        var request=new AidRequest(op.operationId(),op.instanceId(),op.sourceEventId(),op.campaignId(),AidRequest.OPERATION,op.definitionFingerprint(),A,NA,RELIEF,new BigDecimal("12.50"),NOW);
        var nativeReceipt=new AidReceipt("native",DOMAIN,op.operationId(),NA,RELIEF,request.amount(),"OTHER",request.description(),NOW.plusSeconds(1));
        store.transaction(c->AidLedger.admit(c,request,"native",DOMAIN,NOW));
        engine.commitAid(request,nativeReceipt,"native",DOMAIN,List.of(new PlayerContext(A,NA,Map.of(),Set.of("starchronicle.use"))));
        assertEquals(2,engine.contributions(null,null,0,100).size());
        assertEquals(new BigDecimal("12.5"),engine.contributionTotal("nation:"+NA,ContributionType.RESOURCE_AID,"treasury"));
        assertEquals(1,engine.history(null,null,0,100).size());
    }
    @Test void localContributionFailureRollsBackNativeProjectionAndHistoryThenReconcilesOnce() {
        var op=prepare(EpicOperation.Type.RESOURCE_AID,"agricultural:10","starcore.resource");
        store.transaction(c->{try(var s=c.createStatement()){s.execute("CREATE TRIGGER fail_contribution BEFORE INSERT ON contributions BEGIN SELECT RAISE(ABORT,'disk simulation'); END");}return null;});
        assertThrows(IllegalStateException.class,()->engine.observeEpicOperation(op,receipt(op),"native",DOMAIN));
        assertEquals(EpicOperationState.Status.PREPARED,ledger.operation(op.operationId()).status());
        assertTrue(engine.history(null,null,0,100).isEmpty());assertTrue(engine.contributions(null,null,0,100).isEmpty());
        store.transaction(c->{try(var s=c.createStatement()){s.execute("DROP TRIGGER fail_contribution");}return null;});
        engine.observeEpicOperation(op,receipt(op),"native",DOMAIN);assertEquals(2,engine.contributions(null,null,0,100).size());
    }
    @Test void recoveryUsesOnlySavedPinnedCommittedReceiptsWithABoundedBatch() {
        var op=prepare(EpicOperation.Type.RESOURCE_AID,"agricultural:10","starcore.resource");
        ledger.observe(op,receipt(op),"native",DOMAIN,NOW.plusSeconds(1));
        assertTrue(engine.contributions(null,null,0,100).isEmpty());
        store.close();openDatabase();assertEquals(1,engine.recoverEpicContributions(1));assertEquals(0,engine.recoverEpicContributions(1));
        assertEquals(2,engine.contributions(null,null,0,100).size());
    }
    @Test void unknownRejectedAndWrongAuthorityCannotCreateContributionOrHistory() {
        var op=prepare(EpicOperation.Type.RESOURCE_AID,"agricultural:10","starcore.resource");
        for(var status:List.of(EpicObservation.Status.UNKNOWN,EpicObservation.Status.ABSENT,EpicObservation.Status.REJECTED))
            engine.observeEpicOperation(op,new EpicObservation(status,null,"no commit"),"native",DOMAIN);
        assertThrows(SecurityException.class,()->engine.observeEpicOperation(op,receipt(op),"other",DOMAIN));
        assertTrue(engine.contributions(null,null,0,100).isEmpty());assertTrue(engine.history(null,null,0,100).isEmpty());
    }
}
