# 原生科技提交历史与验收

更新：2026-11-26（UTC）。源码提交 `503c66b` 将 StarCore 的持久科技提交日志接入 Chronicle。解锁、撤销、再次解锁即使全部发生在两次扫描之间，也会分别入史；没有研究者字段的原生回执只归属国家，不生成个人参与或贡献。

本轮完整 `verify` 为 **97 套件、905 项，零失败／错误／跳过**，使用正常 fork 与 JaCoCo。42 项专项及 10 项真实证据回归通过。新构件在隔离 Paper 完成三次正常启停，独立检查核对了 139 条真实原生提交。十阶段仍未全部完成。

## 精确构件与范围

| 项目 | 本轮实际值 |
| --- | --- |
| Chronicle | `e53a35d89f4cf3e4c8fa59a04164c90c2fe554c329fff77857a8e5f75a8c04f2` |
| StarCore | `44f450eccc95590aadc54736109e012d5a52bbae459f4107afa498fd4528518d`，沿用冻结候选，本轮无原生源码改动 |
| 服务端 | Paper 1.21.11-132-c5eb079、Java 21、独立端口 29658 |
| 运行目录 | `run/paper-technology-e53a35d8-20261126-1` |
| 操作 | 真实 `NationService.createNation`、`TechnologyService.unlock/revoke`；测试国家与离线身份由隔离夹具创建 |
| 验证边界 | 管理型科技变更、持久分页、无人在线入史、Chronicle 停用期间的补读、重启去重 |

本轮没有玩家客户端，没有执行真实研究计时／消耗流程，也没有把测试身份当作研究者。StarCore 90 项专项与 7,202 项常规回归为此前同一冻结 JAR 的证据，本轮未重跑。所选五插件的史诗双路线验收属于先前 `5758e54b` 构件，见[组合报告](PRODUCTION-COMBINATION-TEST-REPORT.md)；不能把它当作本轮 `e53a35d8` 的组合重验。未部署生产、未认证全栈 Folia。

## 持久协议

原生 `TechnologyHistoryStorage` 将科技状态、`history.domain`、连续序号和待归档回执放在同一次状态提交中；下一次读／写将待归档回执幂等保存到序号文件。`historyAuthorityDomainId()` 与 `technologyChangesAfter(after, limit)` 暴露这一日志。现有解锁在原生协议首次安装时只是状态基线，不补造发生时间。

Chronicle 每轮至多读取 128 条，校验同一存储域、完整连续序号、操作 UUID、国家 UUID、变更种类与真实时间。事件 ID 为 `starcore:technology:<domain>:<operation>`，时间精度原样保留。`UNLOCKED` 映射为 `TECHNOLOGY_BREAKTHROUGH`，标题明确为“科技解锁（原生提交）”；`REVOKED` 映射为 `TECHNOLOGY_REVOKED`。不读取当前国家成员或当前科技定义来改写旧回执。

新增两个 SQLite 附加表，原有 schema 7 表结构保持兼容：

- `committed_history_sources` 保存命名空间、固定存储域、序号及是否已追到日志末尾。
- `committed_history_ingress` 保存不可变事件、序号、初始归档／正常触发模式与 PENDING／ACKED 状态。

游标和整页待处理事件同事务提交。必须先交付已保存的页，才能读取下一页，因此待处理队列不会随失败无限扩大。消费在独立事务进行，成功后才 ACK；消费者提交后、ACK 前中断会重投相同事件，由现有事件指纹去重。读取、投影、SQL 或消费失败均保留可重试进度。换库、存储域变化、回退到旧源快照或序号缺口不会自动清空游标。

首次接入从序号 0 归档到首次不足 128 条的页。这段保守追赶期间只保存历史及因果根，不补发旧剧情、NPC、记忆或奖励。之后的新提交正常进入剧情收件箱，重启和离线不会重新变成首次归档。初次追赶若与新提交重叠，新记录也可能被保守归档，直至追到当时的末尾；这里优先防止升级重复发放效果。

已有 `OBSERVED` 和旧研究回调历史保持原样。具备持久日志能力后，内置科技快照与 `ResearchCompleteEvent` 不再另发同一变更的历史。已见过／固定过的持久源不可用时，保留待处理记录并记录错误，不退回快照生成第二份事件。旧版没有该能力的 StarCore 继续使用原有观察模式。

## 三次真实启动

