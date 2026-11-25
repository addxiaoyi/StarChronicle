"""Archive a successful real national-council Maven build without rewriting prior reports."""
from pathlib import Path
from datetime import datetime, timezone, timedelta
import hashlib
import json
import shutil
import sys
import xml.etree.ElementTree as ET
import zipfile


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    root=Path(__file__).resolve().parents[1]
    log=root/(sys.argv[1] if len(sys.argv)>1 else 'run/council-final-clean-verify.log')
    text=log.read_text(encoding='utf-8',errors='replace')
    if 'BUILD SUCCESS' not in text or 'BUILD FAILURE' in text:raise SystemExit('Refusing a failed or incomplete build')
    suites=[];paths=sorted((root/'target/surefire-reports').glob('TEST-*.xml'))
    for path in paths:
        data=ET.parse(path).getroot().attrib
        suites.append({k:data[k] for k in ('name','tests','failures','errors','skipped')})
    totals={key:sum(int(s[key]) for s in suites) for key in ('tests','failures','errors','skipped')}
    if not suites or any(totals[k] for k in ('failures','errors','skipped')):raise SystemExit('Require complete passing Surefire reports')
    artifact=root/'target/starchronicle-0.1.0-SNAPSHOT.jar';sources=root/'target/starchronicle-0.1.0-SNAPSHOT-sources.jar'
    with zipfile.ZipFile(artifact) as jar:
        names=jar.namelist()
        for name in ('api/NationalVoteAuthority.class','api/NationalVoteState.class','core/NationalVoteLedger.class','integration/StarCoreNationalVoteAuthority.class'):
            if 'io/github/addxiaoyi/starchronicle/'+name not in names:raise SystemExit('Missing national class '+name)
        if any(name.startswith(('org/bukkit/','dev/starcore/')) for name in names):raise SystemExit('Platform classes were bundled')
        major=int.from_bytes(jar.read('io/github/addxiaoyi/starchronicle/paper/StarChroniclePlugin.class')[6:8],'big')
        if major!=65:raise SystemExit('Require Java 21 class files')
        for name in ('docs/API.md','docs/CONTINUOUS-NARRATIVE.md','docs/NATIONAL-COUNCIL.md'):
            if jar.read('META-INF/starchronicle/'+name)!=(root/name).read_bytes():raise SystemExit('Embedded document drift '+name)
    with zipfile.ZipFile(sources) as jar:
        for source in (root/'src/main/java').rglob('*.java'):
            name=source.relative_to(root/'src/main/java').as_posix()
            if jar.read(name)!=source.read_bytes():raise SystemExit('Source JAR drift '+name)
    inputs=sorted([root/'pom.xml',root/'LICENSE']+[p for parent in ('src/main','src/test','examples/national-council') for p in (root/parent).rglob('*') if p.is_file()]+[root/'docs'/f for f in ('API.md','CONTINUOUS-NARRATIVE.md','NATIONAL-COUNCIL.md')])
    entries=[{'path':p.relative_to(root).as_posix(),'sha256':sha(p)} for p in inputs]
    source_hash=hashlib.sha256(json.dumps(entries,ensure_ascii=False,separators=(',',':')).encode()).hexdigest()
    evidence=root/'docs/verification';evidence.mkdir(exist_ok=True)
    output_log=evidence/'national-council-maven-verify.log';shutil.copyfile(log,output_log)
    xml_dir=evidence/'national-council-surefire';xml_dir.mkdir(exist_ok=True)
    for path in paths:shutil.copyfile(path,xml_dir/path.name)
    result={'generated_at':datetime.now(timezone(timedelta(hours=8))).isoformat(),'status':'PASS','command':'.\\mvnw.cmd -B -ntp clean verify','plugin_sha256':sha(artifact),'sources_sha256':sha(sources),'source_fingerprint':source_hash,'class_major':major,'totals':totals,'suites':suites,'inputs':entries,'build_log_sha256':sha(output_log),'surefire_xml':[{'path':p.relative_to(root).as_posix(),'sha256':sha(p)} for p in sorted(xml_dir.glob('TEST-*.xml'))],'server_acceptance':'Separate fingerprint-bound runtime reports required; unit/build evidence alone makes no Paper/Folia claim.'}
    (evidence/'national-council-build-summary.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    national=[s for s in suites if 'National' in s['name'] or 'Canonical' in s['name']]
    lines=['# 国家议事构建与测试报告','',f"生成时间：{result['generated_at']}。",'',f"实际 `clean verify` 通过：**{totals['tests']} 项，失败 0、错误 0、跳过 0**。本报告独立保存新增模块证据，不覆盖此前构件报告。",'', '| 构件 | SHA-256 |','| --- | --- |',f"| 插件 JAR | `{result['plugin_sha256']}` |",f"| Java 源码 JAR | `{result['sources_sha256']}` |",f"| 源码／配置／嵌入文档输入集合 | `{source_hash}` |",'','[完整构建日志](verification/national-council-maven-verify.log)；[测试套件、输入指纹与原始XML清单](verification/national-council-build-summary.json)。','', '## 国家功能与兼容回归','', '| 测试套件 | 项数 | 失败 | 错误 | 跳过 |','| --- | ---: | ---: | ---: | ---: |']
    for suite in national:lines.append('| `'+suite['name']+'` | '+' | '.join(suite[k] for k in ('tests','failures','errors','skipped'))+' |')
    lines+=['','测试包含真实SQLite的国家修订CAS、继任者改票、撤回墓碑、国家失效、冻结证据、操作主体绑定、完成历史投影与错误回滚；模拟Bukkit测试验证菜单token、只读旁观和服务注册注销。完整源代码JAR已逐文件与当前生产Java源码比对；插件含新国家类与相同嵌入文档，未打入Bukkit或StarCore类。','','## 真实服务器验收','','本构建报告只记录实际Maven与SQLite／模拟Bukkit结果。新的真实Paper／Folia验收须使用上表同一插件指纹，另附服务器、客户端、原生操作与重启证据；未明确记录的运行组合仍未验收。旧构件的Paper/Folia报告不能覆盖本构建。','','## 完整目标范围','','国家席位并不代表财政援助、真实遗迹完成、正式战争或上游Folia全栈已经实现。任务完成闸门、长期贡献、探索与自动记忆配方等仍按[剩余工作](REMAINING-WORK.md)推进。国家表决API与可选完整示例见[国家议事协议](NATIONAL-COUNCIL.md)。','']
    (root/'docs/NATIONAL-COUNCIL-TEST-REPORT.md').write_text('\n'.join(lines),encoding='utf-8')
    print(json.dumps({k:result[k] for k in ('status','totals','plugin_sha256','sources_sha256','source_fingerprint')},indent=2))


if __name__=='__main__':main()
