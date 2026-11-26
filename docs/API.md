# StarChronicle 扩展 API

## 有界剧情查询（schema 8）

界面列表使用 `storyPage(playerId, afterId, size)`；首次游标为 null，继续使用返回的 `nextCursor`，直到它为 null。size 为 1–100，每次最多检查 256 个候选；空列表不一定代表结束。`instance(playerId, instanceId)` 只返回本人的单个实例，`instances(playerId,page,size)` 提供成员历史分页，原无分页方法仍返回完整结果。成员索引不授予动作权限，身份在服务器端采集，详情和操作重新核验正文及权威。容量边界、索引迁移与备份见 [容量与维护](CAPACITY.md)。

## 国家史诗

`ChronicleService` 提供 `epicCampaignPlans`、`openEpicSignup`、`epicSignup`、`enrollEpicSignup`、`withdrawEpicSignup`、`sealEpicSignup`、`epicCampaign`、`voteEpicRoute`、`prepareEpicOperation`、`epicOperation`、`applyEpicOperation`、`reconcileEpicOperation` 和 `epicProviders`。身份由插件重新采集，修订号来自状态查询。

`EpicOperationProvider.supports` 在准备前检查能力。apply 之前先持久占位；不确定结果只查原生回执。provider ID 和 authority domain 固定在操作中，服务注销后旧响应失效。`verifyEpicReceipt` 仅检查 DTO 匹配，不能证明外部传入凭证的真实性。详见 [国家史诗契约](NATIONAL-EPIC-CONTRACT.md)。

包名前缀：`io.github.addxiaoyi.starchronicle`。源码 API 为第一阶段 `0.1.0-SNAPSHOT`，未承诺二进制稳定；跨插件部署应固定构建版本。StarChronicle 负责长期编排、共同阶段与历史，StarCore 继续拥有 NarrativeEngine、原任务执行和国家权威状态。

## Bukkit 跨插件服务入口

运行插件通过 Bukkit `ServicesManager` 注册 `ChronicleService`。集成插件应在自己的 `plugin.yml` 中声明 `softdepend: [StarChronicle]`，启动时查询服务，缺失时关闭对应功能。不要通过反射取得内部 SQLite 或直接调用未调度的 GUI。

```java
ChronicleService chronicle = getServer().getServicesManager().load(ChronicleService.class);
if (chronicle != null) {
    chronicle.submit(committedEvent).whenComplete((report, error) -> {
        // Completion 回调可能在工作线程。这里可以写线程安全日志；
        // 对玩家发消息或操作世界应再调度到正确的实体/区域线程。
    });
}
```

`ChronicleService` 暴露异步 `submit`、`join`、`choose`、`instances`、`activeInstances`、`availableInstances`、`availableChoices`、`history`、`questGateState(instanceId,playerId)`、`npc(playerId,npcId)`、`fate(playerId)` 和 `describe(template,publicFacts)`。运行时会自己收集在线玩家的当前权威快照；集成方不能通过该 API 注入自填国籍或权限。`activeInstances()` 是可信服务器插件的管理查询，GUI 应使用 `storyPage` 或 `availableInstances(playerId)` 避免泄露其他国家或个人实例。

`questGateState` 是只读投影，要求玩家已是该实例成员并仍保持实例记录的原国籍。个人模式只显示本人任务门槛；国家模式只向同一原国家成员显示。它不接受完成回执，不执行任务、不作出剧情选择，也不代表国家席位。配置使用 `quest.await` 前需有可信的 `QuestEvidenceProvider`，不能将 `quest.accept` 投递成功或目标达标解释为完成。完整 provider/run/completion 合同与 StarCore 部署边界见 [QUEST-GATES.md](QUEST-GATES.md)。

`reconcileQuestGate(gateId, playerId, expectedAttempt)` 向原提供者核对本人的原任务；`retryQuestGate(gateId, playerId, expectedAttempt)` 仅在原生持久确认 FAILED/ABANDONED 后归档旧凭据、开启新轮次。调用者不能提交接受、完成或终止证明，身份和权限在服务端重新采集。轮次来自 `GateSummary.attempt()`，旧轮次和其他成员请求会被拒绝。可选 `QuestRecoveryProvider` 扩展负责原生对账和终止证明查询；不支持该扩展的提供者仍可读取原协议，但不能自动重试。UNKNOWN 对账不重放奖励，也不授权盲目新接取。

对交互界面，应调用带阶段令牌的版本：

```java
chronicle.choose(instanceId, playerId, renderedNodeId, choiceId);
```

如果玩家看到界面后阶段已经变化，即使后续节点恰有同名选项，旧点击也会被拒绝。无 `expectedNode` 的旧重载为可信自动化保留，不应用于延迟 GUI 点击。

## 跨剧情记忆与范围历史

`ChronicleService.recollections(UUID playerId, Collection<String> references)` 用新鲜服务端身份返回最多256个明确请求的历史事实，结果不可变；调用方不能提供国籍或权限。支持旧 `history.TYPE`、新 `chronicle.history.world/nation/player.TYPE` 与 `past.player.choice.STORY/VERSION/NODE`、`past.resolution.world/nation.STORY/VERSION/NODE`。只返回请求且可核验的键；已知主体的显式新计数可以为0，未知主体缺失。`past.*` 只投影已完成实例中的最新提交日志，败方个人票与共同决议分开，未投票者没有个人票。详见 [连续叙事协议](CONTINUOUS-NARRATIVE.md)。

