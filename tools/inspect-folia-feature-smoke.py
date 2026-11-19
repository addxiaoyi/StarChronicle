"""Read-only SQLite capture for the current feature acceptance; prior evidence is untouched."""
from pathlib import Path
from datetime import datetime,timezone,timedelta
import hashlib,json,sqlite3,sys

root=Path(__file__).resolve().parents[1]
name=sys.argv[2] if len(sys.argv)>2 else 'folia-final-smoke-abaadf4c'
if '/' in name or '\\' in name or name in ['.','..']:raise SystemExit('Invalid isolated run directory name')
run=root/'run'/name
database=run/'plugins'/'StarChronicle'/'chronicle.db'
now=datetime.now(timezone.utc)
with sqlite3.connect(database.as_uri()+'?mode=ro',uri=True) as db:
    tables={row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    data={
        'captured_utc':now.isoformat(),
        'captured_asia_shanghai':now.astimezone(timezone(timedelta(hours=8))).isoformat(),
        'run_directory':run.relative_to(root).as_posix(),
        'plugin_sha256':hashlib.sha256((run/'plugins'/'StarChronicle.jar').read_bytes()).hexdigest(),
        'events':db.execute('SELECT id,type,time,hash,json FROM events ORDER BY id').fetchall(),
        'instances':db.execute('SELECT id,story,scope,completed,updated,json FROM instances ORDER BY id').fetchall(),
        'outcomes':db.execute('SELECT id,status,json FROM outcomes ORDER BY id').fetchall(),
        'facts':db.execute('SELECT owner,key,value FROM facts ORDER BY owner,key').fetchall(),
        'memory_sites':db.execute('SELECT id,event_id,json FROM memory_sites ORDER BY id').fetchall() if 'memory_sites' in tables else [],
        # The checked poll counter is intentionally excluded: it is liveness metadata, not a result.
        'story_triggers':db.execute('SELECT id,trigger_key,event_id,scope,story_json FROM story_triggers ORDER BY id').fetchall() if 'story_triggers' in tables else [],
        'story_trigger_receipts':db.execute('SELECT trigger_key,scope FROM story_trigger_receipts ORDER BY trigger_key,scope').fetchall() if 'story_trigger_receipts' in tables else [],
        'integrity':db.execute('PRAGMA integrity_check').fetchone()[0],
    }
target=root/'docs'/'verification'/sys.argv[1]
target.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({key:value for key,value in data.items() if key not in ['events','instances','facts','outcomes','memory_sites','story_triggers','story_trigger_receipts']},ensure_ascii=False,indent=2))
print('Rows:',{key:len(data[key]) for key in ['events','instances','outcomes','facts','memory_sites','story_triggers','story_trigger_receipts']})
