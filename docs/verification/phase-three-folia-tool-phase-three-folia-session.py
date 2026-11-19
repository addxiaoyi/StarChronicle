"""Run one explicitly authorized serial Folia boot and record actual process exits.

Input routing: server <console command>, client <protocol-client action>.
Clients require an explicit acknowledgement after Done; boot one also requires
an offline API callback and read-only snapshot. Quit clients before server stop.
Launch this interactive process with a real TTY. EOF triggers normal cleanup.
"""
from pathlib import Path
from datetime import datetime, timezone
import argparse, atexit, hashlib, json, os, queue, subprocess, sys, threading, time

os.environ['PYTHONIOENCODING'] = 'utf-8'
for stream in (sys.stdout, sys.stderr):
    if hasattr(stream, 'reconfigure'):
        stream.reconfigure(encoding='utf-8', errors='backslashreplace')
if hasattr(sys.stdin, 'reconfigure'):
    sys.stdin.reconfigure(encoding='utf-8', errors='replace')

ROOT = Path(__file__).resolve().parents[1]

def now():
    return datetime.now(timezone.utc).isoformat()

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-dir', type=Path, required=True)
    parser.add_argument('--expected-sha', required=True)
    parser.add_argument('--boot', type=int, choices=[1, 2, 3], required=True)
    parser.add_argument('--java', type=Path, default=Path(os.environ.get('JAVA_HOME', '')) / 'bin' / 'java.exe')
    parser.add_argument('--node', default='node')
    args = parser.parse_args()
    run = args.run_dir.resolve()
    manifest_path = run / 'acceptance-preparation.json'
    manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
    if run.parent != (ROOT / 'run').resolve() or not run.name.startswith('phase-three-folia-'):
        parser.error('require isolated phase-three-folia-* directory')
    if digest(run / 'plugins' / 'StarChronicle.jar') != args.expected_sha or manifest['plugin_sha256'] != args.expected_sha:
        raise SystemExit('Refusing product fingerprint drift')
    if not manifest.get('fixture_compiled') or digest(run / 'plugins' / 'PhaseThreeFoliaFixture.jar') != manifest.get('fixture_sha256'):
        raise SystemExit('Require the prepared, compiled test-only fixture')
    evidence = run / 'evidence'
    log_path = evidence / f'phase-three-folia-boot{args.boot}.log'
    if log_path.exists():
        raise SystemExit('Refusing to overwrite an already observed server boot')
    process_path = evidence / 'phase-three-folia-processes.json'
    processes = json.loads(process_path.read_text(encoding='utf-8')) if process_path.exists() else {'scope': manifest['scope'], 'plugin_sha256': args.expected_sha, 'fixture_compile_exit_code': manifest.get('fixture_compile_exit_code'), 'boots': [], 'server_exit_codes': [], 'client_exit_codes': []}
    if len(processes['boots']) != args.boot - 1 or any(value != 0 for value in processes['server_exit_codes'] + processes['client_exit_codes']):
        raise SystemExit('Prior observed boots must be complete and successful before continuing serially')
    record = {'boot': args.boot, 'plugin_sha256': args.expected_sha, 'server_started_utc': now(), 'normal_console_stop_requested': False, 'clients_quit_requested': False}
    command = [str(args.java), '-Xms256M', '-Xmx768M', '-jar', 'folia.jar', '--nogui']
    creationflags = getattr(subprocess, 'CREATE_NO_WINDOW', 0)
    client = None
    server = subprocess.Popen(command, cwd=run, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding='utf-8', errors='replace', bufsize=1, creationflags=creationflags)
    def cleanup_owned_children():
        for process, request in ((client, 'quit'), (server, 'stop')):
            if process is not None and process.poll() is None:
                try:
                    process.stdin.write(request + '\n');process.stdin.flush()
                except Exception as problem:
                    record.setdefault('cleanup_errors', []).append(repr(problem))
        for process in (client, server):
            if process is not None and process.poll() is None:
                try:
                    process.wait(timeout=60)
                except subprocess.TimeoutExpired:
                    record.setdefault('forced_cleanup_pids', []).append(process.pid)
                    process.kill();process.wait(timeout=10)
    atexit.register(cleanup_owned_children)
    record.update(server_pid=server.pid, server_command=command)
    manifest['server_started'] = True
    manifest['status'] = f'BOOT_{args.boot}_RUNNING'
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    events = queue.Queue()
    server_ready = False

    def read_process(kind, process, log):
        with log.open('w', encoding='utf-8') as output:
            for line in process.stdout:
                output.write(line);output.flush();events.put((kind, line.rstrip('\n')))
        events.put((kind + '-ended', process.wait()))

    def read_input():
        for line in sys.stdin:
            events.put(('input', line.rstrip('\r\n')))
        events.put(('stdin-eof', None))

    threading.Thread(target=read_process, args=('server', server, log_path), daemon=True).start()
    threading.Thread(target=read_input, daemon=True).start()
    print('Authorized boot started. Route commands with server / client prefixes.', flush=True)
    server_code = client_code = None
    started = time.monotonic()
    try:
        while server_code is None or client is not None and client_code is None:
            try:
                kind, value = events.get(timeout=30)
            except queue.Empty:
                if time.monotonic() - started > 20 * 60:
                    raise TimeoutError('Bounded serial acceptance boot exceeded twenty minutes')
                continue
            if kind in ['server', 'client']:
                print(f'[{kind}] {value}', flush=True)
                if kind == 'server' and 'Done (' in value:
                    server_ready = True
                    print('Server ready; clients require an explicit clients acknowledgement.', flush=True)
                if kind == 'server' and 'PHASE_THREE_EVALUATED ' in value:
                    payload = json.loads(value.split('PHASE_THREE_EVALUATED ', 1)[1])
                    if args.boot == 1 and payload.get('online') == 0 and payload.get('status') == 'QUEUED':
                        record['offline_evaluation_ack'] = payload
                        record['offline_evaluation_ack_observed_utc'] = now()
            elif kind == 'input':
                target, _, command_text = value.partition(' ')
                if target == 'clients':
                    if not server_ready or client is not None:
                        print('Clients require a ready server and may start only once.', flush=True);continue
                    if args.boot == 1:
                        capture = evidence / 'phase-three-folia-offline-evaluated.json'
                        if not record.get('offline_evaluation_ack') or not capture.is_file():
                            print('Refused: require successful offline API acknowledgement and read-only offline-evaluated snapshot first.', flush=True);continue
                        snapshot = json.loads(capture.read_text(encoding='utf-8'))
                        if snapshot.get('plugin_sha256') != args.expected_sha or snapshot.get('schema_version') != 4 or snapshot.get('stage') != 'offline-evaluated' or len(snapshot.get('instances', [])) != 3 or len(snapshot.get('outcomes', [])) != 17:
                            print('Refused: offline snapshot is not the unchanged real baseline result state.', flush=True);continue
                        if datetime.fromisoformat(snapshot['captured_utc']) <= datetime.fromisoformat(record['offline_evaluation_ack']['observedAt'].replace('Z', '+00:00')):
                            print('Refused: snapshot must follow the actual successful evaluation callback.', flush=True);continue
                        record.update(offline_snapshot_sha256=digest(capture), offline_snapshot_captured_utc=snapshot['captured_utc'])
                    record.update(client_start_ack=True, client_start_ack_utc=now())
                    client_command = [args.node, str(ROOT / 'tools' / 'phase-three-folia-clients.cjs'), '--run-dir', str(run), '--boot', str(args.boot)]
                    client = subprocess.Popen(client_command, cwd=ROOT, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding='utf-8', errors='replace', bufsize=1, creationflags=creationflags)
                    record.update(client_pid=client.pid, client_command=client_command, client_started_utc=now())
                    threading.Thread(target=read_process, args=('client', client, evidence / f'phase-three-folia-client-boot{args.boot}.log'), daemon=True).start()
                    continue
                process = server if target == 'server' else client if target == 'client' else None
                if process is None or process.poll() is not None:
                    print('No running target; use server <command> or client <action>.', flush=True)
                else:
                    if target == 'server' and command_text.strip() == 'stop':
                        record['normal_console_stop_requested'] = True
                    if target == 'client' and command_text.strip() == 'quit':
                        record['clients_quit_requested'] = True
                    process.stdin.write(command_text + '\n');process.stdin.flush()
            elif kind == 'server-ended':
                server_code = value;record.update(server_exit_code=value, server_ended_utc=now())
                print('Observed server exit:', value, flush=True)
            elif kind == 'client-ended':
                client_code = value;record.update(client_exit_code=value, client_ended_utc=now())
                print('Observed client exit:', value, flush=True)
            elif kind == 'stdin-eof':
                raise EOFError('Interactive input ended; shutting down only this launcher owned children')
            if time.monotonic() - started > 20 * 60:
                raise TimeoutError('Bounded serial acceptance boot exceeded twenty minutes')
    except BaseException as error:
        record['launcher_problem'] = repr(error)
        cleanup_owned_children()
        server_code = server.returncode
        client_code = None if client is None else client.returncode
        record.update(server_exit_code=server_code, client_exit_code=client_code, server_ended_utc=now(), client_ended_utc=now())
    finally:
        cleanup_owned_children()
        record['plugin_sha256_after'] = digest(run / 'plugins' / 'StarChronicle.jar')
        record['all_owned_children_exited'] = server.poll() is not None and client is not None and client.poll() is not None
        processes['boots'].append(record)
        processes['server_exit_codes'].append(server_code)
        processes['client_exit_codes'].append(client_code)
        processes['normal_console_stop_each_server'] = len(processes['boots']) == 3 and all(item.get('normal_console_stop_requested') for item in processes['boots'])
        processes['clients_quit_normally_each_boot'] = len(processes['boots']) == 3 and all(item.get('clients_quit_requested') and item.get('client_exit_code') == 0 for item in processes['boots'])
        processes['all_owned_children_exited'] = len(processes['boots']) == 3 and all(item.get('all_owned_children_exited') for item in processes['boots'])
        process_path.write_text(json.dumps(processes, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        manifest['status'] = f'BOOT_{args.boot}_OBSERVED'
        manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    return 0 if server_code == client_code == 0 and 'launcher_problem' not in record and record['plugin_sha256_after'] == args.expected_sha else 1

if __name__ == '__main__':
    raise SystemExit(main())
