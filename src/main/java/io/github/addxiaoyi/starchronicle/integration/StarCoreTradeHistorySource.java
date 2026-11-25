package io.github.addxiaoyi.starchronicle.integration;

import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import io.github.addxiaoyi.starchronicle.core.RuleEngine;
import io.github.addxiaoyi.starchronicle.history.CommittedHistoryJournal;
import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import static io.github.addxiaoyi.starchronicle.integration.StarCoreBridge.call;

/** Read-only adapter for the native sequence journal; does not infer economic atomicity or contributors. */
public final class StarCoreTradeHistorySource {
    public static final String SOURCE="starcore.trade.history.v1";
    private final Supplier<Object> service;
    public StarCoreTradeHistorySource(StarCoreBridge bridge){this(()->bridge.available()?bridge.service("module.resource.TradeHistoryService"):null);}
    StarCoreTradeHistorySource(Supplier<Object> service){this.service=Objects.requireNonNull(service);}

    /** Called on the persistence worker. Pending evidence is drained before any further source page. */
    public boolean poll(CommittedHistoryJournal journal){
        if(!journal.pending(1).isEmpty())return false;
        Object nativeService=service.get();if(nativeService==null)return false;
        try {
            nativeService.getClass().getMethod("historyAuthorityDomain");
            nativeService.getClass().getMethod("changesAfter",UUID.class,long.class,int.class);
        }catch(NoSuchMethodException unavailable){return false;}
        try {
            UUID domain=uuid(call(nativeService,"historyAuthorityDomain"));
            var cursor=journal.cursor(SOURCE);
            if(cursor.isPresent()&&!cursor.get().domain().equals(domain))throw new IllegalStateException("Native trade history storage domain changed; reconciliation required");
            long after=cursor.map(CommittedHistoryJournal.Cursor::sequence).orElse(0L);
            Object response=call(nativeService,"changesAfter",domain,after,100);
            if(!(response instanceof List<?> rows)||rows.size()>100)throw new IllegalArgumentException("Invalid native trade history page");
            List<CommittedHistoryJournal.Entry> entries=new ArrayList<>();
            for(Object row:rows)entries.add(convert(domain,row));
            if(service.get()!=nativeService)throw new IllegalStateException("Trade history provider changed during read");
            journal.accept(SOURCE,domain,after,entries);return true;
        }catch(InvocationTargetException failure){
            if(failure.getCause() instanceof UnsupportedOperationException)return false;
            throw new IllegalStateException("Native trade history read failed",failure.getCause());
        }catch(ReflectiveOperationException failure){throw new IllegalStateException("Native trade history protocol mismatch",failure);}
    }

