import dev.starcore.starcore.social.simulation.*;
import dev.starcore.starcore.social.simulation.GossipVerification.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import org.bukkit.plugin.java.JavaPlugin;
import static org.mockito.Mockito.*;

/** Serial file-only product-JAR verification. Never initializes a Minecraft server. */
public final class SocialVerificationProbe {
  private static final UUID CREATOR = new UUID(0, 1), REPORTER = new UUID(0, 2);
  static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
  static JavaPlugin plugin(Path root) {
    var plugin = mock(JavaPlugin.class);
    when(plugin.getDataFolder()).thenReturn(root.toFile());
    when(plugin.getLogger()).thenReturn(Logger.getLogger("offline-gossip-verification"));
    return plugin;
  }
  public static void main(String[] args) throws Exception {
    String phase = args[0]; Path root = Path.of(args[1]); Files.createDirectories(root);
    var plugin = plugin(root); var relations = mock(RelationshipNetwork.class); var reputation = mock(ReputationService.class);
    when(reputation.modifyReputation(any(), anyInt(), any())).thenReturn(CompletableFuture.completedFuture(true));
    if (phase.equals("reject")) {
      boolean rejected = false;
      try { new GossipService(plugin, relations, reputation); } catch (IllegalStateException expected) { rejected = true; }
      check(rejected, "bad or newer checkpoint rejected");
    } else if (phase.equals("old-write")) {
      var gossip = new GossipService(plugin, relations, reputation);
      String id = gossip.createGossip(CREATOR, "old content", GossipService.GossipTopic.CULTURE);
      gossip.commentOnGossip(CREATOR, id, "old comment"); gossip.markGossipSeen(CREATOR, id); gossip.saveData();
      Files.writeString(root.resolve("id.txt"), id); gossip.close();
    } else {
      var gossip = new GossipService(plugin, relations, reputation);
      var service = new GossipVerificationService(gossip, reputation, relations);
      String id = Files.readString(root.resolve("id.txt"));
      check(gossip.getGossipById(id).content().equals("old content"), "old content preserved");
      check(gossip.getGossipById(id).comments().equals(List.of(CREATOR + ": old comment")), "old comment preserved");
      check(gossip.hasSeenGossip(CREATOR, id), "old read status preserved");
      if (phase.equals("upgrade")) {
        check(service.getVerification(id) == null, "old data has no invented votes");
        for (int i = 3; i <= 5; i++) check(service.voteTruth(new UUID(0, i), id), "new vote recorded");
        service.reportGossip(REPORTER, id, ReportReason.MISLEADING, "review me");
        service.trackSource(id, CREATOR, "Creator");
        String second = gossip.createGossip(CREATOR, "correction subject", GossipService.GossipTopic.CULTURE);
        service.debunkGossip(CREATOR, "Creator", second, "correction", false);
        service.addDebunkEvidence(second, CREATOR, "retained reference");
        Files.writeString(root.resolve("second.txt"), second);
      } else if (phase.equals("reopen") || phase.equals("process") || phase.equals("processed")) {
        var v = service.getVerification(id);
        check(v.isVerified() && v.totalVotes() == 3 && v.reportCount() == 1 && v.sourceCount() == 1, "verification restored");
        check(v.sourcePath().getFirst().sourceName().equals("Creator"), "source name restored");
        var d = service.getVerification(Files.readString(root.resolve("second.txt"))).debunkInfo();
        check(d.debunkContent().equals("correction") && d.evidence().equals(List.of("retained reference")), "debunk restored");
        if (phase.equals("process")) {
          check(service.processReportOnce(id, REPORTER, true), "pending report processed");
          verify(reputation).modifyReputation(REPORTER, 5, ReputationService.ReputationReason.PROTECT_WEAK);
        } else if (phase.equals("processed")) {
          check(!service.processReportOnce(id, REPORTER, true), "processed report not rewarded again");
          verify(reputation, never()).modifyReputation(any(), anyInt(), any());
          check(service.getPendingReports().isEmpty(), "processed queue restored");
        } else check(service.getPendingReports().size() == 1, "pending queue derived from records");
        check(!service.voteFalse(new UUID(0, 3), id), "duplicate vote after restart refused");
        gossip.saveData();
      } else if (phase.equals("stale")) {
        var second = new GossipService(plugin, relations, reputation);
        var other = new GossipVerificationService(second, reputation, relations);
        service.voteTruth(new UUID(0, 99), id);
        boolean refused = false;
        try { other.voteFalse(new UUID(0, 100), id); } catch (IllegalStateException expected) { refused = true; }
        check(refused, "stale writer refused"); second.close();
      } else throw new IllegalArgumentException(phase);
      service.close(); gossip.close();
    }
    System.out.println("{\"phase\":\"" + phase + "\",\"verified\":true,\"server_started\":false}");
  }
}
