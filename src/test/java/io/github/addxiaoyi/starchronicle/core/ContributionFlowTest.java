package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.api.contribution.*;
import io.github.addxiaoyi.starchronicle.api.epic.*;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ContributionFlowTest {
    @TempDir Path dir;
    static final UUID ACTOR=UUID.randomUUID(), OTHER=UUID.randomUUID();
    static final String NATION=UUID.randomUUID().toString(), SECOND=UUID.randomUUID().toString(), RELIEF=UUID.randomUUID().toString(), DOMAIN=UUID.randomUUID().toString();
    static final Instant NOW=Instant.parse("2026-11-25T09:00:00Z");
    SqliteStore store;RuleEngine engine;EpicCampaignLedger epic;
    void open(){store=new SqliteStore(dir.resolve("contributions.db"));engine=new RuleEngine(store,List.of(),Clock.fixed(NOW.plusSeconds(10),ZoneOffset.UTC));epic=new EpicCampaignLedger(store);}
    @BeforeEach void setup(){open();}
    @AfterEach void close(){engine.close();}
    ContributionLedger ledger(){return new ContributionLedger(store,Clock.fixed(NOW.plusSeconds(10),ZoneOffset.UTC));}
    WorldEvent source(String id){return new WorldEvent(id,"TRADE_COMPLETED",NOW,Set.of(ACTOR),Set.of(NATION),Map.of("time_basis","COMMITTED_SOURCE"),"test.native.trade","durable native receipt="+id);}
    ContributionRequest request(String op,String source,String amount){return new ContributionRequest(op,ContributionType.TRADE,"player:"+ACTOR,source,ACTOR,NATION,new BigDecimal(amount),"coins",NOW,"receipt="+op);}
    EpicOperation operation(EpicOperation.Type type,String value){
        var plan=new EpicCampaignPlan("test","story","1","SIGNUP","epic.signup_campaign","test","epic.relief_nation",List.of(
                new EpicPhasePolicy("aid",EpicPhasePolicy.Kind.PREPARATION,Set.of(new EpicRequirement(EpicRequirement.Type.valueOf(type.name()),"RELIEF",value))),
                new EpicPhasePolicy("route",EpicPhasePolicy.Kind.FINAL_ROUTE,Set.of())));
        epic.open(plan,"session",RELIEF,NOW);epic.enroll("session",NATION,ACTOR,0,NOW);epic.enroll("session",SECOND,OTHER,1,NOW);
        epic.seal("session",2,ACTOR,NOW);epic.start("instance","session","source",NOW);
        var op=new EpicOperation("op:aid","test","instance","source","aid",type,ACTOR,NATION,RELIEF,value,"a".repeat(64),"starcore.resource",NOW);
        epic.prepare(op,"native",DOMAIN);return op;
    }
    EpicObservation committed(EpicOperation op){return new EpicObservation(EpicObservation.Status.COMMITTED,new EpicReceipt("native",DOMAIN,op.operationId(),op.campaignId(),op.instanceId(),op.sourceEventId(),op.phaseId(),op.type(),ACTOR,NATION,RELIEF,op.nativeReference(),op.definitionFingerprint(),op.permission(),"native:receipt",NOW.plusSeconds(1),"durable native transfer receipt"),"committed");}

    @Test void committedResourceReceiptAtomicallyRecordsBothSubjectsAndSurvivesReplay(){
        var op=operation(EpicOperation.Type.RESOURCE_AID,"agricultural:10");
        engine.observeEpicOperation(op,EpicObservation.unknown("lost reply"),"native",DOMAIN);
        assertTrue(ledger().list(null,null,0,100).isEmpty());
        var proof=committed(op);engine.observeEpicOperation(op,proof,"native",DOMAIN);
        assertEquals(2,ledger().list(null,null,0,100).size());
        assertEquals(new BigDecimal("1E+1"),ledger().total("nation:"+NATION,ContributionType.RESOURCE_AID,"resource.agricultural"));
        assertEquals(1,engine.history(null,null,0,100).size());
        engine.close();open();engine.observeEpicOperation(op,proof,"native",DOMAIN);
        assertEquals(2,ledger().list(null,null,0,100).size());assertEquals(1,engine.history(null,null,0,100).size());
    }

    @Test void invalidProjectionRollsBackReceiptAndCanonicalHistoryTogether(){
        var op=operation(EpicOperation.Type.RESOURCE_AID,"agricultural:1000000000001");
        assertThrows(IllegalArgumentException.class,()->engine.observeEpicOperation(op,committed(op),"native",DOMAIN));
        assertEquals(EpicOperationState.Status.PREPARED,epic.operation(op.operationId()).status());
        assertTrue(engine.history(null,null,0,100).isEmpty());assertTrue(ledger().list(null,null,0,100).isEmpty());
    }

    @Test void trustedContributionSourceCannotSubstituteActorsTimeOrAmountAndAllEntriesRollBack(){
        var source=source("trade:one");var first=request("trade:receipt",source.id(),"12.5");
        engine.acceptContributions(source,List.of(first),List.of());
        engine.close();open();engine.acceptContributions(source,List.of(first),List.of());
        assertEquals(1,ledger().list(null,null,0,10).size());
        var next=source("trade:two");
        assertThrows(IllegalStateException.class,()->engine.acceptContributions(next,List.of(request("fresh",next.id(),"2"),request("trade:receipt",next.id(),"99")),List.of()));
        assertEquals(1,engine.history(null,null,0,10).size());assertEquals(1,ledger().list(null,null,0,10).size());
        var impostor=new ContributionRequest("forged",ContributionType.CIVILIZATION,"player:"+OTHER,next.id(),OTHER,NATION,BigDecimal.ONE,"work",NOW,"unsupported actor");
        assertThrows(IllegalArgumentException.class,()->engine.acceptContributions(next,List.of(impostor),List.of()));
    }

    @Test void nativeReceiptBindingReusesLegacyEvidenceAndRejectsDifferentQuantities(){
        var source=source("legacy:source");engine.accept(source,List.of());
        var legacy=request("legacy:operation",source.id(),"3");ledger().record(legacy);
        var projection=request("new:projection",source.id(),"3");
        store.transaction(c->{assertFalse(ContributionLedger.recordNative(c,DOMAIN,"native:one",projection,"legacy:operation",NOW).fresh());return null;});
        engine.close();open();
        store.transaction(c->{assertFalse(ContributionLedger.recordNative(c,DOMAIN,"native:one",projection,null,NOW).fresh());return null;});
        assertEquals(1,ledger().list(null,null,0,100).size());
        assertThrows(IllegalStateException.class,()->store.transaction(c->ContributionLedger.recordNative(c,DOMAIN,"native:one",request("new:projection",source.id(),"4"),null,NOW)));
    }
}
