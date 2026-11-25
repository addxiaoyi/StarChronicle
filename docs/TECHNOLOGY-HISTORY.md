# 科技提交历史与双插件验收（2026-11-25）

Chronicle 接入 StarCore 已持久化的科技解锁／撤销回执。一次轮询前发生的多次变化分别入史，重复解锁不追加回执，重启后继续使用同一来源域与游标。回执只证明国家科技状态变化，**不记录研究者，也不证明定时研究完成或个人文明贡献**。

审阅入口：[Chronicle 草稿 PR #6](https://github.com/addxiaoyi/StarChronicle/pull/6) 与 [StarCore 草稿 PR #5](https://github.com/addxiaoyi/starcore/pull/5)。

## 配套源码与保证

Chronicle 分支 `codex/technology-history-ingress-20261125`，源码与工具提交 `8ead43c`，基于贸易检查点 `07d1157`。StarCore 分支 `codex/technology-history-acceptance-20261125`，天气锁修复源码 `7cc74160`、外交锁修复 `3a2f3cff`，基于贸易日志检查点 `b2a21106`。本轮未修改另一个 `/home/add/starcore` 工作区。

`StarCoreTechnologyHistorySource` 读取 `TechnologyService.historyAuthorityDomainId()` 和 `technologyChangesAfter(sequence, limit)`。页上限 100；读取前后核对同一服务实例与域，即使空页也核对。域更换、未来游标、缺失原生回执、页内缺序或非法字段时保留游标并等待对账；旧服务或空能力不会伪造来源。

来源为 `starcore.technology.history.v1`，事件 ID 为 `starcore:technology-history:<domain>:<sequence>`。`UNLOCKED`／`REVOKED` 对应 `TECHNOLOGY_UNLOCKED`／`TECHNOLOGY_REVOKED`，保存原操作 UUID、国家、科技键、时代和提交时间。`time_basis=COMMITTED`，`tech.researcher=NOT_RECORDED`，`tech.research_completion=NOT_PROVEN_BY_UNLOCK`；participants 保持空集合。既有 OBSERVED 科技快照与研究完成事件仍保留各自语义，不把观察、直接解锁和研究完成合并成同一种凭证。

原生 `TechnologyHistoryStorage` 将最新回执与科技状态放入同一原子快照；在覆盖下一条前，将已有回执幂等归档为按域和序列寻址的小文件。启动和查询可以排空已提交待归档项；缺文件、域冲突或不确定保存会拒绝后续操作。首次安装前已有的解锁仅是基线状态，不产生新的解锁事件，也不补造旧历史。该协议依赖单一原生模块权威，未验证多个服务器共同写同一科技状态库。

Chronicle 复用贸易的事务收件箱，页与游标同事务，规则引擎提交后才 ACK。此次增加按来源读取待处理记录；贸易或科技某一来源失败只保留自身待处理页，另一来源仍能消费。错误保留在日志，未增加自动跳过坏回执、重置域或强制改绑命令。

## 实服发现的原生 SQLite 竞争故障

早期配对 `393141d0…b908`／`41a4b79a…af41f2` 已完成科技、贸易、成员和 DUNGEON 的三次启动，但 WAR 首次停服出现 `Failed to disable STARCORE module: weather`／`SQLITE_BUSY`，整轮被严格检查器拒绝。即使该 WAR 目录的控制器和六个进程都正常退出，也不计入最终通过。

天气存储原先在延迟事务中先读修订号，再申请写锁；另一个模块持有 SQLite 写锁时，这种升级会立即失败。现在 `advanceRevision` 先用无损 UPDATE 预留写锁，随后读取并比较真实修订号，等待其他模块提交后继续。完整快照、单国保存、删除三种路径共用此修复；原有旧版本冲突检测保持有效，MySQL 路径不做该预留。

真实 SQLite 测试先复现三种失败，随后验证修复：持有其他表的写事务时天气操作等待，释放后正常保存或删除，修订号仅增加一次。首次红例日志为12项中3项失败；收紧线程开始观察后的红例为3项全部失败。失败 XML、真实 WAR 日志均保留。

天气修复中间构件 `54e0fa62…baba23` 重新通过科技、贸易、成员和 WAR，但新 DUNGEON 首次启动的第二次外交接受发生 `SQLITE_BUSY`。Chronicle 将 `DIPLOMACY_ACCEPT` 保留为 `UNKNOWN`，控制器失败后正常停服，严格检查器拒绝整轮。该目录仅有 boot1，不对未知结果盲目重试，也不追加伪造的后两轮成功。

`SqlDiplomacyStateStorage` 的快照、邀请回执和接受回执共用同一延迟事务，也存在读后写升级问题。此次在读修订前仅针对 SQLite 预留写锁，等待后仍比较真实修订号，国家关系和回执仍同事务。新增六项真实数据库测试：三个操作在另一个表持有写锁时等待并在释放后成功、重启恢复且回执重放不再递增修订；三个旧快照操作均拒绝覆盖且不留下回执。修复前六项中三个竞争用例失败，修复后六项全通过；真实 UNKNOWN 日志和红例 XML 均保留。

## 构建验证

| 项目 | 结果 |
| --- | --- |
| Chronicle 完整 `verify` | 911 项／97 套件，0 失败／错误／跳过 |
| 原生科技／天气／外交／API 专项 | 62 项／12 套件，0 失败／错误／跳过 |
| 原生常规 `verify` | 汇总 7,220 项／1,437 套件，0 失败／错误、21 跳过；沿用 POM 原有排除配置 |
| 原生格式与 SBOM | 最终完整构建、Spotless 与既有 JaCoCo 门槛通过；在线 SBOM 单独生成成功 |

Chronicle 新增六项测试覆盖精确变更、空主体、无在线消费、消费后崩溃重放、来源背压隔离、空能力和空页域固定、服务／域替换、缺序与非法回执。原生62项包括科技／天气／API的32项，以及外交竞争、状态冲突、玩家重启流程、生命周期、关系取消与战争同步六套件30项。首次完整回归测试通过后，新增测试文件的 Spotless 检查失败；修正格式并提交 `4f948219` 后完整 verify 通过，保留两份构建日志。没有因文档更新重复构建。

冻结 Chronicle JAR SHA-256：`393141d0eb89437717fe0a80bb52817cbf55aec3e0a8f020d833e57b3a41b908`。

冻结 StarCore JAR SHA-256：`3bcd464fcdac90eb3098908befa18413fb923376da1ffd3820e531749c6cf9bc`，源码检查点 `4f948219`，本机 `target/technology-diplomacy-accepted-starcore-1.0.0.jar`。中间天气构件 `54e0fa627a477956a62e76967c351d8defdd5c4b5a257371a043888847baba23` 继续保留在 `target/technology-accepted-starcore-1.0.0.jar`；不能用它的部分通过替代最终构件验收。

## 真实流程与复验

使用 Java 21、Paper 1.21.11、Node 22.22.2 与 Mineflayer 4.39.0。所有目录使用回环地址、独立数据库／世界、四个非 OP 实际客户端。科技流程直接调用真实 `TechnologyService.unlock/revoke`，不伪造研究完成回调；选择可用定义 `advanced_archery`，所属国家为测试 A。

第一轮先观察0条回执，然后同一控制器动作依次解锁、撤销、再解锁，观察3条；再次解锁已解锁科技，仍为3条。第二轮先验证3条恢复，再撤销，产生第4条。第三轮验证最终撤销状态与全部4条历史保留。每轮 A 实际执行 `/chronicle contributions`，结果为空。

`inspect-technology-paper.py` 核对6次公开 API 观察、原生 SQL 科技状态与待归档快照、域标记和4个不可变回执文件、Chronicle 游标／收件箱／事件及规范哈希、实际命令、时间顺序、构件指纹和完整停服。`test_technology_acceptance.py` 有23项证据回归，含两个真实原生停服失败负例，以及删除瞬时撤销、修改状态、复用操作 ID、错域、旧 SQL 快照、缺 ACK、伪造研究者与贡献等检查。史诗贡献回归增加真实外交 UNKNOWN 失败负例，每条路线17项。

```bash
export EPIC_MINEFLAYER_MODULE=/path/to/node_modules/mineflayer
# 先用 prepare-epic-paper.py prepare/compile 创建新目录，配套最新冻结原生构件
python3 tools/epic-paper-session.py --run run/NEW-DIRECTORY --java-home /path/to/java21 --node /path/to/node --boot 1 --scenario tech-first
python3 tools/epic-paper-session.py --run run/NEW-DIRECTORY --java-home /path/to/java21 --node /path/to/node --boot 2 --scenario tech-second
python3 tools/epic-paper-session.py --run run/NEW-DIRECTORY --java-home /path/to/java21 --node /path/to/node --boot 3 --scenario tech-third
python3 tools/inspect-technology-paper.py run/NEW-DIRECTORY
STARCHRONICLE_TECHNOLOGY_RUN=run/NEW-DIRECTORY STARCHRONICLE_EPIC_FAILURE_LOG=/path/to/legacy-policy-shutdown-failure.log STARCHRONICLE_WEATHER_FAILURE_LOG=/path/to/weather-shutdown-failure.log python3 -m unittest discover -s tools -p test_technology_acceptance.py -v
```

当前原生适配器与验收针对 Paper。MySQL 实库、全栈 Folia、多服务器科技写者、长期容量、目标第三方组合仍待验证；解锁回执不能产生个人贡献。贸易资产共同提交、资源完整游标、文明生产来源与自动记忆精确构件验收仍是后续工作。旧库贡献升级继续由旧构件的独立报告证明。

## 最终组合与保留轮次

最终组合固定为 Chronicle `393141d0…b908`／StarCore `3bcd464f…cf9bc`。五组均用独立世界、数据库与端口，各执行三次完整启动，禁止覆盖失败 boot。五组现已全部完成；30个进程正常退出，23＋20＋15＋17＋17＝92项证据回归全部通过，无跳过。

此前十个目录完整保留：原生 `41a4b79a…af41f2` 的科技、贸易、成员、DUNGEON 各三轮通过，WAR 虽控制器三轮完成但天气停服错误被拒绝；中间原生 `54e0fa62…baba23` 的科技、贸易、成员、WAR 各三轮通过，DUNGEON 首轮外交接受 UNKNOWN 被拒绝。旧目录的通过只代表对应构件与已执行路径，不能替代最终五组。

后续资源来源审计确认 SQL `transferReceiptsAfter` 只按 `sequence > ?` 查询，`latestTransferSequence` 从现有行 MAX 推导；尚无独立持久末尾可检测最后一条丢失。文件存储也只验证严格递增，未强制连续。应先补真实持久头、连续页／未来游标校验及迁移再接入，且回执无操作玩家，不生成个人贡献。


| 最终目录（均位于 `run/`） | 端口 | 三次启动后的证据回归 |
| --- | --- | --- |
| `paper-epic-393141d0-dungeon-technology-final-journal-1` | 29691 | 科技23项 |
| `paper-epic-393141d0-dungeon-technology-final-trade-1` | 29692 | 贸易20项 |
| `paper-epic-393141d0-dungeon-technology-final-member-1` | 29693 | 成员15项 |
| `paper-epic-393141d0-dungeon-technology-final-epic-1` | 29694 | DUNGEON史诗贡献17项 |
| `paper-epic-393141d0-war-technology-final-epic-1` | 29695 | WAR史诗贡献17项 |

WAR 使用原生和谈结束，DUNGEON 使用三秒原生生存房间；这组实际证据覆盖上述业务链、两次重启、唯一结算、贡献与个人后续，不代表战场胜利或实际生产服验收。

[证据包](verification/national-epic-technology-history-20261125-evidence.zip)与[汇总清单](verification/national-epic-technology-history-20261125-summary.json)随 Git 保存。包大小10,347,766字节，SHA-256 `6b468ee137fda22d8da9c709cc98a5d13059a1683c61fa8425a042094ef36b27`；2,370个成员哈希、15份配置脱敏均已核验。包含全部15个运行目录的选定日志／数据库／科技归档、专项与完整回归 XML、两种实际故障及原生竞争红例、构建与独立检查日志。世界、依赖、JAR与完整本机交付留在 `/home/add/starchronicle-technology-history/target/national-epic-technology-history-20261125/`，未作为生产部署。

史诗负例复验需额外设置 `STARCHRONICLE_DIPLOMACY_FAILURE_FIXTURE` 到保留的 `paper-epic-393141d0-dungeon-technology-weather-epic-1/evidence/epic-fixture.jsonl`；原policy失败日志也随包保留在 `verification-logs/original-policy-failure-20261125.log`。重建后的新JAR必须重新建立目录并验收，不能借用本报告的指纹。
