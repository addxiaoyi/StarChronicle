"""Archive exact treasury red/green evidence, full build and selected-stack regressions."""
from pathlib import Path
from datetime import datetime, timezone
import argparse
import hashlib
import importlib.util
import json
import os
import re
import shlex
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile
import yaml

ROOT = Path(__file__).resolve().parents[1]
NATIVE_PRODUCT = 'a8004d8470e93dcb4fef4887d7b31a8386738f9e'
CHRONICLE_PRODUCT = 'fc08038d5e9016ad04280552a3a641193c0971c4'
CASES = [
    ('treasury', 'inspect-treasury-writer-paper.py', 'test_treasury_writer_acceptance.py', 'STARCHRONICLE_TREASURY_WRITER_RUN'),
    ('trade', 'inspect-nation-trade-paper.py', 'test_nation_trade_acceptance.py', 'STARCHRONICLE_NATION_TRADE_RUN'),
    ('dungeon', 'inspect-epic-production-paper.py', 'test_epic_production_acceptance.py', 'STARCHRONICLE_EPIC_RUN'),
    ('war', 'inspect-battlefield-paper.py', 'test_battlefield_acceptance.py', 'STARCHRONICLE_EPIC_RUN'),
]


def require(value, message):
    if not value: raise ValueError(message)


def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def read(path): return json.loads(path.read_text())
def git(root, *args): return subprocess.check_output(['git', '-C', str(root), *args], text=True).strip()


def module(path):
    spec = importlib.util.spec_from_file_location(path.stem, path)
    result = importlib.util.module_from_spec(spec); spec.loader.exec_module(result)
    return result


def counts(path):
    suites = [ET.parse(p).getroot() for p in path.glob('TEST-*.xml')]
    require(suites, 'Missing actual Surefire reports')
    return {key: sum(int(s.get(key, 0)) for s in suites) for key in ('tests', 'failures', 'errors', 'skipped')}


