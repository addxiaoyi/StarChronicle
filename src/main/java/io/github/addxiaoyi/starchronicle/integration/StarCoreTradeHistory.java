package io.github.addxiaoyi.starchronicle.integration;

import io.github.addxiaoyi.starchronicle.api.trade.TradeHistoryReceipt;
import io.github.addxiaoyi.starchronicle.history.CommittedHistoryJournal;
import io.github.addxiaoyi.starchronicle.history.TradeHistoryProjection;
import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import static io.github.addxiaoyi.starchronicle.integration.StarCoreBridge.call;

/** Continuous SQL commit order, including late saves and refunds; never an execution-time cursor. */
public final class StarCoreTradeHistory {
    public static final String NAMESPACE = TradeHistoryProjection.NAMESPACE;
    private final Supplier<Object> source;
    private final CommittedHistoryJournal journal;
    private boolean committedMode;

    public StarCoreTradeHistory(StarCoreBridge bridge, CommittedHistoryJournal journal) {
        this(() -> bridge.service("module.resource.TradeHistoryService"), journal);
    }
    StarCoreTradeHistory(Supplier<Object> source, CommittedHistoryJournal journal) {
        this.source = Objects.requireNonNull(source); this.journal = Objects.requireNonNull(journal);
        committedMode = journal.cursor(NAMESPACE).isPresent();
    }

    public int scan() {
        Object service = source.get();
        if (service == null) {
            if (committedMode) throw new IllegalStateException("Pinned trade history is unavailable");
            return 0;
        }
        try {
            UUID domain = uuid(call(service, "historyAuthorityDomainId"));
            committedMode = true;
            var cursor = journal.cursor(NAMESPACE);
            if (cursor.isPresent() && !cursor.get().authorityDomain().equals(domain))
                throw new IllegalStateException("Trade history authority changed; restore matching backups");
            if (journal.hasPending(NAMESPACE)) return 0;
            long after = cursor.map(CommittedHistoryJournal.Cursor::sequence).orElse(0L);
            Object value = call(service, "tradeChangesAfter", after, CommittedHistoryJournal.PAGE_SIZE);
            if (!(value instanceof List<?> page) || page.size() > CommittedHistoryJournal.PAGE_SIZE)
                throw new IllegalArgumentException("Invalid trade history page");
            List<CommittedHistoryJournal.Entry> entries = new ArrayList<>();
            long expected = after;
            for (Object receipt : page) entries.add(project(receipt, domain, expected = Math.addExact(expected, 1)));
            if (!uuid(call(service, "historyAuthorityDomainId")).equals(domain))
                throw new IllegalStateException("Trade authority changed during source read");
            if (!entries.isEmpty() || cursor.isEmpty() || !cursor.get().live()) journal.stage(NAMESPACE, domain, after, entries);
            return entries.size();
        } catch (NoSuchMethodException legacy) {
            if (!committedMode) return 0;
            throw new IllegalStateException("Pinned trade history protocol is unavailable", legacy);
        } catch (InvocationTargetException failure) {
            if (!committedMode && failure.getCause() instanceof UnsupportedOperationException) return 0;
            throw new IllegalStateException("Cannot read committed trade history", failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot read committed trade history", failure);
        }
    }

    static CommittedHistoryJournal.Entry project(Object source, UUID domain, long expected) throws ReflectiveOperationException {
        if (!domain.equals(uuid(call(source, "authorityDomainId")))) throw new IllegalArgumentException("Trade receipt authority mismatch");
        Object sequence = call(source, "sequence");
        if (!(sequence instanceof Long position) || position != expected) throw new IllegalArgumentException("Trade receipt sequence gap");
        Object raw = call(source, "record");
        var trade = new TradeHistoryReceipt.Trade(uuid(call(raw, "recordId")), text(call(raw, "orderType")), text(call(raw, "orderSource")),
                optionalUuid(call(raw, "sellerPlayerId")), optionalUuid(call(raw, "buyerPlayerId")),
                optionalNation(call(raw, "sellerNationId")), optionalNation(call(raw, "buyerNationId")),
                text(call(raw, "resourceId")), number(call(raw, "amount"), Long.class),
                number(call(raw, "pricePerUnit"), Double.class), number(call(raw, "totalValue"), Double.class),
                number(call(raw, "taxAmount"), Double.class), number(call(raw, "netValue"), Double.class),
                instant(call(raw, "executedAt")), optionalUuid(call(raw, "buyOrderId")), optionalUuid(call(raw, "sellOrderId")),
                TradeHistoryReceipt.Status.valueOf(text(call(raw, "status"))), optionalText(call(raw, "notes")));
        Object previous = call(source, "previousStatus");
        var receipt = new TradeHistoryReceipt(domain, position, instant(call(source, "committedAt")), instant(call(source, "occurredAt")),
                previous == null ? null : TradeHistoryReceipt.Status.valueOf(text(previous)), trade);
        return new CommittedHistoryJournal.Entry(position, TradeHistoryProjection.event(receipt), receipt);
    }

    private static UUID uuid(Object value) {
        if (!(value instanceof UUID id)) throw new IllegalArgumentException("Missing native UUID");
        return id;
    }
    private static Optional<?> optional(Object value) {
        if (!(value instanceof Optional<?> result)) throw new IllegalArgumentException("Missing native optional");
        return result;
    }
    private static UUID optionalUuid(Object value) { return optional(value).map(StarCoreTradeHistory::uuid).orElse(null); }
    private static UUID optionalNation(Object value) {
        return optional(value).map(n -> {
            String raw = n.toString(); UUID id = UUID.fromString(raw);
            if (!id.toString().equals(raw)) throw new IllegalArgumentException("Invalid native nation UUID");
            return id;
        }).orElse(null);
    }
    private static String optionalText(Object value) { return optional(value).map(StarCoreTradeHistory::text).orElse(null); }
    private static String text(Object value) {
        if (value == null) throw new IllegalArgumentException("Missing native text");
        return value.toString();
    }
    private static Instant instant(Object value) {
        if (!(value instanceof Instant time)) throw new IllegalArgumentException("Missing native timestamp");
        return time;
    }
    private static <T extends Number> T number(Object value, Class<T> type) {
        if (!type.isInstance(value)) throw new IllegalArgumentException("Invalid native numeric type");
        return type.cast(value);
    }
}
