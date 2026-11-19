"""Prepare a fresh clone of the stopped real phase-two Paper state. Never starts a JVM."""
from pathlib import Path
import argparse
import hashlib
import importlib.util
import json
import re
import shutil
import sqlite3
import zipfile

spec = importlib.util.spec_from_file_location('phase_three_paper_common', Path(__file__).with_name('phase-three-paper-common.py'))
c = importlib.util.module_from_spec(spec); spec.loader.exec_module(c)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--expected-sha', required=True)
    parser.add_argument('--run-name')
    parser.add_argument('--source-run', type=Path, default=c.ROOT / 'run/phase-two-paper-release-smoke')
    parser.add_argument('--product', type=Path, default=c.ROOT / 'target/starchronicle-0.1.0-SNAPSHOT.jar')
    parser.add_argument('--port', type=int, default=25590)
    args = parser.parse_args()
    if not re.fullmatch(r'[0-9a-f]{64}', args.expected_sha) or not args.product.is_file() or c.digest(args.product) != args.expected_sha:
        raise SystemExit('Require the coordinator-frozen exact new product SHA-256')
    run = c.run_path(c.ROOT / 'run' / (args.run_name or 'phase-three-paper-' + args.expected_sha[:8]))
    source = args.source_run.resolve()
    if run.exists() or source != (c.ROOT / 'run/phase-two-paper-release-smoke').resolve():
        raise SystemExit('Refusing overwrite or an unreviewed source run')
    if not 1024 <= args.port <= 65535:
        raise SystemExit('Invalid isolated loopback port')
    if c.digest(source / 'plugins/StarChronicle.jar') != c.OLD_PLUGIN_SHA:
        raise SystemExit('The source is not the actually accepted phase-two artifact')
    old_report = json.loads((c.ROOT / 'docs/verification/phase-two-paper-summary.json').read_text(encoding='utf-8'))
    if old_report['status'] != 'PASS' or old_report['server_exit_codes'] != [0, 0, 0] or old_report['client_exit_codes'] != [0, 0, 0]:
        raise SystemExit('Require the observed normally stopped three-boot source acceptance')
    old = c.db_snapshot(source / 'plugins/StarChronicle/chronicle.db')
    if old['schema_version'] != 3 or old['integrity'] != 'ok' or old['foreign_key_violations']:
        raise SystemExit('Require the actual intact schema-three source')
    events = {row['id']: row for row in old['events']}
    if c.DISCOVERY not in events or c.ENDING not in events:
        raise SystemExit('Missing original real-client canonical sources')
    retained = [json.loads(row['json']) for row in old['instances'] if row['story'] == c.ARCHIVE]
    if len(retained) != 4 or sum(item['completed'] for item in retained) != 3:
        raise SystemExit('Require three completed A1 instances and actual B still unfinished')
    with zipfile.ZipFile(args.product) as jar:
        for name in ['io/github/addxiaoyi/starchronicle/core/DefinitionCatalog.class', 'io/github/addxiaoyi/starchronicle/api/HistoricalEvaluationReport.class']:
            if name not in jar.namelist():
                raise SystemExit('The product predates the reviewed catalog/evaluation implementation')
    preserved = {}
    for base in [source / 'plugins/StarChronicle', source / 'plugins/StarCore']:
        for path in sorted(base.rglob('*')):
            if path.is_file():
                preserved[path.relative_to(source).as_posix()] = c.digest(path)
    preserved['plugins/StarChronicle.jar'] = c.digest(source / 'plugins/StarChronicle.jar')
    preserved['plugins/StarCore.jar'] = c.digest(source / 'plugins/StarCore.jar')
    (run / 'plugins').mkdir(parents=True)
    (run / 'evidence').mkdir()
    (run / 'fixture-content').mkdir()
    (run / 'fixture').mkdir()
    for name in ['cache', 'libraries', 'world', 'world_nether', 'world_the_end']:
        if (source / name).exists():
            shutil.copytree(source / name, run / name)
    for name in ['paper.jar', 'eula.txt', 'bukkit.yml', 'spigot.yml', 'permissions.yml', 'usercache.json', 'whitelist.json', 'ops.json']:
        if (source / name).is_file():
            shutil.copy2(source / name, run / name)
    if (source / 'config').is_dir():
        shutil.copytree(source / 'config', run / 'config')
    for name in ['StarChronicle', 'StarCore']:
        shutil.copytree(source / 'plugins' / name, run / 'plugins' / name,
                        ignore=shutil.ignore_patterns('*.db-wal', '*.db-shm', '*.db-journal'))
    # Replace only the cloned Chronicle DB with one consistent backup from a read-only handle.
    clone_db = run / 'plugins/StarChronicle/chronicle.db'
    clone_db.unlink()
    with c.database(source / 'plugins/StarChronicle/chronicle.db') as original, sqlite3.connect(clone_db) as clone:
        original.backup(clone)
    shutil.copy2(source / 'plugins/StarCore.jar', run / 'plugins/StarCore.jar')
    shutil.copy2(args.product, run / 'plugins/StarChronicle.jar')
    shutil.copy2(c.ROOT / 'tools/PhaseThreePaperFixture.java', run / 'fixture/PhaseThreePaperFixture.java')
    properties = (source / 'server.properties').read_text(encoding='utf-8')
    for key, value in {'server-ip': '127.0.0.1', 'server-port': str(args.port), 'enable-query': 'false', 'enable-rcon': 'false',
                       'motd': 'Isolated phase-three StarChronicle canonical-history acceptance'}.items():
        properties = re.sub(r'(?m)^' + re.escape(key) + r'=.*$', key + '=' + value, properties)
    (run / 'server.properties').write_text(properties, encoding='utf-8')
    cloned_config = run / 'plugins/StarChronicle/config.yml'
    config_text = cloned_config.read_text(encoding='utf-8')
    if re.search(r'(?m)^install-default-stories:', config_text):
        config_text = re.sub(r'(?m)^install-default-stories:.*$', 'install-default-stories: false', config_text)
    else:
        config_text += '\ninstall-default-stories: false\n'
    cloned_config.write_text(config_text, encoding='utf-8')
    content_hashes = {}
    for name, definition in c.content().items():
        path = run / 'fixture-content' / name;c.write_json(path, definition);content_hashes[name] = c.digest(path)
    baseline = {'stage': 'source-schema3', 'captured_at': c.now(), 'source_run': source.relative_to(c.ROOT).as_posix(),
                'source_database_sha256': c.digest(source / 'plugins/StarChronicle/chronicle.db'), **old}
    with c.database(source / 'plugins/StarCore/starcore.db') as core:
        baseline['core_nation_state'] = [dict(row) for row in core.execute('SELECT property_key,property_value FROM starcore_nation_state ORDER BY property_key')]
        baseline['core_integrity'] = core.execute('PRAGMA integrity_check').fetchone()[0]
    c.write_json(run / 'evidence/source-schema3.json', baseline)
    value = {
        'status': 'PREPARED_NOT_COMPILED_NOT_RUN', 'prepared_utc': c.now(), 'source_run': source.relative_to(c.ROOT).as_posix(),
        'run_directory': run.relative_to(c.ROOT).as_posix(), 'plugin_sha256': args.expected_sha,
        'old_plugin_sha256': c.OLD_PLUGIN_SHA, 'server_sha256': c.digest(run / 'paper.jar'),
        'starcore_sha256': c.digest(run / 'plugins/StarCore.jar'), 'fixture_source_sha256': c.digest(run / 'fixture/PhaseThreePaperFixture.java'),
        'fixture_content_sha256': content_hashes, 'source_preserved_sha256': preserved,
        'clone_initial_database_sha256': c.digest(clone_db), 'source_database_sha256': baseline['source_database_sha256'],
        'server_port': args.port, 'poll_ticks': 12000, 'players': c.PLAYERS, 'nations': c.NATIONS,
        'clone_initial_config_sha256': c.digest(cloned_config), 'install_default_stories': False,
        'source_discovery': c.DISCOVERY, 'source_ending': c.ENDING,
        'fixture_compiled': False, 'server_started': False,
        'scope': 'Three real Paper boots over a cloned actual schema3 database, real StarCore and four original clients; source run untouched',
    }
    if not c.source_unchanged(value):
        raise SystemExit('Source files changed during preparation; do not run this clone')
    c.write_json(run / 'preparation.json', value)
    print(json.dumps(value, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
