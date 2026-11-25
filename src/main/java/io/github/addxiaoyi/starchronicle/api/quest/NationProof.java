package io.github.addxiaoyi.starchronicle.api.quest;

import java.time.Instant;

/** Authoritative country lookup. UNAVAILABLE must never be reported as KNOWN_NONE. */
public record NationProof(State state, String nationId, Instant checkedAt, String issuer, String evidence) {
    public enum State { KNOWN_NATION, KNOWN_NONE, UNAVAILABLE }
}
