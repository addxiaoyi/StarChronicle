"""Normally stop/start only the prepared task-owned MySQL container between stopped Paper boots."""
from pathlib import Path
import argparse
import json
import os
import socket
import subprocess
import shutil
import time
import mysql_paper_snapshot as M

ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--run',type=Path,required=True);p.add_argument('--after-boot',type=int,required=True)
a=p.parse_args();run=a.run.resolve();assert run.parent==(ROOT/'run').resolve()
manifest=json.loads((run/'preparation.json').read_text());target=M.config(run,manifest)
assert target['container']=='starcore-paper-mysql-20261125'
evidence=run/'evidence';session=json.loads((evidence/f'boot{a.after_boot}.session.json').read_text())
assert session['status']=='COMPLETE' and all(v['exit_code']==0 for v in session['processes'])
for item in (ROOT/'run').glob('*/preparation.json'):
    candidate=json.loads(item.read_text())
    if candidate.get('mysql_acceptance',{}).get('container')==target['container']:
        with socket.socket() as probe:probe.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1);probe.bind(('127.0.0.1',candidate['server_port']))
prior=json.loads((evidence/f'mysql/boot{a.after_boot}.json').read_text())
output=evidence/f'mysql/restart-after-boot{a.after_boot}.json';assert not output.exists()
frozen=evidence/f'mysql/restart-after-boot{a.after_boot}.py';assert not frozen.exists()
shutil.copy2(__file__,frozen)
env={k:v for k,v in os.environ.items() if k not in {'DOCKER_HOST','DOCKER_CONTEXT','DOCKER_TLS','DOCKER_TLS_VERIFY','DOCKER_CERT_PATH'}}
base=['docker','--host=unix:///var/run/docker.sock']
def docker(*args):return subprocess.check_output([*base,*args],text=True,env=env).strip()
def state():return json.loads(docker('inspect','--format','{{json .State}}',target['container']))
assert docker('inspect','--format','{{.Id}}',target['container'])==target['container_id']
assert docker('inspect','--format','{{index .Config.Labels "codex.task"}}',target['container'])=='starcore-paper-mysql-20261125'
before=state();assert before['Running'];started=time.time()
docker('stop','--time','30',target['container']);stopped=state();assert not stopped['Running'] and stopped['ExitCode']==0 and not stopped['OOMKilled']
docker('start',target['container']);deadline=time.monotonic()+45
while True:
    try:
        with M.connect(target) as db:
            with db.cursor() as c:
                c.execute('SELECT @@server_uuid');server_uuid=c.fetchone()[0]
        break
    except Exception:
        if time.monotonic()>deadline:raise
        time.sleep(.25)
after=state();assert after['Running'] and after['StartedAt']!=before['StartedAt'] and server_uuid==prior['server']['server_uuid']
actual=[]
with M.connect(target) as db:
    with db.cursor() as c:
        c.execute('SELECT TABLE_NAME,TABLE_TYPE,ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME')
        wanted=sorted([(t['name'],'BASE TABLE',t['engine']) for t in prior['tables']]+[(v[0],'VIEW',None) for v in prior['views']])
        assert list(c.fetchall())==wanted,'Database objects changed across restart'
        c.execute('SELECT TABLE_NAME,VIEW_DEFINITION,CHECK_OPTION,IS_UPDATABLE,SECURITY_TYPE,CHARACTER_SET_CLIENT,COLLATION_CONNECTION FROM information_schema.VIEWS WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME')
        views=[list(row) for row in c.fetchall()];assert views==prior['views'],'Database views changed across restart'
        db.commit()
        c.execute('SET TRANSACTION ISOLATION LEVEL REPEATABLE READ');c.execute('START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY')
        for table in prior['tables']:
            c.execute('SELECT COLUMN_NAME,DATA_TYPE,COLUMN_TYPE,IS_NULLABLE,COLLATION_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=%s ORDER BY ORDINAL_POSITION',(table['name'],))
            columns=[list(r) for r in c.fetchall()];assert columns==table['columns']
            c.execute('SELECT * FROM `'+table['name']+'`');rows=[[M.encode(v) for v in row] for row in c.fetchall()];rows.sort(key=M.compact)
            assert rows==table['rows'],'Database restart changed committed data: '+table['name']
            actual.append(dict(name=table['name'],columns=columns,engine=table['engine'],rows=rows))
        db.commit()
record=dict(status='NORMAL_DATABASE_RESTART_PRESERVED_ALL_TABLES',after_boot=a.after_boot,started_at=started,finished_at=time.time(),container_id=target['container_id'],before=before,stopped=stopped,after=after,server_uuid=server_uuid,
            previous_snapshot_sha256=M.sha(evidence/f'mysql/boot{a.after_boot}.json'),script_sha256=M.sha(frozen),after_tables=actual,after_views=views)
with output.open('x') as f:f.write(json.dumps(record,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(dict(status=record['status'],after_boot=a.after_boot,tables=len(actual),normal_exit=stopped['ExitCode'])))
