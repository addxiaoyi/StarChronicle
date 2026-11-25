# 已核验贡献与史诗援助接线

2026-11-25：国家史诗的国库、资源援助现已写入长期贡献账本，并提供玩家／国家分页查询。此前史诗国库适配器直接调用原生转账，绕过普通援助的贡献入口；旧实服库虽有真实扣款和资源回执，贡献表仍为空。本增量修复该缺口，并从已保存的权威回执回填旧库。

## 玩家命令与服务 API

```text
/chronicle contributions [页]
/chronicle contributions player <UUID或me> [页]
/chronicle contributions nation <UUID或me> [页]
```

需要 `starchronicle.use`，不要求管理员权限。默认查询本人，每页 10 条，页码从 1 起；`nation me` 使用实体所有权线程上的当前身份快照，无国家时明确拒绝，不扩大为全库查询。显示来源提交时间、类型、数量与单位。贡献保留发生时的国家归属，换国不会重写过去的记录。

```java
CompletionStage<List<ContributionEntry>> contributions(
    String subjectId, ContributionType type, int page, int size);
```

`ChronicleService` 提供上述只读方法。主体为 `player:UUID` 或 `nation:UUID`，`subjectId`／`type` 为 null 时不过滤对应维度；API 页码从 0 起，页大小 1–100，按本地记录时间降序、稳定 ID 排序。该接口返回公开历史证据，不执行扣款、资源交付或奖励；旧服务实现通过默认方法明确返回不支持。

一个原生回执在玩家、付款国家两个主体下各有一条贡献，属于同一事实的两个查询视角，不能跨主体求和当作额外收益。国库使用 `treasury`，资源使用 `resource.<标识>`；不同单位也不能直接相加。当前两类援助均归入 `ContributionType.RESOURCE_AID`；`TRADE`、`CIVILIZATION` 尚未接入真实生产来源。

## 提交与恢复

新史诗援助只有在原生 provider/domain 与准备意图严格匹配后，才将操作回执、规范历史、待触发收件箱和贡献在同一 SQLite 事务中提交。中途写入失败会整体回滚。`UNKNOWN`、拒绝、提供者重绑定和缺少原有权威绑定的旧记录不能成为贡献。

资源以原生存储域、回执 ID、资源分量构造稳定键，保留原生 long 数量，不经浮点数转换。国库复用普通援助的事件与贡献 ID，防止同一转账从普通／史诗两个入口重复计数。贡献金额为正数、最多六位小数、不超过 `Long.MAX_VALUE`。

旧库每次轮询最多回填 25 个已提交操作；只读本地已经验证并固定权威的回执，不调用原生变更服务。回填标记、历史与贡献同事务提交；重启、轮询和原操作重放不会重复增加记录。若旧记录缺失必要绑定或与契约冲突，该批事务拒绝并报错，不从当前国家状态猜测历史，也不重新扣款。损坏记录的管理修复流程仍需另行实现。

## 本轮验收

源码提交 `ecbfac5`，分支 `codex/contribution-ledger-integration-20261125`，基于国家史诗已验收提交 `975b39c`。StarCore 保持原验收构件与源码。

| 对象 | 精确结果 |
| --- | --- |
| Chronicle JAR | `57e4171fca515c03839795e9e7e1f45fb14b15ea593d7af31e3f2ba8dbc8e7fd` |
| StarCore JAR | `44f450eccc95590aadc54736109e012d5a52bbae459f4107afa498fd4528518d` |
| Chronicle `verify` | 正常 fork／JaCoCo，92 套件、878 项，零失败／错误／跳过 |
| 配套原生已有验证 | 专项 90 项；常规 verify 7,202 项，零失败／错误、21 skipped，保留既有测试排除配置；本增量未修改原生或重复执行全量测试 |
| 新库 DUNGEON／WAR | 每路线 3 次正常启动与退出；4 个规范援助事件、8 条贡献；API 与 A/B 实际客户端查询在每次启动一致；唯一结算与 A/B/D 各一份后续 |
| 旧库升级 | 复制旧 `57345b09` DUNGEON 成功存档，使用新 JAR 连续启动 boot 4／5；贡献由 0 回填为 8，规范援助事件增加 4 条，重启保持一致 |
| 独立证据回归 | 新库每路线 16 项、升级 8 项，共 40 项通过，基于真实证据副本的破坏检查 |

