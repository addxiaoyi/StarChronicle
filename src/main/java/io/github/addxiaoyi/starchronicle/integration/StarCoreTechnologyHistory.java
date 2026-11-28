package io.github.addxiaoyi.starchronicle.integration;

import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import io.github.addxiaoyi.starchronicle.history.CommittedHistoryJournal;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import static io.github.addxiaoyi.starchronicle.integration.StarCoreBridge.call;

/** Reads the native state/outbox protocol; it never infers a researcher or consults current
 * unlocks/definitions to rewrite an old receipt. A source failure leaves the cursor untouched. */
public final class StarCoreTechnologyHistory {
    public static final String NAMESPACE = "starcore.technology";
    private final Supplier<Object> source;
    private final CommittedHistoryJournal journal;
    private volatile boolean committedMode;

    public StarCoreTechnologyHistory(StarCoreBridge bridge, CommittedHistoryJournal journal) {
        this(() -> bridge.service("module.technology.TechnologyService"), journal);
    }
    StarCoreTechnologyHistory(Supplier<Object> source, CommittedHistoryJournal journal) {
        this.source = Objects.requireNonNull(source); this.journal = Objects.requireNonNull(journal);
        committedMode = journal.cursor(NAMESPACE).isPresent();
    }

    /** Once seen or pinned, an unavailable/broken durable source must not produce fallback
     * observation events or a second ResearchCompleteEvent for the same native change. */
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
            if (committedMode) throw new IllegalStateException("Pinned technology history is unavailable");
            return 0;
        }
        try {
            // The native monitor also owns state mutations. Keep the domain and page from one
            // source read, but release it before touching Chronicle's persistence transaction.
            UUID domain;
            synchronized (service) {
                Optional<UUID> identity = domain(service);
                if (identity.isEmpty()) {
                    if (committedMode) throw new IllegalStateException("Pinned technology history capability disappeared");
                    return 0;
                }
                domain = identity.get();
            }
            committedMode = true;
            var cursor = journal.cursor(NAMESPACE);
            if (cursor.isPresent() && !cursor.get().authorityDomain().equals(domain))
                throw new IllegalStateException("Technology history authority changed; restore matching backups");
            if (journal.hasPending(NAMESPACE)) return 0;
            long after = cursor.map(CommittedHistoryJournal.Cursor::sequence).orElse(0L);
            List<CommittedHistoryJournal.Entry> entries = new ArrayList<>();
            boolean atHead;
            synchronized (service) {
                if (!domain(service).equals(Optional.of(domain))) throw new IllegalStateException("Technology history authority changed during read");
                long readAfter=after==0?0:after-1;
                Object result = call(service, "technologyChangesAfter", readAfter, CommittedHistoryJournal.PAGE_SIZE);
                if (!(result instanceof List<?> page) || page.size() > CommittedHistoryJournal.PAGE_SIZE)
                    throw new IllegalArgumentException("Invalid technology history page");
                atHead=page.size()<CommittedHistoryJournal.PAGE_SIZE;
                if(after>0&&page.isEmpty())throw new IllegalStateException("Technology source lost its acknowledged receipt");
                long expected = readAfter;
                for (Object receipt : page) {
                    expected = Math.addExact(expected, 1);
                    entries.add(project(receipt, domain, expected));
                }
                if (!domain(service).equals(Optional.of(domain))) throw new IllegalStateException("Technology history authority changed during read");
            }
            if(after>0)journal.verifyAnchor(NAMESPACE,entries.removeFirst());
            if (!entries.isEmpty() || cursor.isEmpty() || !cursor.get().live()) journal.stage(NAMESPACE, domain, after, entries,atHead);
            return entries.size();
        } catch (NoSuchMethodException legacy) {
            if (committedMode) throw new IllegalStateException("Pinned technology history protocol is unavailable", legacy);
            return 0;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot read committed technology history", failure);
        }
    }

    private static Optional<UUID> domain(Object service) throws ReflectiveOperationException {
        Object value = call(service, "historyAuthorityDomainId");
        if (!(value instanceof Optional<?> identity)) throw new IllegalArgumentException("Missing technology history capability response");
        if (identity.isEmpty()) return Optional.empty();
        if (!(identity.get() instanceof UUID id)) throw new IllegalArgumentException("Invalid technology authority domain");
        return Optional.of(id);
    }
    static CommittedHistoryJournal.Entry project(Object receipt, UUID domain, long expected) throws ReflectiveOperationException {
        if (!domain.equals(call(receipt, "authorityDomainId"))) throw new IllegalArgumentException("Technology receipt authority mismatch");
        Object sequence = call(receipt, "sequence");
        if (!(sequence instanceof Long position) || position != expected) throw new IllegalArgumentException("Technology receipt sequence gap");
        Object operation = call(receipt, "operationId");
        if (!(operation instanceof UUID id)) throw new IllegalArgumentException("Missing technology operation UUID");
        String nation = text(call(receipt, "nationId"), false);
        if (!UUID.fromString(nation).toString().equals(nation)) throw new IllegalArgumentException("Invalid technology nation UUID");
        String key = text(call(receipt, "technologyKey"), false), era = text(call(receipt, "era"), true);
        String change = text(call(receipt, "change"), false);
        if (!Set.of("UNLOCKED", "REVOKED").contains(change)) throw new IllegalArgumentException("Unknown technology change");
        Object time = call(receipt, "committedAt");
        if (!(time instanceof Instant committedAt)) throw new IllegalArgumentException("Missing technology commit time");
        boolean unlocked = change.equals("UNLOCKED");
        Map<String,String> facts = new TreeMap<>();
        facts.put("title", unlocked ? "科技解锁（原生提交）" : "科技撤销（原生提交）");
        facts.put("technology", key); facts.put("tech.era", era); facts.put("technology.change", change);
        facts.put("nation.id", nation); facts.put("time_basis", "COMMITTED");
        facts.put("history.namespace", NAMESPACE); facts.put("history.authority", domain.toString());
        facts.put("history.sequence", Long.toString(position)); facts.put("history.operation", id.toString());
        WorldEvent event = new WorldEvent("starcore:technology:" + domain + ":" + id,
                unlocked ? "TECHNOLOGY_BREAKTHROUGH" : "TECHNOLOGY_REVOKED", committedAt, Set.of(), Set.of(nation), facts,
                "StarCore", "TechnologyService.technologyChangesAfter; authority=" + domain + "; sequence=" + position
                + "; operation=" + id + "; native state and history outbox committed together; researcher not exposed; history starts at protocol installation");
        return new CommittedHistoryJournal.Entry(position, event);
    }
    private static String text(Object value, boolean empty) {
        if (value == null || (!empty && value.toString().isBlank()) || value.toString().length() > 256)
            throw new IllegalArgumentException("Invalid technology receipt field");
        return value.toString();
    }
}
