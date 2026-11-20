"""Generate an evidence-based local build report after a complete Maven verify.

Usage: python tools/verification_report.py target/verify.log
Does not execute tests, accept missing reports, or turn an unsuccessful build into success.
"""
from pathlib import Path
from datetime import datetime, timezone, timedelta
import hashlib
import sys
import xml.etree.ElementTree as ET
import zipfile
import json
import os

root = Path(__file__).resolve().parents[1]
log_path = root / (sys.argv[1] if len(sys.argv) > 1 else "target/verify.log")
log = log_path.read_text(encoding="utf-8", errors="replace")
if "BUILD SUCCESS" not in log or "BUILD FAILURE" in log:
    raise SystemExit("Report refused: Maven verify did not succeed")
suites = []
for path in sorted((root / "target/surefire-reports").glob("TEST-*.xml")):
    suite = ET.parse(path).getroot()
    suites.append((suite.attrib["name"], int(suite.attrib["tests"]), int(suite.attrib["failures"]), int(suite.attrib["errors"]), int(suite.attrib["skipped"])))
if not suites or any(f or e for _, _, f, e, _ in suites):
    raise SystemExit("Report refused: missing or failing Surefire reports")
artifact = root / "target/starchronicle-0.1.0-SNAPSHOT.jar"
sources = root / "target/starchronicle-0.1.0-SNAPSHOT-sources.jar"
if not artifact.is_file() or not sources.is_file():
    raise SystemExit("Report refused: both plugin and sources JAR are required")
with zipfile.ZipFile(artifact) as jar:
    names = jar.namelist()
    entry_class = "io/github/addxiaoyi/starchronicle/paper/StarChroniclePlugin.class"
    required = ["plugin.yml", "config.yml", "npcs.yml", "stories/last-heir.yml", "stories/siege-embers.yml", "stories/silk-road-scholar.yml", "stories/heir-legacy.yml", entry_class]
    required += [f"stories/fate-{role}.yml" for role in ("soldier", "general", "merchant", "scholar", "explorer", "diplomat", "mayor", "monarch")]
    required += [f"starcore-scenes/chronicle_{scene}.yml" for scene in ("last_heir", "siege_embers", "silk_road")]
    required += ["io/github/addxiaoyi/starchronicle/core/DefinitionCatalog.class",
                 "io/github/addxiaoyi/starchronicle/core/StoryRuntime.class",
                 "io/github/addxiaoyi/starchronicle/api/HistoricalEvaluationReport.class",
                 "io/github/addxiaoyi/starchronicle/core/QuestGateLedger.class",
                 "io/github/addxiaoyi/starchronicle/core/QuestGateDriver.class",
                 "io/github/addxiaoyi/starchronicle/api/quest/QuestEvidenceProvider.class",
                 "io/github/addxiaoyi/starchronicle/history/FirstVisitJournal.class",
                 "io/github/addxiaoyi/starchronicle/history/FirstVisitBuffer.class",
                 "io/github/addxiaoyi/starchronicle/paper/ExplorationDiscoveries.class",
                 "io/github/addxiaoyi/starchronicle/paper/ExplorationRegion.class"]
    for name in required:
        if name not in names:
            raise SystemExit(f"Artifact missing {name}")
    if any(n.startswith(("org/bukkit/", "dev/starcore/")) for n in names):
        raise SystemExit("Artifact illegally bundles platform or StarCore classes")
    major = int.from_bytes(jar.read(entry_class)[6:8], "big")
    if major != 65:
        raise SystemExit(f"Expected Java 21 bytecode, got class version {major}")
    if not any(n.startswith("org/sqlite/native/") for n in names):
        raise SystemExit("Artifact missing SQLite native runtime")
    for resource in (root / "src/main/resources").rglob("*"):
        if resource.is_file():
            name=resource.relative_to(root / "src/main/resources").as_posix()
            if name not in names or jar.read(name)!=resource.read_bytes():
                raise SystemExit("Resource changed after build: "+name)
    for name in ("API.md","STORY-FORMAT.md","CONTINUOUS-NARRATIVE.md","QUEST-GATES.md"):
        if jar.read("META-INF/starchronicle/docs/"+name)!=(root/"docs"/name).read_bytes():
            raise SystemExit("Embedded documentation changed after build: "+name)
