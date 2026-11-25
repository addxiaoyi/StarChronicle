"""Run one isolated Paper boot and its clients with complete append-only evidence."""
from pathlib import Path
import argparse
import base64
import hashlib
import json
import os
import socket
import subprocess
import sys
import threading
import time
import shutil

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--run', type=Path, required=True)
parser.add_argument('--java-home', type=Path, required=True)
parser.add_argument('--node', type=Path, required=True)
parser.add_argument('--boot', type=int, required=True)
parser.add_argument('--scenario', choices=['legacy-seed','legacy-first','legacy-second','legacy-third','treasury-first','treasury-second','treasury-third','factory-first','factory-second','factory-third','production-catalog-first','production-catalog-second','production-catalog-third','production-recovery','production-offline-recovery','sale-recovery','production-first','production-second','production-third','production-offline-first','production-offline-second','sale-offline-first','sale-offline-second','sale-first','sale-second','sale-third','quest-first', 'first', 'second', 'third', 'members-first', 'members-second', 'members-third', 'trade-first', 'trade-second', 'trade-third', 'tech-first', 'tech-second', 'tech-third', 'resource-first', 'resource-second', 'resource-third', 'stack-first', 'stack-second', 'stack-third', 'memory-first', 'memory-second', 'memory-third', 'memory-offline-first', 'memory-offline-second', 'capital-first', 'capital-second', 'capital-third', 'capital-offline-first', 'capital-offline-second'])
parser.add_argument('--contributions', action='store_true', help='Verify resource/treasury contribution projections and actual client queries')
parser.add_argument('--interrupt-after-replay', action='store_true', help='SIGKILL the owned restored JVM, then MySQL, after completed replay; not a hardware power-loss test')
args = parser.parse_args()
run = args.run.resolve()
if run.parent != (ROOT / 'run').resolve() or not run.name.startswith('paper-epic-'):
    raise SystemExit('Require an isolated epic run')
if (run/'restore-incomplete.json').exists():
    raise SystemExit('Restore has not completed; retain its failure and do not start Paper')
manifest = json.loads((run / 'preparation.json').read_text(encoding='utf-8'))
if args.interrupt_after_replay or (args.scenario and args.scenario.endswith('-recovery')):
    if not manifest.get('recovery_acceptance') or not manifest.get('mysql_acceptance'):
        raise SystemExit('Recovery scenarios require an independently restored MySQL run')
if args.interrupt_after_replay and args.scenario not in ('production-recovery','production-offline-recovery','sale-recovery','third'):
    raise SystemExit('Interruption requires a completed recovery replay scenario')
for name, key in [('StarChronicle', 'plugin_sha256'), ('StarCore', 'starcore_sha256'), ('EpicCampaignPaperFixture', 'fixture_sha256')]:
    if hashlib.sha256((run / f'plugins/{name}.jar').read_bytes()).hexdigest() != manifest[key]:
        raise SystemExit('Frozen artifact drift: ' + name)
if manifest.get('production_stack'):
    paper_hashes = {value for name, value in manifest['source_artifacts'].items() if Path(name).name == 'paper.jar'}
    if len(paper_hashes) != 1 or hashlib.sha256((run / 'paper.jar').read_bytes()).hexdigest() not in paper_hashes:
        raise SystemExit('Frozen Paper runtime drift')
for artifact in manifest.get('production_stack', {}).get('artifacts', []):
    if hashlib.sha256((run / 'plugins' / artifact['filename']).read_bytes()).hexdigest() != artifact['sha256']:
        raise SystemExit('Frozen additional plugin drift: ' + artifact['filename'])
probe = socket.socket()
probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
probe.bind(('127.0.0.1', manifest['server_port']))
probe.close()
# Resolve the actual client dependency before starting any game process.
subprocess.run([str(args.node), '-e', 'require(process.argv[1])', os.environ.get('EPIC_MINEFLAYER_MODULE', 'mineflayer')],
               cwd=run/'evidence', check=True, stdout=subprocess.DEVNULL)
flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
prefix = run / f'evidence/boot{args.boot}'
if prefix.with_suffix('.server.log').exists():
    raise SystemExit('Refusing to overwrite a prior boot log')
shutil.copy2(__file__, Path(str(prefix) + '.controller.py'))
shutil.copy2(ROOT / 'tools/epic-paper-clients.cjs', Path(str(prefix) + '.clients.cjs'))
client_script = Path(str(prefix) + '.clients.cjs')
mysql_snapshot = None
if manifest.get('mysql_acceptance'):
    import importlib.util
    frozen_mysql_tool = Path(str(prefix) + '.mysql-snapshot.py')
    shutil.copy2(ROOT / 'tools/mysql_paper_snapshot.py', frozen_mysql_tool)
    spec = importlib.util.spec_from_file_location('captured_mysql_snapshot', frozen_mysql_tool)
    mysql_snapshot = importlib.util.module_from_spec(spec); spec.loader.exec_module(mysql_snapshot)
    mysql_snapshot.config(run, manifest)
if args.interrupt_after_replay:
    import mysql_recovery as recovery
    shutil.copy2(recovery.__file__, Path(str(prefix) + '.recovery.py'))
handles = []
readers = []
process_records = []
observed = []
condition = threading.Condition()


def start(command, name):
    process = subprocess.Popen(command, cwd=run, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, text=True, encoding='utf-8', errors='replace',
                               creationflags=flags, bufsize=1)
    handles.append(process)
    record = dict(name=name, pid=process.pid, command=command, started_at=time.time())
    process_records.append(record)
    def read():
        with Path(str(prefix) + f'.{name}.log').open('x', encoding='utf-8') as log:
            for line in process.stdout:
                log.write(line)
                log.flush()
                with condition:
                    observed.append((name, line))
                    condition.notify_all()
                if (name == 'clients' and '"kind":"MESSAGE"' not in line) or any(word in line for word in ('EPIC_', 'Done (', 'ERROR', 'Exception', 'Stopping server')):
                    print(name + ': ' + (line.rstrip()[:700] + (' [full record retained in log]' if len(line)>700 else '')), flush=True)
        record.update(exit_code=process.wait(), exited_at=time.time())
        print(f'{name}: EXIT {record["exit_code"]}', flush=True)
    reader = threading.Thread(target=read, daemon=True)
    readers.append(reader)
    reader.start()
    return process


