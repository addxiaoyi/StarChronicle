package smoke;

import dev.starcore.starcore.module.nation.NationService;
import dev.starcore.starcore.module.resource.*;
import io.github.addxiaoyi.starchronicle.api.*;
import java.util.*;
import java.util.concurrent.CompletionStage;

/** Explicit initial inventory and read-only observations around actual non-op player commands. */
public final class NationFactoryPaperFixture extends NationProductionPaperFixture {
    private static Map<String,Object> map(Object... pairs){var result=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)result.put(pairs[i].toString(),pairs[i+1]);return result;}
    private UUID id(String key){return UUID.fromString(getConfig().getString("factories.ids."+key));}
    private CompletionStage<?> bind(){return main(()->{
        for(String who:List.of("A","B","C","D"))require(player(who).hasPermission("starcore.nationfactory.use"),"Factory command permission missing");
        if(!getConfig().getBoolean("factories.seeded",false)){
            require(service(NationResourceTransactionService.class).grant(new NationResourceTransactionService.TransactionRequest("factory-fixture-seed",country("A"),
                    Map.of("mineral",900L,"energy",900L,"industrial",1000L),NationResourceTransactionService.Category.OTHER,"Isolated factory construction materials")).applied(),"Factory material seed rejected");
            getConfig().set("factories.seeded",true);saveConfig();
        }
        log("FACTORY_BOUND",map("authority",service(NationFactoryService.class).authorityDomain().map(UUID::toString).orElse(""),"resourceDomain",service(NationResourceTransactionService.class).authorityDomainId(),"nonOp",true));return null;
    });}
    private Map<String,Object> building(NationFactoryService.Building b){return map("factoryId",b.factoryId(),"nationId",b.nationId().toString(),"resourceDomain",b.resourceDomain(),
            "builderId",b.builderId(),"type",b.type().name(),"name",b.name(),"level",b.level(),"operational",b.operational(),"version",b.version(),"builtAt",b.builtAt().toString());}
    private CompletionStage<?> capture(String key,String who,String name){return main(()->{
        var list=service(NationFactoryService.class).buildings(uuid(who),100).stream().filter(b->b.name().equals(name)).toList();require(list.size()==1,"Expected one client-built factory");
        var b=list.getFirst();getConfig().set("factories.ids."+key,b.factoryId().toString());saveConfig();
        log("FACTORY_CAPTURE",map("key",key,"who",who,"building",building(b)));return null;
    });}
    private CompletionStage<?> check(String key,String who,int level,long version,boolean enabled,long runs,long batches){return main(()->{
        var api=service(NationFactoryService.class);var c=api.capacity(id(key),uuid(who)).orElseThrow();var b=c.building();
        require(b.level()==level&&b.version()==version&&b.operational()==enabled&&c.runningRuns()==runs&&c.runningBatches()==batches,"Unexpected factory state/capacity");
        require(b.builderId().equals(uuid("A"))&&b.nationId().equals(country("A")),"Factory original builder/owner changed");
        log("FACTORY_CHECK",map("key",key,"who",who,"building",building(b),"runningRuns",runs,"runningBatches",batches,
                "stockpile",service(ResourceService.class).stockpile(country("A")),"founder",service(NationService.class).nationById(country("A")).orElseThrow().founderId()));return null;
    });}
    private CompletionStage<?> history(int expected){return main(()->{
        var api=service(NationFactoryService.class);var domain=api.authorityDomain().orElseThrow();var records=api.changesAfter(domain,0,100);require(records.size()==expected,"Unexpected factory receipt count");
        log("FACTORY_HISTORY",map("domain",domain,"records",records.stream().map(r->{var c=r.change();return map("sequence",r.sequence(),"operationId",c.operationId(),"actorId",c.actorId(),
                "action",c.action().name(),"building",building(c.building()),"cost",c.cost(),"committedAt",c.committedAt().toString());}).toList()));return null;
    });}
    private CompletionStage<?> provenance(int count){
        var api=Objects.requireNonNull(getServer().getServicesManager().load(ChronicleService.class));
        return api.history(null,null,0,100).thenCompose(events->main(()->{
            var nativeApi=service(NationProductionService.class);var receipts=nativeApi.completionsAfter(nativeApi.completionAuthorityDomain().orElseThrow(),0,100);require(receipts.size()==count,"Missing factory production receipts");
            var bindings=new ArrayList<Map<String,Object>>();
            for(var receipt:receipts){
                var r=receipt.run();var b=Objects.requireNonNull(r.factory());
                var e=events.stream().filter(v->v.id().equals("starcore:nation-production:"+receipt.authorityDomain()+":"+receipt.sequence())).findFirst().orElseThrow();
                require(e.facts().get("production.factory.id").equals(b.factoryId().toString())&&e.facts().get("production.factory.authority_domain").equals(b.authorityDomain().toString())
                        &&e.facts().get("production.factory.version").equals(Long.toString(b.version()))&&e.facts().get("production.factory.level").equals(Integer.toString(b.level()))
                        &&e.facts().get("production.factory.minimum_level").equals(Integer.toString(b.minimumLevel()))&&e.facts().get("production.factory.type").equals(b.type().name()),"Chronicle factory provenance differs");
                bindings.add(map("runId",r.runId(),"actorId",r.actorId(),"binding",map("authorityDomain",b.authorityDomain(),"factoryId",b.factoryId(),"version",b.version(),"type",b.type().name(),"level",b.level(),"minimumLevel",b.minimumLevel()),"event",e));
            }
            log("FACTORY_PROVENANCE",map("count",count,"runs",bindings));return null;
        }));
    }
    @Override protected CompletionStage<?> extraAction(String action,String[] args){return switch(action){
        case "factory-bind"->bind();case "factory-capture"->capture(args[1],args[2],args[3]);
        case "factory-check"->check(args[1],args[2],Integer.parseInt(args[3]),Long.parseLong(args[4]),Boolean.parseBoolean(args[5]),Long.parseLong(args[6]),Long.parseLong(args[7]));
        case "factory-history"->history(Integer.parseInt(args[1]));case "factory-provenance"->provenance(Integer.parseInt(args[1]));
        default->super.extraAction(action,args);
    };}
}
