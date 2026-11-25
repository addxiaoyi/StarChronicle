package io.github.addxiaoyi.starchronicle.integration;

import io.github.addxiaoyi.starchronicle.history.CommittedHistoryJournal;
import io.github.addxiaoyi.starchronicle.history.NationTradeEvidence;
import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import static io.github.addxiaoyi.starchronicle.integration.StarCoreBridge.call;

/** Only the native atomic national-sale capability can supply trade contribution evidence. */
public final class StarCoreNationTradeSource {
    public static final String SOURCE=NationTradeEvidence.SOURCE;
    private final Supplier<Object> service;
    public StarCoreNationTradeSource(StarCoreBridge bridge){this(()->bridge.available()?bridge.service("module.resource.NationTradeService"):null);}
    StarCoreNationTradeSource(Supplier<Object> service){this.service=Objects.requireNonNull(service);}

    public boolean poll(CommittedHistoryJournal journal){
        if(!journal.pending(SOURCE,1).isEmpty())return false;
        Object provider=service.get();if(provider==null)return false;
        try{provider.getClass().getMethod("settlementAuthorityDomain");provider.getClass().getMethod("settlementsAfter",UUID.class,long.class,int.class);}
        catch(NoSuchMethodException unavailable){return false;}
        try{
            var authority=domain(provider);if(authority.isEmpty())return false;
            UUID domain=authority.orElseThrow();var cursor=journal.cursor(SOURCE);
            if(cursor.isPresent()&&!cursor.orElseThrow().domain().equals(domain))throw new IllegalStateException("National trade authority changed; reconciliation required");
            long after=cursor.map(CommittedHistoryJournal.Cursor::sequence).orElse(0L);
            Object response=call(provider,"settlementsAfter",domain,after,100);
            if(!(response instanceof List<?> rows)||rows.size()>100)throw new IllegalArgumentException("Invalid national trade page");
            List<CommittedHistoryJournal.Entry> entries=new ArrayList<>();
            for(Object row:rows)entries.add(convert(domain,row));
            if(service.get()!=provider||!authority.equals(domain(provider)))throw new IllegalStateException("National trade provider changed during read");
            journal.accept(SOURCE,domain,after,entries);return true;
        }catch(InvocationTargetException failure){
            if(failure.getCause() instanceof UnsupportedOperationException)return false;
            throw new IllegalStateException("Native national trade history read failed",failure.getCause());
        }catch(ReflectiveOperationException failure){throw new IllegalStateException("Native national trade protocol mismatch",failure);}
    }

    static CommittedHistoryJournal.Entry convert(UUID domain,Object receipt)throws ReflectiveOperationException{
        if(!domain.equals(uuid(call(receipt,"authorityDomain"))))throw new IllegalArgumentException("Foreign national trade authority");
        Object offer=Objects.requireNonNull(call(receipt,"offer"));
        var proof=new NationTradeEvidence(domain,number(call(receipt,"sequence")),uuid(call(offer,"offerId")),uuid(call(offer,"sellerId")),uuid(call(offer,"buyerId")),
                Objects.requireNonNull(call(offer,"sellerNationId")).toString(),Objects.requireNonNull(call(offer,"buyerNationId")).toString(),text(call(offer,"resourceId")),
                number(call(offer,"amount")),number(call(offer,"unitPriceMinor")),NationTradeEvidence.canonicalUuid(text(call(offer,"resourceDomain"))),
                NationTradeEvidence.canonicalUuid(text(call(offer,"treasuryDomain"))),number(call(receipt,"resourceTransferSequence")),
                instant(call(offer,"createdAt")),instant(call(offer,"expiresAt")),instant(call(receipt,"committedAt")));
        return new CommittedHistoryJournal.Entry(proof.sequence(),proof.event());
    }
    private static Optional<UUID> domain(Object service)throws ReflectiveOperationException{
        Object result=call(service,"settlementAuthorityDomain");if(!(result instanceof Optional<?> value))throw new IllegalArgumentException("Invalid national trade capability");
        return value.map(StarCoreNationTradeSource::uuid);
    }
    private static long number(Object value){if(!(value instanceof Long n))throw new IllegalArgumentException("Expected persisted trade integer");return n;}
    private static UUID uuid(Object value){if(!(value instanceof UUID id))throw new IllegalArgumentException("Expected trade UUID");return id;}
    private static String text(Object value){if(!(value instanceof String text))throw new IllegalArgumentException("Expected trade text");return text;}
    private static Instant instant(Object value){if(!(value instanceof Instant time))throw new IllegalArgumentException("Expected trade timestamp");return time;}
}
