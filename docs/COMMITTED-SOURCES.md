# 持久提交来源

Chronicle 在有界状态工作线程中分页读取配套 StarCore 的真实持久日志。每个来源每轮最多读取 100 条，包含上一条已确认回执作为恢复锚点，因此追赶时每轮最多新增 99 条。来源存储域、提交序号、操作 ID 和规范化证据持久保存；新游标与本地历史、剧情触发、贡献或贸易状态投影在同一 SQLite 事务提交。

| 来源 | 原生协议 | 历史事件与边界 |
| --- | --- | --- |
| 贸易 | `TradeHistoryService.historyAuthorityDomainId/committedChangesAfter` | `TRADE_COMPLETED/REFUNDED/DISPUTED/CANCELLED`，包含交易发生时间和原生状态提交时间；不使用发生时间游标替代提交顺序 |
| 科技 | `TechnologyService.historyAuthorityDomainId/technologyChangesAfter` | `TECHNOLOGY_UNLOCKED/REVOKED`，保留国家、科技和时代；日志没有研究者，不推断玩家 |
| 指定首都 | `NationService.authorityDomainId/capitalDesignationsAfter` | 首次为 `CAPITAL_DESIGNATED`，已有指定首都变化为 `CAPITAL_MOVED`；保留前后城市 ID 和真实操作者 |
| 国家资源交付 | `NationResourceTransactionService.authorityDomainId/transferReceiptsAfter` | `NATIONAL_RESOURCES_TRANSFERRED`，保留付款国、收款国、资源数量、类别、描述；无操作者的回执不直接授予个人贡献 |

来源缺失时保持已有游标。存储域改变、序号缺口、回执冲突、恢复后丢失锚点或插入失败均保留游标并报告错误；不得通过清空游标掩盖问题。互相独立的来源可分别追赶。原有快照观察仍明确标为 `OBSERVED`；科技解锁提交不冒充含研究者的 `TECHNOLOGY_BREAKTHROUGH` 研究完成事件。

## 贸易原生迁移

配套 StarCore 新增 `trade_commit_authority` 与 `trade_commit_log`。变更可查询的贸易状态与追加不可变快照共享原生数据库事务。相同状态和说明重放不重复追加；同一交易 ID 不能更换原始参与者、国家、资源、数量、金额、原生发生时间或订单身份。状态与说明允许通过持久变更追加。

首次迁移分页读取旧 `trade_history`，在同一事务标记为 `baseline=true`。迁移时间不是旧交易的真实提交时间。Chronicle 保存基线证据和游标，但不把它发布为新世界事件或贡献；基线交易以后恢复完成也不补造旧成就。只清理可查询旧交易不会删除不可变日志或允许交易 ID 重绑。

新快照保存原始 `Instant` 精度与空／非空说明；数据库旧 `TIMESTAMP` 的既有精度在迁移时如实保留。所有变更在进入缓存前提交，周期／停服保存不再将旧缓存状态写回。数据库备份必须包含状态表、authority 和完整提交日志。

## 贸易贡献和退款

只有有明确玩家参与的、非自己与自己交易的首次完成，才投影玩家及其原生记录中的国家贡献。同一个国家在一笔交易只计一次；贸易量使用 `resource.<资源ID>`，不与金币或其他资源相加。超出贡献账本数量／单位限制时仍记录贸易历史，事件注明 `OUTSIDE_LEDGER_QUANTITY_LIMITS`，不截断或伪造数量。

原始贡献行只追加。退款、争议和取消更新独立状态投影，`ContributionLedger.total` 只合计当前 `COMPLETED` 的贸易；公开分页查询在 `ContributionEntry.sourceStatus` 中返回当前状态。再次恢复完成重新计入原来的贡献，不能新建第二份。历史中的完成事件仍保留，历史次数不等于净贡献总额。系统不根据这些记录自动执行经济退款、奖励追回或新奖励。

国家资源交付可能同时出现在资源历史与史诗援助历史中，两者类型和证据用途明确不同；实际援助贡献由绑定操作者的史诗回执负责去重。文明生产仍使用可信 `submitContributions` 协议，未提供自动生产来源。

## 验证与恢复

单元测试覆盖迟到完成、退款再完成、重启锚点、基线抑制、变更条款冲突、原生迁移回滚和本地整页回滚。`tools/run-native-history-contract.py` 对冻结的两份 JAR 启动三个独立 JVM，调用真实原生贸易服务与 Chronicle 引擎，并独立读取两份 SQLite 核对。该工具不启动 Minecraft，不是实际玩家交易、服务器或经济插件验收。

```powershell
python tools/run-native-history-contract.py --starcore /path/to/starcore.jar --chronicle /path/to/starchronicle.jar --runtime /path/to/initialized-paper --java-home /path/to/jdk21 --output run/NEW-UNIQUE-CONTRACT-ATTEMPT
```

备份／恢复两边的原生日志及 Chronicle 数据库，保留失败 attempt 的原始日志。实际测试结果与本轮精确构件见 `ALL-STAGES-SOURCE-20261125.md`；接口和候选代码不代表全栈 Folia、第三方组合或生产经济验收完成。
