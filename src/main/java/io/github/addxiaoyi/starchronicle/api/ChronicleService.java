package io.github.addxiaoyi.starchronicle.api;

import io.github.addxiaoyi.starchronicle.core.ProcessReport;
import io.github.addxiaoyi.starchronicle.core.StoryInstance;
import io.github.addxiaoyi.starchronicle.core.NationalAidService;
import io.github.addxiaoyi.starchronicle.api.aid.AidRequest;
import io.github.addxiaoyi.starchronicle.api.epic.EpicContract;
import io.github.addxiaoyi.starchronicle.api.epic.EpicCampaignPlan;
import io.github.addxiaoyi.starchronicle.api.epic.EpicCampaignState;
import io.github.addxiaoyi.starchronicle.api.epic.EpicOperationState;
import io.github.addxiaoyi.starchronicle.api.epic.EpicSignup;
import io.github.addxiaoyi.starchronicle.api.epic.EpicOperation;
import io.github.addxiaoyi.starchronicle.api.epic.EpicReceipt;
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
    default CompletionStage<List<EpicCampaignPlan>> epicCampaignPlans(){return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException("Epic campaigns unavailable"));}
    default CompletionStage<EpicSignup> openEpicSignup(String planId,String sessionId,String reliefNationId,UUID actorId){return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException("Epic signup unavailable"));}
    default CompletionStage<EpicSignup> epicSignup(String sessionId){return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException("Epic signup unavailable"));}
    default CompletionStage<EpicSignup> enrollEpicSignup(String sessionId,UUID actorId,long expectedRevision){return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException("Epic signup unavailable"));}
    default CompletionStage<EpicSignup> withdrawEpicSignup(String sessionId,UUID actorId,long expectedSeatRevision,long expectedSignupRevision){return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException("Epic signup unavailable"));}
    default CompletionStage<EpicCampaignState> sealEpicSignup(String sessionId,UUID actorId,long expectedRevision){return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException("Epic signup unavailable"));}
    default CompletionStage<EpicCampaignState> epicCampaign(String instanceId,UUID actorId){return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException("Epic campaign unavailable"));}
    default CompletionStage<EpicCampaignState> voteEpicRoute(String instanceId,UUID actorId,long expectedRevision,String route){return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException("Epic route voting unavailable"));}
    default CompletionStage<EpicOperationState> prepareEpicOperation(String instanceId,UUID actorId,io.github.addxiaoyi.starchronicle.api.epic.EpicOperation.Type type,String target,String nativeReference,String providerId){return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException("Epic operations unavailable"));}
    default CompletionStage<EpicOperationState> epicOperation(String operationId,UUID actorId){return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException("Epic operations unavailable"));}
    default CompletionStage<EpicOperationState> applyEpicOperation(String operationId,UUID actorId,long expectedRevision){return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException("Epic operations unavailable"));}
    default CompletionStage<EpicOperationState> reconcileEpicOperation(String operationId,UUID actorId,long expectedRevision){return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException("Epic operations unavailable"));}
    default CompletionStage<List<String>> epicProviders(){return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException("Epic providers unavailable"));}
    /** DTO consistency only: does not query native authority, commit a campaign, or authorize
     * canonical events. Use prepare/apply/reconcile for provider-backed evidence. */
    default CompletionStage<EpicReceipt> verifyEpicReceipt(EpicOperation operation, EpicReceipt receipt,
                                                            String providerId, String authorityDomain) {
        try {
            return java.util.concurrent.CompletableFuture.completedFuture(EpicContract.requireCommitted(
                    operation, new io.github.addxiaoyi.starchronicle.api.epic.EpicObservation(
                            io.github.addxiaoyi.starchronicle.api.epic.EpicObservation.Status.COMMITTED,
                            receipt, "caller supplied receipt"), providerId, authorityDomain));
        } catch (RuntimeException error) {
            return java.util.concurrent.CompletableFuture.failedFuture(error);
        }
    }
    /** Submit one national treasury aid intent through the configured native provider. */
    default CompletionStage<NationalAidService.Result> submitAid(AidRequest request){return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException("National aid unavailable"));}
    /** Explicit read-only reconciliation; an UNKNOWN aid is never resubmitted automatically. */
    default CompletionStage<NationalAidService.Result> reconcileAid(AidRequest request){return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException("National aid unavailable"));}
    /** Explicitly register one saved canonical source for one currently enabled version.
     * Trusted plugins only; commands require starchronicle.admin. This queues work and
     * never accepts caller-supplied identity, definitions, seeds or replacement evidence. */
    default CompletionStage<HistoricalEvaluationReport> evaluateHistorical(String sourceEventId,String storyId,String expectedVersion){return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException("Historical evaluation unavailable"));}
    CompletionStage<ProcessReport> choose(String instanceId, UUID playerId, String choiceId);
    CompletionStage<ProcessReport> choose(String instanceId, UUID playerId, String expectedNode, String choiceId);
    /** Current identity is collected by Chronicle. Tokens come from nationalState, never
     * caller-supplied nationality/rank. A stale node or country revision cannot overwrite a vote. */
    default CompletionStage<ProcessReport> nationalVote(String instanceId,UUID playerId,String expectedNode,long expectedRevision,String choiceId){return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException("National ballots unavailable"));}
    default CompletionStage<ProcessReport> withdrawNationalVote(String instanceId,UUID playerId,String expectedNode,long expectedRevision){return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException("National ballots unavailable"));}
    default CompletionStage<NationalVoteState> nationalState(String instanceId,UUID playerId){return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException("National ballots unavailable"));}
    /** Read-only task gate summaries for the current pinned node. The service filters private
     * and country visibility using freshly collected identity; this grants no completion or choice authority. */
    default CompletionStage<io.github.addxiaoyi.starchronicle.api.quest.QuestGateState> questGateState(String instanceId,UUID playerId){return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException("Quest gates unavailable"));}
    CompletionStage<ProcessReport> join(String instanceId, UUID playerId);
    CompletionStage<List<StoryInstance>> instances(UUID playerId);
    CompletionStage<List<StoryInstance>> activeInstances();
    CompletionStage<List<StoryInstance>> availableInstances(UUID playerId);
    CompletionStage<Map<String,StoryDefinition.Choice>> availableChoices(String instanceId, UUID playerId);
    CompletionStage<List<WorldEvent>> history(String nationId, UUID playerId, int page, int size);
    /** Public read-only evidenced memory registrations, filtered by an optional exact logical region. */
    default CompletionStage<List<io.github.addxiaoyi.starchronicle.memory.MemoryEntry>> memoryEntries(String regionId,int page,int size){return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException("Automatic memory recipes unavailable"));}
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
