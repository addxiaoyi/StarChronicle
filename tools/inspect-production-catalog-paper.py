"""Audit actual stopped catalog acceptance independently from the live service and fixture."""
from pathlib import Path
import argparse,importlib.util,json,re,socket
SPEC=importlib.util.spec_from_file_location('production',Path(__file__).with_name('inspect-nation-production-paper.py'))
P=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(P)
check,read,sha,records,instant=P.check,P.read,P.sha,P.records,P.instant
TERMS={
 'old':dict(who='A',batches=1,recipeId='custom_forge',inputs=dict(energy=1,mineral=2),outputs=dict(industrial=3),seconds=30),
 'refund':dict(who='A',batches=2,recipeId='renewed_forge',inputs=dict(energy=4,mineral=10),outputs=dict(industrial=14),seconds=2),
 'changed':dict(who='A',batches=1,recipeId='renewed_forge',inputs=dict(energy=2,mineral=5),outputs=dict(industrial=7),seconds=1),
 'latest':dict(who='A',batches=1,recipeId='renewed_forge',inputs=dict(energy=3,mineral=9),outputs=dict(industrial=11),seconds=1)}
SCENARIOS=['production-catalog-first','production-catalog-second','production-catalog-third']

def commands(boot,runs):
    result=[]
    def cmd(text,response,who='A'):result.append((who,text,response))
    def start(key):
        t=TERMS[key];cmd(f'/nationproduction start {t["recipeId"]} {t["batches"]}','生产已开工')
    def collect(key,response='生产批次已处理'):cmd('/nationproduction complete '+runs[key]['runId'],response)
    if boot==1:
        cmd('/nationproduction reload','没有生产配置管理权限')
        cmd('/nationproduction capacity','运行 0/1 个生产，占用 0/2 批')
        cmd('/nationproduction capacity','需要当前国家创始人身份','D')
        cmd('/nationproduction start custom_forge 3','请求被拒绝');start('old');collect('old','尚未到领取时间')
        cmd('/nationproduction start custom_forge 1','生产已达上限')
        cmd('/nationproduction recipes','定制锻造');cmd('/nationproduction recipes','精炼新规')
        cmd('/nationproduction start renewed_forge 2','生产已达上限')
        cmd('/nationproduction recipes','精炼新规');cmd('/nationproduction start renewed_forge 1','已暂停新开工')
    elif boot==2:
        cmd('/nationproduction start renewed_forge 1','已暂停新开工');collect('old');collect('old','没有重复扣除或发放')
        start('refund');cmd('/nationproduction cancel '+runs['refund']['runId'],'生产批次已处理');cmd('/nationproduction cancel '+runs['refund']['runId'],'没有重复扣除或发放')
        start('changed');cmd('/nationproduction start renewed_forge 2','生产已达上限');cmd('/nationproduction start renewed_forge 2','请求被拒绝');cmd('/nationproduction recipes','精炼再修订')
    else:
        cmd('/nationproduction start renewed_forge 1','已暂停新开工');collect('changed');collect('changed','没有重复扣除或发放')
        start('latest');collect('latest');collect('latest','没有重复扣除或发放')
        cmd('/nationproduction capacity','运行 0/2 个生产，占用 0/3 批');cmd('/chronicle contributions','生产资源 industrial');cmd('/chronicle contributions nation me','生产资源 industrial')
    return result

def native(path,seed,players,captured,boot):
    keys={1:['old'],2:['old','refund','changed'],3:list(TERMS)}[boot]
    completed={1:[],2:['old'],3:['old','changed','latest']}[boot]
    return P.native(path,seed,players,captured,keys,completed,'A',terms=TERMS,cancelled=('refund',))

