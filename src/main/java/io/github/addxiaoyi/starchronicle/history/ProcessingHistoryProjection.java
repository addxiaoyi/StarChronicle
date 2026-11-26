package io.github.addxiaoyi.starchronicle.history;

import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import io.github.addxiaoyi.starchronicle.api.processing.ProcessingHistoryReceipt;
import io.github.addxiaoyi.starchronicle.api.contribution.*;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class ProcessingHistoryProjection {
    public static final String NAMESPACE = "starcore.processing";
    private ProcessingHistoryProjection() {}
    public static String eventId(ProcessingHistoryReceipt r) {
        return "starcore:processing:" + r.authorityDomain() + ":" + r.run().runId() + ":" + r.kind();
    }
    public static WorldEvent event(ProcessingHistoryReceipt r) {
        var run = r.run(); Map<String,String> facts = new TreeMap<>();
        facts.put("title", switch (r.kind()) { case STARTED -> "工厂开工"; case COMPLETED -> "工厂生产完成"; case AUTOMATIC -> "系统资源加工"; });
        facts.put("history.namespace", NAMESPACE); facts.put("history.authority", r.authorityDomain().toString());
        facts.put("history.sequence", Long.toString(r.sequence())); facts.put("history.operation", run.runId().toString());
        facts.put("time_basis", "COMMITTED"); facts.put("processing.kind", r.kind().name());
        facts.put("processing.run", run.runId().toString()); facts.put("processing.recipe", run.recipeId());
        facts.put("processing.batches", Integer.toString(run.batches())); facts.put("processing.started_at", run.startedAt().toString());
        facts.put("processing.ready_at", run.readyAt().toString()); facts.put("nation.id", run.nationId().toString());
        if (run.factoryId() != null) facts.put("processing.factory", run.factoryId().toString());
        if (run.actorId() != null) facts.put("actor.id", run.actorId().toString());
        if (run.completedAt() != null) facts.put("processing.completed_at", run.completedAt().toString());
        facts.put("processing.receipt_sha256", hash(SqliteStore.json(r)));
        facts.put("contribution.policy", "EXACT_OUTPUT_PER_RESOURCE_ORIGINAL_ACTOR_AND_NATION_V1");
        if (r.kind() != ProcessingHistoryReceipt.Kind.COMPLETED) facts.put("contribution.omission", "NOT_COMPLETED_FACTORY_PRODUCTION");
        else if (run.actorId() == null) facts.put("contribution.omission", "NO_NATIVE_PLAYER_ACTOR");
        return new WorldEvent(eventId(r), "PROCESSING_" + r.kind(), r.committedAt(),
                run.actorId() == null ? Set.of() : Set.of(run.actorId()), Set.of(run.nationId().toString()), facts,
                "StarCore", "ProcessingHistoryService.processingChangesAfter; authority=" + r.authorityDomain()
                + "; sequence=" + r.sequence() + "; frozen original input/output and actor; full durable receipt retained");
    }
    public static List<ContributionRequest> contributions(ProcessingHistoryReceipt r) {
        var run = r.run();
        if (r.kind() != ProcessingHistoryReceipt.Kind.COMPLETED || run.actorId() == null) return List.of();
        List<ContributionRequest> result = new ArrayList<>();
        run.outputs().forEach((type, amount) -> {
            String operation = "starcore:production:" + r.authorityDomain() + ":" + run.runId() + ":" + type;
            for (String subject : List.of("player:" + run.actorId(), "nation:" + run.nationId()))
                result.add(new ContributionRequest(operation, ContributionType.CIVILIZATION, subject, eventId(r), run.actorId(),
                        run.nationId().toString(), BigDecimal.valueOf(amount), type, r.committedAt(),
                        "Exact factory output; run=" + run.runId() + "; recipe=" + run.recipeId() + "; batches=" + run.batches()
                        + "; original actor and nation from atomic start; authority=" + r.authorityDomain() + "; sequence=" + r.sequence()));
        });
        return List.copyOf(result);
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
