# 所选 NPC／保护组合的 Paper 验收

最新schema10组合 `cb9fe0a5`／`c8e6c79a` 已完成真实原生菜单据点积分胜利、双路线三启与76项证据回归，见[当前战场／组合重验](BATTLEFIELD-2026-11-26.md)。前轮`a8a6a8a2`和谈／副本60项证据见[此前组合](LATEST-COMBINATION-2026-11-26.md)。本文件保留以下旧构件的完整记录。

更新：2026-11-26（UTC）。用户已授权由代理决定组合，本轮使用 **Paper 1.21.11、Java 21、StarCore 原生国库／资源经济、FancyNpcs 2.10.1-java21、WorldGuard 7.0.17、WorldEdit 7.4.2**。新增纪念点创建授权检查；同一组构件在 DUNGEON／WAR 各三次启动中通过史诗、贡献／成员、NPC 交互及纪念点保护验收。这里的“生产组合”表示所选插件组合，运行环境是隔离服，未部署生产或认证全栈 Folia。

后续已接入[原生科技提交历史](COMMITTED-TECHNOLOGY-HISTORY.md)，新构件完整 905 项及科技三次启动通过。本报告的双路线组合证据只覆盖下列 `5758e54b` 构件，不能替代新构件的组合重验。

## 精确构件和测试

| 构件 | SHA-256 |
| --- | --- |
| StarChronicle | `5758e54b0f0286ff63695138a08dcd02f56f08f421da17380928eb1967796daa` |
| StarCore | `44f450eccc95590aadc54736109e012d5a52bbae459f4107afa498fd4528518d` |
| FancyNpcs 2.10.1-java21 | `f5b52209cfa28b7b453d75340c8568dbb3eaaabfcd10e3ff91e74a1435aa4c61` |
| WorldGuard 7.0.17+2370-e42d8bc | `3f14562509bf01e7680571b6f56932239157ff938f257c3226df3b4088ae54f2` |
| WorldEdit 7.4.2+7450-eb8e82c | `0ee152b1be5dfb51500505e2bf5a8c9d66f09c7fa484bf9aea384d7e7b459b06` |

Chronicle 源码及验收工具提交 `9a0c10310293bf54f72c72bca6158c55ea9a7e32`。完整 `verify` 为 **94 套件、887 项，0 failures／errors／skipped**；保留正常 fork 与 JaCoCo，日志 `target/protection-verify-20261126.log`。新增 5 项权限边界测试，针对性运行 `MemoryProtectionTest,MemorySitesTest` 共 21 项通过。

原生沿用 `c5593e0d` 对应的冻结 JAR，生产修复为 `324072a5`。90 项专项和 7,202 项常规回归（零失败／错误、21 skipped、保留既有排除配置）来自前次同一构件，本轮没有重跑；本轮重新执行的是六次实际组合启动与业务流程。

环境为 Linux、Paper 1.21.11-132-c5eb079、Java 21、Node 22.22.2、Mineflayer 4.39.0、Python 3.11／PyYAML 6.0.1。第三方 JAR 取自本机已有安装，仅复制到独立目录，原服未改动。FancyNpcs JAR 含重叠 ZIP 项，因此只认证上述实际哈希，不声称它是官方未修改版。测试配置关闭 bStats 和 FancyAnalytics 的 metrics／error 上传，保留日志输出。

## 产品行为

`/chronicle memory create` 在管理员权限、当前位置和高度检查后，使用 WorldGuard 的实际玩家身份与 `RegionQuery.testBuild` 检查展示占用范围。Interaction 的宽度会越过中心方块，故同时检查相邻列和上方，共 36 个位置；查询不加载区块。遵循 WorldGuard 自身 bypass，Chronicle 管理权限不替代领地授权。

未安装 WorldGuard 时沿用管理员显式放置；已安装但停用、接口不兼容或查询失败时拒绝创建，且不写纪念点注册表。WorldGuard 列入可选依赖，保证正常加载次序。插件仍只管理自己标记的展示实体，不放置方块、不接管第三方 NPC。检查针对创建时授权，不会因后续领地规则改变而自动移除既有点位。

NPC 桥接没有新增所有权操作。夹具通过 FancyNpcs 的公开管理 API 创建一个测试 NPC，Chronicle 只按真实稳定 ID 的配置绑定读取实际点击事件。取消的交互不会入史；正常交互使用逻辑 `npc.id=ancient-heir` 和原始 `npc.binding=fancynpcs:chronicle-combination-witness`。

## 两条通过的运行目录

| 路线 | 目录／端口 | 结果 |
| --- | --- | --- |
| DUNGEON | `run/paper-epic-5758e54b-dungeon-combination-20261126-4`／29656 | 12 项原生史诗操作、4 笔援助、8 条贡献、1 次结算、A/B/D 各一个后续；三次正常启停 |
| WAR | `run/paper-epic-5758e54b-war-combination-20261126-3`／29657 | 13 项原生史诗操作，援助／贡献／结算／后续结果相同；三次正常启停 |

两路线继续执行真实制作、原生任务精确回执、普通成员换国拒绝／复核／退出／重启后重入和结算重放流程。每次服务端与客户端进程退出码均为 0；独立检查要求无服务端 ERROR、完整保存日志、端口释放以及原生／Chronicle 数据库交叉一致。

每条路线额外验证：