java = args.java_home / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
server = start([str(java), '-Xms64m', '-Xmx384m', '-Xss512k',
                '-XX:+UseSerialGC', '-XX:ActiveProcessorCount=1', '-XX:TieredStopAtLevel=1',
                '-XX:ReservedCodeCacheSize=64m', '-XX:CompressedClassSpaceSize=64m', '-Dfile.encoding=UTF-8',
                '-Dterminal.jline=false', '-Dterminal.ansi=false', '-jar', 'paper.jar', 'nogui'], 'server')
clients = None
print(json.dumps(dict(server_pid=server.pid, run=str(run), boot=args.boot)), flush=True)


def await_lines(cursor, predicate, seconds=45):
    deadline = time.monotonic() + seconds
    with condition:
        while True:
            fresh = observed[cursor:]
            failures = [line for name, line in fresh if 'EPIC_FAILED ' in line or '"kind":"FAILED"' in line]
            if failures:
                raise RuntimeError('Observed failure: ' + failures[-1])
            if predicate(fresh):
                return
            if server.poll() is not None or (clients is not None and clients.poll() is not None):
                raise RuntimeError('Acceptance process exited before the expected observation')
            if time.monotonic() >= deadline:
                raise TimeoutError('No matching live observation within deadline')
            condition.wait(min(1, deadline - time.monotonic()))


def server_action(action, seconds=45):
    cursor = len(observed)
    server.stdin.write('epicverify ' + action + '\n'); server.stdin.flush()
    name = action.split()[0]
    await_lines(cursor, lambda lines: any('EPIC_ACTION_DONE ' in line and '"action":"' + name + '"' in line for _, line in lines), seconds)


def client_action(action):
    cursor = len(observed)
    clients.stdin.write(action + '\n'); clients.stdin.flush()
    name = action.split()[0]
    await_lines(cursor, lambda lines: any('"kind":"ACTION_DONE"' in line and '"action":"' + name + '"' in line for _, line in lines))


def console_expect(command, expected):
    cursor = len(observed)
    server.stdin.write(command + '\n'); server.stdin.flush()
    await_lines(cursor, lambda lines: any(name == 'server' and expected in line for name, line in lines))
    with (run/'evidence/memory-config.jsonl').open('a') as out:
        out.write(json.dumps(dict(boot=args.boot, time=time.time(), command=command, expected=expected, matched=True), ensure_ascii=False)+'\n')


def memory_config(variant):
    source = run/'fixture'/f'memory-{variant}.yml'
    expected = manifest['memory_acceptance']['recipe_variants_sha256'][variant]
    if hashlib.sha256(source.read_bytes()).hexdigest() != expected: raise RuntimeError('Frozen memory catalog variant changed')
    target = run/'plugins/StarChronicle/memory-recipes.yml'
    before = hashlib.sha256(target.read_bytes()).hexdigest(); shutil.copy2(source,target)
    with (run/'evidence/memory-config.jsonl').open('a') as out:
        out.write(json.dumps(dict(boot=args.boot, time=time.time(), variant=variant, before=before, after=expected))+'\n')


def memory_scenario(phase):
    server_action('stack-check')
    server_action('memory-station')
    def walk(who):
        server_action('memory-place ' + who)
        client_action('memory-walk ' + who)
    def choose(who,choice):
        rows = [json.loads(line) for line in (run/'evidence/epic-fixture.jsonl').read_text().splitlines()]
        latest = next(row['data']['state'] for row in reversed(rows) if row['kind'] == 'MEMORY_CHECK')
        active = [i for i in latest['players'][who]['instances'] if not i['completed']]
        if len(active) != 1: raise RuntimeError('Expected one observed active library instance')
        client_action('memory-choice '+who+' '+choice+' '+active[0]['id'])
    if phase == 'memory-first':
        server_action('memory-check zero')
        request = json.dumps(dict(command='/chronicle reload', contains='没有管理权限。'),ensure_ascii=False).encode()
        client_action('expect A ' + base64.urlsafe_b64encode(request).decode().rstrip('='))
        walk('A'); server_action('memory-check first')
        choose('A','testify'); server_action('memory-check testimony')
        choose('D','read'); server_action('memory-check witness')
        memory_config('validate-v2'); console_expect('chronicle validate', '验证通过:')
        server_action('memory-check witness')
        memory_config('invalid-v1'); console_expect('chronicle validate', '验证失败: Memory recipe content changed without a new version:')
        console_expect('chronicle reload', '重载失败，原定义保留: Memory recipe content changed without a new version:')
        walk('B'); server_action('memory-check second')
        choose('B','read'); server_action('memory-check read')
        memory_config('disabled'); console_expect('chronicle reload', '已重载 ')
        walk('C'); server_action('memory-check disabled')
        memory_config('enabled'); console_expect('chronicle reload', '已重载 ')
        server_action('memory-replay'); server_action('memory-check disabled')
        walk('D'); server_action('memory-check pending')
        server_action('memory-replay'); server_action('memory-check pending')
    elif phase == 'memory-second':
        server_action('memory-check pending')
        choose('D','testify'); server_action('memory-check d-complete')
        choose('A','read'); server_action('memory-check complete')
        server_action('memory-replay')
        for who in ('A','B','D'): server_action('memory-repeat ' + who)
        server_action('memory-check complete')
    elif phase == 'memory-third':
        server_action('memory-check complete'); walk('A'); server_action('memory-replay')
        server_action('memory-repeat A'); server_action('memory-check complete')
    elif phase == 'memory-offline-first':
        walk('D'); server_action('memory-check offline-buffered')
    elif phase == 'memory-offline-second':
        server_action('memory-check offline-pending')
        choose('D','testify'); server_action('memory-check offline-testimony')
        choose('A','read'); server_action('memory-check offline-complete')
        server_action('memory-replay'); server_action('memory-check offline-complete')
    else: raise ValueError('Unknown memory scenario')


