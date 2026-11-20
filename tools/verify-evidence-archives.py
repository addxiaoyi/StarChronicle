"""Check exact archived evidence bytes in the checkout and optionally a Git revision.

Usage: python tools/verify-evidence-archives.py --git-ref HEAD
Only inventories with a list of file/bytes/sha256 rows are selected. Other historic
inventory formats remain governed by their original verifier.
"""
from pathlib import Path, PurePosixPath
import argparse
import hashlib
import io
import json
import subprocess


ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--git-ref')
    args = parser.parse_args()
    expected = {}
    inventories = 0
    manifests = set((ROOT / 'docs/verification').rglob('evidence-manifest.json'))
    manifests.update((ROOT / 'docs/verification').rglob('manifest.json'))
    for manifest in sorted(manifests):
        raw = manifest.read_bytes()
        data = json.loads(raw)
        rows = data.get('files')
        copied = data.get('copied_files')
        if isinstance(copied, list) and all(isinstance(row, dict) and
                {'relative', 'size', 'sha256'} <= row.keys() for row in copied):
            rows = [{'file': row['relative'], 'bytes': row['size'], 'sha256': row['sha256']}
                    for row in copied]
        if not isinstance(rows, list) or not all(isinstance(row, dict) and
                {'file', 'bytes', 'sha256'} <= row.keys() for row in rows):
            continue
        inventories += 1
        expected[manifest.relative_to(ROOT).as_posix()] = (len(raw), hashlib.sha256(raw).hexdigest())
        for row in rows:
            relative = PurePosixPath(row['file'])
            if relative.is_absolute() or '..' in relative.parts or '\\' in row['file'] or ':' in row['file']:
                raise ValueError('Unsafe evidence path: ' + row['file'])
            source = manifest.parent.joinpath(*relative.parts)
            if source.is_symlink() or not source.is_file() or not source.resolve().is_relative_to(manifest.parent.resolve()):
                raise ValueError('Unsafe or missing evidence file: ' + str(source))
            identity = (row['bytes'], row['sha256'])
            name = source.relative_to(ROOT).as_posix()
            if name in expected and expected[name] != identity:
                raise ValueError('Conflicting evidence inventories: ' + name)
            expected[name] = identity
    if not inventories:
        raise ValueError('No supported evidence inventories found')
    for name, identity in expected.items():
        raw = (ROOT / name).read_bytes()
        if (len(raw), hashlib.sha256(raw).hexdigest()) != identity:
            raise ValueError('Checkout evidence bytes differ: ' + name)
    if args.git_ref:
        revision = subprocess.run(['git', 'rev-parse', '--verify', args.git_ref + '^{commit}'],
                                  cwd=ROOT, capture_output=True, check=True, text=True).stdout.strip()
        names = sorted(expected)
        request = ''.join(revision + ':' + name + '\n' for name in names).encode()
        result = subprocess.run(['git', 'cat-file', '--batch'], cwd=ROOT,
                                input=request, capture_output=True, check=True)
        stream = io.BytesIO(result.stdout)
        for name in names:
            header = stream.readline().decode().strip().split()
            if len(header) != 3 or header[1] != 'blob':
                raise ValueError('Git evidence blob missing: ' + name)
            raw = stream.read(int(header[2]))
            if stream.read(1) != b'\n' or (len(raw), hashlib.sha256(raw).hexdigest()) != expected[name]:
                raise ValueError('Git evidence bytes differ: ' + name)
        if stream.read():
            raise ValueError('Unexpected Git batch response')
    print(json.dumps({'status': 'VERIFIED', 'inventories': inventories,
                      'files': len(expected), 'git_ref': args.git_ref}, ensure_ascii=False))


if __name__ == '__main__':
    main()
