"""Capture fingerprint-bound, read-only consistent SQLite evidence. Inspect never starts a
process, writes a database, copies configuration contents or claims multiplayer acceptance.
Use --checkpoint boot1|boot2|boot3 to inspect the coordinator's normally stopped copies while
the runtime proceeds to its next boot. Their empty WAL/journal is checked before immutable=1
is used alongside mode=ro, preventing SQLite from creating WAL/SHM metadata for a stopped copy.
Checkpoint origin/proof limitations are recorded.
"""
from pathlib import Path
from datetime import datetime, timezone
import argparse
import base64
import hashlib
import json
import re
import sqlite3
import struct
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
NATIONAL = {'national-smoke-primary', 'national-smoke-invalidation'}
REQUIRED = {'events', 'event_nations', 'event_players', 'instances', 'outcomes', 'facts', 'story_triggers',
            'story_trigger_receipts', 'story_choice_log', 'story_definitions', 'story_definition_formats',
            'story_definition_upgrades', 'story_nation_votes', 'story_nation_vote_log',
            'story_nation_actor_bindings', 'story_nation_resolutions'}


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(chunk)
    return value.hexdigest()


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'))


def stable(*parts):
    result = hashlib.sha256()
    for part in parts:
        raw = str(part).encode('utf-8')
        result.update(str(len(raw)).encode('ascii') + b':' + raw)
    return result.hexdigest()


def typed(value):
    if value is None:
        return {'type': 'NULL'}
    if isinstance(value, int):
        return {'type': 'INTEGER', 'value': str(value)}
    if isinstance(value, float):
        return {'type': 'REAL', 'ieee754_hex': struct.pack('>d', value).hex()}
    if isinstance(value, bytes):
        return {'type': 'BLOB', 'base64': base64.b64encode(value).decode('ascii')}
    return {'type': 'TEXT', 'value': value}


def ordinary(value):
    if isinstance(value, bytes):
        return {'$sqlite_blob_base64': base64.b64encode(value).decode('ascii')}
    if isinstance(value, float):
        return {'$sqlite_real_ieee754': struct.pack('>d', value).hex()}
    return value


def file_state(path):
    result = {}
    for suffix in ['', '-wal', '-shm', '-journal']:
        item = Path(str(path) + suffix)
        result[item.name] = {'exists': item.exists(), 'size': item.stat().st_size if item.exists() else None,
                             'sha256': digest(item) if item.is_file() else None}
    return result


def snapshot(path, selected=None):
    if not path.is_file():
        raise SystemExit('Missing evidence database: ' + str(path))
    before = file_state(path)
    if any(Path(str(path) + suffix).exists() and Path(str(path) + suffix).stat().st_size for suffix in ['-wal', '-journal']):
        raise SystemExit('Require a complete stopped main database with no nonempty WAL/journal before immutable reading')
    connection = sqlite3.connect(path.resolve().as_uri() + '?mode=ro&immutable=1', uri=True, isolation_level=None, timeout=15)
    connection.row_factory = sqlite3.Row
    try:
        connection.execute('PRAGMA query_only=ON')
        connection.execute('BEGIN')
        schema = [dict(row) for row in connection.execute('SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY type,name')]
        names = [row['name'] for row in schema if row['type'] == 'table']
        if selected is not None:
            names = [name for name in names if name in selected]
        tables = {}
        for name in names:
            if not re.fullmatch(r'[A-Za-z0-9_]+', name):
                raise SystemExit('Unexpected SQLite identifier')
            metadata = [dict(row) for row in connection.execute('PRAGMA table_info("' + name + '")')]
            columns = [item['name'] for item in metadata]
            raw_rows = [tuple(row) for row in connection.execute('SELECT * FROM "' + name + '"')]
            raw_rows.sort(key=lambda row: canonical([typed(value) for value in row]))
            exact = [[typed(value) for value in row] for row in raw_rows]
            tables[name] = {'columns': metadata, 'rows': [dict(zip(columns, [ordinary(value) for value in row])) for row in raw_rows],
                            'typed_rows': exact, 'row_count': len(raw_rows),
                            'typed_content_sha256': hashlib.sha256(canonical(exact).encode('utf-8')).hexdigest()}
        result = {'schema_version': connection.execute('PRAGMA user_version').fetchone()[0],
                  'integrity': [row[0] for row in connection.execute('PRAGMA integrity_check')],
                  'foreign_key_violations': [list(row) for row in connection.execute('PRAGMA foreign_key_check')],
                  'schema_objects': schema, 'tables': tables,
                  'read_contract': {'uri_mode': 'ro', 'immutable': True, 'empty_wal_journal_checked': True, 'query_only': True, 'explicit_begin': True,
                                    'transaction_scope': 'one complete consistent read transaction per database; no cross-database atomic transaction'}}
        connection.execute('ROLLBACK')
    finally:
        connection.close()
    after = file_state(path)
    result.update(source_files_before=before, source_files_after=after, source_bytes_unchanged=before == after)
    return result


def rows(data, table):
    return data['tables'].get(table, {}).get('rows', [])


def canonical_uuid(value):
    try:
        return isinstance(value, str) and str(uuid.UUID(value)) == value
    except (ValueError, AttributeError, TypeError):
        return False


