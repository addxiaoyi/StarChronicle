package io.github.addxiaoyi.starchronicle.core;
import io.github.addxiaoyi.starchronicle.api.PlayerContext;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition;
import java.time.Instant;
import java.util.*;

/** Complete pinned definition and membership snapshots survive config edits and restart. */
public record StoryInstance(String id, StoryDefinition definition, String scopeKey, String sourceEventId, String node,
                            Map<UUID,String> members, Map<UUID,String> votes, Map<String,String> choices,
                            Instant startedAt, Instant updatedAt, boolean completed) {
    public StoryInstance { members=Map.copyOf(members); votes=Map.copyOf(votes); choices=Map.copyOf(choices); }
    public boolean contains(UUID player) { return members.containsKey(player); }
    public String title() { return definition.title(); }
}
