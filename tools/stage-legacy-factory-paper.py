"""Stage explicitly synthetic raw legacy records in this task's stopped boot-1 fixture only."""
from pathlib import Path
from contextlib import closing
import argparse, hashlib, json, shutil, socket, sqlite3, uuid
from legacy_factory_raw import fingerprint
ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--run',type=Path,required=True);a=p.parse_args()
run=a.run.resolve();assert run.parent==ROOT/'run' and run.name.startswith('paper-epic-')
m=json.loads((run/'preparation.json').read_text());spec=m['legacy_factory_acceptance'];e=run/'evidence'
assert not (e/'legacy-staging.json').exists() and not (e/'boot2.session.json').exists()
session=json.loads((e/'boot1.session.json').read_text());assert session['status']=='COMPLETE' and session['scenario']=='legacy-seed'
assert all(v['exit_code']==0 for v in session['processes'])
with socket.socket() as s:s.bind(('127.0.0.1',m['server_port']))
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
db=run/'plugins/StarCore/starcore.db';checkpoint=e/'checkpoints/boot1/starcore.db'
assert sha(db)==sha(checkpoint)
fixture=[json.loads(line) for line in (e/'epic-fixture.jsonl').read_text().splitlines()]
seed=next(v['data'] for v in fixture if v['kind']=='SEEDED')
raw={'factory.count':'7'}
for i,key in enumerate(('valid','processing','malformed','duplicate','duplicate','unknown','foreign')):
    fields=dict(id=spec['factories'][key],nationId=seed['A'] if key!='foreign' else str(uuid.uuid4()),name='LegacyForge' if key=='valid' else 'Quarantined'+str(i),type='SMELTER',buildTime='1700000000000',level='3',efficiency='1.1',operational='true')
    if key=='processing':fields.update(recipe='old_recipe',processingStart='1700000000123',processingBatches='37')
    if key=='malformed':fields['level']='99'
    if key=='unknown':fields['unrecognized']='retain this original evidence'
    raw.update({f'factory.{i}.{k}':v for k,v in fields.items()})
before=sha(db)
with closing(sqlite3.connect(db)) as c:
    c.execute('BEGIN IMMEDIATE')
    props=dict(c.execute('SELECT property_key,property_value FROM starcore_resource_state'))
    assert not any(k.startswith('factory.') for k in props)
    assert c.execute('SELECT count(*) FROM starcore_nation_factory_events').fetchone()[0]==0
    revision=c.execute('SELECT revision FROM starcore_resource_revision WHERE singleton=1').fetchone()[0]
    c.executemany('INSERT INTO starcore_resource_state(property_key,property_value) VALUES(?,?)',sorted(raw.items()))
    assert c.execute('UPDATE starcore_resource_revision SET revision=revision+1 WHERE singleton=1 AND revision=?',(revision,)).rowcount==1
    c.commit()
    # The transaction context alone does not close a Python SQLite connection.
    # Flush committed WAL pages before preserving the standalone source file.
    assert c.execute('PRAGMA wal_checkpoint(TRUNCATE)').fetchone()[0]==0
assert not Path(str(db)+'-wal').exists() or Path(str(db)+'-wal').stat().st_size==0
shutil.copy2(db,e/'legacy-source-starcore.db')
with closing(sqlite3.connect('file:'+str(e/'legacy-source-starcore.db')+'?mode=ro',uri=True)) as c:
    assert dict(c.execute('SELECT property_key,property_value FROM starcore_resource_state'))==dict(props,**raw)
    assert c.execute('SELECT revision FROM starcore_resource_revision WHERE singleton=1').fetchone()[0]==revision+1
shutil.copy2(__file__,e/'legacy-stage.py');shutil.copy2(Path(__file__).with_name('legacy_factory_raw.py'),e/'legacy_factory_raw.py')
record=dict(kind='EXPLICIT_STOPPED_FIXTURE_SEED',before_sha256=before,after_sha256=sha(db),revision_before=revision,revision_after=revision+1,
    resource_domain=props['resource.authority-domain'],nation=seed['A'],raw=raw,fingerprint=fingerprint(props['resource.authority-domain'],raw),
    payment_evidence='UNPROVEN',original_builder='UNKNOWN',source_sha256={name:sha(e/name) for name in ('legacy-stage.py','legacy_factory_raw.py')})
(e/'legacy-staging.json').write_text(json.dumps(record,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(dict(status='STAGED_NOT_ACCEPTED',fingerprint=record['fingerprint'],records=7)))
