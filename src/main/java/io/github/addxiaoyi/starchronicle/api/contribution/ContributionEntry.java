package io.github.addxiaoyi.starchronicle.api.contribution;

import java.time.Instant;
import java.util.Objects;

/** Immutable, durably recorded contribution evidence. */
public record ContributionEntry(String id, ContributionRequest request, Instant recordedAt) {
    public ContributionEntry {
        ContributionRequest.token(id, "id", 256);
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(recordedAt, "recordedAt");
        if (recordedAt.isBefore(request.committedAt()))
            throw new IllegalArgumentException("Contribution recordedAt predates committedAt");
    }
}
