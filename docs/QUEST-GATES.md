# 原生任务完成凭证与剧情门槛

本扩展继续使用 StarCore 的任务定义、目标监听、进度、任务界面和完成路径。Chronicle 负责跨玩家的等待编排、持久绑定、完成证据消费与历史，不解释击杀、制作或收集目标，也不自动调用 `completeQuest`。

## 定义格式

```yaml
effects:
  - id: ruins-run
    type: quest.await
    target: chronicle_heir_records
    value: PLAYER
    permission: starchronicle.use
```

这是已有 Effect 结构中的新类型，不增加 StoryDefinition 字段，不改变已冻结的 canonical V1/V2 编码。保存实例继续使用各自固定的完整定义、版本和指纹。

- 只能放在非终结节点的进入效果中。一个节点可以声明多项门槛，必须全部满足。
- `value` 只能是精确的 `PLAYER` 或 `NATION`。国家门槛不能放在 PLAYER 范围的剧情。
- `target` 必须通过实际原生任务存在性验证。同节点不得同时用 `node.quest` 或 `quest.accept` 投递同一任务；已决选择效果中不能追加等待门槛。
- 没有完成凭证时隐藏选择，直接 API 选择和国家投票也会再次检查。完成任务不自动作出剧情选择、不自动投票、不授予国家席位。
- 三份完整可选文件位于 `examples/quest-evidence/`，不覆盖默认文件或旧实例。

## 稳定运行与国家归属

每个固定实例、节点、effect ID 和原成员 UUID 产生一个稳定 gate/binding ID。成员、原国籍、来源事件、剧情版本、定义指纹、打开时间均保存。首次 native 调用前，先在 Chronicle 独立事务中固化 provider ID 和 pin 时间。该事务结束后才调用提供者。

PLAYER 模式要求该成员自己的绑定运行完成。NATION 模式允许同一节点任一原成员的绑定贡献使本国准备完成，但接取及完成时都必须具有 KNOWN_NATION 证明，且等于实例保存的原国籍。UNKNOWN/UNAVAILABLE 不能当作“无国家”；无原国籍的国家门槛保持等待，不固化提供者、不接取任务。同国十名成员完成仍只是一国准备完成，国家议事的一国一席与当前持有人权限独立验证。

贡献者之后离线或换国不会抹去已经真实完成的历史。当前请求投票／查看的成员仍必须保持实例原国籍。普通成员的任务贡献不会让他取得君主权限。PLAYER 门槛摘要只对本人显示，国家门槛摘要只向同原国的合法成员显示。

原生运行必须晚于 provider pin，国家接取证明在 pin 与原生开始之间；完成证明在开始与业务完成之间；完成提交确认不早于业务完成，且不晚于本次可信查询。所有比较统一到毫秒。旧运行、曾经完成标志、目标就绪、接取成功和聊天文本均不是完成凭证。

## 可信 SPI 与存储域

`api.quest.QuestEvidenceProvider` 只暴露：

```java
String providerId();
CompletionStage<QuestObservation> ensureStarted(QuestRequest request);
CompletionStage<QuestObservation> lookup(QuestRequest request);
```

通过 Bukkit ServicesManager 注册可信服务。提供者必须对稳定 binding 幂等接取；lookup 必须是其自有持久权威数据的纯查询，不能接取或完成任务。玩家命令、AI 或客户端不能提交 AcceptedRun/CommittedCompletion 充当证明。

StarCore 适配器反射连接实际 `quest.evidence.QuestEvidenceService` 协议 1，不要求把整个 StarCore 打进 Chronicle。提供者 ID 是 `starcore.quest.evidence.v1:<storageAuthorityId>`；后缀是原生存储域的持久 UUID，不是路径或每次开服随机数。新服务实例可以恢复同一存储域，替换为空库或另一个目录则必须产生不同域。缺失、损坏或有歧义的既有域身份文件会保留原件并 fail closed，不能自动生成新身份覆盖旧运行。

一个 gate 固化后的 provider ID 永不自动切换。服务注销立即撤销运行中 lease，旧回包不能提交；重注册后须重新查同一权威存储。重复 ID 的多个服务会被隔离。适配器还缓存首次确认的原生域，拒绝同一适配器静默更换域。固定的 receipt issuer 保持 `starcore.quest.evidence.v1`，不会把一次重启伪装为另一种来源。

## 原生提交与奖励边界

上游原版审计没有稳定的持久任务运行 ID、完成提交凭证和可靠后注册模板恢复。候选补丁及精确源码基线、可应用 patch、测试范围见 `patches/quest-evidence/`；未部署该补丁的上游不能靠适配接口名获得凭证能力。

