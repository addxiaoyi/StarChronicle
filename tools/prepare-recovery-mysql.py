"""Create a fresh dedicated MySQL instance for restore/crash acceptance; retain failed instances."""
from pathlib import Path
import argparse
import json
import os
import secrets
import time
import mysql_recovery as R


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--directory',type=Path,required=True);p.add_argument('--port',type=int,default=33318)
    p.add_argument('--source-instance',type=Path,required=True)
    p.add_argument('--container',choices=[R.CONTAINER,R.TRADE_CONTAINER],default=R.CONTAINER)
    p.add_argument('--password-env',choices=['STARCORE_RECOVERY_DB_PASSWORD','STARCORE_TRADE_RECOVERY_DB_PASSWORD'],default='STARCORE_RECOVERY_DB_PASSWORD');a=p.parse_args()
    directory=a.directory.resolve();R.private_location(directory);R.check(not directory.exists(),'Require new private directory')
    R.safe_port(a.port)
    R.check(a.container not in R.docker('ps','-a','--format','{{.Names}}').decode().splitlines(),'Existing recovery container retained')
    R.docker('image','inspect',R.IMAGE)
    source=R.read(a.source_instance);R.owned(source)
    databases=list(source['databases']);R.check(all(__import__('re').fullmatch('starcore_paper_[a-z0-9_]+',s) for s in databases),'Unexpected catalog')
    directory.mkdir(mode=0o700)
    envfile=directory/'mysql.env'
    with open(envfile,'x',opener=lambda path,flags:os.open(path,flags,0o600)) as f:
        f.write('MYSQL_ROOT_PASSWORD='+secrets.token_hex(32)+'\nMYSQL_USER=starcore_paper\nMYSQL_PASSWORD='+secrets.token_hex(32)+'\nMYSQL_DATABASE='+databases[0]+'\n')
    identity=R.docker('run','-d','--name',a.container,'--label','codex.task='+a.container,'-p',f'127.0.0.1:{a.port}:3306','--memory','768m','--cpus','1','--env-file',envfile,
                     R.IMAGE,'--innodb-buffer-pool-size=64M','--max-connections=40').decode().strip()
    instance=dict(container=a.container,id=identity,image=R.IMAGE,host='127.0.0.1',port=a.port,user='starcore_paper',databases=databases,password_env=a.password_env,local_only=True)
    R.write(directory/'preparation.json',instance)
    os.environ[instance['password_env']]=dict(line.split('=',1) for line in envfile.read_text().splitlines())['MYSQL_PASSWORD']
    deadline=time.monotonic()+60
    while True:
        try:
            with R.M.connect(dict(instance,database=databases[0])) as db:
                with db.cursor() as c:c.execute('SELECT VERSION()');R.check(c.fetchone()[0].startswith('8.4.'),'Unexpected MySQL version')
            break
        except Exception:
            if time.monotonic()>deadline:raise
            time.sleep(.25)
    sql=[]
    for name in databases[1:]:
        escaped=name.replace('_','\\_');sql += [f'CREATE DATABASE `{name}` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;',f"GRANT ALL PRIVILEGES ON `{escaped}`.* TO 'starcore_paper'@'%';"]
    R.docker('exec','-i',a.container,'sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --user=root --protocol=socket',input=('\n'.join(sql)+'\n').encode())
    R.write(directory/'ready.json',dict(status='EMPTY_RESTORE_INSTANCE_READY',state=R.owned(instance),time=time.time()))
    print(json.dumps(dict(status='READY',container_id=identity,port=a.port,databases=databases)))


if __name__=='__main__':main()
