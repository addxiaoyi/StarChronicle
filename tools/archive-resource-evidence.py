"""Freeze resource history source, successful/failed checks, SQL snapshots and cross-device evidence."""
from pathlib import Path
from datetime import datetime, timezone
import argparse
import hashlib
import importlib.util
import json
import re
import shutil
import socket
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def sha(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def git(root, *args):
    return subprocess.check_output(['git', '-C', str(root), *args], text=True).strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run', type=Path, required=True)
    parser.add_argument('--failed-run', type=Path, action='append', default=[])
    parser.add_argument('--prior-run', type=Path, action='append', default=[])
    parser.add_argument('--native', type=Path, required=True)
    parser.add_argument('--label', required=True)
    args = parser.parse_args()
    run, native = args.run.resolve(), args.native.resolve()
    failed = [p.resolve() for p in args.failed_run]
    prior = [p.resolve() for p in args.prior_run]
    for path in [run, *failed, *prior]:
        if path.parent != (ROOT / 'run').resolve() or not path.name.startswith('paper-resource-'):
            raise RuntimeError('Require isolated resource acceptance directories')
    if not re.fullmatch('[a-z0-9-]+', args.label):
        raise RuntimeError('Invalid archive label')
    if git(ROOT, 'status', '--porcelain', '--', 'src', 'pom.xml', 'docs/API.md') or git(native, 'status', '--porcelain'):
        raise RuntimeError('Product source must be committed and clean')
    manifest = json.loads((run / 'preparation.json').read_text())
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', manifest['port']))
    spec = importlib.util.spec_from_file_location('resource_inspector', ROOT / 'tools/inspect-resource-history.py')
    inspector = importlib.util.module_from_spec(spec); spec.loader.exec_module(inspector)
    accepted = inspector.inspect(run)
    if sha(ROOT / 'target/starchronicle-0.1.0-SNAPSHOT.jar') != manifest['product_sha256'] or sha(native / 'target/starcore-1.0.0.jar') != manifest['native_sha256']:
        raise RuntimeError('Current build differs from frozen artifact')
    with zipfile.ZipFile(ROOT / 'target/starchronicle-0.1.0-SNAPSHOT-sources.jar') as source_jar:
        for path in (ROOT / 'src/main/java').rglob('*.java'):
            if source_jar.read(path.relative_to(ROOT / 'src/main/java').as_posix()) != path.read_bytes():
                raise RuntimeError('Source archive differs from tested source')
    with zipfile.ZipFile(run / 'plugins/StarChronicle.jar') as jar:
        if jar.read('META-INF/starchronicle/docs/API.md') != (ROOT / 'docs/API.md').read_bytes():
            raise RuntimeError('Packaged API documentation mismatch')
    suites = []
    for path in sorted((ROOT / 'target/surefire-reports').glob('TEST-*.xml')):
        value = ET.parse(path).getroot()
        suites.append(dict(name=value.get('name'), file=path.name, sha256=sha(path),
            **{key: int(value.get(key, '0')) for key in ('tests', 'failures', 'errors', 'skipped')}))
    totals = {key: sum(s[key] for s in suites) for key in ('tests', 'failures', 'errors', 'skipped')}
    if totals != dict(tests=945, failures=0, errors=0, skipped=0):
        raise RuntimeError('Unexpected full verification reports')
    verify = ROOT / 'target/resource-chronicle-verify-20261126-3.log'
    packaging = native / 'target/resource-native-verify-20261126-1.log'
    if any('BUILD SUCCESS' not in p.read_text() for p in (verify, packaging)):
        raise RuntimeError('Missing successful build log')
    evidence_test = ROOT / 'target/resource-evidence-tests-20261126-1.log'
    if not re.search(r'Ran 16 tests in .*\n\nOK\s*$', evidence_test.read_text()):
        raise RuntimeError('Missing successful evidence regression log')
    upgrade = json.loads((ROOT / 'target/resource-schema-upgrade/inspection.json').read_text())
    if upgrade.get('status') != 'PASS' or upgrade.get('new_jar_sha256') != manifest['product_sha256'] or not upgrade.get('old_plugin_rejected'):
        raise RuntimeError('Missing real old-database upgrade and old-JAR rejection evidence')
    files = {}
    for attempt in [run, *failed, *prior]:
        if attempt in failed:
            sessions = list((attempt / 'evidence').glob('boot*.session.json'))
            if not sessions or not any(json.loads(s.read_text()).get('passed') is False for s in sessions):
                raise RuntimeError('Failed attempt is not recorded as failed')
        for path in [attempt / 'preparation.json', attempt / 'server.properties', attempt / 'fixture/ResourceHistoryPaperFixture.java']:
            files[attempt.name + '/' + path.relative_to(attempt).as_posix()] = path
        for path in (attempt / 'evidence').rglob('*'):
            if path.is_file():
                files[attempt.name + '/' + path.relative_to(attempt).as_posix()] = path
    source_files = git(ROOT, 'ls-files', '--', 'src', 'pom.xml', 'docs/API.md').splitlines()
    for name in source_files:
        files['chronicle-source/' + name] = ROOT / name
    for suite in suites:
        files['surefire/' + suite['file']] = ROOT / 'target/surefire-reports' / suite['file']
    native_sources = git(native, 'ls-files', '--', 'src', 'pom.xml', 'docs/api/starcore-api-v1.txt').splitlines()
    for name in native_sources:
        files['native-source/' + name] = native / name
    native_suites = []
    for path in sorted((native / 'target/surefire-reports').glob('TEST-*.xml')):
        value = ET.parse(path).getroot()
        native_suites.append(dict(name=value.get('name'), file=path.name, sha256=sha(path),
            **{key: int(value.get(key, '0')) for key in ('tests', 'failures', 'errors', 'skipped')}))
        files['native-surefire/' + path.name] = path
    native_totals = {key: sum(s[key] for s in native_suites) for key in ('tests', 'failures', 'errors', 'skipped')}
    if native_totals != dict(tests=7251, failures=0, errors=0, skipped=21):
        raise RuntimeError('Unexpected native full verification reports')
    for path in (ROOT / 'target/resource-schema9-failed-verification2-reports').glob('*'):
        if path.is_file(): files['chronicle-failed-verification/' + path.name] = path
    for path in (ROOT / 'target/resource-schema-upgrade').rglob('*'):
        if path.is_file(): files['schema-upgrade/' + path.relative_to(ROOT / 'target/resource-schema-upgrade').as_posix()] = path
    for path in (ROOT / 'target/resource-schema8-checkpoint/surefire-reports').glob('TEST-*.xml'):
        files['schema8-intermediate-surefire/' + path.name] = path
    for attempt in prior:
        inspector.inspect(attempt, schema=8)
    for base, prefix in ((ROOT, 'chronicle'), (native, 'native')):
        for path in (base / 'target').glob('resource-*.log'):
            files[prefix + '-logs/' + path.name] = path
    for name in ('ResourceHistoryPaperFixture.java', 'resource-history-paper.py', 'inspect-resource-history.py',
                 'test_resource_history.py', 'archive-resource-evidence.py', 'ResourceSchemaProbe.java'):
        files['tools/' + name] = ROOT / 'tools' / name
    secrets = set()
    for path in files.values():
        if path.name == 'config.yml' and path.parent.name == 'StarCore':
            secrets.update(v.strip().strip('"\'') for v in re.findall(r'(?m)^\s*access-secret:\s*(.*)$', path.read_text()))
    with zipfile.ZipFile(run / 'plugins/StarCore.jar') as jar:
        defaults = {v.strip().strip('"\'') for v in re.findall(r'(?m)^\s*access-secret:\s*(.*)$', jar.read('config.yml').decode())}
    secrets -= defaults | {'', 'REDACTED_TEST_SECRET'}
    destination = ROOT / 'target' / ('resource-history-' + args.label)
    destination.mkdir(exist_ok=False)
    for name in ('StarCore', 'StarChronicle', 'ResourceHistoryPaperFixture'):
        shutil.copy2(run / f'plugins/{name}.jar', destination / f'{name}.jar')
    shutil.copy2(ROOT / 'target/starchronicle-0.1.0-SNAPSHOT-sources.jar', destination / 'StarChronicle-sources.jar')
    archive_path = destination / 'evidence.zip'
    with zipfile.ZipFile(archive_path, 'x', zipfile.ZIP_DEFLATED) as archive:
        for name, path in sorted(files.items()):
            if path.is_symlink(): raise RuntimeError('Evidence symlink rejected')
            content = path.read_bytes()
            if path.name == 'config.yml' and path.parent.name == 'StarCore':
                content = re.sub(r'(?m)^(\s*access-secret:)\s*.*$', r'\1 "REDACTED_TEST_SECRET"', content.decode()).encode()
            if any(secret.encode() in content for secret in secrets):
                raise RuntimeError('Generated secret remained in ' + name)
            archive.writestr(name, content)
        inventory = {entry.filename: hashlib.sha256(archive.read(entry)).hexdigest() for entry in archive.infolist()}
        archive.writestr('evidence-manifest.json', json.dumps(inventory, indent=2) + '\n')
    with zipfile.ZipFile(archive_path) as archive:
        if archive.testzip() is not None or any(hashlib.sha256(archive.read(name)).hexdigest() != digest for name, digest in inventory.items()):
            raise RuntimeError('Evidence archive integrity failed')
    summary = dict(captured_utc=datetime.now(timezone.utc).isoformat(), chronicle_commit=git(ROOT, 'rev-parse', 'HEAD'),
        native_commit=git(native, 'rev-parse', 'HEAD'), accepted=accepted, failed_attempts=[p.name for p in failed], intermediate_schema8_attempts=[p.name for p in prior],
        tests=dict(totals=totals, suites=suites, evidence_regressions=16), source_files={name: sha(ROOT / name) for name in source_files},
        native_tests=dict(totals=native_totals, suites=native_suites), schema_upgrade=upgrade,
        native_source_files={name: sha(native / name) for name in native_sources},
        build_note='Chronicle final verify passed 945 tests and all source files/API.md match its artifacts. Native full verify passed 7251 tests (21 existing skips), API baseline and Spotless. Earlier schema8 verify passed 944; first schema9 verify failed one old hard-coded schema assertion, corrected without weakening its data-preservation checks; failed reports retained.',
        limitations=['Real native resource APIs with offline fixture nations; no online client, factory completion or personal production attribution.',
            'First 3 events archive only; 130 changes committed while Chronicle disabled and recovered across 128-row paging; 136 live events create 164 nation inbox rows because transfers involve two nations.',
            'No latest-jar full epic combination, full-stack Folia, MySQL, third-party economy or production verification.',
            'SQL authority, file migration, lost commit replies and fault boundaries have Java tests; Paper uses fresh SQLite storage.',
            'Factory atomic production/run/actor, researcher history, unknown quest reconciliation, market escrow recovery and long-duration capacity remain open.'],
        evidence=dict(sha256=sha(archive_path), bytes=archive_path.stat().st_size, members=len(inventory) + 1, generated_secrets_redacted=len(secrets)))
    prefix = ROOT / 'docs/verification' / ('resource-history-' + args.label)
    summary_path, zip_path = Path(str(prefix) + '-summary.json'), Path(str(prefix) + '-evidence.zip')
    if summary_path.exists() or zip_path.exists(): raise RuntimeError('Refusing to replace existing evidence')
    content = json.dumps(summary, ensure_ascii=False, indent=2) + '\n'
    (destination / 'manifest.json').write_text(content)
    summary_path.write_text(content); shutil.copy2(archive_path, zip_path)
    print(json.dumps(dict(delivery=str(destination), tests=totals, evidence=summary['evidence'])))


if __name__ == '__main__':
    main()
