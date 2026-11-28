package dev.starcore.starcore.module.resource;

import dev.starcore.starcore.core.storage.AbstractModuleStateStorage;
import dev.starcore.starcore.module.nation.NationService;
import dev.starcore.starcore.module.nation.NationModule;
import dev.starcore.starcore.module.nation.model.Nation;
import dev.starcore.starcore.module.nation.model.NationId;
import dev.starcore.starcore.module.resource.model.Factory;
import dev.starcore.starcore.module.resource.model.ProcessingRecipe;
import io.github.addxiaoyi.starchronicle.core.RuleEngine;
import io.github.addxiaoyi.starchronicle.history.CommittedHistoryJournal;
import io.github.addxiaoyi.starchronicle.integration.CommittedSourceCollector;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.lang.reflect.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.*;
import org.sqlite.SQLiteDataSource;

/** Packaged storage/consumers only. Deterministic identity wiring is not a player-auth test. */
public final class ProcessingMergeProbe {
  static final NationId NATION = NationId.of(new UUID(71, 1));
  static final UUID ACTOR = new UUID(71, 2), SUCCESSOR = new UUID(71, 3);
  static final UUID RUN_ONE = new UUID(71, 11), RUN_TWO = new UUID(71, 12);
  static final UUID AUTO_RUN = new UUID(71, 13);
  static final String RESOURCE_NS = "starcore.resources", PROCESSING_NS = "starcore.processing";
  static final ProcessingRecipe RECIPE = new ProcessingRecipe("merge_recipe", "Merge conversion",
      Map.of("mineral", 3L, "energy", 1L), Map.of("industrial", 2L), 1, 0, 1);

  static final class TestClock extends Clock {
    Instant time = Instant.parse("2026-11-26T00:00:00Z");
    public ZoneId getZone() { return ZoneOffset.UTC; }
    public Clock withZone(ZoneId zone) { return this; }
    public Instant instant() { return time; }
    void advance() { time = time.plusSeconds(120); }
  }

