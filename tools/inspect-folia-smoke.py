"""Capture read-only SQLite evidence from the isolated real Folia smoke server."""
from pathlib import Path
import hashlib, json, sqlite3, sys

root = Path(__file__).resolve().parents[1]
run = root / 'run' / 'folia-smoke'
database = run / 'plugins' / 'StarChronicle' / 'chronicle.db'
with sqlite3.connect(database.as_uri() + '?mode=ro', uri=True) as db:
    data = {
        'plugin_sha256': hashlib.sha256((run / 'plugins' / 'StarChronicle.jar').read_bytes()).hexdigest(),
        'events': db.execute('select id,type,time,hash,json from events order by id').fetchall(),
        'instances': db.execute('select id,story,scope,completed,updated,json from instances order by id').fetchall(),
        'outcomes': db.execute('select id,status,json from outcomes order by id').fetchall(),
        'facts': db.execute('select owner,key,value from facts order by owner,key').fetchall(),
        'integrity': db.execute('pragma integrity_check').fetchone()[0],
    }
out = root / 'docs' / 'verification' / sys.argv[1]
out.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(data, ensure_ascii=False, indent=2))
