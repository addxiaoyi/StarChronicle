"""Audit raw MySQL checkpoints, their read-only projections and paired normal restarts.

Business-specific inspectors then verify the exact player commands, asset conservation,
receipts and Chronicle projections using the independently compared SQLite audit files.
Those files are derived evidence, not the native plugin's storage backend.
"""
from pathlib import Path
from contextlib import closing
from decimal import Decimal
import argparse
import base64
import importlib.util
import json
import re
import socket
import sqlite3

import mysql_paper_snapshot as M


def check(condition, message):
    if not condition:
        raise AssertionError(message)


def read(path):
    return json.loads(path.read_text())


def load(name):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(name))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def decoded(cell):
    if isinstance(cell, dict):
        check(len(cell) == 1, 'Malformed typed MySQL cell')
        tag, value = next(iter(cell.items()))
        check(isinstance(value, str), 'Malformed typed MySQL value')
        if tag == 'binary':
            return base64.b64decode(value, validate=True)
        if tag in ('decimal', 'timedelta_seconds'):
            check(Decimal(value).is_finite(), 'Nonfinite MySQL value')
        else:
            check(tag == 'datetime', 'Unknown MySQL type tag')
        return value
    check(cell is None or isinstance(cell, (str, int, float, bool)), 'Invalid MySQL cell')
    return cell


