package io.github.addxiaoyi.starchronicle.integration;

import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import io.github.addxiaoyi.starchronicle.api.contribution.*;
import io.github.addxiaoyi.starchronicle.core.RuleEngine;
import io.github.addxiaoyi.starchronicle.history.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import static io.github.addxiaoyi.starchronicle.integration.StarCoreBridge.call;

/** Reads only detached durable receipts; no entity, Bukkit world or inferred actor access. */
public final class CommittedSourceCollector {
    public enum Source {
        TRADE("starcore.trade","module.resource.TradeHistoryService","historyAuthorityDomainId","committedChangesAfter"),
        TECHNOLOGY("starcore.technology","module.technology.TechnologyService","historyAuthorityDomainId","technologyChangesAfter"),
        CAPITAL("starcore.capital","module.nation.NationService","authorityDomainId","capitalDesignationsAfter"),
        RESOURCES("starcore.resources","module.resource.NationResourceTransactionService","authorityDomainId","transferReceiptsAfter"),
        PRODUCTION("starcore.production","module.resource.ProcessingService","productionAuthorityDomainId","productionReceiptsAfter");
        public final String id;final String service,domainMethod,pageMethod;
        Source(String id,String service,String domain,String page){this.id=id;this.service=service;domainMethod=domain;pageMethod=page;}
    }
    private final Function<String,Object> services;
    public CommittedSourceCollector(StarCoreBridge bridge){this(bridge::service);}
    CommittedSourceCollector(Function<String,Object> services){this.services=Objects.requireNonNull(services);}

    public Optional<CommittedSourcePage> collect(Source source,Optional<CommittedSourceLedger.Cursor> cursor)throws ReflectiveOperationException {
        Object service=services.apply(source.service);if(service==null)return Optional.empty();
        Object rawDomain;
        try{rawDomain=unwrap(call(service,source.domainMethod));}
        catch(NoSuchMethodException unavailable){return Optional.empty();}
        if(rawDomain==null)return Optional.empty();
        String domain=uuidText(rawDomain);
        if(cursor.isPresent()&&!cursor.get().domain().equals(domain))throw new IllegalStateException("Native source domain changed: "+source.id);
        // Replay the last receipt as an anchor, detecting a restored/replaced journal at the same head.
        long after=cursor.isEmpty()?0:Math.max(0,cursor.get().sequence()-1);
        Object value=call(service,source.pageMethod,after,100);
        if(!(value instanceof List<?> records)||records.size()>100)throw new IllegalStateException("Native source omitted a bounded ordered page");
        List<CommittedSourcePage.Entry> entries=new ArrayList<>();
        for(Object record:records)entries.add(switch(source){
            case TRADE->trade(domain,record);
            case TECHNOLOGY->technology(domain,record);
            case CAPITAL->capital(domain,record);
            case RESOURCES->resources(domain,record);
            case PRODUCTION->production(domain,record);
        });
        if(cursor.isPresent()&&cursor.get().sequence()>0&&entries.isEmpty())throw new IllegalStateException("Native source lost the last acknowledged receipt: "+source.id);
        if(!domain.equals(uuidText(unwrap(call(service,source.domainMethod)))))throw new IllegalStateException("Native domain changed during page read");
        return Optional.of(new CommittedSourcePage(source.id,domain,after,entries));
    }

