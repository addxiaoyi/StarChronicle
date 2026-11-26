"""Freeze trade contribution source, successful/failed checks, SQL snapshots and cross-device evidence."""
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
    args = parser.parse_args()
    run, native = args.run.resolve(), args.native.resolve()
    failed = [p.resolve() for p in args.failed_run]
    for path in [run, *failed]:
        if path.parent != (ROOT / 'run').resolve() or not path.name.startswith('paper-trade-contribution-'):
            raise RuntimeError('Require isolated contribution acceptance directories')
    if not re.fullmatch('[a-z0-9-]+', args.label):
        raise RuntimeError('Invalid archive label')
    if git(ROOT, 'status', '--porcelain', '--', 'src', 'pom.xml', 'docs/API.md') or git(native, 'status', '--porcelain'):
        raise RuntimeError('Product source must be committed and clean')
    manifest = json.loads((run / 'preparation.json').read_text())
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', manifest['port']))
    spec = importlib.util.spec_from_file_location('contribution_inspector', ROOT / 'tools/inspect-trade-contributions.py')
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
    if totals != dict(tests=925, failures=0, errors=0, skipped=0):
        raise RuntimeError('Unexpected full verification reports')
    verify = ROOT / 'target/trade-chronicle-verify-20261126-3.log'
    packaging = ROOT / 'target/trade-chronicle-package-20261126-1.log'
    if any('BUILD SUCCESS' not in p.read_text() for p in (verify, packaging)):
        raise RuntimeError('Missing successful build log')
    evidence_test = ROOT / 'target/trade-contribution-evidence-tests-20261126-1.log'
    if not re.search(r'Ran 13 tests in .*\n\nOK\s*$', evidence_test.read_text()):
        raise RuntimeError('Missing successful evidence regression log')
    files = {}
    for attempt in [run, *failed]:
        if attempt in failed:
            sessions = list((attempt / 'evidence').glob('boot*.session.json'))
            if not sessions or not any(json.loads(s.read_text()).get('passed') is False for s in sessions):
                raise RuntimeError('Failed attempt is not recorded as failed')
        for path in [attempt / 'preparation.json', attempt / 'server.properties', attempt / 'fixture/TradeContributionPaperFixture.java']:
            files[attempt.name + '/' + path.relative_to(attempt).as_posix()] = path
        for path in (attempt / 'evidence').rglob('*'):
            if path.is_file():
                files[attempt.name + '/' + path.relative_to(attempt).as_posix()] = path
    source_files = git(ROOT, 'ls-files', '--', 'src', 'pom.xml', 'docs/API.md').splitlines()
    for name in source_files:
        files['chronicle-source/' + name] = ROOT / name
    for suite in suites:
        files['surefire/' + suite['file']] = ROOT / 'target/surefire-reports' / suite['file']
    for path in (ROOT / 'target/trade-chronicle-failed-verification1-reports').glob('*'):
        if path.is_file(): files['failed-verification/' + path.name] = path
    for pattern in ('trade-chronicle-*.log', 'trade-contribution-*.log'):
        for path in (ROOT / 'target').glob(pattern):
            files['logs/' + path.name] = path
    for name in ('TradeContributionPaperFixture.java', 'trade-contribution-paper.py', 'inspect-trade-contributions.py',
                 'test_trade_contributions.py', 'archive-trade-contributions.py', 'inspect-trade-history.py'):
        files['tools/' + name] = ROOT / 'tools' / name
    secrets = set()
    for path in files.values():
        if path.name == 'config.yml' and path.parent.name == 'StarCore':
            secrets.update(v.strip().strip('"\'') for v in re.findall(r'(?m)^\s*access-secret:\s*(.*)$', path.read_text()))
    with zipfile.ZipFile(run / 'plugins/StarCore.jar') as jar:
        defaults = {v.strip().strip('"\'') for v in re.findall(r'(?m)^\s*access-secret:\s*(.*)$', jar.read('config.yml').decode())}
    secrets -= defaults | {'', 'REDACTED_TEST_SECRET'}
    destination = ROOT / 'target' / ('trade-contribution-' + args.label)
    destination.mkdir(exist_ok=False)
    for name in ('StarCore', 'StarChronicle', 'TradeContributionPaperFixture'):
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
        tests=dict(totals=totals, suites=suites, evidence_regressions=13), source_files={name: sha(ROOT / name) for name in source_files},
        build_note='Final Java code passed 925-test verify; then API.md was updated and packaged with -DskipTests. Sources JAR matches all 114 Java files; final API.md matches the product JAR.',
        limitations=['Offline fixture accounts using real native direct/quick-sell/market services; no online player command or GUI certification.',
            'Refund, dispute, restoration and cancellation are native administrative history corrections, not economic compensation acceptance.',
            'No latest-jar full epic combination, full-stack Folia, MySQL or third-party economy verification.',
            'Market unmatched escrow crash recovery, unknown quest safe new runs, capital/resource history and long-duration capacity remain open.'],
        evidence=dict(sha256=sha(archive_path), bytes=archive_path.stat().st_size, members=len(inventory) + 1, generated_secrets_redacted=len(secrets)))
    prefix = ROOT / 'docs/verification' / ('trade-contribution-' + args.label)
    summary_path, zip_path = Path(str(prefix) + '-summary.json'), Path(str(prefix) + '-evidence.zip')
    if summary_path.exists() or zip_path.exists(): raise RuntimeError('Refusing to replace existing evidence')
    content = json.dumps(summary, ensure_ascii=False, indent=2) + '\n'
    (destination / 'manifest.json').write_text(content)
    summary_path.write_text(content); shutil.copy2(archive_path, zip_path)
    print(json.dumps(dict(delivery=str(destination), tests=totals, evidence=summary['evidence'])))


if __name__ == '__main__':
    main()