def database_checkpoint():
    directory = run/'evidence/checkpoints'/f'boot{args.boot}'; directory.mkdir(parents=True,exist_ok=False)
    mysql_evidence = mysql_snapshot.capture(run, manifest, args.boot) if mysql_snapshot else None
    hashes = {}
    for plugin, name in (('StarChronicle','chronicle.db'),('StarCore','starcore.db')):
        source = run/'plugins'/plugin/name
        for suffix in ('-wal','-journal'):
            sidecar = Path(str(source)+suffix)
            if sidecar.exists() and sidecar.stat().st_size: raise RuntimeError('Stopped database has pending journal: '+plugin)
        before = hashlib.sha256(source.read_bytes()).hexdigest(); shutil.copy2(source,directory/name)
        if hashlib.sha256((directory/name).read_bytes()).hexdigest() != before: raise RuntimeError('Stopped database checkpoint drift')
        hashes[name] = before
    if manifest.get('quest_recovery_acceptance'):
        for source in sorted((run/'plugins/StarCore/quest').rglob('*')):
            if not source.is_file(): continue
            relative=Path('StarCore/quest')/source.relative_to(run/'plugins/StarCore/quest')
            target=directory/relative; target.parent.mkdir(parents=True,exist_ok=True)
            before=hashlib.sha256(source.read_bytes()).hexdigest(); shutil.copy2(source,target)
            if hashlib.sha256(target.read_bytes()).hexdigest()!=before: raise RuntimeError('Native quest checkpoint drift')
            hashes[str(relative)]=before
    if manifest.get('capital_acceptance'):
        for source in sorted((run/'plugins/StarCore').rglob('capital-designations.log*')):
            if not source.is_file() or source.suffix == '.lock': continue
            relative = Path('StarCore')/source.relative_to(run/'plugins/StarCore')
            target = directory/relative; target.parent.mkdir(parents=True, exist_ok=True)
            before = hashlib.sha256(source.read_bytes()).hexdigest(); shutil.copy2(source,target)
            if hashlib.sha256(target.read_bytes()).hexdigest() != before: raise RuntimeError('Capital checkpoint drift')
            hashes[str(relative)] = before
    (directory/'checkpoint.json').write_text(json.dumps(dict(boot=args.boot,time=time.time(),sha256=hashes,**({'mysql':mysql_evidence} if mysql_evidence else {})),indent=2)+'\n')


def member_scenario(phase):
    if phase == 'members-first':
        server_action('members-open')
    fixture = [json.loads(line) for line in (run / 'evidence/epic-fixture.jsonl').read_text().splitlines()]
    identity = next(row['data']['instance'] for row in fixture if row['kind'] == 'MEMBER_OPENED')
    def command(who, tail, expected):
        request = json.dumps(dict(command='/chronicle ' + tail, contains=expected), ensure_ascii=False).encode()
        client_action('expect ' + who + ' ' + base64.urlsafe_b64encode(request).decode().rstrip('='))
    if phase == 'members-first':
        command('D', f'choose {identity} review yes', '已投票 1/2')
        server_action('members-move B')
        server_action('members-check PENDING review')
        command('D', f'member revalidate {identity} review', 'Current nation does not match the original membership')
        command('B', f'choose {identity} review yes', '已投票 1/2')
        server_action('members-check PENDING review')
        server_action('members-move A')
        command('D', f'member status {identity}', '待复核')
        command('D', f'member revalidate {identity} review', '成员资格已重新核验')
        command('D', f'choose {identity} review yes', '已进入下一阶段')
        server_action('members-check APPROVED closing')
        command('D', f'member exit {identity} review', '剧情阶段已改变')
        command('D', f'member exit {identity} closing', '已退出剧情实例')
        server_action('members-check EXITED closing')
        time.sleep(3)
        server_action('members-check EXITED closing')
    elif phase == 'members-second':
        server_action('members-check EXITED closing')
        command('D', f'member status {identity}', '已退出')
        command('D', f'member revalidate {identity} closing', 'Exited membership cannot be silently restored')
        server_action('members-move B')
        command('D', f'join {identity}', '重新加入须恢复原国家身份')
        server_action('members-move A')
        command('D', f'join {identity}', '已加入共同剧情')
        server_action('members-check APPROVED closing')
        command('D', f'choose {identity} closing yes', '已投票 1/2')
        command('B', f'choose {identity} closing yes', '剧情已完成')
        command('D', f'member exit {identity} end', '已完成剧情的成员属于历史记录')
        server_action('members-check APPROVED end')
    elif phase == 'members-third':
        server_action('members-check APPROVED end')
        command('D', f'member status {identity}', '已核验')
        command('D', f'member exit {identity} end', '已完成剧情的成员属于历史记录')
        server_action('members-check APPROVED end')
    else:
        raise ValueError('Unknown member scenario')


def sale_scenario(phase):
    if not manifest.get('nation_trade_acceptance'): raise RuntimeError('Require prepared national sale scenario')
    server_action('stack-check');server_action('sale-bind')
    def command(who,cmd,contains):
        request=json.dumps(dict(command=cmd,contains=contains),ensure_ascii=False).encode()
        client_action('expect '+who+' '+base64.urlsafe_b64encode(request).decode().rstrip('='))
    def identity(key):
        records=[json.loads(line) for line in (run/'evidence/epic-fixture.jsonl').read_text().splitlines()]
        return next(row['data']['offer']['offerId'] for row in reversed(records) if row['kind']=='SALE_OFFER' and row['data']['key']==key)
    def offer(key,who,quantity,price,minor):
        command(who,f'/nationtrade offer EpicB agricultural {quantity} {price} 3600','报价已保存')
        server_action(f'sale-capture {key} {who} {quantity} {minor}')
    def accept(key,response='国家贸易操作已提交'):
        command('B','/nationtrade accept '+identity(key),response)
    if phase=='sale-offline-first':
        offer('primary','A',20,'2.50',250);accept('primary')
        offer('pending','A',5,'4.00',400);server_action('sale-offline-buffered')
    elif phase=='sale-offline-second':
        accept('primary','没有重复成交');server_action('sale-check 1')
        command('B','/chronicle contributions','国库支付')
    elif phase=='sale-first':
        server_action('sale-check 0')
        offer('primary','A',20,'2.50',250)
        server_action('sale-check 0')
        for who in 'DC':command(who,'/nationtrade accept '+identity('primary'),'操作被拒绝')
        command('B','/nationtrade show '+identity('primary'),'国库总价: 50.00')
        accept('primary');server_action('sale-check 1')
        accept('primary','没有重复成交');server_action('sale-check 1')
        offer('pending','A',5,'4.00',400);server_action('sale-status pending OPEN')
    elif phase=='sale-second':
        server_action('sale-check 1');server_action('sale-status pending OPEN')
        accept('pending');server_action('sale-check 2')
        offer('cancelled','A',1,'1.00',100)
        command('A','/nationtrade cancel '+identity('cancelled'),'国家贸易操作已提交')
        accept('cancelled','操作被拒绝');server_action('sale-status cancelled CANCELLED')
        offer('old-founder','A',3,'3.00',300);server_action('sale-successor')
        accept('old-founder','操作被拒绝');server_action('sale-status old-founder OPEN')
        offer('successor','D',4,'1.25',125);server_action('sale-check 2')
    elif phase=='sale-third':
        server_action('sale-check 2');server_action('sale-status successor OPEN')
        accept('old-founder','操作被拒绝');accept('cancelled','操作被拒绝')
        accept('primary','没有重复成交');accept('pending','没有重复成交')
        accept('successor');server_action('sale-check 3')
        accept('successor','没有重复成交');server_action('sale-check 3')
        for who in 'AD':command(who,'/chronicle contributions','成交资源 agricultural')
        command('B','/chronicle contributions','国库支付')
        command('D','/chronicle contributions nation me','成交资源 agricultural')
    elif phase=='sale-recovery':
        server_action('sale-check 3')
        for key in ('old-founder','cancelled'):accept(key,'操作被拒绝')
        for key in ('primary','pending','successor'):accept(key,'没有重复成交')
        server_action('sale-check 3')
        for who in 'AD':command(who,'/chronicle contributions','成交资源 agricultural')
        command('B','/chronicle contributions','国库支付')
        command('D','/chronicle contributions nation me','成交资源 agricultural')
    else: raise ValueError(phase)


