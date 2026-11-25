"""Independent read-only cross-check of a stopped epic run's native and Chronicle stores."""
from pathlib import Path
from datetime import datetime
from decimal import Decimal
import argparse
import hashlib
import json
import socket
import sqlite3


def check(condition, message):
    if not condition:
        raise AssertionError(message)


def connect(path):
    connection = sqlite3.connect(path.resolve().as_uri() + '?mode=ro', uri=True)
    connection.row_factory = sqlite3.Row
    check(connection.execute('pragma integrity_check').fetchone()[0] == 'ok', 'Database integrity failed')
    return connection


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('run', type=Path)
    args = parser.parse_args()
    run = args.run.resolve()
    manifest = json.loads((run / 'preparation.json').read_text(encoding='utf-8'))
    probe = socket.socket()
    probe.bind(('127.0.0.1', manifest['server_port']))
    probe.close()
    for name, key in [('StarCore', 'starcore_sha256'), ('StarChronicle', 'plugin_sha256')]:
        check(hashlib.sha256((run / f'plugins/{name}.jar').read_bytes()).hexdigest() == manifest[key], 'Frozen JAR changed')
    chronicle = connect(run / 'plugins/StarChronicle/chronicle.db')
    native = connect(run / 'plugins/StarCore/starcore.db')
    campaigns = list(chronicle.execute('select * from epic_campaigns'))
    check(len(campaigns) == 1 and campaigns[0]['status'] == 'COMPLETE', 'Expected one completed campaign')
    campaign = campaigns[0]
    rows = list(chronicle.execute('select * from epic_operations'))
    check(len(rows) == (13 if campaign['route'] == 'WAR' else 12), 'Unexpected committed operation count')
    check(all(row['status'] == 'COMMITTED' for row in rows), 'An operation remains uncommitted')
    receipts = [json.loads(row['receipt_json']) for row in rows]
    check(len({(r['authorityDomain'], r['type'], r['nativeReceiptId']) for r in receipts}) == len(receipts), 'Reused native receipt')
    treasury = dict(native.execute('select property_key,property_value from starcore_treasury_state'))
    transfers = [{key.split('.', 2)[2]: value for key, value in treasury.items() if key.startswith(f'transfer.{i}.')}
                 for i in range(int(treasury['transfer.count']))]
    check(len(transfers) == 2, 'Treasury aid duplicated or missing')
    check(native.execute('select count(*) from starcore_resource_transfer_receipts').fetchone()[0] == 2, 'Resource aid duplicated')
    check(native.execute('select count(*) from starcore_diplomacy_invite_receipts').fetchone()[0] == 2, 'Invitation duplicated')
    check(native.execute('select count(*) from starcore_diplomacy_acceptance_receipts').fetchone()[0] == 2, 'Acceptance duplicated')
    for receipt in receipts:
        kind, identity = receipt['type'], receipt['nativeReceiptId']
        if kind == 'TREASURY_AID':
            matches = [r for r in transfers if r['transactionId'] == identity]
            check(len(matches) == 1, 'Native treasury receipt missing')
            r = matches[0]
            check(r['payerId'] == receipt['nationId'] and r['receiverId'] == receipt['target'] and Decimal(r['amount']) == Decimal(receipt['nativeReference']), 'Treasury parties/amount mismatch')
            check(treasury['authorityDomainId'] == receipt['authorityDomain'], 'Treasury domain mismatch')
        elif kind == 'RESOURCE_AID':
            r = native.execute('select * from starcore_resource_transfer_receipts where transaction_id=?', (identity,)).fetchone()
            check(r and r['payer_nation_id'] == receipt['nationId'] and r['receiver_nation_id'] == receipt['target'] and r['resources'] == 'agricultural=10' and r['authority_domain_id'] == receipt['authorityDomain'], 'Resource receipt mismatch')
        elif kind in ('DIPLOMACY_INVITE', 'DIPLOMACY_ACCEPT'):
            table = 'starcore_diplomacy_invite_receipts' if kind.endswith('INVITE') else 'starcore_diplomacy_acceptance_receipts'
            r = native.execute('select * from ' + table + ' where operation_id=?', (identity,)).fetchone()
            check(r and r['actor_id'] == receipt['actorId'] and r['authority_domain_id'] == receipt['authorityDomain'], 'Diplomacy identity mismatch')
            if kind.endswith('ACCEPT'):
                check(r['invitation_operation_id'] == receipt['nativeReference'] and r['acceptor_id'] == receipt['nationId'] and r['inviter_id'] == receipt['target'], 'Acceptance link mismatch')
        elif kind == 'DUNGEON_COMPLETION':
            records = json.loads((run / 'plugins/StarCore/dungeon/completions.json').read_text(encoding='utf-8'))
            matches = [r for r in records if r['id'] == identity]
            check(len(matches) == 1, 'Dungeon record missing')
            r = matches[0]
            check(r['success'] and r['playerId'] == receipt['actorId'] and r['nationId'] == receipt['nationId'] and r['dungeonId'] == receipt['target'], 'Dungeon actor/nation/target mismatch')
            check((run / 'plugins/StarCore/dungeon/authority-domain.txt').read_text().strip() == receipt['authorityDomain'], 'Dungeon domain mismatch')
            check(r['completedAt'] >= datetime.fromisoformat(receipt['appliedAt']).timestamp()*1000 - 1, 'Dungeon timestamp mismatch')
        elif kind == 'NATION_FOUNDING':
            r = native.execute('select * from starcore_nation_founding_receipts where operation_id=?', (identity,)).fetchone()
            check(r and r['nation_id'] == receipt['target'] and r['founder_id'] == receipt['actorId'] and r['authority_domain_id'] == receipt['authorityDomain'], 'Founding receipt mismatch')
        elif kind in ('WAR_COMMIT', 'FINAL_BATTLE'):
            r = native.execute('select war_json from starcore_wars where war_id=?', (identity,)).fetchone()
            check(r is not None, 'Persistent war missing')
            war = json.loads(r[0])
            check(war['aggressor'] == receipt['nationId'] and war['defender'] == receipt['target'], 'War parties differ')
            if kind == 'FINAL_BATTLE':
                check(war.get('endedAt') is not None and war.get('declarationCommitted'), 'War is not durably ended/declared')
        elif kind == 'QUEST_GATE':
            matches = list(chronicle.execute('select completion_json from quest_gates where status=?', ('COMMITTED',)))
            check(any(identity in row[0] for row in matches if row[0]), 'Epic quest receipt has no committed gate')
    settlements = list(chronicle.execute("select json from events where type='HEIR_STRATEGY_SETTLED'"))
    check(len(settlements) == 1, 'Duplicate or missing settlement')
    event = json.loads(settlements[0][0])
    check(event['facts']['epic.final.canonical'] == 'true' and event['facts']['epic.instance'] == campaign['instance_id'], 'Settlement campaign mismatch')
    story = chronicle.execute('select completed from instances where id=?', (campaign['instance_id'],)).fetchone()
    check(story and story[0] == 1, 'Campaign story not completed')
    check(chronicle.execute("select count(*) from instances where story='last-heir-strategy-aftermath'").fetchone()[0] >= 1, 'No actual aftermath story')
    result = dict(status='PASS', route=campaign['route'], instance=campaign['instance_id'],
                  chronicle_sha256=manifest['plugin_sha256'], starcore_sha256=manifest['starcore_sha256'],
                  committed_operations=len(rows), treasury_transfers=2, resource_transfers=2, settlement_events=1,
                  verification='Read-only independent native/Chronicle database and committed dungeon file cross-check; server port unbound.')
    chronicle.close();native.close()
    (run / 'evidence/independent-check.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result))


if __name__ == '__main__':
    main()
