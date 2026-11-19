# 国家议事与一国一席表决

## 状态与适用范围

本模块为共享剧情增加正式的国家票。国家、世界剧情的成员仍然是玩家，但票的所有者是国家；同一国家在同一实例、同一节点只有一席。代表的提交行为、该国的正式立场、全局共同决议和玩家个人意见分别记录。

**本轮国家投票尚未完成真实 Paper/Folia 服务器验收。** 单元测试、SQLite 测试或模拟 Bukkit 测试不等于真实服务器联调。此前已交付构件的 Paper/Folia 证据不认证这一新增模块，也不证明上游 StarCore 可在 Folia 完整运行。具体构建及自动化测试结果以当前分支的最终测试报告为准。

模块不增加命令执行器，不根据剧情文本授予权限，也不直接修改国家、外交、领土、国库或资源。国家票通过不会自动完成这些原生操作。

## 配置与完整示例

`examples/national-council/` 中的文件是可选示例，不在插件默认安装列表中：

| 文件 | 用途 |
| --- | --- |
| `council.yml` | `national-relief-council/1`；两个国家表决阶段，均为两个国家对同一选项达成法定席数 |
| `aftermath.yml` | `national-relief-citizen-record/1`；个人阅读任务，读取当前所属国家在上述两个阶段实际留下的票 |

将两份文件一起复制到测试服的 `plugins/StarChronicle/stories/`，再执行 `/chronicle validate`。验证通过后才使用 `/chronicle reload` 启用。不要把同一故事不同版本的文件同时作为两个当前定义安装；现有实例保留自己的固定定义，已归档的精确版本用于历史读取。

国家议事的关键字段是：

```yaml
scope: WORLD
voteUnit: NATION
```

未写 `voteUnit` 的旧内容继续采用 `PLAYER`。`NATION` 票不能用于 `PLAYER` scope。`scope: NATION` 只覆盖一个国家，因此其节点的国家席数不能大于 `1`；多个国家共同议事使用 `WORLD`。示例的 `mandate` 与 `charter` 是两个独立投票节点，均设置 `quorum: 2`。

这里的 quorum 是**同一选项的国家席数门槛**。两个国家各投不同选项不会因为总票数达到二而推进；这不是自动多数制，也不是按在线人数计算。国家灭亡后，门槛不会自动降低。剩余国家不足门槛时，阶段可能等待后续处理，不能伪造票或修改已冻结决议来让其通过。

示例只使用 `history`、`memory` 两类本地叙事效果。`memory` 写入的是编年史自己的 `region.*` 叙事事实，不代表原生领土、库存或世界方块已经改变。示例没有原生任务或脚本依赖，也不发放物品、金钱或个人荣誉。

### 真实来源与选民范围

`REGIONAL_RELIEF_COUNCIL_OPENED` 是示例约定的来源类型，不是已存在的 StarCore 自动事件。可信服务器适配器必须在真实业务提交之后发布它。事件 ID 应从上游已提交记录生成，在重试与重启后保持不变；不能每次重放都生成新 UUID。

可信适配器提交 `WorldEvent` 时，`participants` 记录真实参与者，`nations` 填入本次有席位的原生国家 UUID 集合，`source`、`evidence` 保留来源及证据。玩家命令、NPC 文本或 AI 输出不得自行声明国籍、代表身份或新的事件证据。

国家席位资格读取该实例保存的原始来源：

- 来源的 `nations` 非空：只有其中的国家有席位；后来新增国家不会自动进入旧事件的选民集合。
- 来源的 `nations` 为空：仅在可信来源明确设置 `facts: {audience: GLOBAL}` 时开放全局国家席位。
- 普通玩家可能是事件参与者或剧情成员，但这不会产生国家代表权。

示例建议使用明确、非空的国家集合。适配器应通过 `ChronicleService.submit(WorldEvent)` 提交；它负责历史、持久触发与去重。`/chronicle evaluate <已保存事件ID> <剧情ID> <精确版本>` 只登记已有来源，不创建或伪造来源，也不直接投票。

## 玩家命令与菜单

