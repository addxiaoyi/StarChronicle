"""Prepare an isolated loopback-only Paper smoke server; never changes an existing server.

Usage: python tools/prepare-paper-smoke.py --paper ABS.jar --starcore ABS.jar --plugin target/starchronicle-0.1.0-SNAPSHOT.jar
Start the returned Java command manually and send: chronicle status, chronicle validate, stop.
Minecraft EULA acceptance is restricted to this explicitly requested test server.
"""
from pathlib import Path
import argparse, shutil, zipfile, re

p=argparse.ArgumentParser();p.add_argument('--paper',required=True);p.add_argument('--starcore',required=True);p.add_argument('--plugin',required=True)
a=p.parse_args();root=Path(__file__).resolve().parents[1];target=root/'run'/'smoke'
if target.exists(): raise SystemExit('Refusing to overwrite an existing run/smoke directory')
for f in [a.paper,a.starcore,a.plugin]:
    if not Path(f).is_file(): raise SystemExit('Missing artifact: '+f)
(target/'plugins'/'StarCore').mkdir(parents=True)
shutil.copy2(a.paper,target/'paper.jar');shutil.copy2(a.starcore,target/'plugins'/'StarCore.jar');shutil.copy2(a.plugin,target/'plugins'/'StarChronicle.jar')
(target/'eula.txt').write_text('eula=true\n',encoding='utf-8')
(target/'server.properties').write_text('server-ip=127.0.0.1\nserver-port=25577\nonline-mode=false\nenable-query=false\nenable-rcon=false\nspawn-protection=0\nview-distance=2\nsimulation-distance=2\nmax-players=4\nlevel-type=minecraft\\:flat\ngenerator-settings={"biome":"minecraft:plains","layers":[{"height":1,"block":"minecraft:bedrock"},{"height":2,"block":"minecraft:dirt"},{"height":1,"block":"minecraft:grass_block"}]}\ngenerate-structures=false\nspawn-monsters=false\nmotd=Isolated StarChronicle smoke test\n',encoding='utf-8')
with zipfile.ZipFile(a.starcore) as jar: config=jar.read('config.yml').decode('utf-8')
config=re.sub(r'(?m)^(map:\n  web:\n(?:    #[^\n]*\n)*)    enabled: true',r'\g<1>    enabled: false',config)
(target/'plugins'/'StarCore'/'config.yml').write_text(config,encoding='utf-8')
print('Prepared isolated loopback-only server:',target)
print('java -Xms512M -Xmx1536M -jar paper.jar --nogui')
