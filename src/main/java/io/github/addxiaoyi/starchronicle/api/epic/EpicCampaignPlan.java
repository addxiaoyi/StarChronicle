package io.github.addxiaoyi.starchronicle.api.epic;

import java.util.*;

/** Version-pinned plan. Changing a phase or native reference requires a new story version. */
public record EpicCampaignPlan(String campaignId, String storyId, String storyVersion,
                               String signupSource, String signupFact, String signupValue,
                               String reliefNationFact, List<EpicPhasePolicy> phases,
                               Map<String, Set<EpicRequirement>> routeRequirements) {
    /** Compatibility constructor for plans that have no native final-route operations. */
    public EpicCampaignPlan(String campaignId, String storyId, String storyVersion,
                            String signupSource, String signupFact, String signupValue,
                            String reliefNationFact, List<EpicPhasePolicy> phases) {
        this(campaignId, storyId, storyVersion, signupSource, signupFact, signupValue,
                reliefNationFact, phases, Map.of());
    }

    public EpicCampaignPlan {
        token(campaignId, "campaignId", 80); token(storyId, "storyId", 80); token(storyVersion, "storyVersion", 40);
        token(signupSource, "signupSource", 256); token(signupFact, "signupFact", 128); token(signupValue, "signupValue", 256);
        token(reliefNationFact, "reliefNationFact", 128);
        Objects.requireNonNull(phases, "phases");
        phases = List.copyOf(phases);
        if (phases.isEmpty() || phases.size() > 32) throw new IllegalArgumentException("Epic requires phases");
        Set<String> ids = new HashSet<>(); int finals = 0;
        for (EpicPhasePolicy p : phases) if (!ids.add(p.phaseId())) throw new IllegalArgumentException("Duplicate epic phase");
            else if (p.kind() == EpicPhasePolicy.Kind.FINAL_ROUTE) finals++;
        if (finals != 1) throw new IllegalArgumentException("Epic requires exactly one final route phase");
        if (phases.getLast().kind() != EpicPhasePolicy.Kind.FINAL_ROUTE)
            throw new IllegalArgumentException("Final route phase must follow every preparation phase");
        // Older persisted plans predate routeRequirements.
        if (routeRequirements == null) routeRequirements = Map.of();
        Map<String, Set<EpicRequirement>> routes = new TreeMap<>();
        for (var entry : routeRequirements.entrySet()) {
            if (!Set.of("WAR", "DUNGEON").contains(entry.getKey()))
                throw new IllegalArgumentException("Unsupported epic final route: " + entry.getKey());
            Objects.requireNonNull(entry.getValue(), "route requirements");
            if (entry.getValue().isEmpty()) throw new IllegalArgumentException("Final route needs native requirements: " + entry.getKey());
            routes.put(entry.getKey(), Collections.unmodifiableSet(new LinkedHashSet<>(entry.getValue())));
        }
        routeRequirements = Collections.unmodifiableMap(routes);
    }

    public EpicPhasePolicy phase(String id) {
        return phases.stream().filter(p -> p.phaseId().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown epic phase: " + id));
    }
    public EpicPhasePolicy finalRoute() { return phases.stream().filter(p -> p.kind() == EpicPhasePolicy.Kind.FINAL_ROUTE).findFirst().orElseThrow(); }
    public Set<EpicRequirement> requirementsForRoute(String route) {
        return routeRequirements.getOrDefault(route, Set.of());
    }

    private static void token(String value, String name, int max) {
        if (value == null || value.isBlank() || value.length() > max || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid epic " + name);
    }
}
