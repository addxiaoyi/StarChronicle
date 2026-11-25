package io.github.addxiaoyi.starchronicle.api.epic;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;

/** Stateless checks shared by epic integrations and tests. */
public final class EpicContract {
    private EpicContract() {}

    /** A command-safe, lossless UTF-8 nation name (including Chinese names). */
    public static String foundingReference(String name) {
        if (name == null || name.isBlank() || name.length() > 128 || name.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Nation name requires 1..128 printable characters");
        String reference = "NATION_FOUNDING_V1:" + java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(name.getBytes(StandardCharsets.UTF_8));
        if (reference.length() > 256) throw new IllegalArgumentException("Encoded nation name exceeds epic reference capacity");
        return reference;
    }

    public static String foundingName(String reference) {
        String prefix = "NATION_FOUNDING_V1:";
        if (reference == null || !reference.startsWith(prefix)) throw new IllegalArgumentException("Expected encoded NATION_FOUNDING_V1:<name>");
        String name = new String(java.util.Base64.getUrlDecoder().decode(reference.substring(prefix.length())), StandardCharsets.UTF_8);
        if (!foundingReference(name).equals(reference)) throw new IllegalArgumentException("Noncanonical UTF-8 founding name");
        return name;
    }

    /** Stable ID for one campaign/instance/phase/action/nation tuple. */
    public static String operationId(String campaignId, String instanceId, String phaseId,
                                     String actionId, String nationId) {
        String joined = String.join("\u001f", campaignId, instanceId, phaseId, actionId, nationId);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(joined.getBytes(StandardCharsets.UTF_8));
            return "epic:" + HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    /** Accept only a receipt pinned to this provider and exact operation identity. */
    public static EpicReceipt requireCommitted(EpicOperation operation, EpicObservation observation,
                                               String providerId, String authorityDomain) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(observation, "observation");
        if (observation.status() != EpicObservation.Status.COMMITTED || observation.receipt() == null)
            throw new IllegalStateException("Epic operation has no committed native receipt: " + operation.operationId());
        EpicReceipt receipt = observation.receipt();
        if (!receipt.matches(operation, providerId, authorityDomain))
            throw new IllegalStateException("Epic receipt does not match the pinned operation: " + operation.operationId());
        return receipt;
    }
}
