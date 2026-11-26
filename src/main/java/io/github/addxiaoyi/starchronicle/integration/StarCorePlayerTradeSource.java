package io.github.addxiaoyi.starchronicle.integration;

import io.github.addxiaoyi.starchronicle.history.CommittedHistoryJournal;
import io.github.addxiaoyi.starchronicle.history.PlayerTradeEvidence;
import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import static io.github.addxiaoyi.starchronicle.integration.StarCoreBridge.call;

/** Never turns a legacy Fabric, mutable history row or unresolved wallet intent into a settlement. */
public final class StarCorePlayerTradeSource {
    public static final String SOURCE=PlayerTradeEvidence.SOURCE;
    private final Supplier<Object> service;
    public StarCorePlayerTradeSource(StarCoreBridge bridge){this(()->bridge.available()?bridge.service("module.resource.PlayerTradeRecoveryService"):null);}
    StarCorePlayerTradeSource(Supplier<Object> service){this.service=Objects.requireNonNull(service);}
    public boolean poll(CommittedHistoryJournal journal){
        if(!journal.pending(SOURCE,1).isEmpty())return false;
        Object provider=service.get();if(provider==null)return false;
        try{provider.getClass().getMethod("authorityDomain");provider.getClass().getMethod("settlementsAfter",UUID.class,long.class,int.class);}
        catch(NoSuchMethodException unavailable){return false;}
        try{
            var authority=domain(provider);if(authority.isEmpty())return false;
            UUID domain=authority.orElseThrow();var cursor=journal.cursor(SOURCE);
            if(cursor.isPresent()&&!cursor.orElseThrow().domain().equals(domain))throw new IllegalStateException("Player trade authority changed; reconciliation required");
            long after=cursor.map(CommittedHistoryJournal.Cursor::sequence).orElse(0L);
            Object response=call(provider,"settlementsAfter",domain,after,100);
            if(!(response instanceof List<?> rows)||rows.size()>100)throw new IllegalArgumentException("Invalid player trade page");
            List<CommittedHistoryJournal.Entry> entries=new ArrayList<>();
            for(Object row:rows)entries.add(convert(domain,row));
            if(service.get()!=provider||!authority.equals(domain(provider)))throw new IllegalStateException("Player trade provider changed during read");
            journal.accept(SOURCE,domain,after,entries);return true;
        }catch(InvocationTargetException failure){
            if(failure.getCause() instanceof UnsupportedOperationException)return false;
            throw new IllegalStateException("Native player trade history read failed",failure.getCause());
        }catch(ReflectiveOperationException failure){throw new IllegalStateException("Native player trade protocol mismatch",failure);}
    }
    static CommittedHistoryJournal.Entry convert(UUID domain,Object receipt)throws ReflectiveOperationException{
        if(!domain.equals(uuid(call(receipt,"authorityDomain"))))throw new IllegalArgumentException("Foreign player trade authority");
        Object s=Objects.requireNonNull(call(receipt,"settlement"));
        if(!"SETTLED".equals(String.valueOf(call(s,"state"))))throw new IllegalArgumentException("Unsettled player trade");
        Object t=Objects.requireNonNull(call(s,"terms"));Object unit=call(t,"unitPrice");
        if(!(unit instanceof BigDecimal price))throw new IllegalArgumentException("Expected exact frozen unit price");
        var proof=new PlayerTradeEvidence(domain,number(call(receipt,"sequence")),uuid(call(t,"id")),uuid(call(t,"seller")),uuid(call(t,"buyer")),
                Objects.requireNonNull(call(t,"sellerNation")).toString(),Objects.requireNonNull(call(t,"buyerNation")).toString(),
                uuid(call(t,"resourceDomain")),uuid(call(t,"treasuryDomain")),text(call(t,"provider")),text(call(t,"resource")),
                number(call(t,"amount")),price,number(call(t,"feeMinor")),number(call(t,"tariffMinor")),
                instant(call(t,"createdAt")),instant(call(t,"expiresAt")),instant(call(s,"acceptedAt")),instant(call(s,"settledAt")));
        return new CommittedHistoryJournal.Entry(proof.sequence(),proof.event());
    }
    private static Optional<UUID> domain(Object service)throws ReflectiveOperationException{
        Object result=call(service,"authorityDomain");if(!(result instanceof Optional<?> value))throw new IllegalArgumentException("Invalid player trade capability");
        return value.map(StarCorePlayerTradeSource::uuid);
    }
    private static long number(Object value){if(!(value instanceof Long n))throw new IllegalArgumentException("Expected persisted player trade integer");return n;}
    private static UUID uuid(Object value){if(!(value instanceof UUID id))throw new IllegalArgumentException("Expected player trade UUID");return id;}
    private static String text(Object value){if(!(value instanceof String text))throw new IllegalArgumentException("Expected player trade text");return text;}
    private static Instant instant(Object value){if(!(value instanceof Instant time))throw new IllegalArgumentException("Expected player trade timestamp");return time;}
}
