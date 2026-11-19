"""Stage and diff a necessary upstream owner-thread contract, without changing the audit checkout.

This is deliberately NOT a complete StarCore Folia port and does not modify plugin.yml.
Run with the read-only StarCore baseline directory as the first argument.
"""
from pathlib import Path
import difflib, sys

root = Path(__file__).resolve().parents[1]
baseline = Path(sys.argv[1]).resolve()
stage = root / 'run' / 'starcore-folia-contract'
prefix = Path('src/main/java/dev/starcore/starcore')
changes = {}

def replace_once(text, old, new):
    if text.count(old) != 1:
        raise ValueError('Expected one exact upstream target: ' + old[:100])
    return text.replace(old, new, 1)

scheduler_path = prefix / 'core/scheduler/StarCoreScheduler.java'
scheduler = (baseline/scheduler_path).read_text(encoding='utf-8')
scheduler = replace_once(scheduler, 'import java.util.List;', 'import java.util.List;\nimport java.util.Map;\nimport java.util.concurrent.ConcurrentHashMap;\nimport java.util.concurrent.atomic.AtomicBoolean;')
scheduler = replace_once(scheduler, 'import org.bukkit.Bukkit;', 'import org.bukkit.Bukkit;\nimport org.bukkit.entity.Entity;')
scheduler = replace_once(scheduler, '  private volatile boolean shutdown = false;', '''  private volatile boolean shutdown = false;

  // Only tasks which have not started may be rejected as definitely not applied.
  private final Map<CompletableFuture<?>, Runnable> pendingEntityTasks =
      new ConcurrentHashMap<>();''')
needle = '  /** Gets the number of completed tasks. */'
scheduler = replace_once(scheduler, needle, '''  /**
   * True only when the current thread owns this entity. This is not a global/main-thread test.
   * Paper implements the same API for its single owning server thread.
   */
  public boolean isOwnedByCurrentThread(Entity entity) {
    return Bukkit.isOwnedByCurrentRegion(Objects.requireNonNull(entity, "entity"));
  }

  /** Fail before touching entity-local data when a synchronous API is called from elsewhere. */
  public void requireEntityOwner(Entity entity) {
    if (!isOwnedByCurrentThread(entity)) {
      throw new IllegalStateException("Operation requires the entity owning thread; use callForEntity");
    }
  }

  /**
   * Run the whole operation on the entity's owning thread and complete only after it returns.
   * The entity scheduler follows teleports; no global or arbitrary location fallback is used.
   *
   * <p>{@link EntityTaskNotAppliedException} means the body never started. Exceptions from the
   * body do not imply rollback and must not trigger an automatic retry of an external effect.
   * Cancelling a future after its body has started cannot undo that body.
   * The retired callback only completes a future; it never touches entity/world state.
   */
  public <T> CompletableFuture<T> callForEntity(Entity entity, Supplier<T> operation) {
    Objects.requireNonNull(entity, "entity");
    Objects.requireNonNull(operation, "operation");
    CompletableFuture<T> result = new CompletableFuture<>();
    AtomicBoolean claimed = new AtomicBoolean();
    Runnable reject = () -> {
      if (claimed.compareAndSet(false, true)) {
        result.completeExceptionally(new EntityTaskNotAppliedException(
            "Entity retired or scheduler stopped before operation started"));
      }
    };
    Runnable execute = () -> {
      if (!claimed.compareAndSet(false, true) || result.isDone()) return;
      if (shutdown) {
        result.completeExceptionally(new EntityTaskNotAppliedException(
            "Scheduler stopped before operation started"));
        return;
      }
      try {
        requireEntityOwner(entity);
        result.complete(operation.get());
      } catch (Throwable error) {
        result.completeExceptionally(error);
      }
    };
    pendingEntityTasks.put(result, reject);
    result.whenComplete((value, error) -> pendingEntityTasks.remove(result));
    if (shutdown) {
      reject.run();
      return result;
    }
    try {
      if (isOwnedByCurrentThread(entity)) {
        execute.run();
      } else if (!entity.getScheduler().execute(plugin, execute, reject, 1L)) {
        reject.run();
      }
    } catch (Throwable error) {
      if (claimed.compareAndSet(false, true)) result.completeExceptionally(error);
    }
    return result;
  }

  /** A precise no-application result for an entity task rejected before its body began. */
  public static final class EntityTaskNotAppliedException extends RejectedExecutionException {
    public EntityTaskNotAppliedException(String message) {
      super(message);
    }
  }

  private void rejectPendingEntityTasks() {
    pendingEntityTasks.values().forEach(Runnable::run);
  }

''' + needle)
scheduler = replace_once(scheduler, '    shutdown = true;\n\n    // 拒绝新任务', '    shutdown = true;\n    rejectPendingEntityTasks();\n\n    // 拒绝新任务')
scheduler = replace_once(scheduler, '  public void shutdownNow() {\n    shutdown = true;', '  public void shutdownNow() {\n    shutdown = true;\n    rejectPendingEntityTasks();')
changes[scheduler_path] = scheduler

