"""Independently check stopped SQL, Essentials files and actual three-boot player trade evidence."""
from contextlib import closing
from decimal import Decimal
from pathlib import Path
import argparse,hashlib,importlib.util,json,re,socket,uuid,yaml

SPEC=importlib.util.spec_from_file_location('trade_inspector',Path(__file__).with_name('inspect-nation-trade-paper.py'))
T=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(T)
check,read,sha,rows,connect=T.check,T.read,T.sha,T.rows,T.connect
SOURCE='starcore.player-trade.settlements.v1';ISSUER='StarCore.PlayerTradeRecoveryService'
SALES=('primary','pending','successor')
TERMS={'primary':('B',20,'2.5',100,200),'pending':('B',5,'4',40,80),'cancelled':('B',1,'1',2,40),'rejected':('B',2,'1',4,80),'successor':('D',4,'1.25',10,200)}
STATES=('OFFERED','RESERVED','DEBIT_INTENT','DEBIT_ACKNOWLEDGED','CREDIT_INTENT','CREDIT_ACKNOWLEDGED','SETTLED','PROJECTED')
def event(domain,n,s):
    value=Decimal(s['unitPrice'])*s['amount'];total=value+Decimal(s['feeMinor']+s['tariffMinor'])/100
    facts={'title':'玩家贸易已结算','time_basis':'COMMITTED_SOURCE','source.authority_domain':domain,'source.sequence':str(n),
        'trade.offer_id':s['id'],'trade.seller_player':s['seller'],'trade.buyer_player':s['buyer'],'trade.seller_nation':s['sellerNation'],'trade.buyer_nation':s['buyerNation'],
        'trade.resource_domain':s['resourceDomain'],'trade.treasury_domain':s['treasuryDomain'],'trade.provider':s['provider'],'trade.resource':s['resource'],'trade.amount':str(s['amount']),
        'trade.unit_price':s['unitPrice'],'trade.gross':f'{value:.2f}','trade.total':f'{total:.2f}','trade.fee_minor':str(s['feeMinor']),'trade.tariff_minor':str(s['tariffMinor']),
        'trade.created_at':s['createdAt'],'trade.expires_at':s['expiresAt'],'trade.accepted_at':s['acceptedAt'],'trade.asset_atomicity':'NATIVE_DELIVERY_AND_TAXES',
        'trade.wallet_evidence':'PROVIDER_ACKNOWLEDGED','trade.inventory':'NATIONAL_STOCKPILE'}
    proof=f'Native player trade settlement; domain={domain}; sequence={n}; offer={s["id"]}; provider={s["provider"]}; original actors and frozen terms; national inventory escrow delivered with fee/tariff treasury credits in one SQL commit; wallet calls acknowledged by provider, not a durable external payment receipt or a joint wallet SQL transaction'
    return dict(id=f'starcore:player-trade:{domain}:{n}',type='PLAYER_TRADE_SETTLED',occurredAt=s['settledAt'],participants=sorted([s['buyer'],s['seller']]),nations=sorted({s['buyerNation'],s['sellerNation']}),facts=facts,source=ISSUER,evidence=proof)