```java
chronicle.recollections(playerId, List.of(
    "past.player.choice.last-heir/1/council",
    "past.resolution.world.last-heir/1/council",
    "chronicle.history.player.HEIR_RESTORATION_SUPPORTED"
));
```

所有规则和 NPC 条件会去除上下文伪造的保留历史命名空间，再从当前 SQLite 事务构造。剧情 `history` 结果、NPC反应、选择日志、因果记录及待触发定义同事务提交；内部派生结果只入箱，不递归执行剧情或等待 Bukkit。运行时因果链重复故事／深度32后只拦截后续并保留来源历史；管理员 `/chronicle blocked` 可查，不能把这类确定未执行的拦截当作外部 UNKNOWN 已执行。当前新配置精确解析当前定义与真实持久化退休版本；内容冲突会明确拒绝，退休定义只提供引用证据，不注册为可执行规则。已有历史读取仍不受文件删除影响。

`scene(playerId)` 与 `sceneAction(playerId, expectedScript, expectedNode, visibleChoiceIndex)` 提供自有 StarCore 原生场景的只读投影和委托操作。Bukkit／原生状态的读取与调用在玩家实体线程执行，恢复日志由持久化工作线程管理。索引从1开始，0只允许推进没有选项的节点。`NativeSceneAdapter` 使用上游 `ConditionEvaluator` 过滤对话和可见选项，实际推进调用 `NarrativeEngine.advanceNode/selectChoice`；不会解释上游效果。只允许当前对象仍归本插件注册的脚本，拒绝过期节点和外部脚本。`/chronicle scene` 和剧情GUI的补充场景入口使用同一入口。

自动恢复另受下文三个自有场景的指纹、命名空间、checkpoint 和持久化操作令牌约束。仅有 `chronicle_` 名称或一个空投影不构成恢复／完成证明。未完成上游线程审计时，Folia 上的原生场景访问和执行继续关闭。

当前 GUI 与玩家命令还会检查 `starchronicle.use`；管理验证、测试事件与 UNKNOWN 对账要求 `starchronicle.admin`。自定义故事权限从已加载的 choice/effect permission 收集，在玩家实体线程使用 Bukkit 权限检查后构造明确权限集合。

## 可信职业与地区事实扩展

```java
public interface ContextContributor {
    Map<String, String> contribute(UUID playerId, PlayerContext authoritativeContext);
}
```

通过 `ServicesManager.register(ContextContributor.class, provider, ownerPlugin, ServicePriority.Normal)` 注册。调用发生在玩家实体线程，应快速返回不可变快照，不做阻塞网络请求。当前允许补充 `player.*`、`region.*` 和 `tech.stage`，不会覆盖已有权威字段，也不能改权限、国籍、财政、资源、战争或外交。`player.role` 统一使用小写，例如 `scholar/explorer/merchant`。

内置角色来源为国家创建者映射 `monarch`、可识别的国家成员 rank，以及管理员明确授予的 `starchronicle.identity.soldier/general/merchant/scholar/explorer/diplomat/mayor/monarch`。这些身份权限不是玩家可以自行选择的职业菜单，也不会因 AI 对话而授予。

## 核心输入：已提交世界事件

内置的首访探索来源可通过 `exploration.enabled` 与显式 cuboid 区域配置启用。每个地区需配置稳定的小写 `id`、世界 UUID 和包含边界的整数方块坐标。插件只记录玩家真实移动或传送进入该区域的首次观察，写入玩家、当前可信国籍、方块坐标和 `time_basis=OBSERVED`。持久指纹将逻辑 ID 绑定到世界和边界，不能把原 ID 静默用于另一块区域。默认关闭；它覆盖已配置方块区域，不代表自动发现任意世界地形。

```java
public record WorldEvent(
    String id, String type, Instant occurredAt,
    Set<UUID> participants, Set<String> nations,
    Map<String, String> facts, String source, String evidence) {}
```

`id` 应取上游提交记录的稳定 ID，在重复投递与重启后保持一致，例如 `starcore:war:42:ended`。不要每次监听回调都随机生成 UUID 来冒充去重键。`evidence` 保存可追溯的记录键、事件类名或日志凭据，不能只写“发生了一件事”。时间使用 `Instant`，GUI 展示格式另行处理。

字段限制：ID 最多 256 字符，证据最多 4096 字符，facts 最多 128 项，participants 最多 10000 人；ID、type、source、evidence 必须非空。对象集合不可变。输入是已提交事实，不能使用可取消前置事件就宣称国家变更已经生效。

相同 ID、相同内容不会重复写历史或重做已有结果，但重复投递可以推进该来源尚未交付的持久化剧情触发；因此 `ProcessReport.duplicate=true` 时也可能返回本次恢复出的实例 ID。相同 ID 携带不同参与者、国家、facts 或证据时拒绝，防止来源碰撞悄悄改写历史。精确时间事件的时间也严格比对；明确标记 OBSERVED 的观察时间例外见文末。

