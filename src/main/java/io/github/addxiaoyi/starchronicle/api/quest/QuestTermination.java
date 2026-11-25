package io.github.addxiaoyi.starchronicle.api.quest;

import java.time.Instant;

/** Trusted durable negative receipt for one accepted run. Never supplied by players. */
public record QuestTermination(AcceptedRun acceptedRun, QuestObservation.Status status,
                               String terminationId, Instant terminatedAt, Instant committedAt,
                               String evidence) {}
