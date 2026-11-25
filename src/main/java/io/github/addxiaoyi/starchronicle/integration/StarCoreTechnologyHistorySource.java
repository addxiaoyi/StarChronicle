package io.github.addxiaoyi.starchronicle.integration;

import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import io.github.addxiaoyi.starchronicle.history.CommittedHistoryJournal;
import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import static io.github.addxiaoyi.starchronicle.integration.StarCoreBridge.call;

/** Committed nation unlock/revoke facts; neither a researcher nor research completion is inferred. */
public final class StarCoreTechnologyHistorySource {
    public static final String SOURCE="starcore.technology.history.v1";
    private final Supplier<Object> service;
    public StarCoreTechnologyHistorySource(StarCoreBridge bridge){this(()->bridge.available()?bridge.service("module.technology.TechnologyService"):null);}
    StarCoreTechnologyHistorySource(Supplier<Object> service){this.service=Objects.requireNonNull(service);}

    public boolean poll(CommittedHistoryJournal journal){
        if(!journal.pending(SOURCE,1).isEmpty())return false;
        Object nativeService=service.get();if(nativeService==null)return false;
        try {
            nativeService.getClass().getMethod("historyAuthorityDomainId");
            nativeService.getClass().getMethod("technologyChangesAfter",long.class,int.class);
        }catch(NoSuchMethodException unavailable){return false;}
        try {
            Optional<UUID> authority=domain(nativeService);if(authority.isEmpty())return false;
            UUID domain=authority.orElseThrow();var cursor=journal.cursor(SOURCE);
            if(cursor.isPresent()&&!cursor.get().domain().equals(domain))throw new IllegalStateException("Native technology history domain changed; reconciliation required");
            long after=cursor.map(CommittedHistoryJournal.Cursor::sequence).orElse(0L);
            Object response=call(nativeService,"technologyChangesAfter",after,100);
            if(!(response instanceof List<?> rows)||rows.size()>100)throw new IllegalArgumentException("Invalid native technology history page");
            List<CommittedHistoryJournal.Entry> entries=new ArrayList<>();
            for(Object row:rows)entries.add(convert(domain,row));
            // This native protocol has separate domain/page calls. Recheck even an empty page.
            if(service.get()!=nativeService||!authority.equals(domain(nativeService)))throw new IllegalStateException("Technology history provider/domain changed during read");
            journal.accept(SOURCE,domain,after,entries);return true;
        }catch(InvocationTargetException failure){
            if(failure.getCause() instanceof UnsupportedOperationException)return false;
            throw new IllegalStateException("Native technology history read failed",failure.getCause());
        }catch(ReflectiveOperationException failure){throw new IllegalStateException("Native technology history protocol mismatch",failure);}
    }

    static CommittedHistoryJournal.Entry convert(UUID domain,Object receipt)throws ReflectiveOperationException {
        if(!domain.equals(uuid(call(receipt,"authorityDomainId"))))throw new IllegalArgumentException("Technology receipt has another authority domain");
        Object sequenceValue=call(receipt,"sequence");
        if(!(sequenceValue instanceof Long sequence)||sequence<1)throw new IllegalArgumentException("Invalid technology sequence");
        UUID operation=uuid(call(receipt,"operationId"));
        Object nativeNation=Objects.requireNonNull(call(receipt,"nationId"));
        String nation=nativeNation.toString();
        if(!UUID.fromString(nation).toString().equals(nation))throw new IllegalArgumentException("Invalid technology nation");
        String key=text(call(receipt,"technologyKey"),false),era=text(call(receipt,"era"),true);
        String change=call(receipt,"change").toString();
        if(!Set.of("UNLOCKED","REVOKED").contains(change))throw new IllegalArgumentException("Unknown committed technology change");
        if(!(call(receipt,"committedAt") instanceof Instant committed))throw new IllegalArgumentException("Invalid technology commit timestamp");
        Map<String,String> facts=Map.ofEntries(
                Map.entry("title",change.equals("UNLOCKED")?"国家科技解锁":"国家科技撤销"),
                Map.entry("source.authority_domain",domain.toString()),Map.entry("source.sequence",Long.toString(sequence)),
                Map.entry("source.operation_id",operation.toString()),Map.entry("time_basis","COMMITTED"),
                Map.entry("technology",key),Map.entry("tech.era",era),Map.entry("tech.change",change),
                Map.entry("tech.researcher","NOT_RECORDED"),Map.entry("tech.research_completion","NOT_PROVEN_BY_UNLOCK"));
        String evidence="Native TechnologyService committed state/history receipt; domain="+domain+"; sequence="+sequence+"; operation="+operation+"; change="+change+"; technology="+key+"; nation="+nation+"; researcher is not recorded; unlock is not proof of research completion; existing pre-protocol unlocks are baseline state, not reconstructed changes";
        WorldEvent event=new WorldEvent("starcore:technology-history:"+domain+":"+sequence,"TECHNOLOGY_"+change,committed,Set.of(),Set.of(nation),facts,"StarCore.TechnologyService",evidence);
        return new CommittedHistoryJournal.Entry(sequence,event);
    }
    private static Optional<UUID> domain(Object service)throws ReflectiveOperationException {
        Object value=call(service,"historyAuthorityDomainId");
        if(!(value instanceof Optional<?> optional))throw new IllegalArgumentException("Invalid technology history capability");
        return optional.map(StarCoreTechnologyHistorySource::uuid);
    }
    private static UUID uuid(Object value){if(!(value instanceof UUID id))throw new IllegalArgumentException("Expected native technology UUID");return id;}
    private static String text(Object value,boolean empty){
        if(!(value instanceof String text)||text.length()>256||(!empty&&text.isBlank()))throw new IllegalArgumentException("Invalid technology metadata");return text;
    }
}
