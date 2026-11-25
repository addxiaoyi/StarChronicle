"""Shared local-only recovery evidence primitives; no production or remote database access."""
from pathlib import Path
from contextlib import closing
import json
import os
import re
import socket
import sqlite3
import subprocess
import time
import mysql_paper_snapshot as M

ROOT = Path(__file__).resolve().parents[1]
IMAGE = 'public.ecr.aws/docker/library/mysql@sha256:8c233bad33d520a7aae1c1e619c3a9775f4a1ba2d463d692fada04ec7f40be00'
CONTAINER = 'starcore-recovery-mysql-20261125-retry2'
TRADE_CONTAINER = 'starcore-recovery-mysql-20261125-trade-retry3'
RECOVERY_CONTAINERS = (CONTAINER, TRADE_CONTAINER, 'starcore-recovery-mysql-20261125')


def check(value, message):
    if not value:
        raise AssertionError(message)


def read(path): return json.loads(Path(path).read_text())
def write(path, data):
    with Path(path).open('x') as stream:
        json.dump(data, stream, ensure_ascii=False, indent=2); stream.write('\n')


def docker(*args, input=None, stdout=subprocess.PIPE, merge_stderr=False):
    env = {k:v for k,v in os.environ.items() if k not in {'DOCKER_HOST','DOCKER_CONTEXT','DOCKER_TLS','DOCKER_TLS_VERIFY','DOCKER_CERT_PATH'}}
    result = subprocess.run(['docker','--host=unix:///var/run/docker.sock',*map(str,args)],input=input,stdout=stdout,stderr=subprocess.STDOUT if merge_stderr else subprocess.PIPE,env=env)
    check(result.returncode == 0, 'Owned Docker operation failed: '+(result.stderr or b'').decode(errors='replace')[-1500:])
    return result.stdout


def owned(instance):
    check(instance['container'] in (*RECOVERY_CONTAINERS, 'starcore-paper-mysql-20261125'), 'Unowned recovery container')
    check(instance['host']=='127.0.0.1' and instance['port'] != 3306 and instance['local_only'], 'Require isolated loopback MySQL')
    check(docker('inspect','--format','{{.Id}}',instance['container']).decode().strip()==instance['id'], 'Container identity differs')
    check(docker('inspect','--format','{{index .Config.Labels "codex.task"}}',instance['container']).decode().strip()==instance['container'], 'Container ownership label differs')
    check(docker('inspect','--format','{{.Config.Image}}',instance['container']).decode().strip()==IMAGE,'Recovery MySQL image differs')
    bindings=json.loads(docker('inspect','--format','{{json .HostConfig.PortBindings}}',instance['container']))
    check(bindings.get('3306/tcp')==[dict(HostIp='127.0.0.1',HostPort=str(instance['port']))],'MySQL port binding differs from owned instance')
    return json.loads(docker('inspect','--format','{{json .State}}',instance['container']))


def port_free(port):
    with socket.socket() as probe:
        probe.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1);probe.bind(('127.0.0.1',port))


def private_location(path):
    path=Path(path).resolve()
    check(not path.is_relative_to(ROOT) and not any((p/'.git').exists() for p in (path,*path.parents)),'Private recovery files must be outside Git worktrees')


def safe_port(port):
    check(1024<=port<=65535 and port not in (3306,8000,8001,8080,8081,8082,8083,8085,9222,31460,50002),'Reserved or invalid port')
    port_free(port)


def stopped(run):
    manifest=read(run/'preparation.json');port_free(manifest['server_port'])
    sessions=[read(p) for p in (run/'evidence').glob('boot*.session.json')]
    check(sessions,'Missing stopped game evidence')
    latest=max(sessions,key=lambda s:s['boot'])
    check(latest['status']=='COMPLETE' and all(p.get('exit_code')==0 for p in latest['processes']), 'Require a normally stopped source')
    # A bound port alone cannot detect a JVM that is still starting or has closed its listener.
    for p in Path('/proc').iterdir():
        if p.name.isdigit():
            try:
                if (p/'cwd').resolve()==run.resolve():
                    command=(p/'cmdline').read_bytes().split(b'\0')
                    check(not command or Path(command[0].decode(errors='replace')).name not in ('java','java.exe'), 'Source JVM is still running')
            except (FileNotFoundError,PermissionError,ProcessLookupError): pass
    return manifest,latest


