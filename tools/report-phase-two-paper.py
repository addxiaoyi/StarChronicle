"""Assert phase-two real-client evidence for exactly one coordinator-frozen artifact.
Missing stages, failed operations or an old artifact can never produce a PASS report."""
from pathlib import Path
import argparse,json,re

ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'docs/verification'
p=argparse.ArgumentParser();p.add_argument('--run-name',default='phase-two-paper-release-smoke');p.add_argument('--expected-plugin-sha256',required=True);p.add_argument('--server-exit-code',type=int,action='append',required=True);p.add_argument('--client-exit-code',type=int,action='append',required=True);a=p.parse_args()
if not re.fullmatch(r'phase-two-paper-[a-z0-9-]{1,40}',a.run_name):raise SystemExit('Invalid isolated run name')
if not re.fullmatch(r'[a-f0-9]{64}',a.expected_plugin_sha256):raise SystemExit('Invalid frozen fingerprint')
names=['before-first-stop','boot1-final','boot2-offline','boot2-recovered','late-member','invite-denied','invite-permitted','boot2-completed','boot2-final','boot3-offline','boot3-final']
stages={name:json.loads((OUT/f'phase-two-paper-{name}.json').read_text(encoding='utf-8')) for name in names}
logs=[(OUT/f'phase-two-paper-boot{i}-final.log').read_text(encoding='utf-8') for i in [1,2,3]]
client=(OUT/'phase-two-paper-clients.log').read_text(encoding='utf-8');server='\n'.join(logs)
manifest=json.loads((ROOT/'run'/a.run_name/'artifact-sha256.json').read_text(encoding='utf-8'))
records=[]
for line in server.splitlines():
    match=re.search(r'PHASE2_([A-Z_]+) (\{.*\})$',line)
    if match:records.append((match.group(1),json.loads(match.group(2))))
protocol=[]
for line in client.splitlines():
    parts=line.split(' ',2)
    if len(parts)==3:protocol.append((parts[1],json.loads(parts[2])))
def entries(kind):return [record for event,record in records if event==kind]
def events(snapshot):return {row['id']:row for row in snapshot['events']}
def payload(row):return json.loads(row['json'])
def instances(snapshot,story):return [payload(row) for row in snapshot['instances'] if row['story']==story]
def relevant_instances(snapshot):return {row['id']:row for row in snapshot['instances'] if row['story'] in ['last-heir','heir-legacy']}
def facts(snapshot):return {(row['owner'],row['key']):row['value'] for row in snapshot['facts']}
def source_outcomes(snapshot):return {row['id']:row for row in snapshot['outcomes'] if payload(row).get('type')=='narrative.start'}
def authority(snapshot):return [row for row in snapshot['outcomes'] if payload(row).get('type')=='authority']
def traces(snapshot):return {row['event_id']:row for row in snapshot['event_causes']}
def cause(snapshot,event,parent,root,depth,path):
    value=traces(snapshot).get(event)
    return value is not None and value['parent_id']==parent and value['root_id']==root and value['depth']==depth and json.loads(value['story_path'])==path
def nations(snapshot):
    props={row['property_key']:row['property_value'] for row in snapshot['core_nation_state']};result={}
    for key,value in props.items():
        match=re.fullmatch(r'(nation\.\d+\.)id',key)
        if match:
            prefix=match.group(1);result[value]={'founder':props.get(prefix+'founderId'),'members':{val for name,val in props.items() if re.fullmatch(re.escape(prefix)+r'member\.\d+\.id',name)}}
    return result
