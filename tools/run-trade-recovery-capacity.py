"""Compare V1 trade recovery with retained execution IDs in serial small JVMs."""
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
    for name in ('previous', 'starcore', 'runtime', 'java-home', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--runs', type=int, default=1000)
    parser.add_argument('--heap-mb', type=int, default=64)
    args = parser.parse_args()
    if args.runs < 257 or args.heap_mb < 48:
        parser.error('Use >=257 executions and >=48 MiB heap')
    spec = importlib.util.spec_from_file_location('capacity', Path(__file__).with_name('run-capacity-contract.py'))
    capacity = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(capacity)
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    artifacts = output / 'artifacts'
    artifacts.mkdir()
    classes = output / 'classes'
    classes.mkdir()
    for source, name in ((args.previous, 'previous.jar'), (args.starcore, 'current.jar'),
                         (Path(__file__).with_name('TradeRecoveryProbe.java'), 'TradeRecoveryProbe.java')):
        shutil.copy2(source, artifacts / name)
    libraries = sorted((args.runtime / 'libraries').rglob('*.jar'))
    if not libraries:
        raise RuntimeError('Runtime libraries missing')
    manifest = {'status': 'RUNNING', 'scope': 'Packaged native V1 trade recovery with real file reads and SQLite, synthetic immutable executions, serial small JVMs; not Minecraft, ongoing load or full resident-ledger capacity certification',
                'completed': args.runs, 'pending': 2, 'heap_mb': args.heap_mb,
                'artifacts': {p.name: capacity.sha(p) for p in artifacts.iterdir()}, 'phases': {}}

    def save():
        (output / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

    def cp(version):
        return os.pathsep.join(str(p.resolve()) for p in (classes, artifacts / (version + '.jar'), *libraries))

    def run(label, program, arguments, launcher=()):
        path = output / (label + '.args')
        path.write_text('\n'.join(json.dumps(str(a).replace('\\', '/')) for a in arguments) + '\n', encoding='utf-8')
        suffix = '.exe' if os.name == 'nt' else ''
        started = time.monotonic()
        with (output / (label + '.log')).open('wb') as log:
            result = subprocess.run([str(args.java_home / 'bin' / (program + suffix)), *launcher, '@' + str(path)],
                                    stdout=log, stderr=subprocess.STDOUT, timeout=600)
        manifest['phases'][label] = {'exit_code': result.returncode, 'elapsed_seconds': round(time.monotonic() - started, 3),
                                     'log_sha256': capacity.sha(output / (label + '.log'))}
        save()
        if result.returncode:
            raise RuntimeError(label + ' failed; original logs retained')
        print(label + ' PASS', flush=True)

    vm = [f'-Xmx{args.heap_mb}m', '-Xss512k', '-XX:+UseSerialGC', '-XX:ActiveProcessorCount=1',
          '-XX:TieredStopAtLevel=1', '-XX:ReservedCodeCacheSize=32m', '-Dfile.encoding=UTF-8']
    main_class = 'dev.starcore.starcore.module.resource.TradeRecoveryProbe'
    try:
        run('compile', 'javac', ['-proc:none', '-encoding', 'UTF-8', '--release', '21', '-cp', cp('previous'), '-d', classes, artifacts / 'TradeRecoveryProbe.java'],
            ['-J-Xmx96m', '-J-XX:+UseSerialGC', '-J-XX:ActiveProcessorCount=1'])
        original = output / 'original'
        original.mkdir()
        run('seed', 'java', [*vm, '-cp', cp('previous'), main_class, 'seed', original, args.runs])
        before = capacity.original_tables(original / 'trade.db')
        file_hash = capacity.sha(original / 'trade.properties')
        (output / 'original-tables.json').write_text(json.dumps(before, indent=2) + '\n', encoding='utf-8')
        for version in ('previous', 'current'):
            folder = output / version
            folder.mkdir()
            for name in ('trade.db', 'trade.properties'):
                shutil.copy2(original / name, folder / name)
            for mode in ('file', 'sql'):
                for phase in (('first',) if version == 'previous' else ('first', 'restart')):
                    label = version + '-' + mode + '-' + phase
                    run(label, 'java', [*vm, '-cp', cp(version), main_class, mode, folder, args.runs, output / (label + '.json')])
                    report = json.loads((output / (label + '.json')).read_text(encoding='utf-8'))
                    assert capacity.sha(folder / 'trade.properties') == file_hash
                    assert capacity.original_tables(folder / 'trade.db') == before
                    with sqlite3.connect(folder / 'trade.db') as db:
                        assert db.execute('PRAGMA integrity_check').fetchone() == ('ok',)
                        assert db.execute('SELECT COUNT(*) FROM starcore_resource_transfer_receipts').fetchone()[0] == args.runs + 1
                    if version == 'current':
                        if mode == 'file':
                            assert report['file_loads'] == 1
                        else:
                            assert report['validation_connections'] == 1
                            assert report['receipt_selects'] <= (args.runs + 2 + 255) // 256
                            assert report['max_parameters'] <= 256
                    manifest['phases'][label]['report'] = report
                    manifest['phases'][label]['independent_validation'] = 'PASS'
                    manifest['phases'][label]['original_tables_preserved'] = len(before)
        manifest['status'] = 'PASS'
    except BaseException:
        manifest['status'] = 'FAILED'
        raise
    finally:
        save()
    print(json.dumps({'status': manifest['status'], 'output': str(output)}, ensure_ascii=False))


if __name__ == '__main__':
    main()
