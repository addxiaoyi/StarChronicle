"""Prepare isolated paid factory, level and bound production acceptance on the selected stack."""
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
source=ROOT/'tools/NationFactoryPaperFixture.java';shutil.copy2(source,run/'fixture'/source.name)
m['fixture_extra_sources_sha256'][source.name]=sha(source);m['fixture_main_class']='NationFactoryPaperFixture'
def recipe(id,level):
 return dict(id=id,name='工厂精炼'+str(level),inputs=dict(mineral=3,energy=1),outputs=dict(industrial=2),**{'seconds-per-batch':30,'max-batches':100,'factory':{'type':'SMELTER','minimum-level':level}})
catalog=dict(format=2,enabled=True,limits={'max-running-runs':16,'max-running-batches':1000},recipes=[recipe('factory_refining',1),recipe('advanced_refining',2)])
(run/'plugins/StarCore/nation-production.json').write_text(json.dumps(catalog,ensure_ascii=False,indent=2)+'\n')
shutil.copy2(run/'plugins/StarCore/nation-production.json',run/'fixture/nation-factory-catalog.json')
with zipfile.ZipFile(run/'plugins/EssentialsX-2.22.0.jar') as z:ess=z.read('config.yml').decode()
assert ess.count('update-check: true')==1
folder=run/'plugins/Essentials';folder.mkdir(exist_ok=True);(folder/'config.yml').write_text(ess.replace('update-check: true','update-check: false'))
m['nation_factory_acceptance']=dict(scenarios=['factory-first','factory-second','factory-third'],catalog_sha256=sha(run/'fixture/nation-factory-catalog.json'),
    operations={name:str(uuid.uuid4()) for name in ('build','upgrade','off','on','successor_upgrade','successor_off')},expected_changes=6,expected_completions=3,expected_contributions=6,essentials_update_check=False)
m['limitations']=['Selected isolated Paper stack and SQLite; MySQL atomicity is a separate native integration test.',
 'Nations and inventory are explicit fixture seeds; factory and production mutations are real non-OP player commands.',
 'Factory records do not place world blocks or claim land. Legacy factory fields are retained but never become paid production or contribution evidence.',
 'Three normal boots are not physical power-loss, Folia or production deployment evidence.']
(run/'preparation.json').write_text(json.dumps(m,ensure_ascii=False,indent=2)+'\n')
subprocess.run(['python3',str(ROOT/'tools/prepare-epic-paper.py'),'compile','--run',str(run),'--java-home',str(a.java_home)],check=True,cwd=ROOT)
print(json.dumps(dict(run=str(run),status='PREPARED_NOT_ACCEPTED')))