def snapshot(target):
    began=time.time()
    with M.connect(target) as db:
        with db.cursor() as c:
            c.execute('SELECT VERSION(),DATABASE(),@@server_uuid,@@innodb_flush_log_at_trx_commit,@@sync_binlog')
            version,catalog,server,flush,sync=c.fetchone()
            check(version.startswith('8.4.') and catalog==target['database'],'Wrong recovery server')
            c.execute("SHOW SESSION STATUS LIKE 'Ssl_cipher'");cipher=c.fetchone()[1];check(cipher,'Unencrypted recovery snapshot')
            c.execute('SELECT TABLE_NAME,TABLE_TYPE,ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME')
            objects=c.fetchall()
            check(objects and all(kind=='VIEW' or (kind=='BASE TABLE' and engine=='InnoDB') for _,kind,engine in objects),'Unsupported nontransactional storage')
            c.execute('SELECT TABLE_NAME,VIEW_DEFINITION,CHECK_OPTION,IS_UPDATABLE,SECURITY_TYPE,CHARACTER_SET_CLIENT,COLLATION_CONNECTION FROM information_schema.VIEWS WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME')
            views=[list(r) for r in c.fetchall()]
            db.commit();c.execute('SET TRANSACTION ISOLATION LEVEL REPEATABLE READ');c.execute('START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY')
            tables=[];ddl={}
            for name,kind,engine in objects:
                check(re.fullmatch('[a-zA-Z0-9_]+',name),'Invalid database identifier')
                c.execute('SHOW CREATE '+('VIEW' if kind=='VIEW' else 'TABLE')+' `'+name+'`');ddl[name]=c.fetchone()[1]
                if kind=='VIEW':continue
                c.execute('SELECT COLUMN_NAME,DATA_TYPE,COLUMN_TYPE,IS_NULLABLE,COLLATION_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=%s ORDER BY ORDINAL_POSITION',(name,))
                columns=[list(r) for r in c.fetchall()]
                c.execute('SELECT * FROM `'+name+'`');rows=[[M.encode(v) for v in r] for r in c.fetchall()];rows.sort(key=M.compact)
                tables.append(dict(name=name,engine=engine,columns=columns,rows=rows))
            c.execute('SELECT TRIGGER_NAME,ACTION_TIMING,EVENT_MANIPULATION,EVENT_OBJECT_TABLE,ACTION_STATEMENT FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() ORDER BY TRIGGER_NAME');triggers=[list(r) for r in c.fetchall()]
            c.execute('SELECT ROUTINE_NAME,ROUTINE_TYPE,ROUTINE_DEFINITION FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA=DATABASE() ORDER BY ROUTINE_NAME');routines=[list(r) for r in c.fetchall()]
            c.execute('SELECT EVENT_NAME,EVENT_DEFINITION,STATUS FROM information_schema.EVENTS WHERE EVENT_SCHEMA=DATABASE() ORDER BY EVENT_NAME');events=[list(r) for r in c.fetchall()]
            db.commit()
    return dict(format=1,storage='mysql',started_at=began,finished_at=time.time(),database=catalog,server_uuid=server,version=version,ssl_cipher=cipher,
                innodb_flush_log_at_trx_commit=flush,sync_binlog=sync,tables=tables,views=views,ddl=ddl,triggers=triggers,routines=routines,events=events)


def canonical_ddl(snapshot):
    """MySQL adds explicit utf8mb4 after dump import; retain every other DDL byte.

    Only normalize a leading column definition whose exact type and collation
    match independently captured information_schema metadata. Never touch SQL
    literals, comments, keys, defaults, constraints, AUTO_INCREMENT or views.
    """
    ddl=dict(snapshot['ddl'])
    for table in snapshot['tables']:
        for name,kind,ctype,nullable,collation in table['columns']:
            if collation and collation.startswith('utf8mb4_'):
                pattern=r'(?m)^(  `'+re.escape(name)+'` '+re.escape(ctype)+r') COLLATE '+re.escape(collation)+r'(?= |,)'
                ddl[table['name']]=re.sub(pattern,lambda m:m.group(1)+' CHARACTER SET utf8mb4 COLLATE '+collation,ddl[table['name']])
    return ddl


def equal_data(a,b):
    for name in ('database','version','tables','views','triggers','routines','events'):
        check(a[name]==b[name],'Recovery database differs: '+name)
    check(canonical_ddl(a)==canonical_ddl(b),'Recovery database differs: ddl')


def files(run):
    result={}
    for path in sorted(run.rglob('*')):
        check(not path.is_symlink(),'Cannot back up a symlink: '+str(path))
        if path.is_file():
            relative=path.relative_to(run).as_posix()
            if '__pycache__' in path.parts:continue
            result[relative]=dict(bytes=path.stat().st_size,sha256=M.sha(path))
    return result


def sqlite_copy(source, destination):
    """SQLite's online backup includes committed WAL frames without changing source data."""
    check(not destination.exists(),'Refusing to overwrite a SQLite recovery snapshot')
    with closing(sqlite3.connect(source.resolve().as_uri()+'?mode=ro',uri=True)) as src:
        with closing(sqlite3.connect(destination)) as dst:
            src.backup(dst)
            check(dst.execute('PRAGMA integrity_check').fetchone()[0]=='ok','Corrupt Chronicle recovery snapshot')


