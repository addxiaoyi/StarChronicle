"""Cross-check actual Paper quest evidence without opening a live database or writing state."""
import argparse
import hashlib
import json
import re
import sqlite3
import sys
import uuid
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[1]
ISSUER = 'starcore.quest.evidence.v1'
FIXTURE = 'QuestEvidencePaperFixture'
TABLES = ('quest_gates', 'quest_gate_completions', 'instances', 'events', 'event_players', 'event_nations', 'event_causes', 'facts', 'outcomes', 'story_choice_log', 'story_nation_vote_log', 'story_nation_resolutions')
CASES = {'player': ('A', 'heir', 'paper', 3, {'sugar_cane': 3}), 'nation': ('B', 'siege', 'book', 1, {'paper': 3, 'leather': 1}), 'atlas': ('C', 'atlas', 'map', 1, {'paper': 8, 'compass': 1})}
COHORTS = {1: {'A', 'B', 'C'}, 2: {'A', 'B', 'C'}, 3: {'A', 'C'}}
RUN_FIELDS = ('bindingId', 'runId', 'playerId', 'questId', 'definitionFingerprint', 'startedAt', 'acceptedNationState', 'acceptedNation', 'acceptedNationCheckedAt', 'issuer', 'acceptedEvidence')
RECEIPT_FIELDS = RUN_FIELDS + ('completionId', 'completedAt', 'completedNationState', 'completedNation', 'completedNationCheckedAt', 'completionEvidence', 'committedAt')
MARKER_FIELDS = ('runId', 'questId', 'bindingId', 'definitionFingerprint', 'startedAt', 'completedAt', 'completionId')
CLIENT_FAILURES = {'ACTION_FAILED', 'ERROR', 'KICKED', 'IDENTITY_FAILED', 'UNEXPECTED_END', 'INPUT_CLOSED', 'INTERRUPTED', 'UNCAUGHT', 'TIMEOUT'}
PRODUCT_DIAGNOSTIC = re.compile(r'QUEST_EVIDENCE_FAILED|Exception.*(?:QuestEvidence|QuestGate|QuestMenu)|(?:QuestEvidence|QuestGate|QuestMenu).*Exception|Could not pass event .* to (?:StarCore|StarChronicle|QuestEvidencePaperFixture)|Quest gate retained:|Quest evidence scan retained:|StarChronicle startup failed|Native quest.*(?:failed|unavailable)', re.I)


def require(condition, detail):
    if not condition:
        raise ValueError(detail)


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for data in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(data)
    return h.hexdigest()


def stable(*fields):
    h = hashlib.sha256()
    for field in fields:
        raw = field.encode('utf-8')
        h.update(str(len(raw)).encode('ascii') + b':' + raw)
    return h.hexdigest()


def canonical(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(',', ':'))


def instant(value):
    result = datetime.fromisoformat(value.replace('Z', '+00:00'))
    require(result.tzinfo is not None and result.utcoffset() is not None, 'Evidence time must include its UTC offset')
    return result


def millis(value):
    t = instant(value)
    delta = t - datetime(1970, 1, 1, tzinfo=timezone.utc)
    return delta.days * 86400000 + delta.seconds * 1000 + delta.microseconds // 1000


def read_json(path):
    return json.loads(path.read_text(encoding='utf-8'))


def select(rows, **fields):
    return [row for row in rows if all(row.get(key) == value for key, value in fields.items())]


def one(rows, **fields):
    items = select(rows, **fields)
    require(len(items) == 1, f'Expected one record {fields}, saw {len(items)}')
    return items[0]


def properties(path):
    """Parse Java Properties.store(OutputStream), including escaped JSON and Unicode."""
    escapes = {'t': '\t', 'n': '\n', 'r': '\r', 'f': '\f'}
    def unescape(value):
        def convert(match):
            token = match.group(1)
            return chr(int(token[1:], 16)) if token.startswith('u') and len(token) == 5 else escapes.get(token, token)
        decoded = re.sub(r'\\(u[0-9a-fA-F]{4}|.)', convert, value)
        return decoded.encode('utf-16-le', errors='surrogatepass').decode('utf-16-le')
    result, pending = {}, ''
    for line in path.read_text(encoding='latin-1').splitlines():
        line = pending + line.lstrip(' \t\f')
        trailing = len(line) - len(line.rstrip('\\'))
        if trailing % 2:
            pending = line[:-1]
            continue
        pending = ''
        if not line or line.startswith(('#', '!')):
            continue
        split, escaped = len(line), False
        for i, ch in enumerate(line):
            if not escaped and ch in '=: \t\f':
                split = i
                break
            escaped = ch == '\\' and not escaped
        separator = line[split:].lstrip(' \t\f')
        if separator.startswith(('=', ':')):
            separator = separator[1:].lstrip(' \t\f')
        key = unescape(line[:split])
        require(key not in result, f'Duplicate property {key} in {path.name}')
        result[key] = unescape(separator)
    require(not pending, f'Incomplete property continuation in {path}')
    return result


def file_state(base):
    return {suffix: (Path(str(base) + suffix).stat().st_size, digest(Path(str(base) + suffix))) for suffix in ('', '-wal', '-shm', '-journal') if Path(str(base) + suffix).is_file()}


def sqlite_snapshot(path):
    before = file_state(path)
    require('' in before and all(before.get(suffix, (0,))[0] == 0 for suffix in ('-wal', '-journal')), 'Need stopped complete DB with no uncheckpointed WAL/journal')
    conn = sqlite3.connect(path.resolve().as_uri() + '?mode=ro&immutable=1', uri=True, isolation_level=None)
    conn.row_factory = sqlite3.Row
    try:
        conn.execute('PRAGMA query_only=ON')
        conn.execute('BEGIN')
        version = conn.execute('PRAGMA user_version').fetchone()[0]
        require(version == 6 and [r[0] for r in conn.execute('PRAGMA integrity_check')] == ['ok'] and not list(conn.execute('PRAGMA foreign_key_check')), 'Expected schema 6, integrity ok and matching foreign keys')
        rows = {table: [dict(r) for r in conn.execute('SELECT * FROM ' + table)] for table in TABLES}
        rows['schema_version'] = version
        unique = {tuple(r[2] for r in conn.execute('PRAGMA index_info("' + name.replace('"', '""') + '")')) for _, name, is_unique, *_ in conn.execute('PRAGMA index_list(quest_gate_completions)') if is_unique}
        require({('issuer', 'completion_id'), ('issuer', 'run_id'), ('gate_id',)} <= unique, 'Missing SQLite uniqueness for distinct completion/run keys or gate')
        conn.execute('ROLLBACK')
    finally:
        conn.close()
    require(before == file_state(path), 'Immutable DB inspection changed or raced a checkpoint')
    return rows


def under(base, relative):
    rel = PurePosixPath(str(relative).replace('\\', '/'))
    require(not rel.is_absolute() and '..' not in rel.parts and ':' not in str(rel), 'Checkpoint path leaves owned tree')
    file = base.joinpath(*rel.parts)
    require(file.is_file() and not file.is_symlink() and file.resolve().is_relative_to(base.resolve()), f'Missing/unsafe checkpoint file {relative}')
    return file


