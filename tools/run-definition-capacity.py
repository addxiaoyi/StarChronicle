"""Compare complete/selected definition retention with frozen JARs in serial 64 MiB JVMs."""
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
    parser.add_argument('--versions', type=int, default=6000)
    args = parser.parse_args()
    if args.versions < 1000 or args.versions % 5:
        parser.error('Use at least 1000 versions in a multiple of five (last reference is V2)')
    spec = importlib.util.spec_from_file_location('capacity', Path(__file__).with_name('run-capacity-contract.py'))
    capacity = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(capacity)
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    frozen, classes = output/'artifacts', output/'classes'
    frozen.mkdir(); classes.mkdir()
    for source, name in [(args.previous, 'previous.jar'), (args.chronicle, 'selected.jar'),
                         (Path(__file__).with_name('DefinitionCapacityProbe.java'), 'DefinitionCapacityProbe.java')]:
        shutil.copy2(source, frozen/name)
    manifest = {'status': 'RUNNING', 'scope': 'Serial isolated JVMs, SQLite and RuleEngine; no Minecraft; 64 MiB heap plus native process memory',
                'historical_versions': args.versions, 'nodes_per_version': 4, 'text_chars_per_node': 4096,
                'artifacts': {p.name: capacity.sha(p) for p in frozen.iterdir()}, 'phases': {}}
    suffix = '.exe' if os.name == 'nt' else ''

    def save():
        (output/'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')

    def run(label, program, arguments, launcher=(), allow_heap_oom=False):
        argument_file = output/(label+'.args')
        argument_file.write_text('\n'.join(json.dumps(str(arg).replace('\\', '/')) for arg in arguments)+'\n', encoding='utf-8')
        start = time.monotonic()
        with (output/(label+'.log')).open('wb') as log:
            result = subprocess.run([str(args.java_home/'bin'/(program+suffix)), *launcher, '@'+str(argument_file)], stdout=log, stderr=subprocess.STDOUT, timeout=900)
        phase = {'exit_code': result.returncode, 'elapsed_seconds': round(time.monotonic()-start, 3), 'log_sha256': capacity.sha(output/(label+'.log'))}
        manifest['phases'][label] = phase
        content = (output/(label+'.log')).read_text(encoding='utf-8', errors='replace')
        if result.returncode and allow_heap_oom and 'java.lang.OutOfMemoryError: Java heap space' in content:
            phase['outcome'] = 'JAVA_HEAP_OOM'
        elif result.returncode:
            phase['outcome'] = 'FAILED';save()
            raise RuntimeError(f'{label} failed; see retained log')
        else:
            phase['outcome'] = 'PASS'
        save();print(label, phase['outcome'], flush=True)
        return phase

    def validate_database(database, before):
        after = capacity.original_tables(database)
        assert after == before, ('Original evidence changed', database.name)
        with sqlite3.connect(database) as db:
            assert db.execute('PRAGMA user_version').fetchone()[0] == 8
            assert db.execute('PRAGMA integrity_check').fetchone()[0] == 'ok'
            assert not db.execute('PRAGMA foreign_key_check').fetchall()
        return after

    vm = ['-Xmx64m', '-Xss512k', '-XX:+UseSerialGC', '-XX:ActiveProcessorCount=1', '-XX:TieredStopAtLevel=1', '-XX:ReservedCodeCacheSize=32m', '-Dfile.encoding=UTF-8']
    try:
        run('compile', 'javac', ['-proc:none', '-encoding', 'UTF-8', '--release', '21', '-cp', frozen/'previous.jar', '-d', classes, frozen/'DefinitionCapacityProbe.java'], ['-J-Xmx96m', '-J-XX:+UseSerialGC'])
        # Small old-JAR control proves that setup and the common probe can succeed.
        small = output/'small-control.db'
        run('seed-control', 'java', [*vm, '-cp', str(classes)+os.pathsep+str(frozen/'previous.jar'), 'DefinitionCapacityProbe', 'seed', small, 10])
        small_before = capacity.original_tables(small)
        run('previous-control', 'java', [*vm, '-cp', str(classes)+os.pathsep+str(frozen/'previous.jar'), 'DefinitionCapacityProbe', 'previous', small, 10, output/'previous-control.json'])
        validate_database(small, small_before)
        original = output/'original.db'
        run('seed', 'java', [*vm, '-cp', str(classes)+os.pathsep+str(frozen/'previous.jar'), 'DefinitionCapacityProbe', 'seed', original, args.versions])
        before = capacity.original_tables(original)
        assert before['story_definitions']['rows'] == args.versions+1
        assert before['story_definition_formats']['rows'] == args.versions+1
        assert before['story_definition_upgrades']['rows'] == args.versions//5
        (output/'original-tables.json').write_text(json.dumps(before, indent=2)+'\n', encoding='utf-8')
        for mode in ('previous', 'selected'):
            database = output/(mode+'.db')
            shutil.copy2(original, database)
            for phase in (('first',) if mode == 'previous' else ('first', 'restart')):
                label = mode+'-'+phase
                result = run(label, 'java', [*vm, '-cp', str(classes)+os.pathsep+str(frozen/(mode+'.jar')), 'DefinitionCapacityProbe', mode, database, args.versions, output/(label+'.json')], allow_heap_oom=mode == 'previous')
                after = validate_database(database, before)
                if result['outcome'] == 'PASS':
                    report = json.loads((output/(label+'.json')).read_text(encoding='utf-8'))
                    assert report['visible_story_ids'] == ['enabled']
                    assert report['max_heap_bytes'] <= 64*1024*1024
                    if mode == 'selected':
                        assert set(report['selected_keys']) == {'enabled/1', 'retired/v000000', f'retired/v{args.versions-1:06d}'}
                        assert report['selected_evidence_rows'] == 3
                result['original_tables_preserved'] = len(before)
                result['independent_validation'] = 'PASS'
                (output/(label+'-tables.json')).write_text(json.dumps(after, indent=2)+'\n', encoding='utf-8')
        manifest['status'] = 'PASS'
    except BaseException:
        manifest['status'] = 'FAILED'
        raise
    finally:
        save()


if __name__ == '__main__':
    main()
