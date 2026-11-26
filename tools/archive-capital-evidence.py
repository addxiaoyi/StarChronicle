"""Freeze capital history source, successful/failed checks, SQL snapshots and cross-device evidence."""
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
    parser.add_argument('--native', type=Path, required=True)
    parser.add_argument('--label', required=True)
    parser.add_argument('--verify-log', type=Path, default=ROOT / 'target/verify.log')
    parser.add_argument('--native-verify-log', type=Path, required=True)
    parser.add_argument('--native-package-log', type=Path)
    parser.add_argument('--evidence-test-log', type=Path, required=True)
    args = parser.parse_args()
    run, native = args.run.resolve(), args.native.resolve()
    failed = [p.resolve() for p in args.failed_run]
    for path in [run, *failed]:
        if path.parent != (ROOT / 'run').resolve() or not path.name.startswith('paper-capital-'):
            raise RuntimeError('Require isolated capital acceptance directories')
    if not re.fullmatch('[a-z0-9-]+', args.label):
        raise RuntimeError('Invalid archive label')
    if git(ROOT, 'status', '--porcelain', '--', 'src', 'pom.xml', 'docs/API.md') or git(native, 'status', '--porcelain'):
        raise RuntimeError('Product source must be committed and clean')
    manifest = json.loads((run / 'preparation.json').read_text())
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', manifest['port']))
    spec = importlib.util.spec_from_file_location('capital_inspector', ROOT / 'tools/inspect-capital-history.py')
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
    verify = args.verify_log.resolve()
    native_verify = args.native_verify_log.resolve()
    packaging = args.native_package_log.resolve() if args.native_package_log else native_verify
    logs = [p.read_text(encoding='utf-8', errors='replace') for p in (verify, native_verify, packaging)]
    if any('BUILD SUCCESS' not in value or 'BUILD FAILURE' in value for value in logs):
        raise RuntimeError('Missing successful build log')
    def verified_totals(suites, totals, log):
        counts = re.findall(r'Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)(?:\r?\n|$)', log)
        completed = set(re.findall(r'Tests run: \d+, Failures: \d+, Errors: \d+, Skipped: \d+, Time elapsed: [^\r\n]+ -- in ([\w.$]+)', log))
        if not suites or not counts or tuple(totals.values()) != tuple(map(int, counts[-1])) or any(totals[k] for k in ('failures', 'errors')) or completed != {s['name'] for s in suites}:
            raise RuntimeError('Successful verify must match all archived XML suites and counts')
    verified_totals(suites, totals, logs[0])
    if totals['skipped']:
        raise RuntimeError('Chronicle verification has skipped tests')
    evidence_test = args.evidence_test_log.resolve()
    evidence_match = re.search(r'Ran (\d+) tests in .*\n\nOK\s*$', evidence_test.read_text())
    if not evidence_match or int(evidence_match[1]) < 1:
        raise RuntimeError('Missing successful evidence regression log')
    evidence_count = int(evidence_match[1])
    files = {'logs/chronicle-verify.log': verify, 'logs/native-verify.log': native_verify,
             'logs/native-package.log': packaging, 'logs/evidence-tests.log': evidence_test}
    for attempt in [run, *failed]:
        if attempt in failed:
            sessions = list((attempt / 'evidence').glob('boot*.session.json'))
            if not sessions or not any(json.loads(s.read_text()).get('passed') is False for s in sessions):
                raise RuntimeError('Failed attempt is not recorded as failed')
        for path in [attempt / 'preparation.json', attempt / 'server.properties', attempt / 'fixture/CapitalHistoryPaperFixture.java']:
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
    verified_totals(native_suites, native_totals, logs[1])
    for path in (native / 'target/capital-native-failed-verification1-reports').glob('*'):
        if path.is_file(): files['native-failed-verification/' + path.name] = path
    for base, prefix in ((ROOT, 'chronicle'), (native, 'native')):
        for path in (base / 'target').glob('capital-*.log'):
            files[prefix + '-logs/' + path.name] = path
    for name in ('CapitalHistoryPaperFixture.java', 'capital-history-paper.py', 'inspect-capital-history.py',
                 'test_capital_history.py', 'archive-capital-evidence.py'):
        files['tools/' + name] = ROOT / 'tools' / name
    secrets = set()
    for path in files.values():
        if path.name == 'config.yml' and path.parent.name == 'StarCore':
            secrets.update(v.strip().strip('"\'') for v in re.findall(r'(?m)^\s*access-secret:\s*(.*)$', path.read_text()))
    with zipfile.ZipFile(run / 'plugins/StarCore.jar') as jar:
        defaults = {v.strip().strip('"\'') for v in re.findall(r'(?m)^\s*access-secret:\s*(.*)$', jar.read('config.yml').decode())}
    secrets -= defaults | {'', 'REDACTED_TEST_SECRET'}
    destination = ROOT / 'target' / ('capital-history-' + args.label)
    destination.mkdir(exist_ok=False)
    for name in ('StarCore', 'StarChronicle', 'CapitalHistoryPaperFixture'):
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
        native_commit=git(native, 'rev-parse', 'HEAD'), accepted=accepted, failed_attempts=[p.name for p in failed],
        tests=dict(totals=totals, suites=suites, evidence_regressions=evidence_count), source_files={name: sha(ROOT / name) for name in source_files},
        native_tests=dict(totals=native_totals, suites=native_suites),
        native_source_files={name: sha(native / name) for name in native_sources},
        build_note=f'Chronicle verify passed {totals["tests"]} tests; source JAR and API.md match the product JAR. Native verify passed {native_totals["tests"]} tests ({native_totals["skipped"]} skipped). Exact completed suites and totals match the supplied verification logs; packaging and evidence regression logs are archived.',
        limitations=['Real native nation/city/designation and ownership transfer services with offline fixture accounts; no online client or command/GUI claim.',
            'First 3 events archive only; 130 changes committed while Chronicle disabled and recovered across 128-row paging.',
            'No latest-jar full epic combination, full-stack Folia, MySQL, third-party economy or production verification.',
            'Legacy migration and failure boundaries have Java tests; Paper scenario starts with a new capital authority, not an old production upgrade.',
            'Resource/researcher production history, unknown quest safe new runs, market escrow recovery and long-duration capacity remain open.'],
        evidence=dict(sha256=sha(archive_path), bytes=archive_path.stat().st_size, members=len(inventory) + 1, generated_secrets_redacted=len(secrets)))
    prefix = ROOT / 'docs/verification' / ('capital-history-' + args.label)
    summary_path, zip_path = Path(str(prefix) + '-summary.json'), Path(str(prefix) + '-evidence.zip')
    if summary_path.exists() or zip_path.exists(): raise RuntimeError('Refusing to replace existing evidence')
    content = json.dumps(summary, ensure_ascii=False, indent=2) + '\n'
    (destination / 'manifest.json').write_text(content)
    summary_path.write_text(content); shutil.copy2(archive_path, zip_path)
    print(json.dumps(dict(delivery=str(destination), tests=totals, evidence=summary['evidence'])))


if __name__ == '__main__':
    main()
