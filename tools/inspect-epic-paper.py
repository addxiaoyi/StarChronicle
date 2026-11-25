"""Independent read-only cross-check of a stopped epic run's native and Chronicle stores."""
from pathlib import Path
from contextlib import ExitStack
from datetime import datetime
from decimal import Decimal
import argparse
import hashlib
import importlib.util
import json
import re
import socket
import sqlite3

SPEC = importlib.util.spec_from_file_location('quest_inspector', Path(__file__).with_name('inspect-quest-evidence-paper.py'))
QUEST = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(QUEST)


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify_boots(run, manifest):
    """Require the actual three-boot exercise, not just an eventually complete database."""
    evidence = run / 'evidence'
    fixture = [json.loads(line) for line in (evidence / 'epic-fixture.jsonl').read_text(encoding='utf-8').splitlines()]
    check(not any(row['kind'] == 'FAILED' for row in fixture), 'Fixture failure retained in run')
    check({row['boot'] for row in fixture} == {1, 2, 3}, 'Exactly three fixture boots required')
    check(len(list(evidence.glob('boot*.session.json'))) == 3, 'Exactly three completed controller sessions required')
    settlements = []
    for boot, scenario in enumerate(('first', 'second', 'third'), 1):
        session = read(evidence / f'boot{boot}.session.json')
        check(session['boot'] == boot and session['scenario'] == scenario and session['status'] == 'COMPLETE', 'Incomplete controller scenario')
        processes = session['processes']
        check(len(processes) == 2 and {p['name'] for p in processes} == {'server', 'clients'}, 'Missing actual server/client processes')
        check(all(p.get('exit_code') == 0 and p['exited_at'] >= p['started_at'] for p in processes), 'Abnormal process exit')
        rows = [row for row in fixture if row['boot'] == boot]
        for kind in ('READY', 'CLOSED', 'SEEDED'):
            check(sum(row['kind'] == kind for row in rows) == 1, 'Missing or duplicate boot lifecycle: ' + kind)
        ready = next(row['data'] for row in rows if row['kind'] == 'READY')
        check(ready == {'product': manifest['plugin_sha256'], 'native': manifest['starcore_sha256'], 'route': manifest['route']}, 'Running artifact/route mismatch')
        check(next(row['data'] for row in rows if row['kind'] == 'SEEDED')['nonOp'] is True, 'Clients must remain non-OP')
        server_log = (evidence / f'boot{boot}.server.log').read_text(encoding='utf-8')
        diagnostics = re.findall(r'^.*(?:ERROR\]|ERROR\]:|Failed to (?:enable|disable) STARCORE module|同步保存失败|异步保存失败|SQLITE_BUSY|startup failed|Could not pass event).*$' , server_log, re.M | re.I)
        check(not diagnostics, 'Server persistence/lifecycle diagnostic: ' + '\n'.join(diagnostics))
        check('Done (' in server_log and 'Stopping server' in server_log and 'All dimensions are saved' in server_log, 'Incomplete server startup/shutdown')
        clients = [json.loads(line) for line in (evidence / f'boot{boot}.clients.log').read_text(encoding='utf-8').splitlines() if line.startswith('{')]
        check(not any(row['kind'] == 'FAILED' for row in clients), 'Protocol client failure')
        check({row['data']['who'] for row in clients if row['kind'] == 'SPAWN'} == set('ABCD'), 'Missing real clients')
        check(all(row['data']['uuid'] == manifest['players'][row['data']['who']] for row in clients if row['kind'] == 'SPAWN'), 'Protocol client identity mismatch')
        if boot == 1:
            for who in 'AB':
                crafted = [row for row in clients if row['kind'] == 'CRAFTED' and row['data']['who'] == who]
                check(len(crafted) == 1, 'Exactly one real craft per actor required')
                data = crafted[0]['data']
                check(data['syntheticEvents'] is False and data['after']['paper'] - data['before']['paper'] == 3 and data['before']['sugar_cane'] - data['after']['sugar_cane'] == 3, 'Craft inventory proof mismatch')
                admitted = [row for row in rows if row['kind'] == 'NATIVE_TASK_READY' and row['data']['player'] == who]
                check(len(admitted) == 1 and admitted[0]['time'] < crafted[0]['time'], 'Task must be accepted before crafting')
            checkpoint = [row['data'] for row in rows if row['kind'] == 'RESTART_CHECKPOINT']
            check(len(checkpoint) == 1 and checkpoint[0]['status'] == 'ROUTE_APPROVED', 'Missing pre-settlement restart checkpoint')
            native_kind = 'NATIVE_DUNGEON_COMPLETED' if manifest['route'] == 'DUNGEON' else 'NATIVE_WAR_ENDED'
            check(sum(row['kind'] == native_kind for row in rows) == (2 if manifest['route'] == 'DUNGEON' else 1), 'Missing native final activity')
        else:
            kinds = [row['kind'] for row in rows]
            passes = [row['data'] for row in rows if row['kind'] == 'ACCEPTANCE_PASS']
            check(len(passes) == 2 and kinds.count('REPLAY_REJECTED') == 1, 'Missing restart/replay checks')
            check(kinds.index('ACCEPTANCE_PASS') < kinds.index('REPLAY_REJECTED') < len(kinds) - 1 - kinds[::-1].index('ACCEPTANCE_PASS'), 'Replay must be bracketed by state checks')
            check(all(p['restarted'] and p['route'] == manifest['route'] for p in passes), 'Wrong restart route')
            settlements.extend(p['settlement']['id'] for p in passes)
    check(len(set(settlements)) == 1, 'Settlement identity changed across restart/replay')
    return settlements[0]


