"""Prepare/inspect a SHA-bound standalone Folia national-history degradation run.

Only the explicit compile subcommand invokes javac; no subcommand starts a server.
Preparation copies the coordinator's stopped Paper checkpoint3 byte-for-byte and never
rewrites its canonical events, countries, ballots, rewards or pinned definitions.
"""
from pathlib import Path
from datetime import datetime, timezone
import argparse
import base64
import hashlib
import json
import os
import re
import shutil
import sqlite3
import subprocess
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_FOLIA = Path(r'C:\Users\Administrator\Documents\StarChronicle\run\phase-three-folia-bfa80b9c-r2')
FOLIA_SHA = 'f52c408490a0225611e67907a3ca19f7e6da2c6bc899e715d5f46844e7103c39'
STORIES = {'primary': 'national-smoke-primary', 'invalidation': 'national-smoke-invalidation'}
FIXTURE = 'NationalCouncilFoliaFixture'
INVARIANTS = ('events', 'event_players', 'event_nations', 'event_causes', 'instances', 'outcomes', 'facts',
              'story_choice_log', 'story_nation_votes', 'story_nation_vote_log', 'story_nation_actor_bindings',
              'story_nation_resolutions', 'story_definitions', 'story_definition_formats', 'story_definition_upgrades')


def now():
    return datetime.now(timezone.utc).isoformat()


def digest(path):
    value = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(chunk)
    return value.hexdigest()


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'))


