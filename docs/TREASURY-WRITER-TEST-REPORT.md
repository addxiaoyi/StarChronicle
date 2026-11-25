# 国库 SQLite 写锁与配套实服验收（2026-11-25）

普通国库保存已改为先获取SQLite写锁，再读取并核对revision。其他模块短暂写入时会按数据库busy_timeout等待；过期快照仍拒绝，联合写入失败仍回滚，提交确认不确定仍须对账。本轮不自动重试无幂等ID的存款。

上一工厂构件的DUNGEON初始化出现SQLITE_BUSY，本轮用相同夹具源码在旧JAR重现存款拒绝，并在新JAR验证等待后成功。旧失败、修复前单元测试与成功现场同时保留；十阶段仍在推进，未合并或部署生产服。

## 精确源码与构件

| 项目 | 冻结产品源码 | 实服JAR SHA-256 |
| --- | --- | --- |
| StarCore | `a8004d8470e93dcb4fef4887d7b31a8386738f9e` | `cf7a39d820ac08c959d2fc71415e10214e6b269fc71d2915163e3bb0f4c05781` |
| StarChronicle | `fc08038d5e9016ad04280552a3a641193c0971c4` | `d49927067fea2e787d24f1be6127a59287352a9a53402d780943b9829276c17f` |

两库续接`codex/treasury-writer-20261125`，叠加原生工厂#16与Chronicle工厂#21。Chronicle产品源码和资源未改，沿用工厂构件及988项完整构建；本轮新增验收工具和报告。后续CI重建JAR按自己的指纹记录，不继承实服JAR结论。

