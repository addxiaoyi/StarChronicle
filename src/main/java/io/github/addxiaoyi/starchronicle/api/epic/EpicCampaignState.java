package io.github.addxiaoyi.starchronicle.api.epic;

import java.time.Instant;
import java.util.*;

/** Read model for one sealed campaign instance. */
public record EpicCampaignState(String instanceId, String sessionId, EpicCampaignPlan plan,
                                String reliefNationId, Status status, String phaseId,
                                Map<String, EpicOperationState> operations,
                                Map<String, RouteVote> routeVotes, String route,
                                long revision, Instant updatedAt) {
    public enum Status { PREPARING, ROUTE_VOTING, ROUTE_APPROVED, COMPLETE, CANCELLED }
    public record RouteVote(String nationId, String choice, long revision, Instant castAt) {}
    public EpicCampaignState {
        Objects.requireNonNull(plan); Objects.requireNonNull(status); Objects.requireNonNull(phaseId); Objects.requireNonNull(updatedAt);
        operations = Collections.unmodifiableMap(new TreeMap<>(operations == null ? Map.of() : operations));
        routeVotes = Collections.unmodifiableMap(new TreeMap<>(routeVotes == null ? Map.of() : routeVotes));
        if (revision < 1) throw new IllegalArgumentException("Campaign revision must be positive");
    }
    public boolean routeReady() { return status == Status.ROUTE_VOTING || status == Status.ROUTE_APPROVED; }
}
