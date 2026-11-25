# 持久贸易历史接线与验收（2026-11-25）

原生贸易历史现在以持久存储域和提交序列读取。Chronicle 将来源游标与不可变收件箱同事务保存，消费成功后确认，因此迟到写入、同一交易状态变化和维护清理都能留下独立历史。**本增量验证历史存储提交；没有证明资产交易与历史跨系统原子提交，也不生成贸易贡献。**

## 配套源码与构件

| 项目 | 续接分支与源码提交 | 冻结 JAR SHA-256 |
| --- | --- | --- |
| StarChronicle | `codex/trade-history-ingress-20261125`，`b84984e`，基于成员分支 `faf5cc0` | `cf68a5a424bdcfb3a072fb901202a9649e2d7908e479165c93c74b634e081702` |
| StarCore | `codex/trade-history-journal-20261125`，`5669c367`，基于国家史诗分支 `95942b6f` | `41a4b79a3b5cfdcfd1af7dd810a32cf5c8a5079692519e7a96e41e9bacaf41f2` |

Java 21、Paper 1.21.11、Node 22.22.2、Mineflayer 4.39.0。Paper JAR 为 `5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba`。本轮没有合并主分支或部署生产；十阶段继续推进。

## 原生提交协议

`TradeHistoryService.historyAuthorityDomain()` 返回该历史库持久 UUID；`changesAfter(expectedDomain, sequence, limit)` 只读返回最多 100 条连续变更，起点为 0。旧实现默认拒绝新能力，Chronicle 不退回不可靠的执行时间分页。

`trade_history_journal_meta` 保存域与已提交高水位，`trade_history_changes` 保存序列、种类、日志写入时间和完整历史记录快照。所有新写入、批量保存、状态修改与清理，均在同一事务先锁住元数据，再修改当前记录、分配序列并追加日志。并发写者不能让较小序列在消费者前进后才提交；回滚同时撤销记录、日志和高水位。读取拒绝错域、未来游标、缺口及缺失的已提交行。

| 原生种类 | Chronicle 事件 | 含义 |
| --- | --- | --- |
| `BASELINE` | `TRADE_HISTORY_BASELINE` | 首次升级时仍存在的旧行；不补造此前修订或原提交时间 |
| `UPSERT` | `TRADE_HISTORY_RECORDED` | 原生历史记录新增或实际持久字段变化 |
| `DELETE` | `TRADE_HISTORY_REMOVED` | 清理当前表前保存的最后快照；不代表退款或经济撤销 |

相同快照重复保存不追加；状态修改从数据库读取当前记录，避免旧缓存覆盖新数据。旧 `persistAllRecords()` 保留兼容入口但不再把缓存重写到数据库，防止复活被其他实例清理的记录。已有缓存查询并未变成跨实例实时查询，持久日志是本增量的权威读取路径。

日志 `recordedAt` 是写入时刻，序列决定提交先后；原 `executedAt` 单独保留。时钟变化不会被解释为缺失交易。旧库 `BASELINE` 按记录 ID 排序，并不代表旧交易真实提交顺序。

## Chronicle 消费与边界

`CommittedHistoryJournal` 的两个附加表随服务启动建立，保留 schema 7。来源为 `starcore.trade.history.v1`，事件 ID 为 `starcore:trade-history:<domain>:<sequence>`。空首页也固定来源域；拉取过程中服务实例变化、错域、缺序、重复 ID 或非法字段会拒绝整页，游标不会前移。

待处理收件箱先送入 `RuleEngine.accept`，成功提交或确认完全相同的重复事件后才 ACK。崩溃发生在两步之间会原样重放；处理失败保留待处理行并停止继续拉取。贸易来源错误单独记录，不中断其他历史与成员扫描。域变化需要明确对账，当前没有自动重置或管理员强制跳过流程。

历史保留原买卖双方与当时国家；缺少主体就保持缺少，绝不把当前君主猜成当年操作者。`time_basis=JOURNAL_RECORDED` 和 `trade.asset_atomicity=NOT_PROVEN_BY_HISTORY_JOURNAL` 明确时间与经济保证。备注在 Chronicle 中保留稳定指纹和长度，原文留在原生快照中。事件仅陈述历史记录变化，`REFUNDED` 状态本身不证明退款完成。

`SimplePlayerTradeService` 仍在经济操作后单独保存历史，保存失败只写日志。因此本协议不能保证每次已经完成的资产交易都有历史，不能据此签发贡献。混合旧版本写者、绕过服务的直接 SQL 变更、原生库被单独回滚或替换也不在保证内；必须保留完整数据库和来源域，不能清空 ACKED 收件箱当作维护方案。

## 已执行验证

| 检查 | 结果 |
| --- | --- |
| Chronicle `verify` | 905 项／96 套件；0 失败、0 错误、0 跳过 |
| StarCore 贸易、原分页、模型及 API 兼容专项 | 17 项／4 套件；0 失败、0 错误、0 跳过 |
| StarCore 常规 `verify` | 汇总 7,211 项／1,436 套件；0 失败、0 错误、21 跳过；沿用 POM 已有排除配置 |
| 在线 SBOM 与改动文件 Spotless | 成功；离线 verify 未生成 SBOM，随后在线补齐 |
| 贸易证据回归 | 20 项通过，包括删除迟到序列、修改状态／国家／时间、错域、缺 ACK、改写收件箱和事件、虚假贡献等负例 |
| DUNGEON／WAR 贡献证据回归 | 各 16 项通过 |
| 成员证据回归 | 15 项通过 |