def players():
    return {who: str(uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:PhaseTwo' + who).encode()).digest(), version=3)) for who in 'ABCD'}


def run_path(sha, supplied=None):
    run = (ROOT / 'run' / ('folia-national-' + sha[:8])).resolve()
    if supplied is not None and Path(supplied).resolve() != run:
        raise SystemExit('Require exactly this worktree/run/folia-national-<sha8>')
    return run


def sidecars(path):
    return {suffix: {'exists': Path(str(path) + suffix).exists(),
                     'size': Path(str(path) + suffix).stat().st_size if Path(str(path) + suffix).exists() else None,
                     'sha256': digest(Path(str(path) + suffix)) if Path(str(path) + suffix).is_file() else None}
            for suffix in ('-wal', '-shm', '-journal')}


def sql_value(value):
    if isinstance(value, bytes):
        return {'$blob_base64': base64.b64encode(value).decode('ascii')}
    return value


def database_snapshot(path, immutable=False):
    if not path.is_file():
        raise SystemExit('Missing actual evidence database: ' + str(path))
    if immutable and any(item['size'] for key, item in sidecars(path).items() if key in ('-wal', '-journal')):
        raise SystemExit('Stopped checkpoint has a nonempty WAL/journal; refuse an incomplete source copy')
    uri = path.resolve().as_uri() + '?mode=ro' + ('&immutable=1' if immutable else '')
    with sqlite3.connect(uri, uri=True, isolation_level=None, timeout=15) as connection:
        connection.row_factory = sqlite3.Row
        connection.execute('PRAGMA query_only=ON')
        connection.execute('BEGIN')
        names = [row[0] for row in connection.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")]
        tables = {}
        for name in names:
            if not re.fullmatch(r'[A-Za-z0-9_]+', name):
                raise SystemExit('Unexpected SQLite identifier')
            rows = [{key: sql_value(value) for key, value in dict(row).items()} for row in connection.execute('SELECT * FROM "' + name + '"')]
            rows.sort(key=canonical)
            columns = [dict(row) for row in connection.execute('PRAGMA table_info("' + name + '")')]
            tables[name] = {'columns': columns, 'rows': rows, 'row_count': len(rows),
                            'content_sha256': hashlib.sha256(canonical(rows).encode('utf-8')).hexdigest()}
        result = {'schema_version': connection.execute('PRAGMA user_version').fetchone()[0],
                  'integrity': [row[0] for row in connection.execute('PRAGMA integrity_check')],
                  'foreign_key_violations': [list(row) for row in connection.execute('PRAGMA foreign_key_check')], 'tables': tables}
        connection.execute('ROLLBACK')
    if result['schema_version'] != 5 or result['integrity'] != ['ok'] or result['foreign_key_violations'] or not set(INVARIANTS) <= tables.keys():
        raise SystemExit('Require an intact real schema5 Chronicle national checkpoint')
    return result


def rows(snapshot, name):
    return snapshot['tables'][name]['rows']


def expected_state(snapshot, which, cohort):
    story = STORIES[which]
    candidates = [json.loads(row['json']) for row in rows(snapshot, 'instances') if row['story'] == story]
    if len(candidates) != 1:
        raise SystemExit('Require one real copied instance for ' + story)
    instance = candidates[0]
    if instance['definition'].get('voteUnit') != 'NATION' or instance['definition']['version'] != '1' or set(instance['members']) != set(cohort.values()):
        raise SystemExit('National source must retain the actual four-player pinned membership')
    if instance['votes'] or any('/' in key for key in instance['choices']):
        raise SystemExit('Source national instance contains fabricated personal ballots')
    node = instance['node']
    if instance['completed']:
        resolutions = [row for row in rows(snapshot, 'story_choice_log') if row['instance_id'] == instance['id'] and row['kind'] == 'RESOLUTION']
        if not resolutions:
            raise SystemExit('Completed source has no real collective resolution')
        node = max(resolutions, key=lambda row: row['seq'])['node_id']
    ballots = []
    counts = {}
    for row in sorted(rows(snapshot, 'story_nation_votes'), key=lambda row: row['nation_id']):
        if row['instance_id'] != instance['id'] or row['node_id'] != node:
            continue
        ballots.append({'nationId': row['nation_id'], 'revision': row['revision'], 'choiceId': row['choice_id'], 'status': row['status'],
                        'actorId': row['actor_id'], 'checkedAt': row['checked_at'], 'authorityEvidence': row['authority_evidence']})
        if row['status'] == 'ACTIVE':
            counts[row['choice_id']] = counts.get(row['choice_id'], 0) + 1
    frozen = [row for row in rows(snapshot, 'story_nation_resolutions') if row['instance_id'] == instance['id'] and row['node_id'] == node]
    if len(frozen) > 1:
        raise SystemExit('Ambiguous source national resolution')
    return {'instanceId': instance['id'], 'nodeId': node, 'completed': instance['completed'],
            'resolution': frozen[0]['choice_id'] if frozen else None, 'quorum': instance['definition']['nodes'][node]['quorum'],
            'counts': counts, 'ballots': ballots}


def source_unchanged(manifest):
    return all(Path(path).is_file() and digest(Path(path)) == sha for path, sha in manifest['source_preserved_sha256'].items())


def prepare(args, run):
    if run.exists():
        raise SystemExit('Refusing to overwrite an existing Folia run/evidence directory')
    source = (args.source_paper_run or ROOT / 'run' / ('paper-national-' + args.expected_sha[:8])).resolve()
    checkpoint = source / 'checkpoints/boot3'
    paper_manifest = source / 'preparation.json'
    process_file = source / 'evidence/national-council-processes.json'
    manifest = json.loads(paper_manifest.read_text(encoding='utf-8'))
    processes = json.loads(process_file.read_text(encoding='utf-8'))
    if source.name != 'paper-national-' + args.expected_sha[:8] or manifest['plugin_sha256'] != args.expected_sha or processes['plugin_sha256'] != args.expected_sha:
        raise SystemExit('Paper checkpoint must belong to the exact approved candidate')
    boots = processes['boots']
    if len(boots) != 3 or [boot['boot'] for boot in boots] != [1, 2, 3] or any(boot.get('server_exit') != 0 or boot.get('client_exit') != 0 for boot in boots):
        raise SystemExit('Require all three actual Paper server/client boots normally stopped before copying checkpoint3')
    product = (args.product or source / 'plugins/StarChronicle.jar').resolve()
    runtime = args.source_folia_runtime.resolve()
    folia = runtime / 'folia.jar'
    if digest(product) != args.expected_sha or digest(source / 'plugins/StarChronicle.jar') != args.expected_sha or digest(folia) != FOLIA_SHA:
        raise SystemExit('Frozen product or retained official Folia fingerprint differs')
    old_db = checkpoint / 'chronicle.db'
    before_sidecars = sidecars(old_db)
    baseline = database_snapshot(old_db, immutable=True)
    cohort = players()
    if manifest.get('players') != cohort:
        raise SystemExit('Source does not contain the original actual PhaseTwoA-D cohort')
    expected = {which: expected_state(baseline, which, cohort) for which in STORIES}
    if not expected['primary']['completed'] or expected['primary']['resolution'] != 'yes' or expected['invalidation']['completed']:
        raise SystemExit('Require the completed primary and still-active invalidation instance from real Paper checkpoint3')
    story_files = [source / 'plugins/StarChronicle/stories' / (name + '.json') for name in STORIES.values()]
    if set(manifest['story_sha256']) != {path.name for path in story_files} or any(digest(path) != manifest['story_sha256'][path.name] for path in story_files):
        raise SystemExit('The exact two original national story file bytes must remain unchanged')
    original_files = [paper_manifest, process_file, old_db, checkpoint / 'fixture-config.yml', product,
                      source / 'plugins/StarChronicle.jar', folia] + story_files
    for folder in ('libraries', 'cache', 'versions'):
        if (runtime / folder).is_dir():
            original_files.extend(path for path in (runtime / folder).rglob('*') if path.is_file())
    preserved = {str(path): digest(path) for path in original_files}
    (run / 'plugins/StarChronicle/stories').mkdir(parents=True)
    (run / 'plugins' / FIXTURE).mkdir()
    (run / 'evidence').mkdir()
    (run / 'fixture').mkdir()
    shutil.copy2(old_db, run / 'plugins/StarChronicle/chronicle.db')
    if digest(run / 'plugins/StarChronicle/chronicle.db') != preserved[str(old_db)]:
        raise SystemExit('Database clone was not an exact byte copy')
    shutil.copy2(product, run / 'plugins/StarChronicle.jar')
    shutil.copy2(folia, run / 'folia.jar')
    for path in story_files:
        shutil.copy2(path, run / 'plugins/StarChronicle/stories' / path.name)
    for folder in ('libraries', 'cache', 'versions'):
        if (runtime / folder).is_dir():
            shutil.copytree(runtime / folder, run / folder)
    with zipfile.ZipFile(product) as archive:
        (run / 'plugins/StarChronicle/npcs.yml').write_bytes(archive.read('npcs.yml'))
    fixture_source = ROOT / 'tools' / (FIXTURE + '.java')
    shutil.copy2(fixture_source, run / 'fixture' / fixture_source.name)
    shutil.copy2(ROOT / 'tools/NATIONAL-COUNCIL-FOLIA-RUN.md', run / 'RUNBOOK.md')
    (run / 'eula.txt').write_text('eula=true\n', encoding='utf-8')
    (run / 'ops.json').write_text('[]\n', encoding='utf-8')
    # Folia may account for pending login connections alongside already admitted players.
    # Four concurrent real clients therefore need headroom rather than an exact cap of four.
    (run / 'server.properties').write_text('server-ip=127.0.0.1\nserver-port=25604\nonline-mode=false\nenable-query=false\nenable-rcon=false\nspawn-protection=0\nview-distance=2\nsimulation-distance=2\nmax-players=12\nlevel-type=minecraft\\:flat\ngenerator-settings={"biome":"minecraft:plains","layers":[{"height":1,"block":"minecraft:bedrock"},{"height":2,"block":"minecraft:dirt"},{"height":1,"block":"minecraft:grass_block"}]}\ngenerate-structures=false\ngamemode=creative\ndifficulty=peaceful\nmotd=Isolated national history standalone Folia degradation\n', encoding='utf-8')
    (run / 'plugins/StarChronicle/config.yml').write_text('require-starcore: false\ninstall-default-stories: false\nadmin-demo-enabled: false\nnpc-hooks: false\nworker-queue-capacity: 2048\npoll-ticks: 12000\n', encoding='utf-8')
    (run / 'config').mkdir()
    (run / 'config/paper-global.yml').write_text('_version: 31\nchunk-system:\n  io-threads: 1\n  worker-threads: 1\nspark:\n  enabled: false\nthreaded-regions:\n  threads: 2\n', encoding='utf-8')
    honor = {who: next((row['value'] for row in rows(baseline, 'facts') if row['owner'] == player and row['key'] == 'player.honor'), '0') for who, player in cohort.items()}
    config = ['expected-sha: ' + args.expected_sha, 'expected:']
    config.extend('  ' + which + ': ' + json.dumps(canonical(value), ensure_ascii=False) for which, value in expected.items())
    config.append('expected-honor: ' + canonical(honor))
    (run / 'plugins' / FIXTURE / 'config.yml').write_text('\n'.join(config) + '\n', encoding='utf-8')
    source_evidence = {'captured_utc': now(), 'source_run': str(source), 'checkpoint': 'boot3', 'database_sha256': digest(old_db),
                       'source_sidecars': before_sidecars, 'normal_paper_processes': processes, 'expected_states': expected,
                       'expected_honor': honor, 'chronicle': baseline,
                       'provenance_limit': 'Coordinator-captured stopped checkpoint; hashes and actual process exits corroborate it, not a cryptographic attestation of an earlier filesystem copy.'}
    write_json(run / 'evidence/national-folia-source-baseline.json', source_evidence)
    shutil.copy2(checkpoint / 'fixture-config.yml', run / 'evidence/source-paper-fixture-config.yml')
    result = {'status': 'PREPARED_NOT_COMPILED_NOT_RUN', 'prepared_utc': now(), 'run_directory': str(run),
              'plugin_sha256': args.expected_sha, 'fixture_sha256': None, 'fixture_source_sha256': digest(fixture_source),
              'fixture_compiled': False, 'server_started': False, 'server_port': 25604, 'server_ip': '127.0.0.1', 'players': cohort,
              'server_sha256': FOLIA_SHA, 'source_paper_run': str(source), 'source_checkpoint': str(checkpoint),
              'source_folia_runtime': str(runtime), 'source_database_sha256': digest(old_db),
              'story_sha256': {path.name: digest(path) for path in story_files}, 'source_preserved_sha256': preserved,
              'source_sidecars': before_sidecars, 'expected_states': expected, 'expected_honor': honor,
              'scope': 'Real standalone Folia downgrade over actual Paper national receipts; no StarCore or replacement national authority, no native country mutation or historical rewrite'}
    if not source_unchanged(result) or before_sidecars != sidecars(old_db):
        raise SystemExit('Source bytes/sidecars changed during read-only preparation; retain this incomplete run for inspection')
    write_json(run / 'preparation.json', result)
    print(json.dumps({'status': result['status'], 'run_directory': str(run), 'plugin_sha256': args.expected_sha,
                      'fixture_compiled': False, 'source_files_unchanged': True, 'source_database_sha256': result['source_database_sha256'],
                      'server_port': 25604, 'players': cohort}, indent=2))


def load(args, run):
    manifest = json.loads((run / 'preparation.json').read_text(encoding='utf-8'))
    if manifest['plugin_sha256'] != args.expected_sha or digest(run / 'plugins/StarChronicle.jar') != args.expected_sha or digest(run / 'folia.jar') != FOLIA_SHA:
        raise SystemExit('Frozen product/server fingerprint drift')
    if not source_unchanged(manifest):
        raise SystemExit('Original retained source files changed; inspect before proceeding')
    for name, sha in manifest['story_sha256'].items():
        if digest(run / 'plugins/StarChronicle/stories' / name) != sha:
            raise SystemExit('Copied original story bytes changed')
    jars = {path.name for path in (run / 'plugins').glob('*.jar')}
    allowed = {'StarChronicle.jar', FIXTURE + '.jar'}
    if not jars <= allowed or not (run / 'plugins/StarChronicle.jar').is_file() or (run / 'plugins/StarCore').exists():
        raise SystemExit('Folia degradation must have no StarCore or extra plugin/authority JARs')
    return manifest


def compile_fixture(args, run):
    manifest = load(args, run)
    if manifest['fixture_compiled'] or (run / 'plugins' / (FIXTURE + '.jar')).exists():
        raise SystemExit('Refusing to replace an already compiled fixture')
    if args.java_home is None or not (args.java_home / 'bin/javac.exe').is_file():
        raise SystemExit('Compile requires an explicit Java 21 --java-home in the coordinator JVM window')
    source = run / 'fixture' / (FIXTURE + '.java')
    if digest(source) != manifest['fixture_source_sha256'] or digest(ROOT / 'tools' / source.name) != manifest['fixture_source_sha256']:
        raise SystemExit('Fixture source changed after preparation')
    api = args.paper_api or Path.home() / '.m2/repository/io/papermc/paper/paper-api/1.21.11-R0.1-SNAPSHOT/paper-api-1.21.11-R0.1-SNAPSHOT.jar'
    if not api.is_file():
        raise SystemExit('Provide the retained --paper-api compile JAR')
    attempts = manifest.setdefault('fixture_compile_attempts', [])
    number = len(attempts) + 1
    classes = run / f'fixture/classes-attempt{number}'
    classes.mkdir()
    jars = sorted((run / 'libraries').rglob('*.jar')) + [run / 'plugins/StarChronicle.jar', api]
    argfile = run / f'fixture/javac-attempt{number}.args'
    argfile.write_text('--release 21\n-proc:none\n-encoding UTF-8\n-cp\n"' + os.pathsep.join(str(path) for path in jars).replace('\\', '/') + '"\n-d\n"' + str(classes).replace('\\', '/') + '"\n"' + str(source).replace('\\', '/') + '"\n', encoding='utf-8')
    command = [str(args.java_home / 'bin/javac.exe'), '-J-Xmx128m', '@' + str(argfile)]
    result = subprocess.run(command, capture_output=True, timeout=120, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
    log = run / f'evidence/fixture-compile-attempt{number}.log'
    log.write_bytes(result.stdout + result.stderr)
    attempts.append({'attempt': number, 'command': command, 'exit_code': result.returncode, 'log': str(log.relative_to(run)), 'source_sha256': digest(source)})
    if result.returncode:
        manifest['status'] = 'FIXTURE_COMPILE_FAILED'
        write_json(run / 'preparation.json', manifest)
        raise SystemExit('Fixture compile failed; retained actual compiler output: ' + str(log))
    target = run / 'plugins' / (FIXTURE + '.jar')
    with zipfile.ZipFile(target, 'x', zipfile.ZIP_DEFLATED) as archive:
        archive.writestr('plugin.yml', 'name: NationalCouncilFoliaFixture\nversion: 1\nmain: smoke.NationalCouncilFoliaFixture\napi-version: "1.21"\ndepend: [StarChronicle]\nfolia-supported: true\ncommands:\n  ncfolia:\n    description: Isolated standalone Folia national-history degradation verifier\n')
        for path in sorted(classes.rglob('*.class')):
            archive.write(path, path.relative_to(classes).as_posix())
    manifest.update(fixture_compiled=True, fixture_sha256=digest(target), fixture_compile_exit_code=result.returncode,
                    status='COMPILED_NOT_RUN', compiled_utc=now(), java_home=str(args.java_home))
    write_json(run / 'preparation.json', manifest)
    print(json.dumps({'plugin_sha256': args.expected_sha, 'fixture_sha256': manifest['fixture_sha256'], 'server_started': False}, indent=2))


def inspect(args, run):
    manifest = load(args, run)
    if not re.fullmatch(r'[a-z0-9][a-z0-9-]{0,47}', args.label or ''):
        raise SystemExit('Inspect requires a bounded --label; prior evidence cannot be overwritten')
    target = run / 'evidence' / ('national-folia-' + args.label + '.json')
    if target.exists():
        raise SystemExit('Refusing to overwrite observed Folia evidence')
    baseline = json.loads((run / 'evidence/national-folia-source-baseline.json').read_text(encoding='utf-8'))['chronicle']
    current = database_snapshot(run / 'plugins/StarChronicle/chronicle.db')
    checks = {name: current['tables'][name] == baseline['tables'][name] for name in INVARIANTS}
    result = {'captured_utc': now(), 'label': args.label, 'plugin_sha256': args.expected_sha,
              'fixture_sha256': manifest.get('fixture_sha256'), 'source_files_unchanged': source_unchanged(manifest),
              'invariant_tables_unchanged': checks, 'database_invariants_status': 'PASS' if all(checks.values()) else 'FAIL',
              'acceptance_status': 'NOT_ASSESSED_BY_READ_ONLY_INSPECT', 'chronicle': current}
    write_json(target, result)
    print(json.dumps({key: value for key, value in result.items() if key != 'chronicle'}, indent=2))
    if not all(checks.values()):
        raise SystemExit('Actual Folia changed a protected source national/history/reward table')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['prepare', 'compile', 'check', 'inspect'])
    parser.add_argument('--expected-sha', required=True)
    parser.add_argument('--run-dir', type=Path)
    parser.add_argument('--source-paper-run', type=Path)
    parser.add_argument('--source-folia-runtime', type=Path, default=DEFAULT_FOLIA)
    parser.add_argument('--product', type=Path)
    parser.add_argument('--java-home', type=Path)
    parser.add_argument('--paper-api', type=Path)
    parser.add_argument('--label')
    args = parser.parse_args()
    if not re.fullmatch(r'[0-9a-f]{64}', args.expected_sha):
        raise SystemExit('Require the complete coordinator-approved SHA-256')
    run = run_path(args.expected_sha, args.run_dir)
    if args.action == 'prepare':
        prepare(args, run)
    elif args.action == 'compile':
        compile_fixture(args, run)
    elif args.action == 'inspect':
        inspect(args, run)
    else:
        manifest = load(args, run)
        if manifest['fixture_compiled'] and digest(run / 'plugins' / (FIXTURE + '.jar')) != manifest['fixture_sha256']:
            raise SystemExit('Compiled fixture fingerprint drift')
        print(json.dumps({'plugin_sha256': args.expected_sha, 'fixture_sha256': manifest.get('fixture_sha256'),
                          'source_files_unchanged': True, 'fixture_compiled': manifest['fixture_compiled'], 'server_started_by_tool': False}, indent=2))


if __name__ == '__main__':
    main()
