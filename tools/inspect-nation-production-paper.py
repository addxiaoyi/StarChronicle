"""Independently audit stopped paid production journals, stockpiles and civilization views."""
from contextlib import closing
from pathlib import Path
from decimal import Decimal
import argparse
import importlib.util
import json
import re
import socket
import uuid

SPEC=importlib.util.spec_from_file_location('trade_primitives',Path(__file__).with_name('inspect-nation-trade-paper.py'))
T=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(T)
check,read,sha,rows,connect,records,instant,millis,stamp,hashed,compact=T.check,T.read,T.sha,T.rows,T.connect,T.records,T.instant,T.millis,T.stamp,T.hashed,T.compact
SOURCE='starcore.nation-production.completions.v1'
ISSUER='StarCore.NationProductionService'
TERMS={'primary':('A',1),'pending':('A',2),'cancelled':('A',1),'old-founder':('A',1),'successor':('D',2)}

def native(path,seed,players,captured,keys,completed,founder):
    with closing(connect(path)) as db:
        heads=rows(db,'select * from starcore_nation_production_head')
        check(len(heads)==1 and heads[0]['singleton']==1,'Missing production head')
        head=heads[0];domain=head['authority_domain'];resource_domain=head['resource_domain']
        check(str(uuid.UUID(domain))==domain and str(uuid.UUID(resource_domain))==resource_domain,'Noncanonical production authority')
        markers={r[0] for r in db.execute('select migration_key from starcore_resource_state_migrations')}
        check({'nation-production-journal-v1','resource-state-revision-v1'}<=markers,'Missing production marker')
        revisions=rows(db,'select * from starcore_resource_revision')
        check(len(revisions)==1 and revisions[0]['singleton']==1 and revisions[0]['revision']>=head['event_sequence'],'Missing inventory revision')
        state=dict(db.execute('select property_key,property_value from starcore_resource_state'))
        check(state['resource.authority-domain']==resource_domain,'Production asset authority changed')
        entries=rows(db,'select * from starcore_nation_production_events order by event_sequence')
        prefix='0'*64;current={};receipts=[];sequence=0;completed_sequence=0
        for row in entries:
            sequence+=1
            check(row['event_sequence']==sequence,'Production event gap')
            run=json.loads(row['run_json']);id=run['runId'];key=next((k for k in keys if captured[k]['runId']==id),None)
            check(key is not None,'Unexpected production run')
            who,batches=TERMS[key]
            expected=dict(format=1,runId=id,actorId=players[who],nationId=seed['A'],resourceDomain=resource_domain,
                recipeId='industrial_refining',batches=batches,inputs={'energy':batches,'mineral':3*batches},outputs={'industrial':2*batches},
                startedAt=captured[key]['startedAt'],readyAt=captured[key]['readyAt'])
            check(run==expected and row['run_json']==compact(expected),'Frozen production terms differ')
            check({k:v for k,v in run.items() if k!='format'}==captured[key],'Observed run differs from paid SQL run')
            check(row['run_id']==id and millis(run['readyAt'])-millis(run['startedAt'])==batches*30000,'Production duration or identity differs')
            status=row['state'];before=current.get(id)
            check(status=='RUNNING' if before is None else before=='RUNNING' and status in ('COMPLETED','CANCELLED'),'Invalid production transition')
            changed=stamp(row['changed_at']);check(row['changed_at']>=millis(run['startedAt']),'Production transition precedes start')
            if status=='RUNNING':check(row['changed_at']==millis(run['startedAt']),'Run start differs from paid transition')
            if status=='COMPLETED':
                completed_sequence+=1
                check(row['completion_sequence']==completed_sequence and row['changed_at']>=millis(run['readyAt']),'Early or duplicate production completion')
                receipts.append(dict(authorityDomain=domain,sequence=completed_sequence,run=captured[key],committedAt=changed))
            else:check(row['completion_sequence'] is None,'Unfinished run has completion sequence')
            prefix=hashed(f'{domain}\n{resource_domain}\n{sequence}\n{row["completion_sequence"] or 0}\n{prefix}\n{status}\n{changed}\n{row["run_json"]}')
            check(row['entry_hash']==prefix,'Production event digest mismatch');current[id]=status
        check(sequence==head['event_sequence'] and completed_sequence==head['completed_sequence']==len(completed) and prefix==head['prefix_hash'],'Production committed prefix incomplete')
        check(len(current)==len(keys) and [r['run']['runId'] for r in receipts]==[captured[k]['runId'] for k in completed],'Production run/receipt count differs')
        for key in keys:check(current[captured[key]['runId']]==('COMPLETED' if key in completed else 'CANCELLED' if key=='cancelled' else 'RUNNING'),'Production state/restart boundary differs')
        used=sum(TERMS[k][1] for k in keys if k!='cancelled');delivered=sum(TERMS[k][1]*2 for k in completed)
        stocks={}
        for i in range(int(state['count'])):
            p=f'stockpile.{i}.';key=(state[p+'nationId'],state[p+'type']);check(key not in stocks,'Duplicate inventory entry');stocks[key]=int(state[p+'amount'])
        expected_stocks={(seed[w],'agricultural'):100 for w in 'ABC'}
        expected_stocks.update({(seed['A'],'mineral'):100-3*used,(seed['A'],'energy'):100-used})
        if delivered:expected_stocks[(seed['A'],'industrial')]=delivered
        check(stocks==expected_stocks,'Production input/output conservation failed')
        check(db.execute('select count(*) from starcore_resource_transfer_receipts').fetchone()[0]==0,'Production fabricated a bilateral transfer')
        treasury=dict(db.execute('select property_key,property_value from starcore_treasury_state'))
        check(all(Decimal(treasury[f'treasury.{i}.balance'])==1000000 for i in range(3)) and treasury['transfer.count']=='0','Production unexpectedly changed treasury')
        nations=dict(db.execute('select property_key,property_value from starcore_nation_state'))
        p=next(k[:-2] for k,v in nations.items() if k.endswith('.id') and '.member.' not in k and v==seed['A'])
        check(nations[p+'founderId']==players[founder],'Persisted production founder differs')
        return dict(head=head,entries=entries,receipts=receipts,stocks=stocks,delivered=delivered)

