"""Detached owner for three real Paper quest-evidence boots; no process starts on import.

Prepare and compile a fresh SHA-bound run first. After reviewing this coordinator, launch its
Python process with Start-Process -WindowStyle Hidden and redirected stdout/stderr files.
The owner records PID plus creation identity, uses append-only command files, and waits for
actual fixture/client/process acknowledgements. A failed action is never retried or relabelled.
"""
from pathlib import Path
from datetime import datetime,timezone
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
FAILURES={'ACTION_FAILED','ERROR','KICKED','IDENTITY_FAILED','UNEXPECTED_END','INPUT_CLOSED','INTERRUPTED','UNCAUGHT','TIMEOUT','ALL_SELECTED_CLIENTS_ENDED_MISSING_REQUEST','CRAFT_UNCONFIRMED'}
STORIES={'player':'qe-player-records','nation':'qe-nation-testimonies','atlas':'qe-player-atlas'}


def now():return datetime.now(timezone.utc).isoformat()
def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def load(path):return json.loads(Path(path).read_text(encoding='utf-8'))
def atomic_json(path,value):
    temporary=path.with_suffix(path.suffix+'.pending')
    temporary.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8');os.replace(temporary,path)
def complete_lines(path):
    if not path.is_file():return []
    if path.stat().st_size>64*1024*1024:raise ValueError('Observed log exceeds bounded acceptance size: '+str(path))
    # A concurrent writer may have appended only part of a multibyte UTF-8 codepoint.
    # Decode only complete newline-terminated records; retain the partial line for next poll.
    return [line.decode('utf-8').rstrip('\r') for line in path.read_bytes().split(b'\n')[:-1]]


def source_unchanged(owner):
    if any(not (ROOT/name).is_file() or sha(ROOT/name)!=value for name,value in owner['source_sha256'].items()):
        raise RuntimeError('Frozen acceptance runtime/inspector source changed; refusing dependent work')


def native_admissions(path,player,quest):
    """Read-only synchronization hint; the subsequent fixture API check remains authoritative."""
    matches=[]
    try:
        for line in complete_lines(path):
            key,separator,value=line.partition('=')
            if not separator or not re.fullmatch('[0-9a-f]{64}',key):continue
            # The native store uses Java Properties with JSON string values. Its acceptance
            # fields are ASCII. Leave non-acceptance rows/unfamiliar escapes unconfirmed.
            snapshot=json.loads(value.replace(r'\:',':').replace(r'\=','='))['snapshot']
            if snapshot.get('playerId')==player and snapshot.get('questId')==quest and snapshot.get('status')=='ACTIVE' and snapshot.get('bindingId')==key:
                matches.append(snapshot)
    except (OSError,ValueError,KeyError):return []
    return matches


