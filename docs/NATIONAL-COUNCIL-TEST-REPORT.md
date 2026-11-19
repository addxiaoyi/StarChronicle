# 国家议事构建与测试报告

生成时间：2026-11-20T06:47:20.046314+08:00。

实际 `clean verify` 通过：**674 项，失败 0、错误 0、跳过 0**。本报告独立保存新增模块证据，不覆盖此前构件报告。

| 构件 | SHA-256 |
| --- | --- |
| 插件 JAR | `792a84e345c4478b3731e7bff426c30d1aa21e1f1be499a967ed3e7a4aaa4694` |
| Java 源码 JAR | `a0a0c5ed3913a760bb59f098146c812741bed8c321ccbbdfe286d7f10f56c642` |
| 源码／配置／嵌入文档输入集合 | `8abeb2989e5e201900db6b63091f79fb3cebef9c507fd39e2ab9435b870a5f02` |

[完整构建日志](verification/national-council-maven-verify.log)；[测试套件、输入指纹与原始XML清单](verification/national-council-build-summary.json)。

## 国家功能与兼容回归

| 测试套件 | 项数 | 失败 | 错误 | 跳过 |
| --- | ---: | ---: | ---: | ---: |
| `io.github.addxiaoyi.starchronicle.config.NationalChoiceDependenciesTest` | 19 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.config.NationalCouncilExampleTest` | 6 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.core.DefinitionCanonicalCodecTest` | 12 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.core.NationalChoiceLedgerTest` | 12 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.core.NationalRuleEngineAuthorityBoundaryTest` | 9 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.core.NationalRuleEngineTest` | 14 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.core.NationalVoteLedgerTest` | 21 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.core.NationalVotePreparationTest` | 1 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.integration.StarCoreNationalVoteAuthorityTest` | 17 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.paper.NationalAuthorityServicesTest` | 4 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.paper.NationalCommandsTest` | 9 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.paper.NationalMenusTest` | 11 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.paper.NationalServicesTest` | 6 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.persistence.CanonicalDefinitionSchemaUpgradeTest` | 11 | 0 | 0 | 0 |

测试包含真实SQLite的国家修订CAS、继任者改票、撤回墓碑、国家失效、冻结证据、操作主体绑定、完成历史投影与错误回滚；模拟Bukkit测试验证菜单token、只读旁观和服务注册注销。完整源代码JAR已逐文件与当前生产Java源码比对；插件含新国家类与相同嵌入文档，未打入Bukkit或StarCore类。

## 真实服务器验收

构建部分记录实际Maven与SQLite／模拟Bukkit结果；后文另列匹配上表插件指纹的真实Paper与Folia运行报告。未明确记录的组合仍未验收，旧构件的服务器报告不能覆盖新增功能。

## 完整目标范围

国家席位并不代表财政援助、真实遗迹完成、正式战争或上游Folia全栈已经实现。任务完成闸门、长期贡献、探索与自动记忆配方等仍按[剩余工作](REMAINING-WORK.md)推进。国家表决API与可选完整示例见[国家议事协议](NATIONAL-COUNCIL.md)。

## 同源码的 Linux CI

GitHub Actions `Maven verification` 运行 `44631993203`、verify任务 `135940749644` 已对 `242b09c37c64712ac50ce1a6f5e656e3c6d948fe`（`codex/national-council`）实际完成：674项，失败／错误／跳过均为0，`BUILD SUCCESS`。见[CI报告](verification/national-council-ci-report.md)及[原始摘要与指纹](verification/national-council-ci-summary.json)。它认证该源码提交的独立Linux构建，不宣称其JAR字节与本地Windows候选相同。

## 对应候选的真实 Paper 验收

同一插件SHA已在真实Paper 1.21.11-132与审计StarCore构件组合完成51/51项观察，三轮服务器、客户端实际退出码均为0；完整报告见[国家议事Paper验收](verification/national-council-paper-report.md)。覆盖真实非OP持有人、同国一席、原生继任、CAS、撤回重启、三国分歧冻结、灭国票失效、跨国后旧资格拒绝、GUI证据与稳定本地奖励。

该范围保留并解释控制器预启动、离线检查重连、只读采集工具及上游Windows dungeon目录fsync问题，不是全日志无ERROR或StarCore全部模块认证。[实际旧Paper控制器](verification/national-council-paper-session-used.py)与[客户端](verification/national-council-paper-clients-used.cjs)单独保留，不以随包加固后的版本冒充。

## 对应候选的真实 Folia 独立降级验收

同一插件SHA在Folia 1.21.11-14、无StarCore及无替代国家权威的组合完成28/28项检查，服务器与客户端实际退出码均为0。四名真实非OP玩家可以读取Paper产生的国家票、冻结结果和历史证据；八次可信API及八次玩家命令投票／撤回请求均因没有当前原生国家资格被拒绝，八张国家议会页面无投票或撤回按钮。操作前、操作后及停服后，15张受保护表与真实Paper基线逐列逐行一致。详见[实际Folia报告](verification/national-council-folia-report.md)与[原始证据及指纹清单](verification/national-council-folia-evidence-inventory.json)。

票据界面的换行字段采用完整UUID、权威证据与修订状态严格重组比对，另有16项Python正负校验，未计入674项JUnit数量。容量预检、旧报告器换行假设和编译日志编码归档问题分别保留说明；没有通过重置国家票、历史或奖励消除失败。该范围不认证StarCore完整Folia国家服务，也不是人眼视觉批准。
