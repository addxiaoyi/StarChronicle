package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.NativeScene;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Coordinates one owned native operation per player until its durable result is known.
 * A lease contains no native executor: callers still dispatch through the player owner and
 * commit the journal before completing it. Reads share that committed result, not raw state. */
public final class NativeSceneFlights {
    private final ConcurrentMap<UUID,Lease> flights=new ConcurrentHashMap<>();

    public static final class Lease {
        private final UUID player;
        private final CompletableFuture<Optional<NativeScene>> result=new CompletableFuture<>();
        private final CompletionStage<Optional<NativeScene>> readOnly=result.minimalCompletionStage();
        private Lease(UUID player){this.player=player;}
        /** The observer cannot complete or cancel the coordinator's internal future. */
        public CompletionStage<Optional<NativeScene>> stage(){return readOnly;}
    }

    public Optional<Lease> acquire(UUID player) {
        Objects.requireNonNull(player);Lease lease=new Lease(player);
        return flights.putIfAbsent(player,lease)==null?Optional.of(lease):Optional.empty();
    }
    public Optional<CompletionStage<Optional<NativeScene>>> current(UUID player) {
        Objects.requireNonNull(player);Lease lease=flights.get(player);
        return lease==null?Optional.empty():Optional.of(lease.stage());
    }
    /** Immutable snapshot suitable for identifying journal leases that still have continuations. */
    public Set<UUID> players(){return Set.copyOf(flights.keySet());}

    /** Release before notifying observers so a successful continuation can acquire a new lease.
     * Only the exact current owner may publish a result; late callbacks are ignored. */
    public void complete(Lease lease,Optional<NativeScene> scene) {
        Objects.requireNonNull(lease);Objects.requireNonNull(scene);
        if(flights.remove(lease.player,lease))lease.result.complete(scene);
    }
    public void fail(Lease lease,Throwable error) {
        Objects.requireNonNull(lease);Objects.requireNonNull(error);
        if(flights.remove(lease.player,lease))lease.result.completeExceptionally(error);
    }
    /** Cancels the current snapshot of work without permanently closing the coordinator.
     * Exact-owner removal cannot erase a later operation created by a completion callback. */
    public void clear() {
        for(Lease lease:List.copyOf(flights.values()))
            fail(lease,new IllegalStateException("Native scene coordinator stopped"));
    }
    /** Waits only for success, then invokes the continuation once. The continuation must return
     * to the state worker to revalidate script/node and acquire its own lease before mutation. */
    public CompletionStage<Optional<NativeScene>> afterCurrent(UUID player,Supplier<CompletionStage<Optional<NativeScene>>> action) {
        Objects.requireNonNull(player);Objects.requireNonNull(action);Lease lease=flights.get(player);
        return lease==null?invoke(action):lease.stage().thenCompose(ignored->invoke(action));
    }
    private static CompletionStage<Optional<NativeScene>> invoke(Supplier<CompletionStage<Optional<NativeScene>>> action) {
        try{return Objects.requireNonNull(action.get(),"Native scene continuation returned no stage");}
        catch(Throwable error){return CompletableFuture.failedFuture(error);}
    }
}
