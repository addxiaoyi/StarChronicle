# StarChronicle

**当前增量：玩家钱包直售持久恢复已实现。** Chronicle1,017项全通过；原生7,604项零失败／错误、76跳过，真实MySQL105例＋SQLite41例聚焦通过。直接交易三次正常Paper启动、30条非OP命令、3笔成交／12条贡献及24项独立证据检查通过；六组同构件回归也通过，共20次正常启动／40次正常进程退出，238项证据测试230通过／8场景跳过；脱敏归档、精确构件打包与新目录解压复验全部通过。买方付税、冻结条款、原卖方权限、重复接受与双历史来源均核对；Vault仅提供调用确认，永久UNKNOWN不盲重扣。见[合同](docs/PLAYER-TRADE-RECOVERY.md)、[验收报告](docs/PLAYER-TRADE-TEST-REPORT.md)与[最新交接](docs/HANDOFF-2026-11-26.md)。未合并或生产部署，十阶段继续推进。

**上一增量：旧工厂原始审计与显式新付款登记已实现。** Chronicle991项完整构建通过；原生7,526项零失败／错误、51跳过，真实MySQL64例通过。迁移专项四次正常Paper启动、40条非OP命令与36项证据检查通过：1条合法空闲记录付费登记、6条隔离，旧字段保留，新生产才生成贡献。六组同构件回归也通过；总计21次正常启动、242项证据通过／8场景跳过，真实两据点100∶0；归档解压复验通过。见[合同](docs/LEGACY-FACTORY-MIGRATION.md)、[验收报告](docs/LEGACY-FACTORY-TEST-REPORT.md)与[十阶段交接](docs/HANDOFF-2026-11-25.md)。未合并或生产部署，目标保持active。

上一国库检查点：**当前增量：普通国库SQLite写锁竞争已修复并完成同构件实服复验。** 原生7,472项零失败／错误、47项跳过；真实MySQL国库2项通过；Chronicle沿用988项构建。新原生JAR配对原Chronicle完成国库、国家直售、DUNGEON、WAR各三次正常启动，共12次；125项证据检查121通过／4场景跳过，解压复验通过。相同夹具源码在旧JAR重现SQLITE_BUSY拒绝并回滚，失败现场保留。见[国库验收报告](docs/TREASURY-WRITER-TEST-REPORT.md)。十阶段仍active，未合并或生产部署。

2026-11-25 国家工厂增量：付费建造、1–10级升级／启停、工厂容量与绑定生产已完成所选Paper验收。Chronicle988项、原生7,465项零失败／错误（47跳过）、独立MySQL56例通过；冻结双JAR17次正常启动，206项证据检查通过／8场景跳过。见[工厂合同](docs/NATION-FACTORIES.md)、[验收报告](docs/NATION-FACTORIES-TEST-REPORT.md)与[十阶段交接](docs/HANDOFF-2026-11-25.md)。旧工厂迁移、全栈Folia和生产部署尚未完成。

2026-11-25 最新提交窗口增量：国家生产／贸易12个真实MySQL进程中断场景通过，12次数据库SIGKILL／重启、8次业务JVM SIGKILL、40次正常JVM退出；修正后31项SQL、32项证据、9项目标保护回归全通过。资产、原操作者与唯一回执恢复／重放不变；本轮为原生服务测试，未新增Paper联机或物理断电结论。见[报告](docs/MYSQL-INFLIGHT-TEST-REPORT.md)及[十阶段交接](docs/HANDOFF-2026-11-25.md)。冻结双JAR不变，目标继续推进。

