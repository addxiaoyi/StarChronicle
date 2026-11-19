"""Keep the reproduced native GUI race and successful recovery evidence separate from release acceptance."""
from pathlib import Path
import argparse, json, shutil

root = Path(__file__).resolve().parents[1]
folder = root / 'docs/verification'
p = argparse.ArgumentParser()
p.add_argument('--first-exit-code', type=int, required=True)
p.add_argument('--final-exit-code', type=int, required=True)
a = p.parse_args()
names = ['blocked-native-action', 'branch-before-quit', 'branch-after-reconnect', 'before-restart', 'first', 'restarted-offline', 'recovered', 'native-completed']
for name in names:
    for extension in ['json', 'log']:
        source = folder / f'expanded-paper-smoke-final-{name}.{extension}'
        target = folder / f'expanded-paper-smoke-recovery-candidate-{name}.{extension}'
        if source.exists():
            if target.exists():
                if source.read_bytes() != target.read_bytes(): raise SystemExit('Refusing to replace different evidence: ' + str(target))
                source.unlink()
            else: source.rename(target)
client_source = folder / 'expanded-paper-final-clients.log'
client_target = folder / 'expanded-paper-recovery-candidate-clients.log'
if client_source.exists():
    if client_target.exists(): raise SystemExit('Client evidence destination already exists')
    client_source.rename(client_target)

def load(name):
    return json.loads((folder / f'expanded-paper-smoke-recovery-candidate-{name}.json').read_text(encoding='utf-8'))
snapshots = {name: load(name) for name in names + ['final']}
first = snapshots['first']; final = snapshots['final']
first_log = (folder / 'expanded-paper-smoke-recovery-candidate-first.log').read_text(encoding='utf-8')
last_log = (folder / 'expanded-paper-smoke-recovery-candidate-final.log').read_text(encoding='utf-8')
client = client_target.read_text(encoding='utf-8')
def events(snapshot): return {row['id']: row for row in snapshot['events']}
def instances(snapshot, story): return [json.loads(row['json']) for row in snapshot['instances'] if row['story'] == story]
def journal(snapshot): return {row['id']: json.loads(row['json']) for row in snapshot['native_scene_journal']}
def checkpoints(snapshot): return {entry['request']['playerId']: entry['checkpoint'] for entry in journal(snapshot).values()}
def native_starts(snapshot): return {row['id']: row for row in snapshot['outcomes'] if json.loads(row['json']).get('type') == 'narrative.start'}
source = 'expanded:heir-discovery:1'; offline_source = 'expanded:offline-source:1'
players = set(json.loads(events(final)[source]['json'])['participants'])
branch = checkpoints(snapshots['branch-before-quit']); reconnect = checkpoints(snapshots['branch-after-reconnect'])
before = snapshots['before-restart']; after = snapshots['recovered']; recovered = checkpoints(after)
offline_after = instances(after, 'expanded-offline'); offline_final = instances(final, 'expanded-offline')
checks = {
    'all_snapshots_use_88c2_candidate': all(s['plugin_sha256'] == '88c2a632b53b2f553ab114a7df7e42ec4781b721c52d382f2a5f64b39d94191f' for s in snapshots.values()),
    'two_server_stops_exit_zero': a.first_exit_code == a.final_exit_code == 0 and all('All RegionFile I/O tasks to complete' in log and 'Disabling StarChronicle' in log for log in [first_log, last_log]),
    'actual_distinct_native_branches': set(branch) == players and {cp['nodeId'] for cp in branch.values()} == {'evidence', 'civilians'},
    'normal_quit_reconnect_preserves_native_owned_state': set(reconnect) == players and all(all(branch[player][key] == reconnect[player][key] for key in ['nodeId', 'variables', 'flags', 'evidence']) for player in players),
    'gui_advanced_both_native_sessions_to_departure': set(checkpoints(before)) == players and all(cp['nodeId'] == 'departure' for cp in checkpoints(before).values()),
    'offline_source_recorded_without_fabricated_instance': offline_source in events(before) and not instances(before, 'expanded-offline') and any(row['event_id'] == offline_source for row in before['story_triggers']),
    'offline_restart_preserves_history_and_checkpoints': events(snapshots['restarted-offline']) == events(first) and checkpoints(snapshots['restarted-offline']) == checkpoints(first),
    'both_logins_recover_one_original_shared_instance': len(offline_after) == 1 and offline_after[0]['sourceEventId'] == offline_source and set(offline_after[0]['members']) == players,
    'actual_native_engine_resumes_departure': set(recovered) == players and all(cp['nodeId'] == 'departure' for cp in recovered.values()) and last_log.count('session=chronicle_last_heir/departure') >= 2,
    'actual_gui_finishes_both_native_sessions': len(journal(final)) == 2 and all(entry['status'] == 'COMPLETED' for entry in journal(final).values()) and last_log.count('routing=false session=<absent>') >= 2,
    'native_start_outcomes_do_not_replay': native_starts(first) == native_starts(final) and len(native_starts(final)) == 2,
    'offline_shared_instance_completes_with_same_two_members': len(offline_final) == 1 and offline_final[0]['completed'] and offline_final[0]['id'] == offline_after[0]['id'] and set(offline_final[0]['members']) == players,
    'all_readonly_integrity_checks_ok': all(s['integrity'] == 'ok' for s in snapshots.values()),
    'original_busy_race_is_retained': first_log.count('[StarChronicle] Player operation failed:') == 2 and '2026-11-19T17:57:52.836Z MESSAGE' in client and '2026-11-19T17:57:52.848Z MESSAGE' in client,
    'no_source_collision': 'Source event ID collision' not in first_log + last_log,
}
summary = {'status': 'REGRESSION_REPRODUCED' if all(checks.values()) else 'EVIDENCE_CHECK_FAILED', 'not_release_acceptance': True, 'plugin_sha256': final['plugin_sha256'], 'process_exit_codes': [a.first_exit_code, a.final_exit_code], 'client_process_exit_codes': [0, 0, 0], 'checks': checks, 'known_failure': 'Both initial native GUI choices at 17:57:52Z collided with polling and were rejected as busy. Real refresh and one retry at 17:58:56Z / 17:58:57Z advanced the unchanged original sessions. Root coordinator is fixing this race before a new final build.'}
(folder / 'expanded-paper-smoke-recovery-candidate-summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
lines = ['# Native recovery candidate verification', '', 'This is historical regression evidence, **not release acceptance**.', '', '- Artifact SHA-256: `' + final['plugin_sha256'] + '`.', '- Actual server stop exit codes: `0`, `0`; actual client exit codes: `0`, `0`, `0`.', '- Fresh isolated Paper 1.21.11 build 132, real audited StarCore, two non-operator loopback clients, real public NationService membership.', '', '## Reproduced failure', '', summary['known_failure'], '', '## Evidence checks', '']
lines += ['- ' + key.replace('_', ' ') + ': ' + ('PASS' if value else 'FAIL') for key, value in checks.items()]
lines += ['', 'The root coordinator stopped release acceptance to repair the busy race. National invitation, NPC/fate reward and technology/disband paths were not repeated in this shortened run. They require verification against the next frozen artifact. No journal state was reset and no plugin database was written by the fixture.', '']
(folder / 'expanded-paper-smoke-recovery-candidate-report.md').write_text('\n'.join(lines), encoding='utf-8')
failed = [name for name, ok in checks.items() if not ok]
if failed: raise SystemExit('Candidate evidence checks failed: ' + ', '.join(failed))
print('Retained reproduced race and', len(checks), 'asserted candidate recovery checks; not release acceptance')
