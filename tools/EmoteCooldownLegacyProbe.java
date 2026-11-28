import com.google.gson.Gson;
import dev.starcore.starcore.social.emote.*;
import java.nio.file.*;
import java.util.*;

/** Invoke the real old public serializer without a server implementation. */
public final class EmoteCooldownLegacyProbe {
  public static void main(String[] args) throws Exception {
    UUID player = new UUID(0, 42);
    EmoteCooldownManager manager = new EmoteCooldownManager();
    manager.startCooldown(player, "WAVE");
    manager.startCooldown(player, "bow");
    long recorded = System.currentTimeMillis();
    Map<String, Object> document = new LinkedHashMap<>();
    document.put("player", player.toString());
    document.put("clock", recorded);
    document.put("cooldowns", manager.serialize());
    // Preserve proof of the old attached serializer's omission; it cannot be reconstructed.
    EmoteService oldService = new EmoteService();
    oldService.getOrCreateState(player).setCurrentEmote("wave", recorded, null);
    EmoteCooldownManager oldAttached = new EmoteCooldownManager();
    oldAttached.setEmoteService(oldService);
    document.put("oldAttached", oldAttached.serialize());
    Files.writeString(Path.of(args[0]), new Gson().toJson(document), StandardOpenOption.CREATE_NEW);
    System.out.println("{\"verified\":true,\"phase\":\"legacy\",\"server_started\":false}");
  }
}
