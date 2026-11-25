# Paper＋MySQL 联合备份与进程中断恢复验收

本轮在原有冻结双插件上增加实际停服联合备份、独立 MySQL 恢复、真实玩家重放和 JVM／MySQL 强制终止后的恢复验收。产品源码与 JAR 未重建。所选组合仍为 Java 21／Paper 1.21.11、EssentialsX 2.22.0／Vault 1.7.3-b131、FancyNpcs 2.10.1-java21+2、WorldEdit 7.4.2／WorldGuard 7.0.17。

本报告对应候选验收工具，十阶段整体仍 active；不表示已合并或部署生产服。上一轮正常联合重启见 [MySQL 实服报告](MYSQL-PAPER-TEST-REPORT.md)。

## 备份与恢复边界

使用上一轮已正常停止的五个现场。短暂启动本任务原始 MySQL 容器，只读导出 `mysqldump`，将整份服务器目录与原生 SQL、完整类型化快照、逐文件 SHA-256 放入私有 ZIP。导出前后核对全部原生数据，检查 Paper 已停、端口释放且无同目录 JVM；服务器文件在打包前后保持相同。备份完成后原始 MySQL 再次正常停止，原目录、库与此前停服证据保留。

完整备份包含世界、玩家、插件配置和私密测试密钥，必须保留在 Git 之外、0700 目录中，ZIP 为 0600。公开证据仅含备份摘要、文件清单、恢复前后数据与运行日志，**不能替代完整私有备份**。本轮不实现运行中、多写入方或外部钱包服务的联合备份。

恢复入口先核对独立提供的 ZIP SHA-256、每个成员大小／摘要、完整双插件数据、路径和成员清单，再验证任务容器身份、新目录及空数据库，最后才导入。真实入口已拒绝既有目录、非空数据库和错误摘要，拒绝前后原生全部表不变。

五现场均恢复到不同于原始 `server_uuid` 的 MySQL 8.4.13 实例，保留原数据库名和应用用户以维持视图引用。每份恢复核对 211 张 InnoDB 表、1 个视图、所有列和全部行、原始 `SHOW CREATE TABLE/VIEW`，并核对触发器、例程和事件目录。SQL 完整定义的唯一规范化是：依照各列独立读取的类型／排序规则，为 MySQL 导入后显式补出的 `CHARACTER SET utf8mb4` 补齐源定义。索引、约束、默认值、注释、AUTO_INCREMENT 与视图均不忽略；两侧原始 DDL 保留。

完整文件恢复校验共 3,016 个文件。恢复时仅允许改变 `preparation.json`、游戏端口及原生 MySQL 端点／密码环境变量；其他文件逐字节一致。恢复完成后另行记录 `Essentials/config.yml` 的 `update-check: true → false`，关闭可选网络更新检查，不改变业务配置。数据库凭据只通过私有环境注入，不放入命令参数或公开归档。

## 真实恢复流程

每个现场依次执行三轮：恢复后正常启动并重放；再次启动、完成重放并让客户端退出，强制终止本任务 JVM，再强制终止其 MySQL、等待数据库恢复；最后正常启动并再次重放。强制终止轮独立标记 `EXPECTED_PROCESS_INTERRUPTION`，JVM exit -9／MySQL exit 137，不能改写为正常关闭。MySQL 记录 `XA crash recovery finished` 后逐表复核，最后游戏轮才提供正常双库检查点。

新增稳定快照检查等待客户端离线的异步写入结束；JVM 终止前后和 MySQL 恢复后逐表比较。Chronicle 使用 SQLite 在线备份读取已提交 WAL，核对 JVM 中断前后数据，正常停服轮还要求不存在待处理 WAL／journal。显式审计投影 `starcore.db` 仍不是原生存储，也不是备份介质；实际后端始终 MySQL。

| 场景 | 成功目录后缀 | 验收启动 | 真实命令／查询 | 保持的业务结果 |
| --- | --- | --- | --- | --- |
| 主生产 | `dungeon-mysql-recovery-production-2` | 5／6／7 | 24 | 4 完成、1 取消、12 industrial、8 文明贡献；原开工人不变 |
| 空服生产 | `dungeon-mysql-recovery-offline-2` | 3／4／5 | 6 | 1 完成、2 贡献；1 个批量 2 的 RUNNING 批次保留 |
| 国家直售 | `dungeon-mysql-recovery-trade-3` | 4／5／6 | 27 | 3 成交、29 agricultural、75.00 国库、12 贡献 |
| DUNGEON | `dungeon-mysql-recovery-dungeon-2` | 4／5／6 | 6 | 唯一结算、原生完成回执、8 援助贡献及原个人后续 |
| WAR | `war-mysql-recovery-war-3` | 4／5／6 | 6 | 已结束的 100∶0／AGGRESSOR 胜利、唯一结算；真实 GUI 拒绝重复占领 |

所有目录均以 `paper-epic-549f99d9-` 开头。合计 15 次验收游戏启动、25 次正常进程退出、5 次 JVM 强制终止、5 次 MySQL 强制终止及恢复、69 条真实命令／贡献查询。WAR 另有三次真实 GUI 拒绝占领检查。诊断与失败轮次单独保留，不计入该合计。

登录与正常保存会增加原生 revision 和 Chronicle 待触发剧情的 `checked` 扫描计数。独立检查器只允许这些明确的计数推进；原国家／创始人、库存、国库资产、生产／贸易／外交／战争回执、权威域、结算、贡献、剧情内容与后续实例保持一致。原生 Java properties 的保存日期注释可以刷新，任务与副本历史内容须相同。

