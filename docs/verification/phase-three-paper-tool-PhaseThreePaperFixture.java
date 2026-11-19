package smoke;

import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition;
import io.github.addxiaoyi.starchronicle.core.RuleEngine;
import io.github.addxiaoyi.starchronicle.core.StoryRuntime;
import io.github.addxiaoyi.starchronicle.core.StoryInstance;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CompletionStage;
import org.bukkit.GameMode;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Isolated acceptance adapter: no database access, reward setters, fabricated snapshots,
 * source reconstruction or country creation. inspect intentionally resumes pending triggers. */
public final class PhaseThreePaperFixture extends JavaPlugin implements CommandExecutor {
    private static final String ENDING="chronicle:975377c7394a74dbaafc65237cf43cdccda38990fe1b2ef3f46b428f6464ebe7";
    private static final String DISCOVERY="phase-two:heir-discovery:1";
    private static final String READER="phase-three-catalog-reader";
    private static final Map<String,UUID> PLAYERS=Map.of(
            "A",UUID.fromString("08ffd450-5001-3023-a480-057fef707037"),
            "B",UUID.fromString("28a86e57-337f-3ee8-8593-6eed0ce4d3d6"),
            "C",UUID.fromString("140669be-0a1a-3206-a8b1-20d23a596b3a"),
            "D",UUID.fromString("63e2bb34-acdd-360c-a4b5-57c748f2ef53"));
    private static final Map<String,String> NATIONS=Map.of(
            "A","d0ad16cb-6c42-41fd-9930-1e1b648c48da","B","25f77bd3-9110-44b5-a6c3-aee49ed19558",
            "C","b0bbe965-8d52-4d4b-9b71-9a01a7e58d54","D","fd61755c-7d80-40cf-aa0b-5737c710c8f4");
    private static final List<String> REFERENCES=List.of(
            "past.player.choice.last-heir/1/council","past.resolution.world.last-heir/1/council",
            "past.player.choice.heir-legacy/1/archive","past.player.choice.heir-legacy/2/new-record",
            "past.player.choice.phase-three-catalog-reader/1/recall",
            "chronicle.history.player.HEIR_RESTORATION_SUPPORTED","chronicle.history.nation.HEIR_RESTORATION_SUPPORTED");
    private ChronicleService api;
    private Plugin core,chronicle;
    private Object registry;
    private Path run;
    private final Map<UUID,PermissionAttachment> permissions=new HashMap<>();

