# NPC 桥接生命周期与玩家会话：源码验证 2026-11-26

旧 NPC 桥接缺少关闭状态守卫，按插件名移除监听会误伤重载后的新实例；只保存 UUID 的交互也可能读取重连后的玩家上下文。本次给 FancyNpcs／Citizens／Adyeshach 添加实例失效标记，保留原玩家、观察时间与本次直接采集的上下文，并把同样的检查贯穿纪念点老兵和异步菜单显示。

## 当前构件

| 构件 | SHA-256 |
| --- | --- |
| Chronicle 插件 | `1c7b8b010c9d330c15b0882753d54095fb906e42caedb926a81055d643cd78ce` |
| Chronicle 源码 JAR | `8a9634b60a7b256e228e5c96b62eb525824083d83cffb590828934d93dd2547c` |
| StarCore，源码及构件未变 | `928637205bffa395a52781eade913650e767c551a04b5f1a78a6a3e86183e87b` |
| 最终证据 ZIP | `4fdae91b6a9cffffb619350e022fb37fc76fd93b5621b227a6b859df0371898e` |

基线：Chronicle `1c4acc0`、StarCore `b048071d`；继续 `codex/plugin-all-stages-20261125` 与草稿 PR #2／#3。最终 [证据 ZIP](verification/npc-lifecycle-source-final-20261126-evidence.zip) **240 个文件**，其中嵌入第一次检查点的 152 文件原始证据 ZIP；[机器摘要](verification/npc-lifecycle-source-final-20261126-summary.json)。没有启动 Minecraft。

## 最终行为

- 启动幂等、关闭终止；按实际 Plugin 实例安装和失效。旧实例迟到 disable 不移除新实例；已经失效的旧回调／排队观察不会随重新启用恢复。注册部分失败时清理残留，其他提供者继续可用。
- 从提供者自己的 ClassLoader 加载 API。MONITOR、ignoreCancelled 和回调取消复核保持；稳定 ID 仅允许非空 String／UUID，最多 256 字符，不把 null 或任意对象转成历史绑定，不创建或修改第三方 NPC。
- 排队前保存原 Player 和观察时间。进入实体调度后检查确切对象；离线或重连拒绝该观察。必须有 `starchronicle.use`，只有 admin 权限也不能绕过。
- 原生桥接直接返回本次采集的不可变 PlayerContext，观察入口不再通过共享 UUID 缓存取回。受众刷新也不会重新获取原参与者。`npc.id` 为逻辑档案，`npc.binding` 为来源稳定身份，时间为原观察时间，新增 `time_basis=OBSERVED`。
- 工作线程在规则引擎入口前复核提供者和关服状态。已经进入处理的观察可完成，已提交历史不会因禁用回滚。此路径不是第三方持久 outbox，不能恢复入库前丢失或停用期间的点击。
- 纪念点老兵保留事件 Player；配置刷新／关闭使排队观察失效，再次启用不会恢复旧观察。NPC 档案、未绑定剧情菜单和查询失败提示将原玩家／提供者检查带到最终显示；等待查询后重连或禁用不会向新会话显示旧结果。
- 兼容 UUID 双参数回调构造器仍保留，外部使用者自行管理下游排队语义；现有命令菜单行为不变。

## 实际测试与失败保留

| 运行 | 测试 | failures | errors | skipped |
| --- | ---: | ---: | ---: | ---: |
| 旧实现初次夹具 | 33 | 10 | 13 | 0 |
| 修正测试环境，生产实现未修复 | 33 | 26 | 0 | 0 |
| 初步修复专项 | 102 | 0 | 0 | 0 |
| 初步完整构建 | 1,010 | 0 | 0 | 0 |
| 最终 clean verify | **1,021** | **0** | **0** | **0** |

新增 **64 项**：桥接 37、观察／显示入口 21、异步菜单结果 4、纪念点 1、直接上下文采集 1。三种事件是有明确标记的 API 形状测试替身；使用真实 Bukkit RegisteredListener／HandlerList 和真实 RegionTasks 配合排队回调。覆盖取消、实例替换、迟到通知、关服、无效身份、隔离类加载器、权限、满队列、原时间／上下文及显示前失效。

初次夹具的 13 errors 是 Bukkit.isPrimaryThread 环境未设置；补齐后得到 26 个真实断言失败。原始 XML／日志保留。首次 Python 启动器的 Windows 路径转义语法错误发生在 Java 前，不计入测试。后续一次 1,021 项中间构建测试成功，但编译开始后调整了最后的错误提示回调，因此没有作为最终交付；在源码稳定后重新 clean verify，以 `full-release` 的全部测试与 JAR 为准。

本机最终使用单个 256 MiB 堆 JVM：`mvnw.cmd -o -B -ntp -DforkCount=0 -Djacoco.skip=true clean verify`，带 Mockito agent。全部主源码／资源与产物逐项核对通过，Java 21 入口检查通过，JAR 不包含 Bukkit／StarCore／NPC 测试夹具类；没有本机 JaCoCo 结果。完整构建报告见 [TEST-REPORT](TEST-REPORT.md)。GitHub 当前提交的独立 Linux CI 在推送后检查。

StarCore 本轮未改动，此前 1,558 项相关回归及国库／贸易／工厂契约仍按原始 Chronicle 构件保留，不改称新 Chronicle 再次实测。

## 尚未完成

第 8 阶段仍需实际 NPC／保护版本组合的启停、事件顺序、跨区和客户端验收。MONITOR 只能证明当时所见取消状态，不能替代目标保护插件行为。全栈 Folia、当前其他实服流程、第三方持久来源、旧来源核对、长时并发和容量边界继续待办；十阶段没有全部完成。按用户选择继续源码与单元测试。
