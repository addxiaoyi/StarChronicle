"""Verify complete three-boot real Paper evidence; publish PASS only for the exact tested JAR."""
from pathlib import Path
from collections import Counter
import argparse
import importlib.util
import json
import re
import shutil

spec = importlib.util.spec_from_file_location('phase_three_paper_common', Path(__file__).with_name('phase-three-paper-common.py'))
c = importlib.util.module_from_spec(spec); spec.loader.exec_module(c)
STAGES = [
    'boot1-migrated-offline','boot1-baseline','boot1-before-validate','boot1-validated','boot1-published',
    'boot1-nonop-denied','boot1-normal-replay','boot1-explicit-queued','boot1-final',
    'boot2-offline','boot2-joined','boot2-before-bad-reload','boot2-bad-reload','boot2-restored','boot2-completed','boot2-final',
    'boot3-offline','boot3-joined','boot3-old-b-completed','boot3-completed','boot3-replayed','boot3-final',
]


def objects(stage,story=None,version=None):
    values=[json.loads(row['json']) for row in stage['instances']]
    return [item for item in values if (story is None or item['definition']['id']==story) and (version is None or item['definition']['version']==version)]


def new_instances(stage):
    return objects(stage,c.ARCHIVE,'2')+objects(stage,c.READER,'1')


def rows_by(stage,table,key='id'):
    return {row[key]:row for row in stage[table]}


def honor(stage,who):
    return int(next(row['value'] for row in stage['facts'] if row['owner']==c.PLAYERS[who] and row['key']=='player.honor'))


def definition_rows(stage,story,version):
    return [row for row in stage.get('story_definitions',[]) if row['story']==story and row['version']==version]


def selected_trigger(row):
    d=json.loads(row['story_json'])
    return d['id']==c.ARCHIVE and d['version']=='2' or d['id']==c.READER and d['version']=='1'


def selected_receipt(stage,story,version,who):
    key=c.stable(c.ENDING,story,version)
    return any(row['trigger_key']==key and row['scope']=='player:'+c.PLAYERS[who] for row in stage['story_trigger_receipts'])


def core_identity(stage):
    props={row['property_key']:row['property_value'] for row in stage['core_nation_state']}
    result={}
    for index in range(int(props['count'])):
        prefix=f'nation.{index}.';nation=props[prefix+'id']
        result[nation]={'founder':props[prefix+'founderId'],'name':props[prefix+'name'],
                        'members':sorted(props[prefix+f'member.{member}.id'] for member in range(int(props[prefix+'memberCount'])))}
    return result


def parse_protocol(text):
    result=[]
    for line in text.splitlines():
        match=re.match(r'^\S+ ([A-Z_]+) (\{.*\})$',line)
        if match:result.append((match.group(1),json.loads(match.group(2))))
    return result


