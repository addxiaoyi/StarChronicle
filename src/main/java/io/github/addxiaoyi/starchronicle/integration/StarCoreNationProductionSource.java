package io.github.addxiaoyi.starchronicle.integration;

import io.github.addxiaoyi.starchronicle.history.CommittedHistoryJournal;
import io.github.addxiaoyi.starchronicle.history.NationProductionEvidence;
import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import static io.github.addxiaoyi.starchronicle.integration.StarCoreBridge.call;

/** Only the native committed production capability can supply civilization contribution evidence. */
public final class StarCoreNationProductionSource {
    public static final String SOURCE=NationProductionEvidence.SOURCE;
    private final Supplier<Object> service;
    public StarCoreNationProductionSource(StarCoreBridge bridge){this(()->bridge.available()?bridge.service("module.resource.NationProductionService"):null);}
    StarCoreNationProductionSource(Supplier<Object> service){this.service=Objects.requireNonNull(service);}

    public boolean poll(CommittedHistoryJournal journal){
        if(!journal.pending(SOURCE,1).isEmpty())return false;
        Object provider=service.get();if(provider==null)return false;
        try{provider.getClass().getMethod("completionAuthorityDomain");provider.getClass().getMethod("completionsAfter",UUID.class,long.class,int.class);}
        catch(NoSuchMethodException unavailable){return false;}
        try{
            var authority=domain(provider);if(authority.isEmpty())return false;
            UUID domain=authority.orElseThrow();var cursor=journal.cursor(SOURCE);
            if(cursor.isPresent()&&!cursor.orElseThrow().domain().equals(domain))throw new IllegalStateException("National production authority changed; reconciliation required");
            long after=cursor.map(CommittedHistoryJournal.Cursor::sequence).orElse(0L);
            Object response=call(provider,"completionsAfter",domain,after,100);
            if(!(response instanceof List<?> rows)||rows.size()>100)throw new IllegalArgumentException("Invalid national production page");
            List<CommittedHistoryJournal.Entry> entries=new ArrayList<>();
            for(Object row:rows)entries.add(convert(domain,row));
            if(service.get()!=provider||!authority.equals(domain(provider)))throw new IllegalStateException("National production provider changed during read");
            journal.accept(SOURCE,domain,after,entries);return true;
        }catch(InvocationTargetException failure){
            if(failure.getCause() instanceof UnsupportedOperationException)return false;
            throw new IllegalStateException("Native national production history read failed",failure.getCause());
        }catch(ReflectiveOperationException failure){throw new IllegalStateException("Native national production protocol mismatch",failure);}
    }

    static CommittedHistoryJournal.Entry convert(UUID domain,Object receipt)throws ReflectiveOperationException{
        if(!domain.equals(uuid(call(receipt,"authorityDomain"))))throw new IllegalArgumentException("Foreign national production authority");
        Object run=Objects.requireNonNull(call(receipt,"run"));
        var proof=new NationProductionEvidence(domain,number(call(receipt,"sequence")),uuid(call(run,"runId")),uuid(call(run,"actorId")),
                Objects.requireNonNull(call(run,"nationId")).toString(),uuid(call(run,"resourceDomain")),text(call(run,"recipeId")),
                number(call(run,"batches")),quantities(call(run,"inputs")),quantities(call(run,"outputs")),
                instant(call(run,"startedAt")),instant(call(run,"readyAt")),instant(call(receipt,"committedAt")));
        return new CommittedHistoryJournal.Entry(proof.sequence(),proof.event());
    }
    private static Optional<UUID> domain(Object service)throws ReflectiveOperationException{
        Object result=call(service,"completionAuthorityDomain");if(!(result instanceof Optional<?> value))throw new IllegalArgumentException("Invalid national production capability");
        return value.map(StarCoreNationProductionSource::uuid);
    }
    private static Map<String,Long> quantities(Object value){
        if(!(value instanceof Map<?,?> source)||source.size()>7)throw new IllegalArgumentException("Invalid production resource map");
        var result=new TreeMap<String,Long>();source.forEach((key,amount)->result.put(text(key),number(amount)));return result;
    }
    private static long number(Object value){if(!(value instanceof Long n))throw new IllegalArgumentException("Expected persisted trade integer");return n;}
    private static UUID uuid(Object value){if(!(value instanceof UUID id))throw new IllegalArgumentException("Expected trade UUID");return id;}
    private static String text(Object value){if(!(value instanceof String text))throw new IllegalArgumentException("Expected trade text");return text;}
    private static Instant instant(Object value){if(!(value instanceof Instant time))throw new IllegalArgumentException("Expected trade timestamp");return time;}
}
