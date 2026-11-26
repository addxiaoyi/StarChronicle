"""Prepare direct player-wallet trade acceptance on the selected frozen Paper stack."""
from pathlib import Path
import argparse
import hashlib
import json
import shutil
import subprocess
import yaml

ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__)
for name in ('attempt','port','starcore','runtime','java-home','artifacts-dir'):
    p.add_argument('--'+name,required=True,type=int if name=='port' else str if name=='attempt' else Path)
a=p.parse_args()
subprocess.run(['python3',str(ROOT/'tools/prepare-production-paper.py'),'--prepare-only','--attempt',a.attempt,'--port',str(a.port),
    '--starcore',str(a.starcore),'--runtime',str(a.runtime),'--java-home',str(a.java_home),'--artifacts-dir',str(a.artifacts_dir)],check=True,cwd=ROOT)
sha=lambda path:hashlib.sha256(path.read_bytes()).hexdigest()
run=ROOT/'run'/f'paper-epic-{sha(ROOT/"target/starchronicle-0.1.0-SNAPSHOT.jar")[:8]}-dungeon-{a.attempt}'
manifest=json.loads((run/'preparation.json').read_text())
extra=ROOT/'tools/PlayerTradePaperFixture.java';shutil.copy2(extra,run/'fixture'/extra.name)
manifest['fixture_extra_sources_sha256'][extra.name]=sha(extra);manifest['fixture_main_class']='PlayerTradePaperFixture'
config_path=run/'plugins/StarChronicle/config.yml';config=yaml.safe_load(config_path.read_text())
config.update({'install-native-example-quests':False,'install-epic-strategy-example':False,'poll-ticks':20})
config_path.write_text(yaml.safe_dump(config,allow_unicode=True,sort_keys=False))
manifest['player_trade_acceptance']={'scenarios':['player-trade-first','player-trade-second','player-trade-third'],
    'settlements':3,'quantity':29,'gross':'75.00','fees':'1.50','tariffs':'4.80','total':'81.30','contributions':12}
manifest['limitations']=['Selected isolated Paper stack, explicit test funds and nations, actual non-OP trade commands.',
    'Wallet effects are acknowledged by Essentials/Vault. Normal restart is not proof of atomic external payment or provider disk durability.',
    'Resource inventory is national stockpile; player backpacks, order book and quick trades are outside this protocol.',
    'SQL rollback and call-intent ambiguity are separate product tests, not physical power-loss acceptance.',
    'Legacy live trade notifications remain best effort; durable consumers use the independent settlement journal.']
(run/'preparation.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
subprocess.run(['python3',str(ROOT/'tools/prepare-epic-paper.py'),'compile','--run',str(run),'--java-home',str(a.java_home)],check=True,cwd=ROOT)
print(json.dumps({'run':str(run),'status':'PREPARED_NOT_ACCEPTED'}))
