package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.PlayerContext;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

/** Durable membership review state. It never rewrites historical ballots or source events. */
public final class MemberReviewLedger {
    public enum Status { PENDING, APPROVED, EXITED }
    public record Review(String instanceId, UUID playerId, String originalNationId,
                         String observedNationId, Status status, String reason, Instant checkedAt) {
        public Review {
            Objects.requireNonNull(instanceId); Objects.requireNonNull(playerId);
            Objects.requireNonNull(originalNationId); observedNationId = observedNationId == null ? "" : observedNationId;
            Objects.requireNonNull(status); reason = reason == null ? "" : reason;
            Objects.requireNonNull(checkedAt);
        }
    }
    private MemberReviewLedger() {}

    public static void createSchema(Connection c) throws SQLException {
        try (var s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS story_member_reviews("+
                    "instance_id TEXT NOT NULL REFERENCES instances(id),player_id TEXT NOT NULL,"+
                    "original_nation_id TEXT NOT NULL,observed_nation_id TEXT NOT NULL,"+
                    "status TEXT NOT NULL CHECK(status IN ('PENDING','APPROVED','EXITED')),"+
                    "reason TEXT NOT NULL,checked_at TEXT NOT NULL,"+
                    "PRIMARY KEY(instance_id,player_id))");
            s.execute("CREATE INDEX IF NOT EXISTS story_member_review_status ON story_member_reviews(status,checked_at,instance_id,player_id)");
            s.execute("CREATE TABLE IF NOT EXISTS story_member_review_log("+
                    "seq INTEGER PRIMARY KEY AUTOINCREMENT,instance_id TEXT NOT NULL REFERENCES instances(id),"+
                    "player_id TEXT NOT NULL,original_nation_id TEXT NOT NULL,observed_nation_id TEXT NOT NULL,"+
                    "status TEXT NOT NULL,reason TEXT NOT NULL,checked_at TEXT NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS story_member_review_log_player ON story_member_review_log(player_id,seq)");
        }
    }

    public static Optional<Review> find(Connection c, String instanceId, UUID playerId) throws SQLException {
        try (var s = c.prepareStatement("SELECT instance_id,player_id,original_nation_id,observed_nation_id,status,reason,checked_at FROM story_member_reviews WHERE instance_id=? AND player_id=?")) {
            s.setString(1, instanceId); s.setString(2, playerId.toString());
            try (var r = s.executeQuery()) { return r.next() ? Optional.of(read(r)) : Optional.empty(); }
        }
    }

    public static List<Review> forPlayer(Connection c, UUID playerId, int limit) throws SQLException {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be 1..1000");
        try (var s = c.prepareStatement("SELECT instance_id,player_id,original_nation_id,observed_nation_id,status,reason,checked_at FROM story_member_reviews WHERE player_id=? ORDER BY checked_at DESC,instance_id LIMIT ?")) {
            s.setString(1, playerId.toString()); s.setInt(2, limit);
            List<Review> result = new ArrayList<>();
            try (var r = s.executeQuery()) { while (r.next()) result.add(read(r)); }
            return List.copyOf(result);
        }
    }

    public static void pending(Connection c, StoryInstance instance, UUID playerId,
                               String observedNationId, String reason, Instant now) throws SQLException {
        upsert(c, instance, playerId, observedNationId, Status.PENDING, reason, now);
    }

    public static void approve(Connection c, StoryInstance instance, PlayerContext context, Instant now) throws SQLException {
        approve(c, instance, context, "Explicit member revalidation", now);
    }
    public static void approve(Connection c, StoryInstance instance, PlayerContext context, String evidence, Instant now) throws SQLException {
        Review review = find(c, instance.id(), context.playerId()).orElse(null);
        if (review != null && review.status() == Status.EXITED) throw new IllegalStateException("Exited membership cannot be silently restored");
        if (!instance.members().containsKey(context.playerId())) throw new SecurityException("Player is not an instance member");
        if (!instance.members().get(context.playerId()).equals(context.nationId())) throw new SecurityException("Current nation does not match the original membership");
        if (review != null && review.status() != Status.PENDING && review.status() != Status.APPROVED)
            throw new IllegalStateException("Membership review is not revalidatable: " + review.status());
        upsert(c, instance, context.playerId(), context.nationId(), Status.APPROVED, evidence, now);
    }

