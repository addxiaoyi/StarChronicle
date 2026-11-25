package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.PlayerContext;
import io.github.addxiaoyi.starchronicle.api.quest.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Bounded asynchronous native evidence pump. Storage callbacks finish before any provider
 * invocation. A timeout only permits canonical lookup of the same pinned backend; it never
 * means completion or permission to switch providers. No native completion/reward API exists here. */
public final class QuestGateDriver implements AutoCloseable {
    @FunctionalInterface public interface StateDispatcher {
        <T> CompletionStage<T> submit(Callable<T> operation);
    }
    private static final class Lease {
        final String id; final QuestEvidenceProvider provider;
        Lease(String id,QuestEvidenceProvider provider){this.id=id;this.provider=provider;}
    }
    private final RuleEngine engine;
    private final StateDispatcher state;
    private final Function<UUID,CompletionStage<PlayerContext>> freshContext;
    private final Clock clock;
    private final long timeoutMillis;
    private final int maxFlights;
    private final ExecutorService calls;
    private final Consumer<String> diagnostic;
    private final Set<String> flights=ConcurrentHashMap.newKeySet();
    private volatile List<Lease> providers=List.of();
    private volatile boolean closed;

    public QuestGateDriver(RuleEngine engine,StateDispatcher state,
                           Function<UUID,CompletionStage<PlayerContext>> freshContext,
                           Clock clock,Duration timeout,int maxFlights,Consumer<String> diagnostic) {
        this.engine=Objects.requireNonNull(engine);this.state=Objects.requireNonNull(state);
        this.freshContext=Objects.requireNonNull(freshContext);this.clock=Objects.requireNonNull(clock);
        timeoutMillis=Objects.requireNonNull(timeout).toMillis();
        if(timeoutMillis<1||timeoutMillis>60000||maxFlights<1||maxFlights>32)throw new IllegalArgumentException("Invalid quest driver bounds");
        this.maxFlights=maxFlights;this.diagnostic=Objects.requireNonNull(diagnostic);
        calls=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(32),r->{
            Thread t=new Thread(r,"StarChronicle-quest-provider");t.setDaemon(true);return t;
        },new ThreadPoolExecutor.AbortPolicy());
    }

    /** Host supplies providers in priority order outside SQL/native authority locks.
     * Duplicate IDs are quarantined, rather than choosing a potentially different backend. */
    public synchronized void providers(List<QuestEvidenceProvider> next) {
        if(closed)return;
        Map<String,List<QuestEvidenceProvider>> grouped=new LinkedHashMap<>();
        for(var provider:List.copyOf(next))try {
            String id=provider.providerId();
            if(id==null||id.isBlank()||id.length()>256||id.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid provider ID");
            var group=grouped.computeIfAbsent(id,k->new ArrayList<>());
            if(group.stream().noneMatch(p->p==provider))group.add(provider);
        }catch(RuntimeException failure){diagnostic.accept("Quest provider excluded: "+failure.getMessage());}
        List<Lease> accepted=new ArrayList<>();
        for(var entry:grouped.entrySet()) {
            if(entry.getValue().size()!=1){diagnostic.accept("Duplicate quest provider ID quarantined: "+entry.getKey());continue;}
            QuestEvidenceProvider provider=entry.getValue().getFirst();
            Lease old=providers.stream().filter(l->l.id.equals(entry.getKey())&&l.provider==provider).findFirst().orElse(null);
            accepted.add(old==null?new Lease(entry.getKey(),provider):old);
        }
        providers=List.copyOf(accepted);
    }

    /** Queue one bounded scan; individual flights remain asynchronous and are deduplicated. */
    public CompletionStage<Void> poll(Set<UUID> onlineOwners) {
        if(closed)return CompletableFuture.completedFuture(null);
        Set<UUID> online=Set.copyOf(onlineOwners);
        return state.submit(()->engine.pendingQuestGates(online,100)).thenAccept(gates->{
            for(var gate:gates) {
                if(closed)break;
                synchronized(flights){if(flights.size()>=maxFlights)break;if(!flights.add(gate.id()))continue;}
                CompletionStage<Void> attempt;
                try{attempt=start(gate,online);}catch(Throwable failure){attempt=CompletableFuture.failedFuture(failure);}
                attempt.whenComplete((ignored,error)->{
                    flights.remove(gate.id());
                    if(error!=null&&!closed)diagnostic.accept("Quest gate retained: "+gate.id()+"; "+message(error));
                });
            }
        });
    }

    private CompletionStage<Void> start(QuestGateLedger.Gate gate,Set<UUID> online) {
        if(gate.mode()==QuestRequest.Mode.NATION&&gate.expectedNationId().isBlank())
            return update(()->engine.questUnavailable(gate.id(),gate.bindingId(),"原成员没有可证明的国家，国家任务保持等待且不接取"));
        Lease lease=gate.providerId()==null?providers.stream().findFirst().orElse(null):
            providers.stream().filter(l->l.id.equals(gate.providerId())).findFirst().orElse(null);
        if(lease==null)return update(()->engine.questUnavailable(gate.id(),gate.bindingId(),"未安装所需的可信原生任务完成凭证服务；保持等待"));
        CompletionStage<QuestGateLedger.Gate> pinned=gate.providerId()==null?
            fresh(gate.playerId()).thenCompose(actor->state.submit(()->{
                requireLease(lease);return engine.prepareQuestGate(gate.id(),lease.id,actor);
            })):CompletableFuture.completedFuture(gate);
        return pinned.thenCompose(current->call(lease,current.request(),false).thenCompose(observation->
            state.submit(()->{
                requireLease(lease);return engine.observeQuestGate(current.id(),current.bindingId(),lease.id,observation);
            }).thenCompose(mutation->{
                var latest=mutation.gate();
                // Only canonical absence permits an idempotent admission. A previously accepted
                // run, an uncertain result, readiness or completed-ever never does so.
                if(!latest.bindingId().equals(current.bindingId())||!latest.active()||latest.completed()||latest.acceptedRun()!=null||observation.status()!=QuestObservation.Status.NOT_STARTED||!online.contains(latest.playerId()))
                    return CompletableFuture.completedFuture(null);
                return fresh(latest.playerId()).thenCompose(actor->state.submit(()->{
                    requireLease(lease);return engine.prepareQuestGate(latest.id(),lease.id,actor);
                })).thenCompose(ready->call(lease,ready.request(),true)).thenCompose(result->update(()->{
                    requireLease(lease);return engine.observeQuestGate(latest.id(),latest.bindingId(),lease.id,result);
                }));
            }))
        ).exceptionallyCompose(error->{
            if(closed)return CompletableFuture.completedFuture(null);
            // Keep the pinned provider and immutable accepted identity. Even a removed
            // provider's late reply cannot commit; a future lease must query canonical state.
            return update(()->{
                var status=new QuestObservation(QuestObservation.Status.UNKNOWN,null,null,now(),bounded(message(error)));
                if(gate.providerId()==null) {
                    // Pin may have failed before any native call. Do not invent a pin.
                    var pinnedNow=engine.questGate(gate.id());
                    if(pinnedNow==null||pinnedNow.providerId()==null)return engine.questUnavailable(gate.id(),gate.bindingId(),bounded(message(error)));
                }
                return engine.observeQuestGate(gate.id(),gate.bindingId(),lease.id,status);
            });
        });
    }

    /** Explicit owner action; uses the same flight lock and provider lease as automatic polling. */
    public CompletionStage<QuestGateLedger.Gate> recover(String gateId,UUID player,int expectedAttempt,boolean retry) {
        synchronized(flights) {
            if(closed||flights.size()>=maxFlights||!flights.add(gateId))
                return CompletableFuture.failedFuture(new IllegalStateException("任务正在核对，请稍后刷新重试"));
        }
        CompletionStage<QuestGateLedger.Gate> result=fresh(player)
            .thenCompose(actor->state.submit(()->engine.prepareQuestRecovery(gateId,expectedAttempt,actor)))
            .thenCompose(gate->{
                Lease lease=providers.stream().filter(p->p.id.equals(gate.providerId())).findFirst().orElseThrow(()->new IllegalStateException("原任务提供者不可用"));
                if(!(lease.provider instanceof QuestRecoveryProvider recovery))throw new IllegalStateException("原任务提供者尚未支持安全恢复");
                if(retry&&gate.status()!=QuestObservation.Status.FAILED&&gate.status()!=QuestObservation.Status.ABANDONED)
                    throw new IllegalStateException("只有已确认失败或放弃的任务可重试；UNKNOWN 请先对账");
                return invoke(lease,()->retry?recovery.lookup(gate.request()):recovery.reconcile(gate.request()))
                    .thenCompose(observation->state.submit(()->{
                        requireLease(lease);return engine.observeQuestGate(gate.id(),gate.bindingId(),lease.id,Objects.requireNonNull(observation)).gate();
                    })).thenCompose(current->{
                        if(!retry)return CompletableFuture.completedFuture(current);
                        return invoke(lease,()->recovery.termination(gate.request())).thenCompose(proof->{
                            if(proof==null)throw new IllegalStateException("原生任务尚无持久终止凭据，未开启新轮次");
                            return fresh(player).thenCompose(actor->state.submit(()->{
                                requireLease(lease);return engine.retryQuestGate(gateId,expectedAttempt,lease.id,proof,actor);
                            }));
                        });
                    });
            });
        return result.whenComplete((value,error)->flights.remove(gateId));
    }

    private <T>CompletionStage<T> invoke(Lease lease,Supplier<CompletionStage<T>> operation) {
        CompletableFuture<T> result=new CompletableFuture<>();
        try {calls.execute(()->{
            if(result.isDone())return;
            try {requireLease(lease);CompletionStage<T> future=Objects.requireNonNull(operation.get());
                future.whenComplete((value,error)->{if(error==null)result.complete(value);else result.completeExceptionally(error);});
            }catch(Throwable failure){result.completeExceptionally(failure);}
        });}catch(RejectedExecutionException full){result.completeExceptionally(full);}
        return result.orTimeout(timeoutMillis,TimeUnit.MILLISECONDS);
    }

    private CompletionStage<PlayerContext> fresh(UUID player) {
        CompletableFuture<PlayerContext> result=new CompletableFuture<>();
        try {
            CompletionStage<PlayerContext> snapshot=freshContext.apply(player);
            if(snapshot==null)throw new IllegalStateException("No player snapshot future");
            snapshot.whenComplete((context,error)->{if(error!=null)result.completeExceptionally(error);
                else if(context==null)result.completeExceptionally(new IllegalStateException("No current player snapshot"));
                else result.complete(context);});
        }catch(Throwable failure){result.completeExceptionally(failure);}
        return result.orTimeout(timeoutMillis,TimeUnit.MILLISECONDS);
    }
    private CompletionStage<QuestObservation> call(Lease lease,QuestRequest request,boolean admit) {
        CompletableFuture<QuestObservation> result=new CompletableFuture<>();
        try {calls.execute(()->{
            if(result.isDone())return;
            try {
                requireLease(lease);
                CompletionStage<QuestObservation> response=admit?lease.provider.ensureStarted(request):lease.provider.lookup(request);
                if(response==null)throw new IllegalStateException("Quest provider returned no future");
                response.whenComplete((value,error)->{
                    if(error!=null)result.completeExceptionally(error);
                    else if(value==null)result.completeExceptionally(new IllegalStateException("Quest provider returned no observation"));
                    else result.complete(value);
                });
            }catch(Throwable failure){result.completeExceptionally(failure);}
        });}catch(RejectedExecutionException full){result.completeExceptionally(full);}
        return result.orTimeout(timeoutMillis,TimeUnit.MILLISECONDS);
    }
    private CompletionStage<Void> update(Callable<?> work) {
        if(closed)return CompletableFuture.completedFuture(null);
        return state.submit(()->{if(!closed)work.call();return (Void)null;});
    }
    private void requireLease(Lease lease){if(closed||providers.stream().noneMatch(current->current==lease))throw new IllegalStateException("Quest provider lease expired");}
    private Instant now(){return Instant.ofEpochMilli(clock.millis());}
    private static String bounded(String message){return message.length()>2048?message.substring(0,2048):message;}
    private static String message(Throwable error){while(error.getCause()!=null&&(error instanceof CompletionException||error instanceof ExecutionException))error=error.getCause();return error.getClass().getSimpleName()+": "+Objects.toString(error.getMessage(),"provider call failed");}
    public int inFlight(){return flights.size();}
    @Override public synchronized void close(){closed=true;providers=List.of();calls.shutdownNow();}
}
