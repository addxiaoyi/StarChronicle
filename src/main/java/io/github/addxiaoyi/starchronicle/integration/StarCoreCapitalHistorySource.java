package io.github.addxiaoyi.starchronicle.integration;

import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import io.github.addxiaoyi.starchronicle.history.CommittedHistoryJournal;
import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import static io.github.addxiaoyi.starchronicle.integration.StarCoreBridge.call;

/** Exact designated-capital commits. Derived city levels and cached locations are not sources. */
public final class StarCoreCapitalHistorySource {
    public static final String SOURCE="starcore.capital.designations.v1";
    private final Supplier<Object> service;
    public StarCoreCapitalHistorySource(StarCoreBridge bridge){this(()->bridge.available()?bridge.service("module.nation.NationService"):null);}
    StarCoreCapitalHistorySource(Supplier<Object> service){this.service=Objects.requireNonNull(service);}

    public boolean poll(CommittedHistoryJournal journal){
        if(!journal.pending(SOURCE,1).isEmpty())return false;
        Object nativeService=service.get();if(nativeService==null)return false;
        try {nativeService.getClass().getMethod("capitalHistoryAfter",long.class,int.class);}
        catch(NoSuchMethodException unsupported){return false;}
        try {
            var cursor=journal.cursor(SOURCE);long after=cursor.map(CommittedHistoryJournal.Cursor::sequence).orElse(0L);
            Object response=call(nativeService,"capitalHistoryAfter",after,100);
            if(!(response instanceof Optional<?> capability))throw new IllegalArgumentException("Invalid capital history capability");
            if(capability.isEmpty())return false;
            Object page=capability.orElseThrow();UUID domain=uuid(call(page,"authorityDomainId"));
            if(cursor.isPresent()&&!cursor.get().domain().equals(domain))throw new IllegalStateException("Native capital history domain changed; reconciliation required");
            Object headValue=call(page,"latestSequence");
            if(!(headValue instanceof Long head)||head<after)throw new IllegalStateException("Native capital history head is behind the consumer cursor");
            Object values=call(page,"entries");
            if(!(values instanceof List<?> rows)||rows.size()!=Math.min(100L,head-after))throw new IllegalArgumentException("Incomplete or oversized capital history page");
            List<CommittedHistoryJournal.Entry> entries=new ArrayList<>();long expected=after;
            for(Object row:rows){
                var entry=convert(domain,row);
                if(entry.sequence()!=Math.addExact(expected,1L))throw new IllegalArgumentException("Noncontiguous capital history page");
                expected=entry.sequence();entries.add(entry);
            }
            if(service.get()!=nativeService)throw new IllegalStateException("Capital history provider changed during read");
            journal.accept(SOURCE,domain,after,entries);return true;
        }catch(InvocationTargetException failure){
            if(failure.getCause() instanceof UnsupportedOperationException)return false;
            throw new IllegalStateException("Native capital history read failed",failure.getCause());
        }catch(ReflectiveOperationException failure){throw new IllegalStateException("Native capital history protocol mismatch",failure);}
    }

    static CommittedHistoryJournal.Entry convert(UUID domain,Object row)throws ReflectiveOperationException {
        Object sequenceValue=call(row,"sequence");
        if(!(sequenceValue instanceof Long sequence)||sequence<1L)throw new IllegalArgumentException("Invalid capital designation sequence");
        UUID operation=uuid(call(row,"operationId")),city=uuid(call(row,"cityId")),actor=uuid(call(row,"actorId"));
        Object nationValue=call(row,"nationId");
        if(nationValue==null)throw new IllegalArgumentException("Missing capital nation");
        String nation=nationValue.toString();
        if(!UUID.fromString(nation).toString().equals(nation))throw new IllegalArgumentException("Invalid capital nation");
        Object previousValue=call(row,"previousCityId");UUID previous=previousValue==null?null:uuid(previousValue);
        if(city.equals(previous))throw new IllegalArgumentException("Capital designation did not change city");
        if(!(call(row,"committedAt") instanceof Instant committed))throw new IllegalArgumentException("Missing capital commit time");
        String kind=previous==null?"DESIGNATED":"MOVED";
        Map<String,String> facts=Map.ofEntries(
                Map.entry("title",previous==null?"国家首次指定首都":"国家迁都"),Map.entry("time_basis","COMMITTED"),
                Map.entry("source.authority_domain",domain.toString()),Map.entry("source.sequence",Long.toString(sequence)),
                Map.entry("capital.operation_id",operation.toString()),Map.entry("capital.nation",nation),
                Map.entry("capital.previous_city",previous==null?"NONE":previous.toString()),Map.entry("capital.city",city.toString()),
                Map.entry("capital.actor",actor.toString()),Map.entry("capital.kind",kind));
        String evidence="Native NationService committed capital designation; domain="+domain+"; sequence="+sequence
                +"; operation="+operation+"; nation="+nation+"; previous="+(previous==null?"NONE":previous)+"; city="+city
                +"; actor="+actor+"; designation and history share the published log head; current rulers and derived locations are not substituted";
        var event=new WorldEvent("starcore:capital-history:"+domain+":"+sequence,"CAPITAL_"+kind,committed,
                Set.of(actor),Set.of(nation),facts,"StarCore.NationService",evidence);
        return new CommittedHistoryJournal.Entry(sequence,event);
    }

    private static UUID uuid(Object value){if(!(value instanceof UUID id))throw new IllegalArgumentException("Invalid capital identity");return id;}
}