def native(path,seed,players,count,offers):
    with closing(connect(path)) as db:
        head=rows(db,'select * from starcore_player_trade_head');check(len(head)==1,'Missing player trade head');head=head[0]
        records=rows(db,'select * from starcore_player_trade_events order by event_sequence')
        check(head['event_sequence']==len(records),'Player trade journal tail missing')
        prefix='0'*64;groups={};settled=[]
        for index,row in enumerate(records,1):
            check(row['event_sequence']==index,'Player trade journal sequence gap')
            value=json.loads(row['snapshot_json']);check(T.compact(value)==row['snapshot_json'],'Noncanonical player trade entry')
            prefix=T.hashed(f'{head["authority_domain"]}\n{head["resource_domain"]}\n{index}\n{prefix}\n{row["snapshot_json"]}')
            check(prefix==row['entry_hash'],'Player trade journal hash changed')
            check(row['offer_id']==value['id'] and row['offer_version']==value['version'],'Player trade index differs')
            group=groups.setdefault(value['id'],[]);check(value['version']==len(group)+1,'Player trade version gap');group.append(value)
            if value['state']=='SETTLED':settled.append(value)
        check(prefix==head['prefix_hash'] and len(settled)==count,'Player trade committed head differs')
        keys=('primary','pending') if count==1 else tuple(TERMS)
        check(set(groups)=={offers[k]['offerId'] for k in keys},'Unexpected player offers')
        for key in keys:
            actor,amount,unit,fee,tariff=TERMS[key];captured=offers[key];values=groups[captured['offerId']];first=values[0]
            expected=dict(format=1,id=captured['offerId'],buyer=players['A'],seller=players[actor],buyerNation=seed['A'],sellerNation=seed['B'],resourceDomain=head['resource_domain'],treasuryDomain=captured['treasuryDomain'],provider=captured['provider'],resource='agricultural',amount=amount,unitPrice=unit,feeMinor=fee,tariffMinor=tariff,createdAt=captured['createdAt'],expiresAt=captured['expiresAt'])
            check(all(all(v[k]==x for k,x in expected.items()) for v in values),'Frozen player terms changed')
            check(T.millis(first['expiresAt'])-T.millis(first['createdAt'])==3600000,'Player offer validity changed')
            wanted=list(STATES) if key in SALES[:count] else ['OFFERED','CANCELLED'] if key=='cancelled' else ['OFFERED','REJECTED'] if key=='rejected' else ['OFFERED']
            check([v['state'] for v in values]==wanted,'Player trade state path differs')
            if len(values)==8:
                check(values[1]['acceptedAt']==values[1]['changedAt'] and values[6]['settledAt']==values[6]['changedAt'],'Acceptance/settlement time differs')
                check(all(v.get('acceptedAt')==values[1]['acceptedAt'] for v in values[1:]) and values[7]['settledAt']==values[6]['settledAt'],'Committed trade time changed')
                check(T.millis(first['createdAt'])<=T.millis(values[1]['acceptedAt'])<T.millis(first['expiresAt']) and T.millis(values[6]['settledAt'])>=T.millis(values[1]['acceptedAt']),'Trade chronology differs')
        check([s['id'] for s in settled]==[offers[k]['offerId'] for k in SALES[:count]],'Settlement order differs')
        resources=dict(db.execute('select property_key,property_value from starcore_resource_state'));treasury=dict(db.execute('select property_key,property_value from starcore_treasury_state'))
        nations=dict(db.execute('select property_key,property_value from starcore_nation_state'))
        for who in 'ABC':
            prefix=next(k[:-2] for k,v in nations.items() if k.endswith('.id') and '.member.' not in k and v==seed[who])
            check(nations[prefix+'founderId']==players['D' if who=='B' and count>=2 else who],'Persisted player trade founder differs')
            members={nations[prefix+f'member.{i}.id'] for i in range(int(nations[prefix+'memberCount']))}
            expected_members='AD' if who=='A' and count==1 else 'BD' if who=='B' and count>=2 else who
            check(members=={players[w] for w in expected_members},'Persisted player trade membership differs')
        quantity=sum(s['amount'] for s in settled);gross=sum((Decimal(s['unitPrice'])*s['amount'] for s in settled),Decimal(0));tax=sum((Decimal(s['feeMinor']+s['tariffMinor'])/100 for s in settled),Decimal(0))
        stocks={(resources[f'stockpile.{i}.nationId'],resources[f'stockpile.{i}.type']):int(resources[f'stockpile.{i}.amount']) for i in range(int(resources['count']))}
        check(stocks=={(seed['A'],'agricultural'):100+quantity,(seed['B'],'agricultural'):100-quantity,(seed['C'],'agricultural'):100},'Player trade inventory conservation failed')
        money={treasury[f'treasury.{i}.nationId']:Decimal(treasury[f'treasury.{i}.balance']) for i in range(int(treasury['count']))}
        check(money=={seed['A']:Decimal(1000000)+tax,seed['B']:Decimal(1000000),seed['C']:Decimal(1000000)},'Player trade treasury conservation failed')
        check(resources['resource.authority-domain']==head['resource_domain'] and int(treasury['credit.count'])==count*2,'Asset domain/credit count differs')
        credits={treasury[f'credit.{i}.transactionId']:{k.split('.',2)[2]:v for k,v in treasury.items() if k.startswith(f'credit.{i}.')} for i in range(count*2)}
        for s in settled:
            check(treasury['authorityDomainId']==s['treasuryDomain'],'Treasury authority changed')
            for kind in ('fee','tariff'):
                id='player-trade:'+s['id']+':'+kind
                wanted=dict(transactionId=id,nationId=seed['A'],amount=f'{Decimal(s[kind+"Minor"])/100:.2f}',category='OTHER',description='Player trade '+kind+' '+s['id'],appliedAt=str(T.millis(s['settledAt'])))
                check(credits.get(id)==wanted,'Player tax receipt changed or missing')
        history=rows(db,'select * from trade_history');business=rows(db,'select * from business_transactions')
        check(len(history)==count and len(business)==count*2,'Trade/business projection count differs')
        for s in settled:
            h=next(v for v in history if v['record_id']==s['id']);value=Decimal(s['unitPrice'])*s['amount']
            check(h['buyer_funded']==1 and h['buyer_player_id']==s['buyer'] and h['seller_player_id']==s['seller'] and Decimal(str(h['net_value']))==value and h['status']=='COMPLETED' and h['executed_at']==T.millis(s['settledAt']),'Player trade history projection differs')
            for side,actor,nation in [('PURCHASE',s['buyer'],s['buyerNation']),('SALE',s['seller'],s['sellerNation'])]:
                id=str(uuid.UUID(bytes=hashlib.md5(('player-trade:'+s['id']+':'+side).encode()).digest(),version=3))
                b=next((v for v in business if v['transaction_id']==id),None)
                check(b is not None and b['player_id']==actor and b['nation_id']==nation and b['type']==side and b['category']=='RESOURCE' and Decimal(b['amount'])==value and b['timestamp']==T.millis(s['settledAt']),'Player business projection differs')
        history_head=rows(db,'select * from trade_history_journal_meta')
        changes=rows(db,'select * from trade_history_changes order by sequence_id')
        check(len(history_head)==1 and history_head[0]['last_sequence']==count and len(changes)==count,'Generic native trade journal differs')
        for n,(change,s) in enumerate(zip(changes,settled),1):
            r=json.loads(change['record_json'])
            check(change['sequence_id']==n and change['change_kind']=='UPSERT' and r['format']==2 and r['recordId']==s['id'] and r['executedAt']==s['settledAt'],'Generic native trade snapshot differs')
        return dict(domain=head['authority_domain'],settled=settled,quantity=quantity,gross=gross,tax=tax,total=gross+tax,history_domain=history_head[0]['authority_domain'],history_changes=changes)

