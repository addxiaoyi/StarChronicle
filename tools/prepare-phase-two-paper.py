"""Prepare a new isolated phase-two Paper run. Compilation starts only when explicitly run
after the coordinator supplies the frozen SHA-256 and the exclusive JVM window."""
from pathlib import Path
import argparse,hashlib,json,os,re,shutil,subprocess,zipfile

ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser()
p.add_argument('--run-name',default='phase-two-paper-release-smoke')
p.add_argument('--expected-plugin-sha256',required=True)
p.add_argument('--paper',type=Path,default=ROOT/'run/smoke/paper.jar')
p.add_argument('--starcore',type=Path,default=ROOT/'run/smoke/plugins/StarCore.jar')
p.add_argument('--plugin',type=Path,default=ROOT/'target/starchronicle-0.1.0-SNAPSHOT.jar')
p.add_argument('--java-home',type=Path,default=Path(os.environ.get('JAVA_HOME',r'C:\Users\Administrator\Documents\luoseng\.toolchains\jdk-21.0.13.0.2+1')))
a=p.parse_args()
if not re.fullmatch(r'phase-two-paper-[a-z0-9-]{1,40}',a.run_name):raise SystemExit('Invalid isolated run name')
if not re.fullmatch(r'[a-f0-9]{64}',a.expected_plugin_sha256):raise SystemExit('Expected an exact frozen plugin SHA-256')
run=ROOT/'run'/a.run_name
if run.exists():raise SystemExit('Refusing to overwrite any prior phase-two run or evidence')
for source in [a.paper,a.starcore,a.plugin,a.java_home/'bin/javac.exe']:
    if not source.is_file():raise SystemExit('Missing required artifact: '+str(source))
digest=lambda path:hashlib.sha256(path.read_bytes()).hexdigest()
if digest(a.plugin)!=a.expected_plugin_sha256:raise SystemExit('Product JAR differs from the coordinator-frozen fingerprint')
resources=['stories/last-heir.yml','stories/heir-legacy.yml','npcs.yml']
with zipfile.ZipFile(a.plugin) as archive:
    resource_hashes={name:hashlib.sha256(archive.read(name)).hexdigest() for name in resources}
(run/'plugins/StarCore').mkdir(parents=True)
(run/'plugins/StarChronicle').mkdir(parents=True)
for source,target in [(a.paper,run/'paper.jar'),(a.starcore,run/'plugins/StarCore.jar'),(a.plugin,run/'plugins/StarChronicle.jar')]:shutil.copy2(source,target)
for folder in ['cache','libraries']:
    if (ROOT/'run/smoke'/folder).exists():shutil.copytree(ROOT/'run/smoke'/folder,run/folder)
(run/'eula.txt').write_text('eula=true\n',encoding='utf-8')
(run/'server.properties').write_text('''server-ip=127.0.0.1
server-port=25589
online-mode=false
enable-query=false
enable-rcon=false
spawn-protection=0
view-distance=2
simulation-distance=2
max-players=6
level-type=minecraft\\:flat
generator-settings={"biome":"minecraft:plains","layers":[{"height":1,"block":"minecraft:bedrock"},{"height":2,"block":"minecraft:dirt"},{"height":1,"block":"minecraft:grass_block"}]}
generate-structures=false
spawn-monsters=false
motd=Isolated phase-two StarChronicle verification
''',encoding='utf-8')
with zipfile.ZipFile(a.starcore) as archive:config=archive.read('config.yml').decode('utf-8')
config=re.sub(r'(?m)^(    nation-create-cost:) [^\n]+',r'\1 0.0',config)
config=re.sub(r'(?m)^(map:\n  web:\n(?:    #[^\n]*\n)*)    enabled: true',r'\g<1>    enabled: false',config)
(run/'plugins/StarCore/config.yml').write_text(config,encoding='utf-8')
# First poll occurs shortly after startup, then ten minutes later. Finish boot one before
# that later poll and do not call api.instances or /chronicle stories after the epic ending.
(run/'plugins/StarChronicle/config.yml').write_text('require-starcore: true\nadmin-demo-enabled: false\nnpc-hooks: false\nworker-queue-capacity: 2048\npoll-ticks: 12000\n',encoding='utf-8')
fixture=run/'fixture';classes=fixture/'classes';classes.mkdir(parents=True)
source=ROOT/'tools/PhaseTwoPaperFixture.java';shutil.copy2(source,fixture/source.name)
jars=list((run/'libraries').rglob('*.jar'))+[run/'plugins/StarChronicle.jar',Path.home()/'.m2/repository/io/papermc/paper/paper-api/1.21.11-R0.1-SNAPSHOT/paper-api-1.21.11-R0.1-SNAPSHOT.jar']
args=fixture/'javac.args'
args.write_text('--release 21\n-proc:none\n-encoding UTF-8\n-cp\n"'+(';'.join(str(path) for path in jars)).replace('\\','/')+'"\n-d\n"'+str(classes).replace('\\','/')+'"\n"'+str(fixture/source.name).replace('\\','/')+'"\n',encoding='utf-8')
subprocess.run([str(a.java_home/'bin/javac.exe'),'@'+str(args)],check=True)
with zipfile.ZipFile(run/'plugins/PhaseTwoPaperFixture.jar','w') as archive:
    archive.writestr('plugin.yml','name: PhaseTwoPaperFixture\nversion: 1\nmain: smoke.PhaseTwoPaperFixture\napi-version: "1.21"\ndepend: [StarCore, StarChronicle]\ncommands:\n  phase2:\n    description: Isolated phase-two real-player acceptance fixture\n')
    for path in classes.rglob('*.class'):archive.write(path,path.relative_to(classes).as_posix())
manifest={name:digest(path) for name,path in [('paper',run/'paper.jar'),('starcore',run/'plugins/StarCore.jar'),('starchronicle',run/'plugins/StarChronicle.jar'),('fixture',run/'plugins/PhaseTwoPaperFixture.jar')]}
manifest.update({'fixture_source_sha256':digest(source),'resource_sha256':resource_hashes,'port':25589,'poll_ticks':12000})
(run/'artifact-sha256.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print('Prepared new phase-two real-Core run:',run)
print(json.dumps(manifest,ensure_ascii=False,indent=2))
print('No server was started. Start only during the exclusive coordinator-assigned JVM window.')
