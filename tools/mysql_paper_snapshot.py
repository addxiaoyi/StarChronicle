"""Capture real stopped MySQL data and derive a lossless SQLite view for existing auditors.

The SQLite file is an explicit audit projection, never a server database claim.
Requires PyMySQL==1.1.2 and a password supplied via the prepared environment name.
"""
from pathlib import Path
from datetime import datetime, date, time as daytime, timedelta
from decimal import Decimal
import base64
import hashlib
import json
import os
import re
import sqlite3
import ssl
import time
import yaml


def sha(path): return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def compact(value): return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'))


def encode(value):
    if isinstance(value, bytes): return {'binary': base64.b64encode(value).decode()}
    if isinstance(value, Decimal): return {'decimal': str(value)}
    if isinstance(value, (datetime, date, daytime)): return {'datetime': value.isoformat()}
    if isinstance(value, timedelta): return {'timedelta_seconds': str(value.total_seconds())}
    if value is None or isinstance(value, (str, int, float, bool)): return value
    raise TypeError(type(value).__name__)


def value(raw):
    if not isinstance(raw, dict): return raw
    assert len(raw) == 1
    key, text = next(iter(raw.items()))
    if key == 'binary': return base64.b64decode(text, validate=True)
    assert key in ('decimal', 'datetime', 'timedelta_seconds')
    return text


def config(run, manifest):
    target = manifest['mysql_acceptance']
    assert target['host'] == '127.0.0.1' and target['port'] != 3306
    assert re.fullmatch('starcore_paper_[a-z0-9_]+', target['database'])
    current = yaml.safe_load((run / 'plugins/StarCore/config.yml').read_text())['database']
    assert current['enabled'] and current['fail-fast'] and current['type'] == 'mysql'
    assert current['mysql'] == target['configuration'], 'Native MySQL configuration drift'
    assert not current['mysql']['password'] and current['mysql']['password-env'] == target['password_env']
    return target


def connect(target):
    import pymysql
    # Isolated loopback fixture with encrypted transport; no remote identity claim.
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE
    return pymysql.connect(host=target['host'], port=target['port'], user=target['user'], database=target['database'],
                           password=os.environ[target['password_env']], charset='utf8mb4', ssl=context,
                           connect_timeout=5, read_timeout=30, write_timeout=30, autocommit=False)


