"""Independently compare stopped capital log bytes, Chronicle SQL and real Paper observations."""
from contextlib import closing
from datetime import datetime, timezone
from pathlib import Path
import argparse
import hashlib
import json
import re
import socket
import sqlite3
import uuid

SOURCE = 'starcore.capital.designations.v1'
FORMAT = 'STARCORE_CAPITAL_HISTORY_V1'


def check(condition, message):
    if not condition: raise AssertionError(message)


def read(path): return json.loads(path.read_text())
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def records(path): return [json.loads(line) for line in path.read_text().splitlines() if line.startswith('{')]
def instant(value): return datetime.fromisoformat(value.replace('Z', '+00:00')).timestamp()
def connect(path):
    db = sqlite3.connect(path.resolve().as_uri()+'?mode=ro&immutable=1', uri=True)
    db.row_factory = sqlite3.Row
    return db


def native(path):
    raw = path.read_bytes()
    fields = path.with_name(path.name+'.head').read_text().strip().split('|')
    check(len(fields)==5 and fields[0]==FORMAT, 'Invalid independent capital head')
    _,domain,count,length,digest = fields
    check(str(uuid.UUID(domain))==domain, 'Invalid native domain')
    header = f'{FORMAT}|{domain}\n'.encode()
    check(path.with_name(path.name+'.authority').read_bytes()==header and raw.startswith(header), 'Native authority domain mismatch')
    check(len(raw)==int(length) and hashlib.sha256(raw).hexdigest()==digest, 'Missing or modified committed native bytes')
    lines = raw[len(header):].decode('ascii').splitlines()
    check(len(lines)==int(count) and (not lines or raw.endswith(b'\n')), 'Native committed head count differs')
    result=[]; previous={}; operations=set()
    for index,line in enumerate(lines,1):
        values=line.split('|');check(len(values)==7,'Malformed native record')
        sequence,operation,nation,old,city,actor,stamp=values
        check(int(sequence)==index and operation not in operations,'Noncontiguous or duplicate native operation')
        for identity in (operation,nation,city,actor): check(str(uuid.UUID(identity))==identity,'Invalid native identity')
        prior=None if old=='-' else old
        check(prior==previous.get(nation) and prior!=city,'Broken native city chain')
        previous[nation]=city;operations.add(operation)
        committed=datetime.fromtimestamp(int(stamp)/1000,timezone.utc).isoformat(timespec='milliseconds').replace('+00:00','Z')
        result.append(dict(sequence=index,operationId=operation,nationId=nation,previousCityId=prior,cityId=city,actorId=actor,committedAt=committed))
    return domain,result


def canonical(rows):
    return [dict(row,committedAt=instant(row['committedAt'])) for row in rows]


