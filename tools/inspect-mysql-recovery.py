"""Audit offline restore and real post-replay process interruption evidence without a live DB.

The private full backup is intentionally separate. Public proofs retain its digest and
verified file inventory. The preceding accepted archive pins the original checkpoints.
"""
from pathlib import Path
from contextlib import closing
from functools import lru_cache
import argparse
import hashlib
import importlib.util
import json
import re
import sqlite3
import zipfile
import yaml
import mysql_recovery as R


def load(name):
    spec=importlib.util.spec_from_file_location(name,Path(__file__).with_name(name));module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module

I=load('inspect-mysql-paper.py');P=load('inspect-nation-production-paper.py');T=P.T
check,read,sha=R.check,R.read,R.M.sha
PRIOR_SHA='08670c71b91c4eb3038cf9f5659623ed4dfd0d15174df282544a74ea99982e82'


@lru_cache(maxsize=1)
def prior_inventory():
    path=R.ROOT/'docs/verification/mysql-paper-20261125-evidence.zip'
    check(sha(path)==PRIOR_SHA,'Prior accepted archive differs')
    with zipfile.ZipFile(path) as z:return json.loads(z.read('evidence-manifest.json'))


def sqlite_state(path):
    with closing(T.connect(path)) as db:
        schema=[list(r) for r in db.execute("SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY type,name")]
        tables={r[0]:sorted([list(v) for v in db.execute('SELECT * FROM "'+r[0]+'"')],key=R.M.compact) for r in db.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")}
        return dict(version=db.execute('pragma user_version').fetchone()[0],schema=schema,tables=tables)


def chronicle_replay_equal(baseline, current):
    """Login may advance the pending trigger scan counter; all content stays exact."""
    import copy
    actual=copy.deepcopy(current)
    original_rows=baseline['tables'].get('story_triggers',[]);rows=actual['tables'].get('story_triggers',[])
    check(len(rows)==len(original_rows),'Recovery changed pending story triggers')
    for before,after in zip(original_rows,rows):
        check(before[:-1]==after[:-1] and isinstance(after[-1],int) and after[-1]>=before[-1],'Recovery changed story trigger identity/content or regressed scan counter')
        after[-1]=before[-1]
    check(actual==baseline,'Recovery replay changed Chronicle rows/schema')


def protected_native(raw):
    """Only ordinary save revision counters may advance; assets/receipts/actors may not."""
    prefixes=('starcore_nation_','starcore_resource_','starcore_treasury_','starcore_war','starcore_battlefield','starcore_diplomacy_invite','starcore_diplomacy_acceptance','starcore_technology_')
    result={}
    for t in raw['tables']:
        if not t['name'].startswith(prefixes) or t['name']=='starcore_resource_revision':continue
        rows=[r for r in t['rows'] if not (t['columns'][0][0]=='property_key' and r[0]=='__starcore_revision__')]
        result[t['name']]=dict(columns=t['columns'],rows=rows)
    return result


def replay_commands(manifest,fixture):
    rows=[]
    def cmd(who,text,response):rows.append((who,text,response))
    if 'nation_production_acceptance' in manifest:
        runs={r['data']['key']:r['data']['run']['runId'] for r in fixture if r['kind']=='PRODUCTION_RUN'}
        offline=manifest['nation_production_acceptance']['mode']=='offline'
        if offline:
            cmd('A','/nationproduction complete '+runs['primary'],'没有重复扣除或发放');cmd('A','/chronicle contributions','生产资源 industrial')
        else:
            cmd('D','/nationproduction complete '+runs['cancelled'],'请求被拒绝')
            for key,who in [('primary','A'),('pending','A'),('old-founder','D'),('successor','D')]:cmd(who,'/nationproduction complete '+runs[key],'没有重复扣除或发放')
            for who in 'AD':cmd(who,'/chronicle contributions','生产资源 industrial')
            cmd('D','/chronicle contributions nation me','生产资源 industrial')
    elif 'nation_trade_acceptance' in manifest:
        offers={r['data']['key']:r['data']['offer']['offerId'] for r in fixture if r['kind']=='SALE_OFFER'}
        for key in ('old-founder','cancelled','primary','pending','successor'):cmd('B','/nationtrade accept '+offers[key],'操作被拒绝' if key in ('old-founder','cancelled') else '没有重复成交')
        for who in 'AD':cmd(who,'/chronicle contributions','成交资源 agricultural')
        cmd('B','/chronicle contributions','国库支付');cmd('D','/chronicle contributions nation me','成交资源 agricultural')
    return rows


def business(manifest,fixture,native,chronicle):
    seed=next(r['data'] for r in fixture if r['kind']=='SEEDED');players=manifest['players']
    if 'nation_production_acceptance' in manifest:
        offline=manifest['nation_production_acceptance']['mode']=='offline';captured={r['data']['key']:r['data']['run'] for r in fixture if r['kind']=='PRODUCTION_RUN'}
        keys=['primary','pending'] if offline else list(P.TERMS);completed=['primary'] if offline else ['primary','pending','old-founder','successor']
        state=P.native(native,seed,players,captured,keys,completed,'A' if offline else 'D');events,contributions=P.chronicle(chronicle,state,len(completed))
        return dict(kind='production-offline' if offline else 'production',receipts=state['receipts'],events=events,contributions=contributions,output=state['delivered'])
    if 'nation_trade_acceptance' in manifest:
        offers={r['data']['key']:r['data']['offer'] for r in fixture if r['kind']=='SALE_OFFER'}
        state=T.native(native,seed,players,3,offers);events,contributions=T.chronicle(chronicle,state)
        return dict(kind='trade',receipts=state['receipts'],events=events,contributions=contributions,assets=state['assets'])
    state=sqlite_state(chronicle)
    with closing(T.connect(chronicle)) as db:
        campaigns=T.rows(db,'select * from epic_campaigns');check(len(campaigns)==1 and campaigns[0]['status']=='COMPLETE' and campaigns[0]['route']==manifest['route'],'Recovered epic campaign is not complete')
        operations=T.rows(db,'select * from epic_operations');check(len(operations)==(13 if manifest['route']=='WAR' else 12) and all(r['status']=='COMMITTED' for r in operations),'Recovered epic operations differ')
        settled=T.rows(db,"select * from events where type='HEIR_STRATEGY_SETTLED'");check(len(settled)==1,'Recovered settlement missing or duplicated')
        contributions=T.rows(db,'select * from contributions');check(len(contributions)==8,'Recovered aid contribution count differs')
    return dict(kind=manifest['route'],settlement=json.loads(settled[0]['json']),contributions=contributions)


def inspect(run, *, artifacts=False, public_redacted=False):
    run=Path(run).resolve();e=run/'evidence/recovery';manifest=read(run/'preparation.json');binding=manifest['recovery_acceptance'];target=R.M.config(run,manifest)
    check(not (run/'restore-incomplete.json').exists(),'Incomplete restore')
    R.port_free(manifest['server_port'])
    proof=read(e/'backup-manifest.json');restore=read(e/'restore.json');source=read(e/'source-native.json');restored=read(e/'restored-native.json');original=read(e/'source-preparation.json');plan=read(e/'acceptance-plan.json')
    def config_sha(path):
        if not public_redacted:return sha(path)
        name=path.relative_to(run).as_posix();redactions=read(run/'public-redactions.json')
        if name not in redactions:return sha(path)
        check(name in ('plugins/StarCore/config.yml','evidence/recovery/source-config.yml'),'Unsupported public config redaction')
        entry=redactions[name];check(sha(path)==entry['redacted_sha256'] and entry['fields']==['map.web.access-secret'],'Public config redaction differs')
        check(yaml.safe_load(path.read_text())['map']['web']['access-secret']=='REDACTED_TEST_SECRET','Public secret placeholder differs')
        return entry['original_sha256']
    source_boot=binding['source_boot'];first=plan['first_boot'];last=first+2
    check(proof['format']==1 and proof['consistency']=='STOPPED_GAME_AND_QUIESCENT_NATIVE_DATABASE' and proof['private'] is True,'Invalid coordinated backup boundary')
    check(proof['source_target']==original['mysql_acceptance'] and proof['stopped_session']==read(run/f'evidence/boot{source_boot}.session.json'),'Backup source/session differs')
    check(proof['stopped_session']['status']=='COMPLETE' and all(p['exit_code']==0 for p in proof['stopped_session']['processes']),'Source was not normally stopped')
    check(sha(e/'source-preparation.json')==proof['source_preparation_sha256']==proof['files']['preparation.json']['sha256'],'Original preparation hash differs')
    check(sha(e/'source-native.json')==proof['database_files']['native.json']['sha256'] and set(proof['database_files'])=={'native.json','native.sql'},'Native dump/snapshot proof differs')
    check(restore['status']=='FULL_OFFLINE_BACKUP_RESTORED' and restore['game_started'] is False and restore['backup_sha256']==binding['backup_sha256'] and re.fullmatch('[0-9a-f]{64}',binding['backup_sha256']),'Restore completion/bundle differs')
    check(restore['source_server_uuid']==source['server_uuid']==original['mysql_acceptance']['server_uuid'] and restore['target_server_uuid']==restored['server_uuid']==target['server_uuid'] and source['server_uuid']!=restored['server_uuid'],'Restore did not use a new MySQL authority instance')
    check(target==restore['target'] and target['container_id']!=original['mysql_acceptance']['container_id'] and target['database']==source['database'],'Wrong independent restore target')
    R.equal_data(source,restored)
    check(len(source['tables'])==211 and len(source['views'])==1 and all(t['engine']=='InnoDB' for t in source['tables']),'Incomplete native catalog restore')
    check(source['innodb_flush_log_at_trx_commit']==restored['innodb_flush_log_at_trx_commit']==1 and source['sync_binlog']==restored['sync_binlog']==1 and source['ssl_cipher'] and restored['ssl_cipher'],'Restore durability/encryption differs')
    expected={'preparation.json','server.properties','plugins/StarCore/config.yml'};changes=restore['permitted_changes'];restored_files=read(e/'restored-files.json')
    check(set(changes)==expected and set(restored_files)==set(proof['files']) and restore['files_verified']==len(proof['files']),'Restore file inventory/allowlist differs')
    for name,entry in restored_files.items():
        if name in expected:check(changes[name]==dict(before_sha256=proof['files'][name]['sha256'],after_sha256=entry['sha256']),'Permitted file change digest differs')
        else:check(entry==proof['files'][name],'Unexpected restored file change: '+name)
    check(sha(run/'preparation.json')==restored_files['preparation.json']['sha256'] and config_sha(run/'plugins/StarCore/config.yml')==restored_files['plugins/StarCore/config.yml']['sha256'],'Restored runtime binding changed')
    check(config_sha(e/'source-config.yml')==proof['files']['plugins/StarCore/config.yml']['sha256'] and sha(e/'source-server.properties')==proof['files']['server.properties']['sha256'],'Original private configuration digest differs')
    old_config=yaml.safe_load((e/'source-config.yml').read_text());new_config=yaml.safe_load((run/'plugins/StarCore/config.yml').read_text())
    expected_config=json.loads(json.dumps(old_config));expected_config['database']['mysql'].update(host=target['host'],port=target['port'],username=target['user'],password='',**{'password-env':target['password_env']})
    check(new_config==expected_config,'Restore changed non-endpoint native configuration')
    properties=(e/'source-server.properties').read_text();expected_properties=re.sub(r'(?m)^server-port=\d+$','server-port='+str(manifest['server_port']),properties)
    check(hashlib.sha256(expected_properties.encode()).hexdigest()==changes['server.properties']['after_sha256'],'Restore changed non-port server properties')
    for name,key in [('StarChronicle','plugin_sha256'),('StarCore','starcore_sha256'),('EpicCampaignPaperFixture','fixture_sha256')]:
        check(proof['files']['plugins/'+name+'.jar']['sha256']==manifest[key]==original[key],'Restore artifact identity changed')
        if artifacts:check(sha(run/f'plugins/{name}.jar')==manifest[key],'Actual runtime artifact changed')
    if artifacts:
        check(sha(run/'paper.jar') in {value for name,value in manifest['source_artifacts'].items() if Path(name).name=='paper.jar'},'Actual Paper artifact changed')
        for artifact in manifest['production_stack']['artifacts']:check(sha(run/'plugins'/artifact['filename'])==artifact['sha256'],'Actual selected dependency changed')
    # Anchor the inherited baseline to the previously published, accepted evidence archive.
    prefix='runs/'+binding['source_run']+'/'
    for name in (f'evidence/mysql/boot{source_boot}.json',f'evidence/checkpoints/boot{source_boot}/chronicle.db',f'evidence/checkpoints/boot{source_boot}/starcore.db'):
        check(sha(run/name)==prior_inventory()[prefix+name]==proof['files'][name]['sha256'],'Accepted original checkpoint changed')
    baseline=read(run/f'evidence/mysql/boot{source_boot}.json');check(source['tables']==baseline['tables'] and source['views']==baseline['views'],'Backup data differs from original accepted shutdown')
    chronicle=run/f'evidence/checkpoints/boot{source_boot}/chronicle.db';check(sha(chronicle)==proof['files']['plugins/StarChronicle/chronicle.db']['sha256'],'Backup does not contain the paired Chronicle database')
    baseline_chronicle=sqlite_state(chronicle);baseline_native=protected_native(source)
    fixture=T.records(run/'evidence/epic-fixture.jsonl');check(not any(r['kind']=='FAILED' for r in fixture),'Fixture failure in retained stream')
    baseline_business=business(manifest,fixture,run/f'evidence/checkpoints/boot{source_boot}/starcore.db',chronicle)
    config_change=plan['config_change'];check(config_change['path']=='plugins/Essentials/config.yml','Unapproved acceptance setting change')
    before=(e/'essentials-before.yml').read_text();after=(e/'essentials-after.yml').read_text()
    check(before.count('update-check: true\n')==1 and after==before.replace('update-check: true\n','update-check: false\n'),'Essentials gameplay configuration changed')
    check(sha(e/'essentials-before.yml')==proof['files'][config_change['path']]['sha256']==config_change['before_sha256'] and sha(e/'essentials-after.yml')==config_change['after_sha256']==sha(run/config_change['path']),'Essentials setting change hash differs')
    diagnostics=plan['diagnostic_boots'];check(first==source_boot+1+len(diagnostics) and len(diagnostics)<=1,'Unexplained prior restore boots')
    for d in diagnostics:
        log=(run/d['log']).read_text();check(d['classification']=='PRECHECK_DIAGNOSTIC_NOT_ACCEPTED' and d['boot']==source_boot+1 and d['log']==f'evidence/boot{d["boot"]}.server.log' and sha(run/d['log'])==d['sha256'] and 'com.earth2me.essentials.updatecheck.UpdateChecker.' in log and 'zip file closed' in log,'Missing retained update-checker diagnostic')
    expected_scenario='production-offline-recovery' if baseline_business['kind']=='production-offline' else 'production-recovery' if baseline_business['kind']=='production' else 'sale-recovery' if baseline_business['kind']=='trade' else 'third'
    check(plan['scenarios']==[expected_scenario]*3 and plan['modes']==['normal-restored-replay','post-replay-process-interruption','normal-recovered-replay'],'Recovery execution plan differs')
    check({p.name for p in (run/'evidence').glob('boot*.session.json')}=={f'boot{n}.session.json' for n in range(1,last+1)},'Recovery session inventory differs')
    expected_commands=replay_commands(manifest,fixture);command_count=0;prior_end=restore['finished_at'];telemetry=[]
    for boot in range(first,last+1):
        crashed=boot==first+1;session=read(run/f'evidence/boot{boot}.session.json');check(session['status']==('EXPECTED_PROCESS_INTERRUPTION' if crashed else 'COMPLETE') and session['boot']==boot and session['scenario']==expected_scenario,'Missing complete restore/recovery session')
        processes=session['processes'];check(len(processes)==2 and {p['name'] for p in processes}=={'server','clients'},'Missing actual runtime processes')
        server=next(p for p in processes if p['name']=='server');client=next(p for p in processes if p['name']=='clients')
        check(server['exit_code']==(-9 if crashed else 0) and client['exit_code']==0 and prior_end<server['started_at']<client['started_at']<client['exited_at']<server['exited_at'],'Recovery process exit or chronology differs');prior_end=server['exited_at']
        for key,suffix in [('controller','controller.py'),('clients','clients.cjs'),('mysql_snapshot','mysql-snapshot.py')]+([('recovery','recovery.py')] if crashed else []):check(sha(run/f'evidence/boot{boot}.{suffix}')==session['source_sha256'][key],'Captured recovery tool changed')
        log=(run/f'evidence/boot{boot}.server.log').read_text();expected=f"STARCORE database ready: MySQL 运行中 ({target['host']}:{target['port']}/{target['database']})"
        check(log.count(expected)==1 and 'STARCORE-MySQL - Start completed.' in log and 'STARCORE-SQLite' not in log and 'Done (' in log,'Restored game did not use MySQL')
        check(('Stopping server' in log) is (not crashed) and ('STARCORE-MySQL - Shutdown completed.' in log) is (not crashed),'Intentional crash disguised as normal shutdown')
        for line in re.findall(r'^.*(?:ERROR\]|ERROR\]:|Failed to (?:enable|disable) STARCORE module|同步保存失败|异步保存失败|SQLITE_BUSY|startup failed|Could not pass event|Committed history source .* retained|History scan retained pending evidence|generated an exception).*$',log,re.M|re.I):
            check(T.EPIC.vendor_telemetry(line),'Recovery persistence/lifecycle diagnostic: '+line);telemetry.append(dict(boot=boot,message=line))
        observed=[r for r in fixture if r['boot']==boot]
        for kind in {r['kind'] for r in observed}:
            emitted=[json.loads(line.split('EPIC_'+kind+' ',1)[1]) for line in log.splitlines() if 'EPIC_'+kind+' ' in line]
            check(emitted==[r['data'] for r in observed if r['kind']==kind],'Fixture/server recovery stream differs')
        for kind in ('READY','SEEDED','STACK_LOADED'):check(sum(r['kind']==kind for r in observed)==1,'Missing recovery lifecycle: '+kind)
        check(sum(r['kind']=='CLOSED' for r in observed)==(0 if crashed else 1),'Wrong recovery disable evidence')
        only=lambda kind:next(r['data'] for r in observed if r['kind']==kind)
        check(only('READY')==dict(product=manifest['plugin_sha256'],native=manifest['starcore_sha256'],route=manifest['route']) and only('SEEDED')==next(r['data'] for r in fixture if r['kind']=='SEEDED') and only('SEEDED')['nonOp'] is True,'Recovered artifact/player identity differs')
        check(only('STACK_LOADED')==next(r['data'] for r in fixture if r['boot']==source_boot and r['kind']=='STACK_LOADED'),'Recovered selected stack differs')
        check(all(server['started_at']<=T.instant(r['time'])<=server['exited_at'] for r in observed),'Recovery observation outside server lifetime')
        clients=T.records(run/f'evidence/boot{boot}.clients.log');check(not any(r['kind']=='FAILED' for r in clients),'Real recovery client failed')
        spawns=[r['data'] for r in clients if r['kind']=='SPAWN'];check(len(spawns)==4 and {s['who'] for s in spawns}==set('ABCD') and all(s['uuid']==manifest['players'][s['who']] for s in spawns),'Recovered client identities differ')
        actual=[r for r in clients if r['kind']=='COMMAND_RESULT'];check([(r['data']['who'],r['data']['command'],r['data']['contains']) for r in actual]==expected_commands,'Recovery replay command sequence differs')
        for row in actual:
            d=row['data'];check(d['contains'] in d['response'] and client['started_at']<=T.instant(row['time'])<=client['exited_at'] and any(r['kind']=='MESSAGE' and r['data']['who']==d['who'] and r['data']['message'] in d['response'] for r in clients),'Recovery command lacks real protocol response')
        command_count+=len(actual)
        if expected_scenario=='third':
            check(sum(r['kind']=='REPLAY_REJECTED' for r in observed)==1 and sum(r['kind']=='ACCEPTANCE_PASS' and T.canonical(r['data']['settlement'])==T.canonical(baseline_business['settlement']) for r in observed)==2,'Epic replay/unique settlement observation missing')
            queries=[r for r in clients if r['kind']=='CONTRIBUTIONS_QUERY'];check([r['data']['who'] for r in queries]==['A','B'] and all('10 国库货币' in r['data']['response'] and '10 资源 agricultural' in r['data']['response'] for r in queries),'Recovered actual aid queries missing');command_count+=len(queries)
            if manifest['route']=='WAR':
                states=[r['data'] for r in observed if r['kind']=='BATTLE_STATE'];check(len(states)==2 and all(s['status']=='ENDED' and s['winner']=='AGGRESSOR' and s['aggressorScore']==100 and s['defenderScore']==0 for s in states),'Recovered battlefield victory differs')
                attempts=[r['data'] for r in clients if r['kind']=='BATTLE_GUI_ATTEMPT'];check(len(attempts)==1 and attempts[0]['who']=='A' and attempts[0]['mode']=='blocked' and attempts[0]['realClick'] is True,'Missing recovered battlefield rejection')
        else:
            kind='PRODUCTION_CHECK' if 'nation_production_acceptance' in manifest else 'SALE_CHECK';checks=[r['data'] for r in observed if r['kind']==kind]
            check(len(checks)==2 and all(r['native']==baseline_business['receipts'] and r['expected']==len(baseline_business['receipts']) for r in checks),'Recovered live receipt replay differs')
            check(all(sorted(r['contributions'],key=lambda c:c['id'])==baseline_business['contributions'] for r in checks),'Recovered original actor contributions differ')
        if crashed:
            d=e/f'interruption-boot{boot}';interrupt=read(d/'interruption.json');check(session['interruption']==interrupt and interrupt['status']=='POST_REPLAY_PROCESS_INTERRUPTION_RECOVERED' and interrupt['boot']==boot and interrupt['projection_baseline_boot']==boot-1,'Missing process interruption proof')
            check({p.name for p in d.iterdir()}==set(interrupt['files'])|{'interruption.json'} and all(sha(d/n)==h for n,h in interrupt['files'].items()),'Interruption evidence digest/inventory differs')
            check(interrupt['server_pid']==server['pid'] and interrupt['server_exit_code']==-9 and interrupt['container_id']==target['container_id'] and interrupt['server_uuid']==target['server_uuid'],'Wrong interrupted processes')
            before,killed,after=[interrupt[k] for k in ('database_before','database_killed','database_after')]
            check(before['Running'] and after['Running'] and not killed['Running'] and killed['ExitCode']==137 and all(not s['OOMKilled'] for s in (before,killed,after)) and before['StartedAt']!=after['StartedAt'],'Missing intentional database SIGKILL/restart')
            check(client['exited_at']<interrupt['started_at']<interrupt['game_killed_at']<=server['exited_at']<interrupt['database_killed_at']<interrupt['finished_at'],'Wrong interruption boundary');prior_end=interrupt['finished_at']
            snapshots=[read(d/n) for n in ('before-game-kill-native.json','after-game-kill-native.json','after-database-restart-native.json')]
            for snap in snapshots:
                R.equal_data(snapshots[0],snap);check(snap['server_uuid']==target['server_uuid'] and snap['innodb_flush_log_at_trx_commit']==snap['sync_binlog']==1,'Crash data/durability authority differs')
            database_log=(d/'database-recovery.log').read_text()
            check('Starting XA crash recovery...' in database_log and 'XA crash recovery finished.' in database_log and 'ready for connections' in database_log,'Database crash recovery log missing')
            raw=snapshots[-1]
            before_chronicle=sqlite_state(d/'before-game-kill-chronicle.db');after_chronicle=sqlite_state(d/'after-game-kill-chronicle.db')
            check(before_chronicle==after_chronicle,'Committed Chronicle data lost at JVM interruption');chronicle_replay_equal(baseline_chronicle,after_chronicle)
            check(not (run/f'evidence/checkpoints/boot{boot}').exists(),'Crash falsely has normal checkpoint')
        else:
            cp=run/f'evidence/checkpoints/boot{boot}';checkpoint=read(cp/'checkpoint.json');raw=read(run/f'evidence/mysql/boot{boot}.json')
            check(checkpoint['boot']==boot and server['exited_at']<=raw['started_at']<=raw['finished_at']<=checkpoint['time'] and checkpoint['mysql']['raw_sha256']==sha(run/f'evidence/mysql/boot{boot}.json'),'Recovery checkpoint boundary differs')
            check(set(checkpoint['sha256'])=={'starcore.db','chronicle.db'} and all(sha(cp/n)==h for n,h in checkpoint['sha256'].items()),'Recovered paired checkpoint differs')
            check(raw['format']==1 and raw['storage']=='mysql' and raw['boot']==boot and raw['target']=={k:target[k] for k in ('host','port','database','user','container','container_id','image')} and raw['server']['server_uuid']==target['server_uuid'],'Recovered checkpoint authority differs')
            check(raw['server']['engine']=='InnoDB' and raw['server']['isolation']=='REPEATABLE-READ' and raw['server']['ssl_cipher'] and raw['server']['database']==target['database'],'Recovered database engine/encryption differs')
            check(checkpoint['mysql']['raw']==f'evidence/mysql/boot{boot}.json' and checkpoint['mysql']['table_count']==211 and checkpoint['mysql']['view_count']==1 and checkpoint['mysql']['projection_sha256']==checkpoint['sha256']['starcore.db'],'Recovered checkpoint provenance differs')
            I.compare_projection(raw,cp/'starcore.db');chronicle_replay_equal(baseline_chronicle,sqlite_state(cp/'chronicle.db'))
            check(business(manifest,fixture,cp/'starcore.db',cp/'chronicle.db')==baseline_business,'Recovered business invariant changed')
        check([{k:v for k,v in t.items() if k!='rows'} for t in raw['tables']]==[{k:v for k,v in t.items() if k!='rows'} for t in source['tables']] and raw['views']==source['views'],'Recovered native catalog schema inventory changed')
        check(protected_native(raw)==baseline_native,'Recovered assets, actors, journals or authority changed')
    for plugin,file in [('StarCore','starcore.db'),('StarChronicle','chronicle.db')]:
        with closing(T.connect(run/f'plugins/{plugin}/{file}')):pass
        check(sha(run/f'plugins/{plugin}/{file}')==sha(run/f'evidence/checkpoints/boot{last}/{file}'),'Final runtime/checkpoint differs')
    for name,entry in proof['files'].items():
        if name.startswith('plugins/StarCore/quest/') or name in ('plugins/StarCore/dungeon/completions.json','plugins/StarCore/dungeon/authority-domain.txt'):
            if sha(run/name)==entry['sha256']:continue
            with zipfile.ZipFile(R.ROOT/'docs/verification/mysql-paper-20261125-evidence.zip') as z:before=z.read(prefix+name)
            check(hashlib.sha256(before).hexdigest()==entry['sha256'],'Backup native local history differs from accepted source')
            after=(run/name).read_bytes()
            if '.properties' in name and before!=after:
                # java.util.Properties.store refreshes this comment on each save.
                def without_save_time(raw):
                    lines=raw.splitlines(keepends=True)
                    check(len(lines)>=2 and lines[0]==b'#STARCORE quest state\n' and re.fullmatch(rb'#[A-Z][a-z]{2} [A-Z][a-z]{2} \d{2} \d{2}:\d{2}:\d{2} UTC \d{4}\n',lines[1]),'Unexpected native properties header')
                    return lines[:1]+lines[2:]
                check(without_save_time(before)==without_save_time(after),'Recovered native local quest/dungeon history changed')
            else:check(before==after,'Recovered native local quest/dungeon history changed')
    return dict(status='MYSQL_OFFLINE_RESTORE_AND_POST_REPLAY_PROCESS_RECOVERY_ACCEPTED',kind=baseline_business['kind'],source_boot=source_boot,accepted_boots=list(range(first,last+1)),
        diagnostic_boots=[r['boot'] for r in diagnostics],full_backup_sha256=binding['backup_sha256'],restored_files=restore['files_verified'],tables=211,views=1,
        actual_commands=command_count,normal_process_exits=5,killed_game_processes=1,killed_database_processes=1,database_restarts=1,
        source_server_uuid=source['server_uuid'],recovered_server_uuid=target['server_uuid'],artifact_bytes_verified=artifacts,private_configuration_redacted=public_redacted,vendor_telemetry=telemetry,
        limits=['Offline paired backup; public evidence omits private full archive.','Post-replay JVM then MySQL SIGKILL; no in-flight transaction, hardware power-loss, live outage, remote TLS or Folia claim.'])


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('run',type=Path);p.add_argument('--artifacts',action='store_true');p.add_argument('--public-redacted',action='store_true');a=p.parse_args()
    print(json.dumps(inspect(a.run,artifacts=a.artifacts,public_redacted=a.public_redacted),ensure_ascii=False,indent=2))
