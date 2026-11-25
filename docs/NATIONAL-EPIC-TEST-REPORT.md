# 国家史诗候选版测试报告

更新：2026-11-25（Asia/Shanghai）。结论：源码与构件已形成开发检查点；StarChronicle 完整测试通过，StarCore 指定测试通过。**最终候选的双插件重启、结算与国战路线实服验收尚未完成，不能视作生产版本或十阶段完成。**

## 精确构件与构建测试

| 项目 | 构件 SHA-256 | 本次验证 |
| --- | --- | --- |
| StarChronicle | `7d3b4ac7a32dc796e59bcb3004a642e20d8d87153a20ff0e2fbe76f2306395be` | `verify`：90 个测试套件、866 项测试，0 failures / errors / skipped |
| StarCore | `4d97f0f180abcbc3910a9fdc36e308c20aa298f055d8b143797ea517a70057ae` | 7 个指定套件、59 项测试，0 failures / errors / skipped；随后 `package -DskipTests` 成功 |

使用 Java 21、低内存串行构建、`-DforkCount=0`。这次没有新的 JaCoCo 覆盖率测量。StarCore 没有在本轮执行完整回归；历史候选的全量回归曾失败，不能用 59 项专项结果覆盖该事实。精确 XML、构建日志与运行证据见 [构建清单](verification/national-epic-build-summary.json) 和 [证据包](verification/national-epic-checkpoint-evidence.zip)。清单中的 HEAD 是打包时的基线提交，源码当时含未提交增量；可续接的完整源码已纳入本次两库检查点，见 [交接报告](HANDOFF-2026-11-25.md)。

主项目覆盖国家报名／冻结、国家席位、各路线要求、原生提供者与存储域绑定、调用前持久 UNKNOWN 认领、并发／重启不盲重放、回执唯一性、宣战与结束配对、规范结算及同事务后续剧情。原生指定套件如下：

```text
QuestEvidenceServiceTest
DungeonRepositoryEpicReceiptTest
DungeonRepositoryIdempotencyTest
NationFoundingOperationTest
WarDeclarationOperationRestartIntegrationTest
WarCompletionReceiptTest
WarMenuReinforcementOwnerTest
```

## Paper 实服进度

环境为隔离回环地址 Paper 1.21.11，使用两个候选插件及真实非 OP 协议客户端。以下不是同一轮全部成功的报告，失败与中断记录按构件保留。

| 运行目录后缀 | 原生构件 | 观察与结论 |
| --- | --- | --- |
| `dungeon-1` | `9f805e67…` 旧候选 | 首次 JVM 内存崩溃；第二次真实任务、准备操作与路线推进成功。普通副本进入没有国家 ID，检查点失败；确认必须使用国家副本入口。整轮失败。 |
| `dungeon-2` | 未完成准备 | 磁盘不足，没有完整准备清单，未启动。 |
| `dungeon-3` | `9f805e67…` 旧候选 | 首轮完成真实任务、10 项准备操作、路线决议与两个国家副本，保存 ROUTE_APPROVED 检查点并正常停服。第二轮 StarCore 恢复任务证据失败，Chronicle 拒绝启动。未通过完整重启链。 |
| `dungeon-4` | `4d97f0f1…` 最终候选 | 已编译并进入启动过程，用户要求转移设备时中断；日志仅确认插件初始化，没有完整场景或验收通过记录。进程现已结束，不声称正常完成停服流程。 |
| `war-1` / `war-2` | 旧／最终候选 | 仅准备目录；国战路线未执行，`war-2` 尚未编译验收插件。 |

`dungeon-3` 的重启失败已定位并修复：`QuestEvidenceService.loadShard` 将裸文件名与包含 `tracked-evidence/` 的相对路径比较，导致合法证据分片无法恢复。最终候选改为比较正确的文件名；新增回归证明真实分片重启恢复且拒绝错误命名。该套件 14 项全部通过，计入上面的 59 项。**修复后的最终 JAR 仍须重新完成实服三次启动。**

10 项准备操作是两国各自的任务证明、国库援助、资源援助、外交邀请及接受。副本通过 `DungeonService.nationEnterDungeon` 获取带 nationId 的真实持久回执。单人 `tryEnterDungeon` 记录不满足国家结算条件。

## 验收工具及范围

工具在 `tools/EpicCampaignPaperFixture.java`、`prepare-epic-paper.py`、`epic-paper-clients.cjs`、`epic-paper-session.py`、`inspect-epic-paper.py`、`archive-epic-evidence.py`。控制器支持 `scenario first/second/third`，逐项等待真实动作完成并保留控制器、客户端和服务端日志。

测试初始化只通过原生服务提供起始资源、国库和制作材料；任务由实际制作数据包完成，不手写完成 DTO。副本是三秒原生生存房间，不覆盖复杂 Boss。国战脚本计划通过合法移交、离国、建国、宣战与原生和谈结束战争，不覆盖战场胜利。建国费用与新国家保护仅在隔离配置中关闭。

独立检查器尚未对完整成功轮次执行，不能宣称其检查通过。证据归档已完成，保存上述 `dungeon-1/3/4` 原始记录与选定测试报告，并脱敏生成的地图访问密钥；没有把失败改写为成功。

## 下一步验收门槛

1. 使用最终两库源码重新构建，记录新构件指纹，创建新的隔离 DUNGEON 目录；不要覆盖已有运行日志。
2. 依次执行三次启动：真实任务／准备／国家副本 → 重启只读回执、结算和拒绝重放 → 再重启验证只产生一次结算与后续剧情。
3. 在独立新目录完成 WAR 路线的建国、宣战、结束回执与相同重启链。
4. 执行独立检查器、归档新证据，扩大原生回归并处理失败，再评估生产组合和后续阶段。

其他已知限制：被拒绝操作尚无重置／重试 API；某些操作次数只由确定性意图 ID 与计划约束限制；首次启动的 sourceEventId 依赖可信调用者，运行时传入已封存事件；通用贡献账本与成员复核仍未完成全部业务接线。
