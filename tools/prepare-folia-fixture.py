"""Compile a test-only Folia fixture against the actual Chronicle Bukkit service API."""
from pathlib import Path
import os,subprocess,zipfile
root=Path(__file__).resolve().parents[1];run=root/'run'/'folia-smoke';fixture=run/'fixture';fixture.mkdir(exist_ok=True)
source=r'''package smoke;
import io.github.addxiaoyi.starchronicle.api.*;
import java.util.*;
import java.time.Instant;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
public final class FoliaSmokeFixture extends JavaPlugin implements CommandExecutor {
 private ChronicleService api;
 public void onEnable(){api=getServer().getServicesManager().load(ChronicleService.class);getCommand("foliasmoke").setExecutor(this);getLogger().info("FOLIA_FIXTURE_READY service="+(api!=null));}
 public boolean onCommand(CommandSender sender,Command c,String label,String[] args){
  if(sender instanceof Player){sender.sendMessage("Test console only");return true;}
  try {
   if(args.length==0||args[0].equals("begin")) {
    Set<UUID> players=new HashSet<>();for(Player p:getServer().getOnlinePlayers())players.add(p.getUniqueId());
    if(players.size()!=2)throw new IllegalStateException("Expected two actual connected clients, got "+players.size());
    WorldEvent e=new WorldEvent("folia-smoke:committed-event-1","FOLIA_SMOKE_EVENT",Instant.parse("2026-11-19T00:00:00Z"),players,Set.of(),Map.of("fixture","loopback protocol clients"),"FoliaSmokeFixture","Console test fixture observed two actual connected protocol clients; fixed event ID/time for restart replay.");
    api.submit(e).whenComplete((r,error)->{if(error!=null)getLogger().severe("FOLIA_SUBMIT_FAILED "+error);else getLogger().info("FOLIA_SUBMITTED duplicate="+r.duplicate()+" instances="+r.instanceIds());});
   } else {
    Player p=getServer().getPlayerExact(args[1]);if(p==null)throw new IllegalArgumentException("Client not online");UUID id=p.getUniqueId();
    api.instances(id).whenComplete((items,error)->{
     if(error!=null){getLogger().severe("FOLIA_QUERY_FAILED "+error);return;}
     var item=items.stream().filter(i->i.definition().id().equals("folia-smoke")).findFirst().orElseThrow();
     if(args[0].equals("vote"))api.choose(item.id(),id,item.node(),"remember").whenComplete((r,e)->getLogger().info(e==null?"FOLIA_VOTED player="+args[1]+" result="+r.message():"FOLIA_VOTE_FAILED "+e));
     else getLogger().info("FOLIA_INSTANCE id="+item.id()+" node="+item.node()+" members="+item.members().size()+" votes="+item.votes().size()+" completed="+item.completed());
    });
   }
  }catch(Exception error){getLogger().severe("FOLIA_FIXTURE_FAILED "+error);}
  return true;
 }
}'''
java=fixture/'FoliaSmokeFixture.java';java.write_text(source,encoding='utf-8');classes=fixture/'classes';classes.mkdir(exist_ok=True)
jars=list((run/'libraries').rglob('*.jar'))+[root/'target'/'starchronicle-0.1.0-SNAPSHOT.jar',Path.home()/'.m2/repository/io/papermc/paper/paper-api/1.21.11-R0.1-SNAPSHOT/paper-api-1.21.11-R0.1-SNAPSHOT.jar']
args=fixture/'javac.args';args.write_text('--release 21\n-encoding UTF-8\n-cp\n"'+(';'.join(str(f) for f in jars)).replace('\\','/')+'"\n-d\n"'+str(classes).replace('\\','/')+'"\n"'+str(java).replace('\\','/')+'"\n',encoding='utf-8')
subprocess.run([str(Path(os.environ['JAVA_HOME'])/'bin/javac.exe'),'@'+str(args)],check=True)
with zipfile.ZipFile(run/'plugins'/'FoliaSmokeFixture.jar','w') as jar:
 jar.writestr('plugin.yml','name: FoliaSmokeFixture\nversion: 1\nmain: smoke.FoliaSmokeFixture\napi-version: "1.21"\ndepend: [StarChronicle]\nfolia-supported: true\ncommands:\n  foliasmoke:\n    description: Isolated console test fixture\n')
 for f in classes.rglob('*.class'):jar.write(f,f.relative_to(classes).as_posix())
print('Built test-only Folia fixture; no fabricated player contexts or production StarCore flag overrides.')
