"""Compare frozen legacy treasury receipts, complete SQL archival and independent restart."""
import argparse
import hashlib
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
    parser.add_argument('--per-kind', type=int, default=500)
    parser.add_argument('--heap-mb', type=int, default=96)
    parser.add_argument('--restart-heap-mb', type=int, default=48)
    args = parser.parse_args()
    if args.per_kind < 171 or min(args.heap_mb, args.restart_heap_mb) < 32:
        parser.error('Use at least 171 receipts per kind and 32 MiB heap')
    output = args.output.resolve(); output.mkdir(parents=True, exist_ok=False)
    artifacts = output / 'artifacts'; artifacts.mkdir()
    classes = output / 'classes'; classes.mkdir()
    for source, name in ((args.previous, 'previous.jar'), (args.starcore, 'current.jar'),
                         (Path(__file__).with_name('TreasuryArchiveProbe.java'), 'TreasuryArchiveProbe.java'),
                         (Path(__file__).with_name('verify-treasury-archive.py'), 'verify-treasury-archive.py')):
        shutil.copy2(source, artifacts / name)
    spec = importlib.util.spec_from_file_location('archive', artifacts / 'verify-treasury-archive.py')
    archive = importlib.util.module_from_spec(spec); spec.loader.exec_module(archive)
    sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    libraries = sorted((args.runtime / 'libraries').rglob('*.jar')); assert libraries
    manifest = {'status': 'RUNNING', 'scope': 'Packaged TreasuryModule, frozen legacy receipts of all three types, exact archived fields/hash chain, unchanged balances/source, 20 saves and original-ID replay; sequential JVMs and SQLite, no Minecraft or long-term load certification',
                'receipts': 3 * args.per_kind, 'per_kind': args.per_kind, 'heap_mb': args.heap_mb, 'restart_heap_mb': args.restart_heap_mb,
                'artifacts': {p.name: sha(p) for p in artifacts.iterdir() if p.is_file()}, 'phases': {}}

    def save():
        (output / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

    def cp(version):
        return os.pathsep.join(str(p.resolve()) for p in (classes, artifacts / (version + '.jar'), *libraries))

    def run(label, program, arguments, launcher=()):
        path = output / (label + '.args')
        path.write_text('\n'.join(json.dumps(str(a).replace('\\', '/')) for a in arguments) + '\n', encoding='utf-8')
        suffix = '.exe' if os.name == 'nt' else ''
        start = time.monotonic()
        with (output / (label + '.log')).open('wb') as log:
            result = subprocess.run([str(args.java_home / 'bin' / (program + suffix)), *launcher, '@' + str(path)], stdout=log, stderr=subprocess.STDOUT, timeout=600)
        manifest['phases'][label] = {'exit_code': result.returncode, 'elapsed_seconds': round(time.monotonic() - start, 3), 'log_sha256': sha(output / (label + '.log'))}
        save()
        if result.returncode: raise RuntimeError(label + ' failed; logs retained')
        print(label + ' PASS', flush=True)

    def vm(heap):
        return [f'-Xmx{heap}m', '-Xss512k', '-XX:+UseSerialGC', '-XX:ActiveProcessorCount=1', '-XX:TieredStopAtLevel=1', '-XX:ReservedCodeCacheSize=32m', '-Dfile.encoding=UTF-8']

    def state(folder):
        with sqlite3.connect(folder / 'treasury.db') as db:
            return dict(db.execute('SELECT property_key,property_value FROM starcore_treasury_state'))

    def validate(folder, original, archived):
        assert sha(original / 'treasury.properties') == sha(folder / 'treasury.properties')
        initial = state(original); current = state(folder)
        stable = lambda p: {k: v for k, v in p.items() if not k.startswith(('spending.', 'credit.', 'transfer.', 'receipt-archive.')) and k != '__starcore_revision__'}
        assert stable(initial) == stable(current), 'Balances/domain changed'
        assert int(current['__starcore_revision__']) > int(initial['__starcore_revision__'])
        with sqlite3.connect(folder / 'treasury.db') as db:
            assert db.execute('PRAGMA integrity_check').fetchone() == ('ok',)
            if archived:
                receipts = archive.read_archive(db, current)
                assert len(receipts) == 3 * args.per_kind
                for kind in ('spending', 'credit', 'transfer'):
                    for index in range(args.per_kind):
                        prefix = f'{kind}.{index}.'
                        fields = {key[len(prefix):]: value for key, value in initial.items() if key.startswith(prefix)}
                        assert fields == receipts[kind, fields['transactionId']]
            else:
                assert {k: v for k, v in current.items() if k != '__starcore_revision__'} == {k: v for k, v in initial.items() if k != '__starcore_revision__'}

    main_class = 'dev.starcore.starcore.module.treasury.TreasuryArchiveProbe'
    try:
        run('compile', 'javac', ['-proc:none', '-encoding', 'UTF-8', '--release', '21', '-cp', cp('previous'), '-d', classes, artifacts / 'TreasuryArchiveProbe.java'], ['-J-Xmx96m', '-J-XX:+UseSerialGC', '-J-XX:ActiveProcessorCount=1'])
        original = output / 'original'; original.mkdir()
        run('seed', 'java', [*vm(args.heap_mb), '-cp', cp('previous'), main_class, 'seed', original, args.per_kind])
        for version in ('previous', 'current'):
            folder = output / version; folder.mkdir()
            for name in ('treasury.db', 'treasury.properties'): shutil.copy2(original / name, folder / name)
            for phase in (('first',) if version == 'previous' else ('first', 'restart')):
                label = version + '-' + phase
                run(label, 'java', [*vm(args.restart_heap_mb if phase == 'restart' else args.heap_mb), '-cp', cp(version), main_class, phase, folder, args.per_kind, output / (label + '.json')])
                report = json.loads((output / (label + '.json')).read_text())
                validate(folder, original, version == 'current')
                report['independent_validation'] = 'PASS'; manifest['phases'][label]['report'] = report
        manifest['status'] = 'PASS'
    except BaseException:
        manifest['status'] = 'FAILED'
        raise
    finally:
        save()
    print(json.dumps({'status': manifest['status'], 'output': str(output)}))


if __name__ == '__main__':
    main()
