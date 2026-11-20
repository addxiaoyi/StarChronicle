"""Prepare, compile, own two serial physical exploration boots, or inspect stopped evidence.

No server/JVM starts during prepare. Compile and run are explicit coordinator-owned actions.
The fixture can arrange physical conditions/read public API but never manufactures discoveries.
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
import sqlite3
import struct
import subprocess
import sys
import threading
import time
import uuid
import zipfile

ROOT=Path(__file__).resolve().parents[1]
FIXTURE='ExplorationPaperFixture'
PYTHON=Path(sys.executable)
NODE=Path(r'C:\Users\Administrator\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe')
JAVA=Path(r'C:\Users\Administrator\Documents\starworld-tools\jdk-21.0.13.1+2')
SOURCES={'paper':Path(r'C:\Users\Administrator\Documents\StarChronicle\run\smoke'),'folia':Path(r'C:\Users\Administrator\Documents\StarChronicle\run\folia-smoke')}
PLAYER=str(uuid.UUID(bytes=hashlib.md5(b'OfflinePlayer:ChronicleScout').digest(),version=3))
STORIES={key:{'id':'fixture-exploration-'+key,'title':'Exploration evidence '+key,'honor':2 if key=='walk' else 3,'region':'fixture-'+key,'ending':'FIXTURE_'+key.upper()+'_FINISHED'} for key in ('walk','teleport')}

def require(value,message):
    if not value:raise ValueError(message)
def digest(path):
    h=hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda:stream.read(1024*1024),b''):h.update(chunk)
    return h.hexdigest()
def now():return datetime.now(timezone.utc).isoformat()
def write(path,value):path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def read(path):return json.loads(path.read_text(encoding='utf-8'))
def story(meta):return {'id':meta['id'],'version':'1','title':meta['title'],'scope':'PLAYER','triggers':['EXPLORATION_DISCOVERY'],'condition':{'fact':'event.region.id','op':'eq','value':meta['region']},'start':'arrival','cooldownSeconds':0,'nodes':{'arrival':{'text':'Actual physical arrival is recorded. Choose whether to archive it.','choices':{'yes':{'label':'Archive my real arrival','next':'done'}}},'done':{'text':'The observed arrival and this choice are now recorded once.','terminal':True,'effects':[{'id':'honor','type':'honor','target':'player','value':str(meta['honor'])},{'id':'history','type':'history','target':meta['ending'],'value':'A real client archived a real configured-region arrival.'}]}}}
def frozen(run,sha):
    manifest=read(run/'preparation.json');require(manifest['product_sha256']==sha and digest(run/'plugins/StarChronicle.jar')==sha,'Frozen product mismatch')
    require(digest(run/(manifest['platform']+'.jar'))==manifest['server_sha256'],'Frozen server mismatch')
    for file,expected in manifest['story_hashes'].items():require(digest(run/'plugins/StarChronicle/stories'/file)==expected,'Prepared story changed')
    require(digest(run/'fixture'/f'{FIXTURE}.java')==manifest['fixture_source_sha256'],'Prepared fixture source changed')
    return manifest

RUNBOOK='''# Physical first-visit acceptance (procedure, not PASS)

Boot1: wait Paper/Folia Done + EXPLORATION_READY, start client; wait actual SPAWN.
server exploreverify station; wait STATION_READY, then server exploreverify start.
wait accepted outside TELEPORT_RESULT; server exploreverify check zero.
client walk; wait WALK_ARRIVED; wait one poll, server exploreverify check walk.
client open walk; wait STORY_VERIFIED; client choose; wait GUI_CHOICE.
server exploreverify check walk-ended.
server exploreverify teleport outside; wait accepted result.
server exploreverify teleport cancel; wait TELEPORT_CANCELLED and rejected result.
server exploreverify check cancelled.
server exploreverify teleport inside; wait accepted result + one poll.
server exploreverify check both.
client open teleport; wait STORY_VERIFIED; client choose; wait GUI_CHOICE.
server exploreverify check final.
client quit; wait CLIENT_EXIT 0; server stop; wait PROCESS_FINISHED + checkpoint.

Boot2: same exact run/JAR/config/world, start only the same client, wait SPAWN.
server exploreverify station; wait STATION_READY; server exploreverify start; wait outside result.
server exploreverify check final (same IDs, payload, honor and ending counts).
client walk; wait WALK_ARRIVED; server exploreverify teleport inside; wait result.
server exploreverify check final; client quit; wait CLIENT_EXIT 0; server stop.
Run inspect only after actual two normal exits. Never edit evidence or retry a failed boot in place.
'''

def prepare(args,run):
    require(not run.exists(),'Refusing overwrite of existing run/evidence')
    source=(args.source or SOURCES[args.platform]).resolve();product=args.product.resolve();server=source/(args.platform+'.jar')
    require(product.is_file() and digest(product)==args.sha and server.is_file(),'Explicit product SHA/server missing')
    require(source!=run and source not in run.parents and run not in source.parents,'Distinct nonnested source required')
    require(1024<=args.port<=65535 and args.port not in (25565,),'Distinct loopback test port required')
    run.mkdir();(run/'plugins/StarChronicle/stories').mkdir(parents=True);(run/'plugins'/FIXTURE).mkdir();(run/'fixture').mkdir();(run/'evidence').mkdir()
    retained={}
    for folder in ('cache','libraries'):
        for path in (source/folder).rglob('*'):require(not path.is_symlink(),'No linked runtime copy sources')
        if (source/folder).is_dir():shutil.copytree(source/folder,run/folder)
    for path,destination in [(server,run/server.name),(product,run/'plugins/StarChronicle.jar'),(ROOT/'tools'/f'{FIXTURE}.java',run/'fixture'/f'{FIXTURE}.java')]:retained[str(path)]=digest(path);shutil.copyfile(path,destination)
    world=uuid.uuid4();(run/'world').mkdir();(run/'world/uid.dat').write_bytes(world.bytes)
    regions=[{'id':meta['region'],'world':str(world),'min-x':1004 if key=='walk' else 1011,'max-x':1006 if key=='walk' else 1013,'min-y':-60,'max-y':-58,'min-z':0,'max-z':3} for key,meta in STORIES.items()]
    config={'require-starcore':False,'install-default-stories':False,'npc-hooks':False,'poll-ticks':20,'worker-queue-capacity':256,'exploration':{'enabled':True,'regions':regions}}
    write(run/'plugins/StarChronicle/config.yml',config);write(run/f'plugins/{FIXTURE}/config.yml',{'product-sha':args.sha,'world-uuid':str(world)})
    hashes={}
    for meta in STORIES.values():file=run/'plugins/StarChronicle/stories'/(meta['id']+'.json');write(file,story(meta));hashes[file.name]=digest(file)
    (run/'eula.txt').write_text('eula=true\n',encoding='utf-8')
    properties={'server-ip':'127.0.0.1','server-port':args.port,'online-mode':'false','enable-rcon':'false','enable-query':'false','enforce-secure-profile':'false','spawn-protection':0,'view-distance':3,'simulation-distance':3,'max-players':3,'gamemode':'survival','difficulty':'peaceful','spawn-monsters':'false','spawn-animals':'false','generate-structures':'false','level-type':'minecraft:flat','generator-settings':'{"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}','sync-chunk-writes':'true'}
    (run/'server.properties').write_text(''.join(f'{key}={value}\n' for key,value in properties.items()),encoding='utf-8')
    write(run/'ops.json',[]);write(run/'whitelist.json',[]);(run/'RUNBOOK.md').write_text(RUNBOOK,encoding='utf-8')
    write(run/'preparation.json',{'prepared_utc':now(),'run':str(run),'platform':args.platform,'product_sha256':args.sha,'server_sha256':digest(server),'fixture_source_sha256':digest(ROOT/'tools'/f'{FIXTURE}.java'),'world_uuid':str(world),'player_uuid':PLAYER,'server_ip':'127.0.0.1','server_port':args.port,'stories':STORIES,'story_hashes':hashes,'source_hashes':retained,'source_world_copied':False,'source_database_copied':False,'compiled':False})
    print(json.dumps({'prepared':str(run),'jvm_started':False,'server_started':False},indent=2))

def compile_fixture(args,run):
    manifest=frozen(run,args.sha);target=run/'plugins'/f'{FIXTURE}.jar';require(not target.exists() and not manifest['compiled'],'Refusing compiled fixture replacement')
    api=Path.home()/'.m2/repository/io/papermc/paper/paper-api/1.21.11-R0.1-SNAPSHOT/paper-api-1.21.11-R0.1-SNAPSHOT.jar';require(api.is_file(),'Paper API missing')
    classes=run/'fixture/classes';classes.mkdir();jars=list((run/'libraries').rglob('*.jar'))+[run/'plugins/StarChronicle.jar',api]
    argfile=run/'fixture/javac.args';argfile.write_text('--release 21\n-proc:none\n-encoding UTF-8\n-cp\n"'+os.pathsep.join(str(p) for p in jars).replace('\\','/')+'"\n-d\n"'+str(classes).replace('\\','/')+'"\n"'+str(run/'fixture'/f'{FIXTURE}.java').replace('\\','/')+'"\n',encoding='utf-8')
    command=[str(args.java/'bin/javac.exe'),'-J-Xmx128m','-J-XX:ActiveProcessorCount=1','-J-XX:+UseSerialGC','@'+str(argfile)]
    result=subprocess.run(command,capture_output=True,timeout=120,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0));(run/'evidence/fixture-compile.log').write_bytes(result.stdout+result.stderr)
    manifest['compile']={'command':command,'exit_code':result.returncode,'at_utc':now()};write(run/'preparation.json',manifest);require(result.returncode==0,'Fixture javac failed; original log retained')
    with zipfile.ZipFile(target,'x',zipfile.ZIP_DEFLATED) as jar:
        jar.writestr('plugin.yml',f'name: {FIXTURE}\nversion: 1\nmain: smoke.{FIXTURE}\napi-version: "1.21"\nfolia-supported: true\ndepend: [StarChronicle]\ncommands:\n  exploreverify:\n    description: Isolated real exploration verification\n')
        for path in sorted(classes.rglob('*.class')):jar.write(path,path.relative_to(classes).as_posix())
    manifest.update(compiled=True,fixture_sha256=digest(target));write(run/'preparation.json',manifest);print(json.dumps({'compiled':True,'fixture_sha256':manifest['fixture_sha256'],'server_started':False},indent=2))

def stopped_checkpoint(run,record):
    db=run/'plugins/StarChronicle/chronicle.db';require(db.is_file(),'Stopped DB missing');destination=run/'evidence/checkpoints'/f"boot{record['boot']}";destination.mkdir(parents=True)
    for suffix in ('-wal','-journal'):require(not Path(str(db)+suffix).exists() or Path(str(db)+suffix).stat().st_size==0,'Uncheckpointed stopped DB')
    before=digest(db);shutil.copyfile(db,destination/'chronicle.db');require(digest(db)==digest(destination/'chronicle.db')==before,'Stopped copy changed')
    desc=destination/'checkpoint.json';write(desc,{'boot':record['boot'],'db_sha256':before,'captured_utc':now(),'product_sha256':record['product_sha256'],'fixture_sha256':record['fixture_sha256'],'server_exit':record['server_exit'],'client_exit':record['client_exit']});return {'path':desc.relative_to(run).as_posix(),'sha256':digest(desc),'db_sha256':before}

def run_boot(args,run):
    from quest_evidence_control import process_identity,watch_commands
    require(args.control_file is not None or sys.stdin.isatty(),'Persistent PTY or explicitly owned control-file transport required before Java launch');manifest=frozen(run,args.sha);require(manifest['compiled'] and digest(run/'plugins'/f'{FIXTURE}.jar')==manifest['fixture_sha256'],'Compiled frozen fixture required')
    evidence=run/'evidence';processes=evidence/'processes.json';records=read(processes) if processes.exists() else {'product_sha256':args.sha,'boots':[]};require(len(records['boots'])==args.boot-1,'Require strictly serial unmodified boot records')
    require(records.get('product_sha256')==args.sha and [record.get('boot') for record in records['boots']]==list(range(1,args.boot)),'Earlier process evidence belongs to another product or boot sequence')
    for old in records['boots']:
        require(old.get('server_exit')==old.get('client_exit')==0 and old.get('checkpoint') and old.get('normal_stop') and old.get('normal_quit') and old.get('all_owned_exited') and not any(old.get(key) for key in ('error','cleanup_client_error','cleanup_server_error','checkpoint_error')),'Earlier boot was not accepted')
        require(digest(run/old['checkpoint']['path'])==old['checkpoint']['sha256'],'Earlier checkpoint provenance changed')
    control=None
    if args.control_file is not None:
        control=args.control_file.resolve()
        require(control==evidence/f'control-boot{args.boot}.jsonl' and not control.exists(),'Require a fresh exact boot control file in this run')
        require(args.controller_pid==os.getppid() and args.controller_identity and process_identity(args.controller_pid)==args.controller_identity,'Control transport must belong to the actual parent coordinator creation identity')
    else:require(args.controller_pid is None and args.controller_identity is None,'Controller identity requires file-control transport')
    client_source=ROOT/'tools/exploration-clients.cjs';controller_source=Path(__file__);server_log=evidence/f'server-boot{args.boot}.log';client_log=evidence/f'client-boot{args.boot}.log';lock=evidence/'active.lock'
    for path in (server_log,client_log):require(not path.exists(),'Refusing overwrite of boot evidence')
    with lock.open('x') as stream:stream.write(json.dumps({'pid':os.getpid(),'identity':process_identity(os.getpid()),'boot':args.boot,'created_utc':now()}))
    for source,prefix in ((client_source,'client'),(controller_source,'controller')):shutil.copyfile(source,evidence/f'{prefix}-used-boot{args.boot}{source.suffix}')
    record={'boot':args.boot,'product_sha256':args.sha,'fixture_sha256':manifest['fixture_sha256'],'client_sha256':digest(client_source),'controller_sha256':digest(controller_source),'started_utc':now(),'server_exit':None,'client_exit':None,'normal_stop':False,'normal_quit':False,'session_pid':os.getpid(),'session_identity':process_identity(os.getpid())};records['boots'].append(record)
    if control is not None:
        helper=ROOT/'tools/quest_evidence_control.py';used=evidence/f'control-helper-used-boot{args.boot}.py';require(not used.exists(),'Refusing helper evidence replacement');shutil.copyfile(helper,used)
        record.update(control_file=control.relative_to(run).as_posix(),controller_pid=args.controller_pid,controller_identity=args.controller_identity,control_helper_sha256=digest(used))
    def save():write(processes,records)
    def send(process,line):
        require(process is not None and process.poll() is None,'Owned process is not running')
        identity=record.get('server_identity' if process is server else 'client_identity')
        require(identity is not None and process_identity(process.pid)==identity,'Owned process creation identity changed before input')
        process.stdin.write(line+'\n');process.stdin.flush()
    events=queue.Queue();threads=[];server=client=None;done=ready=False;flags=getattr(subprocess,'CREATE_NO_WINDOW',0);control_stopped=threading.Event();sequence=0
    def pump(kind,process,path):
        try:
            with path.open('x',encoding='utf-8') as output:
                for line in process.stdout:output.write(line);output.flush();events.put((kind,line.rstrip()))
        finally:events.put((kind+'-end',process.wait()))
    def start_pump(kind,process,path):thread=threading.Thread(target=pump,args=(kind,process,path),daemon=True);thread.start();threads.append(thread)
    def stdin():
        for line in sys.stdin:events.put(('input',line.strip()))
        events.put(('eof',None))
    try:
        if control is not None:
            with control.open('x',encoding='utf-8'):pass
        command=[str(args.java/'bin/java.exe'),'-Xms64M','-Xmx512M','-XX:MaxMetaspaceSize=192M','-XX:ActiveProcessorCount=1','-XX:+UseSerialGC','-XX:CICompilerCount=1','-XX:TieredStopAtLevel=1','-Xss256k','-XX:ReservedCodeCacheSize=64M','-Dfile.encoding=UTF-8','-jar',manifest['platform']+'.jar','--nogui']
        server=subprocess.Popen(command,cwd=run,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace',bufsize=1,creationflags=flags);record.update(server_pid=server.pid,server_identity=process_identity(server.pid),server_command=command);require(record['server_identity'] is not None,'Owned server retired before creation identity was captured');save();start_pump('server',server,server_log)
        if control is None:threading.Thread(target=stdin,daemon=True).start()
        else:threading.Thread(target=watch_commands,args=(control,events,control_stopped,args.controller_pid,args.controller_identity),daemon=True).start()
        print('SERVER_STARTED '+str(server.pid),flush=True);deadline=time.monotonic()+1200
        while time.monotonic()<deadline:
            try:kind,value=events.get(timeout=.5)
            except queue.Empty:
                if server.poll() is not None:break
                continue
            if kind in ('server','client'):
                print('['+kind+'] '+value,flush=True)
                if kind=='server':done=done or 'Done (' in value;ready=ready or 'EXPLORATION_READY' in value
            elif kind=='client-end':record['client_exit']=value;save();print('CLIENT_EXIT '+str(value),flush=True)
            elif kind=='server-end':record['server_exit']=value;break
            elif kind=='eof':raise EOFError('Controller input ended before normal stop')
            elif kind=='control-error':raise RuntimeError(value)
            elif kind=='input':
                sequence+=1
                with (evidence/f'control-receipts-boot{args.boot}.jsonl').open('a',encoding='utf-8') as receipts:receipts.write(json.dumps({'sequence':sequence,'command':value,'time':now()},ensure_ascii=False)+'\n');receipts.flush();os.fsync(receipts.fileno())
                target,_,line=value.partition(' ')
                if target=='status':print(json.dumps({'server':server.poll(),'client':None if client is None else client.poll(),'done':done,'ready':ready}),flush=True)
                elif target=='clients' and line=='start':
                    require(done and ready and client is None,'Wait actual Done/fixture ready and start one client only')
                    command=[str(args.node),'--max-old-space-size=192','--max-semi-space-size=8','--jitless',str(client_source),'--run-dir',str(run),'--expected-sha',args.sha,'--boot',str(args.boot)];client=subprocess.Popen(command,cwd=ROOT,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace',bufsize=1,creationflags=flags);record.update(client_pid=client.pid,client_identity=process_identity(client.pid),client_command=command);require(record['client_identity'] is not None,'Owned client retired before creation identity was captured');save();start_pump('client',client,client_log)
                elif target=='client':
                    if line=='quit':record['normal_quit']=True;save()
                    send(client,line)
                elif target=='server':
                    if line.lower()=='stop':require(client is not None and client.poll() is not None,'Normal client exit must precede stop');record['normal_stop']=True;save()
                    send(server,line)
                elif target=='abort':raise RuntimeError('Owned coordinator aborted this boot: '+line)
                else:raise ValueError('Use clients start, client ACTION, server COMMAND, status')
        else:raise TimeoutError('Bounded exploration boot expired')
    except BaseException as error:record['error']=type(error).__name__+': '+str(error);print('BOOT_FAILED '+record['error'],flush=True)
    finally:
        control_stopped.set()
        if client is not None:
            if client.poll() is None:
                try:send(client,'quit')
                except Exception as error:record['cleanup_client_error']=str(error)
            try:record['client_exit']=client.wait(timeout=35)
            except subprocess.TimeoutExpired:record['cleanup_client_error']='still live after normal quit wait'
        if server is not None:
            if server.poll() is None:
                try:send(server,'stop')
                except Exception as error:record['cleanup_server_error']=str(error)
            try:record['server_exit']=server.wait(timeout=60)
            except subprocess.TimeoutExpired:record['cleanup_server_error']='still live after normal stop wait'
        for thread in threads:thread.join(timeout=5)
        record['finished_utc']=now();record['all_owned_exited']=server is not None and server.poll() is not None and client is not None and client.poll() is not None
        if record['server_exit']==record['client_exit']==0 and record['normal_stop'] and record['normal_quit'] and record['all_owned_exited'] and not record.get('error') and not record.get('cleanup_client_error') and not record.get('cleanup_server_error'):
            try:record['checkpoint']=stopped_checkpoint(run,record)
            except Exception as error:record['checkpoint_error']=type(error).__name__+': '+str(error)
        save()
        if server is not None and server.poll() is not None and (client is None or client.poll() is not None):lock.unlink(missing_ok=True)
        print('PROCESS_FINISHED '+json.dumps(record),flush=True)
    require(record.get('checkpoint') is not None,'Exploration boot not accepted; incomplete evidence retained')

def inspected_time(value):return datetime.fromisoformat(value.replace('Z','+00:00'))

def canonical(value):return json.dumps(value,sort_keys=True,ensure_ascii=False,separators=(',',':'))

def stable_hash(*fields):
    h=hashlib.sha256()
    for field in fields:
        raw=field.encode('utf-8');h.update(str(len(raw)).encode('ascii')+b':'+raw)
    return h.hexdigest()

def matching(rows,boot,event_kind,**fields):
    return [row for row in rows if row['boot']==boot and row['kind']==event_kind and all(row.get('value',row).get(key)==value for key,value in fields.items())]

def observed(rows,boot,event_kind,**fields):
    selected=matching(rows,boot,event_kind,**fields);require(len(selected)==1,f'Boot{boot} requires one {event_kind} {fields}, saw {len(selected)}');return selected[0]

def ordered(*rows):
    stamps=[inspected_time(row['time']) for row in rows];require(stamps==sorted(stamps),'Actual protocol evidence is out of required chronological order')

def checked_path(run,relative,expected):
    require(str(relative).replace('\\','/')==expected,'Checkpoint path is not the expected owned boot path')
    path=run/relative;require(path.is_file() and not path.is_symlink() and path.resolve().is_relative_to(run.resolve()),'Checkpoint path escapes owned regular files');return path

def expected_regions(manifest):
    return [{'id':meta['region'],'world':manifest['world_uuid'],'min-x':1004 if key=='walk' else 1011,'max-x':1006 if key=='walk' else 1013,'min-y':-60,'max-y':-58,'min-z':0,'max-z':3} for key,meta in manifest['stories'].items()]

def region_scope(region):
    raw='\n'.join(str(region[key]) for key in ('id','world','min-x','min-y','min-z','max-x','max-y','max-z'))
    return hashlib.sha256(raw.encode('utf-8')).hexdigest()

def verify_history_row(row):
    event=json.loads(row['json']);require(event['id']==row['id'] and event['type']==row['type'] and inspected_time(event['occurredAt'])==inspected_time(row['time']),'History index differs from canonical event')
    payload={'id':event['id'],'type':event['type'],'time':'OBSERVED' if event['facts'].get('time_basis')=='OBSERVED' else event['occurredAt'],'players':sorted(event['participants']),'nations':sorted(event['nations']),'facts':event['facts'],'source':event['source'],'evidence':event['evidence']}
    require(stable_hash(canonical(payload))==row['hash'],'Canonical history payload hash differs');return event

def final_projection(value):
    return {'discoveries':sorted(value['discoveries'],key=lambda event:event['id']),'honor':value['fate'].get('player.honor','0'),'instances':sorted(value['instances'],key=lambda item:item['id'])}

def cancellation_coverage(manifest,fixture):
    require(manifest['platform'] in ('paper','folia'),'Unknown acceptance platform')
    markers=[row for row in fixture if row['kind']=='CANCELLATION_UNSUPPORTED']
    if manifest['platform']=='paper':
        require(not markers,'Paper cancellation coverage cannot be marked unsupported');return None
    reason='Folia teleportAsync did not dispatch PlayerTeleportEvent in the preserved runtime probe; cancellable teleport coverage is unavailable.'
    expected={'platform':'folia','api':'Player.teleportAsync','available':False,'reason':reason}
    for boot in (1,2):
        marker=observed(fixture,boot,'CANCELLATION_UNSUPPORTED');require(marker['value']==expected,'Folia unsupported cancellation evidence differs from actual platform limitation')
    require(len(markers)==2,'Exactly two Folia unsupported cancellation observations required')
    require(not [row for row in fixture if row['kind']=='TELEPORT_CANCELLED' or row['kind']=='TELEPORT_RESULT' and row['value'].get('kind')=='cancel' or row['kind']=='CHECK_PASS' and row['value'].get('phase')=='cancelled'],'Folia cancellation-unavailable scope cannot accept cancellation commands or success markers')
    return {'status':'UNSUPPORTED','api':expected['api'],'reason':reason}

def inspect_snapshot(db,manifest):
    before=digest(db)
    for suffix in ('-wal','-journal'):require(not Path(str(db)+suffix).exists() or Path(str(db)+suffix).stat().st_size==0,'Snapshot contains uncheckpointed data')
    conn=sqlite3.connect(db.resolve().as_uri()+'?mode=ro&immutable=1',uri=True);conn.row_factory=sqlite3.Row
    try:
        require(conn.execute('PRAGMA user_version').fetchone()[0]==6 and conn.execute('PRAGMA integrity_check').fetchone()[0]=='ok' and not list(conn.execute('PRAGMA foreign_key_check')),'Stopped schema/integrity/foreign-key verification failed')
        event_rows=[dict(row) for row in conn.execute("SELECT * FROM events WHERE type='EXPLORATION_DISCOVERY' OR type IN ('FIXTURE_WALK_FINISHED','FIXTURE_TELEPORT_FINISHED') ORDER BY id")]
        events={row['id']:verify_history_row(row) for row in event_rows};discoveries=[event for event in events.values() if event['type']=='EXPLORATION_DISCOVERY'];require(len(discoveries)==2,'Two actual discovery sources required')
        claims=[dict(row) for row in conn.execute('SELECT * FROM exploration_discoveries ORDER BY event_id')];require(len(claims)==2 and all(row['status']=='ACKED' and row['player_id']==manifest['player_uuid'] for row in claims),'Two acknowledged actor/scope claims required')
        regions={region['id']:region for region in expected_regions(manifest)};bindings={row['region_id']:row['scope'] for row in conn.execute('SELECT * FROM exploration_region_scopes')}
        require(bindings=={key:region_scope(region) for key,region in regions.items()},'Persisted logical region bindings differ from configured world/bounds')
        require({row['event_id'] for row in claims}=={event['id'] for event in discoveries},'Source and ingress identity mismatch')
        for claim in claims:
            event=events[claim['event_id']];require(json.loads(claim['json'])==event,'Immutable ingress JSON differs from history')
            region=regions.get(event['facts'].get('region.id'));require(region is not None,'Unknown discovery region');scope=region_scope(region)
            require(claim['region_scope']==scope and event['id']=='exploration:first-visit:'+manifest['player_uuid']+':'+scope,'Stable first-visit key differs from actor/world/bounds')
            facts=event['facts'];require(set(facts)=={'time_basis','discovery.kind','region.id','region.scope','region.world','region.x','region.y','region.z'} and facts['time_basis']=='OBSERVED' and facts['discovery.kind']=='FIRST_VISIT' and facts['region.scope']==scope and facts['region.world']==manifest['world_uuid'],'Discovery facts do not identify configured observed arrival')
            xyz=[int(facts['region.'+axis]) for axis in 'xyz'];require(all(region['min-'+axis]<=coordinate<=region['max-'+axis] for axis,coordinate in zip('xyz',xyz)),'Recorded first arrival is outside configured bounds')
            evidence='Configured cuboid first-entry observation; player='+manifest['player_uuid']+'; region='+region['id']+'; scope='+scope+'; world='+manifest['world_uuid']+'; block='+','.join(map(str,xyz))
            require(event['participants']==[manifest['player_uuid']] and event['nations']==[] and event['source']=='StarChronicle.ConfiguredRegionFirstVisit' and event['evidence']==evidence,'Discovery authority/evidence differs')
        honor=conn.execute("SELECT value FROM facts WHERE owner=? AND key='player.honor'",(manifest['player_uuid'],)).fetchone();require(honor is not None and honor[0]=='5','Stopped reward ledger does not contain exactly five honor')
        raw_instances=[dict(row) for row in conn.execute('SELECT * FROM instances ORDER BY id')];require(len(raw_instances)==2,'Exactly two configured instances required');summaries=[];expected_outcomes=set()
        for key,meta in manifest['stories'].items():
            rows=[row for row in raw_instances if row['story']==meta['id']];require(len(rows)==1,'Missing/duplicate configured story instance');row=rows[0];instance=json.loads(row['json']);source=next(event for event in discoveries if event['facts']['region.id']==meta['region'])
            choices={'arrival':'yes','arrival/'+manifest['player_uuid']:'yes'}
            require(row['completed']==1 and instance['completed'] is True and row['id']==instance['id'] and row['scope']==instance['scopeKey']=='player:'+manifest['player_uuid'] and instance['node']=='done' and instance['sourceEventId']==source['id'] and instance['members']=={manifest['player_uuid']:''} and instance['choices']==choices and instance['votes']=={},'Stopped instance lost exact source/member/node/choice identity')
            require(instance['definition']['id']==meta['id'] and instance['definition']['version']=='1','Pinned story identity differs')
            history_outcome=stable_hash(instance['id'],'node/done','history','WORLD');honor_outcome=stable_hash(instance['id'],'node/done','honor',manifest['player_uuid']);expected_outcomes.update((history_outcome,honor_outcome))
            ending=events.get('chronicle:'+history_outcome);require(ending is not None and ending['type']==meta['ending'] and ending['participants']==[manifest['player_uuid']] and ending['nations']==[] and ending['source']=='StarChronicle','Ending history does not reference stable outcome/participant')
            require(ending['facts']=={'story':meta['id'],'outcome':'A real client archived a real configured-region arrival.'} and ending['evidence']=='instance='+instance['id']+'; action=node/done; effect=history; source='+source['id'],'Ending history lost actual arrival causality')
            ballot=list(conn.execute("SELECT kind,player_id,choice_id,node_id,provenance FROM story_choice_log WHERE instance_id=?",(instance['id'],)))
            require(len(ballot)==2 and {item['kind'] for item in ballot}=={'BALLOT','RESOLUTION'} and all(item['choice_id']=='yes' and item['node_id']=='arrival' and item['provenance']=='LIVE' for item in ballot) and next(item for item in ballot if item['kind']=='BALLOT')['player_id']==manifest['player_uuid'],'Actual player ballot/resolution records missing')
            summaries.append({'id':instance['id'],'story':meta['id'],'completed':True,'choices':choices})
        outcomes=[dict(row) for row in conn.execute('SELECT * FROM outcomes')];require({row['id'] for row in outcomes}==expected_outcomes and all(row['status']=='DONE' for row in outcomes),'Stable reward/history receipts are missing, extra, or incomplete')
        require(len(events)==4,'Exactly two source and two ending histories required')
        for event in events.values():
            players={row['player'] for row in conn.execute('SELECT player FROM event_players WHERE event_id=?',(event['id'],))};nations=list(conn.execute('SELECT nation FROM event_nations WHERE event_id=?',(event['id'],)))
            require(players=={manifest['player_uuid']} and not nations,'Participant/nation history indexes differ')
        result={'discoveries':sorted(discoveries,key=lambda event:event['id']),'claims':claims,'endings':{event['type']:1 for event in events.values() if event['type']!='EXPLORATION_DISCOVERY'},'honor':'5','instances':sorted(summaries,key=lambda item:item['id']),'outcomes':sorted(expected_outcomes),'db_sha256':before}
    finally:conn.close()
    require(before==digest(db),'Read-only stopped snapshot changed during inspection');return result

def verify_protocol(manifest,fixture,clients):
    unsupported=cancellation_coverage(manifest,fixture)
    for boot in (1,2):
        ready=observed(fixture,boot,'READY');value=ready['value'];require(value['product_sha256']==manifest['product_sha256'] and value['world']==manifest['world_uuid'] and value['player']==manifest['player_uuid'] and value['standalone'] is True and value['noSyntheticSource'] is True,'Runtime fixture identity differs')
        spawn=observed(clients,boot,'SPAWN');require(spawn['uuid']==manifest['player_uuid'],'Actual client UUID differs');station=observed(fixture,boot,'STATION_READY');ordered(ready,spawn,station)
        walk=observed(clients,boot,'WALK_ARRIVED');require(999<=walk['before']['x']<=1002 and abs(walk['before']['z']-1.5)<=1 and 1004<=walk['after']['x']<1007 and -60.2<=walk['after']['y']<=-58 and 0<=walk['after']['z']<4 and walk['implementation']=='real control-state movement packets','Actual physical walk positions differ')
        expected_teleports=(['outside','outside','inside'] if unsupported else ['outside','outside','cancel','inside']) if boot==1 else ['outside','inside']
        teleports=matching(fixture,boot,'TELEPORT_RESULT');require([row['value']['kind'] for row in teleports]==expected_teleports,'Actual physical teleport actions differ from expected sequence');outside=[row for row in teleports if row['value']['kind']=='outside'];require(len(outside)==(2 if boot==1 else 1),'Missing exact outside positioning actions')
        for row in teleports:
            value=row['value'];require(value['world']==manifest['world_uuid'] and value['player']==manifest['player_uuid'],'Teleport result actor/world differs')
            if value['kind']=='outside':require(value['accepted'] is True and value['x']==1000.5 and value['y']==-60 and value['z']==1.5,'Outside teleport failed or moved to wrong point')
        inside=observed(fixture,boot,'TELEPORT_RESULT',kind='inside');value=inside['value'];require(value['accepted'] is True and value['x']==1012.5 and value['y']==-60 and value['z']==1.5,'Accepted actual teleport destination differs')
        finals=matching(fixture,boot,'CHECK_PASS',phase='final');require(len(finals)==(1 if boot==1 else 2),'Missing exact final API checks')
        if boot==1:
            zero=observed(fixture,boot,'CHECK_PASS',phase='zero');after_walk=observed(fixture,boot,'CHECK_PASS',phase='walk');walk_end=observed(fixture,boot,'CHECK_PASS',phase='walk-ended');both=observed(fixture,boot,'CHECK_PASS',phase='both')
            if not unsupported:
                cancelled=observed(fixture,boot,'CHECK_PASS',phase='cancelled');cancel=observed(fixture,boot,'TELEPORT_CANCELLED');cancel_result=observed(fixture,boot,'TELEPORT_RESULT',kind='cancel');require(cancel['value']['player']==manifest['player_uuid'] and cancel['value']['targetX']==1012.5 and cancel_result['value']['accepted'] is False and cancel_result['value']['x']==1000.5 and cancel_result['value']['y']==-60 and cancel_result['value']['z']==1.5,'Cancelled real teleport was accepted or moved the actor')
            choice_walk=observed(clients,boot,'GUI_CHOICE',story=manifest['stories']['walk']['id']);choice_teleport=observed(clients,boot,'GUI_CHOICE',story=manifest['stories']['teleport']['id'])
            for choice in (choice_walk,choice_teleport):
                require(choice['node']=='arrival' and choice['choice']=='yes' and choice['realClick'] is True,'Actual GUI click identity differs')
                detail=observed(clients,boot,'STORY_VERIFIED',story=choice['story']);require(detail['instance']==choice['instance'],'Actual GUI verified instance differs from clicked instance');ordered(detail,choice)
            cancellation_rows=[] if unsupported else [cancel,cancel_result,cancelled]
            ordered(station,outside[0],zero,walk,after_walk,choice_walk,walk_end,outside[1],*cancellation_rows,inside,both,choice_teleport,finals[0])
            source_walk=after_walk['value']['discoveries'];walk_observation_end=after_walk if unsupported else walk
            require(len(source_walk)==1 and source_walk[0]['facts']['region.id']=='fixture-walk' and inspected_time(zero['time'])<=inspected_time(source_walk[0]['occurredAt'])<=inspected_time(walk_observation_end['time']),'Walk source time lacks actual movement bracket')
            source_teleport=next(event for event in both['value']['discoveries'] if event['facts']['region.id']=='fixture-teleport');teleport_observation_start=outside[1] if unsupported else cancelled
            require(inspected_time(teleport_observation_start['time'])<=inspected_time(source_teleport['occurredAt'])<=inspected_time(both['time']),'Teleport source time lacks accepted-arrival bracket')
        else:
            ordered(station,outside[0],finals[0],walk,inside,finals[1]);require(final_projection(finals[0]['value'])==final_projection(finals[1]['value']),'Actual repeated arrivals changed reward/history/choice projection')
        quit_row=observed(clients,boot,'QUIT_REQUESTED');ended=observed(clients,boot,'ENDED');require(ended['requestedQuit'] is True,'Client disconnect was not requested');closed=observed(fixture,boot,'CLOSED');require(closed['value']['normalPluginDisable'] is True,'Fixture not normally disabled');ordered(finals[-1],quit_row,ended,closed)

def inspect(args,run):
    manifest=frozen(run,args.sha);require(manifest['stories']==STORIES and manifest['player_uuid']==PLAYER and manifest['source_world_copied'] is False and manifest['source_database_copied'] is False,'Prepared fixture identity/freshness differs')
    require(manifest['compiled'] is True and manifest['compile']['exit_code']==0 and digest(run/'plugins'/f'{FIXTURE}.jar')==manifest['fixture_sha256'],'Actual fixture JAR drift')
    require({path.name for path in (run/'plugins').glob('*.jar')}=={'StarChronicle.jar',FIXTURE+'.jar'},'Unexpected plugin changes standalone acceptance')
    config=read(run/'plugins/StarChronicle/config.yml');expected={'require-starcore':False,'install-default-stories':False,'npc-hooks':False,'poll-ticks':20,'worker-queue-capacity':256,'exploration':{'enabled':True,'regions':expected_regions(manifest)}};require(config==expected,'Actual product exploration config differs from prepared region contract')
    properties=dict(line.split('=',1) for line in (run/'server.properties').read_text(encoding='utf-8').splitlines() if line and not line.startswith('#') and '=' in line)
    require(properties.get('server-ip')==manifest['server_ip']=='127.0.0.1' and properties.get('server-port')==str(manifest['server_port']) and properties.get('online-mode')=='false' and properties.get('enable-rcon')=='false' and properties.get('enable-query')=='false','Actual server endpoint differs from isolated preparation')
    process=read(run/'evidence/processes.json');require(process['product_sha256']==args.sha and [row['boot'] for row in process['boots']]==[1,2],'Exactly two completed boots required')
    fixture=[json.loads(line) for line in (run/'evidence/fixture.jsonl').read_text(encoding='utf-8').splitlines()];clients=[]
    for line in (run/'evidence/clients.jsonl').read_text(encoding='utf-8').splitlines():stamp,kind,value=line.split(' ',2);clients.append({'time':stamp,'kind':kind,**json.loads(value)})
    require(not [row for row in fixture if row['kind']=='FAILED'],'Actual fixture failure retained; final PASS prohibited')
    require(not [row for row in clients if row['kind'] in ('ACTION_FAILED','ERROR','KICKED','UNEXPECTED_END','IDENTITY_FAILED','UNCAUGHT','TIMEOUT','INPUT_CLOSED')],'Actual client failure retained; final PASS prohibited')
    require(all(row['boot'] in (1,2) for row in fixture+clients),'Unexpected boot evidence')
    checkpoints=[]
    for boot in (1,2):
        record=process['boots'][boot-1];require(record['server_exit']==record['client_exit']==0 and record['normal_stop'] is True and record['normal_quit'] is True and record['all_owned_exited'] is True and not record.get('error') and not record.get('cleanup_server_error') and not record.get('cleanup_client_error'),'Normal owned child exits required')
        require(record['product_sha256']==args.sha and record['fixture_sha256']==manifest['fixture_sha256'],'Process artifact identity differs')
        for prefix,suffix,key in (('client','.cjs','client_sha256'),('controller','.py','controller_sha256')):
            source=run/'evidence'/f'{prefix}-used-boot{boot}{suffix}';require(source.is_file() and digest(source)==record[key],'Copied runtime source differs from observed process fingerprint')
        client_process=observed(clients,boot,'CLIENT_PROCESS');require(client_process['pid']==record['client_pid'] and client_process['product_sha256']==args.sha and client_process['source_sha256']==record['client_sha256'],'Actual client process identity differs')
        require(str(run) in record['client_command'] and args.sha in record['client_command'] and str(boot) in record['client_command'] and manifest['platform']+'.jar' in record['server_command'],'Actual process commands differ from pinned run')
        desc=checked_path(run,record['checkpoint']['path'],f'evidence/checkpoints/boot{boot}/checkpoint.json');require(digest(desc)==record['checkpoint']['sha256'],'Checkpoint descriptor changed');metadata=read(desc)
        require(metadata['boot']==boot and metadata['product_sha256']==record['product_sha256'] and metadata['fixture_sha256']==record['fixture_sha256'] and metadata['server_exit']==metadata['client_exit']==0,'Checkpoint boot/artifact/exit provenance differs')
        require(inspected_time(record['started_utc'])<inspected_time(record['finished_utc'])<=inspected_time(metadata['captured_utc']),'Checkpoint was captured before completed owned processes')
        if boot==2:require(inspected_time(process['boots'][0]['finished_utc'])<inspected_time(record['started_utc']),'Boots were not serial')
        for row in fixture+clients:
            if row['boot']==boot:require(inspected_time(record['started_utc'])<=inspected_time(row['time'])<=inspected_time(record['finished_utc']),'Protocol record falls outside observed process lifetime')
        db=desc.parent/'chronicle.db';require(digest(db)==metadata['db_sha256']==record['checkpoint']['db_sha256'],'Checkpoint bytes changed')
        log=(run/f'evidence/server-boot{boot}.log').read_text(encoding='utf-8');require(not re.search(r'EXPLORATION_FAILED|StarChronicle startup failed|Could not pass event.*(?:StarChronicle|ExplorationPaperFixture)|not owned|Thread failed main thread check|Uncommitted exploration arrivals|Exploration capture buffer full|Exploration arrivals remain buffered',log,re.I),'Server diagnostic in current scope')
        checkpoint=inspect_snapshot(db,manifest);checkpoint['boot']=boot;finals=matching(fixture,boot,'CHECK_PASS',phase='final');require(finals,'Missing API final projection')
        expected_projection={'discoveries':checkpoint['discoveries'],'honor':checkpoint['honor'],'instances':checkpoint['instances']}
        require(all(final_projection(row['value'])==expected_projection for row in finals),'Final API observations disagree with stopped history/reward/choice ledgers');checkpoints.append(checkpoint)
    require({key:value for key,value in checkpoints[0].items() if key not in ('boot','db_sha256')}=={key:value for key,value in checkpoints[1].items() if key not in ('boot','db_sha256')},'Canonical first arrivals/rewards/choices changed after restart')
    verify_protocol(manifest,fixture,clients)
    runtime_db=run/'plugins/StarChronicle/chronicle.db';require(digest(runtime_db)==checkpoints[-1]['db_sha256'],'Final stopped runtime differs from boot2 checkpoint')
    for suffix in ('-wal','-journal'):require(not Path(str(runtime_db)+suffix).exists() or Path(str(runtime_db)+suffix).stat().st_size==0,'Final stopped runtime has uncheckpointed data')
    result={'status':'PASS','platform':manifest['platform'],'product_sha256':args.sha,'fixture_sha256':manifest['fixture_sha256'],'cases':{'real_walk':True,'cancelled_teleport_no_discovery':True,'real_teleport':True,'actual_gui_choice':True,'restart_canonical_identity':True,'repeat_arrival_idempotent':True},'checkpoints':checkpoints,'source':'physical client / actual teleport / stopped SQLite cross-check'}
    unsupported=cancellation_coverage(manifest,fixture)
    if unsupported:
        result['cases']['cancelled_teleport_no_discovery']=None;result['case_details']={'cancelled_teleport_no_discovery':unsupported};result['scope']='Configured first-visit product checks: physical movement, actual async teleport, GUI choices, repeat arrivals and restart persistence. Upstream cancellable teleport coverage is unavailable on the frozen Folia platform.'
    write(run/'evidence/inspection.json',result);print(json.dumps({key:result[key] for key in ('status','platform','product_sha256','cases')},indent=2))

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('action',choices=['prepare','compile','run','inspect']);parser.add_argument('--sha',required=True);parser.add_argument('--platform',choices=['paper','folia'],required=True);parser.add_argument('--attempt',default='retry1');parser.add_argument('--source',type=Path);parser.add_argument('--product',type=Path,default=ROOT/'target/starchronicle-0.1.0-SNAPSHOT.jar');parser.add_argument('--port',type=int,default=25635);parser.add_argument('--java',type=Path,default=JAVA);parser.add_argument('--node',type=Path,default=NODE);parser.add_argument('--boot',type=int,choices=[1,2]);parser.add_argument('--control-file',type=Path);parser.add_argument('--controller-pid',type=int);parser.add_argument('--controller-identity');args=parser.parse_args();require(re.fullmatch('[0-9a-f]{64}',args.sha) and re.fullmatch('[a-z0-9-]{1,24}',args.attempt),'Exact SHA and safe attempt required');run=ROOT/'run'/f'exploration-{args.sha[:8]}-{args.platform}-{args.attempt}'
    if args.action=='prepare':prepare(args,run)
    elif args.action=='compile':compile_fixture(args,run)
    elif args.action=='run':require(args.boot in (1,2),'Run requires --boot');run_boot(args,run)
    else:inspect(args,run)

if __name__=='__main__':
    for stream in (sys.stdin,sys.stdout,sys.stderr):
        if hasattr(stream,'reconfigure'):stream.reconfigure(encoding='utf-8')
    main()
