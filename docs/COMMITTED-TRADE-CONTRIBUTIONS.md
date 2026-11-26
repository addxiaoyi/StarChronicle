# 贸易历史与净贡献验收

StarChronicle [`25fba83`](https://github.com/addxiaoyi/StarChronicle/commit/25fba83) 已接通配套 StarCore `a6452723` 的连续贸易日志。历史、完整原生回执、原始贡献、状态修订和正常剧情收件箱在同一事务提交；退款或争议不会继续计入有效贡献，也不会删除原始证据。

完整回归 **925 项、99 套件，零失败／错误／跳过**。冻结构件在隔离 Paper 完成三次正常启停：**134 笔真实原生交易、139 条历史变更、534 条原始贡献、554 条状态修订**，并通过 13 项证据破坏测试。十阶段仍在推进，本报告只覆盖本轮贸易专项。

## 行为与范围

每次至多读取 128 条，固定原生存储域；序号与完整收件箱一起提交，源异常、缺号、存储域改变不会重置游标。多个来源交错交付，保留各自序号，防止持续科技事件挤占贸易退款的处理。首次追赶建立历史与净贡献，但不补发旧剧情、NPC、纪念点或奖励。正常消息在本地完整提交后才 ACK；有历史行而缺少贸易贡献回执不能 ACK。

完整回执保留原始双方、当时国籍、资源／数量、双精度金额、订单、备注和纳秒时间。历史 occurredAt 来自原生事务事件；事实分别保留原成交 executedAt 和日志提交 committedAt，不用轮询时间替代。事件 ID 包含 domain 与 sequence，同一交易的多次修订不会互相覆盖。超过历史事实 4096 字符的备注保留在完整回执中，历史事实包含长度与 SHA-256。

按资源数量为不同参与玩家和国家分别计一条贡献；同国双方的国家条目只计一次。不同主体的总和不代表全服交易量。没有真实玩家主体的那一侧只入史，不查询现任创始人冒充参与者。原生快速出售的旧 orderType 仍可能是 BUY，因此主体按实际 buyer／seller 字段确定。

数量支持至 `Long.MAX_VALUE`，资源标识支持原生 64 字符，不截断或拆分；国库／援助金额保留原有上限。争议追加 SUSPENDED，退款追加 REFUNDED，取消追加 CANCELLED；净值只合计 ACTIVE。明确恢复后重新计入原条目。同状态备注是 `TRADE_UPDATED`，恢复是 `TRADE_RESTORED`，不会再次触发首次 `TRADE_COMPLETED`。已发剧情奖励不自动撤销。

原生日志安装前的旧完成只存在身份基线，后续退款可能没有对应正贡献：保留完整历史，不凭空扣成负数。首次归档会重建日志覆盖范围内的贡献，旧快照无法证明的交易不补造。跨页追赶或源故障期间净值反映已经消费的位置，不能把尚未追完的账本当作源头实时余额。

公开 `ChronicleService` 新增 `contributionBalances`、`contributionRevisions`、`tradeHistoryReceipt`；原 `contributions` 保留原始条目，`contributionTotal` 返回净值。`/chronicle contributions` 显示当前计入／原始数量与状态。完整接口见 [API](API.md)。数据库升级至 schema 8，保留旧贡献和旧历史；旧插件会拒绝打开新版本数据库，回退需匹配备份。

## 实服证据

| 项目 | 本轮冻结值 |
| --- | --- |
| Chronicle 源码 | `25fba83`，`codex/national-epic-linux-acceptance-20261125` |
| Chronicle SHA-256 | `0e0fc515cec0d268991ceface44ec529c10eac8d06d52c520a674e02b3ebc712` |
| StarCore 源码／SHA-256 | `a6452723`／`b0eacd9fe7bf388f2ae236e5b47c395411121b323b0b9cb93ca13c1cce89bbe4` |
| 环境 | Paper 1.21.11-132-c5eb079、Java 21、原生经济、回环 29660 |
| 成功目录 | `run/paper-trade-contribution-0e0fc515-20261126-3` |
| 存储域 | `f2fd53e8-78dd-47d4-aba0-bcb6048efd3c` |

第 1 次启动先产生直接交易、快速出售和市场撮合各 1 笔，Chronicle 将前三条只归档。再分 tick 执行 130 笔直接交易，并注入收件箱 SQL 失败：原生到 133 时 Chronicle 游标仍为 3，没有部分收件箱；移除触发器后跨 128 条分页全部追上。将第一笔交易设为争议、恢复完成，核对净值先暂停再恢复。最后把第一笔设为退款，同时注入贡献修订写入失败：源和收件箱到 136，已提交历史仍为 135，退款保持待处理，没有只写历史漏写冲正。

第 2 次启动移除故障，自动提交第 136 条退款和四个主体的状态，资产与上一停服快照一致。追加 1 笔真实直接交易，修订市场备注、取消市场历史，最终 139 条历史。第 3 次启动及两次公开检查保持相同数量、状态与净值。

共 132 笔直接交易、1 笔快速出售、1 笔市场撮合。最终买方玩家／国家各计入 133 个 industrial，卖方玩家／国家各计入 131 个；原始条目仍保留。最终真实资产为买方 `99736.04`、137 资源，卖方 `10278.00`、861 资源；历史修订与补录没有额外资产变化。

原生状态修订通过 `TradeHistoryService.updateStatus` 管理接口执行，**不声称实际退款或取消了资产结算**。本轮证明这些权威历史状态能正确投影为净贡献，经济补偿操作需另行验收。演员为固定离线夹具账户，真实服务负责结算；没有玩家客户端、命令／GUI 验收或伪造完成回执。没有认证 MySQL、第三方经济、最新构件的完整史诗五插件组合或全栈 Folia；快速购买实服流程也未覆盖。

独立检查逐条比较原生 SQL、Fabric 交易文件、Chronicle 收件箱／回执／原始贡献／修订、公开 API 和三次停服资产。只允许第一轮注入窗口内精确匹配的 6 条入口故障与 1 条修订故障告警；其他错误一律拒绝。13 项证据回归会破坏副本中的指纹、序号、原国籍、退款状态、回执、归档标志、进程退出、故障记录、重启资产、公开净值、时间和待处理 ACK，确保不能把损坏证据判为成功。

## 测试、失败与复验

新增 20 项 Java 测试：12 项贡献／事务／迁移／重启、7 项来源协议及 1 项多来源公平交付。聚焦初轮有一处 JUnit 泛型重载编译歧义，修正后 38 项通过；完整初轮发现旧迁移测试写死 schema 7，更新至 8 后两次完整 925 项通过。所有失败日志保留，断言和测试排除没有放宽。

最终 Java 源码通过 `trade-chronicle-verify-20261126-3.log` 的 925 项 verify；随后只更新打包内 API.md，以 `-DskipTests package` 生成最终 JAR。冻结 sources JAR 已逐文件核对全部 114 个 Java 文件，最终 API.md 与产品 JAR 完全一致。

两次旧 `59c14083` 实服失败独立保留：attempt 1 在同一 tick 执行 130 笔交易，触发 Paper watchdog 卡顿告警；attempt 2 的故障源日志只保留通用事务包装，无法精确识别注入原因，控制器拒绝。之后夹具分 tick 执行，产品日志暴露具体底层原因，新指纹新 attempt 完成三次验收。独立检查初稿曾把 Java Set 的序列化顺序当作历史差异，改为只对集合字段排序比较；另一次在第三次启动前检查，明确缺少 boot3，均未记为通过。

```bash
env MAVEN_OPTS='-Xmx768m -XX:+UseSerialGC' ./mvnw -B -ntp verify
python3 tools/trade-contribution-paper.py prepare --starcore /path/to/starcore/target/starcore-1.0.0.jar --runtime /path/to/initialized-paper --attempt NEW-ATTEMPT --port 29660 --java-home /path/to/java21
python3 tools/trade-contribution-paper.py run --run run/PREPARED-DIRECTORY --java-home /path/to/java21 --boot 1
python3 tools/trade-contribution-paper.py run --run run/PREPARED-DIRECTORY --java-home /path/to/java21 --boot 2
python3 tools/trade-contribution-paper.py run --run run/PREPARED-DIRECTORY --java-home /path/to/java21 --boot 3
python3 tools/inspect-trade-contributions.py run/PREPARED-DIRECTORY
STARCHRONICLE_TRADE_CONTRIBUTION_RUN=run/PREPARED-DIRECTORY python3 -m unittest discover -s tools -p test_trade_contributions.py -v
```

准备器只接受新 attempt 和独立回环端口，不能覆盖失败；`--runtime` 包含 Paper 1.21.11 的 paper.jar、cache 和 libraries。本机已正常停服并释放端口。

[清单](verification/trade-contribution-linux-20261126-summary.json)与[证据包](verification/trade-contribution-linux-20261126-evidence.zip)包含源码指纹、99 份最终 XML、失败完整回归报告、各轮日志、三次停服快照、两个失败实服与工具；**1,190 成员、8,184,194 字节**，SHA-256 `5c6b69734218a4c7d37dd313b930cf563c149aeb99da8a65602ba55d6390f79c`。逐成员哈希与 ZIP 完整性已核对，3 个隔离服生成密钥脱敏。本机冻结 JAR 在 `target/trade-contribution-linux-20261126/`。原生 7,225 项／21 skipped 的构件与来源证据见[原生贸易报告](NATIVE-TRADE-HISTORY.md)，本轮沿用该原生 JAR，未重复运行原生全量回归。

下一步推进指定首都／资源历史、文明生产的真实主体、UNKNOWN 对账与安全新轮次、市场托管恢复、最新双路线五插件组合、全栈 Folia 和长期容量。不能把本轮贸易专项视为十阶段全部完成。
