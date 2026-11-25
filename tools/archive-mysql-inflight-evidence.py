"""Archive native interruption evidence, retained failures, tools and validation logs.

Private environment files are only read for a leak scan; they are never copied.
The output path must be new. The native offline inspector runs before packaging.
"""
from pathlib import Path
import argparse
import hashlib
import json
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--native', type=Path, required=True)
    p.add_argument('--accepted', type=Path, required=True)
    p.add_argument('--failed', action='append', type=Path, default=[])
    p.add_argument('--validation-log', action='append', type=Path, default=[])
    p.add_argument('--private-env', action='append', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    native, accepted = a.native.resolve(), a.accepted.resolve()
    output = a.output.resolve()
    if output.exists() or output.with_suffix('.summary.json').exists():
        raise ValueError('Existing archive/summary must be retained')
    verified = subprocess.run([sys.executable, str(native / 'tools/inspect_nation_inflight_mysql.py'), str(accepted)],
                              check=True, capture_output=True, text=True)
    product = subprocess.check_output(['git', 'diff', '59ed52abbed5521eff6383df004985b1e13e1bbe', '--', 'src/main', 'pom.xml'], cwd=native)
    if product:
        raise ValueError('Product source drift requires a new validation scope')
    secrets = []
    for envfile in a.private_env:
        if envfile.resolve().is_relative_to(ROOT) or envfile.resolve().is_relative_to(native):
            raise ValueError('Credentials must stay outside the repository')
        for line in envfile.read_text().splitlines():
            key, value = line.split('=', 1)
            if any(word in key for word in ('PASSWORD', 'SECRET', 'TOKEN')) and value:
                secrets.append(value.encode())
    if not secrets:
        raise ValueError('Missing private values for leak scan')
    payload = {}

    def add(name, path):
        if name in payload or path.is_symlink() or not path.is_file():
            raise ValueError('Unsafe/duplicate evidence source')
        raw = path.read_bytes()
        if any(secret in raw for secret in secrets):
            raise ValueError('Private credential found in evidence')
        payload[name] = raw

    for directory, prefix in [(accepted, 'accepted'), *((d.resolve(), 'retained/' + d.name) for d in a.failed)]:
        if prefix != 'accepted' and json.loads((directory / 'manifest.json').read_text())['status'] != 'FAILED':
            raise ValueError('Retained diagnostic is not marked FAILED')
        for path in sorted(directory.rglob('*')):
            if path.is_file():
                add(prefix + '/' + path.relative_to(directory).as_posix(), path)
    for name in ('run_nation_inflight_mysql.py', 'inspect_nation_inflight_mysql.py',
                 'test_nation_inflight_mysql.py', 'test_nation_inflight_safety.py', 'run_nation_settlement_mysql.py'):
        add('tools/' + name, native / 'tools' / name)
    add('tools/archive-mysql-inflight-evidence.py', Path(__file__).resolve())
    for path in a.validation_log:
        add('validation/' + path.name, path)
    payload['validation/offline-inspector.json'] = verified.stdout.encode()
    manifest = {name: hashlib.sha256(raw).hexdigest() for name, raw in sorted(payload.items())}
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, 'x', zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, raw in sorted(payload.items()):
            archive.writestr(name, raw)
        archive.writestr('evidence-manifest.json', json.dumps(manifest, indent=2) + '\n')
    with zipfile.ZipFile(output) as archive:
        if set(archive.namelist()) != set(manifest) | {'evidence-manifest.json'}:
            raise ValueError('Archive inventory mismatch')
        for name, digest in manifest.items():
            if hashlib.sha256(archive.read(name)).hexdigest() != digest:
                raise ValueError('Archive member changed')
    summary = dict(status='VERIFIED', archive=output.name, bytes=output.stat().st_size,
                   members=len(manifest), sha256=hashlib.sha256(output.read_bytes()).hexdigest(),
                   native_head=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=native, text=True).strip(),
                   native_product_head='59ed52abbed5521eff6383df004985b1e13e1bbe',
                   acceptance=json.loads(verified.stdout), failed_attempts=len(a.failed),
                   boundary='Native services compiled from unchanged product sources; fixture identity adapters; real MySQL and process kills. No new Paper or hardware power-loss claim.')
    output.with_suffix('.summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps(summary, indent=2))


if __name__ == '__main__':
    main()
