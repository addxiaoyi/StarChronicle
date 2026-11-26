"""Independently verify real-wallet interruption, immutable audit and no blind recovery."""
from contextlib import closing
from decimal import Decimal
from pathlib import Path
import argparse, hashlib, json, re, shutil, socket, sqlite3, tempfile, yaml

def check(value, message):
    if not value: raise AssertionError(message)
def read(path): return json.loads(path.read_text())
def records(path): return [json.loads(line) for line in path.read_text().splitlines() if line.startswith('{')]
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def hashed(text): return hashlib.sha256(text.encode()).hexdigest()
def rows(db, query, args=()): return [dict(r) for r in db.execute(query, args)]
def connect(path,wal=False):
    if not wal:
        for suffix in ('-wal','-journal'):
            sidecar=Path(str(path)+suffix)
            check(not sidecar.exists() or sidecar.stat().st_size==0,'Stopped audit database has pending journal')
    db=sqlite3.connect(path.resolve().as_uri()+('?mode=ro' if wal else '?mode=ro&immutable=1'),uri=True)
    db.row_factory=sqlite3.Row
    check(db.execute('pragma integrity_check').fetchone()[0]=='ok','Audit SQLite integrity failed')
    return db
def wallet_expected(plan, initial, seeded):
    if seeded or plan['window']=='before': return {'A':Decimal(1000),'B':initial}
    return {'A':Decimal(1000 if plan['leg']==3 else 947),'B':Decimal(60) if plan['leg']==2 else initial}

def native(path, seeded, plan, seed, players, captured,wal=False):
    states=['OFFERED'] if seeded else ['OFFERED','RESERVED','DEBIT_INTENT']
    if not seeded and plan['leg']>=2:states+=['DEBIT_ACKNOWLEDGED','CREDIT_INTENT']
    if not seeded and plan['leg']==3:states+=['CREDIT_REJECTED','REFUND_INTENT']
    with closing(connect(path,wal)) as db:
        heads=rows(db,'select * from starcore_player_trade_head');check(len(heads)==1,'Missing audit journal head');head=heads[0]
        entries=rows(db,'select * from starcore_player_trade_events order by event_sequence')
        check(len(entries)==head['event_sequence']==len(states),'Audit journal length differs')
        prefix='0'*64;snapshots=[]
        for i,(entry,state) in enumerate(zip(entries,states),1):
            s=json.loads(entry['snapshot_json']);snapshots.append(s)
            check(entry['event_sequence']==entry['offer_version']==s['version']==i,'Audit journal sequence differs')
            check(entry['offer_id']==s['id']==captured['id'] and s['state']==state,'Audit state path differs')
            check(json.dumps(s,ensure_ascii=False,separators=(',',':'))==entry['snapshot_json'],'Noncanonical audit snapshot')
            prefix=hashed(f'{head["authority_domain"]}\n{head["resource_domain"]}\n{i}\n{prefix}\n{entry["snapshot_json"]}')
            check(prefix==entry['entry_hash'],'Audit journal hash differs')
            expected=dict(format=1,id=captured['id'],buyer=players['A'],seller=players['B'],buyerNation=seed['A'],sellerNation=seed['B'],
                resourceDomain=head['resource_domain'],resource='agricultural',amount=20,unitPrice='2.5',feeMinor=100,tariffMinor=200)
            check(all(s[k]==v for k,v in expected.items()),'Frozen audit terms differ')
            for k in ('treasuryDomain','provider','createdAt','expiresAt'):
                check(s[k]==captured['terms'][k],'Captured audit terms differ')
            check('settledAt' not in s,'Uncertain trade has settlement time')
            if i==1:check(s['changedAt']==s['createdAt'] and 'acceptedAt' not in s,'Offer time differs')
            else:check(s['acceptedAt']==snapshots[1]['changedAt'],'Acceptance time differs')
        check(prefix==head['prefix_hash'],'Audit committed head differs')
        resource=dict(db.execute('select property_key,property_value from starcore_resource_state'))
        treasury=dict(db.execute('select property_key,property_value from starcore_treasury_state'))
        stocks={(resource[f'stockpile.{i}.nationId'],resource[f'stockpile.{i}.type']):int(resource[f'stockpile.{i}.amount']) for i in range(int(resource['count']))}
        check(stocks=={(seed[w],'agricultural'):80 if w=='B' and not seeded else 100 for w in 'ABC'},'Audit escrow conservation differs')
        balances={treasury[f'treasury.{i}.nationId']:Decimal(treasury[f'treasury.{i}.balance']) for i in range(int(treasury['count']))}
        check(balances=={seed[w]:Decimal(1000000) for w in 'ABC'},'Unsettled audit treasury changed')
        check(int(treasury.get('credit.count','0'))==0,'Unsettled audit has tax receipts')
        check(treasury['authorityDomainId']==snapshots[0]['treasuryDomain'] and resource['resource.authority-domain']==head['resource_domain'],'Audit asset authority differs')
        for table in ('trade_history','business_transactions','trade_history_changes'):
            check(db.execute('select count(*) from '+table).fetchone()[0]==0,'Uncertain trade has native history')
        return dict(head=head,entries=entries,snapshots=snapshots,stocks=sorted(stocks.items()),balances=balances)

