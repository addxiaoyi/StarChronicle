"""Assess actual standalone Folia national degradation evidence for one exact frozen JAR.

Read-only databases; no server/JVM launches. Failed capacity preflight is retained separately
and never counted as multiplayer acceptance. Public PASS is emitted only with complete
successful protocol, GUI, invariant and observed process-exit evidence.
"""
from pathlib import Path
from datetime import datetime, timezone
import argparse
import ast
import copy
import importlib.util
import json
import re
import shutil

SPEC = importlib.util.spec_from_file_location('national_folia_prepare', Path(__file__).with_name('prepare-national-council-folia.py'))
p = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(p)


def title(value):
    if value is None:
        return ''
    if isinstance(value, str):
        return value
    if isinstance(value, list):
        return ''.join(title(item) for item in value)
    if isinstance(value, dict):
        if value.get('type') == 'string':
            return value.get('value', '')
        return title(value.get('text', value.get('value', ''))) + title(value.get('extra', []))
    return ''


def protocol(path):
    result = []
    for line in path.read_text(encoding='utf-8', errors='strict').splitlines():
        match = re.match(r'^(\S+) ([A-Z_]+) (\{.*\})$', line)
        if match:
            result.append({'time': match.group(1), 'kind': match.group(2), 'value': json.loads(match.group(3))})
    return result


def fixture(path):
    return [json.loads(line) for line in path.read_text(encoding='utf-8', errors='strict').splitlines() if line.strip()]


def text_logs(path):
    return path.read_text(encoding='utf-8', errors='strict')


def sanitize(text):
    return '\n'.join('[REDACTED: credential-related server log line]' if re.search(r'(?i)secret|password|bearer|api[-_ ]?key|token\s*[:=]', line) else line for line in text.splitlines()) + '\n'


def same_ballots(left, right):
    def normalized(values):
        answer = []
        for value in values:
            item = dict(value)
            stamp = item.get('checkedAt')
            if isinstance(stamp, str):
                match = re.fullmatch(r'(.*?)(?:\.(\d+))?Z', stamp)
                if match:
                    fraction = (match.group(2) or '').rstrip('0')
                    item['checkedAt'] = match.group(1) + ('.' + fraction if fraction else '') + 'Z'
            answer.append(item)
        return sorted(answer, key=lambda item: item['nationId'])
    return normalized(left) == normalized(right)


def national_gui(view, expected, completed):
    if not view or '国家议会' not in title(view.get('title')):
        return False
    slots = {item['slot']: item for item in view.get('slots', [])}
    own = slots.get(2, {}).get('lore', [])
    counts = slots.get(6, {}).get('lore', [])
    labels = [item.get('label', '') for item in slots.values()]
    lores = [line for item in slots.values() for line in item.get('lore', [])]
    return ('当前核验国家: 无' in own and '国家票修订号: 0' in own
            and all(f'{choice}: {count}' in counts for choice, count in expected['counts'].items())
            and ('落定结果: ' + (expected['resolution'] or '尚未落定')) in counts
            and ('此篇国家决议已落定' if completed else '当前仅可旁观') in labels
            and not any(line.startswith('国家选项: ') for line in lores)
            and '撤回本国当前票' not in labels)


LEDGER_FIELDS = ('修订号: ', '状态: ', '选项: ', '提交者: ', '核验时间: ', '权威来源: ')


def ledger_lore(lore):
    """Undo display wrapping without deleting or inventing any field characters.

    Menus wraps each complete logical lore field into consecutive 38-character chunks.
    The six distinct, ordered prefixes delimit fields; all intervening continuation chunks
    are concatenated verbatim. Duplicate/out-of-order/missing fields fail closed.
    """
    values = {}
    current = None
    for line in lore:
        if not isinstance(line, str):
            return None
        prefix = next((prefix for prefix in LEDGER_FIELDS if line.startswith(prefix)), None)
        if prefix is not None:
            if len(values) >= len(LEDGER_FIELDS) or prefix != LEDGER_FIELDS[len(values)]:
                return None
            current = prefix
            values[current] = line[len(prefix):]
        elif current is None:
            return None
        else:
            values[current] += line
    return values if tuple(values) == LEDGER_FIELDS else None