def inspect_db(path,domain,receipts,expected):
    with closing(connect(path)) as db:
        check(db.execute('pragma integrity_check').fetchone()[0]=='ok','Chronicle database integrity failed')
        cursor=[dict(r) for r in db.execute('select * from committed_history_sources where source=?',(SOURCE,))]
        if expected==0:
            check(cursor==[] or cursor==[dict(source=SOURCE,authority_domain=domain,sequence=0)],'Offline cursor advanced before consumption')
        else: check(cursor==[dict(source=SOURCE,authority_domain=domain,sequence=expected)],'Chronicle cursor/domain mismatch')
        ingress=[dict(r) for r in db.execute('select * from committed_history_ingress where source=? order by sequence',(SOURCE,))]
        stored={r['id']:dict(r) for r in db.execute("select * from events where id like 'starcore:capital-history:%'")}
        history=[json.loads(r['json']) for r in stored.values()]
        check(len(ingress)==len(history)==expected,'Chronicle inbox/event count mismatch')
        check(db.execute("select count(*) from events where type in ('CAPITAL_DESIGNATED','CAPITAL_MOVED')").fetchone()[0]==expected,'A snapshot fabricated or duplicated capital history')
        by_id={e['id']:e for e in history}
        for index,row in enumerate(ingress,1):
            receipt=receipts[index-1];event=json.loads(row['json']);identity=f'starcore:capital-history:{domain}:{index}'
            check(row['sequence']==index and row['status']=='ACKED' and row['event_id']==identity and event['id']==identity,'Inbox acknowledgement or identity differs')
            check(by_id[identity]==event,'Inbox payload differs from committed event')
            check(event['type']==('CAPITAL_DESIGNATED' if index==1 else 'CAPITAL_MOVED'),'Initial designation confused with migration')
            check(instant(event['occurredAt'])==instant(receipt['committedAt']),'Commit time changed')
            check(event['participants']==[receipt['actorId']] and event['nations']==[receipt['nationId']],'Original actor/nation changed')
            expected_facts={'time_basis':'COMMITTED','source.authority_domain':domain,'source.sequence':str(index),
                'capital.operation_id':receipt['operationId'],'capital.nation':receipt['nationId'],'capital.actor':receipt['actorId'],
                'capital.previous_city':receipt['previousCityId'] or 'NONE','capital.city':receipt['cityId'],
                'capital.kind':'DESIGNATED' if index==1 else 'MOVED','title':'国家首次指定首都' if index==1 else '国家迁都'}
            check(event['facts']==expected_facts and event['source']=='StarCore.NationService','Committed capital facts/source differ')
            check(stored[identity]['type']==event['type'] and instant(stored[identity]['time'])==instant(event['occurredAt']),'Stored event columns differ from immutable payload')
            proof=f'Native NationService committed capital designation; domain={domain}; sequence={index}; operation={receipt["operationId"]}; nation={receipt["nationId"]}; previous={receipt["previousCityId"] or "NONE"}; city={receipt["cityId"]}; actor={receipt["actorId"]}; designation and history share the published log head; current rulers and derived locations are not substituted'
            check(event['evidence']==proof,'Capital evidence differs from native operation')
            value=dict(id=identity,type=event['type'],time=event['occurredAt'],players=event['participants'],nations=event['nations'],facts=event['facts'],source=event['source'],evidence=event['evidence'])
            raw=json.dumps(value,sort_keys=True,ensure_ascii=False,separators=(',',':')).encode()
            check(stored[identity]['hash']==hashlib.sha256(str(len(raw)).encode()+b':'+raw).hexdigest(),'Capital canonical event hash mismatch')
            check([r[0] for r in db.execute('select player from event_players where event_id=?',(identity,))]==[receipt['actorId']],'Player projection changed')
            check([r[0] for r in db.execute('select nation from event_nations where event_id=?',(identity,))]==[receipt['nationId']],'Nation projection changed')
        check(db.execute('select count(*) from contributions').fetchone()[0]==0,'Capital change fabricated material contribution')
    return by_id


