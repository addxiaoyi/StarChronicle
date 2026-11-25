"""Independently verify consent, common SQL assets, native journals and Chronicle projections."""
from contextlib import closing
from datetime import datetime, timezone
from decimal import Decimal
from pathlib import Path
import argparse
import hashlib
import importlib.util
import json
import re
import socket
import sqlite3
import uuid

SPEC=importlib.util.spec_from_file_location('epic_inspector',Path(__file__).with_name('inspect-epic-paper.py'))
EPIC=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(EPIC)
check,read,sha=EPIC.check,EPIC.read,EPIC.digest
SOURCE='starcore.nation-trade.settlements.v1'
ISSUER='StarCore.NationTradeService'
SCENARIOS=('sale-first','sale-second','sale-third')
TERMS={'primary':('A',20,250),'pending':('A',5,400),'cancelled':('A',1,100),'old-founder':('A',3,300),'successor':('D',4,125)}
SALES=('primary','pending','successor')
def records(path):return [json.loads(s) for s in path.read_text().splitlines() if s.startswith('{')]
def instant(text):return datetime.fromisoformat(text.replace('Z','+00:00')).timestamp()
def millis(text):return round(instant(text)*1000)
def stamp(ms):return datetime.fromtimestamp(ms/1000,timezone.utc).isoformat(timespec='milliseconds' if ms%1000 else 'seconds').replace('+00:00','Z')
def hashed(text):return hashlib.sha256(text.encode()).hexdigest()
def compact(value):return json.dumps(value,ensure_ascii=False,separators=(',',':'))
def rows(db,sql,args=()):return [dict(r) for r in db.execute(sql,args)]
def connect(path):
    for suffix in ('-wal','-journal'):
        p=Path(str(path)+suffix);check(not p.exists() or p.stat().st_size==0,'Stopped database has pending journal')
    db=sqlite3.connect(path.resolve().as_uri()+'?mode=ro&immutable=1',uri=True);db.row_factory=sqlite3.Row
    check(db.execute('pragma integrity_check').fetchone()[0]=='ok','SQLite integrity failed')
    return db

