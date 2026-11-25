"""Restore a verified offline Paper bundle to a new directory and empty owned MySQL catalog."""
from pathlib import Path
import argparse
import importlib.util
import json
import os
import re
import time
import zipfile
import yaml
import mysql_recovery as R
S=importlib.util.spec_from_file_location('backup',Path(__file__).with_name('backup-mysql-paper.py'));B=importlib.util.module_from_spec(S);S.loader.exec_module(B)


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--backup',type=Path,required=True);p.add_argument('--sha256',required=True);p.add_argument('--instance',type=Path,required=True)
    p.add_argument('--attempt',required=True);p.add_argument('--port',type=int,required=True)
    a=p.parse_args();R.check(re.fullmatch('[a-z0-9-]+',a.attempt),'Invalid attempt')
    proof=B.verify(a.backup,a.sha256);instance=R.read(a.instance);R.check(instance['container'] in (R.CONTAINER,R.TRADE_CONTAINER),'Restore only to the dedicated recovery instance')
    R.check(R.owned(instance)['Running'],'Recovery MySQL not running');database=proof['source_target']['database']
    R.check(database in instance['databases'],'Catalog not owned by recovery fixture');R.safe_port(a.port)
    with zipfile.ZipFile(a.backup) as z:
        original=json.loads(z.read('server/preparation.json'));raw=json.loads(z.read('native.json'))
        originals={name:z.read('server/'+name) for name in ('preparation.json','server.properties','plugins/StarCore/config.yml')}
        run=R.ROOT/'run'/f'paper-epic-{original["plugin_sha256"][:8]}-{original["route"].lower()}-{a.attempt}'
        R.check(not run.exists(),'Restore never overwrites an existing run')
        target={k:instance[k] for k in ('host','port','user','container','image','password_env')};target.update(database=database,container_id=instance['id'])
        with R.M.connect(target) as db:
            with db.cursor() as c:
                c.execute('SHOW FULL TABLES');R.check(not c.fetchall(),'Restore catalog must be empty')
                for table,column in [('ROUTINES','ROUTINE_SCHEMA'),('EVENTS','EVENT_SCHEMA')]:
                    c.execute('SELECT COUNT(*) FROM information_schema.'+table+' WHERE '+column+'=DATABASE()')
                    R.check(c.fetchone()[0]==0,'Restore catalog must have no routines/events')
        run.mkdir(mode=0o700,parents=True);R.write(run/'restore-incomplete.json',dict(backup_sha256=a.sha256,started_at=time.time()))
        # Extract manually only after full bundle verification; reject links, traversal and extra members above.
        for name,expected in proof['files'].items():
            dst=run/name;dst.parent.mkdir(parents=True,exist_ok=True)
            with dst.open('xb') as f:f.write(z.read('server/'+name))
        R.check(all(R.M.sha(run/n)==v['sha256'] for n,v in proof['files'].items()),'Extracted file differs')
        began=time.time()
        R.docker('exec','-i',instance['container'],'sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --user=root --protocol=socket "$1"','restore',database,input=z.read('native.sql'))
    restored=R.snapshot(target);R.equal_data(raw,restored)
    R.check(restored['server_uuid']!=raw['server_uuid'],'Restore must use an independent MySQL server')
    changes={}
    config_path=run/'plugins/StarCore/config.yml';config=yaml.safe_load(config_path.read_text())
    settings=dict(config['database']['mysql']);settings.update(host=target['host'],port=target['port'],username=target['user'],password='',**{'password-env':target['password_env']})
    config['database']['mysql']=settings;config_path.write_text(yaml.safe_dump(config,allow_unicode=True,sort_keys=False))
    target.update(configuration=settings,version=restored['version'],server_uuid=restored['server_uuid'],initial_catalog_empty=True,snapshot_format=proof['source_target']['snapshot_format'])
    properties=run/'server.properties';content=properties.read_text();content,count=re.subn(r'(?m)^server-port=\d+$','server-port='+str(a.port),content);R.check(count==1,'Missing game port');properties.write_text(content)
    original.update(server_port=a.port,mysql_acceptance=target,recovery_acceptance=dict(mode='offline-backup-restore',source_run=Path(proof['source_run']).name,source_boot=proof['stopped_session']['boot'],backup_sha256=a.sha256))
    (run/'preparation.json').write_text(json.dumps(original,ensure_ascii=False,indent=2)+'\n')
    for name in ('preparation.json','server.properties','plugins/StarCore/config.yml'):
        changes[name]=dict(before_sha256=proof['files'][name]['sha256'],after_sha256=R.M.sha(run/name))
    for name,expected in proof['files'].items():
        if name not in changes:R.check(R.M.sha(run/name)==expected['sha256'],'Unexpected restore file change')
    evidence=run/'evidence/recovery';evidence.mkdir()
    for name,content in originals.items():
        (evidence/('source-'+Path(name).name)).write_bytes(content)
    R.write(evidence/'restored-files.json',{name:dict(bytes=(run/name).stat().st_size,sha256=R.M.sha(run/name)) for name in proof['files']})
    R.write(evidence/'backup-manifest.json',proof)
    R.write(evidence/'source-native.json',raw);R.write(evidence/'restored-native.json',restored)
    R.write(evidence/'restore.json',dict(status='FULL_OFFLINE_BACKUP_RESTORED',backup=str(a.backup.resolve()),backup_sha256=a.sha256,started_at=began,finished_at=time.time(),
        source_server_uuid=raw['server_uuid'],target_server_uuid=restored['server_uuid'],target=target,files_verified=len(proof['files']),permitted_changes=changes,game_started=False))
    (run/'restore-incomplete.json').unlink()
    print(json.dumps(dict(status='RESTORED_NOT_RUNTIME_ACCEPTED',run=str(run),files_verified=len(proof['files']),database=database)))


if __name__=='__main__':main()
