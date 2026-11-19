"""Assert the recorded candidate run without presenting its reproduced regression as release PASS."""
from pathlib import Path
import argparse,json

root=Path(__file__).resolve().parents[1];folder=root/'docs/verification'
p=argparse.ArgumentParser();p.add_argument('--exit-code',type=int,required=True);a=p.parse_args()
snapshot=json.loads((folder/'expanded-paper-smoke-candidate.json').read_text(encoding='utf-8'))
denied=json.loads((folder/'expanded-paper-smoke-denied.json').read_text(encoding='utf-8'))
before=json.loads((folder/'expanded-paper-smoke-before-stop.json').read_text(encoding='utf-8'))
log=(folder/'expanded-paper-smoke-candidate.log').read_text(encoding='utf-8')
client=(folder/'expanded-paper-clients.log').read_text(encoding='utf-8')
events=[json.loads(row['json']) for row in snapshot['events']]
instances=[json.loads(row['json']) for row in snapshot['instances']]
facts={(row['owner'],row['key']):row['value'] for row in snapshot['facts']}
source=next(event for event in events if event['id']=='expanded:heir-discovery:1')
players=set(source['participants'])
outcomes=[(row,json.loads(row['json'])) for row in snapshot['outcomes']]
authority=[row for row,data in outcomes if data.get('type')=='authority']
windows=[]
for line in client.splitlines():
    parts=line.split(' ',2)
    if len(parts)==3 and parts[1]=='WINDOW':
        title=json.loads(parts[2])['title'];windows.append(title.get('value','') if isinstance(title,dict) else str(title))
