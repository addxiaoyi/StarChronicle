"""Verify real native technology unlock/revoke commits across three stopped Paper boots."""
from contextlib import closing
from pathlib import Path
import argparse
import base64
import importlib.util
import json
import re
import socket
import uuid

SPEC = importlib.util.spec_from_file_location('trade_inspector', Path(__file__).with_name('inspect-trade-paper.py'))
TRADE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(TRADE)
EPIC, check, records, instant, normalized, stable = TRADE.EPIC, TRADE.check, TRADE.records, TRADE.instant, TRADE.normalized, TRADE.stable
SOURCE = 'starcore.technology.history.v1'


def decode_receipt(value):
    parts = value.split('|')
    check(len(parts) == 9 and parts[0] == '1', 'Invalid native technology receipt format')
    def text(value):
        result = base64.urlsafe_b64decode(value + '=' * (-len(value) % 4)).decode('utf-8')
        check(base64.urlsafe_b64encode(result.encode()).decode().rstrip('=') == value, 'Noncanonical native receipt metadata')
        return result
    for index in (1, 3, 4):
        check(str(uuid.UUID(parts[index])) == parts[index], 'Invalid native receipt identity')
    return dict(domain=parts[1], sequence=int(parts[2]), operation=parts[3], nation=parts[4], technology=text(parts[5]), era=text(parts[6]), change=parts[7], committedAt=parts[8])


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
    bound = next(r['data'] for r in fixture if r['kind'] == 'TECH_BOUND')
    check(bound['nation'] == seeded['A'], 'Technology must belong to the original nation')
    domain, nation, key = (bound[k] for k in ('domain', 'nation', 'technology'))
    observations, lifetimes = {}, {}
    for boot, scenario in enumerate(('tech-first', 'tech-second', 'tech-third'), 1):
        session = EPIC.read(evidence / f'boot{boot}.session.json')
        check(session['boot'] == boot and session['scenario'] == scenario and session['status'] == 'COMPLETE', 'Incomplete controller scenario')
        processes = session['processes']
        check(len(processes) == 2 and {p['name'] for p in processes} == {'server', 'clients'}, 'Missing actual server/client processes')
        check(all(p.get('exit_code') == 0 and p['exited_at'] >= p['started_at'] for p in processes), 'Abnormal process exit')
        server_process = next(p for p in processes if p['name'] == 'server'); lifetimes[boot] = server_process
        client_process = next(p for p in processes if p['name'] == 'clients')
        rows = [r for r in fixture if r['boot'] == boot]
        for kind in ('READY', 'SEEDED', 'CLOSED', 'TECH_BOUND'):
            check(sum(r['kind'] == kind for r in rows) == 1, 'Missing boot lifecycle: ' + kind)
        check(next(r['data'] for r in rows if r['kind'] == 'READY') == {'product': manifest['plugin_sha256'], 'native': manifest['starcore_sha256'], 'route': manifest['route']}, 'Running artifact mismatch')
        check(next(r['data'] for r in rows if r['kind'] == 'SEEDED') == seeded and seeded['nonOp'] is True, 'Native seed identity or permissions changed')
        check(next(r['data'] for r in rows if r['kind'] == 'TECH_BOUND') == bound, 'Technology binding changed after restart')
        check(next(r['data'] for r in rows if r['kind'] == 'CLOSED') == {'normalDisable': True}, 'Abnormal fixture shutdown')
        check(all(server_process['started_at'] <= instant(r['time']) <= server_process['exited_at'] for r in rows), 'Fixture event outside server lifetime')
        server = (evidence / f'boot{boot}.server.log').read_text(encoding='utf-8')
        diagnostics = re.findall(r'^.*(?:ERROR\]|ERROR\]:|Failed to (?:enable|disable) STARCORE module|同步保存失败|异步保存失败|SQLITE_BUSY|startup failed|Could not pass event|Committed history source .* retained|History scan retained pending evidence|Failed to persist technology).*$', server, re.M | re.I)
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
        observations[boot] = [r for r in rows if r['kind'] == 'TECH_CHECK']
        check([r['data']['expected'] for r in observations[boot]] == {1: [0, 3, 3], 2: [3, 4], 3: [4]}[boot], 'Missing live technology API observation')
        for kind in ('TECH_BOUND', 'TECH_CYCLE', 'TECH_REPEAT', 'TECH_REVOKED', 'TECH_CHECK'):
            logged = [json.loads(line.split('EPIC_' + kind + ' ', 1)[1]) for line in server.splitlines() if 'EPIC_' + kind + ' ' in line]
            check(logged == [r['data'] for r in rows if r['kind'] == kind], 'Live technology evidence absent from server stream')
        check(instant(observations[boot][-1]['time']) < instant(command['time']), 'Query must follow history observation')
    check(all(lifetimes[b]['exited_at'] < lifetimes[b + 1]['started_at'] for b in (1, 2)), 'Server boots overlap')
    native_dir = run / 'plugins/StarCore/technology'
    check((native_dir / 'technology-sql-authority').read_text() == 'SQL', 'Native SQL authority marker mismatch')
    check((native_dir / 'history/authority-domain').read_text() == domain, 'Native history authority marker mismatch')
    files = sorted((native_dir / 'history').rglob('*.receipt'))
    expected_paths = [native_dir / f'history/{domain}/0/{seq}.receipt' for seq in range(1, 5)]
    check(files == expected_paths, 'Missing or extra immutable technology receipt')
    receipts = [decode_receipt(path.read_text()) for path in files]
    check([r['sequence'] for r in receipts] == [1, 2, 3, 4] and [r['change'] for r in receipts] == ['UNLOCKED', 'REVOKED', 'UNLOCKED', 'REVOKED'], 'Native technology sequence/change mismatch')
    check(len({r['operation'] for r in receipts}) == 4, 'Native operation identity reused')
    check(all(r['domain'] == domain and r['nation'] == nation and r['technology'] == key and r['era'] == receipts[0]['era'] for r in receipts), 'Native receipt identity/metadata differs from binding')
    with closing(EPIC.connect(run / 'plugins/StarCore/starcore.db')) as db:
        state = dict(db.execute('select property_key,property_value from starcore_technology_state'))
        check(state == {'count': '0', 'history.domain': domain, 'history.sequence': '4', 'history.pending': files[-1].read_text()}, 'Native final state/outbox differs from retained receipts')
    cycle = [r for r in fixture if r['kind'] == 'TECH_CYCLE']; repeat = [r for r in fixture if r['kind'] == 'TECH_REPEAT']; revoke = [r for r in fixture if r['kind'] == 'TECH_REVOKED']
    check(len(cycle) == 1 and cycle[0]['boot'] == 1 and cycle[0]['data'] == {'receipts': receipts[:3], 'unlocked': True, 'researchCompletionClaimed': False}, 'Native rapid unlock/revoke cycle differs')
    check(len(repeat) == 1 and repeat[0]['boot'] == 1 and repeat[0]['data'] == {'changes': 3, 'researchCompletionClaimed': False}, 'Missing idempotent repeated unlock')
    check(len(revoke) == 1 and revoke[0]['boot'] == 2 and revoke[0]['data'] == {'receipts': receipts, 'unlocked': False}, 'Native restarted revoke differs')
    timeline = [observations[1][0], cycle[0], observations[1][1], repeat[0], observations[1][2], observations[2][0], revoke[0], observations[2][1], observations[3][0]]
    check(all(instant(a['time']) < instant(b['time']) for a, b in zip(timeline, timeline[1:])), 'Technology scenario causal order changed')
    for index, receipt in enumerate(receipts):
        start, end = (observations[1][0], cycle[0]) if index < 3 else (observations[2][0], revoke[0])
        check(instant(start['time']) < instant(receipt['committedAt']) < instant(end['time']), 'Native commit outside original operation')
    expected_events = []
    for r in receipts:
        sequence, operation, change = r['sequence'], r['operation'], r['change']
        facts = {'title': '国家科技解锁' if change == 'UNLOCKED' else '国家科技撤销', 'source.authority_domain': domain, 'source.sequence': str(sequence),
                 'source.operation_id': operation, 'time_basis': 'COMMITTED', 'technology': key, 'tech.era': r['era'], 'tech.change': change,
                 'tech.researcher': 'NOT_RECORDED', 'tech.research_completion': 'NOT_PROVEN_BY_UNLOCK'}
        proof = f'Native TechnologyService committed state/history receipt; domain={domain}; sequence={sequence}; operation={operation}; change={change}; technology={key}; nation={nation}; researcher is not recorded; unlock is not proof of research completion; existing pre-protocol unlocks are baseline state, not reconstructed changes'
        expected_events.append(dict(id=f'starcore:technology-history:{domain}:{sequence}', type='TECHNOLOGY_' + change, occurredAt=r['committedAt'], participants=[], nations=[nation], facts=facts, source='StarCore.TechnologyService', evidence=proof))
    for rows in observations.values():
        for row in rows:
            data = row['data']; count = data['expected']
            check(data['domain'] == domain and data['contributions'] == 0 and data['unlocked'] == (count == 3), 'Live technology domain/state/contribution mismatch')
            check(data['native'] == receipts[:count], 'Live native history differs from immutable receipt files')
            actual = sorted((normalized(event) for event in data['events']), key=lambda e: int(e['facts']['source.sequence']))
            check(actual == expected_events[:count], 'Live Chronicle technology history differs from native receipts')
    with closing(EPIC.connect(run / 'plugins/StarChronicle/chronicle.db')) as db:
        cursors = [dict(r) for r in db.execute('select * from committed_history_sources where source=?', (SOURCE,))]
        check(cursors == [dict(source=SOURCE, authority_domain=domain, sequence=4)], 'Chronicle technology domain/cursor mismatch')
        inbox = [dict(r) for r in db.execute('select * from committed_history_ingress where source=? order by sequence', (SOURCE,))]
        check(len(inbox) == 4 and [r['sequence'] for r in inbox] == [1, 2, 3, 4], 'Missing immutable technology inbox')
        for row, expected in zip(inbox, expected_events):
            check(row['status'] == 'ACKED' and row['event_id'] == expected['id'], 'Technology inbox acknowledgement/identity mismatch')
            check(normalized(json.loads(row['json'])) == expected, 'Technology inbox differs from native receipt')
        events = [dict(r) for r in db.execute("select * from events where json_extract(json,'$.source')='StarCore.TechnologyService' or id like 'starcore:technology-history:%' or type in ('TECHNOLOGY_UNLOCKED','TECHNOLOGY_REVOKED') order by id")]
        check(len(events) == 4, 'Expected four retained technology events')
        for row, expected in zip(events, expected_events):
            check(normalized(json.loads(row['json'])) == expected and row['id'] == expected['id'] and row['type'] == expected['type'] and instant(row['time']) == instant(expected['occurredAt']), 'Persisted technology event differs from receipt')
            canonical = dict(id=expected['id'], type=expected['type'], time=expected['occurredAt'], players=[], nations=[nation], facts=expected['facts'], source=expected['source'], evidence=expected['evidence'])
            check(row['hash'] == stable(json.dumps(canonical, ensure_ascii=False, separators=(',', ':'), sort_keys=True)), 'Technology canonical event hash mismatch')
        check(db.execute('select count(*) from contributions').fetchone()[0] == 0, 'Technology facts cannot prove player contributions')
        check(db.execute('select count(*) from epic_campaigns').fetchone()[0] == 0, 'Unexpected epic campaign in technology fixture')
    return dict(status='PASS', chronicle_sha256=manifest['plugin_sha256'], starcore_sha256=manifest['starcore_sha256'], authority_domain=domain,
                verified_boots=3, normal_process_exits=6, actual_command_results=3, live_technology_checks=6, native_changes=4, immutable_inbox_rows=4,
                retained_technology_events=4, contributions=0, current_unlocked_count=0,
                verification='Native unlock/revoke/reunlock, repeated unlock without a new receipt, restarted revoke, authority files, SQL outbox, canonical events and live API/commands cross-checked.',
                limitations=['Direct native unlock/revoke is not timed research completion.', 'Native receipts do not identify a researcher.', 'Existing pre-protocol state does not reconstruct historical changes.'])


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument('run', type=Path); args = parser.parse_args()
    result = inspect(args.run)
    (args.run / 'evidence/technology-check.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False))
