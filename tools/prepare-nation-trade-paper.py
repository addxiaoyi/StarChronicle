"""Prepare real national sale acceptance against the selected frozen Paper/plugin stack."""
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
a=p.parse_args()
subprocess.run(['python3',str(ROOT/'tools/prepare-production-paper.py'),'--prepare-only','--attempt',a.attempt,'--port',str(a.port),
    '--starcore',str(a.starcore),'--runtime',str(a.runtime),'--java-home',str(a.java_home),'--artifacts-dir',str(a.artifacts_dir)],check=True,cwd=ROOT)
sha=lambda path:hashlib.sha256(path.read_bytes()).hexdigest()
run=ROOT/'run'/f'paper-epic-{sha(ROOT/"target/starchronicle-0.1.0-SNAPSHOT.jar")[:8]}-dungeon-{a.attempt}'
manifest=json.loads((run/'preparation.json').read_text())
extra=ROOT/'tools/NationTradePaperFixture.java';shutil.copy2(extra,run/'fixture'/extra.name)
manifest['fixture_extra_sources_sha256'][extra.name]=sha(extra);manifest['fixture_main_class']='NationTradePaperFixture'
configPath=run/'plugins/StarChronicle/config.yml';config=yaml.safe_load(configPath.read_text())
config.update({'install-native-example-quests':False,'install-epic-strategy-example':False,'poll-ticks':72000 if a.offline else 20})
configPath.write_text(yaml.safe_dump(config,allow_unicode=True,sort_keys=False))
manifest['nation_trade_acceptance']={'scenarios':['sale-first','sale-second','sale-third'],'resource':'agricultural','sales':3,'quantity':29,'total_value':'75.00','contributions':12}
if a.offline:
    manifest['nation_trade_acceptance']={'scenarios':['sale-offline-first','sale-offline-second'],'resource':'agricultural','sales':1,'quantity':20,'total_value':'50.00','contributions':4,'mode':'offline'}
manifest['limitations']=['Selected isolated Paper stack; no production deployment or full Folia acceptance.',
    'Test nations, treasury funds and inventory are explicitly seeded through public native APIs; nation creation is free in this fixture.',
    'Offers, acceptance, cancellation and replays are actual non-OP player commands. Founder handover uses the real native authorization service.',
    'This protocol pays from national treasuries and delivers national inventory. It makes no claim for Vault wallets, player inventory, legacy market orders or taxes.',
    'SQL rollback and lost commit acknowledgements are product tests; these real boots validate normal persisted recovery.']
(run/'preparation.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
subprocess.run(['python3',str(ROOT/'tools/prepare-epic-paper.py'),'compile','--run',str(run),'--java-home',str(a.java_home)],check=True,cwd=ROOT)
print(json.dumps({'run':str(run),'status':'PREPARED_NOT_ACCEPTED'}))
