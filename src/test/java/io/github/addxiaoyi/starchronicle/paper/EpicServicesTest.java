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
        op=new EpicOperation("op:aid","heir","instance","source","aid",EpicOperation.Type.RESOURCE_AID,ACTOR,NA,RELIEF,"agricultural:10","a".repeat(64),"starcore.resource",Instant.now());
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
        field("engine",new RuleEngine(store,List.of(),java.time.Clock.systemUTC()));
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
        verify(provider,times(1)).apply(op);
    }
    @Test void removedProviderCannotCommitALateReplyAndRecoveryOnlyLooksUp()throws Exception {
        var first=service.applyEpicOperation(op.operationId(),ACTOR,1).toCompletableFuture();
        field("epicOperationProviders",List.of());nativeReply.complete(committed());
        assertThrows(CompletionException.class,first::join);assertEquals(EpicOperationState.Status.UNKNOWN,ledger.operation(op.operationId()).status());
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
}