types={kind:[event for event in events if event['type']==kind] for kind in ['NATION_FOUNDED','TECHNOLOGY_BREAKTHROUGH','NATION_DESTROYED','HEIR_RESTORATION_SUPPORTED']}
checks={
    'artifact_manifest_matches_tested_jar':snapshot['plugin_sha256']==snapshot['artifacts']['starchronicle'],
    'server_stopped_normally_exit_zero':a.exit_code==0 and 'All RegionFile I/O tasks to complete' in log and 'Disabling StarChronicle' in log,
    'two_actual_non_op_nation_founders':len(players)==2 and log.count('founder=true')==2 and log.count('ally=false op=false')==2,
    'all_three_native_scenes_auto_registered':'EXPANDED_SCENE_CAPABILITIES allThreeInstalled=true' in log,
    'both_native_sessions_started_at_actual_arrival':log.count("started for player ExpandedCore")==3 and log.count("at node 'arrival'")==2,
    'gui_selected_distinct_native_branches':'node=evidence choices=[]' in log and 'node=civilians choices=[]' in log,
    'native_sessions_reached_departure_and_ended':log.count('node=departure choices=[]')==2 and log.count('present=false')>=3,
    'source_replay_was_deduplicated':'EXPANDED_NATIVE_SUBMITTED duplicate=true instances=[]' in log,
    'shared_campaign_completed_with_two_members':any(i['definition']['id']=='last-heir' and i['completed'] and len(i['members'])==2 for i in instances),
    'exactly_one_shared_restoration_history':len(types['HEIR_RESTORATION_SUPPORTED'])==1,
    'per_player_honor_and_npc_relationships_persist':all(facts.get((player,'player.honor'))=='15' and facts.get((player,'npc.ancient-heir.reputation'))=='25' and facts.get((player,'npc.ancient-heir.trust'))=='12' for player in players),
    'npc_global_growth_applied_once':facts.get(('WORLD','npc.ancient-heir.growth'))=='20' and facts.get(('WORLD','npc.ancient-heir.position'))=='council-speaker',
    'no_authority_outcome_when_permission_denied':not any(json.loads(row['json']).get('type')=='authority' for row in denied['outcomes']) and 'EXPANDED_INVITE_CHOICE_REJECTED' in log,
    'one_native_invitation_outcome_committed':len(authority)==1 and authority[0]['status']=='DONE' and 'EXPANDED_INVITE_NATIVE pending=[' in log and 'relation=NEUTRAL' in log,
    'same_admitted_outcome_coalesced_after_commit':'EXPANDED_INVITE_DUPLICATE result=APPLIED' in log and 'originalAlreadyCommitted=true' in log,
    'two_native_foundations_imported':len(types['NATION_FOUNDED'])==2,
    'two_real_unlocks_observed_without_fake_actor':len(types['TECHNOLOGY_BREAKTHROUGH'])==2 and all(e['facts'].get('time_basis')=='OBSERVED' and not e['participants'] and 'observationInterval=' in e['evidence'] for e in types['TECHNOLOGY_BREAKTHROUGH']),
    'real_disband_observed_once_without_fake_time':len(types['NATION_DESTROYED'])==1 and types['NATION_DESTROYED'][0]['facts'].get('time_basis')=='OBSERVED' and 'exact mutation time and actor unknown' in types['NATION_DESTROYED'][0]['evidence'] and 'EXPANDED_NATION_DISBANDED' in log,
    'observation_journal_fully_acked':len(snapshot['observation_ingress'])==3 and all(row['status']=='ACKED' for row in snapshot['observation_ingress']),
    'real_native_npc_fate_history_and_evidence_gui_packets':all(any(label in title for title in windows) for label in ['原生场景','人物档案','我的命运','世界编年史','历史证据']),
    'readonly_database_integrity_ok':snapshot['integrity']=='ok' and denied['integrity']=='ok',
    'normal_stop_preserved_committed_events':snapshot['events']==before['events'],
    'no_chronicle_runtime_warning_or_collision':'Source event ID collision' not in log and not any('[StarChronicle]' in line and (' WARN]' in line or ' ERROR]' in line) for line in log.splitlines()),
}
issue_start=log.find('script=chronicle_siege_embers node=witness')
regression=issue_start>=0 and '(saved=false)' in log[issue_start:] and 'EXPANDED_SCENE player=A present=false' in log[issue_start:]
summary={'status':'CANDIDATE_EVIDENCE_WITH_KNOWN_REGRESSION','plugin_sha256':snapshot['plugin_sha256'],'process_exit_code':a.exit_code,'checks':checks,'known_regressions':{'normal_disconnect_loses_unfinished_native_session':regression},'not_release_acceptance':True}
(folder/'expanded-paper-smoke-candidate-summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
failed=[name for name,passed in checks.items() if not passed]
if failed:raise SystemExit('Candidate evidence assertions failed: '+', '.join(failed))
if not regression:raise SystemExit('Expected native disconnect regression was not demonstrated by the archived log')
lines=['# Expanded Paper / real StarCore candidate evidence','','This run verifies the listed capabilities on the candidate artifact and records an unfinished-native-session regression. It is **not final release acceptance** and cannot cover a subsequently changed JAR.','','- Tested UTC interval: 2026-11-19 16:41–16:54 (2026-11-20 00:41–00:54 Asia/Shanghai).','- Paper 1.21.11 build 132, Java 21.0.13, real audited StarCore f769e627 baseline.','- Only `127.0.0.1:25579`, isolated `run/expanded-paper-smoke`, fresh world and databases. Two actual mineflayer offline loopback clients, both non-operators.','- Nation creation, technology unlock and founder disband use the real public services. The fixture grants explicit Bukkit permission attachments for the invitation test and never edits either plugin database or supplies a fabricated `PlayerContext`.','- The ancient-heir test signal is explicitly labeled test-only, with actual connected player UUIDs and service-read national membership. The invitation definition is isolated test YAML populated with real nation IDs.','- Tested plugin SHA-256: `'+snapshot['plugin_sha256']+'`.','- Server console `stop` completed world saving and the observed process exit code was `'+str(a.exit_code)+'`. Both client batches quit normally.','','## Checked capabilities','']
lines+=['- '+name.replace('_',' ')+': PASS.' for name in checks]
lines+=['','## Reproduced regression','','After the first two native scenes completed normally, a second real technology unlock triggered a separate test story referencing the already automatically installed `chronicle_siege_embers` script. The actual native session was visible at `witness`. Both clients then disconnected normally. StarCore logged the unfinished session destroyed with `saved=false`; after the same UUID reconnected, `ChronicleService.scene` returned empty. This is retained as defect evidence. It is not reclassified as passing restoration, and the fixture did not restart the scene or rewrite native state to hide it.','','The root implementation is being revised separately for deferred audience handling and safe native continuation. Any new artifact requires its own fingerprints and affected-path verification.','','## Evidence','','- `expanded-paper-smoke-candidate.log`: sanitized actual server log; complete credential-related lines are redacted.','- `expanded-paper-clients.log`: real login, commands, inventory windows, item components, GUI clicks and normal disconnect/reconnect packets. Protocol evidence is not a claim of human visual inspection.','- `expanded-paper-smoke-denied.json`: read-only SQLite capture after the denied choice; no authority outcome exists.','- `expanded-paper-smoke-permitted.json`: read-only capture after actual invitation, showing the unique DONE outcome.','- `expanded-paper-smoke-before-stop.json` and `expanded-paper-smoke-candidate.json`: committed event/fact/instance/outcome/journal evidence and integrity checks.','- `tools/prepare-expanded-paper-smoke.py`, `ExpandedPaperFixture.java`, `expanded-paper-clients.cjs`, `inspect-expanded-paper-smoke.py` reproduce the isolated test setup and evidence capture.','','## Boundaries','','This candidate run does not test a whole server restart, actual war combat, third-party NPC plugin packets, designated-capital authority, real money/item rewards, or AI services. Those must not be inferred from these results. NPC state and all descriptions worked without an AI provider. Independent upstream/platform warnings include Windows dungeon-directory durability failure, optional ProtocolLib absence, native-profiler fallback, offline-mode notices and leaderboard refresh timing. They remain in the sanitized evidence; they did not block the verified national/narrative services.','']
(folder/'expanded-paper-smoke-candidate-report.md').write_text('\n'.join(lines),encoding='utf-8')
print('Asserted',len(checks),'candidate capabilities and retained the native normal-disconnect regression; not final release PASS.')
