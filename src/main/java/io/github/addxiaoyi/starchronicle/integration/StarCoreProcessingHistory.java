package io.github.addxiaoyi.starchronicle.integration;

import io.github.addxiaoyi.starchronicle.api.processing.ProcessingHistoryReceipt;
import io.github.addxiaoyi.starchronicle.history.ProcessingHistoryProjection;
import io.github.addxiaoyi.starchronicle.history.CommittedHistoryJournal;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import static io.github.addxiaoyi.starchronicle.integration.StarCoreBridge.call;

/** Replays the native processing log with frozen runs and original actors. */
public final class StarCoreProcessingHistory {
    public static final String NAMESPACE = ProcessingHistoryProjection.NAMESPACE;
    private final Supplier<Object> source;
    private final CommittedHistoryJournal journal;
    private volatile boolean committedMode;

    public StarCoreProcessingHistory(StarCoreBridge bridge, CommittedHistoryJournal journal) {
        this(() -> bridge.service("module.resource.ProcessingHistoryService"), journal);
    }
    StarCoreProcessingHistory(Supplier<Object> source, CommittedHistoryJournal journal) {
        this.source = Objects.requireNonNull(source); this.journal = Objects.requireNonNull(journal);
        committedMode = journal.cursor(NAMESPACE).isPresent();
    }

    /** Once seen or pinned, an unavailable/broken durable source must not produce fallback
     * observation events for the same native change. */
    public boolean usesCommittedHistory() {
        if (committedMode) return true;
        Object service = source.get();
        if (service == null) return false;
        try {
            if (domain(service).isPresent()) committedMode = true;
        } catch (NoSuchMethodException legacy) {
            return false;
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            committedMode = true;
        }
        return committedMode;
    }

    public int scan() {
        Object service = source.get();
        if (service == null) {
            if (committedMode) throw new IllegalStateException("Pinned processing history is unavailable");
            return 0;
        }
        try {
            // The native monitor also owns state mutations. Keep the domain and page from one
            // source read, but release it before touching Chronicle's persistence transaction.
            UUID domain;
            synchronized (service) {
                Optional<UUID> identity = domain(service);
                if (identity.isEmpty()) {
                    if (committedMode) throw new IllegalStateException("Pinned processing history capability disappeared");
                    return 0;
                }
                domain = identity.get();
            }
            committedMode = true;
            var cursor = journal.cursor(NAMESPACE);
            if (cursor.isPresent() && !cursor.get().authorityDomain().equals(domain))
                throw new IllegalStateException("Processing history authority changed; restore matching backups");
            if (journal.hasPending(NAMESPACE)) return 0;
            long after = cursor.map(CommittedHistoryJournal.Cursor::sequence).orElse(0L);
            List<CommittedHistoryJournal.Entry> entries = new ArrayList<>();
            synchronized (service) {
                if (!domain(service).equals(Optional.of(domain))) throw new IllegalStateException("Processing history authority changed during read");
                Object result = call(service, "processingChangesAfter", after, CommittedHistoryJournal.PAGE_SIZE);
                if (!(result instanceof List<?> page) || page.size() > CommittedHistoryJournal.PAGE_SIZE)
                    throw new IllegalArgumentException("Invalid processing history page");
                long expected = after;
                for (Object receipt : page) {
                    expected = Math.addExact(expected, 1);
                    entries.add(project(receipt, domain, expected));
                }
                if (!domain(service).equals(Optional.of(domain))) throw new IllegalStateException("Processing history authority changed during read");
            }
            if (!entries.isEmpty() || cursor.isEmpty() || !cursor.get().live()) journal.stage(NAMESPACE, domain, after, entries);
            return entries.size();
        } catch (NoSuchMethodException legacy) {
            if (committedMode) throw new IllegalStateException("Pinned processing history protocol is unavailable", legacy);
            return 0;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot read committed processing history", failure);
        }
    }

    private static Optional<UUID> domain(Object service) throws ReflectiveOperationException {
        Object value = call(service, "processingHistoryAuthorityDomainId");
        if (!(value instanceof Optional<?> identity)) throw new IllegalArgumentException("Missing processing history capability response");
        if (identity.isEmpty()) return Optional.empty();
        if (!(identity.get() instanceof UUID id)) throw new IllegalArgumentException("Invalid resource authority domain");
        return Optional.of(id);
    }
    static CommittedHistoryJournal.Entry project(Object raw, UUID domain, long expected) throws ReflectiveOperationException {
        if (!domain.equals(uuid(call(raw, "authorityDomainId")))) throw new IllegalArgumentException("Resource receipt authority mismatch");
        Object sequence = call(raw, "sequence");
        if (!(sequence instanceof Long position) || position != expected) throw new IllegalArgumentException("Resource receipt sequence gap");
        Object kind = call(raw, "kind"), time = call(raw, "committedAt");
        if (!(kind instanceof Enum<?> value) || !(time instanceof Instant at)) throw new IllegalArgumentException("Missing processing kind/time");
        Object nativeRun = call(raw, "run");
        if (nativeRun == null) throw new IllegalArgumentException("Missing processing run");
        UUID runId = uuid(call(nativeRun, "runId")), factory = nullableUuid(call(nativeRun, "factoryId"));
        UUID actor = nullableUuid(call(nativeRun, "actorId"));
        String nationText = Objects.toString(call(nativeRun, "nationId"), "");
        UUID nation = UUID.fromString(nationText);
        if (!nation.toString().equals(nationText)) throw new IllegalArgumentException("Invalid processing nation");
        Object batchCount = call(nativeRun, "batches");
        if (!(batchCount instanceof Integer batches)) throw new IllegalArgumentException("Invalid native batch count");
        var run = new ProcessingHistoryReceipt.Run(runId, factory, nation, actor, text(call(nativeRun, "recipeId")), batches,
                resources(call(nativeRun, "inputs")), resources(call(nativeRun, "outputs")),
                instant(call(nativeRun, "startedAt")), instant(call(nativeRun, "readyAt")),
                call(nativeRun, "completedAt") == null ? null : instant(call(nativeRun, "completedAt")));
        var receipt = new ProcessingHistoryReceipt(domain, position, ProcessingHistoryReceipt.Kind.valueOf(value.name()), run, at);
        return new CommittedHistoryJournal.Entry(position, ProcessingHistoryProjection.event(receipt), null, null, receipt);
    }
    private static UUID nullableUuid(Object value) { return value == null ? null : uuid(value); }
    private static Instant instant(Object value) {
        if (!(value instanceof Instant time)) throw new IllegalArgumentException("Missing processing time");
        return time;
    }
    private static Map<String,Long> resources(Object raw) {
        if (!(raw instanceof Map<?,?> map) || map.isEmpty() || map.size() > 32) throw new IllegalArgumentException("Invalid processing resources");
        Map<String,Long> result = new TreeMap<>();
        for (var e : map.entrySet()) {
            if (!(e.getKey() instanceof String type) || !(e.getValue() instanceof Long amount)) throw new IllegalArgumentException("Invalid processing quantity");
            result.put(type, amount);
        }
        return result;
    }
    private static String text(Object value) {
        if (!(value instanceof String s)) throw new IllegalArgumentException("Missing native resource text");
        return s;
    }
    private static UUID uuid(Object value) {
        if (!(value instanceof UUID id)) throw new IllegalArgumentException("Missing native resource UUID");
        return id;
    }
}
