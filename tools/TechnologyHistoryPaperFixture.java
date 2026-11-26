package smoke;

import dev.starcore.starcore.module.nation.NationService;
import dev.starcore.starcore.module.nation.model.NationId;
import dev.starcore.starcore.module.technology.*;
import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletionStage;
import org.bukkit.command.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Isolated administrative native unlock/revoke acceptance, without player attribution,
 * fake research callbacks, provider substitution, or direct product database writes. */
public final class TechnologyHistoryPaperFixture extends JavaPlugin implements CommandExecutor {
    private Path run;
    private TechnologyService technology;
    private ChronicleService api;
    private NationId nation;
    private String key;
    private int boot;

    @Override public void onEnable() {
        try {
            run=getDataFolder().toPath().toAbsolutePath().normalize().getParent().getParent();
            require(run.getFileName().toString().startsWith("paper-technology-"),"Isolated directory required");
            require(hash(run.resolve("plugins/StarChronicle.jar")).equals(getConfig().getString("product-sha")),"Chronicle hash drift");
            require(hash(run.resolve("plugins/StarCore.jar")).equals(getConfig().getString("native-sha")),"Native hash drift");
            Plugin core=Objects.requireNonNull(getServer().getPluginManager().getPlugin("StarCore"));
            Object context=core.getClass().getMethod("context").invoke(core);
            Object registry=context.getClass().getMethod("serviceRegistry").invoke(context);
            technology=service(registry,TechnologyService.class);
            api=Objects.requireNonNull(getServer().getServicesManager().load(ChronicleService.class));
            boot=getConfig().getInt("boot")+1;getConfig().set("boot",boot);
            if(boot==1) {
                require(technology.technologyChangesAfter(0,128).isEmpty(),"Fresh source log required");
                var created=service(registry,NationService.class).createNation(UUID.nameUUIDFromBytes("TechnologyHistoryFixture".getBytes(StandardCharsets.UTF_8)),"HistoryFixture","HistoryAcceptance");
                nation=created.id();key=technology.availableTechnologies().stream().sorted().findFirst().orElseThrow();
                getConfig().set("nation",nation.toString());getConfig().set("key",key);
                mutate(3); // Native history exists before Chronicle's first scheduled source poll.
            } else { nation=NationId.fromString(getConfig().getString("nation"));key=getConfig().getString("key"); }
            saveConfig();Objects.requireNonNull(getCommand("technologyverify")).setExecutor(this);
            log("READY",Map.of("domain",technology.historyAuthorityDomainId().orElseThrow(),"nation",nation.toString(),"key",key));
        } catch(Throwable error) { fail(error);getServer().getPluginManager().disablePlugin(this); }
    }
    private static <T>T service(Object registry,Class<T> type)throws Exception {
        return type.cast(((Optional<?>)registry.getClass().getMethod("find",Class.class).invoke(registry,type)).orElseThrow());
    }
    private List<TechnologyChangeReceipt> receipts() {
        List<TechnologyChangeReceipt> result=new ArrayList<>();long after=0;
        while(true) {
            var page=technology.technologyChangesAfter(after,128);result.addAll(page);
            require(result.size()<=256,"Fixture source exceeded bounded scenario");
            if(page.size()<128)return List.copyOf(result);
            after=page.getLast().sequence();
        }
    }
    private void mutate(int count) {
        require(getServer().getOnlinePlayers().isEmpty(),"Fixture only permits no-player administration");
        require(count>=1&&count<=130,"Mutation count outside scenario");
        synchronized(technology) {
            for(int i=0;i<count;i++) {
                boolean prior=technology.hasTechnology(nation,key);
                require(prior?technology.revoke(nation,key):technology.unlock(nation,key),"Native change was rejected");
                require(technology.hasTechnology(nation,key)!=prior,"Native state did not change");
            }
        }
        var records=receipts();
        log("NATIVE",Map.of("count",records.size(),"records",records.stream().map(r->Map.of(
                "domain",r.authorityDomainId().toString(),"sequence",r.sequence(),"operation",r.operationId().toString(),
                "nation",r.nationId().toString(),"key",r.technologyKey(),"era",r.era(),"change",r.change().name(),"time",r.committedAt().toString())).toList()));
    }
    private CompletionStage<?> check(int count) {
        var receipts=receipts();require(receipts.size()==count,"Unexpected native source count");
        return api.history(null,null,0,100).thenCombine(api.history(null,null,1,100),(first,second)->{
            List<WorldEvent> all=new ArrayList<>(first);all.addAll(second);
            var events=all.stream().filter(e->e.facts().getOrDefault("history.namespace","").equals("starcore.technology")).toList();
            require(events.size()==count,"Unexpected committed history count="+events.size());
            require(all.stream().noneMatch(e->e.id().startsWith("starcore:research:")||e.facts().getOrDefault("observation.namespace","").equals("starcore.technologies")),"Duplicate fallback technology event");
            for(var receipt:receipts) {
                String id="starcore:technology:"+receipt.authorityDomainId()+":"+receipt.operationId();
                var event=events.stream().filter(e->e.id().equals(id)).findFirst().orElseThrow();
                require(event.occurredAt().equals(receipt.committedAt()),"Lost native timestamp precision");
                require(event.nations().equals(Set.of(nation.toString()))&&event.participants().isEmpty(),"False historical attribution");
                require(event.type().equals(receipt.change()==TechnologyChangeReceipt.Change.UNLOCKED?"TECHNOLOGY_BREAKTHROUGH":"TECHNOLOGY_REVOKED"),"Wrong change kind");
                require(event.facts().get("history.sequence").equals(Long.toString(receipt.sequence())),"Wrong source sequence");
            }
            log("CHECK",Map.of("count",count,"events",events));return count;
        });
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        if(!(sender instanceof ConsoleCommandSender))return false;
        try {
            switch(args[0]) {
                case "check" -> check(Integer.parseInt(args[1])).whenComplete((value,error)->{if(error!=null)fail(error);else log("DONE",Map.of("action","check","count",value));});
                case "burst" -> {mutate(Integer.parseInt(args[1]));log("DONE",Map.of("action","burst"));}
                case "offline" -> {
                    require(getServer().getOnlinePlayers().isEmpty(),"Offline maintenance required");
                    getServer().getPluginManager().disablePlugin(Objects.requireNonNull(getServer().getPluginManager().getPlugin("StarChronicle")));
                    mutate(3);log("DONE",Map.of("action","offline"));
                }
                default -> throw new IllegalArgumentException("Unknown fixture command");
            }
        } catch(Throwable error) {fail(error);}
        return true;
    }
    private void fail(Throwable error) {while(error.getCause()!=null)error=error.getCause();log("FAILED",Map.of("error",error.toString()));error.printStackTrace();}
    private synchronized void log(String kind,Object data) {
        getLogger().info("TECH_"+kind+" "+SqliteStore.json(data));
        try {Files.writeString(run.resolve("evidence/fixture.jsonl"),SqliteStore.json(Map.of("time",Instant.now().toString(),"boot",boot,"kind",kind,"data",data))+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
        catch(Exception error){throw new IllegalStateException(error);}
    }
    private static String hash(Path file)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));}
    private static void require(boolean value,String reason){if(!value)throw new IllegalStateException(reason);}
    @Override public void onDisable(){if(run!=null)log("CLOSED",Map.of("normalDisable",true));}
}