def legacy_factory_scenario(phase):
    spec=manifest['legacy_factory_acceptance'];factory=spec['factories']['valid'];ops=spec['operations']
    server_action('stack-check');server_action('production-bind');server_action('legacy-bind')
    if phase=='legacy-seed':
        server_action('production-check 0');return
    staged=json.loads((run/'evidence/legacy-staging.json').read_text());digest=staged['fingerprint']
    def command(cmd,response,who='A'):
        time.sleep(1.25)
        request=json.dumps(dict(command=cmd,contains=response),ensure_ascii=False).encode()
        client_action('expect '+who+' '+base64.urlsafe_b64encode(request).decode().rstrip('='))
    def identity(key):
        rows=[json.loads(line) for line in (run/'evidence/epic-fixture.jsonl').read_text().splitlines()]
        return next(r['data']['run']['runId'] for r in reversed(rows) if r['kind']=='PRODUCTION_RUN' and r['data']['key']==key)
    def start(key,batches,who='A'):
        command(f'/nationproduction startat {factory} legacy_refining {batches}','生产已开工',who)
        server_action(f'production-capture {key} {who} {batches}')
    def collect(key,who='A',response='生产批次已处理'):
        command('/nationproduction complete '+identity(key),response,who)
    register=f'/nationfactory registerlegacy {factory} {digest} {ops["register"]}'
    if phase=='legacy-first':
        console_expect('nationfactory audit','旧工厂原始记录 7')
        command('/nationfactory legacy','登记会新收取1至当前等级累计材料')
        command('/nationfactory audit','没有全服旧工厂审计权限')
        command(register,'请求被拒绝','D')
        command(f'/nationfactory registerlegacy {factory} {"0"*64}','旧记录摘要／ID占用已变化')
        for key in ('processing','malformed','duplicate','unknown','foreign'):
            command(f'/nationfactory registerlegacy {spec["factories"][key]} {digest}','请求被拒绝')
        command(register,'工厂请求已提交');command(register,'没有重复扣除材料')
        command(f'/nationfactory registerlegacy {factory} {digest}','旧记录摘要／ID占用已变化')
        command('/nationfactory show '+factory,'没有可查看的这个工厂','B')
        command('/nationfactory show '+factory,'旧工厂付费登记')
        command(f'/nationproduction startat {factory} legacy_refining 1','工厂不可用')
        command(f'/nationfactory enable {factory} 1 {ops["enable"]}','工厂请求已提交')
        command(f'/nationfactory enable {factory} 1 {ops["enable"]}','没有重复扣除材料')
        start('legacy_old',1);start('legacy_cancel',2)
        server_action('legacy-check A 3 2 true 2');server_action('production-check 0')
    elif phase=='legacy-second':
        server_action('legacy-check A 3 2 true 2')
        server_action('production-ready legacy_old A',seconds=110);collect('legacy_old');collect('legacy_old',response='没有重复扣除或发放')
        command('/nationproduction cancel '+identity('legacy_cancel'),'生产批次已处理')
        command('/nationproduction cancel '+identity('legacy_cancel'),'没有重复扣除或发放')
        server_action('production-successor')
        command(f'/nationfactory upgrade {factory} 2','请求被拒绝')
        command(f'/nationfactory upgrade {factory} 2 {ops["upgrade"]}','工厂请求已提交','D')
        start('legacy_successor',1,'D')
        command(f'/nationfactory disable {factory} 3 {ops["disable"]}','工厂请求已提交','D')
        command(register,'没有重复扣除材料');command(register,'请求被拒绝','D')
        command('/nationfactory operation '+ops['register'],'旧建造人未证明')
        server_action('legacy-check D 4 4 false 1');server_action('production-check 1')
    elif phase=='legacy-third':
        server_action('legacy-check D 4 4 false 1')
        server_action('production-ready legacy_successor D',seconds=110);collect('legacy_successor','D');collect('legacy_successor','D','没有重复扣除或发放')
        collect('legacy_old',response='没有重复扣除或发放')
        command(register,'没有重复扣除材料')
        command(f'/nationfactory upgrade {factory} 2 {ops["upgrade"]}','没有重复扣除材料','D')
        command(f'/nationfactory disable {factory} 3 {ops["disable"]}','没有重复扣除材料','D')
        command('/nationfactory legacy','ALREADY_REGISTERED_OR_ID_COLLISION','D')
        server_action('legacy-check D 4 4 false 0');server_action('production-check 2')
        command('/chronicle contributions','生产资源 industrial')
        command('/chronicle contributions','生产资源 industrial','D')
        command('/chronicle contributions nation me','生产资源 industrial','D')
    else:raise ValueError(phase)


