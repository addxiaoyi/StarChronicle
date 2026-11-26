# 原生贸易提交日志验收

配套 StarCore [`a6452723`](https://github.com/addxiaoyi/starcore/commit/a6452723) 已补上贸易连续提交日志、结算前持久准备及成交后历史失败恢复。原生常规 `verify` **7,225 项，零失败／错误，21 项既有跳过**；76 项聚焦回归通过。新原生构件完成隔离 Paper 三次正常启停、134 笔真实服务成交和 11 项证据破坏回归。

Chronicle 本轮没有产品源码变更，沿用 `e53a35d8` 的完整 905 项构件，未重跑该完整构建。**贸易历史与贡献尚未接到 Chronicle**；本轮只证明原生来源和恢复协议。十阶段仍未全部完成。

## 原生能力

`TradeHistoryService.historyAuthorityDomainId()` 返回固定 UUID；`tradeChangesAfter(sequence, limit)` 按连续提交序号读取，每页 1..128。保存顺序与成交时间无关，所以迟到记录、晚转完成、退款和争议都会进入日志。每条包含原记录、previousStatus、SQL 提交时间 committedAt 和原生事务发生时间 occurredAt；新查询表同样保留纳秒执行时间。

记录、最新身份、提交日志和 head 在同一事务落库。写入先取得数据库写锁；重复保存完全相同的记录不生成新序号，同 ID 改写身份／金额或回写旧状态被拒绝，状态修订使用 `updateStatus`。旧库迁移只登记身份基线，不补造旧事件。查询记录清理不删除审计；旧缓存不再定时覆盖最新状态。缺号、游标超前、存储域变化或事务时间线篡改会拒绝继续。

直接交易、快速买卖和市场撮合在外部操作前写入 `trade_settlement_intents`，再向持久 `TransactionFabric` 绑定相同域、operation、完整记录和结算上下文。只有原生 COMMITTED 回执可生成完成记录。历史失败时保留真实已结算结果，后台每 20 秒读取至多 128 个准备记录，循环补录；恢复不会再次扣钱、退款、消耗或发放资源。

日志与已应用事务时间线 checkpoint 原子提交，支持失败后重读。已完成后 REFUND_PENDING／MANUAL_REVIEW 进入 DISPUTED，确认退款进入 REFUNDED。下一步贡献投影必须支持净值与冲正，不能只累加所有 COMPLETED。原生状态便利方法 `economyState()` 的旧退款映射未用于此协议，消费者应读取明确的 status 与 previousStatus。

缺少持久依赖会在结算前拒绝。结果不明的准备记录保持可查询，不能自动重试资产操作。市场中断会暂停两张订单的撮合、取消、过期清理及停服退款；待核对集合与 MANUAL_REVIEW 防止再次发放。完整的订单／托管恢复和管理员解除流程仍缺：未开始撮合的订单托管主要在内存，不能据此承诺跨系统原子提交或异常崩溃后全自动资产恢复。

## 实际构件与范围

| 项目 | 本轮证据 |
| --- | --- |
| 原生源码 | `a6452723`，分支 `codex/national-epic-linux-acceptance-20261125` |
| StarCore JAR SHA-256 | `b0eacd9fe7bf388f2ae236e5b47c395411121b323b0b9cb93ca13c1cce89bbe4` |
| Chronicle JAR SHA-256 | `e53a35d89f4cf3e4c8fa59a04164c90c2fe554c329fff77857a8e5f75a8c04f2` |
| 运行环境 | Paper 1.21.11-132-c5eb079、Java 21、原生经济；本机回环 29659 |
| 成功目录 | `run/paper-trade-e53a35d8-20261126-2` |
| 失败保留 | `run/paper-trade-e53a35d8-20261126-1`：控制器把预期注入错误误判为失败；服务器正常退出，未改写为通过 |
| 第 1 次启动 | 直接交易、快速出售、市场撮合各 1 笔；第 4 笔直接交易在注入 SQL 日志失败下完成资产结算，保留 4 个 intent、3 个历史、1 个待补录 |
| 第 2 次启动 | 停服移除故障触发器后自动补录至 4，余额／资源不变；再执行 130 笔真实直接交易至 134，跨 128 条分页 |
| 第 3 次启动 | 134 条保持稳定，双次检查、无额外资产变化 |

总计 132 笔直接交易、1 笔快速出售、1 笔市场撮合；每笔直接交易均再调用结构化接受接口验证幂等及无关 actor 拒绝。独立检查比对 SQL 身份／查询／提交日志／准备 checkpoint、事务文件 metadata／状态时间线／指纹、公开 API 与原生余额和资源快照。

所有 actor 是隔离服固定离线测试账户，调用真实原生服务，没有玩家客户端，也没有伪造事件或完成回执。真实快速购买未实服验证（单元层验证双向快速恢复）；MySQL、第三方经济、完整史诗组合和全栈 Folia 不在本轮范围。故障使用测试服 SQL trigger，仅阻断历史表插入；成功第二次尝试只允许注入窗口内一条格式、operation 与事务 ID 均精确匹配的错误，其余错误一律失败。

## 测试及复验

原生常规回归 1,437 套件、7,225 项；新增 23 项是 11 项日志与 12 项恢复测试。另有既有服务测试更新为真实文件存储依赖，聚焦共 76 项。未扩大测试排除配置。常规 verify 前移走此前混合 JaCoCo 数据，正常 fork／Mockito agent／JaCoCo 重跑。依赖仓库 403 与 SBOM 元数据警告在完整日志保留，最终 BUILD SUCCESS。

早期聚焦回归包括旧夹具未注入依赖、一个遗漏 import、Mockito 异常 stub 重置方式、错误的订单 getter 导致失败；全部日志与首次实服失败均已归档。最后 76 项通过后运行完整 verify，再以其冻结构件实服验收。

在配套原生仓库构建：

```bash
env MAVEN_OPTS='-Xmx768m -XX:+UseSerialGC' ./mvnw -B -ntp -Dspotless.ratchetFrom=HEAD^ verify
```

在 Chronicle 仓库准备新 attempt（不可覆盖旧轮次）：

```bash
python3 tools/trade-history-paper.py prepare --starcore /path/to/starcore/target/starcore-1.0.0.jar --runtime /path/to/initialized-paper --attempt NEW-ATTEMPT --port 29659 --java-home /path/to/java21
python3 tools/trade-history-paper.py run --run run/PREPARED-DIRECTORY --java-home /path/to/java21 --boot 1
python3 tools/trade-history-paper.py run --run run/PREPARED-DIRECTORY --java-home /path/to/java21 --boot 2
python3 tools/trade-history-paper.py run --run run/PREPARED-DIRECTORY --java-home /path/to/java21 --boot 3
python3 tools/inspect-trade-history.py run/PREPARED-DIRECTORY
STARCHRONICLE_TRADE_RUN=run/PREPARED-DIRECTORY python3 -m unittest discover -s tools -p test_trade_history.py -v
```

`--runtime` 必须包含已有 Paper 1.21.11 的 paper.jar、cache、libraries；Chronicle 默认取 target 当前 JAR。本次使用旧 Chronicle 产品的指纹仅覆盖原生贸易接受范围，不扩张旧科技／史诗报告的配套原生版本。

已归档[清单](verification/native-trade-history-linux-20261126-summary.json)和[证据包](verification/native-trade-history-linux-20261126-evidence.zip)：**5,525 成员、15,993,580 字节**，SHA-256 `b6c45b78ecd0fa4a6eb15a643733c7287980cc118ce3b5294248173e488d4fd0`。包括原生已提交源码指纹、实际全量 XML 报告、各轮构建失败、三次停服快照与失败尝试、运行／检查／归档工具；两个隔离服生成密钥已脱敏，逐成员哈希与 ZIP 完整性核对通过。本机冻结 JAR 与同份包在 `target/native-trade-history-linux-20261126/`。

下一步接入 Chronicle 有界贸易来源、历史与贡献同事务、首次归档及退款冲正；同时推进市场托管恢复、在线流程、其他原生历史源、UNKNOWN 安全新轮次、Folia 与容量验收。