def check(condition, message):
    if not condition:
        raise AssertionError(message)


def connect(path):
    connection = sqlite3.connect(path.resolve().as_uri() + '?mode=ro', uri=True)
    connection.row_factory = sqlite3.Row
    check(connection.execute('pragma integrity_check').fetchone()[0] == 'ok', 'Database integrity failed')
    return connection


def inspect(run):
    run = run.resolve()
    manifest = json.loads((run / 'preparation.json').read_text(encoding='utf-8'))
    probe = socket.socket()
    probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    probe.bind(('127.0.0.1', manifest['server_port']))
    probe.close()
    for name, key in [('StarCore', 'starcore_sha256'), ('StarChronicle', 'plugin_sha256'), ('EpicCampaignPaperFixture', 'fixture_sha256')]:
        check(hashlib.sha256((run / f'plugins/{name}.jar').read_bytes()).hexdigest() == manifest[key], 'Frozen JAR changed')
    check(digest(run / 'fixture/EpicCampaignPaperFixture.java') == manifest['fixture_source_sha256'], 'Frozen fixture source changed')
    settlement_id = verify_boots(run, manifest)
    with ExitStack() as connections:
        chronicle = connect(run / 'plugins/StarChronicle/chronicle.db')
        connections.callback(chronicle.close)
        native = connect(run / 'plugins/StarCore/starcore.db')
        connections.callback(native.close)
        campaigns = list(chronicle.execute('select * from epic_campaigns'))
        check(len(campaigns) == 1 and campaigns[0]['status'] == 'COMPLETE', 'Expected one completed campaign')
        campaign = campaigns[0]
        check(campaign['route'] == manifest['route'], 'Campaign route differs from exercised route')
        rows = list(chronicle.execute('select * from epic_operations'))
        check(len(rows) == (13 if campaign['route'] == 'WAR' else 12), 'Unexpected committed operation count')
        check(all(row['status'] == 'COMMITTED' for row in rows), 'An operation remains uncommitted')
        receipts = [json.loads(row['receipt_json']) for row in rows]
        for row, receipt in zip(rows, receipts):
            operation = json.loads(row['operation_json'])
            check(all(receipt[key] == value for key, value in operation.items() if key != 'requestedAt'), 'Receipt differs from pinned intent')
            check(datetime.fromisoformat(receipt['appliedAt']) >= datetime.fromisoformat(operation['requestedAt']), 'Receipt predates intent')
            check(receipt['instanceId'] == campaign['instance_id'] and receipt['sourceEventId'] == campaign['source_event_id'], 'Receipt belongs to another campaign')
            pinned = chronicle.execute('select provider_id,authority_domain from epic_operation_providers where operation_id=?', (receipt['operationId'],)).fetchone()
            check(pinned and tuple(pinned) == (receipt['issuer'], receipt['authorityDomain']), 'Provider/domain binding mismatch')
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
                check(r['category'] == 'OTHER' and r['description'] == f"StarChronicle aid {receipt['campaignId']} outcome={receipt['operationId']} definition={receipt['definitionFingerprint']}", 'Treasury intent description mismatch')
                check(int(r['appliedAt']) == QUEST.millis(receipt['appliedAt']), 'Treasury receipt time mismatch')
            elif kind == 'RESOURCE_AID':
                r = native.execute('select * from starcore_resource_transfer_receipts where transaction_id=?', (identity,)).fetchone()
                check(r and r['payer_nation_id'] == receipt['nationId'] and r['receiver_nation_id'] == receipt['target'] and r['resources'] == 'agricultural=10' and r['authority_domain_id'] == receipt['authorityDomain'], 'Resource receipt mismatch')
                check(r['category'] == 'OTHER' and r['description'] == f"StarChronicle epic {receipt['campaignId']} {receipt['operationId']}" and r['applied_at'] == QUEST.millis(receipt['appliedAt']), 'Resource intent/time mismatch')
            elif kind in ('DIPLOMACY_INVITE', 'DIPLOMACY_ACCEPT'):
                table = 'starcore_diplomacy_invite_receipts' if kind.endswith('INVITE') else 'starcore_diplomacy_acceptance_receipts'
                r = native.execute('select * from ' + table + ' where operation_id=?', (identity,)).fetchone()
                check(r and r['actor_id'] == receipt['actorId'] and r['authority_domain_id'] == receipt['authorityDomain'], 'Diplomacy identity mismatch')
                check(r['committed_at'] == QUEST.millis(receipt['appliedAt']), 'Diplomacy receipt time mismatch')
                if kind.endswith('ACCEPT'):
                    check(r['invitation_operation_id'] == receipt['nativeReference'] and r['acceptor_id'] == receipt['nationId'] and r['inviter_id'] == receipt['target'], 'Acceptance link mismatch')
                else:
                    check(r['inviter_id'] == receipt['nationId'] and r['invited_id'] == receipt['target'], 'Invitation parties mismatch')
            elif kind == 'DUNGEON_COMPLETION':
                records = json.loads((run / 'plugins/StarCore/dungeon/completions.json').read_text(encoding='utf-8'))
                matches = [r for r in records if r['id'] == identity]
                check(len(matches) == 1, 'Dungeon record missing')
                r = matches[0]
                check(r['success'] and r['playerId'] == receipt['actorId'] and r['nationId'] == receipt['nationId'] and r['dungeonId'] == receipt['target'], 'Dungeon actor/nation/target mismatch')
                check((run / 'plugins/StarCore/dungeon/authority-domain.txt').read_text().strip() == receipt['authorityDomain'], 'Dungeon domain mismatch')
                check(r['completedAt'] == QUEST.millis(receipt['appliedAt']), 'Dungeon timestamp mismatch')
            elif kind == 'NATION_FOUNDING':
                r = native.execute('select * from starcore_nation_founding_receipts where operation_id=?', (identity,)).fetchone()
                check(r and r['nation_id'] == receipt['target'] and r['founder_id'] == receipt['actorId'] and r['authority_domain_id'] == receipt['authorityDomain'], 'Founding receipt mismatch')
                check(r['committed_at'] == QUEST.millis(receipt['appliedAt']), 'Founding receipt time mismatch')
            elif kind in ('WAR_COMMIT', 'FINAL_BATTLE'):
                r = native.execute('select war_json from starcore_wars where war_id=?', (identity,)).fetchone()
                check(r is not None, 'Persistent war missing')
                war = json.loads(r[0])
                check(war['aggressor'] == receipt['nationId'] and war['defender'] == receipt['target'], 'War parties differ')
                check(war.get('declarationCommitted') is True and war['declarationOperationId'] == identity, 'War declaration is not committed')
                domain = native.execute("select property_value from starcore_war_state where property_key='authorityDomainId'").fetchone()
                check(domain and domain[0] == receipt['authorityDomain'], 'War authority domain mismatch')
                check(war['endedAt' if kind == 'FINAL_BATTLE' else 'declaredAt'] == QUEST.millis(receipt['appliedAt']), 'War receipt time mismatch')
                if kind == 'FINAL_BATTLE':
                    check(war['status'] == 'ENDED' and war['endedAt'] >= war['declaredAt'], 'War is not durably ended/declared')
                    check(any(r['type'] == 'WAR_COMMIT' and r['nativeReceiptId'] == identity and r['authorityDomain'] == receipt['authorityDomain'] for r in receipts), 'Final battle has no paired declaration')
            elif kind == 'QUEST_GATE':
                gates = [dict(g) for g in chronicle.execute("select * from quest_gates where status='COMMITTED'")]
                matches = [g for g in gates if json.loads(g['completion_json'])['completionId'] == identity]
                check(len(matches) == 1, 'Epic quest receipt has no unique committed gate')
                gate = matches[0]; completion = json.loads(gate['completion_json'])
                check(gate['instance_id'] == campaign['instance_id'] and gate['player_id'] == receipt['actorId'] and gate['expected_nation_id'] == receipt['nationId'], 'Quest gate actor/nation/instance mismatch')
                root = run / 'plugins/StarCore/quest'
                authority = QUEST.properties(root / 'evidence-authority.properties')['authority']
                check(authority == receipt['authorityDomain'] and gate['provider_id'] == 'starcore.quest.evidence.v1:' + authority, 'Quest authority mismatch')
                snapshots = [json.loads(QUEST.properties(p)['entry'])['snapshot'] for p in (root / 'tracked-evidence').glob('*.properties')]
                matching = [s for s in snapshots if s['bindingId'] == gate['id']]
                check(len(matching) == 1, 'Native quest shard missing')
                s = matching[0]
                check(s['status'] == 'COMPLETED' and s['rewardStatus'] == 'INVOKED', 'Native quest completion is not committed')
                check(all(s[k] == completion[k] for k in ('bindingId','runId','playerId','questId','definitionFingerprint','completionId','issuer','acceptedEvidence')), 'Native quest identity/evidence mismatch')
                check(s['completionEvidence'] == completion['evidence'] and s['acceptedNation'] == s['completedNation'] == receipt['nationId'], 'Native quest nation/completion proof mismatch')
                check(all(s[k] == QUEST.millis(completion[k]) for k in ('startedAt','completedAt','committedAt')), 'Native quest timestamps mismatch')
            else:
                raise AssertionError('Unexpected receipt type: ' + kind)
        settlements = list(chronicle.execute("select json from events where type='HEIR_STRATEGY_SETTLED'"))
        check(len(settlements) == 1, 'Duplicate or missing settlement')
        event = json.loads(settlements[0][0])
        check(event['id'] == settlement_id and event['facts']['epic.route'] == manifest['route'], 'Settlement differs from live observations')
        check(event['facts']['epic.final.canonical'] == 'true' and event['facts']['epic.instance'] == campaign['instance_id'], 'Settlement campaign mismatch')
        story = chronicle.execute('select completed from instances where id=?', (campaign['instance_id'],)).fetchone()
        check(story and story[0] == 1, 'Campaign story not completed')
        aftermath = [dict(row) for row in chronicle.execute("select * from instances where story='last-heir-strategy-aftermath'")]
        expected_players = {manifest['players'][who] for who in 'ABD'}
        check(set(event['participants']) == expected_players, 'Settlement participants differ from actual eligible players')
        check(len(aftermath) == len(expected_players) and {row['scope'] for row in aftermath} == {'player:' + player for player in expected_players}, 'Missing or duplicate aftermath per player')
        check(all(json.loads(row['json'])['sourceEventId'] == event['id'] for row in aftermath), 'Aftermath has unrelated source')
        check(chronicle.execute("select count(*) from story_nation_resolutions where instance_id=? and node_id=? and choice_id='settle'", (campaign['instance_id'], 'war-await' if manifest['route'] == 'WAR' else 'dungeon-await')).fetchone()[0] == 1, 'Missing unique canonical settle resolution')
        result = dict(status='PASS', route=campaign['route'], instance=campaign['instance_id'],
                      chronicle_sha256=manifest['plugin_sha256'], starcore_sha256=manifest['starcore_sha256'],
                      committed_operations=len(rows), treasury_transfers=2, resource_transfers=2, settlement_events=1,
                      verified_boots=3, aftermath_instances=len(aftermath),
                      verification='Three normal server/client exits, live craft/restart/replay evidence and independent native/Chronicle receipt cross-check; server port unbound.')
        return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('run', type=Path)
    args = parser.parse_args()
    run = args.run.resolve()
    result = inspect(run)
    (run / 'evidence/independent-check.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result))


if __name__ == '__main__':
    main()
