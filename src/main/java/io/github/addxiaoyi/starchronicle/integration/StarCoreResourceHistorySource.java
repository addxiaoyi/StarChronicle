package io.github.addxiaoyi.starchronicle.integration;

import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import io.github.addxiaoyi.starchronicle.core.RuleEngine;
import io.github.addxiaoyi.starchronicle.history.CommittedHistoryJournal;
import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import static io.github.addxiaoyi.starchronicle.integration.StarCoreBridge.call;
import static io.github.addxiaoyi.starchronicle.persistence.SqliteStore.json;

/** Committed transfers between national stockpiles; the native receipt does not identify an actor. */
public final class StarCoreResourceHistorySource {
    public static final String SOURCE="starcore.resource.transfers.v1";
    private final Supplier<Object> service;
    public StarCoreResourceHistorySource(StarCoreBridge bridge){this(()->bridge.available()?bridge.service("module.resource.NationResourceTransactionService"):null);}
    StarCoreResourceHistorySource(Supplier<Object> service){this.service=Objects.requireNonNull(service);}

    public boolean poll(CommittedHistoryJournal journal){
        if(!journal.pending(SOURCE,1).isEmpty())return false;
        Object nativeService=service.get();if(nativeService==null)return false;
        try {
            nativeService.getClass().getMethod("transferHistoryAuthorityDomainId");
            nativeService.getClass().getMethod("transferReceiptsAfter",long.class,int.class);
        }catch(NoSuchMethodException unavailable){return false;}
        try {
            Optional<UUID> authority=domain(nativeService);if(authority.isEmpty())return false;
            UUID domain=authority.orElseThrow();var cursor=journal.cursor(SOURCE);
            if(cursor.isPresent()&&!cursor.get().domain().equals(domain))throw new IllegalStateException("Native resource history domain changed; reconciliation required");
            long after=cursor.map(CommittedHistoryJournal.Cursor::sequence).orElse(0L);
            Object response=call(nativeService,"transferReceiptsAfter",after,100);
            if(!(response instanceof List<?> rows)||rows.size()>100)throw new IllegalArgumentException("Invalid native resource transfer page");
            List<CommittedHistoryJournal.Entry> entries=new ArrayList<>();
            for(Object row:rows)entries.add(convert(domain,row));
            if(service.get()!=nativeService||!authority.equals(domain(nativeService)))throw new IllegalStateException("Resource history provider/domain changed during read");
            journal.accept(SOURCE,domain,after,entries);return true;
        }catch(InvocationTargetException failure){
            if(failure.getCause() instanceof UnsupportedOperationException)return false;
            throw new IllegalStateException("Native resource transfer history read failed",failure.getCause());
        }catch(ReflectiveOperationException failure){throw new IllegalStateException("Native resource transfer history protocol mismatch",failure);}
    }

    static CommittedHistoryJournal.Entry convert(UUID domain,Object receipt)throws ReflectiveOperationException {
        if(!domain.toString().equals(uuidString(call(receipt,"authorityDomainId"))))throw new IllegalArgumentException("Resource receipt has another authority domain");
        Object sequenceValue=call(receipt,"sequence");
        if(!(sequenceValue instanceof Long sequence)||sequence<1)throw new IllegalArgumentException("Invalid resource transfer sequence");
        String transaction=text(call(receipt,"transactionId"),4096,false);
        String payer=uuidString(Objects.requireNonNull(call(receipt,"payerNationId")).toString());
        String receiver=uuidString(Objects.requireNonNull(call(receipt,"receiverNationId")).toString());
        if(payer.equals(receiver))throw new IllegalArgumentException("Resource transfer must have distinct nations");
        Object quantities=call(receipt,"resources");
        if(!(quantities instanceof Map<?,?> amounts)||amounts.isEmpty())throw new IllegalArgumentException("Invalid resource transfer quantities");
        Map<String,Long> resources=new TreeMap<>();
        for(var entry:amounts.entrySet()){
            if(!(entry.getKey() instanceof String key)||key.isBlank()||!key.equals(key.trim().toLowerCase(Locale.ROOT))||!(entry.getValue() instanceof Long amount)||amount<=0)
                throw new IllegalArgumentException("Invalid resource transfer quantity");
            resources.put(key,amount);
        }
        String category=call(receipt,"category").toString();
        if(!Set.of("RESEARCH","COMPENSATION_REFUND","OTHER").contains(category))throw new IllegalArgumentException("Unknown resource transfer category");
        String description=text(call(receipt,"description"),200,true);
        if(!(call(receipt,"appliedAt") instanceof Instant committed))throw new IllegalArgumentException("Invalid resource commit timestamp");
        Map<String,String> facts=Map.ofEntries(
                Map.entry("title","国家资源转移已提交"),Map.entry("time_basis","COMMITTED"),
                Map.entry("source.authority_domain",domain.toString()),Map.entry("source.sequence",Long.toString(sequence)),
                Map.entry("resource.transaction_id",transaction),Map.entry("resource.payer",payer),Map.entry("resource.receiver",receiver),
                Map.entry("resource.quantities",json(resources)),Map.entry("resource.category",category),
                Map.entry("resource.description_sha256",RuleEngine.stable(description)),Map.entry("resource.description_length",Integer.toString(description.length())),
                Map.entry("resource.actor","NOT_RECORDED"),Map.entry("resource.scope","NATION_STOCKPILE_TRANSFER"));
        String evidence="Native NationResourceTransactionService committed transfer; domain="+domain+"; sequence="+sequence+"; transaction_sha256="+RuleEngine.stable(transaction)+"; payer="+payer+"; receiver="+receiver+"; stockpiles, receipt and journal head share a commit; actor is not recorded; category is request metadata; grant/consume and player trade are separate sources";
        WorldEvent event=new WorldEvent("starcore:resource-transfer-history:"+domain+":"+sequence,"NATIONAL_RESOURCE_TRANSFER_COMMITTED",committed,Set.of(),Set.of(payer,receiver),facts,"StarCore.NationResourceTransactionService",evidence);
        return new CommittedHistoryJournal.Entry(sequence,event);
    }

    private static Optional<UUID> domain(Object service)throws ReflectiveOperationException {
        Object value=call(service,"transferHistoryAuthorityDomainId");
        if(!(value instanceof Optional<?> optional))throw new IllegalArgumentException("Invalid resource history capability");
        return optional.map(id->{if(!(id instanceof UUID uuid))throw new IllegalArgumentException("Expected resource history UUID");return uuid;});
    }
    private static String uuidString(Object value){if(!(value instanceof String id)||!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException("Invalid resource identity");return id;}
    private static String text(Object value,int limit,boolean empty){
        if(!(value instanceof String text)||text.length()>limit||(!empty&&text.isBlank()))throw new IllegalArgumentException("Invalid resource metadata");return text;
    }
}
