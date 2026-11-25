# 自动世界记忆实服验收

更新：2026-11-25（UTC）。**真实地区首访、地方记忆、图书馆两种结局、重启恢复和无人在线登记均已通过所选 Paper 组合的独立验收。** 主场景三次启动、离线场景两次启动，十个服务端／客户端进程均正常退出。本轮增加验收工具与证据，未修改产品代码。

## 构件和实际范围

| 构件 | SHA-256 | 对应构建证据 |
| --- | --- | --- |
| StarChronicle | `222fbb4064a84692ee3783a525737495566f1a529c3753df2392695797a4e345` | 沿用资源历史阶段 919 项构建的冻结 JAR |
| StarCore | `d9f296190482ef644c5b5d52c3a16b7c0d7a304bbbc5a788d5e401627c2f84da` | 沿用生产组合阶段 34 项专项和 7,261 项常规 verify 的冻结 JAR；21 项跳过，保留既有排除 |

表内 Maven 数字属于已有同指纹构建，实服阶段未重建产品或扩充其覆盖声明。产品 `src/` 与 `pom.xml` 已对照原构建提交，确认没有漂移。后续 GitHub CI 对同一产品源码另外构建，结果见下方交付检查；CI JAR 不替换实服受验指纹。配套原生分支仍为 `codex/worldguard-production-integration-20261125`，本轮 Chronicle 分支为 `codex/memory-recipe-runtime-20261125`，验收工具提交 `e25e928`，归档报告 `ebe8c01`，草稿 [PR #9](https://github.com/addxiaoyi/StarChronicle/pull/9) 叠加生产组合 #8。

运行环境为 Java 21、Paper 1.21.11、Mineflayer 4.39.0；第三方冻结组合为 EssentialsX 2.22.0、Vault 1.7.3-b131、FancyNpcs 2.10.1-java21+2、WorldEdit 7.4.2、WorldGuard 7.0.17。来源、下载指纹及完整版本见[组合锁](verification/production-stack-candidate-20261125.json)和[组合报告](PRODUCTION-STACK.md)。本轮使用上述实际插件，但没有重新执行组合报告中的转账、圈地或 NPC 生命周期场景。

## 实际场景

| 目录后缀（共同前缀 `paper-epic-222fbb40-dungeon-`） | 行为 | 结果 |
| --- | --- | --- |
| `memory-candidate-2` | A／B／C／D 实际跨越配置地区边界，第三次启动由 A 重访；真实菜单点击；验证、非法重载、停用／启用；两次重启 | 4 个唯一首访、3 条记忆、5 个图书馆实例、5 次选择、17 个 API 检查点；15 次原来源重放去重、7 次完成选择拒绝 |
| `memory-offline-candidate-1` | D 实际到访后将 ingress 留至下次启动；空服登记记忆；客户端随后登录和选择 | 1 个首访、1 条记忆、2 个实例、2 次选择、6 个 API 检查点；原观察时间保持不变 |

使用交付的 `caravan-library.yml`，只在独立运行目录启用示例 `caravan-archive/1` 配方。四个客户端均非 OP，国籍由原生真实服务建立；A 和 D 同国，B／C 分属其他国家。夹具只布置隔离步行站与初始国籍，没有直接插入探索、记忆、结果或荣誉数据库记录。

主场景中 A 留下证言、D 阅读 A 的档案，B 阅读自己的档案。停用时 C 的首访仍进入真实历史，但不生成记忆；启用后重放也不补算。D 的后续首访生成新档案，为 A 和 D 留下两个未完成实例。第二次启动恢复这两个实例，D 留下证言、A 阅读，第三次启动复查和重访不重复产生记忆或奖励。A／D 各得一次本地荣誉 3，读者结局不发荣誉。

同国成员可以成为图书馆读者。A 首访的历史和记忆信号始终只有 A 这一个参与者；D 的阅读记录是后续事件，因果链指向 A 的真实首访。检查器独立核对每个角色的原事件、记忆、后续实例、结局、地区投影、选择审计和稳定结果回执，避免把读者写成原探索者。

离线场景把常规轮询周期设置为一小时，真实到访先持久写入 ingress。首次正常停服快照确认它是 `PENDING`，此时没有已接受的探索历史、记忆或实例。第二次启动在任何客户端进程开始前生成记忆；API 显示在线人数零且没有活动实例。原首访时间仍来自第一次启动，登记时间来自第二次启动。登录后为同国 A／D 各生成一个实例，完成证言／阅读两条路径。

主场景还验证：非管理员重载被拒；`validate` 新版本不归档；同版本修改标题时 `validate` 和 `reload` 均失败并保留旧内容；原来源重复提交和完成选择重放不能增加记忆、结局、审计或荣誉。

## 检查器、失败和证据

检查器直接只读核对 SQLite，包括规范事件哈希、参与者／国家索引、记忆定义指纹、原时间、完整因果路径、按读者的实例和选择、触发消费、不可变选择审计、七／三个 `DONE` 结果回执，以及每次正常停服的数据库快照。另核对冻结 JAR／夹具／控制器／客户端、实际移动和 GUI 数据、服务端与夹具日志、生命周期、退出码及重放前后状态。

以两个成功运行目录的副本执行证据破坏回归：**主场景 26 项通过、1 项跳过；离线场景 25 项通过、2 项跳过；共 51 项通过，3 项因场景不适用跳过。** 主场景跳过仅离线的空服断言；离线场景跳过只在主场景执行的完成选择拒绝和管理员权限断言。删除移动／菜单点击、移除回执或审计、篡改原参与者／时间／因果链、重复荣誉、删除重放证据、假定离线时存在玩家、异常退出或保存错误均被拒绝。

`memory-candidate-1` 保留实际失败与正常停服记录：夹具最初错误地假设只有原探索者会收到支线，因 D 的合法同国受众而失败。修正夹具预期后使用全新目录，不改产品或覆写旧 boot。检查器开发期间修正了个人选择键的预期；首次证据回归另有三个测试辅助代码错误（把 Node 非 JSON 提示行当成 JSON），失败日志同样归档。它们不改变真实服务端通过范围。

[完整汇总](verification/memory-recipes-paper-20261125-summary.json)与[证据包](verification/memory-recipes-paper-20261125-evidence.zip)包含两个成功场景和保留失败、逐次停服数据库、配置变更、真实日志、源码及原构建引用。证据包 1,906,558 字节、296 个成员哈希通过，SHA-256：`d7a83a3a10c47c5c2cc0dba536d6cad28e4dc9e1da16aff69666cfa03d1cdff5`。配置密钥已脱敏；完整双 JAR 与两库源码 tar 在本机 `target/memory-recipes-paper-20261125/`，不随 Git 提供。

## CI 交付检查

[CI 45660659630](https://github.com/addxiaoyi/StarChronicle/actions/runs/45660659630) 在 `47076de` 上通过了 919 项 `clean verify`，零失败／错误／跳过，但打包阶段拒绝了历史证据文件。该轮完整[失败日志](verification/memory-ci-packaging-failure-20261125.log)保留，不能将 Maven 成功称作完整 CI 成功。

根因是既有探索／任务证据中的 86 个文件保存为 CRLF，而原清单记录的是 LF 字节。仅对换行恢复后大小与 SHA-256 都逐字匹配原清单的文件执行恢复，没有改写原清单或放宽检查。恢复后的 10 份清单、263 个文件通过严格检查。每项修复前后指纹见[恢复记录](verification/evidence-line-ending-recovery-20261125.json)，修复前字节保留在[备份包](verification/evidence-line-ending-recovery-20261125-before.zip)。旧失败记录的业务内容也保持不变；本轮记忆证据 ZIP 没有被修改。

该修复只涉及 Git 保存的历史证据和交付说明，配方／插件代码及已验收构件保持不变。后续 CI 在修复提交上重新构建并打包，状态以 PR 检查为准。实服归档的源码 tar 固定于 `e25e928`，早于这项恢复；跨设备构建应使用当前分支。

## 复验与限制

新设备按[交接](HANDOFF-2026-11-25.md)取得两库源码并构建，准备初始化的 Paper 目录与锁定第三方 JAR。新构件必须记录新指纹，不直接套用旧 JAR 的通过结论。准备和执行示例：

```bash
export EPIC_MINEFLAYER_MODULE=/path/to/epic-client/node_modules/mineflayer
python3 tools/prepare-memory-paper.py --attempt NEW-ATTEMPT --port 29722 \
  --starcore /path/to/StarCore.jar --runtime /path/to/initialized-paper \
  --java-home /path/to/java21 --artifacts-dir /path/to/locked-plugins
python3 tools/epic-paper-session.py --run run/PREPARED-DIRECTORY \
  --java-home /path/to/java21 --node /path/to/node --boot 1 --scenario memory-first
python3 tools/epic-paper-session.py --run run/PREPARED-DIRECTORY \
  --java-home /path/to/java21 --node /path/to/node --boot 2 --scenario memory-second
python3 tools/epic-paper-session.py --run run/PREPARED-DIRECTORY \
  --java-home /path/to/java21 --node /path/to/node --boot 3 --scenario memory-third
python3 tools/inspect-memory-paper.py run/PREPARED-DIRECTORY
STARCHRONICLE_MEMORY_RUN=run/PREPARED-DIRECTORY \
STARCHRONICLE_MEMORY_FAILED_RUN=run/RETAINED-FAILED-DIRECTORY \
  python3 -m unittest discover -s tools -p test_memory_acceptance.py -v
```

离线场景使用新 attempt 和端口，并向准备命令增加 `--offline`；依次执行 `memory-offline-first`、`memory-offline-second`。无旧失败目录时，回归中的对应负例会明确跳过，须记录真实结果。归档工具为 `tools/archive-memory-evidence.py`；当前脚本要求保留本轮失败现场和匹配的旧构建清单，因此主要用于固定构件的证据归档，不是任意新构件的通用发布工具。

验收范围是明确配置地区的首访和实际配方链。未验证任意地形自动发现、城市战役配方、真实战争参与证明、实体施工、Folia 自动记忆、长时压力或生产部署；配方不创建方块／NPC。本轮世界记忆验收也不代替全部史诗在新生产组合上的业务验收。十阶段继续推进。