def generic_history(db,state):
    source='starcore.trade.history.v1';domain=state['history_domain'];count=len(state['settled'])
    check(rows(db,'select * from committed_history_sources where source=?',(source,))==[dict(source=source,authority_domain=domain,sequence=count)],'Generic history cursor differs')
    inbox=rows(db,'select * from committed_history_ingress where source=? order by sequence',(source,))
    events=rows(db,"select * from events where id like 'starcore:trade-history:%' order by id")
    check(len(inbox)==len(events)==count,'Generic history ingress/event count differs')
    check(not rows(db,"select * from contributions where source_event_id like 'starcore:trade-history:%'"),'Generic history generated extra contributions')
    for n,(change,s) in enumerate(zip(state['history_changes'],state['settled']),1):
        record=json.loads(change['record_json']);gross=Decimal(s['unitPrice'])*s['amount'];tax=Decimal(s['feeMinor']+s['tariffMinor'])/100
        facts={'title':'贸易历史记录更新','time_basis':'JOURNAL_RECORDED','source.authority_domain':domain,'source.sequence':str(n),'source.change':'UPSERT',
            'trade.record_id':s['id'],'trade.seller_player':s['seller'],'trade.buyer_player':s['buyer'],'trade.seller_nation':s['sellerNation'],'trade.buyer_nation':s['buyerNation'],
            'trade.resource':s['resource'],'trade.amount':str(s['amount']),'trade.price_per_unit':str(float(s['unitPrice'])),'trade.total_value':str(float(gross)),
            'trade.net_value':str(float(gross)),'trade.tax_amount':str(float(tax)),'trade.tax_payer':'BUYER','trade.status':'COMPLETED','trade.order_type':'BUY','trade.order_source':'PLAYER',
            'trade.executed_at':s['settledAt'],'trade.journal_recorded_at':T.stamp(change['recorded_at']),'trade.asset_atomicity':'NOT_PROVEN_BY_HISTORY_JOURNAL',
            'trade.notes_sha256':T.hashed(str(len(record['notes'].encode()))+':'+record['notes']),'trade.notes_length':str(len(record['notes']))}
        proof=f'Native TradeHistoryService journal; domain={domain}; sequence={n}; kind=UPSERT; record={s["id"]}; executedAt={s["settledAt"]}; recordedAt={T.stamp(change["recorded_at"])}; journal and history row share a commit; external assets do not; BASELINE is surviving state, DELETE is maintenance, REFUNDED status does not prove refund completion'
        expected=dict(id=f'starcore:trade-history:{domain}:{n}',type='TRADE_HISTORY_RECORDED',occurredAt=T.stamp(change['recorded_at']),participants=sorted([s['buyer'],s['seller']]),nations=sorted({s['buyerNation'],s['sellerNation']}),facts=facts,source='StarCore.TradeHistoryService',evidence=proof)
        row=next(v for v in events if v['id']==expected['id']);ev=json.loads(row['json']);entry=inbox[n-1]
        check(entry['sequence']==n and entry['status']=='ACKED' and entry['event_id']==expected['id'] and T.canonical(json.loads(entry['json']))==expected,'Generic history ingress differs')
        check(T.canonical(ev)==expected and row['type']==expected['type'] and T.millis(row['time'])==change['recorded_at'],'Generic history event differs')
        raw=json.dumps(dict(id=ev['id'],type=ev['type'],time=ev['occurredAt'],players=expected['participants'],nations=expected['nations'],facts=ev['facts'],source=ev['source'],evidence=ev['evidence']),ensure_ascii=False,separators=(',',':'),sort_keys=True)
        check(row['hash']==T.hashed(str(len(raw.encode()))+':'+raw),'Generic history hash differs')

