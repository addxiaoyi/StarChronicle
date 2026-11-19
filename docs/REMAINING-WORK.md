# 第一阶段交付后的剩余工作

静态核对日期：2026-11-20。本文件是后续工作清单，不是已实现功能清单，也不是一次新的运行验收。

第一阶段已有实际可交付实现：独立 Maven 插件、配置规则引擎、事件触发与离线触发恢复、PLAYER/NATION/WORLD 实例、SQLite 持久化、稳定结果回执、历史与剧情 GUI、三条主线、八身份个人线、NPC 关系、实体纪念点和真实历史书。已有实现及其执行证据见 [REQUIREMENTS.md](REQUIREMENTS.md) 和 [TEST-REPORT.md](TEST-REPORT.md)。最终 JAR 指纹、统一构建与 Paper/Folia 的实际验收范围仍以对应构建报告为准；本文件不把旧构建的测试结果套用到后续变更。

以下 P1/P2 是**下一阶段的优先级**。接口、示例、候选补丁和计划均不算这些缺口已经完成。源码锚点以本次静态检查时的版本为准。本文件不在 `pom.xml` 的文档嵌入白名单中；未改写已冻结的嵌入 API／剧情格式文档。

## 一、StarChronicle 可以独立补齐的工作

| 优先级 | 完整目标中的缺口 | 当前实际边界 | 最小后续实现 |
| --- | --- | --- | --- |
| P1 | 跨剧情读取玩家过去的选择 | `choice.NODE` 和 `story.choice.NODE` 只从当前实例读取；已完成实例虽然保留在数据库中，但没有跨实例条件投影。见 [RuleEngine.java:182](../src/main/java/io/github/addxiaoyi/starchronicle/core/RuleEngine.java#L182)、[RuleEngine.java:257](../src/main/java/io/github/addxiaoyi/starchronicle/core/RuleEngine.java#L257)。 | 新增事务内选择日志与只读投影，明确个人投票、共同决议、版本和多次游玩语义；不要把共同结果复制成每个成员的个人选择。详见下文方案 A。 |
| P1 | 剧情自产结果自动接续另一剧情 | `accept` 才调用 `queueTrigger`；`history` 效果直接调用 `record`，后者只存历史与 NPC 反应。结果虽然可被后来到来的条件查询看到，但 `triggers: [某自产历史类型]` 不会因此自动排入触发队列。见 [RuleEngine.java:58](../src/main/java/io/github/addxiaoyi/starchronicle/core/RuleEngine.java#L58)、[RuleEngine.java:224](../src/main/java/io/github/addxiaoyi/starchronicle/core/RuleEngine.java#L224)、[RuleEngine.java:355](../src/main/java/io/github/addxiaoyi/starchronicle/core/RuleEngine.java#L355)。 | 提取同一连接上的事件入库＋触发入箱路径；自产历史与外来事件共用，保留去重、离线受众与范围回执。禁止递归调用公开 `accept`。详见方案 B。 |
| P1 | 条件中的国家历史／个人历史统计 | `history.TYPE` 当前明确是全世界总数；历史 GUI 已能按国家／个人查询，不能据此宣称条件已有同范围统计。NPC 对话与反应也单独使用全局计数。见 [RuleEngine.java:264](../src/main/java/io/github/addxiaoyi/starchronicle/core/RuleEngine.java#L264)、[RuleEngine.java:317](../src/main/java/io/github/addxiaoyi/starchronicle/core/RuleEngine.java#L317)、[NpcLedger.java:140](../src/main/java/io/github/addxiaoyi/starchronicle/npc/NpcLedger.java#L140)。 | 复用 `events`、`event_nations`、`event_players` 及现有索引；引入公共历史事实解析器，供规则和 NPC 使用，不另维护容易漂移的计数副本。详见方案 C。 |
| P2 | 真正按国家分别表态的史诗编排 | 《帝国最后的继承人》是 WORLD 共享故事，人数法定数为两名玩家；率先凑齐的主张决定共同路线。它不是一国一票，也没有同时保存每个国家的正式立场。见 [last-heir.yml:4](../src/main/resources/stories/last-heir.yml#L4)、[last-heir.yml:19](../src/main/resources/stories/last-heir.yml#L19)。 | 在现有实例之上增加显式的国家叙事立场／代表席位及投票单位配置；保留投票时国家快照、代表权限与结果 receipt。此层可独立完成；把立场变成真实条约、战争或国库划拨仍属于第二组。 |
| P2 | 以真实任务／战役完成为阶段闸门 | 现有 node `quest`／`script` 是启动投递；投递成功不等于任务完成，也不自动阻止玩家继续在 Chronicle GUI 选择。见 [RuleEngine.java:203](../src/main/java/io/github/addxiaoyi/starchronicle/core/RuleEngine.java#L203)、[StarCoreBridge.java:151](../src/main/java/io/github/addxiaoyi/starchronicle/integration/StarCoreBridge.java#L151)。 | 本插件可增加有界 `await`／阶段闸门定义、完成关联表与配置验证；只消费绑定实例、玩家／国家和稳定任务运行 ID 的真实提交结果。真实完成证据及上游恢复要求见第二组。不得另写一套任务目标处理器。 |
| P2 | 长期贡献而不只是荣誉／称号 | 八条身份线与本地荣誉、称号、NPC 关系已经实现；目前没有通用的国家援助贡献账本、贡献排行或按可信贡献生成专属后续线。 | 增加由已提交来源驱动的贡献账本及聚合投影，唯一键至少包含来源操作 ID、贡献种类与参与主体。金额、资源交付和战役贡献必须来自真实权威回执，不能把点击“援助建议”当成已捐赠。 |
| P2 | 世界记忆的自动内容配方 | `memory` 效果可持久化 `region.*`，管理员可创建有真实来源的纪念点、见证者和历史书；当前没有“某地区围城后自动挑选哪些支线与展示”的通用配方。见 [RuleEngine.java:227](../src/main/java/io/github/addxiaoyi/starchronicle/core/RuleEngine.java#L227)、[MemoryLedger.java:17](../src/main/java/io/github/addxiaoyi/starchronicle/memory/MemoryLedger.java#L17)。 | 增加来源事件→记忆配方→剧情信号／逻辑展示登记的事务投影；以事件 ID＋配方 ID 去重。无需修改领地的文本、图书和支线可独立完成；实际施工和保护权限需第二组的服内授权。 |

上述工作不要求重做已经具备的 YAML/JSON 文件内容格式、严格验证器、八种身份剧情、NPC 关系模型或无 AI 回退。原需求没有强制要求拖拽式可视化编辑器；它可作为额外产品功能，不能倒推为当前文件格式没有交付。

## 二、必须有真实上游权威或部署支持的工作

这里的“依赖”不表示每项都必须修改 StarCore 源码。有些适配器可以写在本仓库，但必须接入真实服务、真实资料和实际部署；一个 SPI 声明不能替代它们。

| 目标 | 仍缺的真实条件 | 本插件应保持的边界与证据入口 |
| --- | --- | --- |
| 原生自动记录指定首都迁移 | 审计版本的 `CityService` 没有持久化“指定首都”的公开 getter／提交事件；最高等级城市和位置缓存都不是指定首都证据。 | [SnapshotCollector.java:67](../src/main/java/io/github/addxiaoyi/starchronicle/integration/SnapshotCollector.java#L67) 明确返回该 scope 不可用。需要真实的持久化权威读接口或已部署且经过核查的 `SnapshotProvider`。`CAPITAL-AUTHORITY-CONTRACT.md` 是提案，不是已部署能力。 |
| 精确、无损的全类世界历史 | 当前国家消失／科技解锁快照只能证明观察到的变化；两次扫描间发生又撤回的变化、停服期间被清理的事实、缺失的原始演员与时间无法从快照恢复。 | 需要源系统的持久化提交日志／outbox、稳定操作 ID、发生时间和主体明细；继续保留 `OBSERVED`、完整 scope 与首轮基线语义。不能从在线国民推造战争参与者。见 [HISTORY-OBSERVATION.md](HISTORY-OBSERVATION.md)。 |
| 重大贸易、文明生产、真实探索与大型世界事件自动入史 | 当前公开提交 API 可消费这些事件，但没有内置真实交易所／探索系统／生产账本的持久化生产者。SenLuo 审计对象是烹饪与内容插件，其可取消回调没有稳定提交回执。 | 必须按实际安装插件接入 post-commit 操作来源；普通物品提取／收割回调或管理员 demo 不等于真实大宗贸易与文明突破。见 [UPSTREAM-AUDIT.md](UPSTREAM-AUDIT.md)。 |
| 复国、财政援助、资源交付、正式外交、战争和地区实际变化 | 内置权威适配器目前只支持真实创始人授权的 `diplomacy.invite`，不是强制结盟，更不是通用国家状态执行器。 | [StarCoreAuthorityProvider.java:23](../src/main/java/io/github/addxiaoyi/starchronicle/integration/StarCoreAuthorityProvider.java#L23)。其他操作须逐项核对实际 StarCore 服务、权限、执行线程与原子性；禁止控制台命令替代、模型 setter 或“提案等于生效”。资源／财政接口存在不代表已接通本剧情。 |
| 跨插件国家变更／外部奖励严格一次 | 上游国家／任务 API 不接受可共同提交的 Chronicle outcome ID；本地事务无法覆盖另一个插件的真实修改。 | 本地回执和 outbox 可阻止盲目重放；`RUNNING/UNKNOWN` 必须对账。若要承诺端到端严格一次，须上游提供持久化幂等键与可查询提交结果，或参与同一权威事务。现有接口与内存去重不算这个合同已经完成。见 [StarCoreAuthorityProvider.java:17](../src/main/java/io/github/addxiaoyi/starchronicle/integration/StarCoreAuthorityProvider.java#L17)。 |
| 自动恢复 Chronicle 后注册的原生任务模板与进度 | 原审计 Quest 模块在依赖插件注册模板前恢复，另有进度二次克隆及存储隔离问题。 | 现有独立候选补丁与针对性测试不是服上补丁已生效；[CoreContentInstaller.java:68](../src/main/java/io/github/addxiaoyi/starchronicle/integration/CoreContentInstaller.java#L68) 默认只安装场景，不启用三项自定义原生任务。须先合入／部署核验后的上游修复并验证两个重启、进度与奖励边界。 |
| 整套 StarCore＋StarChronicle 的 Folia 原生剧情 | 审计 StarCore 没有完整 Folia 支持；旧调度、GUI、计时器、状态保存和 Boss 回调仍需要全链路移植。 | [StarCoreBridge.java:45](../src/main/java/io/github/addxiaoyi/starchronicle/integration/StarCoreBridge.java#L45) 仍拒绝 Folia 原生 narrative.start。独立模式成功不等于真实国家全栈成功；已有局部所有权合同补丁不等于整个上游已经移植。见 [FOLIA-COMPATIBILITY.md](FOLIA-COMPATIBILITY.md)。 |
| 八身份对应真实国家职务、升迁与贡献来源 | 当前可识别真实创始人、标准 rank 名、显式管理员身份权限和可信扩展；陌生上游职务体系没有通用映射。 | [StarCoreBridge.java:94](../src/main/java/io/github/addxiaoyi/starchronicle/integration/StarCoreBridge.java#L94)。需服内权限／职务系统的真实适配与明确映射；本地“叙事称号”和 NPC 职位不授予真实国家权限。 |
| 第三方 NPC 与地区保护的生产组合 | 已有 FancyNpcs／Citizens／Adyeshach 交互钩子与逻辑人物绑定，但没有由空绑定自动创建实际第三方 NPC；各安装版本、Folia 能力和领地保护仍需真实组合验收。 | [NpcHooks.java:26](../src/main/java/io/github/addxiaoyi/starchronicle/paper/NpcHooks.java#L26) 负责钩子生命周期。本插件只清理自身标记实体；自动纪念点施工、第三方 NPC 创建／权限和地区改建必须得到对应服务与管理授权。 |
| 真实 AI 文本服务 | 可选 `TextProvider`、有界执行、超时和模板回退已实现；未捆绑具体付费模型、密钥或服务。 | [TextService.java:22](../src/main/java/io/github/addxiaoyi/starchronicle/core/TextService.java#L22)。如服务器选择启用，需实际部署提供者及其网络超时／隐私策略。此项是可选部署，不是规则系统运行前置条件；AI 不能取得执行器或把返回文本变成效果。 |

## 方案 A：跨剧情选择的最小可靠实现

### 1. 语义先于存储

保留现有两种本实例事实：`choice.NODE` 为该玩家实际投票优先，`story.choice.NODE` 为达到法定人数后的共同决议。新事实另设命名空间，避免悄悄改变旧剧情含义。

建议第一版只暴露**已完成实例**中的“该玩家最后记录的投票”及“匹配范围的共同决议”。投过败方票、后来加入但没有投票、当前仍在进行的故事必须能够区分。没有投票不能继承其他人的投票；共同结果不证明所有成员个人同意。

例如可新增下列明确引用形式，以下均为拟议格式而非当前可用配置：

```yaml
condition:
  fact: past.player.choice.silk-road-scholar/1/research
  op: eq
  value: share
```

`story/version/node` 使用 `/` 分隔，因为现有稳定 ID 不允许 `/`，从而避免用点号拼接产生歧义。版本明确固定；跨版本兼容必须由作者显式声明。共同决议另用 `past.resolution.*`，NATION 引用绑定当前可信国家的准确 scope，WORLD 引用明确为世界共同决议。

### 2. 类与表

- 新增 `core/ChoiceLedger`：只接受调用方当前 `Connection`，拒绝 auto-commit，负责追加投票／决议和查询历史投影；不持有 Bukkit、国家修改或奖励执行权限。
- 新增或提取 `core/FactRequirements`：从规则和 NPC 条件收集所需引用，批量读取，避免为每个条件扫描所有实例 JSON。
- `RuleEngine.choose` 是唯一内置写入位置；`RuleEngine.facts` 和 NPC 对话通过同一个投影器读取。现有 `StoryInstance.choices` 继续服务本实例显示和兼容。
- 在下一版 schema 中新增 `story_choice_log`。最小列为：`seq INTEGER PRIMARY KEY AUTOINCREMENT`、`id UNIQUE`、`instance_id REFERENCES instances(id)`、`source_event_id REFERENCES events(id)`、`story_id`、`story_version`、`node_id`、`kind(BALLOT/RESOLUTION)`、`actor_key`、`player_id`、`nation_id`、`scope_key`、`revision`、`choice_id`、`chosen_at`、`provenance`。唯一约束为 `(instance_id,node_id,kind,actor_key,revision)`；个人 actor 是玩家 UUID，共同决议 actor 是实例 scope。
- 建立 `(player_id,story_id,story_version,node_id,seq DESC)` 和 `(scope_key,story_id,story_version,node_id,kind,seq DESC)` 查询索引。`seq` 表示本数据库已提交记录的确定顺序，不宣称是真实世界发生时间或跨服务全局顺序。

### 3. 事务路径与迁移

`choose` 在成员、当前国家、expected-node、分支条件及权限全部验证后，才写入日志。相同投票的重复请求在现有 duplicate 分支返回，不增加 revision。真正改投在同一事务中分配下一 revision；达到 quorum 时额外追加唯一共同决议。日志、实例投票／迁移、本地效果、历史与 outbox 必须共同提交或回滚，复用 [SqliteStore.transaction:48](../src/main/java/io/github/addxiaoyi/starchronicle/persistence/SqliteStore.java#L48)。

已有实例的 `choices` 只能恢复各节点最后保留的投票和共同结果，不能重建全部改投过程或精确投票时间。迁移可导入这些快照，标为 `LEGACY_INSTANCE`，以稳定导入 ID 去重，并明确其顺序依据只是旧实例 `updatedAt/id`。不要把这个时间冒称原投票时间。schema、回填和版本推进应在启动期间同一迁移事务中完成；重开库不得重复导入。

条件投影必须清除调用方注入的 `past.*` 再从库构造。国家选择使用实例成员在投票时已验证的国家快照，不能因后来转国而改写旧立场。GUI 和条件的读取都应走状态工作线程；不能在实体线程等待 SQLite。

配置验证器需在整个目录加载完成后校验所引用的故事、版本、节点和选项，并为第一版的跨剧情前置条件检测循环依赖。旧实例和离线触发的定义快照仍需保留其可读性；不能因当前文件删除就丢失历史选择。

最低测试：A 结局影响 B、重启后相同结果、败方个人投票与共同决议不同、未投票成员没有个人事实、改投只有最后有效个人投票被查询、版本隔离、转国不改史、重复不增行、写入失败整体回滚，以及真实 V2 库迁移两次结果一致。

## 方案 B：自产历史结果进入后续触发链

这是 Chronicle 内的 P1，当前尚未实现。只给 `Condition` 增加更多事实，不能替代这条事件接线。

最小路径是提取私有或包内 `EventIngress.recordAndQueue(Connection, WorldEvent, definitions, audience)`，让公开 `accept` 和 `applyEffects` 的 `history` 分支共用：

1. 按现有稳定 ID／canonical fingerprint 插入事件及 `event_nations/event_players`；同 ID 不同证据仍拒绝。
2. 仅对 fresh 事件执行 NPC 反应，并为匹配定义持久化 `story_triggers`。自产结果可先只使用其证据中的 participants/nations 建立离线种子，不需要在数据库事务中读取在线玩家。
3. 保留现有 `story_trigger_receipts(trigger_key,scope)`，所有来源共用同一套 PLAYER/NATION/WORLD、GLOBAL 和国家受众隔离规则。
4. 效果 receipt、事件、NPC 关系、pending trigger 与发起实例迁移必须同事务提交。不要在 `applyEffects` 中调用会重新开启事务的公开 `accept`，不要在提交前向其他线程发布“已经完成”的结果。
5. 触发只入箱，不在 `record` 中递归启动故事。由现有有界 drain／轮询恢复；即使进程在效果提交后立即退出，下一次启动仍能读到待触发项。

需要同时控制**跨故事**循环。单个故事节点 DAG 和每批 100 条限额不能阻止 A 结局产生新事件→B→A 持续创建新实例。最小版本应校验当前定义的“history 效果类型→消费该类型的故事”依赖，并为内部派生事件记录父事件／根事件与有界深度；达到上限必须保留可诊断结果并停止派生，不能悄悄丢史。外来提交不能伪装内部因果元数据。

如果需要通用 `STORY_COMPLETED`，其 ID 应由实例和已提交终点稳定生成，并明确只是叙事完成，不是国家复国／战争胜利；不要用每次轮询的随机 UUID。选择日志的 BALLOT 不应默认全变成世界正史，避免一次改投触发整条世界剧情。

最低测试：A 的自产 history 无需新外部事件就使 B 入箱；全员离线＋重启后恢复；重复 A 结果不重建 B／奖励；NPC 反应和新 trigger 一起回滚；跨国／GLOBAL 范围不扩大；被禁用或改版的待处理定义保留原快照；直接与间接循环均被阻止。

## 方案 C：国家／个人范围的历史事实

### 1. 复用既有正史，先不加计数表

现有 [SqliteStore.java:24](../src/main/java/io/github/addxiaoyi/starchronicle/persistence/SqliteStore.java#L24) 已有 `events`、`event_nations`、`event_players` 及后两者的主体查询索引。最小实现新增 `history/HistoricalFacts`，接受同一个事务连接、可信玩家 UUID 与可选当前国家 ID：

```sql
-- 当前真实国家的历史；不是触发事件列出的任意一个国家
SELECT e.type, COUNT(*)
FROM events e JOIN event_nations n ON n.event_id=e.id
WHERE n.nation=? GROUP BY e.type;

-- 仅来源证据明确列出的个人参与历史
SELECT e.type, COUNT(*)
FROM events e JOIN event_players p ON p.event_id=e.id
WHERE p.player=? GROUP BY e.type;
```

建议新增 `history.world.TYPE`、`history.nation.TYPE`、`history.player.TYPE`，保留旧 `history.TYPE` 的全世界含义。国家／个人语义按已有索引定义：作为当前国民看到事件、加入叙事或观看广播，都不会补写为原历史参与者。

对配置确实引用、且主体已知的计数类型，可返回字符串 `0`；“计数事实可查询”与“计数大于零”应区分，判断发生过请用 `op: gt` 和 `value: "0"`。主体缺失时省略事实并继续使用现有三值逻辑，不能把未知国家当空国家零历史。旧 `history.TYPE` 的 missing／exists 行为不能无版本说明地改变。

多国事件对某一国只计一次。若以后提供 source-nations 聚合，必须显式定义是并集，并用 `COUNT(DISTINCT e.id)`；不能把参与两个国家的同一场战争算两次。地区历史需要稳定地区证据和可选 `event_regions` 投影，不能从纪念点摆放位置反推事件发生地；这不属于首个最小补丁。

### 2. 一致性与调用位置

- `RuleEngine.facts` 与 `NpcLedger.history` 应复用同一解析器，避免 GUI、剧情和 NPC 对同名事实给出不同范围。
- 删除调用方提供的全部保留历史命名空间后再从 canonical 数据库构造；事件 facts 仍保留 `event.*` 前缀，不能覆盖可信历史计数。
- 查询与相应规则迁移／NPC 反应在同一 `Connection`、同一 SQLite 事务读取。暂不做跨事务内存缓存或另外递增计数，避免重放、回滚和重启后漂移。
- 必须明确当前事件是否已计入。与现有入库顺序一致的最小方案是“当前已提交／本事务已插入的来源也计入”；延后恢复的触发在恢复当次读取当前已记录历史。若需要“事件发生当时”或“严格早于当前事件”的条件，应另增明确语义，不能借 observed time 猜因果顺序。
- NPC 的事件反应没有即时职业／当前国籍上下文。不要把 `event.nations` 的第一个值当某个玩家当前国家；无明确主体时不构造国家／个人事实。新增 `past.player.*`、`history.player.*` 后还需更新 [NpcLedger.needsPlayer:147](../src/main/java/io/github/addxiaoyi/starchronicle/npc/NpcLedger.java#L147) 等主体依赖判断，避免匿名反应错误满足个人条件。

最低测试：国家 A/B 隔离、同事件参与双国不双计、个人是证据参与者而非在线受众、GLOBAL 不伪造个人史、上下文注入被覆盖、未知国家保持 UNKNOWN、已知主体缺失类型为零、自产历史在同事务可见、回滚后不增、重启一致及旧 `history.TYPE` 兼容。

## 建议推进顺序与交付门槛

先完成当前冻结构建的真实 Paper 验收和源代码／构建证据检查点，再以独立变更推进上述工作，避免把新功能插入正在验收的产物。

下一阶段建议先做方案 C（无需新历史存储），随后做方案 A 的迁移与跨故事前置条件，再做方案 B 的派生触发及因果防环，最后扩展国家席位、任务闸门和记忆配方。每个步骤都须提交实际源代码、配置示例、持久化／迁移测试与对应构建证据，不能只更新 API 文档或新增空 SPI。

任何真实国家、任务、NPC 组合和 Folia 承诺，都应单独记录上游版本、安装配置、构建指纹、真实服务器操作与重启结果。独立规则测试通过、候选补丁通过或 standalone 启动成功，都不能代替这些外部条件。