def preparation_checks(run, manifest, expected):
    """Check the deployed bytes and isolated endpoint, independent of session assertions."""
    require(Path(manifest['run_directory']).resolve() == run.resolve() and manifest.get('source_database_copied') is False and manifest.get('source_world_copied') is False, 'Prepared identity or fresh-world/database contract differs')
    require(manifest.get('fixture_compiled') is True and manifest.get('plugin_sha256') == expected, 'Require the exact compiled preparation')
    artifacts = {'plugin_sha256': 'plugins/StarChronicle.jar', 'starcore_sha256': 'plugins/StarCore.jar', 'paper_sha256': 'paper.jar', 'fixture_sha256': 'plugins/' + FIXTURE + '.jar'}
    for key, name in artifacts.items():
        require(re.fullmatch('[0-9a-f]{64}', manifest.get(key, '') or '') and digest(under(run, name)) == manifest[key], 'Deployed artifact SHA drift: ' + key)
    require({p.name for p in (run / 'plugins').glob('*.jar')} == {'StarChronicle.jar', 'StarCore.jar', FIXTURE + '.jar'}, 'Unexpected plugin changes isolated quest acceptance')
    source = under(run, 'fixture/' + FIXTURE + '.java')
    require(digest(source) == manifest.get('fixture_source_sha256'), 'Compiled fixture source drift')
    compiled = manifest.get('fixture_compile_attempts', [])
    require(compiled and compiled[-1].get('exit_code') == 0 and compiled[-1].get('source_sha256') == manifest['fixture_source_sha256'] and instant(manifest['prepared_utc']) <= instant(compiled[-1]['at_utc']) <= instant(manifest['compiled_utc']), 'Missing successful source-bound fixture compile')
    require(set(manifest.get('players', {})) == {'A', 'B', 'C'} and all(value == str(uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:PhaseTwo' + who).encode()).digest(), version=3)) for who, value in manifest['players'].items()), 'Prepared real client identities differ')
    stories = manifest.get('story_sha256', {})
    directory = run / 'plugins/StarChronicle/stories'
    require(stories and {p.name for p in directory.iterdir() if p.is_file() and p.suffix in {'.yml', '.yaml', '.json'}} == set(stories), 'Prepared story set changed')
    for name, sha in stories.items():
        require(Path(name).name == name and digest(under(directory, name)) == sha, 'Prepared story bytes changed: ' + name)
    config = under(run, 'plugins/StarChronicle/config.yml').read_text(encoding='utf-8')
    require(config == 'require-starcore: true\ninstall-default-stories: false\ninstall-native-example-quests: true\nadmin-demo-enabled: false\nnpc-hooks: false\nworker-queue-capacity: 2048\npoll-ticks: 40\n', 'Actual Chronicle acceptance config differs')
    props = properties(under(run, 'server.properties'))
    require(manifest.get('server_ip') == '127.0.0.1' and isinstance(manifest.get('server_port'), int) and 1024 <= manifest['server_port'] <= 65535 and props.get('server-ip') == '127.0.0.1' and props.get('server-port') == str(manifest['server_port']) and props.get('online-mode') == 'false' and props.get('enable-rcon') == props.get('enable-query') == 'false', 'Actual isolated endpoint differs from preparation')
    require(read_json(under(run, 'ops.json')) == [], 'Acceptance clients must remain non-OP')
    return artifacts


def checkpoint(run, number, process, manifest):
    base = run / 'evidence/checkpoints' / f'boot{number}'
    descriptor = base / 'checkpoint.json'
    value = read_json(descriptor)
    require(value.get('boot') == number and value.get('captured_utc'), 'Incorrect checkpoint boot evidence')
    provenance = process.get('checkpoint', {})
    require(provenance.get('path') == f'checkpoints/boot{number}/checkpoint.json' and provenance.get('sha256') == digest(descriptor), 'Checkpoint descriptor differs from session provenance')
    for key in ('plugin_sha256', 'starcore_sha256', 'fixture_sha256', 'server_pid', 'client_pid', 'server_exit', 'client_exit', 'normal_server_stop_requested', 'clients_quit_all_requested', 'all_owned_children_exited'):
        require(key in value and value[key] == process.get(key), f'Checkpoint provenance differs from observed process record: {key}')
    require(all(value.get(key) == manifest[key] for key in ('plugin_sha256', 'starcore_sha256', 'fixture_sha256')), 'Checkpoint artifact identity differs from preparation')
    require(value.get('server_exit') == 0 and value.get('client_exit') == 0 and all(value.get(key) is True for key in ('normal_server_stop_requested', 'clients_quit_all_requested', 'all_owned_children_exited')), 'Checkpoint lacks observed normal shutdown')
    require(instant(value['captured_utc']) >= max(instant(process['server_ended_utc']), instant(process['client_ended_utc'])), 'Checkpoint predates actual child exit')
    entries = value['copied_files']
    require(provenance.get('file_count') == len(entries), 'Checkpoint file count differs from observed process record')
    stamped = {}
    for item in entries:
        file = under(base, item['relative'])
        key = file.relative_to(base).as_posix()
        require(key not in stamped and digest(file) == item['sha256'] and file.stat().st_size == item['size'], 'Checkpoint copy hash or size mismatch: ' + key)
        stamped[key] = item['sha256']
    actual = {p.relative_to(base).as_posix() for p in base.rglob('*') if p.is_file() and p != descriptor}
    require(actual == set(stamped), 'Unexpected or unbound checkpoint files')
    native = base / 'native-quest'
    authority = properties(native / 'evidence-authority.properties')
    require(authority == {'protocol': '1', 'authority': str(uuid.UUID(authority['authority']))}, 'Invalid native domain authority file')
    native_quests = properties(native / 'quests.properties')
    tracked = {binding: json.loads(raw) for binding, raw in properties(native / 'tracked-evidence.properties').items()}
    journals = {}
    directory = native / 'completion-journal'
    if directory.exists():
        for path in directory.glob('*.json'):
            item = read_json(path)
            key = item['completionId']
            require(key not in journals and path.name == key + '.json' and item['status'] == 'COMPLETED', 'Incorrect immutable native journal identity')
            journals[key] = item
    result = {'boot': number, 'descriptor_sha256': digest(descriptor), 'files': stamped, 'authority': authority['authority'], 'quests': native_quests, 'tracked': tracked, 'journal': journals, 'db': sqlite_snapshot(base / 'chronicle.db')}
    if number == 3:
        actual_paths = {'chronicle.db': run / 'plugins/StarChronicle/chronicle.db'}
        actual_paths.update({key: run / 'plugins/StarCore/quest' / key.removeprefix('native-quest/') for key in stamped if key.startswith('native-quest/')})
        require(all(path.is_file() and digest(path) == stamped[key] for key, path in actual_paths.items()), 'Final stopped runtime differs from the archived checkpoint')
    return result


def fixture_log(path):
    rows = []
    for n, line in enumerate(path.read_text(encoding='utf-8').splitlines(), 1):
        row = json.loads(line)
        require(row.get('boot') in COHORTS and isinstance(row.get('kind'), str) and 'value' in row, f'Unexpected fixture record {n}')
        instant(row['time'])
        rows.append(row | {'line': n})
    return rows


def client_log(path):
    rows = []
    for n, line in enumerate(path.read_text(encoding='utf-8').splitlines(), 1):
        date, kind, encoded = line.split(' ', 2)
        instant(date)
        value = json.loads(encoded)
        require(value.get('boot') in COHORTS, f'Unexpected client boot at line {n}')
        rows.append({'time': date, 'boot': value['boot'], 'kind': kind, 'value': value, 'line': n})
    return rows


def matched(rows, boot, kind, **fields):
    return [r for r in select(rows, boot=boot, kind=kind) if isinstance(r['value'], dict) and all(r['value'].get(k) == v for k, v in fields.items())]


def observed(rows, boot, kind, **fields):
    found = matched(rows, boot, kind, **fields)
    require(len(found) == 1, f'Boot {boot} needs one {kind} {fields}; saw {len(found)}')
    return found[0]


def snapshot_fields(value, names):
    return {key: value.get(key) for key in names}


def quest_record(props, player, which, quest):
    # PersistenceManager's pq namespace is outside this file. The actual per-module
    # quests.properties stores UUID-prefixed keys, as written by the native storage.
    key = f'{player}.{which}'
    field = 'id' if which == 'active' else 'questId'
    return one(json.loads(props.get(key, '[]')), **{field: quest})


def verify_world_event(row):
    event = json.loads(row['json'])
    require(row['id'] == event['id'] and row['type'] == event['type'] and millis(row['time']) == millis(event['occurredAt']), 'Event table identity differs from canonical JSON')
    exact = {'id': event['id'], 'type': event['type'], 'time': 'OBSERVED' if event['facts'].get('time_basis') == 'OBSERVED' else event['occurredAt'], 'players': sorted(event['participants']), 'nations': sorted(event['nations']), 'facts': event['facts'], 'source': event['source'], 'evidence': event['evidence']}
    require(stable(canonical(exact)) == row['hash'], 'Canonical history event payload hash differs')
    return event


def check_case(key, manifest, fixture, clients, checkpoints, countries):
    who, qkey, item, progress, ingredients = CASES[key]
    story, quest = manifest['stories'][key], manifest['native_quests'][qkey]
    player = manifest['players'][who]
    crafted = observed(clients, 1, 'CRAFTED', player=who, item=item)['value']
    request = observed(clients, 1, 'CRAFT_REQUESTED', player=who, item=item)['value']
    before, after = crafted['before'], crafted['after']
    station = observed(fixture, 1, 'CRAFTING_STATION')['value']
    expected_delta = {name: -amount for name, amount in ingredients.items()} | {item: progress}
    actual_delta = {name: after.get(name, 0) - before.get(name, 0) for name in set(before) | set(after) if after.get(name, 0) != before.get(name, 0)}
    require(request['table'] == {k: station[k] for k in ('x', 'y', 'z')} and request['before'] == before and crafted['amount'] == progress and request['amount'] == progress and request.get('attempt') == crafted.get('attempt') == 1 and actual_delta == crafted['delta'] == expected_delta, 'Real craft table, ingredients or exact inventory delta differs')
    require(instant(observed(clients, 1, 'CRAFT_REQUESTED', player=who, item=item)['time']) <= instant(observed(clients, 1, 'CRAFTED', player=who, item=item)['time']), 'Craft request was recorded after the actual result')
    return check_native_case(key, manifest, fixture, clients, checkpoints, countries)


def check_native_case(key, manifest, fixture, clients, checkpoints, countries):
    """Cross-check immutable native and Chronicle records independently of craft envelope parsing."""
    who, qkey, item, progress, _ = CASES[key]
    story, quest = manifest['stories'][key], manifest['native_quests'][qkey]
    player = manifest['players'][who]
    craft_ack = observed(fixture, 1, 'NATIVE_ACTIVE_CHECK_PASS', player=who, story=story['id'], checkpoint='capture')
    rest_ack = observed(fixture, 2, 'NATIVE_ACTIVE_CHECK_PASS', player=who, story=story['id'], checkpoint='restore')
    native_one, native_two = craft_ack['value']['snapshot'], rest_ack['value']['snapshot']
    immutable = snapshot_fields(native_one, RUN_FIELDS)
    binding = native_one['bindingId']; run_id = native_one['runId']
    require(instant(observed(clients, 1, 'CRAFTED', player=who, item=item)['time']) <= instant(craft_ack['time']), 'Progress capture predates actual craft')
    require(snapshot_fields(native_two, RUN_FIELDS) == immutable and craft_ack['value']['baseline_sha256'] == rest_ack['value']['baseline_sha256'] and all(v['value']['progress'] == progress and v['value']['snapshot']['status'] == 'ACTIVE' and v['value']['snapshot'].get('completionId') is None for v in (craft_ack, rest_ack)), 'Native restoration changed task identity/progress or supplied an early receipt')
    require(str(uuid.UUID(run_id)) == run_id and re.fullmatch('[0-9a-f]{64}', native_one['definitionFingerprint']) is not None and native_one['playerId'] == player and native_one['questId'] == quest['id'] and native_one['acceptedNation'] == countries[who] and native_one['acceptedNationState'] == 'KNOWN_NATION' and native_one['issuer'] == ISSUER, 'Native tracked run identity/country is invalid')
    first, paused, final = checkpoints
    active_disk = quest_record(first['quests'], player, 'active', quest['id'])
    require(active_disk['id'] == quest['id'] and active_disk['runId'] == run_id and active_disk['bindingId'] == binding and active_disk['fingerprint'] == native_one['definitionFingerprint'] and active_disk['startedAt'] == native_one['startedAt'] and len(active_disk['objectives']) == 1 and active_disk['objectives'][0]['type'] == 'CRAFT_ITEM' and active_disk['objectives'][0]['progress'] == progress, 'Boot-one independently persisted active identity/craft progress differs')
    require(first['tracked'][binding]['snapshot']['status'] == 'ACTIVE' and snapshot_fields(first['tracked'][binding]['snapshot'], RUN_FIELDS) == immutable and first['tracked'][binding]['requireSameNation'] == (key == 'nation') and first['tracked'][binding]['expectedNation'] == countries[who], 'Independently persisted native ACTIVE index differs')
    expected_state_who = 'A' if key == 'nation' else who
    state = observed(fixture, 1, 'STATE', player=expected_state_who, story=story['id'])['value']
    require(state['gates']['ready'] is False and state['visibleChoices'] == [] and state['completed'] is False and state['gates']['blockedEffectIds'] == ['native-craft-proof'], 'Objective-ready native task unlocked choice before a durable receipt')
    native_detail = observed(clients, 2, 'NATIVE_DETAIL_VERIFIED', player=who, questId=quest['id'])['value']
    native_click = observed(clients, 2, 'CLICKED', player=who, questId=quest['id'], path='native-complete')['value']
    observed(clients, 2, 'NATIVE_COMPLETION_CLICK_ONLY', player=who, questId=quest['id'])
    slot = one(native_detail['window']['slots'], slot=32)
    require(native_detail['questName'] == quest['name'] and native_detail['window']['title'] == '任务详情 - ' + quest['name'] and native_click['fromTitle'] == native_detail['window']['title'] and native_click['windowId'] == native_detail['windowId'] and native_click['slot'] == 32 and slot['name'] == 'nether_star' and slot['label'] == '完成任务' and {'所有目标已完成！', '点击领取奖励'} <= set(slot['lore']) and ('ID: ' + quest['id']) in one(native_detail['window']['slots'], slot=11)['lore'], 'Actual native GUI detail or completion click does not identify this task')
    receipts2 = matched(fixture, 2, 'NATIVE_RECEIPT_CHECK_PASS', player=who, story=story['id'])
    require(receipts2 and (who != 'B' or any(r['value'].get('online') is False for r in receipts2)), 'Missing canonical native receipt while paused, or offline donor check')
    receipt2 = receipts2[0]['value']; receipt3 = observed(fixture, 3, 'NATIVE_RECEIPT_CHECK_PASS', player=who, story=story['id'])['value']; snap = receipt2['snapshot']
    completion = snap['completionId']; require(str(uuid.UUID(completion)) == completion, 'Invalid native completion ID')
    require(snapshot_fields(snap, RUN_FIELDS) == immutable and snap['status'] == 'COMPLETED' and snap['completedNationState'] == 'KNOWN_NATION' and snap['completedNation'] == countries[who] and receipt2['postAckCanonical'] is True and receipt3['postAckCanonical'] is True, 'Canonical completion changed acceptance or country proof')
    require(native_one['acceptedNationCheckedAt'] <= snap['startedAt'] <= snap['completedNationCheckedAt'] <= snap['completedAt'] <= snap['committedAt'] and bool(snap['completionEvidence']), 'Invalid native task/country completion chronology')
    require((who != 'B' or receipt3['online'] is False) and receipt2['canonical_sha256'] == receipt3['canonical_sha256'] == hashlib.sha256(canonical(snapshot_fields(snap, RECEIPT_FIELDS)).encode('utf-8')).hexdigest(), 'Native receipt changed after second restart')
    for stopped in (paused, final):
        journal = stopped['journal'][completion]
        marker = quest_record(stopped['quests'], player, 'runCompletions', quest['id'])
        tracked = stopped['tracked'][binding]['snapshot']
        require(snapshot_fields(journal, RECEIPT_FIELDS) == snapshot_fields(tracked, RECEIPT_FIELDS) == snapshot_fields(snap, RECEIPT_FIELDS) and marker == snapshot_fields(snap, MARKER_FIELDS) == receipt2['nativeMarker'] == receipt3['nativeMarker'], 'Immutable journal, tracked index, native RunCompletion, and runtime canonical receipt do not agree')
        require(f"{quest['id']}:1" in stopped['quests'].get(f'{player}.counts', '').split(';'), 'Native transaction count is not exactly one')
        require(not select(json.loads(stopped['quests'].get(f'{player}.active', '[]')), id=quest['id']), 'Completed native task remained active or was readmitted')
    require(paused['files'][f'native-quest/completion-journal/{completion}.json'] == final['files'][f'native-quest/completion-journal/{completion}.json'], 'Native immutable receipt file was rewritten across boot3')
    gates = [one(cp['db']['quest_gates'], id=binding) for cp in checkpoints]
    initial_gate, paused_gate, gate = gates
    require(all(g['id'] == stable('quest-gate', g['instance_id'], 'await', 'native-craft-proof', player) and g['player_id'] == player and g['node_id'] == 'await' and g['effect_id'] == 'native-craft-proof' and g['mode'] == story['mode'] and g['quest_id'] == quest['id'] and g['expected_nation_id'] == countries[who] and g['provider_id'] == ISSUER + ':' + first['authority'] and g['provider_pinned_at'] == initial_gate['provider_pinned_at'] and g['id'] == initial_gate['id'] for g in gates), 'Chronicle gate did not preserve original instance/binding/owner/country/provider/storage-domain pin')
    require(gate['opened_at'] <= gate['provider_pinned_at'] <= native_one['acceptedNationCheckedAt'] and gate['status'] == 'COMMITTED' and initial_gate['status'] != 'COMMITTED' and paused_gate['status'] != 'COMMITTED' and initial_gate['completion_json'] is None and paused_gate['completion_json'] is None, 'Chronicle consumed a proof before restart or violated pin/acceptance chronology')
    accepted_nation = {'state': snap['acceptedNationState'], 'nationId': snap['acceptedNation'], 'issuer': ISSUER, 'evidence': snap['acceptedEvidence']}
    completed_nation = {'state': snap['completedNationState'], 'nationId': snap['completedNation'], 'issuer': ISSUER, 'evidence': snap['completionEvidence']}
    for g in gates:
        accepted = json.loads(g['accepted_json'])
        require(snapshot_fields(accepted, ('bindingId', 'runId', 'playerId', 'questId', 'definitionFingerprint', 'issuer')) == snapshot_fields(snap, ('bindingId', 'runId', 'playerId', 'questId', 'definitionFingerprint', 'issuer')) and millis(accepted['startedAt']) == snap['startedAt'] and accepted['evidence'] == snap['acceptedEvidence'] and snapshot_fields(accepted['acceptedNation'], accepted_nation) == accepted_nation and millis(accepted['acceptedNation']['checkedAt']) == snap['acceptedNationCheckedAt'], 'Chronicle immutable accepted JSON differs from native acceptance')
    db = final['db']; consumed = one(db['quest_gate_completions'], gate_id=binding)
    proof = json.loads(consumed['proof_json'])
    require(consumed['issuer'] == ISSUER and consumed['completion_id'] == completion and consumed['run_id'] == run_id and consumed['provider_id'] == gate['provider_id'] and consumed['proof_json'] == gate['completion_json'] and consumed['proof_hash'] == stable('quest-completion-proof', consumed['proof_json']) and snap['committedAt'] <= consumed['received_at'] <= gate['last_checked_at'], 'SQLite consumed row, proof digest or receive chronology differs')
    require(snapshot_fields(proof, ('bindingId', 'runId', 'playerId', 'questId', 'definitionFingerprint', 'completionId', 'issuer', 'acceptedEvidence')) == snapshot_fields(snap, ('bindingId', 'runId', 'playerId', 'questId', 'definitionFingerprint', 'completionId', 'issuer', 'acceptedEvidence')) and all(millis(proof[k]) == snap[k] for k in ('startedAt', 'completedAt', 'committedAt')) and proof['acceptedNation'] == accepted['acceptedNation'] and snapshot_fields(proof['completedNation'], completed_nation) == completed_nation and millis(proof['completedNation']['checkedAt']) == snap['completedNationCheckedAt'] and proof['evidence'] == snap['completionEvidence'], 'SQLite canonical DTO does not match native journal and acceptance')
    event_id = 'chronicle:quest:' + stable(ISSUER, completion)
    source_id = 'quest-evidence-paper:' + key + ':1'
    origin_row = one(db['events'], id=source_id); origin = verify_world_event(origin_row)
    origin_participants = {manifest['players'][w] for w in ('ABC' if key == 'nation' else who)}
    require(gate['source_event_id'] == source_id and origin['type'] == 'QE_' + key.upper() + '_FIXTURE' and origin['facts'] == {'fixture-only': 'true', 'fixture-story': story['id']} and origin['source'] == FIXTURE and set(origin['participants']) == origin_participants and set(origin['nations']) == ({countries['A'], countries['C']} if key == 'nation' else set()), 'Chronicle source lost actual fixture audience or marker')
    event = verify_world_event(one(db['events'], id=event_id, type='quest.completed'))
    require(event['source'] == ISSUER and set(event['participants']) == {player} and set(event['nations']) == {countries[who]} and millis(event['occurredAt']) == snap['completedAt'] and event['facts']['quest.gate'] == binding and event['facts']['quest.run'] == run_id and event['facts']['quest.completion'] == completion and event['facts']['quest.definition'] == snap['definitionFingerprint'] and event['facts']['time_basis'] == 'COMMITTED_SOURCE' and event['facts']['story'] == story['id'] and 'receipt_sha256=' + consumed['proof_hash'] in event['evidence'], 'Chronicle completion history lacks exact receipt provenance')
    require({r['player'] for r in select(db['event_players'], event_id=event_id)} == {player} and {r['nation'] for r in select(db['event_nations'], event_id=event_id)} == {countries[who]}, 'Chronicle participant/nation history indexes differ')
    cause = one(db['event_causes'], event_id=event_id)
    require(cause['parent_id'] == cause['root_id'] == source_id and cause['depth'] == 1 and json.loads(cause['story_path']) == [story['id']], 'Canonical completion lost source causality')
    return {'binding': binding, 'run': run_id, 'completion': completion, 'event': event_id, 'player': player, 'nativeQuest': quest['id'], 'progress': progress, 'proof_hash': consumed['proof_hash'], 'instance': gate['instance_id']}


def process_checks(run, manifest, processes, server_logs):
    require(len(processes.get('boots', [])) == 3, 'Exactly three serial boot records are required')
    boots = processes['boots']
    require([b.get('boot') for b in boots] == [1, 2, 3], 'Boot records are not exactly boot1, boot2, boot3')
    require(all(processes.get(key) == manifest[key] for key in ('plugin_sha256', 'starcore_sha256', 'fixture_sha256')), 'Process envelope belongs to another prepared candidate')
    previous = None
    for number, item in enumerate(boots, 1):
        require(item.get('server_exit') == 0 and item.get('client_exit') == 0, f'Boot {number} did not exit normally')
        require(item.get('normal_server_stop_requested') is True and item.get('clients_quit_all_requested') is True and item.get('all_owned_children_exited') is True, f'Boot {number} lacks normal stop/child shutdown evidence')
        require(not item.get('cleanup_errors') and not item.get('launcher_error') and not item.get('checkpoint_error'), f'Boot {number} retained a launcher, cleanup or checkpoint error')
        expected_logs = {'server_log': f'evidence/quest-evidence-boot{number}.log', 'client_log': f'evidence/quest-evidence-client-boot{number}.log'}
        require(all(item.get(key) == value for key, value in expected_logs.items()), f'Boot {number} lacks exact server/client log provenance')
        server_logs[number] = under(run, item['server_log']).read_text(encoding='utf-8', errors='replace')
        under(run, item['client_log'])
        fields = ('requested_utc', 'server_started_utc', 'fixture_ready_observed_utc', 'client_start_ack_utc', 'client_started_utc', 'clients_quit_requested_utc', 'client_ended_utc', 'server_stop_requested_utc', 'server_ended_utc', 'finished_utc')
        require(all(item.get(key) for key in fields), f'Boot {number} lacks observed process chronology')
        times = [instant(item[key]) for key in fields]
        require(all(a <= b for a, b in zip(times, times[1:])) and instant(item['server_started_utc']) <= instant(item['paper_done_observed_utc']) <= instant(item['client_start_ack_utc']), f'Boot {number} process chronology differs')
        require(instant(manifest['compiled_utc']) <= times[0], f'Boot {number} predates the compiled fixture')
        require(previous is None or previous < times[0], 'Boots were not serial after owned process termination')
        previous = times[-1]
        expected_artifacts = {key: manifest[key] for key in ('plugin_sha256', 'starcore_sha256', 'paper_sha256', 'fixture_sha256')}
        require(item.get('artifacts_after') == expected_artifacts, f'Boot {number} post-stop artifact identities differ')
        require(isinstance(item.get('server_pid'), int) and item['server_pid'] > 0 and isinstance(item.get('client_pid'), int) and item['client_pid'] > 0 and item['server_pid'] != item['client_pid'], f'Boot {number} lacks distinct owned process identities')
        cohort = sorted(COHORTS[number]); require(item.get('client_cohort') == cohort, f'Boot {number} process cohort differs')
        command = item.get('client_command', [])
        require(isinstance(command, list) and command and command.count('--run-dir') == command.count('--expected-sha') == command.count('--boot') == command.count('--players') == 1, f'Boot {number} client command is not bound')
        for flag, value in (('--run-dir', str(run)), ('--expected-sha', manifest['plugin_sha256']), ('--boot', str(number)), ('--players', ','.join(cohort))):
            require(command.index(flag) + 1 < len(command) and command[command.index(flag) + 1] == value, f'Boot {number} client command differs: {flag}')
        require(len(item.get('server_command', [])) >= 4 and item['server_command'][-3:] == ['-jar', 'paper.jar', '--nogui'], f'Boot {number} server command differs')
    require(not any(PRODUCT_DIAGNOSTIC.search(value) for value in server_logs.values()), 'Server logs contain an unhandled quest/native diagnostic')
    for number, item in enumerate(boots, 1):
        require(all(item.get(key) == manifest[key] for key in ('plugin_sha256', 'starcore_sha256', 'fixture_sha256')), f'Boot {number} JAR fingerprint differs from preparation')
        require(item.get('client_script_sha256') and item.get('controller_sha256'), f'Boot {number} lacks controller/client source fingerprints')
        client_source = run / 'evidence' / f'quest-evidence-client-used-boot{number}.cjs'
        session_source = run / 'evidence' / f'quest-evidence-session-used-boot{number}.py'
        require(client_source.is_file() and digest(client_source) == item['client_script_sha256'] and session_source.is_file() and digest(session_source) == item['controller_sha256'], f'Boot {number} copied controller/client sources differ from recorded fingerprints')
    return {'boots': boots, 'server_logs': server_logs}


def check_fixture_protocol(fixture, clients, manifest, processes):
    require(not any(row['kind'] == 'FAILED' for row in fixture), 'Actual fixture FAILED record retained; final PASS prohibited')
    for item in processes['boots']:
        boot = item['boot']; ready = observed(fixture, boot, 'READY'); closed = observed(fixture, boot, 'CLOSED')
        value = ready['value']
        require(value.get('product_sha256') == manifest['plugin_sha256'] and value.get('starcore_sha256') == manifest['starcore_sha256'] and value.get('protocol') == 1 and value.get('noDatabaseAccess') is True and value.get('noSyntheticCompletion') is True, 'Runtime fixture identity/protocol differs')
        require(closed['value'].get('normalPluginDisable') is True and instant(ready['time']) < instant(closed['time']), 'Fixture lacks normal plugin shutdown')
        require(len(matched(fixture, boot, 'IDENTITY')) == 3, f'Boot {boot} did not record exactly the three actual native identities')
        for row in select(fixture, boot=boot):
            require(instant(item['server_started_utc']) <= instant(row['time']) <= instant(item['server_ended_utc']), f'Boot {boot} fixture row falls outside owned server lifetime')
        process = observed(clients, boot, 'CLIENT_PROCESS')['value']
        require(process.get('pid') == item['client_pid'] and process.get('plugin_sha256') == manifest['plugin_sha256'] and process.get('script_sha256') == item['client_script_sha256'] and process.get('cohort') == item['client_cohort'], f'Boot {boot} actual client process identity differs')
        for row in select(clients, boot=boot):
            require(instant(item['client_started_utc']) <= instant(row['time']) <= instant(item['client_ended_utc']), f'Boot {boot} client row falls outside owned client lifetime')
        require(instant(ready['time']) <= instant(observed(clients, boot, 'CLIENT_PROCESS')['time']) and all(instant(r['time']) <= instant(closed['time']) for r in matched(clients, boot, 'ENDED')), f'Boot {boot} client started before fixture readiness or outlived shutdown')


def check_client_shutdown(rows, boot, expected):
    quit_all = matched(rows, boot, 'QUIT_ALL_REQUESTED')
    ended = matched(rows, boot, 'ENDED')
    require(len(quit_all) == 1, f'Boot {boot} needs one normal quit-all request; saw {len(quit_all)}')
    require(len(ended) == len(expected) and {r['value'].get('player') for r in ended} == expected and all(r['value'].get('requestedQuit') is True for r in ended), f'Boot {boot} lacks actual normal quit evidence for every selected client')
    return sorted(r['value']['player'] for r in ended)


def native_country_index(fixture, manifest):
    identities = {}
    for number in COHORTS:
        rows = matched(fixture, number, 'IDENTITY')
        require({row['value'].get('player') for row in rows} == {'A', 'B', 'C'}, f'Boot {number} lacks native identity observations for A/B/C')
        actual = {row['value']['player']: row['value'] for row in rows}
        require(all(actual[who].get('uuid') == manifest['players'][who] and actual[who].get('op') is False for who in 'ABC'), f'Boot {number} native identity/permission observation differs from pinned players')
        require(actual['A'].get('founder') is True and actual['B'].get('founder') is False and actual['C'].get('founder') is True and actual['A'].get('nation') == actual['B'].get('nation') and actual['C'].get('nation') != actual['A'].get('nation'), f'Boot {number} authoritative country/founder observations are invalid')
        countries = {who: actual[who]['nation'] for who in 'ABC'}
        if identities:
            require(countries == identities[1], f'Boot {number} authoritative country identity changed')
        identities[number] = countries
    return identities[1]


def check_client_protocol(clients, manifest, processes):
    for number, expected in COHORTS.items():
        rows = [r for r in clients if r['boot'] == number]
        require({r['value'].get('player') for r in rows if r['kind'] == 'SPAWN'} == expected, f'Boot {number} did not spawn exactly the expected real client cohort')
        require(not [r for r in rows if r['kind'] in CLIENT_FAILURES], f'Boot {number} has an actual client failure')
        check_client_shutdown(rows, number, expected)
        for row in rows:
            if row['kind'] == 'SPAWN':
                player = row['value']['player']
                require(row['value']['uuid'] == manifest['players'][player], f'Boot {number} client UUID drift for {player}')
    # A real GUI completion is a client click, never a fixture command or generated proof.
    for key, (_, qkey, _, _, _) in CASES.items():
        who = CASES[key][0]
        click = matched(clients, 2, 'CLICKED', player=who, path='native-complete', questId=manifest['native_quests'][qkey]['id'])
        require(len(click) == 1, f'Boot2 lacks exact real GUI completion click for {key}')
        require(not any(r['kind'] == 'NATIVE_COMPLETION_CLICK_ONLY' and r['value'].get('questId') == manifest['native_quests'][qkey]['id'] and r['value'].get('synthetic') for r in clients), 'Client marked synthetic completion')
    return {'spawned': {number: sorted({r['value'].get('player') for r in clients if r['boot'] == number and r['kind'] == 'SPAWN'}) for number in COHORTS}}


def check_client_runtime_sources(run, clients, processes):
    """Fresh acceptance must preserve the exact wire and inventory library actually loaded."""
    expected_names = {'mineflayer_entry', 'mineflayer_inventory', 'prismarine_windows_entry', 'prismarine_windows_model', 'prismarine_item_entry', 'craft_wire'}
    baseline = None
    for item in processes['boots']:
        boot = item['boot']; row = observed(clients, boot, 'CLIENT_RUNTIME_SOURCES')['value']
        base = f'evidence/quest-evidence-client-runtime-used-boot{boot}'
        saved = read_json(under(run, base + '/runtime.json'))
        require({key: value for key, value in row.items() if key != 'boot'} == saved, 'Actual client runtime source record differs from saved manifest')
        require(set(saved.get('sources', {})) == expected_names and set(saved.get('versions', {})) == {'mineflayer', 'prismarine_windows', 'prismarine_item'} and all(isinstance(value, str) and value for value in saved['versions'].values()), 'Fresh client runtime lacks the reviewed wire/library source set')
        fingerprints = {}
        for name, source in saved['sources'].items():
            require(str(source.get('copy', '')).replace('\\', '/') == base + '/' + name + '.cjs', 'Client runtime source copy leaves its exact boot directory')
            copied = under(run, source['copy'])
            require(re.fullmatch('[0-9a-f]{64}', source.get('sha256', '') or '') and digest(copied) == source['sha256'], 'Actual client runtime source bytes drifted: ' + name)
            fingerprints[name] = source['sha256']
        wire_copy = under(run, f'evidence/quest-evidence-wire-used-boot{boot}.cjs')
        transport = under(run, f'evidence/quest-evidence-control-used-boot{boot}.py')
        require(item.get('client_wire_sha256') == fingerprints['craft_wire'] == digest(wire_copy) and item.get('control_transport_sha256') == digest(transport), 'Session wire/control source differs from actual loaded/captured source')
        current = {'versions': saved['versions'], 'fingerprints': fingerprints}
        require(baseline is None or current == baseline, 'Client inventory/wire runtime changed between the three boots')
        baseline = current
    return baseline


def raw_window_snapshot(packet, snapshot):
    require(type(packet.get('stateId')) is int and packet.get('windowId') == snapshot.get('windowId') and packet['stateId'] == snapshot.get('stateId') and isinstance(packet.get('items'), list) and len(packet['items']) == len(snapshot.get('slots', [])) == 46 and 'carriedItem' in packet, 'Raw full-window schema or confirmed window/state identity differs')
    for raw, decoded in zip(packet['items'] + [packet['carriedItem']], snapshot['slots'] + [snapshot.get('cursor')]):
        require(isinstance(raw, dict) and type(raw.get('itemCount')) is int and 0 <= raw['itemCount'] <= 99, 'Raw slot has an unsupported stack schema')
        if raw['itemCount'] == 0:
            require(decoded is None, 'Predicted nonempty slot differs from raw empty server slot')
        else:
            require(type(raw.get('itemId')) is int and isinstance(decoded, dict) and decoded.get('type') == raw['itemId'] and decoded.get('count') == raw['itemCount'] and isinstance(decoded.get('name'), str), 'Decoded slot type/count differs from the actual raw server item')


def map_layout(snapshot, paper, compass, map_id, placed=None, compass_stage=None, consumed=False):
    slots = snapshot['slots']; cursor = snapshot['cursor']; paper_slots = (1, 2, 3, 4, 6, 7, 8, 9)
    def is_item(value, item, count):
        return isinstance(value, dict) and value.get('type') == item and value.get('count') == count
    def count(item):
        return sum(value['count'] for value in slots[1:] + [cursor] if isinstance(value, dict) and value.get('type') == item)
    if consumed:
        return slots[0] is None and all(value is None for value in slots[1:10]) and count(paper) == count(compass) == 0 and count(map_id) == 1
    if not all(is_item(slots[slot], paper, 1) if index < placed else slots[slot] is None for index, slot in enumerate(paper_slots)):
        return False
    if not (is_item(slots[5], compass, 1) if compass_stage == 'grid' else slots[5] is None):
        return False
    expected_cursor = (compass, 1) if compass_stage == 'cursor' else (paper, 8 - placed) if placed < 8 else None
    return (is_item(cursor, *expected_cursor) if expected_cursor else cursor is None) and count(paper) == 8 and count(compass) == 1 and (is_item(slots[0], map_id, 1) if compass_stage == 'grid' else slots[0] is None)


def check_map_wire_protocol(clients):
    """A decoded local model cannot substitute for an actual received full-window packet."""
    boot = 1; who = 'C'; rows = matched(clients, boot, 'CRAFT_GRID_REQUESTED', player=who, item='map')
    require(len(rows) == 1, 'Fresh map craft requires one real grid request')
    grid_row = rows[0]; grid = grid_row['value']; window_id = grid['windowId']
    request = observed(clients, boot, 'CRAFT_REQUESTED', player=who, item='map'); crafted = observed(clients, boot, 'CRAFTED', player=who, item='map')
    output_id = request['value']['outputId']; require(request['value']['output'] == 'map' and request['value']['before'].get('map', 0) == 0, 'Fresh wire map evidence requires one new map from the empty map baseline')
    require(grid['paperSlots'] == [1, 2, 3, 4, 6, 7, 8, 9] and grid['compassSlotInGrid'] == 5 and grid['resultSlot'] == 0 and type(grid['paperSlot']) is int and type(grid['compassSlot']) is int and 10 <= grid['paperSlot'] < 46 and 10 <= grid['compassSlot'] < 46 and grid['paperSlot'] != grid['compassSlot'], 'Real map grid input/result slot identity differs')
    relevant = [row for row in clients if row['boot'] == boot and row['value'].get('player') == who and row['kind'].startswith('CRAFT_')]
    require(not any(row['kind'] in {'CRAFT_GRID_FAILURE', 'CRAFT_UNCONFIRMED'} for row in relevant), 'Actual raw map craft failure retained')
    sends = [r for r in relevant if r['kind'] == 'CRAFT_CLIENT_PACKET_WRITE']; packets = [r for r in relevant if r['kind'] == 'CRAFT_SERVER_PACKET']; syncs = [r for r in relevant if r['kind'] == 'CRAFT_SYNC_REQUEST']; confirmed = [r for r in relevant if r['kind'] == 'CRAFT_SERVER_WINDOW_CONFIRMED']
    descriptions = ['paper pickup'] + ['paper placement slot ' + str(slot) for slot in grid['paperSlots']] + ['compass pickup', 'compass placement and actual map result', 'only result click']
    moved = matched(clients, boot, 'CRAFT_RESULT_ON_CURSOR', player=who, item='map')
    require(len(moved) <= 1, 'Crafted map cursor was moved repeatedly')
    if moved:
        descriptions.append('already crafted map moved to inventory')
    require([row['value']['description'] for row in syncs] == descriptions == [row['value']['description'] for row in confirmed] and len(sends) == len(descriptions) * 2, 'Map synchronization steps or original/synchronization write counts differ')
    require([row['value']['writeSequence'] for row in sends] == list(range(1, len(sends) + 1)) and [row['value']['receiveSequence'] for row in packets] == list(range(1, len(packets) + 1)), 'Actual map packet sequence is missing, repeated or reordered')
    first = confirmed[0]['value']['snapshot']; paper_item = first['cursor']; require(isinstance(paper_item, dict) and paper_item.get('name') == 'paper', 'Raw paper pickup did not confirm a paper cursor')
    paper_id = paper_item['type']; compasses = [value for value in first['slots'] if isinstance(value, dict) and value.get('name') == 'compass']; require(len(compasses) == 1 and compasses[0]['count'] == 1, 'Raw first pickup lacks the single real compass')
    compass_id = compasses[0]['type']; require(len({paper_id, compass_id, output_id}) == 3, 'Map wire item identities collide')
    expected_clicks = [(grid['paperSlot'], 0)] + [(slot, 1) for slot in grid['paperSlots']] + [(grid['compassSlot'], 0), (5, 1), (0, 0)]
    if moved:
        destination = moved[0]['value']['destinationSlot']; require(type(destination) is int and 10 <= destination < 46, 'Crafted map cursor destination is outside the player inventory')
        expected_clicks.append((destination, 0))
    previous = grid_row['line']; snapshots = []
    for index, (description, (slot, button)) in enumerate(zip(descriptions, expected_clicks)):
        original, synchronization = sends[index * 2:index * 2 + 2]; sync = syncs[index]; confirmation = confirmed[index]; original_packet = original['value']['packet']; sync_packet = synchronization['value']['packet']; snapshot = confirmation['value']['snapshot']
        packet_row = one([row for row in packets if row['value']['receiveSequence'] == snapshot['receiveSequence']])
        require(original['value']['packetName'] == synchronization['value']['packetName'] == 'window_click' and original_packet['windowId'] == window_id and original_packet['slot'] == slot and original_packet['mouseButton'] == button and original_packet['mode'] == 0, 'Actual original map click differs or repeats the recipe result')
        require(sync_packet['windowId'] == window_id and sync_packet['stateId'] == -1 and sync_packet['slot'] == -999 and sync_packet['mouseButton'] == 2 and sync_packet['mode'] == 5 and sync_packet['changedSlots'] == [], 'Synchronization write is not the reviewed non-action window resynchronization request')
        require(sync['value']['windowId'] == snapshot['windowId'] == window_id and sync['value']['writeSequence'] == original['value']['writeSequence'] and sync['value']['receiveSequence'] < snapshot['receiveSequence'] and packet_row['value']['packetName'] == 'window_items', 'Raw response cannot be bound to this post-click synchronization')
        require(previous < original['line'] < sync['line'] < synchronization['line'] < packet_row['line'] < confirmation['line'] < crafted['line'], 'Original click, synchronization, raw response and confirmation ordering differs')
        raw_window_snapshot(packet_row['value']['packet'], snapshot)
        if index <= 8:
            valid = map_layout(snapshot, paper_id, compass_id, output_id, index, 'inventory')
        elif index == 9:
            valid = map_layout(snapshot, paper_id, compass_id, output_id, 8, 'cursor')
        elif index == 10:
            valid = map_layout(snapshot, paper_id, compass_id, output_id, 8, 'grid')
        else:
            valid = map_layout(snapshot, paper_id, compass_id, output_id, consumed=True)
        require(valid, 'Actual raw map materials, cursor or result conservation differs after ' + description)
        if index == 12:
            require(snapshot['cursor'] is None and snapshot['slots'][slot] == {'type': output_id, 'name': 'map', 'count': 1}, 'Already-created map was not moved once into the acknowledged destination')
        previous = confirmation['line']; snapshots.append(snapshot)
    result = observed(clients, boot, 'CRAFT_RESULT_VERIFIED', player=who, item='map')
    require(result['value'].get('serverSnapshot') == snapshots[10] and result['value']['slot'] == 0 and result['value']['count'] == 1 and confirmed[10]['line'] < result['line'] < sends[22]['line'], 'Unique map result click lacks the preceding raw map result proof')
    require(bool(moved) == (snapshots[11]['cursor'] is not None), 'Actual raw crafted cursor and single inventory move disagree')
    return {'original_clicks': len(descriptions), 'synchronization_requests': len(syncs), 'raw_confirmations': len(confirmed), 'result_clicks': 1}


def final_projection(value):
    """The stable fields explicitly compared before and after canonical source replay."""
    endings = event_index(value['history'])
    completions = event_index(value['nativeCompletions'])
    stories = sorted((story['id'], story['story'], story['completed'], story['choices']) for story in value['stories'])
    return canonical({'A_fate': value['A_fate'], 'C_fate': value['C_fate'], 'B_online': value['B_online'], 'endings': endings, 'completions': completions, 'stories': stories})


def event_index(events):
    result = {}
    for event in events:
        require(event['id'] not in result and len(event['participants']) == len(set(event['participants'])) and len(event['nations']) == len(set(event['nations'])), 'API event identity or set projection is duplicated')
        result[event['id']] = event | {'participants': sorted(event['participants']), 'nations': sorted(event['nations'])}
    return result


def lore_field(item, prefix):
    boundaries = ('实例: ', '范围: ', '参与人数: ', '当前阶段: ', '国家票节点: ', '一国一席', '投票: ', '节点: ', '修订号: ', '状态: ', '效果: ', '参与者: ', '权限和条件', '同一阶段', '提交时', 'ID: ', '名称: ', '描述: ', '分类: ', '限时: ')
    lines = item.get('lore', [])
    starts = [n for n, line in enumerate(lines) if line.startswith(prefix)]
    require(len(starts) == 1, 'Actual GUI lore field is missing or ambiguous: ' + prefix)
    start = starts[0]; value = lines[start][len(prefix):]
    for line in lines[start + 1:]:
        if line.startswith(boundaries):
            break
        value += line
    return value.strip()


def check_chronicle_projection(manifest, fixture, clients, checkpoints, countries, final, cases):
    """Bind actual inventory clicks to durable choices, endings and exact local receipts."""
    db = checkpoints[-1]['db']; expected_outcomes = set(); expected_honor = {value: 0 for value in manifest['players'].values()}
    instances = [json.loads(row['json']) for row in db['instances']]
    require(len(instances) == 3 and {i['definition']['id'] for i in instances} == {s['id'] for s in manifest['stories'].values()}, 'Stopped DB has additional or missing fixture instances')
    require(not checkpoints[0]['db']['outcomes'] and not checkpoints[1]['db']['outcomes'] and not checkpoints[0]['db']['story_choice_log'] and not checkpoints[1]['db']['story_choice_log'], 'Choice or reward was committed before actual boot3 GUI actions')
    require(len(db['story_choice_log']) == 5 and len(db['story_nation_vote_log']) == len(db['story_nation_resolutions']) == 1, 'Final personal/national choice ledgers have missing or repeated rows')
    api_stories = {item['id']: item for item in final['stories']}
    require(len(api_stories) == len(final['stories']) == 3, 'Final API has additional or duplicate fixture instances')
    for key, meta in manifest['stories'].items():
        actor_who = 'C' if key == 'atlas' else 'A'; actor = manifest['players'][actor_who]
        instance = one(instances, id=cases[key]['instance']); instance_id = instance['id']; source_id = 'quest-evidence-paper:' + key + ':1'
        definition = instance['definition']; nation = key == 'nation'; expected_members = {manifest['players'][who]: countries[who] for who in ('ABC' if nation else actor_who)}
        expected_choices = {'await': meta['choice']} | ({} if nation else {'await/' + actor: meta['choice']})
        require(instance_id == stable(meta['id'], '1', 'world', source_id) and definition['id'] == meta['id'] and definition['version'] == '1' and definition['title'] == meta['title'] and definition['scope'] == 'WORLD' and definition.get('voteUnit', 'PLAYER') == ('NATION' if nation else 'PLAYER') and instance['sourceEventId'] == source_id and instance['scopeKey'] == 'world' and instance['node'] == 'done' and instance['completed'] is True and instance['members'] == expected_members and instance['choices'] == expected_choices and instance['votes'] == {}, 'Stopped instance identity, members, completed node or actual choices differ')
        row = one(db['instances'], id=instance_id)
        require(row['story'] == meta['id'] and row['scope'] == 'world' and row['completed'] == 1 and millis(row['updated']) == millis(instance['updatedAt']) and api_stories[instance_id] == {'id': instance_id, 'story': meta['id'], 'completed': True, 'choices': expected_choices}, 'Instance SQL indexes or final API differ from canonical stopped state')
        verified = observed(clients, 3, 'STORY_VERIFIED', player=actor_who, storyId=meta['id']); clicked = observed(clients, 3, 'CLICKED', player=actor_who, path='story-choice', storyId=meta['id']); chosen = observed(clients, 3, 'GUI_CHOICE', player=actor_who, storyId=meta['id'])
        window = verified['value']['window']; header = one(window['slots'], slot=4); slot = one(window['slots'], slot=clicked['value']['slot'])
        tokens = {'player': actor_who, 'storyId': meta['id'], 'instanceId': instance_id, 'choice': meta['choice'], 'voteUnit': 'NATION' if nation else 'PLAYER', 'node': 'await', 'revision': '0' if nation else None}
        require(all(snapshot_fields(item['value'], tokens) == tokens for item in (clicked, chosen)) and verified['value']['instanceId'] == instance_id and window['type'] == 'minecraft:generic_9x6' and window['title'] == ('国家议会 · ' if nation else '') + meta['title'] and clicked['value']['windowId'] == window['id'] and clicked['value']['fromTitle'] == window['title'] and 9 <= slot['slot'] < 45 and slot['name'] == 'lime_dye' and header['name'] == 'written_book' and header['label'] == meta['title'] and lore_field(header, '实例: ') == instance_id and lore_field(header, '国家票节点: ' if nation else '当前阶段: ') == 'await' and lore_field(slot, '国家选项: ' if nation else '选项: ') == meta['choice'], 'Actual Chronicle GUI window, choice or immutable tokens differ')
        require(instant(verified['time']) <= instant(clicked['time']) <= instant(chosen['time']), 'Actual story verification/click/result order differs')
        if nation:
            require(lore_field(slot, '修订号: ') == '0' and lore_field(slot, '节点: ') == 'await', 'Actual national GUI revision token differs')
        resolution = one(db['story_choice_log'], instance_id=instance_id, kind='RESOLUTION')
        common = {'source_event_id': source_id, 'story_id': meta['id'], 'story_version': '1', 'node_id': 'await', 'choice_id': meta['choice'], 'scope_key': 'world', 'revision': 1, 'committed_by': actor, 'provenance': 'LIVE', 'nation_id': countries[actor_who]}
        require(snapshot_fields(resolution, common) == common and resolution['id'] == stable('choice-log', instance_id, 'await', 'RESOLUTION', 'world', '1') and resolution['actor_key'] == 'world' and resolution['player_id'] is None and instant(verified['time']) <= instant(resolution['chosen_at']) <= instant(clicked['time']), 'Durable choice resolution differs from actual GUI actor/choice/time')
        if nation:
            vote = one(db['story_nation_vote_log'], instance_id=instance_id)
            require(vote['id'] == stable('national-vote', instance_id, 'await', countries['A'], '1') and vote['source_event_id'] == source_id and vote['story_id'] == meta['id'] and vote['story_version'] == '1' and vote['node_id'] == 'await' and vote['nation_id'] == vote['actor_nation_id'] == countries['A'] and vote['actor_id'] == actor and vote['choice_id'] == meta['choice'] and vote['action'] == 'CAST' and vote['status'] == 'ACTIVE' and vote['revision'] == 1 and vote['changed_at'] == resolution['chosen_at'], 'Durable national vote differs from actual authorized GUI actor')
            authority = json.loads(vote['authority_evidence'])
            require(authority['actor'] == actor and authority['nation'] == countries['A'] and authority['issuer'] == 'StarCore.NationModule.current-holder' and authority['checkedAt'] == vote['checked_at'] and instant(verified['time']) <= instant(vote['checked_at']) <= instant(vote['changed_at']), 'National authority evidence differs from actual native holder')
            national = one(db['story_nation_resolutions'], instance_id=instance_id); frozen = json.loads(national['json'])
            require(national['id'] == resolution['id'] and national['actor_id'] == actor and national['choice_id'] == meta['choice'] and national['quorum'] == 1 and national['authority_evidence'] == vote['authority_evidence'] and frozen == {'id': resolution['id'], 'instanceId': instance_id, 'nodeId': 'await', 'choiceId': meta['choice'], 'quorum': 1, 'actorId': actor, 'checkedAt': vote['checked_at'], 'counted': [{'nationId': countries['A'], 'revision': 1, 'choiceId': meta['choice'], 'actorId': actor}], 'authorityEvidence': vote['authority_evidence']}, 'Frozen national resolution differs from actual counted vote')
        else:
            ballot = one(db['story_choice_log'], instance_id=instance_id, kind='BALLOT'); actor_key = 'player:' + actor
            require(snapshot_fields(ballot, common) == common and ballot['id'] == stable('choice-log', instance_id, 'await', 'BALLOT', actor_key, '1') and ballot['actor_key'] == actor_key and ballot['player_id'] == actor and instant(verified['time']) <= instant(ballot['chosen_at']) <= instant(resolution['chosen_at']), 'Durable personal ballot differs from actual GUI actor')
        ending_outcome = stable(instance_id, 'node/done', 'stable-ending', 'WORLD'); expected_outcomes.add(ending_outcome)
        ending_id = 'chronicle:' + ending_outcome; ending = verify_world_event(one(db['events'], id=ending_id))
        outcome_text = 'Fixture-only completed campaign, following real native craft and GUI submission.'
        require(ending['type'] == meta['ending'] and ending['source'] == 'StarChronicle' and set(ending['participants']) == set(expected_members) and set(ending['nations']) == set(expected_members.values()) and ending['facts'] == {'story': meta['id'], 'outcome': outcome_text} and ending['evidence'] == f'instance={instance_id}; action=node/done; effect=stable-ending; source={source_id}' and instant(resolution['chosen_at']) <= instant(ending['occurredAt']) <= instant(clicked['time']), 'Ending history differs from actual GUI resolution and stable result ID')
        require({r['player'] for r in select(db['event_players'], event_id=ending_id)} == set(expected_members) and {r['nation'] for r in select(db['event_nations'], event_id=ending_id)} == set(expected_members.values()), 'Ending participant/nation indexes differ')
        cause = one(db['event_causes'], event_id=ending_id)
        require(cause['parent_id'] == cause['root_id'] == source_id and cause['depth'] == 1 and json.loads(cause['story_path']) == [meta['id']], 'Ending source causality differs')
        for member in expected_members:
            expected_honor[member] += meta['honor']; expected_outcomes.add(stable(instance_id, 'node/done', 'stable-honor', member))
    require({r['id'] for r in db['outcomes']} == expected_outcomes and len(db['outcomes']) == len(expected_outcomes) and all(r['status'] == 'DONE' and r['json'] == '{}' for r in db['outcomes']), 'Exact stable local reward/ending receipts differ')
    require(sorted((r['owner'], r['key'], r['value']) for r in db['facts']) == sorted((player, 'player.honor', str(amount)) for player, amount in expected_honor.items()), 'Stopped honor ledger differs from exact once-only local rewards')
    for who in ('A', 'C'):
        require(final[who + '_fate']['player.honor'] == str(expected_honor[manifest['players'][who]]), 'Final API honor differs from independently stopped ledger')
    canonical_events = event_index([verify_world_event(r) for r in db['events']])
    api_events = event_index(final['history'])
    require(canonical(api_events) == canonical(canonical_events), 'Final API history differs from stopped canonical history')
    completed_events = {key: value for key, value in canonical_events.items() if value['type'] == 'quest.completed'}
    require(len(final['nativeCompletions']) == 3 and canonical(event_index(final['nativeCompletions'])) == canonical(completed_events), 'Final API native completion projection differs from stopped history')
    return {'actual_gui_choices': 3, 'personal_ballots': 2, 'national_votes': 1, 'resolutions': 3, 'done_outcomes': len(expected_outcomes), 'honor': expected_honor}


def inspect(args):
    expected = args.expected_sha
    require(re.fullmatch(r'[0-9a-f]{64}', expected or ''), 'Require complete product SHA')
    run = (Path(args.run_dir).resolve() if args.run_dir is not None else
           (ROOT / 'run' / ('paper-quest-evidence-' + expected[:8] + '-' + args.attempt)).resolve())
    require(run.parent == (ROOT / 'run').resolve(), 'Run escaped this worktree')
    require(re.fullmatch(r'paper-quest-evidence-' + re.escape(expected[:8]) + r'-[a-z0-9-]{1,24}', run.name), 'Run directory must be the exact SHA-bound attempt path')
    manifest = read_json(run / 'preparation.json')
    preparation_checks(run, manifest, expected)
    fixture = fixture_log(run / 'evidence/quest-fixture.jsonl')
    require(not any(row['kind'] == 'FAILED' for row in fixture), 'Actual fixture FAILED record retained; final PASS prohibited')
    clients = client_log(run / 'evidence/quest-evidence-clients.log')
    processes = read_json(run / 'evidence/quest-evidence-processes.json')
    process = process_checks(run, manifest, processes, {})
    check_fixture_protocol(fixture, clients, manifest, processes)
    runtime_sources = check_client_runtime_sources(run, clients, processes)
    protocol = check_client_protocol(clients, manifest, processes)
    wire = check_map_wire_protocol(clients)
    countries = native_country_index(fixture, manifest)
    for who in ('A', 'C'):
        require(countries[who] and re.fullmatch(r'[0-9a-f-]{36}', countries[who]), f'Missing captured nation ID {who}')
    require(countries['B'] == countries['A'], 'B must be the ordinary member of A nation')
    checkpoints = [checkpoint(run, n, process['boots'][n - 1], manifest) for n in (1, 2, 3)]
    cases = {key: check_case(key, manifest, fixture, clients, checkpoints, countries) for key in CASES}
    paused = observed(fixture, 2, 'PAUSED_CHECK_PASS')
    require(paused['value']['registeredProviders'] == 0 and paused['value']['countryReady'] is False, 'Provider unregister did not leave Chronicle paused with country blocked')
    offline = observed(fixture, 3, 'OFFLINE_COUNTRY_CHECK_PASS')
    require(offline['value']['B_online'] is False and offline['value']['A']['ready'] is True and offline['value']['C']['ready'] is False, 'Offline B canonical receipt did not unlock only A original country')
    before_replay = observed(fixture, 3, 'PRE_REPLAY_CHECK_PASS')['value']
    final = observed(fixture, 3, 'FINAL_CHECK_PASS')['value']
    replay = [r for r in fixture if r['kind'] == 'REPLAY_DUPLICATE']
    require({r['value'].get('story') for r in replay} == {'qe-player-records', 'qe-nation-testimonies', 'qe-player-atlas'} and len(replay) == 3, 'Missing one replay duplicate per source story')
    for row in replay:
        require(row['value']['report']['duplicate'] is True, 'Canonical replay was accepted as a new source')
    require(observed(fixture, 3, 'PRE_REPLAY_CHECK_PASS')['line'] < min(row['line'] for row in replay) and max(row['line'] for row in replay) < observed(fixture, 3, 'FINAL_CHECK_PASS')['line'], 'Replay was not bracketed by explicit pre-replay and post-replay checks')
    require(final_projection(before_replay) == final_projection(final), 'Canonical event replay changed the persisted completion/story/reward projection')
    # Final state is independently checked from the API fixture and the stopped DB.
    require(final['A_fate']['player.honor'] == '10' and final['C_fate']['player.honor'] == '12', 'Final online honor does not equal A=10/C=12')
    all_events = checkpoints[-1]['db']['events']
    completions = [json.loads(row['json']) for row in all_events if row['type'] == 'quest.completed']
    endings = [row for row in all_events if row['type'] in ('QE_PLAYER_FINISHED', 'QE_NATION_FINISHED', 'QE_ATLAS_FINISHED')]
    require(len(completions) == 3 and len({row['id'] for row in completions}) == 3 and len(endings) == 3 and {row['type'] for row in endings} == {'QE_PLAYER_FINISHED', 'QE_NATION_FINISHED', 'QE_ATLAS_FINISHED'}, 'Final history has duplicate/missing completion or ending events')
    # Every completion must have one Chronicle source row and one consumed proof key.
    consumed = checkpoints[-1]['db']['quest_gate_completions']
    require(len(consumed) == 3 and len({(r['issuer'], r['completion_id']) for r in consumed}) == 3 and len({(r['issuer'], r['run_id']) for r in consumed}) == 3 and len({r['gate_id'] for r in consumed}) == 3, 'Consumed completion keys are not exactly once')
    projection = check_chronicle_projection(manifest, fixture, clients, checkpoints, countries, final, cases)
    result = {'status': 'PASS', 'scope': 'Three normal Paper boots with real CRAFT_ITEM progress, native GUI completion, provider pause, offline canonical nation readiness and Chronicle history', 'product_sha256': expected, 'starcore_sha256': manifest['starcore_sha256'], 'fixture_sha256': manifest['fixture_sha256'], 'processes': process, 'protocol': protocol, 'cases': cases, 'checkpoints': [{'boot': n, 'descriptor_sha256': cp['descriptor_sha256'], 'authority': cp['authority'], 'native_journal_count': len(cp['journal']), 'db_schema': cp['db']['schema_version']} for n, cp in zip((1, 2, 3), checkpoints)], 'replays': len(replay), 'history': {'quest_completed': len(completions), 'endings': len(endings), 'consumed_keys': len(consumed)}, 'fixture_log_sha256': digest(run / 'evidence/quest-fixture.jsonl'), 'client_log_sha256': digest(run / 'evidence/quest-evidence-clients.log')}
    result['chronicle_projection'] = projection
    result['client_runtime_sources'] = runtime_sources
    result['map_wire'] = wire
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--expected-sha', required=True)
    parser.add_argument('--run-dir', type=Path)
    parser.add_argument('--attempt', default='retry1')
    parser.add_argument('--live', action='store_true', help='Only report bounded pending evidence; never report PASS')
    args = parser.parse_args()
    try:
        if args.live:
            expected = args.expected_sha
            require(re.fullmatch(r'[0-9a-f]{64}', expected or ''), 'Require complete product SHA')
            run = (Path(args.run_dir).resolve() if args.run_dir is not None else
                   (ROOT / 'run' / ('paper-quest-evidence-' + expected[:8] + '-' + args.attempt)).resolve())
            require(run.parent == (ROOT / 'run').resolve(), 'Run escaped this worktree')
            require(re.fullmatch(r'paper-quest-evidence-' + re.escape(expected[:8]) + r'-[a-z0-9-]{1,24}', run.name), 'Run directory must be the exact SHA-bound attempt path')
            path = run / 'evidence/quest-fixture.jsonl'
            rows = fixture_log(path) if path.is_file() else []
            pending = [row for row in rows if row['kind'] in ('READY', 'SOURCE', 'NATIVE_ACTIVE_CHECK_PASS', 'PAUSED_CHECK_PASS')]
            print(json.dumps({'status': 'PENDING', 'final_pass_prohibited': True, 'observed_records': len(rows), 'pending_markers': len(pending), 'reason': 'Three complete stopped boot snapshots and final cross-database checks are required.'}, ensure_ascii=False, indent=2))
            return 0
        result = inspect(args)
        output_run = (Path(args.run_dir).resolve() if args.run_dir is not None else
                      (ROOT / 'run' / ('paper-quest-evidence-' + args.expected_sha[:8] + '-' + args.attempt)).resolve())
        output = output_run / 'evidence/quest-evidence-inspection.json'
        output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        print(json.dumps({k: result[k] for k in ('status', 'product_sha256', 'starcore_sha256', 'fixture_sha256', 'history')}, ensure_ascii=False, indent=2))
        return 0
    except Exception as error:
        print(json.dumps({'status': 'FAIL', 'reason': str(error), 'final_pass_prohibited': True}, ensure_ascii=False, indent=2), file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
