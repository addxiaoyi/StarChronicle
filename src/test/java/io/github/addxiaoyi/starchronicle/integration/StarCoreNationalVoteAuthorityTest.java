package io.github.addxiaoyi.starchronicle.integration;

import io.github.addxiaoyi.starchronicle.api.NationalVoteAuthority;
import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real native-access seam, monitor ordering and scoped-view contracts. No Bukkit Player,
 * role, rank, permission, OP or caller-supplied facts participate in these authority tests. */
class StarCoreNationalVoteAuthorityTest {
    private static final UUID HOLDER=UUID.fromString("00000000-0000-4000-8000-000000000051");
    private static final UUID SUCCESSOR=UUID.fromString("00000000-0000-4000-8000-000000000052");
    private static final UUID MEMBER=UUID.fromString("00000000-0000-4000-8000-000000000053");
    private static final String OWN="aaaaaaaa-1111-4111-8111-111111111111";
    private static final String OTHER="bbbbbbbb-2222-4222-8222-222222222222";
    private static final String MISSING="cccccccc-3333-4333-8333-333333333333";
    private static final Instant NOW=Instant.parse("2026-11-20T08:09:10.123456789Z");
    private NativeFixture nativeState;
    private StarCoreNationalVoteAuthority authority;

    @BeforeEach void setup(){nativeState=new NativeFixture();authority=new StarCoreNationalVoteAuthority(()->nativeState,Clock.fixed(NOW,ZoneOffset.UTC));}

    @Test void actualCurrentHolderReadsAndCallbackShareNativeMonitorAndReturnTheCallbackResult()throws Exception {
        assertTrue(authority.available());Thread caller=Thread.currentThread();
        String result=authority.withRepresentative(HOLDER,OWN,view->{
            assertTrue(Thread.holdsLock(nativeState.lock));assertEquals(HOLDER,view.actorId());assertEquals(OWN,view.nationId());assertEquals(NOW,view.checkedAt());
            assertTrue(view.issuer().contains("NationModule"));assertTrue(view.evidence().contains(OWN));assertTrue(view.evidence().contains(HOLDER.toString()));
            assertTrue(view.nationExists(OWN));assertTrue(view.nationExists(OTHER));assertFalse(view.nationExists(MISSING));assertTrue(Thread.holdsLock(nativeState.lock));return "committed-local-result";
        });
        assertEquals("committed-local-result",result);assertFalse(Thread.holdsLock(nativeState.lock));
        assertEquals(List.of("nationOf:"+HOLDER,"exists:"+OWN,"holder:"+HOLDER+":"+OWN,"exists:"+OWN,"exists:"+OTHER,"exists:"+MISSING),nativeState.calls);
        assertTrue(nativeState.readThreads.stream().allMatch(caller::equals));
    }

    @Test void ordinaryMemberHasNoNationalAuthorityEvenWhenTheCapabilityIsAvailable(){
        assertTrue(authority.available());AtomicInteger callbacks=new AtomicInteger();
        assertThrows(SecurityException.class,()->authority.withRepresentative(MEMBER,OWN,view->callbacks.incrementAndGet()));assertEquals(0,callbacks.get());
        assertEquals(List.of("nationOf:"+MEMBER,"exists:"+OWN,"holder:"+MEMBER+":"+OWN),nativeState.calls);
    }

    @Test void ordinaryMembershipCanBeRevalidatedWithoutGrantingTheNationalBallot()throws Exception {
        var escaped=authority.withMembership(MEMBER,OWN,view->{
            assertTrue(Thread.holdsLock(nativeState.lock));assertEquals(MEMBER,view.actorId());
            assertEquals("StarCore.NationModule.membership",view.issuer());
            assertFalse(view.evidence().contains("currentHolder"));return view;
        });
        assertEquals(List.of("nationOf:"+MEMBER,"exists:"+OWN),nativeState.calls);assertInvalidView(escaped);
        assertThrows(SecurityException.class,()->authority.withRepresentative(MEMBER,OWN,view->null));
    }

    @Test void emptyMembershipRequiresAnActualNativeNationlessIdentity()throws Exception {
        UUID nationless=UUID.randomUUID();
        assertEquals("",authority.withMembership(nationless,"",NationalVoteAuthority.View::nationId));
        assertThrows(SecurityException.class,()->authority.withMembership(MEMBER,"",view->null));
        assertThrows(SecurityException.class,()->authority.withMembership(nationless,OWN,view->null));
    }

