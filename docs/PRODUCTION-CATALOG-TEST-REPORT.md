# 国家生产配方与容量验收

本轮实现可编辑生产配方、只读校验／原子重载、每次批数和每国运行容量，并将容量复核放进库存扣除所在的 SQL 事务。旧批次固定的投入、产出、原操作者与领取时间不随配置变更；取消精确退还原投入。配置合同见[操作说明](PRODUCTION-CATALOG.md)。

## 精确源码与构件

| 项目 | 本轮来源 | 冻结 JAR SHA-256 |
| --- | --- | --- |
| StarChronicle | `1aacb5ad874e153fc41dbda1502d9dc8729f91fc`，产品源码未改 | `835588de9c62a73c0b2828b383beb9cbaaea0e56aeff103a6e798b6828ddb24a` |
| StarCore | `c9992387d27808f69a4c78411d5407c24bb717f8`，新增配方／容量代码 | `72825e31e0d62af9f21180df8332b46f2553320a92a70ced1e26a6ceffdabc92` |

工作分支均为 `codex/production-catalog-20261125`，分别叠加 Chronicle#19／StarCore#14。使用 Java21 和 `./mvnw -o -B -ntp clean verify`。Chronicle 979 项零失败／错误／跳过；原生 7,401 项零失败／错误，39 项跳过，其中18项是默认关闭的MySQL方法入口，另21项为既有跳过。原生Spotless、JaCoCo通过，离线SBOM未生成；既有完整构建排除配置未扩大覆盖。

原生新增配置／结算／命令专项在完整构建内合计70项通过（21＋38＋11）。早期68项聚焦运行的XML和日志保留，当时尚未加入最后两项命令测试。

## MySQL 专项

专用实例 `starcore-inflight-mysql-catalog-20261125`、loopback33324，固定MySQL镜像摘要 `8c233bad33d520a7aae1c1e619c3a9775f4a1ba2d463d692fada04ec7f40be00`；实际8.4.13、InnoDB、REPEATABLE-READ，TLS连接。

34个实际测试调用全部通过，零跳过；默认构建中的18个方法入口展开参数化后为34例，不能把两者简单相加。新增三例验证独立服务竞争最后容量、直接存储调用的事务内复核、停用配置后冻结旧批次的完成／重放。保留原31例完整事务快照、触发器失败、提交确认丢失、重读失败、并发、旧快照及完整日志删尾拒绝。

专项末尾独立读取11表的DDL与全部行，清理后均为空；容器正常exit0停止、无OOM、数据保留。私密文件 `/tmp/starcore-catalog-mysql-20261125/mysql.env` 不进入公开证据。现场 `starcore-production-catalog/target/catalog-mysql-20261125` 中的原始manifest记录运行时未提交前的base HEAD；测试源哈希与后续`c9992387`相同，归档另外绑定实际产品提交。该专项不代表本轮Paper／MySQL、进程中断或物理断电已复验。

## 所选 Paper 组合与验收范围

Java21／Paper1.21.11、EssentialsX2.22.0／Vault1.7.3-b131、FancyNpcs2.10.1-java21+2、WorldEdit7.4.2／WorldGuard7.0.17。此次实服两插件均使用SQLite，数据库检查逐轮在服务端停止后进行。Optional Essentials更新检查关闭并记录；正常防刷屏保持启用。测试国家和初始库存由明确的fixture API注入，生产资产操作均使用真实非OP协议客户端。

配方专项使用四种有效配置和一份重复JSON键的无效配置，覆盖无权限重载、validate不发布、有效重载、无效热重载保留、启动无效时拒绝新开工、已移除配方的旧批次、运行数／运行批数限制、配方单次限制、精确退款与已处理重放。数据库核对独立验证每条生产事件摘要／head、资源revision、实际库存、Chronicle规范历史／专用ingress／个人和国家贡献。