def event(receipt):
    run=receipt['run'];d=receipt['authorityDomain'];n=receipt['sequence']
    facts={'title':'国家生产已完成','time_basis':'COMMITTED_SOURCE','source.authority_domain':d,'source.sequence':str(n),
        'production.run_id':run['runId'],'production.actor':run['actorId'],'production.nation':run['nationId'],'production.resource_domain':run['resourceDomain'],
        'production.recipe':run['recipeId'],'production.batches':str(run['batches']),'production.started_at':run['startedAt'],'production.ready_at':run['readyAt'],
        'production.asset_atomicity':'NATIVE_SQL_STATE_TRANSITIONS'}
    for side in ('input','output'):
        facts.update({f'production.{side}.{key}':str(value) for key,value in run[side+'s'].items()})
    evidence=f'Native national production; authority={d}; sequence={n}; run={run["runId"]}; resource_domain={run["resourceDomain"]}; input consumption and paid run share one SQL commit; exact outputs and unique completion share one SQL commit; original initiator is frozen; no player inventory, external wallet, legacy factory attribution or extra rewards'
    return dict(id=f'starcore:nation-production:{d}:{n}',type='NATIONAL_PRODUCTION_COMMITTED',occurredAt=receipt['committedAt'],participants=[run['actorId']],nations=[run['nationId']],facts=facts,source=ISSUER,evidence=evidence)

