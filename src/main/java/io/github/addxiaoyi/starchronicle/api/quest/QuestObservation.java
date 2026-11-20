package io.github.addxiaoyi.starchronicle.api.quest;

import java.time.Instant;

/** Trusted provider observation; only COMMITTED may carry a completion receipt. */
public record QuestObservation(Status status, AcceptedRun acceptedRun, CommittedCompletion completion,
                               Instant observedAt, String message) {
    public enum Status { UNSUPPORTED, NOT_STARTED, ADMITTING, ACTIVE, COMMITTED, ABANDONED, FAILED, CONFLICT, UNKNOWN }
}
