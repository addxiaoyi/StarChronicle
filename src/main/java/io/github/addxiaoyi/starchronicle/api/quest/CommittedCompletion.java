package io.github.addxiaoyi.starchronicle.api.quest;

import java.time.Instant;
import java.util.UUID;

/** Receipt issued only after the native quest completion is durably acknowledged.
 * Objective readiness, acceptance and completed-ever flags are not receipts. */
public record CommittedCompletion(String bindingId, String runId, String completionId, UUID playerId,
                                  String questId, String definitionFingerprint, Instant startedAt,
                                  Instant completedAt, Instant committedAt, NationProof acceptedNation,
                                  NationProof completedNation, String issuer, String acceptedEvidence,
                                  String evidence) {
    public AcceptedRun acceptedRun() {
        return new AcceptedRun(bindingId,runId,playerId,questId,definitionFingerprint,startedAt,
                acceptedNation,issuer,acceptedEvidence);
    }
}
