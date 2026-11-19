"""Build a test-only fixture plugin inside run/smoke, never shipped in StarChronicle.
Creates a single nation through the real StarCore NationService in the isolated smoke database.
The seeded founder is a deterministic test fixture, not an online/privileged player snapshot.
"""
from pathlib import Path
import subprocess,zipfile,re,os
root=Path(__file__).resolve().parents[1];run=root/'run'/'smoke';fixture=run/'fixture';fixture.mkdir(exist_ok=True)
config=run/'plugins'/'StarCore'/'config.yml'
text=config.read_text(encoding='utf-8');text=re.sub(r'(?m)^(    nation-create-cost:) [^\n]+',r'\1 0.0',text);config.write_text(text,encoding='utf-8')
source=r'''package smoke;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;
public final class ChronicleSmokeFixture extends JavaPlugin {
 public void onEnable(){getServer().getGlobalRegionScheduler().runDelayed(this,t->seed(),40L);}
 private void seed(){try{
  var core=getServer().getPluginManager().getPlugin("StarCore");
  Object context=core.getClass().getMethod("context").invoke(core);
  Object registry=context.getClass().getMethod("serviceRegistry").invoke(context);
  Class<?> type=Class.forName("dev.starcore.starcore.module.nation.NationService",true,core.getClass().getClassLoader());
  Object service=((Optional<?>)registry.getClass().getMethod("find",Class.class).invoke(registry,type)).orElseThrow();
  if(((Optional<?>)type.getMethod("nationByName",String.class).invoke(service,"ChronicleSmokeNation")).isEmpty()) {
   Object nation=type.getMethod("createNation",UUID.class,String.class,String.class).invoke(service,UUID.fromString("ded7a9b7-4364-3c69-aa5a-660fa264e417"),"ChronicleSmokeFounder","ChronicleSmokeNation");
   getLogger().info("SMOKE_CREATED_REAL_NATION "+nation);
  } else getLogger().info("SMOKE_EXISTING_REAL_NATION restart deduplication check");
 }catch(Exception error){getLogger().severe("SMOKE_SEED_FAILED "+error);error.printStackTrace();}}
}'''
java=fixture/'ChronicleSmokeFixture.java';java.write_text(source,encoding='utf-8')
lib=list((run/'libraries').rglob('*.jar'))+[Path.home()/'.m2/repository/io/papermc/paper/paper-api/1.21.11-R0.1-SNAPSHOT/paper-api-1.21.11-R0.1-SNAPSHOT.jar']
jdk=Path(os.environ['JAVA_HOME']);classes=fixture/'classes';classes.mkdir(exist_ok=True)
args=fixture/'javac.args';args.write_text('--release 21\n-encoding UTF-8\n-cp\n"'+(';'.join(str(f) for f in lib)).replace('\\','/')+'"\n-d\n"'+str(classes).replace('\\','/')+'"\n"'+str(java).replace('\\','/')+'"\n',encoding='utf-8')
subprocess.run([str(jdk/'bin/javac.exe'),'@'+str(args)],check=True)
with zipfile.ZipFile(run/'plugins'/'ChronicleSmokeFixture.jar','w') as jar:
 jar.writestr('plugin.yml','name: ChronicleSmokeFixture\nversion: 1\nmain: smoke.ChronicleSmokeFixture\napi-version: "1.21"\ndepend: [StarCore, StarChronicle]\nfolia-supported: true\n')
 for path in classes.rglob('*.class'): jar.write(path,path.relative_to(classes).as_posix())
print('Built isolated smoke fixture through real StarCore NationService; creation cost set to zero only in run/smoke.')
