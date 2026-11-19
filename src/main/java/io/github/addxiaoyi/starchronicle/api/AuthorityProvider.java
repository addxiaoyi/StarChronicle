package io.github.addxiaoyi.starchronicle.api;
import java.util.concurrent.CompletionStage;

/** Trusted adapter to StarCore authority services. Recheck membership, permission and preconditions.
 * Providers supporting retries must atomically deduplicate outcomeId with the actual mutation.
 * The built-in diplomacy-invitation adapter is single-attempt: upstream lacks a shared receipt
 * transaction, so ambiguous responses are UNKNOWN and must never be automatically retried. */
public interface AuthorityProvider {
    CompletionStage<CoreGateway.DeliveryResult> apply(Delivery delivery);
}
