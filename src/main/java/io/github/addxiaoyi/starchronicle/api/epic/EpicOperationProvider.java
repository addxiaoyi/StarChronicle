package io.github.addxiaoyi.starchronicle.api.epic;

import java.util.concurrent.CompletionStage;

/**
 * Trusted bridge for a single authoritative epic operation. Implementations
 * must use StarCore services or the QuestEvidenceProvider contract, persist a
 * native idempotency receipt, and never accept a player-supplied receipt.
 */
public interface EpicOperationProvider {
    String providerId();
    String authorityDomain();
    /** Capability check before a durable intent is assigned to this provider. */
    default boolean supports(EpicOperation operation) { return true; }
    CompletionStage<EpicObservation> apply(EpicOperation operation);
    /** Async providers must recheck this lease immediately before a native mutation. */
    default CompletionStage<EpicObservation> apply(EpicOperation operation, java.util.function.BooleanSupplier lease) {
        return lease.getAsBoolean() ? apply(operation) : java.util.concurrent.CompletableFuture.completedFuture(
                EpicObservation.unknown("Epic provider lease expired before invocation"));
    }
    CompletionStage<EpicObservation> lookup(EpicOperation operation);
    default CompletionStage<java.util.Optional<EpicRejectionProof>> rejectionProof(EpicOperation operation) {
        return java.util.concurrent.CompletableFuture.completedFuture(java.util.Optional.empty());
    }
}
