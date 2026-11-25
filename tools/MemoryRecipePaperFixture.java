package smoke;

import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.core.StoryInstance;
import io.github.addxiaoyi.starchronicle.memory.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;

/** Real first visits only. Reads public APIs, and replays only stored canonical sources.
 * It never edits a database, manufactures discoveries/memories, or grants narrative rewards. */
public final class MemoryRecipePaperFixture extends ProductionStackPaperFixture {
    private static final String REGION="old-caravan-road", RECIPE="caravan-archive", STORY="caravan-library";
    private record Expected(String sources,String memories,Map<String,String> completed,boolean offline) {}
    private ChronicleService api(){return Objects.requireNonNull(getServer().getServicesManager().load(ChronicleService.class));}
    private Path run(){return getDataFolder().toPath().toAbsolutePath().getParent().getParent();}
    private World world(){return Objects.requireNonNull(getServer().getWorld(UUID.fromString(getConfig().getString("memory-world"))));}
    private static Map<String,Object> map(Object...pairs){Map<String,Object> value=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)value.put(pairs[i].toString(),pairs[i+1]);return value;}
    private Expected expected(String phase){
        var testimony=Map.of("A:A","testify");var witness=Map.of("A:A","testify","D:A","read");
        var read=Map.of("A:A","testify","D:A","read","B:B","read");
        return switch(phase){
            case "zero","offline-buffered"->new Expected("","",Map.of(),false);
            case "first"->new Expected("A","A",Map.of(),false);
            case "testimony"->new Expected("A","A",testimony,false);
            case "witness"->new Expected("A","A",witness,false);
            case "second"->new Expected("AB","AB",witness,false);
            case "read"->new Expected("AB","AB",read,false);
            case "disabled"->new Expected("ABC","AB",read,false);
            case "pending"->new Expected("ABCD","ABD",read,false);
            case "d-complete"->new Expected("ABCD","ABD",Map.of("A:A","testify","D:A","read","B:B","read","D:D","testify"),false);
            case "complete"->new Expected("ABCD","ABD",Map.of("A:A","testify","D:A","read","B:B","read","D:D","testify","A:D","read"),false);
            case "offline-recorded"->new Expected("D","D",Map.of(),true);
            case "offline-pending"->new Expected("D","D",Map.of(),false);
            case "offline-testimony"->new Expected("D","D",Map.of("D:D","testify"),false);
            case "offline-complete"->new Expected("D","D",Map.of("D:D","testify","A:D","read"),false);
            default->throw new IllegalArgumentException("Unknown memory checkpoint: "+phase);
        };
    }
    private CompletionStage<?> station(){return main(()->{
        World w=world();
        for(int x=996;x<=1018;x++)for(int z=-2;z<=4;z++){
            w.getBlockAt(x,-61,z).setType(Material.STONE,false);
            for(int y=-60;y<=-57;y++)w.getBlockAt(x,y,z).setType(Material.AIR,false);
        }
        log("MEMORY_STATION",map("world",w.getUID(),"minX",996,"maxX",1018,"y",-60,"fixtureOnly",true));return null;
    });}
    private CompletionStage<?> place(String who){return main(()->{
        var p=player(who);require(!p.hasPermission("starchronicle.admin"),"Actual actor must not be administrator");
        require(p.teleport(new Location(world(),1000.5,-60,1.5)),"Outside station teleport rejected");
        log("MEMORY_OUTSIDE",map("who",who,"player",p.getUniqueId(),"world",p.getWorld().getUID(),"x",p.getLocation().getX(),"y",p.getLocation().getY(),"z",p.getLocation().getZ(),"nonOp",true));return null;
    });}
    private CompletionStage<Map<String,Object>> snapshot(){
        return main(()->getServer().getOnlinePlayers().stream().map(p->p.getUniqueId().toString()).sorted().toList())
            .thenCompose(online->api().history(null,null,0,100).thenCompose(history->api().memoryEntries(REGION,0,100).thenCompose(entries->{
                Map<String,Object> state=map("online",online,"history",history.stream().filter(e->e.type().equals("EXPLORATION_DISCOVERY")||e.type().equals("WORLD_MEMORY_RECORDED")||e.type().startsWith("CARAVAN_ARCHIVE_")).toList(),"entries",entries,"players",new TreeMap<String,Object>());
                if(online.isEmpty())return api().activeInstances().thenApply(active->{state.put("active",active);return state;});
                CompletionStage<Map<String,Object>> result=CompletableFuture.completedFuture(state);
                for(String who:List.of("A","B","C","D"))result=result.thenCompose(data->api().instances(uuid(who)).thenCompose(instances->api().fate(uuid(who)).thenApply(fate->{
                    @SuppressWarnings("unchecked") Map<String,Object> players=(Map<String,Object>)data.get("players");
                    players.put(who,map("instances",instances.stream().filter(i->i.definition().id().equals(STORY)).toList(),"fate",fate));return data;
                })));
                return result;
            })));
    }
    @SuppressWarnings("unchecked") private void verify(String phase,Map<String,Object> data)throws Exception {
        Expected want=expected(phase);
        List<String> online=(List<String>)data.get("online");require(want.offline()?online.isEmpty():online.size()==4,"Unexpected actual online audience");
        List<WorldEvent> history=(List<WorldEvent>)data.get("history");List<MemoryEntry> entries=(List<MemoryEntry>)data.get("entries");
        List<WorldEvent> sources=history.stream().filter(e->e.type().equals("EXPLORATION_DISCOVERY")).toList();
        List<WorldEvent> signals=history.stream().filter(e->e.type().equals("WORLD_MEMORY_RECORDED")).toList();
        require(sources.size()==want.sources().length()&&entries.size()==want.memories().length()&&signals.size()==entries.size(),"Memory/source counts have not reached "+phase+": sources="+sources.size()+" entries="+entries.size());
        var recipe=MemoryRecipeCatalog.load(run().resolve("fixture/memory-enabled.yml")).recipes().getFirst();
        Map<String,Object> players=(Map<String,Object>)data.get("players");
        for(String who:List.of("A","B","C","D")){
            List<WorldEvent> own=sources.stream().filter(e->e.participants().contains(uuid(who))).toList();
            require(own.size()==(want.sources().contains(who)?1:0),"Actual first-visit actor mismatch: "+who);
            WorldEvent source=own.isEmpty()?null:own.getFirst();
            if(source!=null){
                require(source.participants().equals(Set.of(uuid(who)))&&source.nations().equals(Set.of(country(who.equals("D")?"A":who).toString()))&&source.source().equals("StarChronicle.ConfiguredRegionFirstVisit"),"Actual exploration provenance mismatch");
                var facts=source.facts();require(REGION.equals(facts.get("region.id"))&&getConfig().getString("memory-world").equals(facts.get("region.world"))&&"FIRST_VISIT".equals(facts.get("discovery.kind"))&&"OBSERVED".equals(facts.get("time_basis")),"Actual region evidence mismatch");
                int x=Integer.parseInt(facts.get("region.x")),y=Integer.parseInt(facts.get("region.y")),z=Integer.parseInt(facts.get("region.z"));require(x>=1004&&x<=1006&&y>=-60&&y<=-58&&z>=0&&z<=3,"Discovery outside actual configured bounds");
            }
            List<MemoryEntry> ownEntries=entries.stream().filter(e->source!=null&&e.sourceEventId().equals(source.id())).toList();
            require(ownEntries.size()==(want.memories().contains(who)?1:0),"Disabled/replayed source generated unexpected memory: "+who);
            MemoryEntry entry=ownEntries.isEmpty()?null:ownEntries.getFirst();
            if(entry!=null){
                require(entry.recipeId().equals(RECIPE)&&entry.recipeVersion().equals("1")&&entry.regionId().equals(REGION)&&entry.title().equals(recipe.title())&&entry.text().equals(recipe.text())&&entry.definitionHash().equals(MemoryRecipeLedger.fingerprint(recipe)),"Pinned recipe content changed");
                require(entry.sourceOccurredAt().equals(source.occurredAt())&&!entry.recordedAt().isBefore(source.occurredAt()),"Memory source/recording time mismatch");
                WorldEvent signal=signals.stream().filter(e->e.id().equals(entry.signalEventId())).findFirst().orElseThrow();
                require(signal.equals(MemoryRecipeLedger.signal(entry,source)),"Memory signal changed canonical provenance");
            }
            if(want.offline())continue;
            Map<String,Object> playerData=(Map<String,Object>)players.get(who);List<StoryInstance> instances=(List<StoryInstance>)playerData.get("instances");Map<String,String> fate=(Map<String,String>)playerData.get("fate");
            List<MemoryEntry> visible=entries.stream().filter(e->sources.stream().anyMatch(s->s.id().equals(e.sourceEventId())&&s.nations().contains(country(who.equals("D")?"A":who).toString()))).toList();
            require(instances.size()==visible.size(),"Library instance audience mismatch: "+who);
            for(MemoryEntry readable:visible){
                WorldEvent original=sources.stream().filter(s->s.id().equals(readable.sourceEventId())).findFirst().orElseThrow();
                String origin=List.of("A","B","C","D").stream().filter(key->original.participants().equals(Set.of(uuid(key)))).findFirst().orElseThrow();
                String choice=want.completed().get(who+":"+origin);
                StoryInstance instance=instances.stream().filter(i->i.sourceEventId().equals(readable.signalEventId())).findFirst().orElseThrow();
                require(instance.members().keySet().equals(Set.of(uuid(who)))&&instance.definition().version().equals("1"),"Library source/actor/definition mismatch");
                require(instance.completed()==(choice!=null),"Library completion mismatch: "+who);
                if(choice==null)require(instance.node().equals("archive")&&instance.choices().isEmpty(),"Pending library state changed");
                else require(instance.choices().get("archive").equals(choice)&&instance.node().equals(choice.equals("testify")?"testimony":"reader"),"Actual library choice mismatch");
            }
            long testimonies=want.completed().entrySet().stream().filter(e->e.getKey().startsWith(who+":")&&e.getValue().equals("testify")).count();
            require(Integer.parseInt(fate.getOrDefault("player.honor","0"))==3*testimonies,"Memory granted or replayed honor: "+who);
            long endings=history.stream().filter(e->e.participants().contains(uuid(who))&&e.type().startsWith("CARAVAN_ARCHIVE_")).count();
            require(endings==want.completed().keySet().stream().filter(key->key.startsWith(who+":")).count(),"Library ending count mismatch: "+who);
        }
        if(want.offline())require(((List<?>)data.get("active")).isEmpty(),"Offline memory activated a player story without audience");
    }
    private CompletionStage<?> check(String phase){
        expected(phase);CompletableFuture<Void> done=new CompletableFuture<>();attempt(phase,System.nanoTime()+TimeUnit.SECONDS.toNanos(25),done);return done;
    }
    private void attempt(String phase,long deadline,CompletableFuture<Void> done){
        snapshot().whenComplete((data,error)->{
            Throwable problem=error;
            if(problem==null)try{verify(phase,data);log("MEMORY_CHECK",map("phase",phase,"state",data));done.complete(null);return;}catch(Throwable failure){problem=failure;}
            if(System.nanoTime()>=deadline){done.completeExceptionally(problem);return;}
            getServer().getScheduler().runTaskLater(this,()->attempt(phase,deadline,done),5L);
        });
    }
    private CompletionStage<?> replay(){return api().history(null,null,0,100).thenCompose(history->{
        CompletionStage<Void> next=CompletableFuture.completedFuture(null);
        for(WorldEvent event:history)if(event.type().equals("EXPLORATION_DISCOVERY"))next=next.thenCompose(ignored->api().submit(event).thenAccept(result->{require(result.duplicate(),"Canonical replay was not deduplicated");log("MEMORY_REPLAY",map("source",event,"duplicate",true));}));
        return next;
    });}
    private CompletionStage<?> repeat(String who){return api().instances(uuid(who)).thenCompose(instances->{
        CompletionStage<Void> next=CompletableFuture.completedFuture(null);
        for(StoryInstance instance:instances)if(instance.definition().id().equals(STORY)){
            require(instance.completed(),"Only completed real choice may be replayed");
            next=next.thenCompose(ignored->api().choose(instance.id(),uuid(who),"archive",instance.choices().get("archive")).handle((result,error)->{
                require(error!=null,"Completed choice replay was accepted");log("MEMORY_CHOICE_REJECTED",map("who",who,"instance",instance.id(),"error",error.toString()));return null;
            }));
        }
        return next;
    });}
    @Override protected CompletionStage<?> extraAction(String action,String[] args){return switch(action){
        case "memory-station"->station();case "memory-place"->place(args[1]);case "memory-check"->check(args[1]);
        case "memory-replay"->replay();case "memory-repeat"->repeat(args[1]);default->super.extraAction(action,args);
    };}
}
