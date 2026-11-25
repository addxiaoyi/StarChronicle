# Paper＋MySQL 双插件与国家史诗验收

2026-11-25：同一冻结双JAR在所选Paper／第三方插件组合接入真实MySQL8.4.13，完成主生产3＋空服2＋国家直售3＋DUNGEON3＋WAR3，共14次游戏服启动、9次数据库正常重启。28个游戏服／客户端进程全部exit0，最终专用MySQL容器也正常exit0停止。原始MySQL行、类型、视图定义及重启前后完整状态与业务结果独立核对通过。

本增量仅修改验收工具与报告，两库产品源码不变，没有重建实服双JAR。主项目979项与原生7,354项（21项既有跳过）属于上一冻结构建，31项原生MySQL事务测试属于[上一专项](MYSQL-SETTLEMENT-TEST-REPORT.md)。不能把这些数字相加称为本轮新构建。

## 配套源码和实际后端

| 项目 | 精确值 |
| --- | --- |
| Chronicle分支 | `codex/mysql-paper-20261125`，草稿[PR #17](https://github.com/addxiaoyi/StarChronicle/pull/17)，叠加#16 |
| 原生分支／提交 | `codex/mysql-settlement-20261125`／`362a02861f90de394f5bef0565208fa84862b0a9`，草稿[PR #13](https://github.com/addxiaoyi/starcore/pull/13)，无需新原生分支 |
| 产品源码 | Chronicle `2f800988fbdd99447974e891a0623b39d7ed028a`；StarCore `59ed52abbed5521eff6383df004985b1e13e1bbe` |
| Chronicle JAR SHA-256 | `549f99d9e2fca919d42263fc184e3f790d101b7f65a1c91f067eb9716ce7fc5f` |
| 原生 JAR SHA-256 | `26e6fb895d0ee6422c8347b63dc442763b475ef5eb91f8c92c94702fb2ebb886` |
| MySQL | Community Server8.4.13／InnoDB／REPEATABLE-READ，专用容器`starcore-paper-mysql-20261125`、127.0.0.1:33317 |
| 镜像 | `public.ecr.aws/docker/library/mysql@sha256:8c233bad33d520a7aae1c1e619c3a9775f4a1ba2d463d692fada04ec7f40be00` |
| 第三方组合 | Java21／Paper1.21.11，EssentialsX2.22.0／Vault1.7.3-b131，FancyNpcs2.10.1-java21+2，WorldEdit7.4.2／WorldGuard7.0.17；[精确锁](verification/production-stack-candidate-20261125.json) |

**StarCore实际后端为MySQL，StarChronicle仍为SQLite。** 每个`starcore.db`证据文件都是停服后从原始MySQL快照派生的审计投影，不能标成服务端原生SQLite存档。快照保存全部InnoDB基表的列名、DATA_TYPE／COLUMN_TYPE、空值／排序规则元数据和原始值，二进制／Decimal／时间等显式编码；另保存视图定义。每轮对所有列与值逐一比较，再复用业务检查器验证资产与回执。投影不承载MySQL索引、约束、排序规则语义或可执行视图，也不是生产备份。

每个现场初始目录、世界和专用数据库均为空且独立。插件配置启用`database.fail-fast`，每次实际启动日志确认MySQL连接、原生生命周期和正常关闭；跨启动保留的审计投影若被服务端写入，采证直接拒绝。TLS用于回环加密，没有外部主机身份验收结论。

## 五个真实现场

| 目录 | 游戏启动 | 数据库重启 | 实际命令结果 |
| --- | ---: | ---: | --- |
| `paper-epic-549f99d9-dungeon-mysql-production-main-2` | 3 | 2 | 24 |
| `paper-epic-549f99d9-dungeon-mysql-production-offline-1` | 2 | 1 | 11 |
| `paper-epic-549f99d9-dungeon-mysql-trade-1` | 3 | 2 | 24 |
| `paper-epic-549f99d9-dungeon-mysql-dungeon-1` | 3 | 2 | 史诗／贡献协议 |
| `paper-epic-549f99d9-war-mysql-war-1` | 3 | 2 | 史诗／贡献协议 |

主生产24条非OP命令完成5个run的开工、4完成／1取消，保留10条状态事件、4份唯一完成回执及8条文明贡献；净投入18矿物＋6能源、产出12工业资源。过早领取、普通成员／别国操作、失去创始人身份均拒绝；现任创始人领取旧批次仍归原开工人；重放不重复发货或计贡献。

空服11条命令；第一次停服时原生已有1完成、Chronicle无对应事件或贡献。数据库正常重启并恢复正常poll后，在任何客户端启动前补录1条历史与2条贡献；重放保持不变。保留的是**1笔RUNNING任务、批量2**，不是两笔任务；历史生产报告中的“2批RUNNING”应按这个批量解释。

国家直售24条非OP命令、3成交、29农业资源、75.00国库金额、12条贡献；跨重启保留报价和授权边界，取消／旧创始人报价拒绝，现任创始人新报价保留本人。双方国库及国家库存守恒、资源与国库回执、连续结算日志、Chronicle专用收件／来源游标与贡献逐字段吻合。

DUNGEON与WAR分别完成三次启动、第二轮唯一结算和第三轮恢复／拒绝重放，各有8条援助贡献与3份个人后续。WAR为真实非OP菜单占领两据点、100∶0、AGGRESSOR／VICTORY_DETERMINED；副本是三秒原生生存房间。未覆盖PvP、完整攻城或世界领地转移。

## 检查、失败和保留证据

每个现场执行31项MySQL证据检查，共155次通过。已有业务证据检查分别为主生产33通过／4空服专属跳过、空服37全通过、贸易38全通过、副本27通过／2旧场景跳过、国战39通过／2旧场景跳过；合计174通过／8跳过。两类合计337次执行、329通过／8跳过，跳过不计为通过。

新负例在真实证据副本上删除／重哈希MySQL表、资产或投影、改服务器域／TLS、改视图、删除重启记录、伪造正常退出与启动时间、修改已冻结脚本、改后端或最终投影，必须拒绝；另验证高精度Decimal、二进制和NULL不会丢失。原始快照与每次数据库重启之间逐表、逐列、逐行及视图定义完全一致，游戏服必须在数据库恢复验证完成后才启动。

首个`mysql-production-main-1`的真实业务与两个进程正常结束，但采证工具误将`information_schema.TABLES`返回的视图当成InnoDB基表而失败。失败session、冻结原脚本、日志、原数据库及标明POST_FAILURE_DIAGNOSTIC_ONLY的诊断快照均保留；修正后使用新`mysql-production-main-2`和`starcore_paper_production_retry2`完整重做，没有重写失败为成功或修改产品。

可选FancyAnalytics的status200空遥测按既有精确例外保留；原生持久化与生命周期错误不豁免。首启动的旧本地化表已存在迁移警告和ProtocolLib缺失的原生菜单回退均留在原始日志，后者没有阻止真实据点GUI验收。

[证据ZIP](verification/mysql-paper-20261125-evidence.zip)：4,709,820字节、994个逐项SHA-256成员全部核验；ZIP SHA-256 `08670c71b91c4eb3038cf9f5659623ed4dfd0d15174df282544a74ea99982e82`。[机器清单](verification/mysql-paper-20261125-summary.json)列出每次启动、原始快照、审计投影、重启状态、测试与失败。本机`target/mysql-paper-20261125/`还含冻结双JAR与两库源码tar；验收工具源码提交`ecff8876605e5162d9a3ec0014b700b8b0fb8b9f`。配置测试密钥脱敏，归档逐字扫描确认未包含MySQL应用或root密码；不归档私密`mysql.env`。

工具初始提交`8f158cb`的PR CI [45694428299](https://github.com/addxiaoyi/StarChronicle/actions/runs/45694428299)另行通过979项测试及1,452条交付校验。该CI JAR为`959a09460f7a69a575fecf34812846bab595c82c582ee9c18991a61aefbc0a58`，与实服冻结构件分别记录；原始CI日志和其摘要随证据归档。上一#16最终`1d79de2`的push CI45692038589也成功，日志与独立构件指纹同样保留。最终报告提交的CI状态见PR #17。

## 新设备复验

需要Java21、Node22／Mineflayer4.39.0、Docker和Python的PyMySQL1.1.2／PyYAML。先按两库分支重建，新的JAR指纹必须用新目录复验，不能继承上述构件结论。将官方MySQL固定digest拉到本机，运行`prepare-mysql-paper-instance.py --directory /ABS/PRIVATE/NEW --port 33317`准备新专用实例；它拒绝已存在的容器／目录／占用端口。当前已运行实例来自保留的原准备现场，后增的实例准备入口仅完成静态解析，尚未另建实例执行启动验收。

从私密配置在本地注入`STARCORE_DB_PASSWORD`，并设置`EPIC_MINEFLAYER_MODULE`。不要把密码放在命令参数或Git文件。运行入口使用所需Python环境：

```bash
python tools/run-mysql-paper-acceptance.py --scenario production --attempt UNIQUE \
  --port 29750 --database starcore_paper_production --instance /ABS/PRIVATE/NEW/preparation.json \
  --starcore /ABS/StarCore.jar --runtime /ABS/initialized-paper \
  --artifacts-dir /ABS/locked-plugins --java-home /ABS/java21 --node /ABS/node
```

分别用新attempt／端口／空数据库执行`offline`、`trade`、`dungeon`、`war`。入口执行准备、绑定、全部游戏启动、每次之间的数据库正常重启、业务审计与两组证据回归；失败立即停止，不能覆盖已有目录。每个原始boot日志和捕获脚本在`run/.../evidence`，入口日志在`target/mysql-paper-ATTEMPT`。主生产本轮沿用逐步等价命令，其余四现场实际经该入口执行。

验收结束用`stop-mysql-paper.py --instance .../preparation.json`正常停止本任务数据库并保存状态；所有关联Paper端口必须已释放，容器ID和所有权标签必须匹配。保留数据库与世界以便审查。检查器`inspect-mysql-paper.py RUN`只读本地证据，不要求MySQL仍运行；`archive-mysql-paper-evidence.py --help`给出五现场归档入口。

本轮关闭的是所选组合的Paper＋MySQL正常联合重启验收缺口。实际断电／运行中数据库中断、备份恢复、复制、远端TLS、长期负载、超大存档、旧工厂恢复、旧钱包／市场共同提交及全栈Folia仍待推进；候选未合并或生产部署，十阶段保持active。
