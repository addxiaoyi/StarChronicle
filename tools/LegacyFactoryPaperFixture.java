package smoke;

import dev.starcore.starcore.module.resource.*;
import java.util.*;
import java.util.concurrent.CompletionStage;

/** Explicit fixture materials and read-only public observations around real player commands. */
public final class LegacyFactoryPaperFixture extends NationProductionPaperFixture {
    private static Map<String,Object> map(Object... pairs){var result=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)result.put(pairs[i].toString(),pairs[i+1]);return result;}
    private CompletionStage<?> bind(){return main(()->{
        if(!getConfig().getBoolean("legacy.seeded",false)){
            require(service(NationResourceTransactionService.class).grant(new NationResourceTransactionService.TransactionRequest("legacy-factory-fixture-seed",country("A"),
                    Map.of("mineral",900L,"energy",900L,"industrial",1000L),NationResourceTransactionService.Category.OTHER,"Explicit isolated legacy registration materials")).applied(),"Legacy material seed rejected");
            getConfig().set("legacy.seeded",true);saveConfig();
        }
        var api=service(NationFactoryService.class);var report=api.legacyAudit();
        log("LEGACY_AUDIT",map("report",report,"quotes",api.legacyQuotes(uuid("A"),0,100),"nonOp",!player("A").isOp(),"stockpile",service(ResourceService.class).stockpile(country("A"))));return null;
    });}
    private Map<String,Object> building(NationFactoryService.Building b){return map("factoryId",b.factoryId(),"nationId",b.nationId().toString(),"resourceDomain",b.resourceDomain(),
        "builderId",b.builderId(),"type",b.type().name(),"name",b.name(),"level",b.level(),"operational",b.operational(),"version",b.version(),"builtAt",b.builtAt().toString(),"origin",b.origin().name());}
    private CompletionStage<?> check(String who,int level,long version,boolean enabled,long running){return main(()->{
        var api=service(NationFactoryService.class);var buildings=api.buildings(uuid(who),100);require(buildings.size()==1,"Unexpected paid factory count");
        var b=buildings.getFirst();var capacity=api.capacity(b.factoryId(),uuid(who)).orElseThrow();
        require(b.name().equals("LegacyForge")&&b.type()==NationFactoryService.Type.SMELTER&&b.origin()==NationFactoryService.Origin.LEGACY_REGISTERED,"Wrong legacy registration identity");
        require(b.level()==level&&b.version()==version&&b.operational()==enabled&&capacity.runningRuns()==running,"Wrong legacy factory state");
        require(b.builderId().equals(uuid("A"))&&b.nationId().equals(country("A")),"Registration payer/owner changed");
        var history=api.changesAfter(api.authorityDomain().orElseThrow(),0,100);require(history.size()==version,"Factory version/receipt count mismatch");
        var first=history.getFirst().change();require(first.action()==NationFactoryService.Action.REGISTER_LEGACY&&first.legacy()!=null,"Missing registration provenance");
        require(first.legacy().fields().get("buildTime").equals("1700000000000")&&first.legacy().fields().get("efficiency").equals("1.1"),"Original legacy fields changed");
        var audit=api.legacyAudit();require(audit.entries().size()==7&&audit.issues().isEmpty(),"Legacy source records changed");
        require(audit.fingerprint().equals(first.legacy().snapshotHash()),"Legacy raw source drifted after payment");
        log("LEGACY_CHECK",map("who",who,"building",building(b),"runningRuns",capacity.runningRuns(),"runningBatches",capacity.runningBatches(),"audit",audit,
            "stockpile",service(ResourceService.class).stockpile(country("A")),"history",history.stream().map(r->{var c=r.change();return map("sequence",r.sequence(),"operationId",c.operationId(),"actorId",c.actorId(),"action",c.action().name(),"building",building(c.building()),"cost",c.cost(),"committedAt",c.committedAt().toString(),"legacy",c.legacy());}).toList()));return null;
    });}
    @Override protected CompletionStage<?> extraAction(String action,String[] args){return switch(action){
        case "legacy-bind"->bind();
        case "legacy-check"->check(args[1],Integer.parseInt(args[2]),Long.parseLong(args[3]),Boolean.parseBoolean(args[4]),Long.parseLong(args[5]));
        default->super.extraAction(action,args);
    };}
}
