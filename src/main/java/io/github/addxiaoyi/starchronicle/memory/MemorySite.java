package io.github.addxiaoyi.starchronicle.memory;

import java.util.Objects;
import java.util.UUID;

/** A deliberately placed, plugin-owned historical display. Never a claim to build on foreign land. */
public record MemorySite(String id, UUID world, int x, int y, int z, String title,
                         String eventId, String npcId, boolean enabled) {
    public MemorySite {
        if (id == null || !id.matches("[a-z0-9_.-]{1,64}")) throw new IllegalArgumentException("Invalid memory site ID");
        Objects.requireNonNull(world);
        if (Math.abs((long)x)>29_999_984 || Math.abs((long)z)>29_999_984 || y < -2048 || y > 2048)
            throw new IllegalArgumentException("Invalid memory coordinates");
        if (title == null || title.isBlank() || title.length()>128) throw new IllegalArgumentException("Invalid memory title");
        if (eventId == null || eventId.isBlank() || eventId.length()>256) throw new IllegalArgumentException("Invalid source event ID");
        npcId=Objects.requireNonNullElse(npcId, "");
        if (!npcId.isEmpty() && !npcId.matches("[A-Za-z0-9_.-]{1,80}")) throw new IllegalArgumentException("Invalid NPC ID");
    }
    public int chunkX() { return x >> 4; }
    public int chunkZ() { return z >> 4; }
}