新事件与当时匹配规则的完整待触发定义在同一 SQLite 事务中提交。没有在线受众、当前前置条件未满足或仍在冷却，不等于剧情结果已经消费：`resumeTriggers` 会在真实玩家加入、实例查询和工作线程轮询时，使用新鲜服务端快照继续判断。它只处理已提交的待触发记录，不把所有旧历史反向解释为新剧情，也不在每次轮询中重新扫描所有配置规则。

`facts` 的键在条件中加 `event.` 前缀；例如 `facts={"battle_kind":"siege"}` 对应 `event.battle_kind`。核心不会把事件 facts 当成当前国家财政来替换权威查询。

历史参与者与剧情受众分开：默认受众是来源 `participants` 中的人，或当前属于来源 `nations` 的国民；旁观国民不会因此被追加到历史 actors。可信来源如需全服广播，必须显式设置 `facts.audience=GLOBAL`；空的参与者／国家集合本身不表示广播。`GLOBAL` 扩大受众，不授予角色或国家权限，也不允许越过显式 `source.nations` 为其他国家创建 NATION 剧情。来源未列国家时，参与者种子依据该参与者恢复时的当前国籍求实际作用域；若同时明确 GLOBAL，则各合格广播受众的实际国家可形成独立 NATION 实例。

待触发记录按作用域保留种子：WORLD 使用世界种子，NATION 使用来源国家或有证据的参与者种子；PLAYER 除直接参与者外，使用 `nation-players:<国家ID>` 保留相关国民的后续个人触发，显式广播使用通配受众种子。消费收据（ScopeReceipt）以来源／剧情／版本触发键与**实际** `player:UUID`、`nation:ID` 或 `world` 作用域去重；广播或国民种子不会因首位玩家处理完毕就冒充所有人的结果。恢复按既有规则推进实例和效果收据，原事件参与者、国家、时间及证据保持不变。

## 当前身份和权威上下文

```java
public record PlayerContext(UUID playerId, String nationId,
                            Map<String, String> facts, Set<String> permissions) {}
public interface CoreGateway {
    PlayerContext context(UUID playerId);
    boolean hasScript(String id);
    boolean hasQuest(String id);
    CompletionStage<DeliveryResult> deliver(Delivery delivery);
    enum DeliveryResult { APPLIED, REJECTED, UNKNOWN }
}
```

上下文必须来自当前权威状态和权限系统，不能来自 `/command nation=...` 等玩家输入。无国家时 `nationId` 使用空字符串。`permissions` 是已经检查过的明确权限集合；核心的 `permitted` 不自行解释通配符。生产适配器须在玩家所属实体线程或上游允许的线程读取 Bukkit 权限，再把不可变快照送往编排工作线程。

StarCore 默认桥接只能提供实际可查询的字段。职业、外交、资源等扩展条件可使用同一 facts 格式，但键名存在于示例不等于上游已实现全部对应数据。缺失字段使比较条件为 false。选择时重新取得上下文；既有成员换国后选择会被拒绝。

当前资源快照同时提供 `nation.resource.KEY` 与 `resource.KEY`，便于内容编写。`TechnologyFacts` 从真实已解锁定义的 era 取最高 `TechnologyEra.getOrder()`，提供 `tech.stage/tech.era`；未知定义、空集合或不完整读取时省略，允许可信贡献者补充，不能用技术条目数冒充时代。研究事件的 `event.tech.stage` 是触发时的时代快照，不自动改变玩家身份。

## 纯 Java 规则引擎

`RuleEngine` 可脱离 Bukkit 进行测试。构造时传入本地 SQLite store、已验证定义与 `Clock`：

```java
var store = new SqliteStore(Path.of("chronicle.db"));
var definitions = ConfigLoader.load(Path.of("stories"), gateway::hasScript, gateway::hasQuest);
try (var engine = new RuleEngine(store, definitions, Clock.systemUTC())) {
    UUID player = UUID.fromString("00000000-0000-0000-0000-000000000001");
    var context = new PlayerContext(player, "river-league",
        Map.of("player.role", "scholar", "tech.stage", "2"), Set.of());
    var event = new WorldEvent("exploration:caravanserai:100", "EXPLORATION_DISCOVERY",
        Instant.parse("2026-11-19T00:00:00Z"), Set.of(player), Set.of("river-league"),
        Map.of("discovery", "ancient-caravanserai"), "TrustedExplorationAdapter",
        "discovery-record=100; region=ancient-caravanserai");
    ProcessReport accepted = engine.accept(event, List.of(context));
    String instanceId = accepted.instanceIds().getFirst();
    engine.choose(instanceId, player, "discovery", "research", context);
    engine.choose(instanceId, player, "research", "share", context);
    List<WorldEvent> biography = engine.history(null, player, 0, 45);
}
```

代码中的来源事件是集成示例，不是自动生成服务器事实的方法。生产调用请使用插件暴露的集成入口，避免另开同一数据库连接并绕过生命周期。

