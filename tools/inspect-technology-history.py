"""Independently compare real native state/receipts, Chronicle SQL and three process boots."""
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


def read_database(path):
    return sqlite3.connect(path.resolve().as_uri() + '?mode=ro', uri=True)


def text(value):
    return base64.urlsafe_b64decode(value + '=' * (-len(value) % 4)).decode()


def receipt(value):
    parts = value.split('|')
    check(len(parts) == 9 and parts[0] == '1', 'Invalid native receipt encoding')
    return dict(domain=parts[1], sequence=int(parts[2]), operation=parts[3], nation=parts[4],
                key=text(parts[5]), era=text(parts[6]), change=parts[7], time=parts[8])


def inspect(run):
    run = Path(run).resolve()
    manifest = json.loads((run / 'preparation.json').read_text())
    for filename, key in (('plugins/StarChronicle.jar', 'product_sha256'), ('plugins/StarCore.jar', 'native_sha256'),
                          ('plugins/TechnologyHistoryPaperFixture.jar', 'fixture_sha256'), ('paper.jar', 'paper_sha256'),
                          ('fixture/TechnologyHistoryPaperFixture.java', 'fixture_source_sha256')):
        check(sha(run / filename) == manifest[key], 'Artifact fingerprint drift: ' + filename)
    fixtures = [json.loads(line) for line in (run / 'evidence/fixture.jsonl').read_text().splitlines() if line]
    check(not any(r['kind'] == 'FAILED' for r in fixtures), 'Fixture reported a real failure')
    all_events = {}
    identity = None
    boot_counts = []
    for boot, imported, head in ((1, 133, 136), (2, 139, 139), (3, 139, 139)):
        session = json.loads((run / f'evidence/boot{boot}.session.json').read_text())
        check(session['passed'] is True and session['exit_code'] == 0 and not session.get('forced_kill'), 'Incomplete process lifecycle')
        check(session['exited_at'] > session['started_at'], 'Missing process timestamps')
        log = (run / f'evidence/boot{boot}.server.log').read_text()
        check('Done (' in log and 'Stopping server' in log and 'TECH_CLOSED' in log, 'Missing normal enable/disable')
        check(not re.search(r'\bERROR\b|TECH_FAILED|History scan retained pending evidence|Technology history scan retained its cursor|Source event was not accepted|Exception in thread', log), 'Server error or dropped source evidence')
        check('StarChronicle] Enabling StarChronicle' in log and 'StarCore] Enabling StarCore' in log, 'Missing real two-plugin enable')
        selected = [r for r in fixtures if r['boot'] == boot]
        check(sum(r['kind'] == 'READY' for r in selected) == 1 and sum(r['kind'] == 'CLOSED' for r in selected) == 1, 'Fixture boot lifecycle mismatch')
        expected_checks = {1: [3, 133], 2: [136, 139], 3: [139, 139]}[boot]
        checks = [r['data'] for r in selected if r['kind'] == 'CHECK']
        check([r['count'] for r in checks] == expected_checks, 'Missing live source/API checkpoint')
        check([r['events'] for r in session['checkpoints']] == expected_checks, 'Missing live SQL checkpoint')
        for point in session['checkpoints']:
            check(point['archive'] == 3 and point['pending'] == 0 and point['triggers'] == point['events'] - 3, 'Initial archive or live trigger checkpoint changed')
        data = run / f'evidence/boot{boot}.data'
        with read_database(data / 'StarCore/starcore.db') as native:
            check(native.execute('PRAGMA integrity_check').fetchone()[0] == 'ok', 'Native database integrity failed')
            state = dict(native.execute('SELECT property_key,property_value FROM starcore_technology_state'))
        domain = state['history.domain']
        check(int(state['history.sequence']) == head, 'Native source head changed')
        check((data / 'StarCore/technology/history/authority-domain').read_text() == domain, 'Native authority marker mismatch')
        records = {}
        for file in (data / 'StarCore/technology/history' / domain).rglob('*.receipt'):
            parsed = receipt(file.read_text())
            check(parsed['domain'] == domain and file.stem == str(parsed['sequence']), 'Native receipt identity mismatch')
            check(parsed['sequence'] not in records, 'Repeated native sequence')
            records[parsed['sequence']] = parsed
        check(sorted(records) == list(range(1, head + 1)), 'Missing native committed receipt')
        check(receipt(state['history.pending']) == records[head], 'Native state and committed outbox disagree')
        first = records[1]
        current_identity = (domain, first['nation'], first['key'], first['era'])
        check(identity is None or identity == current_identity, 'Native authority changed between boots')
        identity = current_identity
        check(len({r['operation'] for r in records.values()}) == head, 'Native operation ID reused')
        for seq, item in records.items():
            check((item['domain'], item['nation'], item['key'], item['era']) == identity, 'Source attribution changed')
            check(item['change'] == ('UNLOCKED' if seq % 2 else 'REVOKED'), 'Native change order mismatch')
        unlocked = {v for k, v in state.items() if re.fullmatch(r'technology\.\d+\.key\.\d+', k)}
        check(unlocked == ({first['key']} if head % 2 else set()), 'Native final technology state mismatch')
        with read_database(data / 'StarChronicle/chronicle.db') as db:
            check(db.execute('PRAGMA integrity_check').fetchone()[0] == 'ok', 'Chronicle database integrity failed')
            check(not db.execute('PRAGMA foreign_key_check').fetchall(), 'Chronicle foreign keys failed')
            cursor = db.execute("SELECT authority_domain,sequence,live FROM committed_history_sources WHERE namespace='starcore.technology'").fetchone()
            check(cursor == (domain, imported, 1), 'Chronicle cursor does not match native domain/sequence')
            ingress = db.execute("SELECT sequence,status,activate,json FROM committed_history_ingress WHERE namespace='starcore.technology' ORDER BY sequence").fetchall()
            check([r[0] for r in ingress] == list(range(1, imported + 1)), 'Missing or repeated ingress sequence')
            check(all(r[1] == 'ACKED' and r[2] == int(r[0] > 3) for r in ingress), 'Pending ingress or initial archive mode changed')
            events = [json.loads(r[0]) for r in db.execute('SELECT json FROM events')]
            exact = {int(e['facts']['history.sequence']): e for e in events if e['facts'].get('history.namespace') == 'starcore.technology'}
            check(len(exact) == imported, 'Missing or repeated exact history event')
            check(not any(e['id'].startswith('starcore:research:') or e['facts'].get('observation.namespace') == 'starcore.technologies' for e in events), 'Duplicate legacy technology history')
            for seq, event in exact.items():
                original = records[seq]
                check(event['id'] == f'starcore:technology:{domain}:{original["operation"]}', 'Event operation identity changed')
                check(event['occurredAt'] == original['time'], 'Native timestamp precision changed')
                check(event['participants'] == [] and event['nations'] == [original['nation']], 'False historical player/nation attribution')
                check(event['type'] == ('TECHNOLOGY_BREAKTHROUGH' if seq % 2 else 'TECHNOLOGY_REVOKED'), 'History change type changed')
                check(event['facts']['technology'] == original['key'] and event['facts']['tech.era'] == original['era'] and event['facts']['time_basis'] == 'COMMITTED', 'Receipt facts changed')
                check(json.loads(ingress[seq - 1][3]) == event, 'Pending and canonical event disagree')
                check(seq not in all_events or all_events[seq] == event, 'Event changed after restart')
                all_events[seq] = event
            triggers = db.execute("SELECT event_id FROM story_triggers WHERE json_extract(story_json,'$.id')='technology-history-test'").fetchall()
            check({r[0] for r in triggers} == {e['id'] for seq, e in exact.items() if seq > 3} and len(triggers) == imported - 3, 'Archive triggered or live commit lost its inbox')
            for table in ('instances', 'outcomes', 'contributions', 'memory_entries'):
                check(db.execute('SELECT COUNT(*) FROM ' + table).fetchone()[0] == 0, 'Unattributed unlock produced effects: ' + table)
        for checkpoint in checks:
            check(len(checkpoint['events']) == checkpoint['count'], 'Live API omitted source events')
            for event in checkpoint['events']:
                check(event == exact.get(int(event['facts']['history.sequence'])), 'Public API evidence differs from stored event')
        boot_counts.append(dict(boot=boot, native_head=head, imported=imported, initial_archive=3, story_inbox=imported - 3))
    offline = [r for r in fixtures if r['kind'] == 'DONE' and r['data']['action'] == 'offline']
    check(len(offline) == 1 and offline[0]['boot'] == 1, 'Missing Chronicle-offline source mutations')
    native_logs = [r['data'] for r in fixtures if r['kind'] == 'NATIVE']
    check([r['count'] for r in native_logs] == [3, 133, 136, 139], 'Missing real mutation batches')
    for entry in native_logs:
        check(entry['records'] == [records[i] for i in range(1, entry['count'] + 1)], 'Native public receipt log differs from persistent journal')
    return dict(status='PASS', chronicle_sha256=manifest['product_sha256'], starcore_sha256=manifest['native_sha256'],
                verified_boots=3, native_changes=139, archive_only=3, live_history=136, recovered_after_offline=3,
                boot_counts=boot_counts, source_domain=identity[0], participants=0,
                scope='Isolated Paper native administrative unlock/revoke history, bounded paging and restart; no real research participant or contribution attribution')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('run', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    result = inspect(args.run)
    if args.output:
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(result, ensure_ascii=False))