def test_log(path):
    raw = path.read_text()
    count = re.search(r'^Ran (\d+) tests in ', raw, re.M)
    ok = re.search(r'^OK(?: \(skipped=(\d+)\))?$', raw, re.M)
    require(count and ok and not re.search(r'^(FAILED|ERROR:|FAIL:)', raw, re.M), 'Evidence test failure: ' + path.name)
    skipped = int(ok[1] or 0)
    return dict(tests=int(count[1]), passed=int(count[1]) - skipped, skipped=skipped, sha256=sha(path))


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name, *_ in CASES: p.add_argument('--' + name + '-run', type=Path, required=True)
    for name in ('native', 'red-run', 'private-env', 'treasury-test-log', 'regressions', 'red-tests', 'focused-tests'):
        p.add_argument('--' + name, type=Path, required=True)
    p.add_argument('--log', type=Path, action='append', default=[])
    p.add_argument('--label', default='treasury-writer-20261125')
    a = p.parse_args()
    require(re.fullmatch('[a-z0-9-]+', a.label), 'Invalid archive label')
    native = a.native.resolve(); frozen = ROOT / 'target/treasury-writer-frozen'
    for root, product in [(native, NATIVE_PRODUCT), (ROOT, CHRONICLE_PRODUCT)]:
        require(not git(root, 'status', '--porcelain'), 'Commit sources before archiving')
        require(not git(root, 'diff', product, 'HEAD', '--', 'src', 'pom.xml'), 'Frozen product source drift')
    runs = [getattr(a, name + '_run').resolve() for name, *_ in CASES]
    results = [module(ROOT / 'tools' / tool).inspect(run) for (_, tool, *_), run in zip(CASES, runs)]
    for result in results:
        require(all(result[key] == results[0][key] for key in ('chronicle_sha256', 'starcore_sha256')), 'Changed live pair')
    red = a.red_run.resolve()
    negative = module(ROOT / 'tools/inspect-treasury-writer-paper.py').inspect_negative(red, runs[0])
    pair = read(frozen / 'manifest.json')
    for plugin, key, product in [('StarCore', 'starcore_sha256', NATIVE_PRODUCT), ('StarChronicle', 'chronicle_sha256', CHRONICLE_PRODUCT)]:
        require(sha(frozen / (plugin + '.jar')) == pair[plugin]['sha256'] == results[0][key]
                and pair[plugin]['source_head'] == product, 'Frozen build provenance differs')
    builds = {}
    for name, total, skipped in [('native', 7472, 47), ('chronicle', 988, 0)]:
        value = counts(frozen / (name + '-surefire'))
        raw = (frozen / (name + '-verify.log')).read_text()
        require(value == dict(tests=total, failures=0, errors=0, skipped=skipped)
                and 'BUILD SUCCESS' in raw and 'BUILD FAILURE' not in raw, 'Full build evidence mismatch')
        require(re.search(r'Tests run: ' + str(total) + r', Failures: 0, Errors: 0, Skipped: ' + str(skipped), raw), 'Build log/XML counts differ')
        builds[name] = dict(counts=value, inherited=name == 'chronicle')
    require(counts(a.red_tests / 'surefire-reports') == dict(tests=7, failures=6, errors=0, skipped=0), 'Missing original red unit tests')
    require(counts(a.focused_tests / 'surefire-reports') == dict(tests=19, failures=0, errors=0, skipped=0), 'Missing focused green tests')
    mysql = native / 'target/treasury-writer-mysql'; sql = read(mysql / 'manifest.json')
    require(sql['exit_code'] == 0 and sql['tests'] == dict(tests=2, failures=0, errors=0, skipped=0), 'Missing actual MySQL treasury tests')
    for name, digest in sql['files'].items(): require(sha(mysql / name) == digest, 'Changed MySQL evidence')
    stopped = read(mysql / 'mysql-stopped.json')
    require(stopped['state']['Status'] == 'exited' and stopped['state']['ExitCode'] == 0
            and not stopped['state']['Running'] and not stopped['state']['OOMKilled'], 'Owned MySQL did not stop normally')
    require((mysql / 'runtime.txt').read_text() == '8.4.13\tREPEATABLE-READ\t1\t1\nInnoDB\n0\n', 'MySQL runtime/empty fixture mismatch')
    regressions = {name: test_log(a.treasury_test_log if name == 'treasury' else a.regressions / (name + '-evidence-tests.log')) for name, *_ in CASES}
    summary = dict(status='TREASURY_WRITER_AND_FROZEN_PAIR_SELECTED_STACK_ACCEPTED', captured_utc=datetime.now(timezone.utc).isoformat(),
        source_heads=dict(chronicle=git(ROOT, 'rev-parse', 'HEAD'), native=git(native, 'rev-parse', 'HEAD')),
        product_sources=dict(chronicle=CHRONICLE_PRODUCT, native=NATIVE_PRODUCT), frozen_pair=pair,
        builds=builds, focused_tests=19, red_unit_tests=dict(tests=7, failures=6), mysql=sql, mysql_shutdown=stopped,
        runs=[dict(kind=case[0], directory=run.name, **result) for case, run, result in zip(CASES, runs, results)],
        negative_run=dict(directory=red.name, **negative), regressions=regressions,
        limitation='This exact pair has twelve successful normal Paper boots and the retained unfixed failure. Earlier MySQL settlement/Paper/crash and factory runtime evidence is not transferred to this new native JAR.')
    dest = ROOT / 'target' / a.label; dest.mkdir(exist_ok=False)
    for plugin in ('StarCore', 'StarChronicle'): shutil.copy2(frozen / (plugin + '.jar'), dest / (plugin + '.jar'))
    for root, name in [(ROOT, 'starchronicle-source.tar.gz'), (native, 'starcore-source.tar.gz')]:
        with (dest / name).open('xb') as out: subprocess.run(['git', '-C', str(root), 'archive', '--format=tar.gz', 'HEAD'], stdout=out, check=True)
    pattern = r'(?m)^([ \t]*(?:access-secret|password|api-key|mineskin_api_key):)[ \t]*([^\n]*)$'
    public = set()
    for root in (ROOT, native):
        for match in re.finditer(pattern, (root / 'src/main/resources/config.yml').read_text()):
            value = yaml.safe_load(match[2])
            if isinstance(value, str) and value: public.add(value)
    secrets = set()
    for line in a.private_env.read_text().splitlines():
        if '=' not in line: continue
        key, value = line.split('=', 1)
        if re.search('password|secret', key, re.I):
            values = shlex.split(value)
            if values and len(values[0]) >= 8: secrets.add(values[0].encode())
    require(secrets, 'Private credential scan has no inputs')
    redactions = []; archive_path = dest / 'evidence.zip'
    with zipfile.ZipFile(archive_path, 'x', zipfile.ZIP_DEFLATED) as archive:
        def add(path, name):
            require(path.is_file() and not path.is_symlink() and name not in archive.namelist(), 'Unsafe/duplicate evidence: ' + name)
            raw = path.read_bytes()
            if path.suffix in ('.yml', '.yaml'):
                for match in re.finditer(pattern, raw.decode()):
                    value = yaml.safe_load(match[2])
                    if isinstance(value, str) and len(value) >= 16 and value not in public and value != 'REDACTED_TEST_SECRET': secrets.add(value.encode())
                text, count = re.subn(pattern, r'\1 "REDACTED_TEST_SECRET"', raw.decode()); raw = text.encode()
                if count: redactions.append(dict(path=name, fields=count))
            archive.writestr(name, raw)
        for run in [*runs, red]:
            files = [run / 'preparation.json', run / 'server.properties']
            files += [p for p in (run / 'evidence').rglob('*') if p.is_file()]
            files += [p for p in (run / 'fixture').glob('*') if p.suffix in ('.java', '.json', '.yml', '.yaml')]
            for plugin in ('StarCore', 'StarChronicle', 'EpicCampaignPaperFixture', 'Essentials'):
                folder = run / 'plugins' / plugin
                files += [p for p in folder.rglob('*') if p.is_file() and (p.suffix in ('.db', '.yml', '.yaml', '.json', '.properties')
                          or 'quest' in p.relative_to(folder).parts or p.name == 'authority-domain.txt' or p.name.startswith('capital-designations.log'))]
            for path in sorted(set(files)): add(path, 'runs/' + run.name + '/' + path.relative_to(run).as_posix())
        for name in ('native', 'chronicle'):
            add(frozen / (name + '-verify.log'), 'build/' + name + '/full-verify.log')
            for path in sorted((frozen / (name + '-surefire')).iterdir()):
                if path.is_file(): add(path, 'build/' + name + '/surefire/' + path.name)
        for folder, prefix in [(mysql, 'mysql'), (a.regressions, 'regressions'), (a.red_tests, 'red-unit'), (a.focused_tests, 'focused')]:
            for path in sorted(folder.rglob('*')):
                if path.is_file(): add(path, prefix + '/' + path.relative_to(folder).as_posix())
        for path in dict.fromkeys([a.treasury_test_log, *a.log]): add(path, 'logs/' + path.name)
        for path in sorted((ROOT / 'tools').iterdir()):
            if path.is_file() and path.suffix in ('.py', '.java', '.cjs'): add(path, 'tools/' + path.name)
        for relative in git(native, 'diff', '--name-only', NATIVE_PRODUCT + '^', NATIVE_PRODUCT, '--', 'src').splitlines():
            add(native / relative, 'native-source/' + relative)
        add(ROOT / 'docs/verification/production-stack-candidate-20261125.json', 'production-stack-lock.json')
        summary['redactions'] = redactions
        archive.writestr('source-provenance.json', json.dumps(summary, ensure_ascii=False, indent=2) + '\n')
        inventory = {info.filename: hashlib.sha256(archive.read(info.filename)).hexdigest() for info in archive.infolist()}
        archive.writestr('evidence-manifest.json', json.dumps(inventory, indent=2) + '\n')
    with zipfile.ZipFile(archive_path) as archive:
        require(archive.testzip() is None, 'Corrupt evidence archive')
        for name, digest in inventory.items():
            raw = archive.read(name)
            require(hashlib.sha256(raw).hexdigest() == digest, 'Archive member changed')
            require(not any(secret in raw for secret in secrets), 'Private credential appears in member: ' + name)
        extracted = dest / 'extracted-check'; extracted.mkdir()
        archive.extractall(extracted)
    for run in [*runs, red]:
        copy = extracted / 'runs' / run.name
        binaries = ['paper.jar', 'plugins/StarCore.jar', 'plugins/StarChronicle.jar', 'plugins/EpicCampaignPaperFixture.jar']
        binaries += ['plugins/' + row['filename'] for row in read(run / 'preparation.json')['production_stack']['artifacts']]
        for name in binaries:
            target = copy / name; target.parent.mkdir(parents=True, exist_ok=True); target.symlink_to(run / name)
    extracted_results = {}
    for (name, tool, test, variable), run in zip(CASES, runs):
        copy = extracted / 'runs' / run.name
        commands = [('inspect', ['python3', str(extracted / 'tools' / tool), str(copy)], None),
                    ('tests', ['python3', '-m', 'unittest', 'discover', '-s', str(extracted / 'tools'), '-p', test, '-v'], dict(os.environ, **{variable: str(copy)}))]
        for kind, command, env in commands:
            path = dest / (name + '-extracted-' + kind + '.log')
            with path.open('xb') as log: code = subprocess.run(command, env=env, stdout=log, stderr=subprocess.STDOUT).returncode
            require(code == 0, 'Extracted check failed: ' + name + ' ' + kind)
            if kind == 'tests': extracted_results[name] = test_log(path)
    module(extracted / 'tools/inspect-treasury-writer-paper.py').inspect_negative(extracted / 'runs' / red.name, extracted / 'runs' / runs[0].name)
    summary['extracted_inspectors'] = 'ALL_FOUR_AND_NEGATIVE_PASSED'
    summary['extracted_regressions'] = extracted_results
    summary['credential_scan'] = 'Private environment and non-template configured credential bytes absent; secret fields redacted.'
    summary['evidence_archive'] = dict(sha256=sha(archive_path), bytes=archive_path.stat().st_size, members=len(inventory))
    summary['source_archives'] = {name: sha(dest / name) for name in ('starchronicle-source.tar.gz', 'starcore-source.tar.gz')}
    text = json.dumps(summary, ensure_ascii=False, indent=2) + '\n'; (dest / 'manifest.json').write_text(text)
    public = ROOT / 'docs/verification'
    require(not (public / (a.label + '-evidence.zip')).exists() and not (public / (a.label + '-summary.json')).exists(), 'Published evidence already exists')
    shutil.copy2(archive_path, public / (a.label + '-evidence.zip')); (public / (a.label + '-summary.json')).write_text(text)
    print(json.dumps(dict(archive=summary['evidence_archive'], regressions=regressions, extracted_regressions=extracted_results), indent=2))


if __name__ == '__main__': main()
