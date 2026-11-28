package io.github.addxiaoyi.starchronicle.api.contribution;

import java.time.Instant;
import java.util.Objects;

/** Immutable, durably recorded contribution evidence. */
public record ContributionEntry(String id, ContributionRequest request, Instant recordedAt, String sourceStatus) {
    public ContributionEntry(String id,ContributionRequest request,Instant recordedAt){this(id,request,recordedAt,"COMMITTED");}
    public ContributionEntry {
        ContributionRequest.token(id, "id", 256);
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(recordedAt, "recordedAt");
        if(sourceStatus==null)sourceStatus="COMMITTED";
        if(!java.util.Set.of("COMMITTED","COMPLETED","REFUNDED","DISPUTED","CANCELLED").contains(sourceStatus))throw new IllegalArgumentException("Invalid contribution source status");
        if (recordedAt.isBefore(request.committedAt()))
            throw new IllegalArgumentException("Contribution recordedAt predates committedAt");
    }
}
