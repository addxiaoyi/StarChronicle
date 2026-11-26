package io.github.addxiaoyi.starchronicle.api.processing;

import java.time.Instant;
import java.util.*;

/** Exact frozen native factory run, including all charged energy and the original actor. */
public record ProcessingHistoryReceipt(UUID authorityDomain, long sequence, Kind kind, Run run, Instant committedAt) {
    public enum Kind { STARTED, COMPLETED, AUTOMATIC }
    public record Run(UUID runId, UUID factoryId, UUID nationId, UUID actorId, String recipeId, int batches,
                      Map<String,Long> inputs, Map<String,Long> outputs, Instant startedAt, Instant readyAt, Instant completedAt) {
        public Run {
            Objects.requireNonNull(runId); Objects.requireNonNull(nationId);
            Objects.requireNonNull(startedAt); Objects.requireNonNull(readyAt);
            if (recipeId == null || !recipeId.matches("[a-zA-Z0-9_.:-]{1,128}") || batches < 1)
                throw new IllegalArgumentException("Invalid processing request");
            inputs = resources(inputs); outputs = resources(outputs);
            if (readyAt.isBefore(startedAt) || completedAt != null && completedAt.isBefore(readyAt)
                    || factoryId == null && (actorId != null || !startedAt.equals(readyAt)))
                throw new IllegalArgumentException("Invalid processing timeline or actor");
        }
        private static Map<String,Long> resources(Map<String,Long> values) {
            Map<String,Long> result = Collections.unmodifiableMap(new TreeMap<>(values));
            if (result.isEmpty() || result.size() > 32) throw new IllegalArgumentException("Invalid processing resource count");
            result.forEach((key, value) -> {
                if (key == null || !key.matches("[a-z][a-z0-9_]{0,63}") || value == null || value <= 0)
                    throw new IllegalArgumentException("Invalid processing resource");
            });
            return result;
        }
        public Run started() { return new Run(runId, factoryId, nationId, actorId, recipeId, batches, inputs, outputs, startedAt, readyAt, null); }
    }
    public ProcessingHistoryReceipt {
        Objects.requireNonNull(authorityDomain); Objects.requireNonNull(kind); Objects.requireNonNull(run); Objects.requireNonNull(committedAt);
        if (sequence < 1 || (kind == Kind.STARTED) != (run.completedAt() == null)
                || (kind == Kind.AUTOMATIC) != (run.factoryId() == null)
                || !committedAt.equals(kind == Kind.STARTED ? run.startedAt() : run.completedAt()))
            throw new IllegalArgumentException("Invalid processing commit");
    }
}
