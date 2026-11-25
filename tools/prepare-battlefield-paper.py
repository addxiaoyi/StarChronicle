"""Prepare a fresh selected-stack campaign with real client stronghold capture and score victory."""
from pathlib import Path
import argparse
import hashlib
import json
import shutil
import subprocess
ROOT=Path(__file__).resolve().parents[1]
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
p=argparse.ArgumentParser(description=__doc__)
for name in ('attempt','port','starcore','runtime','artifacts-dir','java-home'):p.add_argument('--'+name,required=True)
a=p.parse_args()
command=['python3',str(ROOT/'tools/prepare-production-paper.py'),'--route','WAR','--campaign','--prepare-only']
for name in ('attempt','port','starcore','runtime','artifacts-dir','java-home'):command+=['--'+name,getattr(a,name.replace('-','_'))]
subprocess.run(command,cwd=ROOT,check=True)
run=ROOT/'run'/f'paper-epic-{sha(ROOT/"target/starchronicle-0.1.0-SNAPSHOT.jar")[:8]}-war-{a.attempt}'
manifest=json.loads((run/'preparation.json').read_text());source=ROOT/'tools/BattlefieldPaperFixture.java';shutil.copy2(source,run/'fixture'/source.name)
manifest['fixture_extra_sources_sha256'][source.name]=sha(source);manifest['fixture_main_class']='BattlefieldPaperFixture'
manifest['battlefield_acceptance']=dict(mode='native stronghold score victory',strongholds=2,points_each=50,expected_winner='AGGRESSOR',end_reason='VICTORY_DETERMINED')
manifest['production_campaign']['war_ending']='native stronghold score victory'
manifest['limitations']=['Selected isolated Paper stack; no production deployment or Folia claim.',
    'Fixture seeds countries, treasury, resources, ingredients, a battlefield and two defender-owned citadels; paid founding is disabled.',
    'Fixture uses the native aggressor-authorized start service to bypass the preparation timer; no model setters or fake score receipts.',
    'Real non-OP clients use the native stronghold GUI and the principal war-end command; native rules determine the score winner.',
    'This tests menu-based stronghold capture, not PvP kills, siege movement, army simulation, occupation duration or world-territory transfer.']
(run/'preparation.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
subprocess.run(['python3',str(ROOT/'tools/prepare-epic-paper.py'),'compile','--run',str(run),'--java-home',a.java_home],cwd=ROOT,check=True)
print(json.dumps(dict(run=str(run),status='PREPARED_NOT_ACCEPTED')))
