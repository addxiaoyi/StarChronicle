"""Compile only the isolated test fixture after an explicit coordinator JVM window grant."""
from pathlib import Path
import argparse
import importlib.util
import json
import os
import subprocess
import zipfile

spec = importlib.util.spec_from_file_location('phase_three_paper_common', Path(__file__).with_name('phase-three-paper-common.py'))
c = importlib.util.module_from_spec(spec); spec.loader.exec_module(c)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-dir', type=Path, required=True)
    parser.add_argument('--expected-sha', required=True)
    parser.add_argument('--java-home', type=Path, default=Path(r'C:\Users\Administrator\Documents\luoseng\.toolchains\jdk-21.0.13.0.2+1'))
    args = parser.parse_args()
    run = c.run_path(args.run_dir); value = c.manifest(run, args.expected_sha)
    if value.get('fixture_compiled') or (run / 'plugins/PhaseThreePaperFixture.jar').exists():
        raise SystemExit('Refusing to replace an already compiled fixture')
    source = run / 'fixture/PhaseThreePaperFixture.java'
    if c.digest(source) != value['fixture_source_sha256'] or c.digest(c.ROOT / 'tools/PhaseThreePaperFixture.java') != value['fixture_source_sha256']:
        raise SystemExit('Fixture source drifted after preparation')
    compiler = args.java_home / 'bin/javac.exe'
    if not compiler.is_file():
        raise SystemExit('Java 21 compiler not found')
    classes = run / 'fixture/classes'; classes.mkdir(exist_ok=True)
    if any(classes.rglob('*.class')):
        raise SystemExit('Refusing a repair over partial class output; inspect the previous attempt')
    jars = sorted((run / 'libraries').rglob('*.jar')) + [run / 'plugins/StarChronicle.jar',
            Path.home() / '.m2/repository/io/papermc/paper/paper-api/1.21.11-R0.1-SNAPSHOT/paper-api-1.21.11-R0.1-SNAPSHOT.jar']
    if not jars[-1].is_file():
        raise SystemExit('Retained Paper API compile dependency missing')
    args_file = run / 'fixture/javac.args'
    args_file.write_text('--release 21\n-proc:none\n-encoding UTF-8\n-cp\n"' +
                        os.pathsep.join(str(path) for path in jars).replace('\\', '/') + '"\n-d\n"' +
                        str(classes).replace('\\', '/') + '"\n"' + str(source).replace('\\', '/') + '"\n', encoding='utf-8')
    attempts = value.setdefault('fixture_compile_attempts', [])
    if not attempts and 'fixture_compile_exit_code' in value:
        old_log = run / 'evidence/fixture-compile.log'
        saved = run / 'evidence/fixture-compile-attempt1.log'
        if old_log.exists() and not saved.exists():
            saved.write_bytes(old_log.read_bytes())
        attempts.append({'attempt': 1, 'exit_code': value['fixture_compile_exit_code'],
                         'command': value['fixture_compile_command'],
                         'log': saved.relative_to(run).as_posix(),
                         'note': 'Retained first tooling attempt: javac rejects -J VM options inside an argument file; no class or server was started.'})
    attempt = len(attempts) + 1
    command = [str(compiler), '-J-Xmx128m', '@' + str(args_file)]
    result = subprocess.run(command, capture_output=True, timeout=120)
    log = run / f'evidence/fixture-compile-attempt{attempt}.log'
    log.write_bytes(result.stdout + result.stderr)
    attempts.append({'attempt': attempt, 'exit_code': result.returncode, 'command': command,
                     'log': log.relative_to(run).as_posix()})
    value['fixture_compile_exit_code'] = result.returncode
    value['fixture_compile_command'] = command
    value['fixture_compiled_utc'] = c.now()
    if result.returncode:
        value['status'] = 'FIXTURE_COMPILE_FAILED'; c.write_json(run / 'preparation.json', value)
        raise SystemExit('Fixture compilation failed; inspect evidence/fixture-compile.log')
    target = run / 'plugins/PhaseThreePaperFixture.jar'
    with zipfile.ZipFile(target, 'w') as archive:
        archive.writestr('plugin.yml', 'name: PhaseThreePaperFixture\nversion: 1\nmain: smoke.PhaseThreePaperFixture\napi-version: "1.21"\ndepend: [StarCore, StarChronicle]\ncommands:\n  phase3:\n    description: Isolated phase-three acceptance fixture\n')
        for path in sorted(classes.rglob('*.class')):
            archive.write(path, path.relative_to(classes).as_posix())
    value.update(fixture_compiled=True, fixture_sha256=c.digest(target), java_home=str(args.java_home), status='COMPILED_NOT_RUN')
    if not c.source_unchanged(value):
        raise SystemExit('Original source files changed; no server may be started')
    c.write_json(run / 'preparation.json', value)
    print(json.dumps({'fixture_compile_exit_code': result.returncode, 'fixture_sha256': value['fixture_sha256'],
                      'plugin_sha256': value['plugin_sha256'], 'server_started': False}, indent=2))


if __name__ == '__main__':
    main()
