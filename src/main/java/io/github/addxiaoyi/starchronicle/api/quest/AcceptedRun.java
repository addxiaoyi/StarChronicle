package io.github.addxiaoyi.starchronicle.api.quest;

import java.time.Instant;
import java.util.UUID;

/** Canonical native acceptance identity; all fields remain immutable through completion. */
public record AcceptedRun(String bindingId, String runId, UUID playerId, String questId,
                          String definitionFingerprint, Instant startedAt, NationProof acceptedNation,
                          String issuer, String evidence) {}
