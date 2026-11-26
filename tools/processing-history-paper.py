"""Prepare and run isolated native factory processing acceptance, preserving every attempt."""
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
FIXTURE = 'ProcessingHistoryPaperFixture'


def sha(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(value, encoding='utf-8')


def prepare(args):
    product, native, runtime = args.product.resolve(), args.starcore.resolve(), args.runtime.resolve()
    run = ROOT / 'run' / f'paper-processing-{sha(product)[:8]}-{args.attempt}'
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
                'port': args.port, 'scenario': 'atomic native factory production with real loopback player commands, leadership transfer and active-run restart'}
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
    write(run / 'plugins/StarChronicle/stories/processing-history-test.yml', '''id: processing-history-test
version: "1"
title: Native committed history acceptance
scope: NATION
triggers: [PROCESSING_COMPLETED]
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
max-players=2
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
        archive.writestr('plugin.yml', f'name: {FIXTURE}\nversion: 1\nmain: smoke.{FIXTURE}\napi-version: "1.21"\ndepend: [StarCore, StarChronicle]\ncommands:\n  processingverify:\n    description: Isolated native history acceptance\n')
    manifest['fixture_sha256'] = sha(target)
    manifest['client_sha256'] = sha(ROOT / 'tools/processing-paper-clients.cjs')
    shutil.copy2(ROOT / 'tools/processing-paper-clients.cjs', run / 'fixture/processing-paper-clients.cjs')
    write(run / 'preparation.json', json.dumps(manifest, indent=2) + '\n')
    print(json.dumps({'run': str(run), **manifest}), flush=True)


def database_state(run):
    database = run / 'plugins/StarChronicle/chronicle.db'
    with sqlite3.connect(database.as_uri() + '?mode=ro', uri=True) as c:
        source = c.execute("SELECT authority_domain,sequence,live FROM committed_history_sources WHERE namespace='starcore.processing'").fetchone()
        pending = c.execute("SELECT COUNT(*) FROM committed_history_ingress WHERE status='PENDING' AND namespace='starcore.processing'").fetchone()[0]
        receipts = c.execute('SELECT COUNT(*) FROM processing_history_receipts').fetchone()[0]
        contributions = c.execute("SELECT COUNT(*) FROM contributions WHERE type='CIVILIZATION'").fetchone()[0]
        archive = c.execute("SELECT COUNT(*) FROM committed_history_ingress WHERE activate=0 AND namespace='starcore.processing'").fetchone()[0]
        resources = c.execute("SELECT sequence FROM committed_history_sources WHERE namespace='starcore.resources'").fetchone()
        return dict(source=source, pending=pending, receipts=receipts, contributions=contributions, archive=archive, resource_sequence=resources)


def execute(args):
    run = args.run.resolve()
    if run.parent != (ROOT / 'run').resolve() or not run.name.startswith('paper-processing-'):
        raise RuntimeError('Require isolated run')
    manifest = json.loads((run / 'preparation.json').read_text())
    for name, key in (('plugins/StarChronicle.jar', 'product_sha256'), ('plugins/StarCore.jar', 'native_sha256'),
                      (f'plugins/{FIXTURE}.jar', 'fixture_sha256'), ('paper.jar', 'paper_sha256'),
                      ('fixture/processing-paper-clients.cjs', 'client_sha256')):
        if sha(run / name) != manifest[key]: raise RuntimeError('Frozen artifact drift: ' + name)
    with socket.socket() as probe: probe.bind(('127.0.0.1', manifest['port']))
    prefix = run / 'evidence' / f'boot{args.boot}'
    if Path(str(prefix) + '.server.log').exists(): raise RuntimeError('Prior boot must remain unchanged')
    shutil.copy2(__file__, Path(str(prefix) + '.controller.py'))
    records, lines, readers = [], [], []
    condition = threading.Condition()
    server = clients = None
    result = dict(boot=args.boot, passed=False, checkpoints=[], processes=records)

    def launch(command, name, env=None):
        p = subprocess.Popen(command, cwd=run, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                             text=True, encoding='utf-8', errors='replace', bufsize=1, env=env)
        rec = dict(name=name, pid=p.pid, command=command, started_at=time.time()); records.append(rec)
        def read():
            with Path(str(prefix) + f'.{name}.log').open('x') as log:
                for line in p.stdout:
                    log.write(line); log.flush()
                    with condition: lines.append((name, line)); condition.notify_all()
                    if name == 'clients' or any(x in line for x in ('PROCESSING_', 'Done (', 'ERROR', 'Exception', 'Stopping server')):
                        print(name + ': ' + line.rstrip()[:320], flush=True)
            rec.update(exit_code=p.wait(), exited_at=time.time())
        t = threading.Thread(target=read, daemon=True); t.start(); readers.append(t)
        return p

    def await_state(predicate, seconds=90):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            if any('PROCESSING_FAILED' in line or '"kind":"FAILED"' in line or ' ERROR]' in line
                   or 'history scan retained its cursor:' in line or 'History scan retained pending evidence:' in line for _, line in lines):
                raise RuntimeError('Acceptance/source failure reported')
            if server.poll() is not None: raise RuntimeError('Server exited before acceptance')
            if predicate(): return
            with condition: condition.wait(.2)
        raise TimeoutError('Live checkpoint did not arrive')

    def send(action):
        pos = len(lines); server.stdin.write('processingverify ' + action + '\n'); server.stdin.flush()
        await_state(lambda: any('PROCESSING_DONE' in line and '"action":"' + action.split()[0] + '"' in line for _, line in lines[pos:]))

    def checkpoint(count, contributions, resource_count):
        def ready():
            try:
                s = database_state(run)
                return s['source'] is not None and s['source'][1:] == (count, 1) and s['pending'] == 0 and s['receipts'] == count and s['contributions'] == contributions and s['archive'] == 1 and s['resource_sequence'] == (resource_count,)
            except sqlite3.OperationalError: return False
        await_state(ready); send('check ' + str(count)); state = database_state(run); result['checkpoints'].append(state)
        print('PROCESSING_DATABASE ' + json.dumps(state), flush=True)

    def chat(who, command, expected):
        pos = len(lines); clients.stdin.write('chat ' + who + ' /resource factory ' + command + '\n'); clients.stdin.flush()
        await_state(lambda: any(name == 'clients' and '"who":"' + who + '"' in line and expected in line for name, line in lines[pos:]))

    def stop_clients():
        nonlocal clients
        if clients is not None and clients.poll() is None:
            clients.stdin.write('quit\n'); clients.stdin.flush(); clients.wait(timeout=20)
            if clients.returncode != 0: raise RuntimeError('Clients exited abnormally')
            await_state(lambda: sum('"kind":"ENDED"' in line for name, line in lines if name == 'clients') >= 2)

    command = [str(args.java_home / 'bin/java'), '-Xms64m', '-Xmx448m', '-Xss512k', '-XX:+UseSerialGC', '-XX:ActiveProcessorCount=1',
               '-XX:TieredStopAtLevel=1', '-XX:ReservedCodeCacheSize=64m', '-XX:CompressedClassSpaceSize=64m', '-Dfile.encoding=UTF-8',
               '-Dterminal.jline=false', '-Dterminal.ansi=false', '-jar', 'paper.jar', 'nogui']
    try:
        server = launch(command, 'server')
        await_state(lambda: any('Done (' in line for _, line in lines) and any('PROCESSING_READY' in line for _, line in lines))
        targets = json.loads((run / 'evidence/targets.json').read_text())
        if args.boot == 1: checkpoint(1, 0, 3)
        elif args.boot == 3: checkpoint(137, 12, 139)
        env = os.environ.copy(); env['PROCESSING_MINEFLAYER_MODULE'] = str(args.client_module.resolve())
        clients = launch([str(args.node), '--max-old-space-size=192', str(run / 'fixture/processing-paper-clients.cjs'), str(run)], 'clients', env)
        await_state(lambda: sum('"kind":"SPAWN"' in line for name, line in lines if name == 'clients') >= 2)
        f, first, active, last = (targets[k] for k in ('factory', 'first', 'long', 'last'))
        if args.boot == 1:
            chat('A', f'build {f} SMELTER NativeFactory', '工厂已保存')
            chat('B', f'start {f} acceptance-smelt 2 {first}', '当前国家国主')
            chat('A', f'start {f} acceptance-smelt 2 {first}', '批次已提交')
            chat('A', f'start {f} acceptance-smelt 2 {first}', '批次已提交')
            chat('A', f'start {f} acceptance-smelt 3 {first}', '参数无效')
            chat('A', f'complete {first}', '尚未到期')
            send('handover B')
            chat('A', f'off {f}', '当前国家国主')
            chat('B', f'upgrade {f}', '工厂等级已保存')
            start_time = time.monotonic(); await_state(lambda: time.monotonic() - start_time >= 5)
            chat('B', f'complete {first}', '批次已交付'); checkpoint(3, 4, 5)
            # The second run starts after the upgrade (efficiency 1.05), so reset only the
            # configured recipe duration to 31 seconds: ceil(31/1.05)=30. The product freezes it.
            chat('B', f'start {f} acceptance-long 1 {active}', '批次已提交')
            checkpoint(4, 4, 6)
            stop_clients(); send('offline')
        elif args.boot == 2:
            send('handover A')
            chat('B', f'complete {active}', '当前国家国主')
            # A normal global timer may already complete the run. The same run ID returns its
            # original receipt regardless; completion time and counts are checked independently.
            start_time = time.monotonic(); await_state(lambda: time.monotonic() - start_time >= 31)
            chat('A', f'complete {active}', '批次已交付'); checkpoint(135, 8, 137)
            chat('A', f'start {f} acceptance-smelt 1 {last}', '批次已提交')
            start_time = time.monotonic(); await_state(lambda: time.monotonic() - start_time >= 3)
            chat('A', f'complete {last}', '批次已交付'); checkpoint(137, 12, 139)
            send('replay'); checkpoint(137, 12, 139); stop_clients()
        else:
            chat('A', f'receipt {first}', '原操作者')
            chat('A', f'complete {last}', '批次已交付')
            chat('A', f'start {f} acceptance-smelt 1 {last}', '批次已提交')
            send('replay'); checkpoint(137, 12, 139); stop_clients()
        result['passed'] = True
    except BaseException as error:
        result['error'] = repr(error); raise
    finally:
        if clients is not None and clients.poll() is None:
            clients.stdin.write('quit\n'); clients.stdin.flush()
            try: clients.wait(timeout=20)
            except subprocess.TimeoutExpired: clients.kill(); clients.wait(); result['passed'] = False
        if server is not None and server.poll() is None:
            server.stdin.write('stop\n'); server.stdin.flush()
            try: server.wait(timeout=45)
            except subprocess.TimeoutExpired: server.kill(); server.wait(); result['passed'] = False; result['forced_kill'] = True
        for reader in readers: reader.join(timeout=5)
        if any(record.get('exit_code') != 0 for record in records): result['passed'] = False
        if not any('Stopping server' in line for _, line in lines) or any('PROCESSING_FAILED' in line or ' ERROR]' in line for _, line in lines): result['passed'] = False
        for name in ('StarChronicle', 'StarCore', FIXTURE):
            if (run / 'plugins' / name).exists(): shutil.copytree(run / 'plugins' / name, Path(str(prefix) + '.data') / name)
        write(Path(str(prefix) + '.session.json'), json.dumps(result, indent=2) + '\n')
    if not result['passed']: raise RuntimeError('Acceptance or shutdown did not pass')
    print(json.dumps(result), flush=True)


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__); subs = p.add_subparsers(dest='action', required=True)
    prep = subs.add_parser('prepare'); prep.add_argument('--product', type=Path, default=ROOT / 'target/starchronicle-0.1.0-SNAPSHOT.jar')
    prep.add_argument('--starcore', type=Path, required=True); prep.add_argument('--runtime', type=Path, required=True)
    prep.add_argument('--attempt', required=True); prep.add_argument('--port', type=int, required=True); prep.add_argument('--java-home', type=Path, required=True)
    session = subs.add_parser('run'); session.add_argument('--run', type=Path, required=True); session.add_argument('--java-home', type=Path, required=True)
    session.add_argument('--boot', type=int, choices=(1,2,3), required=True); session.add_argument('--node', type=Path, required=True)
    session.add_argument('--client-module', type=Path, required=True)
    args = p.parse_args(); (prepare if args.action == 'prepare' else execute)(args)
