import dev.starcore.starcore.social.simulation.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import org.bukkit.plugin.java.JavaPlugin;
import static org.mockito.Mockito.*;

/** File-only frozen-product probe; no Minecraft server is initialized. */
public class SocialGossipProbe {
  private static final UUID PLAYER = UUID.fromString("22222222-3333-4444-5555-666666666666");
  static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
  static GossipService service(Path root) {
    JavaPlugin plugin = mock(JavaPlugin.class);
    when(plugin.getDataFolder()).thenReturn(root.toFile());
    when(plugin.getLogger()).thenReturn(Logger.getLogger("offline-gossip"));
    return new GossipService(plugin, mock(RelationshipNetwork.class), mock(ReputationService.class));
  }
  public static void main(String[] args) throws Exception {
    String phase = args[0]; Path root = Path.of(args[1]); Files.createDirectories(root);
    if (phase.equals("old-write")) {
      var old = service(root);
      String id = old.createGossip(PLAYER, "legacy gossip", GossipService.GossipTopic.CULTURE);
      old.commentOnGossip(PLAYER, id, "legacy comment");
      old.saveData();
      Files.writeString(root.resolve("id.txt"), id);
    } else if (phase.equals("upgrade")) {
      var current = service(root); String id = Files.readString(root.resolve("id.txt"));
      var gossip = current.getGossipById(id);
      require(gossip.content().equals("legacy gossip") && gossip.comments().size() == 1, "Legacy records retained");
      require(!current.hasSeenGossip(PLAYER, id), "Do not invent old read status");
      current.markGossipSeen(PLAYER, id);
      current.commentOnGossip(PLAYER, id, "new comment");
      current.saveData(); current.close();
    } else if (phase.equals("reopen")) {
      var current = service(root); String id = Files.readString(root.resolve("id.txt"));
      require(current.getGossipById(id).comments().size() == 2, "New comments persist");
      require(current.hasSeenGossip(PLAYER, id), "Read state persists");
      require(current.getUnreadGossipCount(PLAYER) == 0, "Read state is applied");
      current.close(); current.saveData();
    } else if (phase.equals("stale")) {
      var first = service(root); var stale = service(root);
      String id = Files.readString(root.resolve("id.txt"));
      first.updateCredibility(id, .1); first.saveData();
      boolean refused = false;
      try { stale.saveData(); } catch (IllegalStateException expected) { refused = true; }
      require(refused, "Stale writer refused"); first.close(); stale.close();
    } else if (phase.equals("reject")) {
      boolean refused = false;
      try { service(root); } catch (IllegalStateException expected) { refused = true; }
      require(refused, "Invalid data refused");
    } else throw new IllegalArgumentException(phase);
    System.out.println("{\"phase\":\"" + phase + "\",\"verified\":true,\"server_started\":false}");
  }
}
