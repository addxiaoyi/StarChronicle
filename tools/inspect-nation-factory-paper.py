"""Independent stopped factory SQL/payment/provenance and actual player command audit."""
from contextlib import closing
from pathlib import Path
from decimal import Decimal
import argparse,importlib.util,json,re,socket,uuid
SPEC=importlib.util.spec_from_file_location('production',Path(__file__).with_name('inspect-nation-production-paper.py'))
P=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(P)
check,read,sha,records,instant,compact,hashed=P.check,P.read,P.sha,P.records,P.instant,P.compact,P.hashed
SCENARIOS=['factory-first','factory-second','factory-third']
KEYS=['factory_old','factory_cancel','factory_advanced','factory_successor']
TERMS={key:dict(who='D' if key=='factory_successor' else 'A',batches=b,recipeId='advanced_refining' if key=='factory_advanced' else 'factory_refining',
 inputs=dict(energy=b,mineral=3*b),outputs=dict(industrial=2*b),seconds=30*b) for key,b in zip(KEYS,[1,2,2,1])}

def factories(path,seed,players,operations,expected_count):
    with closing(P.connect(path)) as db:
        heads=P.rows(db,'select * from starcore_nation_factory_head');check(len(heads)==1 and heads[0]['singleton']==1,'Missing factory head')
        h=heads[0];domain=h['authority_domain'];resource=h['resource_domain']
        check(str(uuid.UUID(domain))==domain and str(uuid.UUID(resource))==resource,'Noncanonical factory domain')
        props=dict(db.execute('select property_key,property_value from starcore_resource_state'))
        check(props['resource.authority-domain']==resource,'Factory resource authority differs')
        check(db.execute("select count(*) from starcore_resource_state_migrations where migration_key='nation-factory-journal-v1'").fetchone()[0]==1,'Missing factory marker')
        rows=P.rows(db,'select * from starcore_nation_factory_events order by event_sequence')
        check(len(rows)==h['event_sequence']==expected_count,'Factory committed prefix incomplete')
        names=['build','upgrade','off','on','successor_upgrade','successor_off'][:expected_count]
        levels=[1,2,2,2,3,3];enabled=[True,True,False,True,True,False]
        prefix='0'*64;history=[];total={};built=None;last=0
        for i,(name,row) in enumerate(zip(names,rows),1):
            c=json.loads(row['change_json']);b=c['building'];built=built or b['builtAt'];action='BUILD' if i==1 else 'UPGRADE' if i in (2,5) else 'SET_OPERATIONAL'
            cost=dict(energy=4*levels[i-1]**2,industrial=5*levels[i-1]**2,mineral=20*levels[i-1]**2) if action!='SET_OPERATIONAL' else {}
            expected=dict(factoryId=operations['build'],nationId=seed['A'],resourceDomain=resource,builderId=players['A'],type='SMELTER',name='Forge',level=levels[i-1],operational=enabled[i-1],version=i,builtAt=built)
            full=dict(format=1,operationId=operations[name],actorId=players['D' if i>=5 else 'A'],action=action,building=expected,cost=cost,committedAt=c['committedAt'])
            check(c==full and row['change_json']==compact(full),'Frozen factory identity/cost/transition differs')
            check(row['event_sequence']==i and row['operation_id']==operations[name] and row['factory_id']==operations['build'],'Factory sequence/operation identity differs')
            check(instant(c['committedAt'])>=last and instant(c['committedAt'])>=instant(built),'Factory time moved backwards');last=instant(c['committedAt'])
            if i==1:check(c['committedAt']==built,'Factory construction time differs')
            prefix=hashed(f'{domain}\n{resource}\n{i}\n{prefix}\n{row["change_json"]}');check(prefix==row['entry_hash'],'Factory event digest mismatch')
            for key,amount in cost.items():total[key]=total.get(key,0)+amount
            history.append(dict(sequence=i,**{key:value for key,value in c.items() if key!='format'}))
        check(prefix==h['prefix_hash'],'Factory head digest mismatch')
        revision=P.rows(db,'select * from starcore_resource_revision');check(len(revision)==1 and revision[0]['revision']>=expected_count,'Factory inventory revision missing')
        return dict(head=h,rows=rows,history=history,cost=total)

