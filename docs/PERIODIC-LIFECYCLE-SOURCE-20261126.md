# 共享周期任务生命周期：源码验证 2026-11-26

StarCore 的实体周期任务原先不受关闭跟踪，关服后必须等下一次 tick 才能清理打字机状态；注册／回调异常和已退休的旧回调也可能留下或再次使用该状态。本次统一跟踪实体与全局周期任务，补齐注册、取消、退休、关闭及异常清理。

## 当前构件与证据

| 构件 | SHA-256 |
| --- | --- |
| StarCore | `2e472300295588e34144475c0520fcf321a9d31a9ba3a0b73b6dd3f6828a5ba1` |
| Chronicle，主源码与构件未变 | `1c7b8b010c9d330c15b0882753d54095fb906e42caedb926a81055d643cd78ce` |
| Chronicle 源码 JAR | `8a9634b60a7b256e228e5c96b62eb525824083d83cffb590828934d93dd2547c` |
| 证据 ZIP | `0196996509c0036baa464a2924a1414ef72661351933b0ccaac39c18526d9eec` |

基线：Chronicle `d9e24fa`、StarCore `b048071d`。继续原配套分支与草稿 PR #2／#3。[证据 ZIP](verification/periodic-lifecycle-source-20261126-evidence.zip) 共 **248 个文件**，含原始失败、三组最终 XML、编译／打包／SBOM 日志、冻结源码与检查工具；[机器摘要](verification/periodic-lifecycle-source-20261126-summary.json)。未启动 Minecraft。

## 行为与调用边界

- 实体周期 API 仍使用确切实体的调度器，每次回调先核验所有权。返回并传入回调的是同一个跟踪 `ScheduledTask`，调用者不能依赖它与平台原句柄的对象身份相同；公开方法签名保持。
- 显式取消立即移除跟踪、抑制迟到回调，不调用 retired。关闭、实体退休、空／失败注册或回调异常至多退休一次，不再等待下一次 tick 才清理打字机独立状态。
- 平台注册前先发布跟踪项；关服或退休先于平台返回时，迟到的句柄仍取消。注册期间提前进入的回调与自取消也受相同规则约束。
- retired 可在关闭或注册线程执行，只能清理独立状态，不可访问实体／世界。当前唯一实体周期消费者 TypewriterController 只删除 concurrent map 中捕获的状态并更新 volatile 标记。
- 注册／回调异常释放跟踪；清理异常作为 suppressed 保留原异常。一个任务取消或退休失败会记录日志，不中断其他周期任务和线程池关闭。
- 零初始延迟规范为下一 tick，负延迟或非正周期在平台注册前拒绝。已通过准入检查的回调可完成；取消不回滚业务，也不声明业务从未发生。单次 `callForEntity` 的未应用结果契约保持独立。

已沿当前使用链核对剧情、任务、国库、赔款、战争服务／态势、委托与和约的全局周期入口。没有将旧同步调用批量改为全局调度。

## 实际验证

| 运行 | tests | failures | errors | skipped |
| --- | ---: | ---: | ---: | ---: |
| 旧实现红例 | 15 | 13 | 2 | 0 |
| 调度／剧情／任务 | 391 | 0 | 0 | 7 |
| 战争／赔款 | 475 | 0 | 0 | 2 |
| 国库／GUI／科研支付 | 265 | 0 | 0 | 0 |
| 当前相关回归合计 | **1,131** | **0** | **0** | **9** |

新增 **27 项**周期任务专项全部通过，包含真实另一线程关服与注册未返回的交错、旧回调、提前退休、自取消、无效 owner、失败注册／回调／清理、零延迟以及真实 TypewriterController 状态清理。平台调度器为 Mockito 替身，不是原生 Folia 调度执行。

旧实现的 2 errors 来自失败的业务回调再次执行，均保留原始 XML 和源码；其余 13 项为断言失败。最终专项在红例后扩展为 27 项。没有增加测试排除。7 项既有剧情跳过需要真实 Bukkit Player；2 项 WarModuleAuthorityTest 跳过因引用已移除接口，不能把测试注释中声称的集成覆盖视为本轮实服证据。`ConscriptionTest` 的既有排除及 POM 排除均保持，1,131 项不等于全库回归。

本机每次仅使用一个 256 MiB 堆 JVM，`forkCount=0`、`jacoco.skip=true` 和 Mockito agent；没有本机覆盖率结果。先完成 Spotless 和 2,266 个主源／1,082 个测试源的编译，再用三个独立 JVM 执行以上选择器，最后复用稳定的已测试类打包：

```text
mvnw.cmd -o -B -ntp -Djacoco.skip=true spotless:apply -DskipTests test-compile
mvnw.cmd -o -B -ntp -Djacoco.skip=true -DforkCount=0 -Dtest=dev.starcore.starcore.core.scheduler.**,dev.starcore.starcore.module.narrative.**,dev.starcore.starcore.quest.** surefire:test
mvnw.cmd -o -B -ntp -Djacoco.skip=true -DforkCount=0 -Dtest=dev.starcore.starcore.war.**,dev.starcore.starcore.module.war.**,!ConscriptionTest surefire:test
mvnw.cmd -o -B -ntp -Djacoco.skip=true -DforkCount=0 -Dtest=Treasury*Test,SqlTreasuryStateStorageTest,NationTransferEconomyStateTest,ResearchPaymentProcessorTest surefire:test
mvnw.cmd -o -B -ntp -Djacoco.skip=true -Dmaven.main.skip=true -Dmaven.test.skip=true verify
mvnw.cmd -B -ntp -Djacoco.skip=true cyclonedx:makeAggregateBom@generate-sbom
```

离线打包跳过 SBOM，随后在线命令成功生成并校验 36 组件清单，依赖元数据警告原样保留。最终 JAR 内 5 个调度器类与已测试 target/classes 字节相同，Java 21 字节码检查通过，旧／新 JAR 的公开调度器与 GlobalRepeatingTask 签名一致。源码亦与编译时冻结副本一致。这些检查不替代运行兼容验收。

Chronicle 主源码、JAR 和原 1,021 项完整测试结果沿用 NPC 检查点，本轮未本地重跑。此前国库／贸易／工厂契约和容量报告保留原构件边界，没有重新标记为此 StarCore 构件的实测。Chronicle 推送后另行跟踪 Linux CI；StarCore 草稿 PR 的基线不是 main，现有工作流触发条件不覆盖该 PR。

## 未完成范围

第 7 阶段仍需逐个审计 `runSync*`、`runNextTick`、FoliaCompatScheduler 及 Essentials 模块计时器，迁移其实体／区域／全局所有权并验证真实全栈。第 8 阶段实际 NPC／保护插件版本组合、第 9 阶段长时并发、首次迁移峰值、旧来源核对和其余容量问题也继续待办。当前只推进源码与单元测试，十阶段没有全部完成。
