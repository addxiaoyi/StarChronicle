"""Read-only evidence capture. Run with a stage name; never updates either plugin database."""
from pathlib import Path
import argparse,hashlib,json,re,sqlite3

root=Path(__file__).resolve().parents[1];out=root/'docs/verification'
p=argparse.ArgumentParser();p.add_argument('stage');p.add_argument('--run-name',default='expanded-paper-smoke');p.add_argument('--archive-log',action='store_true');a=p.parse_args()
if not re.fullmatch(r'[a-z0-9-]{1,40}',a.stage):raise SystemExit('Invalid evidence stage')
if not re.fullmatch(r'expanded-paper-[a-z0-9-]{1,40}',a.run_name):raise SystemExit('Invalid isolated run name')
run=root/'run'/a.run_name
data={'plugin_sha256':hashlib.sha256((run/'plugins/StarChronicle.jar').read_bytes()).hexdigest(),'artifacts':json.loads((run/'artifact-sha256.json').read_text())}
with sqlite3.connect((run/'plugins/StarChronicle/chronicle.db').as_uri()+'?mode=ro',uri=True) as db:
    db.row_factory=sqlite3.Row
    tables=[row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")]
    for table in tables:
        if not re.fullmatch(r'[A-Za-z0-9_]+',table):raise SystemExit('Unexpected local schema table name')
        data[table]=[dict(row) for row in db.execute('SELECT * FROM '+table)]
    data['integrity']=db.execute('PRAGMA integrity_check').fetchone()[0]
target=out/f'expanded-paper-smoke-{a.stage}.json';target.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
if a.archive_log:
    lines=[];redactions=0
    for line in (run/'logs/latest.log').read_text(encoding='utf-8',errors='replace').splitlines():
        if re.search(r'(?i)secret|password|bearer|api[-_ ]?key|token\s*[:=]',line):
            lines.append('[REDACTED: credential-related log line]');redactions+=1
        else:lines.append(line)
    (out/f'expanded-paper-smoke-{a.stage}.log').write_text('\n'.join(lines)+'\n',encoding='utf-8')
    print('Archived sanitized log; redacted lines:',redactions)
authority=[row for row in data['outcomes'] if json.loads(row['json']).get('type')=='authority']
print(json.dumps({'stage':a.stage,'sha256':data['plugin_sha256'],'events':len(data['events']),'authority_outcomes':authority,'integrity':data['integrity']},ensure_ascii=False,indent=2))
