"""Check native receipts against Chronicle SQL, public balances, status revisions and restart snapshots."""
from pathlib import Path
from decimal import Decimal
import argparse
import collections
import hashlib
import importlib.util
import json
import re
from trade_contribution_protocol import artifact_schema, history_warning, injected_fault

spec = importlib.util.spec_from_file_location('native_trade_inspector', Path(__file__).with_name('inspect-trade-history.py'))
native = importlib.util.module_from_spec(spec)
spec.loader.exec_module(native)
require, sha, read_db = native.require, native.sha, native.read_db
STATUS = {'COMPLETED': 'ACTIVE', 'DISPUTED': 'SUSPENDED', 'REFUNDED': 'REFUNDED', 'CANCELLED': 'CANCELLED'}


def event_id(receipt):
    return f"starcore:trade-change:{receipt['domain']}:{receipt['sequence']}"


def canonical_event(event):
    # Java Set iteration order is intentionally unspecified, including across JSON decoding.
    return dict(event, participants=sorted(event['participants']), nations=sorted(event['nations']))


def full_receipt(receipt):
    r = receipt['record']
    return dict(authorityDomain=receipt['domain'], sequence=receipt['sequence'], committedAt=receipt['committedAt'],
                occurredAt=receipt['occurredAt'], previousStatus=receipt['previousStatus'], trade=dict(
                    recordId=r['id'], orderType=r['type'], orderSource=r['source'], sellerPlayer=r.get('seller'), buyerPlayer=r.get('buyer'),
                    sellerNation=r.get('sellerNation'), buyerNation=r.get('buyerNation'), resource=r['resource'], amount=r['amount'],
                    pricePerUnit=float(r['price']), totalValue=r['amount'] * float(r['price']), taxAmount=float(r['tax']),
                    netValue=r['amount'] * float(r['price']) - float(r['tax']), executedAt=r['executedAt'],
                    buyOrder=r.get('buyOrder'), sellOrder=r.get('sellOrder'), status=r['status'], notes=r.get('notes')))


def subject_rows(receipt):
    r = receipt['record']
    result = {}
    for role in ('seller', 'buyer'):
        actor, nation = r.get(role), r.get(role + 'Nation')
        if actor:
            result.setdefault('player:' + actor, (actor, nation))
            if nation:
                result.setdefault('nation:' + nation, (actor, nation))
    return result


def expected_projection(receipts):
    originals, revisions, states = {}, [], {}
    per_trade = collections.defaultdict(set)
    for receipt in receipts:
        r = receipt['record']
        operation = 'starcore:trade:' + receipt['domain'] + ':' + r['id']
        if r['status'] == 'COMPLETED':
            for subject, (actor, nation) in subject_rows(receipt).items():
                entry_id = 'chronicle:contribution:' + hashlib.sha256((operation + '|TRADE|' + subject).encode()).hexdigest()
                originals.setdefault(entry_id, dict(operationId=operation, type='TRADE', subjectId=subject, sourceEventId=event_id(receipt),
                    actorId=actor, nationId=nation, amount=r['amount'], unit=r['resource'], committedAt=receipt['committedAt']))
                per_trade[r['id']].add(entry_id)
        for entry_id in sorted(per_trade[r['id']]):
            revision = dict(contributionId=entry_id, sourceEventId=event_id(receipt), status=STATUS[r['status']], committedAt=receipt['committedAt'])
            revisions.append(revision)
            states[entry_id] = revision
    return originals, revisions, states


