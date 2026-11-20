"""Archive completed exploration evidence without changing source logs or distributing databases/JARs.

The strict inspector is rerun with its sole report write captured in memory. --check only
verifies the source and prints the proposed archive inventory; it creates no archive files.
"""
from pathlib import Path
import argparse
from contextlib import redirect_stdout
import hashlib
import importlib.util
import io
import json
import re
import sys
from types import SimpleNamespace
from unittest.mock import patch
from quest_evidence_control import process_identity

ROOT=Path(__file__).resolve().parents[1]


def digest(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def require(value,message):
    if not value:raise RuntimeError(message)
def load(path):return json.loads(path.read_text(encoding='utf-8'))


def inspect_read_only(run,sha):
    specification=importlib.util.spec_from_file_location('exploration_archive_inspector',ROOT/'tools/exploration-acceptance.py')
    inspector=importlib.util.module_from_spec(specification);specification.loader.exec_module(inspector)
    retained=run/'evidence/inspection.json';before=retained.read_bytes();saved=json.loads(before);captured=[]
    def capture(path,value):
        require(path==retained,'Inspector attempted an unexpected write');captured.append(value)
    with patch.object(inspector,'write',capture),redirect_stdout(io.StringIO()):inspector.inspect(SimpleNamespace(sha=sha),run)
    require(len(captured)==1 and captured[0]==saved and saved.get('status')=='PASS','Retained PASS differs from independent strict inspection')
    require(retained.read_bytes()==before,'Source inspection evidence was changed')
    return saved


def inventory(run):
    files=[run/'preparation.json',run/'server.properties',run/'fixture/ExplorationPaperFixture.java',run/'plugins/StarChronicle/config.yml']
    files+=sorted((run/'plugins/StarChronicle/stories').glob('*.json'))
    files+=sorted(path for path in (run/'evidence').rglob('*') if path.is_file() and path.suffix in {'.json','.jsonl','.log','.py','.cjs','.lock'})
    require(not (run/'evidence/active.lock').exists(),'A boot still has an active ownership lock')
    result=[]
    for path in sorted(set(files)):
        require(path.is_file() and not path.is_symlink() and path.resolve().is_relative_to(run),'Unsafe or missing evidence file')
        name=path.relative_to(run).as_posix();require(path.stat().st_size<=100*1024*1024,'Unbounded raw evidence file')
        result.append({'source':name,'file':name,'bytes':path.stat().st_size,'sha256':digest(path)})
    require(len(result)<=200,'Unexpected raw evidence inventory size')
    return result


def require_retired_auto_owner(run):
    path=run/'evidence/auto-owner.json'
    if not path.exists():return
    owner=load(path);require(owner.get('status')=='PASS' and owner.get('inspection_exit')==0,'Automatic owner has not completed strict acceptance')
    require(type(owner.get('owner_pid')) is int and owner.get('owner_identity'),'Automatic owner creation identity is missing')
    require(process_identity(owner['owner_pid'])!=owner['owner_identity'],'Automatic coordinator is still running; raw inventory is not final')
    require([item.get('boot') for item in owner.get('boots',[])]==[1,2] and all(item.get('status')=='NORMAL_COMPLETED' and item.get('exit_code')==0 for item in owner['boots']),'Automatic owner lacks two normally completed sessions')
    for record in load(run/'evidence/processes.json')['boots']:
        require(record.get('all_owned_exited') is True,'Owned boot children have not all exited')
        for kind in ('session','server','client'):
            require(type(record.get(kind+'_pid')) is int and record.get(kind+'_identity'),'Owned child creation identity is missing')
            require(process_identity(record[kind+'_pid'])!=record[kind+'_identity'],'Owned '+kind+' is still running; archive is premature')


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--sha',required=True);parser.add_argument('--platform',choices=['paper','folia'],required=True);parser.add_argument('--attempt',required=True);parser.add_argument('--check',action='store_true');args=parser.parse_args()
    require(re.fullmatch('[0-9a-f]{64}',args.sha) and re.fullmatch('[a-z0-9-]{1,24}',args.attempt),'Require exact SHA and safe attempt')
    run=(ROOT/'run'/f'exploration-{args.sha[:8]}-{args.platform}-{args.attempt}').resolve();require(run.is_dir(),'Completed source run is missing')
    require_retired_auto_owner(run)
    result=inspect_read_only(run,args.sha);require(result['platform']==args.platform and result['product_sha256']==args.sha,'Source identity differs')
    files=inventory(run);prefix=f'exploration-{args.platform}-{args.sha[:8]}';destination=ROOT/'docs/verification'/prefix
    summary_path=destination.with_name(prefix+'-summary.json');report_path=destination.with_name(prefix+'-report.md')
    manifest={'source_run':str(run),'plugin_sha256':args.sha,'files':files,'excludes':['runtime databases','third-party JARs','server worlds']}
    if args.check:
        print(json.dumps({'status':'CHECKED_READ_ONLY','source_run':str(run),'proposed_archive':str(destination),'files':len(files),'cases':result['cases']},ensure_ascii=False,indent=2));return
    require(not any(path.exists() for path in (destination,summary_path,report_path)),'Refusing to overwrite an existing evidence archive')
    destination.mkdir()
    for item in files:
        source=run/item['source'];raw=source.read_bytes();require(len(raw)==item['bytes'] and hashlib.sha256(raw).hexdigest()==item['sha256'],'Source changed during archive')
        target=destination/item['file'];target.parent.mkdir(parents=True,exist_ok=True)
        with target.open('xb') as output:output.write(raw)
    (destination/'evidence-manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    require(inventory(run)==files,'Source evidence changed before archive completed')
    summary={**result,'plugin_sha256':args.sha,'checks':result['cases'],'source_run':str(run),'raw_evidence':prefix+'/evidence-manifest.json'}
    summary_path.write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    checks=result['cases'];passed=sum(value is True for value in checks.values());unsupported=[key for key,value in checks.items() if value is None]
    lines=[f'# 配置地区首访 {args.platform.title()} 验收','',f'插件 SHA-256：`{args.sha}`。同一独立测试服完成两次正常启动／退出，server 与 client 各次退出码均为 0。','',f'{passed} 项可执行检查通过：真实步行、实际传送、真实 GUI 选择、重启后规范首访及再次到访幂等。']
    if args.platform=='paper':lines+=['','本轮还验证了真实取消传送不入史。']
    if unsupported:lines+=['','Folia 取消传送明确为 `UNSUPPORTED`／`null`，不计为通过。原始启动日志保留实际平台限制，控制器没有发送或伪造取消传送。']
    lines+=['','两次正常停服的 SQLite 均保留 2 条规范首访、2 条 ACKED 首访回执、2 个完成实例、2 条 LIVE ballot／2 条 resolution、4 条 DONE 结果，最终荣誉严格为 5。独立检查器复核实际物理动作、GUI 实例与节点、来源／范围／个人索引及两次数据库检查点的一致性。','',f'机器结果见 [summary]({prefix}-summary.json)，{len(files)} 份原始日志、实际使用脚本、配置和检查点描述见 [逐文件哈希清单]({prefix}/evidence-manifest.json)。原始停服数据库保留在 `{run.relative_to(ROOT).as_posix()}`，归档不包含数据库、服务器世界或第三方 JAR。','','本轮显式使用 `require-starcore:false`，只验证 Chronicle 自有探索能力，不代表 StarCore 全栈 Folia 或原生国家权威通过。时间基准为 `OBSERVED`，瞬间经过且未被实体线程确认的到访不会被补造。旧构建或失败 attempt 独立保留，不能与本轮合并计算。','']
    report_path.write_text('\n'.join(lines),encoding='utf-8')
    print(json.dumps({'status':'ARCHIVED','archive':str(destination),'files':len(files),'manifest_sha256':digest(destination/'evidence-manifest.json'),'summary_sha256':digest(summary_path)},ensure_ascii=False,indent=2))


if __name__=='__main__':
    for stream in (sys.stdout,sys.stderr):
        if hasattr(stream,'reconfigure'):stream.reconfigure(encoding='utf-8',errors='backslashreplace')
    main()
