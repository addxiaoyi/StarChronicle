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
}
