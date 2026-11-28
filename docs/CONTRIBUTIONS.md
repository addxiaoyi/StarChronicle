# 持久贡献账本

`/chronicle contributions [player|nation] [页]` 查询本人或当前国家的记录，单独页码等同本人查询，页码从 1 开始。每行列出类别、实际数量、单位、原生提交时间和来源历史。Java API `contributions(subjectId,type,page,size)` 支持可选主体／类别及最多 100 项分页，页码从 0 开始。

国家史诗的 `TREASURY_AID` 和 `RESOURCE_AID` 只有在提供者返回匹配的持久完成回执后才记账。史诗操作完成、阶段推进、来源历史、国家与实际操作者贡献在同一 SQLite 事务提交；UNKNOWN、拒绝、报名、投票和准备请求均不产生贡献。国库单位为 `treasury`，资源单位为 `resource.<资源ID>`，不同单位不相加。

每份原生交付按存储域、原生回执 ID、贡献类别、单位和主体持久去重。直接援助与史诗适配器观察到同一原生回执时保留首次账本证据，不重复计账；已有直接援助记录在被真实回执重新核对时建立绑定。数量、主体或提交时间冲突会拒绝整个本地事务。不能删除贡献或奖励回执来允许重放。

同步 GitHub 后兼容上游不带 `resource.` 前缀的旧单位和按操作编号保存的贡献，保留原始行与历史文本。原生回执绑定和 `epic_contribution_projections` 标记同事务建立，恢复每次最多处理 100 个已提交操作，只有缺失主体才补录。公开 `contributionTotal` 将 RESOURCE_AID 的新旧资源单位作为别名合计；底层 `ContributionLedger.total` 仍按精确单位查询。两种名称不是不同资源，国库与其他资源继续分别统计。

可信生产插件可使用 `submitContributions(WorldEvent,List<ContributionRequest>)` 同事务提交真实贸易／文明生产事件与贡献。每项请求必须绑定来源事件 ID、发生时间、真实参与者及关联国家；主体只能是该操作者或其已证明的国家。该 API 不执行交易或生产，也不接受玩家命令、AI 文本或未交付意图作为证明。来源系统负责持久化其原生完成证据并在崩溃后重投。

当前 StarCore 国家史诗援助、原生贸易提交日志与国家即时加工日志已接入本流程。贸易使用配套 StarCore 新增的 `committedChangesAfter`，包含迟到完成及退款状态变化；旧 `getRecordsAfter(executedAt,recordId,limit)` 不作为无损提交源。交易贡献保留原始行，退款／争议／取消从有效合计排除，查询返回 `sourceStatus`。具体基线、双主体归属及恢复规则见 [持久提交来源](COMMITTED-SOURCES.md)。

国家即时加工在实际投入、产出与原生回执同事务完成后产生 `CIVILIZATION` 贡献，按每种产出分别计量；再次用作投入不会改写过去的生产记录，生产累计量不同于库存余额。没有实际操作者的系统加工仅记国家历史，不推断国王或在线国民为操作者。旧定时工厂和第三方工厂仍不属于该自动来源。

`ContributionFlowTest` 验证真实 SQLite 重启重放、双主体投影、错误数量导致整笔事务回滚、可信来源绑定以及旧记录的原生回执去重。扩展实服工具通过 `prepare-epic-paper.py prepare --extended ...` 验证四笔真实援助的八项投影；必须实际跑完三次启动及独立检查后才可认定该构件验收通过。