def factory_scenario(phase):
    spec=manifest.get('nation_factory_acceptance')
    if not spec:raise RuntimeError('Require prepared factory scenario')
    server_action('stack-check');server_action('production-bind');server_action('factory-bind')
    operations=spec['operations'];factory=operations['build']
    def command(cmd,response,who='A'):
        time.sleep(1.25)
        request=json.dumps(dict(command=cmd,contains=response),ensure_ascii=False).encode()
        client_action('expect '+who+' '+base64.urlsafe_b64encode(request).decode().rstrip('='))
    def identity(key):
        rows=[json.loads(line) for line in (run/'evidence/epic-fixture.jsonl').read_text().splitlines()]
        return next(r['data']['run']['runId'] for r in reversed(rows) if r['kind']=='PRODUCTION_RUN' and r['data']['key']==key)
    def start(key,recipe,batches,who='A'):
        command(f'/nationproduction startat {factory} {recipe} {batches}','生产已开工',who)
        server_action(f'production-capture {key} {who} {batches}')
    def collect(key,who='A',response='生产批次已处理'):
        command('/nationproduction complete '+identity(key),response,who)
    def check(who,level,version,enabled,runs,batches):
        server_action(f'factory-check forge {who} {level} {version} {str(enabled).lower()} {runs} {batches}')
    if phase=='factory-first':
        command('/nationfactory plans SMELTER 2','容量 2 个生产、50 批')
        command('/nationproduction start factory_refining 1','此配方需要工厂')
        command('/nationfactory build SMELTER Intruder','请求被拒绝','D')
        command(f'/nationfactory build SMELTER Forge {factory}','工厂请求已提交')
        server_action('factory-capture forge A Forge')
        command(f'/nationfactory build SMELTER Forge {factory}','没有重复扣除材料')
        command(f'/nationfactory build REFINERY Forge {factory}','请求被拒绝')
        command('/nationfactory show '+factory,'没有可查看的这个工厂','B')
        command(f'/nationproduction startat {factory} factory_refining 1','工厂不可用','B')
        command(f'/nationproduction startat {factory} advanced_refining 1','工厂不可用')
        command(f'/nationproduction startat {factory} factory_refining 26','工厂容量已满')
        start('factory_old','factory_refining',1)
        collect('factory_old',response='尚未到领取时间')
        command(f'/nationproduction startat {factory} factory_refining 1','工厂容量已满')
        command(f'/nationfactory upgrade {factory} 1 {operations["upgrade"]}','工厂请求已提交')
        command(f'/nationfactory upgrade {factory} 1 {operations["upgrade"]}','没有重复扣除材料')
        command(f'/nationfactory upgrade {factory} 1','工厂版本已变化')
        start('factory_cancel','factory_refining',2)
        command(f'/nationproduction startat {factory} factory_refining 1','工厂容量已满')
        command(f'/nationfactory disable {factory} 2 {operations["off"]}','工厂请求已提交')
        command(f'/nationproduction startat {factory} factory_refining 1','工厂不可用')
        check('A',2,3,False,2,3);server_action('factory-history 3');server_action('production-check 0')
    elif phase=='factory-second':
        check('A',2,3,False,2,3)
        server_action('production-ready factory_old A',seconds=110);collect('factory_old')
        collect('factory_old',response='没有重复扣除或发放')
        command('/nationproduction cancel '+identity('factory_cancel'),'生产批次已处理')
        command('/nationproduction cancel '+identity('factory_cancel'),'没有重复扣除或发放')
        command(f'/nationfactory enable {factory} 3 {operations["on"]}','工厂请求已提交')
        start('factory_advanced','advanced_refining',2)
        server_action('production-successor')
        command(f'/nationfactory upgrade {factory} 4','请求被拒绝')
        command('/nationfactory show '+factory,'没有可查看的这个工厂')
        command('/nationfactory operation '+factory,'原操作者')
        command(f'/nationfactory upgrade {factory} 4 {operations["successor_upgrade"]}','工厂请求已提交','D')
        start('factory_successor','factory_refining',1,'D')
        command(f'/nationfactory disable {factory} 5 {operations["successor_off"]}','工厂请求已提交','D')
        command(f'/nationproduction startat {factory} factory_refining 1','工厂不可用','D')
        check('D',3,6,False,2,3);server_action('factory-history 6');server_action('production-check 1');server_action('factory-provenance 1')
    elif phase=='factory-third':
        check('D',3,6,False,2,3)
        command(f'/nationfactory upgrade {factory} 4 {operations["successor_upgrade"]}','没有重复扣除材料','D')
        command(f'/nationfactory disable {factory} 5 {operations["successor_off"]}','没有重复扣除材料','D')
        collect('factory_advanced',response='请求被拒绝')
        for key in ('factory_advanced','factory_successor'):
            server_action(f'production-ready {key} D',seconds=110);collect(key,'D');collect(key,'D','没有重复扣除或发放')
        collect('factory_advanced',response='没有重复扣除或发放')
        command('/nationproduction cancel '+identity('factory_cancel'),'没有重复扣除或发放','D')
        check('D',3,6,False,0,0);server_action('factory-history 6');server_action('production-check 3');server_action('factory-provenance 3')
        command('/chronicle contributions','生产资源 industrial')
        command('/chronicle contributions','生产资源 industrial','D')
        command('/chronicle contributions nation me','生产资源 industrial','D')
    else:raise ValueError(phase)


