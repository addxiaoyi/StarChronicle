"""Independently compare native capital SQLite, Chronicle, public receipts and three process boots."""
from pathlib import Path
import argparse
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


def read_database(path):
    return sqlite3.connect(path.resolve().as_uri() + '?mode=ro', uri=True)


def inspect(run):
    run = Path(run).resolve()
    manifest = json.loads((run / 'preparation.json').read_text())
    for filename, key in (('plugins/StarChronicle.jar', 'product_sha256'), ('plugins/StarCore.jar', 'native_sha256'),
                          ('plugins/CapitalHistoryPaperFixture.jar', 'fixture_sha256'), ('paper.jar', 'paper_sha256'),
                          ('fixture/CapitalHistoryPaperFixture.java', 'fixture_source_sha256')):
        check(sha(run / filename) == manifest[key], 'Artifact fingerprint drift: ' + filename)
    fixtures = [json.loads(line) for line in (run / 'evidence/fixture.jsonl').read_text().splitlines() if line]
    check(not any(r['kind'] == 'FAILED' for r in fixtures), 'Fixture reported a real failure')
    all_events, identity, boot_counts = {}, None, []
    for boot, imported, head in ((1, 6, 136), (2, 139, 139), (3, 139, 139)):
        session = json.loads((run / f'evidence/boot{boot}.session.json').read_text())
        check(session['passed'] is True and session['exit_code'] == 0 and not session.get('forced_kill'), 'Incomplete process lifecycle')
        check(session['exited_at'] > session['started_at'], 'Missing process timestamps')
        log = (run / f'evidence/boot{boot}.server.log').read_text()
        check('Done (' in log and 'Stopping server' in log and 'CAPITAL_CLOSED' in log, 'Missing normal enable/disable')
        check(not re.search(r'\bERROR\b|CAPITAL_FAILED|History scan retained pending evidence|Capital history scan retained its cursor|history retained its original ledger|Source event was not accepted|Exception in thread|Server thread dump', log), 'Server error or dropped source evidence')
        check('StarChronicle] Enabling StarChronicle' in log and 'StarCore] Enabling StarCore' in log, 'Missing real two-plugin enable')
        selected = [r for r in fixtures if r['boot'] == boot]
        ready = [r['data'] for r in selected if r['kind'] == 'READY']
        check(len(ready) == 1 and sum(r['kind'] == 'CLOSED' for r in selected) == 1, 'Fixture boot lifecycle mismatch')
        check(identity is None or identity == ready[0], 'Native identity changed between boots')
        identity = ready[0]
        domain, nation = identity['domain'], identity['nation']
        checks = [r['data'] for r in selected if r['kind'] == 'CHECK']
        expected_checks = {1: [3, 6], 2: [136, 139], 3: [139, 139]}[boot]
        check([r['count'] for r in checks] == expected_checks, 'Missing live source/API checkpoint')
        check([r['events'] for r in session['checkpoints']] == expected_checks, 'Missing live SQL checkpoint')
        for point in session['checkpoints']:
            check(point['archive'] == 3 and point['pending'] == 0 and point['triggers'] == point['events'] - 3, 'Initial archive or live trigger checkpoint changed')
        authority = [r['data'] for r in selected if r['kind'] == 'AUTHORITY']
        start = expected_checks[0]
        check(len(authority) == 1 and authority[0]['count'] == start and authority[0]['replaySequence'] == start, 'Missing native authorization and replay proof')
        check(authority[0]['actor'] == identity['founder' if boot == 1 else 'successor'] and
              authority[0]['rejectedActor'] == identity['successor' if boot == 1 else 'founder'], 'Founder transfer authorization changed')
        data = run / f'evidence/boot{boot}.data'
        with read_database(data / 'StarCore/starcore.db') as state_db:
            state = dict(state_db.execute('SELECT property_key,property_value FROM starcore_nation_state'))
            keys = [key[:-3] for key, value in state.items() if re.fullmatch(r'nation\.\d+\.id', key) and value == nation]
            check(len(keys) == 1 and state[keys[0] + '.founderId'] == identity['successor'], 'Native current founder did not persist')
        for key, mayor in (('cityA', 'founder'), ('cityB', 'successor')):
            city = json.loads((data / 'StarCore/cities' / (identity[key] + '.json')).read_text())
            check(city['id'] == identity[key] and city['nationId'] == nation and
                  city['residents'][identity[mayor]] == 'MAYOR', 'Native city ownership did not persist')
        capital = data / 'StarCore/nation/capital-designations.log'
        fence = capital.read_text().strip().split('|')
        check(len(fence) == 4 and fence[:3] == ['STARCORE_CAPITAL_SQLITE_V1', 'READY', domain], 'Native authority marker mismatch')
        check(sha(Path(str(capital) + '.legacy')) == fence[3], 'Legacy migration archive changed')
        with read_database(Path(str(capital) + '.sqlite')) as native:
            check(native.execute('PRAGMA integrity_check').fetchone()[0] == 'ok', 'Native database integrity failed')
            state = native.execute('SELECT version,domain,sequence,legacy_sha256 FROM authority WHERE id=1').fetchone()
            check(state == (1, domain, head, fence[3]), 'Native source head or domain changed')
            records = {row[0]: dict(domain=domain, sequence=row[0], operation=row[1], nation=row[2], previous=row[3], city=row[4], actor=row[5], time=row[6])
                       for row in native.execute('SELECT sequence,operation,nation,previous_city,city,actor,committed_at FROM designations ORDER BY sequence')}
        check(sorted(records) == list(range(1, head + 1)), 'Missing native committed receipt')
        check(len({r['operation'] for r in records.values()}) == head, 'Native operation ID reused')
        check(any(re.search(r'\.\d{4,9}Z$', row['time']) for row in records.values()), 'No submillisecond commit evidence')
        for seq, item in records.items():
            check(item['nation'] == nation and item['actor'] == identity['founder' if seq <= 6 else 'successor'], 'Source actor or nation attribution changed')
            check(item['city'] == identity['cityA' if seq % 2 else 'cityB'] and
                  item['previous'] == (None if seq == 1 else identity['cityB' if seq % 2 else 'cityA']), 'Native capital transition changed')
        with read_database(data / 'StarChronicle/chronicle.db') as db:
            check(db.execute('PRAGMA integrity_check').fetchone()[0] == 'ok', 'Chronicle database integrity failed')
            check(not db.execute('PRAGMA foreign_key_check').fetchall(), 'Chronicle foreign keys failed')
            cursor = db.execute("SELECT authority_domain,sequence,live FROM committed_history_sources WHERE namespace='starcore.capitals'").fetchone()
            check(cursor == (domain, imported, 1), 'Chronicle cursor does not match native domain/sequence')
            ingress = db.execute("SELECT sequence,status,activate,json FROM committed_history_ingress WHERE namespace='starcore.capitals' ORDER BY sequence").fetchall()
            check([r[0] for r in ingress] == list(range(1, imported + 1)), 'Missing or repeated ingress sequence')
            check(all(r[1] == 'ACKED' and r[2] == int(r[0] > 3) for r in ingress), 'Pending ingress or initial archive mode changed')
            events = [json.loads(r[0]) for r in db.execute('SELECT json FROM events')]
            exact = {int(e['facts']['history.sequence']): e for e in events if e['facts'].get('history.namespace') == 'starcore.capitals'}
            check(len(exact) == imported, 'Missing or repeated exact history event')
            check(not any(e['facts'].get('observation.namespace') == 'starcore.capitals' for e in events), 'Duplicate legacy capital history')
            for seq, event in exact.items():
                original = records[seq]
                check(event['id'] == f'starcore:capital:{domain}:{original["operation"]}', 'Event operation identity changed')
                check(event['occurredAt'] == original['time'], 'Native timestamp precision changed')
                check(event['participants'] == [original['actor']] and event['nations'] == [nation], 'False historical player/nation attribution')
                check(event['type'] == ('CAPITAL_DESIGNATED' if seq == 1 else 'CAPITAL_MOVED'), 'History change type changed')
                expected = {'capital.city': original['city'], 'city.id': original['city'], 'capital.change': 'DESIGNATED' if seq == 1 else 'MOVED',
                            'actor.id': original['actor'], 'nation.id': nation, 'time_basis': 'COMMITTED', 'history.namespace': 'starcore.capitals',
                            'history.authority': domain, 'history.sequence': str(seq), 'history.operation': original['operation']}
                check(all(event['facts'].get(key) == value for key, value in expected.items()) and
                      event['facts'].get('capital.previous') == original['previous'], 'Receipt facts changed')
                check(json.loads(ingress[seq - 1][3]) == event, 'Pending and canonical event disagree')
                check(seq not in all_events or all_events[seq] == event, 'Event changed after restart')
                all_events[seq] = event
            triggers = db.execute("SELECT event_id FROM story_triggers WHERE json_extract(story_json,'$.id')='capital-history-test'").fetchall()
            check({r[0] for r in triggers} == {e['id'] for seq, e in exact.items() if seq > 3} and len(triggers) == imported - 3, 'Archive triggered or live commit lost its inbox')
            for table in ('instances', 'outcomes', 'contributions', 'memory_entries'):
                check(db.execute('SELECT COUNT(*) FROM ' + table).fetchone()[0] == 0, 'Offline source produced unintended effects: ' + table)
        for checkpoint in checks:
            check(len(checkpoint['events']) == checkpoint['count'], 'Live API omitted source events')
            for event in checkpoint['events']:
                check(event == exact.get(int(event['facts']['history.sequence'])), 'Public API evidence differs from stored event')
        boot_counts.append(dict(boot=boot, native_head=head, imported=imported, initial_archive=3, story_inbox=imported - 3))
    offline = [r for r in fixtures if r['kind'] == 'DONE' and r['data']['action'] == 'offline']
    check(len(offline) == 1 and offline[0]['boot'] == 1, 'Missing Chronicle-offline source mutations')
    native_logs = [r['data'] for r in fixtures if r['kind'] == 'NATIVE']
    check([r['count'] for r in native_logs] == [3, 6, 136, 139], 'Missing real mutation batches')
    for entry in native_logs:
        check(entry['records'] == [records[i] for i in range(1, entry['count'] + 1)], 'Native public receipt log differs from persistent journal')
    return dict(status='PASS', chronicle_sha256=manifest['product_sha256'], starcore_sha256=manifest['native_sha256'],
                verified_boots=3, native_changes=139, archive_only=3, live_history=136, recovered_after_offline=130,
                boot_counts=boot_counts, source_domain=identity['domain'], original_actors=2,
                scope='Isolated Paper native capital designation, ownership transfer, paging, authorization and restarts; offline fixture identities, no client or full epic combination claim')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('run', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    result = inspect(args.run)
    if args.output:
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(result, ensure_ascii=False))
