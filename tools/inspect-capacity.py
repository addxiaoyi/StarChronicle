"""Read Chronicle capacity without modifying it; optionally create a consistent SQLite backup."""
import argparse
import json
from pathlib import Path
import sqlite3
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('database', type=Path)
    parser.add_argument('--backup', type=Path, help='New destination only; includes committed WAL contents')
    parser.add_argument('--timeout', type=int, default=120, help='Maximum seconds for each read or backup')
    args = parser.parse_args()
    source = args.database.resolve(strict=True)
    if not source.is_file() or args.timeout < 1:
        parser.error('An existing database file and a positive timeout are required')
    deadline = time.monotonic()+args.timeout
    with sqlite3.connect(source.as_uri()+'?mode=ro', uri=True, timeout=5) as database:
        database.execute('PRAGMA query_only=ON')
        database.execute('BEGIN')
        database.set_progress_handler(lambda: int(time.monotonic()>deadline), 10000)
        report = {'database': str(source), 'schema_version': database.execute('PRAGMA user_version').fetchone()[0]}
        if not 1 <= report['schema_version'] <= 8:
            raise SystemExit('Unsupported Chronicle schema')
        page_size = database.execute('PRAGMA page_size').fetchone()[0]
        report['allocated_bytes'] = page_size*database.execute('PRAGMA page_count').fetchone()[0]
        report['reusable_bytes'] = page_size*database.execute('PRAGMA freelist_count').fetchone()[0]
        tables = {row[0] for row in database.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        selected = ['events', 'instances', 'instance_members', 'outcomes', 'story_triggers', 'story_trigger_receipts', 'story_choice_log', 'story_definitions', 'contributions', 'committed_source_receipts']
        report['rows'] = {name: database.execute('SELECT COUNT(*) FROM '+name).fetchone()[0] for name in selected if name in tables}
        if 'outcomes' in tables:
            report['outcome_status'] = dict(database.execute('SELECT status,COUNT(*) FROM outcomes GROUP BY status'))
        if 'story_triggers' in tables:
            report['trigger_audiences'] = dict(database.execute("SELECT CASE WHEN scope IN ('players:*','nations:*','world') THEN scope WHEN scope LIKE 'nation-players:%' THEN 'nation-players:<id>' ELSE substr(scope,1,instr(scope,':'))||'<id>' END,COUNT(*) FROM story_triggers GROUP BY 1"))
        if args.backup:
            destination = args.backup.resolve()
            # Exclusive creation also rejects aliases of the source and existing backup files.
            with destination.open('xb'):
                pass
            deadline = time.monotonic()+args.timeout
            try:
                with sqlite3.connect(destination) as backup:
                    def progress(status, remaining, total):
                        if time.monotonic()>deadline:
                            raise TimeoutError('Backup timed out; source was not modified')
                    database.backup(backup, pages=256, progress=progress, sleep=0.05)
                    backup.set_progress_handler(lambda: int(time.monotonic()>deadline), 10000)
                    check = backup.execute('PRAGMA integrity_check').fetchone()[0]
                    if check != 'ok':
                        raise RuntimeError('Backup integrity check failed: '+check)
                report['backup'] = {'path': str(destination), 'integrity_check': 'ok', 'bytes': destination.stat().st_size}
            except BaseException:
                print(json.dumps({'backup_status': 'FAILED', 'path': str(destination)}, ensure_ascii=False))
                raise
        print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
