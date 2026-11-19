package io.github.addxiaoyi.starchronicle.api;

import java.time.Instant;
import java.util.*;

/** Immutable committed source event. The ID must survive upstream redelivery and restart. */
public record WorldEvent(String id, String type, Instant occurredAt, Set<UUID> participants,
                         Set<String> nations, Map<String,String> facts, String source, String evidence) {
    public WorldEvent {
        require(id, "id"); require(type, "type"); require(source, "source"); require(evidence, "evidence");
        Objects.requireNonNull(occurredAt, "occurredAt");
        participants = Set.copyOf(participants); nations = Set.copyOf(nations); facts = Map.copyOf(facts);
        if (id.length()>256 || type.length()>128 || source.length()>256 || evidence.length()>4096 || facts.size()>128 || participants.size()>10000 || nations.size()>10000)
            throw new IllegalArgumentException("Event exceeds limits");
        for(String nation:nations)if(nation.isBlank()||nation.length()>256)throw new IllegalArgumentException("Invalid nation ID");
        for(var fact:facts.entrySet())if(fact.getKey().isBlank()||fact.getKey().length()>128||fact.getValue().length()>4096)throw new IllegalArgumentException("Event fact exceeds limits");
    }
    private static void require(String value, String field) {
        if(value == null || value.isBlank()) throw new IllegalArgumentException("Missing event " + field);
    }
    /** Explicit trusted broadcast audience. It never adds people to historical participants. */
    public boolean globalAudience(){return "GLOBAL".equals(facts.get("audience"));}
}
