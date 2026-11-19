"""Read-only SQLite and artifact evidence from the isolated Paper smoke server."""
from pathlib import Path
import hashlib,json,sqlite3,sys
root=Path(__file__).resolve().parents[1];run=root/'run'/'smoke'
database=run/'plugins'/'StarChronicle'/'chronicle.db'
with sqlite3.connect(database.as_uri()+'?mode=ro',uri=True) as db:
    data={'plugin_sha256':hashlib.sha256((run/'plugins'/'StarChronicle.jar').read_bytes()).hexdigest(),
          'events':db.execute('select id,type,time,hash from events order by id').fetchall(),
          'facts':db.execute('select owner,key,value from facts order by owner,key').fetchall(),
          'integrity':db.execute('pragma integrity_check').fetchone()[0]}
out=root/'docs'/'verification'/sys.argv[1];out.write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(data,ensure_ascii=False,indent=2))
