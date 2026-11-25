"""Cross-check a stopped three-boot history-store fixture, without claiming asset transfers."""
from contextlib import closing
from datetime import datetime, timezone
from pathlib import Path
import argparse
import hashlib
import importlib.util
import json
import re
import socket
import uuid

SPEC = importlib.util.spec_from_file_location('epic_inspector', Path(__file__).with_name('inspect-epic-paper.py'))
EPIC = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(EPIC)
check = EPIC.check
SOURCE = 'starcore.trade.history.v1'


def records(path):
    return [json.loads(line) for line in path.read_text(encoding='utf-8').splitlines() if line.startswith('{')]


def instant(value):
    return datetime.fromisoformat(value).timestamp()


def normalized(event):
    return dict(event, participants=sorted(event['participants']), nations=sorted(event['nations']))


def stable(value):
    data = value.encode('utf-8')
    return hashlib.sha256(str(len(data)).encode() + b':' + data).hexdigest()


def trade_id(name):
    return str(uuid.UUID(bytes=hashlib.md5(('isolated-history-store-' + name).encode()).digest(), version=3))


def inspect(run):
    run = run.resolve()
    manifest = EPIC.read(run / 'preparation.json')
    with socket.socket() as probe:
        probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        probe.bind(('127.0.0.1', manifest['server_port']))
    for name, key in [('StarCore', 'starcore_sha256'), ('StarChronicle', 'plugin_sha256'), ('EpicCampaignPaperFixture', 'fixture_sha256')]:
        check(EPIC.digest(run / f'plugins/{name}.jar') == manifest[key], 'Frozen JAR changed')
    check(EPIC.digest(run / 'fixture/EpicCampaignPaperFixture.java') == manifest['fixture_source_sha256'], 'Frozen fixture source changed')
    evidence = run / 'evidence'
    fixture = records(evidence / 'epic-fixture.jsonl')
    check(not any(r['kind'] == 'FAILED' for r in fixture), 'Fixture failure retained in run')
    check({r['boot'] for r in fixture} == {1, 2, 3}, 'Exactly three fixture boots required')
    check(len(list(evidence.glob('boot*.session.json'))) == 3, 'Exactly three completed controller sessions required')
    seeded = next(r['data'] for r in fixture if r['kind'] == 'SEEDED')
    players = manifest['players']
    observations, commands, lifetimes = {}, {}, {}
    for boot, scenario in enumerate(('trade-first', 'trade-second', 'trade-third'), 1):
        session = EPIC.read(evidence / f'boot{boot}.session.json')
        check(session['boot'] == boot and session['scenario'] == scenario and session['status'] == 'COMPLETE', 'Incomplete controller scenario')
        processes = session['processes']
        check(len(processes) == 2 and {p['name'] for p in processes} == {'server', 'clients'}, 'Missing actual server/client processes')
        check(all(p.get('exit_code') == 0 and p['exited_at'] >= p['started_at'] for p in processes), 'Abnormal process exit')
        server_process = next(p for p in processes if p['name'] == 'server')
        client_process = next(p for p in processes if p['name'] == 'clients')
        lifetimes[boot] = server_process
        rows = [r for r in fixture if r['boot'] == boot]
        for kind in ('READY', 'SEEDED', 'CLOSED'):
            check(sum(r['kind'] == kind for r in rows) == 1, 'Missing boot lifecycle: ' + kind)
        check(next(r['data'] for r in rows if r['kind'] == 'READY') ==
              {'product': manifest['plugin_sha256'], 'native': manifest['starcore_sha256'], 'route': manifest['route']}, 'Running artifact mismatch')
        check(next(r['data'] for r in rows if r['kind'] == 'SEEDED') == seeded and seeded['nonOp'] is True, 'Native seed identity or permissions changed')
        check(next(r['data'] for r in rows if r['kind'] == 'CLOSED') == {'normalDisable': True}, 'Abnormal fixture shutdown')
        check(all(server_process['started_at'] <= instant(r['time']) <= server_process['exited_at'] for r in rows), 'Fixture event outside server lifetime')
        server = (evidence / f'boot{boot}.server.log').read_text(encoding='utf-8')
        diagnostics = re.findall(r'^.*(?:ERROR\]|ERROR\]:|Failed to (?:enable|disable) STARCORE module|同步保存失败|异步保存失败|SQLITE_BUSY|startup failed|Could not pass event|Trade history source retained|Committed history source .* retained|History scan retained pending evidence).*$', server, re.M | re.I)
        check(not diagnostics, 'Server persistence/lifecycle diagnostic: ' + '\n'.join(diagnostics))
        check(all(word in server for word in ('Done (', 'Stopping server', 'All dimensions are saved')), 'Incomplete server startup/shutdown')
        clients = records(evidence / f'boot{boot}.clients.log')
        check(not any(r['kind'] == 'FAILED' for r in clients), 'Protocol client failure')
        spawns = [r['data'] for r in clients if r['kind'] == 'SPAWN']
        check(len(spawns) == 4 and {s['who'] for s in spawns} == set('ABCD') and all(s['uuid'] == players[s['who']] for s in spawns), 'Missing real client identities')
        commands[boot] = [r for r in clients if r['kind'] == 'COMMAND_RESULT']
        check(len(commands[boot]) == 1, 'Missing actual contribution query')
        command = commands[boot][0]
        data = command['data']
        check((data['who'], data['command'], data['contains']) == ('A', '/chronicle contributions', '本页暂无已核验贡献。') and data['contains'] in data['response'], 'Unexpected actual contribution response')
        check(client_process['started_at'] <= instant(command['time']) <= client_process['exited_at'], 'Command outside client lifetime')
        observations[boot] = [r for r in rows if r['kind'] == 'TRADE_CHECK']
        check([r['data']['expected'] for r in observations[boot]] == {1: [1, 2, 3], 2: [3, 5], 3: [5]}[boot], 'Missing live trade API observation')
        for kind in ('TRADE_WRITE', 'TRADE_DISPUTED', 'TRADE_CLEANED', 'TRADE_CHECK'):
            logged = [json.loads(line.split('EPIC_' + kind + ' ', 1)[1]) for line in server.splitlines() if 'EPIC_' + kind + ' ' in line]
            check(logged == [r['data'] for r in rows if r['kind'] == kind], 'Live trade evidence absent from server stream')
        check(instant(observations[boot][-1]['time']) < instant(command['time']), 'Query must follow history observation')
    check(all(lifetimes[b]['exited_at'] < lifetimes[b + 1]['started_at'] for b in (1, 2)), 'Server boots overlap')
    with closing(EPIC.connect(run / 'plugins/StarCore/starcore.db')) as db:
        metadata = [dict(r) for r in db.execute('select * from trade_history_journal_meta')]
        check(len(metadata) == 1 and metadata[0]['singleton'] == 1 and metadata[0]['initialized'] == 1 and metadata[0]['last_sequence'] == 5, 'Native journal metadata mismatch')
        domain = metadata[0]['authority_domain']
        check(str(uuid.UUID(domain)) == domain, 'Invalid native authority domain')
        changes = [dict(r) for r in db.execute('select * from trade_history_changes order by sequence_id')]
        check([r['sequence_id'] for r in changes] == [1, 2, 3, 4, 5], 'Native committed sequence gap')
        check(db.execute('select count(*) from trade_history').fetchone()[0] == 0, 'Maintenance did not remove current rows')
    names = ['first', 'late', 'first', 'first', 'late']
    statuses = ['COMPLETED', 'COMPLETED', 'DISPUTED', 'DISPUTED', 'COMPLETED']
    snapshots, expected_events, native_summaries = [], [], []
    for seq, (row, name, status) in enumerate(zip(changes, names, statuses), 1):
        kind = 'UPSERT' if seq <= 3 else 'DELETE'
        executed = ('2020' if name == 'first' else '2019') + '-01-01T00:00:00Z'
        notes = 'Isolated history-store status update' if status == 'DISPUTED' else 'Isolated history-store fixture; no asset movement claimed'
        snapshot = dict(format=1, recordId=trade_id(name), orderType='BUY', orderSource='PLAYER',
                        sellerPlayerId=players['A'], buyerPlayerId=players['B'], sellerNationId=seeded['A'], buyerNationId=seeded['B'],
                        resourceId='agricultural', amount=2, pricePerUnit=5.0, taxAmount=0.5, executedAt=executed,
                        buyOrderId=None, sellOrderId=None, status=status, notes=notes)
        check(json.loads(row['record_json']) == snapshot and row['change_kind'] == kind, 'Native immutable snapshot differs from scenario')
        snapshots.append(snapshot)
        recorded = datetime.fromtimestamp(row['recorded_at'] / 1000, timezone.utc).isoformat(timespec='milliseconds').replace('+00:00', 'Z')
        # java.time.Instant emits seconds only when milliseconds are exactly zero.
        recorded = recorded.replace('.000Z', 'Z')
        native_summaries.append(dict(sequence=seq, kind=kind, record=trade_id(name), status=status, executedAt=executed, recordedAt=recorded))
        facts = {'source.authority_domain': domain, 'source.sequence': str(seq), 'source.change': kind, 'time_basis': 'JOURNAL_RECORDED',
                 'trade.asset_atomicity': 'NOT_PROVEN_BY_HISTORY_JOURNAL', 'trade.record_id': trade_id(name), 'trade.status': status,
                 'trade.executed_at': executed, 'trade.journal_recorded_at': recorded, 'trade.resource': 'agricultural',
                 'trade.amount': '2', 'trade.price_per_unit': '5.0', 'trade.total_value': '10.0', 'trade.tax_amount': '0.5', 'trade.net_value': '9.5',
                 'trade.order_type': 'BUY', 'trade.order_source': 'PLAYER', 'trade.seller_player': players['A'], 'trade.buyer_player': players['B'],
                 'trade.seller_nation': seeded['A'], 'trade.buyer_nation': seeded['B'], 'trade.notes_sha256': stable(notes), 'trade.notes_length': str(len(notes)),
                 'title': '贸易历史记录更新' if kind == 'UPSERT' else '贸易历史维护清理'}
        proof = f'Native TradeHistoryService journal; domain={domain}; sequence={seq}; kind={kind}; record={trade_id(name)}; executedAt={executed}; recordedAt={recorded}; journal and history row share a commit; external assets do not; BASELINE is surviving state, DELETE is maintenance, REFUNDED status does not prove refund completion'
        expected_events.append(normalized(dict(id=f'starcore:trade-history:{domain}:{seq}', type='TRADE_HISTORY_RECORDED' if kind == 'UPSERT' else 'TRADE_HISTORY_REMOVED',
                                               occurredAt=recorded, participants=[players['A'], players['B']], nations=[seeded['A'], seeded['B']], facts=facts, source='StarCore.TradeHistoryService', evidence=proof)))
    writes = [r for r in fixture if r['kind'] == 'TRADE_WRITE']
    disputes = [r for r in fixture if r['kind'] == 'TRADE_DISPUTED']
    cleans = [r for r in fixture if r['kind'] == 'TRADE_CLEANED']
    check(len(writes) == 2 and all(r['boot'] == 1 for r in writes), 'Expected two original history-store writes')
    for row, name in zip(writes, ('first', 'late')):
        check(row['data'] == dict(name=name, id=trade_id(name), executedAt=snapshots[0 if name == 'first' else 1]['executedAt'], domain=domain, assetMovement=False), 'History write identity or asset claim changed')
    check(len(disputes) == 1 and disputes[0]['boot'] == 1 and disputes[0]['data'] == {'id': trade_id('first')}, 'Missing original status update')
    check(len(cleans) == 1 and cleans[0]['boot'] == 2 and cleans[0]['data'] == {'removed': 2, 'refundClaimed': False}, 'Maintenance is not an economic reversal')
    timeline = [writes[0], observations[1][0], writes[1], observations[1][1], disputes[0], observations[1][2], observations[2][0], cleans[0], observations[2][1], observations[3][0]]
    check(all(instant(a['time']) < instant(b['time']) for a, b in zip(timeline, timeline[1:])), 'Trade scenario causal order changed')
    for seq, operation, before in [(1, writes[0], lifetimes[1]['started_at']), (2, writes[1], instant(observations[1][0]['time'])), (3, disputes[0], instant(observations[1][1]['time'])), (4, cleans[0], instant(observations[2][0]['time'])), (5, cleans[0], instant(observations[2][0]['time']))]:
        check(before <= changes[seq - 1]['recorded_at'] / 1000 <= instant(operation['time']), 'Journal time outside original operation')
    for rows in observations.values():
        for row in rows:
            data = row['data']; count = data['expected']
            check(data['domain'] == domain and data['contributions'] == 0, 'Live source domain or contribution claim changed')
            check(data['native'] == native_summaries[:count], 'Live native journal differs from persisted snapshots')
            actual = sorted((normalized(event) for event in data['events']), key=lambda e: int(e['facts']['source.sequence']))
            check(actual == expected_events[:count], 'Live Chronicle history differs from immutable native journal')
    with closing(EPIC.connect(run / 'plugins/StarChronicle/chronicle.db')) as db:
        cursors = [dict(r) for r in db.execute('select * from committed_history_sources where source=?', (SOURCE,))]
        check(cursors == [dict(source=SOURCE, authority_domain=domain, sequence=5)], 'Chronicle source domain/cursor mismatch')
        inbox = [dict(r) for r in db.execute('select * from committed_history_ingress where source=? order by sequence', (SOURCE,))]
        check(len(inbox) == 5 and [r['sequence'] for r in inbox] == [1, 2, 3, 4, 5], 'Missing immutable inbox row')
        for seq, (row, expected) in enumerate(zip(inbox, expected_events), 1):
            check(row['source'] == SOURCE and row['status'] == 'ACKED' and row['event_id'] == expected['id'], 'Inbox acknowledgement or identity mismatch')
            check(normalized(json.loads(row['json'])) == expected, 'Inbox differs from native journal')
        events = [dict(r) for r in db.execute("select * from events where json_extract(json,'$.source')='StarCore.TradeHistoryService' or type like 'TRADE_HISTORY_%' or id like 'starcore:trade-history:%' order by id")]
        check(len(events) == 5, 'Expected exactly five retained trade events')
        for row, expected in zip(events, expected_events):
            check(normalized(json.loads(row['json'])) == expected and row['id'] == expected['id'] and row['type'] == expected['type'] and instant(row['time']) == instant(expected['occurredAt']), 'Persisted trade event differs from journal')
            canonical = dict(id=expected['id'], type=expected['type'], time=expected['occurredAt'], players=expected['participants'], nations=expected['nations'], facts=expected['facts'], source=expected['source'], evidence=expected['evidence'])
            check(row['hash'] == stable(json.dumps(canonical, ensure_ascii=False, separators=(',', ':'), sort_keys=True)), 'Trade canonical event hash mismatch')
        check(db.execute('select count(*) from contributions').fetchone()[0] == 0, 'History storage cannot prove contributions')
        check(db.execute('select count(*) from epic_campaigns').fetchone()[0] == 0, 'Unexpected epic campaign in trade fixture')
    return dict(status='PASS', chronicle_sha256=manifest['plugin_sha256'], starcore_sha256=manifest['starcore_sha256'], authority_domain=domain,
                verified_boots=3, normal_process_exits=6, actual_command_results=3, live_trade_checks=6, native_changes=5,
                immutable_inbox_rows=5, retained_trade_events=5, contributions=0, current_trade_rows=0,
                verification='Actual history-store writes, late execution time, status revision, maintenance deletion and three boots cross-checked against native journal, immutable inbox, canonical events and live API/commands.',
                limitations=['No asset movement was performed or proven.', 'REFUNDED is a record status; DELETE is maintenance.', 'Legacy BASELINE migration and concurrency are covered separately by unit tests.'])


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('run', type=Path)
    args = parser.parse_args()
    result = inspect(args.run)
    (args.run / 'evidence/trade-check.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False))
