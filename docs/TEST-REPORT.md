# StarChronicle 构建与验证报告

生成时间：2026-11-26T02:34:12+08:00（Asia/Shanghai）。

此报告由 `tools/verification_report.py` 从实际 Maven 日志、Surefire XML 和打包产物生成；不会将静态审查或未执行的服务器测试计为通过。

## 构建结果

- 执行命令：`./mvnw -o -B -ntp verify`（Java 21）。
- Maven：`BUILD SUCCESS`，完整构建输出见 `verification/maven-verify.log`。
- 单元／集成回归：947 项，失败 0，错误 0，跳过 0。
- 产物包含真实插件入口、配置、三条主剧情、旧都档案后续、八条身份线、NPC目录、三个原生场景与 SQLite 原生库。
- 插件入口字节码为 Java 21（class major 65）。未打入 Bukkit/Paper 或 StarCore 类。
- 同时生成全部 Java 源码 JAR。

| 测试套件 | 项数 | 失败 | 错误 | 跳过 |
|---|---:|---:|---:|---:|
| `io.github.addxiaoyi.starchronicle.api.epic.EpicCampaignPlanCatalogTest` | 1 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.api.epic.EpicContractTest` | 5 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.config.ArchivedStoryDependenciesTest` | 14 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.config.ConditionLogicTest` | 13 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.config.ConfigLoaderTest` | 12 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.config.LastHeirStrategyContentTest` | 6 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.config.NationalChoiceDependenciesTest` | 19 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.config.NationalCouncilExampleTest` | 6 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.config.QuestGateConfigTest` | 9 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.config.SampleStoryContractTest` | 6 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.config.StoryDependenciesTest` | 50 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.core.ChoiceLedgerTest` | 13 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.core.CompletedInstanceSafetyTest` | 9 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.core.ContributionLedgerTest` | 3 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.core.CrossStoryEngineTest` | 8 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.core.DeferredTriggerTest` | 17 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.core.DefinitionCanonicalCodecTest` | 12 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.core.DefinitionCatalogTest` | 14 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.core.DerivedCausalityTest` | 4 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.core.EpicCampaignLedgerTest` | 8 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.core.EpicContributionProjectionTest` | 7 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.core.EpicStoryProjectionTest` | 2 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.core.FateStoriesTest` | 18 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.core.HeirLegacyPlaythroughTest` | 1 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.core.HistoricalCodexExampleTest` | 2 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.core.HistoricalEvaluationTest` | 18 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.core.InvocationGateTest` | 3 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.core.MemberReviewWorkflowTest` | 10 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.core.NationalAidServiceTest` | 5 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.core.NationalChoiceLedgerTest` | 12 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.core.NationalRuleEngineAuthorityBoundaryTest` | 9 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.core.NationalRuleEngineTest` | 14 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.core.NationalVoteLedgerTest` | 21 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.core.NationalVotePreparationTest` | 1 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.core.NativeDispatchTest` | 1 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.core.NativeSceneDeliveryTest` | 1 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.core.NativeSceneFlightsTest` | 10 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.core.OrchestrationRegressionTest` | 2 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.core.QuestGateDriverTest` | 21 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.core.QuestGateEngineTest` | 24 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.core.QuestGateLedgerTest` | 32 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.core.RecollectionsTest` | 11 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.core.RetiredStoryRuntimeTest` | 7 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.core.RuleEngineTest` | 23 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.core.RuntimePublicationTest` | 1 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.core.SampleStoryPlaythroughTest` | 22 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.core.TextServiceTest` | 8 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.history.CommittedHistoryJournalTest` | 4 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.history.EventCausalityTest` | 10 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.history.FirstVisitBufferTest` | 2 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.history.HistoricalFactsTest` | 9 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.history.ObservationJournalTest` | 14 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.integration.CoreContentInstallerTest` | 11 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.integration.NativeRecoverySafetyTest` | 12 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.integration.NativeSceneAdapterTest` | 9 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.integration.NativeSceneRecoveryAdapterTest` | 21 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.integration.SnapshotCollectorTest` | 3 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.integration.StarCoreAuthorityProviderTest` | 16 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.integration.StarCoreBridgeTest` | 21 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.integration.StarCoreCapitalHistorySourceTest` | 8 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.integration.StarCoreEpicOperationProviderTest` | 6 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.integration.StarCoreNationalVoteAuthorityTest` | 19 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.integration.StarCoreQuestEvidenceProviderTest` | 25 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.integration.StarCoreResourceHistorySourceTest` | 8 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.integration.StarCoreTechnologyHistorySourceTest` | 6 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.integration.StarCoreTradeHistorySourceTest` | 5 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.integration.StarCoreTreasuryEpicProviderTest` | 1 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.integration.TechnologyFactsTest` | 7 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.memory.MemoryLedgerTest` | 5 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.memory.MemoryRecipeCatalogTest` | 6 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.memory.MemoryRecipeEngineTest` | 16 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.memory.MemoryRecipeExampleTest` | 2 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.narrative.NativeSceneJournalTest` | 25 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.npc.NpcEngineTransactionTest` | 3 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.npc.NpcScopedHistoryTest` | 9 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.npc.NpcSystemTest` | 12 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.paper.ContributionCommandsTest` | 5 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.paper.EpicServicesTest` | 3 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.paper.ExplorationDiscoveriesTest` | 5 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.paper.ExplorationRegionTest` | 3 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.paper.FirstVisitJournalTest` | 4 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.paper.HistoricalCommandsTest` | 5 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.paper.MemberCommandsTest` | 4 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.paper.MemorySitesTest` | 16 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.paper.MenusTest` | 8 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.paper.NationalAuthorityServicesTest` | 4 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.paper.NationalCommandsTest` | 9 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.paper.NationalMenusTest` | 11 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.paper.NationalServicesTest` | 8 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.paper.PlayerOwnershipConsumersTest` | 3 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.paper.QuestGateMenusTest` | 14 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.paper.RegionTasksTest` | 6 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.paper.StateWorkerTest` | 3 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.persistence.CanonicalDefinitionSchemaUpgradeTest` | 11 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.persistence.ChoiceSchemaUpgradeTest` | 3 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.persistence.DefinitionSchemaUpgradeTest` | 4 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.persistence.MemoryRecipeSchemaUpgradeTest` | 2 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.persistence.SchemaMigrationTest` | 2 | 0 | 0 | 0 |
| `io.github.addxiaoyi.starchronicle.persistence.SqliteStoreTest` | 4 | 0 | 0 | 0 |

## 覆盖率

由 JaCoCo 对本次构建插桩所得；此数值仅表示已执行代码，不等同于 Paper/Folia 实服兼容性。

| 包 | 行覆盖 | 分支覆盖 |
|---|---:|---:|
| `io/github/addxiaoyi/starchronicle/api/aid` | 34/34 (100.0%) | 35/62 (56.5%) |
| `io/github/addxiaoyi/starchronicle/api/quest` | 37/37 (100.0%) | 13/18 (72.2%) |
| `io/github/addxiaoyi/starchronicle/paper` | 423/1323 (32.0%) | 477/1589 (30.0%) |
| `io/github/addxiaoyi/starchronicle/integration` | 884/1337 (66.1%) | 925/1640 (56.4%) |
| `io/github/addxiaoyi/starchronicle/persistence` | 59/59 (100.0%) | 15/18 (83.3%) |
| `io/github/addxiaoyi/starchronicle/narrative` | 88/88 (100.0%) | 113/150 (75.3%) |
| `io/github/addxiaoyi/starchronicle/api` | 27/67 (40.3%) | 25/42 (59.5%) |
| `io/github/addxiaoyi/starchronicle/memory` | 136/136 (100.0%) | 136/188 (72.3%) |
| `io/github/addxiaoyi/starchronicle/api/contribution` | 31/35 (88.6%) | 14/28 (50.0%) |
| `io/github/addxiaoyi/starchronicle/history` | 287/291 (98.6%) | 272/337 (80.7%) |
| `io/github/addxiaoyi/starchronicle/config` | 264/272 (97.1%) | 358/444 (80.6%) |
| `io/github/addxiaoyi/starchronicle/npc` | 228/231 (98.7%) | 213/288 (74.0%) |
| `io/github/addxiaoyi/starchronicle/core` | 2284/2431 (94.0%) | 2237/3080 (72.6%) |
| `io/github/addxiaoyi/starchronicle/api/epic` | 234/261 (89.7%) | 151/274 (55.1%) |

## 产物校验

| 文件 | 字节数 | SHA-256 |
|---|---:|---|
| `target/starchronicle-0.1.0-SNAPSHOT.jar` | 18081448 | `c5455ec5e790b6b4d9d48ab381938be4e91a80aa001a29e4bd90e6b817bda55e` |
| `target/starchronicle-0.1.0-SNAPSHOT-sources.jar` | 351077 | `8ebc061a0c36dc42a7621e2f062e3205d0741a024b24c600217d0b8879179ad5` |

## 真实服务器证据与构建对应

以下实机证据独立于JUnit统计。构建指纹不同的旧报告保留为回归背景，不能作为当前JAR实机验收。

| 报告 | 实测构建SHA-256 | 对应当前JAR | 记录结论 |
|---|---|---|---|
| [Paper + StarCore](verification/paper-smoke-report.md) | `5e227d20c46eeb91dd98d9d33e6f55cd66fbfc4fd7ecd7391f57eb9643f0d86e` | 否，不能覆盖当前构建 | PASS；14/14 |
| [Folia standalone](verification/folia-smoke-report.md) | `5e227d20c46eeb91dd98d9d33e6f55cd66fbfc4fd7ecd7391f57eb9643f0d86e` | 否，不能覆盖当前构建 | PASS；18/18 |
| [Expanded Paper + StarCore](verification/expanded-paper-smoke-report.md) | `abaadf4c4dd318d518687bafe2596f43e32c9f9a115b4074deeae9f37abc22df` | 否，不能覆盖当前构建 | PASS；27/27 |
| [Expanded Folia standalone](verification/expanded-folia-smoke-report.md) | `abaadf4c4dd318d518687bafe2596f43e32c9f9a115b4074deeae9f37abc22df` | 否，不能覆盖当前构建 | PASS；45/45 |
| [Continuous narrative Paper + StarCore](verification/phase-two-paper-report.md) | `d765e0d14b9a9122e0ba1304c9c1b0d3ef844d4458295b1f4847ecb213bd943b` | 否，不能覆盖当前构建 | PASS；31/31 |
| [Continuous narrative Folia standalone](verification/phase-two-folia-report.md) | `d765e0d14b9a9122e0ba1304c9c1b0d3ef844d4458295b1f4847ecb213bd943b` | 否，不能覆盖当前构建 | PASS；56/56 |
| [Actual V2 database upgrade](verification/phase-two-v2-upgrade-report.md) | `d765e0d14b9a9122e0ba1304c9c1b0d3ef844d4458295b1f4847ecb213bd943b` | 否，不能覆盖当前构建 | PASS；11/11 |
| [Version catalog Paper + StarCore](verification/phase-three-paper-report.md) | `bfa80b9ca62b1f28fdd37a2f1d7244b9b94cb8c34814273fda742ff8c6f28154` | 否，不能覆盖当前构建 | PASS；43/43 |
| [Version catalog Folia standalone](verification/phase-three-folia-report.md) | `bfa80b9ca62b1f28fdd37a2f1d7244b9b94cb8c34814273fda742ff8c6f28154` | 否，不能覆盖当前构建 | PASS；45/45 |
| [Actual definition upgrade: folia-v3-bfa80b9c](verification/definition-upgrade-folia-v3-bfa80b9c-report.md) | `bfa80b9ca62b1f28fdd37a2f1d7244b9b94cb8c34814273fda742ff8c6f28154` | 否，不能覆盖当前构建 | PASS；19/19 |
| [Actual definition upgrade: paper-v2-bfa80b9c](verification/definition-upgrade-paper-v2-bfa80b9c-report.md) | `bfa80b9ca62b1f28fdd37a2f1d7244b9b94cb8c34814273fda742ff8c6f28154` | 否，不能覆盖当前构建 | PASS；20/20 |
| [Actual definition upgrade: paper-v3-bfa80b9c](verification/definition-upgrade-paper-v3-bfa80b9c-report.md) | `bfa80b9ca62b1f28fdd37a2f1d7244b9b94cb8c34814273fda742ff8c6f28154` | 否，不能覆盖当前构建 | PASS；19/19 |
| [Configured first visits: folia-4ede86a8](verification/exploration-folia-4ede86a8-report.md) | `4ede86a8bb6315776184c612a488f0c9ebc0321ccef73ce84ddfb097c1f374ca` | 否，不能覆盖当前构建 | PASS；5/5；未执行／未支持：cancelled_teleport_no_discovery |
| [Configured first visits: folia-8fdcf8d3](verification/exploration-folia-8fdcf8d3-report.md) | `8fdcf8d375c22ca06c5bc15eb8e67b0ac58342b1e5a5636ea29307a88b423051` | 否，不能覆盖当前构建 | PASS；5/5；未执行／未支持：cancelled_teleport_no_discovery |
| [Configured first visits: paper-4ede86a8](verification/exploration-paper-4ede86a8-report.md) | `4ede86a8bb6315776184c612a488f0c9ebc0321ccef73ce84ddfb097c1f374ca` | 否，不能覆盖当前构建 | PASS；6/6 |
| [Configured first visits: paper-8fdcf8d3](verification/exploration-paper-8fdcf8d3-report.md) | `8fdcf8d375c22ca06c5bc15eb8e67b0ac58342b1e5a5636ea29307a88b423051` | 否，不能覆盖当前构建 | PASS；6/6 |
| [Native quest evidence: paper-4ede86a8-retry27](verification/quest-evidence-paper-4ede86a8-retry27-report.md) | `4ede86a8bb6315776184c612a488f0c9ebc0321ccef73ce84ddfb097c1f374ca` | 否，不能覆盖当前构建 | PASS；24/24；未执行／未支持：native_full_folia_multiplayer_acceptance, full_upstream_starcore_regression, production_deployment_or_external_rewards |

## 原生任务候选的独立边界

StarCore 候选 JAR SHA-256：`c6fb0e5e00d05b5545e515bd67596fed1af05ea644f20c996aeae98c809f197a`。
聚焦测试：44 项，失败 0，错误 0，跳过 0；该次证据为六份实际 Surefire XML，未保留统一原始控制台日志。
StarCore 完整回归：7968 项，失败 11，错误 123，跳过 21，`BUILD FAILURE`。不能用聚焦测试或 Paper 功能验收覆盖该失败。
候选补丁尚未上游合并或生产部署。各次失败、不完整 attempt、UNKNOWN 对账与 Folia 限制见 [任务证据审查](QUEST-GATE-REVIEW.md) 和 [原生静态交接](../patches/quest-evidence/static-handoff-report.json)。

## 验证边界

- 数据库与规则测试使用真实 SQLite JDBC，并包含多线程调用、重启重开、事件重放、稳定效果收据和错误回滚。
- Bukkit/Paper API 的编译或 Mockito 调度测试不能替代实际 Paper/Folia 服务器多人联机验收。
- 上游 StarCore 的 Folia 调度限制、未提供的提交后事件、国家权限和第三方 NPC 具体构建版本，以 `UPSTREAM-AUDIT.md`、`NPC-INTEGRATIONS.md` 与 `REQUIREMENTS.md` 的证据和边界为准。
- 本报告不声称已运行真实国家战争、NPC 客户端数据包或外部奖励到账的端到端验收。
