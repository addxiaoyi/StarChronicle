# 贡献账本与成员复核验收报告

后续保护增量的 887 项及所选五插件组合验收见[组合报告](PRODUCTION-COMBINATION-TEST-REPORT.md)。本文保留 882 项贡献／成员检查点。

更新：2026-11-26（UTC）。本轮接通国家史诗的国库／资源贡献、公开查询和普通成员复核／退出／重新加入流程。同一对构件在隔离 Paper 上完成 DUNGEON／WAR 各三次正常启停、唯一结算和重放核验；十阶段整体仍未完成。

## 构件与构建

| 项目 | SHA-256／源码 | 结果 |
| --- | --- | --- |
| StarChronicle | `a85e1f5065db57dfee498232c1610e22229053afc38d72aa2185ecf4d9d246d3`；源码提交 `a1500a827c13f98f5298f81fb9585427fd925e76` | 本轮完整 `verify`：93 套件、882 项，0 failures／errors／skipped |
| StarCore | `44f450eccc95590aadc54736109e012d5a52bbae459f4107afa498fd4528518d`；生产修复 `324072a5`、构建基线 `c5593e0d` | 沿用 2026-11-25 同一冻结构件；既有专项 90 项、常规回归 7,202 项零失败／错误、21 skipped，本轮没有重跑这两组原生测试 |
| 独立证据检查 | `tools/inspect-epic-paper.py`、`tools/test_epic_acceptance.py` | 两路线独立检查通过；各 14 项证据破坏回归通过，无跳过 |
| 旧库补录探针 | `tools/ContributionUpgradeProbe.java` | 两份旧真实数据库的副本均通过分批、重新打开、重放及历史表不变校验 |

Java 21、Paper 1.21.11、Node 22.22.2、Mineflayer 4.39.0。主项目命令为 `env MAVEN_OPTS='-Xmx768m -XX:+UseSerialGC' ./mvnw -B -ntp verify`，保留正常测试 fork 与 JaCoCo。最终日志为 `target/ledgers-verify-final-20261126.log`。原生常规回归保留当时 POM 的测试排除配置，边界见[前一报告](NATIONAL-EPIC-TEST-REPORT.md)。

## 实现与回归覆盖

- `TREASURY_AID`／`RESOURCE_AID` 的已核验原生回执、规范历史、剧情 inbox 和玩家／国家各一条贡献在同一 SQLite 事务提交。准备、UNKNOWN、拒绝和路线投票不计贡献；注入贡献写入失败时，整笔本地投影回滚，可由持久原生回执恢复。
- 国库援助 API 与史诗入口复用相同事件、请求和贡献键，同一转账只计一次。执行原生操作之前检查贡献金额／单位限制；查询按单位使用精确十进制汇总。
- 后台恢复只读取 provider/domain 固定的 COMMITTED 回执，每批最多 50 笔（引擎上限 100），不调用原生扣款。每笔核对玩家和国家两条归属是否齐全。
- 公开 API 与 `/chronicle contributions`、`review`、`leave`、`reviews` 可用；复核与退出后的 `join` 在 StarCore NationModule 成员变更锁内检查当前国籍与国家存续。普通成员可操作；无服务、缺权限、换国未恢复、异步／错误身份回调和已完成实例均保守拒绝。
- 显式退出持久保存，后台 resume 不再自动拉回，原选择保留。旧任务 gate 只有在存在匹配 EXITED 原成员证据时才允许保持可读且停止执行；已经提交的同国证明保留历史归属，缺少退出证据的成员丢失仍被拒绝。