def native(path,seed,players,captured,operations,boot):
    f=factories(path,seed,players,operations,3 if boot==1 else 6)
    bound={}
    for key,version,level,minimum in zip(KEYS,[1,2,4,5],[1,2,2,3],[1,1,2,1]):
        bound[key]=dict(authorityDomain=f['head']['authority_domain'],factoryId=operations['build'],version=version,type='SMELTER',level=level,minimumLevel=minimum)
    keys=KEYS[:2] if boot==1 else KEYS;completed={1:[],2:[KEYS[0]],3:[KEYS[0],KEYS[2],KEYS[3]]}[boot]
    state=P.native(path,seed,players,captured,keys,completed,'A' if boot==1 else 'D',terms=TERMS,cancelled=() if boot==1 else (KEYS[1],),
        factory_bindings=bound,initial=dict(mineral=1000,energy=1000,industrial=1000),infrastructure_cost=f['cost'])
    check(f['head']['resource_domain']==state['head']['resource_domain'],'Factory/production authority differs')
    for row in state['entries']:
        r=json.loads(row['run_json']);admission=f['history'][r['factory']['version']-1]
        check(admission['building']['operational'] and instant(admission['committedAt'])<=instant(r['startedAt']),'Production predates valid factory admission')
    return f,state

def commands(boot,runs,ops):
    result=[];factory=ops['build']
    def c(text,response,who='A'):result.append((who,text,response))
    def start(key):
        t=TERMS[key];c(f'/nationproduction startat {factory} {t["recipeId"]} {t["batches"]}','生产已开工',t['who'])
    def collect(key,who='A',response='生产批次已处理'):c('/nationproduction complete '+runs[key]['runId'],response,who)
    if boot==1:
        c('/nationfactory plans SMELTER 2','容量 2 个生产、50 批');c('/nationproduction start factory_refining 1','此配方需要工厂');c('/nationfactory build SMELTER Intruder','请求被拒绝','D')
        c(f'/nationfactory build SMELTER Forge {factory}','工厂请求已提交');c(f'/nationfactory build SMELTER Forge {factory}','没有重复扣除材料');c(f'/nationfactory build REFINERY Forge {factory}','请求被拒绝')
        c('/nationfactory show '+factory,'没有可查看的这个工厂','B');c(f'/nationproduction startat {factory} factory_refining 1','工厂不可用','B')
        c(f'/nationproduction startat {factory} advanced_refining 1','工厂不可用');c(f'/nationproduction startat {factory} factory_refining 26','工厂容量已满')
        start(KEYS[0]);collect(KEYS[0],response='尚未到领取时间');c(f'/nationproduction startat {factory} factory_refining 1','工厂容量已满')
        c(f'/nationfactory upgrade {factory} 1 {ops["upgrade"]}','工厂请求已提交');c(f'/nationfactory upgrade {factory} 1 {ops["upgrade"]}','没有重复扣除材料');c(f'/nationfactory upgrade {factory} 1','工厂版本已变化')
        start(KEYS[1]);c(f'/nationproduction startat {factory} factory_refining 1','工厂容量已满');c(f'/nationfactory disable {factory} 2 {ops["off"]}','工厂请求已提交');c(f'/nationproduction startat {factory} factory_refining 1','工厂不可用')
    elif boot==2:
        collect(KEYS[0]);collect(KEYS[0],response='没有重复扣除或发放')
        c('/nationproduction cancel '+runs[KEYS[1]]['runId'],'生产批次已处理');c('/nationproduction cancel '+runs[KEYS[1]]['runId'],'没有重复扣除或发放')
        c(f'/nationfactory enable {factory} 3 {ops["on"]}','工厂请求已提交');start(KEYS[2])
        c(f'/nationfactory upgrade {factory} 4','请求被拒绝');c('/nationfactory show '+factory,'没有可查看的这个工厂');c('/nationfactory operation '+factory,'原操作者')
        c(f'/nationfactory upgrade {factory} 4 {ops["successor_upgrade"]}','工厂请求已提交','D');start(KEYS[3])
        c(f'/nationfactory disable {factory} 5 {ops["successor_off"]}','工厂请求已提交','D');c(f'/nationproduction startat {factory} factory_refining 1','工厂不可用','D')
    else:
        c(f'/nationfactory upgrade {factory} 4 {ops["successor_upgrade"]}','没有重复扣除材料','D');c(f'/nationfactory disable {factory} 5 {ops["successor_off"]}','没有重复扣除材料','D')
        collect(KEYS[2],response='请求被拒绝')
        for key in KEYS[2:]:collect(key,'D');collect(key,'D','没有重复扣除或发放')
        collect(KEYS[2],response='没有重复扣除或发放');c('/nationproduction cancel '+runs[KEYS[1]]['runId'],'没有重复扣除或发放','D')
        c('/chronicle contributions','生产资源 industrial');c('/chronicle contributions','生产资源 industrial','D');c('/chronicle contributions nation me','生产资源 industrial','D')
    return result