def native(path,seed,players,count,offers):
    with closing(connect(path)) as db:
        head=rows(db,'select * from starcore_nation_trade_head')
        check(len(head)==1 and head[0]['singleton']==1 and head[0]['committed_sequence']==count,'Independent trade head differs')
        head=head[0];domain=head['authority_domain']
        for key in ('authority_domain','resource_domain','treasury_domain'):check(str(uuid.UUID(head[key]))==head[key],'Noncanonical authority')
        markers={r[0] for r in db.execute('select migration_key from starcore_resource_state_migrations')}
        check({'nation-trade-journal-v1','resource-state-revision-v1','resource-transfer-receipts-v1','resource-transfer-history-v1'}<=markers,'Missing migration marker')
        revision=rows(db,'select * from starcore_resource_revision')
        check(len(revision)==1 and revision[0]['singleton']==1 and revision[0]['revision']>=count,'Missing resource state revision')
        resource=dict(db.execute('select property_key,property_value from starcore_resource_state'))
        treasury=dict(db.execute('select property_key,property_value from starcore_treasury_state'))
        nation_state=dict(db.execute('select property_key,property_value from starcore_nation_state'))
        for who in 'ABC':
            prefix=next(k[:-2] for k,v in nation_state.items() if k.endswith('.id') and '.member.' not in k and v==seed[who])
            founder=players['D' if who=='A' and count>=2 else who]
            check(nation_state[prefix+'founderId']==founder,'Persisted founder differs from handover')
            members={nation_state[prefix+f'member.{i}.id'] for i in range(int(nation_state[prefix+'memberCount']))}
            check(members=={players[w] for w in ('AD' if who=='A' else who)},'Persisted nation membership differs')
        check(resource['resource.authority-domain']==head['resource_domain'] and treasury['authorityDomainId']==head['treasury_domain'] and int(treasury['__starcore_revision__'])>=count,'Asset authority/revision differs')
        stored=rows(db,'select * from starcore_nation_trade_offers');expected_keys=('primary','pending') if count==1 else tuple(TERMS)
        check(len(stored)==len(expected_keys) and {r['offer_id'] for r in stored}=={offers[k]['offerId'] for k in expected_keys},'Unexpected/missing persisted offers')
        by_id={}
        for r in stored:
            o=json.loads(r['offer_json']);key=next(k for k in expected_keys if offers[k]['offerId']==r['offer_id'])
            actor,amount,price=TERMS[key]
            expected=dict(format=1,offerId=r['offer_id'],sellerId=players[actor],buyerId=players['B'],sellerNationId=seed['A'],buyerNationId=seed['B'],resourceId='agricultural',amount=amount,unitPriceMinor=price,resourceDomain=head['resource_domain'],treasuryDomain=head['treasury_domain'],createdAt=offers[key]['createdAt'],expiresAt=offers[key]['expiresAt'])
            check(o==expected and r['offer_json']==compact(expected),'Persisted consent terms differ')
            check({k:v for k,v in o.items() if k!='format'}==offers[key],'Observed player offer differs from SQL')
            check(r['offer_hash']==hashed(r['offer_json']) and r['seller_id']==o['sellerId'] and r['buyer_id']==o['buyerId'] and r['expires_at']==millis(o['expiresAt']),'Offer hash/index differs')
            check(millis(o['expiresAt'])-millis(o['createdAt'])==3600000,'Offer validity differs')
            status='SETTLED' if key in SALES[:count] else 'CANCELLED' if key=='cancelled' else 'OPEN'
            check(r['status']==status,'Offer status/restart boundary differs');by_id[o['offerId']]=r
        settlements=rows(db,'select * from starcore_nation_trade_settlements order by sequence_id')
        transfers=rows(db,'select * from starcore_resource_transfer_receipts order by sequence')
        check(len(settlements)==len(transfers)==count and int(treasury['transfer.count'])==count,'Native receipt count differs')
        resource_head=rows(db,'select * from starcore_resource_transfer_history')
        check(resource_head==[dict(state_id=1,authority_domain_id=head['resource_domain'],committed_sequence=count)],'Independent resource head differs')
        funding=[{k.split('.',2)[2]:v for k,v in treasury.items() if k.startswith(f'transfer.{i}.')} for i in range(count)]
        receipts=[]
        for i,(r,t,key) in enumerate(zip(settlements,transfers,SALES),1):
            o=offers[key];transaction='nation-trade:'+o['offerId'];value=Decimal(o['amount']*o['unitPriceMinor'])/100
            check(r['sequence_id']==r['resource_sequence']==i and r['authority_domain']==domain and r['offer_id']==o['offerId'] and r['offer_json']==by_id[o['offerId']]['offer_json'],'Settlement identity/terms differ')
            check(r['receipt_hash']==hashed(f'{domain}\n{i}\n{i}\n{r["committed_at"]}\n{r["offer_json"]}'),'Settlement payload hash differs')
            check(millis(o['createdAt'])<=r['committed_at']<millis(o['expiresAt']),'Settlement outside consent validity')
            check(t==dict(transaction_id=transaction,authority_domain_id=head['resource_domain'],sequence=i,payer_nation_id=seed['A'],receiver_nation_id=seed['B'],resources=f'agricultural={o["amount"]}',category='OTHER',description='National trade '+o['offerId'],applied_at=r['committed_at']),'Resource delivery receipt differs')
            f=[v for v in funding if v['transactionId']==transaction];check(len(f)==1,'Treasury payment receipt missing')
            f=f[0];check(f['payerId']==seed['B'] and f['receiverId']==seed['A'] and Decimal(f['amount'])==value and f['category']=='OTHER' and f['description']=='National trade '+o['offerId'] and int(f['appliedAt'])>=r['committed_at'],'Treasury payment receipt differs')
            receipts.append(dict(authorityDomain=domain,sequence=i,offer=o,resourceTransferSequence=i,committedAt=stamp(r['committed_at'])))
        quantity=sum(TERMS[k][1] for k in SALES[:count]);value=sum(Decimal(TERMS[k][1]*TERMS[k][2])/100 for k in SALES[:count])
        stocks={};balances={}
        for i in range(int(resource['count'])):
            p=f'stockpile.{i}.';key=(resource[p+'nationId'],resource[p+'type']);check(key not in stocks,'Duplicate stockpile');stocks[key]=int(resource[p+'amount'])
        for i in range(int(treasury['count'])):
            p=f'treasury.{i}.';key=treasury[p+'nationId'];check(key not in balances,'Duplicate treasury');balances[key]=Decimal(treasury[p+'balance'])
        check(stocks=={(seed['A'],'agricultural'):100-quantity,(seed['B'],'agricultural'):100+quantity,(seed['C'],'agricultural'):100},'National inventory conservation failed')
        check(balances=={seed['A']:Decimal(1000000)+value,seed['B']:Decimal(1000000)-value,seed['C']:Decimal(1000000)},'National treasury conservation failed')
        check(treasury['spending.count']==treasury['credit.count']=='0','Unexpected extra national debits/credits')
        return dict(head=head,receipts=receipts,offers=by_id,fundingTimes={v['transactionId']:int(v['appliedAt']) for v in funding},assets=dict(quantity=quantity,value=f'{value:.2f}',sellerResources=100-quantity,buyerResources=100+quantity,sellerTreasury=f'{1000000+value:.2f}',buyerTreasury=f'{1000000-value:.2f}'))