def chronicle(path,wal=False):
    with closing(connect(path,wal)) as db:
        for query in (
            "select * from events where type='PLAYER_TRADE_SETTLED' or id like 'starcore:player-trade:%' or id like 'starcore:trade-history:%'",
            "select * from committed_history_ingress where source in ('starcore.player-trade.settlements.v1','starcore.trade.history.v1')",
            "select * from contributions where type='TRADE'",
            "select * from committed_history_sources where source in ('starcore.player-trade.settlements.v1','starcore.trade.history.v1') and sequence<>0"):
            check(not rows(db,query),'Uncertain trade has Chronicle settlement or contribution')

def audit_view(view,state,plan,seeded,expected_money):
    audit=view['audit'];head=state['head'];local=state['snapshots']
    check(audit['authorityDomain']==head['authority_domain'] and audit['headSequence']==head['event_sequence'] and audit['headHash']==head['prefix_hash'],'Live audit checkpoint differs')
    check(len(audit['entries'])==len(local),'Live audit history incomplete')
    for entry,row,s in zip(audit['entries'],state['entries'],local):
        check(entry['sequence']==row['event_sequence'] and entry['prefixHash']==row['entry_hash'],'Live audit entry identity differs')
        live=entry['snapshot'];terms=live['terms'];flattened=dict(terms)
        for key in ('buyerNation','sellerNation'):flattened[key]=flattened[key]['value']
        flattened['unitPrice']=str(Decimal(str(flattened['unitPrice'])).normalize())
        flattened.update({k:v for k,v in live.items() if k!='terms' and v is not None});flattened['format']=1
        check(flattened==s,'Live audit snapshot differs')
    check(view['walletCalls']==0 and view['settlements']==0,'Read-only audit invoked wallet or settled')
    check(view['buyerStock']==100 and view['sellerStock']==(100 if seeded else 80),'Live audit escrow differs')
    check({k:Decimal(v) for k,v in view['wallet'].items()}==expected_money,'Live audit wallet differs')
    check(view['nextAction']==('SELLER_DECISION' if seeded else 'PROVIDER_EVIDENCE_REQUIRED'),'Live audit recovery guidance differs')
    calls=view['calls'];check(len(calls)==3,'Live audit wallet steps missing')
    for i,call in enumerate(calls,1):
        step=('BUYER_DEBIT','SELLER_CREDIT','BUYER_REFUND')[i-1]
        evidence='NO_INTENT' if seeded or i>plan['leg'] else 'INTENT_WITHOUT_RESULT' if i==plan['leg'] else 'PROVIDER_REJECTED' if i==2 else 'PROVIDER_ACKNOWLEDGED'
        op=call['operation'];check(call['step']==step and call['evidence']==evidence,'Live audit evidence classification differs')
        check(op['provider']==local[0]['provider'] and op['account']==local[0]['seller' if i==2 else 'buyer'] and op['direction']==('DEBIT' if i==1 else 'CREDIT') and Decimal(str(op['amount']))==Decimal(50 if i==2 else 53),'Live audit frozen wallet call differs')
        intent=next((e for e in audit['entries'] if e['snapshot']['state']==('DEBIT_INTENT','CREDIT_INTENT','REFUND_INTENT')[i-1]),None)
        check(call.get('intent')==intent,'Live audit intent link differs')
        result=None if intent is None or intent==audit['entries'][-1] else audit['entries'][audit['entries'].index(intent)+1]
        check(call.get('result')==result,'Live audit result link differs')

