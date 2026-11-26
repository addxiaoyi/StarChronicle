# 工厂原子加工、完整历史与文明生产贡献

2026-11-26：Chronicle `aed1424` 接通配套 StarCore `c09545ba` 的完整加工历史。原生开工同时扣全部原料并保存冻结批次，交付同时发全部成品并完成批次；Chronicle按原操作者和原国家保存生产贡献。主项目956项、原生7,281项（21项既有跳过）、最终构件Paper三次启停和28项独立证据回归通过。十阶段整体仍在推进。

## 原生原子边界与实际入口

`ProcessingRun` 固定run UUID、工厂、原国家、原actor、配方、批数、全部原料（含energy）、精确产出、开工／可交付／完成Instant。STARTED与扣料／在制工厂共用持久提交，COMPLETED与全部产出／空闲工厂共用提交；AUTOMATIC为没有个人actor的系统即时加工。资源余额历史与其同事务提交，通过run关联完整前后余额。

工厂和库存共用ResourceModule锁；玩家变更先获取真实NationModule锁，再获取资源锁。入口与服务层都核验当前实际国主及成员关系，普通成员和旧国主拒绝，无管理员绕过；国主交接不改写旧批次原操作者。无actor旧API保留为可信系统入口，不推定玩家。工厂读取返回副本，公开restore不能覆盖原生权威。

`/resource factory` 提供list、recipes、`build <工厂UUID> <类型> <名称>`、`start <工厂UUID> <配方ID> <批数> [批次UUID]`、`complete <批次UUID>`、receipt、upgrade和on/off。稳定run重试返回同一结果，参数冲突拒绝，已完成run不会开启新轮次。原料不足不缩减批数；产出溢出则保留任务等待，不丢弃成品。建设／升级当前明确免费，尚无建设经济成本。

默认配方已改为实际资源：mineral→industrial、agricultural→luxury、industrial→chemical、industrial+chemical→strategic，能源合并计入投入。配方支持的资源、正数、finite能源、等级及时间均校验；数量／时间溢出在提交前拒绝。能源对整轮向上取整。产出使用精确冻结数量，不经grant政策加成；升级、配方重注册与重启不会改变旧任务。实际完成时间记交付时刻，可交付时间保持开工时冻结值。

## 持久回执与故障恢复

`ProcessingHistoryService` 注册于原生服务表：`processingHistoryAuthorityDomainId()` 返回独立域，`processingChangesAfter(after,limit)` 连续分页1..128条，`processingReceipt(runId)` 返回该批次最新完整回执。

`ProcessingHistoryStorage` 在ResourceHistoryStorage外层，将序号、唯一提交令牌、单条pending和库存／工厂一起保存。下一次资产提交前先归档pending，文件为 `resource/processing-history/<domain>/events/<bucket>/<sequence>.receipt`，每桶1024条。`runs.sqlite` 保存域与run／kind／sequence索引，允许仅落后一条pending；缺索引、序号缺口、换域、回退和完整回执不符均拒绝，不能当作从未开工。启动核对所有未完成run与存档工厂，缺整个工厂或缺run也会报错。

提交前失败恢复库存和工厂；提交成功但丢回包时只读核对完整持久快照，不再执行资产操作、不补偿grant。无法确认则隔离资源读写及旧内存停服保存。缺档或不可归档会阻止后续提交。原实现的enable本来就是先initializeServices再loadState；本次没有修复不存在的启用顺序问题，而是加入完整run、索引和恢复核对。

## Chronicle与贡献口径

来源 `starcore.processing`，事件为 `PROCESSING_STARTED`、`PROCESSING_COMPLETED`、`PROCESSING_AUTOMATIC`，稳定ID `starcore:processing:<domain>:<run>:<kind>`。完整回执与游标／ingress一起暂存，历史、完整最终回执、贡献和剧情收件箱同事务提交；最终回执与ingress不一致时不能ACK。`ChronicleService.processingHistoryReceipt(eventId)` 可查完整证据，事件事实保留SHA。来源一旦固定不可降级、换域或跳过坏页。

