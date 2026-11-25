"""Verify a real accepted sale persisted before consumption, then imported before any login."""
from contextlib import closing
from pathlib import Path
import argparse
import importlib.util
import json
import re
import socket
SPEC=importlib.util.spec_from_file_location('nation_trade',Path(__file__).with_name('inspect-nation-trade-paper.py'))
I=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(I)
check,read,sha,records=I.check,I.read,I.sha,I.records

def inspect(run):
    run=run.resolve();manifest=read(run/'preparation.json');evidence=run/'evidence';fixture=records(evidence/'epic-fixture.jsonl')
    phases=['sale-offline-first','sale-offline-second']
    check(manifest['nation_trade_acceptance']==dict(scenarios=phases,resource='agricultural',sales=1,quantity=20,total_value='50.00',contributions=4,mode='offline'),'Wrong offline acceptance contract')
    with socket.socket() as probe:
        probe.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1);probe.bind(('127.0.0.1',manifest['server_port']))
    for name,key in [('StarCore','starcore_sha256'),('StarChronicle','plugin_sha256'),('EpicCampaignPaperFixture','fixture_sha256')]:check(sha(run/f'plugins/{name}.jar')==manifest[key],'Frozen offline artifact changed')
    for name,digest in {'EpicCampaignPaperFixture.java':manifest['fixture_source_sha256'],**manifest['fixture_extra_sources_sha256']}.items():check(sha(run/'fixture'/name)==digest,'Frozen offline fixture changed')
    paper={v for k,v in manifest['source_artifacts'].items() if Path(k).name=='paper.jar'};check(len(paper)==1 and sha(run/'paper.jar') in paper,'Frozen offline Paper changed')
    for artifact in manifest['production_stack']['artifacts']:check(sha(run/'plugins'/artifact['filename'])==artifact['sha256'],'Frozen offline dependency changed')
    check({r['boot'] for r in fixture}=={1,2} and len(list(evidence.glob('boot*.session.json')))==2 and not any(r['kind']=='FAILED' for r in fixture),'Missing/failed offline boot')
    seed=next(r['data'] for r in fixture if r['kind']=='SEEDED');players=manifest['players'];offers={r['data']['key']:r['data']['offer'] for r in fixture if r['kind']=='SALE_OFFER'}
    check(set(offers)=={'primary','pending'},'Offline actual offers missing')
    final=I.native(run/'plugins/StarCore/starcore.db',seed,players,1,offers);events,contributions=I.chronicle(run/'plugins/StarChronicle/chronicle.db',final);domain=final['head']['authority_domain']
    resume=read(evidence/'sale-poll-resume.json')
    check(I.hashed(resume['before'])==resume['before_sha256'] and I.hashed(resume['after'])==resume['after_sha256'],'Offline configuration provenance changed')
    check(resume['before'].count('poll-ticks: 72000\n')==1 and resume['after']==resume['before'].replace('poll-ticks: 72000\n','poll-ticks: 20\n') and (run/'plugins/StarChronicle/config.yml').read_text()==resume['after'],'Offline poll interval change differs')
    previous=None;stack=None;telemetry=[];seen=[];acceptance=None
    for boot,phase in enumerate(phases,1):
        session=read(evidence/f'boot{boot}.session.json');check(session['boot']==boot and session['scenario']==phase and session['status']=='COMPLETE','Incomplete offline scenario')
        procs=session['processes'];check(len(procs)==2 and {p['name'] for p in procs}=={'server','clients'} and all(p.get('exit_code')==0 for p in procs),'Abnormal offline process exit')
        server=next(p for p in procs if p['name']=='server');client=next(p for p in procs if p['name']=='clients')
        check(server['started_at']<client['started_at']<client['exited_at']<server['exited_at'],'Offline process chronology differs')
        if previous:check(previous['exited_at']<server['started_at'],'Overlapping offline boots')
        previous=server
        for name,suffix in [('controller','py'),('clients','cjs')]:check(sha(evidence/f'boot{boot}.{name}.{suffix}')==session['source_sha256'][name],'Captured offline script changed')
        obs=[r for r in fixture if r['boot']==boot]
        for kind in ('READY','CLOSED','SEEDED','STACK_LOADED','SALE_BOUND'):check(sum(r['kind']==kind for r in obs)==1,'Missing offline lifecycle: '+kind)
        one=lambda kind:next(r['data'] for r in obs if r['kind']==kind)
        check(one('READY')==dict(product=manifest['plugin_sha256'],native=manifest['starcore_sha256'],route=manifest['route']) and one('SEEDED')==seed and seed['nonOp'] is True and one('CLOSED')==dict(normalDisable=True),'Offline live identity mismatch')
        current=one('STACK_LOADED');check(set(current['plugins'])=={'StarCore','StarChronicle','FancyNpcs','WorldGuard','WorldEdit','Essentials','Vault'} and all(current['plugins'].values()) and current['businessEconomy']=='dev.starcore.starcore.foundation.economy.PlayerVaultEconomyService','Missing offline selected stack')
        if stack:check(stack==current,'Offline stack changed')
        stack=current
        check(one('SALE_BOUND')==dict(domain='' if boot==1 else domain,resourceDomain=final['head']['resource_domain'],treasuryDomain=final['head']['treasury_domain'],nonOp=True,actualService='dev.starcore.starcore.module.resource.SimpleNationTradeService'),'Offline source provider differs')
        check(all(server['started_at']<=I.instant(r['time'])<=server['exited_at'] for r in obs),'Offline observation lifetime differs')
        log=(evidence/f'boot{boot}.server.log').read_text()
        for line in re.findall(r'^.*(?:ERROR\]|ERROR\]:|Failed to (?:enable|disable) STARCORE module|同步保存失败|异步保存失败|SQLITE_BUSY|startup failed|Could not pass event|Committed history source .* retained|History scan retained pending evidence).*$',log,re.M|re.I):
            check(I.EPIC.vendor_telemetry(line),'Offline server diagnostic: '+line);telemetry.append(dict(boot=boot,message=line))
        check(all(s in log for s in ('Done (','Stopping server','All dimensions are saved')),'Offline normal lifecycle incomplete')
        for kind in {r['kind'] for r in obs}:
            emitted=[json.loads(s.split('EPIC_'+kind+' ',1)[1]) for s in log.splitlines() if 'EPIC_'+kind+' ' in s]
            check(emitted==[r['data'] for r in obs if r['kind']==kind],'Offline fixture/server stream mismatch')
        clients=records(evidence/f'boot{boot}.clients.log');check(not any(r['kind']=='FAILED' for r in clients),'Offline client failure')
        spawns=[r['data'] for r in clients if r['kind']=='SPAWN'];check(len(spawns)==4 and {s['who'] for s in spawns}==set('ABCD') and all(s['uuid']==players[s['who']] for s in spawns),'Offline client identities differ')
        cmds=[r for r in clients if r['kind']=='COMMAND_RESULT'];wanted=([I.commands(1,offers)[0],I.commands(1,offers)[4],I.commands(1,offers)[6]] if boot==1 else [('B','/nationtrade accept '+offers['primary']['offerId'],'没有重复成交'),('B','/chronicle contributions','国库支付')])
        check([(r['data']['who'],r['data']['command'],r['data']['contains']) for r in cmds]==wanted,'Offline actual command sequence differs')
        check(all(r['data']['contains'] in r['data']['response'] and client['started_at']<=I.instant(r['time'])<=client['exited_at'] for r in cmds),'Offline actual response differs')
        check(all(any(m['kind']=='MESSAGE' and m['data']['who']==r['data']['who'] and m['data']['message'] in r['data']['response'] for m in clients) for r in cmds),'Offline command protocol messages missing')
        cp=evidence/f'checkpoints/boot{boot}';inv=read(cp/'checkpoint.json');check(inv['boot']==boot and set(inv['sha256'])=={'starcore.db','chronicle.db'} and inv['time']>=server['exited_at'],'Offline checkpoint boundary differs')
        check({p.name for p in cp.iterdir()}=={'starcore.db','chronicle.db','checkpoint.json'},'Unexpected offline checkpoint file')
        for name,digest in inv['sha256'].items():
            check(sha(cp/name)==digest,'Offline checkpoint bytes changed')
            if boot==2:check(sha(run/'plugins'/('StarCore' if name=='starcore.db' else 'StarChronicle')/name)==digest,'Offline final database changed')
        state=I.native(cp/'starcore.db',seed,players,1,offers);check(state==final,'Offline replay changed native assets/consent/receipts')
        if boot==1:
            with closing(I.connect(cp/'chronicle.db')) as db:
                check(db.execute('select count(*) from contributions').fetchone()[0]==0 and db.execute("select count(*) from events where type='NATIONAL_TRADE_COMMITTED'").fetchone()[0]==0 and db.execute('select count(*) from committed_history_ingress where source=?',(I.SOURCE,)).fetchone()[0]==0,'Sale consumed before offline handoff')
                cursor=I.rows(db,'select * from committed_history_sources where source=?',(I.SOURCE,));check(not cursor or cursor==[dict(source=I.SOURCE,authority_domain=domain,sequence=0)],'Offline cursor advanced before handoff')
            buffered=[r for r in obs if r['kind']=='SALE_OFFLINE_BUFFERED'];check(len(buffered)==1 and buffered[0]['data']==dict(domain=domain,settlements=1,events=0,contributions=0),'Missing durable offline backlog')
            acceptance=cmds[1];check(I.millis(final['receipts'][0]['committedAt'])<=final['fundingTimes']['nation-trade:'+offers['primary']['offerId']]<=I.millis(acceptance['time'])<=I.millis(buffered[0]['time']),'Offline native receipt chronology differs')
        else:
            check(I.chronicle(cp/'chronicle.db',state)==(events,contributions),'Offline durable projection changed')
            consumed=[r for r in obs if r['kind']=='SALE_OFFLINE_CONSUMED'];check(len(consumed)==1 and consumed[0]['data']==dict(onlinePlayers=0,settlements=1,contributions=4) and I.instant(consumed[0]['time'])<client['started_at'],'Backlog not verified before actual login')
            observations=[r for r in obs if r['kind']=='SALE_CHECK'];check(len(observations)==2 and I.instant(observations[0]['time'])<=I.instant(consumed[0]['time']),'Offline live verification missing')
            for r in observations:
                d=r['data'];check(d['expected']==1 and d['domain']==domain and d['native']==state['receipts'] and [I.canonical(e) for e in d['events']]==events and sorted(d['contributions'],key=lambda c:c['id'])==contributions,'Offline live evidence differs from SQL')
            check(all(I.instant(c['recordedAt'])<client['started_at'] and I.instant(c['recordedAt'])>=server['started_at'] for c in contributions),'Offline contributions not committed before login')
    return dict(status='SELECTED_PAPER_OFFLINE_NATIONAL_TRADE_ACCEPTED',chronicle_sha256=manifest['plugin_sha256'],starcore_sha256=manifest['starcore_sha256'],boots=2,normal_process_exits=4,settlements=1,contributions=4,actual_command_results=5,offline_before_login=True,authority_domain=domain,assets=final['assets'],vendor_telemetry=telemetry,limitations=manifest['limitations'])

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('run',type=Path)
    print(json.dumps(inspect(p.parse_args().run),ensure_ascii=False,indent=2))