所有玩家入口均要求 `starchronicle.use`。国家表决还需要当前真实国家持有人通过原生权威核验。`starchronicle.admin`、OP、`starchronicle.identity.monarch`、职业、称号、自定义 rank 或普通国家权限不是国家票凭证。

| 命令 | 行为 |
| --- | --- |
| `/chronicle stories` | 打开剧情列表；国家票实例进入国家议会页面 |
| `/chronicle join <instance>` | 按当前身份、前置条件与实例范围申请加入；加入本身不授予国家席位 |
| `/chronicle national <instance>` | 查看自己已加入实例的国家票、资格与票数投影 |
| `/chronicle vote <instance> <node> <revision> <choice>` | 使用页面提供的节点、国家席位修订号提交或修改立场 |
| `/chronicle withdraw <instance> <node> <revision>` | 使用相同 token 撤回尚未冻结的本国票 |
| `/chronicle past <story/version/node>` | 查询个人选择、本国国家立场、世界决议及国家 scope 决议中存在的记录 |

命令不接受国籍、国家持有人 UUID、职业或国家权限参数。控制台不能通过这些命令代替某个玩家提交国家票。旧 `/chronicle choose` 与旧 `choose(...)` API 用于个人票，遇到 `voteUnit: NATION` 会明确拒绝，不会自动读取最新修订号代投。

菜单展示一国一席、法定席数、当前真实代表资格、该国修订号、共同决议及各国已记录票的修订号、状态、提交者、核验时间与来源证据。已加入成员可以只读旁观；没有代表权时不显示可用投票或撤回按钮。

按钮绑定构建该页面时取得的节点和修订号。菜单在选择条件查询前后两次核对国家状态，避免把旧节点文本与新节点选项组合起来。后端仍会执行最终校验；过期请求被拒绝后重新刷新，不会偷偷换成新 token 重试。菜单会取消 shift、数字键、拖拽及底部背包转移等操作，只有该会话所有者的顶部普通左键可触发绑定动作。

## 修订号、改票、撤回与冻结

CAS token 由 `expectedNode` 与当前国家在该节点的 `expectedRevision` 组成。修订号属于 `(instance, node, nation)`，不是玩家自己的计数，也不是整个议会的全局计数。

尚无本国记录时，页面返回 `viewerRevision = 0`。例如在未达到法定席数前：

| 操作 | 请求使用的修订号 | 成功后的修订号及状态 |
| --- | --- | --- |
| 第一次提交 | `0` | `1 / ACTIVE` |
| 改投另一选项 | `1` | `2 / ACTIVE` |
| 撤回 | `2` | `3 / WITHDRAWN` |
| 撤回后重新提交 | `3` | `4 / ACTIVE` |

每次使用后均重新读取页面，不要据此表在客户端自行递增。重复提交相同立场，在 token 仍为当前版本时可以是无变更结果；使用旧修订号，即使选项相同也会被拒绝。撤回保留墓碑及追加历史，不删除记录；撤回不要求旧选项条件仍成立，但仍要求当前原生代表权和有效实例资格。

国家继承人取得真实持有人职位后可在未冻结阶段修改该国已有席位，不会产生第二席。旧持有人失去代表资格。同一玩家在同一实例节点已经作为某国行为主体后，不能靠转国再替另一国累计第二席；实例中的已加入国籍也会复核。国籍变化不会重写旧席位的国家归属。

下一次获授权的投票或撤回会在同一原生权威范围内核验已记录国家的存续。确认灭亡的未冻结国家票可追加 `INVALIDATE` 动作并投影为 `INVALIDATED`。权威服务不可用、响应异常或读取失败必须导致失败，不能当成“国家已灭亡”。

`counts` 是 `ACTIVE` 记录的只读投影，可能包含自上次合法变更以来已经消失的国家；读页面不会为了修正数字而写库。正式落定以前重新核验各国存续，保留这次真正计入 quorum 的国家、修订号、选项、行为主体及权威证据。已冻结决议不会随日后国家灭亡或持有人变化重新计票。

