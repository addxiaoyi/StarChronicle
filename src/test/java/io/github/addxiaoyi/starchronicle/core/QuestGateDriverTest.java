package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.api.quest.*;
import io.github.addxiaoyi.starchronicle.api.quest.QuestObservation.Status;
import io.github.addxiaoyi.starchronicle.api.quest.QuestRequest.Mode;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition.*;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Controlled native futures and real SQLite. Every wait is bounded; no sleeping server loop. */
class QuestGateDriverTest {
    @TempDir Path directory;
    final Instant now=Instant.parse("2026-11-20T12:00:00Z");
    final Clock clock=Clock.fixed(now,ZoneOffset.UTC);
    final UUID a=new UUID(0,1),b=new UUID(0,2);
    final String issuer="starcore.quest.evidence.v1";
    final List<String> diagnostics=new CopyOnWriteArrayList<>();
    final List<String> order=new CopyOnWriteArrayList<>();
    final AtomicReference<Throwable> backgroundFailure=new AtomicReference<>();
    final ExecutorService stateExecutor=Executors.newSingleThreadExecutor(r->new Thread(r,"quest-state-test"));
    final ScheduledExecutorService watcher=Executors.newSingleThreadScheduledExecutor(r->new Thread(r,"quest-observer-test"));
    SqliteStore store;RuleEngine engine;QuestGateDriver driver;String instanceId;
    String originalNation="river";
    List<UUID> players;

