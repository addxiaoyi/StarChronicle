# 自动世界记忆配方

此增量在原历史事务中把有明确来源的事件登记为长期地方记忆，并为后续剧情生成 `WORLD_MEMORY_RECORDED`。它不要求有人在线，不生成方块、物品或第三方 NPC，不修改国家权威状态。原有管理员纪念点、见证者实体和图书馆继续使用其各自的授权与生命周期。

## 配置与真实来源

默认 `memory-recipes.yml` 为 `recipes: []`。`config.yml` 的 `memory-recipes-file` 可选择本插件数据目录内的 YAML/JSON 文件名；更改文件名需要正常重启。文件本身由 `/chronicle validate` 和 `/chronicle reload` 与剧情一起读取。示例位于 [可选配方](../examples/memory-recipes/memory-recipes.yml) 和 [完整图书馆支线](../examples/memory-recipes/caravan-library.yml)，两条配方默认 `enabled: false`。

```yaml
recipes:
  - id: caravan-archive
    version: "1"
    enabled: true
    sources: [StarChronicle.ConfiguredRegionFirstVisit]
    eventTypes: [EXPLORATION_DISCOVERY]
    requiredFacts:
      region.id: old-caravan-road
      discovery.kind: FIRST_VISIT
      time_basis: OBSERVED
    regionId: old-caravan-road
    title: 旧商路档案
    text: 一次真实首访已登记。原始观察时间与参与者保存在来源历史中。
```

来源和类型逐字匹配，不支持通配符。`requiredFacts` 是来源事件自身 facts 的精确匹配，不读取玩家声称的地区、权限、AI 文本或现时国籍。它必须包含 `region.id` 或 `city.id`。若含 `region.id`，其值必须与固定 `regionId` 相等；仅含城市时，配置明确把该稳定城市 ID 映射到这个逻辑地区，来源若另外携带矛盾地区也会拒绝匹配。原生 StarCore 战役来源只有战争／结局等字段，缺少城市、地区或围城提交证据时不会命中城市配方。示例 `ServerCityWarJournal` 是需要服内真实实现的提交后发布者名称，不是已安装能力。

目录最多 64 条配方，文件最多 256 KiB。每条最多 16 个精确来源、16 个类型和 32 个必需事实。地区与配方 ID 使用 1–40 位小写字母、数字、点、下划线或连字符，首位为字母／数字，并保留 `memory` 点分段，防止本地事实路径歧义。版本最多 40 位；标题 128 字符，正文 2048 字符。解析拒绝未知字段、重复键、隐式标量转换、尾随文档和超限配置。没有命令、奖励、效果脚本或任意表达式入口。

## 事务、去重与时间

首次接受真实来源时，同一 SQLite 事务完成来源历史、NPC 反馈、记忆条目、本地事实、信号历史、真实因果路径和待触发剧情。条目以来源事件 ID＋配方 ID＋精确版本唯一；定义 JSON 与哈希持久化，同 ID／版本改内容拒绝加载。`enabled` 是独立运行开关，可在不改内容版本的情况下停用／启用。规则与配方在同一次有效配置发布中切换；验证不归档定义，任何解析、验证或归档失败保留旧运行配置。

记忆 `recordedAt` 和信号 `occurredAt` 是本次实际登记时间；来源原时间独立保存在 `sourceOccurredAt`／`source.occurred_at`。信号使用 `time_basis=RECORDED`，不会把过去战争或观察事件说成现在重新发生。来源 `OBSERVED` 的含义保持不变。

原事件重放、首访 ingress 未 ACK 后重投、重启和停用后重新启用都不会重新登记。新增配方或提升版本只作用于今后首次接受的来源；不会扫描旧历史自动补算，`evaluateHistorical` 也只评估故事而不重放配方。失败事务整体回滚，下一次重新提交真实来源才可正常接受。

每条条目写入 `region.<regionId>.memory.<recipeId>.entry/version/source/source_occurred_at/recorded_at/title/text`。这是该地区／配方最后一次成功登记的投影，完整历次登记保存在 `memory_entries`，不会靠覆盖投影删除历史。信号沿用来源真实参与者、国家和明确的 GLOBAL 受众标记，不把在线国民变成来源参战者或探索者。

## 因果与容量保护

schema 7 在旧 `event_causes` 追加默认 `[]` 的 `recipe_path`，原 `story_path` 与旧历史 JSON 不改写。原四参 `Trace` 构造仍表示故事路径；新五参构造同时携带配方路径，总深度为二者长度之和。故事和配方各自检查重复 ID（跨版本仍阻断），总深度上限为 32。

已有配方因果路径的故事结果，仅从自身 canonical 来源复制已有 `region.id/city.id` 并记录 `region.source_event`，让地方后续保留证据。没有配方路径的旧故事结果保持原有效载荷。外部 facts 不能伪造父事件、根事件或配方路径。

同一顶层规则 SQL 操作最多生成 256 条配方信号，所有递归分支和同事务多个故事效果共享预算，避免多条互相回馈配方产生组合爆炸。超限、重复或深度阻断保留真实来源并登记 `memory_recipe_blocks`；`/chronicle blocked` 同时显示故事与配方原因。被截断的部分不会在重放、重启或轮询时自动补发。配置作者仍应避免无意义的反馈配方。

## 查询、后续与展示

`ChronicleService.memoryEntries(regionId, page, size)` 是异步只读查询；`regionId=null` 查询全部，页从 0 起，size 为 1–100。条目包含来源／信号 ID、定义哈希、地区、纯文本、原时间与登记时间。常规历史 GUI、个人传记、国家历史和真实历史书可阅读 `WORLD_MEMORY_RECORDED` 及其完整证据；没有额外奖励领取入口。

支线用 `event.memory.recipe`、`event.region.id` 与 `region.*.memory.*` 条件连接真实信号。示例的两条支线结局完整；玩家选择留下证言才使用已有故事机制授予本地荣誉，不是配方自行发奖。`NpcCatalog` 可配置对该信号的关系或成长反应，仍受原事务与稳定 NPC 反应回执保护。

若要在当地展示纪念点，管理员仍需先取得位置许可，再把真实记忆信号或原来源事件交给 `/chronicle memory create`。配方不擅自建设、不生成未加载区块，也不接管 Citizens／FancyNpcs／Adyeshach 的实体。

## 验证状态

本增量提供严格目录、真实 SQLite 回滚／重放／恢复、无在线受众、现有 NPC 原子反应、同版本冲突、交替因果循环、全局扇出预算与 32 层链测试，以及冻结 `f2a4344` schema 6 DDL 的迁移／迁移失败回滚测试。测试源码存在不代表已通过构建或实服；具体结论以匹配新 JAR 指纹的报告为准，旧 `4ede86a8` 探索验收不覆盖 schema 7 增量。
