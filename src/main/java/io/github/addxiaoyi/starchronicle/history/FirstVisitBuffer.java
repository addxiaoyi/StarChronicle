package io.github.addxiaoyi.starchronicle.history;

import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** Bounded owner-thread capture buffer. A failed worker or write never releases the first snapshot. */
public final class FirstVisitBuffer {
    public enum Admission { ADDED, ALREADY_PENDING, FULL }
    private final int capacity;
    private final Map<String,WorldEvent> pending=new LinkedHashMap<>();
    private boolean sealed;
    public FirstVisitBuffer(int capacity) {if(capacity<1)throw new IllegalArgumentException("Buffer capacity must be positive");this.capacity=capacity;}
    public synchronized Admission offer(WorldEvent event) {
        Objects.requireNonNull(event);
        if(sealed)return Admission.FULL;
        if(pending.containsKey(event.id()))return Admission.ALREADY_PENDING;
        if(pending.size()>=capacity)return Admission.FULL;
        pending.put(event.id(),event);return Admission.ADDED;
    }
    /** One state worker drains entries. SQL is executed outside the capture monitor. */
    public int drain(Consumer<WorldEvent> persist,int limit) {
        if(limit<1)throw new IllegalArgumentException("Drain limit must be positive");int count=0;
        while(count<limit) {
            WorldEvent event;
            synchronized(this){event=pending.isEmpty()?null:pending.values().iterator().next();}
            if(event==null)break;
            persist.accept(event);
            synchronized(this){pending.remove(event.id(),event);}
            count++;
        }
        return count;
    }
    public synchronized int size(){return pending.size();}
    public synchronized void seal(){sealed=true;}
}