with zipfile.ZipFile(sources) as jar:
    for source in (root / "src/main/java").rglob("*.java"):
        name=source.relative_to(root / "src/main/java").as_posix()
        if name not in jar.namelist() or jar.read(name)!=source.read_bytes():
            raise SystemExit("Main source changed after build: "+name)

total = tuple(sum(s[i] for s in suites) for i in range(1, 5))
verify_command = sys.argv[2] if len(sys.argv)>2 else (".\\mvnw.cmd" if os.name == "nt" else "./mvnw") + " -B -ntp clean verify"
lines = [
    "# StarChronicle 构建与验证报告", "",
    f"生成时间：{datetime.now(timezone(timedelta(hours=8))).isoformat(timespec='seconds')}（Asia/Shanghai）。", "",
    "此报告由 `tools/verification_report.py` 从实际 Maven 日志、Surefire XML 和打包产物生成；不会将静态审查或未执行的服务器测试计为通过。", "",
    "## 构建结果", "", f"- 执行命令：`{verify_command}`（Java 21）。",
    "- Maven：`BUILD SUCCESS`，完整构建输出见 `verification/maven-verify.log`。",
    f"- 单元／集成回归：{total[0]} 项，失败 {total[1]}，错误 {total[2]}，跳过 {total[3]}。",
    "- 产物包含真实插件入口、配置、三条主剧情、旧都档案后续、八条身份线、NPC目录、三个原生场景与 SQLite 原生库。",
    "- 插件入口字节码为 Java 21（class major 65）。未打入 Bukkit/Paper 或 StarCore 类。",
    "- 同时生成全部 Java 源码 JAR。", "",
    "| 测试套件 | 项数 | 失败 | 错误 | 跳过 |", "|---|---:|---:|---:|---:|"
]
lines += [f"| `{name}` | {t} | {f} | {e} | {s} |" for name,t,f,e,s in suites]
coverage = root / "target/site/jacoco/jacoco.xml"
if coverage.is_file():
    lines += ["", "## 覆盖率", "", "由 JaCoCo 对本次构建插桩所得；此数值仅表示已执行代码，不等同于 Paper/Folia 实服兼容性。", "", "| 包 | 行覆盖 | 分支覆盖 |", "|---|---:|---:|"]
    for package in ET.parse(coverage).getroot().findall("package"):
        values = {}
        for c in package.findall("counter"):
            covered, missed = int(c.attrib["covered"]), int(c.attrib["missed"])
            values[c.attrib["type"]] = f"{covered}/{covered+missed} ({covered/(covered+missed)*100:.1f}%)" if covered+missed else "无适用分支"
        lines.append(f"| `{package.attrib['name']}` | {values.get('LINE','—')} | {values.get('BRANCH','—')} |")
lines += ["", "## 产物校验", "", "| 文件 | 字节数 | SHA-256 |", "|---|---:|---|"]
for path in (artifact, sources):
    lines.append(f"| `{path.relative_to(root).as_posix()}` | {path.stat().st_size} | `{hashlib.sha256(path.read_bytes()).hexdigest()}` |")
lines += ["", "## 真实服务器证据与构建对应", "", "以下实机证据独立于JUnit统计。构建指纹不同的旧报告保留为回归背景，不能作为当前JAR实机验收。", "", "| 报告 | 实测构建SHA-256 | 对应当前JAR | 记录结论 |", "|---|---|---|---|"]
current_hash=hashlib.sha256(artifact.read_bytes()).hexdigest()
evidence_reports=[("paper-smoke","Paper + StarCore"),("folia-smoke","Folia standalone"),("expanded-paper-smoke","Expanded Paper + StarCore"),("expanded-folia-smoke","Expanded Folia standalone"),("phase-two-paper","Continuous narrative Paper + StarCore"),("phase-two-folia","Continuous narrative Folia standalone"),("phase-two-v2-upgrade","Actual V2 database upgrade"),("phase-three-paper","Version catalog Paper + StarCore"),("phase-three-folia","Version catalog Folia standalone")]
for summary_path in sorted((root/"docs/verification").glob("definition-upgrade-*-summary.json")):
    prefix=summary_path.name.removesuffix("-summary.json")
    evidence_reports.append((prefix,"Actual definition upgrade: "+prefix.removeprefix("definition-upgrade-")))
