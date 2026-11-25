"""Archive actual MySQL settlement runs, failures and preceding frozen-pair CI evidence."""
from pathlib import Path
from datetime import datetime, timezone
import argparse
import hashlib
import json
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
TEST = 'dev.starcore.starcore.module.resource.MySqlNationSettlementIntegrationTest'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def git(root, *args):
    return subprocess.check_output(['git', '-C', str(root), *args], text=True).strip()


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--native', type=Path, required=True)
    p.add_argument('--fixture', type=Path, required=True)
    p.add_argument('--verified-run', type=Path, required=True)
    p.add_argument('--log', type=Path, action='append', default=[])
    p.add_argument('--label', default='mysql-settlement-20261125')
    a = p.parse_args()
    assert a.label.replace('-', '').isalnum()
    native = a.native.resolve()
    assert not git(native, 'status', '--porcelain'), 'Commit test source before archiving'
    baseline = '7ff849643b0b1541dacd83f8fe3962b9c2a0fdae'
    assert not git(native, 'diff', baseline, 'HEAD', '--', 'src/main', 'pom.xml'), 'Product changed; require a new product verification'
    assert not git(ROOT, 'diff', '642b28654ba62d8747305bc2e848cf1df39b868a', '--', 'src', 'pom.xml')
    verified = json.loads((a.verified_run / 'manifest.json').read_text())
    assert verified['status'] == 'PASSED' and verified['exit_code'] == 0
    assert verified['tests'] == dict(tests=31, failures=0, errors=0, skipped=0)
    for name, digest in verified['files'].items():
        assert Path(name).name == name and sha(a.verified_run / name) == digest
    source = native / ('src/test/java/' + TEST.replace('.', '/') + '.java')
    assert sha(source) == verified['files'][source.name]
    suite = ET.parse(a.verified_run / ('TEST-' + TEST + '.xml')).getroot()
    assert suite.get('name') == TEST and len(suite.findall('testcase')) == 31
    assert not suite.findall('.//skipped') and not suite.findall('.//failure') and not suite.findall('.//error')
    fixture = json.loads((a.fixture / 'preparation.json').read_text())
    shutdown = json.loads((a.fixture / 'shutdown.json').read_text())
    assert fixture['id'] and fixture['database'] == 'starcore_settlement_it' and fixture['port'] == verified['target']['port']
    assert shutdown['container'] == fixture['container'] and shutdown['state']['ExitCode'] == 0 and not shutdown['state']['Running'] and not shutdown['state']['OOMKilled']
    metadata = (a.fixture / 'server-metadata.tsv').read_text()
    assert '8.4.13' in metadata and 'REPEATABLE-READ' in metadata and metadata.count('\tInnoDB') == 12
    rows = [r for r in (a.fixture / 'empty-after-tests.tsv').read_text().splitlines() if not r.startswith('table_name')]
    assert len(rows) == 11 and all(r.endswith('\t0') for r in rows)
    files = {}
    def add(path, name):
        assert path.is_file() and not path.is_symlink() and name not in files
        files[name] = path.read_bytes()
    for name in ('preparation.json', 'server-metadata.tsv', 'empty-after-tests.tsv', 'shutdown.json', 'mysql-server.log'):
        add(a.fixture / name, 'fixture/' + name)
    for path in a.verified_run.iterdir():
        if path.is_file(): add(path, 'verified/' + path.name)
    for directory in sorted((native / 'target').glob('mysql-settlement-focused-*')):
        for path in sorted(directory.rglob('TEST-*.xml')): add(path, 'retained/' + directory.name + '/' + path.name)
    for path in a.log: add(path, 'logs/' + path.name)
    for name in ('tools/run_nation_settlement_mysql.py', 'docs/NATION-SETTLEMENT-MYSQL.md'):
        add(native / name, 'native/' + name)
    add(Path(__file__), 'tools/' + Path(__file__).name)
    # Literal secret scan before any archive is written. The private credential file is never copied.
    secrets = [line.split('=', 1)[1].encode() for line in (a.fixture / 'mysql.env').read_text().splitlines() if line.startswith(('MYSQL_PASSWORD=', 'MYSQL_ROOT_PASSWORD='))]
    assert len(secrets) == 2 and all(secrets)
    assert all(secret not in data for data in files.values() for secret in secrets), 'Private test credential in evidence'
    summary = dict(status='MYSQL_SETTLEMENT_STORAGE_ACCEPTED',captured_utc=datetime.now(timezone.utc).isoformat(),native_head=git(native, 'rev-parse', 'HEAD'),product_baseline=baseline,product_unchanged=True,verified=verified,fixture=fixture,normal_shutdown=True,limitations=['Native source integration on dedicated MySQL 8.4.13 / InnoDB; not Paper with MySQL acceptance.','Clock and nation/thread authority are controlled fixtures; no real database crash or process-restart persistence test.','No new product JAR; previous frozen-pair Paper evidence remains bound to its own exact artifacts.'])
    files['source-provenance.json'] = (json.dumps(summary, ensure_ascii=False, indent=2) + '\n').encode()
    inventory = {name: hashlib.sha256(data).hexdigest() for name, data in files.items()}
    out = ROOT / 'docs/verification'; archive = out / (a.label + '-evidence.zip'); report = out / (a.label + '-summary.json')
    assert not archive.exists() and not report.exists()
    with zipfile.ZipFile(archive, 'x', zipfile.ZIP_DEFLATED) as z:
        for name, data in files.items(): z.writestr(name, data)
        z.writestr('evidence-manifest.json', json.dumps(inventory, indent=2) + '\n')
    with zipfile.ZipFile(archive) as z:
        assert z.testzip() is None and all(hashlib.sha256(z.read(n)).hexdigest() == h for n, h in inventory.items())
    summary['archive'] = dict(sha256=sha(archive), bytes=archive.stat().st_size, members=len(inventory))
    report.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(summary['archive']))


if __name__ == '__main__':
    main()
