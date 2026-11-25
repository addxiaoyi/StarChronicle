package io.github.addxiaoyi.starchronicle.api;

import java.util.UUID;

/** Caller-specific membership projection; a status read is never native approval. */
public record MemberState(String instanceId, UUID playerId, String nodeId, boolean completed,
                          String originalNationId, String observedNationId, String status, String reason) {}
