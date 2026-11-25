"""Read-only verification of real UNKNOWN, explicit retry, native task identity and four boots."""
from contextlib import closing
from pathlib import Path
import argparse
import importlib.util
import json
import re
SPEC=importlib.util.spec_from_file_location('recovery_stack',Path(__file__).with_name('inspect-epic-production-paper.py'))
STACK=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(STACK)
EPIC=STACK.EPIC;Q=EPIC.QUEST
check,read,sha=STACK.check,STACK.read,STACK.sha
SCENARIOS=('quest-first','first','second','third')
IDENTITY=('bindingId','runId','playerId','questId','definitionFingerprint','issuer')


def round_binding(db,gate):
    head=db.execute('select round from quest_gate_round_heads where gate_id=?',(gate['id'],)).fetchone()
    number=head[0] if head else 0
    rows=list(db.execute('select * from quest_gate_round_history where gate_id=? order by round',(gate['id'],)))
    check(number in (0,1) and len(rows)==number,'Missing or unexpected retired task round')
    if number==0:return gate['id']
    row=rows[0];proof=json.loads(row['proof_json']);old=proof['gate'];terminal=proof['terminalProof']
    binding=Q.stable('quest-gate-round',gate['id'],'1')
    check(row['round']==0 and row['proof_hash']==Q.stable('quest-round-proof',row['proof_json']),'Retired task proof hash differs')
    mapping={'id':'id','instanceId':'instance_id','sourceEventId':'source_event_id','storyId':'story_id','storyVersion':'story_version','storyFingerprint':'story_fingerprint','scopeKey':'scope_key','nodeId':'node_id','effectId':'effect_id','playerId':'player_id','questId':'quest_id','mode':'mode','expectedNationId':'expected_nation_id','providerId':'provider_id'}
    check(all(old[k]==gate[v] for k,v in mapping.items()),'Retired task identity/provider changed')
    check(old['round']==0 and old['active'] is True and old['status']=='ABANDONED' and old['completion'] is None and old['revision']<gate['revision'],'Retired task is not an original abandoned run')
    check(terminal['status']=='ABANDONED' and terminal['acceptedRun']==old['acceptedRun'] and terminal['completion'] is None and proof['requestedBy']==gate['player_id'],'Retirement lacks owner and exact terminal proof')
    check(old['acceptedRun']['bindingId']==gate['id'] and proof['nextBindingId']==binding,'Retired/replacement binding differs')
    check(Q.millis(old['openedAt'])<=Q.millis(old['providerPinnedAt'])<=Q.millis(old['acceptedRun']['startedAt'])<=Q.millis(terminal['observedAt'])<=Q.millis(proof['retiredAt'])==Q.millis(old['lastCheckedAt'])==gate['opened_at']==gate['provider_pinned_at'],'Retired task chronology differs')
    return binding


def native_files(root):
    authority=Q.properties(root/'evidence-authority.properties')['authority']
    shards={}
    for path in (root/'tracked-evidence').glob('*.properties'):
        entry=json.loads(Q.properties(path)['entry']);s=entry['snapshot'];binding=s['bindingId']
        import hashlib
        check(path.name==hashlib.sha256(binding.encode()).hexdigest()+'.properties' and binding not in shards,'Native shard binding/name differs')
        shards[binding]=s
    journal={}
    for path in (root/'completion-journal').glob('*.json'):
        s=read(path);check(path.name==s['completionId']+'.json' and s['completionId'] not in journal,'Native journal identity differs');journal[s['completionId']]=s
    return authority,shards,journal,Q.properties(root/'quests.properties')


def acceptance(accepted,native):
    check(all(accepted[k]==native[k] for k in IDENTITY) and Q.millis(accepted['startedAt'])==native['startedAt'] and accepted['evidence']==native['acceptedEvidence'],'Accepted task identity/evidence differs from native shard')
    country=accepted['acceptedNation']
    check(country['state']==native['acceptedNationState']=='KNOWN_NATION' and country['nationId']==native['acceptedNation'] and Q.millis(country['checkedAt'])==native['acceptedNationCheckedAt'],'Task acceptance country differs')


