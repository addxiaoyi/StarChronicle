package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.PlayerContext;
import io.github.addxiaoyi.starchronicle.api.aid.*;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;

import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** Coordinates a single StarCore treasury transfer with Chronicle evidence.
 * Native calls are made after admission commits and are never retried blindly. */
public final class NationalAidService {
    public record Result(AidLedger.Status status, boolean nativeInvoked,
                         String detail, Contribution contribution) {
        public Result {
            Objects.requireNonNull(status);
            detail = detail == null ? "" : detail;
        }
        public boolean terminal() { return status == AidLedger.Status.COMMITTED || status == AidLedger.Status.REJECTED; }
    }

    private record Admission(boolean fresh, AidLedger.Row existing) {}

    private final SqliteStore store;
    private final RuleEngine engine;
    private final AidProvider provider;
    private final Clock clock;
    private final Executor stateExecutor;
    private final String issuer;
    private final String authorityDomain;

    public NationalAidService(SqliteStore store, RuleEngine engine, AidProvider provider, Clock clock) {
        this(store, engine, provider, clock, Runnable::run);
    }

    public NationalAidService(SqliteStore store, RuleEngine engine, AidProvider provider, Clock clock,
                              Executor stateExecutor) {
        this.store = Objects.requireNonNull(store);
        this.engine = Objects.requireNonNull(engine);
        this.provider = Objects.requireNonNull(provider);
        this.clock = Objects.requireNonNull(clock);
        this.stateExecutor = Objects.requireNonNull(stateExecutor);
        this.issuer = AidRequest.token(provider.providerId(), 256);
        this.authorityDomain = AidRequest.uuid(provider.authorityDomain());
        store.transaction(c -> { AidLedger.createSchema(c); return null; });
    }

    /** First submission only. An existing UNKNOWN/ADMITTED row is returned for
     * review and reconciliation; this method never invokes the provider twice. */
    public CompletionStage<Result> submit(AidRequest request, PlayerContext actor,
                                          Collection<PlayerContext> audience) {
        validateActor(request, actor);
        verifyProviderIdentity();
        List<PlayerContext> contexts = audience(actor, audience, request.payerId(), request.receiverId());
        return state(() -> {
            Admission admission = store.transaction(c -> {
                Optional<AidLedger.Row> old = AidLedger.find(c, issuer, request.outcomeId());
                if (old.isPresent()) {
                    verifySame(old.get(), request);
                    return new Admission(false, old.get());
                }
                AidLedger.admit(c, request, issuer, authorityDomain, clock.instant());
                return new Admission(true, AidLedger.find(c, issuer, request.outcomeId()).orElseThrow());
            });
            return new Prepared(contexts, admission);
        }).thenCompose(prepared -> {
            if (!prepared.admission().fresh()) return CompletableFuture.completedFuture(existing(prepared.admission().existing()));
            return invoke(request, prepared.contexts(), true);
        });
    }

    /** Explicit operator/worker reconciliation. It may query the provider but
     * never submits money again. */
    public CompletionStage<Result> reconcile(AidRequest request, PlayerContext actor,
                                             Collection<PlayerContext> audience) {
        validateActor(request, actor);
        verifyProviderIdentity();
        List<PlayerContext> contexts = audience(actor, audience, request.payerId(), request.receiverId());
        return state(() -> {
            AidLedger.Row row = store.transaction(c -> AidLedger.find(c, issuer, request.outcomeId()).orElseThrow(
                    () -> new IllegalArgumentException("Unknown aid intent: " + request.outcomeId())));
            verifySame(row, request);
            return new Reconciliation(contexts, row);
        }).thenCompose(reconciliation -> {
            AidLedger.Row row = reconciliation.row();
            if (row.status() == AidLedger.Status.COMMITTED || row.status() == AidLedger.Status.REJECTED)
                return CompletableFuture.completedFuture(existing(row));
            try {
                CompletionStage<AidObservation> lookup = Objects.requireNonNull(provider.lookup(request), "Aid provider returned null lookup stage");
                return lookup.handleAsync((observation, error) -> {
                    if (error != null) return CompletableFuture.completedFuture(settleUnknown(request, null, "Provider reconciliation failed: " + message(error), false));
                    return handleObservation(request, reconciliation.contexts(), observation, false, row.status());
                }, stateExecutor).thenCompose(stage -> stage);
            } catch (Throwable error) {
                return state(() -> settleUnknown(request, null, "Provider reconciliation failed: " + message(error), false));
            }
        });
    }

