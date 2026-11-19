# 第一阶段需求与证据映射

这份清单区分已交付代码、上游真实接口和仍需后续阶段的内容。构建、测试执行数字与服务端运行范围以 `TEST-REPORT.md` 为准；下列测试类是可执行证据入口，不代替实际执行结果。

## 第一阶段验收范围

| 要求 | 源码／资源证据 | 验证入口与实际边界 |
|---|---|---|
| 独立 Maven 插件 | `pom.xml`、Maven Wrapper、`paper/StarChroniclePlugin.java`、`plugin.yml` | JAR 打包和入口检查；默认要求真实 StarCore，显式 standalone 仅用于独立规则／历史模式 |
| 先审计并优先复用 | `UPSTREAM-AUDIT.md`、`integration/StarCoreBridge.java` | 使用上游 NarrativeEngine、ScriptLoader、QuestService；不复制原执行器或Boss系统 |
| 配置驱动可运行规则 | `config/StoryDefinition.java`、`Condition.java`、`ConfigLoader.java` | `ConfigLoaderTest` 与 `SampleStoryContractTest`；严格未知字段、图结构、任务／脚本引用和效果白名单 |
| 事件条件触发 | `core/RuleEngine.accept`、`integration/StarCoreBridge` | `RuleEngineTest` 的来源条件／历史条件／缺失事实校验；真实可订阅范围见审计 |
| 多玩家／多国家实例 | `StoryInstance`、`RuleEngine.join/choose` | PLAYER/NATION/WORLD隔离、共享投票、改投；并发测试；WORLD按玩家计数而非一国一票 |
| 持久化与重启恢复 | `persistence/SqliteStore.java` | SQLite WAL + FULL事务；节点、成员、票、定义快照、来源与结果回执持久化 |
| 事件与奖励去重 | `RuleEngine.record/applyEffects`、outcomes表 | 相同来源重放无操作、同ID不同证据拒绝；本地奖励与迁移同事务；外部UNKNOWN隔离 |
| 跨剧情个人选择与共同决议 | `ChoiceLedger`、`StoryDependencies`、`recollections` API | 完成实例的实际个人投票与WORLD/NATION共同决议分离；未知旧投票时间不伪造；目录引用与必需循环校验 |
| 国家／个人历史条件 | `HistoricalFacts`、`NpcLedger` | `chronicle.history.*`直接查询正史索引；旧history.TYPE逐字兼容；已知0与未知主体不同 |
| 自产结果接续与因果保护 | `RuleEngine.recordAndQueue`、`EventCausality`、`heir-legacy.yml` | 同事务历史／NPC／入箱；离线恢复、跨旧pinned环与32层保护；原事件保留并提供blocked诊断 |
| 世界历史与证据 | `api/WorldEvent.java`、`RuleEngine.history` | 时间轴、国家、个人、交集过滤及分页；来源ID、时间、参与者、国家、source/evidence |
| 历史与剧情GUI | `paper/Menus.java` | 玩家实体线程展示、分页、资格过滤、节点令牌；真实客户端视觉验收范围看测试报告 |
| 三条完整剧情 | `stories/last-heir.yml`、`siege-embers.yml`、`silk-road-scholar.yml` | `SampleStoryPlaythroughTest` 遍历22条root→ending路线并在中途重启、重放 |
| 权威国家变更 | `api/AuthorityProvider.java`、`integration/StarCoreAuthorityProvider.java`、`StarCoreBridge.deliver` | 内置保守的真实君主外交邀请；通过StarCore国家权限和DiplomacyService提交，不能强制替对方结盟。其他操作仍需可信提供者 |
| 复用任务和脚本 | node `script/quest`、`narrative.start/quest.accept` | 默认样例无虚构ID；禁用JSON模板展示真实接法；投递不等于等待任务完成 |
| Folia调度 | `paper/RegionTasks.java` 与单工作线程 | 数据库不在实体线程阻塞，GUI回实体线程；未修改的审计StarCore缺Folia支持声明且模块有旧调度，不能称全栈兼容；独立模式可用但无真实国家服务，Folia禁用narrative.start |
| NPC关系与生命周期 | `npc/NpcCatalog.java`、`NpcLedger.java`、`paper/NpcHooks.java`、`MemorySites.java` | 背景、阵营、信任／敌对／声望、成长职位、历史反应与条件对话；同事件事务、稳定反应回执；只管理自身展示实体 |
| 八种玩家命运 | `stories/fate-*.yml`、`core/FateStoriesTest`、`/chronicle fate` | 八条独立身份线、16条完整结局；身份来自当前权威上下文，荣誉和称号持久化 |
| 世界记忆与真实图书馆 | `memory/MemoryLedger.java`、`HistoryBook.java`、`paper/MemorySites.java` | 真实事件绑定纪念点、老兵人物交互、实体区域恢复、历史GUI与原生书阅读；显式管理员放置，不自动改建领地 |
| 可选AI边界 | `api/TextProvider.java`、`core/TextService.java` | 有界文本提供者、超时／失败／无服务完整模板回退；不执行返回文本，无奖励或国家句柄 |
| API与可测试性 | `api/ChronicleService.java`、`ContextContributor.java`、`API.md` | Bukkit服务入口异步提交；核心可脱离Minecraft运行；上下文扩展不能覆盖权威国家字段 |