def event(receipt):
    o=receipt['offer'];d=receipt['authorityDomain'];n=receipt['sequence'];value=Decimal(o['amount']*o['unitPriceMinor'])/100
    facts={'title':'国家贸易已成交','time_basis':'COMMITTED_SOURCE','source.authority_domain':d,'source.sequence':str(n),'trade.offer_id':o['offerId'],'trade.seller_player':o['sellerId'],'trade.buyer_player':o['buyerId'],'trade.seller_nation':o['sellerNationId'],'trade.buyer_nation':o['buyerNationId'],'trade.resource':o['resourceId'],'trade.amount':str(o['amount']),'trade.unit_price_minor':str(o['unitPriceMinor']),'trade.total_value':f'{value:.2f}','trade.resource_domain':o['resourceDomain'],'trade.treasury_domain':o['treasuryDomain'],'trade.resource_sequence':str(receipt['resourceTransferSequence']),'trade.transaction_id':'nation-trade:'+o['offerId'],'trade.created_at':o['createdAt'],'trade.expires_at':o['expiresAt'],'trade.asset_atomicity':'NATIVE_SQL_TRANSACTION','trade.funding':'NATION_TREASURY'}
    evidence=f'Native national sale; authority={d}; sequence={n}; offer={o["offerId"]}; resource_domain={o["resourceDomain"]}; resource_sequence={receipt["resourceTransferSequence"]}; treasury_domain={o["treasuryDomain"]}; both national inventories, national treasury transfer, consent terms and settlement journal share one SQL commit; no external wallet payment or player inventory delivery'
    return dict(id=f'starcore:nation-trade:{d}:{n}',type='NATIONAL_TRADE_COMMITTED',occurredAt=receipt['committedAt'],participants=sorted([o['sellerId'],o['buyerId']]),nations=sorted([o['sellerNationId'],o['buyerNationId']]),facts=facts,source=ISSUER,evidence=evidence)

