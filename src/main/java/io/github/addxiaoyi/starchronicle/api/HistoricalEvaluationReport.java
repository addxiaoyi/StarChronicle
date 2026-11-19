package io.github.addxiaoyi.starchronicle.api;

/** Registration of one saved canonical source against one enabled, exact story version.
 * QUEUED is not execution: fresh server identity, conditions and permissions are checked
 * later by the normal durable inbox. No historical actors or source evidence are changed. */
public record HistoricalEvaluationReport(String sourceEventId,String storyId,String version,
                                         Status status,int newSeeds,long matchingInstances,
                                         long completedInstances,String message) {
    public enum Status { QUEUED, ALREADY_REGISTERED, NO_SOURCE_AUDIENCE, BLOCKED_CAUSAL }
}
