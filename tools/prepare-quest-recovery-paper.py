"""Prepare a new selected-stack task recovery campaign; never alters an existing run."""
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
command=['python3',str(ROOT/'tools/prepare-production-paper.py'),'--route','DUNGEON','--campaign','--prepare-only']
for name in ('attempt','port','starcore','runtime','artifacts-dir','java-home'):command+=['--'+name,getattr(a,name.replace('-','_'))]
subprocess.run(command,cwd=ROOT,check=True)
run=ROOT/'run'/f'paper-epic-{sha(ROOT/"target/starchronicle-0.1.0-SNAPSHOT.jar")[:8]}-dungeon-{a.attempt}'
manifest=json.loads((run/'preparation.json').read_text());source=ROOT/'tools/QuestRecoveryPaperFixture.java';shutil.copy2(source,run/'fixture'/source.name)
manifest['fixture_extra_sources_sha256'][source.name]=sha(source);manifest['fixture_main_class']='QuestRecoveryPaperFixture'
manifest['quest_recovery_acceptance']=dict(quest='chronicle_heir_records',owner='A',round=1,scenarios=['quest-first','first','second','third'],fault='real shard storage stripe delayed; actual GUI abandonment; no forged receipts')
manifest['limitations'] += ['A fixture thread holds the actual persistence stripe for the old task shard until explicitly released; this models delayed storage, not a power loss or irreparable UNKNOWN.', 'Client uses native abandonment and Chronicle reconciliation/retry GUIs; actual crafting and public native completion path remain unchanged.', 'Four normal boots restore the replacement active run, finish the real task, settle once, and reject repeats.']
(run/'preparation.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
subprocess.run(['python3',str(ROOT/'tools/prepare-epic-paper.py'),'compile','--run',str(run),'--java-home',a.java_home],cwd=ROOT,check=True)
print(json.dumps(dict(run=str(run),status='PREPARED_NOT_ACCEPTED')))
