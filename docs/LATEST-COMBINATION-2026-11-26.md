# schema10候选的五插件双路线重验

后续新原生`c8e6c79a`已完成SQLite写锁修复、真实菜单据点积分胜利和五插件双路线76项证据回归，见[当前战场／组合报告](BATTLEFIELD-2026-11-26.md)。下文保留旧原生`a8a6a8a2`的60项和谈／副本组合，不替换其指纹。

2026-11-26：最新工厂加工／历史候选 Chronicle `aed1424` 与 StarCore `c09545ba` 已用所选五插件完成DUNGEON、WAR各三次正常启停，验证真实制作、任务回执、国库／资源援助、成员复核、国家副本／战争结束、唯一结算、个人后续和重放；NPC／领地保护及新增历史来源同时通过。60项证据回归零失败／跳过。本轮补齐了最新构件的组合重验，未部署生产，十阶段仍在推进。

## 构件、环境与范围

| 构件 | SHA-256 |
| --- | --- |
| StarChronicle，schema10 | `cb9fe0a565d29f7492563bba9d9bc1389862edb70ba75a2ae1dceabd87620f6e` |
| StarCore | `a8a6a8a23c578f0aed79578f11504915acd48b36ad6d449c61e57e46ef2f894f` |
| Paper1.21.11-132-c5eb079 | `5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba` |
| FancyNpcs2.10.1-java21 | `f5b52209cfa28b7b453d75340c8568dbb3eaaabfcd10e3ff91e74a1435aa4c61` |
| WorldGuard7.0.17 | `3f14562509bf01e7680571b6f56932239157ff938f257c3226df3b4088ae54f2` |
| WorldEdit7.4.2 | `0ee152b1be5dfb51500505e2bf5a8c9d66f09c7fa484bf9aea384d7e7b459b06` |

组合由用户授权“你自己决定”，延续既有选择及第三方哈希。Linux、Java21、Node23.5.0、Mineflayer4.39.0；原生经济是StarCore国库／资源服务。两个新目录、独立世界／端口、4名真实连接的非OP离线身份；未修改原服。FancyNpcs已有构件存在重叠ZIP项，仅认证该实际哈希。关闭隔离服bStats／FancyAnalytics上传。

本组产品完整Maven结果沿用此次同指纹验证：Chronicle102套件／956项，零失败／错误／跳过；StarCore1,440套件／7,281项，零失败／错误、21项既有skip，Spotless及API兼容通过。59项原生史诗专项属于该全量结果的子集，不另加为新测试。两仓产品源码未因组合重验修改；检查器工具提交 `70b84c9`。工厂原子提交／个人生产的30项原生测试、137条加工三启和28项证据回归见[加工报告](COMMITTED-PROCESSING-HISTORY.md)。本轮新增的是完整组合运行与60项证据回归。

## 真实双路线

| 路线 | 目录与端口 | 最终结果 |
| --- | --- | --- |
| DUNGEON | `run/paper-epic-cb9fe0a5-dungeon-factory-combo-20261126-1`／29651 | 12项原生操作、2笔国库与2笔资源援助、8行贡献、2个国家副本完成凭证、1次结算、A/B/D各1个后续 |
| WAR | `run/paper-epic-cb9fe0a5-war-factory-combo-20261126-1`／29652 | 13项原生操作、相同援助／贡献、真实建国／宣战／战争结束回执、1次结算、A/B/D各1个后续 |

第一启由A/B实际制作纸张、完成已领取的原生任务；冻结报名后执行真实准备、外交及路线活动，停服时尚未结算。普通成员实际换国被拒绝，复核／退出后保持历史任务凭证。第二启重入、核对原生最终回执、完成唯一结算，再重放确认没有第二次转账或后续。第三启再次检查和重放；六个服务器及六个客户端进程均exit0，独立检查核对6个服务器PID、正常保存日志与未占用端口。

每条路线的NPC／保护检查与此前协议相同：无管理权限拒绝，授予管理权限后WorldGuard无所有权区域和展示越界仍拒绝，owner区域真实创建；四次协议NPC点击中一次取消，三次正常入史并打开菜单。三启保持NPC稳定ID与两个纪念实体UUID，外部哨兵实体未改动。最后移除纪念点，仅清除所属实体；4名玩家全部离线后停用提供者并清理监听器，再真实重连确认保护服务停用时创建仍被拒绝。每路线只有1个持久纪念点墓碑、3条NPC历史。

