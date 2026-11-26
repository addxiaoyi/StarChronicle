# 兼容调度取消与 Essentials 周期生命周期：源码验证 2026-11-26

FoliaCompatScheduler 原来的 cancelAll 在 Folia 下为空操作，在 Paper 下则取消整个插件的任务；Essentials 停用时没有取消保存和排行榜计时器，重启模块后旧回调还会读取新实例。本次按调度器实例管理取消，并为 Essentials 每次启用建立独立周期生命周期。

## 构件与证据

| 构件 | SHA-256 |
| --- | --- |
| StarCore | `3c8b69aea67cfa25847c9522afa20ae4972def607b1fabcbb7d0ae69621fae77` |
| Chronicle，主构件未变 | `1c7b8b010c9d330c15b0882753d54095fb906e42caedb926a81055d643cd78ce` |
| Chronicle 源码 JAR | `8a9634b60a7b256e228e5c96b62eb525824083d83cffb590828934d93dd2547c` |
| 证据 ZIP | `ce52ae2a5692899193dcd164b5c5b23b94effcfe01623197e47379222e735a23` |

基线：Chronicle `ef35b4c`、StarCore `181591e`。[证据 ZIP](verification/compat-lifecycle-source-20261126-evidence.zip) 共 **80 个文件**，包含基线失败、最终 XML、编译／打包／SBOM、源码与核验工具；[机器摘要](verification/compat-lifecycle-source-20261126-summary.json)。保留上一周期检查点 CI #169 成功日志和交付 Git 核对记录。没有启动 Minecraft。

## 最终行为

- Paper 和 Folia 都仅取消本调度器实例已登记的实体、区域、全局、异步及周期任务，不调用插件级 cancelTasks，也不影响另一个实例或直接注册的插件任务。
- 平台注册前登记；取消先于平台返回时，迟到句柄仍被取消。取消／退休／失败注册后的旧回调不执行；单次任务至多准入一次，完成后移除跟踪。实体退休只清理独立任务状态。
- 周期回调异常会停止后续执行，保留原异常，清理失败作为 suppressed。单个底层取消异常不会阻止其余任务取消。
- cancelAll 处理当前登记快照，随后新提交仍可运行；已准入的回调允许完成或提交后续工作。它不是关闭闸门、业务回滚或持久未应用回执。平台路由及原延迟换算保持，公开方法描述符保持。
- Essentials 每次启用有独立标识，定时保存与排行榜更新捕获原服务；停用先失效标识、取消任务和移除监听，再排空并关闭数据写入器。重复启停幂等，部分启用失败也清理；旧回调不能使用下一轮数据管理器。已准入回调仍可完成。

## 实际回归与构建

| 运行 | tests | failures | errors | skipped |
| --- | ---: | ---: | ---: | ---: |
| 旧实现基线 | 22 | 21 | 1 | 0 |
| 当前调度器与 Essentials 使用链 | **302** | **0** | **0** | **0** |

新增 **28 项**：兼容调度器 23、Essentials 生命周期 5；同时重跑上一检查点的共享周期 27 项及相关实体契约。直接使用链包含传送、经济命令、数据异步保存、GUI／服务。既有 POM 排除未变，302 项不是原生全库回归。

21 个基线断言失败保留。1 个 error 是旧 Paper 分支在另一线程访问 Bukkit，而 Mockito 静态替身仅限原线程；最终测试在线程内补齐替身，不把这个环境错误算产品缺陷。随后补充注册拒绝、空句柄、提前回调自取消等测试，最终 28 项全部通过。

平台调度器使用 Mockito；Essentials 生命周期用 mockConstruction 观察数据写入器的创建、关闭和保存顺序。同期执行既有异步保存文件测试，但这些并不等于真实服务器或完整热重载验收。

本机串行 256 MiB 堆、forkCount=0、jacoco.skip=true 和 Mockito agent。Spotless、2,266 主源／1,084 测试源编译成功，随后单独执行：

```text
mvnw.cmd -o -B -ntp -Djacoco.skip=true -DforkCount=0 -Dtest=dev.starcore.starcore.core.scheduler.**,dev.starcore.starcore.essentials.** surefire:test
mvnw.cmd -o -B -ntp -Djacoco.skip=true -Dmaven.main.skip=true -Dmaven.test.skip=true verify
mvnw.cmd -B -ntp -Djacoco.skip=true cyclonedx:makeAggregateBom@generate-sbom
```

离线打包后独立在线生成 36 组件 SBOM，元数据警告保留。最终 JAR 的 3 个改动类与已测试 target/classes 字节一致，主源码与编译时冻结副本相同，Java 21 字节码通过。旧／新公开方法声明与描述符一致（Essentials 新增 synchronized 修饰符在 API 比较时单独归一化）。无本机 JaCoCo 结果。

Chronicle 主源码／构件保持原 NPC 检查点及 1,021 项完整本地回归结果，本轮没有本地重跑。上一个 StarCore 构件 `2e472300…8a5ba1` 的 1,131 项／9 跳过，以及更早国库／贸易／工厂契约与容量检查，保持各自证据边界，不能相加成当前构件全量测试。新 Chronicle 文档提交推送后另行跟踪 Linux CI；StarCore 现有工作流不覆盖以非 main 分支为基线的草稿 PR。

## 后续范围

本轮没有完成全模块热重载、命令解绑、所有异步 continuation／传送业务恢复或所有权迁移。特别是旧 runSync／runNextTick、全局 TPA 过期通知访问玩家、延迟边界仍需逐个审计。第 7 阶段全栈 Folia、第 8 阶段实际 NPC／保护版本组合、当前构件实服及第 9 阶段长期负载继续待办。按用户要求只推进源码与单元测试，十阶段未全部完成。