engine_path = prefix / 'module/narrative/engine/NarrativeEngine.java'
engine = (baseline/engine_path).read_text(encoding='utf-8')
engine = replace_once(engine, 'import java.util.concurrent.ConcurrentHashMap;', 'import java.util.concurrent.CompletableFuture;\nimport java.util.concurrent.ConcurrentHashMap;')
engine = replace_once(engine, 'import org.bukkit.Bukkit;\n', '')
engine = replace_once(engine, ''' * <p>All public methods are safe to call from any thread; state mutations are always dispatched to
 * the main Bukkit thread via the provided {@link StarCoreScheduler}.''', ''' * <p>Player-bound void entry points dispatch the whole mutation to that player's owning thread.
 * Synchronous result APIs require the owner thread; asynchronous variants report actual completion.
 * UUID-only mutable state access is owner-thread-only (or requires a quiescent offline lifecycle).
 * This contract alone does not make unrelated modules or bulk persistence Folia-safe.''')
engine = replace_once(engine, '    Objects.requireNonNull(scriptId, "scriptId");\n\n    NarrativeScript script', '    Objects.requireNonNull(scriptId, "scriptId");\n    scheduler.requireEntityOwner(player);\n\n    NarrativeScript script')
engine = replace_once(engine, '  /** Convenience overload using the script\'s default start node. */', '''  /**
   * Start from any thread; completion means all initial state mutation and first rendering returned.
   * Retirement before execution completes exceptionally without creating or changing a session.
   */
  public CompletableFuture<NarrativeScript> startScriptAsync(
      @NotNull Player player, @NotNull String scriptId, @Nullable String startNodeId) {
    return scheduler.callForEntity(player, () -> startScript(player, scriptId, startNodeId));
  }

  public CompletableFuture<NarrativeScript> startScriptAsync(
      @NotNull Player player, @NotNull String scriptId) {
    return startScriptAsync(player, scriptId, null);
  }

  /** Convenience overload using the script's default start node; requires the owning thread. */''')
engine = replace_once(engine, '   * @param player the player to start the script for (must be online)', '   * @param player the player to start the script for (must be online and owned by this thread)')
engine = engine.replace('dispatchMain(', 'dispatchPlayer(player, ')
engine = replace_once(engine, '''   * Attempt to roll back to the most recent checkpoint for this player. Safe to call from any
   * thread.''', '''   * Attempt to roll back to the most recent checkpoint for this player on its owning thread.
   * Use rollbackCheckpointAsync from other threads.''')
engine = replace_once(engine, '  public boolean rollbackCheckpoint(@NotNull Player player) {\n    if (!ready) return false;', '  public boolean rollbackCheckpoint(@NotNull Player player) {\n    if (!ready) return false;\n    scheduler.requireEntityOwner(player);')
engine = replace_once(engine, '  /** Manually create a checkpoint for the current session. Safe to call from any thread. */', '''  /** Run the entire rollback on the owner thread; the future is the actual rollback result. */
  public CompletableFuture<Boolean> rollbackCheckpointAsync(@NotNull Player player) {
    return scheduler.callForEntity(player, () -> rollbackCheckpoint(player));
  }

  /** Manually create a checkpoint for the current session. Safe to call from any thread. */''')
engine = replace_once(engine, '''  /** Dispatch a runnable to the main Bukkit thread. */
  private void dispatchPlayer(player, @NotNull Runnable r) {
    if (Bukkit.isPrimaryThread()) {
      r.run();
    } else {
      scheduler.runSync(r);
    }
  }''', '''  /** Dispatch the whole player operation; a retired player never falls back to a global thread. */
  private void dispatchPlayer(@NotNull Player player, @NotNull Runnable operation) {
    scheduler.callForEntity(player, () -> {
      operation.run();
      return null;
    }).whenComplete((value, error) -> {
      if (error != null) logger.warning("Narrative player operation did not complete: " + error);
    });
  }''')
engine = engine.replace('dispatchPlayer(player, \n', 'dispatchPlayer(player,\n')
changes[engine_path] = engine