first=stages['before-first-stop'];recovered=stages['boot2-recovered'];late=stages['late-member'];completed=stages['boot2-completed'];final=stages['boot3-final']
source_id='phase-two:heir-discovery:1';source_row=events(first)[source_id];source=payload(source_row);players=set(source['participants'])
founders={value['player']:value for value in entries('FOUNDER')}
if set(founders)!=set('ABCD'):raise SystemExit('Missing actual founder evidence')
ids={short:founders[short]['uuid'] for short in 'ABCD'}
late_logs=entries('LATE_MEMBER')
if not late_logs:raise SystemExit('Missing actual late membership evidence')
e=late_logs[0]['uuid'];a_nation=founders['A']['nation']
epic=instances(first,'last-heir')
ending_candidates=[row for row in first['events'] if row['type']=='HEIR_RESTORATION_SUPPORTED']
if len(epic)!=1 or len(ending_candidates)!=1:raise SystemExit('Missing unique committed epic ending')
ending_row=ending_candidates[0];ending=payload(ending_row);ending_id=ending['id']
archives=instances(recovered,'heir-legacy');final_archives=instances(final,'heir-legacy');by_player={next(iter(i['members'])):i for i in final_archives if len(i['members'])==1}
inbox=[row for row in first['story_triggers'] if json.loads(row['story_json'])['id']=='heir-legacy']
council=[row for row in first['story_choice_log'] if row['story_id']=='last-heir' and row['node_id']=='council']
ballots={row['player_id']:row['choice_id'] for row in council if row['kind']=='BALLOT'}
resolutions=[row for row in council if row['kind']=='RESOLUTION']
final_facts=facts(final)
def native(snapshot):
    values=[payload(row) for row in snapshot['native_scene_journal'] if payload(row)['request']['playerId']==ids['A'] and payload(row)['request']['scriptId']=='chronicle_last_heir']
    return values[0] if len(values)==1 else None
native_first=native(first);native_recovered=native(recovered);native_final=native(final)
def preserved_native_state():
    return native_first is not None and native_recovered is not None and native_first['checkpoint'] is not None and native_recovered['checkpoint'] is not None and all(native_first['checkpoint'][key]==native_recovered['checkpoint'][key] for key in ['nodeId','variables','flags','evidence'])
def personal_result(kind,short,title):
    matches=[payload(row) for row in final['events'] if row['type']==kind]
    return len(matches)==1 and set(matches[0]['participants'])=={ids[short]} and set(matches[0]['nations'])=={founders[short]['nation']} and final_facts.get((ids[short],'title.'+title)) is not None and cause(final,matches[0]['id'],ending_id,source_id,2,['last-heir','heir-legacy'])
def target_history(snapshot):return {row['id']:row for row in snapshot['events'] if row['id']==source_id or row['type'] in ['HEIR_COUNCIL_OPENED','HEIR_RESTORATION_SUPPORTED','HEIR_PERSONAL_TESTIMONY','HEIR_WITNESS_ACCOUNT','HEIR_RECORDS_RECONCILED']}
def window_title(record):
    title=record['title'];return title.get('value','') if isinstance(title,dict) else str(title)
