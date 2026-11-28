# GitHub 科技历史合并与旧账本兼容：2026-11-26

合入上游 `503c66b`／`50257dd`，接通科技持久历史，同时保持本分支已安装的通用科技账本。旧通用游标继续使用原事件 ID、格式和激活语义；已有专用账本及新安装使用科技 journal。两套账本同时存在时保留记录并隔离科技同步，不删除或自动选边；其他四类来源保持原流程。

| 构件 | SHA-256 |
| --- | --- |
| Chronicle | `42c0e27b7726548faf8f2bf330af3de44e44225632645824ccf3ad102c22f071` |
| Sources | `f48e8b325b1c086880eccadff4eb8d1f7eb160fd7c4a972d614e7d305b9f8c87` |
| StarCore（独立 517 项检查点） | `dd069f79f141b644a224b16d155dfb7e2d990c428c4dd3f4482bd79c26ec36f4` |
| 本轮证据 ZIP | `4d56718b0266f20253fb43e4354343d45c04c1b9dc8ae83081a6f76b01edbe14` |

完整 `clean verify` **1,076 项通过，0 failures／errors／skipped**：上一轮 1,050 项、上游新增 18 项、合并兼容新增 8 项。最终专项 68 项通过；初轮 68 项中的 2 个测试错误因断言引用不存在的 `event_inbox` 表，已改为真实剧情及待触发数量断言，失败记录保留。单个 256 MiB JVM，forkCount=0、jacoco.skip=true，没有本机覆盖率结论。

专用 journal 初次追赶只归档，不补发 NPC、记忆和剧情奖励；追赶到尾部后恢复正常激活。每次读取包括最后回执锚点，核验完整事件内容，拒绝静默回退与同序号改写。128 条原始页包含锚点时按原始页是否满页判断尾部，避免提前结束归档。pending 按科技 namespace 消费，固定来源不可用时保留待办。原事件 JSON、历史证明与旧触发记录不改写。

新增回归覆盖旧格式跨重启、新装归档／后续激活、上游 pending 恢复一次、双账本与运行时竞争写入、来源不可用、257 条跨页归档，以及静默回退和原位改写。257 个源码／资源／测试文件与完整构建冻结副本一致；相关类与未 shade 构件字节一致，最终 shade 保留正常 Jackson／YAML 重定位。8 类旧公开声明及描述符保留，RuleEngine 新增归档方法。EpicCampaignPaperFixture 和 TechnologyHistoryPaperFixture 均针对当前两库构件编译通过，四个科技 Python 工具语法通过。

归档的 boot3 SQLite 主文件、WAL、SHM 一起复制，用 SQLite backup 生成一致副本。其 139 条科技事件（70 解锁、69 撤销）与 1 条建国事件共 140 条，游标为 139。当前构件在两个独立 JVM 中分别升级、重开，每次均新增 0 事件，没有建立第二套科技游标；40 张原表的全部原列值保持，schema 8 另增 9 表。两次探针错误分别是漏复制 WAL（看到 98 事件／游标 131）和把 139 科技事件误作全部事件数，均独立保留。这是数据库与归档回执副本验证，不是 Minecraft 升级或真实提供者验收。

[证据包](verification/github-technology-merge-20261126-evidence.zip) 含 1393 个文件，[机器摘要](verification/github-technology-merge-20261126-summary.json) 保存测试、API、数据库逐表摘要和边界。上游科技 ZIP 的 1,172 个清单文件逐个哈希通过（1,173 成员），SHA-256 `8f3c6fbdff79e75312734a70137a5b687eb7f27eef35e5884bed6f5a2ebd5b4c`；其 905 项、139 条原生科技提交的三次 Paper 启停及 10 项证据回归属于 Chronicle `e53a35d8…8c04f2`／StarCore `44f450ec…8518d`，本机未重跑，也不用于认证当前构件。

StarCore 已推送 `6d361fd` 的私聊／屏蔽会话修复，新增 33 项，独立 517 项相关回归、打包、36 组件 SBOM、API 与类字节核验通过。旧 WorldGuard／NPC 组合证据和在线热卸载失败边界继续保留。当前遵照用户要求只推进源码与单元测试，不启动 Minecraft；其余社交／GUI／昵称、生命周期、外部经济未知结果与容量仍待继续，十阶段未全部完成。
