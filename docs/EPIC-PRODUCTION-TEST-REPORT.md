# 所选 Paper 组合的完整国家史诗验收

最新任务恢复增量（2026-11-25）：冻结 `c5455ec5…a55e`／`42063ee7…4d9c` 完成真实 UNKNOWN 存储等待、本人 GUI 核对／放弃后新轮次、DUNGEON 四次启动及 WAR 据点胜利三次启动；947／7,278项构建（原生21既有跳过），本轮67项证据回归与另29项旧证据工具兼容回归通过。范围、失败及精确构件见[任务恢复报告](QUEST-RECOVERY-TEST-REPORT.md)。下文保留旧构件的独立检查点。

后续据点增量（2026-11-25）：新原生国家SQLite修复构件 `3dcc34b0…36f6` 配对Chronicle `daa12354…0bf7`，真实菜单占领两座要塞并获得原生100∶0胜利；WAR与DUNGEON各三次启动、唯一结算与重放及70项证据回归通过。原生新构建7,274项（21跳过），Chronicle继承927项。见[据点胜利报告](BATTLEFIELD-TEST-REPORT.md)；以下报告的旧构件与有限范围继续独立保留。

2026-11-25：DUNGEON 与 WAR 两条路线已分别完成三次正常启动、第二次启动后的唯一结算、第三次启动恢复与拒绝重放；58 项独立证据回归全部通过，无跳过。每轮均实际加载 EssentialsX／Vault、FancyNpcs、WorldEdit／WorldGuard，原生业务经济为 PlayerVaultEconomyService。

WAR 使用原生主战国和谈结束；DUNGEON 使用三秒原生生存房间。本报告覆盖完整史诗业务链在所选组合上的集成和恢复，真实战场胜利、收费建国、Folia、MySQL、容量与生产部署仍未验收，十阶段继续推进。

