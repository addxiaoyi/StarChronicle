package io.github.addxiaoyi.starchronicle.api;

import java.time.Instant;
import java.util.UUID;

/** Trusted native authority for formal national narrative ballots, not a role/facts check.
 * Implementations must serialize membership, leadership and dissolution against the complete
 * synchronous callback. Lock order is native nation authority, then Chronicle storage.
 * The callback may read native identities and commit a local SQLite transition; it must not
 * access Bukkit entities, await futures or execute native rewards/nation mutations. */
public interface NationalVoteAuthority {
    @FunctionalInterface interface Authorized<T> { T run(View view)throws Exception; }
    /** A live authoritative view, valid only on the callback thread while its authority lock
     * is held. Missing nations may be excluded from an unresolved quorum; unavailable or
     * malformed authority must throw, never masquerade as a nation having disappeared. */
    interface View {
        UUID actorId();
        String nationId();
        Instant checkedAt();
        String issuer();
        String evidence();
        boolean nationExists(String nationId)throws Exception;
    }
    <T>T withRepresentative(UUID actorId,String expectedNationId,Authorized<T> operation)throws Exception;

    /** Verify ordinary membership (or an explicitly empty nation), under the same native
     * mutation lock. This never grants a national ballot. Older adapters fail closed. */
    default <T>T withMembership(UUID actorId,String expectedNationId,Authorized<T> operation)throws Exception {
        throw new SecurityException("No authoritative membership review service is available");
    }

    static NationalVoteAuthority denying(){return new NationalVoteAuthority(){
        @Override public <T>T withRepresentative(UUID actor,String nation,Authorized<T> operation){throw new SecurityException("No authoritative national representative service is available");}
    };}
}
