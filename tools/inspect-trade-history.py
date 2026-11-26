"""Independently verify native SQL, transaction files, public API, assets and restart evidence."""
from pathlib import Path
from decimal import Decimal
import argparse
import base64
import hashlib
import json
import re
import sqlite3


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def sha(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def read_db(path):
    return sqlite3.connect(Path(path).resolve().as_uri() + '?mode=ro', uri=True)


def properties(path):
    result = {}
    for line in path.read_text().splitlines():
        if not line or line.startswith('#'):
            continue
        # Fixture values contain no continued lines; reject instead of silently misdecoding.
        require(not line.endswith('\\'), 'Unexpected property continuation')
        key, value = line.split('=', 1)
        value = re.sub(r'\\u([0-9a-fA-F]{4})|\\(.)',
                       lambda m: chr(int(m[1], 16)) if m[1] else {'n': '\n', 'r': '\r', 't': '\t', 'f': '\f'}.get(m[2], m[2]), value)
        require(key not in result, 'Duplicate transaction property')
        result[key] = value
    return result


def decoded(value):
    return base64.urlsafe_b64decode(value + '=' * (-len(value) % 4)).decode()


def transactions(path):
    props = properties(path)
    require(props.get('version') == '1', 'Unknown transaction version')
    records = {}
    for key, value in props.items():
        if not key.endswith('.idempotency'):
            continue
        prefix = key.removesuffix('.idempotency')
        metadata = dict(tuple(decoded(part) for part in entry.split(':'))
                        for entry in props[prefix + '.metadata'].split(','))
        events = [[props[f'{prefix}.events.{i}.{field}'] for field in ('at', 'state', 'message')]
                  for i in range(int(props[prefix + '.events.count']))]
        require(value not in records, 'Duplicate transaction key')
        records[value] = dict(id=prefix.removeprefix('tx.'), metadata=metadata, events=events,
                              **{field: props[prefix + '.' + field] for field in ('account', 'amount', 'owner', 'type', 'reason', 'state', 'updated')})
    return records


def assets(db, identity):
    money = dict(db.execute('SELECT account_id,balance FROM starcore_player_balances'))
    state = dict(db.execute('SELECT property_key,property_value FROM starcore_resource_state'))
    stock = {}
    for i in range(int(state['count'])):
        prefix = f'stockpile.{i}.'
        stock[state[prefix + 'nationId'], state[prefix + 'type']] = int(state[prefix + 'amount'])
    return {'buyerMoney': Decimal(str(money[identity['buyer']])), 'sellerMoney': Decimal(str(money[identity['seller']])),
            'buyerResource': stock.get((identity['buyerNation'], 'industrial'), 0),
            'sellerResource': stock.get((identity['sellerNation'], 'industrial'), 0)}


def normalized_assets(value):
    return {key: Decimal(str(amount)) if key.endswith('Money') else amount for key, amount in value.items()}


def inspect(run):
    run = Path(run).resolve()
    manifest = json.loads((run / 'preparation.json').read_text())
    for file, key in (('plugins/StarCore.jar', 'native_sha256'), ('plugins/StarChronicle.jar', 'product_sha256'),
                      ('plugins/TradeHistoryPaperFixture.jar', 'fixture_sha256'), ('fixture/TradeHistoryPaperFixture.java', 'fixture_source_sha256'), ('paper.jar', 'paper_sha256')):
        require(sha(run / file) == manifest[key], 'Artifact fingerprint drift: ' + file)
    fixture = [json.loads(line) for line in (run / 'evidence/fixture.jsonl').read_text().splitlines() if line]
    require(not any(row['kind'] == 'FAILED' for row in fixture), 'Fixture failure')
    identity = next(row['data'] for row in fixture if row['kind'] == 'READY')
    previous_receipts = {}
    previous_assets = None
    previous_intents = {}
    previous_transactions = {}
    boots = []
    for boot, count, intent_count in ((1, 3, 4), (2, 134, 134), (3, 134, 134)):
        session = json.loads((run / f'evidence/boot{boot}.session.json').read_text())
        require(session['passed'] is True and session['exit_code'] == 0 and not session.get('forced_kill'), 'Incomplete process lifecycle')
        require(session['exited_at'] > session['started_at'], 'Missing process timestamps')
        log = (run / f'evidence/boot{boot}.server.log').read_text()
        require('Done (' in log and 'Stopping server' in log and 'TRADE_CLOSED' in log, 'Missing normal lifecycle')
        require('StarChronicle] Enabling StarChronicle' in log and 'StarCore] Enabling StarCore' in log, 'Missing paired plugin startup')
        expected_errors = session.get('expected_errors', [])
        require(len(expected_errors) == (1 if boot == 1 else 0), 'Unexpected injected failure log count')
        errors = [line for line in log.splitlines(keepends=True) if ' ERROR]' in line]
        require(errors == expected_errors, 'Unexpected server error')
        require(not re.search(r'TRADE_FAILED|Exception in thread|Source event was not accepted|History scan retained pending', log), 'Server or history failed')
        selected = [row for row in fixture if row['boot'] == boot]
        require(sum(row['kind'] == 'READY' for row in selected) == 1 and sum(row['kind'] == 'CLOSED' for row in selected) == 1, 'Duplicate fixture lifecycle')
        ready = next(row['data'] for row in selected if row['kind'] == 'READY')
        require(all(ready[key] == identity[key] for key in ('domain', 'buyer', 'seller', 'buyerNation', 'sellerNation')), 'Actor, nation or domain drift')
        if previous_assets is not None:
            require(normalized_assets(ready['balances']) == previous_assets, 'Restart repeated or lost assets')
        checks = [row['data'] for row in selected if row['kind'] == 'CHECK']
        require([point['count'] for point in checks] == {1: [3, 3], 2: [4, 134], 3: [134, 134]}[boot], 'Missing public checkpoints')
        expected_sql = {1: [3], 2: [4, 134], 3: [134, 134]}[boot]
        require([point['commits'] for point in session['checkpoints']] == expected_sql, 'Missing live SQL checkpoint')
        data = run / f'evidence/boot{boot}.data/StarCore'
        txs = transactions(data / 'economy/transaction-fabric.properties')
        with read_db(data / 'starcore.db') as db:
            require(db.execute('PRAGMA integrity_check').fetchall() == [('ok',)], 'Native database corruption')
            require(db.execute('SELECT domain_id,last_sequence,initialized FROM trade_history_authority').fetchall() == [(identity['domain'], count, 1)], 'Native journal head mismatch')
            rows = db.execute('SELECT sequence_id,committed_at,occurred_at,previous_status,payload FROM trade_history_commits ORDER BY sequence_id').fetchall()
            require([row[0] for row in rows] == list(range(1, count + 1)), 'Missing or repeated sequence')
            receipts = {row[0]: dict(domain=identity['domain'], sequence=row[0], committedAt=row[1], occurredAt=row[2], previousStatus=row[3], record=json.loads(row[4])) for row in rows}
            require(all(receipts.get(seq) == value for seq, value in previous_receipts.items()), 'Committed receipt changed across restart')
            stored_identity = {row[0]: json.loads(row[1]) for row in db.execute('SELECT * FROM trade_history_identity')}
            require(len(stored_identity) == count, 'Uncommitted or missing canonical identity')
            intents = {row[0]: dict(record=row[1], account=row[2], amount=row[3], reason=row[4], planned=json.loads(row[5]), context=json.loads(row[6]), applied=row[7], tx=row[8], fingerprint=row[9]) for row in db.execute('SELECT operation_id,record_id,account_id,amount_text,reason,planned_payload,settlement_context,applied_events,transaction_id,applied_fingerprint FROM trade_settlement_intents')}
            require(len(intents) == intent_count and len(txs) == intent_count, 'Intent/transaction count mismatch')
            for operation, intent in intents.items():
                tx = txs['trade-offer:' + operation]
                require(tx['state'] == 'COMMITTED' and tx['owner'] == 'TRADE' and tx['type'] == 'TRADE', 'No committed transaction proof')
                require(tx['account'] == intent['account'] and Decimal(tx['amount']) == Decimal(intent['amount']) and tx['reason'] == intent['reason'], 'Preparation terms changed')
                require(tx['metadata'] == {'trade.protocol': '1', 'trade.domain': identity['domain'], 'trade.operation': operation,
                                           'trade.plan': json.dumps(intent['planned'], separators=(',', ':'), ensure_ascii=False),
                                           'trade.context': json.dumps(intent['context'], sort_keys=True, separators=(',', ':'), ensure_ascii=False)}, 'Prepared payload differs from transaction metadata')
                require([event[1] for event in tx['events']] == ['CREATED', 'RESERVED', 'DEBITED', 'DELIVERED', 'COMMITTED'], 'Unexpected native transaction timeline')
                if intent['applied']:
                    require(intent['applied'] == len(tx['events']) and intent['tx'] == tx['id'], 'Incomplete transaction acknowledgement')
                    fingerprint = hashlib.sha256(json.dumps(tx['events'], separators=(',', ':'), ensure_ascii=False).encode()).hexdigest()
                    require(intent['fingerprint'] == fingerprint, 'Applied transaction fingerprint changed')
                    record = stored_identity[intent['record']]
                    planned = dict(intent['planned'], executedAt=tx['updated'])
                    require(record == planned, 'Settlement record does not preserve prepared identity')
                else:
                    require(boot == 1 and intent['record'] not in stored_identity, 'Unacknowledged settlement outside injected fault')
                if operation in previous_intents:
                    prior = previous_intents[operation]
                    require(all(intent[key] == prior[key] for key in ('record', 'account', 'amount', 'reason', 'planned', 'context')), 'Preparation changed on recovery')
            for key, tx in previous_transactions.items():
                require(txs[key] == tx, 'Recovery replayed or changed a financial transaction')
            for receipt in receipts.values():
                record = receipt['record']
                require(record == stored_identity[record['id']] and receipt['previousStatus'] is None and record['status'] == 'COMPLETED', 'Invalid completion receipt')
                require(receipt['occurredAt'] == record['executedAt'], 'Loss of actual commit time')
                query = db.execute('SELECT executed_at_exact,status,buyer_player_id,seller_player_id,buyer_nation_id,seller_nation_id,amount,price_per_unit,tax_amount FROM trade_history WHERE record_id=?', (record['id'],)).fetchone()
                require(query == (record['executedAt'], record['status'], record['buyer'], record['seller'], record['buyerNation'], record['sellerNation'], record['amount'], float(record['price']), float(record['tax'])), 'Query view differs from committed identity')
            for point in checks:
                actual = {entry['sequence']: dict(entry, previousStatus=entry.get('previousStatus'), record={**{key: None for key in ('buyer', 'seller', 'buyerNation', 'sellerNation', 'buyOrder', 'sellOrder', 'notes')}, **entry['record']}) for entry in point['records']}
                require(actual == {seq: receipts[seq] for seq in range(1, point['count'] + 1)}, 'Public receipts differ from durable SQL')
            final_assets = assets(db, identity)
            if boot == 1:
                settled = [row['data'] for row in selected if row['kind'] == 'SETTLED']
                require(len(settled) == 1, 'Missing injected settlement outcome')
                require(normalized_assets(settled[0]['after']) == final_assets, 'Failed-history trade lost assets on shutdown')
                require(final_assets['buyerResource'] == 7 and final_assets['sellerResource'] == 991, 'Incorrect real resource transfers')
                pending = [(op, i) for op, i in intents.items() if not i['applied']]
                require(len(pending) == 1 and pending[0][1]['record'] == settled[0]['record'], 'Fault did not leave exact preparation')
                require(f'operation={pending[0][0]}, transaction={txs["trade-offer:" + pending[0][0]]["id"]}' in expected_errors[0], 'Expected error belongs to another transaction')
                require(db.execute("SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name='acceptance_reject_trade_commit'").fetchone()[0] == 1, 'No injected SQL trigger')
                require(session['injected_failure_state']['commits'] == 3 and session['injected_failure_state']['pending'] == 1, 'Missing failure checkpoint')
            else:
                require(normalized_assets(checks[-1]['balances']) == final_assets, 'Public assets differ from saved state')
                require(db.execute("SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name='acceptance_reject_trade_commit'").fetchone()[0] == 0, 'Fault trigger remains installed')
                if boot == 2:
                    require(normalized_assets(checks[0]['balances']) == previous_assets, 'Recovery changed assets')
                    require(final_assets['buyerResource'] == previous_assets['buyerResource'] + 130 and final_assets['sellerResource'] == previous_assets['sellerResource'] - 130, 'Burst did not transfer 130 resources')
                    require(final_assets['sellerMoney'] == previous_assets['sellerMoney'] + Decimal('260.00'), 'Burst seller payout mismatch')
                else:
                    require(final_assets == previous_assets, 'Idle restart changed assets')
            previous_receipts, previous_assets, previous_intents, previous_transactions = receipts, final_assets, intents, txs
        boots.append(dict(boot=boot, committed=count, prepared=intent_count, expected_faults=len(expected_errors)))
    return dict(status='PASS', chronicle_sha256=manifest['product_sha256'], starcore_sha256=manifest['native_sha256'], verified_boots=3,
                native_settlements=134, recovered_after_restart=1, boot_counts=boots,
                source_domain=identity['domain'], scope='Native direct/quick-sell/market services on isolated Paper with offline fixture accounts; SQL fault and restart recovery; no Chronicle trade contribution or player command flow certification')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('run', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    result = inspect(args.run)
    if args.output:
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(result, ensure_ascii=False))
