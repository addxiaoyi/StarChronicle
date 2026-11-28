package io.github.addxiaoyi.starchronicle.integration;

import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import io.github.addxiaoyi.starchronicle.history.CommittedHistoryJournal;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import static io.github.addxiaoyi.starchronicle.integration.StarCoreBridge.call;

/** Reads native designated capitals in commit order. City levels, cached locations and current
 * founders never substitute for the original designation. Source failures retain the cursor. */
public final class StarCoreCapitalHistory {
    public static final String NAMESPACE = "starcore.capitals";
    private final Supplier<Object> source;
    private final CommittedHistoryJournal journal;
    private volatile boolean committedMode;

    public StarCoreCapitalHistory(StarCoreBridge bridge, CommittedHistoryJournal journal) {
        this(() -> bridge.service("module.nation.NationService"), journal);
    }
    StarCoreCapitalHistory(Supplier<Object> source, CommittedHistoryJournal journal) {
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
            if (committedMode) throw new IllegalStateException("Pinned capital history is unavailable");
            return 0;
        }
        try {
            // The native monitor also owns state mutations. Keep the domain and page from one
            // source read, but release it before touching Chronicle's persistence transaction.
            UUID domain;
            synchronized (service) {
                Optional<UUID> identity = domain(service);
                if (identity.isEmpty()) {
                    if (committedMode) throw new IllegalStateException("Pinned capital history capability disappeared");
                    return 0;
                }
                domain = identity.get();
            }
            committedMode = true;
            var cursor = journal.cursor(NAMESPACE);
            if (cursor.isPresent() && !cursor.get().authorityDomain().equals(domain))
                throw new IllegalStateException("Capital history authority changed; restore matching backups");
            if (journal.hasPending(NAMESPACE)) return 0;
            long after = cursor.map(CommittedHistoryJournal.Cursor::sequence).orElse(0L);
            List<CommittedHistoryJournal.Entry> entries = new ArrayList<>();
            boolean atHead;
            synchronized (service) {
                if (!domain(service).equals(Optional.of(domain))) throw new IllegalStateException("Capital history authority changed during read");
                long readAfter=after==0?0:after-1;
                Object result = call(service, "capitalChangesAfter", readAfter, CommittedHistoryJournal.PAGE_SIZE);
                if (!(result instanceof List<?> page) || page.size() > CommittedHistoryJournal.PAGE_SIZE)
                    throw new IllegalArgumentException("Invalid capital history page");
                atHead=page.size()<CommittedHistoryJournal.PAGE_SIZE;
                if(after>0&&page.isEmpty())throw new IllegalStateException("Capital source lost its acknowledged receipt");
                long expected = readAfter;
                for (Object receipt : page) {
                    expected = Math.addExact(expected, 1);
                    entries.add(project(receipt, domain, expected));
                }
                if (!domain(service).equals(Optional.of(domain))) throw new IllegalStateException("Capital history authority changed during read");
            }
            if(after>0)journal.verifyAnchor(NAMESPACE,entries.removeFirst());
            if (!entries.isEmpty() || cursor.isEmpty() || !cursor.get().live()) journal.stage(NAMESPACE, domain, after, entries,atHead);
            return entries.size();
        } catch (NoSuchMethodException legacy) {
            if (committedMode) throw new IllegalStateException("Pinned capital history protocol is unavailable", legacy);
            return 0;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot read committed capital history", failure);
        }
    }

    private static Optional<UUID> domain(Object service) throws ReflectiveOperationException {
        Object value = call(service, "capitalHistoryAuthorityDomainId");
        if (!(value instanceof Optional<?> identity)) throw new IllegalArgumentException("Missing capital history capability response");
        if (identity.isEmpty()) return Optional.empty();
        if (!(identity.get() instanceof UUID id)) throw new IllegalArgumentException("Invalid capital authority domain");
        return Optional.of(id);
    }
    static CommittedHistoryJournal.Entry project(Object receipt, UUID domain, long expected) throws ReflectiveOperationException {
        if (!domain.equals(uuid(call(receipt, "authorityDomainId")))) throw new IllegalArgumentException("Capital receipt authority mismatch");
        Object raw = call(receipt, "designation");
        Object sequence = call(raw, "sequence");
        if (!(sequence instanceof Long position) || position != expected) throw new IllegalArgumentException("Capital receipt sequence gap");
        UUID operation = uuid(call(raw, "operationId"));
        Object nativeNation = call(raw, "nationId");
        if (nativeNation == null) throw new IllegalArgumentException("Missing capital nation");
        String nation = nativeNation.toString();
        if (!UUID.fromString(nation).toString().equals(nation)) throw new IllegalArgumentException("Invalid capital nation UUID");
        Object previous = call(raw, "previousCityId");
        UUID previousCity = previous == null ? null : uuid(previous);
        UUID city = uuid(call(raw, "cityId")), actor = uuid(call(raw, "actorId"));
        if (city.equals(previousCity)) throw new IllegalArgumentException("Capital designation did not change");
        Object time = call(raw, "committedAt");
        if (!(time instanceof Instant committedAt)) throw new IllegalArgumentException("Missing capital commit time");
        Map<String,String> facts = new TreeMap<>();
        facts.put("title", previousCity == null ? "首都指定（原生提交）" : "首都迁移（原生提交）");
        facts.put("capital.city", city.toString()); facts.put("city.id", city.toString());
        if (previousCity != null) facts.put("capital.previous", previousCity.toString());
        facts.put("capital.change", previousCity == null ? "DESIGNATED" : "MOVED");
        facts.put("actor.id", actor.toString()); facts.put("nation.id", nation); facts.put("time_basis", "COMMITTED");
        facts.put("history.namespace", NAMESPACE); facts.put("history.authority", domain.toString());
        facts.put("history.sequence", Long.toString(position)); facts.put("history.operation", operation.toString());
        WorldEvent event = new WorldEvent("starcore:capital:" + domain + ":" + operation,
                previousCity == null ? "CAPITAL_DESIGNATED" : "CAPITAL_MOVED", committedAt,
                Set.of(actor), Set.of(nation), facts, "StarCore",
                "NationService.capitalChangesAfter; authority=" + domain + "; sequence=" + position
                + "; operation=" + operation + "; original actor and cities from the committed designation; legacy records retain millisecond precision");
        return new CommittedHistoryJournal.Entry(position, event);
    }
    private static UUID uuid(Object value) {
        if (!(value instanceof UUID id)) throw new IllegalArgumentException("Missing native capital UUID");
        return id;
    }
}