| 方法 | 作用 |
|---|---|
| `accept(WorldEvent, Collection<PlayerContext>)` | 原子记录新事件及匹配规则的待触发快照，再尝试创建／纳入实例；受众须为来源参与人、关联国家当前国民或显式 GLOBAL 广播受众，不改变历史 participants |
| `resumeTriggers(Collection<PlayerContext>)` | 用新鲜当前上下文恢复持久化待触发记录和相关活动实例成员；无受众、条件不满足或冷却中保留待触发状态 |
| `pendingDefinitions()` / `pendingTriggerCount()` | 查询持久化待触发定义／数量；用于恢复所需权限和管理检查，不表示这些剧情已经执行 |
| `join(String instanceId, PlayerContext)` | 显式加入活动共享剧情；依据原来源证据与当前资格校验 |
| `choose(String instanceId, UUID, String choiceId, PlayerContext)` | 校验成员、当前国籍、分支条件和权限；保存票并在达到 quorum 后迁移 |
| `choose(String instanceId, UUID, String expectedNode, String choiceId, PlayerContext)` | 同上，额外拒绝来自旧节点的延迟操作；GUI 应使用此版本 |
| `instances(UUID)` | 查询该玩家参与的实例，含已完成实例 |
| `activeInstances()` | 查询活动实例，供公共剧情列表与管理员检查 |
| `availableInstances(PlayerContext)` | 只列该玩家可见或有资格加入的活动实例；其他国家和其他人的个人实例被过滤 |
| `availableChoices(String instanceId, PlayerContext)` | 按当前成员、国籍、分支条件与选项权限过滤可显示选项；执行时仍重新校验 |
| `facts(UUID)` | 查询该玩家本地荣誉、称号和 NPC 声望等叙事事实 |
| `history(String nation, UUID player, int page, int size)` | 时间倒序、事件 ID 稳定次序的历史；null 表示不加对应过滤，两个过滤可取交集 |
| `reload(List<StoryDefinition>)` | 替换后续新来源匹配所用定义；调用方必须先验证，进行中实例与已排队触发保留各自旧快照 |
| `close()` | 关闭 store；只能在请求已排空后调用 |

`history` 的 page 从 0 起，size 为 1–100，保留编号分页。新增 `historyPage(nationId,playerId,beforeEventId,size)` 返回不可变 `HistoryPage(events,nextCursor)`；首次 beforeEventId 为 null，之后使用返回的 nextCursor，null 表示结束。游标必须是当前过滤范围内已保存的事件 ID，缺失或范围不匹配会拒绝，不能当作首页。它按数据库原始 `(time,id)` 降序续读，不重新格式化旧时间、不改写证据。新插入的较新历史在刷新首页后可见，迟到的较旧证据可能出现在后页；它不是固定时点快照或授权令牌。旧第三方服务实现可继续加载，新增默认方法明确报告不支持。

历史 GUI 的顺序翻页使用游标，最多保留 128 个回退位置；显式编号跳页和更早回退保留原分页接口，证据详情返回原查询位置。`ProcessReport` 包含 `duplicate`、变更实例 ID 和用户消息。核心数据库事务会串行化共同阶段，不能在 Folia 区域线程直接执行磁盘访问。

## 外部效果、权限与崩溃窗口

```java
public record Delivery(String outcomeId, String instanceId, String type,
                       String target, String value, UUID playerId,
                       String nationId, String permission) {}
public interface AuthorityProvider {
    CompletionStage<CoreGateway.DeliveryResult> apply(Delivery delivery);
}
```

`outcomeId` 是稳定幂等键，**不是权限令牌**。权威提供者必须重新验证调用者身份、成员资格、权限、实际国家 ID、当前财政／资源等前置条件。所有真实国家变更必须走 StarCore 权威服务；不能在此直接改表、执行管理命令或信任 AI 生成的操作。

推荐交付协议：

1. `pending()` 读取最多 100 个待投递项。
2. `claimDelivery(outcomeId)` 成功后才允许调用上游，状态从 PENDING 变为 RUNNING。
3. 完整上游提交后返回 APPLIED；明确未执行返回 REJECTED；无法确认返回 UNKNOWN。
4. `finishDelivery(id, result)` 保存回执；启动时遗留 RUNNING 转为 UNKNOWN。
5. 管理员独立检查上游记录后，可用 `acknowledgeUnknown(id)` 仅确认已执行的 UNKNOWN；核心没有盲目重发 UNKNOWN 的入口。

若权威服务能将 `outcomeId` 去重记录与实际国家修改放在同一事务，就可实现外部安全重试协议。现有 StarCore 脚本／任务接口并未据此自动获得跨进程严格一次能力，因此此插件选择隔离不确定结果，避免重复奖励。UNKNOWN 可能意味着已执行，也可能未执行；不能把它直接显示成“奖励已到账”。

`authority` 效果携带使投票最终通过的真实操作玩家及其当前国家，按该国家和效果结果生成一次请求，不会伪装所有实例成员调用国家服务，也不会按国家人数重复修改财政。它不自动替其他参与国家作决定；多国条约应由权威提供者另行验证每一国的授权。

