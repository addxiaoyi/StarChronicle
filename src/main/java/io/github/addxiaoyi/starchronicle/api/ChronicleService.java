package io.github.addxiaoyi.starchronicle.api;

import io.github.addxiaoyi.starchronicle.core.ProcessReport;
import io.github.addxiaoyi.starchronicle.core.StoryInstance;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition;
import io.github.addxiaoyi.starchronicle.npc.NpcView;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** Trusted server-plugin API. Source events must represent committed, evidenced domain actions.
 * Player snapshots are collected by StarChronicle, never accepted from commands or text providers. */
public interface ChronicleService {
    CompletionStage<ProcessReport> submit(WorldEvent event);
    /** Explicitly register one saved canonical source for one currently enabled version.
     * Trusted plugins only; commands require starchronicle.admin. This queues work and
     * never accepts caller-supplied identity, definitions, seeds or replacement evidence. */
    default CompletionStage<HistoricalEvaluationReport> evaluateHistorical(String sourceEventId,String storyId,String expectedVersion){return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException("Historical evaluation unavailable"));}
    CompletionStage<ProcessReport> choose(String instanceId, UUID playerId, String choiceId);
    CompletionStage<ProcessReport> choose(String instanceId, UUID playerId, String expectedNode, String choiceId);
    CompletionStage<ProcessReport> join(String instanceId, UUID playerId);
    CompletionStage<List<StoryInstance>> instances(UUID playerId);
    CompletionStage<List<StoryInstance>> activeInstances();
    CompletionStage<List<StoryInstance>> availableInstances(UUID playerId);
    CompletionStage<Map<String,StoryDefinition.Choice>> availableChoices(String instanceId, UUID playerId);
    CompletionStage<List<WorldEvent>> history(String nationId, UUID playerId, int page, int size);
    /** At most 256 explicit past.* or history references. Identity is freshly collected by
     * Chronicle; unknown subjects and choices are absent rather than fabricated. */
    default CompletionStage<Map<String,String>> recollections(UUID playerId,java.util.Collection<String> references){return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException("Historical fact projection unavailable"));}
    /** Catalog-bound read-only dialogue; current player and historical context are collected by the server. */
    default CompletionStage<NpcView> npc(UUID playerId,String npcId){return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException("NPC module unavailable"));}
    /** Persisted honor, historical titles, and NPC relationships; this is not a mutation API. */
    default CompletionStage<Map<String,String>> fate(UUID playerId){return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException("Fate module unavailable"));}
    /** Optional text provider receives only this explicitly supplied public evidence projection. */
    default CompletionStage<String> describe(String template,Map<String,String> publicFacts){return TextProvider.templates().describe(template,publicFacts);}
    default CompletionStage<java.util.Optional<NativeScene>> scene(UUID playerId){return java.util.concurrent.CompletableFuture.completedFuture(java.util.Optional.empty());}
    /** choice 0 advances a node without choices; positive indices are the native 1-based visible list. */
    default CompletionStage<java.util.Optional<NativeScene>> sceneAction(UUID playerId,String expectedScript,String expectedNode,int choice){return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException("Native scene unavailable"));}
}