def chronicle(path,state):
    with closing(connect(path)) as db:
        settled=state['settled'];domain=state['domain'];count=len(settled)
        generic_history(db,state)
        check(rows(db,'select * from committed_history_sources where source=?',(SOURCE,))==[dict(source=SOURCE,authority_domain=domain,sequence=count)],'Player trade source cursor differs')
        ingress=rows(db,'select * from committed_history_ingress where source=? order by sequence',(SOURCE,))
        history=rows(db,"select * from events where type='PLAYER_TRADE_SETTLED' or id like 'starcore:player-trade:%'")
        contributions=rows(db,"select * from contributions where source_event_id like 'starcore:player-trade:%' order by id")
        check(len(ingress)==len(history)==count and len(contributions)==count*4,'Player trade ingress/event/contribution count differs')
        for n,s in enumerate(settled,1):
            event_id=f'starcore:player-trade:{domain}:{n}';stored=next(h for h in history if h['id']==event_id);ev=json.loads(stored['json']);inbox=ingress[n-1]
            check(inbox['sequence']==n and inbox['status']=='ACKED' and inbox['event_id']==event_id and T.canonical(json.loads(inbox['json']))==T.canonical(ev),'Player trade ingress changed')
            check(T.canonical(ev)==event(domain,n,s) and stored['type']=='PLAYER_TRADE_SETTLED' and T.millis(stored['time'])==T.millis(s['settledAt']),'Player trade canonical event differs')
            canon=T.canonical(ev);raw=json.dumps(dict(id=ev['id'],type=ev['type'],time=ev['occurredAt'],players=canon['participants'],nations=canon['nations'],facts=ev['facts'],source=ev['source'],evidence=ev['evidence']),ensure_ascii=False,separators=(',',':'),sort_keys=True)
            check(stored['hash']==T.hashed(str(len(raw.encode()))+':'+raw),'Player trade event hash differs')
            f=ev['facts'];value=Decimal(s['unitPrice'])*s['amount'];total=value+Decimal(s['feeMinor']+s['tariffMinor'])/100
            wanted={'source.authority_domain':domain,'source.sequence':str(n),'trade.offer_id':s['id'],'trade.provider':s['provider'],'trade.resource':'agricultural','trade.amount':str(s['amount']),'trade.gross':f'{value:.2f}','trade.total':f'{total:.2f}','trade.fee_minor':str(s['feeMinor']),'trade.tariff_minor':str(s['tariffMinor']),'trade.wallet_evidence':'PROVIDER_ACKNOWLEDGED','trade.asset_atomicity':'NATIVE_DELIVERY_AND_TAXES','trade.inventory':'NATIONAL_STOCKPILE'}
            check(all(f.get(k)==v for k,v in wanted.items()),'Player trade source facts differ')
            for side,actor,nation,amount,unit in [('SELLER',s['seller'],s['sellerNation'],Decimal(s['amount']),'trade.player-resource:agricultural'),('BUYER',s['buyer'],s['buyerNation'],total,'trade.wallet-acknowledged')]:
                operation=f'player-trade:{domain}:{s["id"]}:{side}'
                for subject in ('player:'+actor,'nation:'+nation):
                    identity='chronicle:contribution:'+T.hashed(operation+'|TRADE|'+subject)
                    c=next((c for c in contributions if c['id']==identity),None);check(c is not None,'Missing exact player contribution view')
                    req=json.loads(c['request_json'])
                    for column,json_key,wanted_value in [('operation_id','operationId',operation),('subject_id','subjectId',subject),('source_event_id','sourceEventId',event_id),('actor_id','actorId',actor),('nation_id','nationId',nation),('unit','unit',unit),('committed_at','committedAt',s['settledAt'])]:
                        check(c[column]==req[json_key]==wanted_value,'Player contribution subject/source changed')
                    check(Decimal(c['amount'])==Decimal(str(req['amount']))==amount,'Player contribution amount differs')
                    proof=f'Native player trade settlement; domain={domain}; sequence={n}; offer={s["id"]}; side={side}; resource_domain={s["resourceDomain"]}; treasury_domain={s["treasuryDomain"]}; wallet evidence is provider acknowledgement, not external payment durability; player/nation are views, not extra turnover or rewards'
                    check(T.millis(c['recorded_at'])>=T.millis(s['settledAt']) and c['evidence']==req['evidence']==proof and c['type']==req['type']=='TRADE','Player contribution evidence overstated')
        return contributions

