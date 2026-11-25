"""Independently verify raw legacy evidence, new material payment, actual commands and four stopped boots."""
from contextlib import closing
from pathlib import Path
import argparse, importlib.util, json, re, socket, uuid
from legacy_factory_raw import fingerprint
SPEC=importlib.util.spec_from_file_location('production',Path(__file__).with_name('inspect-nation-production-paper.py'))
P=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(P)
check,read,sha,records,instant,compact,hashed=P.check,P.read,P.sha,P.records,P.instant,P.compact,P.hashed
SCENARIOS=['legacy-seed','legacy-first','legacy-second','legacy-third']
KEYS=['legacy_old','legacy_cancel','legacy_successor']
TERMS={key:dict(who='D' if key=='legacy_successor' else 'A',batches=b,recipeId='legacy_refining',inputs=dict(energy=b,mineral=3*b),outputs=dict(industrial=2*b),seconds=30*b) for key,b in zip(KEYS,[1,2,1])}

def tables(path):
    with closing(P.connect(path)) as db:
        names=[r[0] for r in db.execute("select name from sqlite_master where type='table' and name not like 'sqlite_%' order by name")]
        return {name:sorted([tuple(row) for row in db.execute('select * from "'+name+'"')],key=repr) for name in names}

def staged(run,m,seed):
    e=run/'evidence';s=read(e/'legacy-staging.json');raw=s['raw'];spec=m['legacy_factory_acceptance']
    check(s['kind']=='EXPLICIT_STOPPED_FIXTURE_SEED' and s['payment_evidence']=='UNPROVEN' and s['original_builder']=='UNKNOWN','Legacy staging must be explicit and unproven')
    check(s['before_sha256']==sha(e/'checkpoints/boot1/starcore.db') and s['after_sha256']==sha(e/'legacy-source-starcore.db'),'Legacy source snapshot changed')
    check(s['revision_after']==s['revision_before']+1 and s['nation']==seed['A'],'Legacy staging revision/owner differs')
    check(s['fingerprint']==fingerprint(s['resource_domain'],raw),'Legacy raw snapshot digest differs')
    for name,digest in s['source_sha256'].items():check(sha(e/name)==digest,'Legacy staging script changed')
    expected={'factory.count':'7'}
    for i,key in enumerate(('valid','processing','malformed','duplicate','duplicate','unknown','foreign')):
        owner=raw.get(f'factory.{i}.nationId') if key=='foreign' else seed['A']
        if key=='foreign':check(str(uuid.UUID(owner))==owner and owner not in (seed['A'],seed['B'],seed['C']),'Foreign legacy owner was relabeled')
        fields=dict(id=spec['factories'][key],nationId=owner,name='LegacyForge' if key=='valid' else 'Quarantined'+str(i),type='SMELTER',buildTime='1700000000000',level='3',efficiency='1.1',operational='true')
        if key=='processing':fields.update(recipe='old_recipe',processingStart='1700000000123',processingBatches='37')
        if key=='malformed':fields['level']='99'
        if key=='unknown':fields['unrecognized']='retain this original evidence'
        expected.update({f'factory.{i}.{k}':v for k,v in fields.items()})
    check(raw==expected,'Legacy source fields changed')
    before=tables(e/'checkpoints/boot1/starcore.db');after=tables(e/'legacy-source-starcore.db')
    check(set(before)==set(after),'Legacy staging changed schema')
    for table in before:
        if table not in ('starcore_resource_state','starcore_resource_revision'):check(before[table]==after[table],'Legacy staging altered unrelated table: '+table)
    props=dict(before['starcore_resource_state']);check(not any(k.startswith('factory.') for k in props),'Boot 1 already had legacy assets')
    check(dict(after['starcore_resource_state'])==dict(props,**raw),'Legacy staging changed nonfactory resources')
    check(before['starcore_resource_revision']==[(1,s['revision_before'])] and after['starcore_resource_revision']==[(1,s['revision_after'])],'Legacy staging revision was not exact')
    return s

