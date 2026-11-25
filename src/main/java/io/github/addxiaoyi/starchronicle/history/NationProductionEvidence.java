package io.github.addxiaoyi.starchronicle.history;

import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import java.time.Instant;
import java.util.*;

/** Only native completed, paid national production runs may supply civilization contributions. */
public record NationProductionEvidence(UUID domain, long sequence, UUID runId, UUID actor,
        String nation, UUID resourceDomain, String recipe, long batches, Map<String,Long> inputs,
        Map<String,Long> outputs, Instant startedAt, Instant readyAt, Instant committedAt, FactoryAdmission factory) {
    public NationProductionEvidence(UUID domain,long sequence,UUID runId,UUID actor,String nation,UUID resourceDomain,
            String recipe,long batches,Map<String,Long> inputs,Map<String,Long> outputs,Instant startedAt,Instant readyAt,Instant committedAt){
        this(domain,sequence,runId,actor,nation,resourceDomain,recipe,batches,inputs,outputs,startedAt,readyAt,committedAt,null);
    }
    public record FactoryAdmission(UUID domain,UUID factoryId,long version,String type,int level,int minimumLevel,String origin){
        public FactoryAdmission(UUID domain,UUID factoryId,long version,String type,int level,int minimumLevel){
            this(domain,factoryId,version,type,level,minimumLevel,"BUILT");
        }
        public FactoryAdmission {
            Objects.requireNonNull(domain);Objects.requireNonNull(factoryId);
            if(type==null||!Set.of("SMELTER","PROCESSING_PLANT","CHEMICAL_PLANT","ASSEMBLY_PLANT","REFINERY","INTEGRATED_FACTORY").contains(type)
                    ||origin==null||!Set.of("BUILT","LEGACY_REGISTERED").contains(origin)
                    ||level<1||level>10||minimumLevel<1||minimumLevel>level||version<1||("BUILT".equals(origin)&&version<level))
                throw new IllegalArgumentException("Invalid factory admission");
        }
    }
    public static final String SOURCE="starcore.nation-production.completions.v1";
    public static final String ISSUER="StarCore.NationProductionService";
    public NationProductionEvidence {
        Objects.requireNonNull(domain);Objects.requireNonNull(runId);Objects.requireNonNull(actor);Objects.requireNonNull(resourceDomain);
        NationTradeEvidence.canonicalUuid(nation);
        if(sequence<1||batches<1||batches>1000||recipe==null||!recipe.matches("[a-z0-9_.:-]{1,64}"))throw new IllegalArgumentException("Invalid production identity");
        inputs=quantities(inputs);outputs=quantities(outputs);
        for(Instant time:List.of(startedAt,readyAt,committedAt))
            if(time.toEpochMilli()<0||!time.equals(Instant.ofEpochMilli(time.toEpochMilli())))throw new IllegalArgumentException("Production time must use persisted milliseconds");
        if(!readyAt.isAfter(startedAt)||readyAt.isAfter(startedAt.plusSeconds(86_400_000L))||committedAt.isBefore(readyAt))
            throw new IllegalArgumentException("Invalid production timing");
    }
    public String operationId(){return "nation-production:"+domain+":"+runId;}
    public WorldEvent event(){
        var facts=new TreeMap<String,String>();
        facts.put("title","国家生产已完成");facts.put("time_basis","COMMITTED_SOURCE");
        facts.put("source.authority_domain",domain.toString());facts.put("source.sequence",Long.toString(sequence));
        facts.put("production.run_id",runId.toString());facts.put("production.actor",actor.toString());facts.put("production.nation",nation);
        facts.put("production.resource_domain",resourceDomain.toString());facts.put("production.recipe",recipe);facts.put("production.batches",Long.toString(batches));
        inputs.forEach((key,value)->facts.put("production.input."+key,Long.toString(value)));
        outputs.forEach((key,value)->facts.put("production.output."+key,Long.toString(value)));
        facts.put("production.started_at",startedAt.toString());facts.put("production.ready_at",readyAt.toString());
        facts.put("production.asset_atomicity","NATIVE_SQL_STATE_TRANSITIONS");
        if(factory!=null){
            facts.put("production.factory.authority_domain",factory.domain().toString());facts.put("production.factory.id",factory.factoryId().toString());
            facts.put("production.factory.version",Long.toString(factory.version()));facts.put("production.factory.type",factory.type());
            facts.put("production.factory.level",Integer.toString(factory.level()));facts.put("production.factory.minimum_level",Integer.toString(factory.minimumLevel()));
            if(!factory.origin().equals("BUILT"))facts.put("production.factory.origin",factory.origin());
        }
        return new WorldEvent("starcore:nation-production:"+domain+":"+sequence,"NATIONAL_PRODUCTION_COMMITTED",committedAt,
                Set.of(actor),Set.of(nation),facts,ISSUER,
                "Native national production; authority="+domain+"; sequence="+sequence+"; run="+runId+"; resource_domain="+resourceDomain
                        +"; input consumption and paid run share one SQL commit; exact outputs and unique completion share one SQL commit; original initiator is frozen; no player inventory, external wallet, legacy factory attribution or extra rewards");
    }
    public static NationProductionEvidence fromEvent(WorldEvent event){
        var f=event.facts();
        var proof=new NationProductionEvidence(NationTradeEvidence.canonicalUuid(f.get("source.authority_domain")),number(f.get("source.sequence")),
                NationTradeEvidence.canonicalUuid(f.get("production.run_id")),NationTradeEvidence.canonicalUuid(f.get("production.actor")),f.get("production.nation"),
                NationTradeEvidence.canonicalUuid(f.get("production.resource_domain")),f.get("production.recipe"),number(f.get("production.batches")),
                map(f,"production.input."),map(f,"production.output."),Instant.parse(f.get("production.started_at")),Instant.parse(f.get("production.ready_at")),event.occurredAt(),factory(f));
        if(!proof.event().equals(event))throw new IllegalArgumentException("Production event is not its canonical source projection");
        return proof;
    }
    private static FactoryAdmission factory(Map<String,String> facts){
        if(facts.keySet().stream().noneMatch(k->k.startsWith("production.factory.")))return null;
        return new FactoryAdmission(NationTradeEvidence.canonicalUuid(facts.get("production.factory.authority_domain")),
                NationTradeEvidence.canonicalUuid(facts.get("production.factory.id")),number(facts.get("production.factory.version")),
                facts.get("production.factory.type"),Math.toIntExact(number(facts.get("production.factory.level"))),Math.toIntExact(number(facts.get("production.factory.minimum_level"))),facts.getOrDefault("production.factory.origin","BUILT"));
    }
    private static Map<String,Long> quantities(Map<String,Long> values){
        Objects.requireNonNull(values);if(values.isEmpty()||values.size()>7)throw new IllegalArgumentException("Invalid production resources");
        var result=new TreeMap<String,Long>();
        values.forEach((key,amount)->{
            if(!Set.of("mineral","agricultural","energy","luxury","industrial","chemical","strategic").contains(key)||amount==null||amount<1||amount>100_000_000L)
                throw new IllegalArgumentException("Invalid production quantity");
            result.put(key,amount);
        });
        return Collections.unmodifiableMap(result);
    }
    private static Map<String,Long> map(Map<String,String> facts,String prefix){
        var result=new TreeMap<String,Long>();facts.forEach((key,value)->{if(key.startsWith(prefix))result.put(key.substring(prefix.length()),number(value));});return result;
    }
    private static long number(String value){long parsed=Long.parseLong(value);if(!Long.toString(parsed).equals(value))throw new IllegalArgumentException("Noncanonical production integer");return parsed;}
}
