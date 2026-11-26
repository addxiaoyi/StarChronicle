# 当前五插件候选的据点积分胜利与重启验收

2026-11-26：当前schema10候选完成真实非OP玩家经原生战争菜单占领两座要塞、100∶0积分胜利，并在两次重启后核对持久胜利回执、唯一史诗结算和重放。所选五插件的WAR／DUNGEON各三启及76项证据回归全部通过。本轮同时移植并验证国家快照SQLite写锁修复，原生完整7,283项测试通过，21项既有跳过。十阶段继续推进，未合并主分支或部署生产。

## 构件及来源

| 项目 | 精确指纹与来源 |
| --- | --- |
| StarChronicle | `cb9fe0a565d29f7492563bba9d9bc1389862edb70ba75a2ae1dceabd87620f6e`，产品仍为`aed1424`／schema10，沿用102套件／956项零失败构建 |
| StarCore | `c8e6c79abc5be1e84ae7062f259d0ccff61e6be99e98d075496cdd91b5a6ce6c`，`4d01f6430e5ee4bce02627602bee0f30bad29aff`，1,441套件／7,283项，零失败／错误、21项既有skip |
| Paper | 1.21.11，`5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba` |
| 工具 | `ef6a4df1c0bfb3ef7cb6208e44b2086273bf8882`，适配当前五插件及完整历史来源检查 |

固定Java21、FancyNpcs2.10.1-java21、WorldGuard7.0.17、WorldEdit7.4.2；经济为StarCore原生国库／资源服务。第三方精确SHA见[本轮选择清单](verification/production-combination-selection-battlefield-20261126.json)，与[前轮组合](LATEST-COMBINATION-2026-11-26.md)相同。FancyNpcs仍只认证该已固定实际哈希，保留其重叠ZIP项事实。四名回环offline-mode客户端均非OP；隔离目录和世界独立，关闭遥测上传。

本轮复用了独立分支`47d1465`的战场工具，并移植原生`902fcb08`的SQLite修复。旧分支的七插件、旧构件和70项结果未转移到本轮；本轮对当前源码和新原生JAR重新构建、运行和归档。Chronicle产品未重建，123个Java源码逐项匹配既有sources JAR。59项原生史诗专项和8项写锁专项属于全量结果子集，不额外计入7,283项。

## 真实流程

| 路线 | 独立目录／端口 | 结果 |
| --- | --- | --- |
| WAR | `run/paper-epic-cb9fe0a5-war-battlefield-20261126-1`／29653 | 13项原生操作，2次有效占领、6次无效点击，100∶0，VICTORY_DETERMINED／AGGRESSOR |
| DUNGEON | `run/paper-epic-cb9fe0a5-dungeon-battlefield-20261126-1`／29654 | 12项原生操作，2个国家副本完成凭证 |

两路线均由A/B实际制作完成精确原生任务，冻结报名、执行2笔国库／2笔资源援助和外交。每路线8行原玩家／原国家贡献、1次唯一结算、A/B/D各1个个人后续；成员换国拒绝、复核、退出与重启后显式重入均通过。

WAR中A复国并宣战后，经`/war gui`、战场详情和要塞管理菜单完成真实协议点击；准备期不允许占领，活动期依次得到50、100积分，重复占领和非参战国点击不增积分。A明确无`starcore.war.admin`，以真实进攻国创始人身份执行`/war end 复兴帝国 EpicTestB`；原生据积分发出胜利事件并保存战争完成回执。夹具只监听实际InventoryClickEvent／WarEndedEvent，不合成点击或事件、不设置积分。

第一启在战争已经结束、史诗仍为ROUTE_APPROVED时正常停服；第二启读取持久回执后唯一结算并重放，第三启再次恢复和重放。独立检查逐条关联8次客户端点击与服务端观察；两次已应用计分标记、两座要塞归属／捕获时间、100∶0积分和战争结束时间在三份停服数据库与最终数据库保持一致，待发计分为空。第二／三启及最终Chronicle库的全部epic表与贡献逐表一致。

NPC和保护场景继续完整执行：每路线4次真实NPC点击（1次取消、3次入史），重启保持稳定NPC ID及2个纪念实体UUID；权限不足、无领地所有权及边界越界被拒绝，实际owner区域允许创建。最后真实命令移除仅清理所属实体，4名玩家全部离线后停用提供者，再真实重连核验保护停用仍拒绝创建。六个服务端和六个客户端进程全部exit0，端口已释放。