def production_catalog_scenario(phase):
    if not manifest.get('production_catalog_acceptance'): raise RuntimeError('Require prepared catalog scenario')
    server_action('stack-check');server_action('production-bind')
    def command(cmd,contains,who='A'):
        # Respect the server's normal spam protection during dense admin-boundary scenarios.
        time.sleep(1.25)
        request=json.dumps(dict(command=cmd,contains=contains),ensure_ascii=False).encode()
        client_action('expect '+who+' '+base64.urlsafe_b64encode(request).decode().rstrip('='))
    def identity(key):
        rows=[json.loads(line) for line in (run/'evidence/epic-fixture.jsonl').read_text().splitlines()]
        return next(r['data']['run']['runId'] for r in reversed(rows) if r['kind']=='PRODUCTION_RUN' and r['data']['key']==key)
    def start(key,recipe,batches):
        command(f'/nationproduction start {recipe} {batches}','生产已开工')
        server_action(f'production-capture {key} A {batches}')
    def collect(key,response='生产批次已处理'):
        command('/nationproduction complete '+identity(key),response)
    def capacity(enabled,runs,batches,used,used_batches):
        server_action(f'production-catalog-check {str(enabled).lower()} {runs} {batches} {used} {used_batches}')
    def config(variant,reload=True):
        source=run/'fixture'/f'production-catalog-{variant}.json'
        digest=manifest['production_catalog_acceptance']['variants_sha256'][variant]
        if hashlib.sha256(source.read_bytes()).hexdigest()!=digest:raise RuntimeError('Frozen production catalog changed')
        target=run/'plugins/StarCore/nation-production.json'
        before=hashlib.sha256(target.read_bytes()).hexdigest();shutil.copy2(source,target)
        with (run/'evidence/production-catalog-config.jsonl').open('a') as out:
            out.write(json.dumps(dict(boot=args.boot,time=time.time(),variant=variant,before=before,after=digest))+'\n')
        if reload:console_expect('nationproduction reload','生产配置校验失败，当前运行配置保持不变' if variant=='invalid' else '生产配置已生效')
    if phase=='production-catalog-first':
        command('/nationproduction reload','没有生产配置管理权限')
        command('/nationproduction capacity','运行 0/1 个生产，占用 0/2 批')
        command('/nationproduction capacity','需要当前国家创始人身份',who='D')
        command('/nationproduction start custom_forge 3','请求被拒绝')
        start('old','custom_forge',1);collect('old','尚未到领取时间');capacity(True,1,2,1,1)
        command('/nationproduction start custom_forge 1','生产已达上限')
        config('v2',False);console_expect('nationproduction validate','校验通过，尚未改变运行配置')
        command('/nationproduction recipes','定制锻造');capacity(True,1,2,1,1)
        console_expect('nationproduction reload','生产配置已生效')
        command('/nationproduction recipes','精炼新规');capacity(True,2,2,1,1)
        command('/nationproduction start renewed_forge 2','生产已达上限')
        config('invalid');command('/nationproduction recipes','精炼新规');capacity(True,2,2,1,1)
        config('disabled');command('/nationproduction start renewed_forge 1','已暂停新开工');capacity(False,1,2,1,1)
        server_action('production-status old A RUNNING');server_action('production-check 0')
    elif phase=='production-catalog-second':
        capacity(False,1,2,1,1);command('/nationproduction start renewed_forge 1','已暂停新开工')
        server_action('production-ready old A',seconds=110);collect('old');collect('old','没有重复扣除或发放');server_action('production-check 1')
        config('v2');start('refund','renewed_forge',2)
        command('/nationproduction cancel '+identity('refund'),'生产批次已处理')
        command('/nationproduction cancel '+identity('refund'),'没有重复扣除或发放')
        start('changed','renewed_forge',1);capacity(True,2,2,1,1)
        command('/nationproduction start renewed_forge 2','生产已达上限')
        config('v3');command('/nationproduction start renewed_forge 2','请求被拒绝')
        server_action('production-status changed A RUNNING');capacity(True,2,3,1,1)
        config('invalid',False);command('/nationproduction recipes','精炼再修订');server_action('production-check 1')
    elif phase=='production-catalog-third':
        capacity(False,16,1000,1,1);command('/nationproduction start renewed_forge 1','已暂停新开工')
        server_action('production-ready changed A',seconds=110);collect('changed');collect('changed','没有重复扣除或发放');server_action('production-check 2')
        config('v3');capacity(True,2,3,0,0);start('latest','renewed_forge',1)
        server_action('production-ready latest A',seconds=110);collect('latest');collect('latest','没有重复扣除或发放');server_action('production-check 3')
        command('/nationproduction capacity','运行 0/2 个生产，占用 0/3 批')
        command('/chronicle contributions','生产资源 industrial')
        command('/chronicle contributions nation me','生产资源 industrial')
        server_action('production-status refund A CANCELLED');capacity(True,2,3,0,0)
    else:raise ValueError(phase)


def production_scenario(phase):
    if not manifest.get('nation_production_acceptance'): raise RuntimeError('Require prepared production scenario')
    server_action('stack-check');server_action('production-bind')
    def command(who,cmd,contains):
        request=json.dumps(dict(command=cmd,contains=contains),ensure_ascii=False).encode()
        client_action('expect '+who+' '+base64.urlsafe_b64encode(request).decode().rstrip('='))
    def identity(key):
        records=[json.loads(line) for line in (run/'evidence/epic-fixture.jsonl').read_text().splitlines()]
        return next(row['data']['run']['runId'] for row in reversed(records) if row['kind']=='PRODUCTION_RUN' and row['data']['key']==key)
    def start(key,who,batches):
        command(who,f'/nationproduction start industrial_refining {batches}','生产已开工')
        server_action(f'production-capture {key} {who} {batches}')
    def collect(key,who='A',response='生产批次已处理'):
        command(who,'/nationproduction complete '+identity(key),response)
    def ready(key,who='A'):server_action(f'production-ready {key} {who}',seconds=110)
    if phase in ('production-first','production-offline-first'):
        command('A','/nationproduction recipes','工业精炼')
        command('D','/nationproduction start industrial_refining 1','请求被拒绝')
        start('primary','A',1)
        collect('primary',response='尚未到领取时间')
        for who in 'DC':collect('primary',who,'请求被拒绝')
        ready('primary');collect('primary');collect('primary',response='没有重复扣除或发放')
        if phase=='production-offline-first':server_action('production-offline-buffered')
        else:server_action('production-check 1')
        start('pending','A',2);server_action('production-status pending A RUNNING')
    elif phase=='production-offline-second':
        collect('primary',response='没有重复扣除或发放');server_action('production-check 1')
        command('A','/chronicle contributions','生产资源 industrial')
    elif phase=='production-second':
        server_action('production-check 1');server_action('production-status pending A RUNNING')
        ready('pending');collect('pending');server_action('production-check 2')
        start('cancelled','A',1)
        command('A','/nationproduction cancel '+identity('cancelled'),'生产批次已处理')
        collect('cancelled',response='请求被拒绝');server_action('production-status cancelled A CANCELLED')
        start('old-founder','A',1);server_action('production-successor')
        collect('old-founder',response='请求被拒绝')
        start('successor','D',2);server_action('production-check 2')
    elif phase=='production-third':
        server_action('production-check 2');server_action('production-status old-founder D RUNNING')
        collect('cancelled','D','请求被拒绝');collect('primary','A','没有重复扣除或发放')
        ready('old-founder','D');collect('old-founder','D');server_action('production-check 3')
        ready('successor','D');collect('successor','D');collect('successor','D','没有重复扣除或发放');server_action('production-check 4')
        for who in 'AD':command(who,'/chronicle contributions','生产资源 industrial')
        command('D','/chronicle contributions nation me','生产资源 industrial')
    elif phase in ('production-recovery','production-offline-recovery'):
        offline=phase=='production-offline-recovery';count=1 if offline else 4
        server_action('production-check '+str(count))
        if offline:
            server_action('production-status pending A RUNNING')
            collect('primary',response='没有重复扣除或发放')
            command('A','/chronicle contributions','生产资源 industrial')
        else:
            collect('cancelled','D','请求被拒绝')
            for key,who in (('primary','A'),('pending','A'),('old-founder','D'),('successor','D')):
                collect(key,who,'没有重复扣除或发放')
            for who in 'AD':command(who,'/chronicle contributions','生产资源 industrial')
            command('D','/chronicle contributions nation me','生产资源 industrial')
        server_action('production-check '+str(count))
    else:raise ValueError(phase)