def structural(chronicle, core):
    checks = []
    def check(name, condition, detail=None):
        checks.append({'name': name, 'status': 'PASS' if condition else 'FAIL', 'detail': detail})
    check('chronicle_schema5', chronicle['schema_version'] == 5)
    check('chronicle_integrity', chronicle['integrity'] == ['ok'], chronicle['integrity'])
    check('chronicle_foreign_keys', not chronicle['foreign_key_violations'], chronicle['foreign_key_violations'])
    check('complete_required_tables', REQUIRED <= chronicle['tables'].keys(), sorted(REQUIRED - chronicle['tables'].keys()))
    check('chronicle_read_did_not_change_bytes', chronicle['source_bytes_unchanged'])
    check('native_integrity', core['integrity'] == ['ok'], core['integrity'])
    check('native_foreign_keys', not core['foreign_key_violations'], core['foreign_key_violations'])
    check('native_read_did_not_change_bytes', core['source_bytes_unchanged'])
    try:
        events = {row['id']: json.loads(row['json']) for row in rows(chronicle, 'events')}
        instances = {row['id']: json.loads(row['json']) for row in rows(chronicle, 'instances')}
        source_errors = []
        for row in rows(chronicle, 'events'):
            e = events[row['id']]
            value = {'id': e['id'], 'type': e['type'], 'time': 'OBSERVED' if e['facts'].get('time_basis') == 'OBSERVED' else e['occurredAt'],
                     'players': sorted(e['participants']), 'nations': sorted(e['nations']), 'facts': e['facts'], 'source': e['source'], 'evidence': e['evidence']}
            if e['id'] != row['id'] or e['type'] != row['type'] or stable(canonical(value)) != row['hash']:
                source_errors.append(row['id'])
        check('canonical_event_identity_and_hashes', not source_errors, source_errors)
        metadata_errors = []
        for row in rows(chronicle, 'instances'):
            i = instances[row['id']]
            if i['id'] != row['id'] or i['definition']['id'] != row['story'] or i['scopeKey'] != row['scope'] or bool(row['completed']) != i['completed']:
                metadata_errors.append(row['id'])
        check('instance_metadata_matches_pinned_json', not metadata_errors, metadata_errors)
        national_instances = {key: value for key, value in instances.items() if value['definition'].get('voteUnit') == 'NATION'}
        check('national_instances_never_store_personal_vote_maps', all(not i['votes'] and all('/' not in key for key in i['choices']) for i in national_instances.values()))
        formats = {(r['story'], r['version'], r['fingerprint']): r['canonical_version'] for r in rows(chronicle, 'story_definition_formats')}
        upgrades = {(r['story'], r['version'], r['from_fingerprint']): r for r in rows(chronicle, 'story_definition_upgrades')}
        archive_errors = []
        for row in rows(chronicle, 'story_definitions'):
            key = (row['story'], row['version'], row['fingerprint'])
            if hashlib.sha256(row['json'].encode('utf-8')).hexdigest() != row['fingerprint']:
                archive_errors.append({'key': key, 'error': 'raw hash'})
                continue
            definition = json.loads(row['json'])
            if definition['id'] != row['story'] or definition['version'] != row['version'] or not row['provenance'] or not row['first_seen']:
                archive_errors.append({'key': key, 'error': 'metadata'})
            if formats.get(key) == 2:
                if definition.get('voteUnit') not in ['PLAYER', 'NATION'] or key in upgrades:
                    archive_errors.append({'key': key, 'error': 'V2 format/association'})
            elif formats.get(key) == 1:
                link = upgrades.get(key)
                if 'voteUnit' in definition or not link or link['from_canonical_version'] != 1 or link['to_canonical_version'] != 2 or link['algorithm'] != 'V1_TO_V2_ADD_PLAYER_VOTE_UNIT':
                    archive_errors.append({'key': key, 'error': 'V1 format/association'})
                elif hashlib.sha256(link['json'].encode('utf-8')).hexdigest() != link['to_fingerprint'] or json.loads(link['json']) != {**definition, 'voteUnit': 'PLAYER'}:
                    archive_errors.append({'key': key, 'error': 'V1->V2 semantics/hash'})
            else:
                archive_errors.append({'key': key, 'error': 'missing/unknown format'})
        check('raw_definition_evidence_and_versioned_sidecars', not archive_errors, archive_errors)
        logs = rows(chronicle, 'story_nation_vote_log')
        bindings = {(r['instance_id'], r['node_id'], r['actor_id']): r['nation_id'] for r in rows(chronicle, 'story_nation_actor_bindings')}
        grouped = {}
        action_errors = []
        for row in logs:
            key = (row['instance_id'], row['node_id'], row['nation_id'])
            grouped.setdefault(key, []).append(row)
            i = instances.get(row['instance_id'])
            valid_status = (row['status'] == 'ACTIVE' and row['choice_id'] is not None and row['action'] in ['CAST', 'CHANGE'] or
                            row['status'] == 'WITHDRAWN' and row['choice_id'] is None and row['action'] == 'WITHDRAW' or
                            row['status'] == 'INVALIDATED' and row['choice_id'] is None and row['action'] == 'INVALIDATE')
            if not i or i['definition'].get('voteUnit') != 'NATION' or row['id'] != stable('national-vote', *key, row['revision']) or not valid_status or not canonical_uuid(row['nation_id']) or not canonical_uuid(row['actor_id']):
                action_errors.append(row['id'])
                continue
            if row['source_event_id'] != i['sourceEventId'] or row['story_id'] != i['definition']['id'] or row['story_version'] != i['definition']['version'] or not row['authority_evidence']:
                action_errors.append(row['id'])
            if row['action'] != 'INVALIDATE' and (bindings.get((row['instance_id'], row['node_id'], row['actor_id'])) != row['nation_id'] or row['actor_nation_id'] != row['nation_id'] or i['members'].get(row['actor_id']) != row['nation_id']):
                action_errors.append(row['id'])
            source = events[i['sourceEventId']]
            if source['nations'] and row['actor_nation_id'] not in source['nations'] or not source['nations'] and source['facts'].get('audience') != 'GLOBAL':
                action_errors.append(row['id'])
        for key, values in grouped.items():
            revisions = sorted(r['revision'] for r in values)
            if revisions != list(range(1, len(values) + 1)):
                action_errors.append({'key': key, 'revisions': revisions})
        check('national_actions_identity_attribution_and_contiguous_revisions', not action_errors, action_errors)
        latest = {key: max(value, key=lambda r: r['revision']) for key, value in grouped.items()}
        projections = {(r['instance_id'], r['node_id'], r['nation_id']): r for r in rows(chronicle, 'story_nation_votes')}
        projection_errors = []
        for key, projection in projections.items():
            action = latest.get(key)
            if not action or projection['log_seq'] != action['seq'] or any(projection[name] != action[name] for name in ['revision', 'status', 'choice_id', 'actor_id', 'checked_at', 'authority_evidence']):
                projection_errors.append(key)
        check('national_projection_matches_every_latest_log', not projection_errors and projections.keys() == latest.keys(), projection_errors)
        frozen_errors = []
        collective = {r['id']: r for r in rows(chronicle, 'story_choice_log') if r['kind'] == 'RESOLUTION'}
        for row in rows(chronicle, 'story_nation_resolutions'):
            resolution = json.loads(row['json'])
            i = instances[row['instance_id']]
            expected_id = stable('choice-log', i['id'], row['node_id'], 'RESOLUTION', i['scopeKey'], '1')
            actual = [{'nationId': p['nation_id'], 'revision': p['revision'], 'choiceId': p['choice_id'], 'actorId': p['actor_id']} for key, p in sorted(projections.items())
                      if key[:2] == (row['instance_id'], row['node_id']) and p['status'] == 'ACTIVE' and p['choice_id'] == row['choice_id']]
            shared = collective.get(expected_id)
            if row['id'] != expected_id or resolution['id'] != expected_id or resolution['counted'] != actual or len(actual) < row['quorum'] or len({p['nationId'] for p in actual}) != len(actual):
                frozen_errors.append(expected_id)
            if row['quorum'] != i['definition']['nodes'][row['node_id']]['quorum'] or not shared or shared['choice_id'] != row['choice_id'] or shared['player_id'] is not None:
                frozen_errors.append(expected_id)
        check('frozen_quorum_and_shared_resolution_evidence', not frozen_errors, frozen_errors)
        personal = [r['id'] for r in rows(chronicle, 'story_choice_log') if r['kind'] == 'BALLOT' and r['instance_id'] in national_instances]
        check('national_actions_never_become_personal_ballots', not personal, personal)
        check('normally_stopped_outcomes_have_no_inflight_attempts', not any(r['status'] == 'RUNNING' for r in rows(chronicle, 'outcomes')))
        primary = [i for i in instances.values() if i['definition']['id'] == 'national-smoke-primary']
        if primary:
            i = primary[0]
            ending = [e for e in events.values() if e['type'] == 'NATIONAL_COUNCIL_SMOKE_FINISHED']
            check('primary_ending_exists_once_only_after_completion', len(ending) == (1 if i['completed'] else 0), [e['id'] for e in ending])
            if i['completed']:
                receipt_ids = {r['id'] for r in rows(chronicle, 'outcomes') if r['status'] == 'DONE'}
                honor = {r['owner']: r['value'] for r in rows(chronicle, 'facts') if r['key'] == 'player.honor'}
                check('primary_seven_honor_receipts_are_stable_and_once', all(honor.get(player) == '7' and stable(i['id'], 'node/done', 'one-honor-receipt', player) in receipt_ids for player in i['members']))
                expected_event = 'chronicle:' + stable(i['id'], 'node/done', 'actual-national-ending', 'WORLD')
                check('primary_ending_uses_stable_world_outcome_id', len(ending) == 1 and ending[0]['id'] == expected_event)
        summary = {'events': len(events), 'instances': [{'id': i['id'], 'story': i['definition']['id'], 'node': i['node'], 'completed': i['completed'], 'members': i['members']} for i in instances.values()],
                   'national_log_rows': len(logs), 'national_projection_rows': len(projections), 'national_resolution_rows': len(rows(chronicle, 'story_nation_resolutions')),
                   'choice_log_rows': len(rows(chronicle, 'story_choice_log')), 'outcome_rows': len(rows(chronicle, 'outcomes'))}
    except Exception as error:
        check('structural_analysis_completed', False, repr(error))
        summary = {'analysis_error': repr(error)}
    return checks, summary