def interrupt(run, manifest, boot, server):
    """Post-replay sequential JVM/MySQL process kills; deliberately no normal checkpoint."""
    check(manifest.get('recovery_acceptance') and run.parent==ROOT/'run','Require owned restored run')
    target=M.config(run,manifest)
    check(target['container'] in (CONTAINER,TRADE_CONTAINER),'Never interrupt the source or a failed restore instance')
    instance=dict(target,id=target['container_id'],local_only=True)
    state=owned(instance);check(state['Running'],'Recovery MySQL is not running')
    check(server.poll() is None and (Path('/proc')/str(server.pid)/'cwd').resolve()==run,'Wrong server process')
    for proc in Path('/proc').iterdir():
        if not proc.name.isdigit() or int(proc.name)==server.pid:continue
        try:
            cmd=(proc/'cmdline').read_bytes().split(b'\0')
            cwd=(proc/'cwd').resolve()
            check(not (cmd and Path(cmd[0].decode(errors='replace')).name=='java' and cwd.parent==ROOT/'run'),'Another recovery JVM is running')
        except (FileNotFoundError,PermissionError,ProcessLookupError):pass
    prior=read(run/f'evidence/checkpoints/boot{boot-1}/checkpoint.json')
    check(M.sha(run/'plugins/StarCore/starcore.db')==prior['mysql']['projection_sha256'],'Audit projection was modified by game server')
    directory=run/'evidence/recovery'/f'interruption-boot{boot}';directory.mkdir()
    began=time.time()
    # Client process exit precedes server logout callbacks and their async SQL writes.
    # Require a quiescent native snapshot before recording the post-replay kill boundary.
    deadline=time.monotonic()+20;before=snapshot(target);stable=0;probes=0
    while stable<4:
        time.sleep(.25);current=snapshot(target);probes+=1
        try:equal_data(before,current);stable+=1
        except AssertionError:stable=0
        before=current
        check(time.monotonic()<deadline,'Native logout writes did not quiesce before interruption')
    write(directory/'before-game-kill-native.json',before)
    write(directory/'quiescence.json',dict(probes=probes,stable_samples=stable,interval_seconds=.25,started_at=began,finished_at=time.time()))
    sqlite_copy(run/'plugins/StarChronicle/chronicle.db',directory/'before-game-kill-chronicle.db')
    intent=dict(status='INTERRUPTION_IN_PROGRESS',boot=boot,server_pid=server.pid,container_id=instance['id'],started_at=began,
                boundary='Clients completed replay and exited normally; JVM then MySQL SIGKILL; no in-flight transaction or hardware power-loss claim')
    write(directory/'intent.json',intent)
    kill_game_at=time.time();server.kill();check(server.wait(timeout=15)==-9,'JVM was not killed by SIGKILL')
    port_free(manifest['server_port'])
    after_game=snapshot(target);write(directory/'after-game-kill-native.json',after_game);equal_data(before,after_game)
    sqlite_copy(run/'plugins/StarChronicle/chronicle.db',directory/'after-game-kill-chronicle.db')
    kill_database_at=time.time();docker('kill','--signal','KILL',instance['container']);killed=owned(instance)
    check(not killed['Running'] and killed['ExitCode']==137 and not killed['OOMKilled'],'Database did not stop by intentional SIGKILL')
    docker('start',instance['container']);deadline=time.monotonic()+60
    while True:
        try:
            with M.connect(target) as db:
                with db.cursor() as c:c.execute('SELECT 1');check(c.fetchone()==(1,),'Recovery SQL probe failed')
            break
        except Exception:
            if time.monotonic()>deadline:raise
            time.sleep(.25)
    after=snapshot(target);write(directory/'after-database-restart-native.json',after);equal_data(after_game,after)
    check(before['server_uuid']==after_game['server_uuid']==after['server_uuid']==target['server_uuid'],'Interrupted database identity changed')
    restarted=owned(instance);check(restarted['Running'] and restarted['StartedAt']!=state['StartedAt'] and not restarted['OOMKilled'],'Missing recovered database lifecycle')
    log=directory/'database-recovery.log';log.write_bytes(docker('logs','--since',str(began),instance['container'],merge_stderr=True))
    proof=dict(status='POST_REPLAY_PROCESS_INTERRUPTION_RECOVERED',boot=boot,projection_baseline_boot=boot-1,server_pid=server.pid,server_exit_code=-9,
        container=instance['container'],container_id=instance['id'],server_uuid=after['server_uuid'],started_at=began,game_killed_at=kill_game_at,
        database_killed_at=kill_database_at,finished_at=time.time(),database_before=state,database_killed=killed,database_after=restarted,
        table_count=len(after['tables']),view_count=len(after['views']),files={p.name:M.sha(p) for p in sorted(directory.iterdir())},boundary=intent['boundary'])
    write(directory/'interruption.json',proof)
    return proof
