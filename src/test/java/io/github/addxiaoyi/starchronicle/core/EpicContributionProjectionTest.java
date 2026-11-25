package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.epic.*;
import io.github.addxiaoyi.starchronicle.api.contribution.ContributionType;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EpicContributionProjectionTest {
    private static final Instant NOW = Instant.parse("2026-11-25T00:00:00Z");
    private static final String PROVIDER = "starcore.epic.resource_aid";
    private static final String DOMAIN = "00000000-0000-0000-0000-000000000099";
    private static final String NA = "00000000-0000-0000-0000-000000000011";
    private static final String NB = "00000000-0000-0000-0000-000000000012";
    private static final String RELIEF = "00000000-0000-0000-0000-000000000013";
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    @TempDir Path temp;

    private EpicCampaignLedger campaign(SqliteStore store, String reference) {
        return campaign(store, reference, EpicRequirement.Type.RESOURCE_AID);
    }

    private EpicCampaignLedger campaign(SqliteStore store, String reference, EpicRequirement.Type type) {
        EpicCampaignLedger ledger = new EpicCampaignLedger(store);
        var plan = new EpicCampaignPlan("heir", "heir-story", "1", "EPIC_SIGNUP_COMMITTED",
                "epic.signup_campaign", "heir", "epic.relief_nation", List.of(
                new EpicPhasePolicy("aid", EpicPhasePolicy.Kind.PREPARATION,
                        Set.of(new EpicRequirement(type, "RELIEF", reference))),
                new EpicPhasePolicy("route", EpicPhasePolicy.Kind.FINAL_ROUTE, Set.of())));
        ledger.open(plan, "session", RELIEF, NOW);
        ledger.enroll("session", NA, A, 0, NOW);
        ledger.enroll("session", NB, B, 1, NOW);
        var signup = ledger.seal("session", 2, A, NOW);
        ledger.start("instance", "session", signup.sealedEventId(), NOW);
        return ledger;
    }

    private EpicOperation prepare(EpicCampaignLedger ledger, String nation, UUID actor, String reference) {
        var op = new EpicOperation("aid:" + actor, "heir", "instance", ledger.signup("session").sealedEventId(),
                "aid", EpicOperation.Type.RESOURCE_AID, actor, nation, RELIEF, reference,
                "a".repeat(64), "starcore.resource", NOW);
        ledger.prepare(op, PROVIDER, DOMAIN);
        return op;
    }

    private EpicObservation receipt(EpicOperation op) {
        return new EpicObservation(EpicObservation.Status.COMMITTED, new EpicReceipt(PROVIDER, DOMAIN,
                op.operationId(), op.campaignId(), op.instanceId(), op.sourceEventId(), op.phaseId(), op.type(),
                op.actorId(), op.nationId(), op.target(), op.nativeReference(), op.definitionFingerprint(),
                op.permission(), "native:" + op.actorId(), NOW.plusSeconds(1), "persisted source receipt"), "ok");
    }

    @Test void committedAidCreatesSourceAndTwoSubjectsExactlyOnceAcrossRestart() {
        Path db = temp.resolve("restart.db");
        EpicOperation op;
        List<String> identities;
        try (var store = new SqliteStore(db)) {
            var ledger = campaign(store, "agricultural:10");
            op = prepare(ledger, NA, A, "agricultural:10");
            var engine = new RuleEngine(store, List.of(), Clock.fixed(NOW, ZoneOffset.UTC));
            assertEquals(EpicOperationState.Status.COMMITTED,
                    engine.observeEpicOperation(op, receipt(op), PROVIDER, DOMAIN).status());
            var entries = engine.contributions(null, null, 0, 10);
            assertEquals(Set.of("player:" + A, "nation:" + NA), entries.stream().map(e -> e.request().subjectId()).collect(java.util.stream.Collectors.toSet()));
            assertEquals(1, engine.history(null, null, 0, 10).size());
            var event = engine.history(null, null, 0, 10).getFirst();
            assertEquals(NOW.plusSeconds(1), event.occurredAt());
            assertEquals(Set.of(NA, RELIEF), event.nations());
            assertEquals(Set.of(A), event.participants());
            assertEquals("COMMITTED_SOURCE", event.facts().get("time_basis"));
            for (var entry : entries) {
                assertEquals(event.id(), entry.request().sourceEventId());
                assertEquals("resource.agricultural", entry.request().unit());
                assertEquals(0, BigDecimal.TEN.compareTo(entry.request().amount()));
                assertEquals(NOW.plusSeconds(1), entry.recordedAt(), "Clock lag cannot predate the source commit");
            }
            identities = entries.stream().map(e -> e.id()).toList();
        }
        try (var store = new SqliteStore(db)) {
            var engine = new RuleEngine(store, List.of(), Clock.systemUTC());
            engine.observeEpicOperation(op, receipt(op), PROVIDER, DOMAIN);
            assertEquals(0, engine.reconcileEpicContributions(25));
            assertEquals(identities, engine.contributions(null, null, 0, 10).stream().map(e -> e.id()).toList());
            assertEquals(1, engine.history(null, null, 0, 10).size());
        }
    }

    @Test void failedProjectionRollsBackReceiptHistoryAndOtherSubjectBeforeReadOnlyRecovery() {
        try (var store = new SqliteStore(temp.resolve("rollback.db"))) {
            var ledger = campaign(store, "agricultural:10");
            var op = prepare(ledger, NA, A, "agricultural:10");
            var claimed = ledger.claimAttempt(op.operationId(), ledger.operation(op.operationId()).revision(), NOW);
            var engine = new RuleEngine(store, List.of(), Clock.systemUTC());
            store.transaction(c -> {
                try (var s = c.createStatement()) {
                    s.execute("CREATE TRIGGER reject_player_contribution BEFORE INSERT ON contributions WHEN NEW.subject_id LIKE 'player:%' BEGIN SELECT RAISE(ABORT,'simulated disk failure'); END");
                }
                return null;
            });
            assertThrows(RuntimeException.class, () -> engine.observeEpicOperation(op, receipt(op), PROVIDER, DOMAIN));
            assertEquals(claimed, ledger.operation(op.operationId()));
            assertTrue(engine.history(null, null, 0, 10).isEmpty());
            assertTrue(engine.contributions(null, null, 0, 10).isEmpty());
            store.transaction(c -> {try (var s = c.createStatement()) {s.execute("DROP TRIGGER reject_player_contribution");}return null;});
            engine.observeEpicOperation(op, receipt(op), PROVIDER, DOMAIN);
            assertEquals(2, engine.contributions(null, null, 0, 10).size());
        }
    }

    @Test void oldCommittedReceiptsBackfillInBoundedBatchesWithoutChangingOperationRevision() {
        Path db = temp.resolve("upgrade.db");
        List<EpicOperationState> before = new ArrayList<>();
        try (var store = new SqliteStore(db)) {
            var ledger = campaign(store, "agricultural:10");
            for (var entry : Map.of(NA, A, NB, B).entrySet()) {
                var op = prepare(ledger, entry.getKey(), entry.getValue(), "agricultural:10");
                before.add(ledger.observe(op, receipt(op), PROVIDER, DOMAIN, NOW.plusSeconds(2)));
            }
        }
        try (var store = new SqliteStore(db)) {
            var ledger = new EpicCampaignLedger(store);
            var engine = new RuleEngine(store, List.of(), Clock.systemUTC());
            assertEquals(1, engine.reconcileEpicContributions(1));
            assertEquals(2, engine.contributions(null, null, 0, 10).size());
            assertEquals(1, engine.reconcileEpicContributions(1));
            assertEquals(0, engine.reconcileEpicContributions(1));
            assertEquals(4, engine.contributions(null, null, 0, 10).size());
            for (var state : before) assertEquals(state, ledger.operation(state.operation().operationId()));
        }
    }

    @Test void uncommittedOrReboundAuthorityCannotProduceContributions() {
        try (var store = new SqliteStore(temp.resolve("authority.db"))) {
            var ledger = campaign(store, "agricultural:10");
            var op = prepare(ledger, NA, A, "agricultural:10");
            var engine = new RuleEngine(store, List.of(), Clock.systemUTC());
            engine.observeEpicOperation(op, EpicObservation.unknown("unavailable"), PROVIDER, DOMAIN);
            assertEquals(0, engine.reconcileEpicContributions(25));
            assertTrue(engine.contributions(null, null, 0, 10).isEmpty());
            assertThrows(SecurityException.class, () -> engine.observeEpicOperation(op, receipt(op), "other.provider", DOMAIN));
            assertThrows(SecurityException.class, () -> engine.observeEpicOperation(op, receipt(op), PROVIDER, UUID.randomUUID().toString()));
            assertTrue(engine.history(null, null, 0, 10).isEmpty());
        }
    }

    @Test void missingPinInLegacyReceiptFailsClosed() {
        try (var store = new SqliteStore(temp.resolve("pin.db"))) {
            var ledger = campaign(store, "agricultural:10");
            var op = prepare(ledger, NA, A, "agricultural:10");
            ledger.observe(op, receipt(op), PROVIDER, DOMAIN, NOW);
            store.transaction(c -> {try (var s = c.createStatement()) {s.execute("DELETE FROM epic_operation_providers");}return null;});
            var engine = new RuleEngine(store, List.of(), Clock.systemUTC());
            assertThrows(SecurityException.class, () -> engine.reconcileEpicContributions(25));
            assertTrue(engine.contributions(null, null, 0, 10).isEmpty());
            assertTrue(engine.history(null, null, 0, 10).isEmpty());
        }
    }

    @Test void nativeLongAmountsRemainExactAndPaginationFiltersBySubjectAndType() {
        String reference = "agricultural:" + Long.MAX_VALUE;
        try (var store = new SqliteStore(temp.resolve("large.db"))) {
            var ledger = campaign(store, reference);
            var op = prepare(ledger, NA, A, reference);
            var engine = new RuleEngine(store, List.of(), Clock.systemUTC());
            engine.observeEpicOperation(op, receipt(op), PROVIDER, DOMAIN);
            var first = engine.contributions(null, ContributionType.RESOURCE_AID, 0, 1);
            var second = engine.contributions(null, ContributionType.RESOURCE_AID, 1, 1);
            assertEquals(BigDecimal.valueOf(Long.MAX_VALUE), first.getFirst().request().amount());
            assertNotEquals(first.getFirst().id(), second.getFirst().id());
            assertEquals(1, engine.contributions("nation:" + NA, null, 0, 10).size());
            assertTrue(engine.contributions("nation:" + RELIEF, null, 0, 10).isEmpty());
            assertTrue(engine.contributions(null, ContributionType.TRADE, 0, 10).isEmpty());
            assertThrows(IllegalArgumentException.class, () -> engine.contributions(null, null, -1, 10));
            assertThrows(IllegalArgumentException.class, () -> engine.contributions(null, null, 0, 101));
            assertThrows(IllegalArgumentException.class, () -> engine.reconcileEpicContributions(101));
        }
    }

    @Test void treasuryEpicAndExistingAidServiceShareHistoryAndContributionIdentity() {
        try (var store = new SqliteStore(temp.resolve("treasury.db"))) {
            var ledger = campaign(store, "10.00", EpicRequirement.Type.TREASURY_AID);
            var op = new EpicOperation("aid:treasury", "heir", "instance", ledger.signup("session").sealedEventId(),
                    "aid", EpicOperation.Type.TREASURY_AID, A, NA, RELIEF, "10.00", "a".repeat(64),
                    io.github.addxiaoyi.starchronicle.api.aid.AidRequest.PERMISSION, NOW);
            ledger.prepare(op, PROVIDER, DOMAIN);
            var proof = receipt(op).receipt();
            proof = new EpicReceipt(proof.issuer(), proof.authorityDomain(), proof.operationId(), proof.campaignId(),
                    proof.instanceId(), proof.sourceEventId(), proof.phaseId(), proof.type(), proof.actorId(), proof.nationId(),
                    proof.target(), proof.nativeReference(), proof.definitionFingerprint(), proof.permission(), op.operationId(),
                    proof.appliedAt(), proof.evidence());
            // Upgrade path: the old epic provider stored its receipt without using NationalAidService.
            ledger.observe(op, new EpicObservation(EpicObservation.Status.COMMITTED, proof, "old epic receipt"), PROVIDER, DOMAIN, NOW);
            var engine = new RuleEngine(store, List.of(), Clock.systemUTC());
            assertEquals(1, engine.reconcileEpicContributions(1));
            assertEquals(2, engine.contributions(null, null, 0, 10).size());
            assertTrue(engine.contributions(null, null, 0, 10).stream().allMatch(e -> e.request().unit().equals("treasury")));
            var request = EpicContract.treasuryAidRequest(op);
            var nativeReceipt = new io.github.addxiaoyi.starchronicle.api.aid.AidReceipt(PROVIDER, DOMAIN, op.operationId(),
                    NA, RELIEF, request.amount(), "OTHER", request.description(), proof.appliedAt());
            store.transaction(c -> AidLedger.admit(c, request, PROVIDER, DOMAIN, NOW));
            var context = new io.github.addxiaoyi.starchronicle.api.PlayerContext(A, NA, Map.of(), Set.of());
            engine.commitAid(request, nativeReceipt, PROVIDER, DOMAIN, List.of(context));
            assertEquals(2, engine.contributions(null, null, 0, 10).size(), "Same transfer across both entrypoints must not double count");
            assertEquals(1, engine.history(null, null, 0, 10).size());
            assertEquals(0, engine.reconcileEpicContributions(1));
        }
    }
}
