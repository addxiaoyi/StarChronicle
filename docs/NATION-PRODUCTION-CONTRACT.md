# 国家生产批次与文明贡献合同

本增量新增国家库存生产批次：真实当前创始人开工，原料扣除和不可变批次同一 SQL 事务提交；到期领取时，固定成品和唯一完工回执同一 SQL 事务提交；取消则精确退还原料并追加取消记录。Chronicle 只消费这个原生完工来源。阶段进度以验收报告中的精确构件和现场为准。

## 玩家入口与固定条款

`/nationproduction recipes|capacity|start|list|show|complete|cancel` 需要 `starcore.nationproduction.use`；资产操作还核验真实当前国家、成员和创始人。控制台不能代替玩家。`start <配方> <批数> [原批次ID]` 在请求前显示批次 ID，结果不明时可用同一 ID 查询／重试。服务拒绝同 ID 更换原操作者、配方或批数。

默认配方使用实际国家资源类型，每批工业精炼消耗 mineral3＋energy1、产出 industrial2、30秒；化工合成消耗 agricultural2＋energy1、产出 chemical2、45秒；战略材料消耗 industrial3＋chemical2＋energy1、产出 strategic1、60秒。默认每次最多1000批，数量精确整数，等待时间随批数相乘。新配置合同见[生产配方与容量](PRODUCTION-CATALOG.md)。

批次保存 runId、原操作者、原国家、资源资产域、配方ID、批数、完整输入／输出及开工／可领取时间。投入已扣除，领取不再读取当前配方或套用 `grant` 的国策加成；取消也不会额外获得加成。产物属于原国家库存，没有玩家背包交付、金币收费或额外奖励。当前创始人可领取／取消本国原批次；交接不改变原操作者，原操作者失去权限后只能查看原记录和核对已处理请求。灭国或缺少合法创始人的未完成批次保持待核对。

## 原生持久状态

只有 SQL 后端与 Paper 主线程支持变更；文件后端、错误线程和 Folia 拒绝。`starcore_nation_production_events` 追加 RUNNING→COMPLETED 或 RUNNING→CANCELLED；不得再次变更终态。独立 head、迁移标记、完整事件序列、完工序列、每项摘要及前缀摘要约束日志连续性。删除完整末尾、开工行、head、替换条款或状态均拒绝读取／继续普通资源保存，不能从剩余 MAX(sequence) 建立新权威。

每次生产转换持有资源模块锁，经同一存储队列写入库存、全资源 revision、生产事件与 head，再发布内存库存。旧资源快照不能覆盖新生产。提交结果不明则完整重读资源；重读失败阻止普通保存，需恢复存储后重启核对。不会用逐项 consume／grant 补偿猜测提交结果。

这是两次有序原子转换：开工投入与待完成批次共同持久，完工交付与回执共同持久。等待期间服务器无需运行，玩家重启后明确领取。新开工配方与国家运行上限现由严格 JSON 配置；SQL 事务内再次复核容量。没有自动替玩家开工／领取、工厂建筑容量或等级加速。

旧 `ProcessingService.startProcessing/completeProcessing/autoProcess` 缺少操作者、稳定批次及共同提交，已拒绝资产变更；旧工厂与配方保留供查看，定时刷新不结算。升级不能证明旧工厂原料是否完整扣除，因此不自动退款、交付或转换为新批次。旧接口调用方须改用新服务；历史记录需要人工核对，这是一项兼容性边界。

## Chronicle 来源与贡献

- 来源键：`starcore.nation-production.completions.v1`。
- 原生服务：`StarCore.NationProductionService`。
- 事件类型：`NATIONAL_PRODUCTION_COMMITTED`；ID 为 `starcore:nation-production:<domain>:<sequence>`。
- 贡献：CIVILIZATION，每种实际输出各生成原国家和原操作者两条查询视图；单位 `production.resource:<resource>`、数量为实际产出。
- 操作键：`nation-production:<domain>:<runId>:<resource>`。同一 run 不得通过新序列、更换输出集合或操作者再次签发贡献。

来源按连续完工序列有界读取，Chronicle 先保存专用持久 ingress；只有精确规范事件与该收件一致才可提交历史、剧情收件和全部贡献。普通 `engine.accept(event)` 或其他来源键不能生成这类贡献。原生服务或域在读取期间切换会拒绝游标推进；无在线玩家时也可接收积压。

国家和个人是同一生产结果的两个查询视角，不能加总成双倍产量。取消、尚未完成、旧工厂、当前创始人的身份快照或普通资源 grant 均不生成生产贡献。该变更复用 Chronicle schema8 的已有来源和贡献表。

## 验收边界

产品测试覆盖输入不足、等待闸门、重启、取消精确退款、国策加成隔离、当前权限与原操作者、三种转换的 SQL 注入失败、提交回执丢失及重读失败、并发、旧快照、日志删尾／篡改、来源切换与贡献事务回滚。

实服须使用冻结双 JAR，执行真实非 OP 命令与正常双插件重启，并从每轮停服 SQL 独立复核库存、生产链、来源收件和贡献。空服补录必须发生在任何客户端进程启动前。未验证项包括物理断电、全栈Folia、长期大容量、旧工厂恢复、建筑容量与配方平衡。MySQL专项、正常重启与进程中断各按对应报告和精确构件保留，本轮新增配置不继承旧构件实服结论。当前原生完整日志校验为线性扫描，不能宣称长期容量验收完成。