def chronicle(path,state,count):
    expected=[event(r) for r in state['receipts'][:count]];domain=state['head']['authority_domain']
    with closing(connect(path)) as db:
        check(db.execute('pragma user_version').fetchone()[0]==8,'Chronicle schema differs')
        cursor=rows(db,'select * from committed_history_sources where source=?',(SOURCE,))
        ingress=rows(db,'select * from committed_history_ingress where source=? order by sequence',(SOURCE,))
        history=rows(db,"select * from events where type='NATIONAL_PRODUCTION_COMMITTED' or json_extract(json,'$.source')=?",(ISSUER,))
        contributions=rows(db,"select * from contributions where type='CIVILIZATION' order by id")
        check(len(ingress)==len(history)==count and len(contributions)==2*count,'Production ingress/history/contribution count differs')
        check(cursor==([dict(source=SOURCE,authority_domain=domain,sequence=count)] if count else []),'Production source cursor differs')
        result=[]
        for receipt,e,inbox in zip(state['receipts'],expected,ingress):
            matches=[v for v in history if v['id']==e['id']];check(len(matches)==1,'Canonical production event missing');row=matches[0]
            check(json.loads(row['json'])==e and row['type']==e['type'] and millis(row['time'])==millis(e['occurredAt']),'Production event differs from completion')
            check(inbox['sequence']==receipt['sequence'] and inbox['status']=='ACKED' and inbox['event_id']==e['id'] and json.loads(inbox['json'])==e,'Production ingress changed or unacknowledged')
            payload=dict(id=e['id'],type=e['type'],time=e['occurredAt'],players=e['participants'],nations=e['nations'],facts=e['facts'],source=e['source'],evidence=e['evidence']);raw=json.dumps(payload,ensure_ascii=False,separators=(',',':'),sort_keys=True)
            check(row['hash']==hashed(str(len(raw.encode()))+':'+raw),'Canonical production event hash differs')
            check([r[0] for r in db.execute('select player from event_players where event_id=?',(e['id'],))]==e['participants'] and [r[0] for r in db.execute('select nation from event_nations where event_id=?',(e['id'],))]==e['nations'],'Production participant projection differs')
            run=receipt['run'];operation=f'nation-production:{domain}:{run["runId"]}:industrial'
            proof=f'Native national production; domain={domain}; sequence={receipt["sequence"]}; run={run["runId"]}; resource_domain={run["resourceDomain"]}; original_actor={run["actorId"]}; national and player subjects are views of the same output, not additional production or rewards'
            for subject in ('nation:'+run['nationId'],'player:'+run['actorId']):
                identity='chronicle:contribution:'+hashed(operation+'|CIVILIZATION|'+subject)
                matches=[c for c in contributions if c['id']==identity];check(len(matches)==1,'Production contribution view missing');c=matches[0]
                request=dict(operationId=operation,type='CIVILIZATION',subjectId=subject,sourceEventId=e['id'],actorId=run['actorId'],nationId=run['nationId'],amount=run['outputs']['industrial'],unit='production.resource:industrial',committedAt=e['occurredAt'],evidence=proof)
                check(json.loads(c['request_json'])==request,'Production contribution request changed')
                cols=dict(operation_id=operation,type='CIVILIZATION',subject_id=subject,source_event_id=e['id'],actor_id=run['actorId'],nation_id=run['nationId'],unit=request['unit'],committed_at=e['occurredAt'],evidence=proof)
                check(all(c[k]==v for k,v in cols.items()) and Decimal(c['amount'])==request['amount'] and instant(c['recorded_at'])>=instant(e['occurredAt']),'Production contribution columns differ')
                result.append(dict(id=identity,request=request,recordedAt=c['recorded_at']))
        return expected,sorted(result,key=lambda c:c['id'])

