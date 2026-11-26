"""Independently replay native resource receipts and compare three boots, SQL and public APIs."""
from pathlib import Path
import argparse
import base64
import hashlib
import json
import re
import sqlite3


def check(value, reason):
    if not value:
        raise AssertionError(reason)


def sha(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def db(path):
    return sqlite3.connect(Path(path).resolve().as_uri() + '?mode=ro', uri=True)


def decode(raw):
    p = raw.split('|')
    check(len(p) == 11 and p[0] == '1', 'Invalid native receipt format')
    def text(value):
        return base64.urlsafe_b64decode(value + '=' * (-len(value) % 4)).decode()
    rows = []
    for value in p[10].split(','):
        nation, resource, before, after = value.split(':')
        rows.append(dict(nationId=nation, resourceType=resource, before=int(before), after=int(after)))
    result = dict(authorityDomain=p[1], sequence=int(p[2]), operationId=p[3], kind=p[4], reference=text(p[5]),
                  category=text(p[6]), description=text(p[7]), committedAt=p[9], balances=rows)
    result['actorId'] = None if p[8] == '-' else p[8]
    return result


def expected_step(sequence, payer, receiver):
    if sequence <= 3:
        return 'GRANT', {(payer if sequence < 3 else receiver, 'energy' if sequence == 2 else 'mineral'): 10000}
    return {
        0: ('GRANT', {(payer, 'mineral'): 2}),
        1: ('CONSUME', {(payer, 'mineral'): -1}),
        2: ('TRANSACTION_GRANT', {(receiver, 'energy'): 3}),
        3: ('TRANSACTION_CONSUME', {(payer, 'energy'): -2}),
        4: ('TRANSFER', {(payer, 'mineral'): -1, (payer, 'energy'): -1, (receiver, 'mineral'): 1, (receiver, 'energy'): 1}),
    }[sequence % 5]


def inbox(count):
    return count - 3 + (count + 1) // 5


def inspect(run, schema=9):
    run = Path(run).resolve()
    manifest = json.loads((run / 'preparation.json').read_text())
    for filename, key in (('plugins/StarChronicle.jar', 'product_sha256'), ('plugins/StarCore.jar', 'native_sha256'),
                          ('plugins/ResourceHistoryPaperFixture.jar', 'fixture_sha256'), ('paper.jar', 'paper_sha256'),
                          ('fixture/ResourceHistoryPaperFixture.java', 'fixture_source_sha256')):
        check(sha(run / filename) == manifest[key], 'Artifact fingerprint drift: ' + filename)
    fixtures = [json.loads(line) for line in (run / 'evidence/fixture.jsonl').read_text().splitlines() if line]
    check(not any(r['kind'] == 'FAILED' for r in fixtures), 'Fixture reported a real failure')
    identity, all_events, boot_counts, process_ids = None, {}, [], set()
    for boot, imported, head in ((1, 6, 136), (2, 139, 139), (3, 139, 139)):
        session = json.loads((run / f'evidence/boot{boot}.session.json').read_text())
        check(session['passed'] is True and session['exit_code'] == 0 and not session.get('forced_kill'), 'Incomplete process lifecycle')
        check(session['exited_at'] > session['started_at'], 'Missing process timestamps')
        check(session['pid'] not in process_ids, 'Distinct process restarts are required')
        process_ids.add(session['pid'])
        log = (run / f'evidence/boot{boot}.server.log').read_text()
        check('Done (' in log and 'Stopping server' in log and 'RESOURCE_CLOSED' in log, 'Missing normal enable/disable')
        check(not re.search(r'\bERROR\b|RESOURCE_FAILED|History scan retained pending evidence|Resource history scan retained its cursor|Source event was not accepted|Exception in thread|Server thread dump', log), 'Server error or dropped source evidence')
        check('StarChronicle] Enabling StarChronicle' in log and 'StarCore] Enabling StarCore' in log, 'Missing real two-plugin enable')
        selected = [r for r in fixtures if r['boot'] == boot]
        ready = [r['data'] for r in selected if r['kind'] == 'READY']
        check(len(ready) == 1 and sum(r['kind'] == 'CLOSED' for r in selected) == 1, 'Fixture boot lifecycle mismatch')
        check(identity is None or identity == ready[0], 'Native identity changed between boots')
        identity = ready[0]
        domain, payer, receiver = (identity[k] for k in ('domain', 'payer', 'receiver'))
        checks = [r['data'] for r in selected if r['kind'] == 'CHECK']
        expected_checks = {1: [3, 6], 2: [136, 139], 3: [139, 139]}[boot]
        check([r['count'] for r in checks] == expected_checks, 'Missing live source/API checkpoint')
        check([r['events'] for r in session['checkpoints']] == expected_checks, 'Missing live SQL checkpoint')
        for point in session['checkpoints']:
            check(point['archive'] == 3 and point['pending'] == 0 and point['triggers'] == inbox(point['events']), 'Initial archive or live trigger checkpoint changed')
        replay = [r['data'] for r in selected if r['kind'] == 'REPLAY']
        start = expected_checks[0]
        expected_replays = sum(i > 3 and i % 5 in (2, 3, 4) for i in range(1, start + 1))
        check(replay == [dict(count=start, rejections=3, replays=expected_replays)], 'Missing native rejection and replay proof')
        data = run / f'evidence/boot{boot}.data'
        with db(data / 'StarCore/starcore.db') as native:
            check(native.execute('PRAGMA integrity_check').fetchone()[0] == 'ok', 'Native database integrity failed')
            state = dict(native.execute('SELECT property_key,property_value FROM starcore_resource_state'))
            check(state.get('resource.history.domain') == domain and state.get('resource.history.sequence') == str(head), 'Native source head or domain changed')
            transfers = list(native.execute('SELECT transaction_id,payer_nation_id,receiver_nation_id,resources,sequence FROM starcore_resource_transfer_receipts ORDER BY sequence'))
            expected_transfers = [i for i in range(4, head + 1) if i % 5 == 4]
            check(transfers == [(f'resource-fixture:transfer:{i}', payer, receiver, 'energy=1;mineral=1', n) for n, i in enumerate(expected_transfers, 1)], 'Native transfer ledger disagrees with resource receipts')
        history = data / 'StarCore/resource/history'
        check((data / 'StarCore/resource/resource-sql-authority').read_text() == 'READY', 'SQL authority pin missing')
        check((history / 'authority-domain').read_text() == domain, 'Native authority marker mismatch')
        check((history / 'high-water').read_text() == f'{domain}|{head}', 'Native high-water mark mismatch')
        paths = list((history / domain).rglob('*.receipt'))
        check(sorted(int(p.stem) for p in paths) == list(range(1, head + 1)), 'Missing native committed receipt')
        records = {int(p.stem): decode(p.read_text()) for p in paths}
        check(decode(state['resource.history.pending']) == records[head], 'Pending native outbox changed')
        check(len({r['operationId'] for r in records.values()}) == head, 'Native operation ID reused')
        check(any(re.search(r'\.\d{4,9}Z$', r['committedAt']) for r in records.values()), 'No submillisecond commit evidence')
        balances = {}
        for seq in range(1, head + 1):
            r = records[seq]
            check(r['sequence'] == seq and r['authorityDomain'] == domain and r.get('actorId') is None, 'Source identity or actor changed')
            kind, deltas = expected_step(seq, payer, receiver)
            check(r['kind'] == kind and {(b['nationId'], b['resourceType']): b['after'] - b['before'] for b in r['balances']} == deltas, 'Native resource operation changed')
            check(len(r['balances']) == len(deltas), 'Repeated native balance row')
            expected_ref = f'resource-fixture:transfer:{seq}' if kind == 'TRANSFER' else f'resource-fixture:transaction:{seq}' if kind.startswith('TRANSACTION_') else ''
            check(r['reference'] == expected_ref, 'Native resource reference changed')
            for b in r['balances']:
                key = b['nationId'], b['resourceType']
                check(b['before'] == balances.get(key, 0) and b['after'] >= 0, 'Native balance chain changed')
                balances[key] = b['after']
        stockpile = {(state[f'stockpile.{i}.nationId'], state[f'stockpile.{i}.type']): int(state[f'stockpile.{i}.amount']) for i in range(int(state['count']))}
        check(stockpile == balances, 'Persisted inventory differs from receipt replay')
        expected_tx = [i for i in range(4, head + 1) if i % 5 in (2, 3)]
        check(int(state['transaction.count']) == len(expected_tx), 'Transaction receipt count changed')
        actual_tx = {state[f'transaction.{i}.id']: i for i in range(int(state['transaction.count']))}
        check(set(actual_tx) == {f'resource-fixture:transaction:{i}' for i in expected_tx}, 'Native idempotent transaction receipts changed')
        with db(data / 'StarChronicle/chronicle.db') as chronicle:
            check(chronicle.execute('PRAGMA user_version').fetchone()[0] == schema, 'Chronicle schema downgrade boundary changed')
            check(chronicle.execute('PRAGMA integrity_check').fetchone()[0] == 'ok', 'Chronicle database integrity failed')
            check(not chronicle.execute('PRAGMA foreign_key_check').fetchall(), 'Chronicle foreign keys failed')
            cursor = chronicle.execute("SELECT authority_domain,sequence,live FROM committed_history_sources WHERE namespace='starcore.resources'").fetchone()
            check(cursor == (domain, imported, 1), 'Chronicle cursor does not match native domain/sequence')
            ingress = chronicle.execute("SELECT sequence,status,activate,json FROM committed_history_ingress WHERE namespace='starcore.resources' ORDER BY sequence").fetchall()
            check([r[0] for r in ingress] == list(range(1, imported + 1)), 'Missing or repeated ingress sequence')
            check(all(r[1] == 'ACKED' and r[2] == int(r[0] > 3) for r in ingress), 'Pending ingress or initial archive mode changed')
            events = [json.loads(r[0]) for r in chronicle.execute('SELECT json FROM events')]
            exact = {int(e['facts']['history.sequence']): e for e in events if e['facts'].get('history.namespace') == 'starcore.resources'}
            full = {row[0]: (json.loads(row[1]), row[1]) for row in chronicle.execute('SELECT sequence,json FROM resource_history_receipts')}
            staged = {json.loads(r[0])['sequence']: json.loads(r[0]) for r in chronicle.execute('SELECT json FROM committed_resource_ingress')}
            check(set(full) == set(staged) == set(exact) == set(range(1, imported + 1)), 'Missing full resource receipt or event')
            for seq, event in exact.items():
                original = records[seq]
                check(full[seq][0] == original and staged[seq] == original, 'Full resource receipt changed')
                check(event['id'] == f'starcore:resource:{domain}:{original["operationId"]}', 'Event operation identity changed')
                check(event['occurredAt'] == original['committedAt'], 'Native timestamp precision changed')
                nations = {b['nationId'] for b in original['balances']}
                check(event['participants'] == [] and set(event['nations']) == nations, 'False historical player/nation attribution')
                check(event['type'] == 'RESOURCE_BALANCE_CHANGED', 'Wrong history event type')
                expected = {'history.namespace': 'starcore.resources', 'history.authority': domain, 'history.sequence': str(seq),
                            'history.operation': original['operationId'], 'resource.kind': original['kind'], 'resource.reference': original['reference'],
                            'resource.category': original['category'], 'resource.description': original['description'],
                            'resource.balance_count': str(len(original['balances'])), 'time_basis': 'COMMITTED',
                            'resource.receipt_sha256': hashlib.sha256(full[seq][1].encode()).hexdigest(),
                            'contribution.omission': 'BALANCE_CHANGE_IS_NOT_PRODUCTION_OR_NEW_AID_OR_TRADE'}
                check(all(event['facts'].get(k) == v for k, v in expected.items()), 'Resource event facts changed')
                check(json.loads(ingress[seq - 1][3]) == event, 'Pending and canonical event disagree')
                check(seq not in all_events or all_events[seq] == event, 'Event changed after restart')
                all_events[seq] = event
            triggers = [r[0] for r in chronicle.execute("SELECT event_id FROM story_triggers WHERE json_extract(story_json,'$.id')='resource-history-test'")]
            expected_triggers = sorted(e['id'] for seq, e in exact.items() if seq > 3 for _ in e['nations'])
            check(sorted(triggers) == expected_triggers and len(triggers) == inbox(imported), 'Archive triggered or live commit lost its inbox')
            for table in ('instances', 'outcomes', 'contributions', 'memory_entries'):
                check(chronicle.execute('SELECT COUNT(*) FROM ' + table).fetchone()[0] == 0, 'Offline source produced unintended effects: ' + table)
        for checkpoint in checks:
            check(len(checkpoint['events']) == len(checkpoint['receipts']) == checkpoint['count'], 'Live API omitted source events or receipts')
            check(sorted(r['sequence'] for r in checkpoint['receipts']) == list(range(1, checkpoint['count'] + 1)), 'Public receipt sequence repeated or missing')
            for event in checkpoint['events']:
                check(event == exact.get(int(event['facts']['history.sequence'])), 'Public event API differs from storage')
            for receipt in checkpoint['receipts']:
                check(receipt == records.get(receipt['sequence']), 'Public receipt API differs from native evidence')
        boot_counts.append(dict(boot=boot, native_head=head, imported=imported, initial_archive=3, story_inbox=inbox(imported)))
    offline = [r for r in fixtures if r['kind'] == 'DONE' and r['data']['action'] == 'offline']
    check(len(offline) == 1 and offline[0]['boot'] == 1, 'Missing Chronicle-offline source mutations')
    native_logs = [r['data'] for r in fixtures if r['kind'] == 'NATIVE']
    check([r['count'] for r in native_logs] == [3, 6, 136, 139], 'Missing real mutation batches')
    for item in native_logs:
        check(item['records'] == [records[i] for i in range(1, item['count'] + 1)], 'Native public receipt log differs from persistent journal')
        for key in ('payer', 'receiver'):
            reconstructed = {}
            for r in item['records']:
                for b in r['balances']:
                    if b['nationId'] == identity[key]: reconstructed[b['resourceType']] = b['after']
            check({k: v for k, v in item[key].items() if v} == reconstructed, 'Native public stockpile differs from replay')
    return dict(status='PASS', chronicle_sha256=manifest['product_sha256'], starcore_sha256=manifest['native_sha256'],
                schema=schema, verified_boots=3, native_changes=139, archive_only=3, live_history=136, story_inbox=164,
                recovered_after_offline=130, source_domain=identity['domain'], boot_counts=boot_counts,
                scope='Isolated Paper native resource grant/consume/transaction/transfer, full history receipts, paging and restarts; offline identities, no production completion or full combination claim')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('run', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    result = inspect(args.run)
    if args.output:
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(result, ensure_ascii=False))
