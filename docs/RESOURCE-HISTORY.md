# 国家资源转移提交历史

国家库存间的真实转移现已接入 Chronicle 持久收件箱。原生库存、转移回执和独立序列末尾共同提交；Chronicle 保留转出／转入国家、资源数量、提交时间和稳定事件 ID。原生回执没有操作玩家，因此参与者为空，不据此签发个人贡献。

源码检查点：Chronicle `1ae8da2`（基于科技分支 `7382b73`），StarCore `2c2ae268`（基于 `0c13cfc3`）；归档工具 `33cfd4c`。当前两分支为 `codex/resource-history-ingress-20261125`、`codex/resource-transfer-journal-20261125`，叠加在科技增量之上。对应 [Chronicle草稿PR #7](https://github.com/addxiaoyi/StarChronicle/pull/7) 与 [StarCore草稿PR #6](https://github.com/addxiaoyi/starcore/pull/6)。没有合并主分支或部署生产服。

## 原生协议与故障修复

`NationResourceTransactionService.transferHistoryAuthorityDomainId()` 返回 `Optional<UUID>`，默认 empty；旧提供者不会自动声明满足新协议。配套 `transferReceiptsAfter(long,int)` 只读取真实提交回执。页大小为1–1000，游标不能超出已提交末尾；即使请求空页，也验证缺序、末尾和域。

SQL 新表 `starcore_resource_transfer_history` 独立保存 `authority_domain_id` 与 `committed_sequence`；`resource-transfer-history-v1` 迁移标记与该行交叉核验。唯一序号加 COUNT/MIN/MAX 与错域计数，核验1..head连续性。快照、转移和迁移先取得写锁，避免 SQLite 在已有读快照上升级写锁而立即报 `SQLITE_BUSY`；转移库存、CAS推进末尾、插入回执同事务。普通保存不删除转移回执，重复ID插入失败会同时回滚库存与末尾。

此前从仅文件存量迁移时，先插入域可能让通用加载器误判SQL已有完整状态，遗漏库存。本次将旧库存与回执在同事务导入，再初始化连续末尾。首次升级只能基于现存完整连续回执建立基线；**无法证明或重建升级前已经丢失的最后一条回执**。一旦头／标记已经建立，缺失时拒绝重建基线。

`resource/resource-sql-authority` 固定SQL权威，重启时数据库不可用或SQL状态丢失不会回读旧Properties；运行期间也不从文件悄悄切到SQL。首次选择SQL即写标记；如果此时数据库初始化中断、SQL尚未建立状态，后续启动会拒绝自动旧文件导入，应保留现场并从匹配备份恢复，不可删标记绕过检查。普通SQL保存失败向上传播，不再以旧文件保存冒充成功。

文件后端将库存、计数、末尾和回执原子替换，普通同步／异步快照保留既有回执；异步void接口改为同步排队保存。页内只解析请求范围的完整负载，仍扫描序号连续性，不构造完整回执列表。百万回执的已有容量回归继续通过；这不等于大规模SQL持续吞吐已经验收。SQL完整性聚合仍随历史长度增加成本，压力指标属于后续工作。

## Chronicle 接入边界

来源为 `starcore.resource.transfers.v1`；每次最多100条，读取前后检查同一服务实例及权威域，空页亦检查。游标与不可变收件箱原子提交，规则引擎消费成功后ACK；消费者在提交后ACK前崩溃仍可安全重投。科技／贸易来源的待处理页不阻塞资源来源。

事件类型 `NATIONAL_RESOURCE_TRANSFER_COMMITTED`，ID 为 `starcore:resource-transfer-history:<domain>:<sequence>`。事实保留交易ID、方向、排序资源JSON和类别；描述仅保留稳定摘要与字符长度，证据文本使用交易ID摘要。`time_basis=COMMITTED`、`resource.actor=NOT_RECORDED`、`resource.scope=NATION_STOCKPILE_TRANSFER`。类别是调用者元数据，不能据此推断已完成研究或有个人劳动贡献。

遵守现有 WorldEvent 单事实4096字符上限；超限来源会保留为待对账，不会截断、跳过或推进游标。适配器不再把SQL的191字符交易ID上限误加到旧文件API，也不人为限制资源名只能ASCII。普通grant/consume、玩家交易、文明生产各有独立语义，不属于此来源。

## 构建与测试

| 范围 | 实际结果 |
| --- | --- |
| Chronicle 完整 verify | 919项／98套件，0失败／错误／跳过 |
| 原生资源专项 | 40项／8套件，0失败／错误／跳过 |
| 原生常规 verify | 7,241项／1,440套件，0失败／错误、21跳过，沿用原POM排除 |
| 原生格式／覆盖率门槛／SBOM | 完整verify、Spotless、既有JaCoCo门槛通过；在线SBOM成功 |

原生专项覆盖真实旧库存Codec迁移、未来游标、缺中间／末尾、错域、头／标记丢失、普通快照保留回执、SQL失败禁止回退、回执插入失败时共同回滚、文件固定与百万条分页，以及真实SQLite另一模块持有写锁时迁移／快照／转移等待后成功。Chronicle新增8项覆盖精确事实、崩溃重放、旧提供者、空页域与服务变化、缺序、非法回执、跨来源背压、文件ID兼容。

修复前完整性红例11项中10失败，保存在原生 `target/resource-history-before-20261125/`。中途尝试每次解析全部文件回执，导致百万分页的既有回归失败；已撤回该变更，失败日志和XML保存在 `target/resource-history-capacity-regression-20261125/`。最终专项40项与完整构建重新通过，没有隐藏失败尝试。

冻结构件SHA-256：

- Chronicle：`222fbb4064a84692ee3783a525737495566f1a529c3753df2392695797a4e345`。
- StarCore：`a2bb035cd9e5120e84e142355d4092a2dcfe2b16b0d1ea578e8af7c64d7d910c`，本机 `target/resource-history-accepted-starcore-1.0.0.jar`。
- Paper 1.21.11：`5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba`。

## 实服证据

Java21／隔离Paper1.21.11，四个非OP实际协议客户端。初始库存是明确测试种子：A有农业100、矿产50、能源25，B／C各农业100。首轮同一动作A→B转农业7／矿产3，再B→C转农业4／矿产2。每轮都重复首笔、制造相同ID不同参数、请求超出库存；状态分别为ALREADY_APPLIED、REJECTED、INSUFFICIENT_RESOURCES，库存和回执不变。第二轮重启后C→A再转农业2／矿产1；第三轮验证全部恢复。

最终库存A农业95／矿产48／能源25，B农业103／矿产1，C农业102／矿产1。3条原生回执、独立head=3、3条ACKED收件箱、3条持久事件一致。8次公开API观察和3次实际`/chronicle contributions`查询，贡献均为0。

严格检查器核对冻结JAR与fixture、完整进程生命周期、正常停服、服务端流与fixture日志、因果顺序、SQL库存／头／迁移标记／回执、Chronicle游标／收件箱／事件规范哈希和真实客户端命令。26项实服证据回归含删除中间／末尾／head、错域／方向／数量／库存、缺ACK、伪造操作者／贡献，以及旧真实policy与天气停服失败。新目录从不覆盖旧boot。

最终冻结组合的六组均完成三次启动：36个服务端／客户端进程正常退出，26＋23＋20＋15＋17＋17＝118项证据回归全通过，无跳过。

| 目录后缀（前缀均为`run/paper-epic-222fbb40-`） | 端口 | 证据回归 |
| --- | --- | --- |
| `dungeon-resource-final-journal-1` | 29701 | 资源26项 |
| `dungeon-resource-final-tech-1` | 29702 | 科技23项 |
| `dungeon-resource-final-trade-1` | 29703 | 贸易20项 |
| `dungeon-resource-final-member-1` | 29704 | 成员15项 |
| `dungeon-resource-final-epic-1` | 29705 | DUNGEON贡献17项 |
| `war-resource-final-epic-1` | 29706 | WAR贡献17项 |

本轮所有实服控制器均结束，各自进程退出0；运行目录和数据库保留。此前真实policy、天气与外交失败日志仍参与严格负例检查。

```bash
export EPIC_MINEFLAYER_MODULE=/path/to/node_modules/mineflayer
# 用 prepare-epic-paper.py prepare/compile 创建全新目录与端口
python3 tools/epic-paper-session.py --run run/NEW --java-home /path/to/java21 --node /path/to/node --boot 1 --scenario resource-first
python3 tools/epic-paper-session.py --run run/NEW --java-home /path/to/java21 --node /path/to/node --boot 2 --scenario resource-second
python3 tools/epic-paper-session.py --run run/NEW --java-home /path/to/java21 --node /path/to/node --boot 3 --scenario resource-third
python3 tools/inspect-resource-paper.py run/NEW
STARCHRONICLE_RESOURCE_RUN=run/NEW STARCHRONICLE_EPIC_FAILURE_LOG=/path/to/original-policy-failure.log STARCHRONICLE_WEATHER_FAILURE_LOG=/path/to/weather-failure.log python3 -m unittest discover -s tools -p test_resource_acceptance.py -v
```

本轮文件迁移由真实Codec、H2事务与SQLite竞争测试验证，尚未运行完整旧服文件迁移实服演练。MySQL实库、多服务器共享写者、长期压力、全栈Folia和第三方生产组合尚未由本报告证明。旧库贡献升级仍由其独立构件报告证明；WAR采用原生和谈，DUNGEON采用三秒原生生存房间，不等于战场胜利。十阶段仍未整体完成。

[证据包](verification/national-epic-resource-history-20261125-evidence.zip)与[汇总](verification/national-epic-resource-history-20261125-summary.json)包含六组运行证据、实际SQL数据库／标记、专项和完整测试XML、红例及独立检查日志。1,920个成员哈希与6份配置脱敏核验通过；8,365,076字节，SHA-256 `7d6843cfab866b1e9d2d87988d2f5393bb9882175d8449d679625cd478df0ca6`。本机完整交付 `target/national-epic-resource-history-20261125/`，世界／依赖／JAR不作为生产部署。