def native_countries(core):
    properties = {r['property_key']: r['property_value'] for r in rows(core, 'starcore_nation_state')}
    result = []
    for index in range(int(properties.get('count', '0'))):
        prefix = 'nation.' + str(index) + '.'
        nation = {key: properties.get(prefix + key) for key in ['id', 'name', 'founderId', 'kind', 'government', 'foundedAt']}
        members = []
        for key, value in properties.items():
            match = re.fullmatch(re.escape(prefix) + r'member\.(\d+)\.id', key)
            if match:
                base = prefix + 'member.' + match.group(1) + '.'
                members.append({'id': value, 'name': properties.get(base + 'name'), 'rank': properties.get(base + 'rank')})
        nation['members'] = sorted(members, key=lambda m: m['id'])
        nation['current_holder_id'] = nation.pop('founderId')
        nation['holder_is_current_not_historical_founder'] = True
        result.append(nation)
    return {'native_revision': properties.get('__starcore_revision__'), 'declared_count': properties.get('count'), 'countries': result}


def evidence_index(run):
    files, fixture_events, failed = [], [], []
    for path in sorted((run / 'evidence').iterdir()):
        if path.is_file() and path.suffix in ['.log', '.json', '.jsonl'] and not (path.suffix == '.json' and path.name.startswith('national-council-boot')):
            files.append({'path': path.relative_to(run).as_posix(), 'size': path.stat().st_size, 'sha256': digest(path)})
    path = run / 'evidence/national-fixture.jsonl'
    if path.is_file():
        for number, line in enumerate(path.read_text(encoding='utf-8', errors='replace').splitlines(), 1):
            try:
                event = json.loads(line)
                fixture_events.append(event)
                if event.get('kind') == 'FAILED':
                    failed.append({'line': number, 'event': event})
            except json.JSONDecodeError:
                failed.append({'line': number, 'malformed_fixture_json': line})
    processes = json.loads((run / 'evidence/national-council-processes.json').read_text(encoding='utf-8'))
    reconnect = run / 'evidence/national-council-client-reconnect-process.json'
    return {'files': files, 'processes': processes, 'fixture_events': fixture_events, 'all_fixture_failures_retained': failed,
            'reconnect_process': json.loads(reconnect.read_text(encoding='utf-8')) if reconnect.is_file() else None,
            'interpretation': 'Raw fixture failures, preflight attempts and reconnect exits remain evidence; none is silently relabeled as a successful product check.'}


def client_events(run):
    result = []
    for number, line in enumerate((run / 'evidence/national-council-clients.log').read_text(encoding='utf-8', errors='replace').splitlines(), 1):
        match = re.match(r'([^ ]+) ([A-Z_]+) (.*)', line)
        if match:
            try:
                result.append({'line': number, 'time': match[1], 'kind': match[2], 'value': json.loads(match[3])})
            except json.JSONDecodeError:
                result.append({'line': number, 'kind': 'MALFORMED_CLIENT_EVIDENCE', 'value': line})
    return result


