"""Independently replay factory inputs/outputs and verify three boots, original actors and SQL."""
from pathlib import Path
from datetime import datetime, timezone
import argparse
import hashlib
import importlib.util
import json
import re
import sqlite3
import uuid

spec = importlib.util.spec_from_file_location('resource_inspector', Path(__file__).with_name('inspect-resource-history.py'))
resource = importlib.util.module_from_spec(spec)
spec.loader.exec_module(resource)
check, sha, db = resource.check, resource.sha, resource.db


def offline_id(name):
    return str(uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:' + name).encode()).digest(), version=3))


def nanos(value):
    match = re.fullmatch(r'(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.(\d{1,9}))?Z', value)
    check(match, 'Invalid precise Instant')
    return int(datetime.fromisoformat(match[1]).replace(tzinfo=timezone.utc).timestamp()) * 10**9 + int((match[2] or '').ljust(9, '0'))


def decode_run(raw):
    p = raw.split('|')
    check(len(p) == 12 and p[0] == '1', 'Invalid native frozen run')
    def amounts(value):
        pairs = [x.split(':') for x in value.split(',')]
        result = {k: int(v) for k, v in pairs}
        check(len(result) == len(pairs) and all(0 < v < 2**63 for v in result.values()), 'Invalid or repeated resource quantity')
        return result
    # Keep exact nanosecond strings, never round via Python datetime.
    return dict(runId=p[1], factoryId=None if p[2] == '-' else p[2], nationId=p[3], actorId=None if p[4] == '-' else p[4],
                recipeId=p[5], batches=int(p[6]), inputs=amounts(p[7]), outputs=amounts(p[8]), startedAt=p[9], readyAt=p[10],
                completedAt=None if p[11] == '-' else p[11])


def decode(raw):
    p = raw.split('|', 4)
    check(len(p) == 5, 'Invalid native processing receipt')
    return dict(authorityDomain=p[0], sequence=int(p[1]), kind=p[2], committedAt=p[3], run=decode_run(p[4]))


def event_id(receipt):
    return f'starcore:processing:{receipt["authorityDomain"]}:{receipt["run"]["runId"]}:{receipt["kind"]}'


def expected_run(seq, nation):
    if seq == 1:
        name, who, batches, kind, recipe, duration = 'Baseline', None, 1, 'AUTOMATIC', 'acceptance-smelt', 0
    elif 5 <= seq <= 134:
        name, who, batches, kind, recipe, duration = 'OfflineBatch' + str(seq - 5), None, 1, 'AUTOMATIC', 'acceptance-smelt', 0
    else:
        name, who, batches, kind, recipe, duration = {
            2: ('ProductionFirst', 'A', 2, 'STARTED', 'acceptance-smelt', 4),
            3: ('ProductionFirst', 'A', 2, 'COMPLETED', 'acceptance-smelt', 4),
            4: ('ProductionRestart', 'B', 1, 'STARTED', 'acceptance-long', 30),
            135: ('ProductionRestart', 'B', 1, 'COMPLETED', 'acceptance-long', 30),
            136: ('ProductionLast', 'A', 1, 'STARTED', 'acceptance-smelt', 2),
            137: ('ProductionLast', 'A', 1, 'COMPLETED', 'acceptance-smelt', 2),
        }[seq]
    return kind, duration, dict(runId=offline_id(name), factoryId=offline_id('FactoryOne') if who else None, nationId=nation,
                               actorId=offline_id('Produce' + who) if who else None, recipeId=recipe, batches=batches,
                               inputs={'energy': batches, 'mineral': 3*batches}, outputs={'chemical': batches, 'industrial': 2*batches})


def requests(records):
    result = []
    for r in records:
        if r['kind'] != 'COMPLETED':
            continue
        x = r['run']
        for unit, amount in x['outputs'].items():
            for subject in ('player:' + x['actorId'], 'nation:' + x['nationId']):
                result.append(dict(operationId=f'starcore:production:{r["authorityDomain"]}:{x["runId"]}:{unit}',
                    type='CIVILIZATION', subjectId=subject, sourceEventId=event_id(r), actorId=x['actorId'], nationId=x['nationId'],
                    amount=amount, unit=unit, committedAt=r['committedAt'],
                    evidence=f'Exact factory output; run={x["runId"]}; recipe={x["recipeId"]}; batches={x["batches"]}; original actor and nation from atomic start; authority={r["authorityDomain"]}; sequence={r["sequence"]}'))
    return result


def canonical(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(',', ':'))


def stable(*parts):
    return hashlib.sha256(b''.join(str(len(p.encode())).encode()+b':'+p.encode() for p in parts)).hexdigest()


def journal(data, state, prefix, decoder, head):
    history = data / 'StarCore/resource' / ('processing-history' if prefix == 'processing' else 'history')
    domain = state[prefix + '.history.domain']
    check(state[prefix + '.history.sequence'] == str(head), 'Native committed head changed')
    check((history / 'authority-domain').read_text() == domain, 'Native authority marker changed')
    check((history / 'high-water').read_text() == f'{domain}|{head}', 'Native high-water changed')
    paths = list((history / domain).rglob('*.receipt'))
    check(sorted(int(p.stem) for p in paths) == list(range(1, head+1)), 'Missing native immutable receipt')
    records = {int(p.stem): decoder(p.read_text()) for p in paths}
    check(decoder(state[prefix + '.history.pending']) == records[head], 'Native pending receipt changed')
    check(all(r['sequence'] == s and r['authorityDomain'] == domain for s, r in records.items()), 'Native receipt identity changed')
    return domain, records


def verify_commands(boot, fixture, client_rows, targets):
    f, first, active, last = [targets[k] for k in ('factory', 'first', 'long', 'last')]
    commands = {
        1: [('A', f'build {f} SMELTER NativeFactory', '工厂已保存'),
            ('B', f'start {f} acceptance-smelt 2 {first}', '当前国家国主'),
            ('A', f'start {f} acceptance-smelt 2 {first}', '批次已提交'),
            ('A', f'start {f} acceptance-smelt 2 {first}', '批次已提交'),
            ('A', f'start {f} acceptance-smelt 3 {first}', '参数无效'),
            ('A', f'complete {first}', '尚未到期'), ('A', f'off {f}', '当前国家国主'),
            ('B', f'upgrade {f}', '工厂等级已保存'), ('B', f'complete {first}', '批次已交付'),
            ('B', f'start {f} acceptance-long 1 {active}', '批次已提交')],
        2: [('B', f'complete {active}', '当前国家国主'), ('A', f'complete {active}', '批次已交付'),
            ('A', f'start {f} acceptance-smelt 1 {last}', '批次已提交'), ('A', f'complete {last}', '批次已交付')],
        3: [('A', f'receipt {first}', '原操作者'), ('A', f'complete {last}', '批次已交付'),
            ('A', f'start {f} acceptance-smelt 1 {last}', '批次已提交')],
    }[boot]
    expected = [(targets[who.lower()], '/resource factory ' + text) for who, text, _ in commands]
    actual = [r['data'] for r in fixture if r['kind'] == 'PLAYER_COMMAND']
    check([(r['player'], r['command']) for r in actual] == expected, 'Missing real player command or changed identity')
    check(all(r['cancelled'] is False and r['op'] is False for r in actual), 'Command bypassed ordinary player authorization')
    joins = [r['data'] for r in fixture if r['kind'] == 'JOIN']
    check(len(joins) == 2 and {r['player'] for r in joins} == {targets['a'], targets['b']} and all(r['op'] is False for r in joins), 'Missing non-OP player joins')
    sent = [(i, r['data']) for i, r in enumerate(client_rows) if r['kind'] == 'COMMAND']
    check([(r['who'], r['command']) for _, r in sent] == [(w, '/resource factory ' + c) for w,c,_ in commands], 'Client and server commands disagree')
    for n, ((i, row), (who, _, response)) in enumerate(zip(sent, commands)):
        stop = sent[n+1][0] if n+1 < len(sent) else len(client_rows)
        check(any(r['kind'] == 'MESSAGE' and r['data']['who'] == who and response in r['data']['message'] for r in client_rows[i+1:stop]), 'Missing command response: ' + response)
    check(sorted(r['data']['who'] for r in client_rows if r['kind'] == 'SPAWN') == ['A', 'B'], 'Client spawn missing')
    check(sorted(r['data']['who'] for r in client_rows if r['kind'] == 'ENDED') == ['A', 'B'], 'Client shutdown missing')
    for r in client_rows:
        check(r['kind'] != 'FAILED', 'Real client reported failure')
        if r['kind'] == 'SPAWN': check(r['data']['uuid'] == targets[r['data']['who'].lower()], 'Client identity changed')


def inspect(run, intermediate=False):
    run = Path(run).resolve()
    manifest = json.loads((run / 'preparation.json').read_text())
    for name, key in [('plugins/StarChronicle.jar', 'product_sha256'), ('plugins/StarCore.jar', 'native_sha256'),
                      ('plugins/ProcessingHistoryPaperFixture.jar', 'fixture_sha256'), ('paper.jar', 'paper_sha256'),
                      ('fixture/ProcessingHistoryPaperFixture.java', 'fixture_source_sha256'), ('fixture/processing-paper-clients.cjs', 'client_sha256')]:
        check(sha(run/name) == manifest[key], 'Artifact fingerprint drift: ' + name)
    targets = json.loads((run/'evidence/targets.json').read_text())
    for key, name in [('a','ProduceA'), ('b','ProduceB'), ('factory','FactoryOne'), ('first','ProductionFirst'), ('long','ProductionRestart'), ('last','ProductionLast')]:
        check(targets[key] == offline_id(name), 'Fixture identity changed')
    nation = targets['nation']
    fixtures = [json.loads(line) for line in (run/'evidence/fixture.jsonl').read_text().splitlines()]
    check(all(r['kind'] != 'FAILED' and r['boot'] in (1,2,3) for r in fixtures), 'Fixture reported failure or invalid boot')
    pids, previous, previous_resources, previous_contributions, domains, boot_results = set(), {}, {}, {}, None, []
    for boot, head, imported in [(1,134,3 if intermediate else 4), (2,137,137), (3,137,137)]:
        selected = [r for r in fixtures if r['boot'] == boot]
        ready = [r['data'] for r in selected if r['kind'] == 'READY']
        check(len(ready) == 1 and sum(r['kind'] == 'CLOSED' for r in selected) == 1, 'Incomplete fixture lifecycle')
        session = json.loads((run/f'evidence/boot{boot}.session.json').read_text())
        check(session['passed'] is True and session['boot'] == boot and not session.get('forced_kill') and not session.get('error'), 'Incomplete acceptance lifecycle')
        check([p['name'] for p in session['processes']] == ['server','clients'], 'Missing actual server or client process')
        for p in session['processes']:
            check(p['exit_code'] == 0 and p['exited_at'] > p['started_at'] and p['pid'] not in pids, 'Abnormal or repeated process lifecycle')
            pids.add(p['pid'])
        log = (run/f'evidence/boot{boot}.server.log').read_text()
        check(all(x in log for x in ['Done (', 'Stopping server', 'PROCESSING_CLOSED', 'StarChronicle] Enabling StarChronicle', 'StarCore] Enabling StarCore']), 'Missing real plugin enable/disable')
        check(not re.search(r'\bERROR\b|PROCESSING_FAILED|History scan retained pending evidence|history scan retained its cursor|Exception in thread|Server thread dump', log), 'Server failure or lost history')
        clients = [json.loads(l) for l in (run/f'evidence/boot{boot}.clients.log').read_text().splitlines() if l.startswith('{')]
        verify_commands(boot, selected, clients, targets)
        handovers = [r['data'] for r in selected if r['kind'] == 'HANDOVER']
        check(handovers == ({1:[{'from':targets['a'],'to':targets['b']}], 2:[{'from':targets['b'],'to':targets['a']}], 3:[]}[boot]), 'Missing native leadership change')
        checks = [r['data'] for r in selected if r['kind'] == 'CHECK']
        counts = {1:[1,3] if intermediate else [1,3,4], 2:[135,137,137], 3:[137,137]}[boot]
        check([r['count'] for r in checks] == counts and [r['receipts'] for r in session['checkpoints']] == counts, 'Missing live receipt/API checkpoint')
        check([r['data'] for r in selected if r['kind'] == 'REPLAY'] == ([] if boot == 1 else [{'count':137,'replayed':134}]), 'Missing native completed-run replays')
        data = run/f'evidence/boot{boot}.data'
        with db(data/'StarCore/starcore.db') as c:
            check(c.execute('PRAGMA integrity_check').fetchone()[0] == 'ok', 'Native database corrupt')
            state = dict(c.execute('SELECT property_key,property_value FROM starcore_resource_state'))
        domain, records = journal(data, state, 'processing', decode, head)
        resource_domain, resources = journal(data, state, 'resource', resource.decode, head+2)
        check(domains is None or domains == (domain,resource_domain), 'Authority changed on restart')
        domains = domain, resource_domain
        check(ready == [dict(domain=domain, nation=nation, boot=boot)], 'Fixture authority differs from durable storage')
        check((data/'StarCore/resource/resource-sql-authority').read_text() == 'READY', 'SQL authority pin missing')
        with db(data/'StarCore/resource/processing-history/runs.sqlite') as c:
            check(c.execute('PRAGMA integrity_check').fetchone()[0] == 'ok', 'Run index corrupt')
            check(c.execute('SELECT * FROM identity').fetchall() == [(1,domain)], 'Run index authority changed')
            check(c.execute('SELECT sequence,run_id,kind FROM entries ORDER BY sequence').fetchall() == [(s,records[s]['run']['runId'],records[s]['kind']) for s in range(1,head+1)], 'Run index disagrees with committed receipts')
        starts = {}
        for seq in range(1,head+1):
            r, x = records[seq], records[seq]['run']
            kind, seconds, expected = expected_run(seq,nation)
            check(r['kind'] == kind and all(x[k] == v for k,v in expected.items()), 'Original processing actor, inputs or outputs changed')
            check(nanos(x['readyAt']) - nanos(x['startedAt']) == seconds * 10**9, 'Frozen duration changed')
            if kind == 'STARTED':
                check(x['completedAt'] is None and r['committedAt'] == x['startedAt'], 'Start time changed')
                starts[x['runId']] = x
            else:
                check(x['completedAt'] == r['committedAt'] and nanos(x['completedAt']) >= nanos(x['readyAt']), 'Delivery before frozen ready time')
                if kind == 'AUTOMATIC': check(x['startedAt'] == x['readyAt'] == x['completedAt'], 'Automatic conversion was not atomic')
                else: check(starts.pop(x['runId'], None) == {**x,'completedAt':None}, 'Completion lacks exact committed start')
            check(seq not in previous or previous[seq] == r, 'Immutable processing history changed after restart')
            previous[seq] = r
        check(int(state['factory.count']) == 1 and state['factory.0.id'] == targets['factory'] and state['factory.0.nationId'] == nation, 'Persisted factory identity changed')
        check(state['factory.0.level'] == '2' and state['factory.0.efficiency'] == '1.05' and state['factory.0.operational'] == 'true', 'Factory management state changed')
        if boot == 1:
            check(len(starts) == 1 and decode_run(state['factory.0.run']) == records[4]['run'], 'Active run was not frozen before restart')
            check(state['factory.0.recipe'] == 'acceptance-long' and state['factory.0.processingStartInstant'] == records[4]['run']['startedAt'], 'Active factory and frozen run disagree')
        else: check(not starts and all('factory.0.'+k not in state for k in ('run','recipe','processingStart','processingStartInstant')), 'Completed factory still active')
        balances = {}
        check(len({r['operationId'] for r in resources.values()}) == head+2, 'Resource operation ID reused')
        for seq in range(1,head+3):
            r = resources[seq]
            if seq <= 2:
                expected = {'mineral' if seq == 1 else 'energy':20000}
                check(r['kind'] == 'GRANT' and r['actorId'] is None, 'Invalid seed grant')
            else:
                processing = records[seq-2]; x = processing['run']; expected = {}
                if processing['kind'] != 'COMPLETED': expected.update({k:-v for k,v in x['inputs'].items()})
                if processing['kind'] != 'STARTED':
                    for k,v in x['outputs'].items(): expected[k] = expected.get(k,0)+v
                check(r['kind'] == 'ADJUSTMENT' and r['reference'] == x['runId'] and r['actorId'] == x['actorId']
                      and r['category'] == 'PROCESSING_'+processing['kind'] and r['description'] == x['recipeId'], 'Resource commit is not bound to its processing run')
            check(len(r['balances']) == len(expected) and {b['resourceType']:b['after']-b['before'] for b in r['balances']} == expected, 'Resource deltas do not equal exact processing quantities')
            for b in r['balances']:
                check(b['nationId'] == nation and b['before'] == balances.get(b['resourceType'],0) and b['after'] >= 0, 'Native resource balance chain broke')
                balances[b['resourceType']] = b['after']
            check(seq not in previous_resources or previous_resources[seq] == r, 'Resource history changed after restart')
            previous_resources[seq] = r
        stockpile = {state[f'stockpile.{i}.type']:int(state[f'stockpile.{i}.amount']) for i in range(int(state['count'])) if state[f'stockpile.{i}.nationId'] == nation}
        check(len(stockpile) == int(state['count']) and stockpile == balances, 'Persisted inventory differs from receipt replay')
        check(balances == ({'mineral':19598,'energy':19866,'industrial':266,'chemical':133} if boot==1 else {'mineral':19595,'energy':19865,'industrial':270,'chemical':135}), 'Final production inventory changed')
        with db(data/'StarChronicle/chronicle.db') as c:
            check(c.execute('PRAGMA user_version').fetchone()[0] == 10, 'Processing schema boundary changed')
            check(c.execute('PRAGMA integrity_check').fetchone()[0] == 'ok' and not c.execute('PRAGMA foreign_key_check').fetchall(), 'Chronicle integrity failed')
            events = [json.loads(row[0]) for row in c.execute('SELECT json FROM events')]
            for ns, original, d, count, archive in [('processing',records,domain,imported,1), ('resources',resources,resource_domain,imported+2,3)]:
                namespace = 'starcore.'+ns
                check(c.execute('SELECT authority_domain,sequence,live FROM committed_history_sources WHERE namespace=?',(namespace,)).fetchone() == (d,count,1), 'Chronicle cursor changed')
                ingress = c.execute('SELECT sequence,id,status,activate,json FROM committed_history_ingress WHERE namespace=? ORDER BY sequence',(namespace,)).fetchall()
                check([r[0] for r in ingress] == list(range(1,count+1)) and all(r[2]=='ACKED' and r[3]==int(r[0]>archive) for r in ingress), 'Unacknowledged, missing or repeated ingress')
                table = 'processing' if ns == 'processing' else 'resource'
                full = c.execute('SELECT event_id,sequence,json FROM '+table+'_history_receipts ORDER BY sequence').fetchall()
                if ns == 'processing':
                    check(c.execute('SELECT authority_domain,sequence,run_id,kind FROM processing_history_receipts ORDER BY sequence').fetchall()==[(d,s,records[s]['run']['runId'],records[s]['kind']) for s in range(1,count+1)], 'Indexed processing evidence differs from complete payload')
                staged = dict(c.execute('SELECT event_id,json FROM committed_'+table+'_ingress'))
                exact = {int(e['facts']['history.sequence']):e for e in events if e['facts'].get('history.namespace') == namespace}
                check(len(full)==len(staged)==len(exact)==count, 'Missing full evidence or event')
                for seq in range(1,count+1):
                    r, row, ing, e = original[seq], full[seq-1], ingress[seq-1], exact[seq]
                    expected_id = event_id(r) if ns == 'processing' else f'starcore:resource:{d}:{r["operationId"]}'
                    check(row[:2] == (expected_id,seq) and json.loads(row[2]) == r and json.loads(staged[expected_id]) == r, 'Full native receipt differs from Chronicle')
                    check(ing[1] == e['id'] == expected_id and resource.canonical_event(json.loads(ing[4])) == resource.canonical_event(e), 'Ingress and final event differ')
                    check(e['occurredAt'] == r['committedAt'] and e['facts']['time_basis'] == 'COMMITTED', 'Committed event time changed')
                    check(e['facts']['history.authority'] == d and e['facts']['history.sequence'] == str(seq), 'History event identity changed')
                    check(e['facts'][table+'.receipt_sha256'] == hashlib.sha256(row[2].encode()).hexdigest(), 'Receipt fingerprint mismatch')
                    actor = r['run']['actorId'] if ns=='processing' else r['actorId']
                    check(e['participants'] == ([] if actor is None else [actor]) and e['nations'] == [nation], 'Event attributed to current rather than original actor')
                    if ns == 'processing':
                        x = r['run']; facts=e['facts']
                        check(e['type'] == 'PROCESSING_'+r['kind'] and facts['processing.run'] == x['runId'] and facts['processing.recipe']==x['recipeId'] and facts['processing.batches']==str(x['batches']), 'Production facts changed')
                        check(facts['processing.started_at']==x['startedAt'] and facts['processing.ready_at']==x['readyAt'] and facts.get('processing.completed_at')==x['completedAt'], 'Production fact time changed')
                        check(facts.get('actor.id')==actor and facts.get('processing.factory')==x['factoryId'], 'Frozen actor/factory facts changed')
                        check(facts['contribution.policy']=='EXACT_OUTPUT_PER_RESOURCE_ORIGINAL_ACTOR_AND_NATION_V1' and facts.get('contribution.omission')==(None if r['kind']=='COMPLETED' else 'NOT_COMPLETED_FACTORY_PRODUCTION'), 'Production contribution policy changed')
                    else:
                        check(e['type']=='RESOURCE_BALANCE_CHANGED' and e['facts']['contribution.omission']=='BALANCE_CHANGE_IS_NOT_PRODUCTION_OR_NEW_AID_OR_TRADE', 'Resource history duplicated production credit')
                        check(all(e['facts'].get('resource.'+k)==r[k] for k in ('kind','reference','category','description')), 'Resource facts differ from atomic source commit')
            expected_requests = requests([records[s] for s in range(1,imported+1)])
            c.row_factory = sqlite3.Row
            contributions = [dict(r) for r in c.execute('SELECT * FROM contributions')]
            check(sorted(canonical(json.loads(r['request_json'])) for r in contributions)==sorted(canonical(r) for r in expected_requests), 'Contribution missing, repeated or attributed incorrectly')
            for row in contributions:
                request=json.loads(row['request_json'])
                check(row['id']=='chronicle:contribution:'+hashlib.sha256((request['operationId']+'|CIVILIZATION|'+request['subjectId']).encode()).hexdigest(), 'Contribution durable identity changed')
                for column,key in [('operation_id','operationId'),('type','type'),('subject_id','subjectId'),('source_event_id','sourceEventId'),('actor_id','actorId'),('nation_id','nationId'),('unit','unit'),('committed_at','committedAt'),('evidence','evidence')]:
                    check(row[column]==request[key], 'Contribution SQL index differs from full request')
                check(str(request['amount']) == row['amount'] and nanos(row['recorded_at']) >= nanos(row['committed_at']), 'Contribution amount or record time changed')
                check(row['id'] not in previous_contributions or previous_contributions[row['id']]==row, 'Contribution mutated after restart')
                previous_contributions[row['id']]=row
            c.row_factory = None
            trigger_receipts = sorted((stable(event_id(r),'processing-history-test','1'),'nation:'+nation) for r in records.values() if r['sequence']<=imported and r['kind']=='COMPLETED')
            check(c.execute('SELECT COUNT(*) FROM story_triggers').fetchone()[0] == 0 and sorted(c.execute('SELECT trigger_key,scope FROM story_trigger_receipts')) == trigger_receipts, 'Completion trigger receipt missing or changed')
            instances=c.execute('SELECT story,scope,completed,json FROM instances').fetchall()
            check(len(instances)==1 and instances[0][:3]==('processing-history-test','nation:'+nation,0) and json.loads(instances[0][3])['sourceEventId']==event_id(records[3]), 'Real national story instance missing or changed')
            check(c.execute('SELECT COUNT(*) FROM outcomes').fetchone()[0]==0, 'Fixture unexpectedly settled a story')
        for point, sql in zip(checks,session['checkpoints']):
            count=point['count']; expected=requests([records[s] for s in range(1,count+1)])
            check(point['receipts']==[records[s] for s in range(1,count+1)], 'Public processing receipt API differs from source')
            check(sorted(canonical(r['request']) for r in point['contributions'])==sorted(canonical(r) for r in expected), 'Public contribution API differs from source')
            check(sql==dict(source=[domain,count,1],pending=0,receipts=count,contributions=len(expected),archive=1,resource_sequence=[count+2]), 'Live SQL checkpoint changed')
        boot_results.append(dict(boot=boot,native_head=head,imported=imported,contributions=len(expected_requests),stockpile=balances))
    for row in (r for r in fixtures if r['kind']=='NATIVE'):
        value=row['data']; count=value['count']
        check(value['records']==[previous[s] for s in range(1,count+1)] and value['resources']==[previous_resources[s] for s in range(1,count+3)], 'Public native API differs from durable receipts')
        balance={}
        for r in value['resources']:
            for b in r['balances']: balance[b['resourceType']]=b['after']
        check({k:v for k,v in value['stockpile'].items() if v}==balance, 'Native public stockpile differs from source replay')
    native_by_boot=[[r['data'] for r in fixtures if r['boot']==b and r['kind']=='NATIVE'] for b in (1,2,3)]
    check(native_by_boot[1][0]['count']==134 and native_by_boot[1][0]['factory']['processing'] is True and native_by_boot[1][0]['founder']==targets['b'], 'Actual enable did not restore active factory')
    check([r['data'] for r in fixtures if r['kind']=='DONE' and r['data'].get('action')=='offline']==[{'action':'offline'}], 'Missing disabled-Chronicle source burst')
    return dict(status='PASS', chronicle_sha256=manifest['product_sha256'], starcore_sha256=manifest['native_sha256'], schema=10,
                verified_boots=3, native_processing_changes=137, native_resource_changes=139, archive_only=1,
                offline_system_runs=130, personal_completed_runs=3, contributions=12, boot_counts=boot_results,
                scope='Isolated Paper native factory production; two offline loopback non-OP clients; original actors, frozen restart and exact inventory. No full five-plugin or production claim.')


if __name__ == '__main__':
    p=argparse.ArgumentParser(description=__doc__); p.add_argument('run',type=Path); p.add_argument('--intermediate',action='store_true'); p.add_argument('--output',type=Path)
    args=p.parse_args(); result=inspect(args.run,args.intermediate)
    if args.output: args.output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(result,ensure_ascii=False))