    public static void exit(Connection c, StoryInstance instance, UUID playerId, String reason, Instant now) throws SQLException {
        String observed = find(c, instance.id(), playerId).map(Review::observedNationId)
                .orElse(instance.members().getOrDefault(playerId, ""));
        exit(c, instance, playerId, observed, reason, now);
    }

    public static void exit(Connection c, StoryInstance instance, UUID playerId, String observedNationId, String reason, Instant now) throws SQLException {
        upsert(c, instance, playerId, observedNationId, Status.EXITED,
                reason == null || reason.isBlank() ? "Member left the story instance" : reason, now);
    }

    /** A new explicit join is a new membership decision; preserve the review row as approved evidence. */
    public static void rejoin(Connection c, StoryInstance instance, UUID playerId, String nation, Instant now) throws SQLException {
        if (find(c, instance.id(), playerId).isEmpty()) return;
        upsert(c, instance, playerId, nation, Status.APPROVED, "Explicitly rejoined after membership review", now);
    }

    public static void requireEligible(Connection c, StoryInstance instance, UUID playerId,
                                       String currentNation, Instant now) throws SQLException {
        String original = instance.members().get(playerId);
        if (original == null) throw new SecurityException("Player is not an instance member");
        Review review = find(c, instance.id(), playerId).orElse(null);
        if (!Objects.equals(original, currentNation)) {
            throw new SecurityException("Member nationality changed; explicit review is required");
        }
        if (review != null && review.status() != Status.APPROVED)
            throw new SecurityException("Membership review is required before continuing: " + review.status());
    }

    private static void upsert(Connection c, StoryInstance instance, UUID playerId, String observed,
                                Status status, String reason, Instant now) throws SQLException {
        String original = instance.members().get(playerId);
        if (original == null) throw new IllegalStateException("Review target is not an instance member");
        String safeReason = reason == null ? "" : reason;
        if (safeReason.length() > 512) safeReason = safeReason.substring(0, 512);
        Review previous = find(c, instance.id(), playerId).orElse(null);
        if (previous != null && previous.status() == status && previous.observedNationId().equals(observed == null ? "" : observed)
                && (status == Status.PENDING || previous.reason().equals(safeReason))) return;
        if (previous != null && !previous.originalNationId().equals(original))
            throw new SecurityException("Membership cannot silently change its original nation");
        try (var s = c.prepareStatement("INSERT INTO story_member_reviews(instance_id,player_id,original_nation_id,observed_nation_id,status,reason,checked_at) VALUES(?,?,?,?,?,?,?) ON CONFLICT(instance_id,player_id) DO UPDATE SET observed_nation_id=excluded.observed_nation_id,status=excluded.status,reason=excluded.reason,checked_at=excluded.checked_at")) {
            s.setString(1, instance.id()); s.setString(2, playerId.toString()); s.setString(3, original);
            s.setString(4, observed == null ? "" : observed); s.setString(5, status.name()); s.setString(6, safeReason); s.setString(7, now.toString()); s.executeUpdate();
        }
        try (var s = c.prepareStatement("INSERT INTO story_member_review_log(instance_id,player_id,original_nation_id,observed_nation_id,status,reason,checked_at) VALUES(?,?,?,?,?,?,?)")) {
            s.setString(1, instance.id()); s.setString(2, playerId.toString()); s.setString(3, original);
            s.setString(4, observed == null ? "" : observed); s.setString(5, status.name());
            s.setString(6, safeReason); s.setString(7, now.toString()); s.executeUpdate();
        }
    }

    private static Review read(java.sql.ResultSet r) throws SQLException {
        return new Review(r.getString(1), UUID.fromString(r.getString(2)), r.getString(3), r.getString(4),
                Status.valueOf(r.getString(5)), r.getString(6), Instant.parse(r.getString(7)));
    }
}
