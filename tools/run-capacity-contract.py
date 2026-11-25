"""Measure real schema-2/7 upgrades and queries using frozen JARs in serial 64 MiB JVMs."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess
import time


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def original_tables(path):
    result = {}
    with sqlite3.connect(path) as database:
        database.execute('PRAGMA temp_store=FILE')
        for (table,) in database.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name").fetchall():
            columns = database.execute(f'PRAGMA table_info("{table}")').fetchall()
            keys = [row[1] for row in sorted(columns, key=lambda r: r[5]) if row[5]] or [row[1] for row in columns]
            order = ','.join('"'+name+'"' for name in keys)
            digest, count = hashlib.sha256(), 0
            for row in database.execute(f'SELECT * FROM "{table}" ORDER BY {order}'):
                digest.update((json.dumps(row, ensure_ascii=False, separators=(',', ':'))+'\n').encode())
                count += 1
            result[table] = {'rows': count, 'sha256': digest.hexdigest()}
    return result


def validate_migration(path, before, version):
    after = original_tables(path)
    for table, expected in before.items():
        assert after[table] == expected, ('Original data changed', table)
    with sqlite3.connect(path) as db:
        assert db.execute('PRAGMA user_version').fetchone()[0] == 8
        assert db.execute('PRAGMA integrity_check').fetchone()[0] == 'ok'
        assert not db.execute('PRAGMA foreign_key_check').fetchall()
        assert db.execute("SELECT COUNT(*) FROM instances i,json_each(i.json,'$.members') m LEFT JOIN instance_members p ON p.instance_id=i.id AND p.player_id=lower(m.key) WHERE p.instance_id IS NULL").fetchone()[0] == 0
        assert db.execute("SELECT COUNT(*) FROM instance_members p WHERE NOT EXISTS(SELECT 1 FROM instances i,json_each(i.json,'$.members') m WHERE i.id=p.instance_id AND lower(m.key)=p.player_id)").fetchone()[0] == 0
        if version == 2:
            keys = []
            for identity, stamp, completed in db.execute("SELECT id,json_extract(json,'$.updatedAt'),completed FROM instances"):
                seconds, _, fractional = stamp[:-1].partition('.')
                keys.append((seconds, int(fractional.ljust(9, '0') or '0'), identity, completed))
            keys.sort()
            expected_ids = [identity for _, _, identity, complete in keys for _ in range(2 if complete else 1)]
            assert [row[0] for row in db.execute('SELECT instance_id FROM story_choice_log ORDER BY seq')] == expected_ids
            assert db.execute("SELECT COUNT(*) FROM story_choice_log WHERE provenance<>'LEGACY_INSTANCE' OR chosen_at IS NOT NULL OR committed_by IS NOT NULL").fetchone()[0] == 0
    return after


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('previous', 'chronicle', 'java-home', 'output'):
        parser.add_argument('--'+name, type=Path, required=True)
    parser.add_argument('--instances', type=int, default=15000)
    args = parser.parse_args()
    if args.instances < 1000:
        parser.error('Use at least 1000 instances')
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    frozen, classes = output/'artifacts', output/'classes'
    frozen.mkdir(); classes.mkdir()
    for source, name in [(args.previous, 'schema7.jar'), (args.chronicle, 'schema8.jar'),
                         (Path(__file__).with_name('CapacitySeed.java'), 'CapacitySeed.java'),
                         (Path(__file__).with_name('CapacityProbe.java'), 'CapacityProbe.java')]:
        shutil.copy2(source, frozen/name)
    manifest = {'status': 'RUNNING', 'scope': 'Serial isolated JVMs and real SQLite; no Minecraft or live player; fixed 64 MiB heap, native memory is additional',
                'instances_per_database': args.instances, 'artifacts': {p.name: sha(p) for p in frozen.iterdir()}, 'phases': {}}
    suffix = '.exe' if os.name == 'nt' else ''

    def save():
        (output/'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')

    def run(label, program, arguments, launcher=(), expect_oom=False):
        argument_file = output/(label+'.args')
        argument_file.write_text('\n'.join(json.dumps(str(arg).replace('\\', '/')) for arg in arguments)+'\n', encoding='utf-8')
        start = time.monotonic()
        with (output/(label+'.log')).open('wb') as log:
            result = subprocess.run([str(args.java_home/'bin'/(program+suffix)), *launcher, '@'+str(argument_file)], stdout=log, stderr=subprocess.STDOUT, timeout=900)
        manifest['phases'][label] = {'exit_code': result.returncode, 'elapsed_seconds': round(time.monotonic()-start, 3), 'log_sha256': sha(output/(label+'.log'))}
        save()
        if expect_oom:
            assert result.returncode != 0 and 'OutOfMemoryError' in (output/(label+'.log')).read_text(encoding='utf-8', errors='replace'), 'Old build did not reproduce the heap failure'
            manifest['phases'][label]['expected_failure'] = 'Previous schema-7 build exhausts the same 64 MiB heap while migrating the literal V2 fixture'
        elif result.returncode:
            raise RuntimeError(f'{label} failed; see its retained log')
        print(label, 'EXPECTED_OOM' if expect_oom else 'PASS', flush=True)

    vm = ['-Xmx64m', '-Xss512k', '-XX:+UseSerialGC', '-XX:ActiveProcessorCount=1', '-XX:TieredStopAtLevel=1', '-XX:ReservedCodeCacheSize=32m', '-Dfile.encoding=UTF-8']
    try:
        for name, jar in [('CapacitySeed', 'schema7.jar'), ('CapacityProbe', 'schema8.jar')]:
            run('compile-'+name, 'javac', ['-proc:none', '-encoding', 'UTF-8', '--release', '21', '-cp', frozen/jar, '-d', classes, frozen/(name+'.java')], ['-J-Xmx128m', '-J-XX:+UseSerialGC'])
        opener = frozen/'CapacityOpen.java'
        opener.write_text('public class CapacityOpen { public static void main(String[] a) throws Exception { try(var s=new io.github.addxiaoyi.starchronicle.persistence.SqliteStore(java.nio.file.Path.of(a[0]))) { System.out.println("opened"); } } }\n', encoding='utf-8')
        manifest['artifacts'][opener.name] = sha(opener)
        run('compile-baseline', 'javac', ['-proc:none', '--release', '21', '-cp', frozen/'schema7.jar', '-d', classes, opener], ['-J-Xmx128m', '-J-XX:+UseSerialGC'])
        for version in (2, 7):
            database = output/f'schema{version}.db'
            run(f'seed-v{version}', 'java', [*vm, '-cp', str(classes)+os.pathsep+str(frozen/'schema7.jar'), 'CapacitySeed', database, version, args.instances])
            with sqlite3.connect(database) as db:
                assert db.execute('PRAGMA user_version').fetchone()[0] == version
            before = original_tables(database)
            (output/f'v{version}-original.json').write_text(json.dumps(before, indent=2)+'\n', encoding='utf-8')
            if version == 2:
                baseline = output/'baseline-v2.db'
                shutil.copy2(database, baseline)
                run('previous-build-v2-heap', 'java', [*vm, '-cp', str(classes)+os.pathsep+str(frozen/'schema7.jar'), 'CapacityOpen', baseline], expect_oom=True)
                assert original_tables(baseline) == before, 'Failed previous migration changed original data'
            for phase in ('upgrade', 'restart'):
                run(f'v{version}-{phase}', 'java', [*vm, '-cp', str(classes)+os.pathsep+str(frozen/'schema8.jar'), 'CapacityProbe', database, output/f'v{version}-{phase}.json', args.instances])
                after = validate_migration(database, before, version)
                manifest['phases'][f'v{version}-{phase}']['original_tables_preserved'] = len(before)
                manifest['phases'][f'v{version}-{phase}']['independent_validation'] = 'PASS'
                (output/f'v{version}-{phase}-tables.json').write_text(json.dumps(after, indent=2)+'\n', encoding='utf-8')
        manifest['status'] = 'PASS'
    except BaseException:
        manifest['status'] = 'FAILED'
        raise
    finally:
        save()


if __name__ == '__main__':
    main()
