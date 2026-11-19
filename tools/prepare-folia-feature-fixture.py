"""Build a test-only API/ownership inspector. It never fabricates PlayerContext or nations."""
from pathlib import Path
import os,re,subprocess,sys,zipfile

root=Path(__file__).resolve().parents[1]
name=sys.argv[1] if len(sys.argv)>1 else 'folia-final-smoke-abaadf4c'
if not re.fullmatch(r'[a-z0-9][a-z0-9_.-]{0,79}',name):raise SystemExit('Invalid isolated run directory name')
run=root/'run'/name
fixture=run/'fixture';fixture.mkdir(exist_ok=True)
source=r'''package smoke;
import io.github.addxiaoyi.starchronicle.api.*;
import java.util.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
public final class FoliaFeatureFixture extends JavaPlugin implements CommandExecutor {
 private ChronicleService api;
 private Instant eventTime;
 private Instant offlineTime;
 private Set<UUID> cohort=Set.of();
 public void onEnable(){api=getServer().getServicesManager().load(ChronicleService.class);String saved=getConfig().getString("event-time");if(saved!=null)eventTime=Instant.parse(saved);String offline=getConfig().getString("offline-time");if(offline!=null)offlineTime=Instant.parse(offline);cohort=Set.copyOf(getConfig().getStringList("real-cohort").stream().map(UUID::fromString).toList());getCommand("foliafeature").setExecutor(this);getLogger().info("FEATURE_FIXTURE_READY service="+(api!=null));}
 private void log(String text){getLogger().info(text);}
 public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
  if(sender instanceof Player){sender.sendMessage("Console verification only");return true;}
  try {
   if(args.length==0||args[0].equals("begin")) {
    Set<UUID> ids=new HashSet<>();for(Player p:getServer().getOnlinePlayers())ids.add(p.getUniqueId());
    if(ids.size()!=2)throw new IllegalStateException("Expected exactly two real connected clients");
    if(cohort.isEmpty()){cohort=Set.copyOf(ids);getConfig().set("real-cohort",cohort.stream().map(UUID::toString).sorted().toList());saveConfig();}else if(!cohort.equals(ids))throw new IllegalStateException("Connected clients differ from the original observed cohort");
    if(eventTime==null){eventTime=Instant.now().truncatedTo(ChronoUnit.MILLIS);getConfig().set("event-time",eventTime.toString());saveConfig();}
    var event=new WorldEvent("folia-feature:committed-1","FOLIA_FEATURE_EVENT",eventTime,ids,Set.of(),Map.of("fixture","actual loopback clients"),"FoliaFeatureFixture","Test fixture observed two real protocol clients; original observed time is saved for restart replay; no nation or role is asserted.");
    api.submit(event).whenComplete((result,error)->log(error==null?"FEATURE_SUBMITTED duplicate="+result.duplicate()+" instances="+result.instanceIds():"FEATURE_SUBMIT_FAILED "+error));return true;
   }
   if(args[0].equals("offline")||args[0].equals("offlinereplay")) {
    if(cohort.size()!=2)throw new IllegalStateException("Enroll two actual connected clients with begin before testing offline delivery");
    int online=getServer().getOnlinePlayers().size();
    if(args[0].equals("offline")&&online!=0)throw new IllegalStateException("Original offline commit requires zero online players");
    if(offlineTime==null){if(!args[0].equals("offline"))throw new IllegalStateException("Original offline event not committed");offlineTime=Instant.now().truncatedTo(ChronoUnit.MILLIS);getConfig().set("offline-time",offlineTime.toString());saveConfig();}
    var event=new WorldEvent("folia-feature:offline-1","FOLIA_OFFLINE_EVENT",offlineTime,cohort,Set.of(),Map.of("fixture","previously observed real test cohort","audience-at-original-commit","0"),"FoliaFeatureFixture","The fixture enrolled these two UUIDs from real connected test clients before they disconnected. This test event then committed with zero online players. No PlayerContext, nation or role was supplied; original time and payload are replayed unchanged.");
    api.submit(event).whenComplete((result,error)->log(error==null?"FEATURE_OFFLINE_SUBMITTED online="+online+" duplicate="+result.duplicate()+" instances="+result.instanceIds():"FEATURE_OFFLINE_SUBMIT_FAILED "+error));return true;
   }
   Player player=getServer().getPlayerExact(args[1]);if(player==null)throw new IllegalArgumentException("Actual client is not online");UUID id=player.getUniqueId();
   if(args[0].equals("offlinejoin")) {
    api.activeInstances().whenComplete((items,error)->{if(error!=null){log("FEATURE_OFFLINE_JOIN_FAILED "+error);return;}var item=items.stream().filter(i->i.definition().id().equals("folia-offline")).findFirst().orElseThrow();api.join(item.id(),id).whenComplete((result,problem)->log(problem==null?"FEATURE_OFFLINE_JOIN player="+args[1]+" duplicate="+result.duplicate():"FEATURE_OFFLINE_JOIN_FAILED "+problem));});
   } else if(Set.of("inspect","vote","offlineinspect","offlinevote").contains(args[0])){
    boolean offline=args[0].startsWith("offline");String story=offline?"folia-offline":"folia-feature";String tag=offline?"FEATURE_OFFLINE_":"FEATURE_";
    api.instances(id).whenComplete((items,error)->{
     if(error!=null){log("FEATURE_QUERY_FAILED "+error);return;}
     var item=items.stream().filter(i->i.definition().id().equals(story)).findFirst().orElseThrow();
     if(args[0].endsWith("vote"))api.choose(item.id(),id,item.node(),"remember").whenComplete((r,e)->log(e==null?tag+"VOTED player="+args[1]+" result="+r.message():tag+"VOTE_REJECTED "+e));
     else log(tag+"INSTANCE id="+item.id()+" node="+item.node()+" members="+item.members().size()+" votes="+item.votes().size()+" completed="+item.completed());
    });
   } else if(args[0].equals("npc")) {
    api.npc(id,"folia-witness").whenComplete((npc,error)->log(error==null?"FEATURE_NPC player="+args[1]+" rep="+npc.reputation()+" trust="+npc.trust()+" growth="+npc.growth()+" position="+npc.position()+" dialogue="+npc.dialogueId():"FEATURE_NPC_FAILED "+error));
   } else if(args[0].equals("fate")) {
    api.fate(id).whenComplete((facts,error)->log(error==null?"FEATURE_FATE player="+args[1]+" role="+facts.get("player.role")+" honor="+facts.getOrDefault("player.honor","0")+" title="+facts.getOrDefault("title.folia-feature-witness",""):"FEATURE_FATE_FAILED "+error));
   } else if(args[0].equals("scene")) {
    api.scene(id).whenComplete((scene,error)->log(error==null?"FEATURE_SCENE present="+scene.isPresent():"FEATURE_SCENE_FAILED "+error));
   } else if(args[0].equals("sceneact")) {
    api.sceneAction(id,"chronicle_folia_fixture","start",0).whenComplete((scene,error)->log("FEATURE_SCENE_ACTION rejected="+(error!=null)+" error="+error));
   } else if(args[0].equals("edge")) {
    player.getScheduler().execute(this,()->player.teleportAsync(new Location(player.getWorld(),15.5,-60,15.5)).whenComplete((ok,error)->log(error==null?"FEATURE_EDGE teleported="+ok:"FEATURE_EDGE_FAILED "+error)),()->log("FEATURE_RETIRED edge"),1L);
   } else if(args[0].equals("far")) {
    player.getScheduler().execute(this,()->player.teleportAsync(new Location(player.getWorld(),4095.5,-60,4095.5)).whenComplete((ok,error)->log(error==null?"FEATURE_FAR teleported="+ok:"FEATURE_FAR_FAILED "+error)),()->log("FEATURE_RETIRED far"),1L);
   } else if(args[0].equals("separation")) {
    Player other=getServer().getPlayerExact("FoliaFeatureB");if(other==null)throw new IllegalArgumentException("Other client is not online");
    player.getScheduler().execute(this,()->log("FEATURE_SEPARATION playerOwned="+Bukkit.isOwnedByCurrentRegion(player)+" otherOwned="+Bukkit.isOwnedByCurrentRegion(other)),()->log("FEATURE_RETIRED separation"),1L);
   } else if(args[0].equals("bystander")) {
    player.getScheduler().execute(this,()->{var location=player.getLocation().clone().add(-1,0,0);player.getWorld().spawn(location,ArmorStand.class,entity->{entity.setGravity(false);entity.setVisible(false);entity.setPersistent(true);entity.addScoreboardTag("folia-feature-bystander");});log("FEATURE_BYSTANDER spawned=true owned="+Bukkit.isOwnedByCurrentRegion(player));},()->log("FEATURE_RETIRED bystander"),1L);
   } else if(args[0].equals("view")) {
    player.getScheduler().execute(this,()->{var view=player.getOpenInventory();int count=0;for(var item:view.getTopInventory().getContents())if(item!=null&&!item.getType().isAir())count++;var holder=view.getTopInventory().getHolder();log("FEATURE_VIEW owned="+Bukkit.isOwnedByCurrentRegion(player)+" player="+args[1]+" title="+view.getTitle()+" holder="+(holder==null?"null":holder.getClass().getName())+" items="+count);},()->log("FEATURE_RETIRED view"),1L);
   } else if(args[0].equals("sites")) {
    player.getScheduler().execute(this,()->{
     World world=player.getWorld();int x=player.getChunk().getX(),z=player.getChunk().getZ();
     getServer().getRegionScheduler().execute(this,world,x,z,()->{
      Map<String,List<String>> found=new TreeMap<>();boolean owned=true;int bystanders=0;var key=new NamespacedKey("starchronicle","memory-site");var kind=new NamespacedKey("starchronicle","memory-kind");
      for(Entity entity:world.getChunkAt(x,z).getEntities()){
       if(entity.getScoreboardTags().contains("folia-feature-bystander")){bystanders++;owned&=Bukkit.isOwnedByCurrentRegion(entity);}
       String site=entity.getPersistentDataContainer().get(key,PersistentDataType.STRING);if(site==null)continue;
       owned&=Bukkit.isOwnedByCurrentRegion(entity);found.computeIfAbsent(site,k->new ArrayList<>()).add(entity.getPersistentDataContainer().get(kind,PersistentDataType.STRING)+":"+entity.getType());
      }
      found.values().forEach(Collections::sort);log("FEATURE_SITES chunk="+x+","+z+" owned="+owned+" bystanders="+bystanders+" entities="+found);
     });
    },()->log("FEATURE_RETIRED sites"),1L);
   }
  }catch(Throwable error){log("FEATURE_FIXTURE_FAILED "+error);}
  return true;
 }
}'''
java=fixture/'FoliaFeatureFixture.java';java.write_text(source,encoding='utf-8')
classes=fixture/'classes';classes.mkdir(exist_ok=True)
jars=list((run/'libraries').rglob('*.jar'))+[run/'plugins'/'StarChronicle.jar',Path.home()/'.m2/repository/io/papermc/paper/paper-api/1.21.11-R0.1-SNAPSHOT/paper-api-1.21.11-R0.1-SNAPSHOT.jar']
args=fixture/'javac.args';args.write_text('--release 21\n-encoding UTF-8\n-cp\n"'+(';'.join(str(f) for f in jars)).replace('\\','/')+'"\n-d\n"'+str(classes).replace('\\','/')+'"\n"'+str(java).replace('\\','/')+'"\n',encoding='utf-8')
subprocess.run([str(Path(os.environ['JAVA_HOME'])/'bin/javac.exe'),'@'+str(args)],check=True)
with zipfile.ZipFile(run/'plugins'/'FoliaFeatureFixture.jar','w') as archive:
 archive.writestr('plugin.yml','name: FoliaFeatureFixture\nversion: 1\nmain: smoke.FoliaFeatureFixture\napi-version: "1.21"\ndepend: [StarChronicle]\nfolia-supported: true\ncommands:\n  foliafeature:\n    description: Isolated ownership inspector\n')
 for path in classes.rglob('*.class'):archive.write(path,path.relative_to(classes).as_posix())
print('Built test-only feature fixture; real entity/region API checks, no fabricated player state.')
