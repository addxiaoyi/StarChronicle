package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.PlayerContext;
import io.github.addxiaoyi.starchronicle.api.aid.*;
import io.github.addxiaoyi.starchronicle.api.contribution.ContributionType;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class NationalAidServiceTest {
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String PAYER = "00000000-0000-0000-0000-000000000011";
    private static final String RECEIVER = "00000000-0000-0000-0000-000000000012";
    private static final String DOMAIN = "00000000-0000-0000-0000-000000000099";
    private static final Instant NOW = Instant.parse("2026-11-21T00:00:00Z");

    @TempDir Path temp;

    private AidRequest request(String id) {
        return new AidRequest(id, "instance-aid", "source-aid", "heir-legacy", AidRequest.OPERATION,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", ACTOR,
                PAYER, RECEIVER, new BigDecimal("12.50"), NOW);
    }

    private PlayerContext actor() {
        return new PlayerContext(ACTOR, PAYER, Map.of(), Set.of(AidRequest.PERMISSION));
    }

    private static AidReceipt receipt(AidRequest request, String issuer, String domain) {
        return new AidReceipt(issuer, domain, request.outcomeId(), request.payerId(), request.receiverId(),
                request.amount(), "OTHER", request.description(), NOW.plusSeconds(1));
    }

    private static final class FakeProvider implements AidProvider {
        final AtomicInteger submits = new AtomicInteger();
        final AtomicInteger lookups = new AtomicInteger();
        AidObservation submit;
        AidObservation lookup = AidObservation.unknown("not reconciled");
        @Override public String providerId() { return "StarCore.Treasury"; }
        @Override public String authorityDomain() { return DOMAIN; }
        @Override public CompletableFuture<AidObservation> lookup(AidRequest request) { lookups.incrementAndGet(); return CompletableFuture.completedFuture(lookup); }
        @Override public CompletableFuture<AidObservation> submit(AidRequest request) { submits.incrementAndGet(); return CompletableFuture.completedFuture(submit); }
    }

    @Test void committedReceiptWritesHistoryAndSecondSubmitIsPurelyIdempotent() {
        FakeProvider provider = new FakeProvider();
        AidRequest request = request("aid:success");
        provider.submit = new AidObservation(AidObservation.Status.COMMITTED, receipt(request, provider.providerId(), DOMAIN), "committed");
        try (SqliteStore store = new SqliteStore(temp.resolve("success.db")); RuleEngine engine = new RuleEngine(store, List.of(), Clock.fixed(NOW, ZoneOffset.UTC))) {
            NationalAidService service = new NationalAidService(store, engine, provider, Clock.fixed(NOW, ZoneOffset.UTC));
            var first = service.submit(request, actor(), List.of()).toCompletableFuture().join();
            assertEquals(AidLedger.Status.COMMITTED, first.status());
            assertTrue(first.nativeInvoked());
            assertNotNull(first.contribution());
            assertEquals(1, provider.submits.get());
            assertEquals(1, engine.history(null, null, 0, 20).stream().filter(e -> e.id().equals(first.contribution().eventId())).count());
            var contributions = new ContributionLedger(store, Clock.fixed(NOW, ZoneOffset.UTC));
            assertEquals(new BigDecimal("12.5"), contributions.total("nation:" + PAYER, ContributionType.RESOURCE_AID, "treasury"));
            assertEquals(new BigDecimal("12.5"), contributions.total("player:" + ACTOR, ContributionType.RESOURCE_AID, "treasury"));

            var second = service.submit(request, actor(), List.of()).toCompletableFuture().join();
            assertEquals(AidLedger.Status.COMMITTED, second.status());
            assertFalse(second.nativeInvoked());
            assertEquals(1, provider.submits.get());
        }
    }

    @Test void mismatchedReceiptBecomesUnknownAndOnlyExplicitReconcileCanCommit() {
        FakeProvider provider = new FakeProvider();
        AidRequest request = request("aid:reconcile");
        AidReceipt wrong = new AidReceipt(provider.providerId(), DOMAIN, request.outcomeId(), request.payerId(), RECEIVER,
                new BigDecimal("99.00"), "OTHER", request.description(), NOW.plusSeconds(1));
        provider.submit = new AidObservation(AidObservation.Status.COMMITTED, wrong, "tampered");
        try (SqliteStore store = new SqliteStore(temp.resolve("reconcile.db")); RuleEngine engine = new RuleEngine(store, List.of(), Clock.fixed(NOW, ZoneOffset.UTC))) {
            NationalAidService service = new NationalAidService(store, engine, provider, Clock.fixed(NOW, ZoneOffset.UTC));
            var uncertain = service.submit(request, actor(), List.of()).toCompletableFuture().join();
            assertEquals(AidLedger.Status.UNKNOWN, uncertain.status());
            assertEquals(1, provider.submits.get());
            assertEquals(AidLedger.Status.UNKNOWN, service.submit(request, actor(), List.of()).toCompletableFuture().join().status());
            assertEquals(1, provider.submits.get(), "UNKNOWN submission must never be retried");

            provider.lookup = new AidObservation(AidObservation.Status.COMMITTED, receipt(request, provider.providerId(), DOMAIN), "found");
            var committed = service.reconcile(request, actor(), List.of()).toCompletableFuture().join();
            assertEquals(AidLedger.Status.COMMITTED, committed.status());
            assertEquals(1, provider.lookups.get());
            assertEquals(AidLedger.Status.COMMITTED, service.find(request.outcomeId()).orElseThrow().status());
        }
    }

    @Test void permissionAndNationMismatchRejectBeforeAdmission() {
        FakeProvider provider = new FakeProvider();
        try (SqliteStore store = new SqliteStore(temp.resolve("permission.db")); RuleEngine engine = new RuleEngine(store, List.of(), Clock.fixed(NOW, ZoneOffset.UTC))) {
            NationalAidService service = new NationalAidService(store, engine, provider, Clock.fixed(NOW, ZoneOffset.UTC));
            PlayerContext denied = new PlayerContext(ACTOR, PAYER, Map.of(), Set.of());
            assertThrows(SecurityException.class, () -> service.submit(request("aid:denied"), denied, List.of()));
            assertEquals(0, provider.submits.get());
            assertTrue(service.find("aid:denied").isEmpty());
        }
    }

    @Test void nativeRejectedRequiresDurableLookupBeforeTerminalRejection() {
        FakeProvider provider = new FakeProvider();
        AidRequest request = request("aid:rejected");
        provider.submit = new AidObservation(AidObservation.Status.REJECTED, null, "insufficient treasury");
        provider.lookup = new AidObservation(AidObservation.Status.ABSENT, null, "no committed receipt");
        try (SqliteStore store = new SqliteStore(temp.resolve("rejected.db")); RuleEngine engine = new RuleEngine(store, List.of(), Clock.fixed(NOW, ZoneOffset.UTC))) {
            NationalAidService service = new NationalAidService(store, engine, provider, Clock.fixed(NOW, ZoneOffset.UTC));
            var result = service.submit(request, actor(), List.of()).toCompletableFuture().join();
            assertEquals(AidLedger.Status.REJECTED, result.status());
            assertEquals(1, provider.submits.get());
            assertEquals(1, provider.lookups.get(), "A native rejection must be reconciled before it is terminal");
            assertEquals(AidLedger.Status.REJECTED, service.submit(request, actor(), List.of()).toCompletableFuture().join().status());
            assertEquals(1, provider.submits.get());
        }
    }

    @Test void nativeCompletionAndChronicleCommitRunOnConfiguredStateExecutor() throws Exception {
        ExecutorService state = Executors.newSingleThreadExecutor(task -> new Thread(task, "aid-state-test"));
        try {
            CompletableFuture<AidObservation> nativeResult = new CompletableFuture<>();
            CountDownLatch invoked = new CountDownLatch(1);
            AtomicReference<String> invocationThread = new AtomicReference<>();
            AidProvider provider = new AidProvider() {
                @Override public String providerId() { return "StarCore.Treasury"; }
                @Override public String authorityDomain() { return DOMAIN; }
                @Override public CompletableFuture<AidObservation> lookup(AidRequest request) {
                    return CompletableFuture.completedFuture(AidObservation.unknown("not found"));
                }
                @Override public CompletableFuture<AidObservation> submit(AidRequest request) {
                    invocationThread.set(Thread.currentThread().getName());
                    invoked.countDown();
                    return nativeResult;
                }
            };
            AidRequest request = request("aid:worker");
            try (SqliteStore store = new SqliteStore(temp.resolve("worker.db"));
                 RuleEngine engine = new RuleEngine(store, List.of(), Clock.fixed(NOW, ZoneOffset.UTC))) {
                NationalAidService service = new NationalAidService(store, engine, provider,
                        Clock.fixed(NOW, ZoneOffset.UTC), state);
                var result = service.submit(request, actor(), List.of()).thenApply(ignored -> Thread.currentThread().getName());
                assertTrue(invoked.await(3, TimeUnit.SECONDS));
                assertEquals("aid-state-test", invocationThread.get());
                Thread callback = new Thread(() -> nativeResult.complete(new AidObservation(
                        AidObservation.Status.COMMITTED, receipt(request, provider.providerId(), DOMAIN), "committed")),
                        "native-callback-test");
                callback.start();
                callback.join(3000);
                assertFalse(callback.isAlive());
                assertEquals("aid-state-test", result.toCompletableFuture().get(3, TimeUnit.SECONDS));
                assertEquals(AidLedger.Status.COMMITTED, service.find(request.outcomeId()).orElseThrow().status());
            }
        } finally {
            state.shutdownNow();
        }
    }
}
