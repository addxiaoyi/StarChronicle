"""Run one isolated Paper boot and its clients with complete append-only evidence."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import socket
import subprocess
import sys
import threading
import time
import shutil

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--run', type=Path, required=True)
parser.add_argument('--java-home', type=Path, required=True)
parser.add_argument('--node', type=Path, required=True)
parser.add_argument('--boot', type=int, required=True)
parser.add_argument('--scenario', choices=['first', 'second', 'third'])
args = parser.parse_args()
run = args.run.resolve()
if run.parent != (ROOT / 'run').resolve() or not run.name.startswith('paper-epic-'):
    raise SystemExit('Require an isolated epic run')
manifest = json.loads((run / 'preparation.json').read_text(encoding='utf-8'))
for name, key in [('StarChronicle', 'plugin_sha256'), ('StarCore', 'starcore_sha256'), ('EpicCampaignPaperFixture', 'fixture_sha256')]:
    if hashlib.sha256((run / f'plugins/{name}.jar').read_bytes()).hexdigest() != manifest[key]:
        raise SystemExit('Frozen artifact drift: ' + name)
if manifest.get('combination_acceptance'):
    for name, entry in json.loads((run / 'combination.json').read_text())['artifacts'].items():
        if hashlib.sha256((run / f'plugins/{name}.jar').read_bytes()).hexdigest() != entry['sha256']:
            raise SystemExit('Frozen combination artifact drift: ' + name)
probe = socket.socket()
probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
probe.bind(('127.0.0.1', manifest['server_port']))
probe.close()
flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
prefix = run / f'evidence/boot{args.boot}'
if prefix.with_suffix('.server.log').exists():
    raise SystemExit('Refusing to overwrite a prior boot log')
shutil.copy2(__file__, Path(str(prefix) + '.controller.py'))
shutil.copy2(ROOT / 'tools/epic-paper-clients.cjs', Path(str(prefix) + '.clients.cjs'))
handles = []
readers = []
process_records = []
observed = []
condition = threading.Condition()


def start(command, name):
    process = subprocess.Popen(command, cwd=run, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, text=True, encoding='utf-8', errors='replace',
                               creationflags=flags, bufsize=1)
    handles.append(process)
    record = dict(name=name, pid=process.pid, command=command, started_at=time.time())
    process_records.append(record)
    def read():
        with Path(str(prefix) + f'.{name}.log').open('x', encoding='utf-8') as log:
            for line in process.stdout:
                log.write(line)
                log.flush()
                with condition:
                    observed.append((name, line))
                    condition.notify_all()
                if (name == 'clients' and '"kind":"MESSAGE"' not in line) or any(word in line for word in ('EPIC_', 'COMBO_', 'Done (', 'ERROR', 'Exception', 'Stopping server')):
                    print(name + ': ' + (line.rstrip()[:700] + (' [full record retained in log]' if len(line)>700 else '')), flush=True)
        record.update(exit_code=process.wait(), exited_at=time.time())
        print(f'{name}: EXIT {record["exit_code"]}', flush=True)
    reader = threading.Thread(target=read, daemon=True)
    readers.append(reader)
    reader.start()
    return process


java = args.java_home / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
server = start([str(java), '-Xms64m', '-Xmx768m' if manifest.get('combination_acceptance') else '-Xmx384m', '-Xss512k',
                '-XX:+UseSerialGC', '-XX:ActiveProcessorCount=1', '-XX:TieredStopAtLevel=1',
                '-XX:ReservedCodeCacheSize=64m', '-XX:CompressedClassSpaceSize=64m', '-Dfile.encoding=UTF-8',
                '-Dterminal.jline=false', '-Dterminal.ansi=false', '-jar', 'paper.jar', 'nogui'], 'server')
clients = None
print(json.dumps(dict(server_pid=server.pid, run=str(run), boot=args.boot)), flush=True)


def await_lines(cursor, predicate, seconds=45):
    deadline = time.monotonic() + seconds
    with condition:
        while True:
            fresh = observed[cursor:]
            failures = [line for name, line in fresh if 'EPIC_FAILED ' in line or 'COMBO_FAILED ' in line or '"kind":"FAILED"' in line]
            if failures:
                raise RuntimeError('Observed failure: ' + failures[-1])
            if predicate(fresh):
                return
            if server.poll() is not None or (clients is not None and clients.poll() is not None):
                raise RuntimeError('Acceptance process exited before the expected observation')
            if time.monotonic() >= deadline:
                raise TimeoutError('No matching live observation within deadline')
            condition.wait(min(1, deadline - time.monotonic()))


def server_action(action):
    cursor = len(observed)
    server.stdin.write('epicverify ' + action + '\n'); server.stdin.flush()
    name = action.split()[0]
    await_lines(cursor, lambda lines: any('EPIC_ACTION_DONE ' in line and '"action":"' + name + '"' in line for _, line in lines))


def client_action(action):
    cursor = len(observed)
    clients.stdin.write(action + '\n'); clients.stdin.flush()
    name = action.split()[0]
    await_lines(cursor, lambda lines: any('"kind":"ACTION_DONE"' in line and '"action":"' + name + '"' in line for _, line in lines))


def combination(phase):
    def action(value):
        cursor = len(observed)
        server.stdin.write('comboverify ' + value + '\n'); server.stdin.flush()
        await_lines(cursor, lambda lines: any('COMBO_ACTION_DONE ' in line and '"action":"' + value.split()[0] + '"' in line for _, line in lines))
    def chat(value, expected):
        cursor = len(observed)
        client_action('chat A ' + value)
        await_lines(cursor, lambda lines: any(name == 'clients' and expected in line for name, line in lines))
    action('setup')
    source = json.loads((run / 'evidence/combination-target.json').read_text())['source']
    if phase == 'first':
        action('move denied')
        chat('/chronicle memory create combo-no-permission ' + source, '没有管理权限')
        action('admin true')
        chat('/chronicle memory create combo-denied ' + source, 'WorldGuard 不允许')
        action('move edge')
        chat('/chronicle memory create combo-edge ' + source, 'WorldGuard 不允许')
        action('move allowed')
        chat('/chronicle memory create combo-allowed ' + source, '纪念点已保存')
        time.sleep(1)
        action('memory true')
        action('cancel-npc true')
        cursor = len(observed); client_action('npc A')
        await_lines(cursor, lambda lines: any('COMBO_NPC_PACKET ' in line and '"cancelled":true' in line for _, line in lines))
        time.sleep(1); action('npc-history 0')
        action('cancel-npc false')
    else:
        action('move allowed'); action('memory true')
        action('npc-history ' + str(args.boot - 1))
    cursor = len(observed); client_action('npc A')
    await_lines(cursor, lambda lines: any('COMBO_MENU_OPEN ' in line for _, line in lines))
    action('npc-history ' + str(args.boot)); client_action('close A')
    if phase == 'third':
        action('admin true')
        chat('/chronicle memory remove combo-allowed', '纪念点已停用')
        time.sleep(1); action('memory false')
        if manifest.get('combination_maintenance_offline'):
            cursor = len(observed); client_action('disconnect')
            await_lines(cursor, lambda lines: all(any(name == 'server' and 'Epic' + who + ' left the game' in line for name, line in lines) for who in 'ABCD'))
            action('offline')
        action('disable-npc')
        action('disable-protection')
        if manifest.get('combination_maintenance_offline'):
            cursor = len(observed); client_action('connect A')
            await_lines(cursor, lambda lines: any('"kind":"SPAWN"' in line and '"who":"A"' in line for _, line in lines))
            action('admin true')
        chat('/chronicle memory create combo-provider-disabled ' + source, '领地保护服务暂不可用')


def scenario(phase):
    global clients
    await_lines(0, lambda lines: any('Done (' in line for _, line in lines) and any('EPIC_READY ' in line for _, line in lines), 180)
    cursor = len(observed)
    clients = start([str(args.node), '--max-old-space-size=192', str(ROOT / 'tools/epic-paper-clients.cjs'), str(run)], 'clients')
    await_lines(cursor, lambda lines: all(any('"kind":"SPAWN"' in line and '"who":"' + who + '"' in line for _, line in lines) for who in 'ABCD'))
    server_action('seed')
    if phase == 'first':
        server_action('open')
        for who in 'AB':
            server_action('task-ready ' + who)
            client_action('craft ' + who)
            server_action('task-complete ' + who)
        time.sleep(3)
        server_action('preparations')
        if manifest.get('ledger_acceptance'):
            server_action('contributions')
            server_action('member-exit')
        server_action('advance')
        if manifest['route'] == 'DUNGEON':
            for who in 'AB':
                server_action('dungeon ' + who)
                time.sleep(6)
        else:
            server_action('found')
            server_action('declare')
            server_action('end-war')
        server_action('checkpoint')
    elif phase == 'second':
        if manifest.get('ledger_acceptance'):
            server_action('contributions')
            server_action('member-rejoin')
        server_action('final-evidence')
        server_action('settle')
        time.sleep(3)
        server_action('check')
        server_action('replay')
        server_action('check')
    elif phase == 'third':
        server_action('check')
        server_action('replay')
        server_action('check')
    else:
        raise ValueError('first|second|third')
    if manifest.get('ledger_acceptance'):
        server_action('contributions')
    if manifest.get('combination_acceptance'):
        combination(phase)
    clients.stdin.write('quit\n'); clients.stdin.flush(); clients.wait(timeout=15)
    server.stdin.write('stop\n'); server.stdin.flush(); server.wait(timeout=60)
    if clients.returncode != 0 or server.returncode != 0:
        raise RuntimeError('Acceptance processes must exit normally')
    print('SCENARIO_COMPLETE ' + phase, flush=True)
outcome = dict(boot=args.boot, scenario=args.scenario, status='INCOMPLETE')
try:
    commands = ['scenario ' + args.scenario] if args.scenario else sys.stdin
    for raw in commands:
        line = raw.strip()
        if line.startswith('scenario '):
            outcome['scenario'] = line.split()[1]
            scenario(line.split()[1])
            outcome['status'] = 'COMPLETE'
            break
        elif line == 'clients start':
            if clients is not None:
                raise RuntimeError('Clients already started')
            clients = start([str(args.node), '--max-old-space-size=192', str(ROOT / 'tools/epic-paper-clients.cjs'), str(run)], 'clients')
        elif line.startswith('client '):
            if clients is None:
                raise RuntimeError('Clients are not started')
            clients.stdin.write(line[7:] + '\n'); clients.stdin.flush()
        elif line.startswith('server '):
            if server.poll() is not None:
                print('Server already exited: ' + str(server.returncode), flush=True)
                break
            server.stdin.write(line[7:] + '\n'); server.stdin.flush()
            if line == 'server stop':
                server.wait(timeout=60)
                break
        else:
            print('Expected scenario first|second|third | clients start | client ACTION | server COMMAND', flush=True)
except BaseException as error:
    outcome.update(status='FAILED', error=str(error))
    raise
finally:
    if clients is not None and clients.poll() is None:
        clients.stdin.write('quit\n'); clients.stdin.flush()
        try: clients.wait(timeout=15)
        except subprocess.TimeoutExpired: clients.terminate()
    if server.poll() is None:
        server.stdin.write('stop\n'); server.stdin.flush()
        try: server.wait(timeout=60)
        except subprocess.TimeoutExpired:
            print('Server did not stop normally; investigate retained PID ' + str(server.pid), flush=True)
    for reader in readers:
        reader.join(timeout=5)
    outcome['processes'] = process_records
    with Path(str(prefix) + '.session.json').open('x', encoding='utf-8') as log:
        json.dump(outcome, log, indent=2)
        log.write('\n')
