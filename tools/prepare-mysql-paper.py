"""Bind a freshly prepared isolated Paper run to an empty dedicated MySQL catalog."""
from pathlib import Path
import argparse
import json
import socket
import yaml
import mysql_paper_snapshot as M

ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--run',type=Path,required=True)
p.add_argument('--instance',type=Path,required=True,help='Public preparation.json only; never a credential file')
p.add_argument('--database',required=True)
a=p.parse_args();run=a.run.resolve()
assert run.parent==(ROOT/'run').resolve() and run.name.startswith('paper-epic-')
assert not list((run/'evidence').glob('boot*')) and not (run/'plugins/StarCore/starcore.db').exists()
manifest=json.loads((run/'preparation.json').read_text());assert 'mysql_acceptance' not in manifest
instance=json.loads(a.instance.read_text());assert a.database in instance['databases'] and instance['local_only'] is True
with socket.socket() as probe:probe.bind(('127.0.0.1',manifest['server_port']))
path=run/'plugins/StarCore/config.yml';before=path.read_text();config=yaml.safe_load(before)
target=dict(host=instance['host'],port=instance['port'],user=instance['user'],database=a.database,
            container=instance['container'],container_id=instance['id'],image=instance['image'],password_env=instance['password_env'])
with M.connect(target) as db:
    with db.cursor() as c:
        c.execute('SHOW TABLES');assert not c.fetchall(),'Require a new empty catalog'
        c.execute('SELECT VERSION(),DATABASE(),@@server_uuid');version,catalog,server=c.fetchone()
        assert version.startswith('8.4.') and catalog==a.database
configuration=dict(host=target['host'],port=target['port'],database=a.database,username=target['user'],password='',
                   **{'password-env':target['password_env'],'parameters':'useUnicode=true&characterEncoding=utf8&sslMode=REQUIRED&serverTimezone=UTC&connectTimeout=5000&socketTimeout=30000','allow-insecure':True})
config['database'].update(enabled=True,**{'fail-fast':True,'type':'mysql','mysql':configuration})
path.write_text(yaml.safe_dump(config,allow_unicode=True,sort_keys=False))
target.update(configuration=configuration,version=version,server_uuid=server,initial_catalog_empty=True,
              snapshot_format='mysql raw rows plus lossless SQLite audit projection; not a native SQLite database')
manifest['mysql_acceptance']=target
manifest['limitations'].append('Native storage is isolated MySQL8.4/InnoDB over encrypted loopback without production TLS identity verification; starcore.db checkpoints are explicit lossless audit projections of raw MySQL rows.')
(run/'evidence/mysql-config-preparation.json').write_text(json.dumps(dict(before_sha256=M.hashlib.sha256(before.encode()).hexdigest(),after_sha256=M.sha(path),database_configuration=configuration,empty_catalog=True,server_uuid=server,version=version),ensure_ascii=False,indent=2)+'\n')
(run/'preparation.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(dict(run=str(run),database=a.database,status='MYSQL_PREPARED_NOT_ACCEPTED')))
