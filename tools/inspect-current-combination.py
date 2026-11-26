"""Check schema10 source journals during the existing real five-plugin epic scenario."""
from pathlib import Path
import argparse
import hashlib
import importlib.util
import json
import re
import sqlite3


def module(name, filename):
    spec=importlib.util.spec_from_file_location(name,Path(__file__).with_name(filename))
    value=importlib.util.module_from_spec(spec); spec.loader.exec_module(value); return value


COMBINATION=module('combination','inspect-production-combination.py')
RESOURCE=module('resource','inspect-resource-history.py')
check,db=RESOURCE.check,RESOURCE.db


def inspect(run):
    run=Path(run).resolve(); result=COMBINATION.inspect(run)
    logs=[(run/f'evidence/boot{boot}.server.log').read_text() for boot in (1,2,3)]
    check(not any(re.search(r'History scan retained pending evidence|history scan retained its cursor|Source event was not accepted',s) for s in logs), 'Committed source failed during combination')
    pids=[]
    for boot in (1,2,3):
        pids.extend(p['pid'] for p in json.loads((run/f'evidence/boot{boot}.session.json').read_text())['processes'])
    check(len(set(pids))==6,'Combination reused a process identity')
    native=run/'plugins/StarCore'
    with db(native/'starcore.db') as c:
        state=dict(c.execute('SELECT property_key,property_value FROM starcore_resource_state'))
        transfers=c.execute('SELECT transaction_id,payer_nation_id,receiver_nation_id,resources FROM starcore_resource_transfer_receipts').fetchall()
    domain=state['resource.history.domain']; history=native/'resource/history'
    check(state['resource.history.sequence']=='5' and (history/'authority-domain').read_text()==domain and (history/'high-water').read_text()==domain+'|5','Resource authority/head changed')
    paths=list((history/domain).rglob('*.receipt'))
    check(sorted(int(p.stem) for p in paths)==list(range(1,6)),'Missing committed source receipt')
    receipts={int(p.stem):RESOURCE.decode(p.read_text()) for p in paths}
    check(RESOURCE.decode(state['resource.history.pending'])==receipts[5],'Pending resource receipt changed')
    balances={}; nations={}; expected_transfers=[]
    for seq in range(1,6):
        r=receipts[seq]
        check(r['sequence']==seq and r['authorityDomain']==domain and r['actorId'] is None,'Resource source identity changed')
        if seq<=3:
            who='ABC'[seq-1]
            check(r['kind']=='TRANSACTION_GRANT' and r['reference']=='epic-fixture-seed-'+who and len(r['balances'])==1,'Seed receipt changed')
            b=r['balances'][0]; nations[who]=b['nationId']; expected={(b['nationId'],'agricultural'):100}
        else:
            payer=nations['AB'[seq-4]]; receiver=nations['C']
            check(r['kind']=='TRANSFER' and len(r['balances'])==2,'Aid resource source changed')
            expected={(payer,'agricultural'):-10,(receiver,'agricultural'):10}
            expected_transfers.append((r['reference'],payer,receiver,'agricultural=10'))
        check({(b['nationId'],b['resourceType']):b['after']-b['before'] for b in r['balances']}==expected,'Resource source delta changed')
        for b in r['balances']:
            key=b['nationId'],b['resourceType']; check(b['before']==balances.get(key,0),'Resource receipt balance chain broke'); balances[key]=b['after']
    check(len(set(nations.values()))==3 and sorted(transfers)==sorted(expected_transfers),'Native transfer ledger disagrees with resource history')
    stockpile={(state[f'stockpile.{i}.nationId'],state[f'stockpile.{i}.type']):int(state[f'stockpile.{i}.amount']) for i in range(int(state['count']))}
    check(stockpile==balances and balances=={(nations[w],'agricultural'):(120 if w=='C' else 90) for w in 'ABC'},'Persisted resource inventory differs from history replay')
    processing_domain=state['processing.history.domain']
    check(state['processing.history.sequence']=='0' and 'processing.history.pending' not in state,'Aid invented a processing operation')
    check((native/'resource/processing-history/authority-domain').read_text()==processing_domain,'Processing authority marker changed')
    with db(native/'resource/processing-history/runs.sqlite') as c:
        check(c.execute('SELECT * FROM identity').fetchall()==[(1,processing_domain)] and c.execute('SELECT COUNT(*) FROM entries').fetchone()[0]==0,'Aid invented a native processing run')
    with db(run/'plugins/StarChronicle/chronicle.db') as c:
        check(c.execute('PRAGMA user_version').fetchone()[0]==10,'Current combination requires schema10')
        check(not c.execute('PRAGMA foreign_key_check').fetchall(),'Chronicle foreign-key failure')
        cursors={r[0]:r[1:] for r in c.execute('SELECT namespace,authority_domain,sequence,live FROM committed_history_sources')}
        check(set(cursors)=={'starcore.'+x for x in ('technology','capitals','trade','resources','processing')},'Missing committed source registration')
        check(cursors['starcore.resources']==(domain,5,1) and cursors['starcore.processing']==(processing_domain,0,1),'Committed source cursor changed')
        check(all(cursors['starcore.'+x][1:]==(0,1) for x in ('technology','capitals','trade')),'Unexpected other source operations')
        ingress=c.execute('SELECT id,sequence,status,activate,json FROM committed_history_ingress ORDER BY sequence').fetchall()
        check([r[1:4] for r in ingress]==[(s,'ACKED',int(s>3)) for s in range(1,6)],'Source ingress missing, unacknowledged or initial archive changed')
        full=c.execute('SELECT event_id,sequence,json FROM resource_history_receipts ORDER BY sequence').fetchall()
        staged=dict(c.execute('SELECT event_id,json FROM committed_resource_ingress'))
        check(len(full)==len(staged)==5,'Full resource payload missing')
        for seq,row in enumerate(full,1):
            r=receipts[seq]; expected_id=f'starcore:resource:{domain}:{r["operationId"]}'
            check(row[:2]==(expected_id,seq) and json.loads(row[2])==r and json.loads(staged[expected_id])==r,'Complete resource receipt changed')
            event=json.loads(c.execute('SELECT json FROM events WHERE id=?',(expected_id,)).fetchone()[0])
            check(event['occurredAt']==r['committedAt'] and RESOURCE.canonical_event(event)==RESOURCE.canonical_event(json.loads(ingress[seq-1][4])),'Source event differs from ingress or native time')
            check(event['facts']['resource.receipt_sha256']==hashlib.sha256(row[2].encode()).hexdigest() and event['facts']['contribution.omission']=='BALANCE_CHANGE_IS_NOT_PRODUCTION_OR_NEW_AID_OR_TRADE','Resource balance change duplicated production credit')
        for table in ('processing_history_receipts','committed_processing_ingress','trade_history_receipts'):
            check(c.execute('SELECT COUNT(*) FROM '+table).fetchone()[0]==0,'Scenario invented unrelated history: '+table)
        check(c.execute("SELECT type,COUNT(*) FROM contributions GROUP BY type").fetchall()==[('RESOURCE_AID',8)],'Aid was duplicated as production/trade')
    result.update(schema=10,resource_history=5,initial_resource_archive=3,resource_transfers=2,resource_contributions_added=0,processing_history=0,
                  sources_registered=5,source_pending=0,history_scope='Complete resource receipts during epic aid; factory production separately exercised in processing-history acceptance.')
    return result


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__); p.add_argument('run',type=Path); p.add_argument('--output',type=Path)
    a=p.parse_args(); result=inspect(a.run)
    if a.output: a.output.write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result))
