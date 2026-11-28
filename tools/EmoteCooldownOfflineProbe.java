package dev.starcore.starcore.social.emote;

import com.google.gson.Gson;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Separate JVM import/export through the actual new service-backed cooldown authority. */
public final class EmoteCooldownOfflineProbe {
  @SuppressWarnings("unchecked")
  public static void main(String[] args) throws Exception {
    Path input = Path.of(args[0]);
    Map<String, Object> document = new Gson().fromJson(Files.readString(input), Map.class);
    UUID player = UUID.fromString((String) document.get("player"));
    AtomicLong now = new AtomicLong(((Number) document.get("clock")).longValue());
    EmoteService service = new EmoteService(now::get);
    EmoteCooldownManager manager = new EmoteCooldownManager(now::get);
    manager.setEmoteService(service);
    manager.deserialize((Map<String, Object>) document.get("cooldowns"));
    Map<String, Long> loaded = service.getOrCreateState(player).cooldownSnapshot();
    check(loaded.keySet().equals(Set.of("wave", "bow")), "Legacy cooldown keys lost");
    check(!service.getOrCreateState(player).isAnimating(), "Import started an animation");
    check(manager.getPlayerCooldowns(player).size() == 2, "Actual service authority not consulted");
    check(manager.getActiveCooldownCount() == 1, "Actual active count incorrect");
    var state = service.getOrCreateState(player);
    UUID target = new UUID(0, 43);
    state.setCurrentEmote("dance", now.get(), target);
    long revision = state.getAnimationRevision();
    manager.startCooldown(player, "clap");
    check(state.getAnimationRevision() == revision && target.equals(state.getTargetPlayerId()), "Cooldown-only change replaced animation");
    manager.clearCooldown(player, "clap");
    manager.clearCooldown(player, "dance");
    check(state.isAnimating(), "Clearing cooldown stopped animation");
    var unchanged = manager.serialize();
    check(state.cooldownSnapshot().equals(loaded), "Legacy timestamps changed in service state");
    if (args.length > 1) {
      Map<String, Object> output = new LinkedHashMap<>(document);
      output.put("cooldowns", unchanged);
      Files.writeString(Path.of(args[1]), new Gson().toJson(output), StandardOpenOption.CREATE_NEW);
    }
    now.set(loaded.get("wave") + 2_999L);
    check(manager.getRemainingCooldown(player, "wave") == 1, "Last millisecond hidden");
    now.incrementAndGet();
    check(!manager.isOnCooldown(player, "wave"), "Exact expiry not honored");
    check(manager.isOnCooldown(player, "bow"), "One emote expiry cleared another");
    manager.clearPlayerCooldowns(player);
    check(manager.getPlayerCooldowns(player).isEmpty() && manager.getActiveCooldownCount() == 0, "Actual clear did not apply");
    EmoteService emptyService = new EmoteService(now::get);
    EmoteCooldownManager empty = new EmoteCooldownManager(now::get);
    empty.setEmoteService(emptyService);
    empty.deserialize((Map<String, Object>) document.get("oldAttached"));
    check(emptyService.getPlayerState(player).isEmpty(), "Invented evidence omitted by old attached export");
    System.out.println("{\"verified\":true,\"phase\":\"restore\",\"server_started\":false}");
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
