package smoke;

import dev.starcore.starcore.module.city.CityService;
import dev.starcore.starcore.module.nation.NationService;
import io.github.addxiaoyi.starchronicle.api.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;

/** Public native designation operations and read-only independent Chronicle history checks. */
public final class CapitalHistoryPaperFixture extends ProductionStackPaperFixture {
    private ChronicleService api(){return Objects.requireNonNull(getServer().getServicesManager().load(ChronicleService.class));}
    private UUID operation(int number){return UUID.nameUUIDFromBytes(("capital-acceptance-operation-"+number).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    private UUID city(String who){return UUID.fromString(getConfig().getString("capital.cities."+who));}
    private static Map<String,Object> map(Object...pairs){Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)result.put(pairs[i].toString(),pairs[i+1]);return result;}
    private List<Map<String,Object>> receipts(List<NationService.CapitalDesignation> values){return values.stream().map(r->map(
        "sequence",r.sequence(),"operationId",r.operationId(),"nationId",r.nationId().toString(),"previousCityId",r.previousCityId(),
        "cityId",r.cityId(),"actorId",r.actorId(),"committedAt",r.committedAt().toString())).toList();}
    private CompletionStage<?> bind(){return main(()->{
        var nations=service(NationService.class);var cities=service(CityService.class);
        require(nations.capitalHistoryAfter(0,100).orElseThrow().latestSequence()==0,"Expected fresh capital authority");
        for(String who:List.of("A","D","B")){
            var nation=country(who.equals("D")?"A":who);
            var result=cities.createCity(nation,"IsolatedCapital"+who,player(who).getUniqueId(),player(who).getLocation());
            require(result.success(),"Actual native city creation failed: "+result.message());
            getConfig().set("capital.cities."+who,result.city().id().toString());
        }
        saveConfig();
        log("CAPITAL_BOUND",map("domain",nations.capitalHistoryAfter(0,100).orElseThrow().authorityDomainId(),"nation",country("A").toString(),
            "cities",Map.of("A",city("A"),"D",city("D"),"B",city("B")),"actor",uuid("A")));return null;
    });}
    private CompletionStage<?> burst(){return main(()->{
        var nations=service(NationService.class);Instant began=Instant.now();
        require(nations.capitalHistoryAfter(0,100).orElseThrow().latestSequence()==0,"Burst must start from zero");
        for(int i=1;i<=205;i++)nations.designateCapital(operation(i),country("A"),player("A").getUniqueId(),city(i%2==1?"A":"D"));
        require(nations.designatedCapital(country("A")).orElseThrow().cityId().equals(city("A")),"Round trip did not return to A");
        log("CAPITAL_BURST",map("started",began.toString(),"finished",Instant.now().toString(),"count",205,"firstDesignations",1,"moves",204));return null;
    });}
    private CompletionStage<?> reject(String actor){return main(()->{
        var nations=service(NationService.class);long head=nations.capitalHistoryAfter(0,1).orElseThrow().latestSequence();
        int rejected=0;
        try{nations.designateCapital(operation(900),country("A"),uuid(actor.equals("A")?"D":"A"),city("D"));}catch(SecurityException expected){rejected++;}
        try{nations.designateCapital(operation(901),country("A"),uuid(actor),city("B"));}catch(SecurityException expected){rejected++;}
        try{nations.designateCapital(operation(902),country("A"),uuid(actor),UUID.fromString("00000000-0000-4000-8000-000000009999"));}catch(IllegalArgumentException expected){rejected++;}
        UUID current=nations.designatedCapital(country("A")).orElseThrow().cityId();
        try{nations.designateCapital(operation(903),country("A"),uuid(actor),current);}catch(IllegalArgumentException expected){rejected++;}
        if(actor.equals("A")){
            try{nations.designateCapital(operation(1),country("A"),uuid("A"),city("D"));}catch(IllegalStateException expected){rejected++;}
        }
        require(rejected==(actor.equals("A")?5:4)&&nations.capitalHistoryAfter(0,1).orElseThrow().latestSequence()==head,"Rejected capital request changed history");
        log("CAPITAL_REJECTIONS",map("actor",actor,"rejected",rejected,"head",head));return null;
    });}
    private CompletionStage<?> repeat(){return main(()->{
        var nations=service(NationService.class);var original=nations.capitalHistoryAfter(0,1).orElseThrow().entries().getFirst();
        long before=nations.capitalHistoryAfter(0,1).orElseThrow().latestSequence();
        require(nations.designateCapital(operation(1),country("A"),player("A").getUniqueId(),city("A")).equals(original),"Replay changed original designation");
        require(nations.capitalHistoryAfter(0,1).orElseThrow().latestSequence()==before,"Replay appended an extra record");
        log("CAPITAL_REPEAT",map("original",receipts(List.of(original)),"head",before));return null;
    });}
    private CompletionStage<?> buffered(){return events(0,new ArrayList<>()).thenCompose(events->main(()->{
        require(events.stream().noneMatch(e->e.id().startsWith("starcore:capital-history:")),"Capital commits were already consumed before offline checkpoint");
        var page=service(NationService.class).capitalHistoryAfter(0,100).orElseThrow();require(page.latestSequence()==205,"Native offline head must be 205");
        log("CAPITAL_BUFFERED",map("head",205,"domain",page.authorityDomainId(),"consumerEvents",0));return null;
    }));}
    private CompletionStage<?> successor(){return main(()->{
        var nations=service(NationService.class);
        require(nations.transferOwnership(country("A"),uuid("A"),player("D").getUniqueId(),player("D").getName()),"Native founder transfer failed");
        var receipt=nations.designateCapital(operation(206),country("A"),uuid("D"),city("D"));
        log("CAPITAL_SUCCESSOR",map("receipt",receipts(List.of(receipt)),"founder",nations.nationById(country("A")).orElseThrow().founderId()));return null;
    });}
    private CompletionStage<?> delete(){return main(()->{
        var nations=service(NationService.class);var cities=service(CityService.class);
        var before=nations.designatedCapital(country("A")).orElseThrow();
        require(before.cityId().equals(city("D"))&&cities.deleteCity(city("D")),"Actual designated city deletion failed");
        require(nations.designatedCapital(country("A")).orElseThrow().equals(before),"City deletion silently invented a new capital");
        log("CAPITAL_CITY_DELETED",map("city",city("D"),"retained",receipts(List.of(before))));return null;
    });}
    private CompletionStage<List<WorldEvent>> events(int page,List<WorldEvent> result){
        require(page<10,"Unexpected capital acceptance history growth");
        return api().history(null,null,page,100).thenCompose(values->{result.addAll(values);return values.size()<100?CompletableFuture.completedFuture(result):events(page+1,result);});
    }
    private CompletionStage<?> check(int expected){CompletableFuture<Void> result=new CompletableFuture<>();checkPoll(expected,System.nanoTime()+TimeUnit.SECONDS.toNanos(45),result);return result;}
    private void checkPoll(int expected,long deadline,CompletableFuture<Void> done){
        events(0,new ArrayList<>()).thenCompose(all->api().contributions(null,null,0,100).thenCompose(contributions->main(()->{
            try{
                var source=all.stream().filter(e->e.id().startsWith("starcore:capital-history:")).sorted(Comparator.comparingLong(e->Long.parseLong(e.facts().get("source.sequence")))).toList();
                var nations=service(NationService.class);List<NationService.CapitalDesignation> nativeRows=new ArrayList<>();long cursor=0;
                UUID domain=nations.capitalHistoryAfter(0,1).orElseThrow().authorityDomainId();
                boolean complete=source.size()==expected;
                for(int i=0;complete&&i<expected;i++)complete=source.get(i).id().equals("starcore:capital-history:"+domain+":"+(i+1));
                // Offset-based public history pages are separate reads; concurrent ingestion can
                // shift their boundaries. Retry the complete read, retaining the observed IDs.
                if(!complete&&System.nanoTime()<deadline){
                    log("CAPITAL_QUERY_RETRY",map("expected",expected,"observedIds",source.stream().map(WorldEvent::id).toList()));
                    getServer().getScheduler().runTaskLater(this,()->checkPoll(expected,deadline,done),2L);return null;
                }
                require(complete,"Capital public history never reached the complete stable sequence within the deadline");
                do{
                    var page=nations.capitalHistoryAfter(cursor,100).orElseThrow();
                    require(page.latestSequence()==expected&&page.authorityDomainId().equals(domain),"Native head/domain mismatch");
                    nativeRows.addAll(page.entries());cursor=nativeRows.size();
                }while(cursor<expected);
                require(source.size()==expected&&nativeRows.size()==expected,"Capital source/consumer count mismatch");
                require(contributions.isEmpty(),"Capital changes are not material contributions");
                for(int i=0;i<expected;i++){
                    var row=nativeRows.get(i);var event=source.get(i);
                    require(row.sequence()==i+1&&event.id().equals("starcore:capital-history:"+domain+":"+(i+1)),"Capital source identity mismatch");
                    require(event.type().equals(i==0?"CAPITAL_DESIGNATED":"CAPITAL_MOVED")&&event.occurredAt().equals(row.committedAt()),"Capital kind/commit time mismatch");
                    require(event.participants().equals(Set.of(row.actorId()))&&row.actorId().equals(uuid(i<205?"A":"D")),"Historical actor was replaced by a reader or current ruler");
                    require(event.nations().equals(Set.of(row.nationId().toString()))&&row.nationId().equals(country("A")),"Capital nation mismatch");
                    require(event.facts().get("capital.operation_id").equals(row.operationId().toString())&&event.facts().get("capital.city").equals(row.cityId().toString())
                        &&event.facts().get("capital.previous_city").equals(row.previousCityId()==null?"NONE":row.previousCityId().toString()),"Capital operation or cities mismatch");
                }
                if(expected>0)require(nations.designatedCapital(country("A")).orElseThrow().equals(nativeRows.getLast()),"Designation differs from log authority");
                log("CAPITAL_CHECK",map("expected",expected,"domain",domain,"native",receipts(nativeRows),"events",source,"contributions",0,"onlinePlayers",getServer().getOnlinePlayers().size()));done.complete(null);
            }catch(Throwable error){done.completeExceptionally(error);}return null;
        }))).exceptionally(error->{done.completeExceptionally(error);return null;});
    }
    @Override protected CompletionStage<?> extraAction(String action,String[] args){return switch(action){
        case "capital-buffered"->buffered();case "capital-bind"->bind();case "capital-burst"->burst();case "capital-repeat"->repeat();
        case "capital-reject"->reject(args[1]);case "capital-successor"->successor();case "capital-delete"->delete();
        case "capital-check"->check(Integer.parseInt(args[1]));default->super.extraAction(action,args);
    };}
}