每路线schema10五个历史域正常，资源5条完整记录由3条初始归档与2条实时转账组成，库存90／90／120逐项匹配原生库存、不可变来源、完整回执及ACK。加工、贸易、科技、首都在此场景没有业务操作，未产生额外贡献。工厂137条加工专项仍对应旧原生`a8a6a8a2`，见[工厂报告](COMMITTED-PROCESSING-HISTORY.md)，不能用本轮零加工声称已重新执行该专项。

## 真实覆盖边界

战场和两座防守国CITADEL由夹具调用原生服务创建；每座默认战略价值50。准备计时由夹具调用进攻国授权的原生start服务跳过。初始国家、资金、库存、材料是明确种子，隔离配置关闭建国收费和新国保护。

此处验证原生菜单占领所产生的积分胜利，不涵盖PvP击杀、攻城移动、军队模拟、占领时长或世界领地转移；战场controller仍是防守国，变化的是两座要塞owner。结束后的点击针对己方要塞，仅证明该入口不再计分。DUNGEON仍为3秒生存房间。FINAL_BATTLE产品契约继续接受原生已结束战争凭证，本轮没有加入“只有胜利才可结算”的新语义。在线热卸载、全栈Folia和生产部署未认证。

## 写锁修复、失败与验证

国家SQLite原实现先读版本，再尝试写入；另一个模块持有WAL写锁时可能立即返回SQLITE_BUSY。本轮先用真实双连接复现：版本行不存在／已存在两例都失败；修复后在读版本前以不改变值的UPDATE等待写锁，两例及既有冲突／重试边界共8项通过。双写者测试屏障移至事务开始前，仍要求一胜一版本冲突和重载重试。版本CAS、事务回滚及建国回执共同提交保留。完整`./mvnw -B verify`通过格式、API兼容、打包和SBOM。

保留的本轮失败包括系统`mvn`版本不符合项目enforcer（未运行测试），以及旧实现的2项真实SQLITE_BUSY失败和XML。切换项目wrapper后复现和修复验证均完成。六次正式Paper运行及首次独立场景检查全部通过，没有覆盖失败轮次。

证据回归为WAR的14项史诗＋10项组合＋6项来源＋16项战场，以及DUNGEON的14＋10＋6，共76项，零失败／跳过。战场负例覆盖和谈伪装胜利、伪造事件、缺失真实点击／结束命令、替换actor、管理员绕过、重算哈希后的积分／标记／归属／待发状态篡改、夹具漂移、检查点哈希错误及重启凭证被改写。

## 归档与复验

[证据ZIP](verification/national-epic-battlefield-current-linux-20261126-evidence.zip)包含1,818成员／8,092,392字节，SHA-256为`b12062445ec037fb114a8ebdb1a5efea3f5df8f5d88fff3895ab51444a483887`。[汇总清单](verification/national-epic-battlefield-current-linux-20261126-summary.json)保留产品和源码指纹、全部最终XML、完整构建日志、真实失败、六次进程证据、原生及Chronicle数据库、战场三次停服快照、NPC／保护状态和完整来源权威目录。ZIP完整性、全部成员哈希、两仓源码、必需authority文件及端口释放已核对；两个生成密钥脱敏。

冻结JAR及原生源码包在`target/national-epic-battlefield-current-linux-20261126/`；完整两仓源码随候选Git分支交接。新设备必须登记新构件指纹，在新目录重新验收。WAR准备命令增加`--battlefield`：

```bash
python3 tools/prepare-epic-paper.py prepare --route WAR --battlefield --ledgers \
  --attempt battlefield-new-1 --port 29653 --starcore /path/to/StarCore.jar \
  --runtime /path/to/paper-runtime
python3 tools/prepare-epic-paper.py compile --run run/PREPARED-DIRECTORY --java-home /path/to/java21
```

接着按[交接](HANDOFF-2026-11-26.md)执行所选插件准备和first／second／third三次scenario，最后运行：

```bash
python3 tools/inspect-battlefield-paper.py run/PREPARED-DIRECTORY
STARCHRONICLE_COMBINATION_RUN=run/PREPARED-DIRECTORY \
  python3 -m unittest discover -s tools -p test_battlefield_acceptance.py -v
```

两路线还需执行交接中已有的史诗、组合及当前来源检查／回归。本轮完成当前候选的据点积分胜利验收；研究者来源、UNKNOWN对账／安全新轮次、市场托管／实际退款与人工核对、旧工厂迁移与建设经济、全栈Folia、长期容量和生产部署仍在推进。