    @Override public void onEnable(){
        try{
            run=getDataFolder().toPath().toAbsolutePath().normalize().getParent().getParent();
            require(run.getFileName().toString().matches("phase-three-paper-[a-z0-9][a-z0-9-]{0,63}")&&Files.isRegularFile(run.resolve("preparation.json")),"Fixture requires its fresh isolated prepared clone");
            core=Objects.requireNonNull(getServer().getPluginManager().getPlugin("StarCore"));
            chronicle=Objects.requireNonNull(getServer().getPluginManager().getPlugin("StarChronicle"));
            registry=call(call(core,"context"),"serviceRegistry");
            api=Objects.requireNonNull(getServer().getServicesManager().load(ChronicleService.class));
            Objects.requireNonNull(getCommand("phase3")).setExecutor(this);
            log("READY",Map.of("fixtureOnly",true,"core",core.isEnabled(),"service",true));
        }catch(Throwable error){fail("enable",error);getServer().getPluginManager().disablePlugin(this);}
    }
    private static void require(boolean okay,String message){if(!okay)throw new IllegalStateException(message);}
    private void log(String kind,Object value){getLogger().info("PHASE3_"+kind+" "+SqliteStore.json(value));}
    private void fail(String operation,Throwable error){getLogger().severe("PHASE3_FAILED "+SqliteStore.json(Map.of("operation",operation,"error",error.toString())));error.printStackTrace();}
    private Player player(String shortName){
        UUID expected=PLAYERS.get(shortName);require(expected!=null,"Unknown original client");
        Player p=getServer().getPlayerExact("PhaseTwo"+shortName);
        require(p!=null&&p.isOnline()&&p.getUniqueId().equals(expected),"Original real client is not connected");return p;
    }
    private Object nations()throws Exception{
        Class<?> type=Class.forName("dev.starcore.starcore.module.nation.NationService",true,core.getClass().getClassLoader());
        return ((Optional<?>)call(registry,"find",type)).orElseThrow();
    }
    private void seed()throws Exception{
        Object service=nations();Set<String> countries=new HashSet<>();
        for(String who:List.of("A","B","C","D")){
            Player p=player(who);require(!p.isOp(),"Acceptance clients must remain non-operators");
            PermissionAttachment old=permissions.get(p.getUniqueId());
            if(old==null||old.getPermissible()!=p){old=p.addAttachment(this);permissions.put(p.getUniqueId(),old);}
            old.setPermission("starchronicle.use",true);old.setPermission("starchronicle.admin",false);old.setPermission("starcore.nation.ally.add",false);
            p.recalculatePermissions();p.setGameMode(GameMode.CREATIVE);
            Object nation=((Optional<?>)call(service,"nationOf",p.getUniqueId())).orElseThrow();
            Object id=call(nation,"id");require(id.toString().equals(NATIONS.get(who)),"Cloned native membership differs from the actual source");
            require(Boolean.TRUE.equals(call(service,"isFounder",p.getUniqueId(),id)),"Original native founder is absent");countries.add(id.toString());
            log("IDENTITY",Map.of("player",who,"uuid",p.getUniqueId(),"nation",id.toString(),"founder",true,
                    "op",p.isOp(),"use",p.hasPermission("starchronicle.use"),"admin",p.hasPermission("starchronicle.admin")));
        }
        require(countries.size()==4,"Four original native nations must remain distinct");
        log("SEEDED",Map.of("actualPlayers",4,"distinctNations",countries.size(),"nativeMutation",false));
    }
    private CompletionStage<WorldEvent> canonical(String id){
        return api.history(null,null,0,100).thenApply(items->items.stream().filter(e->e.id().equals(id)).findFirst().orElseThrow(()->new IllegalStateException("Retained canonical source not in bounded evidence history")));
    }
    private void replay(String which){
        String id=switch(which){case "ending"->ENDING;case "discovery"->DISCOVERY;default->throw new IllegalArgumentException("ending|discovery");};
        canonical(id).thenCompose(event->{log("CANONICAL_SOURCE",event);return api.submit(event);})
                .whenComplete((report,error)->{if(error!=null)fail("canonical-replay",error);else log("SOURCE_REPLAY",Map.of("source",id,"duplicate",report.duplicate(),"instances",report.instanceIds()));});
    }
    private void evaluate(String story,String version,boolean reject){
        api.evaluateHistorical(ENDING,story,version).whenComplete((report,error)->{
            if(reject){if(error==null)fail("expected-evaluation-rejection",new IllegalStateException("Retired or wrong version unexpectedly enabled"));else log("EVALUATION_REJECTED",Map.of("story",story,"version",version,"error",error.toString()));}
            else if(error!=null)fail("explicit-api-evaluation",error);else log("EVALUATED",report);
        });
    }
    private void facts(String who){
        UUID id=player(who).getUniqueId();
        api.recollections(id,REFERENCES).whenComplete((value,error)->{if(error!=null)fail("recollections",error);else log("RECOLLECTIONS",Map.of("player",who,"facts",value));});
        api.fate(id).whenComplete((value,error)->{if(error!=null)fail("fate",error);else log("FATE",Map.of("player",who,"facts",value));});
        api.npc(id,"ancient-heir").whenComplete((value,error)->{if(error!=null)fail("npc",error);else log("NPC",Map.of("player",who,"reputation",value.reputation(),"trust",value.trust(),"growth",value.growth(),"dialogue",value.dialogueId()));});
    }
    private void inspect(String who){
        facts(who);UUID id=player(who).getUniqueId();
        api.instances(id).whenComplete((items,error)->{
            if(error!=null){fail("instances",error);return;}
            var relevant=items.stream().filter(i->Set.of("last-heir","heir-legacy",READER).contains(i.definition().id()))
                    .map(i->Map.of("id",i.id(),"story",i.definition().id(),"version",i.definition().version(),"node",i.node(),
                            "completed",i.completed(),"scope",i.scopeKey(),"source",i.sourceEventId(),"members",i.members(),"choices",i.choices())).toList();
            log("INSTANCES",Map.of("player",who,"instances",relevant));
        });
    }
    private static Object field(Object object,String name)throws Exception{
        Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);
    }
    private static String fingerprint(Object value)throws Exception{
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(SqliteStore.json(value).getBytes(StandardCharsets.UTF_8)));
    }
    private void runtime(String label)throws Exception{
        StoryRuntime current=(StoryRuntime)field(field(chronicle,"engine"),"runtime");
        List<Map<String,Object>> enabled=new ArrayList<>();
        for(StoryDefinition d:current.enabledDefinitions())enabled.add(Map.of("story",d.id(),"version",d.version(),"title",d.title(),"fingerprint",fingerprint(d)));
        var archived=current.catalog().entries().entrySet().stream().map(e->Map.of("story",e.getKey().storyId(),"version",e.getKey().version(),"variants",e.getValue().stream().map(v->v.fingerprint()).toList())).toList();
        log("RUNTIME",Map.of("label",label,"enabled",enabled,"catalog",archived));
    }
    private Path stories(){return chronicle.getDataFolder().toPath().resolve("stories");}
    private void install()throws Exception{
        Path old=stories().resolve("heir-legacy.yml"),retired=stories().resolve("heir-legacy.yml.phase-three-retired");
        if(Files.exists(old)){require(!Files.exists(retired),"Refusing to overwrite retired original bytes");Files.move(old,retired);}
        require(Files.isRegularFile(retired),"Original A1 file was not preserved");
        for(String name:List.of("heir-legacy-v2.json","phase-three-catalog-reader.json")){
            Path from=run.resolve("fixture-content").resolve(name),to=stories().resolve(name);
            require(Files.isRegularFile(from),"Missing prepared fixture content");
            if(Files.exists(to))require(Arrays.equals(Files.readAllBytes(from),Files.readAllBytes(to)),"Refusing to replace changed fixture content");
            else Files.copy(from,to);
        }
        log("CONFIG_INSTALLED_NOT_RELOADED",Map.of("retired","heir-legacy/1","current",List.of("heir-legacy/2",READER+"/1")));
    }
    private void badConfig()throws Exception{
        Path file=stories().resolve("heir-legacy-v2.json");StoryDefinition d=SqliteStore.decode(Files.readString(file),StoryDefinition.class);
        require(d.id().equals("heir-legacy")&&d.version().equals("2"),"Unexpected test content identity");
        StoryDefinition changed=new StoryDefinition(d.id(),d.version(),d.title()+" (rejected same-version edit)",d.scope(),d.triggers(),d.condition(),d.start(),d.cooldownSeconds(),d.nodes());
        Files.writeString(file,SqliteStore.json(changed),StandardCharsets.UTF_8);
        log("BAD_CONFIG_WRITTEN_NOT_RELOADED",Map.of("story",d.id(),"version",d.version(),"changed","title only"));
    }
    private void restoreConfig()throws Exception{
        Files.copy(run.resolve("fixture-content/heir-legacy-v2.json"),stories().resolve("heir-legacy-v2.json"),StandardCopyOption.REPLACE_EXISTING);
        log("VALID_CONFIG_RESTORED_NOT_RELOADED",Map.of("story","heir-legacy","version","2"));
    }
    private void completedReplay(String who,String story,String version,String choice){
        UUID player=player(who).getUniqueId();
        api.instances(player).thenCompose(items->{StoryInstance item=items.stream().filter(i->i.definition().id().equals(story)&&i.definition().version().equals(version)&&i.completed()).findFirst().orElseThrow();return api.choose(item.id(),player,item.node(),choice);})
                .whenComplete((report,error)->{if(error==null)fail("completed-replay",new IllegalStateException("Completed choice was accepted"));else log("COMPLETED_REJECTED",Map.of("player",who,"story",story,"version",version,"error",error.toString()));});
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
        if(sender instanceof Player){sender.sendMessage("Isolated console fixture only");return true;}
        try{
            switch(args.length==0?"help":args[0]){
                case "seed"->seed();case "install"->install();case "bad-config"->badConfig();case "restore-config"->restoreConfig();
                case "runtime"->runtime(args[1]);case "facts"->facts(args[1]);case "inspect"->inspect(args[1]);
                case "replay"->replay(args[1]);case "evaluate"->evaluate(args[1],args[2],false);case "reject-evaluate"->evaluate(args[1],args[2],true);
                case "complete-replay"->completedReplay(args[1],args[2],args[3],args[4]);
                case "phase"->log("PHASE",Map.of("name",String.join(" ",Arrays.copyOfRange(args,1,args.length))));
                default->log("HELP",Map.of("commands","seed|install|runtime LABEL|facts A|inspect A|evaluate STORY VERSION|reject-evaluate STORY VERSION|replay ending|bad-config|restore-config|complete-replay A STORY VERSION CHOICE|phase LABEL"));
            }
        }catch(Throwable error){fail(args.length==0?"command":args[0],error);}return true;
    }
    private static Object call(Object target,String name,Object...args)throws Exception{
        require(target!=null,"Missing native object for "+name);
        for(Method method:target.getClass().getMethods()){
            if(!method.getName().equals(name)||method.getParameterCount()!=args.length)continue;
            boolean fits=true;Class<?>[] types=method.getParameterTypes();
            for(int i=0;i<types.length;i++)if(args[i]!=null&&!box(types[i]).isInstance(args[i])){fits=false;break;}
            if(fits)return method.invoke(target,args);
        }
        throw new NoSuchMethodException(target.getClass().getName()+"."+name);
    }
    private static Class<?> box(Class<?> type){if(!type.isPrimitive())return type;if(type==boolean.class)return Boolean.class;if(type==int.class)return Integer.class;if(type==long.class)return Long.class;if(type==double.class)return Double.class;return type;}
}
