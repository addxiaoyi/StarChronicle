"""Own exactly one SHA-bound Paper quest-evidence boot and real protocol-client process.

Run only in the coordinator's serial JVM window, using a persistent PTY or the explicit
owned append-only --control-file transport. Inputs:
  clients start [A,B,C]   (boot3 defaults to A,C and refuses B)
  server <console command>
  client <semantic client action>
  status
Quit all clients, observe their actual exit, then send server stop. No process starts on import.
"""
from pathlib import Path
from datetime import datetime, timezone
import argparse
import hashlib
import json
import os
import queue
import re
import shutil
import subprocess
import sys
import threading
import time
from quest_evidence_control import process_identity,watch_commands

ROOT=Path(__file__).resolve().parents[1]
FIXTURE='QuestEvidencePaperFixture'


def digest(path):
    result=hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda:stream.read(1024*1024),b''):
            result.update(chunk)
    return result.hexdigest()
def now():return datetime.now(timezone.utc).isoformat()
def write_json(path,value):path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')


def checkpoint(evidence,run,record):
    chronicle=run/'plugins/StarChronicle/chronicle.db';native=run/'plugins/StarCore/quest'
    if not chronicle.is_file() or not native.is_dir() or not chronicle.resolve().is_relative_to(run) or not native.resolve().is_relative_to(run):
        raise RuntimeError('Stopped Chronicle database or native quest directory is missing')
    required={'quests.properties','tracked-evidence.properties','evidence-authority.properties'}
    if not all((native/name).is_file() for name in required):raise RuntimeError('Stopped native quest storage is incomplete')
    target=evidence/'checkpoints'/f"boot{record['boot']}";target.mkdir(parents=True,exist_ok=False)
    sources=[(chronicle,Path('chronicle.db'))]
    for suffix in ('-wal','-shm','-journal'):
        file=chronicle.with_name(chronicle.name+suffix)
        if file.exists():sources.append((file,Path(file.name)))
    native_paths=sorted(native.rglob('*'))
    if any(path.is_symlink() or not path.resolve().is_relative_to(native.resolve()) for path in native_paths):raise RuntimeError('Refusing a linked path in native quest checkpoint')
    sources.extend((file,Path('native-quest')/file.relative_to(native)) for file in native_paths if file.is_file())
    for directory in [native]+[path for path in native_paths if path.is_dir()]:
        (target/'native-quest'/directory.relative_to(native)).mkdir(parents=True,exist_ok=True)
    copied=[]
    for source,relative in sources:
        if source.is_symlink():raise RuntimeError('Refusing a linked checkpoint source file')
        destination=target/relative;before=digest(source);size=source.stat().st_size
        with source.open('rb') as original,destination.open('xb') as output:shutil.copyfileobj(original,output)
        if digest(destination)!=before or digest(source)!=before or destination.stat().st_size!=size:raise RuntimeError('Stopped checkpoint file changed during byte copy: '+relative.as_posix())
        copied.append({'relative':relative.as_posix(),'sha256':before,'size':size})
    if any(source.stat().st_size and relative.name in {'chronicle.db-wal','chronicle.db-journal'} for source,relative in sources):raise RuntimeError('Normal stop left a nonempty Chronicle WAL/journal; incomplete checkpoint retained')
    receipt=target/'checkpoint.json'
    write_json(receipt,{'boot':record['boot'],'captured_utc':now(),'source_paths':{'chronicle':str(chronicle),'native_quest':str(native)},'copied_files':copied,
        **{key:record[key] for key in ('plugin_sha256','starcore_sha256','fixture_sha256','server_pid','client_pid','server_exit','client_exit','normal_server_stop_requested','clients_quit_all_requested','all_owned_children_exited')}})
    return {'path':receipt.relative_to(evidence).as_posix(),'sha256':digest(receipt),'file_count':len(copied)}


