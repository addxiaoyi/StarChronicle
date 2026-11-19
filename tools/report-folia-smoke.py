"""Generate the bounded standalone Folia result from retained logs and SQLite snapshots."""
from pathlib import Path
import json, re

root = Path(__file__).resolve().parents[1]
out = root / 'docs' / 'verification'
before = json.loads((out/'folia-smoke-before-restart.json').read_text(encoding='utf-8'))
after = json.loads((out/'folia-smoke-after-restart.json').read_text(encoding='utf-8'))
replay = json.loads((out/'folia-smoke-after-replay.json').read_text(encoding='utf-8'))
first = (out/'folia-smoke-first-start.log').read_text(encoding='utf-8')
second = (out/'folia-smoke-restart.log').read_text(encoding='utf-8')
clients = (out/'folia-protocol-clients.log').read_text(encoding='utf-8')
bi = json.loads(before['instances'][0][5])
ai = json.loads(after['instances'][0][5])
checks = {
    'real_folia_1_21_11_build_14': all('Loading Folia 1.21.11-14-' in log for log in (first,second)),
    'real_region_scheduler': all('Initialised EDF Folia scheduler' in log for log in (first,second)),
    'plugin_enabled_both_boots': all('[StarChronicle] Loaded 4 validated stories' in log for log in (first,second)),
    'standalone_explicitly_reported': all('STANDALONE MODE' in log for log in (first,second)),
    'two_real_protocol_clients_each_boot': clients.count('CLIENT_SPAWNED FoliaChronicleA') == 2 and clients.count('CLIENT_SPAWNED FoliaChronicleB') == 2,
    'two_member_shared_instance': len(bi['members']) == 2 and len(ai['members']) == 2,
    'one_vote_persisted_before_restart': bi['node']=='vote' and len(bi['votes'])==1 and not bi['completed'],
    'one_vote_restored_after_restart': 'node=vote members=2 votes=1 completed=false' in second,
    'same_instance_completed_after_second_vote': ai['id']==bi['id'] and ai['node']=='end' and ai['completed'],
    'duplicate_event_replay_rejected': second.count('FOLIA_SUBMITTED duplicate=true instances=[]') >= 4,
    'stable_source_event_hash': next(e for e in before['events'] if e[0].startswith('folia-smoke:'))[:4] == next(e for e in after['events'] if e[0].startswith('folia-smoke:'))[:4],
    'one_history_and_two_title_receipts': len(after['events'])==2 and len(after['outcomes'])==3 and all(o[1]=='DONE' for o in after['outcomes']) and len(after['facts'])==2,
    'completed_replay_did_not_add_receipts': after==replay,
    'sqlite_integrity_ok': all(s['integrity']=='ok' for s in (before,after,replay)),
    'status_validate_reload_commands': all('StarCore=false, stories=4, workerQueue=0' in log and '验证通过: 4 条剧情' in log and '已重载 4 条剧情' in log for log in (first,second)),
    'no_folia_thread_violation': not any(re.search(r'(?i)thread check failed|not on an entity|not on a region|Asynchronous .+!|UnsupportedOperationException|Ticking .+Exception', log) for log in (first,second)),
    'normal_server_shutdowns': all('Saved all player data' in log and 'All RegionFile I/O tasks to complete' in log for log in (first,second)),
    'clients_closed_normally': clients.count('CLIENT_ENDED')==4 and 'CLIENT_ERROR' not in clients and 'CLIENT_TEST_TIMEOUT' not in clients,
}
result = {'scope':'StarChronicle standalone only; NOT StarCore full-stack Folia certification', 'date':'2026-11-19', 'server':'Folia 1.21.11 build 14', 'artifact_sha256':before['plugin_sha256'], 'first_server_exit_code':0, 'restart_server_exit_code':0, 'first_client_exit_code':0, 'restart_client_exit_code':0, 'checks':checks, 'passed':sum(checks.values()), 'total':len(checks)}
(out/'folia-smoke-summary.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
rows='\n'.join(f'| {name} | {"PASS" if ok else "FAIL"} |' for name,ok in checks.items())
report=f'''# Real Folia standalone verification — 2026-11-19

**Scope: StarChronicle standalone (`require-starcore: false`), not StarCore full-stack Folia certification.** The upstream StarCore artifact was deliberately absent. Its unsupported loader flag was not modified. Upstream NarrativeEngine remains disabled on Folia pending the separate compatibility work.

- Official Folia 1.21.11 build 14, commit `529aabc13852021f6081f8a6069959a449c33785`, Java 21.0.13.0.2+1, Windows Server 2022.
- Official Folia JAR SHA-256: `f52c408490a0225611e67907a3ca19f7e6da2c6bc899e715d5f46844e7103c39`.
- Product JAR SHA-256: `{before['plugin_sha256']}`.
- Isolated loopback server `127.0.0.1:25578`, offline test clients, RCON/query disabled; only StarChronicle and a test fixture installed.
- Both server invocations and both automated client invocations exited normally with code 0. No test server from this verification remains running.
- Three bundled stories plus one isolated test story were loaded. The test story uses WORLD scope, quorum 2, one local history effect, and one local title effect for each participant.

Two actual Minecraft 1.21.11 protocol clients connected. The test-only fixture submitted a fixed-ID `WorldEvent` through the published Bukkit `ChronicleService`; production code captured fresh player contexts on the owning entity scheduler. The fixture did not construct trusted contexts or nation membership. A voted once, the server stopped normally, the same players reconnected, and the shared instance restored with its original ID, members and single vote. B's vote completed it. Replaying the source event four times and repeating the completed choice did not add any event, title fact or outcome receipt.

The completed-choice repeat was intentionally refused with `IllegalStateException` and logged by the fixture as `FOLIA_VOTE_FAILED`. This is an expected negative check, not a Folia thread violation. The first shutdown also retained two host warnings, `Failed to flush log messages before plugin unload`; shutdown still completed and the state/SQLite integrity checks passed. The second shutdown did not emit those warnings.

| Check | Result |
| --- | --- |
{rows}

Result: **{sum(checks.values())}/{len(checks)} checks passed**.

Evidence: `folia-smoke-first-start.log`, `folia-smoke-restart.log`, `folia-protocol-clients.log`, `folia-smoke-before-restart.json`, `folia-smoke-after-restart.json`, `folia-smoke-after-replay.json`, `folia-official-build.json`, and `folia-smoke-summary.json` in this directory. The fixture sources and reproducible setup scripts are under `tools/`.

Limits: these are automated protocol clients, not visual or human GUI acceptance. This run proves the recorded standalone API/rule/state/history paths on real Folia. It does not prove NPC-provider behavior, the newly added journal/world-memory/fate features, all StarCore modules, cross-region boss rendering, real nation authority mutations, crash/power-loss durability, or network AI behavior.
'''
(out/'folia-smoke-report.md').write_text(report,encoding='utf-8')
print(json.dumps(result,ensure_ascii=False,indent=2))
if not all(checks.values()):raise SystemExit(1)
