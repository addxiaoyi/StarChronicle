"""Compare frozen V1 module saves with SQL terminal archival and independent restart."""
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
    parser.add_argument('--runs', type=int, default=2000)
    parser.add_argument('--heap-mb', type=int, default=96)
    parser.add_argument('--restart-heap-mb', type=int, default=48)
    args = parser.parse_args()
    if args.runs < 513 or min(args.heap_mb, args.restart_heap_mb) < 32:
        parser.error('Use >=513 completed executions and >=32 MiB heap')
    spec = importlib.util.spec_from_file_location('capacity', Path(__file__).with_name('run-capacity-contract.py'))
    capacity = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(capacity)
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    artifacts = output / 'artifacts'; artifacts.mkdir()
    classes = output / 'classes'; classes.mkdir()
    for source, name in ((args.previous, 'previous.jar'), (args.starcore, 'current.jar'),
                         (Path(__file__).with_name('TradeArchiveProbe.java'), 'TradeArchiveProbe.java')):
        shutil.copy2(source, artifacts / name)
    libraries = sorted((args.runtime / 'libraries').rglob('*.jar'))
    assert libraries, 'Runtime libraries missing'
    manifest = {'status': 'RUNNING', 'scope': 'Packaged native module, synthetic old V1 executions, SQL archival, 20 unchanged saves, merged deep pages and original ID lookup; serial JVMs and real SQLite, no Minecraft or ongoing load certification',
                'completed': args.runs, 'pending': 2, 'heap_mb': args.heap_mb, 'restart_heap_mb': args.restart_heap_mb,
                'artifacts': {p.name: capacity.sha(p) for p in artifacts.iterdir()}, 'phases': {}}

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
        manifest['phases'][label] = {'exit_code': result.returncode, 'elapsed_seconds': round(time.monotonic() - start, 3), 'log_sha256': capacity.sha(output / (label + '.log'))}
        save()
        if result.returncode:
            raise RuntimeError(label + ' failed; logs retained')
        print(label + ' PASS', flush=True)

    def vm(heap):
        return [f'-Xmx{heap}m', '-Xss512k', '-XX:+UseSerialGC', '-XX:ActiveProcessorCount=1', '-XX:TieredStopAtLevel=1', '-XX:ReservedCodeCacheSize=32m', '-Dfile.encoding=UTF-8']

    def state(path):
        with sqlite3.connect(path) as db:
            return dict(db.execute('SELECT property_key,property_value FROM starcore_resource_state'))

    prefix = 'resource.trade-executions.'

    def validate(folder, original, archived):
        after = capacity.original_tables(folder / 'trade.db')
        before = capacity.original_tables(original / 'trade.db')
        for table, fingerprint in before.items():
            if table != 'starcore_resource_state':
                assert after[table] == fingerprint, table
        initial = state(original / 'trade.db'); current = state(folder / 'trade.db')
        retained = lambda p: {k: v for k, v in p.items() if not k.startswith((prefix, 'resource.factory-runs.'))}
        assert retained(initial) == retained(current), 'Non-trade state changed'
        assert capacity.sha(original / 'trade.properties') == capacity.sha(folder / 'trade.properties')
        with sqlite3.connect(folder / 'trade.db') as db:
            assert db.execute('PRAGMA integrity_check').fetchone() == ('ok',)
            if archived:
                expected = {}
                for key, value in initial.items():
                    if key.startswith(prefix + 'row.'):
                        row, _, field = key[len(prefix + 'row.'):].partition('.')
                        expected.setdefault(row, {})[field] = value
                active = {k: v for k, v in current.items() if k.startswith(prefix + 'row.')}
                chain = ''; seen = set()
                for sequence, identity, text, row_hash, chain_hash in db.execute('SELECT archive_sequence,execution_id,row_data,row_hash,chain_hash FROM starcore_resource_trade_archive ORDER BY archive_sequence'):
                    key = hashlib.sha256(identity.encode()).hexdigest()
                    fields = expected[key]
                    assert fields['state'] == 'COMPLETED'
                    assert text == ''.join(f'{k}={v}\n' for k, v in sorted(fields.items()))
                    assert hashlib.sha256(text.encode()).hexdigest() == row_hash
                    assert sequence == len(seen) + 1 and identity not in seen
                    chain = hashlib.sha256(f'{chain}:{sequence}:{row_hash}'.encode()).hexdigest()
                    assert chain == chain_hash
                    seen.add(identity)
                assert len(seen) == args.runs
                assert current[prefix + 'archive-count'] == str(args.runs)
                assert current[prefix + 'archive-head'] == chain
                assert current[prefix + 'version'] == '2' and current[prefix + 'count'] == '2'
                for key, fields in expected.items():
                    if fields['state'] != 'COMPLETED':
                        for field, value in fields.items():
                            assert active[prefix + 'row.' + key + '.' + field] == value
                assert len(active) == 40
            else:
                assert {k: v for k, v in initial.items() if k.startswith(prefix)} == {k: v for k, v in current.items() if k.startswith(prefix)}
        return after

    main_class = 'dev.starcore.starcore.module.resource.TradeArchiveProbe'
    try:
        run('compile', 'javac', ['-proc:none', '-encoding', 'UTF-8', '--release', '21', '-cp', cp('previous'), '-d', classes, artifacts / 'TradeArchiveProbe.java'], ['-J-Xmx96m', '-J-XX:+UseSerialGC', '-J-XX:ActiveProcessorCount=1'])
        original = output / 'original'; original.mkdir()
        run('seed', 'java', [*vm(args.heap_mb), '-cp', cp('previous'), main_class, 'seed', original, args.runs])
        for version in ('previous', 'current'):
            folder = output / version; folder.mkdir()
            for name in ('trade.db', 'trade.properties'):
                shutil.copy2(original / name, folder / name)
            for phase in (('first',) if version == 'previous' else ('first', 'restart')):
                label = version + '-' + phase
                run(label, 'java', [*vm(args.restart_heap_mb if phase == 'restart' else args.heap_mb), '-cp', cp(version), main_class, phase, folder, args.runs, output / (label + '.json')])
                report = json.loads((output / (label + '.json')).read_text())
                report['independent_validation'] = 'PASS'
                tables = validate(folder, original, version == 'current')
                (output / (label + '-tables.json')).write_text(json.dumps(tables, indent=2) + '\n')
                manifest['phases'][label]['report'] = report
        manifest['status'] = 'PASS'
    except BaseException:
        manifest['status'] = 'FAILED'
        raise
    finally:
        save()
    print(json.dumps({'status': manifest['status'], 'output': str(output)}))


if __name__ == '__main__':
    main()