本地叙事属性、历史和阶段迁移在同一 SQLite 事务中提交，不经过外部管理命令。脚本启动／任务接受进入 outbox，不会阻塞节点等待其完成。

### 三个自有原生场景的有限恢复

`NativeSceneJournal` 只为 `chronicle_last_heir`、`chronicle_siege_embers`、`chronicle_silk_road` 三个经审计的自有场景提供恢复。注册对象必须仍归本插件所有，注册内容的 SHA-256 指纹必须与请求一致；节点／选项效果仅允许各自 `chronicle_heir_`、`chronicle_siege_`、`chronicle_caravan_` 前缀内的集合式 `flag/unflag/evidence/removeEvidence/markEnding` 操作。修改为增量奖励、其他命名空间或任意外部脚本，不会自动继承这项保证。

持久化 `Request` 绑定稳定 outcome ID、编排实例、玩家、原国籍、脚本 ID／指纹、初始原生节点和所需权限。每次实际调用前重新检查在线玩家、权限和原国籍；修订号只是 CAS 租约，不是授权。`Checkpoint` 保存确认回读的原生节点、该场景独占前缀的标量变量／flags／evidence 和只读展示。恢复仍调用真实 StarCore 引擎，在核验节点后合并自有状态，不清空其他脚本状态；`saveState(void)` 也不被当作持久提交确认。

| 原生日志状态 | 含义 |
|---|---|
| `READY` | 等待首次调用，或已有已提交 checkpoint 可安全重新附着／恢复 |
| `RUNNING` | 调用前已持久领取，实际原生结果尚未确认回写 |
| `UNKNOWN` | 调用可能已发生，或脚本指纹／所有权、结果回写等存在歧义；隔离且不自动重放 |
| `COMPLETED` | 已核验原生 session 与路由均结束，或管理员独立对账后关闭隔离 |

同一玩家一次只准一个原生调用／恢复所有者。另一场景占用、暂时离线或明确 `NOT_INVOKED` 会保留 `READY`，初始投递可回到 PENDING，不会永久标成拒绝。超时使用 CAS 进入门：确认尚未进入实体操作体时可取消后续进入并按未调用处理；已经进入、反射调用后失败或无法确认是否执行时保守记为 `UNKNOWN`。进程遗留的 RUNNING 以及失去持久化续体的调用同样隔离；旧 revision 的迟到回执不能覆盖新状态。原生操作完成回读、checkpoint 与对玩家暴露成功之间有明确顺序。

`NativeSceneFlights` 将同一玩家正在进行的后台恢复／捕获与 GUI 读取合并到同一个、在结果落盘后才完成的 future，界面不会绕过在途操作读取瞬时 raw view。GUI 动作只在该 future 成功后回到持久化 worker，重新解析 journal，并校验原请求的 script/node 后再决定是否调用原生引擎；等待中的异常或 UNKNOWN 直接传播，不自动重试动作。属于自有 managed 场景但缺少有效持久化令牌时，同样不能通过 raw view 暴露场景来绕过该协议。

`/chronicle uncertain` 同时列出外部效果和原生场景 UNKNOWN。管理员独立核对后可使用 `/chronicle native-ack <outcomeId> CONFIRM_SCENE_COMPLETED` 关闭原生隔离；它只登记该场景已结束／放弃，绝不启动、推进场景或重发奖励。普通 `/chronicle ack ... CONFIRM_APPLIED` 仍用于外部效果对账。其他外部 native 脚本、原生任务与整个 StarCore/Folia 栈不获得上述恢复保证；任何未知奖励都不自动重试。

## NPC 生命周期与文本提供者

```java
public interface NpcProvider extends AutoCloseable {
    String name();
    void showDialogue(UUID player, String npcId, String text);
    void close();
}
public interface TextProvider {
    CompletionStage<String> describe(String template, Map<String,String> publicFacts);
}
```

NPC 提供者是可选展示扩展点。注册方必须记录自己创建／附着的 NPC 与监听器，在 `close` 时只释放自己拥有的资源；禁止遍历并删除其他插件 NPC。内置 `NpcCatalog/NpcLedger` 已提供背景、阵营、长期关系、成长职位和历史反应。`/chronicle memory create` 可显式生成归本插件所有的纪念点和见证者实体；第三方NPC只接交互，不接管所有权。见 [NPC-SYSTEM.md](NPC-SYSTEM.md)、[WORLD-MEMORY.md](WORLD-MEMORY.md)。

安装 WorldGuard 时，纪念点创建在玩家所属线程通过其 `RegionQuery.testBuild` 检查展示占用的整块范围，并遵循 WorldGuard 自身的 bypass。Chronicle 管理权限不替代领地权限；已安装但停用、接口不兼容或查询失败时拒绝创建。没有安装 WorldGuard 时沿用管理员显式放置。该检查是创建授权，不会追溯删除此前已授权点位，也不授予方块施工或第三方 NPC 管理权。

`TextProvider.templates()` 提供基础模板。运行时 `ChronicleService.describe` 使用有界 `TextService`，在提供者缺失、超时、失败或无效响应时完整回退；最多32个真正未完成的请求，输出最多8192字符，替换不递归。它不持有奖励服务、国家服务、Bukkit console 或命令入口，AI 输出不得再解析为效果或条件。见 [TEXT-PROVIDERS.md](TEXT-PROVIDERS.md)。