def capture(run, manifest, boot):
    target = config(run, manifest)
    directory = run / 'evidence/mysql'
    directory.mkdir(exist_ok=True)
    destination = directory / f'boot{boot}.json'
    assert not destination.exists()
    projected = run / 'plugins/StarCore/starcore.db'
    if projected.exists():
        previous = json.loads((run / f'evidence/checkpoints/boot{boot-1}/checkpoint.json').read_text())
        assert sha(projected) == previous['mysql']['projection_sha256'], 'Game server wrote to the audit-only SQLite projection'
    began = time.time()
    with connect(target) as db:
        with db.cursor() as c:
            c.execute('SELECT VERSION(), DATABASE(), CURRENT_USER(), @@server_uuid, @@default_storage_engine, @@transaction_isolation, @@sql_mode, @@log_bin')
            version, catalog, user, server, engine, isolation, mode, binlog = c.fetchone()
            assert version.startswith('8.4.') and catalog == target['database'] and engine == 'InnoDB' and isolation == 'REPEATABLE-READ'
            c.execute("SHOW SESSION STATUS LIKE 'Ssl_cipher'"); cipher = c.fetchone()[1]; assert cipher
            c.execute("SHOW GLOBAL STATUS LIKE 'Uptime'"); uptime = int(c.fetchone()[1])
            c.execute('SELECT TABLE_NAME,TABLE_TYPE,ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME')
            objects = c.fetchall()
            assert all(kind in ('BASE TABLE', 'VIEW') for _, kind, _ in objects)
            tables = [(name, engine) for name, kind, engine in objects if kind == 'BASE TABLE']
            assert tables and all(engine == 'InnoDB' and re.fullmatch('[a-zA-Z0-9_]+', table) for table, engine in tables)
            c.execute('SELECT TABLE_NAME,VIEW_DEFINITION,CHECK_OPTION,IS_UPDATABLE,SECURITY_TYPE,CHARACTER_SET_CLIENT,COLLATION_CONNECTION FROM information_schema.VIEWS WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME')
            views = [list(row) for row in c.fetchall()]
            assert [row[0] for row in views] == [name for name, kind, _ in objects if kind == 'VIEW']
            db.commit()
            c.execute('SET TRANSACTION ISOLATION LEVEL REPEATABLE READ')
            c.execute('START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY')
            exported = []
            for table, engine in tables:
                c.execute('SELECT COLUMN_NAME,DATA_TYPE,COLUMN_TYPE,IS_NULLABLE,COLLATION_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=%s ORDER BY ORDINAL_POSITION', (table,))
                columns = [list(row) for row in c.fetchall()]
                c.execute('SELECT * FROM `' + table + '`')
                rows = [[encode(v) for v in row] for row in c.fetchall()]
                rows.sort(key=compact)
                exported.append(dict(name=table, engine=engine, columns=columns, rows=rows))
            db.commit()
    snapshot = dict(format=1, storage='mysql', boot=boot, started_at=began, finished_at=time.time(),
                    target={key:target[key] for key in ('host','port','database','user','container','container_id','image')},
                    server=dict(version=version, database=catalog, user=user, server_uuid=server, engine=engine, isolation=isolation, sql_mode=mode, binlog=binlog, ssl_cipher=cipher, uptime=uptime),tables=exported,views=views)
    with destination.open('x') as f: f.write(json.dumps(snapshot,ensure_ascii=False,indent=2)+'\n')
    if projected.exists(): projected.unlink()  # Prior explicitly derived snapshot; MySQL is the actual backend.
    project(snapshot, projected)
    verify_projection(snapshot, projected)
    return dict(raw=destination.relative_to(run).as_posix(), raw_sha256=sha(destination), projection_sha256=sha(projected), table_count=len(exported), view_count=len(views))


def project(snapshot, destination):
    assert not destination.exists()
    with sqlite3.connect(destination) as db:
        for table in snapshot['tables']:
            columns = table['columns']
            def affinity(c):
                if c[1] in ('tinyint','smallint','mediumint','int','bigint'):return 'INTEGER'
                if c[1] in ('float','double'):return 'REAL'
                if c[1] in ('binary','varbinary','blob','mediumblob','longblob','tinyblob','bit'):return 'BLOB'
                return 'TEXT'
            for c in columns: assert re.fullmatch('[a-zA-Z0-9_]+',c[0])
            db.execute('CREATE TABLE "'+table['name']+'" ('+','.join('"'+c[0]+'" '+affinity(c) for c in columns)+')')
            if table['rows']:
                db.executemany('INSERT INTO "'+table['name']+'" VALUES('+','.join('?' for _ in columns)+')', [[value(v) for v in row] for row in table['rows']])
        db.commit()


def verify_projection(snapshot, path):
    with sqlite3.connect(path.resolve().as_uri()+'?mode=ro&immutable=1',uri=True) as db:
        assert db.execute('pragma integrity_check').fetchone()[0]=='ok'
        actual={r[0] for r in db.execute("select name from sqlite_master where type='table'")}
        assert actual=={t['name'] for t in snapshot['tables']}
        for table in snapshot['tables']:
            columns=[r[1] for r in db.execute('pragma table_info("'+table['name']+'")')]
            assert columns==[c[0] for c in table['columns']]
            actual=[list(row) for row in db.execute('SELECT * FROM "'+table['name']+'"')]
            wanted=[[value(v) for v in row] for row in table['rows']]
            assert actual==wanted, 'Projection differs from raw MySQL rows: '+table['name']