class Boot:
    def __init__(self,args,number,owner):
        self.args=args;self.number=number;self.owner=owner;self.process=None;self.sequence=0;self.server_stop=False;self.steps=set()
        self.evidence=args.run_dir/'evidence'
        self.control=self.evidence/f'quest-evidence-control-boot{number}.jsonl'
        self.output=self.evidence/f'quest-evidence-owner-boot{number}.log'
        self.command_log=self.evidence/f'quest-evidence-commands-boot{number}.jsonl'
        self.fixture=[];self.clients=[];self.lines={'fixture':0,'clients':0,'output':0};self.output_lines=[]

    def launch(self):
        source_unchanged(self.owner)
        if self.command_log.exists():raise RuntimeError('Refusing to replace an earlier send-once command log')
        with self.command_log.open('x',encoding='utf-8'):pass
        command=[sys.executable,str(ROOT/'tools/quest-evidence-session.py'),'--run-dir',str(self.args.run_dir),'--attempt',self.args.attempt,
            '--expected-sha',self.args.expected_sha,'--expected-starcore-sha',self.args.expected_starcore_sha,'--boot',str(self.number),
            '--java-home',str(self.args.java_home),'--xms','64','--xmx','512','--max-metaspace','192','--active-processors','1',
            '--ci-compiler-count','1','--tiered-stop-at-level','1','--thread-stack-kb','256','--reserved-code-cache-mb','64',
            '--max-seconds','1500','--control-file',str(self.control),'--controller-pid',str(os.getpid()),'--controller-identity',self.owner['process_identity']]
        with self.output.open('x',encoding='utf-8') as output:
            self.process=subprocess.Popen(command,cwd=ROOT,stdin=subprocess.DEVNULL,stdout=output,stderr=subprocess.STDOUT,
                text=True,encoding='utf-8',creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
        self.owner['boots'].append({'boot':self.number,'session_pid':self.process.pid,'session_identity':process_identity(self.process.pid),'command':command,'started_utc':now()})
        self.save('BOOT_STARTED')

    def save(self,stage):
        self.owner.update(stage=stage,updated_utc=now())
        atomic_json(self.evidence/'quest-evidence-owner.json',self.owner)

    def refresh(self):
        fixture_lines=complete_lines(self.evidence/'quest-fixture.jsonl');client_lines=complete_lines(self.evidence/'quest-evidence-clients.log')
        self.output_lines=complete_lines(self.output)
        current={'fixture':len(fixture_lines),'clients':len(client_lines),'output':len(self.output_lines)}
        if any(current[key]<self.lines[key] for key in current):raise RuntimeError('Observed evidence log was truncated')
        self.lines=current
        self.fixture=[dict(r,line=index) for index,line in enumerate(fixture_lines,1) if (r:=json.loads(line)).get('boot')==self.number]
        self.clients=[]
        for index,line in enumerate(client_lines,1):
            date,kind,raw=line.split(' ',2);value=json.loads(raw)
            if value.get('boot')==self.number:self.clients.append({'kind':kind,'value':value,'time':date,'line':index})
        if any(r['kind']=='FAILED' for r in self.fixture):raise RuntimeError('Actual fixture FAILED record observed')
        if any(r['kind'] in FAILURES for r in self.clients):raise RuntimeError('Actual protocol client failure observed')
        if any('SESSION_FAILED ' in line or 'CONTROL_REFUSED ' in line for line in self.output_lines):raise RuntimeError('Actual session failed or refused a command')

    def wait(self,predicate,description,seconds=120,allow_exit=False):
        self.save('WAITING '+description);end=time.monotonic()+seconds
        while True:
            self.refresh()
            if predicate():
                print(f'OBSERVED boot={self.number} {description}',flush=True);return
            if self.process.poll() is not None and not allow_exit:raise RuntimeError(f'Owned session exited {self.process.returncode} before {description}')
            if time.monotonic()>=end:raise TimeoutError(f'No actual acknowledgement for {description}; command will not be repeated')
            time.sleep(.15)

    def observed(self,kind,client=False,after=None,**fields):
        minimum=0 if after is None else after['clients' if client else 'fixture']
        return [row for row in (self.clients if client else self.fixture) if row['line']>minimum and row['kind']==kind and all(row['value'].get(key)==value for key,value in fields.items())]

    def send(self,command,step_id=None,cleanup=False,read_only_observation=False):
        if self.process.poll() is not None:raise RuntimeError('Cannot send to a retired owned session')
        if not self.control.is_file():raise RuntimeError('Session has not created its control file')
        if read_only_observation and not re.fullmatch(r'server qeverify state [ABC] (?:player|nation|atlas)',command):
            raise ValueError('Only the explicitly read-only fixture state query may be polled')
        if not cleanup:self.refresh()
        step_id=step_id or command
        if step_id in self.steps:raise RuntimeError('Refusing to resend an already attempted step: '+step_id)
        self.steps.add(step_id)
        cursor=dict(self.lines)
        self.sequence+=1
        # Durably record intent BEFORE delivery. If delivery is ambiguous, this owner never
        # resends; a new acceptance must use a fresh isolated run and evidence directory.
        entry={'boot':self.number,'sequence':self.sequence,'time':now(),'command':command,'step':step_id,'after_lines':cursor,'policy':'READ_ONLY_OBSERVATION' if read_only_observation else 'SEND_ONCE_NO_RETRY'}
        with self.command_log.open('a',encoding='utf-8') as output:
            output.write(json.dumps(entry,ensure_ascii=False)+'\n');output.flush();os.fsync(output.fileno())
        encoded=json.dumps({'sequence':self.sequence,'command':command},ensure_ascii=False)+'\n'
        with self.control.open('a',encoding='utf-8') as output:output.write(encoded);output.flush();os.fsync(output.fileno())
        print(f'SENT boot={self.number} sequence={self.sequence} {command}',flush=True)
        return cursor

    def action(self,command,kind,client=False,**fields):
        step=command+' '+json.dumps({'kind':kind,'client':client,**fields},sort_keys=True)
        cursor=self.send(command,step_id=step)
        def acknowledged():
            matches=self.observed(kind,client,after=cursor,**fields)
            if len(matches)>1:raise RuntimeError('Duplicate acknowledgement for one send-once step: '+command)
            return len(matches)==1
        self.wait(acknowledged,command)
        return cursor

    def poll_read_only_state(self,who,which,predicate,seconds=60):
        """Each query is a new observation; mutations and protocol actions cannot enter here."""
        command=f'server qeverify state {who} {which}';deadline=time.monotonic()+seconds
        while True:
            cursor=self.send(command,step_id=f'read-only-observation-{self.sequence+1}: '+command,read_only_observation=True)
            def observed_state():
                rows=self.observed('STATE',after=cursor,player=who,story=STORIES[which])
                if len(rows)>1:raise RuntimeError('One read-only observation returned multiple states')
                return len(rows)==1
            self.wait(observed_state,'read-only state observation '+who+' '+which,seconds=max(.1,min(10,deadline-time.monotonic())))
            state=self.observed('STATE',after=cursor,player=who,story=STORIES[which])[0]['value']
            if predicate(state):return state
            if time.monotonic()>=deadline:raise TimeoutError('Read-only state did not reach the required readiness; no action was repeated')
            time.sleep(.25)

    def start_clients(self):
        self.wait(lambda:self.control.is_file() and len(self.observed('READY'))==1 and any('Done (' in line for line in self.output_lines),'Paper Done and fixture READY',240)
        cohort=['A','C'] if self.number==3 else ['A','B','C']
        cursor=self.send('clients start '+','.join(cohort))
        self.wait(lambda:len(self.observed('SPAWN',True,after=cursor))==len(cohort) and {r['value']['player'] for r in self.observed('SPAWN',True,after=cursor)}==set(cohort),'exact actual client SPAWN cohort')
        runtimes=self.observed('CLIENT_RUNTIME_SOURCES',True,after=cursor)
        if len(runtimes)!=1:raise RuntimeError('Client did not preserve exactly one actual runtime source/attribution manifest')
        runtime=runtimes[0]['value']
        fingerprint={'versions':runtime['versions'],'sources':{name:row['sha256'] for name,row in runtime['sources'].items()},
            'attribution':{name:{kind:row['sha256'] for kind,row in entries.items()} for name,entries in runtime['attribution'].items()}}
        if self.owner.setdefault('client_runtime_fingerprint',fingerprint)!=fingerprint:raise RuntimeError('Actual client dependency sources changed between serial boots')
        self.save('CLIENT_RUNTIME_FROZEN')
        self.action('server qeverify seed','SEEDED')

    def boot_one(self):
        for which in ('player','nation','atlas'):self.action('server qeverify begin '+which,'SUBMITTED',story={'player':'qe-player-records','nation':'qe-nation-testimonies','atlas':'qe-player-atlas'}[which])
        prepared=load(self.args.run_dir/'preparation.json')
        for who,which,item,progress in [('A','player','paper',3),('B','nation','book',1),('C','atlas','map',1)]:
            story={'player':'qe-player-records','nation':'qe-nation-testimonies','atlas':'qe-player-atlas'}[which]
            quest_key={'player':'heir','nation':'siege','atlas':'atlas'}[which]
            self.wait(lambda:len(native_admissions(self.args.run_dir/'plugins/StarCore/quest/tracked-evidence.properties',prepared['players'][who],prepared['native_quests'][quest_key]['id']))==1,'actual native persisted ACTIVE admission '+who+' '+which)
            self.action(f'server qeverify check-native {who} {which} 0 read','NATIVE_ACTIVE_CHECK_PASS',player=who,story=story,checkpoint='read',progress=0)
            self.action(f'server qeverify craft-kit {who} {item}','CRAFT_KIT',player=who)
            self.action(f'client craft {who} {item}','CRAFTED',True,player=who,item=item)
            self.action(f'server qeverify check-native {who} {which} {progress} capture','NATIVE_ACTIVE_CHECK_PASS',player=who,story=story,checkpoint='capture',progress=progress)
        for who,which in [('A','player'),('A','nation'),('B','nation'),('C','nation'),('C','atlas')]:
            self.action(f'server qeverify state {who} {which}','STATE',player=who,story={'player':'qe-player-records','nation':'qe-nation-testimonies','atlas':'qe-player-atlas'}[which])

    def boot_two(self):
        for who,which,progress in [('A','player',3),('B','nation',1),('C','atlas',1)]:
            self.action(f'server qeverify check-native {who} {which} {progress} restore','NATIVE_ACTIVE_CHECK_PASS',player=who,checkpoint='restore',progress=progress)
        self.action('server qeverify unregister-provider','PROVIDER_UNREGISTERED')
        prepared=load(self.args.run_dir/'preparation.json')
        for who,quest,qid,story in [('A','heir','chronicle_heir_records','qe-player-records'),('B','siege','chronicle_siege_testimonies','qe-nation-testimonies'),('C','atlas','chronicle_caravan_atlas','qe-player-atlas')]:
            self.action(f'client native-open {who} {quest}','NATIVE_DETAIL_VERIFIED',True,player=who,questId=qid)
            cursor=self.action(f'client native-complete {who}','NATIVE_COMPLETION_CLICK_ONLY',True,player=who,questId=qid)
            self.wait(lambda:any('完成记录已持久确认' in row['value'].get('message','') and prepared['native_quests'][quest]['name'] in row['value'].get('message','') for row in self.observed('MESSAGE',True,after=cursor,player=who)),'actual durable completion message '+who+' '+qid)
            self.action(f'server qeverify check-receipt {who} '+{'A':'player','B':'nation','C':'atlas'}[who],'NATIVE_RECEIPT_CHECK_PASS',player=who,story=story,online=True)
        self.action('server qeverify check-paused','PAUSED_CHECK_PASS')
        self.action('client quit B','ENDED',True,player='B',requestedQuit=True)
        self.action('server qeverify check-receipt B nation','NATIVE_RECEIPT_CHECK_PASS',player='B',online=False)

    def boot_three(self):
        for who,which in [('A','player'),('B','nation'),('C','atlas')]:self.action(f'server qeverify check-receipt {who} {which}','NATIVE_RECEIPT_CHECK_PASS',player=who)
        self.poll_read_only_state('A','player',lambda state:state['gates']['ready'] is True and state['visibleChoices']==['yes'])
        self.poll_read_only_state('C','atlas',lambda state:state['gates']['ready'] is True and state['visibleChoices']==['yes'])
        self.poll_read_only_state('A','nation',lambda state:state['gates']['ready'] is True and state['visibleChoices']==['yes'])
        self.poll_read_only_state('C','nation',lambda state:state['gates']['ready'] is False and state['visibleChoices']==[])
        self.action('server qeverify check-offline-country','OFFLINE_COUNTRY_CHECK_PASS')
        self.action('client open-story C Quest evidence — country testimonies','STORY_VERIFIED',True,player='C',storyId=STORIES['nation'])
        self.action('client inspect C','CLIENT_VIEW',True,player='C')
        self.action('client close C','CLOSED',True,player='C')
        for who,key,title in [('A','player','Quest evidence — personal records'),('A','nation','Quest evidence — country testimonies'),('C','atlas','Quest evidence — caravan atlas')]:
            story={'player':'qe-player-records','nation':'qe-nation-testimonies','atlas':'qe-player-atlas'}[key]
            self.action(f'client open-story {who} {title}','STORY_VERIFIED',True,player=who,storyId=story)
            self.action(f'client choice {who} yes','GUI_CHOICE',True,player=who,storyId=story,choice='yes')
        self.action('server qeverify check-final-before-replay','PRE_REPLAY_CHECK_PASS')
        for which in ('player','nation','atlas'):self.action('server qeverify replay '+which,'REPLAY_DUPLICATE',source='quest-evidence-paper:'+which+':1')
        self.action('server qeverify check-final','FINAL_CHECK_PASS')

    def finish(self):
        self.action('server qeverify flush','NATIVE_FLUSH_CONFIRMED')
        cursor=self.action('client quit all','ALL_SELECTED_CLIENTS_ENDED',True,requested=True)
        self.wait(lambda:any(line=='CLIENT_EXIT 0' for line in self.output_lines[cursor['output']:]),'actual client process exit 0')
        self.send('server stop');self.server_stop=True
        self.wait(lambda:self.process.poll() is not None,'actual normal server/session exit',100,True)
        if self.process.returncode!=0:raise RuntimeError('Owned session failed normal checkpoint stop')
        manifest=load(self.evidence/'quest-evidence-processes.json');record=manifest['boots'][self.number-1]
        if record.get('server_exit')!=0 or record.get('client_exit')!=0 or not record.get('checkpoint'):raise RuntimeError('Actual normal exits/checkpoint are missing')
        self.owner['boots'][-1].update(exit_code=self.process.returncode,finished_utc=now(),checkpoint=record['checkpoint']);self.save('BOOT_COMPLETED')

    def abort(self):
        if self.process is None or self.process.poll() is not None:return
        if not self.server_stop and self.control.is_file():
            try:self.send('abort coordinator encountered a failed or ambiguous acceptance step',cleanup=True)
            except Exception as error:self.owner.setdefault('cleanup_errors',[]).append(str(error))
        try:self.process.wait(timeout=115)
        except subprocess.TimeoutExpired:self.owner.setdefault('cleanup_errors',[]).append('Owned session did not finish its normal own-child cleanup; no unrelated PID was terminated')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-dir',type=Path,required=True);parser.add_argument('--attempt',required=True)
    parser.add_argument('--expected-sha',required=True);parser.add_argument('--expected-starcore-sha',required=True)
    parser.add_argument('--java-home',type=Path,required=True)
    args=parser.parse_args();args.run_dir=args.run_dir.resolve()
    if not all(re.fullmatch('[0-9a-f]{64}',x) for x in [args.expected_sha,args.expected_starcore_sha]) or not re.fullmatch('[a-z0-9-]{1,24}',args.attempt):raise SystemExit('Require exact SHA values and attempt')
    if args.run_dir!=(ROOT/'run'/f'paper-quest-evidence-{args.expected_sha[:8]}-{args.attempt}').resolve():raise SystemExit('Require exact isolated run path')
    evidence=args.run_dir/'evidence';record_path=evidence/'quest-evidence-owner.json'
    if record_path.exists() or (evidence/'quest-evidence-processes.json').exists():raise SystemExit('Refusing to resume/overwrite an attempted acceptance')
    prepared=load(args.run_dir/'preparation.json')
    if not prepared.get('fixture_compiled') or prepared['plugin_sha256']!=args.expected_sha or prepared['starcore_sha256']!=args.expected_starcore_sha:raise SystemExit('Prepared/compiled run differs from explicit frozen artifacts')
    owner={'owner_pid':os.getpid(),'process_identity':process_identity(os.getpid()),'started_utc':now(),'run':str(args.run_dir),'status':'RUNNING','boots':[],
        'source_sha256':{str(path.relative_to(ROOT)):sha(path) for path in [Path(__file__).resolve(),ROOT/'tools/quest-evidence-session.py',ROOT/'tools/quest_evidence_control.py',ROOT/'tools/quest-evidence-clients.cjs',ROOT/'tools/quest-evidence-wire.cjs',ROOT/'tools/inspect-quest-evidence-paper.py']}}
    if not owner['process_identity']:raise SystemExit('Cannot establish exact owner process creation identity')
    for name in owner['source_sha256']:
        target=evidence/('owner-source-'+Path(name).name)
        with target.open('xb') as output:output.write((ROOT/name).read_bytes())
    atomic_json(record_path,owner);active=None
    try:
        for number in (1,2,3):
            active=Boot(args,number,owner);active.launch();active.start_clients()
            {1:active.boot_one,2:active.boot_two,3:active.boot_three}[number]();active.finish()
        owner.update(status='RUNTIME_COMPLETED_PENDING_INSPECTION',finished_utc=now())
    except BaseException as error:
        owner.update(status='FAILED',error=type(error).__name__+': '+str(error),finished_utc=now())
        print('ACCEPTANCE_FAILED '+owner['error'],flush=True)
        if active is not None:active.abort()
    finally:atomic_json(record_path,owner)
    if owner['status']=='FAILED':return 1
    # The independent read-only inspector decides PASS. This owner cannot make itself PASS.
    try:
        source_unchanged(owner)
        result=subprocess.run([sys.executable,str(ROOT/'tools/inspect-quest-evidence-paper.py'),'--run-dir',str(args.run_dir),'--attempt',args.attempt,'--expected-sha',args.expected_sha],cwd=ROOT,capture_output=True,text=True,encoding='utf-8',timeout=120)
        with (evidence/'quest-evidence-inspector-output.log').open('x',encoding='utf-8') as output:output.write(result.stdout+result.stderr)
        owner.update(status='PASS' if result.returncode==0 else 'INSPECTION_FAILED',inspection_exit=result.returncode,finished_utc=now())
        if result.returncode==0:
            inspected=load(evidence/'quest-evidence-inspection.json')
            if inspected.get('status')!='PASS' or inspected.get('product_sha256')!=args.expected_sha:raise RuntimeError('Inspector did not produce an actual matching PASS artifact')
    except Exception as error:
        owner.update(status='INSPECTION_FAILED',inspection_error=type(error).__name__+': '+str(error),finished_utc=now())
    atomic_json(record_path,owner)
    print(owner['status'],flush=True);return 0 if owner['status']=='PASS' else 1


if __name__=='__main__':
    for stream in (sys.stdout,sys.stderr):
        if hasattr(stream,'reconfigure'):stream.reconfigure(encoding='utf-8',errors='backslashreplace')
    raise SystemExit(main())