def command_sequence(boot,offers):
    def command(who,tail,text):return (who,'/trade '+tail,text)
    def offer(key):
        who,amount,unit,*_=TERMS[key]
        return command('A',f'offer Epic{who} agricultural {amount} {Decimal(unit):.2f} 3600','交易请求已发送给')
    def accept(key,who='B',text='交易已结算'):return command(who,'accept '+offers[key]['offerId'],text)
    def status(key,who,text):return command(who,'status '+offers[key]['offerId'],text)
    if boot==1:return [offer('primary'),accept('primary','C','仅原卖方'),accept('primary','D','仅原卖方'),status('primary','C','找不到你参与的交易'),status('primary','B','买方费用: 3.00'),accept('primary'),accept('primary'),offer('pending')]
    if boot==2:return [status('pending','B','买方费用: 1.20'),accept('pending'),offer('cancelled'),command('A','cancel '+offers['cancelled']['offerId'],'交易请求已取消'),accept('cancelled',text='报价已关闭'),offer('rejected'),command('B','reject '+offers['rejected']['offerId'],'已拒绝交易请求'),accept('rejected',text='报价已关闭'),accept('primary'),accept('primary','D','仅原卖方'),offer('successor')]
    return [accept('primary'),accept('pending'),accept('primary','D','仅原卖方'),accept('successor','D'),accept('successor','D'),command('B','resume '+offers['primary']['offerId'],'交易已结算'),command('A','resume '+offers['primary']['offerId'],'仅原卖方'),('B','/chronicle contributions','玩家贸易交付 agricultural'),('D','/chronicle contributions','玩家贸易交付 agricultural'),('A','/chronicle contributions','钱包付款（提供者已确认）'),('D','/chronicle contributions nation me','玩家贸易交付 agricultural')]