def canonical(e):return dict(e,participants=sorted(e['participants']),nations=sorted(e['nations']))
def chronicle(path,native_state):
    receipts=native_state['receipts'];count=len(receipts);domain=native_state['head']['authority_domain'];expected=[event(r) for r in receipts]
    with closing(connect(path)) as db:
        check(db.execute('pragma user_version').fetchone()[0]==8,'Chronicle schema differs')
        cursors=rows(db,'select * from committed_history_sources where source=?',(SOURCE,))
        check(cursors==[dict(source=SOURCE,authority_domain=domain,sequence=count)],'Trade source cursor differs')
        ingress=rows(db,'select * from committed_history_ingress where source=? order by sequence',(SOURCE,))
        history=rows(db,"select * from events where type='NATIONAL_TRADE_COMMITTED' or id like 'starcore:nation-trade:%' or json_extract(json,'$.source')=? order by id",(ISSUER,))
        contributions=rows(db,'select * from contributions order by id')
        check(len(ingress)==len(history)==count and len(contributions)==count*4,'Trade ingress/event/contribution count differs')
        result=[]
        for r,e,inbox in zip(receipts,expected,ingress):
            matches=[v for v in history if v['id']==e['id']];check(len(matches)==1,'Canonical trade event missing');row=matches[0]
            check(canonical(json.loads(row['json']))==e and row['type']==e['type'] and millis(row['time'])==millis(e['occurredAt']),'Stored trade event differs from native receipt')
            check(inbox['sequence']==r['sequence'] and inbox['status']=='ACKED' and inbox['event_id']==e['id'] and canonical(json.loads(inbox['json']))==e,'Trade ingress not acknowledged or changed')
            payload=dict(id=e['id'],type=e['type'],time=e['occurredAt'],players=e['participants'],nations=e['nations'],facts=e['facts'],source=e['source'],evidence=e['evidence']);raw=json.dumps(payload,ensure_ascii=False,separators=(',',':'),sort_keys=True)
            check(row['hash']==hashed(str(len(raw.encode()))+':'+raw),'Trade canonical event hash differs')
            check(sorted(v[0] for v in db.execute('select player from event_players where event_id=?',(e['id'],)))==e['participants'] and sorted(v[0] for v in db.execute('select nation from event_nations where event_id=?',(e['id'],)))==e['nations'],'Trade actor/nation projections differ')
            o=r['offer'];operation=f'nation-trade:{domain}:{o["offerId"]}'
            for side,actor,nation,amount,unit in [('SELLER',o['sellerId'],o['sellerNationId'],Decimal(o['amount']),'trade.resource:agricultural'),('BUYER',o['buyerId'],o['buyerNationId'],Decimal(o['amount']*o['unitPriceMinor'])/100,'trade.treasury')]:
                proof=f'Native national trade settlement; domain={domain}; sequence={r["sequence"]}; offer={o["offerId"]}; side={side}; resource_domain={o["resourceDomain"]}; resource_sequence={r["resourceTransferSequence"]}; treasury_domain={o["treasuryDomain"]}; national and player subjects are views of the same delivery, not additional turnover or rewards'
                for subject in ('nation:'+nation,'player:'+actor):
                    identity='chronicle:contribution:'+hashed(operation+'|TRADE|'+subject);found=[v for v in contributions if v['id']==identity];check(len(found)==1,'Missing exact trade contribution view');c=found[0]
                    request=dict(operationId=operation,type='TRADE',subjectId=subject,sourceEventId=e['id'],actorId=actor,nationId=nation,amount=int(amount) if amount==int(amount) else float(amount),unit=unit,committedAt=e['occurredAt'],evidence=proof)
                    actual=json.loads(c['request_json']);check(actual==request,'Contribution request differs from source')
                    columns={'operation_id':operation,'type':'TRADE','subject_id':subject,'source_event_id':e['id'],'actor_id':actor,'nation_id':nation,'unit':unit,'committed_at':e['occurredAt'],'evidence':proof}
                    check(all(c[k]==v for k,v in columns.items()) and Decimal(c['amount'])==amount and instant(c['recorded_at'])>=instant(e['occurredAt']),'Contribution columns/amount/time differ')
                    result.append(dict(id=identity,request=actual,recordedAt=c['recorded_at']))
        check(db.execute('select count(*) from epic_campaigns').fetchone()[0]==0,'Unexpected campaign generated by sale')
        return expected,sorted(result,key=lambda x:x['id'])