def factories(path,seed,players,spec,source,count):
    with closing(P.connect(path)) as db:
        heads=P.rows(db,'select * from starcore_nation_factory_head');check(len(heads)==1 and heads[0]['singleton']==1,'Missing legacy factory head')
        h=heads[0];domain=h['authority_domain'];resource=h['resource_domain']
        check(str(uuid.UUID(domain))==domain and resource==source['resource_domain'],'Legacy factory authority differs')
        props=dict(db.execute('select property_key,property_value from starcore_resource_state'))
        check(props['resource.authority-domain']==resource and {k:v for k,v in props.items() if k.startswith('factory.')}==source['raw'],'Legacy original properties changed')
        check(db.execute("select count(*) from starcore_resource_state_migrations where migration_key='nation-factory-journal-v1'").fetchone()[0]==1,'Missing legacy factory marker')
        rows=P.rows(db,'select * from starcore_nation_factory_events order by event_sequence');check(len(rows)==h['event_sequence']==count,'Legacy committed prefix incomplete')
        prefix='0'*64;history=[];total={};built=None;last=0
        names=['register','enable','upgrade','disable'][:count]
        for i,(name,row) in enumerate(zip(names,rows),1):
            c=json.loads(row['change_json']);b=c['building'];built=built or b['builtAt']
            level=3 if i<3 else 4;action=['REGISTER_LEGACY','SET_OPERATIONAL','UPGRADE','SET_OPERATIONAL'][i-1]
            cost=dict(energy=56,industrial=70,mineral=280) if i==1 else dict(energy=64,industrial=80,mineral=320) if i==3 else {}
            expected=dict(factoryId=spec['factories']['valid'],nationId=seed['A'],resourceDomain=resource,builderId=players['A'],type='SMELTER',name='LegacyForge',level=level,operational=i in (2,3),version=i,builtAt=built,origin='LEGACY_REGISTERED')
            full=dict(format=2,operationId=spec['operations'][name],actorId=players['A' if i<3 else 'D'],action=action,building=expected,cost=cost,committedAt=c['committedAt'])
            if i==1:full['legacy']=dict(snapshotHash=source['fingerprint'],key='0',fields=dict(sorted((k[len('factory.0.'):],v) for k,v in source['raw'].items() if k.startswith('factory.0.'))))
            check(c==full and row['change_json']==compact(full),'Legacy identity/payment/provenance differs')
            check(row['event_sequence']==i and row['operation_id']==spec['operations'][name] and row['factory_id']==spec['factories']['valid'],'Legacy factory identity/sequence differs')
            check(instant(c['committedAt'])>=last and instant(c['committedAt'])>=instant(built),'Legacy registration time moved backwards');last=instant(c['committedAt'])
            if i==1:check(c['committedAt']==built and instant(built)>1700000000,'Legacy builder/time incorrectly attributed')
            prefix=hashed(f'{domain}\n{resource}\n{i}\n{prefix}\n{row["change_json"]}');check(prefix==row['entry_hash'],'Legacy factory event digest mismatch')
            for key,amount in cost.items():total[key]=total.get(key,0)+amount
            history.append(dict(sequence=i,**{k:v for k,v in c.items() if k!='format'}))
        check(prefix==h['prefix_hash'],'Legacy factory head digest mismatch')
        return dict(head=h,rows=rows,history=history,cost=total)

def native(path,seed,players,runs,spec,source,boot):
    f=factories(path,seed,players,spec,source,2 if boot==2 else 4)
    bindings={key:dict(authorityDomain=f['head']['authority_domain'],factoryId=spec['factories']['valid'],version=2 if key!='legacy_successor' else 3,type='SMELTER',level=3 if key!='legacy_successor' else 4,minimumLevel=3,origin='LEGACY_REGISTERED') for key in KEYS}
    keys=KEYS[:2] if boot==2 else KEYS;completed=[] if boot==2 else [KEYS[0]] if boot==3 else [KEYS[0],KEYS[2]]
    state=P.native(path,seed,players,runs,keys,completed,'A' if boot==2 else 'D',terms=TERMS,cancelled=() if boot==2 else (KEYS[1],),factory_bindings=bindings,initial=dict(mineral=1000,energy=1000,industrial=1000),infrastructure_cost=f['cost'])
    for row in state['entries']:
        r=json.loads(row['run_json']);b=f['history'][r['factory']['version']-1]['building']
        check(b['operational'] and b['level']==r['factory']['level'] and b['origin']==r['factory']['origin'],'Production used invalid legacy admission')
    return f,state

