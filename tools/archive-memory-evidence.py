"""Archive stopped real memory runs, including failures and inherited build provenance."""
from datetime import datetime, timezone
from pathlib import Path
import argparse
import hashlib
import importlib.util
import json
import re
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('memory_inspector', Path(__file__).with_name('inspect-memory-paper.py'))
INSPECTOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(INSPECTOR)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def git(root, *args):
    return subprocess.check_output(['git', '-C', str(root), *args], text=True).strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run', type=Path, required=True)
    parser.add_argument('--offline-run', type=Path, required=True)
    parser.add_argument('--failed-run', type=Path, required=True)
    parser.add_argument('--native', type=Path, required=True)
    parser.add_argument('--regression-log', type=Path, action='append', required=True)
    parser.add_argument('--log', type=Path, action='append', default=[])
    parser.add_argument('--label', default='memory-recipes-paper-20261125')
    args = parser.parse_args()
    if not re.fullmatch('[a-z0-9-]+', args.label):
        raise SystemExit('Invalid archive label')
    runs = [args.run.resolve(), args.offline_run.resolve()]
    accepted = [INSPECTOR.inspect(run) for run in runs]
    if [row['mode'] for row in accepted] != ['main', 'offline']:
        raise SystemExit('Require both actual main and offline scenarios')
    if any(row['chronicle_sha256'] != accepted[0]['chronicle_sha256'] or
           row['starcore_sha256'] != accepted[0]['starcore_sha256'] for row in accepted):
        raise SystemExit('Accepted artifacts differ between scenarios')
    native = args.native.resolve()
    if git(ROOT, 'status', '--porcelain') or git(native, 'status', '--porcelain'):
        raise SystemExit('Commit source/tool changes before recording provenance')
    references = [ROOT / 'docs/verification' / name for name in (
        'production-stack-20261125-summary.json', 'national-epic-resource-history-20261125-summary.json')]
    production, resource = [json.loads(path.read_text()) for path in references]
    if production['acceptance']['starcore_sha256'] != accepted[0]['starcore_sha256'] or resource['chronicle']['jar']['sha256'] != accepted[0]['chronicle_sha256']:
        raise SystemExit('Inherited build evidence does not match the tested artifacts')
    for root, commit in ((ROOT, resource['chronicle']['head']), (native, production['native_head'])):
        if git(root, 'diff', commit, 'HEAD', '--', 'src', 'pom.xml'):
            raise SystemExit('Product source differs from inherited build provenance')
    failed = args.failed_run.resolve()
    failures = [row for row in INSPECTOR.records(failed / 'evidence/epic-fixture.jsonl') if row['kind'] == 'FAILED']
    if not failures:
        raise SystemExit('Preserve the actual failed first attempt')
    regressions = []
    for path in args.regression_log:
        raw = path.read_text()
        ran = re.search(r'^Ran (\d+) tests? in ', raw, re.M)
        success = re.search(r'^OK(?: \(skipped=(\d+)\))?$', raw, re.M)
        if not ran or not success or re.search(r'^(?:FAILED|ERROR:|FAIL:)', raw, re.M):
            raise SystemExit('Evidence regression has not passed: ' + str(path))
        count, skipped = int(ran[1]), int(success[1] or 0)
        regressions.append(dict(log=path.name, tests=count, passed=count-skipped, skipped=skipped, sha256=sha(path)))
    summary = dict(status='ISOLATED_PAPER_MEMORY_ACCEPTED', captured_utc=datetime.now(timezone.utc).isoformat(),
                   chronicle_head=git(ROOT, 'rev-parse', 'HEAD'), native_head=git(native, 'rev-parse', 'HEAD'),
                   runs=[dict(directory=run.name, **result) for run, result in zip(runs, accepted)],
                   regressions=regressions, failed_run=dict(directory=failed.name, failures=failures),
                   inherited_builds=dict(note='No product rebuild in this acceptance increment; reuse the frozen matching artifacts.',
                       chronicle=resource['chronicle']['tests']['totals'], native=production['native_tests']['totals'],
                       references={path.name: sha(path) for path in references}))
    destination = ROOT / 'target' / args.label
    destination.mkdir(parents=True, exist_ok=False)
    for plugin in ('StarCore', 'StarChronicle'):
        shutil.copy2(runs[0] / 'plugins' / (plugin + '.jar'), destination / (plugin + '.jar'))
    for root, name in ((ROOT, 'starchronicle-source.tar.gz'), (native, 'starcore-source.tar.gz')):
        with (destination / name).open('xb') as output:
            subprocess.run(['git', '-C', str(root), 'archive', '--format=tar.gz', 'HEAD'], stdout=output, check=True)
    archive_path = destination / 'evidence.zip'
    with zipfile.ZipFile(archive_path, 'x', zipfile.ZIP_DEFLATED) as archive:
        def add(path, name):
            if path.is_symlink():
                raise SystemExit('Refusing symlink evidence: ' + str(path))
            if path.suffix in ('.yml', '.yaml'):
                raw = re.sub(r'(?m)^(\s*(?:access-secret|password|api-key|mineskin_api_key):)\s*.*$',
                             r'\1 "REDACTED_TEST_SECRET"', path.read_text())
                archive.writestr(name, raw)
            else:
                archive.write(path, name)
        for run in [*runs, failed]:
            files = [run / 'preparation.json', run / 'server.properties', run / 'world/uid.dat']
            files += [path for path in (run / 'evidence').rglob('*') if path.is_file()]
            files += [path for path in (run / 'fixture').iterdir() if path.suffix in ('.java', '.yml', '.yaml', '.json')]
            for plugin in ('StarCore', 'StarChronicle', 'EpicCampaignPaperFixture'):
                directory = run / 'plugins' / plugin
                files += [path for path in directory.rglob('*') if path.suffix in ('.yml', '.yaml', '.json', '.db') and path.is_file()]
            for path in sorted(set(files)):
                if path.is_file():
                    add(path, 'runs/' + run.name + '/' + path.relative_to(run).as_posix())
        for path in [*args.regression_log, *args.log]:
            add(path, 'logs/' + path.name)
        for path in references:
            add(path, 'inherited-builds/' + path.name)
        for name in ('EpicCampaignPaperFixture.java', 'ProductionStackPaperFixture.java', 'MemoryRecipePaperFixture.java',
                     'prepare-epic-paper.py', 'prepare-production-paper.py', 'prepare-memory-paper.py',
                     'epic-paper-session.py', 'epic-paper-clients.cjs', 'inspect-memory-paper.py',
                     'inspect-production-paper.py', 'inspect-trade-paper.py', 'inspect-epic-paper.py',
                     'inspect-quest-evidence-paper.py', 'test_memory_acceptance.py', 'archive-memory-evidence.py'):
            add(ROOT / 'tools' / name, 'tools/' + name)
        for path in (ROOT / 'examples/memory-recipes').iterdir():
            if path.is_file():
                add(path, 'examples/' + path.name)
        add(ROOT / 'docs/verification/production-stack-candidate-20261125.json', 'production-stack-lock.json')
        archive.writestr('source-provenance.json', json.dumps(summary, ensure_ascii=False, indent=2) + '\n')
        inventory = {entry.filename: hashlib.sha256(archive.read(entry.filename)).hexdigest() for entry in archive.infolist()}
        archive.writestr('evidence-manifest.json', json.dumps(inventory, indent=2) + '\n')
    with zipfile.ZipFile(archive_path) as archive:
        if archive.testzip() is not None or any(hashlib.sha256(archive.read(name)).hexdigest() != digest for name, digest in inventory.items()):
            raise SystemExit('Archive failed its member integrity check')
    summary['evidence_archive'] = dict(sha256=sha(archive_path), bytes=archive_path.stat().st_size, members=len(inventory))
    summary['source_archives'] = {name: sha(destination / name) for name in ('starchronicle-source.tar.gz', 'starcore-source.tar.gz')}
    text = json.dumps(summary, ensure_ascii=False, indent=2) + '\n'
    (destination / 'manifest.json').write_text(text)
    output = ROOT / 'docs/verification'
    for name in (args.label + '-evidence.zip', args.label + '-summary.json'):
        if (output / name).exists():
            raise SystemExit('Refusing to overwrite an earlier archive: ' + name)
    shutil.copy2(archive_path, output / (args.label + '-evidence.zip'))
    (output / (args.label + '-summary.json')).write_text(text)
    print(json.dumps(dict(delivery=str(destination), archive=summary['evidence_archive'], regressions=regressions), ensure_ascii=False))


if __name__ == '__main__':
    main()