配套草稿：[StarChronicle #22](https://github.com/addxiaoyi/StarChronicle/pull/22)叠加#21，[StarCore #17](https://github.com/addxiaoyi/starcore/pull/17)叠加#16。最终CI与交付指纹记录在PR正文，避免为记录CI再次改变被验证提交。

## 根因和实现

原实现事务先SELECT revision，再尝试UPDATE。在SQLite WAL下，其他连接持有写锁时，这次读到写的升级可能立即抛SQLITE_BUSY，绕过等待时间。新实现仅在SQLite执行不改变值的revision行UPDATE，先预留写锁；即使revision行尚未存在，也会在读取快照前等待数据库写锁。MySQL不执行这条UPDATE。

取得写锁后仍重读并比较expectedRevision；不会覆盖等待期间已经提交的新版本。后续余额、回执、联合事务参与者和revision的提交／回滚顺序保持原协议。等待超过busy_timeout仍明确失败；这项可用性修复不承诺无限重试或长期负载能力。

## 原生构建与反例

- 修复前新增7项测试：6失败／0错误／0跳过，均捕获本应等待却提前失败；超时拒绝例本来就通过。
- 修复后新增7项与既有国库存储12项，共19项通过。覆盖有／无revision、普通／联合保存、等待期间胜出版本、参与者失败回滚、超时后显式保存一次。
- 完整Java21离线`clean verify`：7,472项／1,453套件，0失败／0错误／47跳过；Spotless和JaCoCo通过，未改排除配置，离线跳过联网CycloneDX。
- 本次全量实际执行国家贸易27项、生产38项、工厂结算37项。47项跳过由26个国家结算MySQL方法入口与21项既有跳过组成，后者包含2项国库MySQL入口。本轮另执行这2项国库MySQL测试；26个国家结算入口未在本轮重跑。
- 专用MySQL8.4.13／InnoDB／REPEATABLE-READ、flush_log=1、sync_binlog=1：2项通过，覆盖首次快照竞争唯一胜者、同ID转账竞争／重放及闲置实例刷新后保存。最终国库表0行，容器exit0且无OOM。

MySQL容器`starcore-treasury-writer-mysql-20261125`，loopback33326，数据库`starcore_treasury_it`。私密配置位于`/tmp/starcore-treasury-writer-mysql-20261125/mysql.env`，不得输出或上传。历史56项工厂MySQL结果和旧构件MySQL实服／中断恢复仍按旧报告保留，不作为本轮新原生JAR的实服证据。

## Paper等待、重启与重放

选定组合为Java21／Paper1.21.11、EssentialsX2.22.0／Vault1.7.3-b131、FancyNpcs2.10.1-java21+2、WorldEdit7.4.2／WorldGuard7.0.17；版本与二进制哈希由每次启动和锁文件复核。

夹具仅在独立测试表中写入以持有真实共享数据库写锁，不直接修改国库表、不替换存储或服务。存款与带回执转账都通过真实公开服务在Paper主线程执行；国家和初始资金为显式夹具种子。

第一轮给A存12.50，再由A转给B 7.25；第二轮重启后核对旧余额／回执，再给C存2.75；第三轮核对恢复。三次分别等待884、833、934毫秒，每次业务提交恰好增加一个revision，四次转账重放均ALREADY_APPLIED且不改SQL快照。正常停服各有一次原生最终状态保存，检查器单独核对，不当作重复业务提交。

| 国家 | 最终余额 | 变化 |
| --- | ---: | ---: |
| A | 1,000,005.25 | +12.50 −7.25 |
| B | 1,000,007.25 | +7.25 |
| C | 1,000,002.75 | +2.75 |

只保留一条原始转账回执；夹具存款不签发Chronicle贡献。三次服务端／客户端均正常exit0，所有停服双库逐份哈希和SQL核对通过。

旧JAR `b06b317f…ed3b` 的独立反例在同一存款调用处出现SQLITE_BUSY并拒绝12.50；停服库三个余额均为1,000,000.00，转账回执0条，独立写锁记录存在。原始FAILED会话及两进程exit0记录保留，不能计为成功启动。

## 同一双JAR业务回归

同一双JAR完成国家直售、DUNGEON、WAR各三次正常启动；加国库专项，共12次成功启动、24次正常进程退出。另有旧JAR预期失败的1次启动和2次正常退出，独立统计。

国家直售24条真实非OP命令、3次成交、29单位农业资源／75.00国库、12条贡献；DUNGEON与WAR均唯一结算、各8条贡献、重放保持。WAR真实非OP客户端占领两据点，原生100∶0判定胜利。

证据测试总计125项，121通过／4场景跳过；国库17、直售38、DUNGEON29、WAR41。四个检查器、旧JAR负例核验及全部证据测试在归档解压后再次通过。

| 场景 | `run/`下目录 | 端口 |
| --- | --- | ---: |
| 国库写锁 | `paper-epic-d4992706-dungeon-treasury-writer-1` | 29789 |
| 旧JAR预期失败 | `paper-epic-d4992706-dungeon-treasury-writer-red-1` | 29790 |
| 国家直售 | `paper-epic-d4992706-dungeon-treas-reg-trade-1` | 29791 |
| DUNGEON | `paper-epic-d4992706-dungeon-treas-reg-dungeon-1` | 29792 |
| WAR | `paper-epic-d4992706-war-treas-reg-war-1` | 29793 |

所有现场已停止，端口释放；原始世界、日志、数据库和工作区保留。第三方FancyAnalytics精确已知遥测诊断单列；原生错误、保存失败或SQLITE_BUSY不会当作成功场景的遥测忽略。

## 交付与复验

公开[证据ZIP](verification/treasury-writer-20261125-evidence.zip)与[机器清单](verification/treasury-writer-20261125-summary.json)：12,391,956字节，4,014个哈希成员加清单，SHA-256 `5b973a0e10bfe1d192f515a4507cfba730042c1f7fbca4309e642310ec5363d0`。逐成员校验、私密凭据扫描与解压复验通过。归档源码HEAD见机器清单，最终交接HEAD及CI见配套草稿PR。

本机工作区为`/home/add/starchronicle-treasury-writer`和`/home/add/starcore-treasury-writer`。`target/treasury-writer-20261125/`包含精确双JAR、源码tar与证据；冻结构件及完整构建报告另存`target/treasury-writer-frozen/`。不要修改`/home/add/starcore`或停止无关服务。

复验工具为`inspect-treasury-writer-paper.py`与`test_treasury_writer_acceptance.py`；设置`STARCHRONICLE_TREASURY_WRITER_RUN`指向停止的对应现场。归档保留失败的7项单元测试、通过的19项聚焦测试、原生完整verify、沿用的Chronicle构建、MySQL专项及全部现场脚本。第三方JAR和运行世界仅本机保留；异机复验须按锁文件补齐相同二进制。

本轮证明短暂写竞争下的实际保存与正常重启，不等于Paper进行中事务强制中断、物理断电、外部钱包共同提交、全栈Folia或长期容量通过。Paper主线程会等待锁，当前实测约1秒；延迟与容量优化仍需单独测量。继续旧工厂迁移／付款审计、旧钱包／市场、UNKNOWN、长期容量和完整战斗／领地等十阶段缺口。