只有带原actor的COMPLETED产生CIVILIZATION贡献；完成记录必须有完全相同的STARTED，包括原主体、配方、投入、产出和冻结时间。每种产出分别给原玩家及原国家记一行，operation为 `starcore:production:<domain>:<run>:<resource>`。个人和国家是同一产出的两个统计维度，不能相加当作全服产量；系统即时加工与STARTED只入史。资源余额历史不重复增加贡献。数量为正signed-long范围的精确整数，不将工业品、化工品等混成无单位总数。

首次历史追赶只归档但建立贡献账本，不补发旧剧情效果；后续正常／离线补读进入收件箱。schema10阻止旧插件跳过生产完整证据。真实schema9 Paper库副本升级保留45张旧表、1,039行，新增完整回执和ingress两表；旧实际 `17508f9f` JAR拒绝打开，拒绝后数据库字节未变。这是离线库升级，不是生产整服迁移。详见[API](API.md)。

## 固定构件与验证

| 构件 | 源码 | SHA-256 |
| --- | --- | --- |
| Chronicle | `aed1424` | `cb9fe0a565d29f7492563bba9d9bc1389862edb70ba75a2ae1dceabd87620f6e` |
| StarCore | `c09545ba` | `a8a6a8a23c578f0aed79578f11504915acd48b36ad6d449c61e57e46ef2f894f` |
| Paper1.21.11-132-c5eb079 | Java21 | `5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba` |

Chronicle102套件／956项，零失败／错误／跳过，完整日志 `target/processing-chronicle-verify-20261126-3.log`。新增11项加工历史测试覆盖原主体、多资源贡献、128条分页、系统无贡献、long数量、缺STARTED、篡改主体／配方／产出、ingress／贡献失败重启、完整ACK与来源损坏；39项相关回归通过。123个Java文件逐个与sources JAR一致，API.md与产品JAR一致。

原生1,440套件／7,281项，零失败／错误、21项既有跳过，未扩大排除，Spotless、API兼容、打包、SBOM通过，日志 `target/processing-native-verify-20261126-2.log`。新增30项工厂测试覆盖真实NationModule授权与交接锁、并发、输入不足、冻结配方、溢出、真实命令、SQLite／文件故障、丢回包、无法确认隔离、旧任务、索引缺失／回退、pending归档和整个在制工厂丢失。

## 隔离Paper三启

最终目录 `run/paper-processing-cb9fe0a5-20261126-2`，回环29661。两名实际Mineflayer非OP玩家通过网络进入offline-mode服务器，以真实命令建设、开工、领取、查回执；fixture只种子资源、注册测试配方、调用真实国家服务交接国主和触发系统加工，不直接改数据库伪造凭证。

| 启动 | 原生加工／资源记录 | Chronicle加工游标 | 贡献行 | 实际验收 |
| --- | --- | --- | --- | --- |
| 1 | 134／136 | 4 | 4 | 初始1批系统加工归档；成员拒绝、开工重试／冲突／提前领取、A→B国主交接；B领取A开工2批；B再开在制任务；停用Chronicle后系统加工130批 |
| 2 | 137／139 | 137 | 12 | 实际enable恢复在制run；配方改为3600秒／999工业品但旧run仍冻结30秒／2工业品；B→A交接，旧国主拒绝；A领取B旧run并再完成一轮；重放134个已完成批次无新增资产／历史 |
| 3 | 137／139 | 137 | 12 | 查询原actor，重复complete／start和系统重放保持相同库存／历史／贡献 |

6个服务器／客户端进程全部正常exit0，三个不同服务器PID。最终3个玩家完成批次：A的2批产工业品4／化工品2，B的1批产工业品2／化工品1，A的末轮1批再产工业品2／化工品1；原actor始终保留。131个系统批次不产生个人贡献。初始矿产／能源各20,000，最终矿产19,595、能源19,865、工业品270、化工品135，与双历史逐条重放一致。

独立Python检查器核对原生数据库、两类不可变文件／pending、run索引、冻结工厂、资源完整前后余额、Chronicle完整回执／ingress／事件／贡献、公开API、真实玩家命令与非OP拒绝。3条完成事件各有持久剧情触发回执；首个国家剧情实际激活，后续被同一活动实例接收，不能将在线情形误判为“无实例”。最终零pending。