  static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }

  static Object call(Object target, String method, Class<?>[] types, Object... args) throws Exception {
    try { return target.getClass().getMethod(method, types).invoke(target, args); }
    catch (InvocationTargetException failure) {
      if (failure.getCause() instanceof Exception exception) throw exception;
      if (failure.getCause() instanceof Error error) throw error;
      throw failure;
    }
  }

  static ResourceStateStorage wrap(String name, ResourceStateStorage delegate, Path path) throws Exception {
    var constructor = Class.forName("dev.starcore.starcore.module.resource." + name)
        .getDeclaredConstructor(ResourceStateStorage.class, Path.class);
    constructor.setAccessible(true);
    return (ResourceStateStorage) constructor.newInstance(delegate, path);
  }

  static void field(Object target, String name, Object value) throws Exception {
    var field = target.getClass().getDeclaredField(name);
    field.setAccessible(true); field.set(target, value);
  }

  @SuppressWarnings("unchecked")
  static <T> T fieldValue(Object target, String name) throws Exception {
    var field = target.getClass().getDeclaredField(name);
    field.setAccessible(true); return (T) field.get(target);
  }

  static Map<Object, Object> mapField(Object target, String name) throws Exception {
    return fieldValue(target, name);
  }

  static Object scanner(String name, ResourceModule source, CommittedHistoryJournal journal) throws Exception {
    var constructor = Class.forName("io.github.addxiaoyi.starchronicle.integration." + name)
        .getDeclaredConstructor(Supplier.class, CommittedHistoryJournal.class);
    constructor.setAccessible(true);
    return constructor.newInstance((Supplier<Object>) () -> source, journal);
  }

  static CommittedSourceCollector collector(ResourceModule module, ProcessingService processing) throws Exception {
    var constructor = CommittedSourceCollector.class.getDeclaredConstructor(Function.class);
    constructor.setAccessible(true);
    return constructor.newInstance((Function<String, Object>)
        key -> key.endsWith("ProcessingService") ? processing : module);
  }

  static List<Object> changes(ResourceModule module, String method) throws Exception {
    List<Object> result = new ArrayList<>(); long after = 0;
    for (int page = 0; page < 50; page++) {
      var values = (List<?>) call(module, method, new Class<?>[] {long.class, int.class}, after, 128);
      result.addAll(values);
      if (values.size() < 128) return result;
      after = (long) call(values.getLast(), "sequence", new Class<?>[0]);
    }
    throw new AssertionError("Unbounded history: " + method);
  }

  static int drain(ResourceModule module, CommittedHistoryJournal journal, RuleEngine engine,
      String adapter, String namespace) throws Exception {
    Object scanner = scanner(adapter, module, journal); int accepted = 0;
    for (int page = 0; page < 50; page++) {
      int scanned = (int) call(scanner, "scan", new Class<?>[0]); int delivered = 0;
      for (var pending : journal.pending(namespace, 128)) {
        engine.acceptCommittedHistory(pending, List.of());
        check(journal.acknowledge(pending.event().id()), "ACK requires full receipt");
        delivered++;
      }
      accepted += delivered;
      if (scanned == 0 && delivered == 0) return accepted;
    }
    throw new AssertionError("Unbounded Chronicle history: " + namespace);
  }

  static void legacyFactory(ResourceModule module, TestClock clock, String id, UUID factory, int batches) {
    var request = new ProcessingService.FactoryRequest(id, factory, NATION, ACTOR, RECIPE.recipeId(), batches);
    check(module.startFactoryRun(request, RECIPE).status() == ProcessingService.FactoryStatus.STARTED,
        "Start old string factory " + id);
    clock.advance();
    check(module.completeFactoryRun(id, NATION, ACTOR).status() == ProcessingService.FactoryStatus.COMPLETED,
        "Complete old string factory " + id);
    check(module.completeFactoryRun(id, NATION, ACTOR).status() == ProcessingService.FactoryStatus.ALREADY_COMPLETED,
        "Old string completion replay must be read-only");
  }

  static void immediate(ResourceModule module, String id, int batches) {
    var request = new ProcessingService.ProductionRequest(id, NATION, ACTOR, RECIPE.recipeId(), batches);
    check(module.processProduction(request, RECIPE).status() == ProcessingService.ProductionStatus.APPLIED,
        "Apply old immediate conversion");
    check(module.processProduction(request, RECIPE).status() == ProcessingService.ProductionStatus.ALREADY_APPLIED,
        "Immediate replay must be read-only");
  }

  static void rejectSchema(Path database, int expectedSchema) throws Exception {
    int actual = (int) SqliteStore.class.getField("SCHEMA_VERSION").get(null);
    check(actual == expectedSchema, "Expected schema " + expectedSchema + ", got " + actual);
    try (var ignored = new SqliteStore(database)) {
      throw new AssertionError("Old schema writer accepted a newer database");
    } catch (IllegalStateException failure) {
      Throwable cause = failure;
      while (cause.getCause() != null) cause = cause.getCause();
      if (!"Database schema newer than this plugin".equals(cause.getMessage())) throw failure;
      System.out.println(SqliteStore.json(Map.of("status", "REJECTED",
          "writer_schema", actual, "reason", cause.getMessage())));
    }
  }

  static Object uuidFactory(ProcessingService service, TestClock clock, UUID id, UUID factory,
      int batches, NationModule nations) throws Exception {
    Class<?>[] types = {UUID.class, UUID.class, UUID.class, String.class, int.class};
    var started = (Optional<?>) call(service, "startProcessing", types, id, factory, ACTOR, RECIPE.recipeId(), batches);
    check(started.isPresent(), "Start new UUID factory");
    check(started.equals(call(service, "startProcessing", types, id, factory, ACTOR, RECIPE.recipeId(), batches)),
        "UUID start retry must return frozen run");
    mapField(nations, "membership").remove(ACTOR);
    mapField(nations, "membership").put(SUCCESSOR, NATION);
    mapField(nations, "nationsById").put(NATION, new Nation(NATION, "Probe nation", SUCCESSOR, "Successor"));
    clock.advance();
    var completed = (Optional<?>) call(service, "completeProcessingRun", new Class<?>[] {UUID.class}, id);
    check(completed.isPresent(), "Complete UUID factory");
    check(ACTOR.equals(call(completed.get(), "actorId", new Class<?>[0])), "Frozen original actor");
    check(completed.equals(call(service, "completeProcessingRun", new Class<?>[] {UUID.class}, id)),
        "UUID completion retry must be read-only");
    mapField(nations, "membership").remove(SUCCESSOR);
    mapField(nations, "membership").put(ACTOR, NATION);
    mapField(nations, "nationsById").put(NATION, new Nation(NATION, "Probe nation", ACTOR, "Original"));
    return completed.get();
  }

  public static void main(String[] args) throws Exception {
    System.setOut(new java.io.PrintStream(System.out, true, java.nio.charset.StandardCharsets.UTF_8));
    Path folder = Path.of(args[0]); String phase = args[1];
    if (phase.equals("reject-schema")) {
      rejectSchema(folder.resolve("chronicle.db"), args.length > 2 ? Integer.parseInt(args[2]) : 10);
      return;
    }
    Files.createDirectories(folder); boolean seed = phase.equals("seed");
    var data = new SQLiteDataSource(); data.setUrl("jdbc:sqlite:" + folder.resolve("starcore.db"));
    ResourceStateStorage storage = new SqlResourceStateStorage(data, new Properties(), java.util.logging.Logger.getAnonymousLogger());
    storage = wrap("ResourceHistoryStorage", storage, folder.resolve("history"));
    if (!seed) storage = wrap("ProcessingHistoryStorage", storage, folder.resolve("processing-history"));
    TestClock clock = new TestClock(); ResourceModule module = new ResourceModule(storage, clock);
    check(!module.authorityDomainId().isBlank(), "Native resource authority available");
    NationModule nations = new NationModule();
    mapField(nations, "nationsById").put(NATION, new Nation(NATION, "Probe nation", ACTOR, "Original"));
    mapField(nations, "membership").put(ACTOR, NATION);
    field(module, "nationService", nations);
    ProcessingService processing = module.getProcessingService();
    if (processing == null) { processing = new SimpleProcessingService(module, new SimpleResourcePriceService()); field(module, "processingService", processing); }
    if (!seed) {
      var setter = processing.getClass().getDeclaredMethod("setNationService", NationService.class);
      setter.setAccessible(true); setter.invoke(processing, nations);
    }
    processing.registerRecipe(RECIPE);
    if (seed) {
      check(module.grant(NATION, "mineral", 1000), "Seed mineral");
      check(module.grant(NATION, "energy", 1000), "Seed energy");
      immediate(module, "legacy:production", 2);
      Factory factory = module.buildNativeFactory(NATION, "Shared factory", Factory.FactoryType.SMELTER);
      legacyFactory(module, clock, "legacy:factory", factory.factoryId(), 1);
    }
    if (phase.equals("append")) {
      UUID factory = module.factoryRun("legacy:factory").orElseThrow().factoryId();
      uuidFactory(processing, clock, RUN_ONE, factory, 3, nations);
      legacyFactory(module, clock, "current:factory", factory, 2);
      immediate(module, "current:production", 1);
      uuidFactory(processing, clock, RUN_TWO, factory, 2, nations);
      var automatic = (Optional<?>) call(processing, "autoProcess",
          new Class<?>[] {UUID.class, NationId.class, String.class, int.class}, AUTO_RUN, NATION, RECIPE.recipeId(), 1);
      check(automatic.isPresent(), "UUID automatic processing");
      check(automatic.equals(call(processing, "autoProcess",
          new Class<?>[] {UUID.class, NationId.class, String.class, int.class}, AUTO_RUN, NATION, RECIPE.recipeId(), 1)),
          "Automatic UUID retry must be read-only");
    }
    try (var db = new SqliteStore(folder.resolve("chronicle.db"))) {
      var engine = new RuleEngine(db, List.of(), Clock.systemUTC());
      var journal = new CommittedHistoryJournal(db); var collector = collector(module, processing);
      int produced = 0, transferred = 0;
      for (var source : List.of(CommittedSourceCollector.Source.RESOURCES, CommittedSourceCollector.Source.PRODUCTION)) {
        for (int page = 0; page < 50; page++) {
          int accepted = engine.acceptCommittedSource(collector.collect(source, engine.committedSourceCursor(source.id)).orElseThrow(), List.of());
          if (source == CommittedSourceCollector.Source.PRODUCTION) produced += accepted; else transferred += accepted;
          if (accepted == 0) break; check(page < 49, "Unbounded old source");
        }
      }
      int resourceAccepted = drain(module, journal, engine, "StarCoreResourceHistory", RESOURCE_NS);
      int processingAccepted = seed ? 0 : drain(module, journal, engine, "StarCoreProcessingHistory", PROCESSING_NS);
      Map<String, Object> result = new TreeMap<>();
      result.put("phase", phase); result.put("native_domain", module.authorityDomainId());
      result.put("stockpile", module.stockpile(NATION));
      result.put("new_productions", produced); result.put("new_transfers", transferred);
      result.put("accepted_resources", resourceAccepted); result.put("accepted_processing", processingAccepted);
      result.put("transactions", ResourceStateCodec.snapshotFromProperties(storage.load(), ResourceModule.RESOURCE_TYPES).receipts());
      result.put("productions", storage.productionReceiptsAfter(0, 128));
      result.put("transfers", storage.transferReceiptsAfter(0, 128));
      result.put("legacy_factory", module.factoryRun("legacy:factory").orElse(null));
      result.put("current_factory", module.factoryRun("current:factory").orElse(null));
      result.put("production_cursor", engine.committedSourceCursor("starcore.production").orElse(null));
      result.put("transfer_cursor", engine.committedSourceCursor(RESOURCE_NS).orElse(null));
      result.put("resource_cursor", journal.cursor(RESOURCE_NS).orElse(null));
      result.put("resource_changes", changes(module, "resourceChangesAfter"));
      if (!seed) {
        result.put("processing_cursor", journal.cursor(PROCESSING_NS).orElse(null));
        result.put("processing_changes", changes(module, "processingChangesAfter"));
      }
      result.put("sql", db.transaction(c -> {
        Map<String, Object> sql = new TreeMap<>();
        try (var s = c.createStatement(); var rows = s.executeQuery("PRAGMA user_version")) { rows.next(); sql.put("schema", rows.getInt(1)); }
        Map<String, Long> counts = new TreeMap<>();
        for (String table : List.of("events", "contributions", "resource_history_receipts",
            "processing_history_receipts", "committed_history_ingress")) {
          try (var s = c.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            s.setString(1, table); try (var rows = s.executeQuery()) { if (!rows.next()) continue; }
          }
          try (var s = c.createStatement(); var rows = s.executeQuery("SELECT count(*) FROM " + table)) { rows.next(); counts.put(table, rows.getLong(1)); }
        }
        sql.put("counts", counts); List<Map<String, String>> contributions = new ArrayList<>();
        try (var s = c.createStatement(); var rows = s.executeQuery("SELECT id,operation_id,subject_id,unit,amount,source_event_id,actor_id,nation_id FROM contributions ORDER BY id")) {
          while (rows.next()) { Map<String, String> row = new TreeMap<>();
            for (String column : List.of("id", "operation_id", "subject_id", "unit", "amount", "source_event_id", "actor_id", "nation_id")) row.put(column, rows.getString(column));
            contributions.add(row);
          }
        }
        sql.put("contributions", contributions);
        Map<String, String> eventTypes = new TreeMap<>();
        try (var s = c.createStatement(); var rows = s.executeQuery("SELECT id,type FROM events ORDER BY id")) {
          while (rows.next()) eventTypes.put(rows.getString(1), rows.getString(2));
        }
        sql.put("event_types", eventTypes); return sql;
      }));
      System.out.println(SqliteStore.json(result));
    } finally { AbstractModuleStateStorage.shutdownNamespace("test"); }
  }
}
