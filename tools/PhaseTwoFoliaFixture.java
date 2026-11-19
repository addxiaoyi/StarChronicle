package smoke;

import io.github.addxiaoyi.starchronicle.api.ChronicleService;
import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Isolated acceptance fixture. Never supplies PlayerContext, nation, role or rewards. */
public final class PhaseTwoFoliaFixture extends JavaPlugin implements CommandExecutor {
    private static final String SOURCE_ID="phase-two-folia:heir-discovery-1";
    private static final String BYSTANDER="phase-two-folia-bystander";
    private static final List<String> REFERENCES=List.of(
        "history.HEIR_RESTORATION_SUPPORTED",
        "chronicle.history.world.HEIR_RESTORATION_SUPPORTED",
        "chronicle.history.nation.HEIR_RESTORATION_SUPPORTED",
        "chronicle.history.player.HEIR_RESTORATION_SUPPORTED",
        "past.player.choice.last-heir/1/council",
        "past.resolution.world.last-heir/1/council",
        "past.resolution.nation.last-heir/1/council",
        "past.player.choice.last-heir/1/preparations",
        "past.resolution.world.last-heir/1/preparations",
        "past.resolution.nation.last-heir/1/preparations",
        "past.player.choice.heir-legacy/1/archive");
    private ChronicleService api;
    private Set<UUID> cohort=Set.of();
    private Instant sourceTime;
    private int boot;

    @Override public void onEnable() {
        api=getServer().getServicesManager().load(ChronicleService.class);
        if(api==null)throw new IllegalStateException("Production ChronicleService unavailable");
        boot=getConfig().getInt("observed-boots",0)+1;
        getConfig().set("observed-boots",boot);saveConfig();
        String saved=getConfig().getString("source-time");if(saved!=null)sourceTime=Instant.parse(saved);
        cohort=Set.copyOf(getConfig().getStringList("actual-cohort").stream().map(UUID::fromString).toList());
        getCommand("phase2folia").setExecutor(this);
        log("BOOT",Map.of("number",boot,"service",true,"standalone",true));
    }

