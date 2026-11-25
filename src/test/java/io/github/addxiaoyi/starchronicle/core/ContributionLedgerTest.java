package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import io.github.addxiaoyi.starchronicle.api.contribution.*;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ContributionLedgerTest {
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant COMMITTED = Instant.parse("2026-11-21T00:00:00Z");
    private static final WorldEvent SOURCE = new WorldEvent("trade:committed", "TRADE_COMMITTED", COMMITTED,
            Set.of(ACTOR), Set.of("nation-a"), Map.of("trade.post_commit", "true"), "TradeService", "receipt=trade-1");
    @TempDir Path temp;

    private ContributionRequest request(String operation, ContributionType type, String subject, String source) {
        return new ContributionRequest(operation, type, subject, source, ACTOR, "nation-a",
                new BigDecimal("12.500"), "units", COMMITTED, "receipt=" + operation);
    }

    @Test void recordsOnlyCommittedSourceAndDeduplicatesStableOperation() {
        try (SqliteStore store = new SqliteStore(temp.resolve("contribution.db"))) {
            RuleEngine engine = new RuleEngine(store, List.of(), Clock.fixed(COMMITTED, ZoneOffset.UTC));
            engine.accept(SOURCE, List.of());
            ContributionLedger ledger = new ContributionLedger(store, Clock.fixed(COMMITTED.plusSeconds(2), ZoneOffset.UTC));
            ContributionRequest request = request("trade:1", ContributionType.TRADE, "nation-a", SOURCE.id());
            var first = ledger.record(request);
            var second = ledger.record(request);
            assertTrue(first.fresh());
            assertFalse(second.fresh());
            assertEquals(first.entry(), second.entry());
            assertEquals(new BigDecimal("12.5"), ledger.total("nation-a", ContributionType.TRADE, "units"));
            assertEquals(1, ledger.list("nation-a", ContributionType.TRADE, 0, 10).size());
        }
    }

    @Test void conflictingReuseAndUnknownSourceAreRejected() {
        try (SqliteStore store = new SqliteStore(temp.resolve("conflict.db"))) {
            ContributionLedger ledger = new ContributionLedger(store, Clock.fixed(COMMITTED, ZoneOffset.UTC));
            assertThrows(IllegalArgumentException.class, () -> ledger.record(request("missing", ContributionType.TRADE, "nation-a", SOURCE.id())));
            new RuleEngine(store, List.of(), Clock.fixed(COMMITTED, ZoneOffset.UTC)).accept(SOURCE, List.of());
            ContributionRequest original = request("same-operation", ContributionType.RESOURCE_AID, "nation-a", SOURCE.id());
            ledger.record(original);
            ContributionRequest conflict = new ContributionRequest(original.operationId(), original.type(), original.subjectId(),
                    original.sourceEventId(), original.actorId(), original.nationId(), new BigDecimal("13.500"), original.unit(),
                    original.committedAt(), original.evidence());
            assertThrows(IllegalStateException.class, () -> ledger.record(conflict));
        }
    }

    @Test void categoriesRemainSeparateAndSurviveRestart() {
        Path path = temp.resolve("restart.db");
        try (SqliteStore store = new SqliteStore(path)) {
            new RuleEngine(store, List.of(), Clock.fixed(COMMITTED, ZoneOffset.UTC)).accept(SOURCE, List.of());
            ContributionLedger ledger = new ContributionLedger(store, Clock.fixed(COMMITTED.plusSeconds(1), ZoneOffset.UTC));
            ledger.record(request("aid:1", ContributionType.RESOURCE_AID, "nation-a", SOURCE.id()));
            ledger.record(request("civ:1", ContributionType.CIVILIZATION, "region:old-capital", SOURCE.id()));
            assertEquals(new BigDecimal("12.5"), ledger.total("nation-a", ContributionType.RESOURCE_AID, "units"));
            assertEquals(BigDecimal.ZERO, ledger.total("nation-a", ContributionType.TRADE, "units"));
        }
        try (SqliteStore store = new SqliteStore(path)) {
            ContributionLedger ledger = new ContributionLedger(store, Clock.systemUTC());
            assertEquals(1, ledger.list(null, ContributionType.CIVILIZATION, 0, 10).size());
            assertTrue(ledger.find("aid:1", ContributionType.RESOURCE_AID, "nation-a").isPresent());
        }
    }
}