WAR仍用原生主体和谈结束，不是战场胜利；DUNGEON仍为3秒原生生存房间，不代表复杂Boss战。在线热卸载有此前真实Paper命令树竞争，继续不认证；本轮停用探针都在玩家离线后执行。

## 新历史来源与贡献交叉核对

独立 `inspect-current-combination.py` 先执行完整史诗与组合检查，再验证当前schema10和5个已注册历史域。每路线资源来源有5条完整记录：3条初始种子仅归档、2条实时原生资源转账均ACKED。逐条重放3国agricultural库存为90／90／120，与原生SQL库存、转账回执、不可变文件／pending、Chronicle完整回执／ingress／事件一致，保留源Instant和空actor。国家援助的个人归属由既有实际援助回执提供，不以普通余额历史推造。

加工、贸易、科技和首都来源在该组合场景没有业务操作，游标0且live；加工run索引为空、无伪造生产回执。8行原玩家／国家援助贡献维持不变，没有重复TRADE或CIVILIZATION条目。该组合证明新来源与史诗并存；工厂实际生产的完整覆盖由同构件加工专项提供，不能把组合中的0批加工当作工厂负载验收。

每条路线14项史诗、10项第三方组合、6项当前历史证据回归，共60项通过。新增6项含真实正例、丢原生资源记录、改完整转账payload、游标超前、援助伪造加工和来源失败警告。检查器不修改原证据；破坏只发生在临时副本。

## 保留的真实失败

首次准备使用的attempt名称超过工具32字符上限，被预检拒绝，未创建或启动服务器；随后compile／optional准备因该manifest不存在失败。3份日志保留，缩短新attempt名称后正常准备，未覆盖旧日志。

当前历史检查器初版错误地假设5条资源记录全部实时。实际源码协议首次捕获的3条种子是归档，2条援助是实时；首次独立检查因此失败。修正为严格核对实际3归档／2实时，保留红例日志，最终两路线完整来源检查和6项回归均通过。没有更改数据库或放宽遗漏回执／ACK检查。六次正式Paper场景没有失败重写。

## 交付与复验

[跨设备清单](verification/national-epic-processing-combination-linux-20261126-summary.json)与[证据包](verification/national-epic-processing-combination-linux-20261126-evidence.zip)含1,789成员／7,621,210字节，SHA-256 `a18f4bc5063c44971fb39b32c7236fed752685720c2de97b5d34520ac476aa5e`。两个隔离服生成密钥已脱敏；ZIP、全部成员SHA、最终源码指纹和端口释放均核对。包含全部最终XML、28份组合运行／检查／失败日志、原生和Chronicle数据库、实际NPC／保护持久配置、独立结果和工具。原生resource、processing-history、technology、nation权威目录也已归档，避免只携带主数据库而遗失恢复凭证。

冻结产品与原生源码包保存在 `target/national-epic-processing-combination-linux-20261126/`；两仓完整源码另随Git及[工厂证据包](verification/processing-history-linux-20261126-evidence.zip)保存。第三方JAR不随Git分发。旧组合证据保留在[旧组合报告](PRODUCTION-COMBINATION-TEST-REPORT.md)，不能混用哈希。

复验沿用[交接](HANDOFF-2026-11-26.md)的prepare／compile／prepare-production-combination与三个scenario命令，attempt限1..32个小写字母、数字、连字符，新构件必须新目录。两路线分别执行：

```bash
python3 tools/inspect-epic-paper.py run/PREPARED-DIRECTORY
python3 tools/inspect-production-combination.py run/PREPARED-DIRECTORY
python3 tools/inspect-current-combination.py run/PREPARED-DIRECTORY
STARCHRONICLE_EPIC_RUN=run/PREPARED-DIRECTORY python3 -m unittest discover -s tools -p test_epic_acceptance.py -v
STARCHRONICLE_COMBINATION_RUN=run/PREPARED-DIRECTORY python3 -m unittest discover -s tools -p test_production_combination.py -v
STARCHRONICLE_COMBINATION_RUN=run/PREPARED-DIRECTORY python3 -m unittest discover -s tools -p test_current_combination.py -v
```

最新双插件重启、唯一结算及所选Paper国战流程已有本组构件证据。仍需真实战场胜利、研究者来源、UNKNOWN对账／安全新轮次、市场托管／实际退款与人工核对、旧工厂在制任务迁移、建设经济策略、全栈Folia、长期容量和生产部署；完整目标保持进行中。
