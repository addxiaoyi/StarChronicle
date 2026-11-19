"""Clone the normally stopped phase-two world/database without writing its originals.

This is file/SQLite-backup preparation only. It never invokes Java or a server.
"""
from pathlib import Path
from datetime import datetime, timezone
import argparse, hashlib, json, re, shutil, sqlite3, zipfile

ROOT=Path(__file__).resolve().parents[1]
OLD_SHA='d765e0d14b9a9122e0ba1304c9c1b0d3ef844d4458295b1f4847ecb213bd943b'
SERVER_SHA='f52c408490a0225611e67907a3ca19f7e6da2c6bc899e715d5f46844e7103c39'
SOURCE_ID='chronicle:5ad9905c3336c82bef3538e5b469a5ae23e6031566a1d9f3af9d4ed07a78b492'
TABLES=('events','event_players','event_nations','instances','outcomes','facts','story_triggers','story_trigger_receipts','event_causes','story_choice_log','story_trigger_blocks','memory_sites','native_scene_journal')

def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def capture(db):
    result={}
    for table in TABLES:
        columns=[row[1] for row in db.execute('PRAGMA table_info('+table+')') if not(table=='story_triggers' and row[1]=='checked')]
        if not columns:raise ValueError('Missing real baseline table '+table)
        rows=[dict(zip(columns,row)) for row in db.execute('SELECT '+','.join(columns)+' FROM '+table)]
        result[table]=sorted(rows,key=lambda row:json.dumps(row,sort_keys=True,ensure_ascii=False))
    return result

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--expected-sha',required=True);p.add_argument('--run-name');p.add_argument('--source-run',type=Path,default=ROOT/'run/phase-two-folia-d765e0d1');p.add_argument('--product',type=Path,default=ROOT/'target/starchronicle-0.1.0-SNAPSHOT.jar');a=p.parse_args()
    if not re.fullmatch('[0-9a-f]{64}',a.expected_sha):p.error('Require full approved final product SHA-256')
    name=a.run_name or 'phase-three-folia-'+a.expected_sha[:8]
    if not re.fullmatch('phase-three-folia-[a-z0-9_.-]+',name):p.error('Require a new phase-three-folia-* directory')
    run=ROOT/'run'/name;source=a.source_run.resolve()
    if run.exists():raise SystemExit('Refusing to overwrite prior evidence/runtime')
    if sha(a.product)!=a.expected_sha or sha(source/'plugins/StarChronicle.jar')!=OLD_SHA or sha(source/'folia.jar')!=SERVER_SHA:raise SystemExit('Approved new/source/server fingerprints differ')
    exits=json.loads((source/'evidence/phase-two-folia-processes.json').read_text(encoding='utf-8'))
    if exits['server_exit_codes']!=[0,0,0] or exits['client_exit_codes']!=[0,0,0] or not exits['all_owned_children_exited']:raise SystemExit('Source must be the normally stopped, actually verified phase-two runtime')
    old_db=source/'plugins/StarChronicle/chronicle.db';old_db_sha=sha(old_db)
    sidecars={str(path):{'exists':path.exists(),'bytes':path.stat().st_size if path.exists() else 0,'sha256':sha(path) if path.exists() else None} for path in [old_db.with_name(old_db.name+'-wal'),old_db.with_name(old_db.name+'-shm')]}
    if any(value['bytes'] for path,value in sidecars.items() if path.endswith('-wal')):raise SystemExit('Source must be normally stopped and fully checkpointed; original WAL will not be changed')
    with sqlite3.connect(old_db.as_uri()+'?mode=ro&immutable=1',uri=True) as db:
        db.execute('PRAGMA query_only=ON');version=db.execute('PRAGMA user_version').fetchone()[0]
        if version!=3:raise SystemExit('Expected untouched real schema-three source')
        baseline=capture(db)
        event=next(row for row in baseline['events'] if row['id']==SOURCE_ID)
        if event['type']!='HEIR_RESTORATION_SUPPORTED':raise SystemExit('Canonical saved source differs')
        actors=set(json.loads(event['json'])['participants'])
        if len(actors)!=2 or len(baseline['story_choice_log'])!=13 or len(baseline['outcomes'])!=17:raise SystemExit('Real original choice/outcome cohort differs')
        (run/'plugins/StarChronicle/stories').mkdir(parents=True);(run/'evidence').mkdir();(run/'fixture').mkdir()
        with sqlite3.connect(run/'plugins/StarChronicle/chronicle.db') as cloned:db.backup(cloned)
    if sha(old_db)!=old_db_sha:raise SystemExit('Original database changed during read-only preparation')
    for name,before in sidecars.items():
        path=Path(name);after={'exists':path.exists(),'bytes':path.stat().st_size if path.exists() else 0,'sha256':sha(path) if path.exists() else None}
        if after!=before:raise SystemExit('Original source sidecar state changed')
    for folder in ('world','world_nether','world_the_end','libraries','cache','versions'):
        if (source/folder).is_dir():shutil.copytree(source/folder,run/folder,ignore=shutil.ignore_patterns('session.lock'))
    for name in ('folia.jar','eula.txt','server.properties'):shutil.copy2(source/name,run/name)
    # Operator permissions are not copied. The fixture installs real per-player false/true
    # permission attachments at LOWEST join priority; no PlayerContext or past fact is supplied.
    shutil.copy2(a.product,run/'plugins/StarChronicle.jar');shutil.copy2(ROOT/'tools/PhaseThreeFoliaFixture.java',run/'fixture/PhaseThreeFoliaFixture.java')
    with zipfile.ZipFile(a.product) as jar:
        stories={Path(name).name:jar.read(name) for name in jar.namelist() if name.startswith('stories/') and name.endswith(('.yml','.yaml','.json'))}
        if len(stories)!=12:raise SystemExit('Expected twelve bundled source stories')
        for name,data in stories.items():(run/'plugins/StarChronicle/stories'/name).write_bytes(data)
        (run/'plugins/StarChronicle/npcs.yml').write_bytes(jar.read('npcs.yml'))
    old=stories['heir-legacy.yml'].decode('utf-8')
    for token in ('version: "1"','title: "旧都档案：每个人的那一票"','condition:\n  all:\n','  archive:\n','cooldownSeconds: 604800'):
        if old.count(token)!=1:raise SystemExit('Bundled legacy layout changed; explicit fixture adaptation required')
    new=old.replace('version: "1"','version: "2"',1).replace('title: "旧都档案：每个人的那一票"','title: "Phase Three Legacy v2"',1)
    # This acceptance advances the same story ID immediately after its real v1
    # completion. Disable only this test version's legitimate production cooldown
    # rather than changing clocks, old updated timestamps or product behavior.
    new=new.replace('cooldownSeconds: 604800','cooldownSeconds: 0',1)
    new=new.replace('triggers: [HEIR_RESTORATION_SUPPORTED, HEIR_RESTORATION_REJECTED, IMPERIAL_HERITAGE_INITIATIVE]','triggers: [PHASE_THREE_ARCHIVE_REVIEWED]',1)
    new=new.replace('condition:\n  all:\n','condition:\n  all:\n    - {fact: past.player.choice.phase-three-archive/1/desk, op: exists}\n',1)
    new=new.replace('  archive:\n','  archive:\n    effects:\n      - {id: phase-three-entry-gate, type: memory, target: phase-three.v2-entry, value: authorized, permission: phase-three.v2.ready}\n',1)
    for old_type,new_type in [('HEIR_PERSONAL_TESTIMONY','PHASE_THREE_V2_TESTIMONY'),('HEIR_WITNESS_ACCOUNT','PHASE_THREE_V2_WITNESS'),('HEIR_RECORDS_RECONCILED','PHASE_THREE_V2_RECONCILED')]:new=new.replace('target: '+old_type,'target: '+new_type)
    (run/'plugins/StarChronicle/stories/heir-legacy.yml').write_text('# TEST ONLY: a real new version, not a rewritten v1 archive.\n'+new,encoding='utf-8')
    review='''# TEST ONLY: exact real old ballots are read; none are supplied by the fixture.
id: phase-three-archive
version: "1"
title: "Phase Three Archive Review"
scope: PLAYER
triggers: [HEIR_RESTORATION_SUPPORTED]
condition: {fact: past.player.choice.heir-legacy/1/archive, op: exists}
start: desk
nodes:
  desk:
    text: "Read your actual saved first-edition decision before writing a new review."
    choices:
      preserve:
        label: "Preserve the original testimony"
        condition: {fact: past.player.choice.heir-legacy/1/archive, op: eq, value: testimony}
        next: preserved
      annotate:
        label: "Annotate the reconciled archive"
        condition: {fact: past.player.choice.heir-legacy/1/archive, op: eq, value: reconcile}
        next: annotated
  preserved:
    text: "The real first-edition testimony remains unchanged."
    terminal: true
    effects:
      - {id: honor, type: honor, target: player, value: "2"}
      - {id: title, type: title, target: phase-three-preserver, value: "Third phase archive preserver"}
      - {id: record, type: history, target: PHASE_THREE_ARCHIVE_REVIEWED, value: "A real returning voter reviewed their saved testimony."}
  annotated:
    text: "The real first-edition reconciliation remains unchanged."
    terminal: true
    effects:
      - {id: honor, type: honor, target: player, value: "2"}
      - {id: title, type: title, target: phase-three-commentator, value: "Third phase archive commentator"}
      - {id: record, type: history, target: PHASE_THREE_ARCHIVE_REVIEWED, value: "A real returning voter reviewed their saved reconciliation."}
'''
    (run/'plugins/StarChronicle/stories/phase-three-archive.yml').write_text(review,encoding='utf-8')
    (run/'plugins/StarChronicle/config.yml').write_text('require-starcore: false\ninstall-default-stories: false\nadmin-demo-enabled: false\nnpc-hooks: false\nworker-queue-capacity: 2048\npoll-ticks: 12000\n',encoding='utf-8')
    (run/'config').mkdir(exist_ok=True);(run/'config/paper-global.yml').write_text('_version: 31\nchunk-system:\n  io-threads: 1\n  worker-threads: 1\nspark:\n  enabled: false\nthreaded-regions:\n  threads: 2\n',encoding='utf-8')
    now=datetime.now(timezone.utc).isoformat()
    baseline.update(schema_version=3,source_database_sha256=old_db_sha,source_sidecars=sidecars,source_plugin_sha256=OLD_SHA,source_event_id=SOURCE_ID,source_run=str(source),captured_utc=now,excluded_columns={'story_triggers':['checked']})
    (run/'evidence/phase-three-folia-source-baseline.json').write_text(json.dumps(baseline,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    manifest={'status':'PREPARED_NOT_COMPILED_NOT_RUN','prepared_utc':now,'plugin_sha256':sha(run/'plugins/StarChronicle.jar'),'server_sha256':sha(run/'folia.jar'),'fixture_source_sha256':sha(run/'fixture/PhaseThreeFoliaFixture.java'),'source_database_sha256':old_db_sha,'source_original_path':str(old_db),'source_plugin_sha256':OLD_SHA,'source_event_id':SOURCE_ID,'cohort':sorted(actors),'run_directory':str(run),'story_count':13,'story_sha256':{path.name:sha(path) for path in sorted((run/'plugins/StarChronicle/stories').iterdir())},'scope':'StarChronicle standalone on real Folia; NOT StarCore full-stack certification','server_port':25578,'poll_ticks':12000,'fixture_compiled':False,'server_started':False}
    (run/'acceptance-preparation.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8');print(json.dumps(manifest,ensure_ascii=True,indent=2))

if __name__=='__main__':main()
