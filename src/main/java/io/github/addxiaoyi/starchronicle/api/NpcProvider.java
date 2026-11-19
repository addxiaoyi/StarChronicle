package io.github.addxiaoyi.starchronicle.api;
import java.util.UUID;

/** Optional NPC display provider; lifecycle ownership is explicit. Never delete foreign NPCs. */
public interface NpcProvider extends AutoCloseable {
    String name();
    void showDialogue(UUID player, String npcId, String text);
    @Override void close();
}
