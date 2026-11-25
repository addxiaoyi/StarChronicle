"""Independently verify ordinary-member review across three real stopped Paper boots."""
from contextlib import closing
from datetime import datetime
from pathlib import Path
import argparse
import importlib.util
import json
import re
import socket

SPEC = importlib.util.spec_from_file_location('epic_inspector', Path(__file__).with_name('inspect-epic-paper.py'))
EPIC = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(EPIC)
check = EPIC.check


def records(path):
    return [json.loads(line) for line in path.read_text(encoding='utf-8').splitlines() if line.startswith('{')]


def instant(value):
    return datetime.fromisoformat(value).timestamp()


def commands(identity):
    def command(who, tail, response):
        return who, '/chronicle ' + tail.replace('{id}', identity), response
    return {
        1: [command('D', 'choose {id} review yes', '已投票 1/2'),
            command('D', 'member revalidate {id} review', 'Current nation does not match the original membership'),
            command('B', 'choose {id} review yes', '已投票 1/2'),
            command('D', 'member status {id}', '待复核'),
            command('D', 'member revalidate {id} review', '成员资格已重新核验'),
            command('D', 'choose {id} review yes', '已进入下一阶段'),
            command('D', 'member exit {id} review', '剧情阶段已改变'),
            command('D', 'member exit {id} closing', '已退出剧情实例')],
        2: [command('D', 'member status {id}', '已退出'),
            command('D', 'member revalidate {id} closing', 'Exited membership cannot be silently restored'),
            command('D', 'join {id}', '重新加入须恢复原国家身份'),
            command('D', 'join {id}', '已加入共同剧情'),
            command('D', 'choose {id} closing yes', '已投票 1/2'),
            command('B', 'choose {id} closing yes', '剧情已完成'),
            command('D', 'member exit {id} end', '已完成剧情的成员属于历史记录')],
        3: [command('D', 'member status {id}', '已核验'),
            command('D', 'member exit {id} end', '已完成剧情的成员属于历史记录')],
    }


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
    opened = [r for r in fixture if r['kind'] == 'MEMBER_OPENED']
    check(len(opened) == 1 and opened[0]['boot'] == 1, 'Expected one original member story')
    identity = opened[0]['data']['instance']
    players = manifest['players']
    expected_commands = commands(identity)
    checks, actual_commands, moves = {}, {}, {}
    seeded = next(r['data'] for r in fixture if r['kind'] == 'SEEDED')
    nations = {who: seeded[who] for who in 'ABC'}
    expected_states = {1: [('PENDING', 'review'), ('PENDING', 'review'), ('APPROVED', 'closing'), ('EXITED', 'closing'), ('EXITED', 'closing')],
                       2: [('EXITED', 'closing'), ('APPROVED', 'closing'), ('APPROVED', 'end')],
                       3: [('APPROVED', 'end'), ('APPROVED', 'end')]}
    expected_choices = {'review/' + players[who]: 'yes' for who in 'BD'}
    final_choices = dict(expected_choices, review='yes', closing='yes')
    final_choices.update({'closing/' + players[who]: 'yes' for who in 'BD'})
    for boot, scenario in enumerate(('members-first', 'members-second', 'members-third'), 1):
        session = EPIC.read(evidence / f'boot{boot}.session.json')
        check(session['boot'] == boot and session['scenario'] == scenario and session['status'] == 'COMPLETE', 'Incomplete controller scenario')
        processes = session['processes']
        check(len(processes) == 2 and {p['name'] for p in processes} == {'server', 'clients'}, 'Missing actual server/client processes')
        check(all(p.get('exit_code') == 0 and p['exited_at'] >= p['started_at'] for p in processes), 'Abnormal process exit')
        rows = [r for r in fixture if r['boot'] == boot]
        for kind in ('READY', 'SEEDED', 'CLOSED'):
            check(sum(r['kind'] == kind for r in rows) == 1, 'Missing boot lifecycle: ' + kind)
        check(next(r['data'] for r in rows if r['kind'] == 'READY') ==
              {'product': manifest['plugin_sha256'], 'native': manifest['starcore_sha256'], 'route': manifest['route']}, 'Running artifact mismatch')
        check(next(r['data'] for r in rows if r['kind'] == 'SEEDED') == seeded and seeded['nonOp'] is True, 'Native seed identity or permissions changed')
        check(next(r['data'] for r in rows if r['kind'] == 'CLOSED') == {'normalDisable': True}, 'Abnormal fixture shutdown')
        server = (evidence / f'boot{boot}.server.log').read_text(encoding='utf-8')
        diagnostics = re.findall(r'^.*(?:ERROR\]|ERROR\]:|Failed to (?:enable|disable) STARCORE module|同步保存失败|异步保存失败|SQLITE_BUSY|startup failed|Could not pass event).*$', server, re.M | re.I)
        check(not diagnostics, 'Server persistence/lifecycle diagnostic: ' + '\n'.join(diagnostics))
        check(all(word in server for word in ('Done (', 'Stopping server', 'All dimensions are saved')), 'Incomplete server startup/shutdown')
        clients = records(evidence / f'boot{boot}.clients.log')
        check(not any(r['kind'] == 'FAILED' for r in clients), 'Protocol client failure')
        spawns = [r['data'] for r in clients if r['kind'] == 'SPAWN']
        check(len(spawns) == 4 and {s['who'] for s in spawns} == set('ABCD') and all(s['uuid'] == players[s['who']] for s in spawns), 'Missing real client identities')
        actual_commands[boot] = [r for r in clients if r['kind'] == 'COMMAND_RESULT']
        check(len(actual_commands[boot]) == len(expected_commands[boot]), 'Missing actual member command query')
        for row, expected in zip(actual_commands[boot], expected_commands[boot]):
            data = row['data']
            check((data['who'], data['command'], data['contains']) == expected and expected[2] in data['response'], 'Unexpected actual member command response')
        moves[boot] = [r for r in rows if r['kind'] == 'MEMBER_NATIVE_MOVE']
        expected_moves = [{'actor': players['D'], 'before': nations[a], 'after': nations[b], 'currentHolder': False} for a, b in [('A', 'B'), ('B', 'A')]] if boot < 3 else []
        check([r['data'] for r in moves[boot]] == expected_moves, 'Native ordinary-member move mismatch')
        checks[boot] = [r for r in rows if r['kind'] == 'MEMBER_CHECK']
        check(len(checks[boot]) == len(expected_states[boot]), 'Missing live member API observation')
        for index, (row, (status, node)) in enumerate(zip(checks[boot], expected_states[boot])):
            state, story, reviews = (row['data'][key] for key in ('state', 'story', 'reviews'))
            check(state['instanceId'] == story['id'] == identity and state['playerId'] == players['D'], 'Member observation identity mismatch')
            check((state['status'], state['nodeId']) == (status, node) and story['node'] == node, 'Unexpected member state/node')
            check(state['completed'] == story['completed'] == (node == 'end'), 'Member completion mismatch')
            check(state['originalNationId'] == nations['A'] and state['observedNationId'] == nations['B' if status == 'PENDING' else 'A'], 'Original or observed nation changed')
            members = {players['A']: nations['A'], players['B']: nations['B']}
            if status != 'EXITED': members[players['D']] = nations['A']
            check(story['members'] == members, 'Member projection mismatch')
            votes = {players['B']: 'yes'} if boot == 1 and index == 1 else {}
            check(story['votes'] == votes, 'Inactive personal vote still counts')
            choices = {'review/' + players['D']: 'yes'} if boot == 1 and index == 0 else expected_choices
            if node == 'closing': choices = dict(expected_choices, review='yes')
            if node == 'end': choices = final_choices
            check(story['choices'] == choices, 'Historical choice projection changed')
            check(len(reviews) == 1 and reviews[0]['status'] == status and reviews[0]['originalNationId'] == nations['A'], 'Review query differs from member state')
            check(reviews[0]['instanceId'] == identity and reviews[0]['playerId'] == players['D'] and reviews[0]['observedNationId'] == state['observedNationId'] and reviews[0]['reason'] == state['reason'], 'Review identity/evidence mismatch')
            # Cross-check the API observations against the independently retained server stream.
            logged = [json.loads(line.split('EPIC_MEMBER_CHECK ', 1)[1]) for line in server.splitlines() if 'EPIC_MEMBER_CHECK ' in line]
            check(row['data'] in logged, 'Live member observation absent from server stream')
        server_process = next(p for p in processes if p['name'] == 'server')
        check(all(server_process['started_at'] <= instant(r['time']) <= server_process['exited_at'] for r in rows), 'Fixture event outside its server lifetime')
    # Interleave native moves, actual responses and API observations; final state alone is insufficient.
    timelines = {1: ['c0', 'm0', 's0', 'c1', 'c2', 's1', 'm1', 'c3', 'c4', 'c5', 's2', 'c6', 'c7', 's3', 's4'],
                 2: ['s0', 'c0', 'c1', 'm0', 'c2', 'm1', 'c3', 's1', 'c4', 'c5', 'c6', 's2'],
                 3: ['s0', 'c0', 'c1', 's1']}
    for boot, order in timelines.items():
        series = {'c': actual_commands[boot], 'm': moves[boot], 's': checks[boot]}
        times = [instant(series[token[0]][int(token[1:])]['time']) for token in order]
        check(all(a < b for a, b in zip(times, times[1:])), 'Member scenario causal order changed')
    check(instant(checks[1][4]['time']) - instant(checks[1][3]['time']) >= 3, 'Missing automatic-rejoin observation interval')
    check(checks[1][-1]['data'] == checks[2][0]['data'], 'Exited state changed across restart')
    check(checks[2][-1]['data'] == checks[3][0]['data'] == checks[3][1]['data'], 'Completed history changed across restart')
    with closing(EPIC.connect(run / 'plugins/StarChronicle/chronicle.db')) as db:
        stored = list(db.execute("select * from instances where story='member-review'"))
        check(len(stored) == 1 and stored[0]['id'] == identity and stored[0]['completed'] == 1, 'Expected one completed member story')
        check(json.loads(stored[0]['json']) == checks[3][-1]['data']['story'], 'Persisted story differs from live observation')
        audit = [dict(r) for r in db.execute('select * from story_member_review_log order by seq')]
        check([r['status'] for r in audit] == ['PENDING', 'APPROVED', 'EXITED', 'APPROVED'], 'Review audit sequence changed')
        observed_reviews = [checks[1][i]['data']['reviews'][0] for i in (0, 2, 3)] + [checks[2][1]['data']['reviews'][0]]
        mapping = {'instance_id': 'instanceId', 'player_id': 'playerId', 'original_nation_id': 'originalNationId', 'observed_nation_id': 'observedNationId', 'status': 'status', 'reason': 'reason', 'checked_at': 'checkedAt'}
        for row, observed in zip(audit, observed_reviews):
            check(all(row[column] == observed[key] for column, key in mapping.items()), 'Review audit differs from live evidence')
        current = list(db.execute('select * from story_member_reviews'))
        check(len(current) == 1 and dict(current[0]) == {k: v for k, v in audit[-1].items() if k != 'seq'}, 'Current review differs from last audit')
        ballots = [dict(r) for r in db.execute('select * from story_choice_log order by seq')]
        expected_ballots = [('review', 'BALLOT', 'D', 1), ('review', 'BALLOT', 'B', 1), ('review', 'BALLOT', 'D', 2), ('review', 'RESOLUTION', 'D', 1),
                            ('closing', 'BALLOT', 'D', 1), ('closing', 'BALLOT', 'B', 1), ('closing', 'RESOLUTION', 'B', 1)]
        check(len(ballots) == len(expected_ballots), 'Historical ballot/revision missing or duplicated')
        for row, (node, kind, who, revision) in zip(ballots, expected_ballots):
            check((row['node_id'], row['kind'], row['committed_by'], row['revision']) == (node, kind, players[who], revision), 'Historical ballot revision mismatch')
            check(row['player_id'] == (players[who] if kind == 'BALLOT' else None) and row['actor_key'] == ('player:' + players[who] if kind == 'BALLOT' else 'world'), 'Historical ballot actor mismatch')
            check(row['instance_id'] == identity and row['source_event_id'] == 'fixture:member-review' and row['nation_id'] == nations['A' if who == 'D' else who], 'Historical ballot attribution mismatch')
            check(row['story_id'] == 'member-review' and row['story_version'] == '1' and row['scope_key'] == 'world' and row['choice_id'] == 'yes' and row['provenance'] == 'LIVE', 'Historical ballot provenance mismatch')
        check(instant(ballots[0]['chosen_at']) < instant(audit[0]['checked_at']) < instant(audit[1]['checked_at']) < instant(ballots[2]['chosen_at']) < instant(audit[2]['checked_at']) < instant(audit[3]['checked_at']) < instant(ballots[4]['chosen_at']), 'Ballot and review chronology mismatch')
        terminal = [json.loads(r[0]) for r in db.execute("select json from events where type='MEMBER_REVIEW_SETTLED'")]
        check(len(terminal) == 1 and set(terminal[0]['participants']) == {players[w] for w in 'ABD'} and set(terminal[0]['nations']) == {nations[w] for w in 'AB'}, 'Missing or duplicated member settlement')
        check(terminal[0]['source'] == 'StarChronicle' and 'instance=' + identity in terminal[0]['evidence'], 'Member settlement source mismatch')
    with closing(EPIC.connect(run / 'plugins/StarCore/starcore.db')) as db:
        native = dict(db.execute('select property_key,property_value from starcore_nation_state'))
        check(native['count'] == '3', 'Unexpected persisted native nations')
        native_members = {}
        for i in range(3):
            prefix = f'nation.{i}.'
            nation = native[prefix + 'id']
            who = next((w for w in 'ABC' if nations[w] == nation), None)
            check(who is not None and native[prefix + 'founderId'] == players[who], 'Persisted native holder changed')
            members = [native[prefix + f'member.{j}.id'] for j in range(int(native[prefix + 'memberCount']))]
            check(set(members) == {players[w] for w in ('AD' if who == 'A' else who)}, 'Persisted native membership mismatch')
            for member in members:
                check(member not in native_members, 'Duplicate persisted native membership')
                native_members[member] = nation
        check(native_members[players['D']] == nations['A'], 'Ordinary member did not return to original native nation')
    return dict(status='PASS', chronicle_sha256=manifest['plugin_sha256'], starcore_sha256=manifest['starcore_sha256'], instance=identity,
                verified_boots=3, normal_process_exits=6, actual_command_results=17, live_member_checks=10, native_member_moves=4,
                review_audit_rows=4, historical_ballots=5, collective_resolutions=2, settlement_events=1,
                verification='Actual non-OP commands, ordered live observations, immutable ballot/review evidence and native persisted membership cross-checked; stopped port.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('run', type=Path)
    args = parser.parse_args()
    result = inspect(args.run)
    (args.run / 'evidence/member-check.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result))