67 项实服证据回归均未跳过，并实际检查了旧 WAR 停服失败日志必须被拒绝。原生专项覆盖并发服务、未提交不可见、回滚不留洞、旧库基线、批次原子性、状态／清理／重启、长整型和空主体；Chronicle 覆盖本地提交失败回滚、消费后崩溃重放、空页域绑定、旧服务、服务替换、背压与字段边界。MySQL 实库、长期容量和全栈 Folia 尚未由本增量验证。

四个成功目录均为同一对冻结构件，各三次完整启动，合计 24 个服务端／客户端进程 exit 0：

| 目录（均在 `run/`） | 端口 | 范围 |
| --- | --- | --- |
| `paper-epic-cf68a5a4-dungeon-trade-journal-1` | 29671 | 历史存储写入、迟到、状态更新、维护清理、两次重启 |
| `paper-epic-cf68a5a4-dungeon-trade-regression-1` | 29672 | 史诗 DUNGEON 与贡献回归 |
| `paper-epic-cf68a5a4-war-trade-regression-1` | 29673 | 史诗 WAR 与贡献回归；原生和谈结束 |
| `paper-epic-cf68a5a4-dungeon-trade-member-regression-2` | 29675 | 换国复核、退出、原国家重入、不可变票与审计 |

贸易测试通过真实 `TradeHistoryService` 保存明确的测试记录，未执行资产交易。四个非 OP 协议客户端实际登录，每轮 A 执行 `/chronicle contributions` 并收到空结果；6 次公开 API 观察与原生 SQL、Chronicle SQL 逐字段核对。

| 序列 | 提交内容 | 原执行时间／最终快照 |
| --- | --- | --- |
| 1 | first 新增 | 2020 年，COMPLETED |
| 2 | late 迟到新增 | 2019 年，COMPLETED；晚于序列 1 写入 |
| 3 | first 状态修订 | 仍为 2020 年，DISPUTED；序列 1 保持 COMPLETED |
| 4 | 清理 first 当前行 | 保留 DISPUTED 快照 |
| 5 | 清理 late 当前行 | 保留 COMPLETED 快照 |

最终原生当前表 0 行、持久日志 5 行、Chronicle 游标 5、收件箱 5 行且全部 ACKED、历史事件 5 行、贡献 0。独立检查器还核验事件规范哈希、启动指纹、时间顺序、实际命令和停服状态。

成员首次目录 `paper-epic-cf68a5a4-dungeon-trade-member-regression-1`（29674）虽完成三次流程与正常退出，但 boot1 的 PaperVersionFetcher 请求远端版本列表出现 `SocketTimeoutException`／ERROR，严格检查器拒绝整轮。原目录和失败检查日志保留；新目录重新执行通过，未放宽检查器或改写旧日志。首次 Chronicle 编译缺少 import 和首次 Spotless 文件模式误用也均保留在归档中。

## 复验与交接

用 `prepare-epic-paper.py prepare` 创建新指纹目录，配合受验 StarCore JAR 和初始化 Paper runtime；然后 `compile`。贸易轮次命令：

```bash
export EPIC_MINEFLAYER_MODULE=/path/to/node_modules/mineflayer
python3 tools/epic-paper-session.py --run run/NEW-DIRECTORY --java-home /path/to/java21 --node /path/to/node --boot 1 --scenario trade-first
python3 tools/epic-paper-session.py --run run/NEW-DIRECTORY --java-home /path/to/java21 --node /path/to/node --boot 2 --scenario trade-second
python3 tools/epic-paper-session.py --run run/NEW-DIRECTORY --java-home /path/to/java21 --node /path/to/node --boot 3 --scenario trade-third
python3 tools/inspect-trade-paper.py run/NEW-DIRECTORY
STARCHRONICLE_TRADE_RUN=run/NEW-DIRECTORY STARCHRONICLE_EPIC_FAILURE_LOG=/path/to/legacy-policy-shutdown-failure.log python3 -m unittest discover -s tools -p test_trade_acceptance.py -v
```

成员和两条史诗流程见[成员报告](MEMBER-REVIEW.md)及[贡献报告](CONTRIBUTIONS.md)。旧库贡献升级仍由旧 `57e4171f…` 构件独立证明，本轮没有重新执行旧库升级。

[证据 ZIP](verification/national-epic-trade-history-20261125-evidence.zip)为 7,996,038 字节，SHA-256 `1b7023fede8917ebe96baac1cee68637967ea4937844e432257c19846def2d5e`；已逐项核验 1,813 个成员哈希及 5 份配置脱敏。它包含五个运行目录、实际构建／测试 XML 和日志、失败证据、数据库、冻结 fixture 源码及检查工具。对应[机器清单](verification/national-epic-trade-history-20261125-summary.json)保存构件、测试选择、Git 状态与源文件指纹。

本机完整交付在 `/home/add/starchronicle-trade-history/target/national-epic-trade-history-20261125/`，另含双插件 JAR 与原生源码 ZIP；运行世界、依赖缓存和 JDK 不入 Git。新设备重建使用新指纹和新目录验收。下一步仍包括经济与历史的真正共同提交、非史诗资源／科技权威日志、文明生产、精确构件自动记忆、MySQL、Folia、目标第三方组合和长期容量。
