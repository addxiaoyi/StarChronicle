package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import io.github.addxiaoyi.starchronicle.api.contribution.ContributionRequest;
import io.github.addxiaoyi.starchronicle.api.contribution.ContributionType;
import io.github.addxiaoyi.starchronicle.api.epic.*;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

import static io.github.addxiaoyi.starchronicle.persistence.SqliteStore.decode;
import static io.github.addxiaoyi.starchronicle.persistence.SqliteStore.json;

/** Projects stored, provider-verified aid receipts without invoking the native service. */
final class EpicContributionProjection {
    private EpicContributionProjection() {}

    static void createSchema(Connection c) throws SQLException {
        try (var s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS epic_contribution_projections(" +
                    "operation_id TEXT PRIMARY KEY REFERENCES epic_operations(operation_id)," +
                    "source_event_id TEXT NOT NULL REFERENCES events(id),projected_at TEXT NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS epic_aid_contribution_candidates ON epic_operations(operation_id) " +
                    "WHERE status='COMMITTED' AND json_extract(operation_json,'$.type') IN ('RESOURCE_AID','TREASURY_AID')");
        }
    }

    static List<EpicOperationState> pending(Connection c, int limit) throws SQLException {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Projection batch must be 1..100");
        try (var s = c.prepareStatement("SELECT o.* FROM epic_operations o WHERE o.status='COMMITTED' " +
                "AND json_extract(o.operation_json,'$.type') IN ('RESOURCE_AID','TREASURY_AID') " +
                "AND NOT EXISTS (SELECT 1 FROM epic_contribution_projections p WHERE p.operation_id=o.operation_id) " +
                "ORDER BY o.operation_id LIMIT ?")) {
            s.setInt(1, limit);
            List<EpicOperationState> result = new ArrayList<>();
            try (var r = s.executeQuery()) {
                while (r.next()) result.add(new EpicOperationState(decode(r.getString("operation_json"), EpicOperation.class),
                        EpicOperationState.Status.COMMITTED, decode(r.getString("receipt_json"), EpicReceipt.class),
                        r.getString("detail"), r.getLong("revision"), Instant.parse(r.getString("updated_at"))));
            }
            return List.copyOf(result);
        }
    }

    static Optional<WorldEvent> event(Connection c, EpicOperationState state) throws SQLException {
        if (state.status() != EpicOperationState.Status.COMMITTED || !Set.of(EpicOperation.Type.RESOURCE_AID, EpicOperation.Type.TREASURY_AID).contains(state.operation().type()))
            return Optional.empty();
        try (var s = c.prepareStatement("SELECT 1 FROM epic_contribution_projections WHERE operation_id=?")) {
            s.setString(1, state.operation().operationId());
            try (var r = s.executeQuery()) { if (r.next()) return Optional.empty(); }
        }
        EpicReceipt receipt;
        try (var s = c.prepareStatement("SELECT provider_id,authority_domain FROM epic_operation_providers WHERE operation_id=?")) {
            s.setString(1, state.operation().operationId());
            try (var r = s.executeQuery()) {
                if (!r.next()) throw new SecurityException("Aid contribution has no pinned native authority");
                receipt = EpicContract.requireCommitted(state.operation(), new EpicObservation(
                        EpicObservation.Status.COMMITTED, state.receipt(), "stored receipt"), r.getString(1), r.getString(2));
            }
        }
        if (receipt.type() == EpicOperation.Type.TREASURY_AID) {
            var request = EpicContract.treasuryAidRequest(state.operation());
            var nativeReceipt = treasuryReceipt(state);
            return Optional.of(RuleEngine.aidEvent(request, nativeReceipt, receipt.issuer(), receipt.authorityDomain()));
        }
        Map<String, String> facts = new TreeMap<>();
        facts.put("title", "国家资源援助已交付");
        facts.put("time_basis", "COMMITTED_SOURCE");
        facts.put("resource.operation", receipt.operationId());
        facts.put("resource.native_receipt", receipt.nativeReceiptId());
        facts.put("resource.authority_domain", receipt.authorityDomain());
        facts.put("resource.payer", receipt.nationId());
        facts.put("resource.receiver", receipt.target());
        facts.put("resource.quantities", json(new TreeMap<>(EpicContract.resourceAmounts(receipt.nativeReference()))));
        facts.put("epic.campaign", receipt.campaignId());
        facts.put("epic.instance", receipt.instanceId());
        facts.put("epic.source_event", receipt.sourceEventId());
        facts.put("receipt.sha256", RuleEngine.stable(json(receipt)));
        String identity = RuleEngine.stable(receipt.authorityDomain(), receipt.nativeReceiptId());
        return Optional.of(new WorldEvent("chronicle:resource-aid:" + identity, "NATIONAL_RESOURCE_AID_COMMITTED",
                receipt.appliedAt(), Set.of(receipt.actorId()), Set.of(receipt.nationId(), receipt.target()), facts,
                receipt.issuer(), "native_receipt=" + receipt.nativeReceiptId() + "; authority_domain=" + receipt.authorityDomain()
                        + "; receipt_sha256=" + facts.get("receipt.sha256")));
    }

    static void record(Connection c, EpicOperationState state, WorldEvent event, Instant now) throws SQLException {
        EpicReceipt receipt = state.receipt();
        Instant recordedAt = now.isBefore(receipt.appliedAt()) ? receipt.appliedAt() : now;
        if (receipt.type() == EpicOperation.Type.TREASURY_AID) {
            RuleEngine.recordAidContributions(c, EpicContract.treasuryAidRequest(state.operation()), treasuryReceipt(state),
                    receipt.issuer(), receipt.authorityDomain(), event.id(), recordedAt);
        } else {
        for (var resource : new TreeMap<>(EpicContract.resourceAmounts(receipt.nativeReference())).entrySet()) {
            String operation = "chronicle:resource-component:" + RuleEngine.stable(receipt.authorityDomain(), receipt.nativeReceiptId(), resource.getKey());
            for (String subject : List.of("nation:" + receipt.nationId(), "player:" + receipt.actorId())) {
                ContributionLedger.record(c, new ContributionRequest(operation, ContributionType.RESOURCE_AID, subject,
                        event.id(), receipt.actorId(), receipt.nationId(), BigDecimal.valueOf(resource.getValue()),
                        "resource." + resource.getKey(), receipt.appliedAt(), event.evidence()), recordedAt);
            }
        }
        }
        try (var s = c.prepareStatement("INSERT INTO epic_contribution_projections VALUES(?,?,?)")) {
            s.setString(1, receipt.operationId()); s.setString(2, event.id()); s.setString(3, recordedAt.toString());
            if (s.executeUpdate() != 1) throw new IllegalStateException("Resource contribution projection was lost");
        }
    }
    /** Lossless projection of fields already checked by the treasury epic provider's pinned contract. */
    private static io.github.addxiaoyi.starchronicle.api.aid.AidReceipt treasuryReceipt(EpicOperationState state) {
        var request = EpicContract.treasuryAidRequest(state.operation());
        var receipt = state.receipt();
        if (!receipt.nativeReceiptId().equals(request.outcomeId()))
            throw new IllegalStateException("Treasury native receipt does not match the aid transaction");
        return new io.github.addxiaoyi.starchronicle.api.aid.AidReceipt(receipt.issuer(), receipt.authorityDomain(),
                receipt.nativeReceiptId(), request.payerId(), request.receiverId(), request.amount(), "OTHER",
                request.description(), receipt.appliedAt());
    }

}
