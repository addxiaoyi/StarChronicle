"""Compare frozen offset/seek history queries in serial 64 MiB JVMs; no Minecraft."""
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
    parser.add_argument('--events', type=int, default=120000)
    args = parser.parse_args()
    if args.events < 10000 or args.events % 6:
        parser.error('Use at least 10000 events in a multiple of six')
    spec = importlib.util.spec_from_file_location('capacity', Path(__file__).with_name('run-capacity-contract.py'))
    capacity = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(capacity)
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    frozen, classes = output/'artifacts', output/'classes'
    frozen.mkdir(); classes.mkdir()
    for source, name in [(args.previous, 'previous.jar'), (args.chronicle, 'selected.jar'),
                         (Path(__file__).with_name('HistoryPaginationProbe.java'), 'HistoryPaginationProbe.java')]:
        shutil.copy2(source, frozen/name)
    manifest = {'status': 'RUNNING', 'scope': 'Serial isolated JVMs, SQLite and RuleEngine; no Minecraft; 64 MiB heap plus native process memory',
                'events': args.events, 'artifacts': {p.name: capacity.sha(p) for p in frozen.iterdir()}, 'phases': {}}
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

    def independent(database, before, report):
        after = capacity.original_tables(database)
        assert after == before, ('Original evidence changed', database.name)
        scopes = {'world': ('', ()),
                  'nation': (' AND EXISTS(SELECT 1 FROM event_nations n WHERE n.event_id=e.id AND n.nation=?)', ('north',)),
                  'player': (' AND EXISTS(SELECT 1 FROM event_players p WHERE p.event_id=e.id AND p.player=?)', ('00000000-0000-0000-0000-000000000001',))}
        scopes['intersection'] = (scopes['nation'][0]+scopes['player'][0], scopes['nation'][1]+scopes['player'][1])
        with sqlite3.connect(database) as db:
            assert db.execute('PRAGMA user_version').fetchone()[0] == 8
            assert db.execute('PRAGMA integrity_check').fetchone()[0] == 'ok'
            assert not db.execute('PRAGMA foreign_key_check').fetchall()
            for scope, (condition, parameters) in scopes.items():
                result = report['scopes'][scope]
                count = db.execute('SELECT COUNT(*) FROM events e WHERE 1=1'+condition, parameters).fetchone()[0]
                page = (count*3//4)//45
                assert result['matching_events'] == count and result['page_number_zero_based'] == page
                base = 'SELECT e.id FROM events e WHERE 1=1'+condition+' ORDER BY e.time DESC,e.id DESC LIMIT ? OFFSET ?'
                assert result['before_event_id'] == db.execute(base, (*parameters, 1, page*45-1)).fetchone()[0]
                for field, offset in [('events', page*45), ('following_events', (page+1)*45)]:
                    expected = [row[0] for row in db.execute(base, (*parameters, 45, offset))]
                    assert result[field] == expected, (scope, field)
                assert not set(result['events']) & set(result['following_events'])
        return after

    vm = ['-Xmx64m', '-Xss512k', '-XX:+UseSerialGC', '-XX:ActiveProcessorCount=1', '-XX:TieredStopAtLevel=1', '-XX:ReservedCodeCacheSize=32m', '-Dfile.encoding=UTF-8']
    try:
        run('compile', 'javac', ['-proc:none', '-encoding', 'UTF-8', '--release', '21', '-cp', frozen/'previous.jar', '-d', classes, frozen/'HistoryPaginationProbe.java'], ['-J-Xmx96m', '-J-XX:+UseSerialGC'])
        original = output/'original.db'
        run('seed', 'java', [*vm, '-cp', str(classes)+os.pathsep+str(frozen/'previous.jar'), 'HistoryPaginationProbe', 'seed', original, args.events])
        before = capacity.original_tables(original)
        assert before['events']['rows'] == args.events
        (output/'original-tables.json').write_text(json.dumps(before, indent=2)+'\n', encoding='utf-8')
        for mode in ('previous', 'selected'):
            database = output/(mode+'.db')
            shutil.copy2(original, database)
            for phase in ('first', 'restart'):
                label = mode+'-'+phase
                run(label, 'java', [*vm, '-cp', str(classes)+os.pathsep+str(frozen/(mode+'.jar')), 'HistoryPaginationProbe', mode, database, args.events, output/(label+'.json')])
                report = json.loads((output/(label+'.json')).read_text(encoding='utf-8'))
                assert report['max_heap_bytes'] <= 64*1024*1024
                after = independent(database, before, report)
                manifest['phases'][label]['original_tables_preserved'] = len(before)
                manifest['phases'][label]['independent_validation'] = 'PASS'
                (output/(label+'-tables.json')).write_text(json.dumps(after, indent=2)+'\n', encoding='utf-8')
        manifest['status'] = 'PASS'
    except BaseException:
        manifest['status'] = 'FAILED'
        raise
    finally:
        save()


if __name__ == '__main__':
    main()