    Optional<AidLedger.Row> find(String transactionId) {
        AidRequest.token(transactionId, 256);
        return store.transaction(c -> AidLedger.find(c, issuer, transactionId));
    }

    private CompletionStage<Result> invoke(AidRequest request, List<PlayerContext> contexts, boolean nativeCall) {
        CompletionStage<AidObservation> call;
        try {
            verifyProviderIdentity();
            call = Objects.requireNonNull(provider.submit(request), "Aid provider returned null stage");
        }
        catch (Throwable error) { return CompletableFuture.completedFuture(settleUnknown(request, null, "Provider invocation failed: " + message(error), nativeCall)); }
        return call.handleAsync((observation, error) -> {
            if (error != null) return CompletableFuture.completedFuture(settleUnknown(request, null, "Provider invocation failed: " + message(error), nativeCall));
            return handleObservation(request, contexts, observation, nativeCall, AidLedger.Status.ADMITTED);
        }, stateExecutor).thenCompose(stage -> stage);
    }

    private CompletionStage<Result> handleObservation(AidRequest request, List<PlayerContext> contexts,
                                                       AidObservation observation, boolean nativeCall,
                                                       AidLedger.Status previous) {
        if (observation == null) return CompletableFuture.completedFuture(settleUnknown(request, null, "Provider returned no observation", nativeCall));
        if (observation.status() == AidObservation.Status.COMMITTED) {
            AidReceipt receipt = observation.receipt();
            if (receipt == null || !receipt.matches(request, issuer, authorityDomain))
                return CompletableFuture.completedFuture(settleUnknown(request, null, "Native receipt does not match the pinned request", nativeCall));
            try {
                String eventId = RuleEngine.aidEventId(issuer, request.outcomeId());
                engine.commitAid(request, receipt, issuer, authorityDomain, contexts);
                return CompletableFuture.completedFuture(new Result(AidLedger.Status.COMMITTED, nativeCall,
                        "Native receipt matched; aid history and story inbox committed", new Contribution(request, receipt, eventId)));
            } catch (RuntimeException error) {
                return CompletableFuture.completedFuture(settleUnknown(request, receipt, "Chronicle commit failed; receipt retained for reconciliation: " + message(error), nativeCall));
            }
        }
        if (observation.status() == AidObservation.Status.REJECTED) {
            if (previous != AidLedger.Status.ADMITTED)
                return CompletableFuture.completedFuture(settleUnknown(request, observation.receipt(),
                        "Receipt lookup returned a rejection state instead of committed/absent evidence", nativeCall));
            try {
                verifyProviderIdentity();
                CompletionStage<AidObservation> lookup = Objects.requireNonNull(provider.lookup(request), "Aid provider returned null lookup stage");
                return lookup.handleAsync((current, error) -> {
                    if (error != null || current == null) return CompletableFuture.completedFuture(settleUnknown(request, null,
                            "Native rejection requires receipt reconciliation: " + (error == null ? "no lookup result" : message(error)), nativeCall));
                    if (current.status() == AidObservation.Status.COMMITTED && current.receipt() != null
                            && current.receipt().matches(request, issuer, authorityDomain))
                        return handleObservation(request, contexts, current, nativeCall, AidLedger.Status.UNKNOWN);
                    if (current.status() == AidObservation.Status.ABSENT)
                        return CompletableFuture.completedFuture(settle(request, AidLedger.Status.REJECTED, null,
                                "Native rejection confirmed by durable receipt lookup: " + observation.detail(), nativeCall));
                    return CompletableFuture.completedFuture(settleUnknown(request, current.receipt(),
                            "Native rejection could not be reconciled to a matching receipt", nativeCall));
                }, stateExecutor).thenCompose(stage -> stage);
            } catch (Throwable error) {
                return CompletableFuture.completedFuture(settleUnknown(request, null,
                        "Native rejection requires receipt reconciliation: " + message(error), nativeCall));
            }
        }
        if (observation.status() == AidObservation.Status.ABSENT && previous == AidLedger.Status.ADMITTED) {
            return CompletableFuture.completedFuture(settleUnknown(request, null, "Provider reports no durable receipt after admission", nativeCall));
        }
        AidReceipt verified=observation.receipt()!=null&&observation.receipt().matches(request,issuer,authorityDomain)
                ?observation.receipt():null;
        return CompletableFuture.completedFuture(settleUnknown(request, verified, observation.detail(), nativeCall));
    }

