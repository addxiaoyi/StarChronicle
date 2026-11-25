package io.github.addxiaoyi.starchronicle.api.epic;

import java.time.Instant;
import java.util.*;

/** Durable national roster before or after freezing. */
public record EpicSignup(String sessionId, EpicCampaignPlan plan, String reliefNationId,
                         Status status, long revision, Map<String, Seat> seats,
                         String sealedEventId, Instant createdAt, Instant updatedAt,
                         Cancellation cancellation) {
    public enum Status { OPEN, SEALED, CANCELLED }
    public enum SeatStatus { ACTIVE, WITHDRAWN }
    public record Seat(String nationId, UUID representativeId, long revision, SeatStatus status,
                       Instant enrolledAt, Instant updatedAt) {
        public Seat { Objects.requireNonNull(representativeId); Objects.requireNonNull(status); if (revision < 0) throw new IllegalArgumentException("Negative seat revision"); }
    }
    public record Cancellation(UUID actorId, Instant at, String reason) {
        public Cancellation { Objects.requireNonNull(actorId); Objects.requireNonNull(at); if (reason == null || reason.isBlank() || reason.length() > 1000) throw new IllegalArgumentException("Invalid cancellation reason"); }
    }
    public EpicSignup {
        Objects.requireNonNull(plan); Objects.requireNonNull(status); Objects.requireNonNull(seats);
        if (sessionId == null || !sessionId.matches("[A-Za-z0-9_.-]{1,80}")) throw new IllegalArgumentException("Invalid epic session");
        if (reliefNationId == null || !UUID.fromString(reliefNationId).toString().equals(reliefNationId)) throw new IllegalArgumentException("Canonical relief nation UUID required");
        if (revision < 0) throw new IllegalArgumentException("Negative signup revision");
        seats = Collections.unmodifiableMap(new TreeMap<>(seats));
        Objects.requireNonNull(createdAt); Objects.requireNonNull(updatedAt);
    }
    public List<Seat> activeSeats() { return seats.values().stream().filter(s -> s.status() == SeatStatus.ACTIVE).toList(); }
}
