"""Prepare a separate loopback-only real Folia smoke server. No StarCore flag overrides."""
from pathlib import Path
import shutil,hashlib,json
root=Path(__file__).resolve().parents[1];run=root/'run'/'folia-smoke';jar=root/'run'/'folia-1.21.11-14.jar'
expected='f52c408490a0225611e67907a3ca19f7e6da2c6bc899e715d5f46844e7103c39'
assert hashlib.sha256(jar.read_bytes()).hexdigest()==expected,'Official Folia artifact checksum mismatch'
if run.exists():raise SystemExit('Refusing to overwrite run/folia-smoke')
(run/'plugins'/'StarChronicle'/'stories').mkdir(parents=True)
shutil.copy2(jar,run/'folia.jar');shutil.copy2(root/'target'/'starchronicle-0.1.0-SNAPSHOT.jar',run/'plugins'/'StarChronicle.jar')
for directory in ['cache','libraries']:
    source=root/'run'/'smoke'/directory
    if source.exists():shutil.copytree(source,run/directory)
(run/'eula.txt').write_text('eula=true\n',encoding='utf-8')
(run/'server.properties').write_text('server-ip=127.0.0.1\nserver-port=25578\nonline-mode=false\nenable-query=false\nenable-rcon=false\nspawn-protection=0\nview-distance=2\nsimulation-distance=2\nmax-players=4\nlevel-type=minecraft\\:flat\ngenerator-settings={"biome":"minecraft:plains","layers":[{"height":1,"block":"minecraft:bedrock"},{"height":2,"block":"minecraft:dirt"},{"height":1,"block":"minecraft:grass_block"}]}\ngenerate-structures=false\ngamemode=creative\ndifficulty=peaceful\nmotd=Isolated StarChronicle real Folia test\n',encoding='utf-8')
(run/'plugins'/'StarChronicle'/'config.yml').write_text('require-starcore: false\nadmin-demo-enabled: false\nnpc-hooks: false\nworker-queue-capacity: 2048\npoll-ticks: 100\n',encoding='utf-8')
(run/'plugins'/'StarChronicle'/'stories'/'folia-smoke.yml').write_text('''id: folia-smoke
version: "1"
title: "Real Folia restart fixture"
scope: WORLD
triggers: [FOLIA_SMOKE_EVENT]
start: vote
nodes:
  vote:
    text: "Two real connected protocol clients vote across a server restart."
    quorum: 2
    choices:
      remember:
        label: "Preserve this history"
        next: end
  end:
    text: "Recovered and completed on the real region-threaded server."
    terminal: true
    effects:
      - {id: record, type: history, target: FOLIA_SMOKE_COMPLETED, value: "Two connected test clients completed a restarted shared storyline."}
      - {id: title, type: title, target: folia-smoke-witness, value: "Folia smoke witness"}
''',encoding='utf-8')
(root/'docs'/'verification'/'folia-official-build.json').write_text(json.dumps({'project':'folia','version':'1.21.11','build':14,'commit':'529aabc13852021f6081f8a6069959a449c33785','sha256':expected,'metadata':'https://fill.papermc.io/v3/projects/folia/versions/1.21.11/builds','artifact':'https://fill-data.papermc.io/v1/objects/'+expected+'/folia-1.21.11-14.jar'},indent=2),encoding='utf-8')
print('Prepared isolated real Folia server:',run)