def check_projection(entries, revisions, totals, receipts):
    originals, expected_revisions, states = expected_projection(receipts)
    require(len(entries) == len(originals), 'Missing or duplicate public/SQL contribution')
    actual = {e['entry']['id']: e for e in entries}
    require(set(actual) == set(originals), 'Wrong contribution identity or historical subject')
    expected_totals = collections.defaultdict(Decimal)
    for entry_id, request in originals.items():
        entry = actual[entry_id]
        require(all(entry['entry']['request'].get(k) == v for k, v in request.items()), 'Contribution changed original actor, nation, amount, unit or source')
        state = states[entry_id]
        require((entry['status'], entry['statusEventId'], entry['statusCommittedAt']) == (state['status'], state['sourceEventId'], state['committedAt']), 'Net contribution status differs from native history')
        if state['status'] == 'ACTIVE':
            expected_totals[request['subjectId']] += Decimal(request['amount'])
    key = lambda r: (r['contributionId'], r['sourceEventId'], r['status'], r['committedAt'])
    require(collections.Counter(map(key, revisions)) == collections.Counter(map(key, expected_revisions)), 'Status revision evidence missing, repeated or reordered')
    require(len({r['revision'] for r in revisions}) == len(revisions), 'Repeated revision sequence')
    by_entry = collections.defaultdict(list)
    for revision in revisions:
        by_entry[revision['contributionId']].append(revision)
    for trail in by_entry.values():
        ordered = sorted(trail, key=lambda r: r['revision'])
        require([int(r['sourceEventId'].rsplit(':', 1)[1]) for r in ordered] == sorted(int(r['sourceEventId'].rsplit(':', 1)[1]) for r in ordered), 'Revisions do not preserve source sequence')
    require({k: Decimal(str(v)) for k, v in totals.items()} == dict(expected_totals), 'Public net totals disagree with native status history')


