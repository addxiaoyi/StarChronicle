"""Prepare and run one fresh frozen-pair MySQL/Paper scenario with normal joint restarts.

The dedicated empty catalog must already exist in the public instance manifest.
Supply the database password only through that manifest's password_env variable.
Failed attempts are retained; use a new catalog, attempt and available port to retry.
"""
from pathlib import Path
import argparse
import hashlib
import json
import os
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--scenario', choices=['production', 'offline', 'trade', 'dungeon', 'war'], required=True)
    parser.add_argument('--attempt', required=True)
    parser.add_argument('--port', type=int, required=True)
    parser.add_argument('--database', required=True)
    for name in ('instance', 'starcore', 'runtime', 'artifacts-dir', 'java-home', 'node'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    assert re.fullmatch('[a-z0-9-]+', args.attempt)
    instance = json.loads(args.instance.read_text())
    assert args.database in instance['databases'] and os.environ.get(instance['password_env'])
    assert os.environ.get('EPIC_MINEFLAYER_MODULE')
    output = ROOT / 'target' / ('mysql-paper-' + args.attempt)
    output.mkdir(parents=True, exist_ok=False)
    def execute(label, arguments, env=None):
        path = output / (label + '.log')
        command = [sys.executable, *map(str, arguments)]
        print(json.dumps(dict(stage=label, log=str(path))), flush=True)
        with path.open('x') as log:
            result = subprocess.run(command, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
        if result.returncode:
            raise RuntimeError(f'{label} failed ({result.returncode}); retained log: {path}')
    preparer = {'production': 'prepare-nation-production-paper.py', 'offline': 'prepare-nation-production-paper.py',
                'trade': 'prepare-nation-trade-paper.py', 'dungeon': 'prepare-production-paper.py', 'war': 'prepare-battlefield-paper.py'}[args.scenario]
    prepare = ['tools/' + preparer]
    for key in ('attempt', 'port', 'starcore', 'runtime', 'artifacts-dir', 'java-home'):
        prepare += ['--' + key, getattr(args, key.replace('-', '_'))]
    if args.scenario == 'offline':
        prepare += ['--offline']
    if args.scenario == 'dungeon':
        prepare += ['--campaign', '--route', 'DUNGEON']
    execute('prepare', prepare)
    digest = hashlib.sha256((ROOT / 'target/starchronicle-0.1.0-SNAPSHOT.jar').read_bytes()).hexdigest()
    route = 'war' if args.scenario == 'war' else 'dungeon'
    run = ROOT / 'run' / f'paper-epic-{digest[:8]}-{route}-{args.attempt}'
    execute('bind', ['tools/prepare-mysql-paper.py', '--run', run, '--instance', args.instance, '--database', args.database])
    scenarios = {'production': ['production-first', 'production-second', 'production-third'],
                 'offline': ['production-offline-first', 'production-offline-second'],
                 'trade': ['sale-first', 'sale-second', 'sale-third'],
                 'dungeon': ['first', 'second', 'third'], 'war': ['first', 'second', 'third']}[args.scenario]
    for boot, scenario in enumerate(scenarios, 1):
        if boot > 1:
            execute(f'restart{boot-1}', ['tools/restart-mysql-paper.py', '--run', run, '--after-boot', boot-1])
            if args.scenario == 'offline':
                execute('resume-polling', ['tools/prepare-nation-production-resume.py', '--run', run])
        session = ['tools/epic-paper-session.py', '--run', run, '--java-home', args.java_home, '--node', args.node, '--boot', boot, '--scenario', scenario]
        if args.scenario in ('dungeon', 'war'):
            session += ['--contributions']
        execute(f'boot{boot}', session)
    execute('inspect', ['tools/inspect-mysql-paper.py', run])
    env = dict(os.environ, STARCHRONICLE_MYSQL_RUN=str(run))
    execute('storage-tests', ['-m', 'unittest', 'discover', '-s', 'tools', '-p', 'test_mysql_paper_acceptance.py', '-v'], env)
    variable, test = {'production': ('STARCHRONICLE_NATION_PRODUCTION_RUN', 'test_nation_production_acceptance.py'),
                      'offline': ('STARCHRONICLE_NATION_PRODUCTION_RUN', 'test_nation_production_acceptance.py'),
                      'trade': ('STARCHRONICLE_NATION_TRADE_RUN', 'test_nation_trade_acceptance.py'),
                      'dungeon': ('STARCHRONICLE_EPIC_RUN', 'test_epic_production_acceptance.py'),
                      'war': ('STARCHRONICLE_EPIC_RUN', 'test_battlefield_acceptance.py')}[args.scenario]
    execute('business-tests', ['-m', 'unittest', 'discover', '-s', 'tools', '-p', test, '-v'], dict(os.environ, **{variable: str(run)}))
    (output / 'result.json').write_text(json.dumps(dict(status='COMPLETE', scenario=args.scenario, run=str(run), output=str(output)), indent=2) + '\n')
    print(json.dumps(dict(status='COMPLETE', run=str(run), output=str(output))), flush=True)


if __name__ == '__main__':
    main()