windows=[window_title(value) for kind,value in protocol if kind=='WINDOW'];choices=[value for kind,value in protocol if kind=='CLICKED' and 'choice' in value]
e_reads=[value['facts'] for value in entries('RECOLLECTIONS') if value.get('player')=='E']
personal_ref='past.player.choice.last-heir/1/council';world_ref='past.resolution.world.last-heir/1/council'
automatic_marker=next((index for index,(kind,value) in enumerate(records) if kind=='PHASE' and value.get('name')=='AUTOMATIC_RECOVERY_CAPTURED'),None)
first_instance_query=next((index for index,(kind,value) in enumerate(records) if kind=='INSTANCES'),None)
checks={
    'all_snapshots_and_copied_jar_match_frozen_artifact':all(s['plugin_sha256']==a.expected_plugin_sha256==manifest['starchronicle'] and s['run_name']==a.run_name for s in stages.values()),
    'published_stories_and_npc_file_match_jar_resources':all(s['loaded_resource_sha256']==manifest['resource_sha256'] for s in stages.values()),
    'three_actual_server_processes_stopped_normally':a.server_exit_code==[0,0,0] and all('All RegionFile I/O tasks to complete' in log and 'Disabling StarChronicle' in log for log in logs),
    'three_actual_client_controllers_exit_normally':a.client_exit_code==[0,0,0] and all(sum(kind=='SPAWN' and v.get('boot')==boot for kind,v in protocol)==count and sum(kind=='ENDED' and v.get('boot')==boot for kind,v in protocol)==count for boot,count in [('boot1',4),('boot2',5),('boot3',5)]) and not any(kind in ['ERROR','KICKED','TIMEOUT','ACTION_FAILED'] for kind,value in protocol),
    'four_actual_non_operator_founders_and_four_distinct_nations':len(players)==4 and players=={ids[x] for x in 'ABCD'} and len({founders[x]['nation'] for x in 'ABCD'})==4 and all(v.get('founder') for v in entries('FOUNDER')) and all(v.get('op') is False and v.get('use') is True for v in entries('PERMISSION')),
    'source_and_ending_preserve_exact_four_historical_members':set(epic[0]['members'])==players and epic[0]['completed'] and set(ending['participants'])==players and set(ending['nations'])==set(source['nations']) and all(events(snapshot).get(source_id)==source_row and events(snapshot).get(ending_id)==ending_row for snapshot in stages.values()),
    'real_epic_api_votes_keep_dissent_and_unvoted_member_distinct':ballots=={ids['A']:'support',ids['B']:'support',ids['D']:'oppose'} and len(resolutions)==1 and resolutions[0]['choice_id']=='support' and resolutions[0]['scope_key']=='world' and epic[0]['choices'].get('council/'+ids['D'])=='oppose' and 'council/'+ids['C'] not in epic[0]['choices'],
    'ending_queues_pinned_followup_before_any_personal_instance':not instances(first,'heir-legacy') and bool(inbox) and all(row['event_id']==ending_id and json.loads(row['story_json'])['version']=='1' for row in inbox) and all(not instances(stages[name],'heir-legacy') for name in ['boot1-final','boot2-offline']),
    'first_restart_keeps_choice_rows_and_source_causes':stages['boot1-final']['story_choice_log']==stages['boot2-offline']['story_choice_log'] and cause(first,source_id,None,source_id,0,[]) and cause(first,ending_id,source_id,source_id,1,['last-heir']) and traces(stages['boot2-offline']).get(ending_id)==traces(first).get(ending_id),
    'four_real_logins_recover_four_individual_followups':len(archives)==4 and {next(iter(i['members'])) for i in archives if len(i['members'])==1}==players and all(i['sourceEventId']==ending_id and i['scopeKey']=='player:'+next(iter(i['members'])) and i['node']=='archive' and not i['completed'] for i in archives),
    'automatic_join_recovery_is_captured_before_fixture_instance_queries':automatic_marker is not None and first_instance_query is not None and automatic_marker<first_instance_query,
    'late_client_only_connects_after_the_first_boot':not any(kind=='SPAWN' and value.get('player')=='E' and value.get('boot')=='boot1' for kind,value in protocol) and e not in players,
    'late_membership_is_committed_by_real_native_service':sum(v.get('addedByNativeService') is True for v in late_logs)==1 and all(v['nation']==a_nation and v['founder'] is False for v in late_logs) and e in nations(late).get(a_nation,{}).get('members',set()) and e in nations(final).get(a_nation,{}).get('members',set()) and nations(final)[a_nation]['founder']==ids['A'],
    'late_citizen_reads_world_and_national_history_but_not_personal_choice':any(read.get(world_ref)=='support' and personal_ref not in read and read.get('chronicle.history.player.HEIR_RESTORATION_SUPPORTED')=='0' and read.get('chronicle.history.nation.HEIR_RESTORATION_SUPPORTED')=='1' and read.get('chronicle.history.world.HEIR_RESTORATION_SUPPORTED')=='1' for read in e_reads),
    'late_citizen_gets_no_personal_story_reward_or_fabricated_actor':len(instances(late,'heir-legacy'))==4 and len(final_archives)==4 and e not in by_player and final_facts.get((e,'player.honor')) is None and all(row['player']!=e for row in final['event_players'] if row['event_id']==ending_id) and any(value.get('player')=='E' and value.get('instances')==[] for value in entries('INSTANCES')),
    'dissent_and_support_select_actual_default_npc_dialogues':any(v.get('player')=='D' and v.get('dialogue')=='personal-dissent' for v in entries('NPC')) and any(v.get('player')=='A' and v.get('dialogue')=='trusted-restoration' for v in entries('NPC')) and all(final_facts.get((ids[x],'npc.ancient-heir.reputation'))=='25' and final_facts.get((ids[x],'npc.ancient-heir.trust'))=='12' for x in 'ABCD') and final_facts.get(('WORLD','npc.ancient-heir.growth'))=='20',
    'actual_gui_exposes_only_eligible_archive_choices':all(any(kind=='CHOICES_VERIFIED' and v.get('player')==short and v.get('choices')==expected for kind,v in protocol) for short,expected in [('A',['reconcile','testimony']),('C',['reconcile','witness']),('D',['reconcile','testimony'])]),
    'actual_api_rejects_voter_witness_and_unvoted_testimony':all(any(v.get('player')==short and v.get('choice')==choice and 'SecurityException' in v.get('error','') for v in entries('INVALID_REJECTED')) for short,choice in [('A','witness'),('C','testimony')]),
    'three_actual_gui_branches_complete_with_correct_personal_history':all(any(v.get('player')==short and v.get('choice')==choice for v in choices) for short,choice in [('A','testimony'),('C','witness'),('D','reconcile')]) and personal_result('HEIR_PERSONAL_TESTIMONY','A','imperial-testimony-keeper') and personal_result('HEIR_WITNESS_ACCOUNT','C','imperial-history-witness') and personal_result('HEIR_RECORDS_RECONCILED','D','imperial-archive-reader'),
    'three_completed_archives_award_17_and_unfinished_b_stays_15':all(final_facts.get((ids[x],'player.honor'))=='17' and by_player[ids[x]]['completed'] for x in 'ACD') and final_facts.get((ids['B'],'player.honor'))=='15' and not by_player[ids['B']]['completed'] and by_player[ids['B']]['node']=='archive',
    'actual_story_permission_denial_has_no_authority_outcome':not authority(stages['invite-denied']) and any('SecurityException' in value.get('error','') and 'starcore.nation.ally.add' in value.get('error','') for value in entries('INVITE_REJECTED')),
    'explicit_actual_permission_allows_one_done_native_invitation':len(authority(stages['invite-permitted']))==1 and authority(stages['invite-permitted'])[0]['status']=='DONE' and payload(authority(stages['invite-permitted'])[0])['nationId']==a_nation and payload(authority(stages['invite-permitted'])[0])['value']==founders['B']['nation'] and any(value.get('relation')=='NEUTRAL' and value.get('from')==a_nation and value.get('to')==founders['B']['nation'] and a_nation in value.get('pending',[]) for value in entries('INVITE_NATIVE')) and any(value.get('permission') is True and value.get('op') is False for value in entries('INVITE_ALLOWED')),
    'already_committed_authority_outcome_coalesces_in_same_boot':len(authority(final))==1 and any(value.get('result')=='APPLIED' and value.get('originalAlreadyCommitted') is True and value.get('outcome')==authority(final)[0]['id'] for value in entries('INVITE_COALESCED')),
    'native_a_really_advances_and_recovers_its_own_checkpoint':preserved_native_state() and native_first['checkpoint']['nodeId']=='departure' and any(v.get('player')=='A' and v.get('session')=='chronicle_last_heir/departure' and v.get('routing') is True for v in entries('NATIVE')),
    'native_a_completes_through_gui_and_never_restarts_on_boot3':native_final is not None and native_final['status']=='COMPLETED' and 'Starting narrative session for '+ids['A'] not in logs[2] and any(v.get('player')=='A' and v.get('routing') is False and v.get('session')=='<absent>' for v in entries('NATIVE')) and sum(kind=='CLICKED' and v.get('player')=='A' and v.get('slot')==31 for kind,v in protocol)>=2,
    'native_start_outcome_ids_do_not_replay':source_outcomes(first)==source_outcomes(final) and len(source_outcomes(final))==4,
    'third_boot_and_source_replay_do_not_duplicate_stories_choices_rewards':relevant_instances(stages['boot2-final'])==relevant_instances(final) and stages['boot2-final']['story_choice_log']==final['story_choice_log'] and stages['boot2-final']['outcomes']==final['outcomes'] and target_history(stages['boot2-final'])==target_history(final) and any(v.get('replayRequested') is True and v.get('duplicate') is True for v in entries('SUBMITTED')) and all(any(v.get('player')==short for v in entries('COMPLETED_REJECTED')) for short in 'ACD'),
    'real_past_command_npc_fate_story_and_history_gui_packets':any(kind=='SENT' and value.get('player')=='D' and value.get('message')=='/chronicle past last-heir/1/council' for kind,value in protocol) and 'past.player.choice.last-heir/1/council = oppose' in client and all(any(label in title for title in windows) for label in ['旧都档案','人物档案','我的命运','世界编年史','历史证据','原生场景']),
    'schema3_integrity_and_foreign_keys_pass_at_every_stage':all(s['schema_version']==3 and s['integrity']=='ok' and s['core_integrity']=='ok' and not s['foreign_key_violations'] for s in stages.values()),
    'no_uncertain_native_or_causal_blocks_are_hidden':all(row['status'] not in ['UNKNOWN','RUNNING'] for row in final['native_scene_journal']) and final['story_trigger_blocks']==[],
    'no_fixture_failure_source_collision_or_chronicle_runtime_warning':'PHASE2_FAILED' not in server and 'Source event ID collision' not in server and not any('[StarChronicle]' in line and (' WARN]' in line or ' ERROR]' in line) for line in server.splitlines()),
}
summary={'status':'PASS' if all(checks.values()) else 'FAIL','plugin_sha256':a.expected_plugin_sha256,'run_name':a.run_name,'server_exit_codes':a.server_exit_code,'client_exit_codes':a.client_exit_code,'checks':checks,'scope':'Three real Paper boots, audited StarCore, four actual founders, one late native member, shipped epic and archive stories, persistent original ballots, NPC dialogue, real GUI choices, native checkpoint recovery and source replay'}
(OUT/'phase-two-paper-summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
failed=[name for name,ok in checks.items() if not ok]
if failed:raise SystemExit('Phase-two assertions failed: '+', '.join(failed))
lines=['# Phase-two Paper / real StarCore verification','','Result: **PASS** for the exact tested JAR below. This report is generated only after every required read-only snapshot, protocol log, native service assertion and observed process exit passes.','','- SHA-256: `'+a.expected_plugin_sha256+'`.','- Fresh isolated `run/'+a.run_name+'`; Paper 1.21.11 build 132, Java 21, audited real StarCore baseline.','- Listener `127.0.0.1:25589`; query/RCON disabled; all clients remained non-operators.','- Actual server exit codes: `'+str(a.server_exit_code)+'`; actual client controller exit codes: `'+str(a.client_exit_code)+'`.','','## Verified checks','']
lines+=['- '+name.replace('_',' ')+': PASS.' for name in checks]
lines+=['','## Durable narrative evidence','','Four real native national founders completed the shipped WORLD epic. D’s actual opposing council ballot remained separate from the supporting world resolution; C had no council ballot. The server was normally stopped after the actual ending was committed and its PLAYER archive triggers were durable, before any archive instance was activated. No ending event was resubmitted to make the recovery work. After restart, four real logins recovered four personal archives from that original ending.','','E joined later and was added to A’s actual nation through NationService.addMember. Its persisted native membership and fresh recollections API responses proved access to the relevant world decision and national history count, while the personal ending count remained zero and no personal archive or reward was fabricated.','','Actual GUI branches completed A’s testimony, C’s witness account and D’s reconciliation. Their histories each retain the correct single actor and the cause path last-heir → heir-legacy rooted at the original discovery receipt. B’s untouched archive remains active with honor 15; the other three retain honor 17 and their distinct titles. A’s actual native session was advanced, normally quit, recovered after restart and actually completed through its GUI. A third normal boot and replay of only the original source did not repeat stories, choices or outcomes.','','A separate isolated story first rejected the actual founder without its explicit alliance permission and created no authority outcome. After a real player permission attachment, the formal story choice committed one authority request, and the real StarCore service created one pending invitation while the relationship remained NEUTRAL. Only after confirming that mutation did the same already-admitted outcome return retained APPLIED in the same boot. This does not claim provider in-memory coalescing is a cross-restart exactly-once contract.','','## Evidence and limits','','- `phase-two-paper-*.json`: read-only Chronicle tables, causal traces, choice logs, native nation properties and integrity checks.','- `phase-two-paper-boot1-final.log`, `boot2-final.log`, `boot3-final.log`: sanitized actual server logs.','- `phase-two-paper-clients.log`: actual clients, late E connection, inventory components, visible choice guards, commands and successful clicks.','- `tools/PHASE-TWO-PAPER-RUN.md`: operator sequence and the boot-one no-drain boundary.','','The discovery and authority signals are explicitly isolated test receipts, not naturally occurring production-world discoveries. Protocol GUI evidence does not claim human visual acceptance. This run does not establish full Folia StarCore support, external quest/reward exactly-once delivery, real combat or other national authority operations. Older reports remain tied to their own artifact fingerprints and are not substituted for this run.','']
(OUT/'phase-two-paper-report.md').write_text('\n'.join(lines),encoding='utf-8')
print('PASS:',len(checks),'phase-two real-artifact assertions; SHA-256',a.expected_plugin_sha256)
