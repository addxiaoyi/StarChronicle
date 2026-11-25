# 国家贸易与生产 MySQL 存储协议验收

2026-11-25：真实MySQL8.4.13上的31项国家贸易／生产专项测试全部通过，零失败／错误／跳过。原生本增量只增加测试、复验入口和文档，Chronicle只增加归档与交接；两库产品源码与上一[生产验收](NATION-PRODUCTION-TEST-REPORT.md)一致。没有重建双JAR，也没有把本次集成测试计入旧的979／7,354项完整构建。

## 源码、环境和结果

| 项目 | 精确值 |
| --- | --- |
| 原生分支／提交 | `codex/mysql-settlement-20261125`／`362a02861f90de394f5bef0565208fa84862b0a9`，草稿[PR #13](https://github.com/addxiaoyi/starcore/pull/13)，叠加生产#12 |
| Chronicle分支 | `codex/mysql-settlement-20261125`，草稿[PR #16](https://github.com/addxiaoyi/StarChronicle/pull/16)，叠加生产#15 |
| 产品源码基线 | Chronicle `2f800988fbdd99447974e891a0623b39d7ed028a`；StarCore `59ed52abbed5521eff6383df004985b1e13e1bbe` |
| MySQL | Community Server8.4.13，服务器镜像更新号8.4.13.1；非MariaDB |
| 镜像digest | `public.ecr.aws/docker/library/mysql@sha256:8c233bad33d520a7aae1c1e619c3a9775f4a1ba2d463d692fada04ec7f40be00` |
| 实际运行 | 独立容器`starcore-settlement-mysql-20261125`，127.0.0.1:33316，专用库／账号`starcore_settlement_it` |
| SQL条件 | 全部11表InnoDB，REPEATABLE-READ，STRICT_TRANS_TABLES，binlog开启；账号仅有该库权限，没有全局SUPER |
| 最终执行 | 原生`target/mysql-settlement-verified-1/`，2026-11-25T20:02:53Z–20:03:29Z，31项零失败／错误／跳过 |
| 测试源码SHA-256 | `a09dbd2f0c2fd35089ca7c2a4d8b52d13bf62bce397e5143ec8776494d6117da` |
| 原始Maven日志SHA-256 | `e9e321d4a6a65bdcf9c28bf3ccc92cf6c100ec060b23431fc82cee7d80f89368` |
| 最终XML SHA-256 | `333d6f574aadc3136c849e60cb5c86c034d23f9c8b38ef078c500029d45a59c9` |

JDBC通过TLS加密连接本机端口；另保留实际SQL客户端的`TLS_AES_128_GCM_SHA256`协商结果。没有外部生产主机身份校验结论。测试结束后11表逐一核对为空，无遗留触发器，专用容器正常exit0停止，无OOM；没有停止本机现有MySQL3306、Redis、PostgreSQL或其他容器。

## 已覆盖的行为

生产的RUNNING扣料、COMPLETED发货和CANCELLED退款各在四个SQL位置注入真实触发器失败：事件INSERT、head UPDATE、资源revision UPDATE和库存INSERT，共12种组合。每次比较全部11表的逐列快照，必须与调用前完全一致，不能只检查某个返回状态。

贸易在国库、库存、资源转移回执和独立结算写入处失败时，同样核对双方资产、报价及所有日志整体回滚；移除触发器后原报价可以成交。正常交叉场景在同一库存先生产投入，再国家直售，最后完成生产，重建模块后余额、回执与稳定批次保持一致。

其余用例覆盖commit已真实提交但JDBC回应丢失、重读也失败后的普通资产写入阻断、重新创建服务后确认旧请求；两套独立模块和持久队列同时领取／成交只有一个实际提交；持有未提交事务时另一MySQL连接看不到任一资产或回执变化；旧快照拒绝覆盖；精确取消退款一次；删除整条生产尾记录后拒绝读取与普通写入。测试没有将UNKNOWN当作成功或安全重放许可。

当前31项分布为：三转换12个故障点、贸易4个故障点、三转换3个确认丢失、引擎检查1、跨协议正常恢复1、精确取消1、贸易确认丢失1、两类重读失败2、旧快照1、两类提交前可见性2、两类独立实例并发2、完整删尾拒绝1。

## 失败保留与复验

Docker Hub首次拉取超时，改用官方ECR镜像源后固定digest。首轮20项中13项通过，7项因为binlog开启而测试账号不能创建故障触发器报错；只在专用容器设置`log_bin_trust_function_creators=ON`，没有给应用账号全局SUPER权限。随后20项全部通过；补齐事务后半段失败窗口和交易可见性后31项通过，最终通过新复验入口再次实际执行31项。各轮XML、源码快照、格式校验与失败日志均保留，跳过数为0。

原生入口为`tools/run_nation_settlement_mysql.py`，测试类为`MySqlNationSettlementIntegrationTest`。预先准备空的独立MySQL8.4数据库及对应权限，配置`JAVA_HOME`和`STARCORE_SETTLEMENT_MYSQL_URL/USER/PASSWORD`；密码由私密环境注入，不能写入命令参数或报告。然后运行：

```bash
python3 tools/run_nation_settlement_mysql.py --output target/mysql-settlement-UNIQUE
```

工具使用仓库Wrapper离线执行`-Dstarcore.mysql.settlement.it=true -Dtest=MySqlNationSettlementIntegrationTest test`。默认常规构建不启用这组测试；不能把默认跳过当作MySQL通过。入口拒绝非loopback、3306端口、非专用库、URL中的凭据和已存在的输出目录；测试进一步拒绝非空／外来表或既有触发器，完成所有权核验后才清理自己的表。新输出保存实际源码、原始日志、XML、逐文件哈希与开始／结束时间。Spotless通过，产品`src/main`与`pom.xml`无变化。

## 证据和上一构件

[证据ZIP](verification/mysql-settlement-20261125-evidence.zip)为88,711字节，32个有哈希成员全部验证；SHA-256 `ffa435388cae304ce49124f3baef3381cc344b4f6e378e650c05ed1ab163d114`。[机器清单](verification/mysql-settlement-20261125-summary.json)保存实际测试、原生提交、数据库镜像和停止状态。归档前逐字扫描私密测试凭据，确认没有包含密码或私密配置；包含初次失败XML、最终XML、真实SQL元数据／空表检查及容器日志。

上一生产#15最终HEAD`642b28654ba62d8747305bc2e848cf1df39b868a`的push CI45689621806、PR CI45689632790均success：979项测试、1,439条交付校验。push CI JAR为`338ddbf540f1f124b13462c36996d805805f3f97fad778588f5dc860434c8a0f`，ZIP为`b396f07230a79eafcbcc114da38755c1cdc78188f5357aed9054f77ed313bc58`；PR CI JAR为`13f1188ab4a33fcaec75adcc9396c4048bd41ad27c05c999ec98620961832cfe`，ZIP为`9221f02bb4e9fc650db549026eb413d62b780a0d06d5407b3f5003e594947c8d`。本次归档纳入这两次原始CI日志和指纹，不再改旧分支制造新构建。

上一冻结实服双JAR仍为Chronicle`549f99d9…7fc5f`与StarCore`26e6fb89…b886`，对应生产3＋空服2＋贸易3＋DUNGEON3＋WAR3共14次Paper／SQLite组合启动及174通过／8跳过证据。测试源码没有改变产品，但本次没有实际运行Paper并切换到MySQL，不能据此扩张旧实服结论。

下一步继续Paper＋MySQL的真实命令、Chronicle来源收件与贡献、数据库和游戏服务联合重启。实际断电／数据库进程崩溃、备份恢复、复制、非事务表引擎、外部TLS部署及长期容量仍未验收。本轮只推进十阶段中的SQL兼容性，不改变其他阶段的未完成状态；候选未合并或生产部署。
