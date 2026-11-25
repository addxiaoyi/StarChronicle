package io.github.addxiaoyi.starchronicle.api.quest;

import java.util.concurrent.CompletionStage;

/** Cross-plugin trusted SPI. Register through the host service manager. Providers must return
 * their own canonical durable state, not proof supplied by players or AI. Invoke outside SQL
 * locks. Chronicle pins providerId before the first call and never fails over an uncertain run.
 * ensureStarted must bind a fresh run idempotently; lookup must not admit or complete a task. */
public interface QuestEvidenceProvider {
    String providerId();
    CompletionStage<QuestObservation> ensureStarted(QuestRequest request);
    CompletionStage<QuestObservation> lookup(QuestRequest request);
    /** Read-only retry lookup. FAILED/ABANDONED results must be durably terminal and cannot
     * later become a completion. Implementations must bind this guarantee to the exact service
     * used for the lookup. Older providers cannot authorize a replacement run. */
    default CompletionStage<QuestObservation> lookupForRetry(QuestRequest request){
        return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException("Provider does not guarantee durable terminal quest state"));
    }
}
