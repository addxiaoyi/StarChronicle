"""Freeze atomic processing source, successful/failed checks, SQL snapshots and cross-device evidence."""
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
        if path.parent != (ROOT / 'run').resolve() or not path.name.startswith('paper-processing-'):
            raise RuntimeError('Require isolated processing acceptance directories')
    if not re.fullmatch('[a-z0-9-]+', args.label):
        raise RuntimeError('Invalid archive label')
    if git(ROOT, 'status', '--porcelain', '--', 'src', 'pom.xml', 'docs/API.md') or git(native, 'status', '--porcelain'):
        raise RuntimeError('Product source must be committed and clean')
    manifest = json.loads((run / 'preparation.json').read_text())
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', manifest['port']))
    spec = importlib.util.spec_from_file_location('processing_inspector', ROOT / 'tools/inspect-processing-history.py')
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
    if totals != dict(tests=956, failures=0, errors=0, skipped=0):
        raise RuntimeError('Unexpected full verification reports')
    verify = ROOT / 'target/processing-chronicle-verify-20261126-3.log'
    packaging = native / 'target/processing-native-verify-20261126-2.log'
    if any('BUILD SUCCESS' not in p.read_text() for p in (verify, packaging)):
        raise RuntimeError('Missing successful build log')
    evidence_test = ROOT / 'target/processing-evidence-tests-20261126-1.log'
    if not re.search(r'Ran 28 tests in .*\n\nOK\s*$', evidence_test.read_text()):
        raise RuntimeError('Missing successful evidence regression log')
    upgrade = json.loads((ROOT / 'target/processing-schema-upgrade-2/inspection.json').read_text())
    if upgrade.get('status') != 'PASS' or upgrade.get('new_jar_sha256') != manifest['product_sha256'] or not upgrade.get('old_plugin_rejected'):
        raise RuntimeError('Missing real old-database upgrade and old-JAR rejection evidence')
    files = {}
    for attempt in [run, *failed, *prior]:
        if attempt in failed:
            sessions = list((attempt / 'evidence').glob('boot*.session.json'))
            if not sessions or not any(json.loads(s.read_text()).get('passed') is False for s in sessions):
                raise RuntimeError('Failed attempt is not recorded as failed')
        for path in [attempt / 'preparation.json', attempt / 'server.properties', attempt / 'fixture/ProcessingHistoryPaperFixture.java', attempt / 'fixture/processing-paper-clients.cjs']:
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
    if native_totals != dict(tests=7281, failures=0, errors=0, skipped=21):
        raise RuntimeError('Unexpected native full verification reports')
    for path in (ROOT / 'target/processing-chronicle-failed-2-reports').glob('*'):
        if path.is_file(): files['chronicle-failed-verification/' + path.name] = path
    for path in (ROOT / 'target/processing-schema-upgrade-2').rglob('*'):
        if path.is_file(): files['schema-upgrade/' + path.relative_to(ROOT / 'target/processing-schema-upgrade-2').as_posix()] = path
    old_database = ROOT / upgrade['source']
    if sha(old_database) != upgrade['source_sha256']:
        raise RuntimeError('Original schema9 database drifted')
    files['schema-upgrade/old-schema9.db'] = old_database
    for path in (native / 'target/processing-failed-1-reports').glob('*'):
        if path.is_file(): files['native-failed-tests/' + path.name] = path
    for path in (ROOT / 'target/processing-schema-upgrade').rglob('*'):
        if path.is_file(): files['schema-upgrade-initial/' + path.relative_to(ROOT / 'target/processing-schema-upgrade').as_posix()] = path
    for attempt in prior:
        inspector.inspect(attempt, intermediate=True)
    for base, prefix in ((ROOT, 'chronicle'), (native, 'native')):
        for path in (base / 'target').glob('processing-*.log'):
            files[prefix + '-logs/' + path.name] = path
    for name in ('ProcessingHistoryPaperFixture.java', 'processing-history-paper.py', 'inspect-processing-history.py',
                 'test_processing_history.py', 'archive-processing-evidence.py', 'ProcessingSchemaProbe.java',
                 'verify-processing-schema.py', 'processing-paper-clients.cjs', 'inspect-resource-history.py'):
        files['tools/' + name] = ROOT / 'tools' / name
    secrets = set()
    for path in files.values():
        if path.name == 'config.yml' and path.parent.name == 'StarCore':
            secrets.update(v.strip().strip('"\'') for v in re.findall(r'(?m)^\s*access-secret:\s*(.*)$', path.read_text()))
    with zipfile.ZipFile(run / 'plugins/StarCore.jar') as jar:
        defaults = {v.strip().strip('"\'') for v in re.findall(r'(?m)^\s*access-secret:\s*(.*)$', jar.read('config.yml').decode())}
    secrets -= defaults | {'', 'REDACTED_TEST_SECRET'}
    destination = ROOT / 'target' / ('processing-history-' + args.label)
    destination.mkdir(exist_ok=False)
    for name in ('StarCore', 'StarChronicle', 'ProcessingHistoryPaperFixture'):
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
        native_commit=git(native, 'rev-parse', 'HEAD'), accepted=accepted, failed_attempts=[p.name for p in failed], intermediate_native_attempts=[p.name for p in prior],
        tests=dict(totals=totals, suites=suites, evidence_regressions=28), source_files={name: sha(ROOT / name) for name in source_files},
        native_tests=dict(totals=native_totals, suites=native_suites), schema_upgrade=upgrade,
        native_source_files={name: sha(native / name) for name in native_sources},
        build_note='Chronicle final verify passed 956 tests; all 123 Java sources and API.md match tested artifacts. Native final verify passed 7281 tests (21 existing skips), API baseline and Spotless. Earlier native 7280-test build is separately archived. Native fixture setup failures, Chronicle JavaDoc typo compile failure and old schema assertion failures are retained.',
        limitations=['Actual loopback offline-mode non-OP player commands; not authenticated production identities.',
            'Final 137 processing and 139 resource receipts; 1 initial processing archive, 130 offline system runs; 12 contributions are separate original-player/original-nation projections of 3 factory completions, not 12 independent production runs.',
            'No latest-jar full five-plugin epic combination, full-stack Folia, MySQL, long-duration capacity or production verification.',
            'Legacy active factories without frozen receipts are preserved but cannot complete automatically; manual reconciliation/cancellation is not implemented. Factory build/upgrade are explicitly free, with no construction economy.',
            'Run-index startup scans historical STARTED rows; long-term scaling is not yet measured. Researcher history, unknown quest reconciliation and market escrow recovery remain open.'],
        evidence=dict(sha256=sha(archive_path), bytes=archive_path.stat().st_size, members=len(inventory) + 1, generated_secrets_redacted=len(secrets)))
    prefix = ROOT / 'docs/verification' / ('processing-history-' + args.label)
    summary_path, zip_path = Path(str(prefix) + '-summary.json'), Path(str(prefix) + '-evidence.zip')
    if summary_path.exists() or zip_path.exists(): raise RuntimeError('Refusing to replace existing evidence')
    content = json.dumps(summary, ensure_ascii=False, indent=2) + '\n'
    (destination / 'manifest.json').write_text(content)
    summary_path.write_text(content); shutil.copy2(archive_path, zip_path)
    print(json.dumps(dict(delivery=str(destination), tests=totals, evidence=summary['evidence'])))


if __name__ == '__main__':
    main()
