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

本构建报告只记录实际Maven与SQLite／模拟Bukkit结果。新的真实Paper／Folia验收须使用上表同一插件指纹，另附服务器、客户端、原生操作与重启证据；未明确记录的运行组合仍未验收。旧构件的Paper/Folia报告不能覆盖本构建。

## 完整目标范围

国家席位并不代表财政援助、真实遗迹完成、正式战争或上游Folia全栈已经实现。任务完成闸门、长期贡献、探索与自动记忆配方等仍按[剩余工作](REMAINING-WORK.md)推进。国家表决API与可选完整示例见[国家议事协议](NATIONAL-COUNCIL.md)。
