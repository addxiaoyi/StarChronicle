package smoke;

import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.time.Instant;
import java.util.*;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Test-only coordinator. Never constructs a player context, event, vote, nation or reward. */
public final class PhaseThreeFoliaFixture extends JavaPlugin implements CommandExecutor,Listener {
    private static final String SOURCE="chronicle:5ad9905c3336c82bef3538e5b469a5ae23e6031566a1d9f3af9d4ed07a78b492";
    private static final String PERMISSION="phase-three.v2.ready";
    private static final List<String> REFS=List.of("history.HEIR_RESTORATION_SUPPORTED","chronicle.history.world.HEIR_RESTORATION_SUPPORTED","chronicle.history.player.HEIR_RESTORATION_SUPPORTED","chronicle.history.nation.HEIR_RESTORATION_SUPPORTED","past.player.choice.last-heir/1/council","past.resolution.world.last-heir/1/council","past.player.choice.heir-legacy/1/archive","past.player.choice.phase-three-archive/1/desk","past.player.choice.heir-legacy/2/archive","past.resolution.nation.last-heir/1/council");
    private ChronicleService api;
    private int boot;
    @Override public void onEnable(){api=getServer().getServicesManager().load(ChronicleService.class);if(api==null)throw new IllegalStateException("ChronicleService unavailable");boot=getConfig().getInt("boots",0)+1;getConfig().set("boots",boot);saveConfig();getCommand("phase3folia").setExecutor(this);getServer().getPluginManager().registerEvents(this,this);log("BOOT",Map.of("boot",boot,"online",getServer().getOnlinePlayers().size(),"sourceId",SOURCE));}
    private void log(String marker,Map<String,?> data){getLogger().info("PHASE_THREE_"+marker+" "+SqliteStore.json(data));}
    private void fail(String action,Throwable error){log("FAILED",Map.of("action",action,"error",error.toString()));}
    private String who(Player player){return player.getName().equals("PhaseTwoFoliaA")?"A":player.getName().equals("PhaseTwoFoliaB")?"B":player.getName();}
    /** The event is on this actual player's owner. LOWEST precedes production NORMAL join
     * snapshot collection; only a real Bukkit permission changes, never an identity fact. */
    @EventHandler(priority=EventPriority.LOWEST) public void permissionBeforeSnapshot(PlayerJoinEvent event){
        Player player=event.getPlayer();boolean grant=boot>=2;player.addAttachment(this,PERMISSION,grant);
        log("GATE",Map.of("player",who(player),"uuid",player.getUniqueId().toString(),"permission",PERMISSION,"granted",player.hasPermission(PERMISSION),"owned",Bukkit.isOwnedByCurrentRegion(player),"priority","LOWEST","observedAt",Instant.now().toString()));
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
        if(sender instanceof Player){sender.sendMessage("Console fixture only");return true;}
        String action=args.length==0?"help":args[0];
        try{
            if(action.equals("evaluate")){
                int online=getServer().getOnlinePlayers().size();if(boot==2||boot==1&&online!=0)throw new IllegalStateException("First evaluation must be offline before boot-one client acknowledgement; no evaluation on boot two");
                api.evaluateHistorical(SOURCE,"phase-three-archive","1").whenComplete((result,error)->{if(error!=null){fail("evaluate",error);return;}log("EVALUATED",Map.of("boot",boot,"online",online,"sourceId",SOURCE,"status",result.status().name(),"report",result,"observedAt",Instant.now().toString()));});return true;
            }
            if(args.length!=2)throw new IllegalArgumentException("Use evaluate or <action> <actual player>");
            Player player=getServer().getPlayerExact(args[1]);if(player==null)throw new IllegalArgumentException("Actual copied-world client is not online");UUID id=player.getUniqueId();String who=who(player);
            switch(action){
                case "inspect" -> api.instances(id).whenComplete((items,error)->{if(error!=null){fail("inspect",error);return;}for(var item:items)log("INSTANCE",Map.of("player",who,"id",item.id(),"story",item.definition().id(),"version",item.definition().version(),"node",item.node(),"completed",item.completed(),"members",item.members().size(),"choices",item.choices()));});
                case "probe" -> {
                    api.recollections(id,REFS).whenComplete((facts,error)->{if(error!=null){fail("recollections",error);return;}log("RECOLLECTIONS",Map.of("player",who,"facts",new TreeMap<>(facts),"nationAbsent",facts.keySet().stream().noneMatch(key->key.startsWith("chronicle.history.nation.")||key.startsWith("past.resolution.nation."))));});
                    api.fate(id).whenComplete((facts,error)->{if(error!=null){fail("fate",error);return;}log("FATE",Map.of("player",who,"facts",new TreeMap<>(facts),"role",facts.getOrDefault("player.role","unassigned"),"honor",facts.getOrDefault("player.honor","0")));});
                }
                case "npc" -> api.npc(id,"ancient-heir").whenComplete((npc,error)->{if(error!=null){fail("npc",error);return;}log("NPC",Map.of("player",who,"dialogue",npc.dialogueId(),"reputation",npc.reputation(),"trust",npc.trust(),"growth",npc.growth(),"position",npc.position()));});
                case "scene" -> api.scene(id).whenComplete((scene,error)->{if(error!=null){fail("scene",error);return;}log("SCENE",Map.of("player",who,"present",scene.isPresent()));});
                case "sceneact" -> api.sceneAction(id,"chronicle_phase_three_fixture","start",0).whenComplete((scene,error)->log("SCENE_ACTION",Map.of("player",who,"rejected",error!=null,"error",error==null?"":error.toString())));
                case "separation" -> {Player other=getServer().getPlayerExact("PhaseTwoFoliaB");if(other==null)throw new IllegalStateException("Other client absent");player.getScheduler().execute(this,()->log("SEPARATION",Map.of("player",who,"owned",Bukkit.isOwnedByCurrentRegion(player),"otherOwned",Bukkit.isOwnedByCurrentRegion(other))),()->fail("separation",new IllegalStateException("Retired player")),1L);}
                case "view" -> player.getScheduler().execute(this,()->{var view=player.getOpenInventory();var holder=view.getTopInventory().getHolder();log("VIEW",Map.of("player",who,"owned",Bukkit.isOwnedByCurrentRegion(player),"title",view.getTitle(),"holder",holder==null?"null":holder.getClass().getName()));},()->fail("view",new IllegalStateException("Retired player")),1L);
                case "sites" -> player.getScheduler().execute(this,()->{World world=player.getWorld();int x=player.getChunk().getX(),z=player.getChunk().getZ();getServer().getRegionScheduler().execute(this,world,x,z,()->{Map<String,List<String>> sites=new TreeMap<>();int bystanders=0;boolean owned=true;var siteKey=new NamespacedKey("starchronicle","memory-site");var kindKey=new NamespacedKey("starchronicle","memory-kind");for(Entity entity:world.getChunkAt(x,z).getEntities()){if(entity.getScoreboardTags().contains("phase-two-folia-bystander")){bystanders++;owned&=Bukkit.isOwnedByCurrentRegion(entity);}String site=entity.getPersistentDataContainer().get(siteKey,PersistentDataType.STRING);if(site==null)continue;owned&=Bukkit.isOwnedByCurrentRegion(entity);sites.computeIfAbsent(site,key->new ArrayList<>()).add(entity.getPersistentDataContainer().get(kindKey,PersistentDataType.STRING)+":"+entity.getType());}sites.values().forEach(Collections::sort);log("SITES",Map.of("chunk",x+","+z,"owned",owned,"bystanders",bystanders,"sites",sites));});},()->fail("sites",new IllegalStateException("Retired player")),1L);
                default -> throw new IllegalArgumentException("Unknown fixture action");
            }
        }catch(Throwable error){fail(action,error);}
        return true;
    }
}
