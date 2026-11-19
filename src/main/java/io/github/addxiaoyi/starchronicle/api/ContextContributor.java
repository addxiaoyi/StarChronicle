package io.github.addxiaoyi.starchronicle.api;

import java.util.Map;
import java.util.UUID;

/** Trusted RPG integration registered through Bukkit ServicesManager. Called on the player's
 * entity thread. Supply role, technology stage or regional narrative facts; never permissions.
 * Core nation, treasury, resource, war and diplomacy facts cannot be overridden. */
public interface ContextContributor {
    Map<String, String> contribute(UUID playerId, PlayerContext authoritativeContext);
}
