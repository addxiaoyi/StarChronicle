package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.epic.*;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class EpicCampaignLedgerTest {
    private static final Instant NOW=Instant.parse("2026-11-25T00:00:00Z");
    private static final UUID A=UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID B=UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID C=UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final String NA="00000000-0000-0000-0000-000000000011";
    private static final String NB="00000000-0000-0000-0000-000000000012";
    private static final String NC="00000000-0000-0000-0000-000000000013";
    private static final String RELIEF="00000000-0000-0000-0000-000000000019";
    private static final String RESTORED="00000000-0000-0000-0000-000000000020";
    private static final String DOMAIN="00000000-0000-0000-0000-000000000099";
    private static final String HASH="0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    @TempDir Path temp;

    private EpicCampaignPlan plan() {
        return new EpicCampaignPlan("heir-strategy","last-heir-strategy","1","StarChronicle.EpicSignup",
                "epic.signup_campaign","heir-strategy","epic.relief_nation",List.of(
                new EpicPhasePolicy("heir-records",EpicPhasePolicy.Kind.PREPARATION,
                        Set.of(new EpicRequirement(EpicRequirement.Type.QUEST_GATE,"chronicle_heir_records","investigation/records-evidence"))),
                new EpicPhasePolicy("heir-route",EpicPhasePolicy.Kind.FINAL_ROUTE,Set.of())),Map.of(
                "WAR",Set.of(
                        new EpicRequirement(EpicRequirement.Type.NATION_FOUNDING,"ANY","NATION_FOUNDING_V1"),
                        new EpicRequirement(EpicRequirement.Type.WAR_COMMIT,"ANY","WAR_DECLARATION_V1"),
                        new EpicRequirement(EpicRequirement.Type.FINAL_BATTLE,"ANY","WAR_OUTCOME_V1")),
                "DUNGEON",Set.of(new EpicRequirement(EpicRequirement.Type.DUNGEON_COMPLETION,"COOPERATIVE_RELIC","DUNGEON_RUN_V1"))));
    }

    private EpicCampaignPlan treatyPlan() {
        return new EpicCampaignPlan("heir-strategy","last-heir-strategy","1","EPIC_SIGNUP_COMMITTED",
                "epic.signup_campaign","heir-strategy","epic.relief_nation",List.of(
                new EpicPhasePolicy("heir-records",EpicPhasePolicy.Kind.PREPARATION,
                        Set.of(new EpicRequirement(EpicRequirement.Type.QUEST_GATE,"chronicle_heir_records","investigation/records-evidence"))),
                new EpicPhasePolicy("heir-treaty",EpicPhasePolicy.Kind.PREPARATION,Set.of(
                        new EpicRequirement(EpicRequirement.Type.DIPLOMACY_INVITE,"RELIEF","ALLIANCE_INVITE_V1"),
                        new EpicRequirement(EpicRequirement.Type.DIPLOMACY_ACCEPT,"ANY","ALLIANCE_ACCEPT_V1"))),
                new EpicPhasePolicy("heir-route",EpicPhasePolicy.Kind.FINAL_ROUTE,Set.of())));
    }

    @Test void aPlanWithoutPreparationsStartsAtItsDeclaredRouteVote() {
        var full = plan();
        var routeOnly = new EpicCampaignPlan(full.campaignId(),full.storyId(),full.storyVersion(),
                full.signupSource(),full.signupFact(),full.signupValue(),full.reliefNationFact(),
                List.of(full.finalRoute()),Map.of("DUNGEON",full.requirementsForRoute("DUNGEON")));
        try(var store=new SqliteStore(temp.resolve("route-only.db"))) {
            var ledger=new EpicCampaignLedger(store);
            ledger.open(routeOnly,"route-only",RELIEF,NOW);
            ledger.enroll("route-only",NA,A,0,NOW);ledger.enroll("route-only",NB,B,1,NOW);
            var sealed=ledger.seal("route-only",2,A,NOW);
            var state=ledger.start("route-only","route-only",sealed.sealedEventId(),NOW);
            assertEquals(EpicCampaignState.Status.ROUTE_VOTING,state.status());
            assertThrows(IllegalArgumentException.class,()->ledger.castRouteVote("route-only",NA,state.revision(),"WAR",NOW));
            var first=ledger.castRouteVote("route-only",NA,state.revision(),"DUNGEON",NOW);
            assertEquals(EpicCampaignState.Status.ROUTE_APPROVED,
                    ledger.castRouteVote("route-only",NB,first.revision(),"DUNGEON",NOW).status());
        }
    }

    @Test void signupFreezesDistinctNationSeatsAndStaleMutationsFail() {
        try(var store=new SqliteStore(temp.resolve("signup.db"))) {
            var ledger=new EpicCampaignLedger(store);var opened=ledger.openSignup(plan(),"session-1",RELIEF,NOW);
            assertEquals(0,opened.revision());
            var first=ledger.enroll("session-1",NA,A,0,NOW.plusSeconds(1));
            assertEquals(1,first.revision());
            assertThrows(IllegalStateException.class,()->ledger.enroll("session-1",NB,B,0,NOW.plusSeconds(2)));
            var second=ledger.enroll("session-1",NB,B,1,NOW.plusSeconds(2));
            var third=ledger.enroll("session-1",NC,C,2,NOW.plusSeconds(3));
            assertEquals(3,third.revision());
            assertThrows(IllegalArgumentException.class,()->ledger.enroll("session-1",RELIEF,A,3,NOW.plusSeconds(4)));
            var sealed=ledger.seal("session-1",third.revision(),A,NOW.plusSeconds(5));
            assertEquals(EpicSignup.Status.SEALED,sealed.status());assertEquals(3,sealed.activeSeats().size());
            assertNotNull(sealed.sealedEventId());
            assertThrows(IllegalStateException.class,()->ledger.withdraw("session-1",NA,A,0,sealed.revision(),NOW.plusSeconds(6)));
        }
    }

    @Test void committedReceiptsGateEveryNationAndRouteNeedsStrictMajority() {
        Path db=temp.resolve("campaign.db");
        String instance="campaign-instance-1";
        try(var store=new SqliteStore(db)) {
            var ledger=new EpicCampaignLedger(store);ledger.open(plan(),"session-2",RELIEF,NOW);
            var signup=ledger.enroll("session-2",NA,A,0,NOW.plusSeconds(1));signup=ledger.enroll("session-2",NB,B,signup.revision(),NOW.plusSeconds(2));signup=ledger.enroll("session-2",NC,C,signup.revision(),NOW.plusSeconds(3));
            ledger.seal("session-2",signup.revision(),A,NOW.plusSeconds(4));
            var campaign=ledger.start(instance,"session-2","chronicle:sealed",NOW.plusSeconds(5));
            assertEquals(EpicCampaignState.Status.PREPARING,campaign.status());
            var a=operation(instance,NA,A,"quest-op-a");var b=operation(instance,NB,B,"quest-op-b");var c=operation(instance,NC,C,"quest-op-c");
            for(var op:List.of(a,b,c)) {
                assertEquals(EpicOperationState.Status.PREPARED,ledger.prepareOperation(op).status());
                assertFalse(ledger.phaseReady(instance,"heir-records",op.nationId()));
                var receipt=receipt(op);
                ledger.commitOperation(op,new EpicObservation(EpicObservation.Status.COMMITTED,receipt,"ok"),"starcore.quest.evidence.v1",DOMAIN,NOW.plusSeconds(10));
                assertTrue(ledger.phaseReady(instance,"heir-records",op.nationId()));
            }
            campaign=ledger.status(instance);assertEquals(EpicCampaignState.Status.ROUTE_VOTING,campaign.status());
            campaign=ledger.routeVote(instance,NA,campaign.revision(),"WAR",NOW.plusSeconds(11));
            assertEquals(EpicCampaignState.Status.ROUTE_VOTING,campaign.status());
            campaign=ledger.routeVote(instance,NB,campaign.revision(),"WAR",NOW.plusSeconds(12));
            assertEquals(EpicCampaignState.Status.ROUTE_APPROVED,campaign.status());assertEquals("WAR",campaign.route());
            Map<String,String> approvedFacts=store.transaction(connection->ledger.storyFacts(connection,"chronicle:sealed",NA));
            assertEquals("true",approvedFacts.get("epic.preparations.ready"));
            assertEquals("true",approvedFacts.get("epic.route.war.open"));
            assertEquals("false",approvedFacts.get("epic.final.war_committed"));

            var founding=finalOperation(instance,NA,A,"founding-op",EpicOperation.Type.NATION_FOUNDING,RESTORED,EpicContract.foundingReference("RestoredHeir"));
            var declaration=finalOperation(instance,RESTORED,A,"war-op",EpicOperation.Type.WAR_COMMIT,NB,"WAR_DECLARATION_V1");
            var battle=finalOperation(instance,RESTORED,A,"battle-op",EpicOperation.Type.FINAL_BATTLE,NB,"pending");
            var foundingState=commitFinal(ledger,founding,nowAt(14));
            assertEquals(EpicCampaignState.Status.ROUTE_APPROVED,ledger.state(instance).status());
            var warState=commitFinal(ledger,declaration,nowAt(15));
            battle=new EpicOperation(battle.operationId(),battle.campaignId(),battle.instanceId(),battle.sourceEventId(),
                    battle.phaseId(),battle.type(),battle.actorId(),battle.nationId(),battle.target(),
                    warState.receipt().nativeReceiptId(),battle.definitionFingerprint(),battle.permission(),battle.requestedAt());
            assertEquals(EpicOperationState.Status.PREPARED,ledger.prepare(battle).status());
            ledger.observe(battle,new EpicObservation(EpicObservation.Status.UNKNOWN,null,"awaiting final outcome"),
                    "starcore.epic",DOMAIN,nowAt(16));
            assertEquals(EpicCampaignState.Status.ROUTE_APPROVED,ledger.state(instance).status());
            ledger.observe(battle,new EpicObservation(EpicObservation.Status.COMMITTED,receipt(battle),"canonical war outcome"),
                    "starcore.epic",DOMAIN,nowAt(17));
            assertEquals(EpicCampaignState.Status.COMPLETE,ledger.state(instance).status());
            Map<String,String> finalFacts=store.transaction(connection->ledger.storyFacts(connection,"chronicle:sealed",NA));
            assertEquals("true",finalFacts.get("epic.final.war_committed"));
            assertEquals("false",finalFacts.get("epic.final.dungeon_committed"));
            assertEquals("true",finalFacts.get("epic.final.canonical"));
            long frozenRevision=campaign.revision();
            assertThrows(IllegalStateException.class,()->ledger.routeVote(instance,NC,frozenRevision,"DUNGEON",NOW.plusSeconds(13)));
        }
        try(var store=new SqliteStore(db)) {
            var state=new EpicCampaignLedger(store).state(instance);
            assertEquals(EpicCampaignState.Status.COMPLETE,state.status());assertEquals(6,state.operations().size());
            assertEquals(2,state.routeVotes().size());
        }
    }

    @Test void onlyMatchingCommittedProviderReceiptAdvancesAndUnknownDoesNot() {
        try(var store=new SqliteStore(temp.resolve("unknown.db"))) {
            var ledger=new EpicCampaignLedger(store);ledger.open(plan(),"session-3",RELIEF,NOW);
            var signup=ledger.enroll("session-3",NA,A,0,NOW.plusSeconds(1));signup=ledger.enroll("session-3",NB,B,signup.revision(),NOW.plusSeconds(2));
            ledger.seal("session-3",signup.revision(),A,NOW.plusSeconds(3));ledger.start("campaign-3","session-3","chronicle:sealed",NOW.plusSeconds(4));
            var op=operation("campaign-3",NA,A,"quest-unknown");ledger.prepare(op,"starcore.quest.evidence.v1",DOMAIN);
            assertEquals(new EpicCampaignLedger.ProviderPin("starcore.quest.evidence.v1",DOMAIN),ledger.providerPin(op.operationId()));
            assertThrows(SecurityException.class,()->ledger.observe(op,EpicObservation.unknown("wrong provider"),"another.provider",DOMAIN,NOW.plusSeconds(5)));
            var unknown=ledger.observe(op,EpicObservation.unknown("provider unavailable"),"starcore.quest.evidence.v1",DOMAIN,NOW.plusSeconds(5));
            assertEquals(EpicOperationState.Status.UNKNOWN,unknown.status());assertFalse(ledger.phaseReady("campaign-3","heir-records",NA));
        }
    }

    @Test void onlyOneAttemptCanWinAndRestartNeverReopensAnUnknownMutation() throws Exception {
        Path db=temp.resolve("attempt.db");EpicOperation op=operation("attempt",NA,A,"one-attempt");
        try(var store=new SqliteStore(db)) {
            var ledger=campaign(store,"attempt");ledger.prepare(op,"starcore.quest.evidence.v1",DOMAIN);
            try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
                var go=new java.util.concurrent.CountDownLatch(1);
                java.util.concurrent.Callable<Boolean> claim=()->{go.await();try{ledger.claimAttempt(op.operationId(),1,NOW);return true;}catch(IllegalStateException lost){return false;}};
                var first=pool.submit(claim);var second=pool.submit(claim);go.countDown();
                assertNotEquals(first.get(),second.get(),"Only one caller may cross the native mutation boundary");
            }
        }
        try(var store=new SqliteStore(db)) {
            var ledger=new EpicCampaignLedger(store);var state=ledger.operation(op.operationId());
            assertEquals(EpicOperationState.Status.UNKNOWN,state.status());
            assertThrows(IllegalStateException.class,()->ledger.claimAttempt(op.operationId(),state.revision(),NOW));
            ledger.observe(op,new EpicObservation(EpicObservation.Status.ABSENT,null,"nothing found"),"starcore.quest.evidence.v1",DOMAIN,NOW);
            assertEquals(EpicOperationState.Status.UNKNOWN,ledger.operation(op.operationId()).status());
            assertThrows(IllegalStateException.class,()->ledger.claimAttempt(op.operationId(),ledger.operation(op.operationId()).revision(),NOW));
        }
    }

    @Test void aNativeReceiptCannotSatisfyTwoIntentsAndCampaignCannotChangeSource() {
        try(var store=new SqliteStore(temp.resolve("reuse.db"))) {
            var ledger=campaign(store,"reuse");
            assertThrows(SecurityException.class,()->ledger.start("reuse","session-reuse","different-source",NOW));
            assertFalse(ledger.phaseReady("reuse","heir-route",RELIEF));
            var first=operation("reuse",NA,A,"first");var second=operation("reuse",NA,A,"second");
            ledger.prepare(first);ledger.prepare(second);
            var proof=receipt(first);ledger.observe(first,new EpicObservation(EpicObservation.Status.COMMITTED,proof,"ok"),proof.issuer(),DOMAIN,NOW);
            var recycled=new EpicReceipt(proof.issuer(),DOMAIN,second.operationId(),second.campaignId(),second.instanceId(),
                    second.sourceEventId(),second.phaseId(),second.type(),second.actorId(),second.nationId(),second.target(),
                    second.nativeReference(),second.definitionFingerprint(),second.permission(),proof.nativeReceiptId(),proof.appliedAt(),"reused");
            assertThrows(RuntimeException.class,()->ledger.observe(second,new EpicObservation(EpicObservation.Status.COMMITTED,recycled,"reused"),proof.issuer(),DOMAIN,NOW));
            assertEquals(EpicOperationState.Status.PREPARED,ledger.operation(second.operationId()).status());
        }
    }

    private EpicCampaignLedger campaign(SqliteStore store,String instance) {
        var ledger=new EpicCampaignLedger(store);String session="session-"+instance;
        ledger.open(plan(),session,RELIEF,NOW);ledger.enroll(session,NA,A,0,NOW);ledger.enroll(session,NB,B,1,NOW);
        ledger.seal(session,2,A,NOW);ledger.start(instance,session,"chronicle:sealed",NOW);return ledger;
    }

    @Test void reliefAcceptanceMustReferenceEachFrozenNationsCommittedInvitation() {
        try(var store=new SqliteStore(temp.resolve("diplomacy-campaign.db"))) {
            var ledger=new EpicCampaignLedger(store);ledger.open(treatyPlan(),"session-treaty",RELIEF,NOW);
            var signup=ledger.enroll("session-treaty",NA,A,0,NOW.plusSeconds(1));
            signup=ledger.enroll("session-treaty",NB,B,signup.revision(),NOW.plusSeconds(2));
            ledger.seal("session-treaty",signup.revision(),A,NOW.plusSeconds(3));
            ledger.start("campaign-treaty","session-treaty","chronicle:treaty",NOW.plusSeconds(4));
            for(var seat:List.of(Map.entry(NA,A),Map.entry(NB,B))) {
                var records=new EpicOperation("records-"+seat.getKey(),"heir-strategy","campaign-treaty","chronicle:treaty",
                        "heir-records",EpicOperation.Type.QUEST_GATE,seat.getValue(),seat.getKey(),"chronicle_heir_records",
                        "investigation/records-evidence",HASH,"starchronicle.use",NOW.plusSeconds(5));
                ledger.prepare(records);ledger.observe(records,new EpicObservation(EpicObservation.Status.COMMITTED,receipt(records),"records"),
                        "starcore.quest.evidence.v1",DOMAIN,NOW.plusSeconds(6));
            }
            var premature=diplomacyOperation("accept-before-invite",RELIEF,C,NA,
                    "00000000-0000-0000-0000-000000000031",EpicOperation.Type.DIPLOMACY_ACCEPT);
            assertThrows(IllegalArgumentException.class,()->ledger.prepare(premature));

            for(var seat:List.of(Map.entry(NA,A),Map.entry(NB,B))) {
                var invite=diplomacyOperation("invite-"+seat.getKey(),seat.getKey(),seat.getValue(),RELIEF,
                        "ALLIANCE_INVITE_V1",EpicOperation.Type.DIPLOMACY_INVITE);
                ledger.prepare(invite);
                var inviteReceipt=receipt(invite);
                ledger.observe(invite,new EpicObservation(EpicObservation.Status.COMMITTED,inviteReceipt,"invited"),
                        "starcore.epic",DOMAIN,NOW.plusSeconds(8));
                var accept=diplomacyOperation("accept-"+seat.getKey(),RELIEF,C,seat.getKey(),
                        inviteReceipt.nativeReceiptId(),EpicOperation.Type.DIPLOMACY_ACCEPT);
                ledger.prepare(accept);
                ledger.observe(accept,new EpicObservation(EpicObservation.Status.COMMITTED,receipt(accept),"accepted"),
                        "starcore.epic",DOMAIN,NOW.plusSeconds(9));
            }
            assertTrue(ledger.phaseReady("campaign-treaty","heir-treaty",NA));
            assertTrue(ledger.phaseReady("campaign-treaty","heir-treaty",NB));
            assertEquals(EpicCampaignState.Status.ROUTE_VOTING,ledger.state("campaign-treaty").status());
        }
    }

    @Test void dungeonRouteNeedsACommittedRecordFromEveryFrozenNation() {
        try(var store=new SqliteStore(temp.resolve("dungeon-campaign.db"))) {
            var ledger=new EpicCampaignLedger(store);ledger.open(plan(),"session-dungeon",RELIEF,NOW);
            var signup=ledger.enroll("session-dungeon",NA,A,0,NOW.plusSeconds(1));
            signup=ledger.enroll("session-dungeon",NB,B,signup.revision(),NOW.plusSeconds(2));
            ledger.seal("session-dungeon",signup.revision(),A,NOW.plusSeconds(3));
            ledger.start("campaign-dungeon","session-dungeon","chronicle:sealed",NOW.plusSeconds(4));
            for(var seat:List.of(Map.entry(NA,A),Map.entry(NB,B))) {
                var records=operation("campaign-dungeon",seat.getKey(),seat.getValue(),"records-"+seat.getKey());
                ledger.prepare(records);
                ledger.observe(records,new EpicObservation(EpicObservation.Status.COMMITTED,receipt(records),"records complete"),
                        "starcore.quest.evidence.v1",DOMAIN,NOW.plusSeconds(6));
            }
            var state=ledger.state("campaign-dungeon");
            state=ledger.routeVote("campaign-dungeon",NA,state.revision(),"DUNGEON",NOW.plusSeconds(7));
            state=ledger.routeVote("campaign-dungeon",NB,state.revision(),"DUNGEON",NOW.plusSeconds(8));
            var first=finalOperation("dungeon-a","campaign-dungeon",NA,A,EpicOperation.Type.DUNGEON_COMPLETION,
                    "COOPERATIVE_RELIC","00000000-0000-0000-0000-000000000081");
            commitFinal(ledger,first,NOW.plusSeconds(9));
            assertEquals(EpicCampaignState.Status.ROUTE_APPROVED,ledger.state("campaign-dungeon").status());
            var second=finalOperation("dungeon-b","campaign-dungeon",NB,B,EpicOperation.Type.DUNGEON_COMPLETION,
                    "COOPERATIVE_RELIC","00000000-0000-0000-0000-000000000082");
            commitFinal(ledger,second,NOW.plusSeconds(10));
            assertEquals(EpicCampaignState.Status.COMPLETE,ledger.state("campaign-dungeon").status());
        }
    }

    private static EpicOperation operation(String instance,String nation,UUID actor,String opId) {
        return new EpicOperation(opId,"heir-strategy",instance,"chronicle:sealed","heir-records",EpicOperation.Type.QUEST_GATE,
                actor,nation,"chronicle_heir_records","investigation/records-evidence",HASH,"starchronicle.use",NOW.plusSeconds(6));
    }
    private static EpicOperation diplomacyOperation(String id,String nation,UUID actor,String target,
                                                    String nativeReference,EpicOperation.Type type) {
        return new EpicOperation(id,"heir-strategy","campaign-treaty","chronicle:treaty","heir-treaty",type,
                actor,nation,target,nativeReference,HASH,"starcore.nation.ally.add",NOW.plusSeconds(7));
    }
    private static EpicOperation finalOperation(String instance,String nation,UUID actor,String opId,
                                                EpicOperation.Type type,String target,String nativeReference) {
        return new EpicOperation(opId,"heir-strategy",instance,"chronicle:sealed","heir-route",type,
                actor,nation,target,nativeReference,HASH,"starchronicle.use",NOW.plusSeconds(13));
    }
    private static EpicOperation finalOperation(String operationId,String instance,String nation,UUID actor,
                                                EpicOperation.Type type,String target,String nativeReference) {
        return new EpicOperation(operationId,"heir-strategy",instance,"chronicle:sealed","heir-route",type,
                actor,nation,target,nativeReference,HASH,"starchronicle.use",NOW.plusSeconds(9));
    }
    private static EpicOperationState commitFinal(EpicCampaignLedger ledger,EpicOperation operation,Instant now) {
        assertEquals(EpicOperationState.Status.PREPARED,ledger.prepare(operation).status());
        return ledger.observe(operation,new EpicObservation(EpicObservation.Status.COMMITTED,receipt(operation),"native commit"),
                "starcore.epic",DOMAIN,now);
    }
    private static Instant nowAt(long seconds) { return NOW.plusSeconds(seconds); }
    private static EpicReceipt receipt(EpicOperation op) {
        String receiptId=op.type()==EpicOperation.Type.WAR_COMMIT?"00000000-0000-0000-0000-000000000077":
                op.type()==EpicOperation.Type.DIPLOMACY_INVITE
                        ?(op.nationId().equals(NA)?"00000000-0000-0000-0000-000000000031":"00000000-0000-0000-0000-000000000032")
                        :"native:"+op.operationId();
        String issuer=op.type()==EpicOperation.Type.QUEST_GATE?"starcore.quest.evidence.v1":"starcore.epic";
        return new EpicReceipt(issuer,DOMAIN,op.operationId(),op.campaignId(),op.instanceId(),op.sourceEventId(),op.phaseId(),op.type(),op.actorId(),op.nationId(),op.target(),op.nativeReference(),op.definitionFingerprint(),op.permission(),receiptId,NOW.plusSeconds(20),"durable committed proof");
    }
}