新增 16 项测试：贡献 5 项、成员复核 5 项、原生普通成员权威 3 项、退出与任务闸门联动 3 项。另更新公开服务和 SQLite fixture 的既有用例。API 约束见 [API.md](API.md#贡献查询与成员复核)。

## 实际服务端流程

| 路线／运行目录 | 实际结果 |
| --- | --- |
| DUNGEON：`run/paper-epic-a85e1f50-dungeon-ledgers-20261126-2`，端口 29642 | 12 项已提交史诗操作；两笔国库＋两笔资源转账；8 条贡献；3 次正常启停；1 次结算；A/B/D 各一份个人后续 |
| WAR：`run/paper-epic-a85e1f50-war-ledgers-20261126-1`，端口 29643 | 13 项已提交史诗操作；相同四笔援助与8条贡献；3 次正常启停；1 次结算；A/B/D 各一份个人后续 |

boot 1：四个非 OP 协议客户端登录，A/B 实际制作纸并通过原生任务提交，原生国库／资源／外交准备完成后查询贡献。普通成员 D 通过真实 NationService 从 A 国转到 B 国，复核因原国籍不符被拒绝；恢复 A 国后，无使用权限仍被拒绝，有权限的普通成员复核成功并显式退出。A/B 能继续推进，副本／战争产生持久完成回执，停服前保持未结算检查点。

boot 2：后台恢复后 D 仍为 EXITED；显式重新加入成功。完成唯一结算并执行重放拒绝。boot 3：再次查询、重放、核对最终数据库。三次启动各自查询的公开贡献结果与最终 SQL／JSON、原生回执的主体、数量、单位、域和时间一致，始终只有 8 条。控制器记录六次服务端与客户端正常退出，独立检查要求端口已释放。

种子仅经原生服务提供初始资金、资源和制作材料，不改任务进度或伪造完成事件。DUNGEON 是三秒原生生存房间；WAR 以 **native principal peace 原生和谈** 结束，不代表战场胜利。该组未安装第三方 NPC／保护插件，也不代表生产部署或全栈 Folia。

14 项证据回归在复制的真实运行目录上分别删除／篡改制作、第三次启动、进程退出、重放、原生任务 shard、权威域、外交主体、个人后续、贡献数量／金额与成员重启证据，检查器均正确拒绝；真实旧停服失败日志也会被拒绝。

## 旧候选数据库补录

来源为前一成功目录 `paper-epic-57345b09-{dungeon,war}-linux-policy-1` 的停止数据库。先复制到 `target/contribution-upgrade-20261126/{dungeon,war}.db`，原文件与 2026-11-25 已归档数据库逐字节 SHA-256 相同。

探针在第一段数据库生命周期恢复 1 笔，关闭后重新打开，再依次恢复 2、1、0 笔；每份最终 4 笔援助、8 条贡献，原生调用数为 0。`epic_operations`、`epic_operation_providers`、`instances`、`outcomes`、`quest_gates`、`quest_gate_completions`、`story_choice_log` 内容前后一致。探针、结果数据库、日志与原件／副本哈希均入证据包。

这是旧真实数据库副本上的本地恢复验收，没有执行旧服务器整服升级启动，也没有补造未知来源的贡献。

## 失败记录与修复

`run/paper-epic-38313625-dungeon-ledgers-20261126-1` 使用中间构件 `38313625210155170510ad63538b46d71a046ffb915c4c0cb238c349d49b210b`。boot 1 的真实制作、四笔援助、8 条贡献和成员退出均成功，但退出后 `advance` 读取旧 gate 时被错误识别为成员数据损坏，整轮 FAILED。客户端／服务端正常退出；保留原运行目录、数据库、fixture 失败和控制器日志。

修复将“有 EXITED 原国籍证据的历史成员”和“无证据的数据丢失”分开，pending 查询在 LIMIT 之前排除 EXITED／PENDING，且支持个人实例唯一成员退出后的历史读取。修复后的第一次专项测试另有 30 个 errors，原因是旧原始 SQLite 测试 fixture 未创建成员复核表；已补 fixture，最终 882 项通过。专项失败日志被保存，未声称保存已被后续运行覆盖的旧 XML。

## 可携带证据与后续

[清单](verification/national-epic-ledgers-linux-20261126-summary.json)与[证据包](verification/national-epic-ledgers-linux-20261126-evidence.zip)保存上述两个成功目录、一个真实失败目录、本轮完整／专项日志、最终 XML、旧库恢复副本和验收工具。284 个成员逐一校验 SHA-256，ZIP 完整性通过，生成的地图访问密钥已脱敏。证据包 SHA-256：

`6ff49cc6cd98e42edcc485e792149645962ca360bc4811f28e0e4e716346cd3b`

归档同时保留旧的固定名称构建日志和原生专项 XML，它们的时间戳与旧来源仍可见，不计为本轮重新执行。两个冻结 JAR 及原生源码包在本机 `target/national-epic-ledgers-linux-20261126/`，不随 Git 分发；新设备重建须记录新哈希并另开运行目录。

阶段 4 的史诗援助来源和公开查询、阶段 5 的 Paper 普通成员流程已获本轮证据；真实贸易／文明生产来源、原生精确历史接线、全栈 Folia、第三方组合和长期容量仍需推进。第三方版本已由代理按用户授权选定为 FancyNpcs 2.10.1-java21、WorldGuard 7.0.17、WorldEdit 7.4.2，固定哈希见[选择清单](verification/production-combination-selection-20261126.json)，在此检查点仅选定；后续功能验收见组合报告。
