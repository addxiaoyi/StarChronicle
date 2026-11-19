"""Prepare an isolated real-Core acceptance run; never starts Java or edits prior runs.

Run after the root build succeeds. Uses the already verified Paper/StarCore binaries by default.
The fixture makes changes only through public services and actual connected players.
"""
from pathlib import Path
import argparse, hashlib, json, os, re, shutil, subprocess, zipfile

ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser()
p.add_argument('--paper',type=Path,default=ROOT/'run/smoke/paper.jar')
p.add_argument('--starcore',type=Path,default=ROOT/'run/smoke/plugins/StarCore.jar')
p.add_argument('--plugin',type=Path,default=ROOT/'target/starchronicle-0.1.0-SNAPSHOT.jar')
p.add_argument('--run-name',default='expanded-paper-smoke')
p.add_argument('--java-home',type=Path,default=Path(os.environ.get('JAVA_HOME',r'C:\Users\Administrator\Documents\luoseng\.toolchains\jdk-21.0.13.0.2+1')))
a=p.parse_args()
if not re.fullmatch(r'expanded-paper-[a-z0-9-]{1,40}',a.run_name):raise SystemExit('Invalid isolated run name')
run=ROOT/'run'/a.run_name
if run.exists():raise SystemExit('Refusing to overwrite existing expanded-paper-smoke evidence')
for path in [a.paper,a.starcore,a.plugin,a.java_home/'bin/javac.exe']:
    if not path.is_file():raise SystemExit('Missing required artifact: '+str(path))
(run/'plugins/StarCore').mkdir(parents=True)
(run/'plugins/StarChronicle/stories').mkdir(parents=True)
for source,dest in [(a.paper,run/'paper.jar'),(a.starcore,run/'plugins/StarCore.jar'),(a.plugin,run/'plugins/StarChronicle.jar')]:shutil.copy2(source,dest)
for folder in ['cache','libraries']:
    source=ROOT/'run/smoke'/folder
    if source.exists():shutil.copytree(source,run/folder)
(run/'eula.txt').write_text('eula=true\n',encoding='utf-8')
(run/'server.properties').write_text('''server-ip=127.0.0.1
server-port=25579
online-mode=false
enable-query=false
enable-rcon=false
spawn-protection=0
view-distance=2
simulation-distance=2
max-players=4
level-type=minecraft\\:flat
generator-settings={"biome":"minecraft:plains","layers":[{"height":1,"block":"minecraft:bedrock"},{"height":2,"block":"minecraft:dirt"},{"height":1,"block":"minecraft:grass_block"}]}
generate-structures=false
spawn-monsters=false
motd=Isolated expanded StarChronicle verification
''',encoding='utf-8')
with zipfile.ZipFile(a.starcore) as archive:config=archive.read('config.yml').decode('utf-8')
config=re.sub(r'(?m)^(    nation-create-cost:) [^\n]+',r'\1 0.0',config)
config=re.sub(r'(?m)^(map:\n  web:\n(?:    #[^\n]*\n)*)    enabled: true',r'\g<1>    enabled: false',config)
(run/'plugins/StarCore/config.yml').write_text(config,encoding='utf-8')
(run/'plugins/StarChronicle/config.yml').write_text('require-starcore: true\nadmin-demo-enabled: false\nnpc-hooks: false\nworker-queue-capacity: 2048\npoll-ticks: 20\n',encoding='utf-8')
fixture=run/'fixture';classes=fixture/'classes';classes.mkdir(parents=True)
source=ROOT/'tools/ExpandedPaperFixture.java';shutil.copy2(source,fixture/source.name)
jars=list((run/'libraries').rglob('*.jar'))+[run/'plugins/StarChronicle.jar',Path.home()/'.m2/repository/io/papermc/paper/paper-api/1.21.11-R0.1-SNAPSHOT/paper-api-1.21.11-R0.1-SNAPSHOT.jar']
args=fixture/'javac.args'
args.write_text('--release 21\n-encoding UTF-8\n-cp\n"'+(';'.join(str(f) for f in jars)).replace('\\','/')+'"\n-d\n"'+str(classes).replace('\\','/')+'"\n"'+str(fixture/source.name).replace('\\','/')+'"\n',encoding='utf-8')
subprocess.run([str(a.java_home/'bin/javac.exe'),'@'+str(args)],check=True)
with zipfile.ZipFile(run/'plugins/ExpandedPaperFixture.jar','w') as archive:
    archive.writestr('plugin.yml','name: ExpandedPaperFixture\nversion: 1\nmain: smoke.ExpandedPaperFixture\napi-version: "1.21"\ndepend: [StarCore, StarChronicle]\ncommands:\n  expanded:\n    description: Isolated console acceptance fixture\n')
    for path in classes.rglob('*.class'):archive.write(path,path.relative_to(classes).as_posix())
manifest={name:hashlib.sha256(path.read_bytes()).hexdigest() for name,path in [('paper',run/'paper.jar'),('starcore',run/'plugins/StarCore.jar'),('starchronicle',run/'plugins/StarChronicle.jar'),('fixture',run/'plugins/ExpandedPaperFixture.jar')]}
(run/'artifact-sha256.json').write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8')
print('Prepared isolated loopback acceptance:',run)
print(json.dumps(manifest,indent=2))
print('Start only in the assigned JVM window. Two clients: tools/expanded-paper-clients.cjs')
