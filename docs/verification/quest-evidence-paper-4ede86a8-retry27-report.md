# 原生任务证据 Paper 三轮重启验收（retry27）

**PASS。** 独立严格检查与第二次独立审查均通过。三轮服务器、协议客户端和会话控制进程退出码全部为 0，拥有的子进程均已退出。

- StarChronicle：`4ede86a8bb6315776184c612a488f0c9ebc0321ccef73ce84ddfb097c1f374ca`。
- 候选 StarCore：`c6fb0e5e00d05b5545e515bd67596fed1af05ea644f20c996aeae98c809f197a`。
- 测试 fixture：`0720a2194de2ad00b3d3145cf5cb8c675ab1df1fcf767309df2526c7b53c295e`。
- 平台：Paper 1.21.11；仅 `127.0.0.1:25635`，固定非 OP 原始玩家 A/B/C；第三轮仅 A/C 上线。

## 实际观察

| 阶段 | 真实行为与结果 |
| --- | --- |
| 第一次启动 | A 制作 3 张纸、B 制作 1 本书、C 制作 1 张地图；库存材料变化和原生 CRAFT_ITEM 进度精确匹配 3/1/1。未完成任务时剧情选项保持锁定。 |
| 地图协议 | 13 次原始库存动作、13 次独立库存同步、13 次原始服务器完整库存确认；结果槽只点击 1 次。旧回包只记 PENDING 并被动等待，不重发动作或同步。 |
| 第二次启动 | 原生 run ID、开始时间、定义指纹和接受证据保持一致；通过真实任务 GUI 槽位 32 完成三项任务，持久确认后查询 canonical receipt，并与原生独立 RunCompletion 匹配。 |
| 暂停与离线 | 合法注销 Chronicle 的原生 provider 后仍可完成原生任务，Chronicle 不提前消费收据；B 实际离线后收据仍可查。 |
| 第三次启动 | B 始终离线；恢复原国家的任务准备状态，另一国家保持 blocked；三个有效剧情选项均由真实 GUI 提交。 |
| 持久结果与重放 | 2 份个人选票、1 份真实国家授权投票、3 份决议、8 份 DONE 效果；荣誉 A=10/B=7/C=12。原始三事件重放去重后仍仅 3 条 quest.completed、3 条结局和 3 个已消费键。 |

## 检查点与独立复核

| 启动 | 正常停服检查点 SHA-256 | 文件数（含本地 DB） |
| --- | --- | --- |
| 1 | `0d67fe88ea1d0b4467adc9f2564561a07c7e80cbed35425cc779ad088a591684` | 10 |
| 2 | `5336104dd78989dd298b6af82a1028f51e3ae840c2ff1b47581c3fb007ff4255` | 13 |
| 3 | `d3e5c773001ce21387fe499f9080a9a71c4774d6fb20b9bd98fc60501b8cc454` | 13 |

三份检查点均为 schema 6，原生存储 authority 一致；完成日志数量为 0 → 3 → 3。第二、三轮的不可变原生收据文件保持逐字节一致。

独立审查对 136 份原始证据／数据库／原生文件计算前后 SHA，全部不变，并拒绝 19 项原生证据、实际 SQLite 副本、原始协议、重复点击、进程时间和来源指纹篡改。直接检查结果经 JSON 规范化后与保留结果完全一致。见 [quest-evidence-paper-4ede86a8-retry27-independent-review.json](quest-evidence-paper-4ede86a8-retry27-independent-review.json)。

## 可审阅交付与边界

[quest-evidence-paper-4ede86a8-retry27/manifest.json](quest-evidence-paper-4ede86a8-retry27/manifest.json) 列出 105 份与原运行逐字节一致的文本证据、大小和 SHA-256。包括完整本轮日志、实际配置、fixture、剧情、原生停服存储文本及检查点描述。源运行未改写。

机器可读结果：[quest-evidence-paper-4ede86a8-retry27-summary.json](quest-evidence-paper-4ede86a8-retry27-summary.json)。详细严格输出：[quest-evidence-paper-4ede86a8-retry27/evidence/quest-evidence-inspection.json](quest-evidence-paper-4ede86a8-retry27/evidence/quest-evidence-inspection.json)。

- This run covers isolated Paper 1.21.11, the exact Chronicle JAR and the patched candidate StarCore JAR. It does not cover the native quest path on Folia.
- Fixture-only source signals and ingredients were supplied explicitly. Actual crafting events, native quest GUI completion, canonical evidence lookup and Chronicle GUI choices were used; no synthetic completion DTO or objective setter was used.
- Core native templates were configured with no external reward payout. Honor is the Chronicle durable reward projection; external economy/items were not asserted.
- The patched StarCore candidate remains a separate upstream handoff. This focused acceptance does not override the known full upstream regression failures or establish production readiness.
- Previous failed/incomplete attempts remain failed/incomplete and were not resumed or combined. Retry27 uses a new world, native state and Chronicle database.
- 14 offline Node wire contracts and 19 Python control tests passed before this run. The independent final review used 19 tamper cases; the retry21-specific offline contract suite is not claimed to have run wholly against retry27.
- The three stopped Chronicle databases are retained locally and identified by original checkpoint manifests; this text archive alone cannot rerun DB acceptance.
- Third-party JARs, world/player data and complete StarCore runtime configuration are excluded.
- Installed vendor source copies stay local. Runtime manifests preserve exact source hashes and package metadata; the available mineflayer MIT LICENSE is copied. Windows/item package LICENSE files were absent and are recorded as such.
