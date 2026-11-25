package smoke;

import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.core.StoryInstance;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.java.JavaPlugin;

/** Isolated physical exploration acceptance. Never submits an event, alters a database,
 * invokes a narrative choice, changes an objective, or grants a product reward. */
public final class ExplorationPaperFixture extends JavaPlugin implements Listener,CommandExecutor {
    private final UUID actor=UUID.nameUUIDFromBytes("OfflinePlayer:ChronicleScout".getBytes(StandardCharsets.UTF_8));
    private final AtomicBoolean cancelTeleport=new AtomicBoolean();
    private final Object logLock=new Object();
    private Path run;private UUID worldId;private ChronicleService api;private int boot;private volatile boolean stationReady;private boolean folia;
    private static void require(boolean value,String message){if(!value)throw new IllegalStateException(message);}
    private static Map<String,Object> map(Object...pairs){Map<String,Object> value=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)value.put(pairs[i].toString(),pairs[i+1]);return value;}
    @Override public void onEnable(){try{
        run=getDataFolder().toPath().toAbsolutePath().normalize().getParent().getParent();
        String sha=getConfig().getString("product-sha","");require(sha.matches("[0-9a-f]{64}")&&run.getFileName().toString().startsWith("exploration-"+sha.substring(0,8)+"-"),"Require SHA-bound isolated exploration run");
        require(digest(run.resolve("plugins/StarChronicle.jar")).equals(sha),"Frozen product SHA changed");
        worldId=UUID.fromString(getConfig().getString("world-uuid",""));require(getServer().getWorld(worldId)!=null,"Pinned fresh world UUID is absent");
        require(getServer().getPluginManager().getPlugin("StarCore")==null,"This acceptance is explicitly standalone");
        api=Objects.requireNonNull(getServer().getServicesManager().load(ChronicleService.class));
        boot=getConfig().getInt("boot-count",0)+1;getConfig().set("boot-count",boot);saveConfig();
        try{Class.forName("io.papermc.paper.threadedregions.RegionizedServer");folia=true;}catch(ClassNotFoundException paper){}
        Objects.requireNonNull(getCommand("exploreverify")).setExecutor(this);getServer().getPluginManager().registerEvents(this,this);
        log("READY",map("product_sha256",sha,"world",worldId,"player",actor,"standalone",true,"noSyntheticSource",true));
        if(folia)log("CANCELLATION_UNSUPPORTED",map("platform","folia","api","Player.teleportAsync","available",false,"reason","Folia teleportAsync did not dispatch PlayerTeleportEvent in the preserved runtime probe; cancellable teleport coverage is unavailable."));
    }catch(Throwable error){fail("enable",error);getServer().getPluginManager().disablePlugin(this);}}
    @Override public void onDisable(){if(run!=null)log("CLOSED",map("normalPluginDisable",true));}
    private void log(String kind,Object value){String json=SqliteStore.json(map("boot",boot,"time",Instant.now().toString(),"kind",kind,"value",value));getLogger().info("EXPLORATION_"+kind+" "+SqliteStore.json(value));if(run!=null)synchronized(logLock){try{Files.writeString(run.resolve("evidence/fixture.jsonl"),json+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(Exception error){getLogger().severe("Fixture evidence append failed: "+error);}}}
    private void fail(String operation,Throwable error){while((error instanceof CompletionException||error instanceof ExecutionException)&&error.getCause()!=null)error=error.getCause();log("FAILED",map("operation",operation,"error",error.toString()));error.printStackTrace();}
    private World world(){return Objects.requireNonNull(getServer().getWorld(worldId));}
    private void owner(String operation,java.util.function.Consumer<Player> action){Player p=getServer().getPlayer(actor);require(p!=null&&p.isOnline(),"Require actual connected pinned client");require(p.getScheduler().execute(this,()->{try{require(p.isOnline()&&p.getUniqueId().equals(actor)&&!p.isOp(),"Non-OP owner identity unavailable");action.accept(p);}catch(Throwable error){fail(operation,error);}},()->fail(operation,new IllegalStateException("Player scheduler retired")),1L),"Player scheduler rejected fixture operation");}
    private void station(){World w=world();List<CompletableFuture<Void>> all=new ArrayList<>();for(int chunkX:List.of(62,63))for(int chunkZ:List.of(-1,0)){
        CompletableFuture<Void> ready=new CompletableFuture<>();all.add(ready);getServer().getRegionScheduler().execute(this,w,chunkX,chunkZ,()->{try{int lowX=Math.max(996,chunkX*16),highX=Math.min(1020,chunkX*16+15),lowZ=Math.max(-2,chunkZ*16),highZ=Math.min(4,chunkZ*16+15);for(int x=lowX;x<=highX;x++)for(int z=lowZ;z<=highZ;z++){w.getBlockAt(x,-61,z).setType(Material.STONE,false);for(int y=-60;y<=-57;y++)w.getBlockAt(x,y,z).setType(Material.AIR,false);}ready.complete(null);}catch(Throwable error){ready.completeExceptionally(error);}});
        }CompletableFuture.allOf(all.toArray(CompletableFuture[]::new)).whenComplete((ignored,error)->{if(error!=null)fail("station",error);else{stationReady=true;log("STATION_READY",map("world",worldId,"minX",996,"maxX",1020,"y",-60,"fixtureOnly",true));}});
    }
    private void start(){require(stationReady,"Wait for STATION_READY before start");owner("start",p->{PermissionAttachment attachment=p.addAttachment(this);attachment.setPermission("starchronicle.use",true);attachment.setPermission("starchronicle.admin",false);p.recalculatePermissions();p.setGameMode(GameMode.SURVIVAL);p.setFoodLevel(20);require(!p.isOp()&&p.hasPermission("starchronicle.use")&&!p.hasPermission("starchronicle.admin"),"Client permission contract failed");teleport(p,"outside",new Location(world(),1000.5,-60,1.5));});}
    private void teleport(Player p,String kind,Location destination){p.teleportAsync(destination).whenComplete((ok,error)->{if(error!=null){fail("teleport-"+kind,error);return;}p.getScheduler().execute(this,()->log("TELEPORT_RESULT",map("kind",kind,"accepted",ok,"player",actor,"world",p.getWorld().getUID(),"x",p.getLocation().getX(),"y",p.getLocation().getY(),"z",p.getLocation().getZ())),()->fail("teleport-result",new IllegalStateException("Player retired")),1L);});}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void cancel(PlayerTeleportEvent event){if(event.getPlayer().getUniqueId().equals(actor)&&event.getTo()!=null&&event.getTo().getBlockX()>=1011&&event.getTo().getBlockX()<=1013&&cancelTeleport.compareAndSet(true,false)){event.setCancelled(true);log("TELEPORT_CANCELLED",map("player",actor,"targetX",event.getTo().getX(),"fixtureOnly",true));}}
    private void check(String phase){
        api.history(null,actor,0,100).thenCompose(history->api.instances(actor).thenCompose(instances->api.fate(actor).thenApply(fate->map("history",history,"instances",instances,"fate",fate)))).whenComplete((data,error)->{if(error!=null){fail("check-"+phase,error);return;}try{
            @SuppressWarnings("unchecked") List<WorldEvent> history=(List<WorldEvent>)data.get("history");@SuppressWarnings("unchecked") List<StoryInstance> instances=(List<StoryInstance>)data.get("instances");@SuppressWarnings("unchecked") Map<String,String> fate=(Map<String,String>)data.get("fate");
            int expected=switch(phase){case "zero"->0;case "walk","walk-ended","cancelled"->1;case "both","final"->2;default->throw new IllegalArgumentException("zero|walk|walk-ended|cancelled|both|final");};
            List<WorldEvent> discoveries=history.stream().filter(e->e.type().equals("EXPLORATION_DISCOVERY")).sorted(Comparator.comparing(e->e.facts().get("region.id"))).toList();require(discoveries.size()==expected,"Unexpected discovery count at "+phase+": "+discoveries.size());
            Set<String> ids=new HashSet<>();for(WorldEvent event:discoveries){String region=event.facts().get("region.id");require(ids.add(region)&&Set.of("fixture-walk","fixture-teleport").contains(region),"Unexpected or repeated region");require(event.participants().equals(Set.of(actor))&&event.nations().isEmpty()&&event.source().equals("StarChronicle.ConfiguredRegionFirstVisit")&&"OBSERVED".equals(event.facts().get("time_basis"))&&"FIRST_VISIT".equals(event.facts().get("discovery.kind"))&&worldId.toString().equals(event.facts().get("region.world")),"Discovery actor/world/evidence mismatch");int x=Integer.parseInt(event.facts().get("region.x")),y=Integer.parseInt(event.facts().get("region.y")),z=Integer.parseInt(event.facts().get("region.z"));require(x>=(region.equals("fixture-walk")?1004:1011)&&x<=(region.equals("fixture-walk")?1006:1013)&&y>=-60&&y<=-58&&z>=0&&z<=3,"Discovery coordinates out of actual configured bounds");String key="source-baseline."+region;String canonical=SqliteStore.json(event);synchronized(logLock){String prior=getConfig().getString(key,canonical);require(prior.equals(canonical),"Canonical discovery changed across lookup/restart");if(!getConfig().contains(key)){getConfig().set(key,canonical);saveConfig();}}}
            if(expected==1)require(ids.equals(Set.of("fixture-walk")),"Cancelled teleport produced false discovery");
            int honor=switch(phase){case "zero","walk"->0;case "walk-ended","cancelled","both"->2;case "final"->5;default->throw new IllegalStateException();};require(Integer.parseInt(fate.getOrDefault("player.honor","0"))==honor,"Honor is not exactly once at "+phase+": "+fate);
            if(phase.equals("final")){require(instances.stream().filter(i->i.definition().id().startsWith("fixture-exploration-")&&i.completed()).count()==2,"Two actual client choices have not completed");for(String type:List.of("FIXTURE_WALK_FINISHED","FIXTURE_TELEPORT_FINISHED"))require(history.stream().filter(e->e.type().equals(type)).count()==1,"Ending history not exactly once: "+type);}
            log("CHECK_PASS",map("phase",phase,"world",worldId,"player",actor,"discoveries",discoveries,"fate",fate,"instances",instances.stream().filter(i->i.definition().id().startsWith("fixture-exploration-")).map(i->map("id",i.id(),"story",i.definition().id(),"completed",i.completed(),"choices",i.choices())).toList()));
        }catch(Throwable failure){fail("check-"+phase,failure);}});
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){if(!(sender instanceof ConsoleCommandSender)){sender.sendMessage("Isolated fixture console only");return true;}try{switch(args.length==0?"help":args[0]){case "station"->station();case "start"->start();case "check"->check(args[1]);case "teleport"->{String kind=args[1];require(Set.of("outside","cancel","inside","walk").contains(kind),"Unknown physical teleport target");require(!(folia&&kind.equals("cancel")),"Async teleport cancellation is unsupported on this Folia build; no synthetic cancellation is permitted");if(kind.equals("cancel"))cancelTeleport.set(true);owner("teleport",p->teleport(p,kind,new Location(world(),kind.equals("outside")?1000.5:kind.equals("walk")?1005.5:1012.5,-60,1.5)));}default->log("HELP",map("commands","station | start | check zero|walk|walk-ended|cancelled|both|final | teleport outside|cancel|inside|walk"));}}catch(Throwable error){fail(args.length==0?"command":args[0],error);}return true;}
    private static String digest(Path file)throws Exception{MessageDigest hash=MessageDigest.getInstance("SHA-256");try(var stream=Files.newInputStream(file)){byte[] buffer=new byte[65536];for(int size;(size=stream.read(buffer))!=-1;)hash.update(buffer,0,size);}return HexFormat.of().formatHex(hash.digest());}
}