    private void log(String marker,Map<String,?> data){getLogger().info("PHASE_TWO_"+marker+" "+SqliteStore.json(data));}
    private void fail(String action,Throwable error){log("FAILED",Map.of("action",action,"error",error.toString()));}
    private String shortName(Player player){return player.getName().equals("PhaseTwoFoliaA")?"A":player.getName().equals("PhaseTwoFoliaB")?"B":player.getName();}

    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        if(sender instanceof Player){sender.sendMessage("Console acceptance fixture only");return true;}
        String action=args.length==0?"help":args[0];
        try {
            if(action.equals("begin")||action.equals("replay")){submit(action.equals("replay"));return true;}
            if(args.length<2)throw new IllegalArgumentException("Use phase2folia begin|replay or <action> <actual player> [...]");
            Player player=getServer().getPlayerExact(args[1]);
            if(player==null)throw new IllegalArgumentException("Actual protocol client is not online");
            UUID playerId=player.getUniqueId();String who=shortName(player);
            switch(action) {
                case "choose" -> {
                    if(args.length!=5)throw new IllegalArgumentException("choose <player> <story> <node> <choice>");
                    api.instances(playerId).whenComplete((items,error)->{
                        if(error!=null){fail("query-before-choice",error);return;}
                        try {
                            var instance=items.stream().filter(item->item.definition().id().equals(args[2])&&!item.completed()&&item.node().equals(args[3])).findFirst().orElseThrow();
                            // Deliberately do not query instances after choosing: that would consume
                            // the derived inbox before the first normal shutdown under test.
                            api.choose(instance.id(),playerId,args[3],args[4]).whenComplete((report,problem)->{
                                if(problem!=null){fail("choose",problem);return;}
                                log("CHOSEN",Map.of("player",who,"story",args[2],"node",args[3],"choice",args[4],"instanceId",instance.id(),"message",report.message()));
                            });
                        }catch(Throwable problem){fail("choose-target",problem);}
                    });
                }
                case "inspect" -> {
                    if(boot==1)throw new IllegalStateException("Boot one must preserve derived inbox; inspect its SQLite snapshot read-only instead");
                    api.instances(playerId).whenComplete((items,error)->{
                        if(error!=null){fail("instances",error);return;}
                        for(var instance:items)log("INSTANCE",Map.of("player",who,"id",instance.id(),"story",instance.definition().id(),"node",instance.node(),"completed",instance.completed(),"members",instance.members().size(),"votes",instance.votes().size(),"choices",instance.choices()));
                    });
                }
                case "probe" -> {
                    api.recollections(playerId,REFERENCES).whenComplete((facts,error)->{
                        if(error!=null){fail("recollections",error);return;}
                        boolean absent=facts.keySet().stream().noneMatch(key->key.startsWith("chronicle.history.nation.")||key.startsWith("past.resolution.nation."));
                        log("RECOLLECTIONS",Map.of("player",who,"facts",new TreeMap<>(facts),"nationKeysAbsent",absent));
                    });
                    api.fate(playerId).whenComplete((facts,error)->{
                        if(error!=null){fail("fate",error);return;}
                        Map<String,String> titles=new TreeMap<>();facts.forEach((key,value)->{if(key.startsWith("title."))titles.put(key,value);});
                        log("FATE",Map.of("player",who,"role",facts.getOrDefault("player.role","unassigned"),"honor",facts.getOrDefault("player.honor","0"),"titles",titles));
                    });
                }
                case "npc" -> api.npc(playerId,"ancient-heir").whenComplete((npc,error)->{
                    if(error!=null){fail("npc",error);return;}
                    log("NPC",Map.of("player",who,"npcId","ancient-heir","rep",npc.reputation(),"trust",npc.trust(),"growth",npc.growth(),"position",npc.position(),"dialogueId",npc.dialogueId()));
                });
                case "scene" -> api.scene(playerId).whenComplete((scene,error)->{
                    if(error!=null){fail("scene",error);return;}log("SCENE",Map.of("player",who,"present",scene.isPresent()));
                });
                case "sceneact" -> api.sceneAction(playerId,"chronicle_phase_two_fixture","start",0).whenComplete((scene,error)->log("SCENE_ACTION",Map.of("player",who,"rejected",error!=null,"error",error==null?"":error.toString())));
                case "edge" -> teleport(player,new Location(player.getWorld(),15.5,-60,15.5),"EDGE");
                case "far" -> teleport(player,new Location(player.getWorld(),4095.5,-60,4095.5),"FAR");
                case "separation" -> {
                    Player other=getServer().getPlayerExact("PhaseTwoFoliaB");if(other==null)throw new IllegalArgumentException("Other actual client is not online");
                    player.getScheduler().execute(this,()->log("SEPARATION",Map.of("player",who,"playerOwned",Bukkit.isOwnedByCurrentRegion(player),"otherOwned",Bukkit.isOwnedByCurrentRegion(other))),()->fail("separation-retired",new IllegalStateException("Player retired")),1L);
                }
                case "bystander" -> player.getScheduler().execute(this,()->{
                    long previous=Arrays.stream(player.getChunk().getEntities()).filter(entity->entity.getScoreboardTags().contains(BYSTANDER)).count();
                    if(previous==0)player.getWorld().spawn(player.getLocation().clone().add(-1,0,0),ArmorStand.class,entity->{entity.setGravity(false);entity.setVisible(false);entity.setPersistent(true);entity.addScoreboardTag(BYSTANDER);});
                    log("BYSTANDER",Map.of("player",who,"spawned",previous==0,"previous",previous,"owned",Bukkit.isOwnedByCurrentRegion(player)));
                },()->fail("bystander-retired",new IllegalStateException("Player retired")),1L);
                case "view" -> player.getScheduler().execute(this,()->{
                    var view=player.getOpenInventory();int count=0;for(var item:view.getTopInventory().getContents())if(item!=null&&!item.getType().isAir())count++;
                    var holder=view.getTopInventory().getHolder();log("VIEW",Map.of("player",who,"owned",Bukkit.isOwnedByCurrentRegion(player),"title",view.getTitle(),"holder",holder==null?"null":holder.getClass().getName(),"items",count));
                },()->fail("view-retired",new IllegalStateException("Player retired")),1L);
                case "sites" -> sites(player);
                default -> throw new IllegalArgumentException("Unknown fixture action: "+action);
            }
        }catch(Throwable error){fail(action,error);}
        return true;
    }

    private void submit(boolean replay) {
        if(replay&&boot<3)throw new IllegalStateException("Source replay is reserved for the third boot");
        if(!replay&&(boot!=1||sourceTime!=null))throw new IllegalStateException("Only first boot may submit the initial source exactly once");
        Set<UUID> actual=new HashSet<>();for(Player player:getServer().getOnlinePlayers())actual.add(player.getUniqueId());
        if(actual.size()!=2)throw new IllegalStateException("Expected exactly two actual connected protocol clients");
        if(!replay){cohort=Set.copyOf(actual);sourceTime=Instant.now().truncatedTo(ChronoUnit.MILLIS);getConfig().set("actual-cohort",cohort.stream().map(UUID::toString).sorted().toList());getConfig().set("source-time",sourceTime.toString());saveConfig();}
        if(!cohort.equals(actual))throw new IllegalStateException("Replay clients differ from the original actual cohort");
        var event=new WorldEvent(SOURCE_ID,"ANCIENT_HEIR_DISCOVERED",sourceTime,cohort,Set.of(),Map.of("fixture","phase-two actual loopback clients","test-only","true"),"PhaseTwoFoliaFixture","Explicit test-only discovery proposed by an acceptance fixture after observing exactly two real protocol clients. The service collects their fresh contexts. No actual empire, nation, role, war or authority mutation is asserted. The original event time and payload are preserved for replay.");
        // Production service owns every fresh PlayerContext; this fixture never constructs one.
        api.submit(event).whenComplete((report,error)->{
            if(error!=null){fail("submit",error);return;}
            log("SOURCE",Map.of("id",SOURCE_ID,"duplicate",report.duplicate(),"online",actual.size(),"players",cohort.stream().map(UUID::toString).sorted().toList(),"instances",report.instanceIds(),"contextsFresh",true));
        });
    }

    private void teleport(Player player,Location location,String marker) {
        player.getScheduler().execute(this,()->player.teleportAsync(location).whenComplete((ok,error)->{
            if(error!=null){fail(marker,error);return;}log(marker,Map.of("player",shortName(player),"teleported",ok));
        }),()->fail(marker+"-retired",new IllegalStateException("Player retired")),1L);
    }

    private void sites(Player player) {
        player.getScheduler().execute(this,()->{
            World world=player.getWorld();int x=player.getChunk().getX(),z=player.getChunk().getZ();
            getServer().getRegionScheduler().execute(this,world,x,z,()->{
                Map<String,List<String>> found=new TreeMap<>();boolean owned=true;int bystanders=0;
                var siteKey=new NamespacedKey("starchronicle","memory-site");var kindKey=new NamespacedKey("starchronicle","memory-kind");
                for(Entity entity:world.getChunkAt(x,z).getEntities()) {
                    if(entity.getScoreboardTags().contains(BYSTANDER)){bystanders++;owned&=Bukkit.isOwnedByCurrentRegion(entity);}
                    String site=entity.getPersistentDataContainer().get(siteKey,PersistentDataType.STRING);if(site==null)continue;
                    owned&=Bukkit.isOwnedByCurrentRegion(entity);found.computeIfAbsent(site,ignored->new ArrayList<>()).add(entity.getPersistentDataContainer().get(kindKey,PersistentDataType.STRING)+":"+entity.getType());
                }
                found.values().forEach(Collections::sort);log("SITES",Map.of("chunk",x+","+z,"owned",owned,"bystanders",bystanders,"sites",found));
            });
        },()->fail("sites-retired",new IllegalStateException("Player retired")),1L);
    }
}