def inspect(run):
    run=Path(run);manifest=read(run/'preparation.json');plan=manifest['player_trade_audit_acceptance'];evidence=run/'evidence'
    check((plan['leg'],plan['window']) in ((1,'before'),(1,'after'),(2,'after'),(3,'after')),'Unknown audit scenario')
    for name,key in [('StarCore','starcore_sha256'),('StarChronicle','plugin_sha256'),('EpicCampaignPaperFixture','fixture_sha256')]:check(sha(run/'plugins'/(name+'.jar'))==manifest[key],'Frozen audit artifact changed')
    check(sha(run/'fixture/EpicCampaignPaperFixture.java')==manifest['fixture_source_sha256'],'Frozen base fixture changed')
    for name,digest in manifest['fixture_extra_sources_sha256'].items():check(sha(run/'fixture'/name)==digest,'Frozen audit fixture changed')
    for vendor in manifest['production_stack']['artifacts']:check(sha(run/'plugins'/vendor['filename'])==vendor['sha256'],'Frozen audit vendor changed')
    fixture=records(evidence/'epic-fixture.jsonl');check(not any(r['kind']=='FAILED' for r in fixture),'Retained audit fixture failure')
    seed=next(r['data'] for r in fixture if r['kind']=='SEEDED');players=manifest['players']
    captures=[r['data'] for r in fixture if r['kind']=='AUDIT_OFFER'];check(len(captures)==1,'Audit offer capture differs');captured=captures[0]
    initial=Decimal(next(r['data']['sellerInitial'] for r in fixture if r['kind']=='AUDIT_BOUND'))
    check(initial==(Decimal('10000000000000') if plan['leg']==3 else Decimal(10)),'Seller rejection boundary differs')
    check(len(list(evidence.glob('boot*.session.json')))==4,'Require four audit boots')
    states=[];commands=0;previous_exit=0
    for boot in range(1,5):
        session=read(evidence/f'boot{boot}.session.json');interrupted=boot==2
        check(session['scenario']==('audit-seed','audit-interrupt','audit-recover','audit-recover')[boot-1] and session['status']==('EXPECTED_PROCESS_INTERRUPTION' if interrupted else 'COMPLETE'),'Audit session incomplete')
        processes=session['processes'];check(len(processes)==2 and {x['name'] for x in processes}=={'server','clients'},'Audit process inventory differs')
        server=next(x for x in processes if x['name']=='server');client=next(x for x in processes if x['name']=='clients')
        check(server.get('exit_code')==(-9 if interrupted else 0) and client.get('exit_code')==0,'Audit process exit differs')
        check(previous_exit<server['started_at']<client['started_at']<client['exited_at']<server['exited_at'],'Audit process chronology differs');previous_exit=server['exited_at']
        for name,suffix in [('controller','py'),('clients','cjs')]:check(sha(evidence/f'boot{boot}.{name}.{suffix}')==session['source_sha256'][name],'Captured audit script changed')
        observed=[r for r in fixture if r['boot']==boot];server_log=(evidence/f'boot{boot}.server.log').read_text()
        for kind in ('READY','SEEDED','STACK_LOADED','AUDIT_BOUND'):check(sum(r['kind']==kind for r in observed)==1,'Audit lifecycle missing')
        check(sum(r['kind']=='CLOSED' for r in observed)==(0 if interrupted else 1),'Audit shutdown evidence differs')
        check('Done (' in server_log and (interrupted or 'All dimensions are saved' in server_log),'Audit server lifecycle incomplete')
        for line in server_log.splitlines():
            if re.search(r'ERROR\]|ERROR\]:|Could not pass event|同步保存失败|异步保存失败|SQLITE_BUSY|Committed history source .* retained|History scan retained pending evidence',line):
                check('[FancyAnalytics]' in line,'Audit server diagnostic: '+line)
        ready=next(r['data'] for r in observed if r['kind']=='READY');bound=next(r['data'] for r in observed if r['kind']=='AUDIT_BOUND')
        check(ready['product']==manifest['plugin_sha256'] and ready['native']==manifest['starcore_sha256'] and seed['nonOp'] and bound['nonOp'],'Live audit artifact/permission differs')
        check(bound['provider']==captured['terms']['provider'],'Audit wallet provider changed')
        stack=next(r['data'] for r in observed if r['kind']=='STACK_LOADED')
        check(set(stack['plugins'])=={'StarCore','StarChronicle','FancyNpcs','WorldGuard','WorldEdit','Essentials','Vault'} and stack['businessEconomy'].endswith('.PlayerVaultEconomyService'),'Audit selected stack differs')
        for kind in {r['kind'] for r in observed}:
            emitted=[json.loads(line.split('EPIC_'+kind+' ',1)[1]) for line in server_log.splitlines() if 'EPIC_'+kind+' ' in line]
            check(emitted==[r['data'] for r in observed if r['kind']==kind],'Audit fixture/server streams differ')
        clients=records(evidence/f'boot{boot}.clients.log');check(not any(r['kind']=='FAILED' for r in clients),'Audit client failure')
        spawns=[r['data'] for r in clients if r['kind']=='SPAWN'];check(len(spawns)==4 and {r['who']:r['uuid'] for r in spawns}==players,'Audit actual clients differ')
        command_rows=[r['data'] for r in clients if r['kind']=='COMMAND_RESULT'];commands+=len(command_rows)
        identity=captured['id']
        expected=[('A','/trade offer EpicB agricultural 20 2.50 600000','交易请求已发送给'),('B','/trade audit '+identity,'无调用意图记录'),('C','/trade audit '+identity,'找不到你参与的交易')] if boot==1 else [] if interrupted else [
            *[(w,'/trade audit '+identity,'结果未记录') for w in 'AB'],*[(w,'/trade audit '+identity,'找不到你参与的交易') for w in 'CD'],
            ('A','/trade resume '+identity,'仅原卖方'),('B','/trade resume '+identity,'付款结果待人工核对'),('B','/trade accept '+identity,'付款结果待人工核对'),('B','/trade audit '+identity,'需要原钱包提供者的独立付款证据')]
        check([(r['who'],r['command'],r['contains']) for r in command_rows]==expected,'Audit actual commands differ')
        for c in command_rows:
            check(c['contains'] in c['response'] and any(r['kind']=='MESSAGE' and r['data']['who']==c['who'] and c['contains'] in r['data']['message'] and r['data']['message'] in c['response'] for r in clients),'Audit response lacks protocol evidence')
        cp=evidence/f'checkpoints/boot{boot}';checkpoint=read(cp/'checkpoint.json')
        check(checkpoint['boot']==boot and checkpoint['time']>=server['exited_at'] and bool(checkpoint.get('interrupted'))==interrupted,'Audit checkpoint boundary differs')
        for name,digest in checkpoint['sha256'].items():check(sha(cp/name)==digest,'Audit checkpoint hash differs')
        check({'starcore.db','chronicle.db',*('Essentials/'+players[w]+'.yml' for w in 'AB')}<=set(checkpoint['sha256']),'Audit checkpoint files missing')
        state=native(cp/'starcore.db',boot==1,plan,seed,players,captured);states.append(state);chronicle(cp/'chronicle.db')
        if boot>2:check(state==states[1],'Audit recovery changed persisted obligation')
        wanted=wallet_expected(plan,initial,boot==1)
        for who in 'AB':check(Decimal(str(yaml.safe_load((cp/'Essentials'/(players[who]+'.yml')).read_text())['money']))==wanted[who],'Persisted audit wallet differs')
        live=[r['data'] for r in observed if r['kind']=='PLAYER_TRADE_AUDIT']
        check([v['phase'] for v in live]==(['seed'] if boot<3 else ['recovered-before','recovered-after']),'Live audit checkpoints missing')
        for view in live:audit_view(view,states[0] if boot<3 else state,plan,boot<3,wallet_expected(plan,initial,boot<3))
        entered=[r['data'] for r in observed if r['kind']=='AUDIT_WALLET_ENTER'];returned=[r['data'] for r in observed if r['kind']=='AUDIT_WALLET_RETURN']
        check([r['call'] for r in entered]==(list(range(1,plan['leg']+1)) if interrupted else []),'Audit wallet invocation count differs')
        check([r['call'] for r in returned]==(list(range(1,(0 if plan['window']=='before' else plan['leg'])+1)) if interrupted else []),'Audit wallet return count differs')
        for r in entered+returned:
            i=r['call'];op=r['operation'];s=state['snapshots'][0]
            check(op==dict(provider=s['provider'],account=players['B' if i==2 else 'A'],direction='DEBIT' if i==1 else 'CREDIT',amount=50 if i==2 else 53),'Actual audit wallet arguments differ')
        for r in returned:check(r['outcome']==('REJECTED' if plan['leg']==3 and r['call']==2 else 'ACKNOWLEDGED'),'Actual wallet outcome differs')
        if interrupted:
            pause=next(r['data'] for r in observed if r['kind']=='AUDIT_PAUSED');interrupt=session['wallet_interruption']
            check(pause==interrupt['pause'] and pause['call']==plan['leg'] and pause['window']==plan['window'] and pause['offer']==identity,'Audit interruption boundary differs')
            check(interrupt['server_pid']==server['pid'] and interrupt['server_exit']==-9 and interrupt['physical_power_loss'] is False,'Audit interruption process differs')
            check({k:Decimal(v) for k,v in interrupt['observed_file_balances'].items()}==wanted,'Audit pre-kill wallet observation differs')
            check('EpicB issued server command: /trade accept '+identity in server_log,'Interrupted acceptance was not an actual player command')
            raw=cp/'interrupted-raw';check((raw/'starcore.db').is_file() and (raw/'chronicle.db').is_file(),'Raw interrupted databases missing')
            with tempfile.TemporaryDirectory() as temporary:
                copy=Path(temporary)/'raw';shutil.copytree(raw,copy)
                check(native(copy/'starcore.db',False,plan,seed,players,captured,wal=True)==state,'Raw interrupted native state differs')
                chronicle(copy/'chronicle.db',wal=True)
    check(native(run/'plugins/StarCore/starcore.db',False,plan,seed,players,captured)==states[-1],'Final native audit differs')
    chronicle(run/'plugins/StarChronicle/chronicle.db')
    with socket.socket() as probe:check(probe.connect_ex(('127.0.0.1',manifest['server_port']))!=0,'Audit server still listening')
    return dict(status='VERIFIED_READ_ONLY_AUDIT_AND_EXPECTED_PROCESS_INTERRUPTION',leg=plan['leg'],window=plan['window'],
        boots=4,normal_server_exits=3,expected_kills=1,commands=commands+1,state=states[-1]['snapshots'][-1]['state'],
        wallet_evidence='INTENT_WITHOUT_RESULT',settlements=0,contributions=0,chronicle_sha256=manifest['plugin_sha256'],starcore_sha256=manifest['starcore_sha256'])

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('run',type=Path);args=parser.parse_args()
    print(json.dumps(inspect(args.run),ensure_ascii=False,indent=2))
