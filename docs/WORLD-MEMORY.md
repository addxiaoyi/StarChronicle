# 世界记忆、纪念点与真实历史书

`memory` 剧情效果保存长期 `region.*` 叙事事实，供后续故事与 NPC 条件对话读取。它不会隐式施工。实体纪念点是管理员显式设置的可选展示，由 `memory/MemoryLedger` 和 `paper/MemorySites` 管理。

## 创建与阅读

先通过 `/chronicle history` 查到已经提交的真实事件 ID。站在获准展示的位置执行：

```text
/chronicle memory create city-library <真实事件ID> siege-veteran
/chronicle memory list
/chronicle memory remove city-library
```

这些命令要求 `starchronicle.admin`。创建位置取当前玩家的世界与整数坐标，不接受任意远程世界坐标。来源事件必须在本服 `events` 表中存在；SQLite 外键拒绝虚构来源。点 ID 不能覆盖或移动已有点，已停用 ID 留作永久清理墓碑，换位置使用新 ID。

显示由带插件 PDC 标记的 `TextDisplay` 和可右键的 `Interaction` 组成；指定逻辑 NPC 时额外生成关闭 AI、无碰撞、无交易操作的村民见证者。点击纪念点打开历史 GUI；点击见证者显示该逻辑 NPC 的当前关系、职位、成长、背景与条件对话。内置 NPC 的具体生平是虚构叙事；界面中的历史事件仍来自本服真实档案。

插件不放置／替换方块，不加载或生成未加载区块，不宣称接管第三方领地保护权限。管理员应先取得当地展示许可。它不会扫描删除其他插件的 NPC 或普通村民；只对同时匹配自身 PDC 点 ID 与展示类型的实体进行去重和清理。点位注册表和来源 ID 持久化；区块加载时在所属区域调度器上恢复。停用点位的墓碑保证当时未加载区块中的旧实体以后也会清理。正常停服不清除展示，下一次启动按所有权标签校验。

## 图书馆

历史 GUI 的证据页面提供“阅读真实历史书”。`/chronicle book [卷数]` 读取倒序历史的一条事件，每卷保留完整事件 ID、UTC 时间、时间基准、来源和完整证据。它调用 `Player.openBook` 展示临时原生书籍，不往背包发奖励，不产生可重放的物品投递。完整国家／参与者清单和 facts 继续在 GUI 证据页中查询。

书籍使用普通 `Component.text`，不解析 MiniMessage、点击命令、JSON 富文本或 AI 输出。无历史时仍显示说明页。历史档案不是由 AI 改写的文本，观察时间明确标为 `OBSERVED`。

## 生命周期与验证边界

数据库工作在单状态线程；实体创建、所属实体清理、区块查询均在对应区域线程；书和 GUI 在玩家实体线程展示。生成前后会检查最新注册表，延迟任务不能复活已经停用的纪念点。

`MemoryLedgerTest` 覆盖来源约束、重复点防覆盖、重启恢复、删除墓碑、坐标和书籍证据；`MemorySitesTest` 验证区域调度与所有权。真实服务器检查的具体构建 hash 和范围以 `TEST-REPORT.md` 及 `verification/` 的原始证据为准；单测不等于全部第三方保护插件和 NPC 插件组合已验收。