## 生产装载流程与边界

1. 固定并安装审计版本的 StarCore，确认 NarrativeEngine 与任务定义已由 StarCore 加载。
2. 安装 StarChronicle。首次生成三条主剧情、八条身份剧情和NPC目录；默认文件不引用不存在的脚本／任务。`CoreContentInstaller` 通过真实ScriptLoader注册三个自有场景，确认成功才在运行时补充主剧情起始节点的script引用；独立模式保留原定义。自有原生任务因上游恢复缺陷默认不安装，见 [STARCORE-CONTENT.md](STARCORE-CONTENT.md)。
3. 将已有真实脚本／任务 ID 放入自己的故事；先运行 `/chronicle validate`。该命令只验证磁盘文件，不装载修改；再用 `/chronicle reload` 验证并替换后续规则，失败时保留原定义。既有实例和已排队触发保留旧快照，权限集合包含活动实例与待触发旧定义，重启后同样恢复。纯 Java `reload` 可供受信任集成使用，不能绕过验证。
4. 只从可信、已提交事件和当前上下文提交 `WorldEvent`。国家／战争等适配器以仓库审计列出的实际支持范围为准。
5. 真实原生权威服务可用时内置最低优先级 `StarCoreAuthorityProvider`：`type=authority,target=diplomacy.invite,value=对方国家UUID,permission=starcore.nation.ally.add`。它重新验证当前君主、国籍、Bukkit与StarCore国家权限，只发送需对方另行接受的邀请。自定义提供者可用更高服务优先级覆盖；未知操作默认拒绝。不确定结果不自动重试。接入NPC插件时按目录绑定真实外部ID。
6. 正常停服后备份数据库；运行中备份必须包含 SQLite WAL 的一致性方案。不要只复制正在写入的 `.db` 文件。

schema 3 的选择迁移在保留 V1/V2 原数据的基础上，同事务创建选择日志及派生因果表，并将旧实例仍保存的选择导入为 `LEGACY_INSTANCE`；未知原投票时间／提交人保持NULL，中间改投不可重建。迁移失败会回滚版本和局部导入，重开不重复；较新 schema 会拒绝由旧程序打开。升级仍不能倒推出已消费来源的旧规则／资格，或补造原生 Request/checkpoint，不自动 backfill 这些触发或奖励。升级前正常停服并备份。详情见 [CONTINUOUS-NARRATIVE.md](CONTINUOUS-NARRATIVE.md)。

`ObservationJournal` 为缺少提交后事件的权威快照提供同事务基线与持久ingress，成功accept后才ack；重启重投保持原ID和证据。原生完整国家列表用于观察国家消失，真实技术集合用于观察新解锁。首都迁移必须有可信 `SnapshotProvider` 的CAPITALS完整集合：审计CityService无持久指定首都getter，默认不猜迁都。重要战役取真实战争记录；探索／大型事件需要有稳定提交证据的服内集成。SenLuoPlugin 实际是 CraftEngine 内容插件，它的烹饪预提交事件不能当作国家历史权威来源。见 [HISTORY-OBSERVATION.md](HISTORY-OBSERVATION.md)。

审计版本的 StarCore 缺少 `folia-supported: true`，且 NarrativeModule 仍使用 Bukkit 主线程定时器。未修改的上游不能据此宣称在 Folia 生产可用。`require-starcore: true` 时其服务不可用会使本插件安全停用；Folia 下显式 standalone 可以运行独立编排／历史，但不会得到真实国家身份或上游任务／剧情执行能力。即使另有可加载的上游适配构建，StarChronicle 仍会在 Folia 拒绝 `narrative.start`，直到完成单独线程兼容审计。

缺少上游发生时间的已提交事件会使用 `facts.time_basis=OBSERVED` 并在 evidence 标注观察时间。本地保留第一次成功记录的时间；同一稳定来源 ID 的后续观察时间变化不构成内容冲突，其他事实、来源、参与者等仍严格比对。没有这一结构化标记的事件仍严格校验时间。观察时间不是精确发生时间，也不证明停服期间漏掉的事件能够自动补齐。

## 精确版本目录与已有历史评估

`ChronicleService.evaluateHistorical(sourceEventId, storyId, expectedVersion)` 返回 `CompletionStage<HistoricalEvaluationReport>`。它仅为已保存canonical来源登记一个当前启用的精确剧情版本，不接收任意定义、玩家上下文或替换事件，也不立即执行效果。`QUEUED / ALREADY_REGISTERED / NO_SOURCE_AUDIENCE / BLOCKED_CAUSAL` 含义及原始历史保护见 [CONTINUOUS-NARRATIVE.md](CONTINUOUS-NARRATIVE.md)。命令 `/chronicle evaluate <事件ID> <剧情ID> <版本>` 要求管理权限；可信插件API保留现有服务端信任边界。

