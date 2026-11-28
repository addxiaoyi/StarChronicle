package io.github.addxiaoyi.starchronicle.api.quest;

import java.util.concurrent.CompletionStage;

/** Optional additive capability. Reconcile only the original binding; never replay rewards,
 * completion or admission. A terminal receipt must follow the native durable state acknowledgement. */
public interface QuestRecoveryProvider extends QuestEvidenceProvider {
    CompletionStage<QuestObservation> reconcile(QuestRequest request);
    CompletionStage<QuestTermination> termination(QuestRequest request);
}
