# 国家史诗真实战略链契约

`last-heir-strategy/1` 将报名、任务、援助、外交和最终路线绑定到同一持久战役。旧版 `last-heir/1` 保留叙事提案语义。入口是 `ChronicleService` 和 `/chronicle epic`；安装与操作见 [内容包说明](../examples/national-epic/last-heir-strategy/README.md)。

## 状态与执行

会期由当前原生国家持有人登记一国一席，至少两国才能冻结。冻结时在原生身份锁内确认救济国及报名国仍存在；救济国不能报名自己。计划在开放会期时保存快照，重载只影响新会期。冻结事件创建匹配版本的 WORLD/NATION 剧情实例。中断后重试 `seal` 可恢复创建，不重复源事件。

所有冻结国家的准备凭证齐全后才接受路线表决；一国一票，严格多数通过即锁定路线。剧情议决仍使用 `/chronicle vote`，路线选择使用 `/chronicle epic route`。

`EpicOperation` 固定 campaign、instance、source event、phase、类型、actor、国家、目标、原生参数、剧情 SHA-256、权限和请求时间。稳定 ID 对同一实例、阶段、动作和国家去重，重复 prepare 保留原时间，不能覆盖原 provider/domain。

1. prepare 检查身份、权限、冻结计划与 provider 能力，持久保存 PREPARED 和 provider/domain。
2. apply 用修订号 CAS 把操作保存为 UNKNOWN，再调用原生服务。并发提交只能有一次占位成功。
3. 来自仍有效 provider 且匹配固定字段的持久回执才能成为 COMMITTED。超时、异常或服务注销保持待对账。
4. UNKNOWN 只接受 reconcile 的只读 lookup；即使 ABSENT 也不重新扣款或变更国家。重启不重开执行入口。
5. 仅 RESOURCE_AID 的最新 REJECTED 意图可经显式 retry 追加下一轮 PREPARED。旧行、旧修订号及历史证明不重写；根操作 ID、单调轮次、唯一前驱与完整拒绝证明在同一 SQLite 事务中保存。新操作／原生 UUID 不复用，完成 retry 不调用资源转账，之后仍须显式 apply。

回执唯一键为 authority domain、操作类型、原生 receipt ID，不能重复用于两个意图。宣战和战争结束是不同证据类型，可引用同一 war ID。

重试依赖 StarCore `transferRejection(transactionId)` 的持久永久栅栏；它必须固定原事务 ID、存储域、双方、规范化数量、类别、描述、拒绝时刻及唯一证明 ID，并在存储层与提交回执互斥。被栅栏固定的原 ID 永远不能转账，即使余额后来充足、普通快照保存、SQL／文件迁移或服务重启。保存确认不明先保持 UNKNOWN，只有恢复后的权威读取才能确认实际保存的拒绝证明，不能将提交异常、缺少回执或历史 REJECTED 文本补造成证明。

provider 的默认 `rejectionProof` 返回空；内置适配器仅为 RESOURCE_AID 验证原生完整 payload 和域，其他类型／旧接口没有重试资格。异步证明完成后再次检查当前 actor／国家、固定权限、原生在任代表、provider/domain 租约、当前阶段和预期修订号；并发 retry 只能追加一个后继。已有后继的拒绝祖先不能被迟到 COMMITTED／UNKNOWN 响应改写。此接口不接受玩家提交的证明。

## 原生证据

| 类型 | provider ID | 验证内容 |
|---|---|---|
| QUEST_GATE | `starcore.quest.evidence.v1:<storageUUID>` | 已提交 NATION quest.await，actor、国家、任务及 node/effect |
| TREASURY_AID | `starcore.treasury` | 交易 ID、双方、金额、类别、描述、存储域及时间 |
| RESOURCE_AID | `starcore.epic.resource_aid` | transferReceipt 的 ID、双方、数量、类别、描述、存储域及时间 |
| DIPLOMACY_INVITE / ACCEPT | `starcore.epic.diplomacy` | 操作 ID、actor、双方、域；接受引用本战役的已提交邀请 |
| NATION_FOUNDING | `starcore.epic.nation_founding` | 操作 ID、新国家 UUID、建国者、新国名、域及时间 |
| WAR_COMMIT / FINAL_BATTLE | `starcore.epic.war` | 宣战回执、warCompletion(warId) 持久结束回执、相同战争及双方 |
| DUNGEON_COMPLETION | `starcore.epic.dungeon_completion` | getCompletionRecord(recordId)，成功、玩家、国家、副本、域及时间 |

各模块固定自己的 authority domain。资源 transfer 返回的结果包装不是回执；可变 getWar 对象和玩家历史列表也不构成持久完成证据。内置原生适配器仅在 Paper 注册。

任务时间下界是对应门槛打开时间；最终只读证据下界是路线批准时间，允许完成真实活动后登记记录 ID。其他操作以 prepare 时间为下界。

建国参数为 `NATION_FOUNDING_V1:<base64url UTF-8 国名>`，由 `EpicContract.foundingReference(name)` 或 `epic founding-reference <新国名>` 生成。原生 foundNation 的参数是 operation ID、新国 ID、founder UUID、当前玩家名、新国名，没有 culture 参数。提名者先以报名国代表身份 prepare，再通过原生治理交接／退国，随后由同一无国籍玩家 apply。Chronicle 不自动退国。建国后可用新国家身份只读对账；宣战仍检查新国家现任持有人和原生新国保护规则。

## 同事务结算

只有账本 COMPLETE 后的剧情终点历史附带 `epic.final.canonical=true`、campaign、instance、route 和回执摘要。结算历史、剧情完成、世界记忆与后续触发收件箱在同一 SQLite 事务中提交，保留报名事件的因果祖先；外部 submit 不能自行声明史诗完成。

aftermath 要求 war-await 或 dungeon-await 的真实 settle 决议，以及 canonical 事件，不引用没有投票的终点节点。重启从持久收件箱恢复，不扫描全部已结束剧情补造事件。

`verifyEpicReceipt` 仅检查 DTO 字段一致性，不查询原生服务，也不授予提交权限。集成应使用 prepare/apply/reconcile。

联合工作树的 StarCore 候选源码包含副本直接回执和战争持久结束查询；旧 `codex-starcore-dungeon-epic-1795325043299` JAR 没有这两个完整接口。缺失能力时 prepare 拒绝绑定。候选构件和验证范围见 [本轮测试记录](NATIONAL-EPIC-TEST-REPORT.md)；上游发布、最终双插件实服验收不能由单元测试替代。
