package io.github.addxiaoyi.starchronicle.api;

import io.github.addxiaoyi.starchronicle.history.ObservationSnapshot;
import java.util.Collection;

/** Trusted read-only authority adapter registered with Bukkit ServicesManager. Runs on the IO
 * worker: return detached, complete committed snapshots; never Bukkit entity/location objects.
 * In particular CAPITALS values must be actual durable designated city IDs, not cache locations,
 * highest-level-city guesses, or UI defaults. Report incomplete on any source read failure. */
public interface SnapshotProvider {
    Collection<ObservationSnapshot> snapshots();
}