test_path = Path('src/test/java/dev/starcore/starcore/core/scheduler/EntityTaskContractTest.java')
changes[test_path] = '''package dev.starcore.starcore.core.scheduler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class EntityTaskContractTest {
  private StarCoreScheduler scheduler;
  private JavaPlugin plugin;
  private Entity entity;
  private EntityScheduler owner;
  private MockedStatic<Bukkit> bukkit;
  private final AtomicReference<Runnable> body = new AtomicReference<>();
  private final AtomicReference<Runnable> retired = new AtomicReference<>();

  @BeforeEach void setup() {
    plugin = mock(JavaPlugin.class);
    entity = mock(Entity.class);
    owner = mock(EntityScheduler.class);
    scheduler = new StarCoreScheduler(plugin, 1, 1);
    bukkit = mockStatic(Bukkit.class);
    when(entity.getScheduler()).thenReturn(owner);
    when(owner.execute(eq(plugin), any(), any(), eq(1L))).thenAnswer(call -> {
      body.set(call.getArgument(1));
      retired.set(call.getArgument(2));
      return true;
    });
  }

  @AfterEach void cleanup() {
    scheduler.shutdownNow();
    bukkit.close();
  }

  private void becomeOwner() {
    bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(entity)).thenReturn(true);
  }

  @Test void ownerExecutesInlineAndReturnsActualValue() {
    becomeOwner();
    assertEquals("done", scheduler.callForEntity(entity, () -> "done").join());
    verifyNoInteractions(owner);
  }

  @Test void queuedIsNotAppliedUntilOwnedExecutionReturns() {
    AtomicInteger calls = new AtomicInteger();
    CompletableFuture<Integer> future = scheduler.callForEntity(entity, calls::incrementAndGet);
    assertFalse(future.isDone());
    assertEquals(0, calls.get());
    becomeOwner();
    body.get().run();
    assertEquals(1, future.join());
    assertEquals(1, calls.get());
  }

  @Test void retiredCompletesWithoutApplyingOrGlobalFallback() {
    AtomicInteger calls = new AtomicInteger();
    CompletableFuture<Integer> future = scheduler.callForEntity(entity, calls::incrementAndGet);
    retired.get().run();
    assertInstanceOf(StarCoreScheduler.EntityTaskNotAppliedException.class,
        assertThrows(CompletionException.class, future::join).getCause());
    becomeOwner();
    body.get().run();
    assertEquals(0, calls.get());
    bukkit.verify(() -> Bukkit.getScheduler(), never());
    bukkit.verify(() -> Bukkit.getGlobalRegionScheduler(), never());
  }

  @Test void schedulingFalseCompletesWithoutApplying() {
    when(owner.execute(eq(plugin), any(), any(), eq(1L))).thenReturn(false);
    CompletableFuture<String> future = scheduler.callForEntity(entity, () -> fail("must not run"));
    assertInstanceOf(StarCoreScheduler.EntityTaskNotAppliedException.class,
        assertThrows(CompletionException.class, future::join).getCause());
  }

  @Test void shutdownRejectsPendingAndPreventsLaterExecution() {
    AtomicInteger calls = new AtomicInteger();
    CompletableFuture<Integer> future = scheduler.callForEntity(entity, calls::incrementAndGet);
    scheduler.shutdownNow();
    becomeOwner();
    body.get().run();
    assertEquals(0, calls.get());
    assertInstanceOf(StarCoreScheduler.EntityTaskNotAppliedException.class,
        assertThrows(CompletionException.class, future::join).getCause());
  }

  @Test void cancelledPendingFuturePreventsApplication() {
    AtomicInteger calls = new AtomicInteger();
    CompletableFuture<Integer> future = scheduler.callForEntity(entity, calls::incrementAndGet);
    assertTrue(future.cancel(false));
    becomeOwner();
    body.get().run();
    assertEquals(0, calls.get());
  }

  @Test void bodyErrorPropagatesAndIsNotRelabelledNotApplied() {
    becomeOwner();
    IllegalStateException failure = new IllegalStateException("body failed after entry");
    CompletableFuture<String> future = scheduler.callForEntity(entity, () -> { throw failure; });
    assertSame(failure, assertThrows(CompletionException.class, future::join).getCause());
  }

  @Test void wrongOwnerCannotPassSynchronousGuard() {
    assertThrows(IllegalStateException.class, () -> scheduler.requireEntityOwner(entity));
  }

  @Test void misroutedSchedulerCallbackFailsBeforeApplication() {
    AtomicInteger calls = new AtomicInteger();
    CompletableFuture<Integer> future = scheduler.callForEntity(entity, calls::incrementAndGet);
    body.get().run();
    assertEquals(0, calls.get());
    assertInstanceOf(IllegalStateException.class,
        assertThrows(CompletionException.class, future::join).getCause());
  }
}
'''