def commands(boot,runs,spec,source):
    result=[];factory=spec['factories']['valid'];ops=spec['operations'];digest=source['fingerprint']
    def c(text,response,who='A'):result.append((who,text,response))
    def start(key):c(f'/nationproduction startat {factory} legacy_refining {TERMS[key]["batches"]}','生产已开工',TERMS[key]['who'])
    def collect(key,who='A',response='生产批次已处理'):c('/nationproduction complete '+runs[key]['runId'],response,who)
    register=f'/nationfactory registerlegacy {factory} {digest} {ops["register"]}'
    if boot==2:
        c('/nationfactory legacy','登记会新收取1至当前等级累计材料');c('/nationfactory audit','没有全服旧工厂审计权限');c(register,'请求被拒绝','D')
        c(f'/nationfactory registerlegacy {factory} {"0"*64}','旧记录摘要／ID占用已变化')
        for key in ('processing','malformed','duplicate','unknown','foreign'):c(f'/nationfactory registerlegacy {spec["factories"][key]} {digest}','请求被拒绝')
        c(register,'工厂请求已提交');c(register,'没有重复扣除材料');c(f'/nationfactory registerlegacy {factory} {digest}','旧记录摘要／ID占用已变化')
        c('/nationfactory show '+factory,'没有可查看的这个工厂','B');c('/nationfactory show '+factory,'旧工厂付费登记')
        c(f'/nationproduction startat {factory} legacy_refining 1','工厂不可用')
        c(f'/nationfactory enable {factory} 1 {ops["enable"]}','工厂请求已提交');c(f'/nationfactory enable {factory} 1 {ops["enable"]}','没有重复扣除材料')
        start(KEYS[0]);start(KEYS[1])
    if boot==3:
        collect(KEYS[0]);collect(KEYS[0],response='没有重复扣除或发放')
        c('/nationproduction cancel '+runs[KEYS[1]]['runId'],'生产批次已处理');c('/nationproduction cancel '+runs[KEYS[1]]['runId'],'没有重复扣除或发放')
        c(f'/nationfactory upgrade {factory} 2','请求被拒绝');c(f'/nationfactory upgrade {factory} 2 {ops["upgrade"]}','工厂请求已提交','D');start(KEYS[2])
        c(f'/nationfactory disable {factory} 3 {ops["disable"]}','工厂请求已提交','D')
        c(register,'没有重复扣除材料');c(register,'请求被拒绝','D');c('/nationfactory operation '+ops['register'],'旧建造人未证明')
    if boot==4:
        collect(KEYS[2],'D');collect(KEYS[2],'D','没有重复扣除或发放');collect(KEYS[0],response='没有重复扣除或发放')
        c(register,'没有重复扣除材料');c(f'/nationfactory upgrade {factory} 2 {ops["upgrade"]}','没有重复扣除材料','D');c(f'/nationfactory disable {factory} 3 {ops["disable"]}','没有重复扣除材料','D')
        c('/nationfactory legacy','ALREADY_REGISTERED_OR_ID_COLLISION','D')
        c('/chronicle contributions','生产资源 industrial');c('/chronicle contributions','生产资源 industrial','D');c('/chronicle contributions nation me','生产资源 industrial','D')
    return result

