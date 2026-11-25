# 国家生产批次与文明贡献验收

2026-11-25：国家库存生产已实现开工扣料、到期领取、精确取消退款及原操作者文明贡献。Chronicle完整构建979项通过，原生7,354项零失败／错误、21项既有跳过。所选Paper组合完成主生产3＋空服2＋贸易3＋DUNGEON3＋WAR3，共14次正常启动；174项证据检查通过，8项场景检查跳过。十阶段仍在推进，候选未合并或部署生产服。

## 精确源码与构件

| 项目 | 产品源码 | 实服JAR SHA-256 |
| --- | --- | --- |
| StarChronicle | `2f800988fbdd99447974e891a0623b39d7ed028a`，分支 `codex/production-settlement-20261125` | `549f99d9e2fca919d42263fc184e3f790d101b7f65a1c91f067eb9716ce7fc5f` |
| StarCore | `59ed52abbed5521eff6383df004985b1e13e1bbe`，分支 `codex/atomic-production-20261125` | `26e6fb895d0ee6422c8347b63dc442763b475ef5eb91f8c92c94702fb2ebb886` |

原生草稿[PR #12](https://github.com/addxiaoyi/starcore/pull/12)叠加贸易#11；Chronicle草稿[PR #15](https://github.com/addxiaoyi/StarChronicle/pull/15)叠加贸易#14。工具与报告修改未重建产品。原生工作流仅自动覆盖main，候选分支未触发远端CI；其结论来自本地完整构建和实服。Chronicle最终CI结果及CI构件指纹登记在本轮PR说明，新构件不能继承以上JAR的实服结论。

用户授权自行确定组合：Java21／Paper1.21.11、EssentialsX2.22.0／Vault1.7.3-b131、FancyNpcs2.10.1-java21+2、WorldEdit7.4.2／WorldGuard7.0.17，见[组合锁](verification/production-stack-candidate-20261125.json)。七插件在每轮启动实际加载，原生业务经济仍为PlayerVaultEconomyService；本生产协议明确只使用国家库存。

## 实际生产与资产

[合同](NATION-PRODUCTION-CONTRACT.md)定义三个默认配方、固定批次条款、SQL事务、原操作者与权限边界。开工扣料和RUNNING记录共同提交；领取产物和唯一完成回执共同提交；取消退料和CANCELLED记录共同提交。正常重启后等待仍按原始readyAt计算，玩家明确领取，不自动发货。

主场景 `run/paper-epic-549f99d9-dungeon-production-candidate-2`，端口29740。A／B／C各有测试国家，D加入A国；每国显式种子100农业资源与1,000,000.00国库，A另有100矿产和100能源。四名真实非OP客户端执行24条命令：

| 启动 | 实际行为与正常停服状态 |
| --- | --- |
| production-first | A查看配方并开1批工业精炼；D开工、D／C领取及A过早领取均拒绝。到期领取2 industrial，重放不增；再开2批，保留RUNNING跨重启。 |
| production-second | 读取原RUNNING批次，到期领取4 industrial；另开1批后取消，精确退回原输入，领取被拒。A再开1批后经真实NationService交接创始人给D，A领取被拒；D新开2批。 |
| production-third | D领取A留下的1批，贡献仍归原A；D领取自己2批。已完成重放和已取消领取不增产物。A／D查询个人贡献，D查询国家贡献。 |

最终5批次、4 COMPLETED／1 CANCELLED、10条连续生产事件、4条完成回执。净消耗mineral18＋energy6，交付industrial12；A库存mineral82、energy94、industrial12，三国农业与国库均未变。8条CIVILIZATION贡献：A发起的3次完成、D发起的1次完成各有原国家／原个人视图；两个主体不能加总成双倍产量。取消不生成贡献，领取人不会替换原开工人。

空服场景 `run/paper-epic-549f99d9-dungeon-production-offline-1`，29741，11条实际命令。第一轮poll72000tick，真实完成1批产出2 industrial，再开2批待完成。停服SQL证明原生已有1完成，而Chronicle该来源事件／ingress／贡献均为0。仅停服后用`prepare-nation-production-resume.py`把poll改为20，保留前后配置及哈希；第二轮在任何客户端进程启动前补录1事件／2贡献。随后四人登录、重放和查询，原生资产与批次未变化。待完成2批仍保留原扣料状态。

## 同一双构件回归

DUNGEON：`run/paper-epic-549f99d9-dungeon-production-dungeon-1`，29742。三轮`first|second|third --contributions`全部通过：真实任务制作、国库／资源援助、外交、原生三秒生存房间、重启后唯一结算与第三轮重放。12项提交操作、4条援助事件、8条援助贡献、A／B／D各一份后续。

WAR：`run/paper-epic-549f99d9-war-production-war-1`，29743，三轮同样通过。非OP且无战争管理员权限的A通过真实GUI占领两座各50分据点，原生AGGRESSOR／VICTORY_DETERMINED／100∶0；六次不满足条件的尝试被拒。持久据点归属、计分标记、结束回执、13项操作、唯一结算、8条援助贡献及3份后续跨两次重启不变。

贸易：`run/paper-epic-549f99d9-dungeon-production-trade-1`，29744，三轮`sale-first|sale-second|sale-third`通过。24条实际命令、3笔成交、29农业资源、75.00国库金额、12条贡献；跨重启报价固定，取消与创始人交接后的旧报价拒绝，新创始人成交保留原操作者。A国库存71／国库1,000,075.00，B国库存129／国库999,925.00，重复请求不增资产或贡献。

以上五个成功目录共14组服务端／客户端均exit0，已停止并释放端口。国家种子、免费建国、战场对象、跳过国战准备计时与三秒副本为显式测试条件。菜单据点胜利不代表PvP、完整攻城、军队移动或世界领地转移；FINAL_BATTLE仍接受通用结束战争回执。

## 构建与证据检查

Chronicle `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw -o -B -ntp verify`：101套件／979项，零失败／错误／跳过，JaCoCo通过。原生同一Wrapper加`-Dcyclonedx.skip=true verify`：1,448套件／7,354项，零失败／错误、21项既有跳过，Spotless及JaCoCo通过；离线SBOM未运行。源码与构件此后冻结。

产品测试覆盖输入不足、三种生产转换的SQL故障回滚、确认丢失、完整重载失败后阻止普通保存、并发、原操作者与当前创始人、到期闸门、重启、取消不带国策加成、旧资源revision、独立head／删尾／条款篡改。Chronicle覆盖专用持久ingress、规范事件、无在线玩家、来源切换、贡献事务回滚、同run变序列或输出集合拒绝及普通事件不签发贡献。

生产检查器从停服SQLite独立计算每轮输入输出、连续生产事件／完成序列和摘要链，核对独立head、固定时间、原人、规范事件hash、来源游标／ACKED收件和逐字段贡献。它同时检查真实命令响应与协议消息、服务端／夹具流一致性、双插件／第三方／Paper／捕获脚本指纹、进程时间与退出，以及无人补录早于客户端进程。仅有夹具PASS文本不能构成验收。

| 真实现场证据检查 | 执行 | 通过 | 跳过 |
| --- | --- | --- | --- |
| 主生产 | 37 | 33 | 4 |
| 空服生产 | 37 | 37 | 0 |
| 贸易 | 38 | 38 | 0 |
| DUNGEON | 29 | 27 | 2 |
| WAR | 41 | 39 | 2 |
| 合计 | 182 | 174 | 8 |

主生产4项跳过仅为空服专用负例，已在空服现场全部运行通过；两条史诗路线各2项跳过依赖旧policy／外交失败现场。本轮通过数字不包含这些跳过。证据副本破坏检查覆盖删尾／丢head、库存／操作者／贡献篡改、提前领取、丢真实命令／协议消息、改进程／脚本／构件、重哈希后的检查点资产篡改及伪造空服消费时机。

## 保留失败与兼容性

原生首轮全量两项旧命令数量断言163失败，随新增命令更新为164后完整构建通过。首轮及聚焦XML、所有构建日志保留；没有删除失败用例以取得通过。

首个生产目录`run/paper-epic-549f99d9-dungeon-production-candidate-1`在发出第一条生产命令前被旧客户端白名单拦截，服务端／客户端均正常退出。白名单仅在`nation_production_acceptance`准备标记存在时允许`/nationproduction`；产品未重建，新candidate-2完成全部三轮。旧目录及失败控制器日志保留。此前过早尝试旧目录第二轮被端口预检拒绝，没有启动第二个服务端，该日志也保留。

各成功目录首次启动的可选FancyAnalytics status200空响应按精确遥测类型记录；原生持久化、生命周期或SQLite错误不会获此例外。

**旧加工调用存在兼容性变化。** `ProcessingService.startProcessing/completeProcessing/autoProcess`缺少原操作者、稳定批次和共同提交，现拒绝资产变更，定时refresh不发放。原工厂与配方目录保留，旧调用方须迁移；旧未完成工厂不会自动退料、发货或转成贡献，需要另行核对。新国家库存生产不包括工厂建筑容量／等级、自动领取、可编辑配方或玩家背包／外部钱包。

## 复验与归档

```bash
python3 tools/prepare-nation-production-paper.py --attempt NEW --port 29740 \
  --starcore /ABS/PATH/StarCore.jar --runtime /ABS/PATH/paper-runtime \
  --artifacts-dir /ABS/PATH/locked-stack --java-home /ABS/PATH/java21
EPIC_MINEFLAYER_MODULE=/ABS/PATH/node_modules/mineflayer python3 tools/epic-paper-session.py \
  --run run/NEW --java-home /ABS/PATH/java21 --node /ABS/PATH/node --boot 1 --scenario production-first
# boot2 production-second；boot3 production-third。不加--contributions。
python3 tools/inspect-nation-production-paper.py run/NEW
STARCHRONICLE_NATION_PRODUCTION_RUN=run/NEW python3 -m unittest discover -s tools -p test_nation_production_acceptance.py -v
```

空服准备加`--offline`，依次执行`production-offline-first`、正常停服后的`prepare-nation-production-resume.py --run run/NEW`和`production-offline-second`。两种生产现场共用上述检查器、测试及环境变量。副本与国战沿用`prepare-production-paper.py --campaign --route DUNGEON`和`prepare-battlefield-paper.py`准备；真实命令参数应以工具`--help`为准，运行目录必须唯一且保留新构件指纹。

`archive-nation-production-evidence.py`核验五个成功目录、一个失败目录、双JAR、两库产品源码与完整构建／失败XML后生成[证据ZIP](verification/nation-production-paper-20261125-evidence.zip)和[机器清单](verification/nation-production-paper-20261125-summary.json)。本机`target/nation-production-paper-20261125/`另含两库源码tar、双JAR和完整清单；逐成员SHA-256验证、配置测试密钥脱敏。本轮归档：17,686,836字节、3,903个有哈希成员全部核验，ZIP SHA-256 `0b84276aabe9b306184d8ba8c48291a2cd2416763b1c5dab4d46d233c6d00733`；18个配置密钥字段脱敏。工具／报告源码归档提交为`d119851b5e1bffecc0425737710b00b3e179ada0`，原生为`7ff849643b0b1541dacd83f8fe3962b9c2a0fdae`，产品源码与实服指纹见上表。新设备重建产生的新JAR须重新验收。

上一贸易#14最终HEAD `75aae16677cf8adc4c31a9d83fe601cccb1e781c`的push CI45683835034、PR CI45683848621均成功：962项及1,423项交付校验。CI JAR分别为`ae108bf68e40ac851713af606542802d9fd47ccf4efc3a6217ef58e5312ba3e2`与`be333c6f715283e6abad87bb71576f789c2a8d54450bb9c3857e2824ac25daef`；交付ZIP分别为`bfcbc659f39f634cc55301fec3d0e535e9144abd31c2d417ba7881dbced0c7f2`与`13d0e141dc0fdea5b6eb4bf584b9e8e8ec7a0691854b759d68f10b68ea4bb3b1`。原日志与机器清单纳入本轮归档，旧分支没有再提交。

正常停服重启不能替代真实断电验收。MySQL、全栈Folia、长期容量、旧工厂恢复与生产部署仍未覆盖；当前原生完整性检查线性扫描全部生产日志。新来源只证明新国家库存生产的真实交付，不能把旧市场或旧工厂历史补造成资产及个人贡献。十阶段保持active。