每个实例节点至多冻结一份决议。旧节点、已冻结节点及已完成实例不能再改票或撤回。国家票追加记录、最新投影、冻结证据、剧情推进及本地效果在一个 SQLite 事务中提交；失败时一起回滚。国家表决不会为代表或全体成员伪造个人 `BALLOT`。

## 跨剧情历史读取

精确引用格式为：

```yaml
condition:
  fact: past.nation.choice.national-relief-council/1/charter
  op: eq
  value: public-ledger
```

该投影从**当前真实国籍**查找该国在已完成实例中最新的有效国家行为。原生持有人已经更换时，国家立场仍属于同一国家；玩家转国后，读取的是新国家的历史，不把前国家的票搬给新国家。未完成、未投票、已撤回或已失效的记录不被补写成某个选项。与多数结果不同但仍保留的有效国家票，不会被胜出选项覆盖。

`aftermath.yml` 同时要求 `mandate` 与 `charter` 的国家立场存在，然后按本国 `charter` 票显示阅读分支。它不奖励“同意”，也不把阅读行为等同于政治支持。个人任务使用 `voteUnit: PLAYER`，它自己的选择与前面的国家票仍是不同记录。

三个相关命名空间的含义不同：

| 引用 | 含义 |
| --- | --- |
| `past.nation.choice.<story>/<version>/<node>` | 当前国家在国家票剧情中保留的实际立场 |
| `past.player.choice.<story>/<version>/<node>` | 个人票剧情中该玩家真实投出的个人票 |
| `past.resolution.world.<story>/<version>/<node>` | `WORLD` 实例最终共同决议，可能与本国票不同 |

`past.resolution.nation.*` 对应 `scope: NATION` 实例的共同结果，不是所有国家票的别名。所有 `past.*` 引用都必须包含精确版本与节点，支持 `eq`、`ne`、`exists`。配置验证会检查引用故事的投票单位、scope、节点、选项及依赖循环。

## 可信插件 API

从 Bukkit `ServicesManager` 获取 `ChronicleService`。服务自行采集真实玩家上下文；调用者不能传入 `PlayerContext`、声称的国籍或代表证明。

```java
CompletionStage<ProcessReport> nationalVote(
    String instanceId, UUID playerId, String expectedNode,
    long expectedRevision, String choiceId);

CompletionStage<ProcessReport> withdrawNationalVote(
    String instanceId, UUID playerId, String expectedNode,
    long expectedRevision);

CompletionStage<NationalVoteState> nationalState(
    String instanceId, UUID playerId);
```

`NationalVoteState` 包含 `instanceId`、`nodeId`、`quorum`、`resolution`、`completed`、`viewerNationId`、`viewerRevision`、`representative`、`canVote`、`canWithdraw`、`message`、`counts` 和 `ballots`。每份 `Ballot` 包含 `nationId`、`revision`、`choiceId`、`status`、`actorId`、`checkedAt`、`authorityEvidence`。

`representative` 仅说明这次读取核验到了真实国家持有人；它不等于可以对任意事件投票。`canVote`、`canWithdraw` 还受成员、来源国家范围、节点和分支条件限制，提交时仍复核。完成后的 `nodeId` 可以指向最后一个冻结的表决节点，而实例当前节点已是终局；自定义 GUI 不应把这两个节点混淆。

读取后再提交的基本流程：

```java
chronicle.nationalState(instanceId, playerId).thenCompose(state -> {
    if (!state.canVote()) {
        return CompletableFuture.failedFuture(
            new SecurityException(state.message()));
    }
    return chronicle.nationalVote(
        instanceId, playerId, state.nodeId(), state.viewerRevision(), choiceId);
});
```

这只是可信插件的 API 用法。异步返回后如果需要操作 Bukkit 玩家或 GUI，应再调度到该玩家的拥有线程；不要在回调中阻塞 `.join()` 等待主线程任务。若提交被拒绝，获取新状态并向用户重新展示，不要自动使用新修订号重投。

`recollections(playerId, references)` 可以读取上述精确 `past.nation.choice.*`。它仍从服务取得当前真实身份，不接受调用者提供的国家。AI 文本提供者只提供文字，不能注册为代表权、提交国家票或触发效果。

## 代表权服务与 StarCore 边界

