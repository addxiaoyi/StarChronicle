"""Build the current-JAR Folia report from actual retained protocol/region/SQLite evidence."""
from pathlib import Path
from datetime import datetime
import json,re

root=Path(__file__).resolve().parents[1];out=root/'docs'/'verification'
prefix='expanded-folia-smoke'
snapshots={name:json.loads((out/f'{prefix}-{name}.json').read_text(encoding='utf-8')) for name in ['before-restart','after-complete','after-replay','restored']}
before=snapshots['before-restart'];after=snapshots['after-complete'];replay=snapshots['after-replay'];restored=snapshots['restored']
logs=[(out/f'{prefix}-{name}.log').read_text(encoding='utf-8') for name in ['first-start','restart','restored-start']]
clients=(out/f'{prefix}-clients.log').read_text(encoding='utf-8')
processes=json.loads((out/f'{prefix}-processes.json').read_text(encoding='utf-8'))
def instance(snapshot):
    return next(json.loads(row[5]) for row in snapshot['instances'] if row[1]=='folia-feature')
bi,ai,ri=map(instance,[before,after,restored])
def facts(snapshot):return {(owner,key):value for owner,key,value in snapshot['facts']}
af,rf=facts(after),facts(restored)
players=set(ai['members'])
source=lambda snapshot:next(row for row in snapshot['events'] if row[0]=='folia-feature:committed-1')
site_map=lambda snapshot:{row[0]:json.loads(row[2]) for row in snapshot['memory_sites']}
same_tables=lambda a,b:all(a[key]==b[key] for key in ['events','instances','outcomes','facts','memory_sites','story_triggers','story_trigger_receipts'])
offline_triggers=[row for row in before['story_triggers'] if row[2]=='folia-feature:offline-1' and row[3]=='world']
protocol=[]
for line in clients.splitlines():
    match=re.match(r'^\S+ (\S+) (\{.*)$',line)
    if match:
        try:protocol.append((match[1],json.loads(match[2])))
        except json.JSONDecodeError:pass