schema 5 的版本目录迁移保留原始 JSON／SHA 与来源，新增格式 sidecar 记录 V1→V2 语义关系，默认 `voteUnit: PLAYER` 不改变旧剧情计票。schema 6 增加 `quest_gates` 与完成消费表，不重编码 V1–V5 数据；实际迁移与全量构建结果见 `TEST-REPORT.md`，目标服务器仍须基于停服备份复验。`DefinitionCatalog` 仅包含正式接受的有效定义或真实旧快照，以 ID／版本／canonical 指纹保存冲突变体；`require(id, version)` 精确解析，缺失／歧义抛异常。`ConfigLoader.load(..., catalog)`、`validateAll(definitions, catalog)`支持退休版本；两阶段加载先做单文件校验，再绑定原生内容，最后由 RuleEngine 事务校验和归档。纯 validate 不归档。已有原生执行、国家权限、UNKNOWN 对账与 Folia 边界不变。

## 国家席位与修订令牌

`voteUnit: NATION` 使用独立的国家动作账本，一国在一个实例节点只有一席。界面先读取 `nationalState(instanceId, playerId)`，再将该页面的 `nodeId`、`viewerRevision` 原样传给 `nationalVote(instanceId, playerId, expectedNode, expectedRevision, choiceId)` 或 `withdrawNationalVote(instanceId, playerId, expectedNode, expectedRevision)`。旧 `choose` 两个重载拒绝国家表决，不能通过省略令牌绕过CAS。API仅接受玩家UUID，当前国籍和权限由服务收集。

正式代表权由 `NationalVoteAuthority` 提供，内置适配器在实际 StarCore NationModule 的同步边界内验证当前国家持有人。OP、职业、称号和自填facts不能提供代表资格。缺少服务时仍可查看已加入实例的只读票据；提交默认拒绝。已记录票数只代表最近成功核验的投影，下一次提交会重新核验所有计票国家。冻结决议不因后续灭国而重算。

`past.nation.choice.STORY/VERSION/NODE` 读取当前真实国家在已完成国家剧情中的最新动作；撤回／失效产生缺失值，不回退更早支持。它不是代表个人的 `past.player.choice`，也不替代 WORLD／NATION实例各自的 `past.resolution.*`。完整格式、国家服务合同、配置示例与运维边界见 [国家议事协议](NATIONAL-COUNCIL.md)。

## 国家援助与国库回执

`ChronicleService.submitAid(AidRequest)` 只允许通过内置 StarCore treasury `AidProvider` 执行一次国家间转账；`reconcileAid(AidRequest)` 是显式的只读回执对账入口。服务会固定 provider/domain、验证当前 actor 和国家提款权限，并以 `(issuer, outcomeId)` 作为幂等键。原生回执未严格匹配前不会写入历史、贡献或剧情结果。

台账会持久化 `READY`、`ADMITTED`、`UNKNOWN`、`COMMITTED` 和 `REJECTED`。重启后的 `ADMITTED` 进入 `UNKNOWN`，不会自动重试；原生拒绝只有在查询明确返回 `ABSENT` 后才会落为 `REJECTED`。StarCore 缺少可信 `authorityDomainId` 时援助服务保持停用。接口字段、回执匹配规则和配置示例见 [国家援助与国库对账](NATIONAL-AID.md)。


## 自动世界记忆配方（schema 7）

`ChronicleService.memoryEntries(String regionId, int page, int size)` 异步只读查询已登记条目，`regionId=null` 查询全部，页从 0 起，size 为 1–100。返回 `MemoryEntry` 的来源／信号 ID、配方版本和指纹、固定地区、纯文本、`sourceOccurredAt` 与 `recordedAt`。既有历史 API／GUI 可查询 `WORLD_MEMORY_RECORDED`；没有手动完成、补发奖励或远程施工能力。

`RuleEngine` 新的六参构造及 `reload(stories, memoryRecipes)`、`validate(stories, memoryRecipes)` 把两套有效配置放在同一 SQL 验证／归档／发布过程。原构造默认空配方目录；原 `reload(stories)` 保留当前配方。纯 validate 不归档。更改同 ID／版本的配方内容会拒绝，启用开关不进入内容指纹；删除／停用不删条目或本地投影，新版本不自动重算旧来源。

schema 7 仅在原因果表追加默认空的 `recipe_path` 并新增定义、条目与配方拦截表。`EventCausality.Trace(parent, root, depth, stories)` 仍兼容；五参形式增加真实 recipes 路径，depth 是两个路径长度之和。源事件 facts 无权设定此路径。故事／配方重复 ID（跨版本）与总深度 32 均拦截；每个顶层 SQL 操作最多生成 256 条配方信号，所有分支共享预算。拦截保留真实源历史与可查询原因，不自动在后续轮询补发。

信号按来源事件的真实 actors／nations 和显式 GLOBAL 受众继承，不添加旁观国民。只有存在 recipe 因果路径的故事结果才可沿 canonical 来源复制已存在的 region.id／city.id，并附 region.source_event；旧无 recipe 的故事 payload 不变。完整配置、线程、时间、字段上限与生产边界见 [MEMORY-RECIPES.md](MEMORY-RECIPES.md)。这项本地叙事登记不代表已经部署上游国家权威能力或取得全栈 Folia 验收。