| 启动 | 原生日志末尾 | Chronicle 科技历史 | 初始归档 | 正常剧情收件箱 | 验证 |
| --- | ---: | ---: | ---: | ---: | --- |
| 1 | 136 | 133 | 3 | 130 | 首次轮询前提交 3 次；再在原生锁内连续提交 130 次，跨页全部入史；停用 Chronicle 后再提交 3 次 |
| 2 | 139 | 139 | 3 | 136 | 自动补齐离线的 3 条，再提交 3 条；旧事件和时间未变 |
| 3 | 139 | 139 | 3 | 136 | 正常重启及多次后台扫描，全部稳定，无重复 |

每次服务端退出码均为 0，有正常停止／插件关闭日志。独立检查同时核对原生 SQL 状态、待归档回执、逐序号文件、Chronicle 游标／ingress／事件／剧情收件箱及公开 API 输出；无 PENDING 遗留、无科技观察回调副本、无虚构参与者，无实例／奖励／贡献／世界记忆副作用。

10 项证据回归对真实证据副本删回执、改源状态、删重启恢复项、错误开启归档触发、改时间、添个人归属、删剧情收件箱、添加源读取失败日志及改异常退出码，均要求检查器拒绝。原始证据不修改。

首次两轮专项测试因新测试配方分别缺少必填事实、缺少明确地区而失败，各为 42 项中的 1 error；修正测试输入后第三轮 42 项通过。失败日志全部保留。三次 Paper 启动没有失败轮次。本测试的无玩家 Chronicle 停用用于验证缺席补读，不是在线热卸载认证；生产维护仍采用整服停止／重启。

## 其他持久来源审计

| 来源 | 已有能力 | 接入前仍需解决 |
| --- | --- | --- |
| 科技 | 状态／outbox 同提交、存储域、连续序号、持久分页 | 本轮已接入；真实研究者关联仍缺，不能记个人文明贡献 |
| 资源转移 | 持久存储域、序号、双方国家、资源、分类、提交时间 | 区分史诗援助与其他用途、跨入口历史去重及真实主体，不把转移改称生产 |
| 指定首都 | `NationCapitalDesignationStore` 的持久追加日志、操作 UUID、序号、操作者与提交时间 | 与其存储域／重置语义共同核验，接通 Chronicle 并做迁移及真实操作验收 |
| 贸易 | `TradeHistoryService.getRecordsAfter(executedAt,recordId,limit)` 读取完成记录 | 成交时间不等于保存顺序；迟到保存、稍后转为 COMPLETED 会落在游标已越过的时间范围，存在漏读；记录还可更新／清理，尚非无损提交日志 |
| 市场成交 | 真实撮合后调用 `saveRecord` | 保存失败会记录严重日志但仍返回成交／发布事件；需要可靠提交日志或 outbox，不能承诺当前协议覆盖全部成交 |
| 经济统计 | `TransactionHistoryService` 的时段收入／支出总额 | 没有逐笔 ID、主体、提交时间和分页，不能替代真实交易回执 |

贸易／文明生产贡献、首都接线、其余全类历史、UNKNOWN 对账、全栈 Folia、长期容量及部署继续列为待办。

## 复验与交付

```bash
env MAVEN_OPTS='-Xmx768m -XX:+UseSerialGC' ./mvnw -B -ntp verify
python3 tools/technology-history-paper.py prepare --starcore /path/to/frozen-starcore.jar --runtime /path/to/initialized-paper --attempt NEW-ATTEMPT --port 29658 --java-home /path/to/java21
python3 tools/technology-history-paper.py run --run run/PREPARED-DIRECTORY --java-home /path/to/java21 --boot 1
python3 tools/technology-history-paper.py run --run run/PREPARED-DIRECTORY --java-home /path/to/java21 --boot 2
python3 tools/technology-history-paper.py run --run run/PREPARED-DIRECTORY --java-home /path/to/java21 --boot 3
python3 tools/inspect-technology-history.py run/PREPARED-DIRECTORY
STARCHRONICLE_TECHNOLOGY_RUN=run/PREPARED-DIRECTORY python3 -m unittest discover -s tools -p test_technology_history.py -v
```

准备工具使用新的世界、插件数据和回环端口；拒绝覆盖已存在的 attempt／启动日志，执行时复核全部 JAR 指纹。本地交付在 `target/technology-history-linux-20261126/`，Git 中的[清单](verification/technology-history-linux-20261126-summary.json)与[证据包](verification/technology-history-linux-20261126-evidence.zip)包含源代码快照、97 套件 XML、实际成功／失败构建日志、三次运行日志、独立检查和原生／Chronicle 数据。

证据包 1,173 个成员、4,954,771 字节，SHA-256 `8f3c6fbdff79e75312734a70137a5b687eb7f27eef35e5884bed6f5a2ebd5b4c`。ZIP 完整性、逐成员哈希已核对；各原生配置副本中的同一个生成地图密钥均已脱敏，并确认其值未留在包内。
