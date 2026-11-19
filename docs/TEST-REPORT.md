# StarChronicle 构建与验证报告

生成时间：2026-11-20T02:17:05+08:00（Asia/Shanghai）。

此报告由 `tools/verification_report.py` 从实际 Maven 日志、Surefire XML 和打包产物生成；不会将静态审查或未执行的服务器测试计为通过。

## 构建结果

- 执行命令：`.\mvnw.cmd -B -ntp clean verify`（Java 21）。
- Maven：`BUILD SUCCESS`，完整构建输出见 `verification/maven-verify.log`。
- 单元／集成回归：325 项，失败 0，错误 0，跳过 0。
- 产物包含真实插件入口、配置、三条主剧情、八条身份线、NPC目录、三个原生场景与 SQLite 原生库。
- 插件入口字节码为 Java 21（class major 65）。未打入 Bukkit/Paper 或 StarCore 类。
- 同时生成全部 Java 源码 JAR。

| 测试套件 | 项数 | 失败 | 错误 | 跳过 |
|---|---:|---:|---:|---:|
| `io.github.addxiaoyi.starchronicle.config.ConditionLogicTest` | 13 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.config.ConfigLoaderTest` | 8 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.config.SampleStoryContractTest` | 6 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.core.DeferredTriggerTest` | 17 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.core.FateStoriesTest` | 18 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.core.InvocationGateTest` | 3 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.core.NativeDispatchTest` | 1 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.core.NativeSceneDeliveryTest` | 1 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.core.NativeSceneFlightsTest` | 10 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.core.OrchestrationRegressionTest` | 2 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.core.RuleEngineTest` | 23 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.core.SampleStoryPlaythroughTest` | 22 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.core.TextServiceTest` | 8 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.history.ObservationJournalTest` | 14 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.integration.CoreContentInstallerTest` | 10 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.integration.NativeRecoverySafetyTest` | 12 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.integration.NativeSceneAdapterTest` | 9 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.integration.NativeSceneRecoveryAdapterTest` | 21 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.integration.SnapshotCollectorTest` | 3 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.integration.StarCoreAuthorityProviderTest` | 16 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.integration.StarCoreBridgeTest` | 19 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.integration.TechnologyFactsTest` | 7 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.memory.MemoryLedgerTest` | 5 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.narrative.NativeSceneJournalTest` | 25 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.npc.NpcEngineTransactionTest` | 3 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.npc.NpcSystemTest` | 12 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.paper.MemorySitesTest` | 16 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.paper.MenusTest` | 8 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.paper.RegionTasksTest` | 5 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.paper.StateWorkerTest` | 2 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.persistence.SchemaMigrationTest` | 2 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.persistence.SqliteStoreTest` | 4 | 0 | 0 | 0 |

## 覆盖率

由 JaCoCo 对本次构建插桩所得；此数值仅表示已执行代码，不等同于 Paper/Folia 实服兼容性。

| 包 | 行覆盖 | 分支覆盖 |
|---|---:|---:|
| `io/github/addxiaoyi/starchronicle/api` | 17/27 (63.0%) | 24/42 (57.1%) |
| `io/github/addxiaoyi/starchronicle/paper` | 81/543 (14.9%) | 113/674 (16.8%) |
| `io/github/addxiaoyi/starchronicle/integration` | 370/570 (64.9%) | 454/829 (54.8%) |
| `io/github/addxiaoyi/starchronicle/memory` | 50/50 (100.0%) | 37/52 (71.2%) |
| `io/github/addxiaoyi/starchronicle/persistence` | 40/40 (100.0%) | 5/8 (62.5%) |
| `io/github/addxiaoyi/starchronicle/history` | 77/78 (98.7%) | 74/91 (81.3%) |
| `io/github/addxiaoyi/starchronicle/config` | 110/114 (96.5%) | 166/234 (70.9%) |
| `io/github/addxiaoyi/starchronicle/narrative` | 88/88 (100.0%) | 113/150 (75.3%) |
| `io/github/addxiaoyi/starchronicle/npc` | 222/225 (98.7%) | 189/272 (69.5%) |
| `io/github/addxiaoyi/starchronicle/core` | 329/333 (98.8%) | 401/483 (83.0%) |

## 产物校验

| 文件 | 字节数 | SHA-256 |
|---|---:|---|
| `target/starchronicle-0.1.0-SNAPSHOT.jar` | 17578721 | `abaadf4c4dd318d518687bafe2596f43e32c9f9a115b4074deeae9f37abc22df` |
| `target/starchronicle-0.1.0-SNAPSHOT-sources.jar` | 140218 | `feff6cdf86e183463b448663b185c9e1cfbfe9e1aa0e4c5f36917a660204fe45` |

## 真实服务器证据与构建对应

以下实机证据独立于JUnit统计。构建指纹不同的旧报告保留为回归背景，不能作为当前JAR实机验收。

| 报告 | 实测构建SHA-256 | 对应当前JAR | 记录结论 |
|---|---|---|---|
| [Paper + StarCore](verification/paper-smoke-report.md) | `5e227d20c46eeb91dd98d9d33e6f55cd66fbfc4fd7ecd7391f57eb9643f0d86e` | 否，不能覆盖当前构建 | 见独立报告；14/14 |
| [Folia standalone](verification/folia-smoke-report.md) | `5e227d20c46eeb91dd98d9d33e6f55cd66fbfc4fd7ecd7391f57eb9643f0d86e` | 否，不能覆盖当前构建 | 见独立报告；18/18 |
| [Expanded Paper + StarCore](verification/expanded-paper-smoke-report.md) | `abaadf4c4dd318d518687bafe2596f43e32c9f9a115b4074deeae9f37abc22df` | 是 | PASS；27/27 |

## 验证边界

- 数据库与规则测试使用真实 SQLite JDBC，并包含多线程调用、重启重开、事件重放、稳定效果收据和错误回滚。
- Bukkit/Paper API 的编译或 Mockito 调度测试不能替代实际 Paper/Folia 服务器多人联机验收。
- 上游 StarCore 的 Folia 调度限制、未提供的提交后事件、国家权限和第三方 NPC 具体构建版本，以 `UPSTREAM-AUDIT.md`、`NPC-INTEGRATIONS.md` 与 `REQUIREMENTS.md` 的证据和边界为准。
- 本报告不声称已运行真实国家战争、NPC 客户端数据包或外部奖励到账的端到端验收。
