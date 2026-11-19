package io.github.addxiaoyi.starchronicle.core;

import java.util.concurrent.atomic.AtomicInteger;

/** Atomic admission boundary shared by an entity task and its timeout/retirement observer.
 * Cancellation wins only before entry; once entered, a missing result is always uncertain. */
public final class InvocationGate {
    private final AtomicInteger state=new AtomicInteger(); // 0 queued, 1 entered, 2 cancelled
    public boolean enter(){return state.compareAndSet(0,1);}
    public boolean cancelBeforeEntry(){return state.compareAndSet(0,2)||state.get()==2;}
}
