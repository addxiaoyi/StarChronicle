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


def verify_ledgers(run, manifest, chronicle, operations, campaign_id):
    entries = [dict(row) for row in chronicle.execute('SELECT * FROM contributions')]
    check(len(entries) == 8, 'Expected eight player/nation contributions for four native transfers')
    aid = [row for row in operations if json.loads(row['operation_json'])['type'] in ('TREASURY_AID', 'RESOURCE_AID')]
    check(len(aid) == 4, 'Expected four provider-backed aid operations')
    for row in aid:
        op, receipt = json.loads(row['operation_json']), json.loads(row['receipt_json'])
        def matching_receipt(entry):
            if entry['operation_id'] == op['operationId']:
                return True
            row = chronicle.execute('SELECT json FROM events WHERE id=?', (entry['source_event_id'],)).fetchone()
            if row is None:
                return False
            facts = json.loads(row[0])['facts']
            return facts.get('aid.authority_domain') == receipt['authorityDomain'] and facts.get('aid.transaction', facts.get('aid.native_receipt')) == receipt['nativeReceiptId']
        matches = [entry for entry in entries if matching_receipt(entry)]
        check(len(matches) == 2, 'Each native transfer needs one player and one national attribution')
        check({entry['subject_id'] for entry in matches} == {'player:' + op['actorId'], 'nation:' + op['nationId']}, 'Contribution subjects differ from authorized intent')
        unit, amount = ('treasury', op['nativeReference']) if op['type'] == 'TREASURY_AID' else op['nativeReference'].split(':')
        for entry in matches:
            request = json.loads(entry['request_json'])
            check(entry['type'] == 'RESOURCE_AID' and entry['actor_id'] == op['actorId'] and entry['nation_id'] == op['nationId'], 'Wrong contribution attribution')
            check(entry['unit'] in ({unit} if unit == 'treasury' else {unit, 'resource.' + unit}) and Decimal(entry['amount']) == Decimal(amount), 'Contribution amount or unit mismatch')
            check(request['operationId'] == entry['operation_id'] and request['sourceEventId'] == entry['source_event_id'], 'Contribution payload differs from indexed source')
            check(request['actorId'] == entry['actor_id'] and request['nationId'] == entry['nation_id']
                  and request['subjectId'] == entry['subject_id'] and request['type'] == entry['type']
                  and request['unit'] == entry['unit'] and Decimal(str(request['amount'])) == Decimal(amount)
                  and request['committedAt'] == entry['committed_at'] and request['evidence'] == entry['evidence'], 'Contribution JSON differs from indexed receipt')
            check(entry['committed_at'] == receipt['appliedAt'], 'Contribution timestamp must be the native commit')
            event_row = chronicle.execute('SELECT json FROM events WHERE id=?', (entry['source_event_id'],)).fetchone()
            check(event_row is not None, 'Contribution lacks canonical history')
            event = json.loads(event_row[0])
            check(event['occurredAt'] == receipt['appliedAt'] and event['facts']['time_basis'] == 'COMMITTED_SOURCE', 'Aid event lost source time')
            check(event['facts']['aid.authority_domain'] == receipt['authorityDomain'] and event['source'] == receipt['issuer'], 'Aid source authority mismatch')
            check(event['type'] == ('NATIONAL_AID_COMMITTED' if unit == 'treasury' else 'NATIONAL_RESOURCE_AID_COMMITTED')
                  and Decimal(event['facts']['aid.amount']) == Decimal(amount), 'Aid history type or amount mismatch')
            check(set(event['participants']) == {op['actorId']} and set(event['nations']) == {op['nationId'], op['target']}, 'Aid event audience mismatch')
    fixture = [json.loads(line) for line in (run / 'evidence/epic-fixture.jsonl').read_text(encoding='utf-8').splitlines()]
    for boot in (1, 2, 3):
        checks = [row for row in fixture if row['boot'] == boot and row['kind'] == 'CONTRIBUTIONS']
        check(bool(checks) and all(len(row['data']) == 8 for row in checks), 'Missing public contribution check across restart')
        expected = {entry['id']: json.loads(entry['request_json']) for entry in entries}
        check(all({entry['id']: entry['request'] for entry in row['data']} == expected for row in checks), 'Public contributions changed across restart/replay')
    def evidence(kind, boot):
        return [row for row in fixture if row['kind'] == kind and row['boot'] == boot]
    check(len(evidence('MEMBER_NATIVE_MOVED', 1)) == 2, 'Missing real native membership round trip')
    check({row['data']['reason'] for row in evidence('MEMBER_REVIEW_REJECTED', 1)} == {'changed nation', 'missing permission'}, 'Missing member review rejection boundaries')
    check(len(evidence('MEMBER_REVALIDATED', 1)) == 1 and len(evidence('MEMBER_EXITED', 1)) == 1, 'Missing native revalidation and explicit exit')
    check(len(evidence('MEMBER_REJOINED', 2)) == 1 and evidence('MEMBER_REJOINED', 2)[0]['data']['afterRestart'], 'Missing explicit rejoin after restart')
    review = chronicle.execute('SELECT status,original_nation_id,observed_nation_id FROM story_member_reviews WHERE instance_id=? AND player_id=?', (campaign_id, manifest['players']['D'])).fetchone()
    check(review and review['status'] == 'APPROVED' and review['original_nation_id'] == review['observed_nation_id'], 'Rejoined member changed original historical nation')


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
        extended = {}
        if manifest.get('extended'):
            contributions = [dict(row) for row in chronicle.execute('SELECT * FROM contributions')]
            check(len(contributions) == 8, 'Expected eight unique native aid contribution projections')
            check(chronicle.execute('SELECT COUNT(*) FROM contribution_receipts').fetchone()[0] == 8, 'Contribution receipt bindings missing')
            aid_operations = [json.loads(row['operation_json']) for row in rows if json.loads(row['operation_json'])['type'] in ('TREASURY_AID', 'RESOURCE_AID')]
            for operation in aid_operations:
                stored = next(row for row in rows if json.loads(row['operation_json'])['operationId'] == operation['operationId'])
                receipt = json.loads(stored['receipt_json'])
                expected_subjects = {'player:' + operation['actorId'], 'nation:' + operation['nationId']}
                found = []
                for entry in contributions:
                    evidence = json.loads(chronicle.execute('SELECT json FROM events WHERE id=?', (entry['source_event_id'],)).fetchone()[0])
                    if evidence['facts'].get('aid.transaction') != receipt['nativeReceiptId']:
                        continue
                    check(evidence['facts']['aid.authority_domain'] == receipt['authorityDomain'], 'Contribution native authority mismatch')
                    check(entry['actor_id'] == operation['actorId'] and entry['nation_id'] == operation['nationId'], 'Contribution actor/nation mismatch')
                    check(entry['type'] == 'RESOURCE_AID' and Decimal(entry['amount']) == 10, 'Contribution amount/type mismatch')
                    check(entry['unit'] == ('treasury' if operation['type'] == 'TREASURY_AID' else 'resource.agricultural'), 'Contribution unit mismatch')
                    check(entry['committed_at'] == receipt['appliedAt'] == evidence['occurredAt'], 'Contribution timestamp mismatch')
                    request = json.loads(entry['request_json'])
                    check(request['subjectId'] == entry['subject_id'] and Decimal(str(request['amount'])) == Decimal(entry['amount']), 'Contribution projection payload mismatch')
                    found.append(entry['subject_id'])
                check(len(found) == 2 and set(found) == expected_subjects, 'Native receipt must credit exactly one player and one nation')
            member_stories = list(chronicle.execute("SELECT id,json,completed FROM instances WHERE story='membership-acceptance'"))
            check(len(member_stories) == 1 and member_stories[0]['completed'] == 1, 'Missing completed membership acceptance')
            member_id = member_stories[0]['id']
            member_story = json.loads(member_stories[0]['json'])
            check(manifest['players']['D'] in member_story['members'], 'Completed member was removed')
            transitions = [dict(row) for row in chronicle.execute('SELECT * FROM story_member_review_log WHERE instance_id=? AND player_id=? ORDER BY seq', (member_id, manifest['players']['D']))]
            check([row['status'] for row in transitions] == ['PENDING','APPROVED','EXITED','APPROVED'], 'Membership transitions missing, repeated or rewritten')
            check(transitions[0]['original_nation_id'] != transitions[0]['observed_nation_id'], 'Missing real nationality drift')
            check(all(row['original_nation_id'] == transitions[0]['original_nation_id'] for row in transitions), 'Unexpected original membership rewrite')
            client_evidence = [json.loads(line) for line in (run / 'evidence/epic-clients.jsonl').read_text(encoding='utf-8').splitlines() if line.strip()]
            check(any(row['kind'] == 'MEMBERSHIP_GUI' and row['data']['who'] == 'D' and row['data']['instance'] == member_id for row in client_evidence), 'Actual client membership GUI missing')
            extended = dict(contribution_entries=8, membership_transitions=4, membership_gui=True)
        if manifest.get('ledger_acceptance'):
            verify_ledgers(run, manifest, chronicle, rows, campaign['instance_id'])
        result = dict(status='PASS', route=campaign['route'], instance=campaign['instance_id'],
                      chronicle_sha256=manifest['plugin_sha256'], starcore_sha256=manifest['starcore_sha256'],
                      committed_operations=len(rows), treasury_transfers=2, resource_transfers=2, settlement_events=1,
                      verified_boots=3, aftermath_instances=len(aftermath), **extended, ledger_acceptance=bool(manifest.get('ledger_acceptance')),
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
