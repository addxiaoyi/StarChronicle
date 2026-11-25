"""Prepare isolated legacy audit/payment acceptance; raw fixtures are staged only after boot 1 stops."""
from pathlib import Path
import argparse, hashlib, json, shutil, subprocess, uuid, zipfile
ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--attempt',required=True);p.add_argument('--port',type=int,required=True)
for name in ('starcore','runtime','java-home','artifacts-dir'):p.add_argument('--'+name,type=Path,required=True)
a=p.parse_args()
subprocess.run(['python3',str(ROOT/'tools/prepare-nation-production-paper.py'),'--prepare-only','--attempt',a.attempt,'--port',str(a.port),'--starcore',str(a.starcore),'--runtime',str(a.runtime),'--java-home',str(a.java_home),'--artifacts-dir',str(a.artifacts_dir)],check=True,cwd=ROOT)
sha=lambda path:hashlib.sha256(path.read_bytes()).hexdigest()
run=ROOT/'run'/f'paper-epic-{sha(ROOT/"target/starchronicle-0.1.0-SNAPSHOT.jar")[:8]}-dungeon-{a.attempt}'
m=json.loads((run/'preparation.json').read_text())
source=ROOT/'tools/LegacyFactoryPaperFixture.java';shutil.copy2(source,run/'fixture'/source.name)
m['fixture_extra_sources_sha256'][source.name]=sha(source);m['fixture_main_class']='LegacyFactoryPaperFixture'
catalog=dict(format=2,enabled=True,limits={'max-running-runs':16,'max-running-batches':1000},recipes=[dict(id='legacy_refining',name='登记工厂精炼',inputs=dict(mineral=3,energy=1),outputs=dict(industrial=2),**{'seconds-per-batch':30,'max-batches':100,'factory':dict(type='SMELTER',**{'minimum-level':3})})])
(run/'plugins/StarCore/nation-production.json').write_text(json.dumps(catalog,ensure_ascii=False,indent=2)+'\n')
shutil.copy2(run/'plugins/StarCore/nation-production.json',run/'fixture/legacy-factory-catalog.json')
with zipfile.ZipFile(run/'plugins/EssentialsX-2.22.0.jar') as z:ess=z.read('config.yml').decode()
assert ess.count('update-check: true')==1
folder=run/'plugins/Essentials';folder.mkdir(exist_ok=True);(folder/'config.yml').write_text(ess.replace('update-check: true','update-check: false'))
m['legacy_factory_acceptance']=dict(scenarios=['legacy-seed','legacy-first','legacy-second','legacy-third'],catalog_sha256=sha(run/'fixture/legacy-factory-catalog.json'),
    operations={name:str(uuid.uuid4()) for name in ('register','enable','upgrade','disable')},factories={name:str(uuid.uuid4()) for name in ('valid','processing','malformed','duplicate','unknown','foreign')},expected_changes=4,expected_completions=2,expected_contributions=4)
m['limitations']=['Selected isolated Paper/SQLite stack; real non-OP player registration/enable/upgrade/production commands.',
 'Nations/materials are explicit fixture seeds. Seven raw legacy records are staged while stopped after boot 1, not claimed as real user assets.',
 'Idle well-formed records require a new full cumulative material payment. Old payments/builders/processing are unproven; no automatic refund, delivery or contribution.',
 'Four normal boots are not crash, physical power-loss, Folia, world-block construction or production deployment evidence.']
(run/'preparation.json').write_text(json.dumps(m,ensure_ascii=False,indent=2)+'\n')
subprocess.run(['python3',str(ROOT/'tools/prepare-epic-paper.py'),'compile','--run',str(run),'--java-home',str(a.java_home)],check=True,cwd=ROOT)
print(json.dumps(dict(run=str(run),status='PREPARED_NOT_ACCEPTED')))