def inspect(run):
    run=run.resolve();manifest=read(run/'preparation.json');evidence=run/'evidence'
    check(manifest['fixture_main_class']=='PlayerTradePaperFixture','Wrong player trade fixture')
    with socket.socket() as probe:probe.bind(('127.0.0.1',manifest['server_port']))
    for name,key in [('StarCore','starcore_sha256'),('StarChronicle','plugin_sha256'),('EpicCampaignPaperFixture','fixture_sha256')]:check(sha(run/'plugins'/f'{name}.jar')==manifest[key],'Frozen artifact changed')
    for name,digest in manifest['fixture_extra_sources_sha256'].items():check(sha(run/'fixture'/name)==digest,'Frozen fixture source changed')
    for vendor in manifest['production_stack']['artifacts']:check(sha(run/'plugins'/vendor['filename'])==vendor['sha256'],'Frozen vendor changed')
    fixture=T.records(evidence/'epic-fixture.jsonl');check(not any(r['kind']=='FAILED' for r in fixture),'Retained player trade fixture failure')
    check(len(list(evidence.glob('boot*.session.json')))==3,'Require three player trade boots')
    seed=next(r['data'] for r in fixture if r['kind']=='SEEDED');players=manifest['players'];captured=[r['data'] for r in fixture if r['kind']=='PLAYER_TRADE_OFFER']
    check([r['key'] for r in captured]==list(TERMS),'Actual player offer capture differs');offers={r['key']:r['offer'] for r in captured}
    prior=None;states=[];previous_contributions=[];commands=[]
    for boot in (1,2,3):
        session=read(evidence/f'boot{boot}.session.json');check(session['status']=='COMPLETE' and session['scenario']=='player-trade-'+('first','second','third')[boot-1],'Incomplete player trade session')
        processes=session['processes'];check(len(processes)==2 and {p['name'] for p in processes}=={'server','clients'} and all(p.get('exit_code')==0 for p in processes),'Abnormal player trade process exit')
        server=next(p for p in processes if p['name']=='server');client=next(p for p in processes if p['name']=='clients')
        check(server['started_at']<client['started_at']<client['exited_at']<server['exited_at'] and (prior is None or prior<server['started_at']),'Player trade process chronology differs');prior=server['exited_at']
        for name,suffix in [('controller','py'),('clients','cjs')]:check(sha(evidence/f'boot{boot}.{name}.{suffix}')==session['source_sha256'][name],'Captured runtime script changed')
        observed=[r for r in fixture if r['boot']==boot]
        for kind in ('READY','SEEDED','CLOSED','STACK_LOADED','PLAYER_TRADE_BOUND'):check(sum(r['kind']==kind for r in observed)==1,'Player trade lifecycle evidence missing')
        ready=next(r['data'] for r in observed if r['kind']=='READY');check(ready['product']==manifest['plugin_sha256'] and ready['native']==manifest['starcore_sha256'] and seed['nonOp'],'Live player trade artifact/permission differs')
        bound=next(r['data'] for r in observed if r['kind']=='PLAYER_TRADE_BOUND');check(bound['nonOp'] and bound['provider']==offers['primary']['provider'],'Live wallet provider changed')
        stack=next(r['data'] for r in observed if r['kind']=='STACK_LOADED');check(set(stack['plugins'])=={'StarCore','StarChronicle','FancyNpcs','WorldGuard','WorldEdit','Essentials','Vault'} and stack['businessEconomy']=='dev.starcore.starcore.foundation.economy.PlayerVaultEconomyService','Selected player trade stack differs')
        live=[r['data'] for r in observed if r['kind']=='PLAYER_TRADE_CHECK'];check([r['expected'] for r in live]=={1:[0,1,1],2:[1,2,2],3:[2,3,3]}[boot],'Live player trade checkpoints missing')
        for view in live:
            n=view['expected'];selected=SALES[:n];quantity=sum(TERMS[k][1] for k in selected);tax=sum((Decimal(TERMS[k][3]+TERMS[k][4])/100 for k in selected),Decimal(0));total=sum((Decimal(TERMS[k][1])*Decimal(TERMS[k][2]) for k in selected),Decimal(0))+tax
            check(view['quantity']==quantity and view['buyerResources']==100+quantity and view['sellerResources']==100-quantity and Decimal(view['buyerTreasury'])==Decimal(1000000)+tax and Decimal(view['total'])==total and Decimal(view['tax'])==tax,'Live player trade assets differ')
            check(len(view['receipts'])==len(view['events'])==len(view['historyEvents'])==n and len(view['contributions'])==4*n,'Live player trade projection count differs')
            check(all(h['facts'].get('trade.tax_payer')=='BUYER' and h['facts'].get('trade.asset_atomicity')=='NOT_PROVEN_BY_HISTORY_JOURNAL' for h in view['historyEvents']),'Live generic trade semantics differ')
            for who in ('A','B','D'):
                wanted=Decimal(1000)-total if who=='A' else Decimal(10)+sum((Decimal(TERMS[k][1])*Decimal(TERMS[k][2]) for k in selected if TERMS[k][0]==who),Decimal(0))
                check(Decimal(view['wallet'][who])==wanted,'Live Essentials balance differs')
        log=(evidence/f'boot{boot}.server.log').read_text();check(all(s in log for s in ('Done (','Stopping server','All dimensions are saved')),'Incomplete normal server lifecycle')
        for line in re.findall(r'^.*(?:ERROR\]|ERROR\]:|Failed to (?:enable|disable) STARCORE module|同步保存失败|异步保存失败|SQLITE_BUSY|startup failed|Could not pass event|Committed history source .* retained|History scan retained pending evidence).*$',log,re.M|re.I):check(T.EPIC.vendor_telemetry(line),'Player trade server diagnostic: '+line)
        for kind in {r['kind'] for r in observed}:
            emitted=[json.loads(line.split('EPIC_'+kind+' ',1)[1]) for line in log.splitlines() if 'EPIC_'+kind+' ' in line]
            check(emitted==[r['data'] for r in observed if r['kind']==kind],'Player fixture/server stream mismatch')
        clients=T.records(evidence/f'boot{boot}.clients.log');check(not any(r['kind']=='FAILED' for r in clients),'Player client failure')
        spawns=[r['data'] for r in clients if r['kind']=='SPAWN'];check(len(spawns)==4 and {r['who']:r['uuid'] for r in spawns}==players,'Actual client identities differ')
        command_rows=[r for r in clients if r['kind']=='COMMAND_RESULT'];actual=[r['data'] for r in command_rows]
        check([(c['who'],c['command'],c['contains']) for c in actual]==command_sequence(boot,offers),'Actual player command sequence differs')
        for row in command_rows:
            c=row['data'];check(c['contains'] in c['response'] and client['started_at']<=T.instant(row['time'])<=client['exited_at'],'Actual command response missing')
            check(any(v['kind']=='MESSAGE' and v['data']['who']==c['who'] and c['contains'] in v['data']['message'] and v['data']['message'] in c['response'] for v in clients),'Command lacks actual protocol message')
        commands+=actual
        cp=evidence/f'checkpoints/boot{boot}';inventory=read(cp/'checkpoint.json')
        wanted={'starcore.db','chronicle.db',*('Essentials/'+players[w]+'.yml' for w in ('A','B','D'))}
        check(inventory['boot']==boot and inventory['time']>=server['exited_at'] and set(inventory['sha256'])==wanted,'Player checkpoint boundary differs')
        for name,digest in inventory['sha256'].items():check(sha(cp/name)==digest,'Stopped player checkpoint changed')
        state=native(cp/'starcore.db',seed,players,boot,offers);cs=chronicle(cp/'chronicle.db',state)
        if states:check(state['domain']==states[0]['domain'] and state['settled'][:boot-1]==states[-1]['settled'],'Player receipt prefix changed across restart')
        check(all(c in cs for c in previous_contributions),'Player contribution prefix changed across restart');previous_contributions=cs
        for who in ('A','B','D'):
            expected=Decimal(1000)-state['total'] if who=='A' else Decimal(10)+sum((Decimal(s['unitPrice'])*s['amount'] for s in state['settled'] if s['seller']==players[who]),Decimal(0))
            wallet=yaml.safe_load((cp/'Essentials'/(players[who]+'.yml')).read_text());check(Decimal(str(wallet['money']))==expected,'Persisted Essentials balance differs')
        if boot==3:
            for plugin,name in [('StarCore','starcore.db'),('StarChronicle','chronicle.db')]:check(sha(run/'plugins'/plugin/name)==inventory['sha256'][name],'Final runtime differs from stopped checkpoint')
        states.append(state)
    for key,who in zip(SALES,('B','B','D')):
        check(sum(c['who']==who and c['command']=='/trade accept '+offers[key]['offerId'] and c['contains']=='交易已结算' for c in commands)>=2,'Missing actual accept and replay')
    for who in 'CD':check(any(c['who']==who and c['command']=='/trade accept '+offers['primary']['offerId'] and c['contains']=='仅原卖方' for c in commands),'Missing nonparticipant rejection')
    check(any(c['command']=='/trade status '+offers['pending']['offerId'] and c['contains']=='买方费用: 1.20' for c in commands),'Frozen fee query missing')
    check(any(c['kind']=='PLAYER_TRADE_SUCCESSOR' for c in fixture),'Founder succession missing')
    check(any(c['who']=='A' and c['command']=='/trade resume '+offers['primary']['offerId'] and c['contains']=='仅原卖方' for c in commands),'Buyer resume authorization missing')
    final=states[-1]
    return dict(status='SELECTED_PAPER_PLAYER_TRADE_ACCEPTED',chronicle_sha256=manifest['plugin_sha256'],starcore_sha256=manifest['starcore_sha256'],boots=3,normal_process_exits=6,actual_commands=len(commands),settlements=3,contributions=12,quantity=final['quantity'],gross=f'{final["gross"]:.2f}',tax=f'{final["tax"]:.2f}',buyer_total=f'{final["total"]:.2f}',authority_domain=final['domain'],limitations=manifest['limitations'])

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('run',type=Path);print(json.dumps(inspect(p.parse_args().run),ensure_ascii=False,indent=2))
