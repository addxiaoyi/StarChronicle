"""Archive explicitly selected offline validation evidence and verify every stored byte.

Only pass sanitized source snapshots, build reports and disposable probe directories.
Runtime worlds, credential files and third-party binaries are not selected automatically.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import zipfile


def file_sha(path: Path) -> str:
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', action='append', required=True, metavar='NAME=PATH')
    parser.add_argument('--exclude-relative-prefix', action='append', default=[])
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--summary', type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists() or args.summary.exists():
        parser.error('Output and summary must be new files')
    exclusions = []
    for text in args.exclude_relative_prefix:
        prefix = PurePosixPath(text)
        if not text or prefix.is_absolute() or '..' in prefix.parts or '\\' in text:
            parser.error('Use a safe relative exclusion prefix')
        exclusions.append(prefix)
    selected = {}
    for item in args.input:
        name, separator, text = item.partition('=')
        prefix = PurePosixPath(name)
        if not separator or not name or prefix.is_absolute() or '..' in prefix.parts or '\\' in name:
            parser.error('Use a safe relative NAME=PATH for every input')
        source = Path(text).resolve()
        if not source.exists() or source.is_symlink():
            parser.error('Input must be an existing regular file or directory: ' + text)
        for path in sorted(source.rglob('*')) if source.is_dir() else [source]:
            if path.is_symlink():
                parser.error('Symlinks are not evidence inputs: ' + str(path))
            if not path.is_file():
                continue
            if source.is_dir() and any(
                    PurePosixPath(path.relative_to(source).as_posix()).is_relative_to(prefix)
                    for prefix in exclusions):
                continue
            if path.suffix.lower() in ('.jar', '.class', '.exe', '.dll', '.mdmp'):
                continue
            if '__pycache__' in path.parts or path.suffix.lower() in ('.pyc', '.pyo'):
                continue
            member = str(prefix / path.relative_to(source).as_posix()) if source.is_dir() else str(prefix)
            if member in selected or member == 'evidence-manifest.json':
                parser.error('Duplicate or reserved member: ' + member)
            selected[member] = path
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.summary.parent.mkdir(parents=True, exist_ok=True)
    manifest, size = {}, 0
    with zipfile.ZipFile(args.output, 'x', zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for name, path in sorted(selected.items()):
            manifest[name] = file_sha(path)
            size += path.stat().st_size
            archive.write(path, name)
        archive.writestr('evidence-manifest.json', json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
    with zipfile.ZipFile(args.output) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)) or set(names) != set(manifest) | {'evidence-manifest.json'}:
            raise AssertionError('Evidence archive member set changed')
        for name, expected in manifest.items():
            with archive.open(name) as stream:
                if hashlib.file_digest(stream, 'sha256').hexdigest() != expected:
                    raise AssertionError('Evidence member changed: ' + name)
    result = {'status': 'VERIFIED', 'scope': 'Offline source, unit-test and database-copy evidence only; no live Minecraft acceptance.',
              'archive': args.output.name, 'archive_sha256': file_sha(args.output),
              'archive_bytes': args.output.stat().st_size, 'manifest_members': len(manifest),
              'total_members': len(manifest) + 1, 'uncompressed_evidence_bytes': size,
              'inputs': args.input, 'excluded_relative_prefixes': args.exclude_relative_prefix,
              'members_sha256': manifest}
    args.summary.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({key: value for key, value in result.items() if key not in ('members_sha256', 'inputs')}, ensure_ascii=False))


if __name__ == '__main__':
    main()
