package smoke;

import dev.starcore.starcore.module.nation.NationService;
import dev.starcore.starcore.module.nation.model.NationId;
import dev.starcore.starcore.module.city.CityService;
import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletionStage;
import org.bukkit.Location;
import org.bukkit.command.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Calls real native nation/city services for fixed offline fixture identities. No fake source
 * provider, client, production installation, or direct source/database state mutation. */
public final class CapitalHistoryPaperFixture extends JavaPlugin implements CommandExecutor {
    private Path run;
    private NationService nations;
    private ChronicleService api;
    private NationId nation;
    private UUID cityA, cityB, foreignCity;
    private final UUID founder=id("CapitalFounder"), successor=id("CapitalSuccessor"), foreign=id("CapitalForeign");
    private int boot;
    private boolean busy;

    @Override public void onEnable() {
        try {
            run=getDataFolder().toPath().toAbsolutePath().normalize().getParent().getParent();
            require(run.getFileName().toString().startsWith("paper-capital-"),"Isolated directory required");
            require(hash(run.resolve("plugins/StarChronicle.jar")).equals(getConfig().getString("product-sha")),"Chronicle hash drift");
            require(hash(run.resolve("plugins/StarCore.jar")).equals(getConfig().getString("native-sha")),"Native hash drift");
            Plugin core=Objects.requireNonNull(getServer().getPluginManager().getPlugin("StarCore"));
            Object context=core.getClass().getMethod("context").invoke(core);
            Object registry=context.getClass().getMethod("serviceRegistry").invoke(context);
            nations=service(registry,NationService.class);
            CityService cities=service(registry,CityService.class);
            api=Objects.requireNonNull(getServer().getServicesManager().load(ChronicleService.class));
            boot=getConfig().getInt("boot")+1;getConfig().set("boot",boot);
            if(boot==1) {
                require(nations.capitalChangesAfter(0,128).isEmpty(),"Fresh source log required");
                nation=nations.createNation(founder,"CapitalFounder","CapitalAcceptance").id();
                require(nations.addMember(nation,successor,"CapitalSuccessor"),"Could not add successor");
                NationId other=nations.createNation(foreign,"CapitalForeign","ForeignCapital").id();
                var world=getServer().getWorlds().getFirst();
                var a=cities.createCity(nation,"CapitalAlpha",founder,new Location(world,0,65,0));
                var b=cities.createCity(nation,"CapitalBeta",successor,new Location(world,32,65,0));
                var f=cities.createCity(other,"CapitalForeign",foreign,new Location(world,64,65,0));
                require(a.success()&&b.success()&&f.success(),"Native city creation rejected");
                cityA=a.city().id();cityB=b.city().id();foreignCity=f.city().id();
                getConfig().set("nation",nation.toString());getConfig().set("city-a",cityA.toString());
                getConfig().set("city-b",cityB.toString());getConfig().set("foreign-city",foreignCity.toString());
                for(int i=0;i<3;i++)change();
                sourceLog();
            } else {
                nation=NationId.fromString(getConfig().getString("nation"));cityA=UUID.fromString(getConfig().getString("city-a"));
                cityB=UUID.fromString(getConfig().getString("city-b"));foreignCity=UUID.fromString(getConfig().getString("foreign-city"));
                require(cities.getCity(cityA).isPresent()&&cities.getCity(cityB).isPresent(),"Native cities did not survive restart");
            }
            saveConfig();Objects.requireNonNull(getCommand("capitalverify")).setExecutor(this);
            log("READY",Map.of("domain",nations.capitalHistoryAuthorityDomainId().orElseThrow(),"nation",nation.toString(),
                    "cityA",cityA.toString(),"cityB",cityB.toString(),"founder",founder.toString(),"successor",successor.toString()));
            rejections();
        } catch(Throwable error) { fail(error);getServer().getPluginManager().disablePlugin(this); }
    }
    private static UUID id(String name){return UUID.nameUUIDFromBytes(("OfflinePlayer:"+name).getBytes(StandardCharsets.UTF_8));}
    private static <T>T service(Object registry,Class<T> type)throws Exception {
        return type.cast(((Optional<?>)registry.getClass().getMethod("find",Class.class).invoke(registry,type)).orElseThrow());
    }
    private List<NationService.CapitalChangeReceipt> receipts() {
        List<NationService.CapitalChangeReceipt> result=new ArrayList<>();long after=0;
        while(true) {
            var page=nations.capitalChangesAfter(after,128);result.addAll(page);
            require(result.size()<=256,"Fixture source exceeded bounded scenario");
            if(page.size()<128)return List.copyOf(result);
            after=page.getLast().designation().sequence();
        }
    }
    private UUID actor(){return nations.nationById(nation).orElseThrow().founderId();}
    private void change() {
        require(getServer().getOnlinePlayers().isEmpty(),"Fixture requires offline identities only");
        UUID previous=nations.designatedCapital(nation).map(NationService.CapitalDesignation::cityId).orElse(null);
        UUID next=cityA.equals(previous)?cityB:cityA;
        var entry=nations.designateCapital(UUID.randomUUID(),nation,actor(),next);
        require(entry.equals(nations.designatedCapital(nation).orElseThrow()),"Native capital state mismatch");
    }
    private void sourceLog() {
        var records=receipts();
        log("NATIVE",Map.of("count",records.size(),"records",records.stream().map(r->{
            var d=r.designation();Map<String,Object> row=new TreeMap<>();row.put("domain",r.authorityDomainId().toString());
            row.put("sequence",d.sequence());row.put("operation",d.operationId().toString());row.put("nation",d.nationId().toString());
            row.put("previous",d.previousCityId()==null?null:d.previousCityId().toString());row.put("city",d.cityId().toString());
            row.put("actor",d.actorId().toString());row.put("time",d.committedAt().toString());return row;
        }).toList()));
    }
    private void mutate(int remaining,String action) {
        try {
            int count=Math.min(5,remaining);for(int i=0;i<count;i++)change();
            if(remaining>count)getServer().getScheduler().runTaskLater(this,()->mutate(remaining-count,action),1);
            else {busy=false;sourceLog();log("DONE",Map.of("action",action));}
        }catch(Throwable error){busy=false;fail(error);}
    }
    private void rejections() {
        var before=receipts();var current=nations.designatedCapital(nation).orElseThrow();
        UUID unauthorized=actor().equals(founder)?successor:founder;
        expect(SecurityException.class,()->nations.designateCapital(UUID.randomUUID(),nation,unauthorized,current.cityId()));
        expect(SecurityException.class,()->nations.designateCapital(UUID.randomUUID(),nation,actor(),foreignCity));
        expect(IllegalArgumentException.class,()->nations.designateCapital(UUID.randomUUID(),nation,actor(),current.cityId()));
        require(current.equals(nations.designateCapital(current.operationId(),nation,current.actorId(),current.cityId())),"Retry did not return original receipt");
        require(before.equals(receipts()),"Rejected or retried operations added source history");
        log("AUTHORITY",Map.of("count",before.size(),"actor",actor().toString(),"rejectedActor",unauthorized.toString(),"replaySequence",current.sequence()));
    }
    private static void expect(Class<? extends RuntimeException> type,Runnable work) {
        try {work.run();}catch(RuntimeException error){if(type.isInstance(error))return;throw error;}
        throw new IllegalStateException("Expected rejection: "+type.getName());
    }
    private CompletionStage<?> check(int count) {
        var receipts=receipts();require(receipts.size()==count,"Unexpected native source count");
        return api.history(null,null,0,100).thenCombine(api.history(null,null,1,100),(first,second)->{
            List<WorldEvent> all=new ArrayList<>(first);all.addAll(second);
            var events=all.stream().filter(e->e.facts().getOrDefault("history.namespace","").equals("starcore.capitals")).toList();
            require(events.size()==count,"Unexpected committed history count="+events.size());
            require(all.stream().noneMatch(e->e.facts().getOrDefault("observation.namespace","").equals("starcore.capitals")),"Duplicate fallback capital event");
            for(var receipt:receipts) {
                var d=receipt.designation();String id="starcore:capital:"+receipt.authorityDomainId()+":"+d.operationId();
                var event=events.stream().filter(e->e.id().equals(id)).findFirst().orElseThrow();
                require(event.occurredAt().equals(d.committedAt()),"Lost native timestamp precision");
                require(event.nations().equals(Set.of(nation.toString()))&&event.participants().equals(Set.of(d.actorId())),"False historical attribution");
                require(event.type().equals(d.previousCityId()==null?"CAPITAL_DESIGNATED":"CAPITAL_MOVED"),"Wrong change kind");
                require(event.facts().get("history.sequence").equals(Long.toString(d.sequence())),"Wrong source sequence");
                require(event.facts().get("city.id").equals(d.cityId().toString()),"Wrong destination city");
                require(Objects.equals(event.facts().get("capital.previous"),d.previousCityId()==null?null:d.previousCityId().toString()),"Wrong previous city");
            }
            log("CHECK",Map.of("count",count,"events",events));return count;
        });
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        if(!(sender instanceof ConsoleCommandSender))return false;
        try {
            require(!busy,"Mutation already active");
            switch(args[0]) {
                case "check" -> check(Integer.parseInt(args[1])).whenComplete((value,error)->{if(error!=null)fail(error);else log("DONE",Map.of("action","check","count",value));});
                case "burst" -> {int count=Integer.parseInt(args[1]);require(count>=1&&count<=130,"Invalid count");busy=true;mutate(count,"burst");}
                case "offline" -> {
                    require(getServer().getOnlinePlayers().isEmpty(),"Offline maintenance required");
                    getServer().getPluginManager().disablePlugin(Objects.requireNonNull(getServer().getPluginManager().getPlugin("StarChronicle")));
                    require(nations.transferOwnership(nation,founder,successor,"CapitalSuccessor"),"Native ownership transfer failed");
                    busy=true;mutate(130,"offline");
                }
                default -> throw new IllegalArgumentException("Unknown fixture command");
            }
        } catch(Throwable error) {fail(error);}
        return true;
    }
    private void fail(Throwable error) {while(error.getCause()!=null)error=error.getCause();log("FAILED",Map.of("error",error.toString()));error.printStackTrace();}
    private synchronized void log(String kind,Object data) {
        getLogger().info("CAPITAL_"+kind+" "+SqliteStore.json(data));
        try {Files.writeString(run.resolve("evidence/fixture.jsonl"),SqliteStore.json(Map.of("time",Instant.now().toString(),"boot",boot,"kind",kind,"data",data))+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
        catch(Exception error){throw new IllegalStateException(error);}
    }
    private static String hash(Path file)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));}
    private static void require(boolean value,String reason){if(!value)throw new IllegalStateException(reason);}
    @Override public void onDisable(){if(run!=null)log("CLOSED",Map.of("normalDisable",true));}
}
