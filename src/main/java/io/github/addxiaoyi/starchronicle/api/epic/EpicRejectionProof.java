package io.github.addxiaoyi.starchronicle.api.epic;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record EpicRejectionProof(String providerId, String authorityDomain, EpicOperation operation,
                                 String nativeProofId, String reason, Instant rejectedAt) {
    public EpicRejectionProof {
        if (providerId == null || providerId.isBlank() || providerId.length() > 256)
            throw new IllegalArgumentException("Invalid rejection proof provider");
        if (authorityDomain == null || !UUID.fromString(authorityDomain).toString().equals(authorityDomain))
            throw new IllegalArgumentException("Canonical rejection authority UUID required");
        Objects.requireNonNull(operation, "operation");
        if (nativeProofId == null || nativeProofId.isBlank() || nativeProofId.length() > 256)
            throw new IllegalArgumentException("Invalid native rejection proof ID");
        if (reason == null || reason.isBlank() || reason.length() > 1024)
            throw new IllegalArgumentException("Invalid native rejection reason");
        Objects.requireNonNull(rejectedAt, "rejectedAt");
        if (rejectedAt.isBefore(operation.requestedAt()))
            throw new IllegalArgumentException("Native rejection predates the pinned operation");
    }

    public boolean matches(EpicOperation expected, String provider, String domain) {
        return operation.equals(expected) && providerId.equals(provider) && authorityDomain.equals(domain);
    }
}
