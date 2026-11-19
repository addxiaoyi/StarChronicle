# 剧情格式与内容编写

第一阶段的编辑器是 YAML/JSON 文件格式、严格加载器和验证入口。它没有图形化拖拽编剧器，也不执行 JavaScript、表达式语言或任意服务器命令。服务器内的 GUI 用于参与剧情与查阅历史。

## 文件布局

启用文件放入插件数据目录的 `stories/`，每文件一条剧情，扩展名为 `.yml`、`.yaml` 或 `.json`。其他扩展名被忽略；随包的 `integration-example.json.disabled` 因此不会加载。单个文件上限 1 MiB，单条剧情最多 256 个节点。YAML 与 JSON 共用同一 Java record 模型，未知字段导致加载失败，避免拼写错误被静默忽略。

```yaml
id: border-library
version: "1"
title: "边境图书馆"
scope: PLAYER
triggers: [EXPLORATION_DISCOVERY]
condition:
  all:
    - {fact: player.role, op: eq, value: scholar}
    - {fact: tech.stage, op: gte, value: "2"}
start: arrival
cooldownSeconds: 86400
nodes:
  arrival:
    text: "你愿意公开这次调查的记录吗？"
    quorum: 1
    choices:
      publish:
        label: "公开调查记录"
        next: published
      preserve:
        label: "先保存原件"
        next: preserved
  published:
    text: "调查记录已加入公共历史。"
    terminal: true
    effects:
      - {id: publish-honor, type: honor, target: player, value: "5"}
      - {id: publish-history, type: history, target: LIBRARY_RECORD_PUBLISHED, value: "调查记录被公开。"}
  preserved:
    text: "原件已列入保存计划。"
    terminal: true
    effects:
      - {id: preserve-memory, type: memory, target: border-library.archive, value: "preservation-planned"}
```

`id`、`version`、节点 ID、选项 ID、效果 ID 使用 1–80 位 ASCII 字母、数字、点、下划线或连字符。`version` 应加引号，便于把内容版本作为稳定字符串管理。标题、文本、条件值及效果文本均有长度限制；普通文本上限为 4096 字符。

## 作用域、阶段和恢复

| scope | 实例隔离 | 典型用途 |
|---|---|---|
| `PLAYER` | 同一剧情、同一玩家 | 个人探索和职业支线 |
| `NATION` | 同一剧情、同一国家 | 战后重建提案和国家议事 |
| `WORLD` | 同一剧情、整个世界 | 多国共同参与的世界史诗 |

实例只从可信来源事件创建。`triggers` 是大小写敏感的事件类型列表。新来源事件与当时匹配规则的完整待触发定义在同一事务保存，再尝试创建实例；无人在线、前置条件不满足或仍在冷却，不会把该触发永久消费。玩家加入、查询实例和后台轮询会用新鲜当前身份调用 `resumeTriggers`，继续处理持久化待触发记录。剧情自产的 `history` 结果也使用这条事务入箱链路，后续由有界 drain 处理；不递归调用 `accept`，不在数据库事务中等待玩家线程。其他真实业务事件仍由可信跨插件集成提交。

默认受众是来源参与者和当前属于来源关联国家的国民，受众不会被追加入历史 actors。需要全服受众时，可信事件提供者必须明确设置 `facts.audience=GLOBAL`；空参与者／国家不隐含广播。NATION 故事受显式 `source.nations` 约束，即使 GLOBAL 或来源参与者后来换国，也不能为未列出的国家创建实例；未列来源国家时，根据有证据参与者或显式 GLOBAL 受众的当前国籍，分别确定实际国家作用域。

WORLD、NATION 和 PLAYER 的待触发种子分别保留世界、国家／参与者、个人资格。PLAYER 国家事件另保留 `nation-players:<国家ID>` 国民种子，以便未在线国民后续形成个人实例；GLOBAL 可保留开放受众种子。实际结果通过来源／剧情／版本触发键与 `world`、`nation:ID` 或 `player:UUID` 的消费收据（ScopeReceipt）去重。国民／广播种子可在首人消费后继续保留，首人的荣誉和效果不会因此再发；国家或世界作用域的收据也不冒充其他作用域的结果。

