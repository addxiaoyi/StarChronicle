"""Prepare real national production acceptance against the selected frozen Paper/plugin stack."""
from pathlib import Path
import argparse
import hashlib
import json
import shutil
import subprocess
import yaml

ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--attempt',required=True)
p.add_argument('--port',type=int,required=True)
p.add_argument('--starcore',type=Path,required=True)
p.add_argument('--runtime',type=Path,required=True)
p.add_argument('--java-home',type=Path,required=True)
p.add_argument('--artifacts-dir',type=Path,required=True)
p.add_argument('--offline',action='store_true')
p.add_argument('--prepare-only',action='store_true',help='Leave fixture uncompiled for a composed acceptance scenario')
a=p.parse_args()
subprocess.run(['python3',str(ROOT/'tools/prepare-production-paper.py'),'--prepare-only','--attempt',a.attempt,'--port',str(a.port),
    '--starcore',str(a.starcore),'--runtime',str(a.runtime),'--java-home',str(a.java_home),'--artifacts-dir',str(a.artifacts_dir)],check=True,cwd=ROOT)
sha=lambda path:hashlib.sha256(path.read_bytes()).hexdigest()
run=ROOT/'run'/f'paper-epic-{sha(ROOT/"target/starchronicle-0.1.0-SNAPSHOT.jar")[:8]}-dungeon-{a.attempt}'
manifest=json.loads((run/'preparation.json').read_text())
extra=ROOT/'tools/NationProductionPaperFixture.java';shutil.copy2(extra,run/'fixture'/extra.name)
manifest['fixture_extra_sources_sha256'][extra.name]=sha(extra);manifest['fixture_main_class']='NationProductionPaperFixture'
configPath=run/'plugins/StarChronicle/config.yml';config=yaml.safe_load(configPath.read_text())
config.update({'install-native-example-quests':False,'install-epic-strategy-example':False,'poll-ticks':72000 if a.offline else 20})
configPath.write_text(yaml.safe_dump(config,allow_unicode=True,sort_keys=False))
manifest['nation_production_acceptance']={'scenarios':['production-first','production-second','production-third'],'completions':4,'contributions':8,'mode':'main'}
if a.offline:
    manifest['nation_production_acceptance']={'scenarios':['production-offline-first','production-offline-second'],'completions':1,'contributions':2,'mode':'offline'}
manifest['limitations']=['Selected isolated Paper stack; no production deployment or full Folia acceptance.',
    'Test nations and initial national resources are explicitly seeded through public native APIs; nation creation is free in this fixture.',
    'Production start, collection, cancellation and replays are actual non-OP player commands. Succession uses the real native authorization service.',
    'Paid production runs use national inventory, fixed recipe terms and original initiators. Legacy actorless factory writes are disabled; no migration invents old contributors.',
    'SQL rollback and lost commit acknowledgements are product tests; real boots validate normal persisted recovery.']
(run/'preparation.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
if not a.prepare_only:
    subprocess.run(['python3',str(ROOT/'tools/prepare-epic-paper.py'),'compile','--run',str(run),'--java-home',str(a.java_home)],check=True,cwd=ROOT)
print(json.dumps({'run':str(run),'status':'PREPARED_NOT_ACCEPTED'}))