def scenario(phase):
    global clients
    await_lines(0, lambda lines: any('Done (' in line for _, line in lines) and any('EPIC_READY ' in line for _, line in lines), 180)
    if phase == 'production-offline-second':
        server_action('production-offline-consumed')
    if phase == 'sale-offline-second':
        server_action('sale-offline-consumed')
    if phase == 'memory-offline-second':
        server_action('memory-check offline-recorded')
    if phase == 'capital-offline-second':
        server_action('capital-check 205')
    cursor = len(observed)
    clients = start([str(args.node), '--max-old-space-size=192', str(client_script), str(run)], 'clients')
    await_lines(cursor, lambda lines: all(any('"kind":"SPAWN"' in line and '"who":"' + who + '"' in line for _, line in lines) for who in 'ABCD'))
    server_action('seed')
    if manifest.get('production_campaign'): server_action('stack-check')
    if manifest.get('battlefield_acceptance'):
        server_action('battle-bind')
        if phase != 'first': server_action('battle-check ended')
    if phase.startswith('legacy-'):
        legacy_factory_scenario(phase)
    elif phase.startswith('treasury-'):
        if not manifest.get('treasury_writer_acceptance'): raise RuntimeError('Treasury writer fixture required')
        server_action('stack-check')
        if phase == 'treasury-first':
            server_action('treasury-mutate first')
        elif phase == 'treasury-second':
            server_action('treasury-check first'); server_action('treasury-replay')
            server_action('treasury-mutate second')
        server_action('treasury-check ' + ('first' if phase == 'treasury-first' else 'second'))
        server_action('treasury-replay')
        server_action('treasury-check ' + ('first' if phase == 'treasury-first' else 'second'))
    elif phase.startswith('factory-'):
        factory_scenario(phase)
    elif phase.startswith('production-catalog-'):
        production_catalog_scenario(phase)
    elif phase.startswith('production-'):
        production_scenario(phase)
    elif phase.startswith('sale-'):
        sale_scenario(phase)
    elif phase == 'quest-first':
        server_action('open')
        for who in 'AB': server_action('task-ready ' + who)
        server_action('quest-check A 0 ACTIVE')
        server_action('quest-hold A')
        client_action('quest-abandon A')
        server_action('quest-check A 0 UNKNOWN')
        client_action('quest-gate A reconcile UNKNOWN 0')
        server_action('quest-unknown-retry')
        server_action('quest-check A 0 UNKNOWN')
        server_action('quest-release')
        server_action('quest-check A 0 ABANDONED')
        server_action('quest-reject foreign')
        client_action('quest-gate A retry ABANDONED 0')
        server_action('quest-check A 1 ACTIVE')
        server_action('quest-reject stale')
        server_action('quest-check B 0 ACTIVE')
    elif phase.startswith('capital-'):
        server_action('stack-check')
        if phase in ('capital-first', 'capital-offline-first'):
            server_action('capital-check 0'); server_action('capital-bind'); server_action('capital-burst')
            server_action('capital-reject A'); server_action('capital-repeat')
            server_action('capital-buffered' if phase == 'capital-offline-first' else 'capital-check 205')
        elif phase == 'capital-second':
            server_action('capital-check 205'); server_action('capital-repeat'); server_action('capital-successor')
            server_action('capital-reject D'); server_action('capital-check 206'); server_action('capital-delete'); server_action('capital-check 206')
        elif phase == 'capital-third':
            server_action('capital-check 206')
        elif phase == 'capital-offline-second':
            server_action('capital-repeat'); server_action('capital-check 205')
        else: raise RuntimeError('Unknown capital scenario')
    elif phase.startswith('memory-'):
        memory_scenario(phase)
    elif phase.startswith('stack-'):
        server_action('stack-check')
        server_action('stack-economy ' + ('first' if phase == 'stack-first' else 'restore'))
        stack_phase = 'first' if phase == 'stack-first' else 'restore'
        server_action('stack-protection ' + stack_phase)
        server_action('stack-geometry')
        server_action('stack-npcs ' + stack_phase)
        fixture = [json.loads(line) for line in (run / 'evidence/epic-fixture.jsonl').read_text().splitlines()]
        ids = next(row['data'] for row in reversed(fixture) if row['kind'] == 'STACK_NPCS')
        server_action(f'stack-npc-history {args.boot - 1} 0')
        client_action('npc A ' + str(ids['boundEntity']))
        time.sleep(2)
        server_action(f'stack-npc-history {args.boot} 1')
        client_action('close A')
        client_action('npc A ' + str(ids['unboundEntity']))
        time.sleep(2)
        server_action(f'stack-npc-history {args.boot} 2')
        client_action('close A')
        server_action('stack-npc-mode cancel')
        client_action('npc A ' + str(ids['boundEntity']))
        time.sleep(2)
        server_action(f'stack-npc-history {args.boot} 3')
        if phase == 'stack-third': server_action('stack-faults')
    elif phase.startswith('resource-'):
        server_action('resource-bind')
        if phase == 'resource-first':
            server_action('resource-check 0')
            server_action('resource-batch')
            server_action('resource-check 2')
            server_action('resource-rejections')
            server_action('resource-check 2')
        elif phase == 'resource-second':
            server_action('resource-check 2')
            server_action('resource-rejections')
            server_action('resource-check 2')
            server_action('resource-restart')
            server_action('resource-check 3')
        else:
            server_action('resource-check 3')
            server_action('resource-rejections')
            server_action('resource-check 3')
        request = json.dumps(dict(command='/chronicle contributions', contains='本页暂无已核验贡献。'), ensure_ascii=False).encode()
        client_action('expect A ' + base64.urlsafe_b64encode(request).decode().rstrip('='))
    elif phase.startswith('tech-'):
        server_action('tech-bind')
        if phase == 'tech-first':
            server_action('tech-check 0')
            server_action('tech-cycle')
            server_action('tech-check 3')
            server_action('tech-repeat')
            server_action('tech-check 3')
        elif phase == 'tech-second':
            server_action('tech-check 3')
            server_action('tech-revoke')
            server_action('tech-check 4')
        else:
            server_action('tech-check 4')
        request = json.dumps(dict(command='/chronicle contributions', contains='本页暂无已核验贡献。'), ensure_ascii=False).encode()
        client_action('expect A ' + base64.urlsafe_b64encode(request).decode().rstrip('='))
    elif phase.startswith('trade-'):
        if phase == 'trade-first':
            server_action('trade-write first')
            server_action('trade-check 1')
            server_action('trade-write late')
            server_action('trade-check 2')
            server_action('trade-dispute')
            server_action('trade-check 3')
        elif phase == 'trade-second':
            server_action('trade-check 3')
            server_action('trade-clean')
            server_action('trade-check 5')
        elif phase == 'trade-third':
            server_action('trade-check 5')
        request = json.dumps(dict(command='/chronicle contributions', contains='本页暂无已核验贡献。'), ensure_ascii=False).encode()
        client_action('expect A ' + base64.urlsafe_b64encode(request).decode().rstrip('='))
    elif phase.startswith('members-'):
        member_scenario(phase)
    elif phase == 'first':
        if manifest.get('quest_recovery_acceptance'):
            server_action('quest-check A 1 ACTIVE')
            server_action('quest-check B 0 ACTIVE')
            server_action('quest-reject stale')
        else: server_action('open')
        for who in 'AB':
            server_action('task-ready ' + who)
            client_action('craft ' + who)
            server_action('task-complete ' + who)
            if manifest.get('quest_recovery_acceptance'): server_action('quest-check '+who+' '+('1' if who=='A' else '0')+' COMMITTED')
        time.sleep(3)
        server_action('preparations')
        server_action('advance')
        if manifest['route'] == 'DUNGEON':
            for who in 'AB':
                server_action('dungeon ' + who)
                time.sleep(6)
        else:
            server_action('found')
            server_action('declare')
            if manifest.get('battlefield_acceptance'):
                server_action('battle-prepare')
                client_action('battle-open A')
                client_action('battle-try A 1 blocked')
                server_action('battle-check 0')
                server_action('battle-start')
                client_action('battle-open A')
                client_action('battle-try A 1 capture')
                server_action('battle-check 50')
                client_action('battle-try A 1 blocked')
                server_action('battle-check 50')
                client_action('battle-try A 2 capture')
                server_action('battle-check 100')
                server_action('battle-view C')
                client_action('battle-try C 1 blocked')
                server_action('battle-check 100')
                client_action('battle-end A')
                server_action('battle-finish')
                server_action('battle-view A')
                client_action('battle-try A 2 blocked')
                server_action('battle-check ended')
            else:
                server_action('end-war')
        server_action('checkpoint')
    elif phase == 'second':
        server_action('final-evidence')
        server_action('settle')
        time.sleep(3)
        server_action('check')
        server_action('replay')
        server_action('check')
    elif phase == 'third':
        server_action('check')
        server_action('replay')
        server_action('check')
    else:
        raise ValueError('first|second|third')
    if manifest.get('battlefield_acceptance') and phase != 'first':
        server_action('battle-view A')
        client_action('battle-try A 1 blocked')
        server_action('battle-check ended')
    if manifest.get('quest_recovery_acceptance') and phase in ('second','third'): server_action('quest-reject completed')
    if args.contributions:
        server_action('contributions')
        client_action('contributions A')
        client_action('contributions B')
    clients.stdin.write('quit\n'); clients.stdin.flush(); clients.wait(timeout=15)
    if args.interrupt_after_replay:
        if clients.returncode != 0:raise RuntimeError('Clients must finish replay before intentional interruption')
        outcome['interruption'] = recovery.interrupt(run, manifest, args.boot, server)
        print('EXPECTED_PROCESS_INTERRUPTION ' + phase, flush=True)
        return
    server.stdin.write('stop\n'); server.stdin.flush(); server.wait(timeout=60)
    if clients.returncode != 0 or server.returncode != 0:
        raise RuntimeError('Acceptance processes must exit normally')
    if manifest.get('treasury_writer_acceptance') or manifest.get('memory_acceptance') or manifest.get('capital_acceptance') or manifest.get('production_campaign') or manifest.get('nation_trade_acceptance') or manifest.get('nation_production_acceptance'): database_checkpoint()
    print('SCENARIO_COMPLETE ' + phase, flush=True)