for summary_path in sorted((root/"docs/verification").glob("exploration-*-summary.json")):
    if summary_path.name=="exploration-build-summary.json":continue
    prefix=summary_path.name.removesuffix("-summary.json")
    evidence_reports.append((prefix,"Configured first visits: "+prefix.removeprefix("exploration-")))
for summary_path in sorted((root/"docs/verification").glob("quest-evidence-*-summary.json")):
    prefix=summary_path.name.removesuffix("-summary.json")
    evidence_reports.append((prefix,"Native quest evidence: "+prefix.removeprefix("quest-evidence-")))
for prefix,title in evidence_reports:
    report=root/f"docs/verification/{prefix}-report.md"
    summary=root/f"docs/verification/{prefix}-summary.json"
    if not report.is_file() or not summary.is_file():
        continue
    data=json.loads(summary.read_text(encoding="utf-8-sig"))
    tested=data.get("plugin_sha256",data.get("artifact_sha256","未记录"))
    checks=data.get("checks",{})
    boolean_checks=[value for value in checks.values() if isinstance(value,bool)] if isinstance(checks,dict) else []
    status=data.get("status")
    if not status:
        status="PASS" if boolean_checks and all(boolean_checks) else "见独立报告"
    if boolean_checks:
        status+=f"；{sum(boolean_checks)}/{len(boolean_checks)}"
    unsupported=[key for key,value in checks.items() if value is None] if isinstance(checks,dict) else []
    if unsupported:
        status+="；未执行／未支持："+", ".join(unsupported)
    lines.append(f"| [{title}](verification/{prefix}-report.md) | `{tested}` | {'是' if tested==current_hash else '否，不能覆盖当前构建'} | {status} |")
handoff=root/"patches/quest-evidence/static-handoff-report.json"
if handoff.is_file():
    upstream=json.loads(handoff.read_text(encoding="utf-8-sig"))
    focused=upstream.get("currentFocusedQuestEvidenceRun",{})
    full=upstream.get("latestFullUpstreamRun",{})
    candidate=upstream.get("currentNativePackage",{})
    lines += ["", "## 原生任务候选的独立边界", "",
              f"StarCore 候选 JAR SHA-256：`{candidate.get('sha256','未记录')}`。",
              f"聚焦测试：{focused.get('tests','未记录')} 项，失败 {focused.get('failures','未记录')}，错误 {focused.get('errors','未记录')}，跳过 {focused.get('skipped','未记录')}；该次证据为六份实际 Surefire XML，未保留统一原始控制台日志。",
              f"StarCore 完整回归：{full.get('tests','未记录')} 项，失败 {full.get('failures','未记录')}，错误 {full.get('errors','未记录')}，跳过 {full.get('skipped','未记录')}，`BUILD {full.get('build','未记录')}`。不能用聚焦测试或 Paper 功能验收覆盖该失败。",
              "候选补丁尚未上游合并或生产部署。各次失败、不完整 attempt、UNKNOWN 对账与 Folia 限制见 [任务证据审查](QUEST-GATE-REVIEW.md) 和 [原生静态交接](../patches/quest-evidence/static-handoff-report.json)。"]
lines += ["", "## 验证边界", "", "- 数据库与规则测试使用真实 SQLite JDBC，并包含多线程调用、重启重开、事件重放、稳定效果收据和错误回滚。", "- Bukkit/Paper API 的编译或 Mockito 调度测试不能替代实际 Paper/Folia 服务器多人联机验收。", "- 上游 StarCore 的 Folia 调度限制、未提供的提交后事件、国家权限和第三方 NPC 具体构建版本，以 `UPSTREAM-AUDIT.md`、`NPC-INTEGRATIONS.md` 与 `REQUIREMENTS.md` 的证据和边界为准。", "- 本报告不声称已运行真实国家战争、NPC 客户端数据包或外部奖励到账的端到端验收。", ""]
destination = root / "docs/TEST-REPORT.md"
destination.parent.mkdir(parents=True, exist_ok=True)
destination.write_text("\n".join(lines), encoding="utf-8")
evidence_dir = root / "docs/verification"
evidence_dir.mkdir(parents=True, exist_ok=True)
(evidence_dir / "maven-verify.log").write_text("\n".join(line.rstrip() for line in log.splitlines())+"\n", encoding="utf-8", newline="\n")
print(f"Wrote {destination}; tests={total[0]}, failures={total[1]}, errors={total[2]}")