## 贡献查询与成员复核

`ChronicleService.contributions(subjectId, type, page, size)` 返回已核验的贡献记录；主体可为 `player:<UUID>`／`nation:<UUID>`，主体与类型传 `null` 表示不过滤。页码从 0 开始，页大小为 1..100。`contributionTotal(subjectId, type, unit)` 使用精确十进制按单位汇总，国库 `treasury` 单独统计；RESOURCE_AID 的 `agricultural` 与 `resource.agricultural` 等新旧单位作为查询别名合计。原始行、单位和证据不改写；底层 `ContributionLedger.total` 仍按精确单位查询。公开服务没有直接写入贡献的入口。

`contributionBalances(subjectId, type, page, size)` 返回原始条目、当前状态、该状态的来源事件／提交时间与 `netAmount()`；只有 ACTIVE 计入净值。`contributionRevisions(contributionId, page, size)` 按追加顺序倒序返回状态凭据。`tradeHistoryReceipt(eventId)` 返回专用贸易来源的完整原生回执，包括超过历史事实长度限制的备注。上述方法均异步只读，分页大小为 1..100；`/chronicle contributions [player|nation] [页]` 显示当前计入／原始数量和状态。

贸易协调器保留已安装格式：存在旧 `committed_source_cursors` 时继续原来源、操作 ID、`resource.<资源>` 单位、baseline 与剧情激活语义，不重复接入专用 journal；新安装或既有上游 journal 使用 `tradeChangesAfter`，首次追赶只归档、建立净贡献，追上后才激活新剧情。两套贸易游标同时存在时隔离贸易读取并保留数据。原生 UUID getter 优先使用 `tradeHistoryAuthorityDomainId`，兼容上游原 UUID getter，但不会把旧 Optional getter 的序号当作专用流。数据库升级到 schema 9，阻止会忽略新修订或运行另一套采集器的 schema 8 旧 JAR 打开；回退必须使用配套旧版备份。

旧贡献的原行和既有历史不改写。旧库升级前没有独立修订表，其当前净状态从原始提交凭据读取；新修订表只追加升级后的变化，不能据此声称它列出了升级前的全部修订。旧原始回执仍在 `committed_source_receipts`，不会伪造为缺少字段的完整 `TradeHistoryReceipt`。旧公开 `ContributionEntry.sourceStatus` 投影保持原行为；专用来源的原始条目保持不变，通过余额／修订接口读取当前状态。退款、争议和取消只改变本地有效合计，不调用资产接口或撤回已经发出的奖励。

专用来源支持原生 64 字符资源和 `Long.MAX_VALUE` 数量；国库／资源援助保留原金额上限。旧来源的数量／单位准入规则保持原样，避免重新读取锚点时改写已经固定的凭据。查询单位仍需使用该条目保存的准确单位，贸易的有前缀／无前缀单位不自动合并。

史诗 `TREASURY_AID`／`RESOURCE_AID` 的提供者回执、历史事件、剧情收件箱和每个资源的两条主体贡献在同一事务提交；UNKNOWN、拒绝、准备或选项投票不会产生贡献。旧候选的已提交回执由有界恢复任务补入，只读已有 provider/domain 固定回执。原生回执绑定兼容两分支的旧操作编号与资源单位，保存 `epic_contribution_projections` 恢复标记并核对原回执；已有行及事件保持原值，缺失主体才补录。新国库援助与史诗援助复用规范事件，同一转账按原生回执去重；旧分支已保存的其他历史事件不删除。

`revalidateMember(instanceId, playerId)` 在 StarCore 当前成员变更锁内重新核验原国籍、国家存续及剧情条件；普通成员也可复核，不需要国王身份。缺失权威服务、换国未恢复、无使用权限、退出或已结束剧情均拒绝。`exitMember(instanceId, playerId)` 显式退出活动剧情，保留历史选择与原生任务证明。后台恢复不会自动重新加入；`join` 对退出玩家要求原国籍及当前权威核验。

`memberReviews(playerId, limit)` 返回本人的复核状态，`limit` 为 1..1000。玩家入口保留 `review`／`revalidate`、`membership`、`reviews`、`leave <实例ID> [当前节点ID]` 与 `contributions [player|nation] [页]`；单独页码等同本人贡献。三参 `exitMember` 校验调用方看到的节点，原两参版本退出处理时的当前活动实例。插件 API 自行收集实时玩家快照，调用者不能提供国籍或审批标志。已退出成员的任务闸门保持可读且不再主动执行，已经提交的同国任务证明仍属于历史国家；原审核行或追加退出历史均可证明旧成员，缺少全部证据仍按损坏拒绝。

嵌入式 `RuleEngine` 旧构造器保持由可信调用方授权上下文的契约；新增带 `MemberAuthority` 构造器在本引擎复核／重新加入时取得同步、单次、同线程的成员凭据。公开插件使用该原生权威模式，退出后重新加入要求原国籍；独立模式仅允许无国家成员。旧嵌入式显式重新加入仍可记录新的成员决定，追加历史不会被改写。该兼容入口不适合接收玩家自填身份。
