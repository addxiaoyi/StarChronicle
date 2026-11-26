package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import io.github.addxiaoyi.starchronicle.api.aid.*;
import io.github.addxiaoyi.starchronicle.api.contribution.*;
import io.github.addxiaoyi.starchronicle.api.epic.*;
import java.math.BigDecimal;
import java.util.*;

/** Projects only a provider-verified aid receipt; never performs a native operation. */
final class EpicContributionProjection {
    record Projection(WorldEvent event, List<ContributionRequest> contributions) {}

    static void validate(EpicOperation operation) {
        if (operation.type() == EpicOperation.Type.TREASURY_AID)
            AidRequest.amount(new BigDecimal(operation.nativeReference()));
        if (operation.type() == EpicOperation.Type.RESOURCE_AID) {
            String[] parts = operation.nativeReference().split(":", -1);
            if (parts.length != 2) throw new IllegalArgumentException("Resource aid requires resource:amount");
            ContributionRequest.token(parts[0].toLowerCase(Locale.ROOT), "resource unit", 32);
            ContributionRequest.amount(BigDecimal.valueOf(Long.parseLong(parts[1])));
        }
    }

    static Optional<Projection> project(EpicOperation operation, EpicReceipt receipt) {
        validate(operation);
        if (operation.type() == EpicOperation.Type.TREASURY_AID) {
            if (!AidRequest.PERMISSION.equals(operation.permission())
                    || !operation.operationId().equals(receipt.nativeReceiptId()))
                throw new SecurityException("Treasury contribution requires the exact transfer receipt");
            // The treasury epic provider verifies these exact fields before projecting its receipt.
            // Reuse the aid API's canonical event/key so the two entry points cannot count it twice.
            AidRequest request = new AidRequest(operation.operationId(), operation.instanceId(),
                    operation.sourceEventId(), operation.campaignId(), AidRequest.OPERATION,
                    operation.definitionFingerprint(), operation.actorId(), operation.nationId(),
                    operation.target(), new BigDecimal(operation.nativeReference()), operation.requestedAt());
            AidReceipt aid = new AidReceipt(receipt.issuer(), receipt.authorityDomain(), receipt.nativeReceiptId(),
                    request.payerId(), request.receiverId(), request.amount(), "OTHER", request.description(), receipt.appliedAt());
            WorldEvent event = RuleEngine.aidEvent(request, aid, receipt.issuer(), receipt.authorityDomain());
            return Optional.of(new Projection(event, aidContributions(request, aid, event.id())));
        }
        if (operation.type() != EpicOperation.Type.RESOURCE_AID) return Optional.empty();
        String[] parts = operation.nativeReference().split(":", -1);
        if (parts.length != 2) throw new IllegalArgumentException("Resource aid requires resource:amount");
        String unit = parts[0].toLowerCase(Locale.ROOT);
        ContributionRequest.token(unit, "resource unit", 32);
        BigDecimal amount = ContributionRequest.amount(BigDecimal.valueOf(Long.parseLong(parts[1])));
        String eventId = "chronicle:resource-aid:" + RuleEngine.stable(receipt.authorityDomain(), receipt.nativeReceiptId());
        Map<String,String> facts = new TreeMap<>();
        facts.put("aid.operation", operation.operationId());
        facts.put("aid.native_receipt", receipt.nativeReceiptId());
        facts.put("aid.issuer", receipt.issuer());
        facts.put("aid.authority_domain", receipt.authorityDomain());
        facts.put("aid.payer", operation.nationId());
        facts.put("aid.receiver", operation.target());
        facts.put("aid.amount", amount.toPlainString());
        facts.put("aid.resource", unit);
        facts.put("aid.campaign", operation.campaignId());
        facts.put("aid.instance", operation.instanceId());
        facts.put("aid.applied_at", receipt.appliedAt().toString());
        facts.put("time_basis", "COMMITTED_SOURCE");
        String evidence = "issuer=" + receipt.issuer() + "; authority_domain=" + receipt.authorityDomain()
                + "; native_receipt=" + receipt.nativeReceiptId() + "; receipt_sha256="
                + RuleEngine.stable(io.github.addxiaoyi.starchronicle.persistence.SqliteStore.json(receipt));
        WorldEvent event = new WorldEvent(eventId, "NATIONAL_RESOURCE_AID_COMMITTED", receipt.appliedAt(),
                Set.of(operation.actorId()), Set.of(operation.nationId(), operation.target()), facts,
                receipt.issuer(), evidence);
        return Optional.of(new Projection(event, List.of(
                entry(operation, receipt, eventId, "nation:" + operation.nationId(), amount, unit, evidence),
                entry(operation, receipt, eventId, "player:" + operation.actorId(), amount, unit, evidence))));
    }

    static List<ContributionRequest> aidContributions(AidRequest request, AidReceipt receipt, String eventId) {
        String evidence = "StarCore receipt issuer=" + receipt.issuer() + " domain=" + receipt.authorityDomain()
                + " transaction=" + receipt.transactionId();
        return List.of(
                new ContributionRequest(request.outcomeId(), ContributionType.RESOURCE_AID, "nation:" + request.payerId(),
                        eventId, request.actorId(), request.payerId(), request.amount(), "treasury", receipt.appliedAt(), evidence),
                new ContributionRequest(request.outcomeId(), ContributionType.RESOURCE_AID, "player:" + request.actorId(),
                        eventId, request.actorId(), request.payerId(), request.amount(), "treasury", receipt.appliedAt(), evidence));
    }

    private static ContributionRequest entry(EpicOperation op, EpicReceipt receipt, String event, String subject,
                                              BigDecimal amount, String unit, String evidence) {
        return new ContributionRequest(op.operationId(), ContributionType.RESOURCE_AID, subject, event,
                op.actorId(), op.nationId(), amount, unit, receipt.appliedAt(), evidence);
    }
}
