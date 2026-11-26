package io.github.addxiaoyi.starchronicle.api.contribution;

import java.time.Instant;
import java.util.Objects;

/** Append-only status evidence; the original contribution is never changed or removed. */
public record ContributionRevision(long revision, String contributionId, String sourceEventId,
                                   Status status, Instant committedAt, Instant recordedAt) {
    public enum Status { ACTIVE, SUSPENDED, REFUNDED, CANCELLED }
    public ContributionRevision {
        if (revision < 1) throw new IllegalArgumentException("Invalid contribution revision");
        ContributionRequest.token(contributionId, "contributionId", 256);
        ContributionRequest.token(sourceEventId, "sourceEventId", 256);
        Objects.requireNonNull(status); Objects.requireNonNull(committedAt); Objects.requireNonNull(recordedAt);
        if (recordedAt.isBefore(committedAt)) throw new IllegalArgumentException("Revision predates source commit");
    }
}