def inspect(run):
    m=read(run/'preparation.json');spec=m['legacy_factory_acceptance'];check(spec['scenarios']==SCENARIOS,'Unexpected legacy scenarios')
    check((spec['expected_changes'],spec['expected_completions'],spec['expected_contributions'])==(4,2,4),'Unexpected legacy acceptance counts')
    check(len(set(spec['operations'].values()))==4 and len(set(spec['factories'].values()))==6,'Duplicate fixture request/asset IDs')
    with socket.socket() as s:s.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1);s.bind(('127.0.0.1',m['server_port']))
    for name,key in [('StarChronicle','plugin_sha256'),('StarCore','starcore_sha256'),('EpicCampaignPaperFixture','fixture_sha256')]:check(sha(run/f'plugins/{name}.jar')==m[key],'Frozen legacy artifact changed')
    for name,digest in {'EpicCampaignPaperFixture.java':m['fixture_source_sha256'],**m['fixture_extra_sources_sha256']}.items():check(sha(run/'fixture'/name)==digest,'Frozen legacy fixture changed')
    paper={v for k,v in m['source_artifacts'].items() if Path(k).name=='paper.jar'};check(len(paper)==1 and sha(run/'paper.jar') in paper,'Frozen Paper changed')
    for a in m['production_stack']['artifacts']:check(sha(run/'plugins'/a['filename'])==a['sha256'],'Frozen dependency changed')
    check(sha(run/'fixture/legacy-factory-catalog.json')==sha(run/'plugins/StarCore/nation-production.json')==spec['catalog_sha256'],'Legacy production catalog changed')
    e=run/'evidence';fixture=records(e/'epic-fixture.jsonl');check(not any(r['kind']=='FAILED' for r in fixture),'Retained legacy failure')
    check({r['boot'] for r in fixture}=={1,2,3,4} and len(list(e.glob('boot*.session.json')))==4,'Missing legacy boot')
    seed=next(r['data'] for r in fixture if r['kind']=='SEEDED');players=m['players'];source=staged(run,m,seed)
    captures=[r['data'] for r in fixture if r['kind']=='PRODUCTION_RUN'];check([r['key'] for r in captures]==KEYS,'Missing legacy production runs')
    runs={r['key']:r['run'] for r in captures};f,final=native(run/'plugins/StarCore/starcore.db',seed,players,runs,spec,source,4)
    events,views=P.chronicle(run/'plugins/StarChronicle/chronicle.db',final,2)
    last=None;count=0;stack=None;telemetry=[]
    for boot,scenario in enumerate(SCENARIOS,1):
        session=read(e/f'boot{boot}.session.json');check(session['boot']==boot and session['scenario']==scenario and session['status']=='COMPLETE','Incomplete legacy session')
        processes=session['processes'];check(len(processes)==2 and {p['name'] for p in processes}=={'server','clients'} and all(p.get('exit_code')==0 for p in processes),'Abnormal legacy process exit')
        server=next(p for p in processes if p['name']=='server');client=next(p for p in processes if p['name']=='clients')
        check(server['started_at']<client['started_at']<client['exited_at']<server['exited_at'],'Legacy process chronology differs')
        if last:check(last['exited_at']<server['started_at'],'Overlapping legacy boots')
        last=server
        for name,ext in [('controller','py'),('clients','cjs')]:check(sha(e/f'boot{boot}.{name}.{ext}')==session['source_sha256'][name],'Captured legacy runtime changed')
        observed=[r for r in fixture if r['boot']==boot]
        for kind in ('READY','SEEDED','CLOSED','STACK_LOADED','PRODUCTION_BOUND','LEGACY_AUDIT'):check(sum(r['kind']==kind for r in observed)==1,'Legacy lifecycle missing: '+kind)
        one=lambda kind:next(r['data'] for r in observed if r['kind']==kind)
        check(one('READY')==dict(product=m['plugin_sha256'],native=m['starcore_sha256'],route=m['route']) and one('SEEDED')==seed and seed['nonOp'] is True and one('CLOSED')==dict(normalDisable=True),'Legacy live artifact/identity differs')
        current=one('STACK_LOADED');check(set(current['plugins'])=={'StarCore','StarChronicle','FancyNpcs','WorldGuard','WorldEdit','Essentials','Vault'} and all(current['plugins'].values()) and current['businessEconomy']=='dev.starcore.starcore.foundation.economy.PlayerVaultEconomyService','Legacy selected stack missing')
        if stack:check(stack==current,'Legacy provider changed')
        stack=current
        log=(e/f'boot{boot}.server.log').read_text()
        for line in re.findall(r'^.*(?:ERROR\]|ERROR\]:|SEVERE|Failed to (?:enable|disable) STARCORE module|同步保存失败|异步保存失败|SQLITE_BUSY|startup failed|Could not pass event|Committed history source .* retained|History scan retained pending evidence).*$',log,re.M|re.I):
            check(P.T.EPIC.vendor_telemetry(line),'Legacy persistence/lifecycle diagnostic: '+line);telemetry.append(dict(boot=boot,message=line))
        check(all(v in log for v in ('Done (','Stopping server','All dimensions are saved')),'Incomplete normal legacy lifecycle')
        for kind in {r['kind'] for r in observed}:
            emitted=[json.loads(line.split('EPIC_'+kind+' ',1)[1]) for line in log.splitlines() if 'EPIC_'+kind+' ' in line]
            check(emitted==[r['data'] for r in observed if r['kind']==kind],'Legacy fixture/server stream differs')
        check(all(server['started_at']<=instant(r['time'])<=server['exited_at'] for r in observed),'Legacy observation outside lifetime')
        clients=records(e/f'boot{boot}.clients.log');check(not any(r['kind']=='FAILED' for r in clients),'Legacy client failure')
        spawns=[r['data'] for r in clients if r['kind']=='SPAWN'];check(len(spawns)==4 and {r['who'] for r in spawns}==set('ABCD') and all(r['uuid']==players[r['who']] for r in spawns),'Legacy client identity differs')
        actual=[r for r in clients if r['kind']=='COMMAND_RESULT'];check([(r['data']['who'],r['data']['command'],r['data']['contains']) for r in actual]==commands(boot,runs,spec,source),'Actual legacy commands differ');count+=len(actual)
        for row in actual:
            d=row['data'];check(d['contains'] in d['response'] and client['started_at']<=instant(row['time'])<=client['exited_at'],'Legacy response/lifetime differs')
            check(any(r['kind']=='MESSAGE' and r['data']['who']==d['who'] and r['data']['message'] in d['response'] for r in clients),'Legacy command lacks protocol response')
            if d['contains']=='工厂请求已提交':
                operation=d['command'].split()[-1];receipt=next(v for v in f['history'] if v['operationId']==operation)
                check(operation in d['response'] and receipt['actorId']==players[d['who']] and client['started_at']<=instant(receipt['committedAt'])<=instant(row['time']),'Legacy payment lacks actual request identity/time')
            if d['contains']=='生产已开工':
                paid=next(v for v in runs.values() if v['runId'] in d['response']);check(paid['actorId']==players[d['who']] and client['started_at']<=instant(paid['startedAt'])<=instant(row['time']),'Legacy production start lacks request identity/time')
            if d['contains']=='生产批次已处理':
                terminal=[v for v in final['entries'] if v['run_id']==d['command'].split()[-1] and v['state']==('CANCELLED' if ' cancel ' in d['command'] else 'COMPLETED')]
                check(len(terminal)==1 and client['started_at']<=terminal[0]['changed_at']/1000<=instant(row['time']),'Legacy production settlement outside command')
        cp=e/f'checkpoints/boot{boot}';inventory=read(cp/'checkpoint.json');check(inventory['boot']==boot and set(inventory['sha256'])=={'chronicle.db','starcore.db'} and server['exited_at']<=inventory['time'],'Legacy stopped checkpoint differs')
        for name,digest in inventory['sha256'].items():
            check(sha(cp/name)==digest,'Stopped legacy checkpoint changed')
            if boot==4:check(sha(run/'plugins'/('StarCore' if name=='starcore.db' else 'StarChronicle')/name)==digest,'Final legacy database differs')
        audit=one('LEGACY_AUDIT');check(audit['nonOp'] is True,'Legacy actor had operator rights')
        n=max(0,boot-2);live=one('PRODUCTION_CHECK');check(live['expected']==n,'Wrong live legacy production count')
        if boot==1:
            check(audit['report']['raw']=={} and audit['quotes']==[] and live['native']==live['events']==live['contributions']==[],'Legacy boot 1 minted history')
            continue
        report=audit['report'];check(report['raw']==source['raw'] and report['fingerprint']==source['fingerprint'] and report['resourceDomain']==source['resource_domain'] and report['issues']==[],'Live raw legacy audit differs')
        expected_issues=[[],['UNRESOLVED_PROCESSING'],['INVALID_METADATA'],['DUPLICATE_FACTORY_ID'],['DUPLICATE_FACTORY_ID'],['UNKNOWN_FIELD'],['UNKNOWN_NATION']]
        check([v['issues'] for v in report['entries']]==expected_issues,'Legacy malformed/inflight/foreign records were not quarantined')
        quotes=audit['quotes'];check(len(quotes)==(6 if boot==2 else 6 if boot==3 else 0),'Legacy quote authorization differs')
        if boot==2:check(quotes[0]['blockers']==[] and quotes[0]['cost']==dict(energy=56,industrial=70,mineral=280),'Legacy cumulative quote differs')
        state_f,state=native(cp/'starcore.db',seed,players,runs,spec,source,boot);ev,cs=P.chronicle(cp/'chronicle.db',state,n,bound_empty=boot==2)
        check(state_f['rows']==f['rows'][:len(state_f['rows'])] and state['entries']==final['entries'][:len(state['entries'])],'Legacy prefix changed across restart')
        check(ev==events[:n] and cs==[v for v in views if v['request']['sourceEventId'] in {x['id'] for x in ev}],'Legacy contributions changed across restart')
        plain=[dict(r,run={k:v for k,v in r['run'].items() if k!='factory'}) for r in final['receipts'][:n]]
        check(live['native']==plain and live['events']==events[:n] and sorted(live['contributions'],key=lambda c:c['id'])==cs,'Live legacy completion/projection differs')
        stocks={resource:0 for resource in ('agricultural','chemical','energy','industrial','luxury','mineral','strategic')};stocks.update({resource:amount for (nation,resource),amount in state['stocks'].items() if nation==seed['A']})
        checks=[r['data'] for r in observed if r['kind']=='LEGACY_CHECK'];wanted={2:[(3,2,True,2,3)],3:[(3,2,True,2,3),(4,4,False,1,1)],4:[(4,4,False,1,1),(4,4,False,0,0)]}[boot]
        check([(v['building']['level'],v['building']['version'],v['building']['operational'],v['runningRuns'],v['runningBatches']) for v in checks]==wanted,'Legacy live capacity differs')
        for v in checks:
            version=v['building']['version'];check(v['building']==f['history'][version-1]['building'],'Legacy live building differs')
            expected=[dict(h,legacy=h.get('legacy')) for h in f['history'][:version]]
            check(v['history']==expected and v['audit']['raw']==source['raw'] and v['audit']['fingerprint']==source['fingerprint'],'Legacy live history/source differs')
        check(checks[-1]['stockpile']==stocks and live['stockpile']==stocks,'Legacy live materials differ')
    succession=[r for r in fixture if r['kind']=='PRODUCTION_SUCCESSOR'];check(len(succession)==1 and succession[0]['boot']==3 and succession[0]['data']==dict(nation=seed['A'],previous=players['A'],current=players['D'],publicNativeOperation=True),'Legacy succession missing')
    return dict(status='SELECTED_PAPER_LEGACY_FACTORY_REGISTRATION_ACCEPTED',boots=4,normal_process_exits=8,actual_command_results=count,legacy_records=7,registered=1,quarantined=6,
        factory_changes=4,completions=2,cancellations=1,contributions=4,registration_cost=dict(energy=56,industrial=70,mineral=280),total_factory_cost=f['cost'],production_inputs=dict(energy=2,mineral=6),industrial_output=4,
        chronicle_sha256=m['plugin_sha256'],starcore_sha256=m['starcore_sha256'],plugins=stack['plugins'],vendor_telemetry=telemetry,limitations=m['limitations'])

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('run',type=Path);print(json.dumps(inspect(p.parse_args().run),ensure_ascii=False,indent=2))
