"""Prepare the selected Paper stack for strict production catalogs and frozen old runs."""
from pathlib import Path
import argparse,hashlib,json,shutil,subprocess,zipfile
ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--attempt',required=True);p.add_argument('--port',type=int,required=True)
for name in ('starcore','runtime','java-home','artifacts-dir'):p.add_argument('--'+name,type=Path,required=True)
a=p.parse_args()
subprocess.run(['python3',str(ROOT/'tools/prepare-nation-production-paper.py'),'--attempt',a.attempt,'--port',str(a.port),'--starcore',str(a.starcore),'--runtime',str(a.runtime),'--java-home',str(a.java_home),'--artifacts-dir',str(a.artifacts_dir)],check=True,cwd=ROOT)
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
run=ROOT/'run'/f'paper-epic-{sha(ROOT/"target/starchronicle-0.1.0-SNAPSHOT.jar")[:8]}-dungeon-{a.attempt}'
m=json.loads((run/'preparation.json').read_text())
def config(id,name,mineral,energy,industrial,seconds,max_batches,runs,batches):
 return dict(format=1,enabled=True,limits={'max-running-runs':runs,'max-running-batches':batches},recipes=[dict(id=id,name=name,inputs=dict(mineral=mineral,energy=energy),outputs=dict(industrial=industrial),**{'seconds-per-batch':seconds,'max-batches':max_batches})])
variants=dict(v1=config('custom_forge','定制锻造',2,1,3,30,2,1,2),v2=config('renewed_forge','精炼新规',5,2,7,1,2,2,2),v3=config('renewed_forge','精炼再修订',9,3,11,1,1,2,3),disabled=dict(format=1,enabled=False,limits={'max-running-runs':1,'max-running-batches':2},recipes=[]))
for name,value in variants.items():(run/'fixture'/f'production-catalog-{name}.json').write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n')
(run/'fixture/production-catalog-invalid.json').write_text('{"format":1,"enabled":true,"enabled":false,"limits":{"max-running-runs":1,"max-running-batches":2},"recipes":[]}\n')
shutil.copy2(run/'fixture/production-catalog-v1.json',run/'plugins/StarCore/nation-production.json')
# Disable only the optional external update check, which can outlive plugin shutdown.
with zipfile.ZipFile(run/'plugins/EssentialsX-2.22.0.jar') as z:ess=z.read('config.yml').decode()
assert ess.count('update-check: true')==1
path=run/'plugins/Essentials';path.mkdir(exist_ok=True)
(path/'config.yml').write_text(ess.replace('update-check: true','update-check: false'))
m['production_catalog_acceptance']=dict(scenarios=['production-catalog-first','production-catalog-second','production-catalog-third'],variants_sha256={name:sha(run/'fixture'/f'production-catalog-{name}.json') for name in (*variants,'invalid')},expected_completions=3,expected_contributions=6,essentials_update_check=False)
m['limitations']=['Selected isolated Paper stack and SQLite; MySQL capacity concurrency is validated separately in native SQL integration tests.','Nation identities and initial inventory are explicit fixture seeds; all production operations use actual non-OP player commands.','Three normal boots validate admin reload, invalid hot reload, invalid startup, paid frozen terms, capacity and exact refunds; this is not a production deployment or Folia acceptance.','Concurrent limits count RUNNING national runs/batches; this does not implement factory buildings, building levels or legacy paid-run migration.']
(run/'preparation.json').write_text(json.dumps(m,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(dict(run=str(run),status='PREPARED_NOT_ACCEPTED')))
