package io.github.addxiaoyi.starchronicle.integration;

import io.github.addxiaoyi.starchronicle.api.MemberAuthority;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;

/** Ordinary members use the same audited NationModule monitor as national representatives. */
public final class StarCoreMemberAuthority implements MemberAuthority {
    private final Supplier<StarCoreNationalVoteAuthority.NativeAccess> access;
    private final Clock clock;

    public StarCoreMemberAuthority(StarCoreBridge bridge) {
        this(() -> StarCoreNationalVoteAuthority.nativeAccess(Objects.requireNonNull(bridge)), Clock.systemUTC());
    }
    StarCoreMemberAuthority(Supplier<StarCoreNationalVoteAuthority.NativeAccess> access, Clock clock) {
        this.access = Objects.requireNonNull(access); this.clock = Objects.requireNonNull(clock);
    }
    public <T> T withMember(UUID player, String expectedNation, Authorized<T> operation) throws Exception {
        Objects.requireNonNull(player); Objects.requireNonNull(expectedNation); Objects.requireNonNull(operation);
        if (!expectedNation.isEmpty() && !UUID.fromString(expectedNation).toString().equals(expectedNation))
            throw new SecurityException("Canonical native nation required");
        var nativeAccess = access.get();
        if (nativeAccess == null) throw new SecurityException("Audited native membership authority is unavailable");
        synchronized (Objects.requireNonNull(nativeAccess.monitor())) {
            String current = nativeAccess.nationOf(player);
            if (!expectedNation.equals(current) || !current.isEmpty() && !nativeAccess.exists(current))
                throw new SecurityException("Current native membership has changed or its nation is gone");
            return operation.run(new Membership(player, current, "StarCore.NationModule.membership",
                    "nationOf=" + current + "; existingNation=" + !current.isEmpty() + "; actor=" + player, clock.instant()));
        }
    }
}