同一作用域已有活动实例时，新触发可以纳入符合条件的参与者，不会覆盖旧图。共同剧情也可显式 `join`；个人实例只允许该实例的本人参与，国家剧情要求当前国籍符合实例国家。国籍发生变化的既有成员不能直接继续选择，需要管理员在业务层处理资格。

`cooldownSeconds` 从该作用域最近实例的 `updatedAt` 计算。冷却中不会重新创建实例，但已经提交的新合法来源会继续作为待触发记录，等冷却到期和当前资格满足后再开展，无需重新伪造或重复提交事件。已经有消费收据的同一来源不会因冷却结束再次发放同一结果。

实例保存完整定义快照、来源事件 ID、当前节点、成员国籍快照、投票与已作选择；尚未激活的触发也保存完整定义和所需权限。因此重启和配置重载不会把旧实例或已排队触发切换到新图。修改内容时提升 `version`，新规则仅用于之后新来源的匹配；不要手工改写已保存的数据库 JSON。

schema 3 保留 V1/V2 的历史、实例、投票、facts、触发与效果回执，事务内增加选择日志与派生因果表。旧实例仍保存的最后选择导入为 `LEGACY_INSTANCE`，不伪造原投票时间，也不能恢复已覆盖的中间改投。旧事件不会自动补排后来才安装的规则，旧原生会话也不会被补造 checkpoint。迁移失败会回滚，重开不重复导入；升级前正常停服并备份数据库。

非终点必须至少有一个 `choices`，终点应设置 `terminal: true` 且没有选项。每节点最多 36 个选项。第一阶段要求节点图为可达的有向无环图：未知目标、环和不可达节点均拒绝加载。循环任务应由新的来源事件创建下一次实例。

`quorum` 为同一选项需要的不同玩家票数，省略时为 1。投票不足时只保存票，不执行选项效果。阶段尚未迁移时可改投；重复投给同一选项是无操作。先达到人数的选项决定共同阶段，随后投票清空。它是参与者投票，**不是一国一票，也不保证参与国家数量**。若需要国家议会，应通过角色、权限与权威适配器实现正式国家代表规则。

## 条件表达式

每个条件恰好包含一种形式：`all`、`any`、`not` 或 `fact`。组合深度上限 16，每组 1–64 个子条件。可以在剧情顶层和选项上使用。没有条件表示允许。普通比较采用三值逻辑：事实缺失、数值无法解析时为“未知”；只有最终明确为 true 才允许，最终为 false 或未知均不满足条件。`ne` 和 `not` 都不能将未知权威状态变成 true。数值比较使用十进制数。

| 运算 | 示例 | 语义 |
|---|---|---|
| `eq` / `ne` | `{fact: player.role, op: eq, value: scholar}` | 大小写敏感字符串比较 |
| `gte` / `lte` / `gt` / `lt` | `{fact: nation.treasury, op: gte, value: "1000"}` | 数值比较 |
| `exists` | `{fact: nation.id, op: exists}` | 事实键存在 |
| `all` / `any` | `all: [...]` | 全部／任一成立 |
| `not` | `not: {fact: nation.war, op: eq, value: "true"}` | true/false取反；未知仍为未知 |

`exists` 是明确的二值存在性检查：缺失时 false，存在时 true，因此 `not: {fact: nation.id, op: exists}` 可以有意匹配无国家身份。其他比较的缺失不能这样放行：战争事实缺失时，`nation.war eq "false"` 和 `not(nation.war eq "true")` 都不满足；国库返回非数字时，数值比较及其取反都不满足。JSON 中值同样建议使用字符串。