    @Test void differentExpectedNationIsRejectedBeforeExistenceOrHolderReads(){
        AtomicInteger callbacks=new AtomicInteger();assertThrows(SecurityException.class,()->authority.withRepresentative(HOLDER,OTHER,view->callbacks.incrementAndGet()));
        assertEquals(0,callbacks.get());assertEquals(List.of("nationOf:"+HOLDER),nativeState.calls);
    }

    @Test void noCurrentMembershipAndAnActuallyDissolvedNationBothRejectWithoutInvokingCallback(){
        AtomicInteger callbacks=new AtomicInteger();UUID unknown=UUID.fromString("00000000-0000-4000-8000-000000000099");
        assertThrows(SecurityException.class,()->authority.withRepresentative(unknown,OWN,view->callbacks.incrementAndGet()));assertEquals(List.of("nationOf:"+unknown),nativeState.calls);
        nativeState.calls.clear();synchronized(nativeState.lock){nativeState.existing.remove(OWN);}
        assertThrows(SecurityException.class,()->authority.withRepresentative(HOLDER,OWN,view->callbacks.incrementAndGet()));assertEquals(0,callbacks.get());assertEquals(List.of("nationOf:"+HOLDER,"exists:"+OWN),nativeState.calls);
    }

    @Test void nullBlankMalformedAndNoncanonicalExpectedNationsFailBeforeNativeAccess(){
        AtomicInteger suppliers=new AtomicInteger(),callbacks=new AtomicInteger();var guarded=new StarCoreNationalVoteAuthority(()->{suppliers.incrementAndGet();return nativeState;},Clock.fixed(NOW,ZoneOffset.UTC));
        for(String invalid:Arrays.asList(null,""," ","river","/op someone",OWN.toUpperCase(Locale.ROOT),"aaaaaaaa-1111-4111-8111-11111111111"))
            assertThrows(SecurityException.class,()->guarded.withRepresentative(HOLDER,invalid,view->callbacks.incrementAndGet()),Objects.toString(invalid,"null"));
        assertEquals(0,suppliers.get());assertEquals(0,callbacks.get());assertTrue(nativeState.calls.isEmpty());
    }

    @Test void missingNativeCapabilityAndDefaultDenyingAuthorityNeverRunOperations(){
        AtomicInteger callbacks=new AtomicInteger();var missing=new StarCoreNationalVoteAuthority(()->null,Clock.fixed(NOW,ZoneOffset.UTC));assertFalse(missing.available());
        assertThrows(SecurityException.class,()->missing.withRepresentative(HOLDER,OWN,view->callbacks.incrementAndGet()));
        assertThrows(SecurityException.class,()->NationalVoteAuthority.denying().withRepresentative(HOLDER,OWN,view->callbacks.incrementAndGet()));assertEquals(0,callbacks.get());
    }

    @Test void nullActorOrOperationCannotReachNativeReads(){
        AtomicInteger callbacks=new AtomicInteger();assertThrows(NullPointerException.class,()->authority.withRepresentative(null,OWN,view->callbacks.incrementAndGet()));
        assertThrows(NullPointerException.class,()->authority.withRepresentative(HOLDER,OWN,null));assertEquals(0,callbacks.get());assertTrue(nativeState.calls.isEmpty());
    }

    @Test void nativeReadFailuresPropagateAsTheirOriginalExceptionsRatherThanMissingNationResults(){
        for(String getter:List.of("nationOf","exists","holder")) {
            nativeState=new NativeFixture();IOException unavailable=new IOException("committed authority read unavailable: "+getter);nativeState.failure=getter;nativeState.readFailure=unavailable;AtomicInteger callbacks=new AtomicInteger();
            assertSame(unavailable,assertThrows(IOException.class,()->authority.withRepresentative(HOLDER,OWN,view->callbacks.incrementAndGet())));assertEquals(0,callbacks.get());assertFalse(Thread.holdsLock(nativeState.lock));
        }
    }

