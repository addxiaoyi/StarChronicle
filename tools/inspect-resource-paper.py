"""Verify real native national stockpile transfers across three stopped Paper boots."""
from contextlib import closing
from pathlib import Path
import argparse
from datetime import datetime, timezone
import importlib.util
import json
import re
import socket
import uuid

SPEC = importlib.util.spec_from_file_location('trade_inspector', Path(__file__).with_name('inspect-trade-paper.py'))
TRADE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(TRADE)
EPIC, check, records, instant, normalized, stable = TRADE.EPIC, TRADE.check, TRADE.records, TRADE.instant, TRADE.normalized, TRADE.stable
SOURCE = 'starcore.resource.transfers.v1'


def inspect(run):
    run = run.resolve(); manifest = EPIC.read(run / 'preparation.json')
    with socket.socket() as probe:
        probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        probe.bind(('127.0.0.1', manifest['server_port']))
    for name, key in [('StarCore', 'starcore_sha256'), ('StarChronicle', 'plugin_sha256'), ('EpicCampaignPaperFixture', 'fixture_sha256')]:
        check(EPIC.digest(run / f'plugins/{name}.jar') == manifest[key], 'Frozen JAR changed')
    check(EPIC.digest(run / 'fixture/EpicCampaignPaperFixture.java') == manifest['fixture_source_sha256'], 'Frozen fixture source changed')
    evidence = run / 'evidence'; fixture = records(evidence / 'epic-fixture.jsonl')
    check(not any(r['kind'] == 'FAILED' for r in fixture), 'Fixture failure retained in run')
    check({r['boot'] for r in fixture} == {1, 2, 3}, 'Exactly three fixture boots required')
    check(len(list(evidence.glob('boot*.session.json'))) == 3, 'Exactly three completed controller sessions required')
    seeded = next(r['data'] for r in fixture if r['kind'] == 'SEEDED')
    bound = next(r['data'] for r in fixture if r['kind'] == 'RESOURCE_BOUND')
    check(all(bound[who] == seeded[who] for who in 'ABC'), 'Resource binding differs from original nations')
    domain = bound['domain']
    check(str(uuid.UUID(domain)) == domain, 'Invalid resource authority domain')
    observations, lifetimes = {}, {}
    for boot, scenario in enumerate(('resource-first', 'resource-second', 'resource-third'), 1):
        session = EPIC.read(evidence / f'boot{boot}.session.json')
        check(session['boot'] == boot and session['scenario'] == scenario and session['status'] == 'COMPLETE', 'Incomplete controller scenario')
        processes = session['processes']
        check(len(processes) == 2 and {p['name'] for p in processes} == {'server', 'clients'}, 'Missing actual server/client processes')
        check(all(p.get('exit_code') == 0 and p['exited_at'] >= p['started_at'] for p in processes), 'Abnormal process exit')
        server_process = next(p for p in processes if p['name'] == 'server'); lifetimes[boot] = server_process
        client_process = next(p for p in processes if p['name'] == 'clients')
        rows = [r for r in fixture if r['boot'] == boot]
        for kind in ('READY', 'SEEDED', 'CLOSED', 'RESOURCE_BOUND'):
            check(sum(r['kind'] == kind for r in rows) == 1, 'Missing boot lifecycle: ' + kind)
        check(next(r['data'] for r in rows if r['kind'] == 'READY') == {'product': manifest['plugin_sha256'], 'native': manifest['starcore_sha256'], 'route': manifest['route']}, 'Running artifact mismatch')
        check(next(r['data'] for r in rows if r['kind'] == 'SEEDED') == seeded and seeded['nonOp'] is True, 'Native seed identity or permissions changed')
        check(next(r['data'] for r in rows if r['kind'] == 'RESOURCE_BOUND') == bound, 'Resource binding changed after restart')
        check(next(r['data'] for r in rows if r['kind'] == 'CLOSED') == {'normalDisable': True}, 'Abnormal fixture shutdown')
        check(all(server_process['started_at'] <= instant(r['time']) <= server_process['exited_at'] for r in rows), 'Fixture event outside server lifetime')
        server = (evidence / f'boot{boot}.server.log').read_text(encoding='utf-8')
        diagnostics = re.findall(r'^.*(?:ERROR\]|ERROR\]:|Failed to (?:enable|disable) STARCORE module|同步保存失败|异步保存失败|SQLITE_BUSY|startup failed|Could not pass event|Committed history source .* retained|History scan retained pending evidence|Failed to persist resource).*$', server, re.M | re.I)
        check(not diagnostics, 'Server persistence/lifecycle diagnostic: ' + '\n'.join(diagnostics))
        check(all(word in server for word in ('Done (', 'Stopping server', 'All dimensions are saved')), 'Incomplete server startup/shutdown')
        clients = records(evidence / f'boot{boot}.clients.log')
        check(not any(r['kind'] == 'FAILED' for r in clients), 'Protocol client failure')
        spawns = [r['data'] for r in clients if r['kind'] == 'SPAWN']
        check(len(spawns) == 4 and {s['who'] for s in spawns} == set('ABCD') and all(s['uuid'] == manifest['players'][s['who']] for s in spawns), 'Missing real client identities')
        commands = [r for r in clients if r['kind'] == 'COMMAND_RESULT']
        check(len(commands) == 1, 'Missing actual contribution query')
        command = commands[0]; data = command['data']
        check((data['who'], data['command'], data['contains']) == ('A', '/chronicle contributions', '本页暂无已核验贡献。') and data['contains'] in data['response'], 'Unexpected actual contribution response')
        check(client_process['started_at'] <= instant(command['time']) <= client_process['exited_at'], 'Command outside client lifetime')
        observations[boot] = [r for r in rows if r['kind'] == 'RESOURCE_CHECK']
        check([r['data']['expected'] for r in observations[boot]] == {1: [0, 2, 2], 2: [2, 2, 3], 3: [3, 3]}[boot], 'Missing live resource API observation')
        for kind in ('RESOURCE_BOUND', 'RESOURCE_BATCH', 'RESOURCE_REJECTIONS', 'RESOURCE_RESTART', 'RESOURCE_CHECK'):
            logged = [json.loads(line.split('EPIC_' + kind + ' ', 1)[1]) for line in server.splitlines() if 'EPIC_' + kind + ' ' in line]
            check(logged == [r['data'] for r in rows if r['kind'] == kind], 'Live resource evidence absent from server stream')
        check(instant(observations[boot][-1]['time']) < instant(command['time']), 'Query must follow history observation')
    check(all(lifetimes[b]['exited_at'] < lifetimes[b + 1]['started_at'] for b in (1, 2)), 'Server boots overlap')
    marker = run / 'plugins/StarCore/resource/resource-sql-authority'
    check(marker.read_text() == 'SQL', 'Native resource SQL authority marker mismatch')
    with closing(EPIC.connect(run / 'plugins/StarCore/starcore.db')) as db:
        heads = [dict(row) for row in db.execute('select * from starcore_resource_transfer_history')]
        check(heads == [dict(state_id=1, authority_domain_id=domain, committed_sequence=3)], 'Native independent journal head mismatch')
        markers = {row[0] for row in db.execute('select migration_key from starcore_resource_state_migrations')}
        check(markers == {'resource-transfer-receipts-v1', 'resource-transfer-history-v1'}, 'Native history migration marker mismatch')
        state = dict(db.execute('select property_key,property_value from starcore_resource_state'))
        check(state['resource.authority-domain'] == domain, 'Native resource state domain mismatch')
        rows = [dict(row) for row in db.execute('select * from starcore_resource_transfer_receipts order by sequence')]
        check(len(rows) == 3 and [row['sequence'] for row in rows] == [1, 2, 3], 'Missing or extra native transfer receipt')
    receipts = []
    for row in rows:
        amounts = dict(part.split('=', 1) for part in row['resources'].split(';'))
        amounts = {key: int(value) for key, value in amounts.items()}
        stamp = datetime.fromtimestamp(row['applied_at'] / 1000, timezone.utc).isoformat(timespec='milliseconds' if row['applied_at'] % 1000 else 'seconds').replace('+00:00', 'Z')
        receipts.append(dict(domain=row['authority_domain_id'], sequence=row['sequence'], transaction=row['transaction_id'], payer=row['payer_nation_id'], receiver=row['receiver_nation_id'], resources=amounts, category=row['category'], description=row['description'], committedAt=stamp))
    requests = [('first', 'A', 'B', {'agricultural': 7, 'mineral': 3}, 'OTHER', 'Isolated first stockpile transfer'),
                ('second', 'B', 'C', {'agricultural': 4, 'mineral': 2}, 'OTHER', 'Isolated second stockpile transfer'),
                ('restart', 'C', 'A', {'agricultural': 2, 'mineral': 1}, 'COMPENSATION_REFUND', 'Isolated restarted stockpile transfer')]
    stocks = {'A': {'agricultural': 100, 'energy': 25, 'mineral': 50}, 'B': {'agricultural': 100}, 'C': {'agricultural': 100}}
    states = [json.loads(json.dumps(stocks))]
    results = []
    for index, (receipt, (name, payer, receiver, quantities, category, description)) in enumerate(zip(receipts, requests), 1):
        expected = dict(domain=domain, sequence=index, transaction='isolated-resource-' + name, payer=bound[payer], receiver=bound[receiver], resources=quantities, category=category, description=description)
        check({key: value for key, value in receipt.items() if key != 'committedAt'} == expected, 'Native resource receipt differs from requested transfer')
        for key, amount in quantities.items():
            stocks[payer][key] -= amount
            stocks[receiver][key] = stocks[receiver].get(key, 0) + amount
        states.append(json.loads(json.dumps(stocks)))
        types = ('agricultural', 'mineral', 'energy', 'luxury', 'industrial', 'chemical', 'strategic')
        results.append(dict(status='APPLIED', transactionId=receipt['transaction'], payerStockpileAfter={key: stocks[payer].get(key, 0) for key in types}, receiverStockpileAfter={key: stocks[receiver].get(key, 0) for key in types}))
    persisted_stocks = {who: {} for who in 'ABC'}
    nation_keys = {bound[who]: who for who in 'ABC'}
    for index in range(int(state['count'])):
        prefix = f'stockpile.{index}.'
        country = nation_keys[state[prefix + 'nationId']]
        key = state[prefix + 'type']
        check(key not in persisted_stocks[country], 'Duplicate native stockpile entry')
        persisted_stocks[country][key] = int(state[prefix + 'amount'])
    check(persisted_stocks == states[3], 'Native final stockpiles differ from committed transfers')
    check(not any(key.startswith('resource.transfer.') for key in state), 'SQL snapshot must not duplicate file history')
    batch = [row for row in fixture if row['kind'] == 'RESOURCE_BATCH']
    restarted = [row for row in fixture if row['kind'] == 'RESOURCE_RESTART']
    rejected = [row for row in fixture if row['kind'] == 'RESOURCE_REJECTIONS']
    check(len(batch) == 1 and batch[0]['boot'] == 1 and batch[0]['data'] == dict(results=results[:2], receipts=receipts[:2], stocks=states[2]), 'Live native batch differs from receipts/stocks')
    check(len(restarted) == 1 and restarted[0]['boot'] == 2 and restarted[0]['data'] == dict(result=results[2], receipts=receipts, stocks=states[3]), 'Live restarted transfer differs from receipts/stocks')
    check([row['boot'] for row in rejected] == [1, 2, 3], 'Missing live repeated/conflicting/insufficient requests')
    for row, count in zip(rejected, (2, 2, 3)):
        check(row['data'] == dict(statuses=['ALREADY_APPLIED', 'REJECTED', 'INSUFFICIENT_RESOURCES'], receipts=receipts[:count], stocks=states[count]), 'Repeated/rejected transfer changed native state')
    timeline = [observations[1][0], batch[0], observations[1][1], rejected[0], observations[1][2], observations[2][0], rejected[1], observations[2][1], restarted[0], observations[2][2], observations[3][0], rejected[2], observations[3][1]]
    check(all(instant(a['time']) < instant(b['time']) for a, b in zip(timeline, timeline[1:])), 'Resource scenario causal order changed')
    for index, receipt in enumerate(receipts):
        start, end = (observations[1][0], batch[0]) if index < 2 else (observations[2][1], restarted[0])
        check(instant(start['time']) < instant(receipt['committedAt']) < instant(end['time']), 'Native resource commit outside actual transfer')
    expected_events = []
    for receipt in receipts:
        sequence, payer, receiver, transaction = (receipt[key] for key in ('sequence', 'payer', 'receiver', 'transaction'))
        facts = {'title': '国家资源转移已提交', 'time_basis': 'COMMITTED', 'source.authority_domain': domain, 'source.sequence': str(sequence),
                 'resource.transaction_id': transaction, 'resource.payer': payer, 'resource.receiver': receiver,
                 'resource.quantities': json.dumps(receipt['resources'], sort_keys=True, separators=(',', ':')), 'resource.category': receipt['category'],
                 'resource.description_sha256': stable(receipt['description']), 'resource.description_length': str(len(receipt['description'])),
                 'resource.actor': 'NOT_RECORDED', 'resource.scope': 'NATION_STOCKPILE_TRANSFER'}
        proof = f'Native NationResourceTransactionService committed transfer; domain={domain}; sequence={sequence}; transaction_sha256={stable(transaction)}; payer={payer}; receiver={receiver}; stockpiles, receipt and journal head share a commit; actor is not recorded; category is request metadata; grant/consume and player trade are separate sources'
        expected_events.append(dict(id=f'starcore:resource-transfer-history:{domain}:{sequence}', type='NATIONAL_RESOURCE_TRANSFER_COMMITTED', occurredAt=receipt['committedAt'], participants=[], nations=sorted([payer, receiver]), facts=facts, source='StarCore.NationResourceTransactionService', evidence=proof))
    for rows in observations.values():
        for row in rows:
            data = row['data']; count = data['expected']
            check(data['domain'] == domain and data['contributions'] == 0 and data['stocks'] == states[count], 'Live resource domain/stockpile/contribution mismatch')
            check(data['native'] == receipts[:count], 'Live native transfer history differs from SQL receipts')
            actual = sorted((normalized(event) for event in data['events']), key=lambda event: int(event['facts']['source.sequence']))
            check(actual == expected_events[:count], 'Live Chronicle resource history differs from native receipts')
    with closing(EPIC.connect(run / 'plugins/StarChronicle/chronicle.db')) as db:
        cursors = [dict(row) for row in db.execute('select * from committed_history_sources where source=?', (SOURCE,))]
        check(cursors == [dict(source=SOURCE, authority_domain=domain, sequence=3)], 'Chronicle resource domain/cursor mismatch')
        inbox = [dict(row) for row in db.execute('select * from committed_history_ingress where source=? order by sequence', (SOURCE,))]
        check(len(inbox) == 3 and [row['sequence'] for row in inbox] == [1, 2, 3], 'Missing immutable resource inbox')
        for row, expected in zip(inbox, expected_events):
            check(row['status'] == 'ACKED' and row['event_id'] == expected['id'], 'Resource inbox acknowledgement/identity mismatch')
            check(normalized(json.loads(row['json'])) == expected, 'Resource inbox differs from native receipt')
        events = [dict(row) for row in db.execute("select * from events where json_extract(json,'$.source')='StarCore.NationResourceTransactionService' or id like 'starcore:resource-transfer-history:%' or type='NATIONAL_RESOURCE_TRANSFER_COMMITTED' order by id")]
        check(len(events) == 3, 'Expected three retained resource events')
        for row, expected in zip(events, expected_events):
            check(normalized(json.loads(row['json'])) == expected and row['id'] == expected['id'] and row['type'] == expected['type'] and instant(row['time']) == instant(expected['occurredAt']), 'Persisted resource event differs from receipt')
            canonical = dict(id=expected['id'], type=expected['type'], time=expected['occurredAt'], players=[], nations=expected['nations'], facts=expected['facts'], source=expected['source'], evidence=expected['evidence'])
            check(row['hash'] == stable(json.dumps(canonical, ensure_ascii=False, separators=(',', ':'), sort_keys=True)), 'Resource canonical event hash mismatch')
        check(db.execute('select count(*) from contributions').fetchone()[0] == 0, 'Resource facts cannot prove player contributions')
        check(db.execute('select count(*) from epic_campaigns').fetchone()[0] == 0, 'Unexpected epic campaign in resource fixture')
    return dict(status='PASS', chronicle_sha256=manifest['plugin_sha256'], starcore_sha256=manifest['starcore_sha256'], authority_domain=domain,
                verified_boots=3, normal_process_exits=6, actual_command_results=3, live_resource_checks=8, native_transfers=3, immutable_inbox_rows=3,
                retained_resource_events=3, contributions=0, final_stockpiles=states[3],
                verification='Native multi-resource batch, repeated/conflicting/insufficient requests, restarted transfer, independent SQL head, inventory and receipts, canonical events and live API/commands cross-checked.',
                limitations=['Native receipt records nations and quantities, without an individual actor.', 'Grant/consume, player trade and production are separate sources.', 'Pre-upgrade tail loss cannot be reconstructed from surviving receipts.'])


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument('run', type=Path); args = parser.parse_args()
    result = inspect(args.run)
    (args.run / 'evidence/resource-check.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False))
