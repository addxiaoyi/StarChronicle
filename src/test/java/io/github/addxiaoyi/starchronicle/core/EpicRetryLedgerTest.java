package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.epic.*;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class EpicRetryLedgerTest {
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final String NATION = "00000000-0000-0000-0000-000000000011";
    private static final String SECOND = "00000000-0000-0000-0000-000000000012";
    private static final String RELIEF = "00000000-0000-0000-0000-000000000019";
    private static final String DOMAIN = "00000000-0000-0000-0000-000000000099";
    private static final Instant NOW = Instant.parse("2026-11-27T00:00:00Z");
    @TempDir Path temp;

    private EpicCampaignLedger campaign(SqliteStore store) {
        var ledger = new EpicCampaignLedger(store);
        var plan = new EpicCampaignPlan("heir", "heir-story", "1", "EPIC_SIGNUP_COMMITTED", "epic.signup_campaign", "heir", "epic.relief_nation",
                List.of(new EpicPhasePolicy("aid", EpicPhasePolicy.Kind.PREPARATION,
                                Set.of(new EpicRequirement(EpicRequirement.Type.RESOURCE_AID, "RELIEF", "agricultural:10"))),
                        new EpicPhasePolicy("route", EpicPhasePolicy.Kind.FINAL_ROUTE, Set.of())));
        ledger.open(plan, "session", RELIEF, NOW);
        ledger.enroll("session", NATION, ACTOR, 0, NOW);
        ledger.enroll("session", SECOND, OTHER, 1, NOW);
        ledger.seal("session", 2, ACTOR, NOW);
        ledger.start("instance", "session", "source", NOW);
        return ledger;
    }

    private EpicOperation operation(String id) {
        return new EpicOperation(id, "heir", "instance", "source", "aid", EpicOperation.Type.RESOURCE_AID,
                ACTOR, NATION, RELIEF, "agricultural:10", "a".repeat(64), "starcore.resource", NOW);
    }

    private EpicOperationState reject(EpicCampaignLedger ledger, EpicOperation operation) {
        ledger.prepare(operation, "native.resource", DOMAIN);
        ledger.claimAttempt(operation.operationId(), 1, operation.requestedAt().plusSeconds(1));
        return ledger.observe(operation, new EpicObservation(EpicObservation.Status.REJECTED, null, "insufficient resources"),
                "native.resource", DOMAIN, operation.requestedAt().plusSeconds(2));
    }

    private EpicRejectionProof proof(EpicOperation operation) {
        return new EpicRejectionProof("native.resource", DOMAIN, operation, UUID.randomUUID().toString(),
                "INSUFFICIENT_RESOURCES", operation.requestedAt().plusSeconds(2));
    }

    private EpicObservation committed(EpicOperation operation, String receiptId) {
        return new EpicObservation(EpicObservation.Status.COMMITTED, new EpicReceipt("native.resource", DOMAIN,
                operation.operationId(), operation.campaignId(), operation.instanceId(), operation.sourceEventId(), operation.phaseId(),
                operation.type(), operation.actorId(), operation.nationId(), operation.target(), operation.nativeReference(),
                operation.definitionFingerprint(), operation.permission(), receiptId, NOW.plusSeconds(30), "native durable receipt"), "committed");
    }

    @Test void retryAppendsFrozenIntentAndProofAcrossSqliteRestarts() {
        Path database = temp.resolve("restart.db");
        EpicOperation root = operation("op:aid");
        EpicRejectionProof rejection = proof(root);
        String secondId;
        try (var store = new SqliteStore(database)) {
            var ledger = campaign(store);
            var rejected = reject(ledger, root);
            var next = ledger.retry(root.operationId(), rejected.revision(), rejection, "native.resource", DOMAIN, NOW.plusSeconds(3));
            secondId = next.operation().operationId();
            assertEquals(EpicOperationState.Status.PREPARED, next.status());
            assertEquals(1, next.revision());
            assertEquals(rejected, ledger.operation(root.operationId()));
            assertEquals(root.actorId(), next.operation().actorId());
            assertEquals(root.nativeReference(), next.operation().nativeReference());
            assertEquals(root.definitionFingerprint(), next.operation().definitionFingerprint());
            assertNotEquals(root.operationId(), secondId);
            assertFalse(ledger.phaseReady("instance", "aid", NATION));
            assertThrows(IllegalStateException.class, () -> ledger.retry(root.operationId(), rejected.revision(), rejection, "native.resource", DOMAIN, NOW.plusSeconds(4)));
        }
        try (var store = new SqliteStore(database)) {
            var ledger = new EpicCampaignLedger(store);
            var lineage = ledger.attempt(secondId);
            assertEquals(root.operationId(), lineage.rootOperationId());
            assertEquals(root.operationId(), lineage.previousOperationId());
            assertEquals(2, lineage.attempt());
            assertEquals(rejection, lineage.rejectionProof());
            var second = ledger.operation(secondId).operation();
            ledger.claimAttempt(secondId, 1, NOW.plusSeconds(4));
            var rejected = ledger.observe(second, new EpicObservation(EpicObservation.Status.REJECTED, null, "still insufficient"), "native.resource", DOMAIN, NOW.plusSeconds(5));
            var third = ledger.retry(secondId, rejected.revision(), proof(second), "native.resource", DOMAIN, NOW.plusSeconds(6));
            assertEquals(3, ledger.attempt(third.operation().operationId()).attempt());
            assertEquals(root.operationId(), ledger.attempt(third.operation().operationId()).rootOperationId());
            assertEquals(3, ledger.state("instance").operations().size());
            assertEquals(EpicOperationState.Status.REJECTED, ledger.operation(root.operationId()).status());
        }
    }

    @Test void preparedUnknownAndCommittedCannotStartAnotherAttempt() {
        for (var status : List.of(EpicOperationState.Status.PREPARED, EpicOperationState.Status.UNKNOWN, EpicOperationState.Status.COMMITTED)) {
            try (var store = new SqliteStore(temp.resolve(status + ".db"))) {
                var ledger = campaign(store); var root = operation("op:aid");
                var state = ledger.prepare(root, "native.resource", DOMAIN);
                if (status != EpicOperationState.Status.PREPARED) state = ledger.claimAttempt(root.operationId(), 1, NOW.plusSeconds(1));
                if (status == EpicOperationState.Status.COMMITTED) state = ledger.observe(root, committed(root, "native:committed"), "native.resource", DOMAIN, NOW.plusSeconds(30));
                long revision = state.revision();
                assertThrows(IllegalStateException.class, () -> ledger.retry(root.operationId(), revision, proof(root), "native.resource", DOMAIN, NOW.plusSeconds(31)));
                assertEquals(1, ledger.state("instance").operations().size());
            }
        }
    }

    @Test void retryRejectsChangedProofPayloadProviderDomainAndStaleRevision() {
        try (var store = new SqliteStore(temp.resolve("proof.db"))) {
            var ledger = campaign(store); var root = operation("op:aid"); var rejected = reject(ledger, root);
            var changed = operation("op:unrelated");
            var wrongProof = new EpicRejectionProof("native.resource", DOMAIN, changed, UUID.randomUUID().toString(), "definite", NOW.plusSeconds(2));
            assertThrows(SecurityException.class, () -> ledger.retry(root.operationId(), rejected.revision(), wrongProof, "native.resource", DOMAIN, NOW.plusSeconds(3)));
            assertThrows(SecurityException.class, () -> ledger.retry(root.operationId(), rejected.revision(), proof(root), "another.provider", DOMAIN, NOW.plusSeconds(3)));
            assertThrows(SecurityException.class, () -> ledger.retry(root.operationId(), rejected.revision(), proof(root), "native.resource", SECOND, NOW.plusSeconds(3)));
            assertThrows(IllegalStateException.class, () -> ledger.retry(root.operationId(), rejected.revision() - 1, proof(root), "native.resource", DOMAIN, NOW.plusSeconds(3)));
            assertThrows(IllegalStateException.class, () -> ledger.retry(root.operationId(), rejected.revision(), proof(root), "native.resource", DOMAIN, NOW.plusSeconds(1)));
            assertEquals(1, ledger.state("instance").operations().size());
        }
    }

    @Test void concurrentRetriesReserveExactlyOneSuccessor() throws Exception {
        try (var store = new SqliteStore(temp.resolve("concurrent.db"))) {
            var ledger = campaign(store); var root = operation("op:aid"); var rejected = reject(ledger, root);
            var rejection = proof(root); var start = new CountDownLatch(1);
            try (var executor = Executors.newFixedThreadPool(2)) {
                Callable<Boolean> retry = () -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    try { ledger.retry(root.operationId(), rejected.revision(), rejection, "native.resource", DOMAIN, NOW.plusSeconds(3)); return true; }
                    catch (IllegalStateException refused) { return false; }
                };
                var first = executor.submit(retry); var second = executor.submit(retry); start.countDown();
                assertNotEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            }
            assertEquals(2, ledger.state("instance").operations().size());
        }
    }

    @Test void rejectedAncestorCannotBeRewrittenAfterSuccessorWasPrepared() {
        try (var store = new SqliteStore(temp.resolve("ancestor.db"))) {
            var ledger = campaign(store); var root = operation("op:aid"); var rejected = reject(ledger, root);
            ledger.retry(root.operationId(), rejected.revision(), proof(root), "native.resource", DOMAIN, NOW.plusSeconds(3));
            assertThrows(SecurityException.class, () -> ledger.observe(root, committed(root, "native:impossible"), "native.resource", DOMAIN, NOW.plusSeconds(30)));
            assertThrows(SecurityException.class, () -> ledger.observe(root, EpicObservation.unknown("late unknown"), "native.resource", DOMAIN, NOW.plusSeconds(30)));
            assertEquals(rejected, ledger.observe(root, new EpicObservation(EpicObservation.Status.REJECTED, null, "same rejection"), "native.resource", DOMAIN, NOW.plusSeconds(30)));
            assertEquals(rejected, ledger.operation(root.operationId()));
        }
    }

    @Test void campaignThatMovedPastTheOriginalPhaseRefusesRetry() {
        try (var store = new SqliteStore(temp.resolve("phase.db"))) {
            var ledger = campaign(store); var root = operation("op:aid"); var rejected = reject(ledger, root);
            var alternative = operation("op:alternative"); ledger.prepare(alternative, "native.resource", DOMAIN);
            ledger.observe(alternative, committed(alternative, "native:alternative"), "native.resource", DOMAIN, NOW.plusSeconds(30));
            var other = new EpicOperation("op:other", root.campaignId(), root.instanceId(), root.sourceEventId(), root.phaseId(), root.type(),
                    OTHER, SECOND, root.target(), root.nativeReference(), root.definitionFingerprint(), root.permission(), NOW);
            ledger.prepare(other, "native.resource", DOMAIN);
            ledger.observe(other, committed(other, "native:other"), "native.resource", DOMAIN, NOW.plusSeconds(30));
            assertEquals(EpicCampaignState.Status.ROUTE_VOTING, ledger.state("instance").status());
            assertThrows(IllegalStateException.class, () -> ledger.retry(root.operationId(), rejected.revision(), proof(root), "native.resource", DOMAIN, NOW.plusSeconds(31)));
            assertEquals(3, ledger.state("instance").operations().size());
        }
    }

    @Test void legacyRowsAcquireOnlyRootLineageWithoutInventedProof() {
        Path database = temp.resolve("legacy.db");
        try (var store = new SqliteStore(database)) {
            var ledger = campaign(store); reject(ledger, operation("op:legacy"));
            store.transaction(connection -> { try (var statement = connection.createStatement()) { statement.execute("DROP TABLE epic_operation_attempts"); } return null; });
        }
        try (var store = new SqliteStore(database)) {
            var ledger = new EpicCampaignLedger(store); var lineage = ledger.attempt("op:legacy");
            assertEquals(1, lineage.attempt()); assertNull(lineage.rejectionProof()); assertNull(lineage.previousOperationId());
            assertEquals(EpicOperationState.Status.REJECTED, ledger.operation("op:legacy").status());
        }
    }
}
