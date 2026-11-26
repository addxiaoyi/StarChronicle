package io.github.addxiaoyi.starchronicle.api.resource;

import java.time.Instant;
import java.util.*;

/** Exact native stockpile evidence. A balance increase alone does not establish production. */
public record ResourceHistoryReceipt(UUID authorityDomain, long sequence, UUID operationId,
                                     Kind kind, String reference, String category, String description,
                                     UUID actorId, Instant committedAt, List<BalanceChange> balances) {
    public enum Kind { ADJUSTMENT, GRANT, CONSUME, TRANSACTION_GRANT, TRANSACTION_CONSUME, TRANSFER }
    public record BalanceChange(UUID nationId, String resourceType, long before, long after) {
        public BalanceChange {
            Objects.requireNonNull(nationId);
            if (resourceType == null || !resourceType.matches("[a-z][a-z0-9_]{0,63}")
                    || before < 0 || after < 0 || before == after)
                throw new IllegalArgumentException("Invalid resource balance change");
        }
        public long delta() { return after - before; }
    }
    public ResourceHistoryReceipt {
        Objects.requireNonNull(authorityDomain); Objects.requireNonNull(operationId);
        Objects.requireNonNull(kind); Objects.requireNonNull(committedAt);
        if (sequence < 1 || reference == null || reference.length() > 4096
                || category == null || category.length() > 64 || description == null || description.length() > 200)
            throw new IllegalArgumentException("Invalid resource receipt identity or cause");
        balances = List.copyOf(balances);
        if (balances.isEmpty() || balances.size() > 128) throw new IllegalArgumentException("Invalid resource balance count");
        Set<String> keys = new HashSet<>();
        for (var b : balances) {
            if (!keys.add(b.nationId() + ":" + b.resourceType())) throw new IllegalArgumentException("Repeated resource balance");
            if ((kind == Kind.GRANT || kind == Kind.TRANSACTION_GRANT) && b.delta() <= 0
                    || (kind == Kind.CONSUME || kind == Kind.TRANSACTION_CONSUME) && b.delta() >= 0)
                throw new IllegalArgumentException("Resource cause disagrees with balance direction");
        }
        if (kind == Kind.TRANSFER) {
            Map<String,java.math.BigInteger> net = new HashMap<>();
            for (var b : balances) net.merge(b.resourceType(), java.math.BigInteger.valueOf(b.delta()), java.math.BigInteger::add);
            if (net.values().stream().anyMatch(v -> v.signum() != 0)) throw new IllegalArgumentException("Resource transfer is not conserved");
        }
    }
}
