"""Freeze native trade acceptance, real build reports, failure attempts and source fingerprints."""
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
    parser.add_argument('--failed-run', type=Path, required=True)
    parser.add_argument('--native', type=Path, required=True)
    parser.add_argument('--label', required=True)
    args = parser.parse_args()
    run, failed, native = args.run.resolve(), args.failed_run.resolve(), args.native.resolve()
    for path in (run, failed):
        if path.parent != (ROOT / 'run').resolve() or not path.name.startswith('paper-trade-'):
            raise RuntimeError('Require isolated trade acceptance directories')
    if not re.fullmatch(r'[a-z0-9-]+', args.label):
        raise RuntimeError('Invalid archive label')
    if git(native, 'status', '--porcelain'):
        raise RuntimeError('Native source must be committed and clean')
    manifest = json.loads((run / 'preparation.json').read_text())
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', manifest['port']))
    spec = importlib.util.spec_from_file_location('trade_inspect', ROOT / 'tools/inspect-trade-history.py')
    inspector = importlib.util.module_from_spec(spec); spec.loader.exec_module(inspector)
    accepted = inspector.inspect(run)
    if sha(native / 'target/starcore-1.0.0.jar') != manifest['native_sha256']:
        raise RuntimeError('Native build differs from tested artifact')
    suites = []
    for path in sorted((native / 'target/surefire-reports').glob('TEST-*.xml')):
        value = ET.parse(path).getroot()
        suites.append(dict(name=value.get('name'), file=path.name, sha256=sha(path),
                           **{key: int(value.get(key, '0')) for key in ('tests', 'failures', 'errors', 'skipped')}))
    totals = {key: sum(suite[key] for suite in suites) for key in ('tests', 'failures', 'errors', 'skipped')}
    if not suites or totals['failures'] or totals['errors']:
        raise RuntimeError('Require passing native build reports')
    build_log = native / 'target/trade-native-verify-20261126-1.log'
    if 'BUILD SUCCESS' not in build_log.read_text():
        raise RuntimeError('Missing successful verify log')
    files = {}
    for attempt in (run, failed):
        for path in [attempt / 'preparation.json', attempt / 'server.properties', attempt / 'fixture/TradeHistoryPaperFixture.java']:
            files[attempt.name + '/' + path.relative_to(attempt).as_posix()] = path
        for path in (attempt / 'evidence').rglob('*'):
            if path.is_file():
                files[attempt.name + '/' + path.relative_to(attempt).as_posix()] = path
    source_files = git(native, 'ls-files', '--', 'src', 'pom.xml').splitlines()
    for name in source_files:
        files['native-source/' + name] = native / name
    for suite in suites:
        files['native-surefire/' + suite['file']] = native / 'target/surefire-reports' / suite['file']
    files['native-doc/COMMITTED-TRADE-HISTORY-2026-11-26.md'] = native / 'docs/COMMITTED-TRADE-HISTORY-2026-11-26.md'
    for pattern in ('trade-journal-tests-*.log', 'trade-settlement-tests-*.log', 'trade-native-verify-*.log'):
        for path in (native / 'target').glob(pattern):
            files['logs/native/' + path.name] = path
    for path in (ROOT / 'target').glob('trade-evidence-tests-*.log'):
        files['logs/chronicle/' + path.name] = path
    for name in ('TradeHistoryPaperFixture.java', 'trade-history-paper.py', 'inspect-trade-history.py', 'test_trade_history.py', 'archive-trade-evidence.py'):
        files['tools/' + name] = ROOT / 'tools' / name
    secrets = set()
    for path in files.values():
        if path.name == 'config.yml' and path.parent.name == 'StarCore':
            secrets.update(value.strip().strip('"\'') for value in re.findall(r'(?m)^\s*access-secret:\s*(.*)$', path.read_text()))
    with zipfile.ZipFile(run / 'plugins/StarCore.jar') as jar:
        defaults = {value.strip().strip('"\'') for value in re.findall(r'(?m)^\s*access-secret:\s*(.*)$', jar.read('config.yml').decode())}
    secrets -= defaults | {'', 'REDACTED_TEST_SECRET'}
    destination = ROOT / 'target' / ('native-trade-history-' + args.label)
    destination.mkdir(exist_ok=False)
    for name in ('StarCore', 'StarChronicle', 'TradeHistoryPaperFixture'):
        shutil.copy2(run / f'plugins/{name}.jar', destination / f'{name}.jar')
    archive_path = destination / 'evidence.zip'
    with zipfile.ZipFile(archive_path, 'x', zipfile.ZIP_DEFLATED) as archive:
        for name, path in sorted(files.items()):
            if path.is_symlink():
                raise RuntimeError('Evidence symlink rejected')
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
            raise RuntimeError('ZIP content verification failed')
    summary = dict(captured_utc=datetime.now(timezone.utc).isoformat(), native_commit=git(native, 'rev-parse', 'HEAD'),
                   chronicle_commit=git(ROOT, 'rev-parse', 'HEAD'), accepted=accepted, failed_attempt=failed.name,
                   native_tests=dict(totals=totals, suites=suites), native_source_files={name: sha(native / name) for name in source_files},
                   chronicle_note='Product e53a35d8 unchanged; prior complete 905 tests not rerun for native-only changes.',
                   limitations=['Offline fixture accounts and real native services; no online player commands or GUI acceptance.',
                                'Actual quick buy, MySQL, third-party economy, full epic plugin combination and Folia not verified here.',
                                'Unmatched market escrow crash recovery and manual resolution remain incomplete.',
                                'Chronicle trade source, contribution and reversal projection remain to implement.'],
                   evidence=dict(sha256=sha(archive_path), bytes=archive_path.stat().st_size, members=len(inventory) + 1, generated_secrets_redacted=len(secrets)))
    target = ROOT / 'docs/verification' / ('native-trade-history-' + args.label)
    summary_path, zip_path = Path(str(target) + '-summary.json'), Path(str(target) + '-evidence.zip')
    if summary_path.exists() or zip_path.exists():
        raise RuntimeError('Refusing to replace published evidence')
    content = json.dumps(summary, ensure_ascii=False, indent=2) + '\n'
    (destination / 'manifest.json').write_text(content)
    summary_path.write_text(content); shutil.copy2(archive_path, zip_path)
    print(json.dumps(dict(delivery=str(destination), tests=totals, evidence=summary['evidence'])))


if __name__ == '__main__':
    main()
