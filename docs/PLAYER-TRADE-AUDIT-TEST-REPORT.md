# 玩家直售审计与实际调用中断验收

原交易参与者的只读审计已实现。原生7,626项完整构建零失败／错误，82项跳过；真实MySQL51例全部通过。最终双构件完成四个真实钱包中断窗口及正常交易回归，共19次Paper启动、4次预期SIGKILL、34次正常进程退出、110条实际非OP命令，84项独立证据测试全部通过。十阶段整体仍在推进，未合并或生产部署。

## 精确构件

| 产品 | 产品源码 | 本轮Paper JAR SHA-256 |
| --- | --- | --- |
| StarCore | `61db75125721c5097c8c7c7fa3f4e8d0745e532d` | `7dcb5f11e53e287a895ab83fcc56e3bbc845489f36267a1af1f4c58f191e35b6` |
| StarChronicle | 沿用`47cdf354358c9b6a0103c9c7ee10b9c1090d4227` | `d4ff800e0503c513948cc61d66100de141f8c1de99b68880a9e9f55e4b785793` |

本轮两库分支均为`codex/player-trade-audit-20261126`，草稿为[Chronicle #25](https://github.com/addxiaoyi/StarChronicle/pull/25)和[Native #20](https://github.com/addxiaoyi/starcore/pull/20)，分别叠加Chronicle#24与原生玩家恢复#19。冻结包在`target/audit-frozen-2/`。Chronicle产品代码及POM未修改，沿用已验收构件及其1,017项构建报告；本轮工具或文档的CI构件另记指纹。

固定组合继续为Java21／Paper1.21.11、EssentialsX2.22.0／Vault1.7.3-b131、FancyNpcs2.10.1-java21+2、WorldEdit7.4.2、WorldGuard7.0.17。所有测试均为隔离服，显式种入初始国家、国库、国家库存和玩家余额。

## 构建与数据库

| 验证 | 实际结果 |
| --- | --- |
| 原生Java21 `./mvnw -o -Dcyclonedx.skip=true spotless:apply clean verify` | 7,626项／1,459套件，0失败／错误、82跳过；Spotless及JaCoCo通过 |
| 玩家恢复／审计SQLite | 51例，包含在上述全量构建中 |
| 原生实际MySQL玩家恢复／审计 | 51例全部通过，0跳过；独立执行，不与全量相加当作去重总数 |
| Chronicle沿用构件 | 1,017项／102套件，0失败／错误／跳过 |

82项跳过为61个结算opt-in MySQL方法入口（国家结算30、玩家31）和21项既有跳过。MySQL运行时参数化展开为玩家51例。本轮未重新执行旧国家结算64例；此前通过记录仍属于旧产品检查点。数据库为真实MySQL8.4.13、InnoDB、REPEATABLE-READ，`innodb_flush_log_at_trx_commit=1`、`sync_binlog=1`、`log_bin=1`。独立任务容器已正常停止，exit0、无OOM，数据及私密配置保留。

新增测试覆盖原参与者／离国查询、旁观者拒绝、不可变完整步骤、穿插其他报价、钱包服务缺失／非写线程查询、缺失响应与已记录UNKNOWN分别处理、退款／投影待办不被查询推进、损坏日志／税款拒绝及生产存储路由拒绝退回文件。没有新增管理员强制恢复接口。

## Paper验证方法

每个中断现场实际完成四次启动：创建原报价后正常停服；真实非OP卖方接受，测试夹具在指定钱包调用边界暂停，由控制器对自己创建的JVM执行SIGKILL；两次正常重启，双方审计、旁观者拒绝、买方继续拒绝、原卖方重复接受／继续和再次审计。

夹具通过测试反射代理保留原钱包的提供者身份，把实际调用委托给原生`PlayerVaultEconomyService`及真实Essentials。产品没有测试故障开关。退款场景把卖方初始余额设为Essentials真实上限，由提供者明确拒绝卖方收款，再中断真实买方退款后的返回窗口；不伪造拒绝或成功响应。

每次中断均保存原始数据库／WAL／SHM／journal，再从独立副本读取已提交SQL视图。控制器在杀进程前观察预期Essentials余额文件，因此结果只证明这些实际窗口的恢复，不证明物理断电、提供者通用磁盘耐久性、或Vault与SQL共同事务。

四个窗口是：买方扣款前、买方扣款后、卖方收款后、买方退款后。预期分别留在`DEBIT_INTENT`、`DEBIT_INTENT`、`CREDIT_INTENT`、`REFUND_INTENT`。审计应显示`INTENT_WITHOUT_RESULT`；库存保持20单位托管，买方不收货、国库不收税、成交与贡献均为0。当前余额不同也不能解除该状态。

正常交易另用同一双构件完成三次启动／30条命令、3笔成交、29资源、买方付款81.30、费用6.30、12贡献视图，24项独立证据反例测试通过。各场景结束后核对SQL、Essentials文件、原始协议消息、进程退出及API快照，不能只使用夹具的“通过”文字。

## 实际结果

| 场景 | 启动／JVM强制中断 | 最终原状态 | 钱包文件A／B | 独立证据测试 |
| --- | --- | --- | --- | --- |
| 扣款前 | 4／1 | `DEBIT_INTENT` | 1000.00／10.00 | 15通过 |
| 扣款后 | 4／1 | `DEBIT_INTENT` | 947.00／10.00 | 15通过 |
| 卖方收款后 | 4／1 | `CREDIT_INTENT` | 947.00／60.00 | 15通过 |
| 买方退款后 | 4／1 | `REFUND_INTENT` | 1000.00／10000000000000.00 | 15通过 |
| 正常三笔交易 | 3／0 | 3笔`PROJECTED` | A918.70／B80.00／D15.00 | 24通过 |

四个中断现场均保持买方库存100、卖方库存80、20单位托管，国库不收取该笔费用，成交／贡献均为0；每组20条真实命令。退款场景卖方余额是明确设置的Essentials上限，并未接受货款。每组两次恢复中的API／SQL／摘要完全一致，钱包调用计数为0。正常交易30条命令和三笔结算维持上一协议。

现场路径为`run/paper-epic-d4ff800e-dungeon-audit-3-{debit-before,debit-after,credit-after,refund-after}`，正常场景为`run/paper-epic-d4ff800e-dungeon-audit-normal-1`。所有本轮服务器及数据库已停止，预期中断与正常退出分别计数。原始控制器、客户端脚本、Java夹具、服务端／协议日志及每次停止快照均保留。

独立检查器`tools/inspect-player-trade-audit-paper.py`验证完整日志摘要、精确状态路径、原始WAL恢复、库存托管、国库、零成交／贡献、提供者实际参数／返回值、协议命令、进程身份和重复恢复。每场景15个反例包含重算文件哈希后的库存／余额／税款修改、删意图、改头、伪造成功、错误PID／退出、缺失原始库和脚本／构件漂移；正常交易另24例。

## 保留失败

1. `paper-epic-d4ff800e-dungeon-audit-debit-before-1`：新测试场景未进入客户端`/trade`白名单，报价前被工具拦下；服务及客户端均正常退出。修正测试工具后新建现场。
2. `paper-epic-d4ff800e-dungeon-audit-2-debit-before`：真实`/trade audit`暴露生产存储路由缺少新接口转发，返回通用失败；仅创建报价，无钱包调用。原生`f1057213`／JAR`5bcbd68b…4624`不能算通过实服。修复为`61db7512`及上述新构件，保留旧日志／数据库。
3. 原生full2新增路由反例首次没有预置SQL权威标记，导致首次迁移合法读取旧文件被错误判成回退。测试补齐“已固定SQL”场景后full3通过；full2的1个失败及全部报告保留。
4. 独立检查器首轮把Node弃用警告当JSON解析失败，修正为解析JSON记录并继续核验日志／协议；原现场和原始输出未改写。

原生full1／首轮MySQL50例均对应旧候选；最终只采用full3及MySQL51例。失败不计入成功场景；所有旧worktree、世界和数据库保留。

## 归档与跨设备复验

公开证据为`docs/verification/player-trade-audit-20261126-evidence.zip`，19,551,940字节、6,625个哈希成员加manifest，SHA-256 `eabbb9b43639dd3b5c2df384869d0eb3bd35d6831d8b87b3a2172675d680f8ab`。包含成功及失败现场、原始中断数据库、构建／MySQL日志、工具和证据测试；不包含世界或第三方二进制。配置文件不公开，实际私密环境及配置秘密值逐字节扫描通过。机器摘要见[证据清单](verification/player-trade-audit-20261126-summary.json)。

独立精确构件包在`target/player-trade-audit-20261126/exact-binaries.zip`，113,475,508字节，SHA-256 `0a5169694aa9432357a5948bb31986bc992773ccf6726c57919c2dda46133320`，只在本地，未上传GitHub。包含用于成功五组现场的双产品、Paper、第三方和夹具构件，按内容哈希去重。

两个包已在全新目录`target/player-trade-audit-20261126/transferred-check/`完成字节校验、五个检查器及84项证据测试。复验不依赖原现场绝对路径。本次是在本机恢复到独立目录，不声称已经在另一设备执行或启动新游戏服。

```bash
python3 tools/verify-player-trade-handoff.py \
  --evidence docs/verification/player-trade-audit-20261126-evidence.zip \
  --binaries /path/to/exact-binaries.zip \
  --output /new/empty/location
```

上条命令要求目标目录尚不存在。最终跨设备交付入口为`target/player-trade-audit-final-handoff/START-HERE.txt`，提供两库最终源码、精确产品、两包和逐文件哈希；所有旧交接包均保留。最后提交的CI与源码HEAD写入草稿PR正文，不为了更新自身HEAD反复重建或修改实服构件。

## 尚未覆盖

Vault无原调用ID及持久可查询回执，永久UNKNOWN仍不能可信解除。新审计只提供经过本地完整性校验的材料。外部回执协议、市场／快捷买卖、其他钱包入口、旧已付工厂、全日志长期容量、Folia、物理断电、在线联合恢复和生产部署仍待继续。上一轮DUNGEON／WAR等六组结果按其原生旧指纹独立保留，本轮不把旧结果转记为新构件全栈验收。
