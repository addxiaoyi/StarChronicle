"""Own one serial, fingerprint-bound local Folia standalone boot and its optional real clients.

Input: `server <console command>`, `clients start`, `client <controller command>`, `status`.
Stop clients with `client quit`, then use `server stop`. No process starts on import.
"""
from pathlib import Path
import argparse
import hashlib
import json
import queue
import re
import subprocess
import sys
import threading
import time


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-dir',type=Path,required=True)
    parser.add_argument('--expected-sha',required=True)
    parser.add_argument('--boot',type=int,choices=[1,2,3],required=True)
    parser.add_argument('--java-home',type=Path,default=Path(r'C:\Users\Administrator\Documents\luoseng\.toolchains\jdk-21.0.13.0.2+1'))
    parser.add_argument('--node',type=Path,default=Path(r'C:\Users\Administrator\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe'))
    args=parser.parse_args();run=args.run_dir.resolve()
    if not sys.stdin.isatty():raise SystemExit("This controller requires a persistent interactive TTY; launch exec_command with tty=true before starting Java")
    expected=(Path(__file__).resolve().parents[1]/"run"/("folia-national-"+args.expected_sha[:8])).resolve()
    if not re.fullmatch(r"[0-9a-f]{64}",args.expected_sha) or run!=expected:raise SystemExit("Require this exact worktree/run/platform-national-<sha8> and full SHA")
    if not run.name.startswith('folia-national-') or not (run/'preparation.json').is_file():
        raise SystemExit('Require an isolated prepared folia-national-* directory')
    manifest=json.loads((run/'preparation.json').read_text(encoding='utf-8'))
    if manifest['plugin_sha256']!=args.expected_sha or digest(run/'plugins/StarChronicle.jar')!=args.expected_sha:
        raise SystemExit('Product fingerprint drift')
    fixture=run/'plugins/NationalCouncilFoliaFixture.jar'
    if not fixture.is_file() or digest(fixture)!=manifest.get('fixture_sha256'):
        raise SystemExit('Require the compiled fixture fingerprint')
    if digest(run/'folia.jar')!=manifest.get('server_sha256'):raise SystemExit('Server fingerprint drift')
    if {p.name for p in (run/'plugins').glob('*.jar')}!={'StarChronicle.jar','NationalCouncilFoliaFixture.jar'}:raise SystemExit('Standalone Folia must have no native authority or extra plugin JARs')
    java=args.java_home/'bin/java.exe'
    if not java.is_file() or not args.node.is_file():raise SystemExit('Explicit Java/Node runtime missing')
    evidence=run/'evidence';evidence.mkdir(exist_ok=True)
    process_path=evidence/'national-council-folia-processes.json'
    records=json.loads(process_path.read_text(encoding='utf-8')) if process_path.exists() else {'plugin_sha256':args.expected_sha,'boots':[]}
    if len(records['boots'])!=args.boot-1 or any(b.get('server_exit')!=0 or b.get('client_exit') not in (None,0) for b in records['boots']):
        raise SystemExit('Previous serial boots must have exited normally')
    server_log=evidence/f'national-council-folia-boot{args.boot}.log'
    if server_log.exists():raise SystemExit('Refusing to overwrite observed boot evidence')
    command=[str(java),'-Xms256M','-Xmx768M','-XX:MaxMetaspaceSize=256M','-Dfile.encoding=UTF-8','-jar','folia.jar','--nogui']
    flags=getattr(subprocess,'CREATE_NO_WINDOW',0)
    server=subprocess.Popen(command,cwd=run,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace',bufsize=1,creationflags=flags)
    record={'boot':args.boot,'server_pid':server.pid,'server_command':command,'started_at':time.time(),'server_exit':None,'client_exit':None}
    records['boots'].append(record)
    def save():process_path.write_text(json.dumps(records,indent=2,ensure_ascii=False),encoding='utf-8')
    save();print('SERVER_STARTED '+json.dumps(record),flush=True)
    client=None;client_thread=None
    def pump(process,path,prefix):
        with path.open('x',encoding='utf-8') as output:
            for line in process.stdout:
                output.write(line);output.flush();print(prefix+line.rstrip(),flush=True)
    server_thread=threading.Thread(target=pump,args=(server,server_log,'SERVER '),daemon=True);server_thread.start()
    inputs=queue.Queue()
    def read_input():
        for line in sys.stdin:inputs.put(line.strip())
        inputs.put(None)
    threading.Thread(target=read_input,daemon=True).start()
    try:
        while server.poll() is None:
            try:line=inputs.get(timeout=.25)
            except queue.Empty:continue
            if line is None:
                print('INPUT_CLOSED_REQUESTING_NORMAL_STOP',flush=True);server.stdin.write('stop\n');server.stdin.flush();break
            if line=='status':
                print('STATUS '+json.dumps({'server':server.poll(),'client':client.poll() if client else None}),flush=True)
            elif line=='clients start':
                if client is not None:raise RuntimeError('Clients already started for this boot')
                script=Path(__file__).with_name('national-council-folia-clients.cjs').resolve()
                command=[str(args.node),str(script),'--run-dir',str(run),'--boot',str(args.boot),'--expected-sha',args.expected_sha]
                client=subprocess.Popen(command,cwd=run,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace',bufsize=1,creationflags=flags)
                record['client_pid']=client.pid;record['client_command']=command;save()
                client_thread=threading.Thread(target=pump,args=(client,evidence/f'national-council-folia-client-boot{args.boot}.log','CLIENT '),daemon=True);client_thread.start()
            elif line.startswith('server '):server.stdin.write(line[7:]+'\n');server.stdin.flush()
            elif line.startswith('client '):
                if client is None or client.poll() is not None:raise RuntimeError('Clients are not running')
                client.stdin.write(line[7:]+'\n');client.stdin.flush()
            else:print('UNKNOWN_CONTROL '+line,flush=True)
    finally:
        cleanup_errors=[]
        try:
            if client is not None:
                if client.poll() is None:
                    try:client.stdin.write('quit\n');client.stdin.flush();client.stdin.close()
                    except (OSError,BrokenPipeError) as error:cleanup_errors.append('client_input: '+repr(error))
                try:record['client_exit']=client.wait(timeout=45)
                except subprocess.TimeoutExpired as error:cleanup_errors.append('client_wait: '+repr(error))
        finally:
            try:
                if server.poll() is None:
                    try:server.stdin.write('stop\n');server.stdin.flush()
                    except (OSError,BrokenPipeError) as error:cleanup_errors.append('server_input: '+repr(error))
                try:record['server_exit']=server.wait(timeout=60)
                except subprocess.TimeoutExpired as error:cleanup_errors.append('server_wait: '+repr(error))
            finally:
                record['finished_at']=time.time();record['cleanup_errors']=cleanup_errors
                server_thread.join(timeout=5)
                if client_thread:client_thread.join(timeout=5)
                save();print('PROCESSES_FINISHED '+json.dumps(record),flush=True)
        if cleanup_errors:raise RuntimeError('Owned process cleanup requires attention; inspect retained process record: '+repr(cleanup_errors))
    if record['server_exit']!=0 or record['client_exit'] not in (None,0):raise SystemExit(1)


if __name__=='__main__':
    sys.stdout.reconfigure(encoding='utf-8',errors='backslashreplace')
    sys.stderr.reconfigure(encoding='utf-8',errors='backslashreplace')
    main()
