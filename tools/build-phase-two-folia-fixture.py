"""Compile the isolated fixture only after the serial JVM owner explicitly authorizes it."""
from pathlib import Path
import argparse, hashlib, json, os, subprocess, zipfile

ROOT = Path(__file__).resolve().parents[1]

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-dir', type=Path, required=True)
    parser.add_argument('--expected-sha', required=True)
    parser.add_argument('--java-home', type=Path, default=Path(os.environ['JAVA_HOME']) if os.environ.get('JAVA_HOME') else None)
    args = parser.parse_args()
    run = args.run_dir.resolve()
    if run.parent != (ROOT / 'run').resolve() or not run.name.startswith('phase-two-folia-'):
        parser.error('fixture output must remain inside its new phase-two-folia-* run')
    manifest_path = run / 'acceptance-preparation.json'
    manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
    if args.expected_sha != manifest['plugin_sha256'] or digest(run / 'plugins' / 'StarChronicle.jar') != args.expected_sha:
        raise SystemExit('Product fingerprint differs from approved preparation')
    source = run / 'fixture' / 'PhaseTwoFoliaFixture.java'
    if digest(source) != manifest['fixture_source_sha256']:
        raise SystemExit('Fixture source drifted after preparation')
    classes = run / 'fixture' / 'classes'
    if classes.exists() and any(classes.iterdir()) or (run / 'plugins' / 'PhaseTwoFoliaFixture.jar').exists():
        raise SystemExit('Refusing to overwrite an already compiled fixture')
    if args.java_home is None or not (args.java_home / 'bin' / 'javac.exe').is_file():
        parser.error('Supply --java-home pointing to the installed JDK when JAVA_HOME is unavailable')
    classes.mkdir(exist_ok=True)
    jars = sorted((run / 'libraries').rglob('*.jar')) + [run / 'plugins' / 'StarChronicle.jar', Path.home() / '.m2/repository/io/papermc/paper/paper-api/1.21.11-R0.1-SNAPSHOT/paper-api-1.21.11-R0.1-SNAPSHOT.jar']
    arguments = run / 'fixture' / 'javac.args'
    arguments.write_text('--release 21\n-encoding UTF-8\n-cp\n"' + ';'.join(str(path).replace('\\', '/') for path in jars) + '"\n-d\n"' + str(classes).replace('\\', '/') + '"\n"' + str(source).replace('\\', '/') + '"\n', encoding='utf-8')
    compiled = subprocess.run([str(args.java_home / 'bin' / 'javac.exe'), '@' + str(arguments)], check=True)
    artifact = run / 'plugins' / 'PhaseTwoFoliaFixture.jar'
    with zipfile.ZipFile(artifact, 'w') as archive:
        archive.writestr('plugin.yml', 'name: PhaseTwoFoliaFixture\nversion: 1\nmain: smoke.PhaseTwoFoliaFixture\napi-version: "1.21"\ndepend: [StarChronicle]\nfolia-supported: true\ncommands:\n  phase2folia:\n    description: Isolated phase-two acceptance API and ownership inspector\n')
        for path in classes.rglob('*.class'):
            archive.write(path, path.relative_to(classes).as_posix())
    manifest['fixture_compiled'] = True
    manifest['fixture_compile_exit_code'] = compiled.returncode
    manifest['fixture_sha256'] = digest(artifact)
    manifest['status'] = 'COMPILED_NOT_RUN'
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print('Compiled test-only fixture:', artifact)
    print('Fixture SHA-256:', manifest['fixture_sha256'])

if __name__ == '__main__':
    main()
