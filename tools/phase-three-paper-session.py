"""Run one explicitly authorized serial Paper boot; clients start only on `clients start`.

Routes server/client input and records actual process exits. No run is started by import.
"""
from pathlib import Path
import argparse
import importlib.util
import json
import queue
import subprocess
import sys
import threading
import time

sys.stdout.reconfigure(encoding='utf-8',errors='backslashreplace')
sys.stderr.reconfigure(encoding='utf-8',errors='backslashreplace')

spec = importlib.util.spec_from_file_location('phase_three_paper_common', Path(__file__).with_name('phase-three-paper-common.py'))
c = importlib.util.module_from_spec(spec); spec.loader.exec_module(c)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-dir',type=Path,required=True)
    parser.add_argument('--expected-sha',required=True)
    parser.add_argument('--boot',type=int,choices=[1,2,3],required=True)
    parser.add_argument('--java-home',type=Path,default=Path(r'C:\Users\Administrator\Documents\luoseng\.toolchains\jdk-21.0.13.0.2+1'))
    parser.add_argument('--node',type=Path,default=Path(r'C:\Users\Administrator\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe'))
    args=parser.parse_args();run=c.run_path(args.run_dir);value=c.manifest(run,args.expected_sha)
    if not value.get('fixture_compiled') or c.digest(run/'plugins/PhaseThreePaperFixture.jar')!=value.get('fixture_sha256'):
        raise SystemExit('Require the actually compiled, pinned fixture')
    if not c.source_unchanged(value):raise SystemExit('Original phase-two source drifted')
    java=args.java_home/'bin/java.exe'
    if not java.is_file() or not args.node.is_file():raise SystemExit('Explicit Java/Node runtime missing')
    evidence=run/'evidence';process_path=evidence/'phase-three-paper-processes.json'
    processes=json.loads(process_path.read_text(encoding='utf-8')) if process_path.exists() else {'scope':value['scope'],'plugin_sha256':args.expected_sha,'boots':[],'server_exit_codes':[],'client_exit_codes':[]}
    if len(processes['boots'])!=args.boot-1 or any(code!=0 for code in processes['server_exit_codes']+processes['client_exit_codes']):
        raise SystemExit('Prior serial boots must have actually exited normally')
    server_log=evidence/f'phase-three-paper-boot{args.boot}.log'
    if server_log.exists():raise SystemExit('Refusing to overwrite an observed server boot')
    command=[str(java),'-Xms256M','-Xmx768M','-XX:MaxMetaspaceSize=256M','-Dfile.encoding=UTF-8','-jar','paper.jar','--nogui']
    flags=getattr(subprocess,'CREATE_NO_WINDOW',0)
    server=subprocess.Popen(command,cwd=run,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace',bufsize=1,creationflags=flags)
    record={'boot':args.boot,'server_started_utc':c.now(),'server_pid':server.pid,'server_command':command,'commands':[],
            'normal_console_stop_requested':False,'clients_quit_requested':False,'plugin_sha256':args.expected_sha}
    value.update(server_started=True,status=f'BOOT_{args.boot}_RUNNING');c.write_json(run/'preparation.json',value)
    events=queue.Queue();client=None;server_code=client_code=None;ready=False;started=time.monotonic()

    def read_process(kind,process,path):
        with path.open('w',encoding='utf-8') as output:
            for line in process.stdout:output.write(line);output.flush();events.put((kind,line.rstrip('\n')))
        events.put((kind+'-ended',process.wait()))

    def read_input():
        for line in sys.stdin:events.put(('input',line.rstrip('\r\n')))
        events.put(('input-ended',None))

    threading.Thread(target=read_process,args=('server',server,server_log),daemon=True).start()
    threading.Thread(target=read_input,daemon=True).start()
    print('Authorized Paper boot started. Use clients start; server <command>; client <action>; stage <name>.',flush=True)
    try:
        while server_code is None or client is not None and client_code is None:
            try:kind,text=events.get(timeout=30)
            except queue.Empty:
                if time.monotonic()-started>30*60:raise TimeoutError('Bounded acceptance boot exceeded thirty minutes')
                continue
            if kind in ['server','client']:
                print(f'[{kind}] {text}',flush=True)
                if kind=='server' and 'Done (' in text:ready=True;record['server_done_utc']=c.now()
            elif kind=='input':
                target,_,command_text=text.partition(' ')
                if target=='clients' and command_text=='start':
                    if not ready or client is not None:print('Require one ready server and no prior client controller in this boot.',flush=True);continue
                    client_command=[str(args.node),str(c.ROOT/'tools/phase-three-paper-clients.cjs'),'--run-dir',str(run),'--boot',str(args.boot),'--expected-sha',args.expected_sha]
                    client=subprocess.Popen(client_command,cwd=c.ROOT,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace',bufsize=1,creationflags=flags)
                    record.update(client_pid=client.pid,client_command=client_command,client_started_utc=c.now())
                    threading.Thread(target=read_process,args=('client',client,evidence/f'phase-three-paper-client-boot{args.boot}.log'),daemon=True).start()
                    continue
                if target=='stage':
                    inspect=[sys.executable,str(c.ROOT/'tools/inspect-phase-three-paper.py'),command_text,'--run-dir',str(run),'--expected-sha',args.expected_sha]
                    result=subprocess.run(inspect,cwd=c.ROOT,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=60)
                    print(result.stdout+result.stderr,flush=True);record['commands'].append({'utc':c.now(),'target':'inspector','command':command_text,'exit_code':result.returncode});continue
                process=server if target=='server' else client if target=='client' else None
                if process is None or process.poll() is not None:print('No running target.',flush=True);continue
                if target=='server' and command_text.strip()=='stop':
                    if client is None or client.poll() is None:print('Normally quit the actual client controller before stopping the server.',flush=True);continue
                    record['normal_console_stop_requested']=True
                if target=='client' and command_text.strip()=='quit':record['clients_quit_requested']=True
                record['commands'].append({'utc':c.now(),'target':target,'command':command_text})
                process.stdin.write(command_text+'\n');process.stdin.flush()
            elif kind=='server-ended':server_code=text;record.update(server_exit_code=text,server_ended_utc=c.now());print('Observed server exit:',text,flush=True)
            elif kind=='client-ended':client_code=text;record.update(client_exit_code=text,client_ended_utc=c.now());print('Observed client exit:',text,flush=True)
            elif kind=='input-ended':raise EOFError('Interactive stdin closed; use exec tty=true. No unattended acceptance may continue.')
            if time.monotonic()-started>30*60:raise TimeoutError('Bounded acceptance boot exceeded thirty minutes')
    except (KeyboardInterrupt,Exception) as error:
        record['launcher_problem']=repr(error)
        if client is not None and client.poll() is None:client.stdin.write('quit\n');client.stdin.flush()
        if server.poll() is None:server.stdin.write('stop\n');server.stdin.flush()
        for process in [client,server]:
            if process is not None:
                try:process.wait(timeout=60)
                except subprocess.TimeoutExpired:process.kill();process.wait(timeout=10)
        server_code=server.returncode;client_code=None if client is None else client.returncode
        record.update(server_exit_code=server_code,client_exit_code=client_code)
    finally:
        record['plugin_sha256_after']=c.digest(run/'plugins/StarChronicle.jar')
        record['all_owned_children_exited']=server.poll() is not None and client is not None and client.poll() is not None
        record['source_files_unchanged']=c.source_unchanged(value)
        processes['boots'].append(record);processes['server_exit_codes'].append(server_code);processes['client_exit_codes'].append(client_code)
        c.write_json(process_path,processes);value['status']=f'BOOT_{args.boot}_OBSERVED';c.write_json(run/'preparation.json',value)
    return 0 if server_code==client_code==0 and 'launcher_problem' not in record and record['plugin_sha256_after']==args.expected_sha and record['source_files_unchanged'] else 1


if __name__=='__main__':raise SystemExit(main())
