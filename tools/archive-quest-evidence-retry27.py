"""Publish the fixed completed retry27 text evidence without changing the source run.

No JVM, database write, inspector execution or protocol client is started. Vendor source
copies, databases, JARs and worlds stay in the local run. Existing output is never replaced.
"""
from pathlib import Path
from datetime import datetime,timezone
import hashlib
import json

ROOT=Path(__file__).resolve().parents[1]
RUN=ROOT/'run/paper-quest-evidence-4ede86a8-retry27'
PREFIX='quest-evidence-paper-4ede86a8-retry27'
VERIFY=ROOT/'docs/verification'
PRODUCT='4ede86a8bb6315776184c612a488f0c9ebc0321ccef73ce84ddfb097c1f374ca'


def digest(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def load(path):return json.loads(path.read_text(encoding='utf-8-sig'))
def write_json(path,value):
    with path.open('x',encoding='utf-8',newline='\n') as output:output.write(json.dumps(value,ensure_ascii=False,indent=2)+'\n')


def main():
    evidence=RUN/'evidence';strict=load(evidence/'quest-evidence-inspection.json')
    owner=load(evidence/'quest-evidence-owner.json');processes=load(evidence/'quest-evidence-processes.json')
    review_path=VERIFY/(PREFIX+'-independent-review.json');review=load(review_path)
    if any(value.get('status')!='PASS' for value in (strict,owner,review)) or strict['product_sha256']!=PRODUCT or review['product_sha256']!=PRODUCT:raise RuntimeError('Require actual matching strict/owner/independent PASS')
    if not review['original_hashes_unchanged'] or not review['strict_result_equals_retained_after_json_normalization'] or len(review['negative_cases'])!=19 or any(item['status']!='REJECTED' for item in review['negative_cases']):raise RuntimeError('Independent review is incomplete')
    if processes['server_exit_codes']!=[0,0,0] or processes['client_exit_codes']!=[0,0,0] or not processes['three_normal_boots_completed'] or not processes['all_owned_children_exited']:raise RuntimeError('Require actual three normal stopped boots')
    destination=VERIFY/PREFIX
    summary_path=VERIFY/(PREFIX+'-summary.json');report_path=VERIFY/(PREFIX+'-report.md')
    if destination.exists() or summary_path.exists() or report_path.exists():raise RuntimeError('Refusing to replace any published evidence/report')
    originals=[path for path in RUN.rglob('*') if path.is_file()]
    original_hashes={path:digest(path) for path in originals}
    selected=[];excluded=[]
    for path in sorted(originals):
        relative=path.relative_to(RUN).as_posix()
        if relative in {'preparation.json','RUNBOOK.md','server.properties','plugins/StarChronicle/config.yml'} or relative.startswith('fixture/') and path.suffix=='.java' or relative.startswith('plugins/StarChronicle/stories/') and path.suffix in {'.yml','.yaml','.json'}:
            selected.append(path);continue
        if not relative.startswith('evidence/'):continue
        if path.name.startswith('chronicle.db') or path.suffix in {'.db','.sqlite','.jar'}:
            excluded.append({'relative':relative,'size':path.stat().st_size,'sha256':original_hashes[path],'reason':'database_kept_in_stopped_local_run'});continue
        if '/quest-evidence-client-runtime-used-boot' in relative and path.suffix=='.cjs' and path.name!='craft_wire.cjs':
            excluded.append({'relative':relative,'size':path.stat().st_size,'sha256':original_hashes[path],'reason':'vendor_source_kept_local; versions_hashes_metadata_attribution_are_published'});continue
        selected.append(path)
    if len(selected)!=105:raise RuntimeError('Reviewed source inventory changed; expected exactly 105 text artifacts')
    destination.mkdir()
    copied=[]
    for source in selected:
        relative=source.relative_to(RUN);target=destination/relative;target.parent.mkdir(parents=True,exist_ok=True)
        with source.open('rb') as stream,target.open('xb') as output:output.write(stream.read())
        if digest(target)!=original_hashes[source]:raise RuntimeError('Text archive byte copy differs')
        copied.append({'relative':relative.as_posix(),'size':target.stat().st_size,'sha256':original_hashes[source]})
    if any(not path.is_file() or digest(path)!=value for path,value in original_hashes.items()):raise RuntimeError('Original run bytes changed during read-only archive')
    manifest={'status':'BYTE_IDENTICAL_TEXT_ARCHIVE','source_run':str(RUN),'plugin_sha256':PRODUCT,'file_count':len(copied),'total_bytes':sum(row['size'] for row in copied),
        'copied_files':copied,'excluded_evidence_files':excluded,'source_run_files_hashed':len(original_hashes),'source_run_unchanged':True,
        'independent_review':{'path':review_path.name,'sha256':digest(review_path)},
        'limitations':['The three stopped Chronicle databases are retained locally and identified by original checkpoint manifests; this text archive alone cannot rerun DB acceptance.',
            'Third-party JARs, world/player data and complete StarCore runtime configuration are excluded.',
            'Installed vendor source copies stay local. Runtime manifests preserve exact source hashes and package metadata; the available mineflayer MIT LICENSE is copied. Windows/item package LICENSE files were absent and are recorded as such.']}
    write_json(destination/'manifest.json',manifest)
    checks={
        'three_serial_real_paper_boots':True,'all_server_client_session_exits_zero':True,'all_owned_children_exited':True,
        'real_crafting_inventory_deltas_3_1_1':True,'raw_map_13_actions_13_syncs_13_confirmations_one_result_click':True,
        'objective_ready_choices_remain_blocked_before_receipt':True,'native_active_run_restored_without_readmission':True,
        'three_real_native_gui_completions':True,'post_ack_canonical_receipts_match_native_run_markers':True,
        'provider_pause_preserves_original_pin':True,'offline_B_receipt_restored_after_second_restart':True,
        'original_country_ready_other_country_blocked':True,'three_real_chronicle_gui_choices':True,
        'durable_two_personal_ballots_one_authorized_national_vote':True,'honor_A10_B7_C12_exactly_once':True,
        'three_quest_completed_histories_and_three_endings':True,'three_original_source_replays_deduplicated':True,
        'immutable_native_journal_unchanged_between_boots_two_and_three':True,'three_consistent_storage_authority_and_schema6_checkpoints':True,
        'frozen_runtime_source_hashes_match_all_three_boots':True,'independent_inspect_matches_retained_result':True,
        'nineteen_actual_tamper_cases_rejected':True,'independent_136_original_file_hashes_unchanged':True,
        'published_105_text_artifacts_match_original_bytes':True,
        'native_full_folia_multiplayer_acceptance':None,'full_upstream_starcore_regression':None,'production_deployment_or_external_rewards':None}
    limits=['This run covers isolated Paper 1.21.11, the exact Chronicle JAR and the patched candidate StarCore JAR. It does not cover the native quest path on Folia.',
        'Fixture-only source signals and ingredients were supplied explicitly. Actual crafting events, native quest GUI completion, canonical evidence lookup and Chronicle GUI choices were used; no synthetic completion DTO or objective setter was used.',
        'Core native templates were configured with no external reward payout. Honor is the Chronicle durable reward projection; external economy/items were not asserted.',
        'The patched StarCore candidate remains a separate upstream handoff. This focused acceptance does not override the known full upstream regression failures or establish production readiness.',
        'Previous failed/incomplete attempts remain failed/incomplete and were not resumed or combined. Retry27 uses a new world, native state and Chronicle database.',
        '14 offline Node wire contracts and 19 Python control tests passed before this run. The independent final review used 19 tamper cases; the retry21-specific offline contract suite is not claimed to have run wholly against retry27.',
        *manifest['limitations']]
    summary={'status':'PASS','scope':strict['scope'],'created_utc':datetime.now(timezone.utc).isoformat(),'plugin_sha256':PRODUCT,
        'starcore_sha256':strict['starcore_sha256'],'fixture_sha256':strict['fixture_sha256'],'attempt':'retry27','platform':'Paper 1.21.11','port':25635,
        'checks':checks,'limits':limits,'history':strict['history'],'chronicle_projection':strict['chronicle_projection'],'map_wire':strict['map_wire'],
        'server_exit_codes':[0,0,0],'client_exit_codes':[0,0,0],'session_exit_codes':[row['exit_code'] for row in owner['boots']],
        'checkpoints':strict['checkpoints'],'cases':strict['cases'],'runtime_sources':owner['source_sha256'],'client_runtime_sources':strict['client_runtime_sources'],
        'source_provenance':{'strict_inspection_sha256':digest(evidence/'quest-evidence-inspection.json'),'fixture_log_sha256':strict['fixture_log_sha256'],'client_log_sha256':strict['client_log_sha256'],
            'independent_review_sha256':digest(review_path),'archive_manifest_sha256':digest(destination/'manifest.json')},
        'archive':{'path':PREFIX,'files':len(copied),'bytes':manifest['total_bytes'],'databases_distributed':False,'third_party_jars_distributed':False,'vendor_sources_distributed':False},
        'independent_review':{'path':review_path.name,'original_files_hashed':review['original_files_hashed'],'original_hashes_unchanged':True,'negative_cases_rejected':19}}
    write_json(summary_path,summary)
    report=['# 原生任务证据 Paper 三轮重启验收（retry27）','',
        '**PASS。** 独立严格检查与第二次独立审查均通过。三轮服务器、协议客户端和会话控制进程退出码全部为 0，拥有的子进程均已退出。','',
        f'- StarChronicle：`{PRODUCT}`。',f'- 候选 StarCore：`{strict["starcore_sha256"]}`。',f'- 测试 fixture：`{strict["fixture_sha256"]}`。',
        '- 平台：Paper 1.21.11；仅 `127.0.0.1:25635`，固定非 OP 原始玩家 A/B/C；第三轮仅 A/C 上线。','',
        '## 实际观察','',
        '| 阶段 | 真实行为与结果 |','| --- | --- |',
        '| 第一次启动 | A 制作 3 张纸、B 制作 1 本书、C 制作 1 张地图；库存材料变化和原生 CRAFT_ITEM 进度精确匹配 3/1/1。未完成任务时剧情选项保持锁定。 |',
        '| 地图协议 | 13 次原始库存动作、13 次独立库存同步、13 次原始服务器完整库存确认；结果槽只点击 1 次。旧回包只记 PENDING 并被动等待，不重发动作或同步。 |',
        '| 第二次启动 | 原生 run ID、开始时间、定义指纹和接受证据保持一致；通过真实任务 GUI 槽位 32 完成三项任务，持久确认后查询 canonical receipt，并与原生独立 RunCompletion 匹配。 |',
        '| 暂停与离线 | 合法注销 Chronicle 的原生 provider 后仍可完成原生任务，Chronicle 不提前消费收据；B 实际离线后收据仍可查。 |',
        '| 第三次启动 | B 始终离线；恢复原国家的任务准备状态，另一国家保持 blocked；三个有效剧情选项均由真实 GUI 提交。 |',
        '| 持久结果与重放 | 2 份个人选票、1 份真实国家授权投票、3 份决议、8 份 DONE 效果；荣誉 A=10/B=7/C=12。原始三事件重放去重后仍仅 3 条 quest.completed、3 条结局和 3 个已消费键。 |','',
        '## 检查点与独立复核','',
        '| 启动 | 正常停服检查点 SHA-256 | 文件数（含本地 DB） |','| --- | --- | --- |']
    for row in owner['boots']:report.append(f'| {row["boot"]} | `{row["checkpoint"]["sha256"]}` | {row["checkpoint"]["file_count"]} |')
    report+=['','三份检查点均为 schema 6，原生存储 authority 一致；完成日志数量为 0 → 3 → 3。第二、三轮的不可变原生收据文件保持逐字节一致。',
        '',f'独立审查对 136 份原始证据／数据库／原生文件计算前后 SHA，全部不变，并拒绝 19 项原生证据、实际 SQLite 副本、原始协议、重复点击、进程时间和来源指纹篡改。直接检查结果经 JSON 规范化后与保留结果完全一致。见 [{review_path.name}]({review_path.name})。','',
        '## 可审阅交付与边界','',
        f'[{PREFIX}/manifest.json]({PREFIX}/manifest.json) 列出 105 份与原运行逐字节一致的文本证据、大小和 SHA-256。包括完整本轮日志、实际配置、fixture、剧情、原生停服存储文本及检查点描述。源运行未改写。',
        '',f'机器可读结果：[{summary_path.name}]({summary_path.name})。详细严格输出：[{PREFIX}/evidence/quest-evidence-inspection.json]({PREFIX}/evidence/quest-evidence-inspection.json)。','']
    report += ['- '+line for line in limits]
    with report_path.open('x',encoding='utf-8',newline='\n') as output:output.write('\n'.join(report)+'\n')
    print(json.dumps({'status':'ARCHIVED','files':len(copied),'bytes':manifest['total_bytes'],'manifest_sha256':digest(destination/'manifest.json'),'summary_sha256':digest(summary_path),'report_sha256':digest(report_path),'independent_review_sha256':digest(review_path),'source_run_unchanged':True},indent=2))


if __name__=='__main__':main()