def inspect(run):
    run=Path(run);m=read(run/'preparation.json');catalog=m['production_catalog_acceptance']
    check(catalog['scenarios']==SCENARIOS and catalog['expected_completions']==3 and catalog['expected_contributions']==6,'Unexpected catalog scenario')
    with socket.socket() as probe:probe.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1);probe.bind(('127.0.0.1',m['server_port']))
    for name,key in [('StarChronicle','plugin_sha256'),('StarCore','starcore_sha256'),('EpicCampaignPaperFixture','fixture_sha256')]:check(sha(run/f'plugins/{name}.jar')==m[key],'Frozen catalog artifact changed')
    for name,digest in {'EpicCampaignPaperFixture.java':m['fixture_source_sha256'],**m['fixture_extra_sources_sha256']}.items():check(sha(run/'fixture'/name)==digest,'Frozen catalog fixture changed')
    paper={v for k,v in m['source_artifacts'].items() if Path(k).name=='paper.jar'};check(len(paper)==1 and sha(run/'paper.jar') in paper,'Frozen Paper changed')
    for a in m['production_stack']['artifacts']:check(sha(run/'plugins'/a['filename'])==a['sha256'],'Frozen third-party plugin changed')
    variants=catalog['variants_sha256'];check(set(variants)=={'v1','v2','v3','disabled','invalid'},'Missing catalog variants')
    for name,digest in variants.items():check(sha(run/'fixture'/f'production-catalog-{name}.json')==digest,'Frozen catalog variant changed')
    check(sha(run/'plugins/StarCore/nation-production.json')==variants['v3'],'Final catalog differs')
    check(catalog['essentials_update_check'] is False and 'update-check: false' in (run/'plugins/Essentials/config.yml').read_text(),'Optional updater was not disabled')
    e=run/'evidence';fixture=records(e/'epic-fixture.jsonl');check(not any(r['kind']=='FAILED' for r in fixture),'Retained catalog fixture failure')
    check({r['boot'] for r in fixture}=={1,2,3} and len(list(e.glob('boot*.session.json')))==3,'Missing catalog boot')
    seed=next(r['data'] for r in fixture if r['kind']=='SEEDED');players=m['players']
    captures=[r['data'] for r in fixture if r['kind']=='PRODUCTION_RUN'];check([r['key'] for r in captures]==list(TERMS),'Missing catalog runs')
    runs={r['key']:r['run'] for r in captures};final=native(run/'plugins/StarCore/starcore.db',seed,players,runs,3)
    events,views=P.chronicle(run/'plugins/StarChronicle/chronicle.db',final,3)
    changes=records(e/'production-catalog-config.jsonl');check([(r['boot'],r['variant']) for r in changes]==[(1,'v2'),(1,'invalid'),(1,'disabled'),(2,'v2'),(2,'v3'),(2,'invalid'),(3,'v3')],'Catalog reload/edit sequence differs')
    previous=variants['v1']
    for c in changes:
        check(c['before']==previous and c['after']==variants[c['variant']],'Catalog file transition digest differs');previous=c['after']
    consoles=records(e/'memory-config.jsonl')
    expected_console=[(1,'nationproduction validate','校验通过，尚未改变运行配置'),(1,'nationproduction reload','生产配置已生效'),(1,'nationproduction reload','生产配置校验失败，当前运行配置保持不变'),(1,'nationproduction reload','生产配置已生效'),(2,'nationproduction reload','生产配置已生效'),(2,'nationproduction reload','生产配置已生效'),(3,'nationproduction reload','生产配置已生效')]
    check([(r['boot'],r['command'],r['expected']) for r in consoles]==expected_console and all(r['matched'] is True for r in consoles),'Missing admin validation/reload evidence')
    expected_cap={1:[('v1',1,1),('v1',1,1),('v2',1,1),('v2',1,1),('disabled',1,1)],2:[('disabled',1,1),('v2',1,1),('v3',1,1)],3:[('unavailable',1,1),('v3',0,0),('v3',0,0)]}
    last=None;count=0;telemetry=[];stack=None
    for boot,scenario in enumerate(SCENARIOS,1):
        session=read(e/f'boot{boot}.session.json');check(session['boot']==boot and session['scenario']==scenario and session['status']=='COMPLETE','Incomplete catalog session')
        processes=session['processes'];check(len(processes)==2 and {p['name'] for p in processes}=={'server','clients'} and all(p.get('exit_code')==0 for p in processes),'Abnormal catalog process exit')
        server=next(p for p in processes if p['name']=='server');client=next(p for p in processes if p['name']=='clients')
        check(server['started_at']<client['started_at']<client['exited_at']<server['exited_at'],'Catalog process chronology differs')
        if last:check(last['exited_at']<server['started_at'],'Overlapping catalog boots')
        last=server
        for name,ext in [('controller','py'),('clients','cjs')]:check(sha(e/f'boot{boot}.{name}.{ext}')==session['source_sha256'][name],'Captured catalog runtime script changed')
        observed=[r for r in fixture if r['boot']==boot]
        for kind in ('READY','SEEDED','CLOSED','STACK_LOADED','PRODUCTION_BOUND'):check(sum(r['kind']==kind for r in observed)==1,'Catalog lifecycle missing: '+kind)
        one=lambda k:next(r['data'] for r in observed if r['kind']==k)
        check(one('READY')==dict(product=m['plugin_sha256'],native=m['starcore_sha256'],route=m['route']) and one('SEEDED')==seed and seed['nonOp'] is True and one('CLOSED')==dict(normalDisable=True),'Live catalog identity differs')
        current=one('STACK_LOADED');check(set(current['plugins'])=={'StarCore','StarChronicle','FancyNpcs','WorldGuard','WorldEdit','Essentials','Vault'} and all(current['plugins'].values()) and current['businessEconomy']=='dev.starcore.starcore.foundation.economy.PlayerVaultEconomyService','Catalog stack missing')
        if stack:check(current==stack,'Catalog provider changed')
        stack=current
        check(one('PRODUCTION_BOUND')==dict(resourceDomain=final['head']['resource_domain'],domain='' if boot==1 else final['head']['authority_domain'],nonOp=True,actualService='dev.starcore.starcore.module.resource.SimpleNationProductionService'),'Catalog service binding differs')
        log=(e/f'boot{boot}.server.log').read_text()
        expected_invalid='Nation production catalog invalid; new starts disabled; existing runs remain recoverable: Duplicate JSON key: enabled'
        check(log.count(expected_invalid)==(1 if boot==3 else 0),'Missing invalid-startup disabled evidence')
        for line in re.findall(r'^.*(?:ERROR\]|ERROR\]:|SEVERE|Failed to (?:enable|disable) STARCORE module|同步保存失败|异步保存失败|SQLITE_BUSY|startup failed|Could not pass event|Committed history source .* retained|History scan retained pending evidence).*$',log,re.M|re.I):
            if boot==3 and re.fullmatch(r'\[\d\d:\d\d:\d\d ERROR\]: \[StarCore\] '+re.escape(expected_invalid),line):continue
            check(P.T.EPIC.vendor_telemetry(line),'Catalog persistence/lifecycle diagnostic: '+line);telemetry.append(dict(boot=boot,message=line))
        check(all(v in log for v in ('Done (','Stopping server','All dimensions are saved')),'Incomplete normal catalog lifecycle')
        for kind in {r['kind'] for r in observed}:
            emitted=[json.loads(line.split('EPIC_'+kind+' ',1)[1]) for line in log.splitlines() if 'EPIC_'+kind+' ' in line]
            check(emitted==[r['data'] for r in observed if r['kind']==kind],'Catalog fixture/server stream differs')
        check(all(server['started_at']<=instant(r['time'])<=server['exited_at'] for r in observed),'Catalog fixture outside server lifetime')
        for row in [r for r in changes+consoles if r['boot']==boot]:check(client['started_at']<=row['time']<=client['exited_at'],'Catalog edit/reload outside live scenario')
        for row in [r for r in consoles if r['boot']==boot]:check(row['expected'] in log,'Console reload lacks server output')
        capacities=[r['data'] for r in observed if r['kind']=='PRODUCTION_CATALOG'];check(len(capacities)==len(expected_cap[boot]),'Missing live capacity checkpoints')
        for row,(variant,running,batches) in zip(capacities,expected_cap[boot]):
            cfg=dict(enabled=False,limits={'max-running-runs':16,'max-running-batches':1000},recipes=[]) if variant=='unavailable' else read(run/'fixture'/f'production-catalog-{variant}.json')
            expected=dict(startsEnabled=cfg['enabled'],runningRuns=running,runningBatches=batches,maxRunningRuns=cfg['limits']['max-running-runs'],maxRunningBatches=cfg['limits']['max-running-batches'])
            recipes=[dict(recipeId=r['id'],name=r['name'],inputs=r['inputs'],outputs=r['outputs'],secondsPerBatch=r['seconds-per-batch']) for r in cfg['recipes']]
            check(row==dict(capacity=expected,recipes=recipes,maxBatches={r['id']:r['max-batches'] for r in cfg['recipes']}),'Live capacity or recipe snapshot differs')
        clients=records(e/f'boot{boot}.clients.log');check(not any(r['kind']=='FAILED' for r in clients),'Catalog client failure')
        spawns=[r['data'] for r in clients if r['kind']=='SPAWN'];check(len(spawns)==4 and {r['who'] for r in spawns}==set('ABCD') and all(r['uuid']==players[r['who']] for r in spawns),'Catalog player identity differs')
        actual=[r for r in clients if r['kind']=='COMMAND_RESULT'];check([(r['data']['who'],r['data']['command'],r['data']['contains']) for r in actual]==commands(boot,runs),'Actual catalog command sequence differs');count+=len(actual)
        for row in actual:
            d=row['data'];check(d['contains'] in d['response'] and client['started_at']<=instant(row['time'])<=client['exited_at'],'Catalog command response/lifetime differs')
            check(any(r['kind']=='MESSAGE' and r['data']['who']==d['who'] and r['data']['message'] in d['response'] for r in clients),'Catalog command lacks protocol response')
        started_keys={1:['old'],2:['refund','changed'],3:['latest']}[boot]
        starts=[r for r in actual if r['data']['contains']=='生产已开工']
        check(len(starts)==len(started_keys),'Missing actual paid catalog start')
        for key,row in zip(started_keys,starts):
            captured=next(r for r in observed if r['kind']=='PRODUCTION_RUN' and r['data']['key']==key)
            check(runs[key]['runId'] in row['data']['response'] and client['started_at']<=instant(runs[key]['startedAt'])<=instant(row['time'])<=instant(captured['time']),'Paid catalog start lacks exact command identity/time')
        ready=[r for r in observed if r['kind']=='PRODUCTION_READY']
        check([r['data']['key'] for r in ready]=={1:[],2:['old'],3:['changed','latest']}[boot],'Missing natural readiness evidence')
        for row in ready:
            d=row['data'];r=runs[d['key']]
            check(d['runId']==r['runId'] and d['readyAt']==r['readyAt'] and instant(d['observedAt'])>=instant(r['readyAt']),'Premature catalog readiness evidence')
        for row in actual:
            d=row['data']
            if d['contains']=='生产批次已处理':
                identity=d['command'].split()[-1];state='CANCELLED' if ' cancel ' in d['command'] else 'COMPLETED'
                terminal=[r for r in final['entries'] if r['run_id']==identity and r['state']==state]
                check(len(terminal)==1 and client['started_at']<=terminal[0]['changed_at']/1000<=instant(row['time']),'Catalog terminal receipt outside actual command')
                if state=='COMPLETED':check(any(r['data']['runId']==identity and instant(r['data']['observedAt'])<=terminal[0]['changed_at']/1000 for r in ready),'Catalog completion lacks preceding ready gate')
        checks=[r['data'] for r in observed if r['kind']=='PRODUCTION_CHECK'];check([r['expected'] for r in checks]=={1:[0],2:[1,1],3:[2,3]}[boot],'Missing catalog settlement checkpoints')
        for row in checks:
            n=row['expected'];check(row['native']==final['receipts'][:n] and row['events']==events[:n],'Live catalog history differs')
            check(sorted(row['contributions'],key=lambda c:c['id'])==[c for c in views if c['request']['sourceEventId'] in {v['id'] for v in events[:n]}],'Live catalog contribution differs')
        cp=e/f'checkpoints/boot{boot}';inventory=read(cp/'checkpoint.json')
        check(inventory['boot']==boot and set(inventory['sha256'])=={'chronicle.db','starcore.db'} and server['exited_at']<=inventory['time'],'Catalog stopped checkpoint boundary differs')
        check({p.name for p in cp.iterdir() if p.is_file()}=={'checkpoint.json','chronicle.db','starcore.db'},'Unexpected catalog checkpoint file')
        for name,digest in inventory['sha256'].items():
            check(sha(cp/name)==digest,'Stopped catalog checkpoint changed')
            if boot==3:check(sha(run/'plugins'/('StarCore' if name=='starcore.db' else 'StarChronicle')/name)==digest,'Final catalog database differs')
        state=native(cp/'starcore.db',seed,players,runs,boot);ev,cs=P.chronicle(cp/'chronicle.db',state,{1:0,2:1,3:3}[boot],bound_empty=boot==1)
        check(state['entries']==final['entries'][:len(state['entries'])],'Catalog native prefix changed across restart')
        check(ev==events[:len(ev)] and cs==[c for c in views if c['request']['sourceEventId'] in {v['id'] for v in ev}],'Catalog projections changed across restart')
    return dict(status='SELECTED_PAPER_PRODUCTION_CATALOG_ACCEPTED',boots=3,normal_process_exits=6,actual_command_results=count,completions=3,cancellations=1,contributions=6,industrial_output=21,mineral_consumed=16,energy_consumed=6,chronicle_sha256=m['plugin_sha256'],starcore_sha256=m['starcore_sha256'],plugins=stack['plugins'],vendor_telemetry=telemetry,limitations=m['limitations'])

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('run',type=Path);print(json.dumps(inspect(p.parse_args().run),ensure_ascii=False,indent=2))