def title(value):
    if isinstance(value,str):return value
    if isinstance(value,list):return ''.join(title(item) for item in value)
    if isinstance(value,dict):
        if value.get('type')=='string':return value.get('value','')
        return title(value.get('text',value.get('value','')))+title(value.get('extra',[]))
    return ''


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-dir',type=Path,required=True)
    parser.add_argument('--expected-sha',required=True)
    args=parser.parse_args();run=c.run_path(args.run_dir);manifest=c.manifest(run,args.expected_sha);evidence=run/'evidence'
    required=[evidence/(stage+'.json') for stage in ['source-schema3']+STAGES]+[evidence/'phase-three-paper-processes.json',evidence/'phase-three-paper-clients.log']
    required += [evidence/f'phase-three-paper-boot{boot}.log' for boot in [1,2,3]]+[evidence/f'boot{boot}-final.log' for boot in [1,2,3]]
    missing=[path.name for path in required if not path.is_file()]
    if missing:raise SystemExit('Incomplete actual evidence; no PASS report generated: '+', '.join(missing))
    source=json.loads((evidence/'source-schema3.json').read_text(encoding='utf-8'))
    stages={stage:json.loads((evidence/(stage+'.json')).read_text(encoding='utf-8')) for stage in STAGES}
    processes=json.loads((evidence/'phase-three-paper-processes.json').read_text(encoding='utf-8'))
    # The retained server FileAppender logs are UTF-8. Windows console output may use a
    # native code page, so do not turn decoded console replacement characters into evidence.
    logs=[(evidence/f'boot{boot}-final.log').read_text(encoding='utf-8',errors='strict') for boot in [1,2,3]]
    server='\n'.join(logs);client=(evidence/'phase-three-paper-clients.log').read_text(encoding='utf-8',errors='replace');protocol=parse_protocol(client)
    entries={}
    for line in server.splitlines():
        match=re.search(r'PHASE3_([A-Z_]+) (\{.*\})',line)
        if match:entries.setdefault(match.group(1),[]).append(json.loads(match.group(2)))
    boot3_entries={}
    for line in logs[2].splitlines():
        match=re.search(r'PHASE3_([A-Z_]+) (\{.*\})',line)
        if match:boot3_entries.setdefault(match.group(1),[]).append(json.loads(match.group(2)))
    runtimes={item['label']:item for item in entries.get('RUNTIME',[])}
    def enabled(label):return {(item['story'],item['version']):item for item in runtimes.get(label,{}).get('enabled',[])}
    initial=stages['boot1-migrated-offline'];queued=stages['boot1-explicit-queued'];final=stages['boot3-final']
    source_rows=rows_by(source,'instances');old_b=next(item for item in objects(source,c.ARCHIVE,'1') if c.PLAYERS['B'] in item['members'])
    old_completed={key:row for key,row in source_rows.items() if row['completed']}
    preserved_tables=['events','event_nations','event_players','instances','outcomes','story_triggers','story_trigger_receipts','native_scene_journal','story_choice_log','event_causes','story_trigger_blocks']
    migration_preserved=all(all(row in initial[table] for row in source[table]) for table in preserved_tables)
    origin_events=rows_by(source,'events')
    source_invariant=all(all(rows_by(stage,'events').get(key)==origin_events[key] for key in [c.DISCOVERY,c.ENDING]) for stage in stages.values())
    expected_members={uuid:nation for who,uuid in c.PLAYERS.items() for nation in [c.NATIONS[who]]}
    identities=entries.get('IDENTITY',[])
    actual_identities=all(any(item.get('player')==who and item.get('uuid')==uuid and item.get('nation')==c.NATIONS[who] and item.get('founder') is True and item.get('op') is False and item.get('admin') is False and item.get('use') is True for item in identities) for who,uuid in c.PLAYERS.items())
    all_boot_spawns=all(all(any(kind=='SPAWN' and item.get('boot')==boot and item.get('player')==who and item.get('uuid')==uuid for kind,item in protocol) for who,uuid in c.PLAYERS.items()) for boot in [1,2,3])
    final_new=new_instances(final)
    correct_final=len(final_new)==8 and all(item['completed'] and item['sourceEventId']==c.ENDING and len(item['members'])==1 and all(expected_members.get(player)==nation for player,nation in item['members'].items()) and item['id']==c.stable(item['definition']['id'],item['definition']['version'],item['scopeKey'],c.ENDING) for item in final_new)
    def original_b_unchanged(stage):return rows_by(stage,'instances').get(old_b['id'])==source_rows[old_b['id']]
    def current_pending_count(stage,story,version):return sum(json.loads(row['story_json'])['id']==story and json.loads(row['story_json'])['version']==version for row in stage['story_triggers'])
    def personal_choice(stage,who,story,version,node):
        matches=[row for row in stage['story_choice_log'] if row['story_id']==story and row['story_version']==version and row['player_id']==c.PLAYERS[who] and row['node_id']==node and row['kind']=='BALLOT']
        return matches[-1]['choice_id'] if matches else None
    def canonical_past(who,key,wanted):return any(item.get('player')==who and item.get('facts',{}).get(key)==wanted for item in entries.get('RECOLLECTIONS',[]))
    chosen={'A':'testimony','B':'testimony','C':'witness','D':'reconcile'}
    gui_branches=all(any(kind=='CHOICES_VERIFIED' and item.get('player')==who and '旧档中的版本坐标' in title(item.get('title')) and item.get('choices')==[choice] for kind,item in protocol) and any(kind=='CLICKED' and item.get('player')==who and item.get('choice')==choice and '旧档中的版本坐标' in title(item.get('windowTitle')) for kind,item in protocol) for who,choice in chosen.items())
    a2_gui=all(any(kind=='CLICKED' and item.get('player')==who and item.get('choice')=='annotate' and '旧都档案·第二卷' in title(item.get('windowTitle')) for kind,item in protocol) for who in c.PLAYERS)
    new_history=[row for row in final['events'] if row['type'].startswith('PHASE_THREE_')]
    new_causes=rows_by(final,'event_causes','event_id')
    histories_correct=len(new_history)==8
    history_members_correct=True
    for row in new_history:
        event=json.loads(row['json']);cause=new_causes.get(row['id']);expected_story=c.ARCHIVE if row['type']=='PHASE_THREE_ARCHIVE_ANNOTATED' else c.READER
        histories_correct=histories_correct and len(event['participants'])==1 and len(event['nations'])==1 and bool(cause) and cause['parent_id']==c.ENDING and cause['root_id']==c.DISCOVERY and cause['depth']==2 and json.loads(cause['story_path'])==['last-heir',expected_story]
        history_members_correct=history_members_correct and len(event['participants'])==1 and event['nations']==[expected_members.get(event['participants'][0])]
    expected_history_types=Counter({'PHASE_THREE_ARCHIVE_ANNOTATED':4,'PHASE_THREE_REMEMBERED_TESTIMONY':2,'PHASE_THREE_REMEMBERED_WITNESS':1,'PHASE_THREE_REMEMBERED_RECONCILE':1})
    old_npc=[row for row in source['facts'] if row['key'].startswith('npc.')]
    final_npc=[row for row in final['facts'] if row['key'].startswith('npc.')]
    commands=[item for boot in processes.get('boots',[]) for item in boot.get('commands',[])]
    boot3_commands=[item for boot in processes.get('boots',[]) if boot.get('boot')==3 for item in boot.get('commands',[])]
    stage_order={stage:index for index,stage in enumerate(STAGES)}
    checks={
        'frozen_product_and_fixture_match_every_snapshot':all(stage['plugin_sha256']==args.expected_sha and stage['fixture_sha256']==manifest['fixture_sha256'] for stage in stages.values()),
        'actual_original_schema3_database_and_source_files_are_preserved':source['schema_version']==3 and c.source_unchanged(manifest) and all(stage['source_files_unchanged'] for stage in stages.values()),
        'production_migration_preserves_original_history_instance_choice_outcome_and_inbox_payloads':migration_preserved and initial['schema_version']==4,
        'schema4_integrity_and_foreign_keys_pass_every_stage':all(stage['schema_version']==4 and stage['integrity']=='ok' and stage['core_integrity']=='ok' and not stage['foreign_key_violations'] for stage in stages.values()),
        'catalog_archives_exact_retired_a1_and_real_effective_native_binding':len(definition_rows(initial,c.ARCHIVE,'1'))==1 and any(json.loads(row['json'])['nodes']['summons']['script']=='chronicle_last_heir' for row in definition_rows(initial,'last-heir','1')),
        'current_new_versions_are_absent_before_pure_validation':not definition_rows(stages['boot1-before-validate'],c.ARCHIVE,'2') and not definition_rows(stages['boot1-before-validate'],c.READER,'1'),
        'pure_validate_does_not_archive_publish_or_mutate_the_runtime':stages['boot1-before-validate']['story_definitions']==stages['boot1-validated']['story_definitions'] and enabled('before-validate')==enabled('validated') and (c.ARCHIVE,'1') in enabled('validated') and (c.ARCHIVE,'2') not in enabled('validated'),
        'reload_enables_exact_a2_and_b_while_retiring_a1':(c.ARCHIVE,'2') in enabled('published') and (c.READER,'1') in enabled('published') and (c.ARCHIVE,'1') not in enabled('published') and len(definition_rows(stages['boot1-published'],c.ARCHIVE,'1'))==1,
        'retired_yaml_bytes_are_retained_and_not_reinstalled':all(stages[name]['retired_a1_file_sha256']==manifest['source_preserved_sha256']['plugins/StarChronicle/stories/heir-legacy.yml'] and 'stories/heir-legacy.yml' not in stages[name]['config_sha256'] for name in STAGES if stage_order[name]>=stage_order['boot1-before-validate']),
        'four_original_nonop_clients_and_real_native_nations_are_reused':actual_identities and all_boot_spawns and all(core_identity(stage)==core_identity(source) for stage in stages.values()),
        'nonop_evaluate_is_rejected_without_registering_new_content':not any(selected_trigger(row) for row in stages['boot1-nonop-denied']['story_triggers']) and stages['boot1-published']['story_definitions']==stages['boot1-nonop-denied']['story_definitions'] and any(kind=='SENT' and item.get('player')=='A' and item.get('message')==f'/chronicle evaluate {c.ENDING} {c.READER} 1' for kind,item in protocol) and any(kind=='MESSAGE_VERIFIED' and item.get('player')=='A' and '管理权限' in item.get('contains','') for kind,item in protocol),
        'ordinary_canonical_redelivery_does_not_backfill_installed_content':not new_instances(stages['boot1-normal-replay']) and not any(selected_trigger(row) for row in stages['boot1-normal-replay']['story_triggers']) and any(item.get('source')==c.ENDING and item.get('duplicate') is True for item in entries.get('SOURCE_REPLAY',[])),
        'console_admin_explicitly_registers_each_exact_new_version':all(any(item.get('target')=='server' and item.get('command')==f'chronicle evaluate {c.ENDING} {story} {version}' for item in commands) for story,version in [(c.ARCHIVE,'2'),(c.READER,'1')]),
        'explicit_registration_pins_eight_seeds_per_story_without_immediate_execution':not new_instances(queued) and current_pending_count(queued,c.ARCHIVE,'2')==8 and current_pending_count(queued,c.READER,'1')==8 and all(honor(queued,who)==c.BASE_HONOR[who] for who in c.PLAYERS),
        'trusted_api_retries_keep_the_already_registered_natural_keys':all(any(item.get('storyId')==story and item.get('version')==version and item.get('status')=='ALREADY_REGISTERED' and item.get('newSeeds')==0 for item in entries.get('EVALUATED',[])) for story,version in [(c.ARCHIVE,'2'),(c.READER,'1')]),
        'disabled_old_and_wrong_current_versions_are_rejected':any(item.get('story')==c.ARCHIVE and item.get('version')=='1' for item in entries.get('EVALUATION_REJECTED',[])) and any(item.get('story')==c.READER and item.get('version')=='2' for item in entries.get('EVALUATION_REJECTED',[])),
        'first_restart_retains_offline_registration_before_clients_join':not new_instances(stages['boot2-offline']) and current_pending_count(stages['boot2-offline'],c.ARCHIVE,'2')==8 and current_pending_count(stages['boot2-offline'],c.READER,'1')==8,
        'fresh_real_logins_activate_only_completed_a1_holders_automatically':len(new_instances(stages['boot2-joined']))==6 and all(c.PLAYERS['B'] not in item['members'] for item in new_instances(stages['boot2-joined'])),
        'retired_a1_personal_choices_remain_distinct_in_live_recollections':all(canonical_past(who,c.RETIRED_REF,choice) for who,choice in [('A','testimony'),('C','witness'),('D','reconcile')]) and canonical_past('D','past.player.choice.last-heir/1/council','oppose'),
        'failed_same_version_reload_preserves_catalog_and_actual_runtime':stages['boot2-before-bad-reload']['story_definitions']==stages['boot2-bad-reload']['story_definitions'] and enabled('before-bad')==enabled('bad-reload') and '重载失败' in logs[1] and any(item.get('story')==c.ARCHIVE and item.get('version')=='2' and 'rejected' not in item.get('title','') for item in runtimes.get('bad-reload',{}).get('enabled',[])),
        'restoring_exact_content_keeps_the_same_published_fingerprints':enabled('published')==enabled('restored') and stages['boot2-restored']['fixture_content_sha256']==manifest['fixture_content_sha256'],
        'old_b_stays_active_and_new_scope_receipts_wait_across_second_restart':all(original_b_unchanged(stages[name]) and not selected_receipt(stages[name],c.ARCHIVE,'2','B') and not selected_receipt(stages[name],c.READER,'1','B') for name in ['boot2-joined','boot2-completed','boot2-final','boot3-offline','boot3-joined']),
        'actual_gui_completes_three_new_personal_runs_each_before_second_stop':len(new_instances(stages['boot2-completed']))==6 and all(item['completed'] for item in new_instances(stages['boot2-completed'])) and all(honor(stages['boot2-completed'],who)==22 for who in ['A','C','D']) and honor(stages['boot2-completed'],'B')==15,
        'retired_old_b_really_completes_through_its_original_gui_on_boot3':rows_by(stages['boot3-old-b-completed'],'instances')[old_b['id']]['completed']==1 and personal_choice(final,'B',c.ARCHIVE,'1','archive')=='testimony' and any(kind=='CLICKED' and item.get('boot')==3 and item.get('player')=='B' and item.get('choice')=='testimony' and '旧都档案：每个人的那一票' in title(item.get('windowTitle')) for kind,item in protocol),
        'all_actual_a2_gui_choices_are_recorded':a2_gui,
        'all_actual_b_gui_choices_match_the_exact_retired_personal_choice':gui_branches,
        'eight_new_completed_instances_have_stable_ids_correct_single_members_and_original_source':correct_final,
        'final_honor_is_exactly_22_for_each_actual_player':all(honor(final,who)==22 for who in c.PLAYERS),
        'live_choice_ledger_preserves_both_old_and_new_versions':all(personal_choice(final,who,c.ARCHIVE,'2','new-record')=='annotate' and personal_choice(final,who,c.READER,'1','recall')==choice for who,choice in chosen.items()) and len(final['story_choice_log'])==len(source['story_choice_log'])+18,
        'new_personal_history_has_correct_parent_root_depth_and_story_path':histories_correct,
        'new_history_types_and_each_original_player_nation_match_exactly':history_members_correct and Counter(row['type'] for row in new_history)==expected_history_types,
        'all_previously_completed_json_and_original_source_payloads_are_immutable':all(rows_by(final,'instances').get(key)==row for key,row in old_completed.items()) and source_invariant,
        'original_choice_log_rows_and_done_outcome_payloads_remain_exact':all(row in final['story_choice_log'] for row in source['story_choice_log']) and all(rows_by(final,'outcomes').get(row['id'])==row for row in source['outcomes']),
        'old_source_npc_feedback_is_not_applied_again':old_npc==final_npc,
        'completed_evaluations_and_source_replays_do_not_repeat_rewards_or_history':all(stages['boot3-completed'][table]==stages['boot3-replayed'][table]==final[table] for table in ['instances','outcomes','story_choice_log','events','event_causes','facts']) and all(any(item.get('player')==who and item.get('story')==story and item.get('version')==version and 'IllegalStateException' in item.get('error','') for item in entries.get('COMPLETED_REJECTED',[])) for who in c.PLAYERS for story,version in [(c.ARCHIVE,'2'),(c.READER,'1')]),
        'boot3_itself_observes_both_console_and_api_retries_and_source_duplicates':all(any(item.get('target')=='server' and item.get('command')==f'chronicle evaluate {c.ENDING} {story} {version}' for item in boot3_commands) and any(item.get('storyId')==story and item.get('version')==version and item.get('status')=='ALREADY_REGISTERED' and item.get('newSeeds')==0 and item.get('matchingInstances')==4 and item.get('completedInstances')==4 for item in boot3_entries.get('EVALUATED',[])) for story,version in [(c.ARCHIVE,'2'),(c.READER,'1')]) and logs[2].count('ALREADY_REGISTERED；新增受众种子=0；既有同源实例=4（已完成=4）')==2 and all(any(item.get('source')==source_id and item.get('duplicate') is True and item.get('instances')==[] for item in boot3_entries.get('SOURCE_REPLAY',[])) for source_id in [c.ENDING,c.DISCOVERY]),
        'boot3_itself_rejects_all_eight_completed_choices':all(any(item.get('player')==who and item.get('story')==story and item.get('version')==version and 'IllegalStateException' in item.get('error','') for item in boot3_entries.get('COMPLETED_REJECTED',[])) for who in c.PLAYERS for story,version in [(c.ARCHIVE,'2'),(c.READER,'1')]),
        'final_native_and_causal_state_has_no_unresolved_execution':not final['story_trigger_blocks'] and all(row['status'] not in ['UNKNOWN','RUNNING'] for row in final['native_scene_journal']) and all(row['status']=='DONE' for row in final['outcomes']),
        'all_three_real_server_and_client_processes_exit_normally':processes['server_exit_codes']==[0,0,0] and processes['client_exit_codes']==[0,0,0] and len(processes['boots'])==3 and all(item.get('normal_console_stop_requested') and item.get('clients_quit_requested') and item.get('all_owned_children_exited') and item.get('plugin_sha256_after')==args.expected_sha and item.get('source_files_unchanged') and 'launcher_problem' not in item for item in processes['boots']),
        'extra_failed_preflights_are_retained_separately_and_normally_recovered':not processes.get('preflight_attempts') or len(processes['preflight_attempts'])==2 and {item.get('server_pid') for item in processes['preflight_attempts']}=={4188,6636} and all(item.get('server_exit_code')==0 and item.get('client_pid') is None and item.get('recovered_normal_bukkit_shutdown') is True for item in processes['preflight_attempts']) and all((evidence/name).is_file() and json.loads((evidence/name).read_text(encoding='utf-8')).get('server_exit_code')==0 for name in ['preflight-recovery.json','preflight2-recovery.json']),
        'all_four_clients_normally_disconnect_in_every_boot':all(all(any(kind=='ENDED' and item.get('boot')==boot and item.get('player')==who and item.get('reason')=='phase three acceptance complete' for kind,item in protocol) for who in c.PLAYERS) for boot in [1,2,3]),
        'no_fixture_protocol_or_product_runtime_failure_is_hidden':'PHASE3_FAILED' not in server and not any(kind in ['ERROR','KICKED','ACTION_FAILED','IDENTITY_FAILED','TIMEOUT'] for kind,item in protocol) and not any('[StarChronicle]' in line and (' WARN]' in line or ' ERROR]' in line) for line in server.splitlines()),
        'actual_history_and_version_gui_inventory_components_are_retained':all(any(label in title(item.get('window',{}).get('title')) for kind,item in protocol if kind=='CLIENT_VIEW' and item.get('window')) for label in ['旧都档案·第二卷','旧档中的版本坐标','世界编年史','历史证据']),
    }
    summary={'status':'PASS' if all(checks.values()) else 'FAIL','plugin_sha256':args.expected_sha,'fixture_sha256':manifest['fixture_sha256'],
             'run_name':run.name,'server_exit_codes':processes['server_exit_codes'],'client_exit_codes':processes['client_exit_codes'],
             'fixture_compile_exit_code':manifest['fixture_compile_exit_code'],'fixture_compile_attempts':manifest.get('fixture_compile_attempts',[]),
             'preflight_attempts':processes.get('preflight_attempts',[]),
             'all_observed_server_exit_codes':[item.get('server_exit_code') for item in processes.get('preflight_attempts',[])]+processes['server_exit_codes'],
             'checks':checks,'scope':manifest['scope']+'; two extra pre-client console preflights are retained separately and are not counted among the three complete acceptance boots',
             'source_database_sha256':manifest['source_database_sha256']}
    c.write_json(evidence/'phase-three-paper-summary.json',summary)
    failed=[name for name,okay in checks.items() if not okay]
    if failed:raise SystemExit('Actual phase-three assertions failed; no public PASS report generated: '+', '.join(failed))
    lines=['# Phase-three Paper: retired definitions and explicit historical evaluation','','Result: **PASS** for the exact tested artifact.','',
           '- Product SHA-256: `'+args.expected_sha+'`.','- Fixture SHA-256: `'+manifest['fixture_sha256']+'`.',
           '- Actual server exits: `'+str(processes['server_exit_codes'])+'`; client controller exits: `'+str(processes['client_exit_codes'])+'`.',
           '- Real stopped schema-three source DB SHA-256: `'+manifest['source_database_sha256']+'`.','',
           '## Verified checks','']+['- '+name.replace('_',' ')+': PASS.' for name in checks]
    lines+=['','## Actual scenario and boundaries','',
            'A new isolated clone of the normally stopped phase-two Paper/StarCore data was upgraded by the real production constructor. The original run files and old canonical evidence remained unchanged. The actual effective Paper last-heir/1 native binding was archived; no unbound standalone definition replaced it.',
            '', 'The current heir-legacy/1 file was retired while its original bytes and archived graph remained available. The new current heir-legacy/2 and phase-three-catalog-reader/1 were accepted only after a pure validate operation proved it had not written the catalog. The new reader uses the exact retired personal archive reference. A non-operator actual player could not evaluate historical content; console administration explicitly registered only the retained original ending and named exact versions. Registration did not immediately execute a story, reapply source NPC feedback or alter historical actors.',
            '', 'Three completed old archive holders received the new personal stories after real logins. Actual GUI choices followed their original testimony/witness/reconcile records. The fourth player’s old archive remained active across a further normal restart, while its new version’s receipt stayed unconsumed. After its actual old GUI completion, the new registered stories became available. A deliberately invalid same-version reload was rejected while both runtime fingerprints and catalog rows stayed unchanged.',
            '', 'The third boot completed all eight new personal instances and repeated the exact historical registration, original source redelivery and completed choices. Stable IDs, history, choice logs, outcomes and honor did not repeat. All four actual clients remained non-operators and retained their real original StarCore countries.',
            '', 'The source discovery and ending are retained explicitly test-only receipts from the earlier actual-client run, not natural production-world history. New personal stories assert current narrative choices, not past eligibility. Protocol GUI packets do not claim human visual approval. This run does not establish full StarCore/Folia compatibility, real combat, or external reward/quest cross-restart exactly-once delivery.',
            '', '## Retained preparation observation','',
            'The preparation manifest retains one failed javac tooling attempt: a VM -J option was initially placed in the javac argument file, which javac rejected before producing classes. The option was moved to the actual compiler command line and the next actual compilation exited zero. Both observed attempts and the original log remain available. No server or product/database state was reset to erase that observation.',
            '', 'Two additional initial pre-client server attempts reached product READY; the first migrated the clone before its Python console owner exited on a GBK Unicode printing error. The second launcher had no interactive stdin because its tool exec call omitted tty=true. Both are retained separately and are not counted among the three complete acceptance boots. Each exact owned PID (4188, then 6636) was verified against the actual Bukkit world directory and frozen JAR, then a bounded local Attach agent used the actual fixture plugin scheduler to invoke normal Bukkit shutdown. Held Win32 process handles observed both server exits 0; the second launcher also directly observed server exit 0 and itself exited 1 because it never started a client controller. Agent sources, commands, logs, original failed process tables and shutdown markers remain available. The launcher was repaired to force UTF-8 output, clean up on exceptions and stop on stdin EOF; subsequent interactive tool calls used tty=true. The same database/world continued without reset.',
            '', 'The 22 named read-only stages, actual process table, actual client inventory components and sanitized complete server logs accompany this report. The preparation manifest, both compiler attempts, all retained preflight logs and the recovery source files are published with a SHA-256 inventory. The new history type distribution is exactly four annotations, two testimony memories, one witness memory and one reconcile memory, with every event retaining its original player-to-country pairing. Third-boot repeat checks are scoped to its own actual logs and commands.','']
    report='\n'.join(lines);(evidence/'phase-three-paper-report.md').write_text(report,encoding='utf-8')
    public=c.ROOT/'docs/verification'
    for name in ['source-schema3']+STAGES:
        shutil.copy2(evidence/(name+'.json'),public/('phase-three-paper-'+name+'.json'))
    for boot in [1,2,3]:shutil.copy2(evidence/f'boot{boot}-final.log',public/f'phase-three-paper-boot{boot}-final.log')
    for name in ['phase-three-paper-clients.log','phase-three-paper-processes.json']:
        shutil.copy2(evidence/name,public/name)
    for path in sorted(evidence.glob('preflight*.json')):
        shutil.copy2(path,public/('phase-three-paper-'+path.name))
    for path in sorted(evidence.glob('preflight*-complete-server.log')):
        text=path.read_text(encoding='utf-8',errors='replace')
        sanitized='\n'.join('[REDACTED: credential-related server log line]' if re.search(r'(?i)secret|password|bearer|api[-_ ]?key|token\s*[:=]',line) else line for line in text.splitlines())+'\n'
        (public/('phase-three-paper-'+path.name)).write_text(sanitized,encoding='utf-8')
    shutil.copy2(run/'preparation.json',public/'phase-three-paper-preparation.json')
    for path in sorted(evidence.glob('*.log')):
        if not (path.name.startswith('preflight') or path.name.startswith('fixture-compile')):continue
        text=path.read_text(encoding='utf-8',errors='replace')
        sanitized='\n'.join('[REDACTED: credential-related server log line]' if re.search(r'(?i)secret|password|bearer|api[-_ ]?key|token\s*[:=]',line) else line for line in text.splitlines())+'\n'
        (public/('phase-three-paper-'+path.name)).write_text(sanitized,encoding='utf-8')
    tool_names=['phase-three-paper-common.py','prepare-phase-three-paper.py','build-phase-three-paper-fixture.py','PhaseThreePaperFixture.java','phase-three-paper-clients.cjs','inspect-phase-three-paper.py','phase-three-paper-session.py','report-phase-three-paper.py','PHASE-THREE-PAPER-RUN.md','PhaseThreePaperStopAgent.java','recover-phase-three-paper.py','recover-phase-three-paper-console.py']
    for name in tool_names:shutil.copy2(c.ROOT/'tools'/name,public/('phase-three-paper-tool-'+name))
    c.write_json(public/'phase-three-paper-summary.json',summary)
    (public/'phase-three-paper-report.md').write_text(report,encoding='utf-8')
    inventory={'plugin_sha256':args.expected_sha,'source_database_sha256':manifest['source_database_sha256'],
               'source_files_still_unchanged':c.source_unchanged(manifest),
               'source_preserved_sha256':manifest['source_preserved_sha256'],
               'source_current_sha256':{name:c.digest(c.ROOT/manifest['source_run']/name) if (c.ROOT/manifest['source_run']/name).is_file() else None for name in manifest['source_preserved_sha256']},
               'current_run_database_sha256':{name:c.digest(run/name) for name in manifest['source_preserved_sha256'] if name.endswith('.db') and (run/name).is_file()},
               'current_product_sha256':c.digest(run/'plugins/StarChronicle.jar'),
               'preparation_manifest_sha256':c.digest(run/'preparation.json'),
               'raw_run_evidence_sha256':{path.name:c.digest(path) for path in sorted(evidence.iterdir()) if path.is_file()},
               'tool_sources_sha256':{name:c.digest(c.ROOT/'tools'/name) for name in tool_names},
               'published_evidence_sha256':{path.name:c.digest(path) for path in sorted(public.glob('phase-three-paper*')) if path.is_file() and path.name!='phase-three-paper-evidence-inventory.json'}}
    c.write_json(public/'phase-three-paper-evidence-inventory.json',inventory)
    print('PASS:',len(checks),'real Paper assertions; product SHA-256',args.expected_sha)


if __name__=='__main__':main()