组合遵循三值逻辑：`all` 含明确 false 则为 false，否则有未知则为未知；`any` 含明确 true 则为 true，否则有未知则为未知。例如 `all(已知true, 未知)` 和它的取反都不可通过；`any(已知false, 未知)` 和它的取反也不可通过。已知决定性分支仍可给出结果：`any(已知true, 未知)` 为 true，`not(all(已知false, 未知))` 也为 true。两次取反不会消除未知。

事实来源分为三类：可信当前玩家上下文、原始来源事件、StarChronicle 自身持久化事实。扩展方负责确保外部事实来自权威查询；玩家参数与 AI 文本均不能充当事实源。

| 键 | 来源与含义 |
|---|---|
| `player.id`、`nation.id` | 当前受信任玩家上下文；无国家时不设置 `nation.id` |
| `player.role` | StarCore 桥接或额外可信上下文提供者；身份支持何种映射以实际集成为准 |
| `nation.war`、`nation.treasury`、`resource.*`、`diplomacy.*`、`tech.stage` | 外部权威上下文；加载器支持条件键，不代表上游已经提供每一种事实 |
| `event.type`、`event.*` | 来源事件类型与它的事实；来源快照在后续选项判断中保持不变 |
| `history.TYPE` | 本地数据库中该类型的全世界历史条数，不是某国独立统计 |
| `chronicle.history.world/nation/player.TYPE` | 正史索引的世界／当前国家／明确个人参与计数；显式请求且主体已知时可为0，未知主体缺失 |
| `region.KEY` | 本地 `memory` 效果记录的逻辑世界记忆；不会自动修改区块或领地 |
| `choice.NODE` | 本实例的阶段选择；优先反映该玩家在该阶段记录的选择 |
| `story.choice.NODE` | 本实例达到法定人数后的共同决议，不受个人曾经投票的覆盖 |
| `past.player.choice.STORY/VERSION/NODE` | 已完成实例中的实际个人投票；不会继承共同胜出结果 |
| `past.resolution.world.STORY/VERSION/NODE`、`past.resolution.nation.STORY/VERSION/NODE` | 已完成实例的共同决议，分别限定WORLD或当前真实国家 |
| `player.honor`、`title.ID` | 本地荣誉与历史称号 |
| `npc.ID.reputation/trust/hostility/growth/position` | NPC目录与持久关系、全局成长职位，具体条件见NPC文档 |

国家财政、战争、科技等事实在选择时重新取当前上下文；`event.*` 仍来自原始触发事件。校验器不会证明事实提供者存在。依赖未提供事实的分支会安全地保持不可选。

上述跨剧情选择按最新提交日志序号读取，不按来源时间推测；版本与节点引用显式校验。`past.*` 支持 `eq/ne/exists`，个人、败方和未投票者有不同事实。国家／个人历史的已知零值请用 `gt "0"` 判断发生过，不用 `exists`。全部旧 `history.TYPE` 含义保留，连名为 `nation.X` 的旧事件类型也不与新命名空间混用。完整语义、NPC用法、迁移与限制见 [CONTINUOUS-NARRATIVE.md](CONTINUOUS-NARRATIVE.md)。

## 效果与稳定结果 ID

每个效果提供 `id/type/target/value`，`permission` 可选；`authority` 的权限必须显式指定且不能包含 `*`。同一效果列表中 ID 不得重复，内容约定进一步要求整条剧情内的效果 ID 唯一。已上线效果 ID 不应改名来“重试”。

