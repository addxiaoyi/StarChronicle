package io.github.addxiaoyi.starchronicle.api.aid;

import java.util.concurrent.CompletionStage;

/** Trusted native adapter. Stable provider/domain are pinned before the single admitted call.
 * lookup must be read-only, usable offline, and expose only durably committed native receipts.
 * submit must recheck current actor ownership, membership and native/Bukkit authorization. */
public interface AidProvider {
    String providerId();
    String authorityDomain();
    CompletionStage<AidObservation> lookup(AidRequest request);
    CompletionStage<AidObservation> submit(AidRequest request);
}