def ledger_gui(view, expected):
    if not view or '已记录国家票' not in title(view.get('title')):
        return False
    slots = view.get('slots', [])
    countries = [item.get('label') for item in slots if re.fullmatch(r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}', item.get('label', ''))]
    if sorted(countries) != sorted(ballot['nationId'] for ballot in expected['ballots']):
        return False
    for ballot in expected['ballots']:
        matches = [item for item in slots if item.get('label') == ballot['nationId']]
        if len(matches) != 1:
            return False
        fields = ledger_lore(matches[0].get('lore', []))
        checked_at = datetime.fromisoformat(ballot['checkedAt'].replace('Z', '+00:00')).astimezone(timezone.utc).strftime('%Y-%m-%d %H:%M:%S UTC')
        exact = dict(zip(LEDGER_FIELDS, (str(ballot['revision']), ballot['status'], ballot['choiceId'] or '（已撤回）',
                                       ballot['actorId'], checked_at, ballot['authorityEvidence'])))
        if fields != exact:
            return False
    return True


def ledger_parser_checks(views, expectations):
    """Use actual captured inventories as positive controls, then corrupt only copies.

    Prefix and continuation mutations independently prove that the entire actor UUID and
    entire authority payload matter, while revision/status/choice remain exact as well.
    No captured evidence file is edited by these negative checks.
    """
    checks = {}
    for which, expected in expectations.items():
        original = next((item['window'] for item in views if ledger_gui(item['window'], expected)), None)
        checks[which + '_actual_wrapped_fields_match_exactly'] = original is not None
        for field, prefix, continuation in [('actor', '提交者: ', False), ('actor_continuation', '提交者: ', True),
                                            ('authority', '权威来源: ', False), ('authority_continuation', '权威来源: ', True),
                                            ('revision', '修订号: ', False), ('status', '状态: ', False), ('choice', '选项: ', False)]:
            passed = False
            if original is not None:
                damaged = copy.deepcopy(original)
                item = next(slot for slot in damaged['slots'] if slot.get('label') == expected['ballots'][0]['nationId'])
                lore = item['lore']
                start = next(index for index, line in enumerate(lore) if line.startswith(prefix))
                end = next((index for index in range(start + 1, len(lore)) if any(lore[index].startswith(marker) for marker in LEDGER_FIELDS)), len(lore))
                if continuation:
                    positions = [(index, offset) for index in range(start + 1, end) for offset, character in enumerate(lore[index]) if character.isascii() and character.isalnum()]
                    if positions:
                        index, offset = positions[-1]
                        character = lore[index][offset]
                        replacement = '0' if character != '0' else '1'
                        lore[index] = lore[index][:offset] + replacement + lore[index][offset + 1:]
                        passed = not ledger_gui(damaged, expected)
                elif field == 'revision':
                    lore[start] = prefix + str(expected['ballots'][0]['revision'] + 1)
                    passed = not ledger_gui(damaged, expected)
                elif field == 'status':
                    lore[start] = prefix + ('WITHDRAWN' if expected['ballots'][0]['status'] != 'WITHDRAWN' else 'ACTIVE')
                    passed = not ledger_gui(damaged, expected)
                elif field == 'choice':
                    lore[start] = prefix + ('no' if expected['ballots'][0]['choiceId'] != 'no' else 'yes')
                    passed = not ledger_gui(damaged, expected)
                else:
                    offset = len(prefix) + (2 if field == 'authority' else 0)
                    if offset < len(lore[start]):
                        character = lore[start][offset]
                        replacement = 'f' if character != 'f' else 'e'
                        lore[start] = lore[start][:offset] + replacement + lore[start][offset + 1:]
                        passed = not ledger_gui(damaged, expected)
            checks[which + '_corrupt_' + field + '_is_rejected'] = passed
    return checks


def prior_ledger_parser_checks(path, views, expectations):
    """Evaluate only the two archived pure functions, never its CLI/main or file writes."""
    tree = ast.parse(path.read_text(encoding='utf-8'), filename=str(path))
    functions = [node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name in {'title', 'ledger_gui'}]
    if {node.name for node in functions} != {'title', 'ledger_gui'}:
        raise SystemExit('Archived reporter pure-function evidence is incomplete')
    namespace = {}
    exec(compile(ast.Module(body=functions, type_ignores=[]), str(path), 'exec'), namespace)
    return {which + '_archived_single_line_predicate_rejects_actual_wrapped_gui':
            not any(namespace['ledger_gui'](item['window'], expected) for item in views)
            for which, expected in expectations.items()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--expected-sha', required=True)
    parser.add_argument('--run-dir', type=Path)
    args = parser.parse_args()
    if not re.fullmatch(r'[0-9a-f]{64}', args.expected_sha):
        raise SystemExit('Require full exact approved product SHA-256')
    run = p.run_path(args.expected_sha, args.run_dir)
    manifest = p.load(args, run)
    evidence = run / 'evidence'
    snapshot_labels = ['prepared-baseline', 'capacity-preflight-stopped', 'before-accepted-clients', 'after-actions', 'final-stopped']
    preflight_prefix = 'national-council-folia-capacity-'
    preflight_names = [preflight_prefix + suffix for suffix in ['metadata.json', 'processes-original.json', 'controller.log',
                       'client.log', 'clients-all.log', 'fixture.jsonl', 'server.log', 'session-used.py', 'clients-used.cjs']]
    required = ['national-folia-source-baseline.json', 'national-council-folia-processes.json',
                'national-council-folia-clients.log', 'national-folia-fixture.jsonl', 'national-council-folia-final.log',
                'national-council-folia-boot1.log', 'national-council-folia-client-boot1.log',
                'national-council-folia-session-used.py', 'national-council-folia-clients-used.cjs',
                'fixture-compile-attempt1.log', 'national-council-folia-reporter-before-wrap-fix.py',
                'national-council-folia-reporter-wrap-fix-observation.json',
                'national-council-folia-reporter-archive-encoding-observation.json']
    required += ['national-folia-' + label + '.json' for label in snapshot_labels] + preflight_names
    missing = [name for name in required if not (evidence / name).is_file()]
    if missing:
        raise SystemExit('Incomplete actual Folia evidence; no report generated: ' + ', '.join(missing))
    baseline = json.loads((evidence / 'national-folia-source-baseline.json').read_text(encoding='utf-8'))
    snapshots = {label: json.loads((evidence / ('national-folia-' + label + '.json')).read_text(encoding='utf-8')) for label in snapshot_labels}
    processes = json.loads((evidence / 'national-council-folia-processes.json').read_text(encoding='utf-8'))
    capacity = json.loads((evidence / (preflight_prefix + 'metadata.json')).read_text(encoding='utf-8'))
    cli = protocol(evidence / 'national-council-folia-clients.log')
    old_cli = protocol(evidence / (preflight_prefix + 'clients-all.log'))
    observations = fixture(evidence / 'national-folia-fixture.jsonl')
    old_observations = fixture(evidence / (preflight_prefix + 'fixture.jsonl'))
    final_log = text_logs(evidence / 'national-council-folia-final.log')
    groups = {}
    for entry in observations:
        groups.setdefault(entry['kind'], []).append(entry['value'])
    views = [entry['value'] for entry in cli if entry['kind'] == 'CLIENT_VIEW' and entry['value'].get('window')]
    cohort = manifest['players']
    expected = manifest['expected_states']
    parser_checks = ledger_parser_checks(views, expected)
    repair_observation = json.loads((evidence / 'national-council-folia-reporter-wrap-fix-observation.json').read_text(encoding='utf-8'))
    prior_reporter = evidence / 'national-council-folia-reporter-before-wrap-fix.py'
    prior_sha = p.digest(prior_reporter)
    if prior_sha != '961488c0420f8a1c48dde43f8d84f999cf08bf53fb552ecf394c7f7db5c5493a' or prior_sha != repair_observation.get('original_reporter_sha256'):
        raise SystemExit('Archived pre-fix reporter byte evidence differs')
    prior_parser_checks = prior_ledger_parser_checks(prior_reporter, views, expected)
    compiler_raw = (evidence / 'fixture-compile-attempt1.log').read_bytes()
    try:
        compiler_text = compiler_raw.decode('utf-8', errors='strict')
        compiler_encoding = 'utf-8'
    except UnicodeDecodeError:
        compiler_text = compiler_raw.decode('cp936', errors='strict')
        compiler_encoding = 'cp936'
    archive_observation = json.loads((evidence / 'national-council-folia-reporter-archive-encoding-observation.json').read_text(encoding='utf-8'))
    if (archive_observation.get('classification') != 'REPORTER_ARCHIVE_ENCODING_FAILURE_NOT_PRODUCT_FAILURE'
            or archive_observation.get('observed_exit_code') != 1 or archive_observation.get('execution_observation_is_transcribed') is not True
            or archive_observation.get('source_log_raw_sha256') != p.digest(evidence / 'fixture-compile-attempt1.log')
            or archive_observation.get('product_or_server_or_database_or_raw_gui_changed_to_fix') is not False):
        raise SystemExit('Archive encoding failure observation differs from the retained original compiler evidence')
    source_db = baseline['chronicle']
    final_db = snapshots['final-stopped']['chronicle']
    current_db = p.database_snapshot(run / 'plugins/StarChronicle/chronicle.db')
    native_denials = {'A current native nation is required', 'Audited native national authority is unavailable', 'No authoritative national representative service is available'}
    states_match = True
    for who in cohort:
        for which, original in expected.items():
            states = [item['state'] for item in groups.get('STATE', []) if item.get('player') == who and item.get('story') == p.STORIES[which]]
            states_match = states_match and len(states) >= 2 and all(
                state['instanceId'] == original['instanceId'] and state['nodeId'] == original['nodeId'] and state['completed'] == original['completed']
                and state['quorum'] == original['quorum'] and state.get('resolution') == original['resolution'] and state['counts'] == original['counts']
                and same_ballots(state['ballots'], original['ballots']) and state['viewerNationId'] == '' and state['viewerRevision'] == 0
                and not state['representative'] and not state['canVote'] and not state['canWithdraw'] for state in states)
    boots = processes.get('boots', [])
    attempts = processes.get('preflight_attempts', [])
    verified_node = capacity.get('verified_client_process', {})
    cleanup = capacity.get('client_cleanup_attempts', [])
    current_capacity = dict(line.split('=', 1) for line in (run / 'server.properties').read_text(encoding='utf-8').splitlines() if '=' in line and not line.startswith('#'))
    actual_direct = {}
    for who in cohort:
        sent = [entry['value']['message'] for entry in cli if entry['kind'] == 'SENT' and entry['value'].get('player') == who]
        messages = [entry['value']['message'] for entry in cli if entry['kind'] == 'MESSAGE' and entry['value'].get('player') == who]
        instance = expected['invalidation']['instanceId']
        actual_direct[who] = (any(message in sent for message in ['/chronicle vote ' + instance + ' council 0 yes', '/chronicle vote ' + instance + ' council 0 no'])
                              and '/chronicle withdraw ' + instance + ' council 0' in sent
                              and sum(any(denial in message for denial in native_denials) for message in messages) >= 2)
    checks = {
        'exact_frozen_product_folia_and_fixture': manifest['plugin_sha256'] == args.expected_sha and p.digest(run / 'plugins/StarChronicle.jar') == args.expected_sha and p.digest(run / 'folia.jar') == manifest['server_sha256'] == p.FOLIA_SHA and manifest.get('fixture_compiled') is True and p.digest(run / 'plugins/NationalCouncilFoliaFixture.jar') == manifest['fixture_sha256'] and manifest.get('fixture_compile_exit_code') == 0,
        'original_paper_checkpoint3_and_all_retained_source_files_unchanged': p.source_unchanged(manifest) and baseline['checkpoint'] == 'boot3' and baseline['database_sha256'] == manifest['source_database_sha256'] and len(baseline['normal_paper_processes']['boots']) == 3 and all(boot.get('server_exit') == 0 and boot.get('client_exit') == 0 for boot in baseline['normal_paper_processes']['boots']),
        'same_two_story_file_bytes_and_no_native_plugin': set(manifest['story_sha256']) == {story + '.json' for story in p.STORIES.values()} and all(p.digest(run / 'plugins/StarChronicle/stories' / name) == sha for name, sha in manifest['story_sha256'].items()) and {path.name for path in (run / 'plugins').glob('*.jar')} == {'StarChronicle.jar', 'NationalCouncilFoliaFixture.jar'} and not (run / 'plugins/StarCore').exists(),
        'all_readonly_stages_keep_exact_schema5_national_history_reward_tables': all(snapshot['plugin_sha256'] == args.expected_sha and snapshot['source_files_unchanged'] and snapshot['database_invariants_status'] == 'PASS' and set(snapshot['invariant_tables_unchanged']) == set(p.INVARIANTS) and all(snapshot['invariant_tables_unchanged'].values()) and all(snapshot['chronicle']['tables'][name] == source_db['tables'][name] for name in p.INVARIANTS) for snapshot in snapshots.values()),
        'final_actual_database_still_matches_stopped_snapshot_and_original_protected_payloads': all(current_db['tables'][name] == final_db['tables'][name] == source_db['tables'][name] for name in p.INVARIANTS),
        'source_primary_frozen_and_invalidation_remains_unresolved': expected['primary']['completed'] is True and expected['primary']['resolution'] == 'yes' and expected['primary']['counts'] == {'yes': 2, 'no': 1} and expected['invalidation']['completed'] is False and expected['invalidation']['resolution'] is None and expected['invalidation']['counts'] == {'yes': 1, 'no': 1},
        'real_folia_ready_has_only_product_and_verifier_with_unavailable_builtin_authority': any(item.get('plugin_sha256') == args.expected_sha and item.get('server_port') == 25604 and set(item.get('installedPlugins', [])) == {'StarChronicle', 'NationalCouncilFoliaFixture'} and item.get('noDatabaseAccess') is True and item.get('noFabricatedNation') is True and item.get('authorityProviders') == ['io.github.addxiaoyi.starchronicle.integration.StarCoreNationalVoteAuthority'] for item in groups.get('READY', [])),
        'four_actual_original_clients_spawn_in_the_successful_boot': all(any(entry['kind'] == 'SPAWN' and entry['value'].get('boot') == 1 and entry['value'].get('player') == who and entry['value'].get('uuid') == player for entry in cli) for who, player in cohort.items()),
        'actual_entity_owned_basic_permissions_never_grant_op_admin_or_nation': all(any(item.get('player') == who and item.get('uuid') == player and item.get('op') is False and item.get('use') is True and item.get('admin') is False and item.get('owned') is True and item.get('grantedNation') is False for item in groups.get('IDENTITY', [])) for who, player in cohort.items()),
        'both_actual_joined_national_instances_remain_available_to_all_four_viewers': all(any(item.get('player') == who and item.get('id') == original['instanceId'] and item.get('story') == p.STORIES[which] and len(item.get('members', {})) == 4 and set(item.get('members', {})) == set(cohort.values()) for item in groups.get('INSTANCE', [])) for who in cohort for which, original in expected.items()),
        'repeated_fresh_blank_nation_states_preserve_all_original_counts_revisions_and_evidence': states_match,
        'all_four_have_no_available_national_choices_in_both_stories': all(any(item.get('player') == who and item.get('story') == story and item.get('choices') == [] for item in groups.get('READ_ONLY_CHOICES', [])) for who in cohort for story in p.STORIES.values()),
        'all_four_vote_and_withdraw_api_requests_hit_actual_default_authority_denial': all(any(item.get('player') == who and item.get('story') == p.STORIES['invalidation'] and item.get('action') == action and 'SecurityException' in item.get('cause', '') and any(denial in item.get('cause', '') for denial in native_denials) for item in groups.get('EXPECTED_REJECTION', [])) for who in cohort for action in ['VOTE', 'WITHDRAW']),
        'actual_player_command_vote_and_withdraw_denials_are_retained': any(actual_direct.values()),
        'all_actual_primary_and_active_country_guis_are_readonly_and_show_source_counts': all(any(item.get('player') == who and ('National smoke primary' if which == 'primary' else 'National smoke invalidation') in title(item['window'].get('title')) and national_gui(item['window'], original, original['completed']) for item in views) for who in cohort for which, original in expected.items()),
        'actual_recorded_ballot_gui_preserves_each_original_revision_actor_status_and_authority': all(any(ledger_gui(item['window'], original) for item in views) for original in expected.values()) and all(parser_checks.values()) and all(prior_parser_checks.values()) and repair_observation.get('classification') == 'REPORTER_PARSER_FAILURE_NOT_PRODUCT_FAILURE' and repair_observation.get('observed_exit_code') == 1 and repair_observation.get('data_or_server_changed_to_fix') is False and 'transcribed' in repair_observation.get('observation', '').lower(),
        'fixture_gui_inspection_executes_on_actual_viewer_entity_owner': all(any(item.get('player') == who and item.get('owned') is True and ('国家议会' in item.get('title', '') or '已记录国家票' in item.get('title', '')) for item in groups.get('VIEW', [])) for who in cohort),
        'actual_history_and_evidence_inventory_packets_are_retained': all(any(label in title(item['window'].get('title')) for item in views) for label in ['世界编年史', '历史证据']),
        'world_history_survives_without_personal_or_current_country_fact_fabrication': all(any(item.get('player') == who and item.get('nationFactsAbsent') is True and item.get('facts', {}).get('past.resolution.world.national-smoke-primary/1/council') == 'yes' and not any(key.startswith(('past.nation.choice.', 'past.player.choice.', 'past.resolution.nation.', 'chronicle.history.nation.')) for key in item.get('facts', {})) for item in groups.get('RECOLLECTIONS', [])) for who in cohort),
        'all_four_honor_values_remain_the_original_seven': manifest['expected_honor'] == {who: '7' for who in cohort} and all(any(item.get('player') == who and item.get('expectedHonor') == '7' and item.get('facts', {}).get('player.honor') == '7' for item in groups.get('FATE', [])) for who in cohort),
        'successful_route_contains_no_hidden_fixture_or_client_failure': not groups.get('FAILED') and not any(entry['kind'] in {'ERROR', 'KICKED', 'ACTION_FAILED', 'IDENTITY_FAILED', 'TIMEOUT'} for entry in cli) and 'NATIONAL_FOLIA_FAILED' not in final_log,
        'actual_successful_client_and_server_exits_and_session_cleanup_are_normal': len(boots) == 1 and boots[0].get('boot') == 1 and boots[0].get('server_exit') == 0 and boots[0].get('client_exit') == 0 and bool(boots[0].get('finished_at')) and not boots[0].get('cleanup_errors') and 'folia.jar' in boots[0].get('server_command', []) and any(str(run) == part for part in boots[0].get('client_command', [])),
        'all_four_successful_clients_normally_disconnect': all(any(entry['kind'] == 'ENDED' and entry['value'].get('player') == who and entry['value'].get('reason') == 'national council acceptance complete' for entry in cli) for who in cohort),
        'actual_utf8_server_log_contains_normal_product_disable': '[StarChronicle] Disabling StarChronicle' in final_log,
        'failed_capacity_preflight_is_separate_with_actual_nonzero_client_exit': len(attempts) == 1 and capacity.get('server_pid') == 15924 and capacity.get('client_pid') == 4420 and capacity.get('server_exit') == 0 and capacity.get('client_exit') == 1 and capacity.get('classification') == 'FAILED_CAPACITY_PREFLIGHT_NOT_MULTIPLAYER_PASS' and attempts[0].get('server_pid') == 15924 and attempts[0].get('client_pid') == 4420 and attempts[0].get('server_exit') == 0 and attempts[0].get('client_exit') == 1,
        'all_four_original_preflight_admission_failures_and_seed_failure_remain_visible': not any(entry['kind'] == 'SPAWN' for entry in old_cli) and all(any(entry['kind'] == 'KICKED' and entry['value'].get('player') == who and 'server_full' in p.canonical(entry['value']) for entry in old_cli) for who in cohort) and any(entry.get('kind') == 'FAILED' and 'not online' in entry.get('value', {}).get('error', '') for entry in old_observations),
        'failed_node_cleanup_verified_exact_pid_command_before_bounded_force': verified_node.get('ProcessId') == 4420 and verified_node.get('Name', '').lower() == 'node.exe' and str(run).lower() in verified_node.get('CommandLine', '').lower() and 'national-council-folia-clients.cjs' in verified_node.get('CommandLine', '') and len(cleanup) == 2 and cleanup[0].get('exit_code') == 1 and cleanup[1].get('exit_code') == 0 and cleanup[1].get('command') == ['taskkill.exe', '/F', '/PID', '4420'],
        'capacity_headroom_repair_does_not_erase_the_stopped_preflight': capacity.get('server_capacity_before') == 4 and capacity.get('server_capacity_after') == 12 and current_capacity.get('max-players') == '12' and current_capacity.get('server-port') == '25604' and current_capacity.get('server-ip') == '127.0.0.1' and all(snapshots['capacity-preflight-stopped']['invariant_tables_unchanged'].values()),
    }
    summary = {'status': 'PASS' if all(checks.values()) else 'FAIL', 'generated_utc': datetime.now(timezone.utc).isoformat(),
               'plugin_sha256': args.expected_sha, 'fixture_sha256': manifest['fixture_sha256'], 'server_sha256': manifest['server_sha256'],
               'run_name': run.name, 'source_database_sha256': manifest['source_database_sha256'], 'checks': checks,
               'python_ledger_parser_checks': parser_checks,
               'reporter_wrap_repair': {'archived_original_script_sha256': prior_sha, 'prior_pure_function_checks': prior_parser_checks,
                                        'original_cli_stdout_saved_as_file': False, 'original_failed_summary_retained': False,
                                        'execution_observation_is_transcribed': True, 'observation': repair_observation},
               'compiler_log': {'raw_sha256': p.digest(evidence / 'fixture-compile-attempt1.log'), 'raw_encoding': compiler_encoding,
                                'utf8_derivative': 'national-council-folia-fixture-compile-attempt1-decoded-utf8.log'},
               'archive_encoding_repair_observation': archive_observation,
               'successful_harness_source_sha256': {name: p.digest(evidence / name) for name in ['national-council-folia-session-used.py', 'national-council-folia-clients-used.cjs']},
               'server_exit_codes': [boot.get('server_exit') for boot in boots], 'client_exit_codes': [boot.get('client_exit') for boot in boots],
               'preflight_attempts': attempts, 'capacity_preflight': capacity, 'expected_states': expected,
               'scope': 'Actual standalone Folia degradation over real stopped Paper national history. Failed admission preflight remains separate. No StarCore/full native authority or full-stack Folia compatibility claim.'}
    p.write_json(evidence / 'national-council-folia-summary.json', summary)
    failed = [name for name, passed in checks.items() if not passed]
    if failed:
        raise SystemExit('Actual Folia checks failed; no public PASS emitted: ' + ', '.join(failed))
    report = '\n'.join([
        '# Actual standalone Folia national-history degradation', '', 'Result: **PASS** for the exact tested artifact.', '',
        '- Product SHA-256: `' + args.expected_sha + '`.', '- Fixture SHA-256: `' + manifest['fixture_sha256'] + '`.',
        '- Actual accepted-run server exits: `' + str(summary['server_exit_codes']) + '`; client exits: `' + str(summary['client_exit_codes']) + '`.',
        '- Source stopped Paper checkpoint3 database: `' + manifest['source_database_sha256'] + '`.', '', '## Verified checks', ''
    ] + ['- ' + name.replace('_', ' ') + ': PASS.' for name in checks] + [
        '', '## Actual behavior and limits', '',
        'Four original real non-operator clients joined an actual standalone Folia server. Only StarChronicle and the test verifier were installed. No StarCore, native nation state, replacement national authority or supplied player nationality was used. Each freshly collected viewer had blank current nationality and no national voting authority.', '',
        'The actual normally stopped Paper checkpoint3 Chronicle database and its exact two national story files were copied without rewriting their canonical history. The completed primary retained its yes result, yes=2/no=1 country projection and original revisions/evidence. The unfinished invalidation instance retained yes=1/no=1 and the original INVALIDATED tombstone. Read-only viewing and denied vote/withdraw attempts did not invalidate or reattribute an old country ballot.', '',
        'Actual player inventory packets retained both country council pages, original country ballot ledgers, world history and evidence pages. Ledger lore is reconstructed at its six ordered field boundaries: 38-character continuation chunks are concatenated verbatim, and the complete actor UUID, complete authority payload, revision, status, choice and displayed UTC time must equal the copied source exactly. Sixteen Python positive/negative checks over captured inventory copies additionally reject damaged actor/authority prefixes and continuations, revisions, status and choices. These packets demonstrate real GUI state, not human visual approval. The world resolution remained readable; current-country and personal-choice facts were absent, while each player retained exactly seven prior honor. Protected national tables, pinned instances, history/catalog records and local reward payloads were identical before and after.', '',
        'This run verifies safe StarChronicle standalone Folia degradation over Paper-produced national receipts. It does not certify StarCore on Folia, an available national authority, combat or external quest/reward exactly-once delivery.', '',
        '## Reporter wrapping repair', '',
        'The first completed-run reporter invocation exited1 with only the recorded-ballot GUI check false. Its parser incorrectly required complete actor/authority fields on single physical lore lines. The original CLI stdout was not saved as a separate file, and its local failed summary was overwritten by the corrected reporter; neither is claimed to remain as raw failure evidence. An explicitly transcribed observation documents that execution, and the exact pre-fix reporter bytes are separately archived with SHA-256 ' + prior_sha + '.', '',
        'Only the archived pure title/ledger predicate functions were evaluated against the retained actual GUI packets; the old CLI/main was not rerun and did not overwrite any results. Its single-line predicate rejects both actual wrapped ledgers, while the corrected ordered-field reconstruction matches every complete field exactly and all sixteen corruption controls pass. No product, JAR, server, database or raw GUI packet was changed or rerun to repair this reporting-tool defect.', '',
        'An additional archive attempt failed after the product checks passed because the newly included raw javac log uses Windows cp936 rather than UTF-8. That archive-tool error is documented as a transcribed observation, not original stdout. The compiler log now remains byte-for-byte original alongside a separately labelled strict cp936-to-UTF8 readable derivative. The completed archive and every published hash were regenerated and checked; no runtime rerun or data change was involved.', '',
        '## Retained failed capacity preflight', '',
        'The first attempt admitted none of the four clients: all were rejected as server_full with max-players=4. Its offline seed command also failed. It is a failed capacity preflight and is not counted as multiplayer acceptance. The actual Folia server PID 15924 stopped normally with exit0. All client sockets had ended, but the old controller kept readline open; its exact Node PID 4420, process name and complete command were verified before bounded cleanup. The non-force cleanup attempt exited1; the subsequent explicit force cleanup exited0, and the controller process result remains exit1.', '',
        'The coordinator changed only stopped-runtime capacity from four to twelve to leave pending-login headroom, repaired controller/session cleanup, and retained the original preflight process/log/tool evidence. The database/world were not reset. The stopped preflight and successful path both retain exact protected-table comparisons with the original Paper checkpoint. Preparation and inspector snapshots alone were never presented as actual multiplayer passage.', ''
    ])
    (evidence / 'national-council-folia-report.md').write_text(report, encoding='utf-8')
    public = p.ROOT / 'docs/verification'
    public.mkdir(parents=True, exist_ok=True)
    published = []
    for name in required + ['national-council-folia-summary.json', 'national-council-folia-report.md']:
        source = evidence / name
        destination_name = name if name.startswith('national-council-folia-') else 'national-council-folia-' + name
        destination = public / destination_name
        if source.name == 'fixture-compile-attempt1.log':
            shutil.copy2(source, destination)
            (public / 'national-council-folia-fixture-compile-attempt1-decoded-utf8.log').write_text(sanitize(compiler_text), encoding='utf-8')
        elif source.suffix == '.log':
            destination.write_text(sanitize(text_logs(source)), encoding='utf-8')
        else:
            shutil.copy2(source, destination)
        published.append(destination)
    shutil.copy2(run / 'preparation.json', public / 'national-council-folia-preparation.json')
    shutil.copy2(evidence / 'source-paper-fixture-config.yml', public / 'national-council-folia-source-paper-fixture-config.yml')
    tool_names = ['prepare-national-council-folia.py', 'NationalCouncilFoliaFixture.java', 'NATIONAL-COUNCIL-FOLIA-RUN.md',
                  'national-council-folia-session.py', 'national-council-folia-clients.cjs', 'report-national-council-folia.py']
    for name in tool_names:
        shutil.copy2(p.ROOT / 'tools' / name, public / ('national-council-folia-tool-' + name))
    inventory = {'plugin_sha256': args.expected_sha, 'fixture_sha256': manifest['fixture_sha256'],
                 'source_preserved_sha256': manifest['source_preserved_sha256'],
                 'source_current_sha256': {name: p.digest(Path(name)) for name in manifest['source_preserved_sha256']},
                 'raw_evidence_sha256': {path.name: p.digest(path) for path in sorted(evidence.iterdir()) if path.is_file()},
                 'current_tool_sources_sha256': {name: p.digest(p.ROOT / 'tools' / name) for name in tool_names},
                 'published_evidence_sha256': {path.name: p.digest(path) for path in sorted(public.glob('national-council-folia-*')) if path.is_file() and path.name != 'national-council-folia-evidence-inventory.json'}}
    p.write_json(public / 'national-council-folia-evidence-inventory.json', inventory)
    print('PASS:', len(checks), 'actual standalone Folia degradation checks; product', args.expected_sha)


if __name__ == '__main__':
    main()
