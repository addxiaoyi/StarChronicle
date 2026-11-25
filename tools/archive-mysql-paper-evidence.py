"""Archive real Paper/MySQL sessions, exact audit projections, restarts and retained failures."""
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
SPEC = importlib.util.spec_from_file_location('mysql_paper_auditor', ROOT / 'tools/inspect-mysql-paper.py')
I = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(I)


def git(root, *arguments):
    return subprocess.check_output(['git', '-C', str(root), *arguments], text=True).strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run', type=Path, action='append', required=True)
    parser.add_argument('--failed-run', type=Path, action='append', required=True)
    parser.add_argument('--log', type=Path, action='append', default=[])
    parser.add_argument('--regression-log', type=Path, action='append', required=True)
    parser.add_argument('--native', type=Path, required=True)
    parser.add_argument('--instance', type=Path, required=True)
    parser.add_argument('--label', default='mysql-paper-20261125')
    args = parser.parse_args()
    assert re.fullmatch('[a-z0-9-]+', args.label)
    assert not git(ROOT, 'status', '--porcelain'), 'Commit tooling before archiving'
    native = args.native.resolve()
    product_sources = dict(chronicle='2f800988fbdd99447974e891a0623b39d7ed028a', native='59ed52abbed5521eff6383df004985b1e13e1bbe')
    assert not git(ROOT, 'diff', product_sources['chronicle'], 'HEAD', '--', 'src', 'pom.xml')
    assert not git(native, 'diff', product_sources['native'], 'HEAD', '--', 'src/main', 'pom.xml')
    runs = [p.resolve() for p in args.run]
    assert len(runs) == len(set(runs)) == 5
    accepted = [I.inspect(run) for run in runs]
    manifests = [I.read(run / 'preparation.json') for run in runs]
    assert len({m['mysql_acceptance']['database'] for m in manifests}) == 5
    kinds = [('production-' + m['nation_production_acceptance']['mode']) if 'nation_production_acceptance' in m else
             'trade' if 'nation_trade_acceptance' in m else m['route'] for m in manifests]
    assert set(kinds) == {'production-main', 'production-offline', 'trade', 'DUNGEON', 'WAR'}
    assert sum(result['business']['boots'] for result in accepted) == 14
    assert sum(result['storage']['normal_database_restarts'] for result in accepted) == 9
    assert all(m[key] == manifests[0][key] for m in manifests for key in ('plugin_sha256', 'starcore_sha256'))
    fixture = I.read(args.instance)
    shutdown = I.read(args.instance.parent / 'shutdown.json')
    assert shutdown['container_id'] == fixture['id'] and shutdown['state']['ExitCode'] == 0 and not shutdown['state']['Running'] and not shutdown['state']['OOMKilled']
    assert all(m['mysql_acceptance']['container_id'] == fixture['id'] for m in manifests)
    assert all(process['exited_at'] < shutdown['started_at'] for run in runs for session in (run / 'evidence').glob('boot*.session.json') for process in I.read(session)['processes'])
    regressions = []
    for path in args.regression_log:
        raw = path.read_text()
        count = re.search(r'^Ran (\d+) tests in ', raw, re.M)
        ok = re.search(r'^OK(?: \(skipped=(\d+)\))?$', raw, re.M)
        assert count and ok and not re.search(r'^(FAILED|ERROR:|FAIL:)', raw, re.M), 'Regression log did not pass: ' + str(path)
        skipped = int(ok[1] or 0)
        regressions.append(dict(log=str(path), tests=int(count[1]), passed=int(count[1])-skipped, skipped=skipped, sha256=I.M.sha(path)))
    failures = []
    for run in args.failed_run:
        sessions = [I.read(p) for p in sorted((run / 'evidence').glob('boot*.session.json'))]
        assert sessions and any(s['status'] == 'FAILED' for s in sessions)
        assert all(p['exit_code'] == 0 for s in sessions for p in s['processes'])
        failures.append(dict(directory=run.name, sessions=sessions))
    summary = dict(status='MYSQL_PAPER_PRODUCTION_OFFLINE_TRADE_DUNGEON_WAR_ACCEPTED', captured_utc=datetime.now(timezone.utc).isoformat(),
                   chronicle_head=git(ROOT, 'rev-parse', 'HEAD'), native_head=git(native, 'rev-parse', 'HEAD'), product_sources=product_sources,
                   product_unchanged=True, native_storage='MySQL 8.4.13 / InnoDB', chronicle_storage='SQLite',
                   audit_projection='Every native starcore.db in this evidence is an explicit derived SQLite audit projection, compared column by column to raw MySQL exports; it was not native server storage.',
                   instance=fixture, normal_shutdown=shutdown, runs=[dict(directory=r.name, **a) for r, a in zip(runs, accepted)], regressions=regressions, failed_runs=failures,
                   inherited_builds=dict(chronicle_tests=979, native_tests=7354, native_skipped=21, source='nation-production-paper-20261125-summary.json', rerun_in_this_increment=False),
                   limitations=['Normal database and game restarts only; no power loss, live database outage, backup restore, replication or long-term load claim.',
                                'Dedicated loopback MySQL uses encrypted TLS without remote host identity verification.',
                                'Selected Java21/Paper1.21.11 and frozen third-party plugins only; no full Folia or production deployment.',
                                'Fixture seeds initial nations/assets, disables founding charges and creates a three-second survival dungeon and two strongholds; battlefield result is menu-based 100:0 score victory, not PvP/siege/territory transfer.',
                                'Base-table data and column metadata plus view definitions are audited; the SQLite projection does not preserve native indexes, constraints, collations or executable views.',
                                'Legacy factory/wallet/market recovery and remaining ten-stage work remain open.'])
    files = {}
    redactions = []
    def add(path, name):
        assert path.is_file() and not path.is_symlink() and name not in files
        data = path.read_bytes()
        if path.suffix in ('.yml', '.yaml'):
            def redact(match):
                if match[2].strip() in ('', "''", '""', '~', 'null'):
                    return match[0]
                redactions.append(name)
                return match[1] + ' "REDACTED_TEST_SECRET"'
            data = re.sub(r'(?m)^(\s*(?:access-secret|password|api-key|mineskin_api_key):)([^\n]*)$', redact, data.decode()).encode()
        files[name] = data
    for run in [*runs, *[p.resolve() for p in args.failed_run]]:
        paths = [run / 'preparation.json', run / 'server.properties']
        paths += [p for p in (run / 'evidence').rglob('*') if p.is_file() and '__pycache__' not in p.parts]
        paths += [p for p in (run / 'fixture').iterdir() if p.suffix in ('.java', '.yml', '.yaml', '.json')]
        for plugin in ('StarCore', 'StarChronicle', 'EpicCampaignPaperFixture'):
            paths += [p for p in (run / 'plugins' / plugin).rglob('*') if p.is_file() and (p.suffix in ('.db', '.yml', '.yaml', '.json', '.properties') or 'quest' in p.relative_to(run / 'plugins' / plugin).parts or p.name.startswith('capital-designations.log'))]
        for path in sorted(set(paths)):
            add(path, 'runs/' + run.name + '/' + path.relative_to(run).as_posix())
    for name in ('preparation.json', 'shutdown.json', 'mysql-server.log', 'catalog-setup.log'):
        add(args.instance.parent / name, 'mysql-instance/' + name)
    for path in dict.fromkeys([*args.log, *args.regression_log]):
        assert path.resolve().is_relative_to(ROOT) or path.resolve().is_relative_to(Path('/tmp'))
        name = path.relative_to(ROOT).as_posix() if path.is_absolute() and path.is_relative_to(ROOT) else path.as_posix().lstrip('/')
        add(path, 'logs/' + name)
    for path in sorted((ROOT / 'tools').iterdir()):
        if path.is_file() and path.suffix in ('.py', '.java', '.cjs'):
            add(path, 'tools/' + path.name)
    for name in ('production-stack-candidate-20261125.json', 'nation-production-paper-20261125-summary.json', 'mysql-settlement-20261125-summary.json'):
        add(ROOT / 'docs/verification' / name, 'previous/' + name)
    summary['redacted_files'] = sorted(set(redactions))
    files['source-provenance.json'] = (json.dumps(summary, ensure_ascii=False, indent=2) + '\n').encode()
    secret_values = [line.split('=', 1)[1].encode() for line in (args.instance.parent / 'mysql.env').read_text().splitlines() if line.startswith(('MYSQL_PASSWORD=', 'MYSQL_ROOT_PASSWORD='))]
    assert len(secret_values) == 2 and all(secret_values)
    assert all(secret not in data for data in files.values() for secret in secret_values), 'Private credential in evidence'
    out = ROOT / 'target' / args.label
    out.mkdir(parents=True, exist_ok=False)
    for plugin in ('StarCore', 'StarChronicle'):
        shutil.copy2(runs[0] / 'plugins' / (plugin + '.jar'), out / (plugin + '.jar'))
    for root, name in ((ROOT, 'starchronicle-source.tar.gz'), (native, 'starcore-source.tar.gz')):
        with (out / name).open('xb') as f:
            subprocess.run(['git', '-C', str(root), 'archive', '--format=tar.gz', 'HEAD'], stdout=f, check=True)
    inventory = {name: hashlib.sha256(data).hexdigest() for name, data in files.items()}
    archive_path = out / 'evidence.zip'
    with zipfile.ZipFile(archive_path, 'x', zipfile.ZIP_DEFLATED) as archive:
        for name, data in files.items():
            archive.writestr(name, data)
        archive.writestr('evidence-manifest.json', json.dumps(inventory, indent=2) + '\n')
    with zipfile.ZipFile(archive_path) as archive:
        assert archive.testzip() is None and all(hashlib.sha256(archive.read(n)).hexdigest() == h for n, h in inventory.items())
    summary['archive'] = dict(sha256=I.M.sha(archive_path), bytes=archive_path.stat().st_size, members=len(inventory))
    summary['source_archives'] = {name: I.M.sha(out / name) for name in ('starchronicle-source.tar.gz', 'starcore-source.tar.gz')}
    data = json.dumps(summary, ensure_ascii=False, indent=2) + '\n'
    (out / 'manifest.json').write_text(data)
    destination = ROOT / 'docs/verification'
    assert not (destination / (args.label + '-evidence.zip')).exists() and not (destination / (args.label + '-summary.json')).exists()
    shutil.copy2(archive_path, destination / (args.label + '-evidence.zip'))
    (destination / (args.label + '-summary.json')).write_text(data)
    print(json.dumps(dict(archive=summary['archive'], delivery=str(out), regressions=regressions), ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
