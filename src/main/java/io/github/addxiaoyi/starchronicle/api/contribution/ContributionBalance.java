package io.github.addxiaoyi.starchronicle.api.contribution;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/** Original evidence plus its latest status; amounts of suspended/refunded/cancelled entries are zero. */
public record ContributionBalance(ContributionEntry entry, ContributionRevision.Status status,
                                  String statusEventId, Instant statusCommittedAt) {
    public ContributionBalance {
        Objects.requireNonNull(entry); Objects.requireNonNull(status); Objects.requireNonNull(statusCommittedAt);
        ContributionRequest.token(statusEventId, "statusEventId", 256);
    }
    public BigDecimal netAmount() { return status == ContributionRevision.Status.ACTIVE ? entry.request().amount() : BigDecimal.ZERO; }
}
