"""Verify national processing and Chronicle replay in three JVMs without Minecraft."""
import argparse
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
    args = parser.parse_args()
    source = Path(__file__).with_name('ProductionContractProbe.java').resolve()
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
    manifest = {'status': 'RUNNING', 'artifacts': {p.name: sha(p) for p in frozen.iterdir()},
                'scope': 'Three JVMs; real packaged national resource/processing services and SQLite databases; fixed detached NationService fixture; no Minecraft, live player or timed factory certification',
                'phases': {}}
    cp = os.pathsep.join(p.resolve().as_posix() for p in [frozen/'starchronicle.jar', frozen/'starcore.jar', *libraries])
    classes = output / 'classes'
    classes.mkdir()
    suffix = '.exe' if os.name == 'nt' else ''

    def run(label, program, arguments, launcher=()):
        argument_file = output / (label+'.args')
        argument_file.write_text('\n'.join(json.dumps(str(arg).replace('\\', '/')) for arg in arguments)+'\n', encoding='utf-8')
        with (output / (label+'.log')).open('wb') as log:
            result = subprocess.run([str(args.java_home/'bin'/(program+suffix)), *launcher, '@'+str(argument_file)], stdout=log, stderr=subprocess.STDOUT, timeout=120)
        manifest['phases'][label] = {'exit_code': result.returncode, 'log_sha256': sha(output/(label+'.log'))}
        (output/'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
        if result.returncode:
            raise RuntimeError(f'{label} failed; see {output / (label+".log")}')

    try:
        run('compile', 'javac', ['-proc:none', '-encoding', 'UTF-8', '--release', '21', '-cp', cp, '-d', str(classes), str(frozen/source.name)], ['-J-Xmx128m', '-J-XX:+UseSerialGC'])
        for phase in ['first', 'second', 'third']:
            run(phase, 'java', ['-Xmx128m', '-Xss512k', '-XX:+UseSerialGC', '-XX:ActiveProcessorCount=1', '-XX:TieredStopAtLevel=1', '-XX:ReservedCodeCacheSize=32m', '-Dfile.encoding=UTF-8', '-cp', str(classes)+os.pathsep+cp, 'io.github.addxiaoyi.starchronicle.integration.ProductionContractProbe', str(output), phase])
        with sqlite3.connect(output/'native.db') as native, sqlite3.connect(output/'chronicle.db') as chronicle:
            assert native.execute('SELECT COUNT(*) FROM starcore_resource_production_receipts').fetchone()[0] == 3
            assert chronicle.execute('SELECT COUNT(*) FROM committed_source_receipts').fetchone()[0] == 3
            assert chronicle.execute('SELECT COUNT(*) FROM events').fetchone()[0] == 3
            assert chronicle.execute('SELECT COUNT(*) FROM contributions').fetchone()[0] == 4
            state = dict(native.execute('SELECT property_key,property_value FROM starcore_resource_state'))
            domain = state['resource.authority-domain']
            assert state['resource.production.sequence'] == '3'
            assert chronicle.execute('SELECT domain,sequence FROM committed_source_cursors').fetchone() == (domain, 3)
            for seq, operation, nation, actor, recipe, batches, inputs, outputs, committed_at in native.execute('SELECT sequence,operation_id,nation_id,actor_id,recipe_id,batches,inputs,outputs,committed_at FROM starcore_resource_production_receipts ORDER BY sequence'):
                raw = chronicle.execute('SELECT json FROM committed_source_receipts WHERE sequence=?', (seq,)).fetchone()[0]
                entry = json.loads(raw)
                assert entry['operationId'] == operation
                event = entry['event']
                assert event['nations'] == [nation]
                assert event['participants'] == ([] if actor is None else [actor])
                assert event['facts']['production.recipe'] == recipe
                assert event['facts']['production.batches'] == str(batches)
                from datetime import datetime
                assert round(datetime.fromisoformat(event['occurredAt'].replace('Z', '+00:00')).timestamp()*1000) == committed_at
                for side, quantities in [('input', inputs), ('output', outputs)]:
                    for pair in quantities.split(';'):
                        key, value = pair.split('=')
                        assert event['facts']['production.'+side+'.'+key] == value
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
