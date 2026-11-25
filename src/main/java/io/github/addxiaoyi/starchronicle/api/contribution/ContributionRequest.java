package io.github.addxiaoyi.starchronicle.api.contribution;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A contribution claim backed by an already committed source event. */
public record ContributionRequest(String operationId, ContributionType type, String subjectId,
                                  String sourceEventId, UUID actorId, String nationId,
                                  BigDecimal amount, String unit, Instant committedAt,
                                  String evidence) {
    public ContributionRequest {
        token(operationId, "operationId", 256);
        Objects.requireNonNull(type, "type");
        token(subjectId, "subjectId", 256);
        token(sourceEventId, "sourceEventId", 256);
        Objects.requireNonNull(actorId, "actorId");
        if (nationId != null && !nationId.isBlank()) token(nationId, "nationId", 256);
        amount = amount(amount);
        token(unit, "unit", 320);
        Objects.requireNonNull(committedAt, "committedAt");
        if (evidence == null || evidence.isBlank() || evidence.length() > 4096)
            throw new IllegalArgumentException("Invalid contribution evidence");
    }

    public static BigDecimal amount(BigDecimal value) {
        if (value == null || value.signum() <= 0 || value.scale() > 6
                || value.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException("Contribution amount must be positive, use at most six decimals, and not exceed Long.MAX_VALUE");
        }
        return value.stripTrailingZeros();
    }

    public static String token(String value, String field, int max) {
        if (value == null || value.isBlank() || value.length() > max
                || !value.matches("[A-Za-z0-9_.:-]{1," + max + "}")) {
            throw new IllegalArgumentException("Invalid contribution " + field);
        }
        return value;
    }

    /** Canonical request identity used to detect conflicting reuse of an operation key. */
    public String identity() {
        return String.join("|", operationId, type.name(), subjectId, sourceEventId,
                actorId.toString(), Objects.toString(nationId, ""), amount.toPlainString(), unit,
                committedAt.toString(), evidence);
    }
}