生产默认使用 `StarCoreNationalVoteAuthority(StarCoreBridge)`。它只接受已审计的真实 `dev.starcore.starcore.module.nation.NationModule`，读取当前 `nationOf`、`nationById` 与 `isFounder`。这里的 `isFounder` 在该实现中表示**当前国家持有人**，不是永远拥有特权的历史建国者。

桥接会检查涉及建国、加入、退出、继承、分裂与解散的原生方法仍使用同一个同步 monitor。上游实现或锁约定改变、服务缺失、国籍错误或持有人校验失败时拒绝授权；不根据类名近似、缓存 rank、OP 或普通 `Nation.hasPermission` 猜测资格。每次请求重新解析原生服务，不把启动时的身份当永久授权。

服务器可通过可信 Bukkit 服务扩展 `NationalVoteAuthority`：

```java
getServer().getServicesManager().register(
    NationalVoteAuthority.class, trustedNativeAuthority,
    this, ServicePriority.Normal);
```

内置适配器注册为 `Lowest`，较高优先级的可信提供者优先。不要依赖多个外部提供者相同优先级时的选择顺序。注册和注销事件在原生回调外更新 volatile 提供者；注销时先切换为拒绝，再剔除该提供者并选择仍注册的服务。关闭扩展时先使自身授权失效，再按 Bukkit 生命周期注销。已开始的并发调用也必须由提供者自身的生命周期与原生锁保证失败关闭。

权威接口的关键签名是：

```java
<T> T withRepresentative(
    UUID actorId, String expectedNationId, Authorized<T> operation)
    throws Exception;
```

提供者必须满足以下契约：

1. 在完整同步回调期间串行化原生国籍、持有人变化和国家解散；锁顺序为原生国家权威，再到编年史存储。
2. 在调用线程上同步、恰好调用一次回调；不可异步调用、跨线程调用、保存 `View` 后使用，或吞掉回调失败。
3. 返回真实 `actorId`、`nationId`、`checkedAt`、`issuer`、`evidence`；`nationExists` 必须来自同一有效权威范围。
4. 服务失效或读取异常时抛错；只有权威明确确认不存在才返回 `false`。
5. 锁内只进行必要原生身份读取和本地 SQLite 转移；不访问 Bukkit 实体，不查 Bukkit 注册表，不等待 future，不发奖励，不执行命令或修改原生国家。

本地投票事务完成、释放原生回调之后才处理效果 outbox。`NationalVoteAuthority` 与用于执行原生效果的 `AuthorityProvider` 是不同接口。现有内置原生效果适配器只支持经过权限复核的 `diplomacy.invite` 联盟邀请，收方仍需在 StarCore 接受；国家票通过并不构成这项权限，也不代表战争、领土、资源或财政变更已经实施。外部效果出现 `UNKNOWN` 仍须对账，不自动重放未知结果。

在没有 StarCore 或其他有效原生权威服务的 Folia/独立模式中，国家投票与撤回默认拒绝；已加入实例的成员仍可读取已记录票。StarCore 原生 NarrativeEngine 的既有 Folia 限制仍然存在，本模块没有解除该限制，也没有认证第三方 NPC 或完整上游国家模块的 Folia 兼容性。

## 持久化与验证范围

schema 5 的国家票使用追加行为日志、最新票投影、每节点行为主体绑定和冻结决议表。原有个人票和旧实例不被改写为国家票。版本目录保存明确的投票单位；改变同一版本的既有内容会触发版本冲突，应发布新版本而不是改写历史。

本目录示例附带 `NationalCouncilExampleTest`，用于通过真实 `ConfigLoader` 校验两份完整文件、两个国家阶段、跨故事精确国家选择引用及安全效果边界。它不启动 Bukkit、Paper、Folia 或 StarCore，不证明真实原生持有人、线程调度、多人 GUI 或服务卸载已经在服务器上验收。

发布前仍需以候选构件在真实测试服复核两阶段投票、继承后改票、撤回重启、国家解散、同节点转国防双席、过期菜单拒绝、非代表旁观以及授权服务注销。不得将旧构件的验收报告改名作为本轮结果。
