package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.NativeScene;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeSceneFlightsTest {
    final UUID player=UUID.fromString("00000000-0000-0000-0000-000000000011");
    final UUID other=UUID.fromString("00000000-0000-0000-0000-000000000012");
    Optional<NativeScene> scene(String node){return Optional.of(new NativeScene("chronicle_last_heir",node,"Owned","Committed "+node,List.of()));}

    @Test void concurrentAdmissionHasExactlyOneOwnerAndReadsRemainPendingUntilCompletion() throws Exception {
        var flights=new NativeSceneFlights();CountDownLatch start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Optional<NativeSceneFlights.Lease>> acquire=()->{assertTrue(start.await(5,TimeUnit.SECONDS));return flights.acquire(player);};
            Future<Optional<NativeSceneFlights.Lease>> first=pool.submit(acquire),second=pool.submit(acquire);start.countDown();
            var a=first.get(5,TimeUnit.SECONDS);var b=second.get(5,TimeUnit.SECONDS);assertNotEquals(a.isPresent(),b.isPresent());
            var lease=a.orElseGet(b::orElseThrow);var read=flights.current(player).orElseThrow();
            assertFalse(read.toCompletableFuture().isDone());assertEquals(Set.of(player),flights.players());
            flights.complete(lease,scene("arrival"));assertEquals(scene("arrival"),read.toCompletableFuture().get(5,TimeUnit.SECONDS));
            assertTrue(flights.current(player).isEmpty());assertTrue(flights.players().isEmpty());
        }
    }

    @Test void asynchronousContinuationStartsOnlyAfterCommitAndCanAcquireTheReleasedPlayer() throws Exception {
        var flights=new NativeSceneFlights();var first=flights.acquire(player).orElseThrow();
        AtomicReference<NativeSceneFlights.Lease> next=new AtomicReference<>();AtomicInteger calls=new AtomicInteger();
        CompletionStage<Optional<NativeScene>> action=flights.afterCurrent(player,()->{
            assertTrue(flights.current(player).isEmpty(),"Owner must be released before continuation notification");
            calls.incrementAndGet();next.set(flights.acquire(player).orElseThrow());return next.get().stage();
        });
        CountDownLatch dispatched=new CountDownLatch(1),commit=new CountDownLatch(1);
        try(var pool=Executors.newSingleThreadExecutor()) {
            Future<?> completion=pool.submit(()->{dispatched.countDown();try{assertTrue(commit.await(5,TimeUnit.SECONDS));}catch(InterruptedException error){Thread.currentThread().interrupt();throw new AssertionError(error);}flights.complete(first,scene("arrival"));});
            assertTrue(dispatched.await(5,TimeUnit.SECONDS));assertEquals(0,calls.get());assertNull(next.get());
            assertFalse(action.toCompletableFuture().isDone());commit.countDown();completion.get(5,TimeUnit.SECONDS);
            assertEquals(1,calls.get());assertNotNull(next.get());assertFalse(action.toCompletableFuture().isDone());
            flights.complete(next.get(),scene("evidence"));assertEquals(scene("evidence"),action.toCompletableFuture().get(5,TimeUnit.SECONDS));
        }
    }

    @Test void onePlayersCompletionDoesNotReleaseOrCompleteAnotherPlayersFlight() {
        var flights=new NativeSceneFlights();var a=flights.acquire(player).orElseThrow();var b=flights.acquire(other).orElseThrow();
        var waiting=flights.current(other).orElseThrow();Set<UUID> before=flights.players();
        flights.complete(a,scene("arrival"));assertEquals(Set.of(other),flights.players());assertEquals(Set.of(player,other),before);
        assertThrows(UnsupportedOperationException.class,()->before.clear());assertFalse(waiting.toCompletableFuture().isDone());
        assertTrue(flights.acquire(other).isEmpty());flights.complete(b,Optional.empty());assertEquals(Optional.empty(),waiting.toCompletableFuture().join());
    }

    @Test void failedFlightPropagatesWithoutCallingOrRetryingTheQueuedAction() {
        var flights=new NativeSceneFlights();var lease=flights.acquire(player).orElseThrow();AtomicInteger calls=new AtomicInteger();
        var waiting=flights.afterCurrent(player,()->{calls.incrementAndGet();return CompletableFuture.completedFuture(scene("forbidden"));});
        var failure=new IllegalStateException("Journal checkpoint did not commit");flights.fail(lease,failure);
        assertSame(failure,assertThrows(CompletionException.class,()->waiting.toCompletableFuture().join()).getCause());
        assertEquals(0,calls.get());assertTrue(flights.players().isEmpty());assertTrue(flights.acquire(player).isPresent());
    }

    @Test void repeatedOldCompletionCannotRemoveOrPublishOverANewOwner() {
        var flights=new NativeSceneFlights();var old=flights.acquire(player).orElseThrow();flights.complete(old,scene("arrival"));
        var next=flights.acquire(player).orElseThrow();var current=flights.current(player).orElseThrow();
        flights.complete(old,scene("obsolete"));flights.fail(old,new IllegalStateException("obsolete error"));
        assertEquals(Set.of(player),flights.players());assertTrue(flights.acquire(player).isEmpty());assertFalse(current.toCompletableFuture().isDone());
        assertEquals(scene("arrival"),old.stage().toCompletableFuture().join());flights.complete(next,scene("evidence"));
        assertEquals(scene("evidence"),current.toCompletableFuture().join());assertTrue(flights.players().isEmpty());
    }

    @Test void clearFailsEveryWaiterAndOldCallbacksCannotEraseAnOperationAdmittedAfterward() {
        var flights=new NativeSceneFlights();var a=flights.acquire(player).orElseThrow();var b=flights.acquire(other).orElseThrow();AtomicInteger calls=new AtomicInteger();
        var waiting=flights.afterCurrent(player,()->{calls.incrementAndGet();return CompletableFuture.completedFuture(scene("forbidden"));});
        flights.clear();assertTrue(flights.players().isEmpty());assertEquals(0,calls.get());
        assertInstanceOf(IllegalStateException.class,assertThrows(CompletionException.class,()->waiting.toCompletableFuture().join()).getCause());
        assertInstanceOf(IllegalStateException.class,assertThrows(CompletionException.class,()->b.stage().toCompletableFuture().join()).getCause());
        var next=flights.acquire(player).orElseThrow();flights.complete(a,scene("obsolete"));flights.fail(a,new IllegalStateException("late"));
        assertEquals(Set.of(player),flights.players());assertFalse(next.stage().toCompletableFuture().isDone());
        flights.complete(next,Optional.empty());assertEquals(Optional.empty(),next.stage().toCompletableFuture().join());
    }

    @Test void observerCompletionOrCancellationCannotMutateTheOwnersSharedResult() {
        var flights=new NativeSceneFlights();var lease=flights.acquire(player).orElseThrow();
        var cancelled=flights.current(player).orElseThrow().toCompletableFuture();var forged=lease.stage().toCompletableFuture();
        var observer=flights.current(player).orElseThrow().toCompletableFuture();assertTrue(cancelled.cancel(false));assertTrue(forged.complete(scene("forged")));
        assertFalse(observer.isDone());assertTrue(flights.acquire(player).isEmpty());
        flights.complete(lease,scene("evidence"));assertEquals(scene("evidence"),observer.join());
        assertEquals(scene("evidence"),lease.stage().toCompletableFuture().join());
    }

    @Test void noCurrentFlightInvokesTheSupplierImmediatelyAndOnlyOnce() {
        var flights=new NativeSceneFlights();AtomicInteger calls=new AtomicInteger();var supplied=new CompletableFuture<Optional<NativeScene>>();
        var result=flights.afterCurrent(player,()->{calls.incrementAndGet();return supplied;});
        assertEquals(1,calls.get());assertTrue(flights.players().isEmpty());assertFalse(result.toCompletableFuture().isDone());
        supplied.complete(scene("arrival"));assertEquals(scene("arrival"),result.toCompletableFuture().join());assertEquals(1,calls.get());
    }

    @Test void immediateSupplierThrowOrNullBecomesAFailedStageWithoutRetry() {
        var flights=new NativeSceneFlights();AtomicInteger calls=new AtomicInteger();var failure=new IllegalArgumentException("invalid expected node");
        var thrown=assertDoesNotThrow(()->flights.afterCurrent(player,()->{calls.incrementAndGet();throw failure;}));
        assertSame(failure,assertThrows(CompletionException.class,()->thrown.toCompletableFuture().join()).getCause());assertEquals(1,calls.get());
        var absent=assertDoesNotThrow(()->flights.afterCurrent(player,()->null));
        assertInstanceOf(NullPointerException.class,assertThrows(CompletionException.class,()->absent.toCompletableFuture().join()).getCause());
    }

    @Test void deferredSupplierThrowOrNullStillFailsOnlyAfterSuccessfulPrecedingOperation() {
        var flights=new NativeSceneFlights();var first=flights.acquire(player).orElseThrow();AtomicInteger calls=new AtomicInteger();
        var failure=new IllegalStateException("stale node after preceding result");
        var thrown=flights.afterCurrent(player,()->{calls.incrementAndGet();throw failure;});var absent=flights.afterCurrent(player,()->null);
        assertEquals(0,calls.get());assertFalse(thrown.toCompletableFuture().isDone());assertFalse(absent.toCompletableFuture().isDone());
        flights.complete(first,scene("evidence"));assertEquals(1,calls.get());
        assertSame(failure,assertThrows(CompletionException.class,()->thrown.toCompletableFuture().join()).getCause());
        assertInstanceOf(NullPointerException.class,assertThrows(CompletionException.class,()->absent.toCompletableFuture().join()).getCause());
    }
}