def commands(boot,offers):
    def accept(key,response='国家贸易操作已提交',who='B'):return (who,'/nationtrade accept '+offers[key]['offerId'],response)
    def offer(key):
        who,quantity,minor=TERMS[key];return (who,f'/nationtrade offer EpicB agricultural {quantity} {minor/100:.2f} 3600','报价已保存')
    if boot==1:return [offer('primary'),accept('primary','操作被拒绝','D'),accept('primary','操作被拒绝','C'),('B','/nationtrade show '+offers['primary']['offerId'],'国库总价: 50.00'),accept('primary'),accept('primary','没有重复成交'),offer('pending')]
    if boot==2:return [accept('pending'),offer('cancelled'),('A','/nationtrade cancel '+offers['cancelled']['offerId'],'国家贸易操作已提交'),accept('cancelled','操作被拒绝'),offer('old-founder'),accept('old-founder','操作被拒绝'),offer('successor')]
    return [accept('old-founder','操作被拒绝'),accept('cancelled','操作被拒绝'),accept('primary','没有重复成交'),accept('pending','没有重复成交'),accept('successor'),accept('successor','没有重复成交'),('A','/chronicle contributions','成交资源 agricultural'),('D','/chronicle contributions','成交资源 agricultural'),('B','/chronicle contributions','国库支付'),('D','/chronicle contributions nation me','成交资源 agricultural')]

