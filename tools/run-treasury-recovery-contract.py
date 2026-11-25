"""Run packaged treasury commit recovery in three small sequential JVMs, without Minecraft."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('starcore', 'runtime', 'java-home', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    artifacts = output / 'artifacts'
    artifacts.mkdir()
    source = Path(__file__).with_name('TreasuryRecoveryProbe.java')
    for original, name in ((args.starcore, 'starcore.jar'), (source, source.name)):
        shutil.copy2(original, artifacts / name)
    libraries = sorted((args.runtime / 'libraries').rglob('*.jar'))
    if not libraries:
        raise RuntimeError('Missing runtime libraries')
    cp = os.pathsep.join(p.resolve().as_posix() for p in (artifacts / 'starcore.jar', *libraries))
    classes = output / 'classes'
    classes.mkdir()
    manifest = {'status': 'RUNNING', 'scope': 'Packaged TreasuryModule and real SQLite, commit then response/read failure, quarantine flush, original-ID replay in two subsequent JVMs; no Minecraft, Chronicle adapter or MySQL certification',
                'artifacts': {p.name: digest(p) for p in artifacts.iterdir()}, 'phases': {}}

    def run(label, program, arguments, launcher=()):
        argument_file = output / (label + '.args')
        argument_file.write_text('\n'.join(json.dumps(str(arg).replace('\\', '/')) for arg in arguments) + '\n', encoding='utf-8')
        suffix = '.exe' if os.name == 'nt' else ''
        with (output / (label + '.log')).open('wb') as log:
            result = subprocess.run([str(args.java_home / 'bin' / (program + suffix)), *launcher, '@' + str(argument_file)],
                                    stdout=log, stderr=subprocess.STDOUT, timeout=120)
        manifest['phases'][label] = {'exit_code': result.returncode, 'log_sha256': digest(output / (label + '.log'))}
        if result.returncode:
            raise RuntimeError(f'{label} failed: {output / (label + ".log")}')

    try:
        run('compile', 'javac', ['-encoding', 'UTF-8', '-cp', cp, '-d', classes, artifacts / source.name],
            ['-J-Xmx96m', '-J-XX:+UseSerialGC', '-J-XX:ActiveProcessorCount=1'])
        for phase in ('first', 'second', 'third'):
            run(phase, 'java', ['-Xmx96m', '-Xss512k', '-XX:+UseSerialGC', '-XX:ActiveProcessorCount=1',
                                '-XX:TieredStopAtLevel=1', '-XX:ReservedCodeCacheSize=32m', '-cp', str(classes) + os.pathsep + cp,
                                'dev.starcore.starcore.module.treasury.TreasuryRecoveryProbe', output, phase])
        with sqlite3.connect(f'file:{output.as_posix()}/treasury.db?mode=ro', uri=True) as database:
            assert database.execute('PRAGMA integrity_check').fetchone() == ('ok',)
            state = dict(database.execute('SELECT property_key,property_value FROM starcore_treasury_state'))
            assert state['authorityDomainId'] == (output / 'domain.txt').read_text()
            assert state['transfer.count'] == '1' and state['credit.count'] == '2'
            assert state['transfer.0.transactionId'] == 'contract-transfer'
            assert state['transfer.0.amount'] == '20.00'
            balances = {state[f'treasury.{i}.nationId']: state[f'treasury.{i}.balance'] for i in range(int(state['count']))}
            assert balances == {'00000000-0000-0000-0000-000000000001': '80.00', '00000000-0000-0000-0000-000000000002': '30.00'}
        manifest['status'] = 'PASS'
    except BaseException:
        manifest['status'] = 'FAILED'
        raise
    finally:
        manifest['files'] = {p.relative_to(output).as_posix(): digest(p) for p in output.rglob('*') if p.is_file() and p.name != 'manifest.json'}
        (output / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(manifest, ensure_ascii=False))


if __name__ == '__main__':
    main()
