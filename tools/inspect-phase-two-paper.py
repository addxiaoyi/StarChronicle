"""Capture phase-two evidence with read-only SQLite handles; never writes either plugin DB."""
from pathlib import Path
from datetime import datetime,timezone
import argparse,hashlib,json,re,sqlite3

ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'docs/verification'
p=argparse.ArgumentParser();p.add_argument('stage');p.add_argument('--run-name',default='phase-two-paper-release-smoke');p.add_argument('--archive-log',action='store_true');a=p.parse_args()
if not re.fullmatch(r'[a-z0-9-]{1,48}',a.stage):raise SystemExit('Invalid evidence stage')
if not re.fullmatch(r'phase-two-paper-[a-z0-9-]{1,40}',a.run_name):raise SystemExit('Invalid isolated run name')
run=ROOT/'run'/a.run_name;target=OUT/f'phase-two-paper-{a.stage}.json';log_target=OUT/f'phase-two-paper-{a.stage}.log'
if target.exists() or a.archive_log and log_target.exists():raise SystemExit('Refusing to overwrite an existing phase-two evidence stage')
manifest=json.loads((run/'artifact-sha256.json').read_text(encoding='utf-8'))
data={'stage':a.stage,'run_name':a.run_name,'captured_at':datetime.now(timezone.utc).isoformat(),'plugin_sha256':hashlib.sha256((run/'plugins/StarChronicle.jar').read_bytes()).hexdigest(),'artifacts':manifest}
with sqlite3.connect((run/'plugins/StarChronicle/chronicle.db').as_uri()+'?mode=ro',uri=True) as db:
    db.row_factory=sqlite3.Row;data['schema_version']=db.execute('PRAGMA user_version').fetchone()[0]
    tables=[row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")]
    for table in tables:
        if not re.fullmatch(r'[A-Za-z0-9_]+',table):raise SystemExit('Unexpected schema identifier')
        data[table]=[dict(row) for row in db.execute('SELECT * FROM '+table)]
    data['integrity']=db.execute('PRAGMA integrity_check').fetchone()[0];data['foreign_key_violations']=[tuple(row) for row in db.execute('PRAGMA foreign_key_check')]
with sqlite3.connect((run/'plugins/StarCore/starcore.db').as_uri()+'?mode=ro',uri=True) as db:
    db.row_factory=sqlite3.Row
    data['core_nation_state']=[dict(row) for row in db.execute('SELECT property_key,property_value FROM starcore_nation_state ORDER BY property_key')]
    data['core_integrity']=db.execute('PRAGMA integrity_check').fetchone()[0]
data['loaded_resource_sha256']={name:hashlib.sha256((run/'plugins/StarChronicle'/name).read_bytes()).hexdigest() for name in manifest['resource_sha256']}
target.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
if a.archive_log:
    lines=[];redactions=0
    for line in (run/'logs/latest.log').read_text(encoding='utf-8',errors='replace').splitlines():
        if re.search(r'(?i)secret|password|bearer|api[-_ ]?key|token\s*[:=]',line):lines.append('[REDACTED: credential-related log line]');redactions+=1
        else:lines.append(line)
    log_target.write_text('\n'.join(lines)+'\n',encoding='utf-8');print('Archived sanitized server log; redactions:',redactions)
instances=[json.loads(row['json']) for row in data['instances'] if row['story'] in ['last-heir','heir-legacy']]
print(json.dumps({'stage':a.stage,'sha256':data['plugin_sha256'],'schema':data['schema_version'],'relevant_instances':[{'story':i['definition']['id'],'scope':i['scopeKey'],'node':i['node'],'completed':i['completed']} for i in instances],'choice_rows':len(data['story_choice_log']),'causal_rows':len(data['event_causes']),'integrity':data['integrity'],'core_integrity':data['core_integrity']},ensure_ascii=False,indent=2))
