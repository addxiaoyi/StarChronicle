package smoke;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import de.oliver.fancynpcs.api.*;
import de.oliver.fancynpcs.api.events.NpcInteractEvent;
import io.github.addxiaoyi.starchronicle.api.ChronicleService;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Actual optional providers, player commands and network interactions; no product DB writes. */
public final class ProductionCombinationFixture extends JavaPlugin implements Listener,CommandExecutor {
    private static final String NPC="chronicle-combination-witness",SITE="combo-allowed";
    private Path run;private ChronicleService api;private int boot,opens;private boolean cancelNpc;
    private PermissionAttachment permission;
    @Override public void onEnable(){try{
        run=getDataFolder().toPath().toAbsolutePath().normalize().getParent().getParent();
        require(run.getFileName().toString().startsWith("paper-epic-")&&Files.exists(run.resolve("combination.json")),"Isolated combination manifest required");
        for(String name:List.of("StarCore","StarChronicle","FancyNpcs","WorldGuard","WorldEdit"))
            require(Objects.requireNonNull(getServer().getPluginManager().getPlugin(name)).isEnabled(),name+" unavailable");
        api=Objects.requireNonNull(getServer().getServicesManager().load(ChronicleService.class));
        boot=getConfig().getInt("boot",0)+1;getConfig().set("boot",boot);saveConfig();
        getServer().getPluginManager().registerEvents(this,this);getCommand("comboverify").setExecutor(this);
        log("READY",Map.of("boot",boot));
    }catch(Throwable error){fail(error);getServer().getPluginManager().disablePlugin(this);}}
    @Override public void onDisable(){if(run!=null)log("CLOSED",Map.of("normalDisable",true));}
    private static void require(boolean value,String message){if(!value)throw new IllegalStateException(message);}
    private Player player(){Player p=getServer().getPlayerExact("EpicA");require(p!=null&&!p.isOp(),"Real non-OP player required");return p;}
    private World world(){return getServer().getWorlds().getFirst();}
    private int y(){return getConfig().getInt("floorY");}
    private synchronized void log(String kind,Object data){try{
        String json=SqliteStore.json(Map.of("time",Instant.now().toString(),"boot",boot,"kind",kind,"data",data));
        Files.writeString(run.resolve("evidence/combination-fixture.jsonl"),json+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        getLogger().info("COMBO_"+kind+" "+SqliteStore.json(data));
    }catch(Exception e){throw new IllegalStateException(e);}}
    private void fail(Throwable error){while(error.getCause()!=null)error=error.getCause();log("FAILED",Map.of("error",error.toString()));error.printStackTrace();}
    private <T>CompletionStage<T> main(Callable<T> task){CompletableFuture<T> result=new CompletableFuture<>();Runnable r=()->{try{result.complete(task.call());}catch(Throwable e){result.completeExceptionally(e);}};if(Bukkit.isPrimaryThread())r.run();else getServer().getScheduler().runTask(this,r);return result;}
    private long npcHistory(List<io.github.addxiaoyi.starchronicle.api.WorldEvent> events){return events.stream().filter(e->e.type().equals("NPC_INTERACT")&&("fancynpcs:"+NPC).equals(e.facts().get("npc.binding"))).count();}
    private Npc npc(){return Objects.requireNonNull(FancyNpcsPlugin.get().getNpcManager().getNpcById(NPC),"Provider NPC missing");}
    private CompletionStage<Void> ready(){
        CompletableFuture<Void> result=new CompletableFuture<>();
        new org.bukkit.scheduler.BukkitRunnable(){int polls;
            public void run(){try{
                var manager=FancyNpcsPlugin.get().getNpcManager();
                if(manager.isLoaded()){log("NPC_LOADED",Map.of("polls",polls,"count",manager.getAllNpcs().size()));cancel();result.complete(null);}
                else if(++polls>=60){cancel();result.completeExceptionally(new IllegalStateException("Native NPC loading did not finish"));}
            }catch(Throwable error){cancel();result.completeExceptionally(error);}}
        }.runTaskTimer(this,0L,10L);
        return result;
    }
    private long hookCount(){return Arrays.stream(NpcInteractEvent.getHandlerList().getRegisteredListeners()).filter(l->l.getPlugin().getName().equals("StarChronicle")).count();}
    private void targets()throws Exception{Files.writeString(run.resolve("evidence/combination-target.json"),SqliteStore.json(Map.of("npc",npc().getEntityId(),"source",getConfig().getString("source")))+"\n");}
    private CompletionStage<?> setup(){UUID actor=player().getUniqueId();return ready().thenCompose(ignored->api.history(null,actor,0,100)).thenCompose(events->main(()->{
        require(hookCount()==1,"Expected exactly one actual NPC bridge");
        if(!getConfig().getBoolean("seeded",false)){
            String source=events.stream().filter(e->e.type().equals("NATIONAL_RESOURCE_AID_COMMITTED")).findFirst().orElseThrow().id();
            getConfig().set("source",source);getConfig().set("floorY",world().getHighestBlockYAt(30,0)+1);
            var manager=WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world()));require(manager!=null,"Region manager unavailable");
            var denied=new ProtectedCuboidRegion("chronicle-denied",BlockVector3.at(18,world().getMinHeight(),-3),BlockVector3.at(24,world().getMaxHeight()-1,3));
            var owned=new ProtectedCuboidRegion("chronicle-owned",BlockVector3.at(28,world().getMinHeight(),-3),BlockVector3.at(34,world().getMaxHeight()-1,4));
            owned.getOwners().addPlayer(actor);manager.addRegion(denied);manager.addRegion(owned);manager.save();
            NpcData data=new NpcData(NPC,"ChronicleAcceptance",actor,"Combination witness",null,new Location(world(),30.5,y(),2.5),
                    false,true,false,false,NamedTextColor.WHITE,EntityType.VILLAGER,Map.of(),false,0,null,Map.of(),0f,1f,-1,Map.of(),false);
            Npc created=FancyNpcsPlugin.get().getNpcAdapter().apply(data);created.setSaveToFile(true);created.create();
            FancyNpcsPlugin.get().getNpcManager().registerNpc(created);created.spawnForAll();FancyNpcsPlugin.get().getNpcManager().saveNpcs(true);
            ArmorStand foreign=world().spawn(new Location(world(),31.5,y(),1.5),ArmorStand.class,e->{e.setPersistent(true);e.setInvulnerable(true);e.setGravity(false);e.customName(Component.text("Foreign fixture sentinel"));});
            getConfig().set("foreign",foreign.getUniqueId().toString());getConfig().set("seeded",true);saveConfig();
        }
        permission=player().addAttachment(this);permission.setPermission("starchronicle.admin",false);
        permission.setPermission("worldguard.region.bypass."+world().getName(),false);player().recalculatePermissions();
        require(!WorldGuard.getInstance().getPlatform().getSessionManager().hasBypass(com.sk89q.worldguard.bukkit.WorldGuardPlugin.inst().wrapPlayer(player()),BukkitAdapter.adapt(world())),"WorldGuard bypass must be false");
        targets();log("SETUP",Map.of("source",getConfig().getString("source"),"npc",NPC,"nativeNpcCount",FancyNpcsPlugin.get().getNpcManager().getAllNpcs().size(),"bridgeListeners",hookCount(),"npcHistory",npcHistory(events),"bypass",false));return null;
    }));}
    private List<Entity> owned(){return Arrays.stream(world().getChunkAt(1,0).getEntities()).filter(e->SITE.equals(e.getPersistentDataContainer().get(new NamespacedKey("starchronicle","memory-site"),PersistentDataType.STRING))).toList();}
    private void memory(boolean present){
        List<Entity> owned=owned();require(owned.size()==(present?2:0),"Wrong owned entity count: "+owned.size());
        Entity foreign=world().getEntity(UUID.fromString(getConfig().getString("foreign")));require(foreign instanceof ArmorStand&&foreign.isValid(),"Foreign entity changed or removed");
        List<String> ids=owned.stream().map(e->e.getUniqueId().toString()).sorted().toList();
        if(present){if(boot==1){getConfig().set("ownedIds",ids);saveConfig();}else require(ids.equals(getConfig().getStringList("ownedIds")),"Restart replaced or duplicated owned entities");}
        require(FancyNpcsPlugin.get().getNpcManager().getAllNpcs().size()==1&&npc().getData().getId().equals(NPC),"Foreign NPC lifecycle changed");
        log("MEMORY",Map.of("present",present,"owned",ids,"foreign",foreign.getUniqueId().toString(),"npc",NPC));
    }
    @EventHandler(priority=EventPriority.LOWEST) public void cancel(NpcInteractEvent event){if(event.getNpc().getData().getId().equals(NPC)&&cancelNpc)event.setCancelled(true);}
    @EventHandler(priority=EventPriority.MONITOR) public void interaction(NpcInteractEvent event){if(event.getNpc().getData().getId().equals(NPC))log("NPC_PACKET",Map.of("cancelled",event.isCancelled(),"player",event.getPlayer().getUniqueId().toString(),"trigger",event.getInteractionType().name()));}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void opened(InventoryOpenEvent event){if(event.getPlayer().getName().equals("EpicA")){opens++;log("MENU_OPEN",Map.of("opens",opens));}}
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){if(!(sender instanceof ConsoleCommandSender)||args.length==0)return true;try{
        CompletionStage<?> result=switch(args[0]){
            case "setup"->setup();
            case "admin"->main(()->{permission=player().addAttachment(this);permission.setPermission("starchronicle.admin",Boolean.parseBoolean(args[1]));player().recalculatePermissions();return null;});
            case "move"->main(()->{double x=switch(args[1]){case "denied"->20.5;case "edge"->17.5;default->30.5;};require(player().teleport(new Location(world(),x,y(),0.5)),"Teleport rejected");npc().spawnForAll();targets();log("POSITION",Map.of("point",args[1],"x",x,"y",y()));return null;});
            case "cancel-npc"->main(()->{cancelNpc=Boolean.parseBoolean(args[1]);return null;});
            case "npc-history"->api.history(null,player().getUniqueId(),0,100).thenAccept(events->{long count=npcHistory(events);require(count==Long.parseLong(args[1]),"Unexpected NPC history count: "+count);log("NPC_HISTORY",Map.of("count",count));});
            case "memory"->main(()->{memory(Boolean.parseBoolean(args[1]));return null;});
            case "offline"->main(()->{require(getServer().getOnlinePlayers().isEmpty(),"Maintenance requires all clients offline");log("MAINTENANCE_EMPTY",Map.of("online",0));return null;});
            case "disable-npc"->main(()->{getServer().getPluginManager().disablePlugin(FancyNpcsPlugin.get().getPlugin());require(hookCount()==0,"NPC bridge leaked on provider disable");log("NPC_DISABLED",Map.of("bridgeListeners",hookCount()));return null;});
            case "disable-protection"->main(()->{getServer().getPluginManager().disablePlugin(getServer().getPluginManager().getPlugin("WorldGuard"));log("PROTECTION_DISABLED",Map.of("enabled",false));return null;});
            default->throw new IllegalArgumentException("Unknown action");
        };
        result.whenComplete((value,error)->{if(error!=null)fail(error);else log("ACTION_DONE",Map.of("action",args[0]));});
    }catch(Throwable error){fail(error);}return true;}
}