1. 非 OP 玩家无 Chronicle 管理权限时无法创建纪念点。授予该权限后，WorldGuard 无所有权区域仍拒绝；站在区域外但展示范围跨入保护边界时也拒绝。
2. 在 WorldGuard 将该玩家列为 owner 的区域内创建成功，来源是本轮真实资源援助历史。数据库仅出现这一处点位，三个初始拒绝都无持久记录。
3. 真实协议客户端发送四次 NPC 点击：首次被普通 Bukkit 取消监听器取消，历史仍为 0；随后三个启动各一次有效点击，历史依次为 1／2／3，并打开 NPC 菜单。没有手工构造成功点击或调用 NPC 交互处理函数。
4. 重启等待 FancyNpcs 的 `isLoaded()`，原生 NPC 仍为同一个稳定 ID。纪念点的两个实体 UUID 三次启动不变；外部 ArmorStand 哨兵与第三方 NPC 保持存在。
5. 最后显式移除纪念点，所属实体数从 2 降为 0，外部实体与 NPC 保留。全部四个客户端真实离线，服务端确认在线数为 0，再停用 NPC／保护插件；Chronicle 的 NPC 桥接监听器变为 0。真实客户端重新登录、获得管理权限后，WorldGuard 停用状态仍阻止创建，第四个拒绝也无持久记录。

每条路线通过史诗独立检查和组合独立检查。每条另有 14 项史诗、10 项组合证据破坏回归，合计 **48 项通过，无跳过**。组合回归破坏实际证据副本中的点击、就绪状态、监听器清理、离线维护边界、保护拒绝、点位数据、重启 UUID、原生区域 owner 和插件哈希，检查器均拒绝。

## 保留的失败和准备轮次

以下目录均以 `paper-epic-5758e54b-` 开头，产品 JAR 未因夹具修正而更换：

| 后缀 | 实际状态 |
| --- | --- |
| `dungeon-combination-20261126-1` | 失败：夹具在主线程直接调用 FancyNpcs `spawn`，触发 `NpcSpawnEvent may only be triggered asynchronously`；此外默认遥测请求出现 ERROR。改用提供者的 `spawnForAll` 调度入口，并通过配置关闭测试遥测。 |
| `dungeon-combination-20261126-2` | 前两次业务流程完成；第三次在 FancyNpcs 加载完成前查询而失败。NPC 文件仍在；新夹具仅等待 `isLoaded()`，不重建或手填 NPC。 |
| `dungeon-combination-20261126-3` | 三次场景执行结束，但在线停用 FancyNpcs 时，Paper 异步命令构建线程出现 `ConcurrentModificationException`，独立检查拒绝，整轮失败。 |
| `war-combination-20261126-1` | 仅准备／编译，未启动，不计通过。 |
| `war-combination-20261126-2` | 三次场景结束，同样出现在线停用引发的命令树并发异常，独立检查拒绝。 |
| 最终 `dungeon-…-4`／`war-…-3` | 三次业务流程、独立检查及全部证据回归通过。停用检查前确认所有玩家离线，再真实重连检查拒绝行为。 |

**本组合不认证有玩家在线时的插件热卸载／热重载。** 已观察到两条路线的真实 Paper 命令树竞争；没有修改第三方 JAR 或放宽 ERROR 检查。正常生产维护应采用整服停止／重启，本轮的额外停用探针只在所有客户端离线后执行。

## 证据、复验和边界

[完整清单](verification/national-epic-combination-linux-20261126-summary.json)及[证据包](verification/national-epic-combination-linux-20261126-evidence.zip)包含七个运行目录、36 份额外日志、最终测试 XML、原始进程记录、原生 NPC／区域配置、Chronicle／StarCore 数据库、失败记录和工具源码。603 个成员哈希、ZIP 完整性及六个实际启动过的目录中的生成访问密钥脱敏均已核对；未启动目录中的原生默认占位字符串不属于生成密钥，归档字段也统一脱敏。

证据包 SHA-256：`2c7d1081e8d8ca6b2cabaa61426de260ba1e48cca57cb97f22e0e7d68574fba1`。冻结 Chronicle／StarCore JAR 及原生源码包在 `target/national-epic-combination-linux-20261126/`；第三方二进制不随 Git 分发，须取得与选择清单匹配的构件。

复验先用 `prepare-epic-paper.py prepare --ledgers` 创建独立路线，再编译史诗夹具，启动前运行 `prepare-production-combination.py --run <目录> --java-home <JDK> --fancynpcs <JAR> --worldguard <JAR> --worldedit <JAR>`。此工具拒绝覆盖旧组合或已启动目录，并核对三份第三方哈希。之后按交接文档运行三个 scenario，执行两种 inspect；分别设置 `STARCHRONICLE_EPIC_RUN`／`STARCHRONICLE_COMBINATION_RUN` 执行两组 unittest。Python 组合检查器需要 PyYAML。

WAR 仍采用原生和谈结束，DUNGEON 仍为三秒原生生存房间。测试没有覆盖战场胜利、复杂 Boss、线上经济平衡、Citizens／Adyeshach、其他保护插件、全栈 Folia、长时压力或自动创建第三方 NPC。阶段 8 的所选 Paper 组合有了明确证据；其余十阶段工作继续按[最新交接](HANDOFF-2026-11-26.md)推进。
