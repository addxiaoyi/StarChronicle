"""Run actual packaged upgrade probes in serial disposable JVMs; never start Minecraft."""
from __future__ import annotations

import argparse
from collections import Counter
from decimal import Decimal
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import sqlite3
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
NATIVE = ROOT.parent / 'StarChronicle-audit/starcore-all-stages'
ARCHIVE = ROOT / 'docs/verification/processing-history-linux-20261126-evidence.zip'
PREFIX = 'paper-processing-cb9fe0a5-20261126-2/evidence/boot3.data/'
MAIN = 'dev.starcore.starcore.module.resource.ProcessingMergeProbe'
JVM = ['-Xms8m', '-Xmx96m', '-Xss512k', '-XX:+UseSerialGC', '-XX:ActiveProcessorCount=1',
       '-XX:TieredStopAtLevel=1', '-XX:ReservedCodeCacheSize=24m',
       '-XX:CompressedClassSpaceSize=24m', '-Dfile.encoding=UTF-8']


def sha(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def write_json(path: Path, value) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def database(path: Path):
    return sqlite3.connect(path.resolve().as_uri() + '?mode=ro', uri=True)


def backup(source: Path, destination: Path) -> None:
    with database(source) as original, sqlite3.connect(destination) as copy:
        assert original.execute('PRAGMA integrity_check').fetchone() == ('ok',), source
        original.backup(copy)


def snapshot(path: Path, previous=None) -> dict:
    def scalar(value):
        return {'blob_hex': value.hex()} if isinstance(value, bytes) else value
    with database(path) as db:
        assert db.execute('PRAGMA integrity_check').fetchone() == ('ok',), path
        result = {}
        for (table,) in db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name"):
            quoted = '"' + table.replace('"', '""') + '"'
            available = [row[1] for row in db.execute('PRAGMA table_info(' + quoted + ')')]
            columns = previous[table]['columns'] if previous and table in previous else available
            assert set(columns) <= set(available), (path, table, 'original column missing')
            selected = ','.join('"' + col.replace('"', '""') + '"' for col in columns)
            rows = sorted(json.dumps([scalar(v) for v in row], ensure_ascii=False, separators=(',', ':'))
                          for row in db.execute('SELECT ' + selected + ' FROM ' + quoted))
            result[table] = {'columns': columns, 'rows': rows,
                             'sha256': sha(json.dumps(rows, ensure_ascii=False).encode())}
        return result


def retained(before: dict, after: dict, exact=False, mutable_property_keys=frozenset()) -> None:
    for name, table in before.items():
        assert name in after and table['columns'] == after[name]['columns'], name
        if exact:
            assert table == after[name], ('original rows changed', name)
        else:
            lost = Counter(table['rows']) - Counter(after[name]['rows'])
            if name == 'starcore_resource_state' and mutable_property_keys:
                updated = [json.loads(row) for row in after[name]['rows']]
                for row in list(lost):
                    key, old_value = json.loads(row)
                    if key in mutable_property_keys:
                        matches = [value for candidate, value in updated if candidate == key]
                        assert len(matches) == 1 and matches[0] != old_value, ('Missing upgraded property', key)
                        del lost[row]
            assert not lost, ('original rows lost', name)


def assert_stockpile(actual: dict, expected: dict) -> None:
    assert {key: value for key, value in actual.items() if value != 0} == expected, actual


def immutable_files(folder: Path) -> dict:
    # The SQLite run index is verified by original columns/rows, not incidental page layout.
    return {path.relative_to(folder).as_posix(): sha(path.read_bytes())
            for path in folder.rglob('*') if path.is_file()
            and not any(path.name.endswith(ending) for ending in
                        ('.sqlite', '.sqlite-wal', '.sqlite-shm', '.sqlite-journal'))}


def parse_args():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('label', help='New evidence directory suffix; an existing output is refused')
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--native-root', type=Path, default=NATIVE)
    parser.add_argument('--chronicle-jar', type=Path, default=ROOT / 'target/starchronicle-0.1.0-SNAPSHOT.jar')
    parser.add_argument('--native-jar', type=Path, default=None)
    parser.add_argument('--baseline', type=Path, default=ROOT / 'run/processing-history-baseline-20261126')
    parser.add_argument('--upstream-schema10-jar', type=Path,
                        default=ROOT / 'run/processing-schema10-source-20261126/target/starchronicle-0.1.0-SNAPSHOT.jar',
                        help='Independently built upstream schema10 writer; not the reported cb9fe0a5 binary')
    parser.add_argument('--upstream-source-commit', default='aed14242589f95bbbcc8cb76de22a2d4135bebab')
    parser.add_argument('--libraries', type=Path, default=ROOT / 'run/paper-epic-1faf2ba2-dungeon-phases-2/libraries')
    return parser.parse_args()


def main():
    args = parse_args()
    if args.native_jar is None:
        args.native_jar = args.native_root / 'target/starcore-1.0.0.jar'
    assert re.fullmatch(r'[A-Za-z0-9_.-]+', args.label), 'Use a simple evidence label'
    required = [args.chronicle_jar, args.native_jar, args.baseline / 'chronicle.jar',
                args.baseline / 'native.jar', ARCHIVE, ROOT / 'tools/ProcessingMergeProbe.java']
    for path in required:
        assert path.is_file(), ('Required input missing', path)
    libraries = sorted(args.libraries.rglob('*.jar'))
    assert libraries, ('Missing already-installed Paper API libraries', args.libraries)
    out = ROOT / ('run/processing-merge-databases-' + args.label + '-20261126')
    out.mkdir(exist_ok=False)
    runs = []
    metadata = {
        'status': 'RUNNING', 'scope': 'Actual packaged native storage and Chronicle consumers in serial disposable JVM/database copies; no Minecraft server, network players, production migration or actual player-authority acceptance.',
        'chronicle_sha256': sha(args.chronicle_jar.read_bytes()),
        'starcore_sha256': sha(args.native_jar.read_bytes()),
        'baseline_chronicle_sha256': sha((args.baseline / 'chronicle.jar').read_bytes()),
        'baseline_native_sha256': sha((args.baseline / 'native.jar').read_bytes()),
        'upstream_archive_sha256': sha(ARCHIVE.read_bytes()), 'runs': runs,
        'upstream_schema10_source_commit': args.upstream_source_commit,
        'upstream_schema10_binary_provenance': 'Independent rebuild from the supplied source commit; not the original cb9fe0a5 report binary and not evidence that its test suite passed.'}
    write_json(out / 'started.json', metadata)

    def classpath(old=False, chronicle_override=None):
        products = ([args.baseline / 'chronicle.jar', args.baseline / 'native.jar'] if old
                    else [args.chronicle_jar, args.native_jar])
        if chronicle_override is not None:
            products = [chronicle_override, args.native_jar]
        return os.pathsep.join(str(path.resolve()) for path in products + libraries + [out])

    def argfile(folder, name, values):
        path = folder / (name + '.args')
        path.write_text('\n'.join('"' + str(v).replace('\\', '/') + '"' for v in values) + '\n', encoding='utf-8')
        return path

    executable = lambda name: args.java_home / 'bin' / (name + ('.exe' if os.name == 'nt' else ''))

    def run(case, phase, old=False, chronicle_override=None, log_suffix='', phase_args=()):
        folder = out / case
        folder.mkdir(exist_ok=True)
        logname = phase + log_suffix
        command = argfile(folder, logname, JVM + ['-cp', classpath(old, chronicle_override), MAIN, folder, phase, *phase_args])
        process = subprocess.run([str(executable('java')), '@' + str(command)], capture_output=True, timeout=600)
        (folder / (logname + '.log')).write_bytes(process.stdout + process.stderr)
        runs.append({'case': case, 'phase': phase, 'writer': str(chronicle_override or (args.baseline / 'chronicle.jar' if old else args.chronicle_jar)),
                     'exit_code': process.returncode, 'log': str((folder / (logname + '.log')).relative_to(out))})
        assert process.returncode == 0, (case, phase, 'See full log', logname)
        objects = [json.loads(line) for line in process.stdout.decode('utf-8').splitlines() if line.startswith('{')]
        assert len(objects) == 1, (case, phase, 'Expected one result')
        result = objects[0]
        write_json(folder / (logname + '.json'), result)
        return result

    def stable(first, second):
        ignored = {'phase', 'accepted_processing', 'accepted_resources', 'new_productions', 'new_transfers'}
        assert {k: v for k, v in first.items() if k not in ignored} == {k: v for k, v in second.items() if k not in ignored}
        for key in ignored - {'phase'}:
            assert second[key] == 0, ('Replayed work', key)

    def compact(value):
        large = {'transactions', 'productions', 'transfers', 'processing_changes', 'resource_changes'}
        result = {k: v for k, v in value.items() if k not in large and k != 'sql'}
        result.update({key + '_count': len(value.get(key, [])) for key in large})
        result['sql'] = {k: v for k, v in value['sql'].items() if k not in ('contributions', 'event_types')}
        return result

    try:
        with zipfile.ZipFile(ARCHIVE) as archive:
            manifest = json.loads(archive.read('evidence-manifest.json'))
            assert len(archive.namelist()) == len(set(archive.namelist())), 'Duplicate archive member'
            assert set(archive.namelist()) == set(manifest) | {'evidence-manifest.json'}
            for name, digest in manifest.items():
                assert sha(archive.read(name)) == digest, name
            for name in archive.namelist():
                if not name.startswith(PREFIX):
                    continue
                relative = name[len(PREFIX):]
                if not relative.startswith(('StarCore/resource/history/', 'StarCore/resource/processing-history/',
                                            'StarCore/starcore.db', 'StarChronicle/chronicle.db')):
                    continue
                assert '..' not in PurePosixPath(relative).parts and not PurePosixPath(relative).is_absolute()
                destination = out / 'raw' / relative
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(archive.read(name))
        metadata['manifest_files'] = len(manifest)
        source = ROOT / 'tools/ProcessingMergeProbe.java'
        shutil.copy2(source, out / source.name)
        compile_args = argfile(out, 'compile', ['-encoding', 'UTF-8', '-proc:none', '--release', '21',
                                               '-classpath', classpath(), '-d', out, source])
        process = subprocess.run([str(executable('javac')), '-J-Xms8m', '-J-Xmx96m', '-J-XX:+UseSerialGC',
                                  '-J-XX:ActiveProcessorCount=1', '-J-XX:TieredStopAtLevel=1',
                                  '-J-XX:ReservedCodeCacheSize=24m', '-J-XX:CompressedClassSpaceSize=24m',
                                  '@' + str(compile_args)], capture_output=True, timeout=600)
        (out / 'compile.log').write_bytes(process.stdout + process.stderr)
        assert process.returncode == 0, 'Probe compilation failed; see compile.log'

        seed = run('legacy', 'seed', old=True)
        assert seed['sql']['schema'] == 10
        assert_stockpile(seed['stockpile'], {'mineral': 991, 'energy': 997, 'industrial': 6})
        assert len(seed['productions']) == 2 and seed['production_cursor']['sequence'] == 2
        assert seed['sql']['counts']['contributions'] == 4
        before = {name: snapshot(out / 'legacy' / name) for name in ('starcore.db', 'chronicle.db')}
        write_json(out / 'legacy/before.json', before)
        upgraded = run('legacy', 'upgrade')
        assert upgraded['sql']['schema'] == 11
        for key in ('native_domain', 'stockpile', 'transactions', 'productions', 'transfers',
                    'legacy_factory', 'production_cursor', 'transfer_cursor', 'resource_changes', 'resource_cursor'):
            assert seed[key] == upgraded[key], key
        assert upgraded['sql']['contributions'] == seed['sql']['contributions']
        assert upgraded['accepted_processing'] == upgraded['new_productions'] == 0
        assert upgraded['processing_changes'] == [] and upgraded['processing_cursor']['sequence'] == 0
        metadata['legacy_upgrade_mutable_properties'] = [
            'resource.factory-runs.head', 'resource.history.commit']
        for name, original in before.items():
            after_upgrade = snapshot(out / 'legacy' / name, original)
            retained(original, after_upgrade,
                     mutable_property_keys=set(metadata['legacy_upgrade_mutable_properties'])
                     if name == 'starcore.db' else frozenset())
            write_json(out / 'legacy' / (name + '-after-upgrade.json'), after_upgrade)
        stable(upgraded, run('legacy', 'reopen-before-append'))
        appended = run('legacy', 'append')
        assert_stockpile(appended['stockpile'], {'mineral': 964, 'energy': 988, 'industrial': 24})
        assert appended['legacy_factory'] == seed['legacy_factory']
        assert appended['productions'][:2] == seed['productions']
        assert len(appended['productions']) == 4 and appended['new_productions'] == 2
        assert appended['production_cursor']['sequence'] == 4
        assert len(appended['processing_changes']) == appended['accepted_processing'] == 5
        assert appended['processing_cursor']['sequence'] == 5 and appended['processing_cursor']['live']
        assert appended['sql']['counts']['processing_history_receipts'] == 5
        contributions = appended['sql']['contributions']
        assert len(contributions) == 12, 'New/old factory completions must not double-credit'
        totals = {}
        contribution_keys = set()
        for entry in contributions:
            key = (entry['operation_id'], entry['subject_id'])
            assert key not in contribution_keys, ('Duplicate contribution subject/operation', key)
            contribution_keys.add(key)
            if entry['unit'] == 'resource.industrial':
                expected_event = 'CIVILIZATION_PRODUCTION_COMMITTED'
            elif entry['unit'] == 'industrial':
                expected_event = 'PROCESSING_COMPLETED'
            else:
                raise AssertionError(('Unexpected or mixed production unit', entry))
            assert appended['sql']['event_types'][entry['source_event_id']] == expected_event, entry
            pair = (entry['subject_id'], entry['unit'])
            totals[pair] = totals.get(pair, Decimal(0)) + Decimal(entry['amount'])
        assert len(totals) == 4 and set(totals.values()) == {Decimal(10), Decimal(12)}, totals
        assert {entry['kind'] for entry in appended['processing_changes']} == {'STARTED', 'COMPLETED', 'AUTOMATIC'}
        assert all(entry in contributions for entry in seed['sql']['contributions'])
        stable(appended, run('legacy', 'reopen-after-append-1'))
        stable(appended, run('legacy', 'reopen-after-append-2'))

        folder = out / 'upstream'; folder.mkdir()
        backup(out / 'raw/StarCore/starcore.db', folder / 'starcore.db')
        backup(out / 'raw/StarChronicle/chronicle.db', folder / 'chronicle.db')
        shutil.copytree(out / 'raw/StarCore/resource/history', folder / 'history')
        shutil.copytree(out / 'raw/StarCore/resource/processing-history', folder / 'processing-history')
        before_upstream = {name: snapshot(folder / name) for name in
                           ('starcore.db', 'chronicle.db', 'processing-history/runs.sqlite')}
        write_json(folder / 'before.json', before_upstream)
        assert len(before_upstream['chronicle.db']['processing_history_receipts']['rows']) == 137
        assert len(before_upstream['chronicle.db']['contributions']['rows']) == 12
        original_files = {name: immutable_files(folder / name) for name in ('history', 'processing-history')}
        upstream = run('upstream', 'upgrade')
        assert upstream['sql']['schema'] == 11
        assert len(upstream['processing_changes']) == 137 and upstream['accepted_processing'] == 0
        assert len(upstream['resource_changes']) == 139 and upstream['accepted_resources'] == 0
        assert upstream['processing_cursor']['sequence'] == 137 and upstream['resource_cursor']['sequence'] == 139
        for name, original in before_upstream.items():
            after_upgrade = snapshot(folder / name, original)
            retained(original, after_upgrade,
                     mutable_property_keys=set(metadata['legacy_upgrade_mutable_properties'])
                     if name == 'starcore.db' else frozenset())
            write_json(folder / (name.replace('/', '-') + '-after-upgrade.json'), after_upgrade)
        assert upstream['sql']['counts']['contributions'] == 12
        for name, original in original_files.items():
            assert original == immutable_files(folder / name), ('Immutable native evidence changed', name)
        stable(upstream, run('upstream', 'reopen-1'))
        stable(upstream, run('upstream', 'reopen-2'))
        after_upstream = {}
        for name, original in before_upstream.items():
            after_upstream[name] = snapshot(folder / name, original)
            retained(original, after_upstream[name],
                     mutable_property_keys=set(metadata['legacy_upgrade_mutable_properties'])
                     if name == 'starcore.db' else frozenset())
        for table in ('processing_history_receipts', 'contributions'):
            assert before_upstream['chronicle.db'][table] == after_upstream['chronicle.db'][table], table
        assert before_upstream['processing-history/runs.sqlite'] == after_upstream['processing-history/runs.sqlite']

        writers = [('local-resource-schema10', args.baseline / 'chronicle.jar')]
        missing = []
        if args.upstream_schema10_jar.is_file():
            writers.append(('upstream-processing-schema10', args.upstream_schema10_jar))
            metadata['upstream_schema10_sha256'] = sha(args.upstream_schema10_jar.read_bytes())
        else:
            missing.append('Second schema10 writer binary missing: ' + str(args.upstream_schema10_jar))
        refused = []
        for case in ('legacy', 'upstream'):
            for name, jar in writers:
                database_path = out / case / 'chronicle.db'
                before_refusal = snapshot(database_path)
                before_bytes = sha(database_path.read_bytes())
                result = run(case, 'reject-schema', chronicle_override=jar, log_suffix='-' + name, phase_args=('10',))
                assert result['status'] == 'REJECTED' and result['writer_schema'] == 10
                assert before_refusal == snapshot(database_path)
                assert before_bytes == sha(database_path.read_bytes()), 'Old writer altered schema11 file'
                refused.append({'case': case, 'writer': name, 'sha256': sha(jar.read_bytes())})
        metadata.update({'status': 'PARTIAL' if missing else 'VERIFIED', 'missing': missing,
                         'legacy': {'seed': compact(seed), 'upgrade': compact(upgraded), 'append': compact(appended),
                                    'original_tables': {key: len(value) for key, value in before.items()}},
                         'upstream': compact(upstream) | {'original_tables': {key: len(value) for key, value in before_upstream.items()}},
                         'old_writers_refused': refused})
        write_json(out / 'result.json', metadata)
        print(json.dumps(metadata, ensure_ascii=False))
    except BaseException as failure:
        metadata.update({'status': 'FAILED', 'failure': repr(failure)})
        write_json(out / 'failure.json', metadata)
        raise


if __name__ == '__main__':
    main()
