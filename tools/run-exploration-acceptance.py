"""Own two serial physical exploration boots without depending on an interactive tool session.

Run only against a fresh, already prepared and compiled SHA-bound attempt. The existing
independent inspector decides PASS after normal exits; this controller never rewrites evidence.
"""
from pathlib import Path
from datetime import datetime, timezone
import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import time
from quest_evidence_control import process_identity

ROOT=Path(__file__).resolve().parents[1]
JAVA=Path(r'C:\Users\Administrator\Documents\starworld-tools\jdk-21.0.13.1+2')
NODE=Path(r'C:\Users\Administrator\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe')
CLIENT_FAILURES={'ACTION_FAILED','ERROR','KICKED','UNEXPECTED_END','IDENTITY_FAILED','UNCAUGHT','TIMEOUT','INPUT_CLOSED'}


def now():return datetime.now(timezone.utc).isoformat()
def digest(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def load(path):return json.loads(Path(path).read_text(encoding='utf-8'))
def require(value,message):
    if not value:raise RuntimeError(message)


def atomic_json(path,value):
    temporary=path.with_name(path.name+f'.tmp-{os.getpid()}')
    with temporary.open('w',encoding='utf-8') as output:
        output.write(json.dumps(value,ensure_ascii=False,indent=2)+'\n');output.flush();os.fsync(output.fileno())
    os.replace(temporary,path)


class JsonLines:
    """Read only complete appended UTF-8 rows and reject truncation or file replacement."""
    def __init__(self,path,client=False):
        self.path=Path(path);self.client=client;self.position=0;self.pending=b'';self.identity=None;self.rows=[]
    def refresh(self):
        if not self.path.exists():
            require(self.identity is None,'Evidence file disappeared: '+str(self.path));return
        stat=self.path.stat();identity=(stat.st_dev,stat.st_ino)
        if self.identity is None:self.identity=identity
        require(identity==self.identity and stat.st_size>=self.position,'Evidence file was replaced or truncated: '+str(self.path))
        with self.path.open('rb') as source:
            require((os.fstat(source.fileno()).st_dev,os.fstat(source.fileno()).st_ino)==self.identity,'Evidence handle changed')
            source.seek(self.position);raw=source.read();self.position+=len(raw)
        self.pending+=raw
        require(len(self.pending)<=4*1024*1024 or b'\n' in self.pending,'Evidence line exceeds bounded length')
        while b'\n' in self.pending:
            line,self.pending=self.pending.split(b'\n',1)
            if not line:continue
            text=line.decode('utf-8')
            if self.client:
                stamp,kind,payload=text.split(' ',2);value=json.loads(payload);row={'time':stamp,'kind':kind,'value':value,'boot':value['boot']}
            else:row=json.loads(text)
            require(type(row.get('boot')) is int and isinstance(row.get('kind'),str) and isinstance(row.get('value'),dict),'Malformed actual protocol row')
            row={**row,'cursor':len(self.rows)};self.rows.append(row)


def after(rows,boot,cursor,kind,fields):
    return [row for row in rows if row['boot']==boot and row['cursor']>=cursor and row['kind']==kind and all(row['value'].get(key)==value for key,value in fields.items())]


class Boot:
    def __init__(self,args,number,owner):
        self.args=args;self.number=number;self.owner=owner;self.run=args.run_dir;self.evidence=self.run/'evidence'
        self.control=self.evidence/f'control-boot{number}.jsonl';self.output=self.evidence/f'auto-session-boot{number}.log'
        self.intents=self.evidence/f'command-intents-boot{number}.jsonl'
        self.fixture=JsonLines(self.evidence/'fixture.jsonl');self.clients=JsonLines(self.evidence/'clients.jsonl',True)
        self.sequence=0;self.process=None;self.identity=None;self.server_stop=False
    def save(self,phase):
        self.owner['phase']=f'boot{self.number}: '+phase;self.owner['updated_utc']=now();atomic_json(self.evidence/'auto-owner.json',self.owner)
    def verify_sources(self):
        for name,expected in self.owner['source_sha256'].items():require(digest(ROOT/name)==expected,'Controller/client/helper source changed during this attempt: '+name)
    def launch(self):
        self.verify_sources()
        require(not any(path.exists() for path in (self.control,self.output,self.intents)),'Refusing boot control/output replacement')
        with self.intents.open('x',encoding='utf-8'):pass
        command=[sys.executable,str(ROOT/'tools/exploration-acceptance.py'),'run','--sha',self.args.sha,'--platform',self.args.platform,'--attempt',self.args.attempt,'--boot',str(self.number),'--java',str(self.args.java),'--node',str(self.args.node),'--control-file',str(self.control),'--controller-pid',str(os.getpid()),'--controller-identity',self.owner['owner_identity']]
        with self.output.open('x',encoding='utf-8') as output:
            self.process=subprocess.Popen(command,cwd=ROOT,stdin=subprocess.DEVNULL,stdout=output,stderr=subprocess.STDOUT,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
        self.identity=process_identity(self.process.pid);require(self.identity is not None,'Owned boot session retired before its creation identity was captured')
        self.owner['boots'].append({'boot':self.number,'pid':self.process.pid,'identity':self.identity,'command':command,'started_utc':now(),'status':'RUNNING'});self.save('SESSION_STARTED')
    def refresh(self):
        self.fixture.refresh();self.clients.refresh()
        require(not any(row['boot']==self.number and row['kind']=='FAILED' for row in self.fixture.rows),'Actual fixture FAILED record observed')
        require(not any(row['boot']==self.number and row['kind'] in CLIENT_FAILURES for row in self.clients.rows),'Actual protocol client failure observed')
        if self.output.exists():
            text=self.output.read_text(encoding='utf-8',errors='replace')
            require('BOOT_FAILED ' not in text,'Actual owned boot failed; no command will be repeated')
        if self.process is not None and self.process.poll() is None:require(process_identity(self.process.pid)==self.identity,'Owned boot session creation identity changed')
    def wait(self,predicate,description,seconds=120,allow_exit=False):
        self.save('WAITING '+description);deadline=time.monotonic()+seconds
        while True:
            self.refresh()
            if predicate():print(f'OBSERVED boot={self.number} {description}',flush=True);return
            if self.process.poll() is not None and not allow_exit:raise RuntimeError(f'Owned session exited {self.process.returncode} before {description}')
            if time.monotonic()>=deadline:raise TimeoutError('No actual acknowledgement for '+description+'; command will not be repeated')
            time.sleep(.15)
    def matches(self,event_kind,cursor=0,client=False,**fields):return after((self.clients if client else self.fixture).rows,self.number,cursor,event_kind,fields)
    def send(self,command,cleanup=False):
        if cleanup:require(command.startswith('abort '),'Cleanup transport accepts only an explicit abort')
        else:self.refresh()
        require(self.process.poll() is None and process_identity(self.process.pid)==self.identity,'Cannot send to a retired or replaced owned session')
        require(self.control.is_file(),'Owned session has not created its control file')
        require(command and not any(ch in command for ch in '\r\n\0'),'Invalid single control command')
        self.sequence+=1;cursors={'fixture':len(self.fixture.rows),'client':len(self.clients.rows)}
        intent={'sequence':self.sequence,'command':command,'time':now(),'cursors':cursors,'session_pid':self.process.pid,'session_identity':self.identity}
        # Record intent before the single append. A failure is terminal, never a resend decision.
        with self.intents.open('a',encoding='utf-8') as output:output.write(json.dumps(intent,ensure_ascii=False)+'\n');output.flush();os.fsync(output.fileno())
        with self.control.open('a',encoding='utf-8') as output:output.write(json.dumps({'sequence':self.sequence,'command':command},ensure_ascii=False)+'\n');output.flush();os.fsync(output.fileno())
        print(f'SENT boot={self.number} sequence={self.sequence} {command}',flush=True);return cursors
    def action(self,command,event_kind,client=False,**fields):
        cursors=self.send(command);cursor=cursors['client' if client else 'fixture']
        def acknowledged():
            rows=self.matches(event_kind,cursor,client,**fields);require(len(rows)<=1,'Duplicate actual acknowledgement after '+command);return len(rows)==1
        self.wait(acknowledged,command);return cursors
    def settle(self):
        # A fixed quiet interval allows the configured one-second owner poll to observe arrival.
        # It never asserts discovery, submits a synthetic event, or repeats a failed check.
        deadline=time.monotonic()+3
        while time.monotonic()<deadline:self.refresh();require(self.process.poll() is None,'Owned session retired during arrival settlement');time.sleep(.15)
    def start(self):
        self.wait(lambda:self.control.is_file() and len(self.matches('READY'))==1 and 'Done (' in self.output.read_text(encoding='utf-8',errors='replace'),'server Done and exact fixture READY',240)
        if self.args.platform=='folia':
            coverage=self.matches('CANCELLATION_UNSUPPORTED');require(len(coverage)==1 and coverage[0]['value'].get('available') is False,'Folia cancellation must retain the actual UNSUPPORTED runtime marker')
        else:require(not self.matches('CANCELLATION_UNSUPPORTED'),'Paper must exercise its real cancellation case')
        self.action('clients start','SPAWN',True,uuid=load(self.run/'preparation.json')['player_uuid'])
        self.action('server exploreverify station','STATION_READY')
        self.action('server exploreverify start','TELEPORT_RESULT',kind='outside',accepted=True)
    def first(self):
        self.action('server exploreverify check zero','CHECK_PASS',phase='zero')
        self.action('client walk','WALK_ARRIVED',True);self.settle()
        self.action('server exploreverify check walk','CHECK_PASS',phase='walk')
        self.action('client open walk','STORY_VERIFIED',True,story='fixture-exploration-walk')
        self.action('client choose','GUI_CHOICE',True,story='fixture-exploration-walk',node='arrival',choice='yes',realClick=True)
        self.action('server exploreverify check walk-ended','CHECK_PASS',phase='walk-ended')
        self.action('server exploreverify teleport outside','TELEPORT_RESULT',kind='outside',accepted=True)
        if self.args.platform=='paper':
            cursors=self.action('server exploreverify teleport cancel','TELEPORT_RESULT',kind='cancel',accepted=False)
            require(len(self.matches('TELEPORT_CANCELLED',cursors['fixture']))==1,'Real rejected teleport lacks its actual cancellation event')
            self.action('server exploreverify check cancelled','CHECK_PASS',phase='cancelled')
        self.action('server exploreverify teleport inside','TELEPORT_RESULT',kind='inside',accepted=True);self.settle()
        self.action('server exploreverify check both','CHECK_PASS',phase='both')
        self.action('client open teleport','STORY_VERIFIED',True,story='fixture-exploration-teleport')
        self.action('client choose','GUI_CHOICE',True,story='fixture-exploration-teleport',node='arrival',choice='yes',realClick=True)
        self.action('server exploreverify check final','CHECK_PASS',phase='final')
    def second(self):
        self.action('server exploreverify check final','CHECK_PASS',phase='final')
        self.action('client walk','WALK_ARRIVED',True)
        self.action('server exploreverify teleport inside','TELEPORT_RESULT',kind='inside',accepted=True);self.settle()
        self.action('server exploreverify check final','CHECK_PASS',phase='final')
    def finish(self):
        cursors=self.action('client quit','QUIT_REQUESTED',True)
        self.wait(lambda:len(self.matches('ENDED',cursors['client'],True,requestedQuit=True))==1,'actual requested client END')
        self.wait(lambda:'CLIENT_EXIT 0' in self.output.read_text(encoding='utf-8',errors='replace'),'actual client process exit 0')
        self.send('server stop');self.server_stop=True
        self.wait(lambda:self.process.poll() is not None,'normal owned server/session exit',100,True)
        require(self.process.returncode==0,'Owned session failed normal checkpoint stop')
        record=load(self.evidence/'processes.json')['boots'][self.number-1]
        require(record.get('server_exit')==record.get('client_exit')==0 and record.get('normal_stop') and record.get('normal_quit') and record.get('all_owned_exited') and record.get('checkpoint'),'Normal exits or stopped checkpoint are missing')
        require(not any(record.get(key) for key in ('error','cleanup_client_error','cleanup_server_error','checkpoint_error')),'Owned session retained an error')
        self.verify_sources();self.owner['boots'][-1].update(status='NORMAL_COMPLETED',exit_code=self.process.returncode,finished_utc=now(),checkpoint=record['checkpoint']);self.save('BOOT_COMPLETED')
    def abort(self):
        if self.process is None or self.process.poll() is not None:return
        if not self.server_stop and self.control.is_file():
            try:self.send('abort acceptance encountered a failed or ambiguous step',cleanup=True)
            except Exception as error:self.owner.setdefault('cleanup_errors',[]).append(str(error))
        try:self.process.wait(timeout=115)
        except subprocess.TimeoutExpired:self.owner.setdefault('cleanup_errors',[]).append('Owned session did not finish its own-child cleanup; no unrelated PID was terminated')


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--sha',required=True);parser.add_argument('--platform',choices=['paper','folia'],required=True);parser.add_argument('--attempt',required=True);parser.add_argument('--run-dir',type=Path);parser.add_argument('--java',type=Path,default=JAVA);parser.add_argument('--node',type=Path,default=NODE);args=parser.parse_args()
    require(re.fullmatch('[0-9a-f]{64}',args.sha) and re.fullmatch('[a-z0-9-]{1,24}',args.attempt),'Require exact frozen SHA and safe attempt')
    expected=(ROOT/'run'/f'exploration-{args.sha[:8]}-{args.platform}-{args.attempt}').resolve();args.run_dir=(args.run_dir or expected).resolve();require(args.run_dir==expected,'Require exact isolated SHA/platform/attempt directory')
    evidence=expected/'evidence';require(evidence.is_dir(),'Run prepare and compile before launching acceptance')
    require(not (evidence/'auto-owner.json').exists() and not (evidence/'processes.json').exists(),'Refusing to resume or overwrite an attempted acceptance')
    prepared=load(expected/'preparation.json');require(prepared.get('compiled') and prepared.get('product_sha256')==args.sha and prepared.get('platform')==args.platform,'Prepared/compiled identity differs')
    identity=process_identity(os.getpid());require(identity is not None,'Cannot establish coordinator creation identity')
    with (evidence/'auto-owner.lock').open('x',encoding='utf-8') as lock:lock.write(json.dumps({'pid':os.getpid(),'identity':identity,'time':now()}))
    sources=[Path(__file__).resolve(),ROOT/'tools/exploration-acceptance.py',ROOT/'tools/quest_evidence_control.py',ROOT/'tools/exploration-clients.cjs']
    owner={'owner_pid':os.getpid(),'owner_identity':identity,'started_utc':now(),'run':str(expected),'status':'RUNNING','boots':[],'source_sha256':{path.relative_to(ROOT).as_posix():digest(path) for path in sources}}
    for source in sources:
        destination=evidence/('auto-source-'+source.name)
        with destination.open('xb') as output:output.write(source.read_bytes())
    atomic_json(evidence/'auto-owner.json',owner);active=None
    try:
        for number in (1,2):
            active=Boot(args,number,owner);active.launch();active.start();(active.first if number==1 else active.second)();active.finish()
        owner.update(status='RUNTIME_COMPLETED_PENDING_INSPECTION',finished_utc=now())
    except BaseException as error:
        owner.update(status='FAILED',error=type(error).__name__+': '+str(error),finished_utc=now());print('ACCEPTANCE_FAILED '+owner['error'],flush=True)
        if active is not None:active.abort()
    finally:atomic_json(evidence/'auto-owner.json',owner)
    if owner['status']=='FAILED':return 1
    # The independent strict inspector owns the final conclusion, after both normal stopped boots.
    result=subprocess.run([sys.executable,str(ROOT/'tools/exploration-acceptance.py'),'inspect','--sha',args.sha,'--platform',args.platform,'--attempt',args.attempt],cwd=ROOT,capture_output=True,text=True,encoding='utf-8',timeout=120)
    with (evidence/'auto-inspector-output.log').open('x',encoding='utf-8') as output:output.write(result.stdout+result.stderr)
    owner.update(status='PASS' if result.returncode==0 else 'INSPECTION_FAILED',inspection_exit=result.returncode,finished_utc=now());atomic_json(evidence/'auto-owner.json',owner)
    print(owner['status'],flush=True);return result.returncode


if __name__=='__main__':
    for stream in (sys.stdout,sys.stderr):
        if hasattr(stream,'reconfigure'):stream.reconfigure(encoding='utf-8',errors='backslashreplace')
    raise SystemExit(main())
