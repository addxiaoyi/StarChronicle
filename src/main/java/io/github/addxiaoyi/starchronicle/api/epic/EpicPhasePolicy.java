package io.github.addxiaoyi.starchronicle.api.epic;

import java.util.*;

/** Immutable preparation requirements or the final route phase. */
public record EpicPhasePolicy(String phaseId, Kind kind, Set<EpicRequirement> requirements) {
    public enum Kind { PREPARATION, FINAL_ROUTE }

    public EpicPhasePolicy {
        token(phaseId, "phaseId", 80);
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(requirements, "requirements");
        TreeSet<EpicRequirement> sorted = new TreeSet<>(Comparator
                .comparing((EpicRequirement r) -> r.type().name())
                .thenComparing(EpicRequirement::target)
                .thenComparing(EpicRequirement::nativeReference));
        sorted.addAll(requirements);
        if (sorted.size() != requirements.size()) throw new IllegalArgumentException("Duplicate epic requirement");
        if (sorted.size() > 128) throw new IllegalArgumentException("Too many epic requirements");
        if (kind == Kind.PREPARATION && sorted.isEmpty()) throw new IllegalArgumentException("Preparation phase needs requirements");
        if (kind == Kind.FINAL_ROUTE && !sorted.isEmpty()) throw new IllegalArgumentException("Final route cannot have preparation requirements");
        requirements = Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
    }

    private static void token(String value, String name, int max) {
        if (value == null || value.isBlank() || value.length() > max || !value.matches("[A-Za-z0-9_.-]+"))
            throw new IllegalArgumentException("Invalid epic " + name);
    }
}
