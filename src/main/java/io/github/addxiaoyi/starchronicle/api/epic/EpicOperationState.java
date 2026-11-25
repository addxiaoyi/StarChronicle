package io.github.addxiaoyi.starchronicle.api.epic;

import java.time.Instant;

/** Local operation ledger state; only COMMITTED carries canonical native evidence. */
public record EpicOperationState(EpicOperation operation, Status status, EpicReceipt receipt,
                                 String detail, long revision, Instant updatedAt) {
    public enum Status { PREPARED, UNKNOWN, COMMITTED, REJECTED }
    public EpicOperationState {
        java.util.Objects.requireNonNull(operation); java.util.Objects.requireNonNull(status); java.util.Objects.requireNonNull(updatedAt);
        if ((status == Status.COMMITTED) != (receipt != null)) throw new IllegalArgumentException("Committed operation requires a receipt");
        if (status != Status.COMMITTED && receipt != null) throw new IllegalArgumentException("Non-committed operation cannot carry receipt");
        if (detail == null) detail = ""; if (detail.length() > 2048) detail = detail.substring(0, 2048);
        if (revision < 1) throw new IllegalArgumentException("Operation revision must be positive");
    }
}