本增量草稿 [Chronicle PR #11](https://github.com/addxiaoyi/StarChronicle/pull/11) 叠加首都 #10，原生继续使用 [StarCore PR #8](https://github.com/addxiaoyi/starcore/pull/8)，均未合并。

## 冻结构件与构建来源

| 项目 | 精确 SHA-256 | 来源 |
| --- | --- | --- |
| StarChronicle | `daa123548d66b5da6c8bd3c759767256d1bc0e6f7a66edfe5eb9c79d1fe70bf7` | 首都增量冻结 JAR |
| StarCore | `3199771bfe9d194443d1d106546ea5ac783a98452b8240e9a67e0b6f09fec135` | 首都增量冻结 JAR |
| Paper 1.21.11 | `5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba` | 所选第三方锁 |

本增量没有修改产品 src／pom.xml，也没有重建产品。归档工具比对两库产品源码与首都汇总中的提交、两条实服路线的双 JAR 指纹，确认与[首都验收](CAPITAL-HISTORY-TEST-REPORT.md)一致。沿用的构建证据为 Chronicle 927 项，StarCore 7,272 项，零失败／错误、原生 21 项既有跳过；这些数字不是本增量重新执行的测试。完整 XML 在首都证据包，本包保存其汇总与哈希。

工具提交 `2261cada2bfd38676812a4aed9d26396aa09fb2d`，原生归档提交 `423e976a7967553975be19e4df7234db0fdff5bd`。原生产品提交仍为 `54587227`。后续 CI 重建构件单独登记，不替代上述实服指纹。

环境为 Java 21、Paper 1.21.11、EssentialsX 2.22.0、Vault 1.7.3-b131、FancyNpcs 2.10.1-java21+2、WorldEdit 7.4.2+7450-eb8e82c、WorldGuard 7.0.17+2370-e42d8bc。全部第三方下载地址及哈希见[锁文件](verification/production-stack-candidate-20261125.json)。

## 两条实际路线

| 项目 | DUNGEON | WAR |
| --- | --- | --- |
| 运行目录 | `paper-epic-daa12354-dungeon-epic-stack-candidate-1` | `paper-epic-daa12354-war-epic-stack-candidate-1` |
| 端口 | 29727 | 29728 |
| 已提交史诗操作 | 12 | 13 |
| 国库／资源援助 | 2／2 | 2／2 |
| 援助源事件／贡献条目 | 4／8 | 4／8 |
| 结算事件／个人后续 | 1／3 | 1／3 |
| 正常服务端与客户端进程 | 各 3，退出 0 | 各 3，退出 0 |
| 证据回归 | 29 通过 | 29 通过 |

四个非 OP 协议客户端真实登录。A／B 实际制作、完成原生任务闸门；随后按公开服务完成国库与资源援助、外交邀请与接受、路线审批，以及国家副本或复国／宣战／和谈。测试夹具明确提供初始国家、资金、库存和制作材料，隔离配置关闭建国费用。WAR 不以设置积分或伪造完成回执冒充胜利。

boot1 留下 ROUTE_APPROVED，未结算即正常停服；boot2 从持久原生完成回执录入最终证据，结算后检查／重放／再检查；boot3 恢复后再次检查／重放／检查。A／B 每次启动均执行实际 /chronicle contributions 查询。两条路线端口均已释放。

独立检查器复用史诗与贡献检查，逐项关联原生回执、权威域、操作ID、主体、精确时间、任务分片、完成事件和贡献索引。另逐轮验证实际加载插件、所选经济提供者、冻结依赖 JAR、夹具及已执行控制器／客户端源码；停服后保存双库与哈希。boot1 结算数为 0，boot2／3 为 1；完成战役、操作、选票、贡献、贡献投影及国家决议表在 boot2、boot3、最终库完全一致。

## 回归和保留的诊断

58 项回归均在真实完成目录的副本上执行，覆盖缺失 boot、错误回执、重复后续、贡献与索引篡改、依赖／脚本漂移、提供者替换、缺失实际加载观察、停服检查点改写、提前结算、重放期间贡献变化，以及原生持久化错误不能作为遥测忽略。历史真实策略停服失败和外交失败输入也已归档。

两条路线 boot1 均记录 FancyAnalytics 的 “Failed to create event (status 200)” 空响应。首次独立检查器因此拒绝，两个失败输出保留。之后将这条精确可选遥测错误与已知 create-record HTTP5xx HTML 响应显式分类，并在成功汇总中逐条列出；原生加载、事件与持久化错误仍使验收失败。六次实际启动没有战役流程失败，未覆盖或改写任何 boot。

## 归档与新设备复验

[证据 ZIP](verification/epic-production-paper-20261125-evidence.zip)为 1,672,825 字节，304 个有哈希的成员，另含成员清单。SHA-256 为 `48bf255f01775252a742440eef7425923bd080244356a8ea78b04375d9b47a50`。配置中 6 个密钥字段脱敏，ZIP 完整性与全部成员哈希校验通过。[汇总 JSON](verification/epic-production-paper-20261125-summary.json)保存源码、构件、插件、继承构建、回归及每轮结果。本机 `target/epic-production-paper-20261125/` 另含双 JAR 与两库源码 tar。

新设备重建必须登记新 JAR 哈希、新运行目录，不能继承旧构件的实服通过结论。按实际本机路径替换以下占位项，DUNGEON／WAR 各使用独立目录和端口：

~~~bash
python3 tools/prepare-production-paper.py \
  --campaign --route DUNGEON --attempt epic-new-device-1 --port 29727 \
  --starcore /path/to/StarCore.jar --runtime /path/to/paper-runtime \
  --artifacts-dir /path/to/locked-third-party-jars --java-home /path/to/java21

# 同一新目录依次执行 boot 1/2/3，scenario 为 first/second/third
EPIC_MINEFLAYER_MODULE=/path/to/node_modules/mineflayer \
python3 tools/epic-paper-session.py --run run/NEW_RUN \
  --java-home /path/to/java21 --node /path/to/node \
  --boot 1 --scenario first --contributions
python3 tools/inspect-epic-production-paper.py run/NEW_RUN

STARCHRONICLE_EPIC_RUN=run/NEW_RUN \
STARCHRONICLE_EPIC_FAILURE_LOG=/path/to/retained-policy-failure.log \
STARCHRONICLE_DIPLOMACY_FAILURE_FIXTURE=/path/to/retained-diplomacy-failure.jsonl \
python3 tools/test_epic_production_acceptance.py
~~~

本增量关闭“所选第三方组合完整 DUNGEON／WAR 史诗链及重启结算”的验收缺口。后续推进真实战场胜利、UNKNOWN 对账和失败／放弃后的安全新轮次；其余工作见[交接报告](HANDOFF-2026-11-25.md)与[剩余工作](REMAINING-WORK.md)。

最终 [CI 45668006968](https://github.com/addxiaoyi/StarChronicle/actions/runs/45668006968) 在 a88b12d 通过927项测试及1,389项交付检查。CI JAR SHA-256为 ebc2b4f6cc1cfb5cee3e7ff211243baf480c3fb9bdbdf1c6d47983ee53ca2972，交付ZIP为95cb369b03eb2f59840e678a941d1de4a95cb90d0022c331b6f8f7ec81ba4a3d；CI构件未做实服测试，不替代本文冻结双JAR。