def inspect(run):
    run=run.resolve();manifest=read(run/'preparation.json');contract=manifest['nation_trade_acceptance'];evidence=run/'evidence'
    check(contract==dict(scenarios=list(SCENARIOS),resource='agricultural',sales=3,quantity=29,total_value='75.00',contributions=12),'Unexpected sale acceptance contract')
    check(manifest['fixture_main_class']=='NationTradePaperFixture','Wrong fixture entry point')
    with socket.socket() as probe:
        probe.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1);probe.bind(('127.0.0.1',manifest['server_port']))
    for name,key in [('StarCore','starcore_sha256'),('StarChronicle','plugin_sha256'),('EpicCampaignPaperFixture','fixture_sha256')]:check(sha(run/f'plugins/{name}.jar')==manifest[key],'Frozen artifact changed')
    for name,digest in {'EpicCampaignPaperFixture.java':manifest['fixture_source_sha256'],**manifest['fixture_extra_sources_sha256']}.items():check(sha(run/'fixture'/name)==digest,'Frozen fixture source changed')
    paper={v for k,v in manifest['source_artifacts'].items() if Path(k).name=='paper.jar'};check(len(paper)==1 and sha(run/'paper.jar') in paper,'Frozen Paper changed')
    for artifact in manifest['production_stack']['artifacts']:check(sha(run/'plugins'/artifact['filename'])==artifact['sha256'],'Frozen dependency changed')
    fixture=records(evidence/'epic-fixture.jsonl');check(not any(r['kind']=='FAILED' for r in fixture),'Retained fixture failure')
    check({r['boot'] for r in fixture}=={1,2,3} and len(list(evidence.glob('boot*.session.json')))==3,'Missing complete sale boots')
    seed=next(r['data'] for r in fixture if r['kind']=='SEEDED');captured=[r for r in fixture if r['kind']=='SALE_OFFER']
    check([r['data']['key'] for r in captured]==list(TERMS),'Missing actual client-created offers')
    offers={r['data']['key']:r['data']['offer'] for r in captured};players=manifest['players']
    final=native(run/'plugins/StarCore/starcore.db',seed,players,3,offers);events,contributions=chronicle(run/'plugins/StarChronicle/chronicle.db',final)
    domain=final['head']['authority_domain'];previous=None;stack=None;telemetry=[];snapshots=[];command_count=0
    for boot,scenario in enumerate(SCENARIOS,1):
        session=read(evidence/f'boot{boot}.session.json')
        check(session['boot']==boot and session['scenario']==scenario and session['status']=='COMPLETE' and session['contributions'] is False,'Incomplete sale session')
        processes=session['processes'];check(len(processes)==2 and {p['name'] for p in processes}=={'server','clients'},'Missing actual server/client process')
        check(all(p.get('exit_code')==0 and p['exited_at']>=p['started_at'] for p in processes),'Abnormal process exit')
        server=next(p for p in processes if p['name']=='server');client=next(p for p in processes if p['name']=='clients')
        check(server['started_at']<client['started_at']<client['exited_at']<server['exited_at'],'Process chronology differs')
        if previous:check(previous['exited_at']<server['started_at'],'Overlapping sale boots')
        previous=server
        for name,suffix in [('controller','py'),('clients','cjs')]:check(sha(evidence/f'boot{boot}.{name}.{suffix}')==session['source_sha256'][name],'Captured runtime script changed')
        observed=[r for r in fixture if r['boot']==boot]
        for kind in ('READY','SEEDED','CLOSED','STACK_LOADED','SALE_BOUND'):check(sum(r['kind']==kind for r in observed)==1,'Missing boot lifecycle: '+kind)
        one=lambda kind:next(r['data'] for r in observed if r['kind']==kind)
        check(one('READY')==dict(product=manifest['plugin_sha256'],native=manifest['starcore_sha256'],route=manifest['route']) and one('SEEDED')==seed and seed['nonOp'] is True and one('CLOSED')==dict(normalDisable=True),'Live artifact/identity/permission mismatch')
        current=one('STACK_LOADED');check(set(current['plugins'])=={'StarCore','StarChronicle','FancyNpcs','WorldGuard','WorldEdit','Essentials','Vault'} and all(current['plugins'].values()) and current['businessEconomy']=='dev.starcore.starcore.foundation.economy.PlayerVaultEconomyService','Selected stack missing')
        if stack:check(current==stack,'Selected providers changed across restart')
        stack=current
        bound=one('SALE_BOUND');check(bound==dict(resourceDomain=final['head']['resource_domain'],treasuryDomain=final['head']['treasury_domain'],domain='' if boot==1 else domain,nonOp=True,actualService='dev.starcore.starcore.module.resource.SimpleNationTradeService'),'Trade provider binding differs')
        check(all(server['started_at']<=instant(r['time'])<=server['exited_at'] for r in observed),'Fixture observation outside actual server lifetime')
        log=(evidence/f'boot{boot}.server.log').read_text()
        for line in re.findall(r'^.*(?:ERROR\]|ERROR\]:|Failed to (?:enable|disable) STARCORE module|同步保存失败|异步保存失败|SQLITE_BUSY|startup failed|Could not pass event|Committed history source .* retained|History scan retained pending evidence).*$',log,re.M|re.I):
            check(EPIC.vendor_telemetry(line),'Server persistence/lifecycle diagnostic: '+line);telemetry.append(dict(boot=boot,message=line))
        check(all(s in log for s in ('Done (','Stopping server','All dimensions are saved')),'Incomplete normal server lifecycle')
        for kind in {r['kind'] for r in observed}:
            emitted=[json.loads(line.split('EPIC_'+kind+' ',1)[1]) for line in log.splitlines() if 'EPIC_'+kind+' ' in line]
            check(emitted==[r['data'] for r in observed if r['kind']==kind],'Fixture/server stream mismatch: '+kind)
        clients=records(evidence/f'boot{boot}.clients.log');check(not any(r['kind']=='FAILED' for r in clients),'Protocol client failure')
        spawns=[r['data'] for r in clients if r['kind']=='SPAWN'];check(len(spawns)==4 and {s['who'] for s in spawns}==set('ABCD') and all(s['uuid']==players[s['who']] for s in spawns),'Actual client identity mismatch')
        actual=[r for r in clients if r['kind']=='COMMAND_RESULT'];wanted=commands(boot,offers)
        check([(r['data']['who'],r['data']['command'],r['data']['contains']) for r in actual]==wanted,'Actual command sequence differs')
        for r in actual:
            check(r['data']['contains'] in r['data']['response'] and client['started_at']<=instant(r['time'])<=client['exited_at'],'Actual command response/lifetime differs')
            check(any(v['kind']=='MESSAGE' and v['data']['who']==r['data']['who'] and v['data']['message'] in r['data']['response'] for v in clients),'Command lacks real protocol message')
        command_count+=len(actual)
        checks=[r for r in observed if r['kind']=='SALE_CHECK'];check([r['data']['expected'] for r in checks]=={1:[0,0,1,1],2:[1,2,2],3:[2,3,3]}[boot],'Missing live trade checkpoint')
        for row in checks:
            d=row['data'];n=d['expected'];check(d['native']==final['receipts'][:n] and d['domain']==(domain if n or boot>1 else '' if row is checks[0] else domain),'Live native history differs')
            check([canonical(e) for e in d['events']]==events[:n],'Live history differs from SQL')
            check(sorted(d['contributions'],key=lambda c:c['id'])==sorted([c for c in contributions if c['request']['sourceEventId'] in {e['id'] for e in events[:n]}],key=lambda c:c['id']),'Live contribution differs from SQL')
            q=sum(TERMS[k][1] for k in SALES[:n]);v=sum(Decimal(TERMS[k][1]*TERMS[k][2])/100 for k in SALES[:n]);expected_assets=dict(quantity=q,value=v,sellerResources=100-q,buyerResources=100+q,sellerTreasury=Decimal(1000000)+v,buyerTreasury=Decimal(1000000)-v)
            check({k:Decimal(str(value)) for k,value in d['assets'].items()}==expected_assets,'Live assets differ from settled receipts')
        cp=evidence/f'checkpoints/boot{boot}';inventory=read(cp/'checkpoint.json')
        check(inventory['boot']==boot and set(inventory['sha256'])=={'chronicle.db','starcore.db'} and server['exited_at']<=inventory['time'],'Stopped checkpoint boundary differs')
        check({p.name for p in cp.iterdir() if p.is_file()}=={'checkpoint.json','chronicle.db','starcore.db'},'Unexpected checkpoint files')
        for name,digest in inventory['sha256'].items():
            check(sha(cp/name)==digest,'Stopped checkpoint changed')
            if boot==3:check(sha(run/'plugins'/('StarCore' if name=='starcore.db' else 'StarChronicle')/name)==digest,'Final runtime differs from checkpoint')
        state=native(cp/'starcore.db',seed,players,boot,offers);ev,cs=chronicle(cp/'chronicle.db',state)
        check(state['head']['authority_domain']==domain and state['receipts']==final['receipts'][:boot] and ev==events[:boot],'Native/event prefix changed across restart')
        check(cs==sorted([c for c in contributions if c['request']['sourceEventId'] in {e['id'] for e in ev}],key=lambda c:c['id']),'Contribution prefix changed across restart')
        snapshots.append(state)
    succession=[r for r in fixture if r['kind']=='SALE_SUCCESSOR'];check(len(succession)==1 and succession[0]['boot']==2 and succession[0]['data']==dict(nation=seed['A'],previous=players['A'],current=players['D'],publicNativeOperation=True),'Actual founder handover missing')
    for r in fixture:
        if r['kind']=='SALE_CHECK':check(r['data']['founder']==players['D' if instant(r['time'])>instant(succession[0]['time']) else 'A'],'Historical/current founder confused')
    for receipt,key,boot in zip(final['receipts'],SALES,(1,2,3)):
        cmds=[r for r in records(evidence/f'boot{boot}.clients.log') if r['kind']=='COMMAND_RESULT'];accepted=next(r for r in cmds if r['data']['command']=='/nationtrade accept '+offers[key]['offerId'] and r['data']['contains']=='国家贸易操作已提交')
        check(millis(offers[key]['createdAt'])<=millis(receipt['committedAt'])<=final['fundingTimes']['nation-trade:'+offers[key]['offerId']]<=millis(accepted['time']),'Settlement chronology differs from actual acceptance')
    return dict(status='SELECTED_PAPER_NATIONAL_TRADE_ACCEPTED',chronicle_sha256=manifest['plugin_sha256'],starcore_sha256=manifest['starcore_sha256'],boots=3,normal_process_exits=6,actual_command_results=command_count,settlements=3,resource_receipts=3,treasury_receipts=3,contributions=12,assets=final['assets'],authority_domain=domain,plugins=stack['plugins'],vendor_telemetry=telemetry,limitations=manifest['limitations'])

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('run',type=Path)
    print(json.dumps(inspect(p.parse_args().run),ensure_ascii=False,indent=2))
