"""Verify timed factory recovery and Chronicle ingestion in three small, sequential JVMs."""
import argparse
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ['starcore', 'chronicle', 'runtime', 'java-home', 'output']:
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--heap-mb', type=int, default=96)
    args = parser.parse_args()
    if args.heap_mb < 32:
        parser.error('Use at least 32 MiB heap')
    source = Path(__file__).with_name('FactoryContractProbe.java').resolve()
    libraries = sorted((args.runtime / 'libraries').rglob('*.jar'))
    if not libraries:
        raise SystemExit('Runtime libraries missing')
    for artifact in [args.starcore, args.chronicle, source]:
        if not artifact.is_file():
            raise SystemExit(f'Missing artifact: {artifact}')
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    frozen = output / 'artifacts'
    frozen.mkdir()
    for artifact, name in [(args.starcore, 'starcore.jar'), (args.chronicle, 'starchronicle.jar'), (source, source.name)]:
        shutil.copy2(artifact, frozen / name)
    manifest = {'status': 'RUNNING', 'heap_mb': args.heap_mb, 'artifacts': {p.name: sha(p) for p in frozen.iterdir()},
                'scope': 'Three sequential JVMs; packaged timed factory and Chronicle services; real SQLite; controlled clock and detached NationService fixture; no Minecraft or live player certification',
                'phases': {}}
    cp = os.pathsep.join(p.resolve().as_posix() for p in [frozen/'starchronicle.jar', frozen/'starcore.jar', *libraries])
    classes = output / 'classes'
    classes.mkdir()
    suffix = '.exe' if os.name == 'nt' else ''

    def run(label, program, arguments, launcher=()):
        argument_file = output / (label+'.args')
        argument_file.write_text('\n'.join(json.dumps(str(arg).replace('\\', '/')) for arg in arguments)+'\n', encoding='utf-8')
        with (output / (label+'.log')).open('wb') as log:
            result = subprocess.run([str(args.java_home/'bin'/(program+suffix)), *launcher, '@'+str(argument_file)],
                                    stdout=log, stderr=subprocess.STDOUT, timeout=120)
        manifest['phases'][label] = {'exit_code': result.returncode, 'log_sha256': sha(output/(label+'.log'))}
        (output/'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
        if result.returncode:
            raise RuntimeError(f'{label} failed; see {output/(label+".log")}')

    try:
        run('compile', 'javac', ['-encoding', 'UTF-8', '-cp', cp, '-d', classes, frozen/source.name],
            ['-J-Xmx96m', '-J-XX:+UseSerialGC', '-J-XX:ActiveProcessorCount=1'])
        for phase in ['first', 'second', 'third']:
            run(phase, 'java', [f'-Xmx{args.heap_mb}m', '-Xss512k', '-XX:+UseSerialGC', '-XX:ActiveProcessorCount=1',
                               '-XX:TieredStopAtLevel=1', '-XX:ReservedCodeCacheSize=32m',
                               '-cp', str(classes)+os.pathsep+cp,
                               'io.github.addxiaoyi.starchronicle.integration.FactoryContractProbe', output, phase])
        with sqlite3.connect(f'file:{output.as_posix()}/native.db?mode=ro', uri=True) as native, \
                sqlite3.connect(f'file:{output.as_posix()}/chronicle.db?mode=ro', uri=True) as chronicle:
            assert native.execute('PRAGMA integrity_check').fetchone() == ('ok',)
            assert chronicle.execute('PRAGMA integrity_check').fetchone() == ('ok',)
            state = dict(native.execute('SELECT property_key,property_value FROM starcore_resource_state'))
            version = state['resource.factory-runs.version']
            assert version in ('1', '2', '3')
            assert state['resource.factory-runs.count'] == ('0' if version == '3' else '2')
            if version == '3':
                assert state['resource.factory-runs.archive-count'] == '2'
                assert native.execute('SELECT COUNT(*) FROM starcore_resource_factory_archive').fetchone() == (2,)
            assert state['resource.production.sequence'] == '2'
            assert 'factory.0.recipe' not in state
            domain = state['resource.authority-domain']
            assert chronicle.execute('SELECT domain,sequence FROM committed_source_cursors').fetchone() == (domain, 2)
            rows = native.execute('SELECT sequence,operation_id,nation_id,actor_id,recipe_id,batches,inputs,outputs,committed_at FROM starcore_resource_production_receipts ORDER BY sequence').fetchall()
            assert len(rows) == 2
            for index, (seq, operation, nation, actor, recipe, batches, inputs, outputs, committed_at) in enumerate(rows):
                run_id = operation.removeprefix('factory:')
                row = str(index) if version == '1' else hashlib.sha256(run_id.encode('utf-8')).hexdigest()
                key = f'resource.factory-runs.row.{row}.'
                if version == '3':
                    archived = native.execute('SELECT run_id,inputs,outputs,completion_sequence,completed_at,ready_at FROM starcore_resource_factory_archive WHERE run_key=?', (row,)).fetchone()
                    assert archived[:5] == (run_id, inputs, outputs, seq, committed_at)
                    assert archived[5] <= committed_at
                else:
                    assert operation == 'factory:' + state[key+'id']
                    assert state[key+'inputs'] == inputs and state[key+'outputs'] == outputs
                    assert state[key+'sequence'] == str(seq)
                    assert state[key+'completed'] == str(committed_at)
                    assert int(state[key+'ready']) <= committed_at
                raw = chronicle.execute('SELECT json FROM committed_source_receipts WHERE sequence=?', (seq,)).fetchone()[0]
                entry = json.loads(raw)
                assert entry['operationId'] == operation
                event = entry['event']
                assert event['nations'] == [nation]
                assert event['participants'] == ([] if actor is None else [actor])
                assert event['facts']['production.recipe'] == recipe
                assert event['facts']['production.batches'] == str(batches)
                assert round(datetime.fromisoformat(event['occurredAt'].replace('Z', '+00:00')).timestamp()*1000) == committed_at
                for side, quantities in [('input', inputs), ('output', outputs)]:
                    for pair in quantities.split(';'):
                        resource, value = pair.split('=')
                        assert event['facts']['production.'+side+'.'+resource] == value
                assert len(entry['contributions']) == (0 if actor is None else 2)
        manifest['status'] = 'PASS'
    except BaseException:
        manifest['status'] = 'FAILED'
        raise
    finally:
        manifest['files'] = {p.relative_to(output).as_posix(): sha(p) for p in output.rglob('*') if p.is_file() and p.name != 'manifest.json'}
        (output/'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps({'status': manifest['status'], 'evidence': str(output), 'artifacts': manifest['artifacts']}, ensure_ascii=False))


if __name__ == '__main__':
    main()
