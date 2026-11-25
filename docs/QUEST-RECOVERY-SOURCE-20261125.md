# 任务恢复源码与测试检查点

后续国家即时加工来源与当前构件证据见 [2026-11-25 国家加工检查点](PRODUCTION-SOURCE-20261125.md)。本页保留任务恢复检查点自身的构件与测试范围。

日期：2026-11-25。两库分支均为 `codex/plugin-all-stages-20261125`；本轮从 Chronicle `099c29d`、StarCore `1fca7c9` 续接。用户选择先推进源码与单元测试，没有启动 Minecraft。

## 完成范围

- 原生失败／放弃／每日重置追加精确 RunTermination；任务状态保存确认与不可变终止日志完成后才公布可重试状态，原 run 永不复用。
- 独立恢复协议保留基础协议和存储域身份。UNKNOWN 只核对原始接受、原生状态与已有 journal，缺证明继续阻塞，绝不重新完成任务、执行开始命令或重放奖励。
- Chronicle 为每次任务维护独立 binding 和递增轮次；同事务归档旧 gate、终止证明、hash 和操作者，再开启新轮次。旧轮次迟到回包、裸 UNKNOWN、过期按钮、错误成员、换国、提供者注销和重复点击不能改写新轮次。
- 公开服务、`quest-reconcile`／`quest-retry` 命令、补全与本人任务 GUI；恢复前后重新采集身份和权限，国家其他成员只有查看权。
- 成员退出后通过退出日志核验旧任务身份，未完成门槛停止轮询，已完成的国家贡献保留；退出和不同国籍重新加入不再让整个门槛读取报存档损坏。
- 原生接取与恢复确认迟到时重新检查当前运行，防止覆盖期间发生的终止或执行迟到开始命令。持久化快照在原生任务所属线程采集。

协议与运维说明见 [任务门槛](QUEST-GATES.md)；原生实现说明位于配套仓库 `docs/QUEST-RECOVERY-2026-11-25.md`。

## 精确构件与验证

| 构件 | SHA-256 |
| --- | --- |
| StarChronicle | `b94cd898b779f96c9689581c619ac5c59747a62c40673cb38d1edc0ef8fa3362` |
| StarCore | `977ed805c5d8640abe9495372dcdd7059974ecae318453051cdd8182e46c2b98` |

Chronicle 完整 `verify`：914 项，零失败／错误／跳过。最后仅更新打包文档后执行 `package -DskipTests`，独立比对全部 1,757 个 class，与 914 项通过的 `b8f2000d…` 构件逐项相同；最终构件再次通过下述三 JVM 契约。

StarCore 专项 `verify`：76 项，零失败／错误／跳过；包括证据恢复、原生状态持久化、证据权威、队列、原生菜单、所有权守卫和公共 API 兼容性。格式、打包及 36 组件 SBOM 生成通过。本轮没有重跑 StarCore 完整套件，也不沿用旧全量成绩或覆盖率。两库使用 Java 21、256 MB Maven heap、串行 GC 与 `forkCount=0`；原生预加载 Mockito agent。

`tools/run-quest-recovery-contract.py` 用两份最终 JAR 编译真实反射适配器契约，在三个独立 JVM 中运行实际 `QuestService`、`QuestEvidenceService`、`PersistenceService`、Chronicle 引擎和文件 SQLite：

1. 首轮真实原生服务接取后放弃，终止日志确认，本地保存 ABANDONED。
2. 新 JVM 恢复相同存储域和终止证据，归档第一轮并开启第二轮；旧轮次回包忽略。新 run 接取后保存一项原生进度。
3. 第三个 JVM 恢复第二轮 binding、原始接受身份与原生进度，第一轮终止证据仍在；查询／对账重放不生成新轮次或完成历史。

Python 独立核对 SQLite archive／current gate 与原生终止日志的 run、binding、证明与终止 ID。最终目录 `run/quest-recovery-contract-20261125-2`，前一轮 `-1` 保留。Bukkit 玩家、调度器和插件容器为模拟对象，测试没有真实玩家操作、目标完成或奖励发放，也没有 Minecraft 服务器。冻结 JAR 仅保留在上述目录的 `artifacts/`，未提交二进制插件到 Git。

[证据包](verification/quest-recovery-source-20261125-evidence.zip)含 266 份日志、XML、两个契约目录数据和逐文件哈希。ZIP SHA-256：`c2631a408ea65d28f2cc4513b8168c751db129dd86050a201e00182537a0404f`。精确机器摘要见 [summary](verification/quest-recovery-source-20261125-summary.json)。此前贸易／来源三 JVM 证据仅覆盖其冻结构件，不自动套用本轮指纹。

## 保留失败与边界

原生首次 42 项有两项失败：旧测试断言立即发布终止状态，新实现要求等待持久确认；改为有界等待并增加暂停／失败测试后通过。Chronicle 首次完整 912 项有一项失败，发现退出成员被旧门槛校验视为损坏；增加退出日志身份核验、停止轮询与重启测试后 914 项通过。两次失败 XML 和日志均已保留。中途一次 PowerShell 启动报 `Releasing the double mapped memory failed`，未执行源码编辑；后续使用文件补丁继续，没有将其记为测试成功。

原生对账当前要求玩家在线；缺失精确接受、终止或完成证明的旧未知状态继续阻塞。未声明任意外部奖励端到端严格一次。Folia 原生适配器继续关闭，当前构件实服、完整原生回归、第三方 NPC／保护组合、长期压力与文明生产来源仍待继续。十阶段目标保持进行中。

## 下一步

继续文明生产的原生持久来源与贡献接线、长期容量与维护。内存条件允许后，使用当前配套构件在新的隔离测试目录做实际任务恢复、退出／重入、贸易／生产和第三方组合验收；不覆盖已有失败或旧成功记录。
