"""Run three fresh JVMs against both candidate JARs, without starting Minecraft."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--starcore', type=Path, required=True)
    parser.add_argument('--chronicle', type=Path, required=True)
    parser.add_argument('--runtime', type=Path, required=True, help='Initialized Paper runtime with libraries/')
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True, help='New evidence directory; never reused')
    args = parser.parse_args()
    source = Path(__file__).with_name('NativeHistoryContractProbe.java').resolve()
    libraries = sorted((args.runtime / 'libraries').rglob('*.jar'))
    if not libraries:
        raise SystemExit('Runtime libraries missing')
    for artifact in [args.starcore, args.chronicle, source]:
        if not artifact.is_file():
            raise SystemExit(f'Missing artifact: {artifact}')
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    frozen = output / 'artifacts'
    frozen.mkdir()
    for artifact, name in [(args.starcore, 'starcore.jar'), (args.chronicle, 'starchronicle.jar'), (source, source.name)]:
        shutil.copy2(artifact, frozen / name)
    manifest = {'status': 'RUNNING', 'artifacts': {p.name: sha(p) for p in frozen.iterdir()},
                'scope': 'Three JVMs; real two-plugin SQLite service contract; no Minecraft server or economy execution',
                'phases': {}}
    cp = os.pathsep.join(p.resolve().as_posix() for p in [frozen/'starchronicle.jar', frozen/'starcore.jar', *libraries])
    classes = output / 'classes'
    classes.mkdir()
    suffix = '.exe' if os.name == 'nt' else ''
    java = args.java_home / 'bin' / ('java'+suffix)
    javac = args.java_home / 'bin' / ('javac'+suffix)

    def run(label, command):
        # Java argument files avoid Windows' command-line length limit for the Paper classpath.
        argument_file = output / (label+'.args')
        argument_file.write_text('\n'.join(json.dumps(str(arg).replace('\\', '/')) for arg in command[1:] if not str(arg).startswith('-J'))+'\n', encoding='utf-8')
        launcher_options = [arg for arg in command[1:] if str(arg).startswith('-J')]
        with (output / (label+'.log')).open('w', encoding='utf-8') as log:
            result = subprocess.run([command[0], *launcher_options, '@'+str(argument_file)], stdout=log, stderr=subprocess.STDOUT, timeout=120)
        manifest['phases'][label] = {'exit_code': result.returncode, 'log_sha256': sha(output/(label+'.log'))}
        (output/'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
        if result.returncode:
            raise RuntimeError(f'{label} failed; see {output / (label+".log")}')

    try:
        run('compile', [str(javac), '-J-Xmx128m', '-J-XX:+UseSerialGC', '-proc:none', '-encoding', 'UTF-8', '--release', '21', '-cp', cp, '-d', str(classes), str(frozen/source.name)])
        for phase in ['first', 'second', 'third']:
            run(phase, [str(java), '-Xmx128m', '-Xss512k', '-XX:+UseSerialGC', '-XX:ActiveProcessorCount=1', '-XX:ReservedCodeCacheSize=32m', '-Dfile.encoding=UTF-8', '-cp', str(classes)+os.pathsep+cp, 'io.github.addxiaoyi.starchronicle.integration.NativeHistoryContractProbe', str(output), phase])
        with sqlite3.connect(output/'native.db') as native, sqlite3.connect(output/'chronicle.db') as chronicle:
            assert native.execute('SELECT COUNT(*) FROM trade_commit_log').fetchone()[0] == 4
            assert chronicle.execute('SELECT COUNT(*) FROM committed_source_receipts').fetchone()[0] == 4
            assert chronicle.execute('SELECT COUNT(*) FROM events').fetchone()[0] == 4
            assert chronicle.execute('SELECT COUNT(*) FROM contributions').fetchone()[0] == 8
            domain = native.execute('SELECT domain FROM trade_commit_authority WHERE id=1').fetchone()[0]
            assert chronicle.execute('SELECT domain,sequence FROM committed_source_cursors').fetchone() == (domain, 4)
            for sequence, payload in native.execute('SELECT sequence,payload FROM trade_commit_log'):
                raw = chronicle.execute('SELECT json FROM committed_source_receipts WHERE sequence=?', (sequence,)).fetchone()[0]
                entry = json.loads(raw)
                import base64
                fields = [base64.b64decode(field).decode() for field in payload.split('|')[1:]]
                assert entry['event']['facts']['trade.id'] == fields[0]
                assert entry['event']['facts']['trade.status'] == fields[14]
                assert entry['event']['facts']['trade.executed_at'] == fields[11]
        manifest['status'] = 'PASS'
    except BaseException:
        manifest['status'] = 'FAILED'
        raise
    finally:
        manifest['files'] = {str(p.relative_to(output)): sha(p) for p in output.rglob('*') if p.is_file() and p.name != 'manifest.json'}
        (output/'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps({'status': manifest['status'], 'evidence': str(output), 'artifacts': manifest['artifacts']}, ensure_ascii=False))


if __name__ == '__main__':
    main()
