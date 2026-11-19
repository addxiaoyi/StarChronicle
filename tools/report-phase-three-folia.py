"""Verify actual retired-version Folia acceptance evidence without invoking Java."""
from pathlib import Path
from datetime import datetime, timedelta, timezone
from collections import Counter
import argparse, hashlib, json, re

PREFIX='phase-three-folia'
STAGES=('migrated-offline','offline-evaluated','b-recovered','after-b','v2-recovered','after-complete','restored','after-replay')
TABLES=('events','event_players','event_nations','instances','outcomes','facts','story_triggers','story_trigger_receipts','event_causes','story_choice_log','story_trigger_blocks','memory_sites','native_scene_journal')
SOURCE='chronicle:5ad9905c3336c82bef3538e5b469a5ae23e6031566a1d9f3af9d4ed07a78b492'
UUIDS={'A':'1d93f8d9-73dd-379e-ad87-fa6b7b92a55b','B':'501fd117-4620-35f4-882b-6d7044ea83a5'}
OLD_CHOICES={'A':'testimony','B':'reconcile'}
NEW_CHOICES={'A':'preserve','B':'annotate'}

def read(path):return json.loads(path.read_text(encoding='utf-8'))
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def time(value):return datetime.fromisoformat(value.replace('Z','+00:00'))
def canon(value):return json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':'))
def same_rows(left,right):return Counter(canon(row) for row in left)==Counter(canon(row) for row in right)
def real_personal_owners(rows):return len(rows)==2 and all(len(item['members'])==1 and item['scopeKey']=='player:'+next(iter(item['members'])) and set(item['members'].values())=={''} for item in rows) and {next(iter(item['members'])) for item in rows}==set(UUIDS.values())
def facts(snapshot):return {(row['owner'],row['key']):row['value'] for row in snapshot['facts']}
def instances(snapshot,story,version):return [json.loads(row['json']) for row in snapshot['instances'] if row['story']==story and json.loads(row['json'])['definition']['version']==version]
def pending(snapshot,story,version):return [row for row in snapshot['story_triggers'] if (item:=json.loads(row['story_json']))['id']==story and item['version']==version]
def markers(log,name):return [json.loads(match.group(1)) for line in log.splitlines() if (match:=re.search('PHASE_THREE_'+name+r' (\{.*\})\s*$',line))]

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--run-dir',type=Path,required=True);p.add_argument('--expected-sha',required=True);p.add_argument('--output-dir',type=Path);a=p.parse_args()
    if not re.fullmatch('[0-9a-f]{64}',a.expected_sha):p.error('Require full approved product hash')
    run=a.run_dir.resolve();e=run/'evidence';out=(a.output_dir or e).resolve();manifest=read(run/'acceptance-preparation.json');base=read(e/(PREFIX+'-source-baseline.json'))
    prior_path=e/(PREFIX+'-prior-attempt.json');prior_attempt=read(prior_path) if prior_path.is_file() else None
    provenance_path=e/(PREFIX+'-tool-provenance.json');tool_provenance=read(provenance_path) if provenance_path.is_file() else None
    acceptance_preparation_sha256=sha(run/'acceptance-preparation.json')
    snaps={stage:read(e/f'{PREFIX}-{stage}.json') for stage in STAGES};logs=[(e/f'{PREFIX}-boot{boot}.log').read_text(encoding='utf-8') for boot in (1,2,3)];processes=read(e/(PREFIX+'-processes.json'))
    protocol=[]
    for line in (e/(PREFIX+'-clients.log')).read_text(encoding='utf-8').splitlines():
        if match:=re.match(r'^(\S+) ([A-Z_]+) (\{.*\})$',line):
            data=json.loads(match[3]);data['_utc']=match[1];protocol.append((match[2],data))
    def client(event,boot=None,player=None):return [data for kind,data in protocol if kind==event and (boot is None or data.get('boot')==boot) and (player is None or data.get('player')==player)]
    checks={};details={}
    def check(name,operation):
        try:checks[name]=bool(operation())
        except Exception as error:checks[name]=False;details[name]=type(error).__name__+': '+str(error)
    migrated=snaps['migrated-offline'];offline=snaps['offline-evaluated'];b_ready=snaps['b-recovered'];after_b=snaps['after-b'];v2_ready=snaps['v2-recovered'];complete=snaps['after-complete'];restored=snaps['restored'];replay=snaps['after-replay']
    check('approved_final_product_all_snapshots_and_runtime',lambda:manifest['plugin_sha256']==a.expected_sha==sha(run/'plugins/StarChronicle.jar') and all(item['plugin_sha256']==a.expected_sha for item in snaps.values()))
    check('same_server_fixture_and_source_hashes_each_boot',lambda:all(len({item[key] for item in snaps.values()})==1 for key in ('server_sha256','fixture_sha256','fixture_source_sha256')) and complete['server_sha256']=='f52c408490a0225611e67907a3ca19f7e6da2c6bc899e715d5f46844e7103c39' and complete['fixture_sha256']==sha(run/'plugins/PhaseThreeFoliaFixture.jar'))
    check('real_schema3_baseline_and_readonly_schema4_migration_evidence',lambda:base['schema_version']==3 and all(item['schema_version']==4 and item['integrity']==['ok'] and item['foreign_key_violations']==[] and item['database_open_mode']=='ro; query_only=ON; consistent read transaction' for item in snaps.values()))
    check('original_source_database_and_sidecars_unchanged',lambda:sha(Path(manifest['source_original_path']))==base['source_database_sha256'] and all({'exists':(path:=Path(name)).exists(),'bytes':path.stat().st_size if path.exists() else 0,'sha256':sha(path) if path.exists() else None}==state for name,state in base['source_sidecars'].items()))
    check('baseline_is_actual_previous_verified_cohort',lambda:base['source_plugin_sha256']=='d765e0d14b9a9122e0ba1304c9c1b0d3ef844d4458295b1f4847ecb213bd943b' and set(manifest['cohort'])==set(UUIDS.values()) and len(base['story_choice_log'])==13 and len(base['outcomes'])==17 and len(base['instances'])==3)
    check('no_historical_results_changed_during_offline_migration',lambda:all(same_rows(migrated[table],base[table]) for table in TABLES))
    def old_rows_unchanged():
        for snap in snaps.values():
            for table in TABLES:
                if table=='facts':
                    current=facts(snap)
                    for row in base[table]:
                        if row['key']=='player.honor' and row['owner'] in UUIDS.values():continue
                        if current.get((row['owner'],row['key']))!=row['value']:return False
                else:
                    current=Counter(canon(row) for row in snap[table])
                    if any(current[row]<count for row,count in Counter(canon(row) for row in base[table]).items()):return False
        return True
    check('every_old_choice_outcome_instance_and_non_honor_row_is_byte_equivalent',old_rows_unchanged)
    check('canonical_source_payload_hash_and_actor_evidence_unchanged',lambda:all(next(row for row in snap['events'] if row['id']==SOURCE)==next(row for row in base['events'] if row['id']==SOURCE) for snap in snaps.values()))
    def archived_v1():
        old=next(json.loads(row['json'])['definition'] for row in base['instances'] if row['story']=='heir-legacy')
        for snap in snaps.values():
            rows=[row for row in snap['story_definitions'] if row['story']=='heir-legacy' and row['version']=='1']
            if len(rows)!=1 or json.loads(rows[0]['json'])!=old or sha_text(rows[0]['json'])!=rows[0]['fingerprint']:return False
        return True
    def sha_text(value):return hashlib.sha256(value.encode('utf-8')).hexdigest()
    check('real_retired_v1_definition_is_migrated_once_without_reconstruction',archived_v1)
    check('current_v2_and_b_are_archived_as_distinct_versions',lambda:all({(row['story'],row['version']) for row in snap['story_definitions']} >= {('heir-legacy','1'),('heir-legacy','2'),('phase-three-archive','1')} and all(sha_text(row['json'])==row['fingerprint'] for row in snap['story_definitions']) for snap in snaps.values()))
    def exact_chain():
        definitions={(row['story'],row['version']):json.loads(row['json']) for row in migrated['story_definitions']}
        review=definitions[('phase-three-archive','1')];v2=definitions[('heir-legacy','2')]
        return review['condition']=={'all':None,'any':None,'not':None,'fact':'past.player.choice.heir-legacy/1/archive','op':'exists','value':None} and v2['cooldownSeconds']==0 and v2['triggers']==['PHASE_THREE_ARCHIVE_REVIEWED'] and any(item.get('fact')=='past.player.choice.phase-three-archive/1/desk' and item.get('op')=='exists' for item in v2['condition']['all']) and any(item['permission']=='phase-three.v2.ready' and item['type']=='memory' for item in v2['nodes']['archive']['effects'])
    check('test_v1_to_b_to_v2_chain_uses_exact_real_references_and_a_real_permission_gate',exact_chain)
    check('thirteen_current_stories_and_install_defaults_disabled',lambda:len(manifest['story_sha256'])==13 and all(sha(run/'plugins/StarChronicle/stories'/name)==digest for name,digest in manifest['story_sha256'].items()) and 'install-default-stories: false' in (run/'plugins/StarChronicle/config.yml').read_text(encoding='utf-8') and all('Loaded 13 validated stories' in log for log in logs))
    check('real_folia_two_region_threads_and_standalone_boundary',lambda:all('Loading Folia 1.21.11-14-' in log and 'initial 2 target thread(s)' in log and 'STANDALONE MODE' in log for log in logs) and not (run/'plugins/StarCore.jar').exists())
    check('offline_evaluation_only_queues_two_real_player_inboxes',lambda:len(pending(migrated,'phase-three-archive','1'))==0 and len(pending(offline,'phase-three-archive','1'))==2 and len(offline['instances'])==3 and offline['outcomes']==migrated['outcomes'] and offline['facts']==migrated['facts'] and offline['events']==migrated['events'] and not instances(offline,'heir-legacy','2'))
    def offline_ack_order():
        boot=processes['boots'][0];ack=boot['offline_evaluation_ack'];capture=offline['captured_utc']
        return ack['online']==0 and ack['status']=='QUEUED' and ack['sourceId']==SOURCE and boot['offline_snapshot_sha256']==sha(e/(PREFIX+'-offline-evaluated.json')) and time(ack['observedAt'])<time(capture)<time(boot['client_start_ack_utc'])<=time(boot['client_started_utc'])<min(time(row['_utc']) for row in client('SPAWN',1))
    check('explicit_offline_api_ack_snapshot_operator_ack_and_login_have_strict_utc_order',offline_ack_order)
    check('actual_original_player_names_and_uuids_each_boot',lambda:all(len(rows:=client('SPAWN',boot,label))==1 and rows[0]['uuid']==player and rows[0]['username']=='PhaseTwoFolia'+label for boot in (1,2,3) for label,player in UUIDS.items()))
    check('owning_region_lowest_real_permission_false_then_true',lambda:all(any(row['player']==label and row['uuid']==player and row['permission']=='phase-three.v2.ready' and row['owned'] is True and row['priority']=='LOWEST' and row['granted']==(boot>=2) for row in markers(logs[boot-1],'GATE')) for boot in (1,2,3) for label,player in UUIDS.items()))
    check('players_remain_in_distinct_actual_regions',lambda:all(any(row['owned'] is True and row['otherOwned'] is False for row in markers(log,'SEPARATION')) for log in logs))
    check('real_logins_recover_b_without_granting_rewards_or_v2',lambda:real_personal_owners(instances(b_ready,'phase-three-archive','1')) and all(item['node']=='desk' and not item['completed'] and item['choices']=={} for item in instances(b_ready,'phase-three-archive','1')) and len(b_ready['outcomes'])==17 and not instances(b_ready,'heir-legacy','2') and all(facts(b_ready)[(player,'player.honor')]=='17' for player in UUIDS.values()))
    def recovery_precedes_any_query(snapshot,boot):
        captured=time(snapshot['captured_utc']);local=time(snapshot['captured_asia_shanghai']);spawns=client('SPAWN',boot);sent=client('SENT',boot)
        if local.utcoffset()!=timedelta(hours=8) or captured.astimezone(timezone(timedelta(hours=8)))!=local or len(spawns)!=2 or {row['player'] for row in spawns}!=set(UUIDS) or not sent:return False
        if not max(time(row['_utc']) for row in spawns)<captured<min(time(row['_utc']) for row in sent):return False
        query_seconds=[]
        for line in logs[boot-1].splitlines():
            if not re.search(r'PHASE_THREE_(?:INSTANCE|RECOLLECTIONS|FATE) ',line):continue
            match=re.search(r'\[(\d{2}:\d{2}:\d{2})\s+INFO\]:',line)
            if not match:return False
            query_seconds.append(datetime.combine(local.date(),datetime.strptime(match[1],'%H:%M:%S').time(),tzinfo=local.tzinfo))
        # A marker printed during the capture's second cannot establish ordering.
        # Require capture strictly before the beginning of the first query second.
        return bool(query_seconds) and local<min(query_seconds)
    check('b_recovery_capture_follows_both_logins_and_strictly_precedes_any_command_or_fixture_query',lambda:recovery_precedes_any_query(b_ready,1))
    check('b_real_gui_uses_each_actual_old_personal_choice',lambda:all(any(row['story']=='phase-three-archive' and row['version']=='1' and row['choice']==NEW_CHOICES[label] for row in client('GUI_CHOICE',1,label)) for label in UUIDS))
    def b_complete():
        rows=instances(after_b,'phase-three-archive','1')
        return real_personal_owners(rows) and all(item['completed'] and item['choices'].get('desk/'+player)==NEW_CHOICES[label] and item['sourceEventId']==SOURCE for label,player in UUIDS.items() for item in rows if player in item['members']) and all(facts(after_b)[(player,'player.honor')]=='19' for player in UUIDS.values())
    check('b_completes_once_and_awards_only_two_new_honor_per_player',b_complete)
    check('real_permission_denial_keeps_v2_derived_inboxes_across_first_stop',lambda:not instances(after_b,'heir-legacy','2') and len(pending(after_b,'heir-legacy','2'))==2 and len(after_b['story_choice_log'])==17 and len(after_b['outcomes'])==23)
    check('second_boot_permission_and_completed_b_restore_two_unvoted_v2_instances',lambda:real_personal_owners(instances(v2_ready,'heir-legacy','2')) and all(item['node']=='archive' and not item['completed'] and item['choices']=={} for item in instances(v2_ready,'heir-legacy','2')) and not pending(v2_ready,'heir-legacy','2') and all(facts(v2_ready)[(player,'player.honor')]=='19' for player in UUIDS.values()))
    check('v2_recovery_capture_follows_both_logins_and_strictly_precedes_any_command_or_fixture_query',lambda:recovery_precedes_any_query(v2_ready,2))
    check('v2_real_gui_branches_are_version_2_testimony_and_reconcile',lambda:all(any(row['story']=='heir-legacy' and row['version']=='2' and row['choice']==OLD_CHOICES[label] for row in client('GUI_CHOICE',2,label)) for label in UUIDS))
    def v2_completed(snap):
        rows=instances(snap,'heir-legacy','2')
        return real_personal_owners(rows) and all(item['completed'] and item['choices'].get('archive/'+player)==OLD_CHOICES[label] and item['node']==('personal' if label=='A' else 'reconciled') for label,player in UUIDS.items() for item in rows if player in item['members'])
    check('both_v2_endings_and_honor21_survive_restarts',lambda:all(v2_completed(snap) and all(facts(snap)[(player,'player.honor')]=='21' for player in UUIDS.values()) for snap in (complete,restored,replay)))
    check('old_first_edition_votes_remain_the_original_testimony_and_reconcile',lambda:all(any(row['story_id']=='heir-legacy' and row['story_version']=='1' and row['kind']=='BALLOT' and row['player_id']==player and row['node_id']=='archive' and row['choice_id']==OLD_CHOICES[label] for row in snap['story_choice_log']) for snap in snaps.values() for label,player in UUIDS.items()))
    def new_choices_valid():
        rows=complete['story_choice_log'];fresh=[row for row in rows if row['seq']>13]
        if len(rows)!=21 or len({row['id'] for row in rows})!=21 or len(fresh)!=8:return False
        for label,player in UUIDS.items():
            for story,version,node,choice in [('phase-three-archive','1','desk',NEW_CHOICES[label]),('heir-legacy','2','archive',OLD_CHOICES[label])]:
                group=[row for row in fresh if row['story_id']==story and row['story_version']==version and row['committed_by']==player]
                if len(group)!=2 or {row['kind'] for row in group}!={'BALLOT','RESOLUTION'}:return False
                for row in group:
                    if row['node_id']!=node or row['choice_id']!=choice or row['provenance']!='LIVE' or row['revision']!=1 or row['nation_id']!='' or not row['chosen_at'] or row['scope_key']!='player:'+player or row['actor_key']!='player:'+player or row['player_id']!=(player if row['kind']=='BALLOT' else None):return False
        return len(complete['outcomes'])==31 and all(row['status']=='DONE' for row in complete['outcomes'])
    check('new_choice_rows_are_live_exact_actor_versions_and_stable_receipts',new_choices_valid)
    def causal_chain():
        causes={row['event_id']:row for row in complete['event_causes']};source=causes[SOURCE]
        reviews=[row for row in complete['events'] if row['type']=='PHASE_THREE_ARCHIVE_REVIEWED']
        finals=[row for row in complete['events'] if row['type'] in ('PHASE_THREE_V2_TESTIMONY','PHASE_THREE_V2_RECONCILED')]
        if len(reviews)!=2 or len(finals)!=2:return False
        for row in reviews:
            cause=causes[row['id']]
            if cause['parent_id']!=SOURCE or cause['root_id']!=source['root_id'] or cause['depth']!=2 or json.loads(cause['story_path'])!=['last-heir','phase-three-archive']:return False
        for row in finals:
            cause=causes[row['id']]
            if cause['parent_id'] not in {event['id'] for event in reviews} or cause['root_id']!=source['root_id'] or cause['depth']!=3 or json.loads(cause['story_path'])!=['last-heir','phase-three-archive','heir-legacy']:return False
        return not complete['story_trigger_blocks']
    check('actual_derived_causal_paths_run_b_then_v2_without_a_retired_loop',causal_chain)
    check('third_boot_re_evaluation_is_natural_key_already_registered',lambda:len(rows:=markers(logs[2],'EVALUATED'))>=1 and all(row['sourceId']==SOURCE and row['status']=='ALREADY_REGISTERED' for row in rows))
    check('restoration_and_repeated_evaluation_change_no_game_result_table',lambda:all(complete[table]==restored[table]==replay[table] for table in TABLES+('story_definitions',)))
    check('canonical_history_counts_old_choices_and_current_choices_are_read_by_api',lambda:all(any(row['player']==label and row['nationAbsent'] and row['facts'].get('history.HEIR_RESTORATION_SUPPORTED')=='1' and row['facts'].get('chronicle.history.world.HEIR_RESTORATION_SUPPORTED')=='1' and row['facts'].get('chronicle.history.player.HEIR_RESTORATION_SUPPORTED')=='1' and row['facts'].get('past.player.choice.heir-legacy/1/archive')==OLD_CHOICES[label] and row['facts'].get('past.player.choice.phase-three-archive/1/desk')==NEW_CHOICES[label] and row['facts'].get('past.player.choice.heir-legacy/2/archive')==OLD_CHOICES[label] for row in markers(logs[2],'RECOLLECTIONS')) for label in UUIDS))
    check('no_role_or_nation_is_fabricated',lambda:all(any(row['player']==label and row['role']=='unassigned' and row['honor']=='21' for row in markers(logs[2],'FATE')) for label in UUIDS) and all(not item['event_nations'] for item in snaps.values()))
    check('historical_npc_relationship_and_dialogue_are_unchanged',lambda:all(any(row['player']==label and row['reputation']==25 and row['trust']==12 and row['growth']==20 and row['position']=='council-speaker' and row['dialogue']=='trusted-restoration' for row in markers(logs[2],'NPC')) for label in UUIDS))
    entities=['monument:INTERACTION','text:TEXT_DISPLAY','veteran:VILLAGER']
    check('cloned_world_memory_and_unrelated_bystander_keep_real_region_ownership',lambda:all(any(row['owned'] and row['bystanders']==1 and row['sites'].get('phase-two-live')==entities and 'phase-two-removed' not in row['sites'] for row in markers(log,'SITES')) for log in logs))
    check('native_scene_stays_unavailable_and_action_rejected',lambda:any(row['present'] is False for log in logs for row in markers(log,'SCENE')) and any(row['rejected'] is True for log in logs for row in markers(log,'SCENE_ACTION')))
    check('actual_story_history_npc_fate_and_book_protocol_paths',lambda:all(any(title in canon(row.get('title')) for row in client('WINDOW')) for title in ('Phase Three Archive Review','Phase Three Legacy v2','世界编年史','人物档案','我的命运与历史荣誉')) and bool(client('BOOK')))
    check('gui_presented_on_actual_owner_and_shift_click_preserved',lambda:all(row['owned'] for log in logs for row in markers(log,'VIEW')) and any(row['unchangedInventory'] and row['slotStillPresent'] for row in client('SHIFT_CHECK')))
    def process_ok():
        boots=processes['boots'];pids=[row[key] for row in boots for key in ('server_pid','client_pid')]
        return len(boots)==3 and [row['boot'] for row in boots]==[1,2,3] and len(set(pids))==6 and all(isinstance(pid,int) and pid>0 for pid in pids) and processes['fixture_compile_exit_code']==0 and processes['server_exit_codes']==[0,0,0] and processes['client_exit_codes']==[0,0,0] and all(row['client_start_ack'] and row['all_owned_children_exited'] and row['normal_console_stop_requested'] and row['clients_quit_requested'] and row['plugin_sha256_after']==a.expected_sha and not row.get('launcher_problem') and not row.get('cleanup_errors') and not row.get('forced_cleanup_pids') for row in boots)
    check('three_real_server_and_client_processes_have_acknowledged_normal_zero_exits',process_ok)
    check('six_real_clients_quit_and_end_normally',lambda:all(len(client('QUIT',boot))==1 and all(len(client('QUIT_REQUESTED',boot,label))==len(client('ENDED',boot,label))==1 and client('ENDED',boot,label)[0]['requestedQuit'] for label in UUIDS) for boot in (1,2,3)))
    check('all_servers_finished_player_save_and_region_io',lambda:all('Saved all player data' in log and 'All RegionFile I/O tasks to complete' in log for log in logs))
    check('no_thread_check_plugin_failure_or_protocol_timeout',lambda:not re.search(r'(?i)thread check failed|not on an entity|not on a region|Asynchronous .+!|Error occurred while|Exception in thread|PHASE_THREE_FAILED','\n'.join(logs)) and not any(event in ('ERROR','ACTION_FAILED','TIMEOUT','UNCAUGHT') for event,data in protocol))
    prior_observed={}
    if prior_attempt is not None:
        def preserved_prior_attempt_is_independent():
            previous=Path(prior_attempt['prior_attempt_directory']).resolve();previous_db=previous/'plugins/StarChronicle/chronicle.db';original=Path(manifest['source_original_path']).resolve()
            source_run=Path(base['source_run']).resolve();old_processes=read(previous/'evidence'/(PREFIX+'-processes.json'));old_ready=read(previous/'evidence'/(PREFIX+'-v2-recovered.json'));old_reason=read(previous/'evidence'/(PREFIX+'-cooldown-attempt.json'));old_boot2=(previous/'evidence'/(PREFIX+'-boot2.log')).read_text(encoding='utf-8')
            prior_observed.update(server_exit_codes=old_processes['server_exit_codes'],client_exit_codes=old_processes['client_exit_codes'],retained_database_sha256=sha(previous_db),cooldown_seconds=[json.loads(row['json'])['cooldownSeconds'] for row in old_ready['story_definitions'] if row['story']=='heir-legacy' and row['version']=='2'])
            if previous in (run,source_run) or Path(prior_attempt['new_run_directory']).resolve()!=run or original!=(source_run/'plugins/StarChronicle/chronicle.db').resolve() or original==previous_db.resolve():return False
            if not (sha(original)==base['source_database_sha256']==manifest['source_database_sha256']==prior_attempt['source_database_sha256']) or sha(previous_db)!=prior_attempt['prior_stopped_database_sha256']:return False
            if prior_attempt['prior_server_exit_codes']!=[0,0] or prior_attempt['prior_client_exit_codes']!=[0,0] or old_processes['server_exit_codes']!=[0,0] or old_processes['client_exit_codes']!=[0,0] or [row['boot'] for row in old_processes['boots']]!=[1,2]:return False
            if any(row['server_exit_code']!=0 or row['client_exit_code']!=0 or not row['all_owned_children_exited'] or not row['normal_console_stop_requested'] or not row['clients_quit_requested'] or row.get('launcher_problem') for row in old_processes['boots']):return False
            if old_reason['status']!='FAILED_ACCEPTANCE_SCENARIO_CONFIGURATION' or old_reason['product_defect'] is not False or old_reason['source_database_modified'] is not False or old_reason['runtime_modified_after_start'] is not False or not old_reason['all_two_boot_owned_children_exited']:return False
            if prior_observed['cooldown_seconds']!=[604800] or instances(old_ready,'heir-legacy','2') or len(pending(old_ready,'heir-legacy','2'))!=2 or not real_personal_owners(instances(old_ready,'phase-three-archive','1')):return False
            if not all(any(row['player']==label and row['uuid']==player and row['owned'] is True and row['granted'] is True for row in markers(old_boot2,'GATE')) for label,player in UUIDS.items()):return False
            for player in UUIDS.values():
                edition=next(item for item in instances(old_ready,'heir-legacy','1') if player in item['members'])
                if not edition['completed'] or time(edition['updatedAt'])+timedelta(seconds=604800)<=time(old_ready['captured_utc']):return False
            current_v2=[row for row in migrated['story_definitions'] if row['story']=='heir-legacy' and row['version']=='2']
            return len(current_v2)==1 and json.loads(current_v2[0]['json'])['cooldownSeconds']==0 and time(manifest['prepared_utc'])<=time(current_v2[0]['first_seen']) and time(manifest['prepared_utc'])<=time(processes['boots'][0]['server_started_utc']) and prior_attempt['new_v2_story_sha256']==manifest['story_sha256']['heir-legacy.yml']==sha(run/'plugins/StarChronicle/stories/heir-legacy.yml') and all(same_rows(migrated[table],base[table]) for table in TABLES)
        check('preserved_two_boot_cooldown_case_and_current_zero_cooldown_fork_have_separate_unchanged_sources_and_exits',preserved_prior_attempt_is_independent)
    if tool_provenance is not None:
        def delivery_tool_fingerprints_match():
            mapping=tool_provenance['sha256'];repository=Path(__file__).resolve().parents[1]
            if not isinstance(mapping,dict) or not mapping:return False
            resolved=set()
            for name,digest in mapping.items():
                if not isinstance(name,str) or not isinstance(digest,str) or not re.fullmatch('[0-9a-f]{64}',digest):return False
                path=Path(name)
                if not path.is_absolute() or not path.is_file() or sha(path)!=digest:return False
                resolved.add(path.resolve())
            tools=('prepare-phase-three-folia.py','build-phase-three-folia-fixture.py','PhaseThreeFoliaFixture.java','inspect-phase-three-folia.py','phase-three-folia-session.py','phase-three-folia-clients.cjs','report-phase-three-folia.py')
            required={run/'acceptance-preparation.json',run/'fixture/PhaseThreeFoliaFixture.java',run/'plugins/PhaseThreeFoliaFixture.jar',run/'plugins/StarChronicle/stories/heir-legacy.yml',run/'plugins/StarChronicle/stories/phase-three-archive.yml'}|{repository/'tools'/name for name in tools}
            completed=[time(row[key]) for row in processes['boots'] for key in ('server_ended_utc','client_ended_utc')]
            return {path.resolve() for path in required}.issubset(resolved) and time(tool_provenance['recorded_utc'])>=max(completed) and sha(run/'acceptance-preparation.json')==acceptance_preparation_sha256
        check('post_acceptance_delivery_manifest_tools_fixture_and_test_configuration_fingerprints_match',delivery_tool_fingerprints_match)
    java=[re.search(r'\[bootstrap\] Running Java (.+)$',log,re.M).group(1).strip() for log in logs]
    notes_path=e/(PREFIX+'-setup-notes.json')
    prior_summary=None if prior_attempt is None else {'metadata':prior_attempt,'observed_retained_attempt':prior_observed,'verification_passed':checks['preserved_two_boot_cooldown_case_and_current_zero_cooldown_fork_have_separate_unchanged_sources_and_exits'],'exit_code_scope':'Prior two-boot exits are retained here only. The top-level three-boot exits belong exclusively to the current independent runtime.'}
    summary={'scope':manifest['scope'],'plugin_sha256':a.expected_sha,'source_plugin_sha256':base['source_plugin_sha256'],'source_database_sha256':base['source_database_sha256'],'run_directory':str(run),'java_runtime_per_boot':java,'server_exit_codes':processes['server_exit_codes'],'client_exit_codes':processes['client_exit_codes'],'preparation_notes':read(notes_path) if notes_path.is_file() else None,'prior_attempt':prior_summary,'acceptance_preparation_sha256':acceptance_preparation_sha256,'tool_provenance':tool_provenance,'tool_provenance_verified':checks.get('post_acceptance_delivery_manifest_tools_fixture_and_test_configuration_fingerprints_match'),'tool_provenance_scope':'Post-acceptance delivery fingerprints, not pre-startup logs. First-acceptance cooldown zero is independently established by the first archived definition and manifest preparation UTC.','checks':checks,'details':details,'passed':sum(checks.values()),'total':len(checks),'all_passed':all(checks.values())}
    out.mkdir(parents=True,exist_ok=True);(out/(PREFIX+'-summary.json')).write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    rows='\n'.join('| '+name+' | '+('PASS' if value else 'FAIL')+' |' for name,value in checks.items())
    prior_note=''
    if prior_summary is not None:
        prior_note=(f"The preserved previous runtime `{prior_attempt['prior_attempt_directory']}` contains two normally stopped boots. Its legitimate 604800-second same-story cooldown blocked immediate version-two activation despite the true permission attachment; that retained scenario is not a product defect. The retained database fingerprint is `{prior_attempt['prior_stopped_database_sha256']}`. Current runtime `{run}` is a new fork of the unchanged phase-two source `{base['source_database_sha256']}`, with test-only v2 cooldown zero from its first archived definition acceptance. The prior `[0, 0]` server/client exits are reported only in `prior_attempt`; they are never combined with this runtime's separate three-boot results.\n" if prior_summary['verification_passed'] else "Prior-attempt metadata is present but its separate source, cooldown, or process provenance failed verification. Its claimed scenario is not accepted as evidence for this runtime.\n")
    provenance_note=(f"The optional `{PREFIX}-tool-provenance.json` is evaluated as an acceptance-complete delivery record: its recorded UTC must follow the observed process exits. Its SHA-256 mapping is checked against every listed actual file and must cover the preparation manifest, all seven Folia verification sources, runtime fixture Java/JAR, and both test-story configurations. Verification result: `{'PASS' if summary['tool_provenance_verified'] else 'FAIL'}`. These delivery fingerprints are not startup-time logs. The first-acceptance zero cooldown remains independently established by the first archived v2 definition and the preparation manifest UTC, rather than by this later record.\n" if tool_provenance is not None else "No optional post-acceptance tool-provenance record was supplied. The summary still records the current preparation manifest SHA-256; it does not present a later delivery fingerprint as startup evidence.\n")
    report=f'''# Phase-three real Folia retired-version acceptance

Scope: **StarChronicle standalone on real Folia, not StarCore full-stack certification**. The original normally stopped phase-two world and schema-three database were copied. The original database and sidecar states remain checked against preparation fingerprints. No new player identity, old vote, nation or authority service was fabricated.

- Approved product: `{a.expected_sha}`.
- Previous actual product: `{base['source_plugin_sha256']}`.
- Previous original database: `{base['source_database_sha256']}`.
- Current preparation manifest SHA-256: `{acceptance_preparation_sha256}`.
- Java observed on all boots: `{'; '.join(sorted(set(java)))}`.
- Canonical saved ending: `{SOURCE}`.

The real first-edition heir-legacy definition is migrated from saved instances. Current content enables version two and a separate review story reading the exact first-edition personal archive. Boot one first evaluates the saved ending with zero online players. A completed API acknowledgement, a subsequent read-only snapshot proving no new instances/rewards, an explicit operator client-start acknowledgement, and the actual protocol login timestamps establish strict UTC ordering; no same-second inference is needed.

{prior_note}

{provenance_note}

The same original A/B accounts log into their copied world. They use their real saved testimony/reconcile choices to complete different GUI review branches. A real Bukkit permission attachment is false on boot one and true on later boots, installed on the owning player region at LOWEST join priority before the production NORMAL-priority fresh snapshot. This permission gate is explicit in the test-only version-two starting-node effect; it does not replace identity or historical facts. Completed review choices and derived history queue the second edition. Boot two restores its personal instances from those inboxes, then actual GUI choices complete them. Boot three verifies restoration and repeats explicit evaluation of the identical saved source without another reward.

Both intermediate recovery captures must fall after both real account SPAWN timestamps and before the first client SENT command on their respective boots. They must also be strictly earlier than the beginning of the first server-log second containing an INSTANCE, RECOLLECTIONS, or FATE query marker. A same-second capture fails this timing check; the report does not infer sub-second order from second-resolution server timestamps. The archived test v2 definition must have cooldownSeconds zero.

Old choice/outcome/instance rows and all other non-new rows are compared in full, retaining their literal JSON column text. Only the two legitimate honor totals may change among old facts; the documented trigger checked liveness column is excluded. Expected honor progresses 17 to 19 to 21. All old votes, old outcome receipts, NPC relationships, historical evidence and cloned memory ownership remain intact.

| Check | Result |
| --- | --- |
{rows}

Observed result: **{summary['passed']}/{summary['total']} checks passed**; `all_passed` is true only when every check succeeds. Actual process exits are read from `{PREFIX}-processes.json`. Raw evidence consists of the unchanged source baseline, eight read-only schema-four captures, three server logs, three client process logs, aggregate protocol log and process metadata.

Any pre-JVM preparation/tool limitations are retained separately in `{PREFIX}-setup-notes.json` and in the machine summary. They are not counted as successful server boots. Launcher failures, EOF, forced cleanup or nonzero real process exits prevent acceptance.

Limits: protocol automation is not human visual acceptance. Native StarCore scenes stay unavailable and their actions remain rejected. This does not certify upstream StarCore on Folia, real national state mutations, third-party NPC providers, external rewards, crash/power-loss recovery or AI services.
'''
    (out/(PREFIX+'-report.md')).write_text(report,encoding='utf-8');print(json.dumps(summary,ensure_ascii=True,indent=2))
    if not summary['all_passed']:raise SystemExit(1)

if __name__=='__main__':main()