| type | target | value | 实际行为 |
|---|---|---|---|
| `honor` | `player` | 整数，绝对值不大于 100000 | 为实例成员累计本地荣誉 |
| `title` | 稳定称号 ID | 称号显示名 | 保存 `title.ID`，同一 ID 的值会覆盖 |
| `reputation` | 稳定 NPC ID | 有界整数 | 累计该成员的本地 NPC 声望 |
| `trust` / `hostility` | NPC目录中存在的ID | 整数增量，绝对值≤100000 | 信任限制-1000..1000，敌对限制0..1000 |
| `growth` | NPC目录中存在的ID | 整数增量，绝对值≤100000 | 全局成长限制0..1000000，按已配置阈值选择职位，每结果只执行一次 |
| `position` | NPC目录中存在的ID | 该NPC已声明的职位ID | 设置全局职位；拒绝任意未声明职位 |
| `memory` | 地区／记忆稳定键 | 文本状态 | 保存全世界逻辑事实 `region.KEY` |
| `history` | 最多128字符的历史事件类型 | 结果描述 | 同事务生成历史／NPC反馈／因果证据，并排入匹配后续定义 |
| `narrative.start` | 已注册 StarCore 脚本 ID | 原生脚本中真实存在的起始节点ID；空值使用原生startNode | 入持久化队列，从指定原生节点启动；node.script字段则从脚本自身startNode启动，绝不复用本编排图节点ID |
| `quest.accept` | 已存在 StarCore 任务 ID | 文本上下文 | 入持久化投递队列，由现有任务服务处理 |
| `authority` | 可信权威提供者认可的操作 ID | 操作参数文本 | 提交可信提供者；默认无权修改国家状态 |

`honor/title/reputation/trust/hostility` 是 StarChronicle 的本地叙事属性，不自动兑换经济货币、物品或 StarCore 奖励。生产加载／validate／reload同时核验NPC引用和职位。`memory` 保存逻辑记忆；`/chronicle memory create` 由管理员显式绑定真实事件生成自有展示和可选见证者，详见 [WORLD-MEMORY.md](WORLD-MEMORY.md)。

节点效果在进入节点时执行；选项效果只在投票通过时执行。共享剧情中的个人效果作用于当时实例成员，世界记忆与历史效果每个结果执行一次。后加入者进入当前节点，不补发此前节点与此前选项的个人效果。

稳定结果 ID 由实例、节点或选项路径、效果 ID 和接收者生成。节点迁移、本地效果与结果回执位于同一 SQLite 事务，事件重放不会重复领取。来源事件去重不取消尚未交付的触发：重复来源可能恢复待触发实例，但不能重复已有 ScopeReceipt 或效果收据。外部调用通过 outbox 分离；调用期间崩溃的结果会标为 `UNKNOWN`，不会盲目重发。三个自有原生场景另有下述有限恢复协议；未知外部奖励仍不自动重试。见 [API.md](API.md)。

加载器在整目录解析后校验过去选择引用和跨故事依赖。激活及所有完成路径都需要的 `past.*` 引用形成硬依赖；可选分支、`NOT exists` 首次参与和独立 `ANY` 回退不会误判。历史生产者→消费该类型的故事另建边，拒绝直接／间接循环。运行时仍对旧 pinned 图组合检查因果路径，重复故事或深度32后的后续被拦截，真实结果历史保留；`/chronicle blocked` 可查诊断，不会发奖或重放外部效果。

## 复用 StarCore 脚本和任务

节点可填写 `script` 与 `quest`，复用现有 NarrativeEngine 和任务接受流程。它们在进入节点时投递，**不表示此节点自动等待脚本或任务完成**。需要任务完成门槛时，让可信集成提供完成事实或提交新的已提交事件，再由规则选择或后续剧情承接。随包三个 `chronicle_` 场景经过真实ScriptLoader成功注册后才补入起始节点；不会覆盖用户自己填写的引用。自有原生任务默认不安装，因为审计版StarCore会在模板注册前恢复进度，见 [STARCORE-CONTENT.md](STARCORE-CONTENT.md)。

自动恢复仅适用于 `chronicle_last_heir`、`chronicle_siege_embers`、`chronicle_silk_road` 三个仍由本插件持有注册对象、指纹一致且只含各自自有前缀集合式效果的场景。允许的原生效果是对应前缀的 `flag/unflag/evidence/removeEvidence/markEnding`；不能加入累加奖励、外部状态修改或其他脚本前缀后仍声称可安全重入。其 Request 保存初始原生节点、权限和原国籍，checkpoint 只捕获自有状态及已确认回读节点；恢复实际执行仍交给 StarCore，不另解释原生效果。

