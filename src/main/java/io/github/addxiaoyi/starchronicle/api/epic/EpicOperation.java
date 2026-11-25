package io.github.addxiaoyi.starchronicle.api.epic;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable intent for one authoritative step in a national epic. Chronicle
 * stores the intent and its story context; a trusted provider owns the native
 * mutation or completion receipt. Player input and AI output must never create
 * an {@link EpicReceipt} directly.
 */
public record EpicOperation(String operationId, String campaignId, String instanceId,
                            String sourceEventId, String phaseId, Type type,
                            UUID actorId, String nationId, String target,
                            String nativeReference, String definitionFingerprint,
                            String permission, Instant requestedAt) {
    public enum Type {
        TREASURY_AID, RESOURCE_AID, QUEST_GATE, DIPLOMACY_INVITE, DIPLOMACY_ACCEPT,
        WAR_COMMIT, NATION_FOUNDING, FINAL_BATTLE, DUNGEON_COMPLETION
    }

    public EpicOperation {
        token(operationId, "operationId", 256);
        token(campaignId, "campaignId", 80);
        token(instanceId, "instanceId", 256);
        token(sourceEventId, "sourceEventId", 256);
        token(phaseId, "phaseId", 80);
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(actorId, "actorId");
        uuid(nationId, "nationId");
        token(target, "target", 256);
        token(nativeReference, "nativeReference", 256);
        if (definitionFingerprint == null || !definitionFingerprint.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("definitionFingerprint must be lowercase SHA-256");
        if (permission == null || permission.isBlank() || permission.length() > 256 || permission.contains("*"))
            throw new IllegalArgumentException("Epic operation requires an explicit permission");
        requestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
    }

    private static void token(String value, String field, int max) {
        if (value == null || value.isBlank() || value.length() > max
                || !value.matches("[A-Za-z0-9_.:/-]+"))
            throw new IllegalArgumentException("Invalid epic " + field);
    }

    private static void uuid(String value, String field) {
        try {
            if (value == null || !UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Canonical " + field + " UUID required", error);
        }
    }
}
