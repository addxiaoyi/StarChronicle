# 贸易贡献合并与 schema 9 升级：2026-11-26

合入上游 `25fba83`／`30fa96b` 的完整贸易回执、净贡献与修订，保留本分支已安装的通用贸易账本。**1,105 项完整 clean verify 通过**，失败／错误／跳过均为 0；新增上游 20 项与合并兼容 9 项，先前 47 项专项亦通过。125 主源／118 测试源编译，267 份源码／资源／测试冻结核验。没有启动 Minecraft。

| 构件 | SHA-256 |
| --- | --- |
| Chronicle schema 9 | `e634ba8815b9f67a6de416b90293f381757201716fc10154340f4ae0d0d76e19` |
| Sources JAR | `d04337e2d842c623aabe412d630a3b00646a663bc39048642d4d9e6cb94c83c4` |
| StarCore，产品未改 | `e690d49296f580ba40d037f64d0a8f0720e38627d97a938951b75f26ed2fb41e` |
| 本次证据 ZIP | `dc1e86fb1c6380ea0622533a48ec6c63c995bd54804e51a8d524fdf0d131e879` |

[证据包](verification/github-trade-contribution-merge-20261126-evidence.zip)共 1149 成员，逐文件 SHA 与清单核验通过；[机器摘要](verification/github-trade-contribution-merge-20261126-summary.json)保存完整测试、API、字节码、数据库前后摘要及失败原因。

## 行为与兼容

- 单一贸易协调器根据已安装游标选读取路径：旧 `committed_source_cursors` 保留原事件／贡献 ID、原 `resource.<资源>` 单位、baseline 和剧情激活语义；专用 journal 新安装首次只归档，追赶结束后激活后续提交。两种贸易游标共存或固定来源消失时保留数据并拒绝继续，不重复创建另一套来源。
- 专用流保存完整纳秒回执及长备注，原始贡献和追加状态、历史／剧情收件箱同事务提交；没有完整回执不能 ACK。退款／争议／取消只改变净合计，恢复复用原条目；不调用经济或资源接口，也不撤回已经发出的剧情奖励。
- 每页重读完整锚点，检测静默回退、原位改写、域变化和缺号。128 条原始页含锚点时仍按原页判断追赶结束。各来源独立消费有界页面，科技持续写入不会饿死贸易退款。
- 旧库新增变化同事务追加修订；升级前的当前状态读取原始提交时间和事件，原凭据不改写。升级前没有独立修订表时不伪造旧修订或不完整的 `TradeHistoryReceipt`，原始记录仍保留在旧来源表中。旧 API 的 sourceStatus 与单位／数量准入规则保持原样。
- 新只读 API 提供 contributionBalances、contributionRevisions、tradeHistoryReceipt；玩家／国家贡献命令显示当前计入及原始数量。专用来源支持 64 字符资源和 Long.MAX_VALUE 数量，援助原有上限保留。详细契约见 [API](API.md)。
- **schema 9 阻止旧 schema 8 writer**。真实旧 JAR 对照能打开原上游 schema 8，并把应为 133／131 的主体净值算为 141／139；新版本升级后，旧 JAR 在三种副本上均拒绝打开且未改变任何行。回退必须恢复匹配的旧版备份。

## 验证

首个 schema 8 合并候选的 1,105 项完整回归也通过，但旧 writer 对照证明需要版本闸门。加入 schema 9 后重新完整 clean verify，仍为 1,105 项全通过；不将前一构件替代最终验证。本机每次仅一个 Maven JVM（256 MiB，forkCount=0、jacoco.skip=true），探针 128 MiB，串行运行，无本机覆盖率结论。

12 类旧公开声明／描述符保持，相关已测试 pre-shade 类与打包字节一致，最终字节码为 Java 21。EpicCampaign、TechnologyHistory、TradeHistory、TradeContribution 四种实服夹具编译通过。来源 getter 支持配套 StarCore 改名后的 UUID API，也兼容上游原 UUID getter，不把旧 Optional 流的序号当作新流。

完整 DB/WAL/SHM 一致备份后，以当前两 JAR 的真实 service／collector／RuleEngine 在六个独立 JVM 中核对：

| 副本 | 两次启动结果 | 原始数据 |
| --- | --- | --- |
| 旧通用来源 | 134 历史、534 贡献、游标 134；两次新增 0 | Chronicle 42 张原表原列保持，StarCore 124 张保持 |
| 上游专用来源 | 139 贸易回执、534 贡献、554 修订；两次新增 0；净值 133／131 | Chronicle 43 张原表原列保持，StarCore 124 张保持；另外两条建国历史也保持 |
| 全新 Chronicle | 首次导入 139，第二次 0；同为 534 贡献／554 修订和净值 133／131 | StarCore 124 张原表原列保持 |

另以三个独立旧 JAR JVM 验证 schema 9 拒绝写入。上游证据的 1,189 清单文件／1,190 ZIP 成员全部 SHA 通过。两次探针自身失败保留：第一版把全部历史条数误当贸易条数；第二版用完整新列与只含原列的快照比较，碰到本分支已有 quest_gates.attempt 迁移。最终检查区分原列保全与完整升级后快照，没有改写或删减数据来满足断言。

配套实服工具根据冻结 JAR 中 SCHEMA_VERSION 常量核对数据库版本，分别严格识别 schema 8／9 的两种故障注入日志；语法、实际旧／新 JAR 及错误日志拒绝检查通过，未运行新实服。归档器读取明确提供的完整构建及证据日志，不再把 925 项写死为当前测试数。

本轮前一合并 `838dca7` 的 [CI #204](https://github.com/addxiaoyi/StarChronicle/actions/runs/45774502952) 已全部成功，1,076 项与交付校验通过；本次合并的新 CI 单独跟进。StarCore 保持 `ef432da` 与其 1,929 项分批相关测试检查点，未宣称全库或新实服通过。

上游 [贸易贡献实服报告](COMMITTED-TRADE-CONTRIBUTIONS.md) 的 925 项、134 真实成交、139 变更和三次 Paper 启停仅属于其原构件。当前构件 Paper／Folia、完整市场托管恢复、其他生命周期与 GUI、外部经济未知结果及长期容量继续待办，十阶段尚未全部完成。
