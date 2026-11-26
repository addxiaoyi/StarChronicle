"""Compare frozen complete/selective history queries in serial 64 MiB JVMs; no server."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('previous', 'chronicle', 'java-home', 'output'):
        parser.add_argument('--'+name, type=Path, required=True)
    parser.add_argument('--events', type=int, default=60000)
    parser.add_argument('--rare-every', type=int, default=499, help='Use 1 to put every event in the requested type')
    parser.add_argument('--previous-projection', choices=('complete', 'requested'), default='complete')
    args = parser.parse_args()
    if args.events < 10000:
        parser.error('Use at least 10000 events')
    if args.rare_every < 1:
        parser.error('Use a positive rare interval')
    spec = importlib.util.spec_from_file_location('capacity', Path(__file__).with_name('run-capacity-contract.py'))
    capacity = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(capacity)
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    frozen, classes = output/'artifacts', output/'classes'
    frozen.mkdir(); classes.mkdir()
    for source, name in [(args.previous, 'previous.jar'), (args.chronicle, 'selected.jar'),
                         (Path(__file__).with_name('HistoryQueryProbe.java'), 'HistoryQueryProbe.java')]:
        shutil.copy2(source, frozen/name)
    manifest = {'status': 'RUNNING', 'scope': 'Serial isolated JVMs, SQLite and RuleEngine; no Minecraft or concurrent clients; 64 MiB heap plus native process memory',
                'events': args.events, 'rare_every': args.rare_every, 'previous_projection': args.previous_projection,
                'artifacts': {p.name: capacity.sha(p) for p in frozen.iterdir()}, 'phases': {}}
    suffix = '.exe' if os.name == 'nt' else ''

    def save():
        (output/'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')

    def run(label, program, arguments, launcher=()):
        argument_file = output/(label+'.args')
        argument_file.write_text('\n'.join(json.dumps(str(arg).replace('\\', '/')) for arg in arguments)+'\n', encoding='utf-8')
        start = time.monotonic()
        with (output/(label+'.log')).open('wb') as log:
            result = subprocess.run([str(args.java_home/'bin'/(program+suffix)), *launcher, '@'+str(argument_file)], stdout=log, stderr=subprocess.STDOUT, timeout=900)
        manifest['phases'][label] = {'exit_code': result.returncode, 'elapsed_seconds': round(time.monotonic()-start, 3), 'log_sha256': capacity.sha(output/(label+'.log'))}
        save()
        if result.returncode:
            raise RuntimeError(f'{label} failed; see retained log')
        print(label, 'PASS', flush=True)

    vm = ['-Xmx64m', '-Xss512k', '-XX:+UseSerialGC', '-XX:ActiveProcessorCount=1', '-XX:TieredStopAtLevel=1', '-XX:ReservedCodeCacheSize=32m', '-Dfile.encoding=UTF-8']
    try:
        run('compile', 'javac', ['-proc:none', '-encoding', 'UTF-8', '--release', '21', '-cp', frozen/'previous.jar', '-d', classes, frozen/'HistoryQueryProbe.java'], ['-J-Xmx96m', '-J-XX:+UseSerialGC'])
        original = output/'original.db'
        run('seed', 'java', [*vm, '-cp', str(classes)+os.pathsep+str(frozen/'previous.jar'), 'HistoryQueryProbe', 'seed', original, args.events, '-', args.rare_every])
        before = capacity.original_tables(original)
        (output/'original-tables.json').write_text(json.dumps(before, indent=2)+'\n', encoding='utf-8')
        for mode in ('previous', 'selected'):
            database = output/(mode+'.db')
            shutil.copy2(original, database)
            for phase in ('first', 'restart'):
                label = mode+'-'+phase
                reader_mode = 'selected' if mode == 'selected' or args.previous_projection == 'requested' else 'previous'
                run(label, 'java', [*vm, '-cp', str(classes)+os.pathsep+str(frozen/(mode+'.jar')), 'HistoryQueryProbe', reader_mode, database, args.events, output/(label+'.json'), args.rare_every])
                after = capacity.original_tables(database)
                for table, expected_table in before.items():
                    assert after[table] == expected_table, ('Original evidence changed', label, table)
                report = json.loads((output/(label+'.json')).read_text(encoding='utf-8'))
                with sqlite3.connect(database) as db:
                    assert db.execute('PRAGMA user_version').fetchone()[0] == report['schema_version']
                    assert db.execute('PRAGMA integrity_check').fetchone()[0] == 'ok'
                    assert not db.execute('PRAGMA foreign_key_check').fetchall()
                    expected = {'history.RARE': str(db.execute("SELECT COUNT(*) FROM events WHERE type='RARE'").fetchone()[0])}
                    expected['chronicle.history.world.RARE'] = expected['history.RARE']
                    for scope, table, field, subject in [('player','event_players','player','00000000-0000-0000-0000-000000000001'),('nation','event_nations','nation','north')]:
                        expected['chronicle.history.'+scope+'.RARE'] = str(db.execute(f"SELECT COUNT(*) FROM {table} s JOIN events e ON e.id=s.event_id WHERE s.{field}=? AND e.type='RARE'", (subject,)).fetchone()[0])
                    for scope in ('world','player','nation'):
                        expected['chronicle.history.'+scope+'.MISSING'] = '0'
                    assert report['counts'] == expected
                assert report['visible_ids'] == [f'history-story-{n:02}' for n in range(24) if n%6 != 4]
                assert report['unknown_nation_visible_ids'] == [f'history-story-{n:02}' for n in range(24) if n%6 < 2]
                manifest['phases'][label]['original_tables_preserved'] = len(before)
                manifest['phases'][label]['added_tables'] = sorted(set(after)-set(before))
                manifest['phases'][label]['independent_validation'] = 'PASS'
        manifest['status'] = 'PASS'
    except BaseException:
        manifest['status'] = 'FAILED'
        raise
    finally:
        save()


if __name__ == '__main__':
    main()
