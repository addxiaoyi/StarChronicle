package io.github.addxiaoyi.starchronicle.integration;

import io.github.addxiaoyi.starchronicle.api.quest.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Public native protocol fakes exercise reflection without Bukkit, StarCore or a server.
 * The coordinator runs these tests; authoring this class starts no JVM. */
class StarCoreQuestEvidenceProviderTest {
    static final String DOMAIN="00000000-0000-0000-0000-000000000001",OTHER_DOMAIN="00000000-0000-0000-0000-000000000002";
    static final UUID PLAYER=UUID.fromString("00000000-0000-0000-0000-000000000011");
    static final String NATION="00000000-0000-0000-0000-000000000021",OTHER_NATION="00000000-0000-0000-0000-000000000022";
    static final String RUN="00000000-0000-0000-0000-000000000031",COMPLETION="00000000-0000-0000-0000-000000000041";
    static final QuestRequest PLAYER_REQUEST=new QuestRequest("binding:one",PLAYER,"native-quest",Instant.ofEpochMilli(1000),"",QuestRequest.Mode.PLAYER);
    static final QuestRequest NATION_REQUEST=new QuestRequest("binding:one",PLAYER,"native-quest",Instant.ofEpochMilli(1000),NATION,QuestRequest.Mode.NATION);
    final MutableClock clock=new MutableClock(Instant.ofEpochSecond(10,123456789));

