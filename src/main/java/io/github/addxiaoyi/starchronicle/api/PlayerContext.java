package io.github.addxiaoyi.starchronicle.api;
import java.util.*;

/** Trusted current snapshot; never build this from player command arguments or AI output. */
public record PlayerContext(UUID playerId, String nationId, Map<String,String> facts, Set<String> permissions) {
    public PlayerContext { Objects.requireNonNull(playerId); nationId=nationId==null?"":nationId; facts=Map.copyOf(facts); permissions=Set.copyOf(permissions); }
    public boolean permitted(String permission) { return permission==null || permission.isBlank() || permissions.contains(permission); }
}