    @Test void aSupplierFailureIsUnavailableForDetectionAndStillPropagatesForAttemptedAuthorization(){
        IllegalStateException unavailable=new IllegalStateException("native registry unavailable");var failed=new StarCoreNationalVoteAuthority(()->{throw unavailable;},Clock.fixed(NOW,ZoneOffset.UTC));AtomicInteger callbacks=new AtomicInteger();
        assertFalse(failed.available());assertSame(unavailable,assertThrows(IllegalStateException.class,()->failed.withRepresentative(HOLDER,OWN,view->callbacks.incrementAndGet())));assertEquals(0,callbacks.get());
    }

    @Test void missingMonitorCannotExecuteAnAuthorizedCallback(){
        NativeFixture broken=new NativeFixture(){@Override public Object monitor(){return null;}};AtomicInteger callbacks=new AtomicInteger();var denied=new StarCoreNationalVoteAuthority(()->broken,Clock.fixed(NOW,ZoneOffset.UTC));
        assertThrows(NullPointerException.class,()->denied.withRepresentative(HOLDER,OWN,view->callbacks.incrementAndGet()));assertEquals(0,callbacks.get());assertTrue(broken.calls.isEmpty());
    }

    @Test void waitingForNativeMonitorRevalidatesTransferredOwnershipThenAdmitsTheNewHolder()throws Exception {
        AtomicInteger oldCallbacks=new AtomicInteger();Future<String> waiting;
        try(var executor=Executors.newSingleThreadExecutor()) {
            synchronized(nativeState.lock) {
                waiting=executor.submit(()->authority.withRepresentative(HOLDER,OWN,view->{oldCallbacks.incrementAndGet();return "must-not-commit";}));
                assertTrue(nativeState.monitorRequested.await(3,TimeUnit.SECONDS),"worker must request the actual native monitor");assertFalse(waiting.isDone());assertTrue(nativeState.calls.isEmpty(),"no native identity may be prefetched before its mutation monitor");
                nativeState.transfer(OWN,SUCCESSOR);
            }
            ExecutionException rejected=assertThrows(ExecutionException.class,()->waiting.get(3,TimeUnit.SECONDS));assertInstanceOf(SecurityException.class,rejected.getCause());assertEquals(0,oldCallbacks.get());
            assertEquals("successor-commit",authority.withRepresentative(SUCCESSOR,OWN,view->{assertEquals(SUCCESSOR,view.actorId());assertTrue(Thread.holdsLock(nativeState.lock));return "successor-commit";}));
        }
    }

    @Test void everyViewGetterAndNationLookupExpiresOnTheOriginalThreadAfterCallbackReturns()throws Exception {
        NationalVoteAuthority.View escaped=authority.withRepresentative(HOLDER,OWN,view->view);assertInvalidView(escaped);assertFalse(Thread.holdsLock(nativeState.lock));
    }

    @Test void viewCannotBeUsedFromAnotherThreadEvenWhileItsOwnerCallbackStillHoldsTheMonitor()throws Exception {
        AtomicReference<NationalVoteAuthority.View> escaped=new AtomicReference<>();CountDownLatch entered=new CountDownLatch(1),finish=new CountDownLatch(1);
        try(var executor=Executors.newSingleThreadExecutor()) {
            Future<String> owner=executor.submit(()->authority.withRepresentative(HOLDER,OWN,view->{escaped.set(view);entered.countDown();assertTrue(finish.await(3,TimeUnit.SECONDS),"bounded test coordination only");return "owner-finished";}));
            try {assertTrue(entered.await(3,TimeUnit.SECONDS));assertNotNull(escaped.get());assertInvalidView(escaped.get());}
            finally {finish.countDown();}
            assertEquals("owner-finished",owner.get(3,TimeUnit.SECONDS));assertInvalidView(escaped.get());
        }
    }

    @Test void callbackExceptionClosesTheViewAndReleasesNativeMonitorForAnotherThread()throws Exception {
        AtomicReference<NationalVoteAuthority.View> escaped=new AtomicReference<>();IOException failedCommit=new IOException("local transition rolled back");
        assertSame(failedCommit,assertThrows(IOException.class,()->authority.withRepresentative(HOLDER,OWN,view->{escaped.set(view);assertTrue(Thread.holdsLock(nativeState.lock));throw failedCommit;})));
        assertInvalidView(escaped.get());assertFalse(Thread.holdsLock(nativeState.lock));
        try(var executor=Executors.newSingleThreadExecutor()){assertEquals("released",executor.submit(()->{synchronized(nativeState.lock){return "released";}}).get(3,TimeUnit.SECONDS));}
    }

