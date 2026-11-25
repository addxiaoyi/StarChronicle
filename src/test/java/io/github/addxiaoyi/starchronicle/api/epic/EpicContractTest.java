package io.github.addxiaoyi.starchronicle.api.epic;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EpicContractTest {
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String NATION = "00000000-0000-0000-0000-000000000011";
    private static final String DOMAIN = "00000000-0000-0000-0000-000000000099";
    private static final String HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final Instant NOW = Instant.parse("2026-11-21T00:00:00Z");

    private EpicOperation operation(String id) {
        return new EpicOperation(id, "last-heir", "instance-1", "starcore:heir:1",
                "relief", EpicOperation.Type.TREASURY_AID, ACTOR, NATION,
                "old-capital", "treasury-transfer", HASH,
                "starcore.nation.bank.withdraw", NOW);
    }

    private EpicReceipt receipt(EpicOperation operation, String provider) {
        return new EpicReceipt(provider, DOMAIN, operation.operationId(), operation.campaignId(),
                operation.instanceId(), operation.sourceEventId(), operation.phaseId(), operation.type(),
                operation.actorId(), operation.nationId(), operation.target(), operation.nativeReference(),
                operation.definitionFingerprint(), operation.permission(),
                "native:" + operation.operationId().substring(5), NOW.plusSeconds(1),
                "committed native receipt");
    }

    @Test void stableOperationIdBindsAllStrategicDimensions() {
        String first = EpicContract.operationId("last-heir", "instance-1", "relief", "aid", NATION);
        assertEquals(first, EpicContract.operationId("last-heir", "instance-1", "relief", "aid", NATION));
        assertNotEquals(first, EpicContract.operationId("last-heir", "instance-1", "battle", "aid", NATION));
        assertTrue(first.startsWith("epic:"));
    }

    @Test void committedReceiptMustMatchProviderAndOperation() {
        EpicOperation operation = operation("epic:aid-1");
        EpicReceipt receipt = receipt(operation, "starcore.treasury");
        assertSame(receipt, EpicContract.requireCommitted(operation,
                new EpicObservation(EpicObservation.Status.COMMITTED, receipt, "ok"),
                "starcore.treasury", DOMAIN));
        assertThrows(IllegalStateException.class, () -> EpicContract.requireCommitted(operation,
                new EpicObservation(EpicObservation.Status.COMMITTED, receipt, "ok"),
                "other.provider", DOMAIN));
    }

    @Test void unknownOrMismatchedObservationsCannotAdvanceEpic() {
        EpicOperation operation = operation("epic:aid-2");
        assertThrows(IllegalStateException.class, () -> EpicContract.requireCommitted(operation,
                EpicObservation.unknown("provider unavailable"), "starcore.treasury", DOMAIN));
        EpicReceipt wrong = new EpicReceipt("starcore.treasury", DOMAIN, operation.operationId(),
                operation.campaignId(), operation.instanceId(), operation.sourceEventId(), "other-phase",
                operation.type(), operation.actorId(), operation.nationId(), operation.target(),
                operation.nativeReference(), operation.definitionFingerprint(), operation.permission(),
                "native:other", NOW.plusSeconds(1), "receipt for another phase");
        assertThrows(IllegalStateException.class, () -> EpicContract.requireCommitted(operation,
                new EpicObservation(EpicObservation.Status.COMMITTED, wrong, "mismatch"),
                "starcore.treasury", DOMAIN));
    }

    @Test void receiptBindsEveryStoryAndAuthorizationDimension() {
        EpicOperation operation = operation("epic:aid-3");
        EpicReceipt good = receipt(operation, "starcore.treasury");
        EpicOperation changedPhase = new EpicOperation(operation.operationId(), operation.campaignId(),
                operation.instanceId(), operation.sourceEventId(), "battle", operation.type(),
                operation.actorId(), operation.nationId(), operation.target(), operation.nativeReference(),
                operation.definitionFingerprint(), operation.permission(), operation.requestedAt());
        EpicOperation changedDefinition = new EpicOperation(operation.operationId(), operation.campaignId(),
                operation.instanceId(), operation.sourceEventId(), operation.phaseId(), operation.type(),
                operation.actorId(), operation.nationId(), operation.target(), operation.nativeReference(),
                "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
                operation.permission(), operation.requestedAt());
        EpicOperation changedPermission = new EpicOperation(operation.operationId(), operation.campaignId(),
                operation.instanceId(), operation.sourceEventId(), operation.phaseId(), operation.type(),
                operation.actorId(), operation.nationId(), operation.target(), operation.nativeReference(),
                operation.definitionFingerprint(), "starcore.nation.bank.deposit", operation.requestedAt());
        for (EpicOperation changed : new EpicOperation[]{changedPhase, changedDefinition, changedPermission}) {
            assertThrows(IllegalStateException.class, () -> EpicContract.requireCommitted(changed,
                    new EpicObservation(EpicObservation.Status.COMMITTED, good, "same native receipt"),
                    "starcore.treasury", DOMAIN));
        }
    }

    @Test void operationRejectsNonCanonicalAuthorityInputs() {
        assertThrows(IllegalArgumentException.class, () -> new EpicOperation(
                "epic:bad", "last-heir", "instance-1", "source", "relief",
                EpicOperation.Type.TREASURY_AID, ACTOR, "nation-name", "capital", "native",
                HASH, "starcore.nation.bank.withdraw", NOW));
        EpicOperation operation = operation("epic:bad");
        assertThrows(IllegalArgumentException.class, () -> new EpicReceipt(
                "provider", DOMAIN, operation.operationId(), operation.campaignId(), operation.instanceId(),
                operation.sourceEventId(), operation.phaseId(), EpicOperation.Type.QUEST_GATE, ACTOR,
                NATION, "capital", operation.nativeReference(), operation.definitionFingerprint(),
                operation.permission(), "receipt", NOW, ""));
    }
}
