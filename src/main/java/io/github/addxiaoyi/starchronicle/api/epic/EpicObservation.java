package io.github.addxiaoyi.starchronicle.api.epic;

import java.util.Objects;

/** Read-only provider observation. Only COMMITTED may carry a native receipt. */
public record EpicObservation(Status status, EpicReceipt receipt, String detail) {
    public enum Status { ABSENT, COMMITTED, REJECTED, UNKNOWN }

    public EpicObservation {
        Objects.requireNonNull(status, "status");
        if ((status == Status.COMMITTED) != (receipt != null))
            throw new IllegalArgumentException("Committed epic observation requires exactly one receipt");
        detail = detail == null ? "" : detail;
        if (detail.length() > 2048) detail = detail.substring(0, 2048);
    }

    public static EpicObservation unknown(String detail) { return new EpicObservation(Status.UNKNOWN, null, detail); }
}