def commands(boot,offline,runs):
    rows=[]
    def c(who,cmd,response):rows.append((who,cmd,response))
    def start(key):
        who,batches=TERMS[key];c(who,f'/nationproduction start industrial_refining {batches}','生产已开工')
    def complete(key,who='A',response='生产批次已处理'):c(who,'/nationproduction complete '+runs[key]['runId'],response)
    if boot==1:
        c('A','/nationproduction recipes','工业精炼');c('D','/nationproduction start industrial_refining 1','请求被拒绝')
        start('primary');complete('primary',response='尚未到领取时间')
        for who in 'DC':complete('primary',who,'请求被拒绝')
        complete('primary');complete('primary',response='没有重复扣除或发放');start('pending')
    elif offline:
        complete('primary',response='没有重复扣除或发放');c('A','/chronicle contributions','生产资源 industrial')
    elif boot==2:
        complete('pending');start('cancelled');c('A','/nationproduction cancel '+runs['cancelled']['runId'],'生产批次已处理')
        complete('cancelled',response='请求被拒绝');start('old-founder');complete('old-founder',response='请求被拒绝');start('successor')
    else:
        complete('cancelled','D','请求被拒绝');complete('primary','A','没有重复扣除或发放');complete('old-founder','D');complete('successor','D');complete('successor','D','没有重复扣除或发放')
        for who in 'AD':c(who,'/chronicle contributions','生产资源 industrial')
        c('D','/chronicle contributions nation me','生产资源 industrial')
    return rows