    PlayerContext context(UUID player){return new PlayerContext(player,originalNation,Map.of(),Set.of("starchronicle.use","quest.admit"));}
    void start(int members,Mode mode) {
        start(members,mode,"river");
    }
    void start(int members,Mode mode,String nation) {
        originalNation=nation;
        players=new ArrayList<>();for(int n=1;n<=members;n++)players.add(new UUID(0,n));
        var wait=new Node("Supplies",null,null,1,Map.of("yes",new Choice("Continue","end",null,null,List.of())),List.of(new Effect("supply","quest.await","native-supply",mode.name(),"quest.admit")),false);
        var story=new StoryDefinition("driver","1","Driver",Scope.WORLD,List.of("ROOT"),null,"wait",0,Map.of("wait",wait,"end",new Node("End",null,null,1,Map.of(),List.of(),true)));
        store=new SqliteStore(directory.resolve("driver.db"));engine=new RuleEngine(store,List.of(story),clock);
        instanceId=engine.accept(new WorldEvent("driver:source","ROOT",now,new HashSet<>(players),nation.isBlank()?Set.of():Set.of(nation),Map.of(),"native-test","committed source"),players.stream().map(this::context).toList()).instanceIds().getFirst();
    }
    QuestGateDriver.StateDispatcher dispatcher(boolean inline) {
        return new QuestGateDriver.StateDispatcher(){
            public <T>CompletionStage<T> submit(Callable<T> work) {
                if(inline)try{return CompletableFuture.completedFuture(work.call());}catch(Throwable error){return CompletableFuture.failedFuture(error);}
                return CompletableFuture.supplyAsync(()->{try{return work.call();}catch(Exception error){throw new CompletionException(error);}},stateExecutor);
            }
        };
    }
    void driver(Duration timeout,int maxFlights,Function<UUID,CompletionStage<PlayerContext>> fresh,boolean inline) {
        driver=new QuestGateDriver(engine,dispatcher(inline),fresh,clock,timeout,maxFlights,diagnostics::add);
    }
    void driver(){driver(Duration.ofSeconds(3),8,id->CompletableFuture.completedFuture(context(id)),false);}
    void poll(Set<UUID> online)throws Exception {driver.poll(online).toCompletableFuture().get(3,TimeUnit.SECONDS);}
    void poll()throws Exception {poll(new HashSet<>(players));}
    void await(BooleanSupplier test,String message)throws Exception {
        CountDownLatch satisfied=new CountDownLatch(1);
        ScheduledFuture<?> observation=watcher.scheduleAtFixedRate(()->{try{if(test.getAsBoolean())satisfied.countDown();}catch(Throwable error){backgroundFailure.compareAndSet(null,error);satisfied.countDown();}},0,5,TimeUnit.MILLISECONDS);
        try{assertTrue(satisfied.await(4,TimeUnit.SECONDS),message);}finally{observation.cancel(false);}
        if(backgroundFailure.get()!=null)throw new AssertionError("Asynchronous provider assertion failed",backgroundFailure.get());
    }
    void idle()throws Exception {await(()->driver.inFlight()==0,"Quest flight did not settle");}
    QuestGateLedger.Gate saved(String id){return store.transaction(c->QuestGateLedger.find(c,id));}
    QuestGateLedger.Gate own(UUID owner){return engine.pendingQuestGates(new HashSet<>(players),100).stream().filter(g->g.playerId().equals(owner)).findFirst().orElseThrow();}
    long history(){return engine.history(null,null,0,100).stream().filter(e->e.type().equals("quest.completed")).count();}
    NationProof nation(QuestRequest request){return new NationProof(NationProof.State.KNOWN_NATION,request.expectedNationId(),now,"native-country","country checked at native transition");}
    AcceptedRun accepted(QuestRequest request){return new AcceptedRun(request.bindingId(),"run:"+request.bindingId(),request.playerId(),request.questId(),"a".repeat(64),now,nation(request),issuer,"canonical native acceptance");}
    QuestObservation observation(QuestRequest request,Status status) {
        if(status==Status.COMMITTED) {
            var r=accepted(request);var proof=new CommittedCompletion(r.bindingId(),r.runId(),"completion:"+request.bindingId(),r.playerId(),r.questId(),r.definitionFingerprint(),r.startedAt(),now,now,r.acceptedNation(),nation(request),issuer,r.evidence(),"canonical durable native completion");
            return new QuestObservation(status,r,proof,now,"committed");
        }
        return new QuestObservation(status,status==Status.ACTIVE?accepted(request):null,null,now,status.name());
    }
    final class Provider implements QuestEvidenceProvider {
        final String id;final AtomicInteger lookups=new AtomicInteger(),admissions=new AtomicInteger();
        final BlockingQueue<QuestRequest> lookupRequests=new LinkedBlockingQueue<>(),admissionRequests=new LinkedBlockingQueue<>();
        volatile Function<QuestRequest,CompletionStage<QuestObservation>> onLookup=r->CompletableFuture.completedFuture(observation(r,Status.NOT_STARTED));
        volatile Function<QuestRequest,CompletionStage<QuestObservation>> onAdmission=r->CompletableFuture.completedFuture(observation(r,Status.ACTIVE));
        Provider(String id){this.id=id;}
        public String providerId(){return id;}
        private CompletionStage<QuestObservation> invoke(QuestRequest request,boolean admission) {
            try {
                assertFalse(Thread.holdsLock(store),"Provider invocation must not own Chronicle SQL monitor");
                // A second SQLite connection proves that the provider pin was committed,
                // rather than merely written on Chronicle's still-open transaction.
                try(var c=DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("driver.db").toAbsolutePath());var s=c.prepareStatement("SELECT provider_id,provider_pinned_at FROM quest_gates WHERE id=?")) {
                    s.setString(1,request.bindingId());try(var r=s.executeQuery()){assertTrue(r.next());assertEquals(id,r.getString(1));assertNotNull(r.getObject(2));}
                }
                order.add((admission?"admit:":"lookup:")+request.bindingId());
                if(admission){admissions.incrementAndGet();admissionRequests.add(request);return onAdmission.apply(request);}
                lookups.incrementAndGet();lookupRequests.add(request);return onLookup.apply(request);
            }catch(Throwable failure){backgroundFailure.compareAndSet(null,failure);return CompletableFuture.failedFuture(failure);}
        }
        public CompletionStage<QuestObservation> lookup(QuestRequest request){return invoke(request,false);}
        public CompletionStage<QuestObservation> ensureStarted(QuestRequest request){return invoke(request,true);}
    }
    QuestRequest lookup(Provider p)throws Exception {var request=p.lookupRequests.poll(3,TimeUnit.SECONDS);assertNotNull(request,"No native lookup call");return request;}
    QuestRequest admission(Provider p)throws Exception {var request=p.admissionRequests.poll(3,TimeUnit.SECONDS);assertNotNull(request,"No native admission call");return request;}
    @AfterEach void close()throws Exception {
        if(driver!=null)driver.close();stateExecutor.shutdown();assertTrue(stateExecutor.awaitTermination(4,TimeUnit.SECONDS));watcher.shutdownNow();if(engine!=null)engine.close();
    }

    @Test void canonicalLookupRunsAfterCommittedPinAndOutsideStorageBeforeAdmission()throws Exception {
        start(1,Mode.PLAYER);AtomicInteger fresh=new AtomicInteger();driver(Duration.ofSeconds(3),2,id->{fresh.incrementAndGet();return CompletableFuture.completedFuture(context(id));},false);var p=new Provider("native-a");driver.providers(List.of(p));poll();
        var lookup=lookup(p);var admit=admission(p);idle();assertEquals(lookup,admit);assertEquals(List.of("lookup:"+lookup.bindingId(),"admit:"+lookup.bindingId()),order);assertEquals(2,fresh.get());assertEquals(1,p.lookups.get());assertEquals(1,p.admissions.get());assertEquals(Status.ACTIVE,saved(lookup.bindingId()).status());assertNotNull(saved(lookup.bindingId()).acceptedRun());assertEquals(0,history());
    }
    @Test void canonicalCommittedLookupUnlocksWithoutAdmissionOrAutomaticStoryAdvance()throws Exception {
        start(1,Mode.PLAYER);driver();var p=new Provider("native-a");p.onLookup=r->CompletableFuture.completedFuture(observation(r,Status.COMMITTED));driver.providers(List.of(p));poll();lookup(p);idle();assertEquals(0,p.admissions.get());assertEquals(1,history());assertTrue(engine.questGateState(instanceId,context(a)).ready());assertFalse(engine.instances(a).getFirst().completed());
    }
    @Test void onlyCanonicalNotStartedCanAdmitAndOtherStatusesRemainEvidenceOnly()throws Exception {
        var statuses=List.of(Status.ACTIVE,Status.UNKNOWN,Status.CONFLICT,Status.UNSUPPORTED,Status.ADMITTING,Status.ABANDONED,Status.FAILED);start(statuses.size(),Mode.PLAYER);driver();var p=new Provider("native-a");
        p.onLookup=r->CompletableFuture.completedFuture(observation(r,statuses.get((int)r.playerId().getLeastSignificantBits()-1)));driver.providers(List.of(p));poll();await(()->p.lookups.get()==statuses.size(),"Not every status was looked up");idle();assertEquals(0,p.admissions.get());assertEquals(0,history());
        for(var player:players)assertFalse(engine.questGateState(instanceId,context(player)).ready());
    }
    @Test void anAlreadyAcceptedRunCannotReadmitEvenIfProviderReportsNotStarted()throws Exception {
        start(1,Mode.PLAYER);driver();var p=new Provider("native-a");p.onLookup=r->CompletableFuture.completedFuture(new QuestObservation(Status.NOT_STARTED,accepted(r),null,now,"canonical identity retained"));driver.providers(List.of(p));poll();var request=lookup(p);idle();assertNotNull(saved(request.bindingId()).acceptedRun());assertEquals(0,p.admissions.get());assertEquals(0,history());
    }
    @Test void admissionTimeoutKeepsBackendAndLateReplyCannotCommitUntilCanonicalLookup()throws Exception {
        start(1,Mode.PLAYER);driver(Duration.ofMillis(500),2,id->CompletableFuture.completedFuture(context(id)),false);Provider original=new Provider("native-a"),replacement=new Provider("native-b");
        CompletableFuture<QuestObservation> pendingAdmission=new CompletableFuture<>();AtomicReference<Status> canonical=new AtomicReference<>(Status.NOT_STARTED);original.onLookup=r->CompletableFuture.completedFuture(observation(r,canonical.get()));original.onAdmission=r->pendingAdmission;driver.providers(List.of(original));poll();var request=lookup(original);admission(original);idle();
        assertEquals(Status.UNKNOWN,saved(request.bindingId()).status());assertEquals("native-a",saved(request.bindingId()).providerId());canonical.set(Status.UNKNOWN);driver.providers(List.of(replacement,original));poll();lookup(original);idle();assertEquals(1,original.admissions.get());assertEquals(0,replacement.lookups.get());
        pendingAdmission.complete(observation(request,Status.COMMITTED));assertEquals(0,history());assertFalse(saved(request.bindingId()).completed());
        canonical.set(Status.COMMITTED);poll();lookup(original);idle();assertEquals(1,original.admissions.get());assertEquals(1,history());assertTrue(saved(request.bindingId()).completed());
    }
    @Test void removedProviderLateCompletionCannotConsumeProofOrWriteHistory()throws Exception {
        start(1,Mode.PLAYER);driver();var p=new Provider("native-a");CompletableFuture<QuestObservation> pending=new CompletableFuture<>();p.onLookup=r->pending;driver.providers(List.of(p));poll();var request=lookup(p);driver.providers(List.of());pending.complete(observation(request,Status.COMMITTED));idle();assertEquals(0,history());assertFalse(saved(request.bindingId()).completed());assertNull(saved(request.bindingId()).completion());assertEquals("native-a",saved(request.bindingId()).providerId());assertEquals(0,p.admissions.get());
    }
    @Test void duplicateProviderIdsAreQuarantinedButRepeatedSameObjectIsOneProvider()throws Exception {
        start(1,Mode.PLAYER);driver();Provider first=new Provider("native-a"),conflict=new Provider("native-a");var gate=own(a);driver.providers(List.of(first,conflict));poll();idle();assertNull(saved(gate.id()).providerId());assertEquals(Status.UNSUPPORTED,saved(gate.id()).status());assertEquals(0,first.lookups.get());assertEquals(0,conflict.lookups.get());assertTrue(diagnostics.stream().anyMatch(s->s.contains("Duplicate")));
        driver.providers(List.of(first,first));poll();lookup(first);admission(first);idle();assertEquals(1,first.lookups.get());assertEquals(1,first.admissions.get());
    }
    @Test void pinnedOfflineOwnerCanLookupAndUnlockOriginalNationWithoutFreshAdmission()throws Exception {
        start(2,Mode.NATION);var gate=engine.prepareQuestGate(own(a).id(),"native-a",context(a));AtomicInteger fresh=new AtomicInteger();driver(Duration.ofSeconds(3),2,id->{fresh.incrementAndGet();return CompletableFuture.failedFuture(new AssertionError("Offline owner cannot admit"));},false);var p=new Provider("native-a");p.onLookup=r->CompletableFuture.completedFuture(observation(r,Status.COMMITTED));driver.providers(List.of(p));poll(Set.of());lookup(p);idle();assertEquals(0,fresh.get());assertEquals(0,p.admissions.get());assertEquals(1,history());assertTrue(engine.questGateState(instanceId,context(b)).ready());assertTrue(saved(gate.id()).completed());assertNull(own(b).providerId());
    }
    @Test void missingFreshPermissionRejectsBeforePinOrAnyNativeCall()throws Exception {
        start(1,Mode.PLAYER);var gate=own(a);driver(Duration.ofSeconds(3),2,id->CompletableFuture.completedFuture(new PlayerContext(id,"river",Map.of(),Set.of())),false);var p=new Provider("native-a");driver.providers(List.of(p));poll();idle();assertNull(saved(gate.id()).providerId());assertEquals(Status.UNSUPPORTED,saved(gate.id()).status());assertEquals(0,p.lookups.get());assertEquals(0,p.admissions.get());assertEquals(0,history());
    }
    @Test void permissionRevokedBetweenLookupAndAdmissionPreventsNativeAdmission()throws Exception {
        start(1,Mode.PLAYER);AtomicInteger fresh=new AtomicInteger();driver(Duration.ofSeconds(3),2,id->CompletableFuture.completedFuture(fresh.incrementAndGet()==1?context(id):new PlayerContext(id,"river",Map.of(),Set.of())),false);var p=new Provider("native-a");driver.providers(List.of(p));poll();var request=lookup(p);idle();assertEquals(2,fresh.get());assertEquals(0,p.admissions.get());assertEquals("native-a",saved(request.bindingId()).providerId());assertNull(saved(request.bindingId()).acceptedRun());assertEquals(0,history());
    }
    @Test void closeDiscardsLateRepliesAndFuturePolls()throws Exception {
        start(1,Mode.PLAYER);driver();var p=new Provider("native-a");CompletableFuture<QuestObservation> pending=new CompletableFuture<>();p.onLookup=r->pending;driver.providers(List.of(p));poll();var request=lookup(p);driver.close();pending.complete(observation(request,Status.COMMITTED));idle();poll();assertEquals(1,p.lookups.get());assertEquals(0,p.admissions.get());assertFalse(saved(request.bindingId()).completed());assertEquals(0,history());
    }
    @Test void repeatedPollsNeverDuplicateOneInflightBinding()throws Exception {
        start(3,Mode.PLAYER);driver(Duration.ofSeconds(3),1,id->CompletableFuture.completedFuture(context(id)),false);var p=new Provider("native-a");CompletableFuture<QuestObservation> pending=new CompletableFuture<>();p.onLookup=r->pending;driver.providers(List.of(p));poll();var request=lookup(p);
        for(int n=0;n<10;n++)poll();assertEquals(1,driver.inFlight());assertEquals(1,p.lookups.get());assertEquals(0,p.admissions.get());pending.complete(observation(request,Status.UNKNOWN));idle();
    }
    @Test void concurrentPollsRespectAtomicFlightCapacity()throws Exception {
        start(10,Mode.PLAYER);driver(Duration.ofSeconds(3),2,id->CompletableFuture.completedFuture(context(id)),true);var p=new Provider("native-a");Map<String,CompletableFuture<QuestObservation>> pending=new ConcurrentHashMap<>();p.onLookup=r->pending.computeIfAbsent(r.bindingId(),id->new CompletableFuture<>());driver.providers(List.of(p));
        try(var callers=Executors.newFixedThreadPool(8)) {
            CountDownLatch release=new CountDownLatch(1);List<Future<?>> polls=new ArrayList<>();for(int n=0;n<8;n++)polls.add(callers.submit(()->{assertTrue(release.await(3,TimeUnit.SECONDS));driver.poll(new HashSet<>(players)).toCompletableFuture().get(3,TimeUnit.SECONDS);return null;}));release.countDown();for(var call:polls)call.get(4,TimeUnit.SECONDS);
        }
        await(()->p.lookups.get()==2&&pending.size()==2,"Two flight slots were not occupied");assertEquals(2,driver.inFlight());assertEquals(2,pending.size());assertEquals(2,p.lookups.get());
        for(var request:List.copyOf(p.lookupRequests))pending.get(request.bindingId()).complete(observation(request,Status.UNKNOWN));idle();assertEquals(0,p.admissions.get());
    }
    @Test void pinnedUnknownBackendNeverFollowsNewProviderPriority()throws Exception {
        start(1,Mode.PLAYER);driver();Provider original=new Provider("native-a"),newPriority=new Provider("native-b");original.onLookup=r->CompletableFuture.completedFuture(observation(r,Status.UNKNOWN));driver.providers(List.of(original));poll();var request=lookup(original);idle();driver.providers(List.of(newPriority,original));poll();lookup(original);idle();assertEquals("native-a",saved(request.bindingId()).providerId());assertEquals(2,original.lookups.get());assertEquals(0,newPriority.lookups.get());assertEquals(0,original.admissions.get());assertEquals(0,newPriority.admissions.get());
    }
    @Test void nationalGateWithoutOriginalCountryWaitsWithoutPinOrNativeCall()throws Exception {
        start(1,Mode.NATION,"");var gate=own(a);AtomicInteger fresh=new AtomicInteger();
        driver(Duration.ofSeconds(3),2,id->{fresh.incrementAndGet();return CompletableFuture.completedFuture(context(id));},false);var p=new Provider("native-a");driver.providers(List.of(p));poll();idle();
        assertEquals(Status.UNSUPPORTED,saved(gate.id()).status());assertNull(saved(gate.id()).providerId());assertNull(saved(gate.id()).providerPinnedAt());assertEquals(0,p.lookups.get());assertEquals(0,p.admissions.get());assertEquals(0,fresh.get());assertEquals(0,history());assertFalse(engine.questGateState(instanceId,context(a)).ready());
    }
}