def inspect(run):
    run=run.resolve();manifest=read(run/'preparation.json');contract=manifest['quest_recovery_acceptance']
    check(manifest['route']=='DUNGEON' and contract['quest']=='chronicle_heir_records' and contract['owner']=='A' and contract['round']==1 and contract['scenarios']==list(SCENARIOS),'Unexpected task recovery contract')
    result=STACK.inspect(run,expected_fixture='QuestRecoveryPaperFixture',scenarios=SCENARIOS,quest_binding=round_binding)
    rows=STACK.read_lines(run/'evidence/epic-fixture.jsonl');clients={b:STACK.read_lines(run/f'evidence/boot{b}.clients.log') for b in range(1,5)}
    def only(kind,boot=1):
        selected=[r for r in rows if r['kind']==kind and r['boot']==boot];check(len(selected)==1,'Missing/duplicate task recovery evidence: '+kind);return selected[0]
    held=only('QUEST_IO_HELD');released=only('QUEST_IO_RELEASED');unknown_retry=only('QUEST_UNKNOWN_RETRY_HELD')
    check(held['data']['player']=='A' and held['time']<unknown_retry['time']<released['time'] and released['data']['writesResumed'] is True,'Actual storage-delay boundary missing')
    check(unknown_retry['data']['actualCanonicalRead'] is True and unknown_retry['data']['gate']['round']==0 and unknown_retry['data']['gate']['status']=='UNKNOWN','UNKNOWN allocated a replacement run')
    states=[r for r in rows if r['kind']=='QUEST_RECOVERY_STATE'];first=[r for r in states if r['boot']==1]
    expected=[('A',0,'ACTIVE'),('A',0,'UNKNOWN'),('A',0,'UNKNOWN'),('A',0,'ABANDONED'),('A',1,'ACTIVE'),('B',0,'ACTIVE')]
    check([(r['data']['who'],r['data']['round'],r['data']['status']) for r in first]==expected,'Actual task recovery sequence differs')
    for row in states:
        d=row['data'];g=d['gate'];n=d['native'];disk=d['disk']['snapshot'];who=d['who']
        check(g['playerId']==n['playerId']==manifest['players'][who] and g['round']==d['round'] and g['status']==d['status'] and g['runId']==n['runId'],'Live recovery identity/status differs')
        check(n['bindingId']==(g['gateId'] if d['round']==0 else Q.stable('quest-gate-round',g['gateId'],str(d['round']))),'Live recovery binding differs')
        check(n['status']==('COMPLETED' if d['status']=='COMMITTED' else d['status']) and d['active']==(d['status']=='ACTIVE'),'Live native lifecycle differs')
        if d['status']=='UNKNOWN':
            check(disk['status']=='ACTIVE' and held['time']<row['time']<released['time'],'UNKNOWN must retain the pre-write native shard')
        else:check(disk['status']==n['status'],'Live canonical state appeared before durable write')
        check(Q.snapshot_fields(disk,Q.RUN_FIELDS)==Q.snapshot_fields(n,Q.RUN_FIELDS),'Storage delay changed native acceptance identity')
    abandoned=[r for r in clients[1] if r['kind']=='QUEST_GUI_ABANDON'];gui=[r for r in clients[1] if r['kind']=='QUEST_GUI_RECOVERY']
    check(len(abandoned)==1 and abandoned[0]['data']==dict(who='A',quest=contract['quest'],slot=30,realClick=True,syntheticEvents=False),'Real native GUI abandonment missing')
    check(held['time']<abandoned[0]['time']<first[1]['time'],'Actual abandonment did not occur during storage delay')
    check(len(gui)==2 and [r['data']['action'] for r in gui]==['reconcile','retry'],'Real reconciliation/retry GUI sequence missing')
    for row in gui:
        d=row['data'];check(d['who']=='A' and d['realClick'] is True and d['syntheticEvents'] is False and d['before']['gate']==d['after']['gate']==held['data']['binding'],'Task recovery GUI provenance differs')
        check(d['before']['round']=='1' and d['before']['run']==held['data']['runId'],'Task GUI lost the original run')
        if d['action']=='reconcile':check(d['slot']==46 and d['before']==d['after'] and d['before']['status']=='状态待核对' and held['time']<row['time']<released['time'],'UNKNOWN reconcile changed the task or ran outside the delay')
        else:check(d['slot']==52 and d['before']['status']=='原生任务已放弃' and d['after']['round']=='2' and released['time']<row['time']<first[4]['time'],'Retry did not follow a durable abandonment')
    rejected=[r for r in rows if r['kind']=='QUEST_RETRY_REJECTED']
    check([(r['boot'],r['data']['kind']) for r in rejected]==[(1,'foreign'),(1,'stale'),(2,'stale'),(3,'completed'),(4,'completed')],'Owner/stale/completed retry rejections missing')
    snapshots=[];retired_run=None;replacement_run=None;task_ids={}
    for boot in range(1,5):
        cp=run/f'evidence/checkpoints/boot{boot}';inventory=read(cp/'checkpoint.json')['sha256']
        files={p.relative_to(cp).as_posix() for p in cp.rglob('*') if p.is_file() and p.name!='checkpoint.json'}
        # Legacy read-only inspections created empty WAL/shared-memory files. They are
        # not task evidence; a nonempty journal at a stopped checkpoint is never ignored.
        for name in ('chronicle.db','starcore.db'):
            for suffix in ('-wal','-journal'):
                sidecar=cp/(name+suffix);check(not sidecar.exists() or sidecar.stat().st_size==0,'Stopped task checkpoint has a pending journal')
            files.difference_update({name+'-wal',name+'-shm',name+'-journal'})
        check(files==set(inventory),'Missing/unbound stopped task checkpoint files')
        for name,digest in inventory.items():
            check(sha(cp/name)==digest,'Stopped task checkpoint changed')
            if boot==4:
                actual=run/'plugins'/name if name.startswith('StarCore/') else run/'plugins'/('StarChronicle' if name=='chronicle.db' else 'StarCore')/name
                check(sha(actual)==digest,'Final runtime differs from stopped task checkpoint')
        authority,shards,journal,quests=native_files(cp/'StarCore/quest')
        check(len(shards)==4 and len(journal)==(0 if boot==1 else 2),'Missing/extra native run or completion')
        with closing(EPIC.connect(cp/'chronicle.db')) as db:
            check(db.execute('pragma user_version').fetchone()[0]==8,'Recovery schema mismatch')
            gates=STACK.table(db,'quest_gates');check(len(gates)==3 and {g['player_id'] for g in gates}=={manifest['players'][w] for w in 'ABD'},'Unexpected task owners')
            check(db.execute('select count(*) from quest_gate_round_heads').fetchone()[0]==1 and db.execute('select count(*) from quest_gate_round_history').fetchone()[0]==1,'Duplicate/missing task retirement')
            for g in gates:
                who=next(w for w in 'ABD' if manifest['players'][w]==g['player_id']);binding=round_binding(db,g);native=shards[binding]
                check(g['provider_id']=='starcore.quest.evidence.v1:'+authority and g['quest_id']==contract['quest'] and g['id']==Q.stable('quest-gate',g['instance_id'],g['node_id'],g['effect_id'],g['player_id']),'Task gate provider or stable identity differs')
                check(binding!=g['id'] if who=='A' else binding==g['id'],'Wrong owner received a new task round')
                acceptance(json.loads(g['accepted_json']),native)
                check(g['provider_pinned_at']<=native['acceptedNationCheckedAt']<=native['startedAt'],'Task admission predates provider pin')
                active=json.loads(quests.get(g['player_id']+'.active','[]'));active=[a for a in active if a['id']==g['quest_id']]
                completed=who in 'AB' and boot>1
                check(g['status']==('COMMITTED' if completed else 'ACTIVE') and native['status']==('COMPLETED' if completed else 'ACTIVE'),'Stopped task status differs')
                if who=='A':
                    old=shards[g['id']];proof=json.loads(db.execute('select proof_json from quest_gate_round_history where gate_id=?',(g['id'],)).fetchone()[0]);acceptance(proof['gate']['acceptedRun'],old)
                    check(old['status']=='ABANDONED' and old.get('completionId') is None and old['runId']!=native['runId'],'Original terminal run lost or reused')
                    if retired_run is None:retired_run=old['runId'];replacement_run=native['runId']
                    check(old['runId']==retired_run and native['runId']==replacement_run,'Task run changed across restart')
                if completed:
                    check(not active,'Completed task was readmitted')
                    completion=json.loads(g['completion_json']);record=dict(db.execute('select * from quest_gate_completions where gate_id=?',(g['id'],)).fetchone())
                    check(record['proof_json']==g['completion_json'] and record['proof_hash']==Q.stable('quest-completion-proof',record['proof_json']) and record['completion_id']==native['completionId'] and record['run_id']==native['runId'] and record['provider_id']==g['provider_id'],'Consumed proof or identity mismatch')
                    check(native['rewardStatus']=='INVOKED' and Q.snapshot_fields(journal[native['completionId']],Q.RECEIPT_FIELDS)==Q.snapshot_fields(native,Q.RECEIPT_FIELDS),'Native immutable journal differs')
                    marker=Q.quest_record(quests,g['player_id'],'runCompletions',g['quest_id']);check(marker==Q.snapshot_fields(native,Q.MARKER_FIELDS),'Native completed-run marker differs')
                    check(g['quest_id']+':1' in quests[g['player_id']+'.counts'].split(';'),'Native task completed more than once')
                    for key in (*IDENTITY,'completionId','acceptedEvidence'):check(completion[key]==native[key],'Task completion DTO differs from native journal')
                    check(all(Q.millis(completion[k])==native[k] for k in ('startedAt','completedAt','committedAt')) and completion['evidence']==native['completionEvidence'],'Task completion time/evidence differs')
                    event_id='chronicle:quest:'+Q.stable(native['issuer'],native['completionId']);event=Q.verify_world_event(dict(db.execute('select * from events where id=?',(event_id,)).fetchone()))
                    check(event['facts']['quest.gate']==g['id'] and event['facts']['quest.run']==native['runId'] and event['facts']['quest.completion']==native['completionId'] and event['participants']==[g['player_id']] and event['nations']==[g['expected_nation_id']],'Task completion history attribution differs')
                    task_ids[who]=dict(gate=g['id'],binding=binding,run=native['runId'],completion=native['completionId'])
                else:
                    check(len(active)==1 and active[0]['runId']==native['runId'] and active[0]['bindingId']==binding and active[0]['startedAt']==native['startedAt'] and all(o['progress']==0 for o in active[0]['objectives']),'Unfinished task was not restored exactly')
            check(db.execute("select count(*) from events where type='quest.completed'").fetchone()[0]==(0 if boot==1 else 2),'Duplicate/early task history')
            snapshots.append({name:STACK.table(db,name) for name in ('quest_gate_round_heads','quest_gate_round_history','quest_gate_completions')})
        server=(run/f'evidence/boot{boot}.server.log').read_text()
        check(not re.search(r'Quest gate retained:|Quest evidence scan retained:|Native terminal-state persistence failed',server),'Unexpected quest persistence diagnostic')
        for kind in ('QUEST_IO_HELD','QUEST_IO_RELEASED','QUEST_UNKNOWN_RETRY_HELD','QUEST_RECOVERY_STATE','QUEST_RETRY_REJECTED'):
            emitted=[json.loads(line.split('EPIC_'+kind+' ',1)[1]) for line in server.splitlines() if 'EPIC_'+kind+' ' in line]
            check(emitted==[r['data'] for r in rows if r['boot']==boot and r['kind']==kind],'Task server evidence differs: '+kind)
    check(snapshots[1]==snapshots[2]==snapshots[3] and all(snapshots[0][name]==snapshots[-1][name] for name in ('quest_gate_round_heads','quest_gate_round_history')),'Retirement/completion changed across restart or replay')
    check(task_ids['A']['run']==replacement_run and retired_run not in {v['run'] for v in task_ids.values()},'Retired task became a completed source')
    return dict(result,status='TASK_RECOVERY_AND_SELECTED_PAPER_EPIC_ACCEPTED',retired_runs=1,task_rounds=dict(A=1,B=0,D=0),task_completions=task_ids,retired_run=retired_run,
        limitation='Controlled delay of the actual native task shard write; no power-loss, permanently unresolved native UNKNOWN, reward replay, paid founding, full-stack Folia or production-deployment claim.')

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('run',type=Path)
    print(json.dumps(inspect(p.parse_args().run),ensure_ascii=False,indent=2))