def inspect(run):
    run=Path(run);manifest=read(run/'preparation.json');config=manifest['nation_production_acceptance'];offline=config['mode']=='offline'
    scenarios=config['scenarios'];boots=2 if offline else 3
    check(scenarios==(['production-offline-first','production-offline-second'] if offline else ['production-first','production-second','production-third']),'Unexpected production scenarios')
    with socket.socket() as probe:probe.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1);probe.bind(('127.0.0.1',manifest['server_port']))
    for name,key in [('StarChronicle','plugin_sha256'),('StarCore','starcore_sha256'),('EpicCampaignPaperFixture','fixture_sha256')]:check(sha(run/f'plugins/{name}.jar')==manifest[key],'Frozen production artifact changed')
    for name,digest in {'EpicCampaignPaperFixture.java':manifest['fixture_source_sha256'],**manifest['fixture_extra_sources_sha256']}.items():check(sha(run/'fixture'/name)==digest,'Frozen fixture source changed')
    paper={v for k,v in manifest['source_artifacts'].items() if Path(k).name=='paper.jar'};check(len(paper)==1 and sha(run/'paper.jar') in paper,'Frozen Paper changed')
    for artifact in manifest['production_stack']['artifacts']:check(sha(run/'plugins'/artifact['filename'])==artifact['sha256'],'Frozen dependency changed')
    evidence=run/'evidence';fixture=records(evidence/'epic-fixture.jsonl')
    check(not any(r['kind']=='FAILED' for r in fixture),'Retained production fixture failure')
    check({r['boot'] for r in fixture}==set(range(1,boots+1)) and len(list(evidence.glob('boot*.session.json')))==boots,'Missing complete production boots')
    seed=next(r['data'] for r in fixture if r['kind']=='SEEDED');players=manifest['players']
    captures=[r['data'] for r in fixture if r['kind']=='PRODUCTION_RUN'];keys=['primary','pending'] if offline else list(TERMS)
    check([r['key'] for r in captures]==keys,'Missing actual client-created production runs')
    captured={r['key']:r['run'] for r in captures}
    completed=['primary'] if offline else ['primary','pending','old-founder','successor']
    final=native(run/'plugins/StarCore/starcore.db',seed,players,captured,keys,completed,'A' if offline else 'D')
    events,contributions=chronicle(run/'plugins/StarChronicle/chronicle.db',final,len(completed))
    previous=None;command_count=0;telemetry=[];stack=None
    for boot,scenario in enumerate(scenarios,1):
        session=read(evidence/f'boot{boot}.session.json');check(session['boot']==boot and session['scenario']==scenario and session['status']=='COMPLETE','Incomplete production session')
        processes=session['processes'];check(len(processes)==2 and {p['name'] for p in processes}=={'server','clients'} and all(p.get('exit_code')==0 for p in processes),'Abnormal production process exit')
        server=next(p for p in processes if p['name']=='server');client=next(p for p in processes if p['name']=='clients')
        check(server['started_at']<client['started_at']<client['exited_at']<server['exited_at'],'Production process chronology differs')
        if previous:check(previous['exited_at']<server['started_at'],'Overlapping production boots')
        previous=server
        for name,suffix in [('controller','py'),('clients','cjs')]:check(sha(evidence/f'boot{boot}.{name}.{suffix}')==session['source_sha256'][name],'Captured runtime script changed')
        observed=[r for r in fixture if r['boot']==boot]
        for kind in ('READY','SEEDED','CLOSED','STACK_LOADED','PRODUCTION_BOUND'):check(sum(r['kind']==kind for r in observed)==1,'Missing boot lifecycle: '+kind)
        one=lambda kind:next(r['data'] for r in observed if r['kind']==kind)
        check(one('READY')==dict(product=manifest['plugin_sha256'],native=manifest['starcore_sha256'],route=manifest['route']) and one('SEEDED')==seed and seed['nonOp'] is True and one('CLOSED')==dict(normalDisable=True),'Live artifact/identity mismatch')
        current=one('STACK_LOADED');check(set(current['plugins'])=={'StarCore','StarChronicle','FancyNpcs','WorldGuard','WorldEdit','Essentials','Vault'} and all(current['plugins'].values()) and current['businessEconomy']=='dev.starcore.starcore.foundation.economy.PlayerVaultEconomyService','Production stack missing')
        if stack:check(current==stack,'Production providers changed')
        stack=current
        check(one('PRODUCTION_BOUND')==dict(resourceDomain=final['head']['resource_domain'],domain='' if boot==1 else final['head']['authority_domain'],nonOp=True,actualService='dev.starcore.starcore.module.resource.SimpleNationProductionService'),'Production service binding differs')
        log=(evidence/f'boot{boot}.server.log').read_text()
        for line in re.findall(r'^.*(?:ERROR\]|ERROR\]:|Failed to (?:enable|disable) STARCORE module|同步保存失败|异步保存失败|SQLITE_BUSY|startup failed|Could not pass event|Committed history source .* retained|History scan retained pending evidence).*$',log,re.M|re.I):
            check(T.EPIC.vendor_telemetry(line),'Server persistence/lifecycle diagnostic: '+line);telemetry.append(dict(boot=boot,message=line))
        check(all(v in log for v in ('Done (','Stopping server','All dimensions are saved')),'Incomplete normal lifecycle')
        for kind in {r['kind'] for r in observed}:
            emitted=[json.loads(line.split('EPIC_'+kind+' ',1)[1]) for line in log.splitlines() if 'EPIC_'+kind+' ' in line]
            check(emitted==[r['data'] for r in observed if r['kind']==kind],'Fixture/server stream differs')
        check(all(server['started_at']<=instant(r['time'])<=server['exited_at'] for r in observed),'Fixture outside server lifetime')
        clients=records(evidence/f'boot{boot}.clients.log');check(not any(r['kind']=='FAILED' for r in clients),'Client failure')
        spawns=[r['data'] for r in clients if r['kind']=='SPAWN'];check(len(spawns)==4 and {r['who'] for r in spawns}==set('ABCD') and all(r['uuid']==players[r['who']] for r in spawns),'Client identity differs')
        actual=[r for r in clients if r['kind']=='COMMAND_RESULT'];check([(r['data']['who'],r['data']['command'],r['data']['contains']) for r in actual]==commands(boot,offline,captured),'Actual production command sequence differs')
        for row in actual:
            d=row['data'];check(d['contains'] in d['response'] and client['started_at']<=instant(row['time'])<=client['exited_at'],'Actual production response/lifetime differs')
            check(any(r['kind']=='MESSAGE' and r['data']['who']==d['who'] and r['data']['message'] in d['response'] for r in clients),'Production command lacks protocol message')
        command_count+=len(actual)
        for receipt in final['receipts']:
            for row in actual:
                if row['data']['command']=='/nationproduction complete '+receipt['run']['runId'] and row['data']['contains']=='生产批次已处理':
                    check(server['started_at']<=instant(receipt['committedAt'])<=instant(row['time']),'Production receipt outside actual collection')
        checks=[r['data'] for r in observed if r['kind']=='PRODUCTION_CHECK']
        wanted=({1:[],2:[1,1]} if offline else {1:[1],2:[1,2,2],3:[2,3,4]})[boot]
        check([r['expected'] for r in checks]==wanted,'Missing live production checkpoint')
        for row in checks:
            n=row['expected'];check(row['native']==final['receipts'][:n] and row['events']==events[:n],'Live production receipt/history differs')
            check(sorted(row['contributions'],key=lambda c:c['id'])==[c for c in contributions if c['request']['sourceEventId'] in {e['id'] for e in events[:n]}],'Live production contribution differs')
        cp=evidence/f'checkpoints/boot{boot}';inventory=read(cp/'checkpoint.json')
        check(inventory['boot']==boot and set(inventory['sha256'])=={'chronicle.db','starcore.db'} and server['exited_at']<=inventory['time'],'Stopped production checkpoint boundary differs')
        check({p.name for p in cp.iterdir() if p.is_file()}=={'checkpoint.json','chronicle.db','starcore.db'},'Unexpected production checkpoint file')
        for name,digest in inventory['sha256'].items():
            check(sha(cp/name)==digest,'Stopped production checkpoint changed')
            if boot==boots:check(sha(run/'plugins'/('StarCore' if name=='starcore.db' else 'StarChronicle')/name)==digest,'Final production runtime differs')
        saved_keys=keys if offline or boot>1 else ['primary','pending'];saved_complete=completed if offline or boot==3 else completed[:boot]
        state=native(cp/'starcore.db',seed,players,captured,saved_keys,saved_complete,'A' if offline or boot==1 else 'D')
        ev,cs=chronicle(cp/'chronicle.db',state,0 if offline and boot==1 else len(saved_complete))
        check(state['entries']==final['entries'][:len(state['entries'])],'Native production prefix changed across restart')
        check(ev==events[:len(ev)] and cs==[c for c in contributions if c['request']['sourceEventId'] in {e['id'] for e in ev}],'Production projections changed across restart')
        if offline and boot==2:
            row=next(r for r in observed if r['kind']=='PRODUCTION_OFFLINE_CONSUMED');check(row['data']==dict(onlinePlayers=0,completions=1,contributions=2) and instant(row['time'])<client['started_at'],'Backlog consumed after client launch')
    if offline:
        buffered=[r for r in fixture if r['kind']=='PRODUCTION_OFFLINE_BUFFERED'];check(len(buffered)==1 and buffered[0]['boot']==1 and buffered[0]['data']==dict(completions=1,events=0,contributions=0),'Missing unconsumed production backlog')
        resume=read(evidence/'production-poll-resume.json');check(hashed(resume['before'])==resume['before_sha256'] and hashed(resume['after'])==resume['after_sha256'] and resume['before'].replace('poll-ticks: 72000\n','poll-ticks: 20\n')==resume['after'],'Unexpected offline polling change')
        check((run/'plugins/StarChronicle/config.yml').read_text()==resume['after'],'Offline production config differs')
    else:
        succession=[r for r in fixture if r['kind']=='PRODUCTION_SUCCESSOR'];check(len(succession)==1 and succession[0]['boot']==2 and succession[0]['data']==dict(nation=seed['A'],previous=players['A'],current=players['D'],publicNativeOperation=True),'Missing native production succession')
    return dict(status='SELECTED_PAPER_NATIONAL_PRODUCTION_ACCEPTED',mode=config['mode'],chronicle_sha256=manifest['plugin_sha256'],starcore_sha256=manifest['starcore_sha256'],
        boots=boots,normal_process_exits=2*boots,actual_command_results=command_count,completions=len(completed),contributions=2*len(completed),industrial_output=final['delivered'],authority_domain=final['head']['authority_domain'],plugins=stack['plugins'],vendor_telemetry=telemetry,limitations=manifest['limitations'])

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('run',type=Path)
    print(json.dumps(inspect(p.parse_args().run),ensure_ascii=False,indent=2))