旧库升级逐行核对 12 张 Chronicle 业务表与全部旧历史未变，原生资源／外交／建国／战争回执、资源余额、副本文件未变，国库余额与全部交易回执未变。国库内部保存修订号随两次停服从 9 增至 11；第一版检查器误将此元数据变化判为失败，原始失败日志和检查器已保留。修正版只允许这个已核实的增量，并新增异常修订号及余额变更负例。

运行目录为 `run/paper-epic-57e4171f-{dungeon,war}-contributions-1` 和 `run/paper-epic-57e4171f-dungeon-contribution-upgrade-1`，端口分别 29641／29642／29643。八次服务端及八次客户端进程均退出 0。环境为 Linux、Java 21、Paper 1.21.11、Node 22.22.2、Mineflayer 4.39.0。

仍使用隔离资金／资源种子、三秒原生生存副本与原生和谈结束 WAR；不代表战场胜利、目标生产经济、全栈 Folia 或任意外部资源转移已自动计入贡献。贸易／文明生产和精确全类历史继续开发。

## 复验与跨设备交接

新开实服沿用 [交接报告](HANDOFF-2026-11-25.md) 的准备与编译命令，在三个 `epic-paper-session.py` 调用中增加 `--contributions`。完成后执行：

```bash
python3 tools/inspect-contribution-paper.py run/PREPARED-DIRECTORY
STARCHRONICLE_EPIC_RUN=run/PREPARED-DIRECTORY STARCHRONICLE_EPIC_FAILURE_LOG=/path/to/retained-war-linux-2-policy-shutdown.log python3 -m unittest discover -s tools -p test_contribution_acceptance.py -v
```

升级必须使用已经正常停服、三次启动验收通过且贡献表为空的真实旧存档：

```bash
python3 tools/prepare-contribution-upgrade.py --source /path/to/old-accepted-run --port 29643 --starcore /path/to/accepted-starcore.jar --runtime /path/to/initialized-paper --java-home /path/to/java21
python3 tools/epic-paper-session.py --run run/PREPARED-UPGRADE --java-home /path/to/java21 --node /path/to/node --boot 4 --scenario third --contributions
python3 tools/epic-paper-session.py --run run/PREPARED-UPGRADE --java-home /path/to/java21 --node /path/to/node --boot 5 --scenario third --contributions
python3 tools/inspect-contribution-upgrade.py run/PREPARED-UPGRADE
STARCHRONICLE_CONTRIBUTION_UPGRADE_RUN=run/PREPARED-UPGRADE python3 -m unittest discover -s tools -p test_contribution_upgrade.py -v
```

工具保留独立可核验的旧库基线，只修改新运行目录。`--attempt` 必须唯一，不能覆盖旧失败日志。升级检查要求原生构件不变，国库仅增加两次正常关闭的保存修订号；其它并行业务应另设验收场景。

[完整清单](verification/national-epic-contributions-20261125-summary.json)与[原始证据包](verification/national-epic-contributions-20261125-evidence.zip)保存构建、原生已有测试、三个运行目录、旧库基线、失败尝试与检查器。已核验 1,926 个成员哈希及 4 份原生配置脱敏，ZIP SHA-256：`5af377239eb8e54fb757d1ecbc0e36a0a5848a197e93ca11c52938d28f8590ff`。本机完整交付目录为 `target/national-epic-contributions-20261125/`，含受验 JAR；Git 证据包不包含本轮新 JAR，旧基线 JAR 用于核验升级来源。新设备重建后按自己的 SHA 新开运行目录复验。