def check_checkpoint(evidence,record):
    target=evidence/'checkpoints'/f"boot{record['boot']}";receipt=target/'checkpoint.json';proof=record.get('checkpoint',{})
    if not receipt.is_file() or digest(receipt)!=proof.get('sha256'):raise SystemExit('Earlier stopped checkpoint manifest is missing or changed')
    metadata=json.loads(receipt.read_text(encoding='utf-8'));copy=metadata['copied_files']
    if metadata['boot']!=record['boot'] or len(copy)!=proof.get('file_count'):raise SystemExit('Earlier checkpoint does not match its observed boot')
    for item in copy:
        file=(target/item['relative']).resolve()
        if not file.is_relative_to(target.resolve()) or not file.is_file() or file.stat().st_size!=item['size'] or digest(file)!=item['sha256']:raise SystemExit('Earlier stopped checkpoint bytes changed')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-dir',type=Path,required=True)
    parser.add_argument('--attempt',default='retry1')
    parser.add_argument('--expected-sha',required=True)
    parser.add_argument('--expected-starcore-sha',required=True)
    parser.add_argument('--boot',type=int,choices=[1,2,3],required=True)
    parser.add_argument('--java-home',type=Path,required=True)
    parser.add_argument('--node',type=Path,default=Path(r'C:\Users\Administrator\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe'))
    parser.add_argument('--xms',type=int,default=256)
    parser.add_argument('--xmx',type=int,default=768)
    parser.add_argument('--max-metaspace',type=int,default=256)
    parser.add_argument('--active-processors',type=int,default=2)
    parser.add_argument('--ci-compiler-count',type=int,default=0)
    parser.add_argument('--tiered-stop-at-level',type=int,default=0)
    parser.add_argument('--thread-stack-kb',type=int,default=0)
    parser.add_argument('--reserved-code-cache-mb',type=int,default=0)
    parser.add_argument('--max-seconds',type=int,default=1800)
    parser.add_argument('--control-file',type=Path)
    parser.add_argument('--controller-pid',type=int)
    parser.add_argument('--controller-identity')
    args=parser.parse_args()
    # This guard precedes every child-process creation, including all Java invocation.
    if args.control_file is None and not sys.stdin.isatty():raise SystemExit('Persistent PTY or explicitly owned control-file transport required')
    if not re.fullmatch(r'[0-9a-f]{64}',args.expected_sha) or not re.fullmatch(r'[0-9a-f]{64}',args.expected_starcore_sha):raise SystemExit('Require complete coordinator-frozen Chronicle and StarCore SHA-256 values')
    if not re.fullmatch(r'[a-z0-9-]{1,24}',args.attempt):raise SystemExit('Attempt suffix must contain 1-24 lowercase ASCII letters, digits or hyphens')
    run=args.run_dir.resolve();expected=(ROOT/'run'/('paper-quest-evidence-'+args.expected_sha[:8]+'-'+args.attempt)).resolve()
    if run!=expected or run.parent!=(ROOT/'run').resolve():raise SystemExit('Require this exact worktree/run/paper-quest-evidence-<sha8>-<attempt>')
    manifest=json.loads((run/'preparation.json').read_text(encoding='utf-8'))
    if Path(manifest['run_directory']).resolve()!=run or manifest['plugin_sha256']!=args.expected_sha or manifest['starcore_sha256']!=args.expected_starcore_sha:raise SystemExit('Prepared run identity differs from explicit frozen values')
    artifacts={'plugin_sha256':run/'plugins/StarChronicle.jar','starcore_sha256':run/'plugins/StarCore.jar','paper_sha256':run/'paper.jar','fixture_sha256':run/'plugins'/(FIXTURE+'.jar')}
    if not manifest.get('fixture_compiled') or any(not path.is_file() or digest(path)!=manifest.get(key) for key,path in artifacts.items()):raise SystemExit('Require exact prepared/compiled product, native core, server and fixture fingerprints')
    if {path.name for path in (run/'plugins').glob('*.jar')}!={'StarChronicle.jar','StarCore.jar',FIXTURE+'.jar'}:raise SystemExit('Unexpected native or fixture plugin JAR in the isolated runtime')
    fixture_source=run/'fixture'/(FIXTURE+'.java')
    if not fixture_source.is_file() or digest(fixture_source)!=manifest.get('fixture_source_sha256') or digest(ROOT/'tools'/(FIXTURE+'.java'))!=manifest.get('fixture_source_sha256'):raise SystemExit('Compiled fixture source identity changed')
    story_hashes=manifest.get('story_sha256',{})
    if not story_hashes or any(Path(name).name!=name for name in story_hashes):raise SystemExit('Missing or unsafe prepared story filenames')
    story_dir=run/'plugins/StarChronicle/stories'
    if {path.name for path in story_dir.iterdir() if path.is_file() and path.suffix in {'.yml','.yaml','.json'}}!=set(story_hashes) or any(digest(story_dir/name)!=sha for name,sha in story_hashes.items()):raise SystemExit('Prepared story set or bytes changed')
    properties={}
    for line in (run/'server.properties').read_text(encoding='utf-8').splitlines():
        if line.strip() and not line.lstrip().startswith('#') and '=' in line:
            key,value=line.split('=',1);properties[key.strip()]=value.strip()
    if manifest.get('server_ip')!='127.0.0.1' or properties.get('server-ip')!='127.0.0.1' or properties.get('server-port')!=str(manifest['server_port']) or properties.get('online-mode')!='false' or properties.get('enable-rcon')!='false' or properties.get('enable-query')!='false':raise SystemExit('Actual isolated loopback/offline endpoint differs from preparation')
    if not isinstance(manifest['server_port'],int) or not 1024<=manifest['server_port']<=65535 or set(manifest['players'])!={'A','B','C'}:raise SystemExit('Invalid pinned endpoint/cohort')
    java=args.java_home/'bin/java.exe';client_script=ROOT/'tools/quest-evidence-clients.cjs'
    if not java.is_file() or not args.node.is_file() or not client_script.is_file() or not 60<=args.max_seconds<=3600:raise SystemExit('Missing explicit Java/Node/client runtime or invalid bounded duration')
    evidence=run/'evidence';evidence.mkdir(exist_ok=True)
    if args.control_file is not None:
        control=args.control_file.resolve()
        if control!=evidence/f'quest-evidence-control-boot{args.boot}.jsonl' or control.exists():raise SystemExit('Require fresh exact boot control file inside this run evidence')
        if args.controller_pid!=os.getppid() or not args.controller_identity or process_identity(args.controller_pid)!=args.controller_identity:raise SystemExit('Control transport must belong to the actual coordinator parent process identity')
    elif args.controller_pid is not None or args.controller_identity is not None:raise SystemExit('Controller identity requires explicit control-file transport')
    process_path=evidence/'quest-evidence-processes.json'
    records=json.loads(process_path.read_text(encoding='utf-8')) if process_path.exists() else {'plugin_sha256':args.expected_sha,'starcore_sha256':args.expected_starcore_sha,'fixture_sha256':manifest['fixture_sha256'],'boots':[]}
    if records.get('plugin_sha256')!=args.expected_sha or records.get('starcore_sha256')!=args.expected_starcore_sha or records.get('fixture_sha256')!=manifest['fixture_sha256']:raise SystemExit('Earlier process evidence belongs to another candidate')
    boots=records['boots']
    if len(boots)!=args.boot-1 or [boot['boot'] for boot in boots]!=list(range(1,args.boot)) or any(boot.get('server_exit')!=0 or boot.get('client_exit')!=0 or boot.get('cleanup_errors') or boot.get('launcher_error') or boot.get('checkpoint_error') or not boot.get('all_owned_children_exited') for boot in boots):raise SystemExit('Earlier serial boots must have actual normal exits and no unresolved cleanup')
    for boot in boots:check_checkpoint(evidence,boot)
    server_log=evidence/f'quest-evidence-boot{args.boot}.log';client_log=evidence/f'quest-evidence-client-boot{args.boot}.log'
    used_controller=evidence/f'quest-evidence-session-used-boot{args.boot}.py';used_client=evidence/f'quest-evidence-client-used-boot{args.boot}.cjs'
    control_source=ROOT/'tools/quest_evidence_control.py';used_control=evidence/f'quest-evidence-control-used-boot{args.boot}.py'
    wire_source=ROOT/'tools/quest-evidence-wire.cjs';used_wire=evidence/f'quest-evidence-wire-used-boot{args.boot}.cjs'
    if any(path.exists() for path in [server_log,client_log,used_controller,used_client,used_control,used_wire]):raise SystemExit('Refusing to overwrite actual boot/source evidence')
    lock=evidence/'quest-evidence-active-session.lock'
    with lock.open('x',encoding='utf-8') as stream:stream.write(json.dumps({'launcher_pid':os.getpid(),'boot':args.boot,'plugin_sha256':args.expected_sha,'created_utc':now()}))
    used_controller.write_bytes(Path(__file__).read_bytes());used_client.write_bytes(client_script.read_bytes());used_control.write_bytes(control_source.read_bytes());used_wire.write_bytes(wire_source.read_bytes())
    record={'boot':args.boot,'plugin_sha256':args.expected_sha,'starcore_sha256':args.expected_starcore_sha,'fixture_sha256':manifest['fixture_sha256'],'controller_sha256':digest(used_controller),'client_script_sha256':digest(used_client),'session_sha256':digest(used_controller),'client_sha256':digest(used_client),'control_transport_sha256':digest(used_control),'client_wire_sha256':digest(used_wire),'server_log':server_log.relative_to(run).as_posix(),'client_log':client_log.relative_to(run).as_posix(),'requested_utc':now(),'server_exit':None,'client_exit':None,'normal_server_stop_requested':False,'clients_quit_all_requested':False,'cleanup_errors':[]}
    boots.append(record)
    control_stopped=threading.Event()
    server=client=None;threads=[];events=queue.Queue();flags=getattr(subprocess,'CREATE_NO_WINDOW',0);done=False;fixture_ready=False;client_started=False

    def save():
        records['server_exit_codes']=[boot.get('server_exit') for boot in boots]
        records['client_exit_codes']=[boot.get('client_exit') for boot in boots]
        records['all_owned_children_exited']=all(boot.get('all_owned_children_exited',False) for boot in boots)
        records['three_normal_boots_completed']=len(boots)==3 and all(boot.get('server_exit')==0 and boot.get('client_exit')==0 and boot.get('checkpoint') and not boot.get('cleanup_errors') and not boot.get('launcher_error') for boot in boots)
        write_json(process_path,records)
    def send(process,line):
        if process is None or process.poll() is not None:raise RuntimeError('Requested owned child is not running')
        process.stdin.write(line+'\n');process.stdin.flush()
    def pump(kind,process,path):
        try:
            with path.open('x',encoding='utf-8') as output:
                for line in process.stdout:output.write(line);output.flush();events.put((kind,line.rstrip('\r\n')))
        except Exception as error:events.put(('pump-error',kind+': '+repr(error)))
        finally:events.put((kind+'-ended',process.wait()))
    def reader():
        for line in sys.stdin:events.put(('input',line.rstrip('\r\n')))
        events.put(('input-eof',None))
    def start_pump(kind,process,path):
        thread=threading.Thread(target=pump,args=(kind,process,path),daemon=True);threads.append(thread);thread.start()
    def cohort(text):
        value=(text or ('A,C' if args.boot==3 else 'A,B,C')).replace(' ','');selected=value.split(',')
        if not selected or any(who not in {'A','B','C'} for who in selected) or len(set(selected))!=len(selected) or (args.boot==3 and 'B' in selected):raise ValueError('Invalid selected original cohort; boot3 keeps B offline')
        return selected
    started=time.monotonic()
    try:
        if not 64 <= args.xms <= args.xmx <= 2048 or not 96 <= args.max_metaspace <= 512:
            raise ValueError('Require bounded JVM memory: 64 <= Xms <= Xmx <= 2048 MB and 96 <= metaspace <= 512 MB')
        if not 1 <= args.active_processors <= 8 or args.ci_compiler_count < 0 or args.tiered_stop_at_level < 0 or args.thread_stack_kb < 0 or args.reserved_code_cache_mb < 0:
            raise ValueError('Invalid JVM native-memory tuning bounds')
        command=[str(java),f'-Xms{args.xms}M',f'-Xmx{args.xmx}M',f'-XX:MaxMetaspaceSize={args.max_metaspace}M',f'-XX:ActiveProcessorCount={args.active_processors}','-XX:+UseSerialGC']
        if args.ci_compiler_count: command.append(f'-XX:CICompilerCount={args.ci_compiler_count}')
        if args.tiered_stop_at_level: command.append(f'-XX:TieredStopAtLevel={args.tiered_stop_at_level}')
        if args.thread_stack_kb: command.append(f'-Xss{args.thread_stack_kb}k')
        if args.reserved_code_cache_mb: command.append(f'-XX:ReservedCodeCacheSize={args.reserved_code_cache_mb}M')
        command.extend(['-Dfile.encoding=UTF-8','-jar','paper.jar','--nogui'])
        if args.control_file is not None:
            with control.open('x',encoding='utf-8'):pass
            record.update(control_file=control.relative_to(run).as_posix(),controller_pid=args.controller_pid,controller_identity=args.controller_identity)
        server=subprocess.Popen(command,cwd=run,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace',bufsize=1,creationflags=flags)
        record.update(server_pid=server.pid,server_command=command,server_started_utc=now());save();start_pump('server',server,server_log)
        if args.control_file is None:threading.Thread(target=reader,daemon=True).start()
        else:threading.Thread(target=watch_commands,args=(control,events,control_stopped,args.controller_pid,args.controller_identity),daemon=True).start()
        print('SERVER_STARTED '+json.dumps(record,ensure_ascii=False),flush=True)
        while True:
            if time.monotonic()-started>args.max_seconds:raise TimeoutError('Bounded owned quest-evidence session expired')
            try:kind,value=events.get(timeout=.5)
            except queue.Empty:
                if server.poll() is not None:break
                continue
            if kind in {'server','client'}:
                print('['+kind+'] '+value,flush=True)
                if kind=='server' and 'Done (' in value:done=True;record['paper_done_observed_utc']=now()
                if kind=='server' and 'QUEST_EVIDENCE_READY' in value:fixture_ready=True;record['fixture_ready_observed_utc']=now()
            elif kind=='server-ended':record.update(server_exit=value,server_ended_utc=now());print('SERVER_EXIT '+str(value),flush=True);break
            elif kind=='client-ended':record.update(client_exit=value,client_ended_utc=now());save();print('CLIENT_EXIT '+str(value),flush=True)
            elif kind=='pump-error':raise RuntimeError('Owned output pump failed: '+value)
            elif kind=='control-error':raise RuntimeError('Owned control transport failed: '+value)
            elif kind=='input-eof':raise EOFError('Controller PTY input ended; stop only this session owned children')
            elif kind=='input':
                target,_,command_text=value.strip().partition(' ')
                if target=='abort':raise RuntimeError('Coordinator explicitly aborted this incomplete acceptance boot: '+command_text)
                if target=='status':print('STATUS '+json.dumps({'server_pid':server.pid,'server_exit':server.poll(),'client_pid':client.pid if client else None,'client_exit':client.poll() if client else None,'paper_done':done,'fixture_ready':fixture_ready}),flush=True);continue
                if target=='clients' and (command_text=='start' or command_text.startswith('start ')):
                    if client_started:print('CONTROL_REFUSED clients may start only once in this boot',flush=True);continue
                    if not done or not fixture_ready or server.poll() is not None:print('CONTROL_REFUSED wait for actual Paper Done and QUEST_EVIDENCE_READY',flush=True);continue
                    suffix=command_text[5:].strip();selected=cohort(suffix)
                    # Bound heap/native compiler pressure for the three real protocol clients.
                    # These flags change runtime resource use only; cohort and evidence stay exact.
                    command=[str(args.node),'--max-old-space-size=192','--max-semi-space-size=8','--jitless',str(client_script),'--run-dir',str(run),'--expected-sha',args.expected_sha,'--attempt',args.attempt,'--boot',str(args.boot),'--players',','.join(selected)]
                    record.update(client_start_ack_utc=now(),client_cohort=selected,client_command=command);client_started=True
                    client=subprocess.Popen(command,cwd=ROOT,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace',bufsize=1,creationflags=flags)
                    record.update(client_pid=client.pid,client_started_utc=now());save();start_pump('client',client,client_log);continue
                if target=='server':
                    if command_text.strip().lower()=='stop':
                        if client is not None and client.poll() is None:print('CONTROL_REFUSED quit all clients and wait for actual CLIENT_EXIT before server stop',flush=True);continue
                        record['normal_server_stop_requested']=True;record['server_stop_requested_utc']=now();save()
                    send(server,command_text)
                elif target=='client':
                    if command_text.strip() in {'quit','quit all'}:
                        record['clients_quit_all_requested']=True;record['clients_quit_requested_utc']=now();save();send(client,'quit all');client.stdin.close()
                    else:send(client,command_text)
                else:print('CONTROL_REFUSED use server/client prefixes, clients start [A,B,C], or status',flush=True)
    except BaseException as error:
        record['launcher_error']=type(error).__name__+': '+str(error);print('SESSION_FAILED '+record['launcher_error'],flush=True)
    finally:
        control_stopped.set()
        # Each phase has its own finally: broken client pipes/timeouts cannot skip server
        # stop/wait or process evidence. Only this launcher's Popen children are touched.
        try:
            if client is not None:
                if client.poll() is None:
                    try:send(client,'quit all');client.stdin.close();record['cleanup_client_quit_requested_utc']=now()
                    except (OSError,RuntimeError,ValueError) as error:record['cleanup_errors'].append('client_input: '+repr(error))
                try:record['client_exit']=client.wait(timeout=45);record.setdefault('client_ended_utc',now())
                except subprocess.TimeoutExpired as error:record['cleanup_errors'].append('client_wait: '+repr(error))
        finally:
            try:
                if server is not None:
                    if server.poll() is None:
                        try:send(server,'stop');record['cleanup_server_stop_requested_utc']=now()
                        except (OSError,RuntimeError,ValueError) as error:record['cleanup_errors'].append('server_input: '+repr(error))
                    try:record['server_exit']=server.wait(timeout=60);record.setdefault('server_ended_utc',now())
                    except subprocess.TimeoutExpired as error:record['cleanup_errors'].append('server_wait: '+repr(error))
            finally:
                record['all_owned_children_exited']=server is not None and server.poll() is not None and client is not None and client.poll() is not None
                record['finished_utc']=now();record['artifacts_after']={key:digest(path) if path.is_file() else None for key,path in artifacts.items()}
                for thread in threads:thread.join(timeout=5)
                if record['server_exit']==0 and record['client_exit']==0 and record['all_owned_children_exited'] and record['normal_server_stop_requested'] and record['clients_quit_all_requested'] and not record['cleanup_errors'] and not record.get('launcher_error') and all(record['artifacts_after'][key]==manifest[key] for key in artifacts):
                    try:record['checkpoint']=checkpoint(evidence,run,record)
                    except Exception as error:record['checkpoint_error']=type(error).__name__+': '+str(error)
                save();print('PROCESSES_FINISHED '+json.dumps(record,ensure_ascii=False),flush=True)
                if (server is None or server.poll() is not None) and (client is None or client.poll() is not None):lock.unlink(missing_ok=True)
    success=record['server_exit']==0 and record['client_exit']==0 and record['all_owned_children_exited'] and record['normal_server_stop_requested'] and record['clients_quit_all_requested'] and record.get('checkpoint') and not record['cleanup_errors'] and not record.get('launcher_error') and all(record['artifacts_after'][key]==manifest[key] for key in artifacts)
    if not success:raise SystemExit('Owned quest-evidence boot is not accepted; retained process record identifies incomplete/failed work')


if __name__=='__main__':
    for stream in (sys.stdin,sys.stdout,sys.stderr):
        if hasattr(stream,'reconfigure'):stream.reconfigure(encoding='utf-8',errors='backslashreplace' if stream is not sys.stdin else 'replace')
    main()