    private static CommittedSourcePage.Entry trade(String domain,Object change)throws ReflectiveOperationException {
        requireDomain(domain,call(change,"authorityDomainId"));
        long sequence=number(call(change,"sequence"));String operation=uuidText(call(change,"operationId"));
        Instant committed=time(call(change,"committedAt"));boolean baseline=Boolean.TRUE.equals(call(change,"baseline"));
        Object record=call(change,"trade");String id=uuidText(call(record,"recordId")),status=text(call(record,"status"));
        String resource=text(call(record,"resourceId"));long amount=number(call(record,"amount"));
        UUID seller=optionalUuid(call(record,"sellerPlayerId")),buyer=optionalUuid(call(record,"buyerPlayerId"));
        String sellerNation=optionalId(call(record,"sellerNationId")),buyerNation=optionalId(call(record,"buyerNationId"));
        Map<String,String> facts=base(domain,sequence,operation);facts.put("title",baseline?"旧贸易基线":"贸易状态提交");
        facts.put("trade.id",id);facts.put("trade.status",status);facts.put("trade.resource",resource);facts.put("trade.amount",Long.toString(amount));
        facts.put("trade.price",text(call(record,"pricePerUnit")));facts.put("trade.tax",text(call(record,"taxAmount")));facts.put("trade.executed_at",time(call(record,"executedAt")).toString());
        facts.put("trade.order_type",text(call(record,"orderType")));facts.put("trade.order_source",text(call(record,"orderSource")));
        facts.put("trade.seller",seller==null?"":seller.toString());facts.put("trade.buyer",buyer==null?"":buyer.toString());
        facts.put("trade.seller_nation",sellerNation);facts.put("trade.buyer_nation",buyerNation);
        facts.put("trade.buy_order",optionalId(call(record,"buyOrderId")));facts.put("trade.sell_order",optionalId(call(record,"sellOrderId")));
        Map<String,String> immutable=new TreeMap<>(facts);immutable.keySet().removeAll(Set.of("title","trade.status","source.sequence","source.operation"));
        String terms=RuleEngine.stable(io.github.addxiaoyi.starchronicle.persistence.SqliteStore.json(immutable));
        facts.put("trade.notes_sha256",RuleEngine.stable(io.github.addxiaoyi.starchronicle.persistence.SqliteStore.json(unwrap(call(record,"notes")))));
        String eventId=eventId("trade",domain,operation),unit="resource."+resource;
        boolean eligible=!(seller==null&&buyer==null)&&!(seller!=null&&seller.equals(buyer));
        facts.put("trade.contribution",eligible?"ELIGIBLE":"NO_DISTINCT_ACTOR");
        try{ContributionRequest.token(unit,"unit",32);ContributionRequest.amount(BigDecimal.valueOf(amount));}
        catch(IllegalArgumentException unsupported){eligible=false;facts.put("trade.contribution","OUTSIDE_LEDGER_QUANTITY_LIMITS");}
        List<ContributionRequest> contributions=new ArrayList<>();Set<String> subjects=new HashSet<>();
        if(eligible&&!baseline&&status.equals("COMPLETED")) {
            project(contributions,subjects,domain,id,eventId,seller,sellerNation,amount,unit,committed);
            project(contributions,subjects,domain,id,eventId,buyer,buyerNation,amount,unit,committed);
        }
        Set<UUID> players=new HashSet<>();if(seller!=null)players.add(seller);if(buyer!=null)players.add(buyer);
        Set<String> nations=new HashSet<>();if(!sellerNation.isEmpty())nations.add(sellerNation);if(!buyerNation.isEmpty())nations.add(buyerNation);
        WorldEvent event=new WorldEvent(eventId,baseline?"TRADE_BASELINE":"TRADE_"+status,committed,players,nations,facts,"StarCore.TradeHistoryService","native immutable trade commit="+operation+"; record="+id+"; domain="+domain);
        return new CommittedSourcePage.Entry(sequence,operation,event,baseline,new CommittedSourcePage.Trade(id,terms,status,contributions));
    }
    private static void project(List<ContributionRequest> result,Set<String> subjects,String domain,String trade,String event,UUID actor,String nation,long amount,String unit,Instant time) {
        if(actor==null)return;
        List<String> ids=new ArrayList<>(List.of("player:"+actor));if(!nation.isEmpty())ids.add("nation:"+nation);
        for(String subject:ids)if(subjects.add(subject))result.add(new ContributionRequest("starcore:trade:"+RuleEngine.stable(domain,trade),ContributionType.TRADE,subject,event,actor,nation.isEmpty()?null:nation,BigDecimal.valueOf(amount),unit,time,"native trade="+trade+"; authority_domain="+domain));
    }
    private static CommittedSourcePage.Entry technology(String domain,Object change)throws ReflectiveOperationException {
        requireDomain(domain,call(change,"authorityDomainId"));long sequence=number(call(change,"sequence"));String operation=uuidText(call(change,"operationId"));
        String nation=uuidText(call(change,"nationId")),action=text(call(change,"change"));
        if(!Set.of("UNLOCKED","REVOKED").contains(action))throw new IllegalStateException("Unknown native technology transition");
        Map<String,String> facts=base(domain,sequence,operation);facts.put("title",action.equals("UNLOCKED")?"科技解锁":"科技撤销");facts.put("technology",text(call(change,"technologyKey")));facts.put("tech.era",text(call(change,"era")));facts.put("tech.change",action);
        return new CommittedSourcePage.Entry(sequence,operation,new WorldEvent(eventId("technology",domain,operation),"TECHNOLOGY_"+action,time(call(change,"committedAt")),Set.of(),Set.of(nation),facts,"StarCore.TechnologyService","native technology operation="+operation+"; researcher is not exposed by this source"),false,null);
    }
    private static CommittedSourcePage.Entry capital(String domain,Object change)throws ReflectiveOperationException {
        long sequence=number(call(change,"sequence"));String operation=uuidText(call(change,"operationId"));
        String nation=uuidText(call(change,"nationId"));UUID actor=UUID.fromString(uuidText(call(change,"actorId")));
        Object previous=call(change,"previousCityId");Map<String,String> facts=base(domain,sequence,operation);
        facts.put("title",previous==null?"指定首都":"首都迁移");facts.put("capital.city_id",uuidText(call(change,"cityId")));facts.put("capital.previous_city_id",previous==null?"":uuidText(previous));
        return new CommittedSourcePage.Entry(sequence,operation,new WorldEvent(eventId("capital",domain,operation),previous==null?"CAPITAL_DESIGNATED":"CAPITAL_MOVED",time(call(change,"committedAt")),Set.of(actor),Set.of(nation),facts,"StarCore.NationService","native capital designation="+operation),false,null);
    }
    private static CommittedSourcePage.Entry resources(String domain,Object receipt)throws ReflectiveOperationException {
        requireDomain(domain,call(receipt,"authorityDomainId"));long sequence=number(call(receipt,"sequence"));String operation=text(call(receipt,"transactionId"));
        Map<String,String> facts=base(domain,sequence,operation);facts.put("title","国家资源交付");facts.put("resource.payer",uuidText(call(receipt,"payerNationId")));facts.put("resource.receiver",uuidText(call(receipt,"receiverNationId")));
        Object resources=call(receipt,"resources");if(!(resources instanceof Map<?,?> map)||map.isEmpty()||map.size()>64)throw new IllegalStateException("Missing native resource quantities");
        for(var resource:map.entrySet())facts.put("resource.amount."+text(resource.getKey()),Long.toString(number(resource.getValue())));
        facts.put("resource.category",text(call(receipt,"category")));
        facts.put("resource.description",text(call(receipt,"description")));
        return new CommittedSourcePage.Entry(sequence,operation,new WorldEvent(eventId("resources",domain,operation),"NATIONAL_RESOURCES_TRANSFERRED",time(call(receipt,"appliedAt")),Set.of(),Set.of(facts.get("resource.payer"),facts.get("resource.receiver")),facts,"StarCore.NationResourceTransactionService","native transfer="+operation+"; actor is not exposed; epic-linked contributions require separate actor proof"),false,null);
    }
    private static Map<String,String> base(String domain,long sequence,String operation){return new TreeMap<>(Map.of("time_basis","COMMITTED_SOURCE","source.domain",domain,"source.sequence",Long.toString(sequence),"source.operation",operation));}
    private static CommittedSourcePage.Entry production(String domain,Object receipt)throws ReflectiveOperationException {
        requireDomain(domain,call(receipt,"authorityDomainId"));
        long sequence=number(call(receipt,"sequence"));String operation=text(call(receipt,"operationId"));
        String nation=uuidText(call(receipt,"nationId")),recipe=text(call(receipt,"recipeId"));
        UUID actor=optionalUuid(call(receipt,"actorId"));Instant committed=time(call(receipt,"committedAt"));
        long batches=number(call(receipt,"batches"));if(batches<1||batches>1_000_000)throw new IllegalStateException("Invalid production batch count");
        var inputs=quantities(call(receipt,"inputs"));var outputs=quantities(call(receipt,"outputs"));
        Map<String,String> facts=base(domain,sequence,operation);facts.put("title","国家资源加工");
        facts.put("production.recipe",recipe);facts.put("production.batches",Long.toString(batches));
        inputs.forEach((key,value)->facts.put("production.input."+key,Long.toString(value)));
        outputs.forEach((key,value)->facts.put("production.output."+key,Long.toString(value)));
        String event=eventId("production",domain,operation);
        List<ContributionRequest> entries=new ArrayList<>();
        boolean eligible=actor!=null;
        try { for(var output:outputs.entrySet()){ContributionRequest.token("resource."+output.getKey(),"unit",32);ContributionRequest.amount(BigDecimal.valueOf(output.getValue()));} }
        catch(IllegalArgumentException unsupported){eligible=false;}
        facts.put("production.contribution",actor==null?"NO_ACTOR":eligible?"ELIGIBLE":"OUTSIDE_LEDGER_QUANTITY_LIMITS");
        if(eligible)for(var output:outputs.entrySet())for(String subject:List.of("player:"+actor,"nation:"+nation))
            entries.add(new ContributionRequest("starcore:production:"+RuleEngine.stable(domain,operation,output.getKey()),ContributionType.CIVILIZATION,subject,event,actor,nation,BigDecimal.valueOf(output.getValue()),"resource."+output.getKey(),committed,"native national conversion="+operation+"; recipe="+recipe+"; authority_domain="+domain));
        return new CommittedSourcePage.Entry(sequence,operation,new WorldEvent(event,"CIVILIZATION_PRODUCTION_COMMITTED",committed,actor==null?Set.of():Set.of(actor),Set.of(nation),facts,"StarCore.ProcessingService","native atomic national processing="+operation+"; timed factories and ordinary harvesting are separate sources"),false,null,entries);
    }
    private static SortedMap<String,Long> quantities(Object value) {
        if(!(value instanceof Map<?,?> map)||map.isEmpty()||map.size()>32)throw new IllegalArgumentException("Missing bounded production quantities");
        SortedMap<String,Long> result=new TreeMap<>();
        for(var entry:map.entrySet()) {
            String key=text(entry.getKey());long amount=number(entry.getValue());
            if(!key.matches("[a-z][a-z0-9_]{0,31}")||amount<1)throw new IllegalArgumentException("Invalid production quantity");
            result.put(key,amount);
        }
        return result;
    }
    private static String eventId(String source,String domain,String operation){return "starcore:committed:"+source+":"+RuleEngine.stable(domain,operation);}
    private static Object unwrap(Object value){return value instanceof Optional<?> o?o.orElse(null):value;}
    private static String text(Object value){if(value==null)throw new IllegalArgumentException("Native receipt field missing");return value.toString();}
    private static String uuidText(Object value){String text=text(value);if(!UUID.fromString(text).toString().equals(text))throw new IllegalArgumentException("Noncanonical native UUID");return text;}
    private static String optionalId(Object value){Object unwrapped=unwrap(value);return unwrapped==null?"":uuidText(unwrapped);}
    private static UUID optionalUuid(Object value){String id=optionalId(value);return id.isEmpty()?null:UUID.fromString(id);}
    private static void requireDomain(String expected,Object value){if(!expected.equals(uuidText(value)))throw new IllegalStateException("Receipt belongs to another storage domain");}
    private static long number(Object value){if(!(value instanceof Long||value instanceof Integer))throw new IllegalArgumentException("Expected native integer");return ((Number)value).longValue();}
    private static Instant time(Object value){if(!(value instanceof Instant time))throw new IllegalArgumentException("Native commit timestamp missing");return time;}
}