候选原生完成顺序为：持久 PREPARED 守卫 → 原生目标／运行再校验 → 原生任务完成状态及 saveConfirmed ack → 不可变 completion journal 写入并 force → 对外公开 COMPLETED 凭证 → 单次守卫下调用原生奖励路径。

`completedAt` 是业务完成时间；`committedAt` 在原生任务状态持久 ack 之后采样，并写进随后强制落盘的不可变 journal。它不是预知自己文件 force 返回的时间戳；Chronicle 只能在后一确认完成之后读到该凭证。接受证据和完成证据为不同的不可变字段，后续奖励诊断不能改写接受身份。

奖励的 INVOKED 只表示原生方法返回，不宣称外部经济、命令或库存已端到端 exactly once 交付。PREPARED/UNKNOWN、执行中断或丢失确认都不自动重放。Chronicle 消费任务凭证不会再次执行原生奖励；自己的剧情结果继续使用原有稳定 outcome ID/outbox。

已确认的原生过期和每日重置会记录为 `FAILED`／`ABANDONED`，继续保留原始接受身份且不满足剧情门槛。当前协议没有为同一 gate 开始新一轮任务的管理API；原生 `UNKNOWN` 也没有独立对账API。两类状态都安全阻塞，不能通过更换 provider 或手工编辑存档重试。要支持恢复重试，需要后续为 attempt ID 和原子对账增加版本化协议。

## 本地原子消费和世界历史

schema 6 仅新增 `quest_gates`、`quest_gate_completions` 及索引／约束，不重编码旧剧情 JSON 或删除旧历史、选择和奖励凭据。完成 source 同时约束 `(issuer, completion_id)` 与 `(issuer, run_id)`，不能一份原生运行满足两个不同绑定。

接受身份一旦保存不可改变。完整完成 JSON、hash、消费键、gate 完成状态、`quest.completed` 世界历史、因果来源与后续触发入箱共享同一 Chronicle 事务。任一冲突或插入故障都整体回滚。消费 source 已存在但 gate 未完成属于原子状态损坏，会拒绝继续，不能静默“修好”gate 而跳过历史。已结束实例或旧节点的迟到回执不会改写剧情结果。

历史 ID 由 issuer 和 completion ID 稳定派生；时间取原生业务完成时间，参与者是实际完成玩家；国家只取完成凭证的已知国家证明，不按查询时的国籍倒推。历史保留 quest/run/completion/定义/start/commit/gate 字段及 receipt hash，本地账本保留完整证明。证据正文不会把超长原生 JSON 塞进历史事件的 4096 字符字段。

## 线程、容量与恢复

SQL 与规则操作使用现有有界状态工作队列。提供者调用使用独立两线程、有界队列，默认最多八个 gate flight；同一 gate 不并发重入，快照和提供者等待均有超时。provider 方法不在 Chronicle SQL 锁、国家权威 monitor 或区域线程中调用。身份与权限由玩家 owning scheduler 获取，接取前再校验。

轮询先过滤当前活动节点，再应用上限；离线且尚未固化提供者的成员不会挤占在线接取；已固化的离线成员可以执行纯 canonical lookup，让其真实完成贡献恢复同国准备状态。按 last_checked/updated/id 轮转。只有 canonical NOT_STARTED 且从未保存接受身份，才可再次以同 binding 进行幂等接取；UNKNOWN、冲突、目标就绪均不能授权重新接取。

关闭插件先撤销提供者 lease 和新调用，状态队列排空后关闭 SQLite。实际 StarCore 候选仍只支持 Paper 主线程所有权；Folia 上不注册该原生完成提供者。Chronicle 独立模式可以持久等待并展示缺少能力的原因，不能把独立模式通过称为 StarCore 全栈 Folia 认证。

## 查询与运维

`ChronicleService.questGateState(instanceId, playerId)` 获取服务端新身份，并返回固定实例／节点的只读摘要（ready、被阻挡的 effect ID、任务、成员、原国籍、状态、provider/run/completion ID 和诊断）。不接受 caller supplied PlayerContext，不暴露完整 proof，也不提供手动完成、改库解锁或补发奖励入口。剧情 GUI 的任务门槛按钮、列表和详情每次重新读取服务端，拒绝把旧节点文本与新阶段状态拼接。

备份时将 Chronicle 数据库与 StarCore 的存储域文件、tracked index、原生任务状态及 completion journal 一起保存。不要只还原一边或删除 UNKNOWN 凭据来强行重试。确需修复不一致时，应先保存原始文件并独立核对原生运行和奖励，不能以替换 provider ID、数据库编辑或“曾经完成”标志绕过证据。

本文是协议和运行边界说明，验收结论必须以对应源码、JAR 指纹和真实构建／服务器证据为准；不把候选实现、接口存在或旧版本报告当作本版本验收结果。
