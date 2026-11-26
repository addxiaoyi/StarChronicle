# 玩家钱包直售恢复验收（2026-11-26）

直接`/trade offer`／`accept`已接入持久分步协议，Chronicle从独立成交来源生成原操作者贡献。玩家专项三次正常Paper启动、30条非OP命令、独立SQL／钱包文件核对及24项证据检查通过；六组同构件业务回归也通过，共20次正常启动、40次正常进程退出；238项证据测试230通过／8场景跳过，归档迁移复验通过。协议详见[玩家直售恢复合同](PLAYER-TRADE-RECOVERY.md)。十阶段整体保持active。

## 冻结源码与构件

| 项目 | 产品源码 | 实服JAR SHA-256 |
| --- | --- | --- |
| StarCore | `03c827868f0221b11a3478af42adf82233300a63` | `db46f7d885d1901e69c3556791803a3c0650848e2c09963efe00f95f6436a874` |
| StarChronicle | `47cdf354358c9b6a0103c9c7ee10b9c1090d4227` | `d4ff800e0503c513948cc61d66100de141f8c1de99b68880a9e9f55e4b785793` |

两库分支均为`codex/player-trade-recovery-20261126`；[StarCore #19](https://github.com/addxiaoyi/starcore/pull/19)叠加旧厂#18，[StarChronicle #24](https://github.com/addxiaoyi/StarChronicle/pull/24)叠加旧厂#23。均为草稿，未合并或部署生产服。后续CI重建构件按自己的指纹计证。

## 业务结果与持久边界

买方发出报价，原卖方接受。双方玩家／国家、资源／国库域、钱包提供者与版本、单价、货款、费用及有效期在报价时冻结。卖方国家库存先与RESERVED共同提交；每次钱包调用前持久INTENT，仅成功保存新INTENT的执行者可调用一次。空响应、异常、金额不一致或响应确认丢失停在待核对，不重复扣款。

明确扣款拒绝可返库存；明确卖方收款拒绝才尝试一次买方退款，确认后返还库存。交货、原生手续费／关税国库入账、精确税款回执和SETTLED共同SQL提交。历史与商业记录通过持久待办、稳定ID补写，成功后标记PROJECTED。原参与者保留查询权，只有原卖方能继续原请求；继任者不能冒用。

Chronicle事件、四个贡献查询视图及收件确认共同提交。卖方贡献是国家库存交付量，买方贡献是提供者已确认的含税付款；每方的个人和国家记录是查询视角，不能重复计收入或奖励。普通贸易历史独立消费，明确`trade.tax_payer=BUYER`，仍保留`NOT_PROVEN_BY_HISTORY_JOURNAL`且不额外签发贡献。旧API缺少`buyerFunded()`或返回false时，旧事件内容不变。

Vault SUCCESS仅是提供者确认，没有持久幂等键或可独立查询的付款凭据。钱包与原生SQL不共用事务；本轮正常重启不证明外部付款磁盘持久化或物理断电原子性。原`TradeAcceptedEvent`仍是尽力实时通知，PROJECTED与通知之间中断可漏通知，不能作为持久季节奖励投递。

## 本地构建与真实MySQL

| 验证 | 实际结果 |
| --- | --- |
| Chronicle Java21 `mvn -o clean verify` | 1,017项／102套件，0失败／错误／跳过 |
| 原生Java21 `./mvnw -o -Dcyclonedx.skip=true clean verify` | 7,604项／1,458套件，0失败／错误、76跳过；Spotless／JaCoCo通过 |
| 独立聚焦构建 | 146例全通过：41例SQLite玩家直售、41例MySQL玩家直售、64例MySQL国家贸易／生产／工厂 |

原生76项跳过由55个opt-in MySQL方法入口与21项既有跳过构成；真实MySQL另展开105例全部执行。不能把146例全部称为MySQL，也不能与完整构建相加作为去重总数。仅离线跳过联网CycloneDX，检查排除项未改。

玩家协议41例在SQLite与MySQL分别执行，覆盖各钱包INTENT／响应保存前后中断、明确拒绝与退款、不确定金额、SQL提交前后异常、容量不足、64个未终结上限、税务晚注册、投影重试、权限／过期／取消、删尾／摘要／税款篡改、双实例竞争。Error注入和JDBC确认丢失是测试故障，不等于实服物理断电。

MySQL8.4.13／InnoDB／REPEATABLE-READ，flush_log=1、sync_binlog=1、binlog=1。自有容器`starcore-player-trade-mysql-20261126`已正常exit0、无OOM，数据保留。真实MySQL测试与本轮Paper／SQLite实服分别计证；旧构件MySQL实服结果不转移到本构件。

## 玩家专项实服

固定Java21／Paper1.21.11、EssentialsX2.22.0／Vault1.7.3-b131、FancyNpcs2.10.1-java21+2、WorldEdit7.4.2／WorldGuard7.0.17。国家、库存和余额由夹具显式准备；EpicA/B/C/D为真实非OP客户端。国家库存初始各100，国库各1,000,000；买方A钱包1,000，卖方B与D各10。

| 正常启动 | 实际操作与停止状态 |
| --- | --- |
| 第一轮 | A向B购买20×2.50，手续费1.00＋关税2.00；C/D越权接受被拒，C不能查详情。重复接受不再付款；另建5×4.00待接受报价后停服。A余额947、B60。 |
| 第二轮 | 关税改为40%，重启前报价仍按原费用0.40＋0.80成交。取消／拒绝后不能接受。D从A国加入B国并继任创始人，仍不能冒用B旧成交；A对D新建4×1.25报价。A925.80、B80。 |
| 第三轮 | D接受新报价，费用0.10＋关税2.00；多次接受／原B继续均不重复付款，A不能执行卖方继续。实际查询个人和国家贡献。最终A918.70、B80、D15。 |

三轮均正常停服，六个服务端／客户端进程exit0。最终交付29单位资源，货款75.00，手续费1.50＋关税4.80，买方总付81.30；A国库存129、B国71，A国库1,000,006.30。共有3笔SETTLED、3条玩家来源事件、12条贡献视图、3条普通贸易历史事件、6条商业记录。30条真实命令逐条核对主体、响应和协议消息。

24项证据测试覆盖日志删尾／摘要、冻结条款、精确税款、资源与国库、历史付税标记、商业记录、来源游标、贡献金额／主体、付款证据夸大、Essentials文件、异常退出、重放命令遗漏、脚本／构件变化。另验证参与者／国家集合顺序不改变事件含义，普通历史停滞或额外贡献必须被拒。

## 同构件回归

七组均使用表中精确双JAR，独立目录与数据库，正常停止后检查。汇总保留原runner与修复后的测试日志，没有覆盖失败文件。

| 场景 | 正常启动／进程退出 | 证据通过／跳过 | 实际结果 |
| --- | --- | --- | --- |
| 玩家直售 | 3／6 | 24／0 | 30命令、3成交、12贡献、钱包／库存／税款及双历史一致 |
| DUNGEON | 3／6 | 27／2 | 国家副本结算、4条援助来源、8贡献与重放 |
| WAR | 3／6 | 39／2 | 真实两据点100∶0，VICTORY_DETERMINED；唯一结算及重放 |
| 国家直售 | 3／6 | 38／0 | 24命令、3成交、12贡献，国库／库存共同回执 |
| 付费工厂 | 3／6 | 32／0 | 45命令、6工厂变更、3生产完成／1取消、6贡献 |
| 默认生产 | 3／6 | 33／4 | 24命令、4完成、8贡献，跨重启原批次与原操作者 |
| 空服生产补录 | 2／4 | 37／0 | 11命令、1完成、2贡献，恢复轮询后无人在线时入库 |
| 合计 | 20／40 | 230／8 | 238项证据测试，跳过均为场景不适用 |

本轮WAR仅覆盖原生菜单据点积分胜利，不包含PvP、完整攻城或领地转移。FancyAnalytics外部遥测错误按既有明确规则单独保留；原生持久化／Chronicle来源错误不被忽略。全部自有Paper服务已正常停止。

## 保留失败与修正

`player-trade-1`夹具编译因两个helper缺少异常声明失败，未启动服务。`player-trade-2`发现资源模块先于税务模块注册，报价仅有1.00手续费而遗漏2.00关税；客户端在付款前拒绝，正常停服。原生改为创建报价时解析税务服务，缺少服务拒绝跨国报价。

`player-trade-3`的三轮资产／贡献已完成，但旧Chronicle普通贸易读取器把卖方净收入固定为货款减税，持续报错并停在游标0，独立检查拒绝该现场。修复可选buyerFunded语义后，在`player-trade-4`新目录重新执行；没有删除错误日志或关闭普通来源。旧`player-reg-1`和`player-reg-2`在候选被替换后停止，自有服务均exit0，原现场保留。

最终现场首轮独立检查另发现检查器把无序参与者集合的JSON顺序当作内容差异。修正为按事件合同比较集合，增加顺序不敏感回归；原失败日志与未执行测试报告保留，未修改产品、数据库或服务日志。国家直售旧证据反例使用固定的11月25日时间，在跨日新现场提前触发时间下界拒绝；改为实际记录时间加一秒后，原跨重启前缀检查按预期拒绝，38项全部通过。该失败日志和原runner结果保留，剩余三组在新runner目录继续。早期构建、聚焦和MySQL fixture失败也保留；其中极大资源grant触发现有单次上限，拆分种子grant后最终用例通过。

## 复验与交付

```bash
python3 tools/inspect-player-trade-paper.py run/paper-epic-d4ff800e-dungeon-player-trade-4
STARCHRONICLE_PLAYER_TRADE_RUN="$PWD/run/paper-epic-d4ff800e-dungeon-player-trade-4" python3 -m unittest discover -s tools -p test_player_trade_acceptance.py -v
```

重做实服须新attempt及空闲端口，以`prepare-player-trade-paper.py`准备后，按`epic-paper-session.py`的`player-trade-first/second/third`运行。六组业务使用`run-player-trade-regressions.py --frozen-dir ...`，每组独立世界／数据库。不得覆盖旧目录，实际参数和锁定依赖见工具帮助与各现场preparation.json。

本机工作区为`/home/add/starchronicle-player-trade-recovery`和`/home/add/starcore-player-trade-recovery`；冻结双JAR与完整构建报告在前者`target/player-trade-frozen-3/`。本轮归档、凭据扫描和可迁移包的解压复验均已通过。私密MySQL环境文件禁止输出或上传；不修改`/home/add/starcore`或停止无关服务。

公开[证据ZIP](verification/player-trade-20261126-evidence.zip)与[机器清单](verification/player-trade-20261126-summary.json)：33,077,504字节，10,988个哈希成员加清单，SHA-256 `9e38e48041888824673dbdba6ef43836097306285488e33b9665da0d9672d89d`。保留七个接受现场、六个失败／替换现场、全量构建、MySQL、原失败测试与runner记录。公开包不含世界或第三方二进制；原世界和全部现场继续留在本机。

`target/player-trade-20261126/exact-binaries.zip`另保存15个去重后的精确构件：双产品、Paper、五个第三方插件与七个实际夹具。大小113,572,736字节，SHA-256 `e24f850314bc89795853fb7d3920471027bab194332e153d73da0bbd473b329e`。该包只保存在本机，未上传GitHub。25份配置中的所列秘密字段已脱敏，包括自动生成的Paper管理密钥；实际私密值扫描通过。原私密配置不修改。

迁移复验从这两个包恢复所有文件并逐一校验哈希，七个检查器和238项测试结果与原停止现场一致。复验在本机全新目录完成，不声称已在另一台设备启动游戏服。把包和工具复制到另一设备后可执行：

```bash
python3 tools/verify-player-trade-handoff.py --evidence player-trade-20261126-evidence.zip --binaries exact-binaries.zip --output /new/path/player-trade-check
```

需要Python3与PyYAML；输出目录必须不存在。工具只检查停止证据，不启动服务器。归档源码HEAD为Chronicle `9eac7dabbeb92e85f0d16e3a3af357d3e92e1fc7`、原生`03c827868f0221b11a3478af42adf82233300a63`；后续文档提交不改变冻结src／pom。最终源码tar、精确构件包和交付清单位于`target/player-trade-final-handoff/`，最终HEAD／CI／交付指纹见配套草稿正文。

中间提交`5c03bd45…fa3`的Linux CI [run45737308999](https://github.com/addxiaoyi/StarChronicle/actions/runs/45737308999)／job139633543221成功，1,017项测试、报告生成、交付校验和上传均通过。CI JAR为`6c184cfa…9568`，与实服冻结JAR分别计证。原生工作流只触发main目标，本叠加分支没有远端CI结论，采用保留的本地完整日志。

剩余工作包括市场订单簿／快捷买卖、其他内部钱包入口、个人背包、永久UNKNOWN的可信处置、长期全日志核对成本、Paper实际调用中断／物理断电、旧工厂未明资产、完整Folia、真实建筑／领地和战斗。具体十阶段状态见[本轮交接](HANDOFF-2026-11-26.md)。