配方专项已完成三次正常启动、31条实际客户端命令、3次完成／1次取消、8条原生生产事件和6条文明贡献；净消耗mineral16＋energy6、产出industrial21。首轮只有开工未领取，Chronicle已绑定完成来源的sequence0，不能把它当成尚未绑定来源。冻结双插件五组回归正在收集最终结果。生产／空服补录／国家直售／DUNGEON／WAR据点路线各使用独立目录和端口，新JAR不继承旧构件的实服结论。

## 保留失败与边界

首`catalog-1`及`catalog-regression-production-1`因新工作区未配置Mineflayer模块路径，客户端启动exit1，未进入游戏；服务器正常exit0。已增加在服务器启动前解析客户端依赖的预检。第二次`catalog-2`的密集命令触发正常防刷屏踢出，全部已发命令及失败session保留；修正验收发送间隔后用新`catalog-3`目录完整重做，未修改产品或服务器防刷屏。

独立检查器初版遗漏了已轮询但尚无完工时的sequence0来源绑定，已依据真实CommittedHistoryJournal合同修正，并增加删除空来源绑定的反例。首轮证据反例测试10项失败源于测试修改SQLite后未关闭连接，留下WAL并被检查器正确拒绝；修正为显式关闭后重新执行，原失败日志保留。

容量针对RUNNING国家生产，不等于工厂建筑／等级。到期未领取、旧配方已删除、容量下调后的批次仍占用。重载只在本服发布；共享数据库的多实例须部署一致策略，不提供跨服配置共识。旧工厂缺少可靠原操作者与完整付费证据，仍不自动迁移、退料、发货或生成个人贡献。

本轮不包含Folia、真实部署、物理断电、Paper调用路径中断、长期容量和旧钱包／市场协议。上述历史验收按旧精确构件保留。十阶段整体仍active，未合并或部署生产服。

## 新设备复验入口

先安装Java21、Python3／PyYAML、Node与Mineflayer，并按锁文件下载第三方JAR。设置 `EPIC_MINEFLAYER_MODULE` 为该设备实际Mineflayer模块目录；会在启动服务器前预检。构建后选择新attempt、新空目录和空闲loopback端口，绝不覆盖旧失败现场。

```bash
export EPIC_MINEFLAYER_MODULE=/absolute/path/to/node_modules/mineflayer
python3 tools/prepare-production-catalog-paper.py --attempt catalog-new --port 29782 \
  --starcore /absolute/path/to/starcore/target/starcore-1.0.0.jar \
  --runtime /absolute/path/to/prepared-paper-runtime \
  --java-home /absolute/path/to/java21 --artifacts-dir /absolute/path/to/locked-plugins
python3 tools/epic-paper-session.py --run run/paper-epic-<new-sha8>-dungeon-catalog-new \
  --java-home /absolute/path/to/java21 --node /absolute/path/to/node \
  --boot 1 --scenario production-catalog-first
```

同一目录继续boot2／`production-catalog-second`、boot3／`production-catalog-third`，每次等前一服务器正常退出。运行 `inspect-production-catalog-paper.py <run>`；设置 `STARCHRONICLE_PRODUCTION_CATALOG_RUN=<run>` 后运行 `python3 -m unittest discover -s tools -p test_production_catalog_acceptance.py -v`。

五组默认回归入口仍为 `prepare-nation-production-paper.py`（主场景／`--offline`）、`prepare-nation-trade-paper.py`、`prepare-production-paper.py --campaign`、`prepare-battlefield-paper.py`；各自检查器必须通过。MySQL专项使用原生 `tools/run_nation_settlement_mysql.py --output <new-directory>` 和新的专用loopback `starcore_settlement_it` 空库，凭据仅通过其规定的环境变量注入。公开归档中的原始actual-runner保留本次实际编排；它的路径／attempt不能直接在旧现场重复运行。

重新构建的JAR必须记录其新指纹并重新执行实服，不能将源码相同视为字节相同。公开证据不携带第三方二进制和私有凭据；逐成员哈希和源码归档用于复核与跨设备继续开发。
