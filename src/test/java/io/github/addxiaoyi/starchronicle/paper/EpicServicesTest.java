package io.github.addxiaoyi.starchronicle.paper;

import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.api.epic.*;
import io.github.addxiaoyi.starchronicle.core.*;
import io.github.addxiaoyi.starchronicle.integration.StarCoreBridge;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real persistence at the asynchronous public service boundary. */
class EpicServicesTest {
    static final UUID ACTOR=UUID.randomUUID(),OTHER=UUID.randomUUID();
    static final String NA=UUID.randomUUID().toString(),NB=UUID.randomUUID().toString(),RELIEF=UUID.randomUUID().toString(),DOMAIN=UUID.randomUUID().toString();
    @TempDir Path temp;
    SqliteStore store;EpicCampaignLedger ledger;EpicOperation op;ChronicleService service;
    StarChroniclePlugin plugin;StarCoreBridge bridge;EpicOperationProvider provider;
    CompletableFuture<EpicObservation> nativeReply;
    void field(String name,Object value)throws Exception {Field f=StarChroniclePlugin.class.getDeclaredField(name);f.setAccessible(true);f.set(plugin,value);}
    @BeforeEach void setup()throws Exception {
        store=new SqliteStore(temp.resolve("service.db"));ledger=new EpicCampaignLedger(store);
        var plan=new EpicCampaignPlan("heir","heir-story","1","EPIC_SIGNUP_COMMITTED","epic.signup_campaign","heir","epic.relief_nation",
                List.of(new EpicPhasePolicy("aid",EpicPhasePolicy.Kind.PREPARATION,Set.of(new EpicRequirement(EpicRequirement.Type.RESOURCE_AID,"RELIEF","agricultural:10"))),
                        new EpicPhasePolicy("route",EpicPhasePolicy.Kind.FINAL_ROUTE,Set.of())));
        ledger.open(plan,"session",RELIEF,Instant.now());ledger.enroll("session",NA,ACTOR,0,Instant.now());ledger.enroll("session",NB,OTHER,1,Instant.now());
        ledger.seal("session",2,ACTOR,Instant.now());ledger.start("instance","session","source",Instant.now());
        op=new EpicOperation("op:aid","heir","instance","source","aid",EpicOperation.Type.RESOURCE_AID,ACTOR,NA,RELIEF,"agricultural:10","a".repeat(64),"starcore.resource",Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        ledger.prepare(op,"test.native",DOMAIN);
        plugin=mock(StarChroniclePlugin.class,CALLS_REAL_METHODS);bridge=mock(StarCoreBridge.class);
        var tasks=mock(RegionTasks.class);var worker=mock(ThreadPoolExecutor.class);var player=mock(Player.class);
        when(player.isOnline()).thenReturn(true);
        doAnswer(call->{call.getArgument(1,java.util.function.Consumer.class).accept(player);return null;}).when(tasks).playerOwned(eq(ACTOR),any(),any());
        doAnswer(call->{call.getArgument(0,Runnable.class).run();return null;}).when(worker).execute(any());
        when(bridge.context(ACTOR)).thenReturn(new PlayerContext(ACTOR,NA,Map.of(),Set.of("starchronicle.use","starcore.resource")));
        provider=mock(EpicOperationProvider.class,CALLS_REAL_METHODS);when(provider.providerId()).thenReturn("test.native");when(provider.authorityDomain()).thenReturn(DOMAIN);
        nativeReply=new CompletableFuture<>();when(provider.apply(op)).thenAnswer(call->{
            assertEquals(EpicOperationState.Status.UNKNOWN,ledger.operation(op.operationId()).status(),"Attempt must be durable before dispatch");return nativeReply;
        });
        field("running",new AtomicBoolean(true));field("tasks",tasks);field("gateway",bridge);field("worker",worker);
        field("engine",new RuleEngine(store,List.of(),java.time.Clock.systemUTC(),null,NationalVoteAuthority.denying(),io.github.addxiaoyi.starchronicle.memory.MemoryRecipeCatalog.empty(),ledger));
        field("permissions",Set.of("starchronicle.use","starcore.resource"));field("epicCampaigns",ledger);field("epicOperationProviders",List.of(provider));
        field("nationalVoteAuthority",new NationalVoteAuthority(){
            public <T>T withRepresentative(UUID actor,String nation,Authorized<T> action)throws Exception {
                return action.run(new View(){public UUID actorId(){return actor;}public String nationId(){return nation;}
                    public Instant checkedAt(){return Instant.now();}public String issuer(){return "test";}public String evidence(){return "native";}public boolean nationExists(String id){return true;}});
            }
        });
        Class<?> type=Arrays.stream(StarChroniclePlugin.class.getDeclaredClasses()).filter(c->c.getSimpleName().equals("Service")).findFirst().orElseThrow();
        Constructor<?> constructor=type.getDeclaredConstructor(StarChroniclePlugin.class);constructor.setAccessible(true);service=(ChronicleService)constructor.newInstance(plugin);
    }
    @AfterEach void close(){store.close();}
    EpicObservation committed(){return new EpicObservation(EpicObservation.Status.COMMITTED,
            new EpicReceipt("test.native",DOMAIN,op.operationId(),op.campaignId(),op.instanceId(),op.sourceEventId(),op.phaseId(),op.type(),op.actorId(),op.nationId(),op.target(),op.nativeReference(),op.definitionFingerprint(),op.permission(),"native:aid",Instant.now(),"durable receipt"),"ok");}
    @Test void overlappingAppliesDispatchOnceAndCannotReapplyUnknown() {
        var first=service.applyEpicOperation(op.operationId(),ACTOR,1).toCompletableFuture();
        assertFalse(first.isDone());
        assertThrows(CompletionException.class,()->service.applyEpicOperation(op.operationId(),ACTOR,1).toCompletableFuture().join());
        assertThrows(CompletionException.class,()->service.applyEpicOperation(op.operationId(),ACTOR,2).toCompletableFuture().join());
        nativeReply.complete(committed());assertEquals(EpicOperationState.Status.COMMITTED,first.join().status());
        assertEquals(2,service.contributions(null,null,0,10).toCompletableFuture().join().size());
        verify(provider,times(1)).apply(op);
    }
    @Test void removedProviderCannotCommitALateReplyAndRecoveryOnlyLooksUp()throws Exception {
        var first=service.applyEpicOperation(op.operationId(),ACTOR,1).toCompletableFuture();
        field("epicOperationProviders",List.of());nativeReply.complete(committed());
        assertThrows(CompletionException.class,first::join);assertEquals(EpicOperationState.Status.UNKNOWN,ledger.operation(op.operationId()).status());
        assertTrue(service.contributions(null,null,0,10).toCompletableFuture().join().isEmpty());
        field("epicOperationProviders",List.of(provider));when(provider.lookup(op)).thenReturn(CompletableFuture.completedFuture(committed()));
        var recovered=service.reconcileEpicOperation(op.operationId(),ACTOR,ledger.operation(op.operationId()).revision()).toCompletableFuture().join();
        assertEquals(EpicOperationState.Status.COMMITTED,recovered.status());verify(provider,times(1)).apply(op);verify(provider,times(1)).lookup(op);
    }
    @Test void lostNativeIdentityOrUsePermissionNeverClaimsOrDispatches() {
        for(PlayerContext context:List.of(new PlayerContext(ACTOR,NB,Map.of(),Set.of("starchronicle.use","starcore.resource")),
                new PlayerContext(ACTOR,NA,Map.of(),Set.of("starcore.resource")))) {
            when(bridge.context(ACTOR)).thenReturn(context);
            assertThrows(CompletionException.class,()->service.applyEpicOperation(op.operationId(),ACTOR,1).toCompletableFuture().join());
            assertEquals(EpicOperationState.Status.PREPARED,ledger.operation(op.operationId()).status());
        }
        verify(provider,never()).apply(any());
    }

    long rejectOperation() {
        ledger.claimAttempt(op.operationId(),1,Instant.now());
        return ledger.observe(op,new EpicObservation(EpicObservation.Status.REJECTED,null,"insufficient resources"),
                provider.providerId(),DOMAIN,Instant.now()).revision();
    }
    EpicRejectionProof rejectionProof() {
        return new EpicRejectionProof("test.native",DOMAIN,op,UUID.randomUUID().toString(),
                "INSUFFICIENT_RESOURCES",Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
    }
    @Test void retryRequiresDurableProofAndNeverInvokesTheNativeMutation() {
        long revision=rejectOperation();
        assertThrows(CompletionException.class,()->service.retryEpicOperation(op.operationId(),ACTOR,revision).toCompletableFuture().join());
        assertEquals(1,ledger.state("instance").operations().size());
        when(provider.rejectionProof(op)).thenReturn(CompletableFuture.completedFuture(Optional.of(rejectionProof())));
        var next=service.retryEpicOperation(op.operationId(),ACTOR,revision).toCompletableFuture().join();
        assertEquals(EpicOperationState.Status.PREPARED,next.status());assertNotEquals(op.operationId(),next.operation().operationId());
        assertEquals(EpicOperationState.Status.REJECTED,ledger.operation(op.operationId()).status());
        assertEquals(2,service.epicOperationAttempt(next.operation().operationId(),ACTOR).toCompletableFuture().join().attempt());
        verify(provider,never()).apply(any());verify(provider,never()).lookup(any());
    }
    @Test void asyncProofCannotBypassProviderLeaseOrFreshIdentity()throws Exception {
        long revision=rejectOperation();
        var pending=new CompletableFuture<Optional<EpicRejectionProof>>();when(provider.rejectionProof(op)).thenReturn(pending);
        var retry=service.retryEpicOperation(op.operationId(),ACTOR,revision).toCompletableFuture();
        assertFalse(retry.isDone());field("epicOperationProviders",List.of());pending.complete(Optional.of(rejectionProof()));
        assertThrows(CompletionException.class,retry::join);assertEquals(1,ledger.state("instance").operations().size());
        field("epicOperationProviders",List.of(provider));
        pending=new CompletableFuture<>();when(provider.rejectionProof(op)).thenReturn(pending);
        retry=service.retryEpicOperation(op.operationId(),ACTOR,revision).toCompletableFuture();
        when(bridge.context(ACTOR)).thenReturn(new PlayerContext(ACTOR,NB,Map.of(),Set.of("starchronicle.use","starcore.resource")));
        pending.complete(Optional.of(rejectionProof()));var failed=retry;
        assertThrows(CompletionException.class,failed::join);assertEquals(1,ledger.state("instance").operations().size());
    }
    @Test void asyncProofCannotBypassFreshNativePermission() {
        long revision=rejectOperation();var pending=new CompletableFuture<Optional<EpicRejectionProof>>();
        when(provider.rejectionProof(op)).thenReturn(pending);
        var retry=service.retryEpicOperation(op.operationId(),ACTOR,revision).toCompletableFuture();
        when(bridge.context(ACTOR)).thenReturn(new PlayerContext(ACTOR,NA,Map.of(),Set.of("starchronicle.use")));
        pending.complete(Optional.of(rejectionProof()));
        assertThrows(CompletionException.class,retry::join);assertEquals(1,ledger.state("instance").operations().size());
    }
    @Test void overlappingRetriesWithOneProofAppendOnePreparedSuccessor() {
        long revision=rejectOperation();var pending=new CompletableFuture<Optional<EpicRejectionProof>>();
        when(provider.rejectionProof(op)).thenReturn(pending);
        var first=service.retryEpicOperation(op.operationId(),ACTOR,revision).toCompletableFuture();
        var second=service.retryEpicOperation(op.operationId(),ACTOR,revision).toCompletableFuture();
        pending.complete(Optional.of(rejectionProof()));
        assertNotEquals(first.isCompletedExceptionally(),second.isCompletedExceptionally());
        var winner=first.isCompletedExceptionally()?second:first;var loser=first.isCompletedExceptionally()?first:second;
        assertEquals(EpicOperationState.Status.PREPARED,winner.join().status());assertThrows(CompletionException.class,loser::join);
        assertEquals(2,ledger.state("instance").operations().size());verify(provider,never()).apply(any());
    }
    @Test void unknownAndPreparedRefuseRetryBeforeProofLookup() {
        assertThrows(CompletionException.class,()->service.retryEpicOperation(op.operationId(),ACTOR,1).toCompletableFuture().join());
        ledger.claimAttempt(op.operationId(),1,Instant.now());
        when(provider.lookup(op)).thenReturn(CompletableFuture.completedFuture(new EpicObservation(EpicObservation.Status.ABSENT,null,"absent")));
        var unknown=service.reconcileEpicOperation(op.operationId(),ACTOR,2).toCompletableFuture().join();
        assertEquals(EpicOperationState.Status.UNKNOWN,unknown.status());
        assertThrows(CompletionException.class,()->service.retryEpicOperation(op.operationId(),ACTOR,unknown.revision()).toCompletableFuture().join());
        verify(provider,never()).rejectionProof(any());verify(provider,never()).apply(any());
    }
    @Test void asyncProofCannotBypassCurrentRepresentative()throws Exception {
        long revision=rejectOperation();var pending=new CompletableFuture<Optional<EpicRejectionProof>>();
        when(provider.rejectionProof(op)).thenReturn(pending);
        var retry=service.retryEpicOperation(op.operationId(),ACTOR,revision).toCompletableFuture();
        field("nationalVoteAuthority",NationalVoteAuthority.denying());pending.complete(Optional.of(rejectionProof()));
        assertThrows(CompletionException.class,retry::join);assertEquals(1,ledger.state("instance").operations().size());
    }
    @Test void asyncProofCannotBypassCurrentProviderCapability() {
        long revision=rejectOperation();var pending=new CompletableFuture<Optional<EpicRejectionProof>>();
        when(provider.rejectionProof(op)).thenReturn(pending);
        var retry=service.retryEpicOperation(op.operationId(),ACTOR,revision).toCompletableFuture();
        when(provider.supports(op)).thenReturn(false);pending.complete(Optional.of(rejectionProof()));
        assertThrows(CompletionException.class,retry::join);assertEquals(1,ledger.state("instance").operations().size());
    }
}