2026-11-25 最新恢复增量：冻结双JAR完成五现场联合备份、独立MySQL恢复与已完成重放后的进程中断恢复，共15次验收启动、5次JVM／MySQL强制终止及恢复、69条真实命令／查询。完整数据与结构、原操作者、资产、唯一结算和贡献保持一致；物理断电及进行中事务窗口仍待验证。见[恢复报告](docs/MYSQL-RECOVERY-TEST-REPORT.md)、[草稿PR #18](https://github.com/addxiaoyi/StarChronicle/pull/18)与[交接](docs/HANDOFF-2026-11-25.md)。十阶段继续推进，未合并或生产部署。

2026-11-25 最新实服增量：冻结双JAR在所选Paper＋真实MySQL8.4.13组合完成生产、空服补录、国家直售、副本与据点国战，共14次游戏服启动、9次数据库重启；329次证据检查通过、8次场景跳过。原生MySQL原始数据与显式审计投影逐列比对，Chronicle仍使用SQLite。见[实服报告](docs/MYSQL-PAPER-TEST-REPORT.md)、[草稿PR #17](https://github.com/addxiaoyi/StarChronicle/pull/17)与[十阶段交接](docs/HANDOFF-2026-11-25.md)。产品源码未变，未合并或生产部署。

2026-11-25 最新数据库增量：真实MySQL8.4.13／InnoDB上的国家生产与贸易31项专项测试全部通过；产品源码和冻结双JAR未改。覆盖共同事务回滚、确认丢失、并发去重、旧快照和完整删尾拒绝。见[MySQL验收](docs/MYSQL-SETTLEMENT-TEST-REPORT.md)与[最新交接](docs/HANDOFF-2026-11-25.md)。该专项之后的Paper联机和正常联合重启已由上述实服增量完成。

2026-11-25 跨设备续接入口：[十阶段进度与交接](docs/HANDOFF-2026-11-25.md)。最新国家生产增量完成开工扣料／批次、到期交付／回执、取消退款／终态共同SQL提交及原操作者文明贡献；Chronicle979项通过，原生7,354项零失败／错误、21项既有跳过。同一双构件完成生产三轮、空服两轮、贸易／DUNGEON／WAR各三轮，共14次正常启动，174项证据检查通过、8项场景检查跳过。见[生产验收](docs/NATION-PRODUCTION-TEST-REPORT.md)。旧加工资产接口已停用，调用方须迁移；十阶段继续，未合并或部署生产服。

上一国家资源转移增量的六组双插件实服、118 项证据回归及对应构件独立保留在[资源报告](docs/RESOURCE-HISTORY.md)。资源回执没有个人操作者，不生成个人贡献。

国家史诗新增 `/chronicle epic` 报名、冻结、准备、执行、对账与路线入口；可选《帝国最后的继承人》战略包默认关闭。部署和真实回执边界见 [国家史诗契约](docs/NATIONAL-EPIC-CONTRACT.md)与[操作示例](examples/national-epic/last-heir-strategy/README.md)。

当前国家议事开发见[一国一席协议与完整可选剧情](docs/NATIONAL-COUNCIL.md)及[对应构件测试报告](docs/NATIONAL-COUNCIL-TEST-REPORT.md)。它增加当前国家持有人授权、修订号改票／撤回和冻结决议；既有默认剧情继续保留原投票单位。完整目标仍按[剩余工作](docs/REMAINING-WORK.md)推进。

Minecraft 国战服务器的长期剧情编排与世界历史插件。玩家通过共同抉择、国家事件和个人身份参与服务器历史。它复用 StarCore 的 NarrativeEngine、任务接受机制和国家权威服务，在其外部补充配置规则、多人实例、持久化投票、世界记忆和历史查询。

本仓库在第一阶段工程上增加了真实的跨剧情选择、范围历史条件与结果接续，目标为 **Java 21、Paper/Folia 1.21.11**。所有实际国家状态变更均须由 StarCore 的受信任服务完成。默认剧情只修改 StarChronicle 本地荣誉、称号、NPC 声望和逻辑记忆，不会直接扣国库、改外交、发物品或运行管理命令。

当前分支另加入可选的 [自动世界记忆配方](docs/MEMORY-RECIPES.md)：schema 7、同事务记忆登记、稳定后续信号、地方支线与有界因果反馈；并新增可选的 [国家援助与国库回执](docs/NATIONAL-AID.md)，严格复用 StarCore 原生国库转账和回执。此前援助 831 项、国家史诗 866 项、贡献 878 项、成员 896 项属于各自独立检查点；贡献旧库升级与成员流程分别见[贡献报告](docs/CONTRIBUTIONS.md)和[成员报告](docs/MEMBER-REVIEW.md)。

## 已实现内容

- YAML/JSON 条件规则：身份、国家、战争、资源、科技、历史、地区与之前选择可通过可信 facts 组合。
- PLAYER/NATION/WORLD 三种作用域；共享阶段按不同玩家投票达到 quorum 推进。
- SQLite 事务、来源事件与待触发定义原子提交、作用域消费收据、稳定结果 ID、定义快照与重启恢复；无人在线不永久消费剧情。
- schema 4 的真实定义版本目录、退休版本精确引用和显式历史评估继续保留；任务闸门最初使用 schema 6；当前schema 8新增本人核对、持久终止后的独立新绑定与旧轮次审计，并兼容旧数据。schema 7 世界记忆与国家援助源码已通过 831 项 `clean verify`；国库功能还依赖尚未合并的 StarCore authority-domain 候选补丁，具体范围见精确指纹报告。
- 世界时间轴、国家历史和个人传记查询；剧情与历史 GUI。
- 可选配置地区首访：管理员显式绑定稳定地区 ID、世界 UUID 与坐标边界；真实玩家首次进入后按玩家与地区唯一入库并触发 `EXPLORATION_DISCOVERY`。默认关闭，不扫描或声称发现未配置区域。
- 外部脚本／任务 outbox；不确定调用隔离为 UNKNOWN，防止盲目重放奖励。
- 可选 `quest.await` 节点门槛：持久绑定到原生任务 run/completion 证据，完成历史与本地收据同事务写入；需要安装提供 `QuestEvidenceService` 协议的 StarCore 候选补丁。原版 StarCore 不提供唯一 run 的持久完成回执，不能仅凭任务接受、进度达标或 `completed-ever` 解锁。
- 任务详情“核对原生任务／重新尝试任务”：UNKNOWN不新开，仅已确认持久FAILED／ABANDONED允许本人显式新轮次；原生旧run保留，不补发奖励，详见[任务门槛合同](docs/QUEST-GATES.md)。
- 三条完整分支剧情：《帝国最后的继承人》《围城余烬》《丝路学者》。
- 个人投票与共同决议的跨剧情日志、当前国家／真实个人参与历史计数；结果历史同事务排入后续剧情，带因果循环与深度保护。
- 完整后续《旧都档案：每个人的那一票》，自动承接主线结局，区分实际投票、个人异议与未投票见证者。
- 八种身份的独立命运线、持久荣誉与称号；NPC 背景、阵营、信任、敌对、成长、职位和条件对话。
- 完整权威快照的事务观测日志：基线与待入库事件一起提交，崩溃后重投，不把观察时间写成精确发生时间。
- 原生贸易历史持久域／提交序列、不可变修订与清理快照、Chronicle 游标及收件箱同事务、消费后确认；依赖配套 StarCore 新协议，历史记录不自动构成资产成交或贡献凭证。
- 原生科技解锁／撤销持久回执接线，来源域与连续序列核验，各来源独立排空；快速连续变更和重启均保留，不把直接解锁推断为研究完成或个人贡献。
- 原生国家库存转移历史：独立持久末尾与连续序列、库存／回执共同提交、旧库存完整迁移、SQL权威固定；Chronicle独立来源收件箱与ACK，不把国家库存记录当作个人贡献。
- 真实事件绑定纪念点、自有见证者实体与原生历史书；区域线程恢复、去重与停用清理。
- 三条通过真实 StarCore ScriptLoader 注册的自有场景，具备指纹和自有集合式效果约束下的有限 checkpoint 恢复；内置保守的君主外交邀请适配器；可选有界纯文本提供者与模板回退。

首访探索的配置、持久化边界和实际示例见 [配置地区首访](docs/EXPLORATION.md)。上一实服检查点的重连所有权修复构建 `4ede86a8` 已完成 Paper 六项、Folia 五项可执行检查，两平台各两次正常启动／退出，并通过每平台九项证据回归。旧 `8fdcf8d3` 构建的报告独立保留。Folia 取消传送用例明确未支持。该开关不需要 AI 服务。

## 先审计再集成

审计范围与真实接口见 [上游审计](docs/UPSTREAM-AUDIT.md)。StarCore 已有的脚本执行和任务系统继续作为执行层。StarChronicle 不另造一套通用脚本解释器。

提供的 SenLuoPlugin 仓库当前 main 实际是 **KaleidoscopeCookeryPlugin 与 CraftEngine 系列内容扩展**，并没有国家／战争／NPC 服务。它只能作为可选生产内容联动来源；其可取消烹饪事件不是国家状态提交证据。不能将不存在的接口写成硬依赖。

## 构建

```text
java -version
.\mvnw.cmd clean verify
```

Linux/macOS 使用 `./mvnw clean verify`；已安装 Maven 时可直接使用 `mvn clean verify`。Maven Wrapper 会下载固定 Maven 发行版，仍需要本机 Java 21。

Maven 会编译 Java 21 源码、运行 JUnit 测试并生成带运行依赖的插件：

```text
target/starchronicle-0.1.0-SNAPSHOT.jar
target/surefire-reports/
```

Paper API 为 provided，不打包入插件。Jackson 和 YAML 依赖重定位到私有包；SQLite 驱动随插件提供。实际构建结果、测试数量和未覆盖范围以 [测试报告](docs/TEST-REPORT.md) 为准，源码中存在测试不等于已经通过线上验收。实服报告按 JAR SHA-256 对应；旧构建／候选构建的证据保留作回归背景，不能替代当前交付 JAR 的验收。

## 安装和配置

1. 使用 Java 21 启动隔离的 Paper/Folia 1.21.11 测试服，先安装兼容的 StarCore。
2. 停服后将构建 JAR 放入 `plugins/`，正常启动；首次生成配置、三条主剧情、八条身份剧情、NPC目录和三个原生场景文件。
3. 阅读配置与日志，先检查规则验证结果，再使用剧情／历史界面参与样例。正式发布前将示例地区、角色和触发条件替换为服务器实际内容。
4. 真实国家事件由 StarCore 桥接，缺少的提交后事件由可信跨插件 API 提供。不要用玩家可自由输入的事件命令代替权威来源。
5. 故事规则只认已提供事实；学者示例要求可信 `player.role` 和 `tech.stage`，缺失时不会凭空授予资格。
6. 修改已接受的剧情内容必须提升版本，再执行 `/chronicle validate` 和 `/chronicle reload`；纯validate不写版本目录，失败保留上一套运行定义。已活动或排队的旧图继续保留；同一story/scope的另一版本活动时，新版等待且不提前消费其receipt。自动原生绑定变化也属于有效内容差异，不能用同版本静默覆盖。
7. schema 6 在保留 V1–V5 的选择、定义目录、历史与回执迁移基础上，新增任务闸门和完成消费表；已放弃/失败闸门继续等待，不重放或伪造任务完成。当前源码的 clean verify 已通过；安装精确完成证据需要配套 StarCore 候选协议，原版 StarCore 不提供它，候选补丁也尚未上游合并或生产部署。升级前正常停服、备份。它不补造缺失定义、当年资格或原生 checkpoint；管理员可用 evaluate 为单条已保存来源登记一条新规则，这属于现在的评估，不是恢复当年未保存的执行。
8. 如需永久删除／重命名示范故事，先设置 `install-default-stories: false`，并保留至少一条有效自定义剧情。默认仍安装缺失示范文件，不会覆盖已有文件。

## 命令与权限

| 命令 | 用途 |
|---|---|
| `/chronicle stories` | 打开本人已参与和符合加入资格的剧情 GUI |
| `/chronicle scene` | 继续自有StarCore原生补充场景，条件和效果仍由原执行器处理 |
| `/chronicle join <实例ID>` | 加入符合条件的共同剧情 |
| `/chronicle choose <实例ID> <当前节点ID> <选项ID>` | 带阶段校验选择或改投 |
| `/chronicle history [页]` | 世界历史 GUI，页码从 1 起 |
| `/chronicle history nation <国家ID> [页]` | 国家历史 |
| `/chronicle history player <UUID或me> [页]` | 个人传记 |
| `/chronicle member list` / `status <实例ID>` | 本人的复核记录／当前成员状态 |
| `/chronicle member revalidate\|exit <实例ID> <当前节点ID>` | 原生身份复核或退出；原国家重新加入沿用 `join`，见[成员流程](docs/MEMBER-REVIEW.md) |
| `/chronicle contributions [页]` | 本人已核验贡献，每页 10 条 |
| `/chronicle contributions nation\|player <UUID或me> [页]` | 指定国家／玩家的贡献；[API 与回执边界](docs/CONTRIBUTIONS.md) |
| `/chronicle book [卷数]` | 用原生书阅读一条真实历史及完整证据 |
| `/chronicle npc <逻辑NPC ID>` | 当前关系、背景、阵营、成长职位与条件对话 |
| `/chronicle fate` | 当前身份、持久荣誉、历史称号及个人剧情入口 |
| `/chronicle past <剧情ID/版本/节点ID>` | 当前玩家的已完成历史投票及可用共同决议 |
| `/chronicle memory create <点ID> <真实事件ID> [逻辑NPC ID]` | 管理员在当前位置设置自有历史纪念点 |
| `/chronicle memory remove <点ID>` / `memory list` | 停用并清理所属展示；查看点位注册表 |
| `/chronicle status` | 管理员查看服务、规则数和队列状态 |
| `/chronicle validate` | 管理员验证磁盘剧情文件 |
| `/chronicle reload` | 验证通过后重载新规则，保留进行中实例与旧规则权限；失败不替换 |
| `/chronicle evaluate <已保存事件ID> <剧情ID> <精确版本>` | 管理员显式登记单条旧正史与当前启用版本；不改来源或立即发奖，随后用新鲜身份恢复 |
| `/chronicle uncertain` | 管理员查看需要独立对账的外部效果与原生场景 UNKNOWN |
| `/chronicle blocked` | 管理员查看派生循环／深度拦截；原历史保留，后续未执行 |
| `/chronicle ack <结果ID> CONFIRM_APPLIED` | 独立确认上游已实际执行后，登记 UNKNOWN 已完成；不重发效果 |
| `/chronicle native-ack <结果ID> CONFIRM_SCENE_COMPLETED` | 独立确认原生场景已结束／放弃后关闭隔离；不启动、推进或重放场景 |
| `/chronicle demo <剧情ID>` | 仅显式开启 `admin-demo-enabled` 的测试服使用，记录来源为 ADMIN_TEST |

玩家操作要求 `starchronicle.use`（默认允许），管理操作另需 `starchronicle.admin`（默认 OP）。职业事实优先来自 StarCore；管理员可明确赋予 `starchronicle.identity.<小写身份>`，完整范围和上下文贡献接口见 API 文档。测试事件不提供假国籍或假权限，也不替代真实角色、科技与国家资格。

默认示例不要求任何虚构 StarCore 脚本。`integration-example.json.disabled` 展示真实脚本、任务和国家效果的接法，替换真实 ID、注册提供者并验证后才可启用。

## 示例剧情与能力范围

| 剧情 | 参与范围 | 结局与反馈 |
|---|---|---|
| 帝国最后的继承人 | 世界共同实例，至少两人同意一项决议 | 支持、反对、救济、遗迹、外交路线；复国倡议／主权宣言／共同遗产三结局 |
| 围城余烬 | 各国家独立实例 | 纪念碑计划／口述史／重建倡议，保存逻辑世界记忆 |
| 丝路学者 | 个人实例，职业和科技前置 | 学者公开或保管研究、探险家制图、商人提出贸易路线 |
| 旧都档案：每个人的那一票 | 主线真实历史参与者的个人后续 | 实际投票证言／未投票见证／公开记录核对三结局 |

默认三条主线的国家投票以参与玩家计数；可选[国家议事剧情](docs/NATIONAL-COUNCIL.md)通过 `voteUnit: NATION` 使用真正一国一席。剧情提案不会伪装成已经发生的国家建立、战役胜利或国库扣款。NPC关系与成长、纪念展示、见证者实体和历史书已实现；显示点位需管理员显式授权，插件不覆盖方块或接管他人NPC。真实国家复国、资源划拨及最终战役仍须绑定服内实际权威业务，不能从虚构剧情直接生成国家事实。

历史记录包含事件 ID、发生时间、参与者、关联国家、来源和证据。已经存在于服务器、但从未向 StarChronicle 提交的旧历史不会自动补全。

新事件及其匹配的待触发定义同事务落库；无人在线、条件暂不满足或冷却未到期时，触发继续保留，在玩家加入和后台轮询时用当前权威快照恢复。WORLD、NATION 与 PLAYER 国民种子按实际作用域消费收据去重，不把首位受众的结果当成全体结果，历史 actors 也不会被旁观国民改写。NATION 剧情受显式来源国家约束；全服广播必须由可信来源明确给出 `facts.audience=GLOBAL`，它不扩大国家授权范围。已排队的冷却触发到期后可继续执行，已消费来源不会因重放再次领奖。

原生自动恢复严格限于三个自有场景：注册对象和文件指纹一致、仅使用对应前缀的集合式效果。Request 绑定初始原生节点、权限和原国籍，checkpoint 保存已核验的自有状态；状态为 `READY/RUNNING/UNKNOWN/COMPLETED`，每名玩家只有一个操作所有者。暂时 busy／明确未调用会等待；超时未进入操作体时可 CAS 取消进入，已进入或结果不明则隔离为 UNKNOWN。其他外部 native 脚本、任务、未知奖励与整个上游栈不获得这项保证，UNKNOWN 不自动重放。详见 [API 恢复合同](docs/API.md)。

Folia 的 UI 操作回到玩家实体线程，数据库编排放在工作线程。审计版本 StarCore 缺少 Folia 支持声明且 NarrativeModule 使用旧调度器；默认 `require-starcore: true` 时上游服务缺失会安全停用本插件。显式 `require-starcore: false` 可用于 Folia 独立规则／历史模式，不提供国家身份或上游执行能力。当前 Folia 禁用 `narrative.start`，不把 StarChronicle 自身调度设计当作上游全栈兼容证明。

## 文档

- [付费国家工厂与绑定生产](docs/NATION-FACTORIES.md)及[本轮精确构件验收](docs/NATION-FACTORIES-TEST-REPORT.md)

- [剧情格式、条件、效果、示例和版本管理](docs/STORY-FORMAT.md)
- [Java API、权威边界、幂等和扩展提供者](docs/API.md)
- [精确原生任务证据、剧情门槛和已知运维限制](docs/QUEST-GATES.md)
- [跨剧情选择、范围历史、版本目录、显式历史评估与V4迁移](docs/CONTINUOUS-NARRATIVE.md)
- [上游代码审计与复用点](docs/UPSTREAM-AUDIT.md)
- [第一阶段需求证据与后续缺口](docs/REQUIREMENTS.md)
- [可选NPC插件与联动范围](docs/NPC-INTEGRATIONS.md)
- [NPC人物、关系、成长与历史反应](docs/NPC-SYSTEM.md)
- [八种身份与完整命运线](docs/FATE-STORIES.md)
- [真实历史纪念点、见证者与原生书](docs/WORLD-MEMORY.md)
- [权威快照、事务观测与重启恢复](docs/HISTORY-OBSERVATION.md)
- [复用StarCore场景与任务恢复限制](docs/STARCORE-CONTENT.md)
- [自有原生场景的持久化恢复协议](docs/NATIVE-SCENE-RECOVERY.md)
- [可选文本提供者、限流与模板回退](docs/TEXT-PROVIDERS.md)
- [国家贸易与生产MySQL专项验收](docs/MYSQL-SETTLEMENT-TEST-REPORT.md)
- [国家生产批次与文明贡献合同](docs/NATION-PRODUCTION-CONTRACT.md)与[精确构件验收](docs/NATION-PRODUCTION-TEST-REPORT.md)
- [国家贸易共同提交合同](docs/NATION-TRADE-CONTRACT.md)与[精确构件验收](docs/NATION-TRADE-TEST-REPORT.md)
- [测试与构建报告](docs/TEST-REPORT.md)
- [交付包、复验命令与安装数据](docs/DELIVERY.md)
- [完整目标的后续工作与真实上游前置条件](docs/REMAINING-WORK.md)

可选 AI 仅用于历史摘要、对话草稿和描述。默认模板无需 AI 即可运行；AI 无权执行命令、发奖励、修改国家状态或产生可信事件。