def inspect(run):
    run=Path(run);m=read(run/'preparation.json');spec=m['nation_factory_acceptance'];ops=spec['operations']
    check(spec['scenarios']==SCENARIOS and (spec['expected_changes'],spec['expected_completions'],spec['expected_contributions'])==(6,3,6),'Unexpected factory scenario')
    check(set(ops)=={'build','upgrade','off','on','successor_upgrade','successor_off'} and len(set(ops.values()))==6,'Factory operation identities differ')
    with socket.socket() as probe:probe.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1);probe.bind(('127.0.0.1',m['server_port']))
    for name,key in [('StarChronicle','plugin_sha256'),('StarCore','starcore_sha256'),('EpicCampaignPaperFixture','fixture_sha256')]:check(sha(run/f'plugins/{name}.jar')==m[key],'Frozen factory artifact changed')
    for name,digest in {'EpicCampaignPaperFixture.java':m['fixture_source_sha256'],**m['fixture_extra_sources_sha256']}.items():check(sha(run/'fixture'/name)==digest,'Frozen factory fixture changed')
    paper={v for k,v in m['source_artifacts'].items() if Path(k).name=='paper.jar'};check(len(paper)==1 and sha(run/'paper.jar') in paper,'Frozen Paper changed')
    for a in m['production_stack']['artifacts']:check(sha(run/'plugins'/a['filename'])==a['sha256'],'Frozen third-party plugin changed')
    check(sha(run/'fixture/nation-factory-catalog.json')==sha(run/'plugins/StarCore/nation-production.json')==spec['catalog_sha256'],'Factory production catalog changed')
    config=read(run/'fixture/nation-factory-catalog.json');check(config['format']==2 and [(r['id'],r['factory']) for r in config['recipes']]==[('factory_refining',dict(type='SMELTER',**{'minimum-level':1})),('advanced_refining',dict(type='SMELTER',**{'minimum-level':2}))],'Factory recipe gate differs')
    e=run/'evidence';fixture=records(e/'epic-fixture.jsonl');check(not any(r['kind']=='FAILED' for r in fixture),'Retained factory failure')
    check({r['boot'] for r in fixture}=={1,2,3} and len(list(e.glob('boot*.session.json')))==3,'Missing factory boot')
    seed=next(r['data'] for r in fixture if r['kind']=='SEEDED');players=m['players']
    captures=[r['data'] for r in fixture if r['kind']=='PRODUCTION_RUN'];check([r['key'] for r in captures]==KEYS,'Missing factory production runs')
    runs={r['key']:r['run'] for r in captures};f,final=native(run/'plugins/StarCore/starcore.db',seed,players,runs,ops,3)
    events,views=P.chronicle(run/'plugins/StarChronicle/chronicle.db',final,3)
    last=None;count=0;stack=None;telemetry=[]
    for boot,scenario in enumerate(SCENARIOS,1):
        session=read(e/f'boot{boot}.session.json');check(session['boot']==boot and session['scenario']==scenario and session['status']=='COMPLETE','Incomplete factory session')
        processes=session['processes'];check(len(processes)==2 and {p['name'] for p in processes}=={'server','clients'} and all(p.get('exit_code')==0 for p in processes),'Abnormal factory process exit')
        server=next(p for p in processes if p['name']=='server');client=next(p for p in processes if p['name']=='clients')
        check(server['started_at']<client['started_at']<client['exited_at']<server['exited_at'],'Factory process chronology differs')
        if last:check(last['exited_at']<server['started_at'],'Overlapping factory boots')
        last=server
        for name,ext in [('controller','py'),('clients','cjs')]:check(sha(e/f'boot{boot}.{name}.{ext}')==session['source_sha256'][name],'Captured factory runtime changed')
        observed=[r for r in fixture if r['boot']==boot]
        for kind in ('READY','SEEDED','CLOSED','STACK_LOADED','PRODUCTION_BOUND','FACTORY_BOUND'):check(sum(r['kind']==kind for r in observed)==1,'Factory lifecycle missing: '+kind)
        one=lambda kind:next(r['data'] for r in observed if r['kind']==kind)
        check(one('READY')==dict(product=m['plugin_sha256'],native=m['starcore_sha256'],route=m['route']) and one('SEEDED')==seed and seed['nonOp'] is True and one('CLOSED')==dict(normalDisable=True),'Factory live identity differs')
        current=one('STACK_LOADED');check(set(current['plugins'])=={'StarCore','StarChronicle','FancyNpcs','WorldGuard','WorldEdit','Essentials','Vault'} and all(current['plugins'].values()) and current['businessEconomy']=='dev.starcore.starcore.foundation.economy.PlayerVaultEconomyService','Factory stack missing')
        if stack:check(current==stack,'Factory providers changed')
        stack=current
        check(one('FACTORY_BOUND')==dict(authority='' if boot==1 else f['head']['authority_domain'],resourceDomain=f['head']['resource_domain'],nonOp=True),'Factory authority binding differs')
        log=(e/f'boot{boot}.server.log').read_text()
        for line in re.findall(r'^.*(?:ERROR\]|ERROR\]:|SEVERE|Failed to (?:enable|disable) STARCORE module|同步保存失败|异步保存失败|SQLITE_BUSY|startup failed|Could not pass event|Committed history source .* retained|History scan retained pending evidence).*$',log,re.M|re.I):
            check(P.T.EPIC.vendor_telemetry(line),'Factory persistence/lifecycle diagnostic: '+line);telemetry.append(dict(boot=boot,message=line))
        check(all(v in log for v in ('Done (','Stopping server','All dimensions are saved')),'Incomplete normal factory lifecycle')
        for kind in {r['kind'] for r in observed}:
            emitted=[json.loads(line.split('EPIC_'+kind+' ',1)[1]) for line in log.splitlines() if 'EPIC_'+kind+' ' in line]
            check(emitted==[r['data'] for r in observed if r['kind']==kind],'Factory fixture/server stream differs')
        check(all(server['started_at']<=instant(r['time'])<=server['exited_at'] for r in observed),'Factory observation outside lifetime')
        clients=records(e/f'boot{boot}.clients.log');check(not any(r['kind']=='FAILED' for r in clients),'Factory client failure')
        spawns=[r['data'] for r in clients if r['kind']=='SPAWN'];check(len(spawns)==4 and {r['who'] for r in spawns}==set('ABCD') and all(r['uuid']==players[r['who']] for r in spawns),'Factory player identity differs')
        actual=[r for r in clients if r['kind']=='COMMAND_RESULT'];check([(r['data']['who'],r['data']['command'],r['data']['contains']) for r in actual]==commands(boot,runs,ops),'Actual factory command sequence differs');count+=len(actual)
        for row in actual:
            d=row['data'];check(d['contains'] in d['response'] and client['started_at']<=instant(row['time'])<=client['exited_at'],'Factory command response/lifetime differs')
            check(any(r['kind']=='MESSAGE' and r['data']['who']==d['who'] and r['data']['message'] in d['response'] for r in clients),'Factory command lacks protocol response')
            if d['contains']=='工厂请求已提交':
                operation=d['command'].split()[-1];receipt=next(v for v in f['history'] if v['operationId']==operation)
                check(operation in d['response'] and receipt['actorId']==players[d['who']] and client['started_at']<=instant(receipt['committedAt'])<=instant(row['time']),'Factory receipt lacks actual request identity/time')
            if d['contains']=='生产已开工':
                paid=next(v for v in runs.values() if v['runId'] in d['response']);check(paid['actorId']==players[d['who']] and client['started_at']<=instant(paid['startedAt'])<=instant(row['time']),'Factory production start lacks actual identity/time')
            if d['contains']=='生产批次已处理':
                terminal=[v for v in final['entries'] if v['run_id']==d['command'].split()[-1] and v['state']==('CANCELLED' if ' cancel ' in d['command'] else 'COMPLETED')]
                check(len(terminal)==1 and client['started_at']<=terminal[0]['changed_at']/1000<=instant(row['time']),'Factory production settlement outside command')
        history=one('FACTORY_HISTORY');check(history==dict(domain=f['head']['authority_domain'],records=f['history'][:3 if boot==1 else 6]),'Live factory history differs')
        checkpoints=[r['data'] for r in observed if r['kind']=='FACTORY_CHECK'];wanted={1:[(2,3,False,2,3)],2:[(2,3,False,2,3),(3,6,False,2,3)],3:[(3,6,False,2,3),(3,6,False,0,0)]}[boot]
        check([(v['building']['level'],v['building']['version'],v['building']['operational'],v['runningRuns'],v['runningBatches']) for v in checkpoints]==wanted,'Live factory capacity differs')
        for v in checkpoints:check(v['building']==f['history'][v['building']['version']-1]['building'],'Live factory building changed')
        checks=[r['data'] for r in observed if r['kind']=='PRODUCTION_CHECK'];n={1:0,2:1,3:3}[boot];check(len(checks)==1 and checks[0]['expected']==n,'Missing factory production checkpoint')
        live=checks[0];plain=[dict(r,run={k:v for k,v in r['run'].items() if k!='factory'}) for r in final['receipts'][:n]]
        check(live['native']==plain and live['events']==events[:n],'Live factory production history differs')
        check(sorted(live['contributions'],key=lambda c:c['id'])==[v for v in views if v['request']['sourceEventId'] in {x['id'] for x in events[:n]}],'Live factory contribution differs')
        if boot>1:
            provenance=one('FACTORY_PROVENANCE');check(provenance['count']==n and provenance['runs']==[dict(runId=r['run']['runId'],actorId=r['run']['actorId'],binding=r['run']['factory'],event=ev) for r,ev in zip(final['receipts'][:n],events[:n])],'Live factory provenance differs')
        cp=e/f'checkpoints/boot{boot}';inventory=read(cp/'checkpoint.json');check(inventory['boot']==boot and set(inventory['sha256'])=={'chronicle.db','starcore.db'} and server['exited_at']<=inventory['time'],'Factory stopped checkpoint boundary differs')
        for name,digest in inventory['sha256'].items():
            check(sha(cp/name)==digest,'Stopped factory checkpoint changed')
            if boot==3:check(sha(run/'plugins'/('StarCore' if name=='starcore.db' else 'StarChronicle')/name)==digest,'Final factory database differs')
        state_f,state=native(cp/'starcore.db',seed,players,runs,ops,boot);ev,cs=P.chronicle(cp/'chronicle.db',state,n,bound_empty=boot==1)
        check(state_f['rows']==f['rows'][:len(state_f['rows'])] and state['entries']==final['entries'][:len(state['entries'])],'Factory/production prefix changed across restart')
        check(ev==events[:len(ev)] and cs==[v for v in views if v['request']['sourceEventId'] in {x['id'] for x in ev}],'Factory projections changed across restart')
        stocks={resource:amount for (nation,resource),amount in state['stocks'].items() if nation==seed['A']}
        check(checkpoints[-1]['stockpile']==stocks and live['stockpile']==stocks,'Live factory stockpile differs')
    succession=[r for r in fixture if r['kind']=='PRODUCTION_SUCCESSOR'];check(len(succession)==1 and succession[0]['boot']==2 and succession[0]['data']==dict(nation=seed['A'],previous=players['A'],current=players['D'],publicNativeOperation=True),'Missing factory succession')
    return dict(status='SELECTED_PAPER_NATION_FACTORIES_ACCEPTED',boots=3,normal_process_exits=6,actual_command_results=count,factory_changes=6,completions=3,cancellations=1,contributions=6,
        construction_and_upgrade_cost=f['cost'],production_inputs=dict(mineral=12,energy=4),industrial_output=8,chronicle_sha256=m['plugin_sha256'],starcore_sha256=m['starcore_sha256'],plugins=stack['plugins'],vendor_telemetry=telemetry,limitations=m['limitations'])

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('run',type=Path);print(json.dumps(inspect(p.parse_args().run),ensure_ascii=False,indent=2))