    @Test void nativeExistenceFailureInsideCallbackPropagatesAndStillClosesItsView()throws Exception {
        AtomicReference<NationalVoteAuthority.View> escaped=new AtomicReference<>();IOException unavailable=new IOException("cannot prove another nation's existence");nativeState.failingExistenceNation=OTHER;nativeState.readFailure=unavailable;
        assertSame(unavailable,assertThrows(IOException.class,()->authority.withRepresentative(HOLDER,OWN,view->{escaped.set(view);return view.nationExists(OTHER);})));assertInvalidView(escaped.get());assertFalse(Thread.holdsLock(nativeState.lock));
    }

    @Test void viewRejectsMalformedNationIdsWithoutConsultingNativeExistence()throws Exception {
        authority.withRepresentative(HOLDER,OWN,view->{int prior=nativeState.calls.size();for(String invalid:Arrays.asList(null,"",OTHER.toUpperCase(Locale.ROOT),"foreign-name"))assertThrows(IllegalArgumentException.class,()->view.nationExists(invalid));assertEquals(prior,nativeState.calls.size());return null;});
    }

    @Test void arbitraryServiceImplementationCannotPretendToBeTheAuditedNationModule(){
        StarCoreBridge bridge=mock(StarCoreBridge.class);when(bridge.service("module.nation.NationService")).thenReturn(new Object());var unknown=new StarCoreNationalVoteAuthority(bridge);AtomicInteger callbacks=new AtomicInteger();
        assertFalse(unknown.available());assertThrows(SecurityException.class,()->unknown.withRepresentative(HOLDER,OWN,view->callbacks.incrementAndGet()));assertEquals(0,callbacks.get());verify(bridge,atLeastOnce()).service("module.nation.NationService");
    }

    private static void assertInvalidView(NationalVoteAuthority.View view) {
        assertNotNull(view);assertThrows(IllegalStateException.class,view::actorId);assertThrows(IllegalStateException.class,view::nationId);assertThrows(IllegalStateException.class,view::checkedAt);assertThrows(IllegalStateException.class,view::issuer);assertThrows(IllegalStateException.class,view::evidence);assertThrows(IllegalStateException.class,()->view.nationExists(OWN));
    }

    private static class NativeFixture implements StarCoreNationalVoteAuthority.NativeAccess {
        final Object lock=new Object();final CountDownLatch monitorRequested=new CountDownLatch(1);final List<String> calls=new ArrayList<>();final List<Thread> readThreads=new ArrayList<>();
        final Map<UUID,String> membership=new HashMap<>();final Map<String,UUID> holders=new HashMap<>();final Set<String> existing=new HashSet<>();
        String failure,failingExistenceNation;Exception readFailure;
        NativeFixture(){membership.put(HOLDER,OWN);membership.put(MEMBER,OWN);membership.put(SUCCESSOR,OTHER);holders.put(OWN,HOLDER);holders.put(OTHER,SUCCESSOR);existing.addAll(List.of(OWN,OTHER));}
        @Override public Object monitor(){monitorRequested.countDown();return lock;}
        private void read(String name,String call)throws Exception {assertTrue(Thread.holdsLock(lock),"native committed identities must be read under the mutation monitor");calls.add(call);readThreads.add(Thread.currentThread());if(name.equals(failure))throw readFailure;}
        @Override public String nationOf(UUID actor)throws Exception {read("nationOf","nationOf:"+actor);return membership.getOrDefault(actor,"");}
        @Override public boolean exists(String nation)throws Exception {read("exists","exists:"+nation);if(nation.equals(failingExistenceNation))throw readFailure;return existing.contains(nation);}
        @Override public boolean currentHolder(UUID actor,String nation)throws Exception {read("holder","holder:"+actor+":"+nation);return actor.equals(holders.get(nation));}
        void transfer(String nation,UUID next){assertTrue(Thread.holdsLock(lock));membership.put(next,nation);holders.put(nation,next);}
    }
}