    static CommittedHistoryJournal.Entry convert(UUID domain,Object change)throws ReflectiveOperationException {
        if(!domain.equals(uuid(call(change,"authorityDomain"))))throw new IllegalArgumentException("Trade change has another storage domain");
        long sequence=positiveLong(call(change,"sequence"));
        String kind=call(change,"kind").toString();
        if(!Set.of("BASELINE","UPSERT","DELETE").contains(kind))throw new IllegalArgumentException("Unknown trade history change kind");
        Instant recorded=instant(call(change,"recordedAt"));
        Object record=Objects.requireNonNull(call(change,"record"));
        UUID identity=uuid(call(record,"recordId"));
        Instant executed=instant(call(record,"executedAt"));
        String status=call(record,"status").toString();
        if(!Set.of("COMPLETED","REFUNDED","CANCELLED","DISPUTED").contains(status))throw new IllegalArgumentException("Unknown native trade status");
        String resource=(String)call(record,"resourceId");
        if(resource==null||!resource.matches("[a-z0-9][a-z0-9_.:-]{0,49}"))throw new IllegalArgumentException("Invalid trade resource");
        Set<UUID> actors=new HashSet<>();Set<String> nations=new HashSet<>();Map<String,String> facts=new HashMap<>();
        for(String side:List.of("buyer","seller")){
            Object player=optional(call(record,side+"PlayerId"));
            if(player!=null){UUID id=uuid(player);actors.add(id);facts.put("trade."+side+"_player",id.toString());}
            Object nation=optional(call(record,side+"NationId"));
            if(nation!=null){String id=uuidString(nation.toString());nations.add(id);facts.put("trade."+side+"_nation",id);}
        }
        for(String side:List.of("buy","sell")){
            Object order=optional(call(record,side+"OrderId"));
            if(order!=null)facts.put("trade."+side+"_order",uuid(order).toString());
        }
        facts.put("trade.amount",Long.toString(positiveLong(call(record,"amount"))));
        double price=finite(call(record,"pricePerUnit")),total=finite(call(record,"totalValue")),tax=finite(call(record,"taxAmount")),net=finite(call(record,"netValue"));
        if(price<=0||total<=0||tax<0||tax>total||net<0||net!=total-tax)throw new IllegalArgumentException("Invalid persisted trade amounts");
        facts.put("trade.price_per_unit",decimal(price));facts.put("trade.total_value",decimal(total));facts.put("trade.tax_amount",decimal(tax));facts.put("trade.net_value",decimal(net));
        facts.put("trade.record_id",identity.toString());facts.put("trade.status",status);facts.put("trade.resource",resource);
        String orderType=call(record,"orderType").toString(),orderSource=call(record,"orderSource").toString();
        if(!Set.of("BUY","SELL").contains(orderType)||!Set.of("PLAYER","NATION","MARKET","EVENT").contains(orderSource))throw new IllegalArgumentException("Unknown native trade order metadata");
        facts.put("trade.order_type",orderType);facts.put("trade.order_source",orderSource);
        facts.put("trade.executed_at",executed.toString());facts.put("trade.journal_recorded_at",recorded.toString());
        facts.put("source.authority_domain",domain.toString());facts.put("source.sequence",Long.toString(sequence));facts.put("source.change",kind);
        facts.put("time_basis","JOURNAL_RECORDED");facts.put("trade.asset_atomicity","NOT_PROVEN_BY_HISTORY_JOURNAL");
        Object notes=optional(call(record,"notes"));
        if(notes!=null){if(!(notes instanceof String text))throw new IllegalArgumentException("Invalid trade notes");facts.put("trade.notes_sha256",RuleEngine.stable(text));facts.put("trade.notes_length",Integer.toString(text.length()));}
        String type=switch(kind){case "BASELINE"->"TRADE_HISTORY_BASELINE";case "DELETE"->"TRADE_HISTORY_REMOVED";default->"TRADE_HISTORY_RECORDED";};
        facts.put("title",switch(kind){case "BASELINE"->"贸易历史迁移基线";case "DELETE"->"贸易历史维护清理";default->"贸易历史记录更新";});
        String evidence="Native TradeHistoryService journal; domain="+domain+"; sequence="+sequence+"; kind="+kind+"; record="+identity+"; executedAt="+executed+"; recordedAt="+recorded+"; journal and history row share a commit; external assets do not; BASELINE is surviving state, DELETE is maintenance, REFUNDED status does not prove refund completion";
        WorldEvent event=new WorldEvent("starcore:trade-history:"+domain+":"+sequence,type,recorded,actors,nations,facts,"StarCore.TradeHistoryService",evidence);
        return new CommittedHistoryJournal.Entry(sequence,event);
    }
    private static UUID uuid(Object value){if(!(value instanceof UUID id))throw new IllegalArgumentException("Expected native UUID");return id;}
    private static String uuidString(String value){if(!UUID.fromString(value).toString().equals(value))throw new IllegalArgumentException("Noncanonical nation UUID");return value;}
    private static Object optional(Object value){if(!(value instanceof Optional<?> optional))throw new IllegalArgumentException("Expected native optional");return optional.orElse(null);}
    private static Instant instant(Object value){if(!(value instanceof Instant time))throw new IllegalArgumentException("Expected native timestamp");return time;}
    private static long positiveLong(Object value){if(!(value instanceof Long number)||number<1)throw new IllegalArgumentException("Expected positive native long");return number;}
    private static double finite(Object value){if(!(value instanceof Double number)||!Double.isFinite(number))throw new IllegalArgumentException("Expected finite native double");return number;}
    private static String decimal(double value){return BigDecimal.valueOf(value).toPlainString();}
}
