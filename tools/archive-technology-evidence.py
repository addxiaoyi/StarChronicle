"""Archive stopped technology acceptance, exact source hashes and actual Maven reports."""
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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run', type=Path, required=True)
    parser.add_argument('--label', required=True)
    parser.add_argument('--log', type=Path, action='append', default=[])
    args = parser.parse_args()
    run = args.run.resolve()
    if run.parent != (ROOT / 'run').resolve() or not run.name.startswith('paper-technology-') or not re.fullmatch(r'[a-z0-9-]+', args.label):
        raise RuntimeError('Require isolated run and simple archive label')
    destination = ROOT / 'target' / ('technology-history-' + args.label)
    destination.mkdir(exist_ok=False)
    manifest = json.loads((run / 'preparation.json').read_text())
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', manifest['port']))
    spec = importlib.util.spec_from_file_location('technology_inspect', ROOT / 'tools/inspect-technology-history.py')
    inspector = importlib.util.module_from_spec(spec); spec.loader.exec_module(inspector)
    accepted = inspector.inspect(run)
    if sha(ROOT / 'target/starchronicle-0.1.0-SNAPSHOT.jar') != manifest['product_sha256']:
        raise RuntimeError('Current build does not match accepted run')
    suites = []
    for path in sorted((ROOT / 'target/surefire-reports').glob('TEST-*.xml')):
        result = ET.parse(path).getroot()
        suites.append(dict(name=result.get('name'), path=path.relative_to(ROOT).as_posix(), sha256=sha(path),
                           **{key: int(result.get(key, '0')) for key in ('tests', 'failures', 'errors', 'skipped')}))
    totals = {key: sum(s[key] for s in suites) for key in ('tests', 'failures', 'errors', 'skipped')}
    if not suites or any(totals[key] for key in ('failures', 'errors', 'skipped')):
        raise RuntimeError('Require complete successful current Maven reports')
    git = lambda *values: subprocess.check_output(['git', '-C', str(ROOT), *values], text=True).strip()
    source_files = sorted(set(git('ls-files', '-c', '-o', '--exclude-standard', '--', 'src', 'pom.xml').splitlines()))
    summary = dict(captured_utc=datetime.now(timezone.utc).isoformat(), head=git('rev-parse', 'HEAD'),
                   worktree_status=git('status', '--porcelain'), accepted=accepted,
                   tests=dict(totals=totals, suites=suites), source_files={name: sha(ROOT / name) for name in source_files},
                   native_note='Frozen 44f450ec artifact; native production code and prior 90/7202 test evidence unchanged; native tests not rerun here.',
                   limitations=['Isolated Paper administration via real native services; no player clients or actual research completion.',
                                'This fingerprint has technology acceptance; the selected five-plugin epic combination remains a separate 5758e54b checkpoint.',
                                'No production deployment, all-source history, trade contribution or full-stack Folia claim.'])
    for filename in ('StarChronicle.jar', 'StarCore.jar', 'TechnologyHistoryPaperFixture.jar'):
        shutil.copy2(run / 'plugins' / filename, destination / filename)
    files = {run / 'preparation.json', run / 'server.properties', run / 'fixture/TechnologyHistoryPaperFixture.java'}
    files.update(p for p in (run / 'evidence').rglob('*') if p.is_file())
    files.update(p for p in (run / 'plugins/StarChronicle').rglob('*') if p.is_file())
    files.update((run / f'plugins/{name}/config.yml') for name in ('StarCore', 'TechnologyHistoryPaperFixture'))
    secrets = set()
    for path in files:
        if path.name == 'config.yml' and path.parent.name == 'StarCore':
            secrets.update(value.strip().strip('"\'') for value in re.findall(r'(?m)^\s*access-secret:\s*(.*)$', path.read_text()))
    with zipfile.ZipFile(run / 'plugins/StarCore.jar') as native:
        defaults = {value.strip().strip('"\'') for value in re.findall(r'(?m)^\s*access-secret:\s*(.*)$', native.read('config.yml').decode())}
    secrets -= defaults | {'', 'REDACTED_TEST_SECRET'}
    archive_path = destination / 'evidence.zip'
    with zipfile.ZipFile(archive_path, 'x', zipfile.ZIP_DEFLATED) as archive:
        def add(path, name, redact=False):
            if path.is_symlink():
                raise RuntimeError('Evidence cannot contain symlinks')
            content = path.read_bytes()
            if redact:
                content = re.sub(r'(?m)^(\s*access-secret:)\s*.*$', r'\1 "REDACTED_TEST_SECRET"', content.decode()).encode()
            if any(value.encode() in content for value in secrets):
                raise RuntimeError('Generated test secret remained in ' + name)
            archive.writestr(name, content)
        for path in sorted(files):
            add(path, run.name + '/' + path.relative_to(run).as_posix(), path.name == 'config.yml' and path.parent.name == 'StarCore')
        for name in source_files:
            add(ROOT / name, 'source/' + name)
        for suite in suites:
            add(ROOT / suite['path'], 'surefire/' + Path(suite['path']).name)
        for index, path in enumerate(args.log):
            add(path, f'logs/{index}-{path.name}')
        for name in ('TechnologyHistoryPaperFixture.java', 'technology-history-paper.py', 'inspect-technology-history.py', 'test_technology_history.py', 'archive-technology-evidence.py'):
            add(ROOT / 'tools' / name, 'tools/' + name)
        inventory = {entry.filename: hashlib.sha256(archive.read(entry)).hexdigest() for entry in archive.infolist()}
        archive.writestr('evidence-manifest.json', json.dumps(inventory, indent=2) + '\n')
    with zipfile.ZipFile(archive_path) as archive:
        if archive.testzip() is not None:
            raise RuntimeError('Evidence ZIP integrity failed')
        for name, digest in inventory.items():
            if hashlib.sha256(archive.read(name)).hexdigest() != digest:
                raise RuntimeError('Evidence member hash mismatch')
    summary['evidence'] = dict(sha256=sha(archive_path), bytes=archive_path.stat().st_size, members=len(inventory) + 1,
                               generated_secrets_redacted=len(secrets))
    summary_path = ROOT / 'docs/verification' / ('technology-history-' + args.label + '-summary.json')
    target_zip = summary_path.with_name('technology-history-' + args.label + '-evidence.zip')
    if summary_path.exists() or target_zip.exists():
        raise RuntimeError('Refusing to replace published evidence')
    content = json.dumps(summary, ensure_ascii=False, indent=2) + '\n'
    (destination / 'manifest.json').write_text(content)
    summary_path.write_text(content)
    shutil.copy2(archive_path, target_zip)
    print(json.dumps(dict(delivery=str(destination), tests=totals, evidence=summary['evidence'])))


if __name__ == '__main__':
    main()
