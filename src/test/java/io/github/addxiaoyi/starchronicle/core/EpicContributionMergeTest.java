package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.api.contribution.*;
import io.github.addxiaoyi.starchronicle.api.epic.*;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class EpicContributionMergeTest {
    @TempDir Path temp;
    EpicContributionTest fixture;
    @BeforeEach void open() { fixture=new EpicContributionTest();fixture.temp=temp;fixture.openDatabase(); }
    @AfterEach void close() { fixture.close(); }
    long count(String table) {return fixture.store.transaction(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM "+table)){r.next();return r.getLong(1);}});}

    List<ContributionEntry> seed(boolean upstream, boolean partial, String amount) {
        var op=fixture.prepare(EpicOperation.Type.RESOURCE_AID,"agricultural:10","starcore.resource");
        var receipt=fixture.receipt(op).receipt();
        fixture.ledger.observe(op,fixture.receipt(op),"native",EpicContributionTest.DOMAIN,receipt.appliedAt());
        String source=upstream?"chronicle:resource-aid:"+RuleEngine.stable(receipt.authorityDomain(),receipt.nativeReceiptId())
                :"chronicle:epic-aid:"+RuleEngine.stable(receipt.authorityDomain(),op.type().name(),receipt.nativeReceiptId());
        var event=new WorldEvent(source,upstream?"NATIONAL_RESOURCE_AID_COMMITTED":"NATIONAL_AID_COMMITTED",receipt.appliedAt(),
                Set.of(op.actorId()),Set.of(op.nationId(),op.target()),Map.of("time_basis","COMMITTED_SOURCE","aid.authority_domain",receipt.authorityDomain(),
                "aid.transaction",receipt.nativeReceiptId()),receipt.issuer(),"Original branch evidence must remain byte-for-byte");
        fixture.engine.accept(event,List.of());
        fixture.store.transaction(c->{
            for(String subject:partial?List.of("nation:"+op.nationId()):List.of("nation:"+op.nationId(),"player:"+op.actorId())) {
                String unit=upstream?"agricultural":"resource.agricultural";
                String key=upstream?op.operationId():"chronicle:native-contribution:"+RuleEngine.stable(receipt.authorityDomain(),receipt.nativeReceiptId(),unit);
                var request=new ContributionRequest(key,ContributionType.RESOURCE_AID,subject,source,op.actorId(),op.nationId(),new BigDecimal(amount),unit,receipt.appliedAt(),"Original branch contribution evidence");
                if(upstream)ContributionLedger.record(c,request,receipt.appliedAt());
                else ContributionLedger.recordNative(c,receipt.authorityDomain(),receipt.nativeReceiptId(),request,null,receipt.appliedAt());
            }
            return null;
        });
        return fixture.engine.contributions(null,null,0,100);
    }

    @Test void upstreamRowsKeepTheirIdsUnitsAndEvidenceWhileReceiptBindingMakesRecoveryIdempotent() {
        var before=seed(true,false,"10");var history=fixture.engine.history(null,null,0,100);
        assertEquals(1,fixture.engine.recoverEpicContributions(1));
        assertEquals(before,fixture.engine.contributions(null,null,0,100));assertEquals(history,fixture.engine.history(null,null,0,100));
        assertEquals(2,count("contribution_receipts"));assertEquals(1,count("epic_contribution_projections"));
        fixture.close();fixture.openDatabase();assertEquals(0,fixture.engine.recoverEpicContributions(1));
        assertEquals(before,fixture.engine.contributions(null,null,0,100));
        for(String unit:List.of("agricultural","resource.agricultural"))
            assertEquals(new BigDecimal("1E+1"),fixture.engine.contributionTotal("player:"+EpicContributionTest.A,ContributionType.RESOURCE_AID,unit));
    }
    @Test void currentBranchRowsKeepTheirOriginalHistoryAndReceiptKeysAcrossRecovery() {
        var before=seed(false,false,"10");var history=fixture.engine.history(null,null,0,100);
        assertEquals(1,fixture.engine.recoverEpicContributions(1));assertEquals(0,fixture.engine.recoverEpicContributions(1));
        assertEquals(before,fixture.engine.contributions(null,null,0,100));assertEquals(history,fixture.engine.history(null,null,0,100));
        assertEquals(2,count("contribution_receipts"));
    }
    @Test void partialLegacyProjectionAddsOnlyTheMissingSubjectUnderTheOriginalEvent() {
        var old=seed(true,true,"10").getFirst();
        assertEquals(1,fixture.engine.recoverEpicContributions(1));
        var entries=fixture.engine.contributions(null,null,0,100);assertEquals(2,entries.size());assertTrue(entries.contains(old));
        assertTrue(entries.stream().allMatch(e->e.request().sourceEventId().equals(old.request().sourceEventId())));
        assertEquals(1,fixture.engine.history(null,null,0,100).size());assertEquals(0,fixture.engine.recoverEpicContributions(1));
    }
    @Test void mismatchedLegacyAmountCannotAcquireAReceiptBindingOrCompletionMarker() {
        var before=seed(true,false,"11");
        assertThrows(IllegalStateException.class,()->fixture.engine.recoverEpicContributions(1));
        assertEquals(before,fixture.engine.contributions(null,null,0,100));assertEquals(0,count("contribution_receipts"));assertEquals(0,count("epic_contribution_projections"));
    }
    @Test void failedMarkerWriteRollsBackAllNewBindingsWithoutRewritingLegacyRows() {
        var before=seed(true,false,"10");
        fixture.store.transaction(c->{try(var s=c.createStatement()){s.execute("CREATE TRIGGER fail_projection BEFORE INSERT ON epic_contribution_projections BEGIN SELECT RAISE(ABORT,'disk failure'); END");}return null;});
        assertThrows(IllegalStateException.class,()->fixture.engine.recoverEpicContributions(1));
        assertEquals(before,fixture.engine.contributions(null,null,0,100));assertEquals(0,count("contribution_receipts"));
        fixture.store.transaction(c->{try(var s=c.createStatement()){s.execute("DROP TRIGGER fail_projection");}return null;});
        assertEquals(1,fixture.engine.recoverEpicContributions(1));
    }
    @Test void replayWithChangedReceiptCannotReplaceCommittedProjection() {
        var op=fixture.prepare(EpicOperation.Type.RESOURCE_AID,"agricultural:10","starcore.resource");
        var observation=fixture.receipt(op);fixture.engine.observeEpicOperation(op,observation,"native",EpicContributionTest.DOMAIN);
        EpicReceipt r=observation.receipt();
        var changed=new EpicReceipt(r.issuer(),r.authorityDomain(),r.operationId(),r.campaignId(),r.instanceId(),r.sourceEventId(),r.phaseId(),r.type(),r.actorId(),r.nationId(),r.target(),r.nativeReference(),r.definitionFingerprint(),r.permission(),r.nativeReceiptId(),r.appliedAt(),"changed proof");
        assertThrows(SecurityException.class,()->fixture.engine.observeEpicOperation(op,new EpicObservation(EpicObservation.Status.COMMITTED,changed,"changed"),"native",EpicContributionTest.DOMAIN));
        assertEquals(2,fixture.engine.contributions(null,null,0,100).size());
    }
}