## 独立核验与失败保留

`inspect-mysql-recovery.py` 将原始检查点固定到上一公开 MySQL 证据 ZIP 的 SHA-256，再检查恢复清单、独立实例、完整结构与数据、真实非 OP 客户端身份／命令响应、捕获脚本指纹、原生与 Chronicle 业务守恒、强制终止证据与后续正常关闭。`--artifacts` 还逐字节核对双 JAR、fixture、Paper 及所选第三方插件。

实际恢复证据反例覆盖缺失配对数据库、DDL 索引／AUTO_INCREMENT／排序规则／视图变化、篡改库存和贡献、伪造正常退出／OOM／同实例重启、错误 PID／中断顺序、缺失协议响应、遗漏恢复日志，以及重新计算文件摘要后的数据删改。ZIP 边界测试拒绝路径穿越、绝对路径、Windows 路径、别名路径、符号链接、重复／未列成员、空 SQL、错误摘要和缺失配对数据。最终每现场34项恢复证据回归全部通过，五现场共170项；ZIP边界22项通过，合计192项、零失败／错误／跳过，另有3项真实入口拒绝验证。机器清单登记每份日志摘要。

保留的失败与诊断：

- 初次准备脚本误用 `Path.open(opener=...)`，在创建容器前失败，修正为内置 `open`，原日志保留。
- 首恢复实例行数据全部一致，但原始 DDL 文本比较拒绝 MySQL 显式补出的字符集声明；保留该实例和未完成目录，规范化规则由列元数据严格限定后在新实例重做。
- 新实例私有启动器曾读取旧密码，恢复前连接被拒；更新本机环境注入，原失败日志保留，没有修改源数据库。
- 生产首次恢复 boot4 的 Essentials 更新检查在停服后访问已关闭 JAR；原日志和 session 保留为诊断，不计通过。记录关闭更新检查后，boot5／6／7 完整通过。
- 首贸易恢复的离线异步影响力／称号写入发生于 JVM 终止前后快照之间，全表比较拒绝该轮。交易资产与回执不变；保留 FAILED session，增加稳定快照检查，在新实例、新目录重做。
- 首国战恢复 boot6 因测试端口绑定失败而未启用插件。保留原始 FAILED session 和 crash report；新增独占端口可用性等待，新目录／端口重跑全三轮。
- 检查器开发期间的失败日志保留，包括 SQLite 测试连接未关闭导致 WAL 未归并；反例现明确关闭连接，并单独验证待处理 WAL 会被拒绝。

没有重新构建产品。冻结 Chronicle SHA-256 为 `549f99d9e2fca919d42263fc184e3f790d101b7f65a1c91f067eb9716ce7fc5f`，StarCore 为 `26e6fb895d0ee6422c8347b63dc442763b475ef5eb91f8c92c94702fb2ebb886`。产品源码分别为 `2f800988fbdd99447974e891a0623b39d7ed028a` 与 `59ed52abbed5521eff6383df004985b1e13e1bbe`。上一完整构建为 Chronicle 979 项与原生 7,354 项（21 既有跳过）；本轮工具验证和后续 CI 各自登记，不把新 CI JAR 当作实服冻结 JAR。

## 复验与交接

入口依次为 `prepare-recovery-mysql.py`、`backup-mysql-paper.py`、`restore-mysql-paper.py`、`configure-mysql-recovery.py`、`run-mysql-recovery-acceptance.py` 和 `inspect-mysql-recovery.py`。这些工具限定本任务容器、loopback、独立目录与已准备 fixture，不是任意生产服备份服务。重试必须使用新目录；不得清空失败数据库或改写失败 session。

```bash
python tools/backup-mysql-paper.py --run SOURCE_RUN --instance SOURCE_INSTANCE --output PRIVATE_BUNDLE.zip
python tools/restore-mysql-paper.py --backup PRIVATE_BUNDLE.zip --sha256 INDEPENDENT_SHA256 --instance NEW_INSTANCE --attempt UNIQUE_ATTEMPT --port FREE_PORT
python tools/configure-mysql-recovery.py --run RESTORED_RUN
python tools/run-mysql-recovery-acceptance.py --run RESTORED_RUN --java-home JAVA21_HOME --node NODE
python tools/inspect-mysql-recovery.py RESTORED_RUN --artifacts
STARCHRONICLE_RECOVERY_RUN=RESTORED_RUN python -m unittest discover -s tools -p test_mysql_recovery_acceptance.py -v
python -m unittest discover -s tools -p test_mysql_recovery_backup.py -v
```

本机续接工作区 `/home/add/starchronicle-mysql-recovery`，分支 `codex/mysql-recovery-20261125`；原生继续 `/home/add/starcore-mysql-settlement`，不修改 `/home/add/starcore`。完整私有备份在 `/tmp/starcore-recovery-private-20261125/`；私有实例目录 `starcore-recovery-mysql-20261125*` 中的 `mysql.env` 不得上传。公开归档由 `archive-mysql-recovery-evidence.py` 生成并在解压后再次运行五个独立检查器。

后续仍需事务进行中的中断与存储介质故障窗口、运行中数据库失联策略、远端 TLS 身份验证、长期容量、旧钱包／市场与工厂迁移、其他 UNKNOWN 恢复、全栈 Folia。当前强制终止发生在已完成重放之后，不表示物理断电、任意事务窗口或跨存储系统原子恢复均已完成。