    private Result settleUnknown(AidRequest request, AidReceipt receipt, String detail, boolean nativeCall) {
        // Never persist an unverified native payload as canonical evidence.
        AidReceipt matched = receipt != null && receipt.matches(request, issuer, authorityDomain) ? receipt : null;
        return settle(request, AidLedger.Status.UNKNOWN, matched, detail, nativeCall);
    }

    private Result settle(AidRequest request, AidLedger.Status status, AidReceipt receipt, String detail, boolean nativeCall) {
        store.transaction(c -> { AidLedger.finish(c, issuer, authorityDomain, request.outcomeId(), status, receipt, null, detail, clock.instant()); return null; });
        return new Result(status, nativeCall, detail, null);
    }

    private Result existing(AidLedger.Row row) {
        Contribution contribution = row.status() == AidLedger.Status.COMMITTED && row.receipt() != null
                ? new Contribution(row.request(), row.receipt(), row.eventId()) : null;
        return new Result(row.status(), false, row.detail(), contribution);
    }

    private void verifySame(AidLedger.Row row, AidRequest request) {
        if (!issuer.equals(row.issuer()) || !authorityDomain.equals(row.authorityDomain())
                || !SqliteStore.json(row.request()).equals(SqliteStore.json(request)))
            throw new IllegalArgumentException("Aid transaction ID is already bound to a different request/provider");
    }

    private void verifyProviderIdentity() {
        if (!issuer.equals(provider.providerId()) || !authorityDomain.equals(provider.authorityDomain()))
            throw new IllegalStateException("Aid provider identity changed after this service pinned it");
    }

    private static void validateActor(AidRequest request, PlayerContext actor) {
        Objects.requireNonNull(actor);
        if (!request.actorId().equals(actor.playerId())) throw new SecurityException("Aid actor does not match the current player");
        if (!request.payerId().equals(actor.nationId())) throw new SecurityException("Aid payer is not the actor's current nation");
        if (!actor.permitted(AidRequest.PERMISSION)) throw new SecurityException("Missing StarCore national treasury permission");
    }

    private static List<PlayerContext> audience(PlayerContext actor, Collection<PlayerContext> supplied,
                                                String payerId, String receiverId) {
        LinkedHashMap<UUID,PlayerContext> result = new LinkedHashMap<>();
        result.put(actor.playerId(), actor);
        if (supplied != null) for (PlayerContext context : supplied) {
            Objects.requireNonNull(context);
            if (!context.playerId().equals(actor.playerId())
                    && !payerId.equals(context.nationId())
                    && !receiverId.equals(context.nationId())) continue;
            if (result.put(context.playerId(), context) != null && !context.equals(actor)) throw new IllegalArgumentException("Duplicate aid audience player");
        }
        return List.copyOf(result.values());
    }

    private <T> CompletableFuture<T> state(Supplier<T> work) {
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            stateExecutor.execute(() -> {
                try { result.complete(work.get()); }
                catch (Throwable error) { result.completeExceptionally(error); }
            });
        } catch (Throwable error) {
            result.completeExceptionally(error);
        }
        return result;
    }

    private record Prepared(List<PlayerContext> contexts, Admission admission) {}
    private record Reconciliation(List<PlayerContext> contexts, AidLedger.Row row) {}

    private static String message(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null) root = root.getCause();
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }
}