该原生日志采用 `READY/RUNNING/UNKNOWN/COMPLETED` 与 revision CAS，每个玩家同时只有一个调用所有者。暂时 busy、离线或明确未调用保留可等待状态；超时只有在 CAS 证明操作体尚未进入、并阻止其稍后进入时才可按未调用处理，已进入或结果不确定则隔离为 UNKNOWN。脚本改版／所有权改变不套用新脚本冒充旧 checkpoint。`/chronicle uncertain` 可查看原生隔离；`/chronicle native-ack <结果ID> CONFIRM_SCENE_COMPLETED` 只在独立对账后关闭隔离，绝不重放场景或奖励。

其他外部 native 脚本、原生任务和整个 StarCore/Folia 栈不因此获得恢复保证。Folia 上原生执行仍关闭；完整状态机、断线／重启边界和人工对账见 [API.md](API.md)。

```yaml
script: registered_script_id
quest: existing_quest_id
```

加载器会调用 `CoreGateway.hasScript/hasQuest` 检查引用是否真实存在。随包 JSON 模板默认禁用，因为模板中的 ID 并非实际存在。替换为真实已加载 ID、接好权限与权威提供者，再将后缀改为 `.json` 并运行验证。

## 三条随包剧情

| 剧情 | 作用域 | 触发来源 | 内容 |
|---|---|---|---|
| `last-heir`《帝国最后的继承人》 | WORLD | `NATIONS_MERGED`；可信 API 可发 `ANCIENT_HEIR_DISCOVERED/WORLD_EVENT` | 两人共同议会、五种参与立场、复国／主权／共同遗产三种结局 |
| `siege-embers`《围城余烬》 | NATION | `WAR_ENDED`；可信 API 可发 `BATTLE_ENDED` | 纪念、口述史、重建提案三线；财政与资源门槛示范 |
| `silk-road-scholar`《丝路学者》 | PLAYER | `TECHNOLOGY_BREAKTHROUGH`；可信 API 可发 `EXPLORATION_DISCOVERY` | 学者／探险家／商人分支、科技前置、研究／保管／制图／贸易四种结局 |

这些故事可由规则引擎独立执行，不依赖虚构任务。合并国家事件不会被写成“继承人真的出现”，战争结束也不会被写成“围城已经确认”；样例文本将提案和已发生的权威事实区分。生产服应按实际剧情内容收窄触发器。`BATTLE_ENDED`、探索发现和继承人出现并非目前桥接已经承诺提供的全部上游事件。

另有完整后续 `heir-legacy.yml`《旧都档案：每个人的那一票》：自动消费主线三种结局，为原叙事历史参与者开启个人档案。投过票、未投票和核对共同档案分别有完整结局，使用实际 `past.*` 与个人历史计数；无需重新伪造结局事件。源故事此前已在V2结束时，升级不自动倒推新后续。

丝路的 `tech.stage` 是当前权威上下文的数值时代。`TechnologyFacts` 由解锁key读取真实定义的era，再取最高 `TechnologyEra.getOrder()`，不会按技术条目数猜时代；缺失定义／不完整来源时省略。可信 `ContextContributor` 可补未提供字段。研究事件自身的 `event.tech.stage` 仍是触发时快照。原生新解锁还由完整权威快照和事务 `ObservationJournal` 记录，明确标为观察到的解锁，不冒称精确研究完成时间。

此外随包八个 `fate-*.yml` 覆盖士兵、将军、商人、学者、探险家、外交官、城主、君主，各有两种完整结局。`PLAYER_JOIN` 是插件按UUID和UTC日去重的真实到服观察；身份仅来自当前权威上下文，不取事件metadata。详见 [FATE-STORIES.md](FATE-STORIES.md)。

验证检查覆盖格式、图结构、任务／脚本存在性和效果白名单。它不检查叙事质量、不证明国家权威操作一定获批，也不模拟所有外部插件事件。
