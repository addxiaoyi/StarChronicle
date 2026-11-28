package dev.starcore.starcore.social.simulation;

import static org.mockito.Mockito.*;
import dev.starcore.starcore.core.database.DatabaseService;
import dev.starcore.starcore.social.simulation.SocialEventScheduler.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.sqlite.SQLiteDataSource;

/** Frozen-product SQL probe. Bukkit is mocked; no server or world is initialized. */
public class SocialEventRecoveryProbe {
  static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
  public static void main(String[] args) throws Exception {
    String phase = args[0]; Path folder = Path.of(args[1]); Files.createDirectories(folder);
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + folder.resolve("events.db"));
    DatabaseService database = mock(DatabaseService.class);
    when(database.isRunning()).thenReturn(true);
    when(database.dataSource()).thenReturn(Optional.of(source));
    JavaPlugin plugin = mock(JavaPlugin.class);
    when(plugin.getLogger()).thenReturn(Logger.getLogger("offline-social-events"));
    if (phase.equals("old-write")) {
      try (var bukkit = mockStatic(Bukkit.class)) {
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong()))
            .thenReturn(mock(BukkitTask.class));
        var legacy = new SocialEventScheduler(plugin);
        legacy.initializePersistence(plugin, database);
        var method = SocialEventScheduler.class.getDeclaredMethod("startFestival", String.class);
        method.setAccessible(true); method.invoke(legacy, "new_year");
        require(legacy.getActiveEvents().size() == 1, "Old product wrote event");
        legacy.stop();
      }
    } else if (phase.equals("upgrade")) {
      var current = new SocialEventStore(database);
      var old = current.load();
      require(old.size() == 1 && old.get("new_year").type() == SocialEventType.FESTIVAL, "Legacy row retained");
      current.insert(new SocialEvent("new-trend", "New trend", "Persisted trend", SocialEventType.TREND,
          System.currentTimeMillis(), 3600000L, new EventEffects(.2, 1, 1)));
    } else if (phase.equals("reopen")) {
      var events = new SocialEventStore(database).load();
      require(events.keySet().equals(Set.of("new_year", "new-trend")), "Independent reopen retained exact records");
      require(events.get("new-trend").effects().reputationBonus() == .2, "Effects retained");
    } else if (phase.equals("stale")) {
      var first = new SocialEventStore(database); first.load();
      var stale = new SocialEventStore(database); stale.load();
      first.insert(new SocialEvent("temporary", "Temporary", "", SocialEventType.TREND,
          System.currentTimeMillis(), 1, new EventEffects(0, 1, 1)));
      boolean rejected = false;
      try { stale.delete("new-trend"); } catch (IllegalStateException expected) { rejected = true; }
      require(rejected, "Stale delete rejected"); first.delete("temporary");
    } else if (phase.equals("reject")) {
      boolean rejected = false;
      try { new SocialEventStore(database).load(); } catch (IllegalStateException expected) { rejected = true; }
      require(rejected, "Invalid authority rejected");
    } else throw new IllegalArgumentException(phase);
    System.out.println("{\"phase\":\"" + phase + "\",\"verified\":true,\"server_started\":false}");
  }
}