def inspect(run):
    run=run.resolve();manifest=read(run/'preparation.json');mode=manifest['capital_acceptance']['mode'];offline=mode=='offline'
    boots=2 if offline else 3;final=205 if offline else 206
    with socket.socket() as probe:
        probe.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1);probe.bind(('127.0.0.1',manifest['server_port']))
    for name,key in [('StarCore','starcore_sha256'),('StarChronicle','plugin_sha256'),('EpicCampaignPaperFixture','fixture_sha256')]:
        check(sha(run/'plugins'/f'{name}.jar')==manifest[key],'Frozen artifact changed')
    for name,digest in {'EpicCampaignPaperFixture.java':manifest['fixture_source_sha256'],**manifest['fixture_extra_sources_sha256']}.items():
        check(sha(run/'fixture'/name)==digest,'Frozen fixture changed')
    paper={v for k,v in manifest['source_artifacts'].items() if Path(k).name=='paper.jar'}
    check(len(paper)==1 and sha(run/'paper.jar') in paper,'Frozen Paper changed')
    for artifact in manifest['production_stack']['artifacts']:
        check(sha(run/'plugins'/artifact['filename'])==artifact['sha256'],'Frozen production dependency changed')
    evidence=run/'evidence';rows=records(evidence/'epic-fixture.jsonl')
    check(not any(r['kind']=='FAILED' for r in rows),'Retained fixture failure')
    check({r['boot'] for r in rows}==set(range(1,boots+1)),'Missing actual fixture boots')
    check(len(list(evidence.glob('boot*.session.json')))==boots,'Missing complete capital boot')
    bound=[r for r in rows if r['kind']=='CAPITAL_BOUND'];check(len(bound)==1 and bound[0]['boot']==1,'Missing real native city binding')
    bound=bound[0]['data'];seed=next(r['data'] for r in rows if r['kind']=='SEEDED')
    check(bound['nation']==seed['A'] and bound['actor']==manifest['players']['A'],'Native binding changed')
    logs=list((run/'plugins/StarCore').rglob('capital-designations.log'));check(len(logs)==1,'Missing native capital authority')
    domain,receipts=native(logs[0]);check(domain==bound['domain'] and len(receipts)==final,'Native final head mismatch')
    burst=[r for r in rows if r['kind']=='CAPITAL_BURST'];check(len(burst)==1 and burst[0]['boot']==1,'Missing native burst')
    burst=burst[0]['data'];check((burst['count'],burst['firstDesignations'],burst['moves'])==(205,1,204),'Burst count differs')
    for index,row in enumerate(receipts,1):
        check(row['nationId']==seed['A'] and row['actorId']==manifest['players']['A' if index<=205 else 'D'],'Native historical actor differs')
        check(row['cityId']==bound['cities']['A' if index%2==1 else 'D'],'Native destination differs')
        expected=uuid.UUID(bytes=hashlib.md5(f'capital-acceptance-operation-{index}'.encode()).digest(),version=3)
        check(row['operationId']==str(expected),'Native operation ID differs')
        if index<=205: check(instant(burst['started'])-0.001<=instant(row['committedAt'])<=instant(burst['finished']),'Native commit outside burst')
    final_events=inspect_db(run/'plugins/StarChronicle/chronicle.db',domain,receipts,final)
    sessions=[];telemetry=[]
    for boot in range(1,boots+1):
        session=read(evidence/f'boot{boot}.session.json');phase=('offline-' if offline else '')+('first','second','third')[boot-1]
        check(session['status']=='COMPLETE' and session['scenario']=='capital-'+phase,'Incomplete controller scenario')
        processes=session['processes'];check(len(processes)==2 and {p['name'] for p in processes}=={'server','clients'},'Missing server/client process')
        check(all(p.get('exit_code')==0 and p['exited_at']>=p['started_at'] for p in processes),'Abnormal capital process exit')
        server=next(p for p in processes if p['name']=='server');client=next(p for p in processes if p['name']=='clients')
        if sessions: check(sessions[-1]['exited_at']<server['started_at'],'Overlapping boots')
        sessions.append(server)
        for name in ('controller','clients'):
            suffix='py' if name=='controller' else 'cjs'
            check(sha(evidence/f'boot{boot}.{name}.{suffix}')==session['source_sha256'][name],'Recorded runtime script changed')
        bootrows=[r for r in rows if r['boot']==boot]
        for kind in ('READY','SEEDED','CLOSED','STACK_LOADED'):
            check(sum(r['kind']==kind for r in bootrows)==1,'Missing live lifecycle/stack check: '+kind)
        check(next(r['data'] for r in bootrows if r['kind']=='SEEDED')==seed and seed['nonOp'] is True,'Seed/permissions changed')
        check(next(r['data'] for r in bootrows if r['kind']=='READY')=={'product':manifest['plugin_sha256'],'native':manifest['starcore_sha256'],'route':manifest['route']},'Running artifact mismatch')
        check(all(server['started_at']<=instant(r['time'])<=server['exited_at'] for r in bootrows),'Evidence outside actual server lifetime')
        log=(evidence/f'boot{boot}.server.log').read_text()
        diagnostics=re.findall(r'^.*(?:ERROR\]|ERROR\]:|Failed to (?:enable|disable) STARCORE module|同步保存失败|异步保存失败|SQLITE_BUSY|Could not pass event|Committed history source .* retained|History scan retained pending evidence).*$',log,re.M|re.I)
        for line in diagnostics:
            check(re.fullmatch(r'\[[0-9:]+ ERROR\]: \[FancyAnalytics\] Failed to create record \(status 5\d\d\): <html>',line) is not None,'Server persistence/lifecycle diagnostic: '+line)
            telemetry.append(dict(boot=boot,message=line))
        check(all(word in log for word in ('Done (','Stopping server','All dimensions are saved')),'Incomplete normal server lifecycle')
        for kind in {r['kind'] for r in bootrows}:
            actual=[json.loads(line.split('EPIC_'+kind+' ',1)[1]) for line in log.splitlines() if 'EPIC_'+kind+' ' in line]
            check(actual==[r['data'] for r in bootrows if r['kind']==kind],'Live fixture data missing from server stream')
        clients=records(evidence/f'boot{boot}.clients.log');check(not any(r['kind']=='FAILED' for r in clients),'Client failure')
        spawns=[r['data'] for r in clients if r['kind']=='SPAWN']
        check(len(spawns)==4 and {s['who'] for s in spawns}==set('ABCD') and all(s['uuid']==manifest['players'][s['who']] for s in spawns),'Missing real client identities')
        observations=[r for r in bootrows if r['kind']=='CAPITAL_CHECK']
        expected_checks=({1:[0],2:[205,205]} if offline else {1:[0,205],2:[205,206,206],3:[206]})[boot]
        check([r['data']['expected'] for r in observations]==expected_checks,'Missing capital live checkpoint')
        for observation in observations:
            data=observation['data'];count=data['expected']
            check(data['domain']==domain and canonical(data['native'])==canonical(receipts[:count]),'Live native page differs from durable log')
            check(data['events']==[final_events[f'starcore:capital-history:{domain}:{i}'] for i in range(1,count+1)],'Live Chronicle history differs from SQL')
        if offline and boot==2:
            check(observations[0]['data']['onlinePlayers']==0 and instant(observations[0]['time'])<client['started_at'],'Offline backlog was not consumed before login')
        checkpoint=evidence/'checkpoints'/f'boot{boot}';inventory=read(checkpoint/'checkpoint.json')
        check(inventory['boot']==boot,'Wrong checkpoint boot')
        for relative,digest in inventory['sha256'].items():check(sha(checkpoint/relative)==digest,'Stopped checkpoint bytes changed')
        native_logs=list((checkpoint/'StarCore').rglob('capital-designations.log'));check(len(native_logs)==1,'Missing native checkpoint')
        check_domain,check_rows=native(native_logs[0]);native_count=205 if offline or boot==1 else 206
        check(check_domain==domain and canonical(check_rows)==canonical(receipts[:native_count]),'Native restart checkpoint changed')
        inspect_db(checkpoint/'chronicle.db',domain,receipts,0 if offline and boot==1 else native_count)
    rejected=[r['data'] for r in rows if r['kind']=='CAPITAL_REJECTIONS']
    check(rejected==([dict(actor='A',rejected=5,head=205)] if offline else [dict(actor='A',rejected=5,head=205),dict(actor='D',rejected=4,head=206)]),'Missing permission/replay/no-op rejections')
    repeated=[r['data'] for r in rows if r['kind']=='CAPITAL_REPEAT'];check(len(repeated)==2,'Missing actual operation retries')
    check(all(canonical(r['original'])==canonical(receipts[:1]) and r['head']==205 for r in repeated),'Replay changed original operation')
    if offline:
        resume=read(evidence/'capital-poll-resume.json')
        check(hashlib.sha256(resume['before'].encode()).hexdigest()==resume['before_sha256'] and hashlib.sha256(resume['after'].encode()).hexdigest()==resume['after_sha256'],'Offline configuration provenance changed')
        check(resume['before'].count('poll-ticks: 72000\n')==1 and resume['after']==resume['before'].replace('poll-ticks: 72000\n','poll-ticks: 20\n'),'Offline polling changed beyond the declared interval')
        check((run/'plugins/StarChronicle/config.yml').read_text()==resume['after'],'Resumed configuration changed')
        buffered=[r['data'] for r in rows if r['kind']=='CAPITAL_BUFFERED'];check(buffered==[dict(head=205,domain=domain,consumerEvents=0)],'Offline handoff not captured')
    else:
        successor=[r for r in rows if r['kind']=='CAPITAL_SUCCESSOR'];deleted=[r for r in rows if r['kind']=='CAPITAL_CITY_DELETED']
        check(len(successor)==len(deleted)==1 and successor[0]['boot']==deleted[0]['boot']==2,'Missing real successor/deletion')
        check(successor[0]['data']['founder']==manifest['players']['D'] and canonical(successor[0]['data']['receipt'])==canonical(receipts[-1:]),'Successor receipt mismatch')
        check(deleted[0]['data']['city']==bound['cities']['D'] and canonical(deleted[0]['data']['retained'])==canonical(receipts[-1:]),'City deletion rewrote history')
    return dict(status='ISOLATED_PAPER_CAPITAL_ACCEPTED',mode=mode,boots=boots,commits=final,first_designations=1,moves=final-1,
                chronicle_sha256=manifest['plugin_sha256'],starcore_sha256=manifest['starcore_sha256'],domain=domain,
                rejected_operations=sum(r['rejected'] for r in rejected),operation_retries=len(repeated),offline_before_login=offline,vendor_telemetry=telemetry)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--run',type=Path,required=True)
    print(json.dumps(inspect(parser.parse_args().run),ensure_ascii=False,indent=2))