28项证据回归全部通过：正例与丢STARTED／回执、改actor／产出／时间／库存／run／索引、重复贡献、改SQL索引字段、缺完整ACK／剧情回执、schema／游标退回、伪造OP／交接／客户端响应、异常进程退出、PID重用、错误日志和构件指纹漂移。测试只破坏临时副本，不改原证据。

## 失败记录与未完成范围

原生首次专项53项出现1fail／2error：fixture未注入playerProfiles导致交接NPE，另一个库存上限测试的一次grant被已有安全上限拒绝。修正fixture及分次合法grant后53项通过，扩展后60项通过，最终30项工厂测试纳入7,281项全量。Chronicle第一次完整构建因错误JavaDoc替换编译失败；第二次因旧测试硬编码schema9失败；改为SCHEMA_VERSION且保留原历史数据校验后956项通过。真实失败日志和XML全部保留。

中间原生 `26fd2f4c…` 的7,280项与 `paper-processing-cb9fe0a5-20261126-1` 三启也独立通过，但当时没有“缺整个在制工厂”的新增保护；其第一启Chronicle实际游标为3。最终以新原生 `a8a6a8a2…` 全新目录重跑，第一启增加确认游标4的检查点。中间证据不替代最终构件；本次最终Paper轮次没有失败重写。

旧在制工厂没有完整run证据，保留且不自动补发；人工核对／取消迁移入口尚未实现。建设／升级经济策略仍未实现。run索引启动会扫描历史STARTED项，长期规模与运维待压测。本次未验证MySQL、线上认证账号、完整五插件双路线、战场胜利、全栈Folia或生产部署。研究者真实来源、UNKNOWN对账／安全新轮次、市场托管／管理员核对仍在[剩余工作](REMAINING-WORK.md)。

## 跨设备复验与归档

```bash
./mvnw -B -ntp verify
python3 tools/processing-history-paper.py prepare --starcore /path/to/starcore/target/starcore-1.0.0.jar --runtime /path/to/initialized-paper --attempt NEW-ATTEMPT --port 29661 --java-home /path/to/java21
python3 tools/processing-history-paper.py run --run run/PREPARED-DIRECTORY --java-home /path/to/java21 --node /path/to/node --client-module /path/to/mineflayer --boot 1
python3 tools/processing-history-paper.py run --run run/PREPARED-DIRECTORY --java-home /path/to/java21 --node /path/to/node --client-module /path/to/mineflayer --boot 2
python3 tools/processing-history-paper.py run --run run/PREPARED-DIRECTORY --java-home /path/to/java21 --node /path/to/node --client-module /path/to/mineflayer --boot 3
python3 tools/inspect-processing-history.py run/PREPARED-DIRECTORY
STARCHRONICLE_PROCESSING_RUN=run/PREPARED-DIRECTORY python3 -m unittest discover -s tools -p test_processing_history.py -v
python3 tools/verify-processing-schema.py --source /path/to/schema9-copy.db --old-jar /path/to/schema9.jar --new-jar target/starchronicle-0.1.0-SNAPSHOT.jar --java-home /path/to/java21 --output target/NEW-UPGRADE
```

Java21、Python3.11+、支持1.21.11的Mineflayer（本机4.39.0）；runtime含paper.jar、cache、libraries。新构件／失败重试必须新attempt。两仓候选分支不变，原生只操作 `/home/add/starcore-national-epic`，不修改另有工作的 `/home/add/starcore`。

停服备份须包含原生数据库、国家数据、resource/history、resource/processing-history（含runs.sqlite）、SQL权威标记和Chronicle库；不能删除标记／索引重新初始化。schema10及原生run协议回退需恢复匹配整套备份。

[跨设备清单](verification/processing-history-linux-20261126-summary.json)及[证据包](verification/processing-history-linux-20261126-evidence.zip)共11,101成员／29,033,422字节，SHA-256 `8f9d1b1681e77020ac5cb1778acf5c4b9ec946a2e7c6f5d3b8312a3c4efc6bc3`。包括两仓源码、全量XML、真实失败、中间与最终六次停服快照、公开回执、原schema9库副本／升级／旧JAR拒绝。两个隔离服生成密钥已脱敏，ZIP和全部成员SHA已核对。冻结JAR在 `target/processing-history-linux-20261126/`，服务已停止，端口释放。