def inspect(run):
    run = Path(run).resolve()
    manifest = json.loads((run / 'preparation.json').read_text())
    for filename, key in (('plugins/StarChronicle.jar', 'product_sha256'), ('plugins/StarCore.jar', 'native_sha256'),
            ('plugins/TradeContributionPaperFixture.jar', 'fixture_sha256'), ('fixture/TradeContributionPaperFixture.java', 'fixture_source_sha256'), ('paper.jar', 'paper_sha256')):
        require(sha(run / filename) == manifest[key], 'Artifact fingerprint drift: ' + filename)
    schema = artifact_schema(run / 'plugins/StarChronicle.jar')
    require(schema == manifest.get('schema_version', 8), 'Preparation/product schema mismatch')
    fixture = [json.loads(line) for line in (run / 'evidence/fixture.jsonl').read_text().splitlines() if line]
    require(not any(r['kind'] == 'FAILED' for r in fixture), 'Fixture failure')
    identity = next(r['data'] for r in fixture if r['kind'] == 'READY')
    previous_receipts, previous_events, previous_entries, previous_revisions, previous_txs = {}, {}, {}, {}, {}
    previous_assets = None
    boots = []
    for boot, source_count, consumer_count, settlements in ((1, 136, 135, 133), (2, 139, 139, 134), (3, 139, 139, 134)):
        session = json.loads((run / f'evidence/boot{boot}.session.json').read_text())
        require(session['passed'] is True and session['exit_code'] == 0 and not session.get('forced_kill'), 'Incomplete process lifecycle')
        require(session['exited_at'] > session['started_at'], 'Missing process timestamps')
        log = (run / f'evidence/boot{boot}.server.log').read_text()
        require(all(marker in log for marker in ('Done (', 'Stopping server', 'TRADE_CONTRIB_CLOSED', 'StarChronicle] Enabling StarChronicle', 'StarCore] Enabling StarCore')), 'Missing paired plugin lifecycle')
        require(not re.search(r' ERROR\]|TRADE_CONTRIB_FAILED|Exception in thread|Trade history reconciliation failed', log), 'Server error')
        faults = [line for line in log.splitlines(keepends=True) if history_warning(line)]
        require(faults == session['expected_faults'], 'Unaccounted source or consumer fault')
        require(bool(faults) == (boot == 1), 'Faults outside first boot')
        if boot == 1:
            for line in faults:
                require(injected_fault(line, schema) in ('ingress', 'revision'), 'Unexpected injected error text')
            require(any('injected trade ingress failure' in f for f in faults) and any('injected contribution revision failure' in f for f in faults), 'Missing either fault scenario')
            staged = session['stage_failure_state']
            require(staged['native'][1] == 133 and staged['cursor'][1] == 3 and staged['pending'] == 0 and staged['receipts'] == 3, 'Staging failure advanced cursor or left partial ingress')
            failed = session['injected_failure_state']
            require(failed['native'][1] == 136 and failed['cursor'][1] == 136 and failed['receipts'] == 135 and failed['pending'] == 1, 'Consumer failure lost pending receipt')
        selected = [r for r in fixture if r['boot'] == boot]
        require(sum(r['kind'] == 'READY' for r in selected) == 1 and sum(r['kind'] == 'CLOSED' for r in selected) == 1, 'Missing/duplicate fixture lifecycle')
        ready = next(r['data'] for r in selected if r['kind'] == 'READY')
        require(all(ready[k] == identity[k] for k in ('domain', 'buyer', 'seller', 'buyerNation', 'sellerNation')), 'Historical identity changed across restart')
        if previous_assets is not None:
            require(native.normalized_assets(ready['balances']) == previous_assets, 'Restart changed assets')
        checks = [r['data'] for r in selected if r['kind'] == 'CHECK']
        expected_checks = {1: [3, 133, 134, 135], 2: [136, 137, 138, 139], 3: [139, 139]}[boot]
        require([r['count'] for r in checks] == expected_checks, 'Missing public checkpoint')
        require([r['receipts'] for r in session['checkpoints']] == expected_checks, 'Missing live SQL checkpoint')
        data = run / f'evidence/boot{boot}.data'
        txs = native.transactions(data / 'StarCore/economy/transaction-fabric.properties')
        with read_db(data / 'StarCore/starcore.db') as core, read_db(data / 'StarChronicle/chronicle.db') as chronicle:
            for db in (core, chronicle):
                require(db.execute('PRAGMA integrity_check').fetchall() == [('ok',)], 'Database integrity failure')
                require(db.execute('PRAGMA foreign_key_check').fetchall() == [], 'Broken foreign key')
            require(chronicle.execute('PRAGMA user_version').fetchone() == (schema,), 'Wrong net-ledger schema')
            require(core.execute('SELECT domain_id,last_sequence,initialized FROM trade_history_authority').fetchall() == [(identity['domain'], source_count, 1)], 'Native head/domain mismatch')
            rows = core.execute('SELECT sequence_id,committed_at,occurred_at,previous_status,payload FROM trade_history_commits ORDER BY sequence_id').fetchall()
            require([r[0] for r in rows] == list(range(1, source_count + 1)), 'Missing native sequence')
            receipts = {r[0]: dict(domain=identity['domain'], sequence=r[0], committedAt=r[1], occurredAt=r[2], previousStatus=r[3], record=json.loads(r[4])) for r in rows}
            require(all(receipts.get(k) == v for k, v in previous_receipts.items()), 'Native audit changed across restart')
            latest, first = {}, {}
            for receipt in receipts.values():
                r = receipt['record']
                if r['id'] in latest:
                    prior = latest[r['id']]
                    require({k: v for k, v in r.items() if k not in ('notes', 'status')} == {k: v for k, v in prior.items() if k not in ('notes', 'status')}, 'Native historical identity was rewritten')
                    require(receipt['previousStatus'] == prior['status'], 'Native previous status mismatch')
                else:
                    require(receipt['previousStatus'] is None and r['status'] == 'COMPLETED', 'Missing real initial settlement')
                    first[r['id']] = receipt
                latest[r['id']] = r
            require(len(first) == settlements, 'Wrong real settlement count')
            identities = {r[0]: json.loads(r[1]) for r in core.execute('SELECT * FROM trade_history_identity')}
            require(identities == latest, 'Native latest identity mismatch')
            for record in latest.values():
                query = core.execute('SELECT executed_at_exact,status,notes FROM trade_history WHERE record_id=?', (record['id'],)).fetchone()
                require(query == (record['executedAt'], record['status'], record.get('notes')), 'Native query view drift')
            intents = core.execute('SELECT operation_id,record_id,planned_payload,applied_events,transaction_id,applied_fingerprint FROM trade_settlement_intents').fetchall()
            require(len(intents) == len(txs) == settlements, 'Missing preparation/transaction proof')
            for op, record_id, planned_json, applied, transaction_id, fingerprint in intents:
                tx = txs['trade-offer:' + op]
                planned = json.loads(planned_json)
                require(tx['state'] == 'COMMITTED' and tx['metadata']['trade.operation'] == op and tx['metadata']['trade.domain'] == identity['domain'], 'Uncommitted or foreign settlement')
                require(json.loads(tx['metadata']['trade.plan']) == planned and planned['id'] == record_id, 'Preparation binding mismatch')
                require(tx['id'] == transaction_id and applied == len(tx['events']) == 5, 'Missing original transaction checkpoint')
                require([e[1] for e in tx['events']] == ['CREATED', 'RESERVED', 'DEBITED', 'DELIVERED', 'COMMITTED'], 'Unexpected financial timeline')
                require(hashlib.sha256(json.dumps(tx['events'], ensure_ascii=False, separators=(',', ':')).encode()).hexdigest() == fingerprint, 'Fabric checkpoint fingerprint changed')
                require(dict(planned, executedAt=tx['updated']) == first[record_id]['record'], 'Completed history does not match native settlement')
            require(all(txs.get(k) == v for k, v in previous_txs.items()), 'History projection repeated a financial operation')
            cursor = chronicle.execute("SELECT authority_domain,sequence,live FROM committed_history_sources WHERE namespace='starcore.trade'").fetchall()
            require(cursor == [(identity['domain'], source_count, 1)], 'Chronicle source cursor mismatch')
            ingress = chronicle.execute("SELECT h.id,h.sequence,h.status,h.activate,h.json,t.json FROM committed_history_ingress h JOIN committed_trade_ingress t ON t.event_id=h.id WHERE h.namespace='starcore.trade' ORDER BY h.sequence").fetchall()
            require([r[1] for r in ingress] == list(range(1, source_count + 1)), 'Ingress payload lost sequence')
            events = {r[0]: json.loads(r[1]) for r in chronicle.execute("SELECT id,json FROM events WHERE json_extract(json,'$.facts.\"history.namespace\"')='starcore.trade'")}
            saved = {r[0]: r[1] for r in chronicle.execute('SELECT event_id,json FROM trade_history_receipts')}
            require(len(events) == len(saved) == consumer_count, 'Partial event or receipt commit')
            for eid, seq, status, activate, event_json, receipt_json in ingress:
                receipt = receipts[seq]
                require(eid == event_id(receipt) and json.loads(receipt_json) == full_receipt(receipt), 'Ingress receipt differs from source')
                require(activate == int(seq > 3), 'Archive/live policy changed')
                require(status == ('ACKED' if seq <= consumer_count else 'PENDING'), 'Wrong receipt acknowledgement')
                if seq > consumer_count:
                    require(eid not in events and eid not in saved, 'Failed projection partially committed')
                    continue
                e, r = json.loads(event_json), receipt['record']
                require(canonical_event(events[eid]) == canonical_event(e) and saved[eid] == receipt_json, 'Consumer receipt or event changed')
                require(e['occurredAt'] == receipt['occurredAt'] and e['facts']['history.committed_at'] == receipt['committedAt'] and e['facts']['trade.executed_at'] == r['executedAt'], 'Native timestamp precision lost')
                require(e['facts']['trade.receipt_sha256'] == hashlib.sha256(receipt_json.encode()).hexdigest(), 'Receipt fingerprint differs from event')
                require(set(e['participants']) == {r[k] for k in ('buyer', 'seller') if r.get(k)} and set(e['nations']) == {r[k] for k in ('buyerNation', 'sellerNation') if r.get(k)}, 'False historical attribution')
                kind = 'TRADE_UPDATED' if receipt['previousStatus'] == r['status'] else 'TRADE_RESTORED' if r['status'] == 'COMPLETED' and receipt['previousStatus'] else 'TRADE_' + r['status']
                require(e['type'] == kind, 'Restoration/notes treated as a new initial completion')
            require(all(events.get(k) == v for k, v in previous_events.items()), 'Historical event rewritten across restart')
            entries = {r[0]: dict(id=r[0], request=json.loads(r[1]), recordedAt=r[2]) for r in chronicle.execute("SELECT id,request_json,recorded_at FROM contributions WHERE type='TRADE'")}
            revisions = [dict(revision=r[0], contributionId=r[1], sourceEventId=r[2], status=r[3], committedAt=r[4], recordedAt=r[5]) for r in chronicle.execute('SELECT * FROM contribution_revisions ORDER BY revision')]
            require([r['revision'] for r in revisions] == list(range(1, len(revisions) + 1)), 'Status revision sequence gap')
            require(all(entries.get(k) == v for k, v in previous_entries.items()), 'Original contribution rewritten on refund/restart')
            require(all({r['revision']: r for r in revisions}.get(k) == v for k, v in previous_revisions.items()), 'Revision audit overwritten')
            for point in checks:
                cutoff = point['count']
                selected_receipts = [receipts[i] for i in range(1, cutoff + 1)]
                require(point['native'] == selected_receipts, 'Public native source differs from SQL')
                require(point['receipts'] == list(map(full_receipt, selected_receipts)), 'Public full receipt loses native fields')
                require(len(point['events']) == cutoff and {e['id']: canonical_event(e) for e in point['events']} == {event_id(r): canonical_event(events[event_id(r)]) for r in selected_receipts}, 'Public history differs from committed SQL')
                check_projection(point['contributions'], point['revisions'], point['totals'], selected_receipts)
                require(all(entries[e['entry']['id']] == e['entry'] for e in point['contributions']), 'Public original contribution differs from SQL')
                require(all(r in revisions for r in point['revisions']), 'Public revision differs from SQL')
            expected_originals, expected_revisions, states = expected_projection([receipts[i] for i in range(1, consumer_count + 1)])
            require(set(entries) == set(expected_originals) and len(revisions) == len(expected_revisions), 'Persisted contribution count mismatch')
            current = [dict(entry=entry, status=states[eid]['status'], statusEventId=states[eid]['sourceEventId'], statusCommittedAt=states[eid]['committedAt']) for eid, entry in entries.items()]
            check_projection(current, revisions, checks[-1]['totals'], [receipts[i] for i in range(1, consumer_count + 1)])
            trigger_count = chronicle.execute("SELECT count(*) FROM sqlite_master WHERE type='trigger' AND name LIKE 'acceptance_reject_%'").fetchone()[0]
            require(trigger_count == (1 if boot == 1 else 0), 'Injected trigger lifecycle mismatch')
            final_assets = native.assets(core, identity)
            require(final_assets == native.normalized_assets(checks[-1]['balances']), 'Public asset snapshot differs from durable assets')
            require(final_assets['buyerResource'] == (136 if boot == 1 else 137) and final_assets['sellerResource'] == (862 if boot == 1 else 861), 'Real resource transfer totals mismatch')
            opening = native.normalized_assets(json.loads(identity['opening']))
            expected_money = {identity['buyer']: opening['buyerMoney'], identity['seller']: opening['sellerMoney']}
            for receipt in first.values():
                r = receipt['record']
                gross, tax = Decimal(r['amount']) * Decimal(r['price']), Decimal(r['tax'])
                if r.get('buyer'):
                    expected_money[r['buyer']] -= gross + tax
                if r.get('seller'):
                    expected_money[r['seller']] += gross - (tax if not r.get('buyer') else Decimal(0))
            require(final_assets['buyerMoney'] == expected_money[identity['buyer']] and final_assets['sellerMoney'] == expected_money[identity['seller']], 'Real money totals mismatch')
            if boot > 1:
                require(native.normalized_assets(checks[0]['balances']) == previous_assets, 'Recovery moved assets')
            if boot == 3:
                require(final_assets == previous_assets, 'Idle restart moved assets')
            previous_receipts, previous_events, previous_entries = receipts, events, entries
            previous_revisions, previous_txs, previous_assets = {r['revision']: r for r in revisions}, txs, final_assets
        boots.append(dict(boot=boot, native_changes=source_count, committed_receipts=consumer_count, settlements=settlements, expected_faults=len(faults)))
    return dict(status='PASS', chronicle_sha256=manifest['product_sha256'], starcore_sha256=manifest['native_sha256'],
        source_domain=identity['domain'], schema_version=schema, verified_boots=3, native_settlements=134, native_changes=139, original_contributions=534,
        status_revisions=554, initial_archive=3, live_changes=136, recovered_after_restart=1, boot_counts=boots,
        final_totals=checks[-1]['totals'], scope='Real native trades with offline fixture accounts; administrative native history corrections and Chronicle net ledger recovery; no economic refund or online player UI certification')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('run', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    result = inspect(args.run)
    if args.output:
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(result, ensure_ascii=False))
