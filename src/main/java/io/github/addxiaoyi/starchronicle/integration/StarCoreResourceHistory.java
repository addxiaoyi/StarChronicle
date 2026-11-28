package io.github.addxiaoyi.starchronicle.integration;

import io.github.addxiaoyi.starchronicle.api.resource.ResourceHistoryReceipt;
import io.github.addxiaoyi.starchronicle.history.ResourceHistoryProjection;
import io.github.addxiaoyi.starchronicle.history.CommittedHistoryJournal;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import static io.github.addxiaoyi.starchronicle.integration.StarCoreBridge.call;

/** Replays the native resource commit log with complete before/after balances. */
public final class StarCoreResourceHistory {
    public static final String NAMESPACE = ResourceHistoryProjection.NAMESPACE;
    private final Supplier<Object> source;
    private final CommittedHistoryJournal journal;
    private volatile boolean committedMode;

    public StarCoreResourceHistory(StarCoreBridge bridge, CommittedHistoryJournal journal) {
        this(() -> bridge.service("module.resource.ResourceHistoryService"), journal);
    }
    StarCoreResourceHistory(Supplier<Object> source, CommittedHistoryJournal journal) {
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
            if (committedMode) throw new IllegalStateException("Pinned resource history is unavailable");
            return 0;
        }
        try {
            // The native monitor also owns state mutations. Keep the domain and page from one
            // source read, but release it before touching Chronicle's persistence transaction.
            UUID domain;
            synchronized (service) {
                Optional<UUID> identity = domain(service);
                if (identity.isEmpty()) {
                    if (committedMode) throw new IllegalStateException("Pinned resource history capability disappeared");
                    return 0;
                }
                domain = identity.get();
            }
            committedMode = true;
            var cursor = journal.cursor(NAMESPACE);
            if (cursor.isPresent() && !cursor.get().authorityDomain().equals(domain))
                throw new IllegalStateException("Resource history authority changed; restore matching backups");
            if (journal.hasPending(NAMESPACE)) return 0;
            long after = cursor.map(CommittedHistoryJournal.Cursor::sequence).orElse(0L);
            List<CommittedHistoryJournal.Entry> entries = new ArrayList<>();
            boolean atHead;
            synchronized (service) {
                if (!domain(service).equals(Optional.of(domain))) throw new IllegalStateException("Resource history authority changed during read");
                long readAfter=after==0?0:after-1;
                Object result = call(service, "resourceChangesAfter", readAfter, CommittedHistoryJournal.PAGE_SIZE);
                if (!(result instanceof List<?> page) || page.size() > CommittedHistoryJournal.PAGE_SIZE)
                    throw new IllegalArgumentException("Invalid resource history page");
                atHead=page.size()<CommittedHistoryJournal.PAGE_SIZE;
                if(after>0&&page.isEmpty())throw new IllegalStateException("Resource source lost its acknowledged receipt");
                long expected = readAfter;
                for (Object receipt : page) {
                    expected = Math.addExact(expected, 1);
                    entries.add(project(receipt, domain, expected));
                }
                if (!domain(service).equals(Optional.of(domain))) throw new IllegalStateException("Resource history authority changed during read");
            }
            if(after>0)journal.verifyAnchor(NAMESPACE,entries.removeFirst());
            if (!entries.isEmpty() || cursor.isEmpty() || !cursor.get().live()) journal.stage(NAMESPACE, domain, after, entries,atHead);
            return entries.size();
        } catch (NoSuchMethodException legacy) {
            if (committedMode) throw new IllegalStateException("Pinned resource history protocol is unavailable", legacy);
            return 0;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot read committed resource history", failure);
        }
    }

    private static Optional<UUID> domain(Object service) throws ReflectiveOperationException {
        Object value = call(service, "historyAuthorityDomainId");
        if (!(value instanceof Optional<?> identity)) throw new IllegalArgumentException("Missing resource history capability response");
        if (identity.isEmpty()) return Optional.empty();
        if (!(identity.get() instanceof UUID id)) throw new IllegalArgumentException("Invalid resource authority domain");
        return Optional.of(id);
    }
    static CommittedHistoryJournal.Entry project(Object raw, UUID domain, long expected) throws ReflectiveOperationException {
        if (!domain.equals(uuid(call(raw, "authorityDomainId")))) throw new IllegalArgumentException("Resource receipt authority mismatch");
        Object sequence = call(raw, "sequence");
        if (!(sequence instanceof Long position) || position != expected) throw new IllegalArgumentException("Resource receipt sequence gap");
        UUID operation = uuid(call(raw, "operationId"));
        Object actorValue = call(raw, "actorId");
        UUID actor = actorValue == null ? null : uuid(actorValue);
        Object time = call(raw, "committedAt");
        if (!(time instanceof Instant at)) throw new IllegalArgumentException("Missing resource commit time");
        Object rows = call(raw, "balances");
        if (!(rows instanceof List<?> balances) || balances.isEmpty() || balances.size() > 128)
            throw new IllegalArgumentException("Invalid resource balance count");
        List<ResourceHistoryReceipt.BalanceChange> changes = new ArrayList<>();
        for (Object b : balances) {
            Object nativeNation = call(b, "nationId");
            if (nativeNation == null) throw new IllegalArgumentException("Missing resource nation");
            String nation = nativeNation.toString();
            UUID nationId = UUID.fromString(nation);
            if (!nationId.toString().equals(nation)) throw new IllegalArgumentException("Invalid resource nation UUID");
            Object before = call(b, "before"), after = call(b, "after");
            if (!(before instanceof Long prior) || !(after instanceof Long next)) throw new IllegalArgumentException("Invalid native resource quantity");
            changes.add(new ResourceHistoryReceipt.BalanceChange(nationId, text(call(b, "resourceType")), prior, next));
        }
        Object kind = call(raw, "kind");
        if (!(kind instanceof Enum<?> value)) throw new IllegalArgumentException("Invalid resource change kind");
        var receipt = new ResourceHistoryReceipt(domain, position, operation, ResourceHistoryReceipt.Kind.valueOf(value.name()),
                text(call(raw, "reference")), text(call(raw, "category")), text(call(raw, "description")), actor, at, changes);
        return new CommittedHistoryJournal.Entry(position, ResourceHistoryProjection.event(receipt), null, receipt);
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