def compare_projection(raw, path):
    """Compare every schema column and value without executing SQL from the export."""
    with closing(sqlite3.connect(path.resolve().as_uri() + '?mode=ro&immutable=1', uri=True)) as db:
        check(db.execute('PRAGMA integrity_check').fetchone()[0] == 'ok', 'Corrupt audit projection')
        names = [row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")]
        check(names == [table['name'] for table in raw['tables']], 'MySQL projection table inventory differs')
        for table in raw['tables']:
            name = table['name']
            check(re.fullmatch(r'[a-zA-Z0-9_]+', name), 'Invalid MySQL table identifier')
            columns = [row[1] for row in db.execute('PRAGMA table_info("' + name + '")')]
            check(columns == [column[0] for column in table['columns']], 'MySQL projection columns differ')
            check(all(len(row) == len(columns) for row in table['rows']), 'MySQL row width differs')
            expected = [[decoded(cell) for cell in row] for row in table['rows']]
            actual = [list(row) for row in db.execute('SELECT * FROM "' + name + '"')]
            check(actual == expected, 'MySQL raw rows differ from projection: ' + name)


def inspect_storage(run):
    run = run.resolve()
    manifest = read(run / 'preparation.json')
    target = M.config(run, manifest)
    check(target['initial_catalog_empty'] is True, 'MySQL catalog was not initially empty')
    check(target['snapshot_format'] == 'mysql raw rows plus lossless SQLite audit projection; not a native SQLite database', 'MySQL projection provenance missing')
    config = read(run / 'evidence/mysql-config-preparation.json')
    check(config['empty_catalog'] is True and config['database_configuration'] == target['configuration'] and config['server_uuid'] == target['server_uuid'] and config['version'] == target['version'], 'MySQL preparation differs')
    with socket.socket() as probe:
        probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        probe.bind(('127.0.0.1', manifest['server_port']))
    scenarios = manifest.get('nation_production_acceptance', manifest.get('nation_trade_acceptance', {})).get('scenarios', ['first', 'second', 'third'])
    boots = len(scenarios)
    evidence = run / 'evidence'
    check({p.name for p in evidence.glob('boot*.session.json')} == {f'boot{b}.session.json' for b in range(1, boots + 1)}, 'MySQL boot inventory differs')
    check({p.name for p in (evidence / 'mysql').glob('*.json')} == {f'boot{b}.json' for b in range(1, boots + 1)} | {f'restart-after-boot{b}.json' for b in range(1, boots)}, 'MySQL snapshot/restart inventory differs')
    snapshots = []
    prior_end = None
    for boot, scenario in enumerate(scenarios, 1):
        session = read(evidence / f'boot{boot}.session.json')
        check(session['boot'] == boot and session['scenario'] == scenario and session['status'] == 'COMPLETE', 'Incomplete MySQL game session')
        processes = session['processes']
        check(len(processes) == 2 and {p['name'] for p in processes} == {'server', 'clients'} and all(p.get('exit_code') == 0 for p in processes), 'Abnormal MySQL game process exit')
        server = next(p for p in processes if p['name'] == 'server')
        if prior_end is not None:
            check(prior_end < server['started_at'], 'Game started before database restart verification')
        for key, suffix in [('controller', 'controller.py'), ('clients', 'clients.cjs'), ('mysql_snapshot', 'mysql-snapshot.py')]:
            check(M.sha(evidence / f'boot{boot}.{suffix}') == session['source_sha256'][key], 'Frozen MySQL capture script changed')
        log = (evidence / f'boot{boot}.server.log').read_text()
        expected = f"STARCORE database ready: MySQL 运行中 ({target['host']}:{target['port']}/{target['database']})"
        check(log.count(expected) == 1 and 'STARCORE-MySQL - Start completed.' in log and 'STARCORE-MySQL - Shutdown completed.' in log and 'STARCORE-SQLite' not in log, 'Native runtime MySQL binding missing or fell back')
        checkpoint = read(evidence / f'checkpoints/boot{boot}/checkpoint.json')
        proof = checkpoint['mysql']
        raw_path = evidence / f'mysql/boot{boot}.json'
        check(proof['raw'] == raw_path.relative_to(run).as_posix() and proof['raw_sha256'] == M.sha(raw_path), 'Raw MySQL snapshot hash/path differs')
        raw = read(raw_path)
        check(raw['format'] == 1 and raw['storage'] == 'mysql' and raw['boot'] == boot, 'MySQL provenance differs')
        check(raw['target'] == {key: target[key] for key in ('host', 'port', 'database', 'user', 'container', 'container_id', 'image')}, 'MySQL snapshot target differs')
        metadata = raw['server']
        check(metadata['version'] == target['version'] and metadata['version'].startswith('8.4.') and metadata['database'] == target['database'] and metadata['server_uuid'] == target['server_uuid'] and metadata['user'].startswith(target['user'] + '@'), 'MySQL authority changed')
        check(metadata['engine'] == 'InnoDB' and metadata['isolation'] == 'REPEATABLE-READ' and metadata['ssl_cipher'] and metadata['uptime'] >= 0, 'MySQL engine/isolation/encryption differs')
        check(server['exited_at'] <= raw['started_at'] <= raw['finished_at'] <= checkpoint['time'], 'MySQL snapshot was not taken after game shutdown')
        names = [table['name'] for table in raw['tables']]
        check(names == sorted(set(names)) and names and proof['table_count'] == len(names), 'MySQL table inventory invalid')
        check(all(table['engine'] == 'InnoDB' for table in raw['tables']), 'Nontransactional MySQL table')
        for table in raw['tables']:
            columns = table['columns']
            check(columns and all(len(column) == 5 and re.fullmatch(r'[a-zA-Z0-9_]+', column[0]) and column[1] and column[2] and column[3] in ('YES', 'NO') for column in columns), 'MySQL column metadata invalid')
            check(len({column[0] for column in columns}) == len(columns), 'Duplicate MySQL column')
            check(table['rows'] == sorted(table['rows'], key=M.compact), 'MySQL rows not canonically ordered')
        views = raw['views']
        check(proof['view_count'] == len(views) and all(len(row) == 7 and row[0] not in names and row[1] for row in views), 'MySQL view provenance invalid')
        check([v[0] for v in views] == sorted({v[0] for v in views}), 'MySQL view inventory invalid')
        projection = evidence / f'checkpoints/boot{boot}/starcore.db'
        check(proof['projection_sha256'] == checkpoint['sha256']['starcore.db'] == M.sha(projection), 'MySQL audit projection hash differs')
        compare_projection(raw, projection)
        if boot == boots:
            check(M.sha(run / 'plugins/StarCore/starcore.db') == M.sha(projection), 'Final MySQL audit projection differs')
        else:
            restart = read(evidence / f'mysql/restart-after-boot{boot}.json')
            check(restart['status'] == 'NORMAL_DATABASE_RESTART_PRESERVED_ALL_TABLES' and restart['after_boot'] == boot and restart['container_id'] == target['container_id'] and restart['server_uuid'] == target['server_uuid'], 'Database restart identity differs')
            check(restart['script_sha256'] == M.sha(evidence / f'mysql/restart-after-boot{boot}.py'), 'Database restart script changed')
            check(restart['previous_snapshot_sha256'] == proof['raw_sha256'], 'Database restart references another snapshot')
            check(checkpoint['time'] <= restart['started_at'] < restart['finished_at'], 'Database restart preceded stopped checkpoint')
            before, stopped, after = (restart[k] for k in ('before', 'stopped', 'after'))
            check(before['Running'] and after['Running'] and not stopped['Running'] and stopped['ExitCode'] == 0 and all(not s['OOMKilled'] for s in (before, stopped, after)) and before['StartedAt'] != after['StartedAt'], 'Abnormal or missing database restart')
            check(restart['after_tables'] == raw['tables'] and restart['after_views'] == views, 'Database restart changed committed data or views')
            prior_end = restart['finished_at']
        snapshots.append(dict(boot=boot, tables=len(names), views=len(views), rows=sum(len(t['rows']) for t in raw['tables']), raw_sha256=proof['raw_sha256'], projection_sha256=proof['projection_sha256']))
    return dict(native_storage='MySQL ' + target['version'] + ' / InnoDB', chronicle_storage='SQLite', audit_projection='Read-only inspection of losslessly compared MySQL rows; not native SQLite storage', database=target['database'], server_uuid=target['server_uuid'], normal_database_restarts=boots - 1, snapshots=snapshots)


def inspect(run):
    storage = inspect_storage(run)
    manifest = read(run / 'preparation.json')
    inspector = ('inspect-nation-production-paper.py' if 'nation_production_acceptance' in manifest else
                 'inspect-nation-trade-paper.py' if 'nation_trade_acceptance' in manifest else
                 'inspect-battlefield-paper.py' if 'battlefield_acceptance' in manifest else 'inspect-epic-production-paper.py')
    business = load(inspector).inspect(run)
    return dict(status='MYSQL_PAPER_SELECTED_STACK_ACCEPTED', storage=storage, business=business)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('run', type=Path)
    print(json.dumps(inspect(parser.parse_args().run), ensure_ascii=False, indent=2))