changes[Path('src/test/java/dev/starcore/starcore/module/narrative/engine/NarrativeEngineOwnerContractTest.java')] = '''package dev.starcore.starcore.module.narrative.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.starcore.starcore.core.scheduler.StarCoreScheduler;
import dev.starcore.starcore.module.narrative.event.NarrativeEventBus;
import dev.starcore.starcore.module.narrative.script.NarrativeScript;
import dev.starcore.starcore.module.narrative.script.ScriptLoader;
import dev.starcore.starcore.module.narrative.state.PlayerNarrativeStateManager;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NarrativeEngineOwnerContractTest {
  private StarCoreScheduler scheduler;
  private ScriptLoader loader;
  private PlayerNarrativeStateManager states;
  private Player player;
  private NarrativeEngine engine;

  @BeforeEach void setup() {
    scheduler = mock(StarCoreScheduler.class);
    loader = mock(ScriptLoader.class);
    states = mock(PlayerNarrativeStateManager.class);
    player = mock(Player.class);
    engine = new NarrativeEngine(Logger.getAnonymousLogger(), scheduler,
        mock(NarrativeEventBus.class), states, loader, mock(TypewriterController.class));
    engine.onEnable();
  }

  @Test void synchronousStartRejectsBeforeLoadingOrMutatingAnything() {
    doThrow(new IllegalStateException("wrong owner")).when(scheduler).requireEntityOwner(player);
    assertThrows(IllegalStateException.class, () -> engine.startScript(player, "test"));
    verifyNoInteractions(loader, states, player);
  }

  @Test void synchronousRollbackRejectsBeforeStateMutation() {
    doThrow(new IllegalStateException("wrong owner")).when(scheduler).requireEntityOwner(player);
    assertThrows(IllegalStateException.class, () -> engine.rollbackCheckpoint(player));
    verifyNoInteractions(states, player);
  }

  @Test void asyncStartDoesNotLoadOrMutateBeforeOwnerTaskRuns() {
    CompletableFuture<NarrativeScript> queued = new CompletableFuture<>();
    AtomicReference<Supplier<NarrativeScript>> action = new AtomicReference<>();
    when(scheduler.<NarrativeScript>callForEntity(eq(player), any())).thenAnswer(call -> {
      action.set(call.getArgument(1));
      return queued;
    });
    CompletableFuture<NarrativeScript> result = engine.startScriptAsync(player, "missing");
    assertSame(queued, result);
    assertFalse(result.isDone());
    verifyNoInteractions(loader, states, player);
    queued.complete(action.get().get());
    assertNull(result.join());
    verify(scheduler).requireEntityOwner(player);
    verify(loader).load("missing");
    verifyNoInteractions(states);
  }

  @Test void retirementPropagatesWithoutStartingAnySession() {
    CompletableFuture<NarrativeScript> rejected = CompletableFuture.failedFuture(
        new StarCoreScheduler.EntityTaskNotAppliedException("retired"));
    when(scheduler.<NarrativeScript>callForEntity(eq(player), any())).thenReturn(rejected);
    assertSame(rejected, engine.startScriptAsync(player, "test"));
    verifyNoInteractions(loader, states, player);
  }
}
'''

for path, content in changes.items():
    target = stage/path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding='utf-8')

pom = '''<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
<modelVersion>4.0.0</modelVersion><groupId>io.github.addxiaoyi.audit</groupId><artifactId>starcore-folia-owner-contract-check</artifactId><version>1</version>
<properties><maven.compiler.release>21</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
<repositories><repository><id>papermc</id><url>https://repo.papermc.io/repository/maven-public/</url></repository></repositories>
<dependencies>
<dependency><groupId>dev.starcore</groupId><artifactId>starcore</artifactId><version>1.0.0</version><scope>provided</scope></dependency>
<dependency><groupId>io.papermc.paper</groupId><artifactId>paper-api</artifactId><version>1.21.11-R0.1-SNAPSHOT</version><scope>provided</scope></dependency>
<dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>5.11.4</version><scope>test</scope></dependency>
<dependency><groupId>org.mockito</groupId><artifactId>mockito-core</artifactId><version>5.15.2</version><scope>test</scope></dependency>
</dependencies>
<build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version></plugin><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.5.2</version></plugin></plugins></build>
</project>'''
(stage/'pom.xml').write_text(pom,encoding='utf-8')
patch = []
for path, content in changes.items():
    original = (baseline/path).read_text(encoding='utf-8') if (baseline/path).exists() else ''
    patch.extend(difflib.unified_diff(original.splitlines(True),content.splitlines(True),fromfile='a/'+path.as_posix() if original else '/dev/null',tofile='b/'+path.as_posix(),n=3))
target = root/'patches'/'starcore-folia-narrative-owner-contract.patch'
target.parent.mkdir(exist_ok=True)
target.write_text(''.join(patch),encoding='utf-8',newline='\n')
print('Staged',len(changes),'files at',stage)
print('Created',target,'without modifying baseline or declaring upstream Folia support')
