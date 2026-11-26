package io.github.addxiaoyi.starchronicle.api;

import java.time.Instant;
import java.util.UUID;

/** Current native membership, serialized against membership changes and dissolution.
 * Hold native authority before invoking the synchronous callback and before Chronicle SQL.
 * No player entity access, futures, rewards or native mutations inside the callback. */
public interface MemberAuthority {
    record Membership(UUID playerId, String nationId, String issuer, String evidence, Instant checkedAt) {
        public Membership {
            java.util.Objects.requireNonNull(playerId); java.util.Objects.requireNonNull(nationId);
            java.util.Objects.requireNonNull(checkedAt);
            if (issuer == null || issuer.isBlank() || evidence == null || evidence.isBlank())
                throw new IllegalArgumentException("Membership requires native evidence");
        }
    }
    @FunctionalInterface interface Authorized<T> { T run(Membership membership) throws Exception; }
    <T> T withMember(UUID playerId, String expectedNationId, Authorized<T> operation) throws Exception;

    static MemberAuthority denying() { return new MemberAuthority() {
        public <T> T withMember(UUID player, String nation, Authorized<T> operation) {
            throw new SecurityException("Current native membership authority is unavailable");
        }
    }; }
}
