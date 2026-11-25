package smoke;

import dev.starcore.starcore.module.nation.NationService;
import dev.starcore.starcore.module.resource.NationProductionService;
import dev.starcore.starcore.module.resource.NationResourceTransactionService;
import dev.starcore.starcore.module.resource.ResourceService;
import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.api.contribution.ContributionType;
import java.util.*;
import java.util.concurrent.*;

/** Observes real production commands; setup and succession use explicit native public APIs. */
public class NationProductionPaperFixture extends ProductionStackPaperFixture {
    private static final String SOURCE="StarCore.NationProductionService";
    private ChronicleService api(){return Objects.requireNonNull(getServer().getServicesManager().load(ChronicleService.class));}
    private static Map<String,Object> map(Object...pairs){Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)result.put(pairs[i].toString(),pairs[i+1]);return result;}
    private UUID runId(String key){return UUID.fromString(getConfig().getString("production.runs."+key));}
    private Map<String,Object> run(NationProductionService.Run r){return map("runId",r.runId(),"actorId",r.actorId(),"nationId",r.nationId().toString(),
        "resourceDomain",r.resourceDomain(),"recipeId",r.recipeId(),"batches",r.batches(),"inputs",r.inputs(),"outputs",r.outputs(),"startedAt",r.startedAt().toString(),"readyAt",r.readyAt().toString());}
    private Map<String,Object> receipt(NationProductionService.CompletionReceipt r){return map("authorityDomain",r.authorityDomain(),"sequence",r.sequence(),"run",run(r.run()),"committedAt",r.committedAt().toString());}
    private CompletionStage<?> bind(){return main(()->{
        for(String who:List.of("A","B","C","D"))require(player(who).hasPermission("starcore.nationproduction.use"),"Native production permission unavailable");
        if(!getConfig().getBoolean("production.seeded",false)){
            require(service(NationResourceTransactionService.class).grant(new NationResourceTransactionService.TransactionRequest("production-fixture-seed",country("A"),
                Map.of("mineral",100L,"energy",100L),NationResourceTransactionService.Category.OTHER,"Isolated production test initial resources")).applied(),"Production seed rejected");
            getConfig().set("production.seeded",true);saveConfig();
        }
        var nativeApi=service(NationProductionService.class);
        log("PRODUCTION_BOUND",map("resourceDomain",service(NationResourceTransactionService.class).authorityDomainId(),"domain",nativeApi.completionAuthorityDomain().map(UUID::toString).orElse(""),
            "nonOp",true,"actualService",nativeApi.getClass().getName()));return null;
    });}
    private CompletionStage<?> capture(String key,String who,long batches){return main(()->{
        var nativeApi=service(NationProductionService.class);
        var runs=nativeApi.runs(uuid(who),100).stream().filter(v->v.state()==NationProductionService.State.RUNNING&&v.run().actorId().equals(uuid(who))&&v.run().batches()==batches).toList();
        require(runs.size()==1,"Expected unique client-created production run: "+key);
        var view=runs.getFirst();require(view.run().nationId().equals(country("A")),"Wrong production country");
        getConfig().set("production.runs."+key,view.run().runId().toString());saveConfig();
        log("PRODUCTION_RUN",map("key",key,"who",who,"run",run(view.run()),"state",view.state()));return null;
    });}
    private CompletionStage<?> status(String key,String who,String expected){return main(()->{
        var view=service(NationProductionService.class).run(runId(key),uuid(who)).orElseThrow();
        require(view.state().name().equals(expected),"Wrong production state: "+key);
        log("PRODUCTION_STATUS",map("key",key,"who",who,"run",run(view.run()),"state",view.state(),"changedAt",view.changedAt().toString()));return null;
    });}
    private CompletionStage<?> ready(String key,String who){
        var done=new CompletableFuture<Void>();readyPoll(key,who,System.nanoTime()+TimeUnit.SECONDS.toNanos(100),done);return done;
    }
    private void readyPoll(String key,String who,long deadline,CompletableFuture<Void> done){main(()->{
        try{
            var view=service(NationProductionService.class).run(runId(key),uuid(who)).orElseThrow();
            require(view.state()==NationProductionService.State.RUNNING,"Production already terminal before actual collection");
            if(java.time.Instant.now().isBefore(view.run().readyAt())){
                require(System.nanoTime()<deadline,"Production ready deadline exceeded");
                getServer().getScheduler().runTaskLater(this,()->readyPoll(key,who,deadline,done),10);return null;
            }
            log("PRODUCTION_READY",map("key",key,"runId",view.run().runId(),"readyAt",view.run().readyAt().toString(),"observedAt",java.time.Instant.now().toString()));done.complete(null);
        }catch(Throwable error){done.completeExceptionally(error);}return null;
    }).exceptionally(error->{done.completeExceptionally(error);return null;});}
    private CompletionStage<?> successor(){return main(()->{
        var nations=service(NationService.class);require(nations.transferOwnership(country("A"),uuid("A"),uuid("D"),player("D").getName()),"Native succession failed");
        require(nations.nationById(country("A")).orElseThrow().isFounder(uuid("D")),"Successor not authoritative");
        log("PRODUCTION_SUCCESSOR",map("nation",country("A").toString(),"previous",uuid("A"),"current",uuid("D"),"publicNativeOperation",true));return null;
    });}
    private CompletionStage<?> check(int expected){var done=new CompletableFuture<Void>();checkPoll(expected,System.nanoTime()+TimeUnit.SECONDS.toNanos(40),done);return done;}
    private void checkPoll(int expected,long deadline,CompletableFuture<Void> done){
        api().history(null,null,0,100).thenCompose(events->api().contributions(null,ContributionType.CIVILIZATION,0,100).thenCompose(contributions->main(()->{
            try{
                var history=events.stream().filter(e->e.source().equals(SOURCE)).sorted(Comparator.comparingLong(e->Long.parseLong(e.facts().get("source.sequence")))).toList();
                if((history.size()!=expected||contributions.size()!=2*expected)&&System.nanoTime()<deadline){
                    getServer().getScheduler().runTaskLater(this,()->checkPoll(expected,deadline,done),10);return null;
                }
                require(history.size()==expected&&contributions.size()==2*expected,"Wrong production history/contribution count");
                var service=service(NationProductionService.class);var domain=service.completionAuthorityDomain();
                var receipts=domain.isEmpty()?List.<NationProductionService.CompletionReceipt>of():service.completionsAfter(domain.orElseThrow(),0,100);
                require(receipts.size()==expected,"Native completion count differs");
                for(int i=0;i<receipts.size();i++){
                    var receipt=receipts.get(i);var event=history.get(i);
                    require(event.id().equals("starcore:nation-production:"+domain.orElseThrow()+":"+receipt.sequence())&&event.participants().equals(Set.of(receipt.run().actorId())),"Original production actor changed");
                    var views=contributions.stream().filter(c->c.request().sourceEventId().equals(event.id())).toList();require(views.size()==2,"Production views missing");
                    for(var entry:views)require(entry.request().actorId().equals(receipt.run().actorId())&&entry.request().nationId().equals(receipt.run().nationId().toString()),"Contribution attribution differs");
                }
                var resources=service(ResourceService.class);
                log("PRODUCTION_CHECK",map("expected",expected,"domain",domain.map(UUID::toString).orElse(""),"native",receipts.stream().map(this::receipt).toList(),
                    "events",history,"contributions",contributions,"stockpile",resources.stockpile(country("A")),"founder",service(NationService.class).nationById(country("A")).orElseThrow().founderId()));
                done.complete(null);
            }catch(Throwable error){done.completeExceptionally(error);}return null;
        }))).exceptionally(error->{done.completeExceptionally(error);return null;});
    }
    private CompletionStage<?> offline(boolean buffered){
        if(!buffered)return check(1).thenCompose(ignored->main(()->{
            require(getServer().getOnlinePlayers().isEmpty(),"Production backlog consumed after clients connected");
            log("PRODUCTION_OFFLINE_CONSUMED",map("onlinePlayers",0,"completions",1,"contributions",2));return null;
        }));
        return api().history(null,null,0,100).thenCompose(events->api().contributions(null,ContributionType.CIVILIZATION,0,100).thenCompose(contributions->main(()->{
            var service=service(NationProductionService.class);require(service.completionsAfter(service.completionAuthorityDomain().orElseThrow(),0,100).size()==1,"Missing buffered completion");
            require(events.stream().noneMatch(e->e.source().equals(SOURCE))&&contributions.isEmpty(),"Production consumer already advanced");
            log("PRODUCTION_OFFLINE_BUFFERED",map("completions",1,"events",0,"contributions",0));return null;
        })));
    }
    private CompletionStage<?> catalogCheck(boolean enabled,int maxRuns,long maxBatches,long running,long batches){return main(()->{
        var service=service(NationProductionService.class);var c=service.capacity(uuid("A")).orElseThrow();
        require(c.startsEnabled()==enabled&&c.maxRunningRuns()==maxRuns&&c.maxRunningBatches()==maxBatches&&c.runningRuns()==running&&c.runningBatches()==batches,"Production catalog capacity differs");
        log("PRODUCTION_CATALOG",map("capacity",c,"recipes",service.recipes(),"maxBatches",service.recipes().stream().collect(java.util.stream.Collectors.toMap(NationProductionService.Recipe::recipeId,r->service.maxBatches(r.recipeId())))));return null;
    });}
    @Override protected CompletionStage<?> extraAction(String action,String[] args){return switch(action){
        case "production-catalog-check"->catalogCheck(Boolean.parseBoolean(args[1]),Integer.parseInt(args[2]),Long.parseLong(args[3]),Long.parseLong(args[4]),Long.parseLong(args[5]));
        case "production-bind"->bind();case "production-capture"->capture(args[1],args[2],Long.parseLong(args[3]));
        case "production-status"->status(args[1],args[2],args[3]);case "production-ready"->ready(args[1],args[2]);
        case "production-successor"->successor();case "production-check"->check(Integer.parseInt(args[1]));
        case "production-offline-buffered"->offline(true);case "production-offline-consumed"->offline(false);
        default->super.extraAction(action,args);
    };}
}