def report(args, run, manifest):
    labels = [args.boot1_label, args.boot2_label, args.boot3_label]
    captures = []
    for label in labels:
        if not re.fullmatch(r'[a-z0-9][a-z0-9-]{0,47}', label):
            raise SystemExit('Invalid captured evidence label')
        captures.append(json.loads((run / 'evidence' / ('national-council-' + label + '.json')).read_text(encoding='utf-8')))
    evidence = evidence_index(run)
    fixtures = evidence['fixture_events']
    clients = client_events(run)
    checks = []
    def check(name, condition, detail=None):
        checks.append({'name': name, 'status': 'PASS' if condition else 'FAIL', 'detail': detail})
    def events(kind):
        return [e for e in fixtures if e['kind'] == kind]
    def table(stage, name):
        return rows(captures[stage]['chronicle'], name)
    def instance(stage, story):
        return next(json.loads(r['json']) for r in table(stage, 'instances') if r['story'] == story)
    def projection(stage, instance_id, nation):
        return next(r for r in table(stage, 'story_nation_votes') if r['instance_id'] == instance_id and r['nation_id'] == nation)
    def rejected(player, story, revision):
        return any(e['value']['player'] == player and e['value']['story'] == story and e['value']['revision'] == revision for e in events('EXPECTED_REJECTION'))
    check('all_three_captures_use_exact_product_and_fixture', all(c['plugin_sha256'] == args.expected_sha and c['fixture_sha256'] == manifest['fixture_sha256'] for c in captures))
    check('retained_source_runtime_assets_are_unchanged', all(Path(path).is_file() and digest(Path(path)) == fingerprint for path, fingerprint in manifest['source_preserved_sha256'].items()))
    check('compiled_fixture_source_and_attempt_are_fingerprint_bound', digest(run / 'fixture/NationalCouncilPaperFixture.java') == manifest['fixture_source_sha256'] and bool(manifest.get('fixture_compile_attempts')) and all(a['exit_code'] == 0 for a in manifest['fixture_compile_attempts']))
    check('all_three_consistent_snapshots_pass_structure_integrity_and_foreign_keys', all(c['structural_status'] == 'PASS' and all(x['status'] == 'PASS' for x in c['checks']) for c in captures), [len(c['checks']) for c in captures])
    check('all_three_snapshot_reads_preserve_main_and_sidecar_bytes', all(c['chronicle']['source_bytes_unchanged'] and c['core_nation_snapshot']['source_bytes_unchanged'] for c in captures))
    boots = evidence['processes'].get('boots', [])
    check('three_serial_main_server_and_client_boots_exit_zero', len(boots) == 3 and [b['boot'] for b in boots] == [1, 2, 3] and all(b['server_exit'] == 0 and b['client_exit'] == 0 for b in boots), [{'boot': b['boot'], 'server_exit': b['server_exit'], 'client_exit': b['client_exit']} for b in boots])
    expected_players = manifest['players']
    for boot in [1, 2, 3]:
        spawned = {e['value']['player']: e['value'].get('uuid') for e in clients if e['kind'] == 'SPAWN' and e['value']['boot'] == boot}
        check('boot' + str(boot) + '_four_actual_pinned_client_identities', spawned == expected_players, spawned)
    check('real_native_identity_checks_use_non_op_non_admin_clients', bool(events('IDENTITY')) and all(not e['value']['op'] and not e['value']['admin'] and e['value']['use'] for e in events('IDENTITY')))
    primary = 'national-smoke-primary'
    secondary = 'national-smoke-invalidation'
    p1, p2, p3 = [instance(stage, primary) for stage in range(3)]
    n2, n3 = [instance(stage, secondary) for stage in [1, 2]]
    river, hill, coast = [p1['members'][expected_players[who]] for who in 'ABC']
    native = [{n['id']: n for n in capture['native_countries']['countries']} for capture in captures]
    check('three_genuine_countries_initially_persisted', len(native[0]) == 3 and set(native[0]) == {river, hill, coast})
    check('actual_native_succession_to_D_survives_first_restart_checkpoint', native[0][river]['current_holder_id'] == expected_players['D'] and native[1][river]['current_holder_id'] == expected_players['D'])
    check('ordinary_member_D_is_rejected', rejected('D', primary, 1))
    check('stale_country_revision_is_rejected', rejected('A', primary, 0))
    check('former_native_holder_A_is_rejected_after_transfer', rejected('A', primary, 1))
    check('primary_instance_id_source_and_pinned_definition_survive_both_restarts', all(p['id'] == p1['id'] and p['sourceEventId'] == p1['sourceEventId'] and p['definition'] == p1['definition'] for p in [p2, p3]))
    r1, h1 = projection(0, p1['id'], river), projection(0, p1['id'], hill)
    check('boot1_retains_unresolved_D_revision4_and_B_withdrawal_revision2', not p1['completed'] and r1['revision'] == 4 and r1['status'] == 'ACTIVE' and r1['choice_id'] == 'yes' and r1['actor_id'] == expected_players['D'] and h1['revision'] == 2 and h1['status'] == 'WITHDRAWN' and h1['choice_id'] is None)
    check('same_country_two_citizens_are_not_counted_as_two_country_seats', len(p1['members']) == 4 and p1['members'][expected_players['A']] == p1['members'][expected_players['D']] and len(table(0, 'story_nation_votes')) == 2 and not table(0, 'story_nation_resolutions'))
    river_actions = sorted([r for r in table(0, 'story_nation_vote_log') if r['nation_id'] == river], key=lambda r: r['revision'])
    check('successor_revote_and_withdraw_append_without_rewriting_original_holder', [r['action'] for r in river_actions] == ['CAST', 'CHANGE', 'WITHDRAW', 'CAST'] and river_actions[0]['actor_id'] == expected_players['A'] and all(r['actor_id'] == expected_players['D'] for r in river_actions[1:]))
    old_actions = {r['id']: r for r in table(0, 'story_nation_vote_log')}
    next_actions = {r['id']: r for r in table(1, 'story_nation_vote_log')}
    check('first_restart_preserves_every_previously_committed_action_byte_value', all(next_actions.get(key) == value for key, value in old_actions.items()))
    check('pending_campaign_has_no_personal_choices_rewards_or_premature_resolution', not table(0, 'story_choice_log') and not table(0, 'outcomes') and not table(0, 'story_nation_resolutions') and not p1['votes'] and not p1['choices'])
    check('fresh_clients_revalidate_pending_and_withdrawn_state_after_restart', len(events('PENDING_CHECK_PASS')) >= 2 and all(e['value']['D']['viewerRevision'] == 4 and e['value']['B']['viewerRevision'] == 2 and not e['value']['A']['representative'] for e in events('PENDING_CHECK_PASS')))
    final_rows = {nation: projection(1, p2['id'], nation) for nation in [river, hill, coast]}
    check('final_primary_has_yes_two_countries_and_no_one_country', p2['completed'] and p3['completed'] and final_rows[river]['choice_id'] == 'yes' and final_rows[coast]['choice_id'] == 'yes' and final_rows[hill]['choice_id'] == 'no' and [final_rows[n]['revision'] for n in [river, coast, hill]] == [4, 1, 3])
    freeze = table(1, 'story_nation_resolutions')
    check('exactly_one_immutable_country_resolution_is_persisted', len(freeze) == 1 and freeze[0]['instance_id'] == p2['id'] and freeze == table(2, 'story_nation_resolutions'))
    choice = table(1, 'story_choice_log')
    check('exactly_one_world_resolution_and_no_personal_ballots', len(choice) == 1 and choice[0]['kind'] == 'RESOLUTION' and choice[0]['player_id'] is None and choice[0]['scope_key'] == 'world' and choice[0]['choice_id'] == 'yes' and choice == table(2, 'story_choice_log'))
    ref = primary + '/1/council'
    b_facts = [e['value']['facts'] for e in events('FACTS') if e['value']['player'] == 'B']
    check('opposing_country_no_is_distinct_from_world_yes_and_absent_personal_choice', any(f.get('past.nation.choice.' + ref) == 'no' and f.get('past.resolution.world.' + ref) == 'yes' and 'past.player.choice.' + ref not in f for f in b_facts))
    d_facts = [e['value']['facts'] for e in events('FACTS') if e['value']['player'] == 'D']
    check('supporting_country_yes_is_not_a_personal_ballot', bool(d_facts) and all(f.get('past.nation.choice.' + ref) == 'yes' and f.get('past.resolution.world.' + ref) == 'yes' and 'past.player.choice.' + ref not in f for f in d_facts))
    honor = [{r['owner']: r['value'] for r in table(stage, 'facts') if r['key'] == 'player.honor'} for stage in [1, 2]]
    check('all_four_receivers_keep_exactly_seven_honor_across_restart_and_replay', all(all(values.get(player) == '7' for player in expected_players.values()) for values in honor) and honor[0] == honor[1])
    check('five_stable_done_receipts_are_unchanged', len(table(1, 'outcomes')) == 5 and table(1, 'outcomes') == table(2, 'outcomes') and all(r['status'] == 'DONE' for r in table(2, 'outcomes')))
    check('canonical_source_replay_is_observed_duplicate_twice', len(events('REPLAY_DUPLICATE')) == 2)
    canonical_sources = [{r['id']: r for r in table(stage, 'events')} for stage in range(3)]
    check('original_primary_source_time_actors_hash_and_evidence_never_rewritten', canonical_sources[0][p1['sourceEventId']] == canonical_sources[1][p1['sourceEventId']] == canonical_sources[2][p1['sourceEventId']])
    check('one_stable_ending_event_remains_after_replay', len([r for r in table(2, 'events') if r['type'] == 'NATIONAL_COUNCIL_SMOKE_FINISHED']) == 1)
    check('native_country_B_really_disappears_and_is_not_recreated_on_restart', hill in native[0] and hill not in native[1] and hill not in native[2] and len(events('NATIVE_DISBAND')) == 1)
    invalidated = projection(1, n2['id'], hill)
    check('native_absence_invalidates_B_revision2_with_null_choice', invalidated['revision'] == 2 and invalidated['status'] == 'INVALIDATED' and invalidated['choice_id'] is None and invalidated['actor_id'] == expected_players['D'])
    invalid_log = [r for r in table(1, 'story_nation_vote_log') if r['instance_id'] == n2['id'] and r['nation_id'] == hill]
    check('invalidation_preserves_original_B_action_and_attributes_actual_D_checker', len(invalid_log) == 2 and sorted(r['action'] for r in invalid_log) == ['CAST', 'INVALIDATE'] and any(r['action'] == 'CAST' and r['actor_id'] == expected_players['B'] for r in invalid_log) and any(r['action'] == 'INVALIDATE' and r['actor_nation_id'] == river for r in invalid_log))
    check('invalidation_campaign_stays_unresolved_without_fabricating_quorum', not n2['completed'] and not n3['completed'] and not any(r['instance_id'] == n2['id'] for r in table(1, 'story_nation_resolutions')) and len(events('INVALIDATION_CHECK_PASS')) >= 2)
    stable_tables = ['story_nation_vote_log', 'story_nation_votes', 'story_nation_actor_bindings', 'story_nation_resolutions', 'story_choice_log', 'outcomes']
    check('second_restart_preserves_logs_projections_bindings_freeze_choice_and_receipts', all(table(1, name) == table(2, name) for name in stable_tables), stable_tables)
    check('frozen_historical_B_no_survives_later_native_country_destruction', projection(2, p3['id'], hill) == final_rows[hill] and final_rows[hill]['status'] == 'ACTIVE')
    check('completed_node_refuses_further_national_vote', rejected('D', primary, 4))
    check('actual_native_country_move_and_new_holder_transfer_are_persisted', len(events('NATIVE_MOVE')) == 1 and len(events('NATIVE_TRANSFER')) == 2 and native[2][coast]['current_holder_id'] == expected_players['A'] and expected_players['A'] not in {m['id'] for m in native[2][river]['members']})
    moved_state = [e['value']['state'] for e in events('STATE') if e['value']['player'] == 'A' and e['value']['story'] == secondary]
    check('real_current_holder_of_new_country_still_cannot_carry_original_campaign_membership', any(s['viewerNationId'] == coast and s['representative'] and not s['canVote'] for s in moved_state) and rejected('A', secondary, 1))
    clicks = [e['value'] for e in clients if e['kind'] == 'CLICKED']
    check('real_GUI_A_and_C_yes_clicks_reach_the_country_ledger', any(v['boot'] == 1 and v['player'] == 'A' and v.get('choice') == 'yes' for v in clicks) and any(v['boot'] == 2 and v['player'] == 'C' and v.get('choice') == 'yes' for v in clicks))
    check('ordinary_country_member_GUI_has_no_vote_choices', any(e['kind'] == 'CHOICES_VERIFIED' and e['value']['boot'] == 1 and e['value']['player'] == 'D' and not e['value']['choices'] for e in clients))
    views = [e['value'] for e in clients if e['kind'] == 'CLIENT_VIEW']
    expected_revisions = {river: 4, coast: 1, hill: 3}
    final_gui = [v for v in views if v['boot'] == 3 and v['window'] and all(any(item['label'] == nation and ('修订号: ' + str(revision)) in item['lore'] for item in v['window']['slots']) for nation, revision in expected_revisions.items())]
    check('completed_GUI_exposes_real_three_country_revision_and_authority_rows', bool(final_gui) and all(any('权威来源:' in line for line in item['lore']) for item in final_gui[0]['window']['slots'] if item['label'] in expected_revisions))
    client_errors = [e for e in clients if e['kind'] in ['ERROR', 'ACTION_FAILED', 'IDENTITY_FAILED', 'KICKED', 'MALFORMED_CLIENT_EVIDENCE']]
    check('actual_clients_have_no_unclassified_protocol_or_action_failures', not client_errors, client_errors)
    failures = evidence['all_fixture_failures_retained']
    known_offline = all(f.get('event', {}).get('value', {}).get('operation') in ['check-pending', 'state'] and 'Actual client must be connected:' in f['event']['value']['error'] for f in failures)
    reconnect = evidence['reconnect_process'] or {}
    check('two_harness_disconnect_checks_are_retained_and_rechecked_after_same_client_reconnect', len(failures) == 2 and known_offline and reconnect.get('exit_code') == 0 and len(events('PENDING_CHECK_PASS')) >= 2)
    preflight = evidence['processes'].get('preflight_attempts', [])
    check('tty_preflight_is_separate_from_three_main_boots_and_stopped_normally', len(preflight) == 1 and preflight[0].get('server_exit') == 0 and preflight[0].get('client_exit') is None and 'tty' in preflight[0].get('reason', '').lower())
    initial_path = run / 'evidence/national-council-boot1.json'
    initial = json.loads(initial_path.read_text(encoding='utf-8')) if initial_path.is_file() else None
    collector_issue = bool(initial and initial['structural_status'] == 'FAIL' and [c['name'] for c in initial['checks'] if c['status'] != 'PASS'] == ['chronicle_read_did_not_change_bytes'] and
                           initial['chronicle']['source_files_before']['chronicle.db']['sha256'] == initial['chronicle']['source_files_after']['chronicle.db']['sha256'] and
                           all(initial['chronicle']['tables'][name]['typed_content_sha256'] == captures[0]['chronicle']['tables'][name]['typed_content_sha256'] for name in initial['chronicle']['tables']))
    check('initial_inspector_sidecar_only_diagnostic_is_preserved_with_unchanged_main_and_table_data', collector_issue)
    build = {'tests': 0, 'failures': 0, 'errors': 0, 'skipped': 0, 'suites': 0}
    for path in (ROOT / 'target/surefire-reports').glob('TEST-*.xml'):
        suite = ET.parse(path).getroot()
        for field in ['tests', 'failures', 'errors', 'skipped']:
            build[field] += int(suite.get(field, '0'))
        build['suites'] += 1
    check('same_candidate_current_surefire_reports_are_passing', build['tests'] > 0 and not build['failures'] and not build['errors'] and not build['skipped'], build)
    check('surefire_candidate_jar_matches_the_exercised_product', digest(ROOT / 'target/starchronicle-0.1.0-SNAPSHOT.jar') == args.expected_sha)
    server_diagnostics = []
    for boot in [1, 2, 3]:
        path = run / 'evidence' / ('national-council-boot' + str(boot) + '-complete-server.log')
        if not path.is_file():
            raise SystemExit('Complete actual server log missing: ' + str(path))
        for number, line in enumerate(path.read_text(encoding='utf-8', errors='replace').splitlines(), 1):
            if re.search(r'\bERROR\b|\bSEVERE\b|Exception|NATIONAL_FAILED', line):
                safe_line = '[REDACTED: credential-related server diagnostic]' if re.search(r'(?i)secret|password|bearer|api[-_ ]?key|token\s*[:=]', line) else line
                server_diagnostics.append({'boot': boot, 'line': number, 'text': safe_line})
    upstream = [line for line in server_diagnostics if 'AccessDeniedException' in line['text'] and ('dungeon' in line['text'].lower() or 'player-snapshots' in line['text'])]
    shutdown_receipt = run / 'evidence/preflight-normal-shutdown.json'
    incidents = {'preflight_without_tty': preflight,
                 'preflight_shutdown_receipt': json.loads(shutdown_receipt.read_text(encoding='utf-8')) if shutdown_receipt.is_file() else None,
                 'offline_fixture_checks': failures, 'same_identity_reconnect': reconnect,
                 'inspector_first_attempt': {'evidence': initial_path.relative_to(run).as_posix(), 'sha256': digest(initial_path), 'main_database_bytes_unchanged': collector_issue,
                     'explanation': 'mode=ro on the owned stopped WAL-mode copy created only empty WAL/SHM metadata; initial FAIL is retained. Corrected captures use mode=ro&immutable=1 after checking no nonempty WAL/journal; database main bytes and every table content remained identical.'},
                 'upstream_windows_directory_fsync': {'observed': bool(upstream), 'locations': upstream,
                     'scope': 'StarCore dungeon/player-snapshots directory fsync raised Windows AccessDenied during startup. This is retained upstream startup scope, not a claim that every StarCore subsystem or all logs are clean. Native nation and Chronicle acceptance proceeded through their real ready services.'},
                 'all_server_error_and_exception_lines': server_diagnostics}
    check('known_upstream_windows_directory_fsync_scope_is_explicitly_retained', bool(upstream))
    status = 'PASS' if all(c['status'] == 'PASS' for c in checks) else 'FAIL'
    public_index = [{'path': f['path'], 'size': f['size'], 'sha256': f['sha256']} for f in evidence['files']]
    for label in labels + ['boot1']:
        path = run / 'evidence' / ('national-council-' + label + '.json')
        public_index.append({'path': path.relative_to(run).as_posix(), 'size': path.stat().st_size, 'sha256': digest(path)})
    result = {'status': status, 'scope': 'Real Paper national-council feature acceptance across three normal main boots and four non-OP clients; not a clean-start certification of every upstream module or a Folia claim.',
              'label': args.label, 'generated_utc': datetime.now(timezone.utc).isoformat(), 'plugin_sha256': args.expected_sha,
              'fixture_sha256': manifest['fixture_sha256'], 'starcore_sha256': manifest['starcore_sha256'], 'paper_sha256': manifest['paper_sha256'],
              'checks': checks, 'passed': sum(c['status'] == 'PASS' for c in checks), 'total': len(checks), 'build': build,
              'server_exit_codes': [b['server_exit'] for b in boots], 'client_exit_codes': [b['client_exit'] for b in boots],
              'claim_all_logs_clean': False, 'has_recorded_harness_and_upstream_incidents': True,
              'incidents': incidents, 'evidence_index': public_index, 'players': expected_players,
              'native_country_ids': {'river': river, 'hill': hill, 'coast': coast},
              'checkpoint_provenance': [c['checkpoint_proof'] for c in captures], 'configuration_contents_archived': False,
              'raw_database_snapshots_stay_in_ignored_run': True}
    targets = [(run / 'evidence' / ('national-council-report-' + args.label + '.json'), result)]
    if args.publish:
        destination = ROOT / 'docs/verification'
        destination.mkdir(exist_ok=True)
        targets.extend([(destination / 'national-council-paper-summary.json', result),
                        (destination / 'national-council-paper-fixture-events.json', {'source_sha256': digest(run / 'evidence/national-fixture.jsonl'), 'events': fixtures}),
                        (destination / 'national-council-paper-client-evidence.json', {'source_sha256': digest(run / 'evidence/national-council-clients.log'), 'events': [e for e in clients if e['kind'] != 'MESSAGE']}),
                        (destination / 'national-council-paper-ledger-evidence.json', {'plugin_sha256': args.expected_sha, 'stages': [{'label': c['label'], 'checks': c['checks'], 'native_countries': c['native_countries'], 'checkpoint_proof': c['checkpoint_proof'], 'tables': {name: c['chronicle']['tables'][name] for name in ['events', 'instances', 'story_nation_vote_log', 'story_nation_votes', 'story_nation_actor_bindings', 'story_nation_resolutions', 'story_choice_log', 'outcomes', 'facts', 'story_definitions', 'story_definition_formats', 'story_definition_upgrades']}} for c in captures]}),
                        (destination / 'national-council-paper-evidence-index.json', {'plugin_sha256': args.expected_sha, 'files': public_index, 'incidents': incidents})])
    for target, _ in targets:
        if target.exists():
            raise SystemExit('Refusing report/evidence overwrite: ' + str(target))
    markdown = '# Real Paper national-council acceptance\n\n'
    markdown += f"Status: **{status}**, {result['passed']}/{result['total']} observed checks. Product SHA-256 `{args.expected_sha}`.\n\n"
    markdown += 'Four actual non-operator protocol clients participated in three distinct native countries. The native NationService created memberships, transferred the current holder to D, later disbanded B, then moved A and transferred the third country to A. Chronicle used its real service, country ledger and actual inventory GUI. No fixture database mutation, fabricated PlayerContext or replacement authority provider was used.\n\n'
    markdown += f"The same candidate's current Surefire evidence totals {build['tests']} tests, {build['failures']} failures, {build['errors']} errors and {build['skipped']} skipped. This collector starts no JVM. Main server exits: `{result['server_exit_codes']}`; main client exits: `{result['client_exit_codes']}`. A preflight and one same-client reconnect are recorded separately.\n\n"
    markdown += 'The first stopped checkpoint retained D’s active country revision 4 and B’s WITHDRAWN revision 2 with no premature resolution or reward. After restart, C’s real GUI vote froze two supporting countries while B’s opposing country remained `no`. The country facts and WORLD resolution stayed separate from personal BALLOT facts. A second restart retained one frozen resolution, one shared choice row and five stable DONE receipts; all four player honor values remained seven after canonical source replay.\n\n'
    markdown += 'A separate unresolved campaign observed real native destruction of B, appended its INVALIDATED revision 2 with `choice_id=NULL`, and retained the actual D checker’s attribution. Future country disappearance did not rewrite the earlier frozen opposing vote. A became the genuine native holder of another country but remained unable to vote through the old campaign’s original-nationality membership. The final real GUI exposed all three frozen country rows and revisions 4/1/3 with authority evidence.\n\n'
    markdown += '## Retained harness and upstream incidents\n\n'
    markdown += '- The first preflight omitted an interactive terminal. Its known server process was normally stopped by the bounded owned stop agent, exit 0; it is not counted as multiplayer acceptance.\n'
    markdown += '- Two fixture reads were scheduled in the same control batch as client quit and failed because D/B were disconnected. Both `NATIONAL_FAILED` records remain. The same four identities reconnected without database/reward reset, exited 0, and the pending checkpoint was successfully rechecked.\n'
    markdown += '- The initial mode=ro collector created empty WAL/SHM metadata on the owned stopped copy. Its FAIL snapshot remains. Main database bytes and every table digest were unchanged. Corrected captures use mode=ro&immutable=1, query_only and an explicit transaction after validating no nonempty WAL/journal, and preserve all source/sidecar bytes.\n'
    markdown += '- StarCore’s dungeon/player-snapshots directory fsync emitted Windows AccessDenied during startup. The original error and stack context remain in the archived logs. This report certifies the tested native nation/Chronicle routes, not every upstream subsystem, all-clean logs, general crash exactly-once national mutations or Folia.\n\n'
    markdown += 'Each snapshot reads a complete consistent transaction per database. Cross-plugin checkpoint provenance relies on the coordinator’s normally stopped copies and recorded exits; hashes identify the exact copies but cannot cryptographically prove an earlier filesystem copy after runtime advances. No credential-bearing configuration contents or database files are published. Raw typed full snapshots remain in the ignored run.\n\n'
    markdown += '## Observed checks\n\n| Check | Result |\n|---|---|\n' + ''.join('| ' + c['name'] + ' | ' + c['status'] + ' |\n' for c in checks)
    markdown += '\nMachine-readable detail is in `national-council-paper-summary.json`, `national-council-paper-ledger-evidence.json`, `national-council-paper-fixture-events.json`, `national-council-paper-client-evidence.json` and `national-council-paper-evidence-index.json`. Three sanitized main server logs plus separate preflight-server and same-client-reconnect logs retain expected denials, harness errors and the upstream startup exception; only credential-related lines are redacted.\n'
    md_path = ROOT / 'docs/verification/national-council-paper-report.md' if args.publish else run / 'evidence' / ('national-council-report-' + args.label + '.md')
    if md_path.exists():
        raise SystemExit('Refusing report overwrite: ' + str(md_path))
    log_archives = []
    if args.publish:
        sources = [('national-council-boot' + str(boot) + '-complete-server.log', 'national-council-paper-boot' + str(boot) + '-server.log') for boot in [1, 2, 3]]
        sources += [('national-council-preflight-complete-server.log', 'national-council-paper-preflight-server.log'),
                    ('national-council-client-boot1-reconnect.log', 'national-council-paper-client-reconnect.log')]
        for source_name, target_name in sources:
            source = run / 'evidence' / source_name
            target = ROOT / 'docs/verification' / target_name
            if target.exists():
                raise SystemExit('Refusing server log overwrite: ' + str(target))
            sanitized, redactions = [], 0
            for line in source.read_text(encoding='utf-8', errors='replace').splitlines():
                if re.search(r'(?i)secret|password|bearer|api[-_ ]?key|token\s*[:=]', line):
                    sanitized.append('[REDACTED: credential-related server log line]')
                    redactions += 1
                else:
                    sanitized.append(line)
            log_archives.append((target, '\n'.join(sanitized) + '\n', redactions))
        result['sanitized_archives'] = [{'name': p.name, 'sha256': hashlib.sha256(text.encode('utf-8')).hexdigest(), 'credential_line_redactions': redactions, 'line_endings_normalized_to_lf': True} for p, text, redactions in log_archives]
    for target, value in targets:
        with target.open('x', encoding='utf-8') as stream:
            stream.write(json.dumps(value, ensure_ascii=False, indent=2) + '\n')
    with md_path.open('x', encoding='utf-8') as stream:
        stream.write(markdown)
    for target, text, _ in log_archives:
        with target.open('x', encoding='utf-8') as stream:
            stream.write(text)
    print(json.dumps({'status': status, 'passed': result['passed'], 'total': result['total'], 'failed_checks': [c for c in checks if c['status'] != 'PASS'], 'report': str(md_path), 'log_redactions': {p.name: count for p, _, count in log_archives}, 'jvm_started': False}, ensure_ascii=False, indent=2))
    if status != 'PASS':
        raise SystemExit(1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', nargs='?', choices=['inspect', 'report'], default='inspect')
    parser.add_argument('--run-dir', type=Path, required=True)
    parser.add_argument('--expected-sha', required=True)
    parser.add_argument('--label', required=True)
    parser.add_argument('--checkpoint')
    parser.add_argument('--boot1-label', default='boot1-immutable')
    parser.add_argument('--boot2-label', default='boot2')
    parser.add_argument('--boot3-label', default='boot3')
    parser.add_argument('--publish', action='store_true', help='Report only: create new docs/verification/national-council-paper-* evidence')
    args = parser.parse_args()
    if not re.fullmatch(r'[0-9a-f]{64}', args.expected_sha) or not re.fullmatch(r'[a-z0-9][a-z0-9-]{0,47}', args.label):
        raise SystemExit('Require complete expected SHA-256 and a bounded safe evidence label')
    run = args.run_dir.resolve()
    if run.parent != (ROOT / 'run').resolve() or run.name != 'paper-national-' + args.expected_sha[:8]:
        raise SystemExit('Require this worktree SHA-bound isolated run directory')
    manifest = json.loads((run / 'preparation.json').read_text(encoding='utf-8'))
    if manifest['plugin_sha256'] != args.expected_sha or digest(run / 'plugins/StarChronicle.jar') != args.expected_sha:
        raise SystemExit('Product SHA drift')
    if not manifest.get('fixture_sha256') or digest(run / 'plugins/NationalCouncilPaperFixture.jar') != manifest['fixture_sha256']:
        raise SystemExit('Fixture SHA drift')
    if args.action == 'report':
        report(args, run, manifest)
        return
    target = run / 'evidence' / ('national-council-' + args.label + '.json')
    if target.exists():
        raise SystemExit('Refusing to overwrite an observed evidence label')
    evidence = evidence_index(run)
    checkpoint_proof = None
    if args.checkpoint:
        if not re.fullmatch(r'boot[123]', args.checkpoint):
            raise SystemExit('Checkpoint must identify an owned boot1|boot2|boot3 stopped copy')
        source = run / 'checkpoints' / args.checkpoint
        matching = [b for b in evidence['processes']['boots'] if b['boot'] == int(args.checkpoint[-1])]
        if len(matching) != 1 or matching[0].get('server_exit') != 0:
            raise SystemExit('Checkpoint requires the corresponding recorded normal server exit')
        chronicle_path, core_path = source / 'chronicle.db', source / 'starcore.db'
        for path in [chronicle_path, core_path]:
            if any(Path(str(path) + suffix).exists() and Path(str(path) + suffix).stat().st_size for suffix in ['-wal', '-journal']):
                raise SystemExit('Stopped checkpoint unexpectedly has a nonempty WAL/journal; do not inspect an incomplete file copy')
        checkpoint_proof = {'checkpoint': args.checkpoint, 'normal_server_exit_record': matching[0],
                            'fixture_config_sha256': digest(source / 'fixture-config.yml') if (source / 'fixture-config.yml').is_file() else None,
                            'origin': 'Coordinator-provided consistent copies captured after normal shutdown; original runtime may already be in a later boot.',
                            'authenticity_limit': 'Hashes identify exactly these stopped copies. Process exit and semantic state corroborate provenance; this tool cannot cryptographically prove an earlier filesystem copy operation after the live runtime has advanced.'}
    else:
        if any(b.get('server_exit') != 0 for b in evidence['processes']['boots']):
            raise SystemExit('Runtime is not recorded stopped; use an explicit stopped --checkpoint')
        chronicle_path, core_path = run / 'plugins/StarChronicle/chronicle.db', run / 'plugins/StarCore/starcore.db'
    chronicle = snapshot(chronicle_path)
    core = snapshot(core_path, {'starcore_nation_state'})
    checks, summary = structural(chronicle, core)
    native = native_countries(core)
    checks.append({'name': 'native_current_holders_are_real_members', 'status': 'PASS' if all(canonical_uuid(n['id']) and canonical_uuid(n['current_holder_id']) and n['current_holder_id'] in {m['id'] for m in n['members']} for n in native['countries']) else 'FAIL', 'detail': native})
    data = {'label': args.label, 'captured_utc': datetime.now(timezone.utc).isoformat(), 'run_name': run.name,
            'plugin_sha256': args.expected_sha, 'fixture_sha256': manifest['fixture_sha256'], 'starcore_sha256': digest(run / 'plugins/StarCore.jar'),
            'preparation_sha256': digest(run / 'preparation.json'), 'checkpoint_proof': checkpoint_proof,
            'chronicle': chronicle, 'core_nation_snapshot': core, 'native_countries': native, 'summary': summary, 'checks': checks,
            'structural_status': 'PASS' if all(c['status'] == 'PASS' for c in checks) else 'FAIL',
            'acceptance_status': 'NOT_ASSESSED_BY_INSPECT', 'harness_evidence': evidence,
            'configuration_contents_archived': False,
            'configuration_fingerprints': {str(p.relative_to(run)): digest(p) for p in [run / 'plugins/StarChronicle/config.yml', run / 'plugins/NationalCouncilPaperFixture/config.yml'] if p.is_file()}}
    with target.open('x', encoding='utf-8') as output:
        output.write(json.dumps(data, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'output': str(target), 'structural_status': data['structural_status'], 'checks': len(checks), 'failed_checks': [c for c in checks if c['status'] != 'PASS'], 'summary': summary, 'native_countries': native, 'fixture_failures_retained': len(evidence['all_fixture_failures_retained']), 'jvm_started': False}, ensure_ascii=False, indent=2))
    if data['structural_status'] != 'PASS':
        raise SystemExit(1)


if __name__ == '__main__':
    main()
