# 国家生产／贸易提交窗口中断验收

2026-11-25：原生服务在真实 MySQL 8.4.13 上完成 12 个事务提交窗口的进程中断场景：12 次 MySQL SIGKILL／重启、8 次业务 JVM SIGKILL、40 次正常 JVM 退出。修正后的 31 项 SQL 回归、32 项离线证据回归、9 项目标保护回归全部通过，零失败／错误／跳过；Spotless 通过。公开归档解压后的检查器和32项证据回归再次通过。

这是对[五现场停服恢复验收](MYSQL-RECOVERY-TEST-REPORT.md)的补充。此次没有新运行 Paper，也没有更换冻结双 JAR；使用与当前产品相同的原生源码新编译服务类，并在测试适配器提供的身份／线程环境中调用真实业务服务。旧的主项目979项、原生7354项构建和各轮Paper实服按原构件保留，不计入此次新增测试。

## 分支、构件与环境

| 项目 | 精确检查点 |
| --- | --- |
| 原生实现 | `codex/mysql-inflight-20261125`，提交 `ebdfacaea2132f86bc3cbaedd6cd6f438d2de0b5`，草稿[PR #14](https://github.com/addxiaoyi/starcore/pull/14)，叠加#13 |
| Chronicle交接 | `codex/mysql-inflight-20261125`，草稿[PR #19](https://github.com/addxiaoyi/StarChronicle/pull/19)，叠加恢复分支#18；本增量只包含归档、报告与打包工具 |
| 产品源码 | Chronicle `2f800988fbdd99447974e891a0623b39d7ed028a`；原生 `59ed52abbed5521eff6383df004985b1e13e1bbe`；`src/main`、`pom.xml`均未改 |
| 冻结Chronicle JAR | `549f99d9e2fca919d42263fc184e3f790d101b7f65a1c91f067eb9716ce7fc5f` |
| 冻结原生JAR | `26e6fb895d0ee6422c8347b63dc442763b475ef5eb91f8c92c94702fb2ebb886` |
| 数据库 | MySQL Community8.4.13，InnoDB，REPEATABLE-READ，`innodb_flush_log_at_trx_commit=1`、`sync_binlog=1` |
| 固定镜像 | `public.ecr.aws/docker/library/mysql@sha256:8c233bad33d520a7aae1c1e619c3a9775f4a1ba2d463d692fada04ec7f40be00` |
| 接受现场 | 原生 `target/mysql-inflight-20261125-attempt3/`；专用容器 `starcore-inflight-mysql-20261125-retry3`，127.0.0.1:33323 |
| 实际执行时间 | 2026-11-25T21:13:42Z 至21:16:04Z；数据库正常exit0停止，无OOM，数据保留 |

选定的生产组合仍为 Java21／Paper1.21.11、EssentialsX2.22.0、Vault1.7.3-b131、FancyNpcs2.10.1-java21+2、WorldEdit7.4.2、WorldGuard7.0.17。此次服务测试不重新证明这些第三方插件的联机行为，也不产生Chronicle个人贡献的新增实服结论。

## 12个场景与断点证据

生产开工、完成、取消和国家直售成交，各运行以下三个窗口，每个窗口使用独立数据库与独立 setup、operate、verify、reload JVM。没有清空或复用已失败场景。

| 窗口 | 执行与独立证据 | 必须满足的恢复结果 |
| --- | --- | --- |
| `mysql_before` | 真实DML完成，JDBC commit前暂停；同连接导出待提交视图，独立连接查到对应InnoDB事务及修改行数，再SIGKILL数据库；离线时释放业务调用 | 返回UNKNOWN；重启后全部11表与原快照相同；旧不确定库存、贸易国库写入均拒绝；新JVM同ID重试成功 |
| `jvm_before` | 同一真实写事务内，commit前SIGKILL业务JVM，然后SIGKILL数据库 | 完整回滚原资产与日志；新JVM同ID重试成功，再次重放只读 |
| `jvm_after` | 驱动真实commit返回后，在结果交还业务层之前暂停；独立连接已看到完整提交，再SIGKILL JVM与数据库 | 重启保持已提交快照；两次新JVM重放均ALREADY_APPLIED，资产和所有回执不变 |

12次数据库强制终止中，4次发生于数据库仍持有写事务时；另外8次发生于业务JVM强制终止之后。`jvm_after`证明业务调用尚未获得确认时退出的恢复，不表示在网络中截断MySQL COMMIT回应。JDBC包装只控制真实调用的暂停点，没有模拟数据库错误或提交成功。所有JVM强制终止记录为-9，数据库强制终止为137且非OOM。

每场景的独立连接保存11表逐列快照、DDL、事务与服务器UUID；恢复必须与提交前原快照或已提交完整快照精确相等。新进程实际通过服务读取、按同ID重试和重放，再核对原操作者、状态、资产、各回执、连续序列。证据检查器还拒绝缺少事务见证、只读事务、半回滚、身份变化、用OOM冒充故障、普通退出冒充SIGKILL、失败重载与重复结算。

初始卖方 mineral300／energy100、国库50.00，买方国库1000.00。生产两批扣mineral6／energy2，完成得industrial4，取消精确退回；贸易卖出mineral20、单价2.50，最终卖方库存280／国库100.00、买方库存20／国库950.00。报价、独立结算、库存回执与国库回执全部关联；生产状态序列和原操作者保持不变。

## 旧测试纠错与失败保留

审计发现原[MySQL31项报告](MYSQL-SETTLEMENT-TEST-REPORT.md)的12个生产回滚用例虽参数化为事件INSERT、head UPDATE、revision UPDATE、库存INSERT，实际SQL却都在事件INSERT建立触发器。旧执行记录是真实的，但不足以证明四个位置覆盖。本轮修正并重新通过31项，四个生产位置现在真实参与注入。另修正固定20:00测试时钟：提交层使用真实时间检查报价过期，之后复跑会失败；现在每例记录当前基准时间，跨JVM复用`clock.json`，产品检查未修改。

| 保留目录 | 实际结果 | 原因／后续 |
| --- | --- | --- |
| `mysql-inflight-20261125-attempt1` | 31项：6失败、16错误，0次故障场景 | 专用实例缺少测试触发器配置；固定报价已过期 |
| `mysql-inflight-20261125-attempt2` | 31项：10失败、0错误，0次故障场景 | 已加入专用`log_bin_trust_function_creators=ON`，固定时钟仍使贸易路径过期 |
| `mysql-inflight-20261125-attempt3` | 31项：零失败／错误／跳过；12场景通过 | 两项测试缺陷修正后完整执行 |

两次失败没有标为通过；原日志、XML、对应源码和容器日志均进入公开包。三个新容器33321／33322／33323最终正常exit0停止，无OOM，数据库和私有配置保留。原恢复容器及其他服务未修改。私有配置在`/tmp/starcore-inflight-20261125`及`-retry2`、`-retry3`，不得上传`mysql.env`。

## 证据、复验与下一步

[公开证据ZIP](verification/mysql-inflight-20261125-evidence.zip)：580,953字节，428个有哈希成员（另有索引文件），SHA-256 `f971cde7406b4b73524d544025951eefee428d8d57b8d12ca60e7acd5e91f43e`。[机器清单](verification/mysql-inflight-20261125-evidence.summary.json)保存统计和精确原生提交。归档前扫描三个私有环境的实际密码，未泄露任何值；完整数据库文件与私有配置不在包内。

解压后可直接运行：

```bash
python3 tools/inspect_nation_inflight_mysql.py accepted
python3 tools/test_nation_inflight_mysql.py accepted
python3 tools/test_nation_inflight_safety.py
```

重新执行真实场景使用原生`tools/run_nation_inflight_mysql.py`，必须选择新的输出目录、外置私密目录、带任务标签的容器和空闲loopback端口。工具先核对目标ID／标签／镜像／绑定才允许终止；现有目录与实例拒绝覆盖。完整参数及边界见原生`docs/NATION-SETTLEMENT-MYSQL-INFLIGHT.md`。本地原生工作流仅对main目标PR触发，本草稿不冒充已有远端CI。

十阶段仍active。尚缺Paper实际调用路径的事务中断、物理断电／文件系统持久性、远端或外部系统联合恢复、旧玩家钱包／市场共同提交、旧工厂迁移、永久UNKNOWN、长期容量及全栈Folia。候选没有合并或生产部署。