## 真实历史覆盖

| 事件种类 | 第一阶段接入方式 | 限制 |
|---|---|---|
| 国家建立 | 权威 Nation 实体的稳定ID与foundedAt扫描 | 可导入安装前仍存在国家的建国记录；国名不是建立时快照时应以证据解释 |
| 战争开始／结束 | 已提交War实体的稳定ID和时间，可重复扫描 | 原始记录无参战玩家明细时保留空participants，不冒充在线国民为参战者 |
| 重要战役／战争领土变更 | 战争结局里真实battle/capture明细 | 只记录上游实际提供的明细，不从聊天／击杀数推测战役 |
| 国家领土宣称 | `NationClaimedEvent`稳定claim ID | 上游无occurredAt时使用并标注首次观察时间 |
| 分裂／合并 | 已批准分裂请求ID、合并结果ID | 分裂缺原发生时间时明确标注观察时间；不能由前置请求直接入正史 |
| 科技突破 | 完成事件适配 + 事务观测日志对权威解锁集合差分 | 原生TechnologyModule完整快照检测新解锁；标明观察时间，不冒称研究完成时间。当前tech.stage取实际已解锁定义的最高时代，不按技术数猜测 |
| 国家消失 | 原生NationModule完整权威快照 + `ObservationJournal` | 首轮只建基线；状态变化与待入库事件同事务，重启后重投／确认；不推断灭亡原因、精确时间或未知参与者 |
| 首都迁移 | 可信 `SnapshotProvider` 的CAPITALS完整快照或已提交事件API | 审计CityService无持久化指定首都getter；不能把可重算缓存／最高等级城市当迁都。原生此项保持不可用，需服内权威提供者 |
| 重大贸易／探索／继承人出现／大型世界事件 | 可信扩展服务提交稳定来源ID与证据 | 默认没有伪造这些服务器事实的轮询器；管理员demo明确记为测试事件 |

历史的 `participants` 表示来源证据明确列出的参与人；剧情 `members` 是当前符合条件、加入共同叙事的玩家。相关国家在线国民可以成为剧情受众，但不会因此改写原战争或战役的参与者名单。剧情自产结果历史会列出作出该剧情抉择时的成员。

## 后续阶段与明确缺口

完整目标的逐项后续清单、Chronicle 可独立完成的工作与上游真实前置条件见 [REMAINING-WORK.md](REMAINING-WORK.md)。跨剧情选择、自产结果接续和范围历史条件现已有实际代码及持久化测试，具体语义见 [CONTINUOUS-NARRATIVE.md](CONTINUOUS-NARRATIVE.md)；当前构建与实服是否对应仍以 `TEST-REPORT.md` 的精确指纹为准。

- 默认国家史诗仍将复国、资源划拨和最终战役视为待权威服务批准的提案；内置外交适配器可安全发邀请，不能代替所有国家同意或创建虚构战役。服内生产内容应绑定真实任务、战争和授权目标。
- 首都变更缺少上游公开持久化权威读接口；已有可信完整快照SPI和可靠journal，不能在缺少提供者时声称原生自动记录迁都。
- 提供YAML/JSON内容编辑格式和验证器；用户要求的文件内容编辑已具备，未额外承诺拖拽式编辑器。
- 可选AI已有实际有界执行和回退，不捆绑付费模型、密钥管理或外部自动审核平台。
- 来源事件缺少稳定提交ID或发生时间时，不能宣称精确、无损、跨停服完整。新的`ObservationJournal`以同事务baseline+ingress解决观察到变化后的丢失窗口；不能找回两个扫描之间发生又撤回的未留档变化。旧`RuleEngine.observe`仍只是通用基线，不用于历史入库。
- 上游脚本／任务调用缺少可共同提交的幂等记录时，UNKNOWN需要人工对账；不对外宣称整个跨插件链路严格一次。
- 自动化单测、打包通过与纸面Folia线程设计不能替代真实服务器启动、真实双人GUI、跨区、NPC版本组合与长期负载验收。

实现范围、已执行测试与真实服务器构建指纹分别记录。当前新增的NPC、八身份和实体记忆是实际源码与测试，不再只是接口；未解决的上游Folia／任务恢复与国都权威缺口仍单独列出，不用独立模式成功替代全栈验收。
