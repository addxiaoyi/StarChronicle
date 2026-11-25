# Paper 生产插件候选组合验收

后续进展（2026-11-25）：所选组合已使用首都阶段冻结 `daa12354…0bf7`／`3199771b…c135` 完成完整 DUNGEON／WAR 史诗链各三次启动、唯一结算和贡献恢复，58 项证据回归通过。详见[完整史诗组合报告](EPIC-PRODUCTION-TEST-REPORT.md)。以下保留初次钱包／圈地／NPC验收的独立构件与范围。

2026-11-25：所选组合已通过隔离 Paper 三次正常启动、两次重启及独立证据检查，27 项证据回归全部通过。原生完整构建 7,261 项／1,443 套件，零失败／错误、21 跳过；34 项经济／保护专项、Spotless、JaCoCo 门槛及在线 SBOM 生成通过。Chronicle 产品源码没有在本增量改动，沿用资源历史阶段已通过 919 项构建的冻结 JAR，没有把该数字作为本轮重新构建结果。

本轮验证实际转账、圈地、FancyNpcs 交互与所列故障。没有执行完整史诗战役、收费建国、MySQL、崩溃中的跨插件共同提交或全栈 Folia；十阶段仍在推进。

配套草稿为 [Chronicle PR #8](https://github.com/addxiaoyi/StarChronicle/pull/8) 与 [StarCore PR #7](https://github.com/addxiaoyi/starcore/pull/7)，分别叠加两库资源历史分支，尚未合并。

## 固定组合与源码

用户已授权自行选择。基线为 Java 21、Paper `1.21.11-132-ver/1.21.11@c5eb079`，Paper JAR SHA-256 为 `5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba`。

| 插件 | 实际加载版本 | SHA-256 |
| --- | --- | --- |
| StarChronicle | 0.1.0-SNAPSHOT | `222fbb4064a84692ee3783a525737495566f1a529c3753df2392695797a4e345` |
| StarCore | 1.0.0 | `d9f296190482ef644c5b5d52c3a16b7c0d7a304bbbc5a788d5e401627c2f84da` |
| EssentialsX | 2.22.0 | `bda4685105977fca2e209820a9f0ad24275bd103390a03236f38e59bfdac58e6` |
| Vault | 1.7.3-b131 | `a6b5ed97f43a5cf5bbaf00a7c8cd23c5afc9bd003f849875af8b36e6cf77d01d` |
| FancyNpcs | 2.10.1-java21+2 | `c95eabf27f721d076ff114aaea757d4d7b062949dc1d8cb21c8d61fc92c4a72a` |
| WorldEdit | 7.4.2+7450-eb8e82c | `0ee152b1be5dfb51500505e2bf5a8c9d66f09c7fa484bf9aea384d7e7b459b06` |
| WorldGuard | 7.0.17+2370-e42d8bc | `3f14562509bf01e7680571b6f56932239157ff938f257c3226df3b4088ae54f2` |

原生源码检查点 `9990704e`，基于 `3604adbb`；WorldGuard／所选圈地经济提交 `0b4448a0`，精确余额修复提交 `9990704e`。本库验收工具检查点 `8c6f60a`，基于资源分支 `6ef4caa`。下载 URL、插件描述符、字节码和排除版本见[候选锁文件](verification/production-stack-candidate-20261125.json)；该文件保留下载时的候选状态，实际运行结论以本报告及[验收汇总](verification/production-stack-20261125-summary.json)为准。

FancyNpcs 2.12.1、WorldEdit 7.4.6 要求 Java 25，未用于本次 Java 21 组合。EssentialsX／Vault 没有声明 Folia 支持，WorldGuard 的 Folia 支持为实验性；本轮 Paper 结果不能替代全栈 Folia 验收。

## 实际修复与配置

原生增加默认关闭的 WorldGuard 7／WorldEdit 7 桥。显式开启后，缺插件、缺世界／RegionManager、插件切换及 API 故障均拒绝圈地；实际区域体积相交覆盖整个区块高度，点位检查保留真实高度。所有已定义区域均作为保留区，包括 BUILD=ALLOW、成员可建造区域及显式 `__global__`，不把玩家 bypass 解释为国家圈地许可。

国家圈地预览、收费和原有失败补偿改用注册的玩家经济服务。此前即使配置 Vault，圈地仍读取内部零余额。EssentialsX 的 Vault 余额会转换为可能略低的 `double`，且 `fractionalDigits()` 返回 -1；现在对已识别的 Essentials 提供者读取公开 `Economy.getMoneyExact(UUID)`，不通过通用两位舍入修饰数据。精确 API 失败走拒绝路径，未知提供者保留其实际报告值。支付仍经 Vault 的 `double` API，不承诺任意小数精度支付。

```yaml
# StarCore/config.yml
economy:
  provider: vault
integrations:
  worldguard:
    enabled: true
```

Chronicle 的 `npc-hooks: true` 配合 `npcs.yml` 中已有逻辑角色的显式 `fancynpcs`／外部 ID 绑定使用。两只测试村民由隔离夹具通过 FancyNpcs API 创建；Chronicle 只处理观察到的交互。生产配置应绑定实际管理的 NPC，测试 ID 和世界不能当作生产内容部署。

## 三次启动的证据

最终目录为 `run/paper-epic-222fbb40-dungeon-production-candidate-4`，端口 29715。目录的 `dungeon` 来自复用的准备器；实际场景为 `stack-first|stack-second|stack-third`，本轮没有运行副本。

| 路径 | 实际结果 |
| --- | --- |
| 所选钱包 | 显式测试资金 A=100、B=20；原生转账 12.50 后，原生及 EssentialsX 均为 A=87.50、B=32.50；余额不足转账拒绝；两次重启保持余额 |
| 圈地收费 | C=10000；WorldGuard 保护区块 `(16,16)` 拒绝且不扣款、不产生所有权；相邻 `(17,16)` 成功收费 96.82，原生和 EssentialsX 精确余额均为 9903.18，SQL 所有权归 C 国 |
| 保护几何 | 高空 `(257,180,257)` 至 `(258,181,258)` 小区域、真实三角形与其包围盒外部、负坐标单方块、BUILD ALLOW 保留区及显式 global 均按预期处理；临时区域删除并保存 |
| 真实 NPC 交互 | 每次启动由非 OP 协议客户端点击绑定、未绑定、被取消的绑定 NPC；总计 9 次点击、9 个真实 FancyNpcs 事件、12 个历史检查点，只新增 3 条 NPC 历史，无个人贡献 |
| NPC 恢复 | 两个固定外部 ID、类型、创建者及位置从 FancyNpcs `npcs.yml` 恢复，数量始终为 2；首次启动等待提供者真实加载完成，没有手动修改 loaded 标记 |
| 真实提供者故障 | 第三轮卸载 WorldGuard RegionManager，保护查询与国家预览拒绝且不扣款，重新加载后恢复；注销两个外部 Vault 提供者而保留 StarCore 自身提供者后，原生转账拒绝且 EssentialsX 余额不变 |

三个服务端、三个客户端进程均正常退出。独立检查器把现场日志、客户端身份和点击、真实事件、API 历史、Chronicle 事件哈希及个人／国家索引、EssentialsX YAML、WorldGuard YAML、FancyNpcs YAML、原生 SQL 归属相互核对，并验证全部冻结 JAR、夹具及各次控制器／客户端源码哈希。

最终 boot1 有一次 FancyAnalytics 遥测接口 HTTP 502，保留并单独列在汇总中。检查器仅允许这一精确类型的可选遥测失败；其他插件加载、事件或持久化错误均会导致失败。ProtocolLib 未安装时原生采用已有菜单回退，本轮不测试 ProtocolLib 菜单。

## 保留的失败

| 目录后缀 | 原生构件前缀 | 真实失败及处理 |
| --- | --- | --- |
| `production-before-1` | `a2bb035c` | 七插件加载，但原生未识别真实高空 WorldGuard 区域；增加保护桥 |
| `production-candidate-1` | `008c6901` | 保护桥正确拒绝，但圈地仍读取内部 0 余额；改用所选玩家经济 |
| `production-candidate-2` | `914e0586` | 实际收费和持久余额正确，Vault 浮点尾差导致严格金额比较失败；改用 Essentials 精确 API |
| `production-candidate-3` | `d9f29619` | 金额与几何均通过，夹具在 FancyNpcs 延迟 100 tick 加载前调用；修复就绪等待，产品 JAR 未改动 |

四次失败均保留原 boot、日志和数据库，服务端／客户端正常退出。未覆盖、改写或把失败目录拼接为最终通过。原生两次测试夹具设置失败的日志及失败 XML 也在证据包中；最终完整报告来自清空旧报告输出后的单次 verify，避免旧排除测试残留混入总数。

## 归档与复验

[证据 ZIP](verification/production-stack-20261125-evidence.zip)与[汇总 JSON](verification/production-stack-20261125-summary.json)包含最终三次启动、四次失败、实际数据库、配置、夹具、运行脚本、完整测试 XML、构建／专项／证据回归日志、SBOM 与提供者字节码检查记录。ZIP 为 8,369,886 字节、3,079 个有哈希的成员；SHA-256 为 `318f25f3cec572a553156acad2adebf5d682689dbb48eb7f49eee92d4c96ed6a`。全部成员哈希和 20 处配置密钥脱敏已核对。本机交付目录 `target/production-stack-20261125/` 另含两库源码 tar、双 JAR 与 manifest。

新设备依据锁文件下载第三方 JAR，构建两库后使用新指纹、新目录验收；以下路径需替换为本机实际位置。不要覆盖已有 boot。

```bash
python3 tools/prepare-production-paper.py \
  --attempt production-new-device-1 --port 29715 \
  --starcore /path/to/StarCore.jar \
  --runtime /path/to/paper-runtime \
  --artifacts-dir /path/to/locked-third-party-jars \
  --java-home /path/to/java21

# 在同一新目录中顺序执行 boot 1/2/3，scenario 分别使用 stack-first/second/third
EPIC_MINEFLAYER_MODULE=/path/to/node_modules/mineflayer \
python3 tools/epic-paper-session.py --run run/NEW_RUN \
  --java-home /path/to/java21 --node /path/to/node --boot 1 --scenario stack-first
python3 tools/inspect-production-paper.py run/NEW_RUN
STARCHRONICLE_PRODUCTION_RUN=run/NEW_RUN \
STARCHRONICLE_PRODUCTION_BASELINE=run/RETAINED_ACTUAL_FAILURE \
python3 -m unittest tools/test_production_acceptance.py -v
```

普通双插件夹具仍可独立编译，不需要第三方 JAR；本轮保留了其编译回归。27 项证据回归覆盖删 boot／真实点击／历史、篡改余额／付款／保护区／归属／NPC／事件哈希及索引、脚本指纹漂移、异常退出和真实旧失败。

## 后续边界

建国恢复日志、部分国家菜单／资源区与聚合查询仍显式使用内部经济，需要逐项迁移，不能将测试中免费建国当作外部钱包收费建国验收。Vault 支付与领地异步保存也不共享事务；本轮没有证明异常中断后的跨插件严格原子性。

完整史诗链和贸易／科技／资源来源的旧双插件实服报告按原构件保留，不能移作新组合的全部业务结论。首都持久权威与自动世界记忆后续已完成所选组合验收；全栈 Folia、贸易资产共同提交、文明生产、MySQL 和长期容量继续见[剩余工作](REMAINING-WORK.md)。