windows=[json.dumps(data.get('title',''),ensure_ascii=False) for event,data in protocol if event=='WINDOW']
views=[line for log in logs for line in log.splitlines() if 'FEATURE_VIEW ' in line]
site_lines=[line for log in logs for line in log.splitlines() if 'FEATURE_SITES ' in line]
expected_site='live-memory=[monument:INTERACTION, text:TEXT_DISPLAY, veteran:VILLAGER]'
checks={
    'approved_final_product_hash_all_boots':all(snapshot['plugin_sha256']=='abaadf4c4dd318d518687bafe2596f43e32c9f9a115b4074deeae9f37abc22df' for snapshot in snapshots.values()),
    'fresh_final_run_directory_all_boots':all(snapshot['run_directory']=='run/folia-final-smoke-abaadf4c' for snapshot in snapshots.values()),
    'real_folia_1_21_11_build_14_all_boots':all('Loading Folia 1.21.11-14-' in log for log in logs),
    'two_actual_region_tick_threads':all('initial 2 target thread(s)' in log for log in logs),
    'players_proven_in_different_owning_regions':'FEATURE_SEPARATION playerOwned=true otherOwned=false' in ''.join(logs),
    'explicit_standalone_scope_all_boots':all('STANDALONE MODE' in log for log in logs),
    'thirteen_validated_stories_all_boots':all('Loaded 13 validated stories' in log for log in logs),
    'two_real_clients_three_boots':sum(event=='SPAWN' for event,_ in protocol)==6,
    'shared_two_member_instance':len(bi['members'])==2 and players==set(bi['members']),
    'first_vote_persisted_before_stop':bi['node']=='vote' and len(bi['votes'])==1 and not bi['completed'],
    'first_vote_restored_before_second_vote':'node=vote members=2 votes=1 completed=false' in logs[1],
    'same_instance_completed_and_restored':bi['id']==ai['id']==ri['id'] and ai['completed'] and ri['completed'] and ai['node']==ri['node']=='end',
    'source_payload_hash_time_survived_restart':source(before)==source(after)==source(restored),
    'source_time_not_future':datetime.fromisoformat(json.loads(source(before)[4])['occurredAt'].replace('Z','+00:00'))<=datetime.fromisoformat(before['captured_utc']),
    'offline_event_committed_without_online_audience':'FEATURE_OFFLINE_SUBMITTED online=0 duplicate=false instances=[]' in logs[0] and any(row[0]=='folia-feature:offline-1' for row in before['events']) and not any(row[1]=='folia-offline' for row in before['instances']),
    'offline_trigger_durably_pending_before_restart':len(offline_triggers)==1 and json.loads(offline_triggers[0][4])['id']=='folia-offline',
    'offline_world_trigger_restored_for_real_players':'FEATURE_OFFLINE_INSTANCE' in logs[1] and 'node=vote members=2 votes=0 completed=false' in logs[1] and any(row[1]=='folia-offline' and row[3]==1 for row in restored['instances']),
    'offline_completion_and_titles_once':sum(row[1]=='FOLIA_OFFLINE_COMPLETED' for row in restored['events'])==1 and all(rf.get((p,'title.folia-offline-witness'))=='Offline Event Witness' for p in players),
    'offline_trigger_has_one_stable_consumption_receipt':len(offline_triggers)==1 and sum(row==[offline_triggers[0][1],'world'] for row in restored['story_trigger_receipts'])==1 and not any(row[2]=='folia-feature:offline-1' for row in restored['story_triggers']),
    'completed_replay_changed_no_table':same_tables(after,replay),
    'completed_state_and_receipts_survived_second_restart':same_tables(replay,restored),
    'one_completed_history_event':sum(row[1]=='FOLIA_FEATURE_COMPLETED' for row in after['events'])==1,
    'npc_reaction_receipt_once':sum(row[0].startswith('npc-reaction:') for row in after['outcomes'])==1,
    'exactly_nine_local_outcomes_done_once':len(after['outcomes'])==9 and all(row[1]=='DONE' for row in after['outcomes']),
    'npc_relationship_and_honor_persisted':all(af.get((p,'npc.folia-witness.reputation'))=='7' and af.get((p,'npc.folia-witness.trust'))=='9' and af.get((p,'player.honor'))=='3' and af.get((p,'title.folia-feature-witness'))=='Folia Feature Witness' for p in players) and af==rf,
    'npc_global_growth_position_persisted':af.get(('WORLD','npc.folia-witness.growth'))=='21' and af.get(('WORLD','npc.folia-witness.position'))=='history-witness' and rf.get(('WORLD','npc.folia-witness.growth'))=='21',
    'npc_dynamic_dialogue_selected_after_restore':'rep=7 trust=9 growth=21 position=history-witness dialogue=trusted' in logs[2],
    'memory_enabled_and_disabled_tombstones_persisted':all(set(site_map(s))=={'live-memory','removed-memory'} and site_map(s)['live-memory']['enabled'] and not site_map(s)['removed-memory']['enabled'] for s in snapshots.values()),
    'memory_real_owned_entities_at_chunk_edge':any('chunk=0,0 owned=true bystanders=1' in line and expected_site in line and 'removed-memory=' in line for line in site_lines),
    'memory_removed_entities_absent_after_both_restarts':all(any('chunk=0,0 owned=true bystanders=1' in line and expected_site in line and 'removed-memory=' not in line for line in log.splitlines()) for log in logs[1:]),
    'untagged_bystander_survived_removal_and_restarts':all('owned=true bystanders=1' in log for log in logs),
    'invalid_and_unauthorized_memory_sites_not_persisted':all('forbidden-memory' not in site_map(s) and 'invalid-memory' not in site_map(s) for s in snapshots.values()) and all(any(event=='SENT' and marker in data.get('message','') for event,data in protocol) for marker in ['forbidden-memory','invalid-memory']) and any(event=='MESSAGE' and data.get('player')=='B' and '管理权限' in data.get('text','') for event,data in protocol),
    'gui_history_evidence_npc_fate_and_story_packets':all(any(label in title for title in windows) for label in ['世界编年史','历史证据','人物档案','我的命运与历史荣誉','Folia Feature Verification']),
    'gui_presented_on_actual_player_owner':bool(views) and all('owned=true' in line for line in views),
    'monument_gui_contains_exact_source_evidence':any(event=='CLIENT_VIEW' and '历史证据' in json.dumps((data.get('window') or {}).get('title',''),ensure_ascii=False) and 'folia-feature:committed-1' in json.dumps(data.get('window',{}),ensure_ascii=False) for event,data in protocol),
    'real_history_book_open_packet':any(event=='BOOK' for event,_ in protocol),
    'shift_click_preserved_inventory_and_menu_item':any(event=='SHIFT_CHECK' and data.get('unchangedInventory') and data.get('slotStillPresent') for event,data in protocol),
    'real_monument_and_veteran_interactions':all(any(event=='INTERACTED' and data.get('kind')==kind for event,data in protocol) for kind in ['monument','veteran']),
    'native_scene_unavailable_and_action_rejected':'FEATURE_SCENE present=false' in ''.join(logs) and 'FEATURE_SCENE_ACTION rejected=true' in ''.join(logs) and any(event=='SENT' and data.get('message')=='/chronicle scene' for event,data in protocol) and any(event=='MESSAGE' and '暂无自有StarCore场景' in data.get('text','') for event,data in protocol),
    'standalone_role_remained_unassigned':any('FEATURE_FATE' in line and 'role=unassigned' in line and 'honor=3' in line for log in logs for line in log.splitlines()),
    'sqlite_integrity_ok_all_captures':all(s['integrity']=='ok' for s in snapshots.values()),
    'no_folia_thread_violation_or_unhandled_plugin_exception':not any(re.search(r'(?i)thread check failed|not on an entity|not on a region|Asynchronous .+!|UnsupportedOperationException|Error occurred while|Exception in thread|FEATURE_\w+_FAILED|FEATURE_FIXTURE_FAILED',log) for log in logs),
    'all_server_and_client_processes_exit_zero':processes.get('server_exit_codes')==[0,0,0] and processes.get('client_exit_codes')==[0,0,0],
    'all_normal_shutdowns_observed':all('Saved all player data' in log and 'All RegionFile I/O tasks to complete' in log for log in logs),
    'no_client_error_or_bounded_timeout':not any(event in ['ERROR','TIMEOUT','ACTION_FAILED'] for event,_ in protocol),
}
date=restored['captured_asia_shanghai'][:10]
summary={'scope':'StarChronicle standalone on real Folia; NOT StarCore full-stack Folia certification','date':date,'plugin_sha256':after['plugin_sha256'],'server':'Folia 1.21.11 build 14','heap_max_mb':768,'region_threads':2,'server_exit_codes':processes['server_exit_codes'],'client_exit_codes':processes['client_exit_codes'],'checks':checks,'passed':sum(checks.values()),'total':len(checks)}
(out/f'{prefix}-summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
rows='\n'.join(f'| {name} | {"PASS" if ok else "FAIL"} |' for name,ok in checks.items())
report=f'''# Expanded real Folia standalone acceptance — {date}

**Scope: current StarChronicle JAR in explicit standalone mode (`require-starcore: false`), not StarCore full-stack Folia certification.** No StarCore descriptor or authority service was fabricated. Upstream NarrativeEngine execution stayed unavailable on Folia.

- Product SHA-256: `{after['plugin_sha256']}`.
- Official Folia 1.21.11 build 14, commit `529aabc13852021f6081f8a6069959a449c33785`, Java 21, Windows Server 2022.
- Official server SHA-256: `f52c408490a0225611e67907a3ca19f7e6da2c6bc899e715d5f46844e7103c39`.
- Isolated loopback port 25578, two actual Minecraft 1.21.11 protocol clients, two region tick threads, `-Xmx768m`. All three server starts were serial; all three server and client exits were observed as 0.
- The eleven bundled stories plus two clearly labelled verification stories were validated. A test-only logical NPC profile reacts solely to actual completion of the shared verification, never to a fabricated nation, role or historical war.

First boot: the two real clients were separated into distant regions; the fixture submitted an evidenced test event through `ChronicleService`, and the service collected its own fresh trusted contexts. A voted once. Actual player commands created two memory sites at the edge of chunk 0,0, then disabled one. The production code spawned the monument hitbox, text display and veteran. A region-owned inspector read their real persistent markers and verified entity ownership. An unrelated tagged test armor stand survived reconciliation. Invalid-source and non-admin placement attempts left no registry row. Both clients then disconnected normally. With the online count verified as zero, the fixture submitted an additional event for the same previously observed, persisted real cohort. The source event committed while no corresponding story instance existed. No trusted PlayerContext was constructed by the fixture.

Second boot: the original instance, both members and A's single vote were restored; the enabled site's three entities were unique, the disabled site's entities were absent, and the unrelated armor stand remained. Actual cohort members joining also restored the separate deferred WORLD trigger from the event committed while everyone was offline. The two participants completed that story with one local title/history result. B's vote then completed the original shared instance. One durable NPC reaction changed each participant's reputation/trust and the character's growth/position; each participant received one local honor/title result. Replays changed no recorded table. Third boot: both completed instances, receipts, relationships, titles, growth, enabled site and disabled tombstone survived another normal restart without repetition.

Actual client commands and protocol clicks exercised history list/evidence, the shared story, NPC dossier, fate menu and written history book. The monument opened its exact stored source evidence; the veteran opened NPC dialogue. Shift-click rollback preserved the client inventory and menu item. A server-side player-owner inspector also verified the open GUI. Standalone fate remained `unassigned`; no role was invented to force an identity story. Native scene viewing returned empty and an attempted scene action was refused.

Expected negative checks remain visible in the retained logs: the deliberately nonexistent memory source produced one `Player operation failed: Chronicle transaction failed` warning; the non-admin placement was denied; completed-choice repeats were refused; and the unavailable native scene action returned a security failure. These checks created no unauthorized memory row or repeated reward and are not counted as unhandled Folia thread failures.

| Check | Result |
| --- | --- |
{rows}

Result: **{sum(checks.values())}/{len(checks)} checks passed**.

Evidence in this directory uses `{prefix}-*`: three complete sanitized server logs, protocol-client log, four read-only SQLite snapshots, actual process exits, and this machine-readable summary. Snapshots include the durable trigger inbox and its immutable consumption receipts; only the polling `checked` counter is excluded from result comparison because it records liveness rather than a game result. Setup/fixture/client/report tools live under `tools/`; runtime files stay in ignored `{after['run_directory']}`.

Limits: protocol automation is not human visual acceptance. This standalone run does not certify upstream StarCore modules, real national mutations, provider-specific Citizens/FancyNpcs/Adyeshach behavior, external reward settlement, crash/power-loss durability, or network AI. See `../FOLIA-COMPATIBILITY.md` for the independently observed upstream loader blocker and the bounded owner-thread prerequisite patch. The older 2026-11-19 evidence is retained separately and is not relabelled as current-JAR acceptance.
'''
(out/f'{prefix}-report.md').write_text(report,encoding='utf-8')
print(json.dumps(summary,ensure_ascii=False,indent=2))
if not all(checks.values()):raise SystemExit(1)
