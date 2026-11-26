"""Prepare and run isolated native resource history acceptance, preserving every attempt."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import re
import shutil
import socket
import sqlite3
import subprocess
import threading
import time
import zipfile

ROOT = Path(__file__).resolve().parents[1]
FIXTURE = 'ResourceHistoryPaperFixture'


def sha(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(value, encoding='utf-8')


def prepare(args):
    product, native, runtime = args.product.resolve(), args.starcore.resolve(), args.runtime.resolve()
    run = ROOT / 'run' / f'paper-resource-{sha(product)[:8]}-{args.attempt}'
    if not re.fullmatch(r'[A-Za-z0-9-]+', args.attempt) or run.exists():
        raise RuntimeError('Require a new, simple attempt name')
    if not 1024 <= args.port <= 65535 or args.port == 25565:
        raise RuntimeError('Require a distinct local acceptance port')
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', args.port))
    with zipfile.ZipFile(native) as archive:
        config = archive.read('config.yml').decode().replace('\r\n', '\n')
    config, count = re.subn(r'(?m)^(    nation-create-cost:) [^\n]+', r'\1 0.0', config)
    if count != 1:
        raise RuntimeError('Unknown native cost configuration')
    config, count = re.subn(r'(?m)^(map:\n  web:\n(?:    #[^\n]*\n)*)    enabled: true', r'\g<1>    enabled: false', config)
    if count != 1:
        raise RuntimeError('Unknown native web configuration')
    for folder in ('plugins', 'evidence', 'fixture/classes'):
        (run / folder).mkdir(parents=True)
    manifest = {'product_sha256': sha(product), 'native_sha256': sha(native), 'paper_sha256': sha(runtime / 'paper.jar'),
                'port': args.port, 'scenario': 'native committed resource balances and transfers; real service calls with offline fixture identities, no client'}
    for source, target in ((product, run / 'plugins/StarChronicle.jar'), (native, run / 'plugins/StarCore.jar'), (runtime / 'paper.jar', run / 'paper.jar')):
        if source.is_symlink():
            raise RuntimeError('Symlink artifact input is not supported')
        shutil.copy2(source, target)
    for folder in ('libraries', 'cache'):
        if any(path.is_symlink() for path in (runtime / folder).rglob('*')):
            raise RuntimeError('Runtime symlinks are not supported')
        shutil.copytree(runtime / folder, run / folder, copy_function=os.link)
    write(run / 'plugins/StarCore/config.yml', config)
    write(run / 'plugins/StarChronicle/config.yml', '''require-starcore: true
install-default-stories: false
install-native-example-quests: false
install-epic-strategy-example: false
admin-demo-enabled: false
npc-hooks: false
poll-ticks: 20
''')
    write(run / 'plugins/StarChronicle/stories/resource-history-test.yml', '''id: resource-history-test
version: "1"
title: Native committed history acceptance
scope: NATION
triggers: [RESOURCE_BALANCE_CHANGED]
start: testimony
nodes:
  testimony:
    text: Await a real national witness
    choices:
      record: {label: Record, next: done}
  done:
    text: Recorded
    terminal: true
''')
    write(run / f'plugins/{FIXTURE}/config.yml', f'product-sha: "{sha(product)}"\nnative-sha: "{sha(native)}"\n')
    write(run / 'plugins/bStats/config.yml', 'enabled: false\nlogFailedRequests: false\nlogSentData: false\nlogResponseStatusText: false\n')
    write(run / 'eula.txt', 'eula=true\n')
    write(run / 'ops.json', '[]\n')
    write(run / 'server.properties', f'''server-ip=127.0.0.1
server-port={args.port}
online-mode=false
enable-query=false
enable-rcon=false
view-distance=2
simulation-distance=2
max-players=1
difficulty=peaceful
level-type=minecraft\\:flat
generator-settings={{"biome":"minecraft:plains","layers":[{{"height":1,"block":"minecraft:bedrock"}},{{"height":2,"block":"minecraft:dirt"}},{{"height":1,"block":"minecraft:grass_block"}}]}}
generate-structures=false
spawn-monsters=false
''')
    source = run / 'fixture' / f'{FIXTURE}.java'
    shutil.copy2(ROOT / 'tools' / f'{FIXTURE}.java', source)
    manifest['fixture_source_sha256'] = sha(source)
    jars = [Path.home() / '.m2/repository/io/papermc/paper/paper-api/1.21.11-R0.1-SNAPSHOT/paper-api-1.21.11-R0.1-SNAPSHOT.jar',
            run / 'plugins/StarChronicle.jar', run / 'plugins/StarCore.jar'] + sorted((run / 'libraries').rglob('*.jar'))
    classes = run / 'fixture/classes'
    argfile = run / 'fixture/javac.args'
    write(argfile, '--release 21\n-proc:none\n-encoding UTF-8\n-cp\n"' + os.pathsep.join(str(p) for p in jars) +
          '"\n-d\n"' + str(classes) + '"\n"' + str(source) + '"\n')
    with (run / 'evidence/fixture-compile.log').open('x') as log:
        subprocess.run([str(args.java_home / 'bin/javac'), '-J-Xmx128m', '@' + str(argfile)], stdout=log, stderr=subprocess.STDOUT, check=True)
    target = run / 'plugins' / f'{FIXTURE}.jar'
    with zipfile.ZipFile(target, 'x', zipfile.ZIP_DEFLATED) as archive:
        for path in classes.rglob('*.class'):
            archive.write(path, path.relative_to(classes))
        archive.writestr('plugin.yml', f'name: {FIXTURE}\nversion: 1\nmain: smoke.{FIXTURE}\napi-version: "1.21"\ndepend: [StarCore, StarChronicle]\ncommands:\n  resourceverify:\n    description: Isolated native history acceptance\n')
    manifest['fixture_sha256'] = sha(target)
    write(run / 'preparation.json', json.dumps(manifest, indent=2) + '\n')
    print(json.dumps({'run': str(run), **manifest}), flush=True)


def database_state(run):
    database = run / 'plugins/StarChronicle/chronicle.db'
    with sqlite3.connect(database.as_uri() + '?mode=ro', uri=True) as connection:
        source = connection.execute("SELECT authority_domain,sequence,live FROM committed_history_sources WHERE namespace='starcore.resources'").fetchone()
        pending = connection.execute('SELECT COUNT(*) FROM committed_history_ingress WHERE status=\'PENDING\' AND namespace=\'starcore.resources\'').fetchone()[0]
        events = connection.execute("SELECT COUNT(*) FROM events WHERE json_extract(json,'$.facts.\"history.namespace\"')='starcore.resources'").fetchone()[0]
        triggers = connection.execute("SELECT COUNT(*) FROM story_triggers WHERE json_extract(story_json,'$.id')='resource-history-test'").fetchone()[0]
        archive = connection.execute('SELECT COUNT(*) FROM committed_history_ingress WHERE activate=0 AND namespace=\'starcore.resources\'').fetchone()[0]
        return {'source': source, 'pending': pending, 'events': events, 'triggers': triggers, 'archive': archive}


def execute(args):
    run = args.run.resolve()
    if run.parent != (ROOT / 'run').resolve() or not run.name.startswith('paper-resource-'):
        raise RuntimeError('Require an isolated prepared run')
    manifest = json.loads((run / 'preparation.json').read_text())
    for filename, key in (('plugins/StarChronicle.jar', 'product_sha256'), ('plugins/StarCore.jar', 'native_sha256'),
                          (f'plugins/{FIXTURE}.jar', 'fixture_sha256'), ('paper.jar', 'paper_sha256')):
        if sha(run / filename) != manifest[key]:
            raise RuntimeError('Frozen artifact drift: ' + filename)
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', manifest['port']))
    prefix = run / 'evidence' / f'boot{args.boot}'
    log_path = Path(str(prefix) + '.server.log')
    if log_path.exists():
        raise RuntimeError('Refusing to overwrite a prior boot')
    shutil.copy2(__file__, Path(str(prefix) + '.controller.py'))
    command = [str(args.java_home / 'bin/java'), '-Xms64m', '-Xmx384m', '-Xss512k', '-XX:+UseSerialGC',
               '-XX:ActiveProcessorCount=1', '-XX:TieredStopAtLevel=1', '-XX:ReservedCodeCacheSize=64m',
               '-XX:CompressedClassSpaceSize=64m', '-Dfile.encoding=UTF-8', '-Dterminal.jline=false', '-Dterminal.ansi=false',
               '-jar', 'paper.jar', 'nogui']
    process = subprocess.Popen(command, cwd=run, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               text=True, encoding='utf-8', errors='replace', bufsize=1)
    record = {'command': command, 'pid': process.pid, 'started_at': time.time(), 'boot': args.boot, 'passed': False, 'checkpoints': []}
    lines, condition = [], threading.Condition()

    def reader():
        with log_path.open('x') as log:
            for line in process.stdout:
                log.write(line); log.flush()
                with condition:
                    lines.append(line); condition.notify_all()
                if any(word in line for word in ('RESOURCE_', 'Done (', 'ERROR', 'Exception', 'Stopping server')):
                    print(line.rstrip()[:260], flush=True)

    thread = threading.Thread(target=reader, daemon=True)
    thread.start()

    def await_condition(predicate, seconds=90):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            if any('RESOURCE_FAILED' in line or ' ERROR]' in line or 'Source event was not accepted:' in line or
                   'History scan retained pending evidence:' in line or 'Resource history scan retained its cursor:' in line for line in lines):
                raise RuntimeError('Server reported an acceptance/source failure')
            if process.poll() is not None:
                raise RuntimeError('Server exited before acceptance')
            if predicate():
                return
            with condition:
                condition.wait(0.5)
        raise TimeoutError('Expected live checkpoint did not arrive')

    def send(action):
        cursor = len(lines)
        process.stdin.write('resourceverify ' + action + '\n'); process.stdin.flush()
        await_condition(lambda: any('RESOURCE_DONE' in line and '"action":"' + action.split()[0] + '"' in line for line in lines[cursor:]))

    def checkpoint(count):
        def ready():
            try:
                state = database_state(run)
                return state['source'] is not None and state['source'][1:] == (count, 1) and state['pending'] == 0 and state['events'] == count and state['triggers'] == count - 3 + (count + 1) // 5 and state['archive'] == 3
            except sqlite3.OperationalError:
                return False
        await_condition(ready)
        send('check ' + str(count))
        state = database_state(run); record['checkpoints'].append(state)
        print('RESOURCE_DATABASE ' + json.dumps(state), flush=True)

    try:
        await_condition(lambda: any('Done (' in line for line in lines) and any('RESOURCE_READY' in line for line in lines))
        if args.boot == 1:
            checkpoint(3); send('burst 3'); checkpoint(6); send('offline')
        elif args.boot == 2:
            checkpoint(136); send('burst 3'); checkpoint(139)
        elif args.boot == 3:
            checkpoint(139)
            # Observe several normal background polls without submitting source events.
            start = time.monotonic(); await_condition(lambda: time.monotonic() - start >= 4)
            checkpoint(139)
        record['passed'] = True
    except BaseException as error:
        record['error'] = repr(error)
        raise
    finally:
        if process.poll() is None:
            process.stdin.write('stop\n'); process.stdin.flush()
            try:
                process.wait(timeout=45)
            except subprocess.TimeoutExpired:
                process.kill(); process.wait(); record['passed'] = False; record['forced_kill'] = True
        thread.join(timeout=5)
        record.update(exit_code=process.returncode, exited_at=time.time())
        if process.returncode != 0 or not any('Stopping server' in line for line in lines) or any(' ERROR]' in line or 'RESOURCE_FAILED' in line for line in lines):
            record['passed'] = False
        for name in ('StarChronicle', 'StarCore'):
            shutil.copytree(run / 'plugins' / name, Path(str(prefix) + '.data') / name)
        write(Path(str(prefix) + '.session.json'), json.dumps(record, indent=2) + '\n')
    if not record['passed']:
        raise RuntimeError('Server shutdown did not pass')
    print(json.dumps(record), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    subs = parser.add_subparsers(dest='action', required=True)
    prep = subs.add_parser('prepare')
    prep.add_argument('--product', type=Path, default=ROOT / 'target/starchronicle-0.1.0-SNAPSHOT.jar')
    prep.add_argument('--starcore', type=Path, required=True)
    prep.add_argument('--runtime', type=Path, required=True)
    prep.add_argument('--attempt', required=True)
    prep.add_argument('--port', type=int, required=True)
    prep.add_argument('--java-home', type=Path, required=True)
    session = subs.add_parser('run')
    session.add_argument('--run', type=Path, required=True)
    session.add_argument('--java-home', type=Path, required=True)
    session.add_argument('--boot', type=int, choices=(1, 2, 3), required=True)
    args = parser.parse_args()
    (prepare if args.action == 'prepare' else execute)(args)
