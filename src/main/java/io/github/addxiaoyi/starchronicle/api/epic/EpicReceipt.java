package io.github.addxiaoyi.starchronicle.api.epic;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Durable receipt from the native authority or quest evidence provider. */
public record EpicReceipt(String issuer, String authorityDomain, String operationId,
                          String campaignId, String instanceId, String sourceEventId, String phaseId,
                          EpicOperation.Type type, UUID actorId, String nationId,
                          String target, String nativeReference, String definitionFingerprint,
                          String permission, String nativeReceiptId, Instant appliedAt, String evidence) {
    public EpicReceipt {
        token(issuer, "issuer", 256);
        uuid(authorityDomain, "authorityDomain");
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
            throw new IllegalArgumentException("Epic receipt requires an explicit permission");
        token(nativeReceiptId, "nativeReceiptId", 256);
        appliedAt = Objects.requireNonNull(appliedAt, "appliedAt");
        if (evidence == null || evidence.isBlank() || evidence.length() > 4096)
            throw new IllegalArgumentException("Missing or oversized epic evidence");
    }

    public boolean matches(EpicOperation operation, String expectedIssuer, String expectedDomain) {
        return issuer.equals(expectedIssuer)
                && authorityDomain.equals(expectedDomain)
                && operationId.equals(operation.operationId())
                && campaignId.equals(operation.campaignId())
                && instanceId.equals(operation.instanceId())
                && sourceEventId.equals(operation.sourceEventId())
                && phaseId.equals(operation.phaseId())
                && type == operation.type()
                && actorId.equals(operation.actorId())
                && nationId.equals(operation.nationId())
                && target.equals(operation.target())
                && nativeReference.equals(operation.nativeReference())
                && definitionFingerprint.equals(operation.definitionFingerprint())
                && permission.equals(operation.permission())
                && !appliedAt.isBefore(operation.requestedAt());
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