outcome = dict(boot=args.boot, scenario=args.scenario, contributions=args.contributions, status='INCOMPLETE')
outcome['source_sha256'] = {
    'controller': hashlib.sha256(Path(str(prefix) + '.controller.py').read_bytes()).hexdigest(),
    'clients': hashlib.sha256(client_script.read_bytes()).hexdigest(),
}
if mysql_snapshot:
    outcome['source_sha256']['mysql_snapshot'] = mysql_snapshot.sha(frozen_mysql_tool)
if args.interrupt_after_replay:
    outcome['source_sha256']['recovery'] = mysql_snapshot.sha(Path(str(prefix) + '.recovery.py'))
try:
    commands = ['scenario ' + args.scenario] if args.scenario else sys.stdin
    for raw in commands:
        line = raw.strip()
        if line.startswith('scenario '):
            outcome['scenario'] = line.split()[1]
            scenario(line.split()[1])
            outcome['status'] = 'EXPECTED_PROCESS_INTERRUPTION' if args.interrupt_after_replay else 'COMPLETE'
            break
        elif line == 'clients start':
            if clients is not None:
                raise RuntimeError('Clients already started')
            clients = start([str(args.node), '--max-old-space-size=192', str(client_script), str(run)], 'clients')
        elif line.startswith('client '):
            if clients is None:
                raise RuntimeError('Clients are not started')
            clients.stdin.write(line[7:] + '\n'); clients.stdin.flush()
        elif line.startswith('server '):
            if server.poll() is not None:
                print('Server already exited: ' + str(server.returncode), flush=True)
                break
            server.stdin.write(line[7:] + '\n'); server.stdin.flush()
            if line == 'server stop':
                server.wait(timeout=60)
                break
        else:
            print('Expected scenario first|second|third | clients start | client ACTION | server COMMAND', flush=True)
except BaseException as error:
    outcome.update(status='FAILED', error=str(error))
    raise
finally:
    if clients is not None and clients.poll() is None:
        clients.stdin.write('quit\n'); clients.stdin.flush()
        try: clients.wait(timeout=15)
        except subprocess.TimeoutExpired: clients.terminate()
    if server.poll() is None:
        server.stdin.write('stop\n'); server.stdin.flush()
        try: server.wait(timeout=60)
        except subprocess.TimeoutExpired:
            print('Server did not stop normally; investigate retained PID ' + str(server.pid), flush=True)
    for reader in readers:
        reader.join(timeout=5)
    outcome['processes'] = process_records
    with Path(str(prefix) + '.session.json').open('x', encoding='utf-8') as log:
        json.dump(outcome, log, indent=2)
        log.write('\n')