    public static final class MutableClock extends Clock {
        Instant time;MutableClock(Instant time){this.time=time;}
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return time;}
    }
    public record Admission(String status,Object snapshot,String message){}
    /** Method signatures/accessors mirror the actual QuestEvidenceService protocol. */
    public static class NativeService {
        int protocol=1,beginCalls,lookupCalls,legacyCalls;boolean enabled=true;String domain=DOMAIN;
        Object canonical;CompletableFuture<Admission> response=CompletableFuture.completedFuture(new Admission("ADMITTED",null,"native"));
        UUID actualPlayer;String actualQuest,actualBinding,actualNation;boolean requireSameNation;boolean failLookup;boolean nullFuture;
        public int protocolVersion(){return protocol;}public boolean available(){return enabled;}public String storageAuthorityId(){return domain;}
        public Object lookup(String binding){lookupCalls++;if(failLookup)throw new IllegalStateException("native lookup failed");return canonical;}
        public CompletionStage<Admission> beginTracked(UUID player,String quest,String binding,String nation,boolean same){beginCalls++;actualPlayer=player;actualQuest=quest;actualBinding=binding;actualNation=nation;requireSameNation=same;return nullFuture?null:response;}
        public boolean hasCompletedQuest(UUID player,String quest){legacyCalls++;return true;}
        public void completeQuest(UUID player,String quest){legacyCalls++;throw new AssertionError("adapter must never complete a native quest");}
        public void executeReward(){legacyCalls++;throw new AssertionError("adapter must never execute a reward");}
    }
    /** A mutable fake permits corruption cases; production returns its immutable Snapshot. */
    public static final class Snapshot {
        String binding="binding:one",run=RUN,quest="native-quest",fingerprint="a".repeat(64),status="ACTIVE",issuer=StarCoreQuestEvidenceProvider.ISSUER;
        UUID player=PLAYER;long started=2000,acceptedChecked=1900,completed=3000,completedChecked=2900,committed=4000;
        String acceptedState="KNOWN_NATION",acceptedNation=NATION,completedState="KNOWN_NATION",completedNation=NATION;
        String acceptedProof="original-acceptance-proof",completionProof="immutable-completion-proof",completion=COMPLETION,generic="GENERIC_FIELD_MUST_NOT_REPLACE_EITHER_PROOF",reward="UNKNOWN";
        public String bindingId(){return binding;}public String runId(){return run;}public UUID playerId(){return player;}public String questId(){return quest;}public String definitionFingerprint(){return fingerprint;}
        public long startedAt(){return started;}public String acceptedNationState(){return acceptedState;}public String acceptedNation(){return acceptedNation;}public long acceptedNationCheckedAt(){return acceptedChecked;}
        public String completionId(){return completion;}public long completedAt(){return completed;}public String completedNationState(){return completedState;}public String completedNation(){return completedNation;}public long completedNationCheckedAt(){return completedChecked;}
        public String status(){return status;}public String rewardStatus(){return reward;}public String issuer(){return issuer;}public String acceptedEvidence(){return acceptedProof;}public String completionEvidence(){return completionProof;}public long committedAt(){return committed;}public String evidence(){return generic;}public String message(){return "canonical native observation";}
    }
    public static final class LegacyWithoutStorageAuthority {
        public int protocolVersion(){return 1;}public boolean available(){return true;}public Object lookup(String binding){return null;}
        public CompletionStage<Admission> beginTracked(UUID player,String quest,String binding,String nation,boolean same){return CompletableFuture.completedFuture(new Admission("REJECTED",null,"no durable authority"));}
    }
    private StarCoreQuestEvidenceProvider adapter(NativeService service){return new StarCoreQuestEvidenceProvider(()->service,false,clock);}
    private QuestObservation lookup(NativeService service){return adapter(service).lookup(PLAYER_REQUEST).toCompletableFuture().join();}
    private Snapshot active(){return new Snapshot();}
    private Snapshot completed(){Snapshot snapshot=active();snapshot.status="COMPLETED";return snapshot;}
    private Snapshot unapplied(String status){Snapshot snapshot=active();snapshot.status=status;snapshot.started=0;snapshot.acceptedProof=null;snapshot.completion=null;snapshot.completed=0;snapshot.completionProof=null;snapshot.committed=0;return snapshot;}

    @Test void publicBridgeConstructorUsesTheActualNativeServiceKeyAndPersistentDomain(){
        StarCoreBridge bridge=mock(StarCoreBridge.class);NativeService nativeService=new NativeService();when(bridge.available()).thenReturn(true);when(bridge.service("quest.evidence.QuestEvidenceService")).thenReturn(nativeService);
        var provider=new StarCoreQuestEvidenceProvider(bridge,false);assertTrue(provider.available());assertEquals(StarCoreQuestEvidenceProvider.ISSUER+":"+DOMAIN,provider.providerId());verify(bridge,atLeastOnce()).service("quest.evidence.QuestEvidenceService");
    }
    @Test void foliaDeclinesWithoutEvenResolvingOrInvokingNativeService(){
        AtomicInteger resolutions=new AtomicInteger();var provider=new StarCoreQuestEvidenceProvider(()->{resolutions.incrementAndGet();throw new AssertionError("Folia must not invoke Paper evidence");},true,clock);
        assertFalse(provider.available());assertThrows(IllegalStateException.class,provider::providerId);assertEquals(QuestObservation.Status.UNSUPPORTED,provider.lookup(PLAYER_REQUEST).toCompletableFuture().join().status());assertEquals(QuestObservation.Status.UNSUPPORTED,provider.ensureStarted(PLAYER_REQUEST).toCompletableFuture().join().status());assertEquals(0,resolutions.get());
    }
    @Test void absenceOrProtocolMismatchCannotPinAProviderOrAdmitAQuest(){
        var missing=new StarCoreQuestEvidenceProvider(()->null,false,clock);assertFalse(missing.available());assertThrows(IllegalStateException.class,missing::providerId);assertEquals(QuestObservation.Status.UNSUPPORTED,missing.lookup(PLAYER_REQUEST).toCompletableFuture().join().status());
        NativeService nativeService=new NativeService();nativeService.protocol=2;var provider=adapter(nativeService);assertFalse(provider.available());assertThrows(IllegalStateException.class,provider::providerId);assertEquals(QuestObservation.Status.UNSUPPORTED,provider.ensureStarted(PLAYER_REQUEST).toCompletableFuture().join().status());assertEquals(0,nativeService.beginCalls);assertEquals(0,nativeService.lookupCalls);
    }
    @Test void missingOrMalformedStorageAuthorityNeverGetsAProcessOrPathFallbackId(){
        var legacy=new StarCoreQuestEvidenceProvider(LegacyWithoutStorageAuthority::new,false,clock);assertFalse(legacy.available());assertThrows(IllegalStateException.class,legacy::providerId);
        for(String value:Arrays.asList(null,"", "run/current", "00000000-0000-0000-0000-00000000000A")){
            NativeService nativeService=new NativeService();nativeService.domain=value;var provider=adapter(nativeService);assertFalse(provider.available());assertThrows(IllegalStateException.class,provider::providerId);assertEquals(0,nativeService.beginCalls);
        }
    }
    @Test void aDifferentBackendDomainCannotMasqueradeAsTheOriginallyPinnedProvider(){
        NativeService original=new NativeService(),replacement=new NativeService();replacement.domain=OTHER_DOMAIN;replacement.canonical=completed();AtomicReference<Object> current=new AtomicReference<>(original);var provider=new StarCoreQuestEvidenceProvider(current::get,false,clock);
        String pinned=provider.providerId();current.set(replacement);assertFalse(provider.available());assertEquals(pinned,provider.providerId());QuestObservation observation=provider.lookup(PLAYER_REQUEST).toCompletableFuture().join();assertEquals(QuestObservation.Status.UNSUPPORTED,observation.status());assertNull(observation.completion());assertEquals(0,replacement.lookupCalls);assertEquals(0,replacement.beginCalls);
    }
    @Test void samePersistedDomainCanRestoreTheSameReceiptThroughANewNativeServiceInstance(){
        NativeService first=new NativeService(),restored=new NativeService();first.canonical=completed();restored.canonical=completed();AtomicReference<Object> current=new AtomicReference<>(first);var provider=new StarCoreQuestEvidenceProvider(current::get,false,clock);
        String id=provider.providerId();QuestObservation before=provider.lookup(PLAYER_REQUEST).toCompletableFuture().join();first.enabled=false;current.set(restored);clock.time=clock.time.plusSeconds(1);QuestObservation after=provider.lookup(PLAYER_REQUEST).toCompletableFuture().join();assertEquals(id,provider.providerId());assertEquals(QuestObservation.Status.COMMITTED,after.status());assertEquals(before.acceptedRun(),after.acceptedRun());assertEquals(before.completion(),after.completion());assertTrue(after.observedAt().isAfter(before.observedAt()));assertEquals(0,restored.beginCalls);
    }
    @Test void canonicalNullIsNotStartedWithoutLegacyCompletedEverOrAdmissionCalls(){
        NativeService nativeService=new NativeService();QuestObservation observation=lookup(nativeService);assertEquals(QuestObservation.Status.NOT_STARTED,observation.status());assertNull(observation.acceptedRun());assertNull(observation.completion());assertEquals(1,nativeService.lookupCalls);assertEquals(0,nativeService.beginCalls);assertEquals(0,nativeService.legacyCalls);
    }
    @Test void activeRunCarriesOnlyItsImmutableAcceptanceAndFreshMillisecondObservation(){
        NativeService nativeService=new NativeService();nativeService.canonical=active();var provider=adapter(nativeService);QuestObservation first=provider.lookup(PLAYER_REQUEST).toCompletableFuture().join();
        assertEquals(QuestObservation.Status.ACTIVE,first.status());assertNull(first.completion());assertEquals("original-acceptance-proof",first.acceptedRun().evidence());assertEquals(Instant.ofEpochMilli(2000),first.acceptedRun().startedAt());assertEquals(Instant.ofEpochMilli(clock.millis()),first.observedAt());assertEquals(0,first.observedAt().getNano()%1_000_000);
        clock.time=clock.time.plusSeconds(5);QuestObservation second=provider.lookup(PLAYER_REQUEST).toCompletableFuture().join();assertEquals(first.acceptedRun(),second.acceptedRun());assertTrue(second.observedAt().isAfter(first.observedAt()));
    }
    @Test void abandonedAndExpiredNativeRunsRemainExplicitNonCompletionStates(){
        NativeService nativeService=new NativeService();Snapshot snapshot=active();snapshot.status="ABANDONED";nativeService.canonical=snapshot;
        QuestObservation abandoned=lookup(nativeService);assertEquals(QuestObservation.Status.ABANDONED,abandoned.status());assertNotNull(abandoned.acceptedRun());assertNull(abandoned.completion());
        snapshot.status="FAILED";QuestObservation failed=lookup(nativeService);assertEquals(QuestObservation.Status.FAILED,failed.status());assertEquals(abandoned.acceptedRun(),failed.acceptedRun());assertNull(failed.completion());
        assertEquals(0,nativeService.legacyCalls);assertEquals(0,nativeService.beginCalls);
    }
    @Test void committedReceiptKeepsAcceptanceAndCompletionEvidenceSeparateEvenWhenRewardIsUnknown(){
        NativeService nativeService=new NativeService();nativeService.canonical=completed();QuestObservation observation=lookup(nativeService);assertEquals(QuestObservation.Status.COMMITTED,observation.status());
        assertEquals("original-acceptance-proof",observation.acceptedRun().evidence());assertEquals("original-acceptance-proof",observation.completion().acceptedEvidence());assertEquals("immutable-completion-proof",observation.completion().evidence());assertEquals(observation.acceptedRun(),observation.completion().acceptedRun());assertEquals(StarCoreQuestEvidenceProvider.ISSUER,observation.completion().issuer());assertEquals(Instant.ofEpochMilli(4000),observation.completion().committedAt());assertEquals(0,nativeService.legacyCalls);
    }
    @Test void genericEvidenceCannotSubstituteForAMissingCompletionProof(){
        Snapshot snapshot=completed();snapshot.completionProof=null;NativeService nativeService=new NativeService();nativeService.canonical=snapshot;QuestObservation observation=lookup(nativeService);assertEquals(QuestObservation.Status.UNKNOWN,observation.status());assertNotNull(observation.acceptedRun());assertNull(observation.completion());
    }
    @Test void completionCannotInventAMissingAcceptanceProof(){
        Snapshot snapshot=completed();snapshot.acceptedProof=null;NativeService nativeService=new NativeService();nativeService.canonical=snapshot;QuestObservation observation=lookup(nativeService);assertEquals(QuestObservation.Status.UNKNOWN,observation.status());assertNull(observation.acceptedRun());assertNull(observation.completion());
    }
    @Test void admissionInProgressAndCompletionInProgressNeverMeanCommitted(){
        NativeService nativeService=new NativeService();nativeService.canonical=unapplied("ADMITTING");var observation=lookup(nativeService);assertEquals(QuestObservation.Status.ADMITTING,observation.status());assertNull(observation.acceptedRun());
        Snapshot snapshot=active();snapshot.status="COMPLETING";nativeService.canonical=snapshot;observation=lookup(nativeService);assertEquals(QuestObservation.Status.UNKNOWN,observation.status());assertNotNull(observation.acceptedRun());assertNull(observation.completion());
    }
    @Test void explicitUnappliedRejectionCanRetryWithoutAFabricatedAcceptedRun(){
        NativeService nativeService=new NativeService();nativeService.canonical=unapplied("REJECTED");QuestObservation observation=lookup(nativeService);assertEquals(QuestObservation.Status.NOT_STARTED,observation.status());assertNull(observation.acceptedRun());assertNull(observation.completion());
        ((Snapshot)nativeService.canonical).acceptedProof="contradictory proof";observation=lookup(nativeService);assertEquals(QuestObservation.Status.UNKNOWN,observation.status());assertNull(observation.acceptedRun());assertNull(observation.completion());
    }
    @Test void nativeNoCountryAndUnavailableCountryStayDistinctForPersonalQuests(){
        NativeService nativeService=new NativeService();Snapshot snapshot=active();snapshot.acceptedNation=null;snapshot.acceptedState="KNOWN_NONE";nativeService.canonical=snapshot;assertEquals(NationProof.State.KNOWN_NONE,lookup(nativeService).acceptedRun().acceptedNation().state());snapshot.acceptedState="UNAVAILABLE";assertEquals(NationProof.State.UNAVAILABLE,lookup(nativeService).acceptedRun().acceptedNation().state());
    }
    @Test void nationalModeRequiresSameRealCountryAtAcceptanceAndCompletion(){
        NativeService nativeService=new NativeService();Snapshot snapshot=completed();snapshot.completedNation=OTHER_NATION;nativeService.canonical=snapshot;QuestObservation observation=adapter(nativeService).lookup(NATION_REQUEST).toCompletableFuture().join();assertEquals(QuestObservation.Status.UNKNOWN,observation.status());assertNotNull(observation.acceptedRun());assertNull(observation.completion());
        snapshot.acceptedState="UNAVAILABLE";snapshot.acceptedNation=null;observation=adapter(nativeService).lookup(NATION_REQUEST).toCompletableFuture().join();assertEquals(QuestObservation.Status.UNKNOWN,observation.status());assertNull(observation.acceptedRun());
    }
    @Test void anotherBindingOwnerOrQuestCannotBeAdopted(){
        NativeService nativeService=new NativeService();Snapshot snapshot=active();nativeService.canonical=snapshot;snapshot.binding="another-binding";assertEquals(QuestObservation.Status.CONFLICT,lookup(nativeService).status());snapshot.binding="binding:one";snapshot.player=UUID.randomUUID();assertEquals(QuestObservation.Status.CONFLICT,lookup(nativeService).status());snapshot.player=PLAYER;snapshot.quest="another-quest";assertEquals(QuestObservation.Status.CONFLICT,lookup(nativeService).status());
    }
    @Test void wrongIssuerOldRunAndFutureCompletionRemainUnknown(){
        NativeService nativeService=new NativeService();Snapshot snapshot=completed();nativeService.canonical=snapshot;snapshot.issuer="claimed";assertEquals(QuestObservation.Status.UNKNOWN,lookup(nativeService).status());snapshot.issuer=StarCoreQuestEvidenceProvider.ISSUER;snapshot.started=500;assertEquals(QuestObservation.Status.UNKNOWN,lookup(nativeService).status());snapshot.started=2000;snapshot.committed=clock.millis()+1;assertEquals(QuestObservation.Status.UNKNOWN,lookup(nativeService).status());
    }
    @Test void ensureUsesExactNativeArgumentsAndCanonicalReadBackInsteadOfAdmissionSnapshot(){
        NativeService nativeService=new NativeService();nativeService.canonical=active();nativeService.response=CompletableFuture.completedFuture(new Admission("ADMITTED",completed(),"hint only"));
        QuestObservation observation=adapter(nativeService).ensureStarted(NATION_REQUEST).toCompletableFuture().join();assertEquals(QuestObservation.Status.ACTIVE,observation.status());assertNull(observation.completion());assertEquals(PLAYER,nativeService.actualPlayer);assertEquals("native-quest",nativeService.actualQuest);assertEquals("binding:one",nativeService.actualBinding);assertEquals(NATION,nativeService.actualNation);assertTrue(nativeService.requireSameNation);assertEquals(1,nativeService.beginCalls);assertEquals(1,nativeService.lookupCalls);
    }
    @Test void rejectedAdmissionWithNoCanonicalAcceptanceIsNotStartedButUnknownIsNot(){
        NativeService nativeService=new NativeService();nativeService.response=CompletableFuture.completedFuture(new Admission("REJECTED",null,"no mutation"));QuestObservation observation=adapter(nativeService).ensureStarted(PLAYER_REQUEST).toCompletableFuture().join();assertEquals(QuestObservation.Status.NOT_STARTED,observation.status());assertNull(observation.acceptedRun());
        nativeService.response=CompletableFuture.completedFuture(new Admission("UNKNOWN",null,"uncertain"));observation=adapter(nativeService).ensureStarted(PLAYER_REQUEST).toCompletableFuture().join();assertEquals(QuestObservation.Status.UNKNOWN,observation.status());assertNull(observation.completion());
    }
    @Test void nullOrExceptionalAdmissionAcknowledgementDoesNotBecomeNotStarted(){
        NativeService nativeService=new NativeService();nativeService.nullFuture=true;assertEquals(QuestObservation.Status.UNKNOWN,adapter(nativeService).ensureStarted(PLAYER_REQUEST).toCompletableFuture().join().status());nativeService.nullFuture=false;nativeService.response=CompletableFuture.failedFuture(new IllegalStateException("uncertain native call"));assertEquals(QuestObservation.Status.UNKNOWN,adapter(nativeService).ensureStarted(PLAYER_REQUEST).toCompletableFuture().join().status());
    }
    @Test void nativeDisableOrReplacementRejectsALateAdmissionReply(){
        NativeService nativeService=new NativeService();nativeService.response=new CompletableFuture<>();var provider=adapter(nativeService);CompletionStage<QuestObservation> pending=provider.ensureStarted(PLAYER_REQUEST);nativeService.enabled=false;nativeService.response.complete(new Admission("ADMITTED",completed(),"late"));assertEquals(QuestObservation.Status.UNKNOWN,pending.toCompletableFuture().join().status());assertEquals(0,nativeService.lookupCalls);
        NativeService first=new NativeService(),second=new NativeService();first.response=new CompletableFuture<>();AtomicReference<Object> current=new AtomicReference<>(first);provider=new StarCoreQuestEvidenceProvider(current::get,false,clock);pending=provider.ensureStarted(PLAYER_REQUEST);current.set(second);first.response.complete(new Admission("ADMITTED",completed(),"old instance"));assertEquals(QuestObservation.Status.UNKNOWN,pending.toCompletableFuture().join().status());assertEquals(0,second.beginCalls);
    }
    @Test void lookupFailuresAndInvalidRequestsDoNotAttemptAdmission(){
        NativeService nativeService=new NativeService();nativeService.failLookup=true;assertEquals(QuestObservation.Status.UNKNOWN,lookup(nativeService).status());assertEquals(0,nativeService.beginCalls);AtomicInteger calls=new AtomicInteger();var provider=new StarCoreQuestEvidenceProvider(()->{calls.incrementAndGet();return nativeService;},false,clock);assertEquals(QuestObservation.Status.UNKNOWN,provider.ensureStarted(null).toCompletableFuture().join().status());assertEquals(0,calls.get());
    }
    public record Termination(Object snapshot,String terminationId,long terminatedAt,long committedAt,String evidence) {}
    public static class RecoveryNativeService extends NativeService {
        int recoveryVersion=1,reconcileCalls;Object terminal;
        CompletableFuture<Object> recovery=CompletableFuture.completedFuture(null);
        public int recoveryProtocolVersion(){return recoveryVersion;}
        public CompletionStage<Object> reconcileTracked(String binding){reconcileCalls++;actualBinding=binding;return recovery;}
        public Object termination(String binding){return terminal;}
    }
    private Snapshot terminalSnapshot(){var s=active();s.status="ABANDONED";s.reward="NOT_STARTED";s.completed=0;s.committed=0;s.completion=null;s.completionProof=null;return s;}
    @Test void additiveRecoveryReadsCanonicalStateAndLeavesBaseProviderIdentityStable() {
        var nativeService=new RecoveryNativeService();nativeService.canonical=active();nativeService.recovery=CompletableFuture.completedFuture(completed());
        var provider=adapter(nativeService);String id=provider.providerId();
        var result=provider.reconcile(PLAYER_REQUEST).toCompletableFuture().join();
        assertEquals(QuestObservation.Status.ACTIVE,result.status());assertEquals(id,provider.providerId());
        assertEquals(1,nativeService.reconcileCalls);assertEquals(0,nativeService.beginCalls);assertEquals(0,nativeService.legacyCalls);
        nativeService.canonical=null;
        assertEquals(QuestObservation.Status.UNKNOWN,provider.reconcile(PLAYER_REQUEST).toCompletableFuture().join().status());
    }
    @Test void oldProtocolRemainsReadableButDoesNotClaimRecoverySupport() {
        var nativeService=new NativeService();nativeService.canonical=active();var provider=adapter(nativeService);
        assertEquals(QuestObservation.Status.ACTIVE,provider.lookup(PLAYER_REQUEST).toCompletableFuture().join().status());
        assertThrows(CompletionException.class,()->provider.reconcile(PLAYER_REQUEST).toCompletableFuture().join());
        assertThrows(CompletionException.class,()->provider.termination(PLAYER_REQUEST).toCompletableFuture().join());assertEquals(0,nativeService.beginCalls);
    }
    @Test void terminalReceiptRequiresMatchingRunDurableTimesAndNoRewardGuard() {
        var nativeService=new RecoveryNativeService();var s=terminalSnapshot();nativeService.canonical=s;
        nativeService.terminal=new Termination(s,COMPLETION,3000,4000,"native terminal proof");var provider=adapter(nativeService);
        var proof=provider.termination(PLAYER_REQUEST).toCompletableFuture().join();
        assertEquals(QuestObservation.Status.ABANDONED,proof.status());assertEquals(RUN,proof.acceptedRun().runId());assertEquals(Instant.ofEpochMilli(4000),proof.committedAt());
        s.reward="UNKNOWN";assertThrows(CompletionException.class,()->provider.termination(PLAYER_REQUEST).toCompletableFuture().join());s.reward="NOT_STARTED";
        nativeService.terminal=new Termination(s,COMPLETION,3000,2000,"bad clock");
        assertThrows(CompletionException.class,()->provider.termination(PLAYER_REQUEST).toCompletableFuture().join());
        nativeService.terminal=new Termination(s,COMPLETION,3000,4000,"native proof");s.binding="other";
        assertThrows(CompletionException.class,()->provider.termination(PLAYER_REQUEST).toCompletableFuture().join());
    }
    @Test void storageReplacementDuringRecoveryRejectsLateAcknowledgement() {
        var first=new RecoveryNativeService();var second=new RecoveryNativeService();first.recovery=new CompletableFuture<>();second.canonical=completed();
        AtomicReference<Object> current=new AtomicReference<>(first);var provider=new StarCoreQuestEvidenceProvider(current::get,false,clock);
        var pending=provider.reconcile(PLAYER_REQUEST).toCompletableFuture();current.set(second);first.recovery.complete(completed());
        assertThrows(CompletionException.class,pending::join);assertEquals(0,second.beginCalls);assertEquals(0,second.lookupCalls);
    }

}
