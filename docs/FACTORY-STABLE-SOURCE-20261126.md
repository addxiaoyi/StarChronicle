# 工厂批次稳定键：源码与写入量验证

2026-11-26，继续两库 `codex/plugin-all-stages-20261125`。按用户选择只做源码、单元／数据库与打包服务契约，未启动 Minecraft。原生资源模块 **304 项 verify**、80 项专项、冻结 V1 兼容夹具及新旧构件容量对照通过；Chronicle 主源码和此前 957 项通过的构件未变。

## 行为与升级

旧批次快照按编号排序后写入 `row.0` 等位置；新增一个排在前面的编号，许多历史字段会更换位置，即使数据库已经支持差量保存也仍会重写。新保存使用 `resource.factory-runs.version=2`，每个批次采用原编号的 SHA-256 作为固定行键，原始编号、全部字段和重试凭据仍完整保存。小写摘要不依赖 SQL 大小写排序规则，最长属性键在现有 `VARCHAR(191)` 内。

恢复同时支持 V1 与 V2，继续验证完整性指纹、规范键、唯一编号、工厂占用、存储域和持久完成回执。有效指纹不能掩盖别名键、缺失／多余字段、错误计数或重复编号。V1 仍严格验证原数字行顺序；单纯读取不改数据。

**第一次成功业务保存会在同一事务中把全部批次键升级为 V2。** 保存失败回滚库存、格式和新回执；提交后回包丢失仍通过完整状态对账恢复。旧 V1 写入器无法读取 V2，升级前须保留完整备份，不能只换回旧 JAR 降级。稳定键不删除历史，不缩短奖励或去重凭据的保留期限。

## 实际回归与构件

Java 21，串行 Maven，256 MiB 堆、`forkCount=0`：

- 原生专项 80 项、资源域 `verify` 304 项，失败／错误／跳过均为 0；格式、打包和独立在线生成的 36 组件 SBOM 通过。
- 新增 16 项覆盖冻结 V1、513 个历史批次前插、最长／大小写不同编号、有效指纹下的坏键、文件协议异常回包及真实 SQLite/H2 升级回滚与重启。
- V1 夹具在主源码修改前由真实旧 JAR 生成，原始 SHA-256 为 `0bd7cd1f1810cbf242b43d05c46b6656d05ca8f4fddfb216a685d859f3238483`，测试先核对字节哈希；Git 禁用该夹具的换行转换。
- 当前两库三个串行 JVM 的工厂契约通过：原子扣料、无配方重启、固定期限完成、旧编号重放、无玩家系统批次、历史与贡献均独立核对。
- 本轮不是原生全库回归，没有当前原生 JaCoCo；Chronicle 沿用未变主源码此前 957 项的构件，不把工具修改另报为新一轮 957 项本地执行。

| 构件 | SHA-256 |
| --- | --- |
| 旧 StarCore，SQL 差量保存检查点 | `97db36ee40dbc6df8c765c0bbbb92dadcddf8989f5b31db77505d814e31470f1` |
| 当前 StarCore | `c5f459b181ff92e828eed9d4c58c3b699593dc261d763b28aa61c69e458bbf02` |
| 配套 Chronicle | `76606f33bcc50ee2faf753cb144acf90cf7513a49e36ecf99d823be6e1408886` |
| Chronicle 源码 JAR | `a275028caddcd19388788c9a93bab26d72b03a9ae83742cf70fb18ddd327f780` |

## 真实服务写入对照

`tools/run-factory-stable-capacity.py` 使用旧 JAR 创建 1,000 个完成批次、1 个活动批次的 V1 SQLite 库，分别复制给新旧构件。每个 JVM 使用 64 MiB 堆。先完成原活动批次并单列升级成本，再通过真实 `ResourceModule`／`ProcessingService` 启动并完成 20 个新批次，编号从 `000-00020` 倒序到 `000-00001`，每次都排在既有编号前面。

| 测量 | V1 旧构件 | V2 新构件 |
| --- | ---: | ---: |
| 首次完成旧活动批次：插入／删除状态行 | 6 / 10 | 13,018 / 13,022 |
| 首次完成旧活动批次：耗时 | 153.673 ms | 323.825 ms |
| 后续 20 批开始＋完成：插入状态行 | 101,631 | 541 |
| 后续 20 批开始＋完成：删除状态行 | 101,377 | 287 |
| 开始操作 p50 | 243.713 ms | 139.724 ms |
| 完成操作 p50 | 126.292 ms | 145.527 ms |

后续合计物理写入从 **203,008 行降至 828 行**，不含首次升级和补足投入的两次库存操作。每步新格式状态写入不超过 50 行，独立 JDBC 计数与 SQL 状态核验一致。该变化降低逆序编号导致的数据库写入；本样本完成操作没有变快，不能据此承诺吞吐或延迟。

Python 独立复算快照指纹、验证 V1 数字顺序／V2 摘要键、检查每行全部 13 个字段，比较两个最终完整逻辑状态和生产回执。原 1,000 条回执与全部历史批次保持原值，仅原活动批次与新增 20 批产生预期完成。两个新进程再次恢复和重放，四张表的行数／逐行摘要保持不变，库存没有再次发放。

`tools/run-resource-snapshot-capacity.py` 另做 50 次真实库存 grant 回归。新构件第一次 grant 因升级插入／删除各 13,016 行，后续 49 次各仅插入／删除一行；50 次总计各 13,065 行。旧构件一直使用 V1，各 50 行。两者仅预期库存改变，所有生产日志与批次逻辑证据不变。p50 为旧 103.814 ms、新 134.619 ms，完整构建／指纹成本仍存在。

这些是共享机器上的串行隔离服务测量，使用可控时钟与固定身份夹具；64 MiB 是 Java 堆而非进程全部内存，不包含 Minecraft、网络、并发或真实玩家生命周期。SQLite/H2 回归不认证 MySQL。

## 证据与复现

[证据 ZIP](verification/factory-stable-source-20261126-evidence.zip)包含 **121 文件**，内部哈希已逐项核验，ZIP SHA-256 为 `689ac2f6c5dae76db4fff0710833785cfa1281c91a109803b8c66da863c17837`。[机器摘要](verification/factory-stable-source-20261126-summary.json)包含测试计数、构件、原始测量、源码哈希和合同清单。

本机冻结目录为 `run/factory-stable-capacity-20261126-1`、`run/resource-snapshot-v2-20261126-1` 和 `run/factory-contract-v2-20261126-1`。仓库工具可用新的输出目录复现：

```text
python tools/run-factory-stable-capacity.py --previous OLD_STARCORE.jar --starcore NEW_STARCORE.jar --chronicle CHRONICLE.jar --runtime INITIALIZED_PAPER --java-home JAVA21 --output NEW_CAPACITY_DIRECTORY
python tools/run-resource-snapshot-capacity.py --previous OLD_STARCORE.jar --starcore NEW_STARCORE.jar --chronicle CHRONICLE.jar --runtime INITIALIZED_PAPER --java-home JAVA21 --output NEW_GRANT_DIRECTORY
python tools/run-factory-contract.py --starcore NEW_STARCORE.jar --chronicle CHRONICLE.jar --runtime INITIALIZED_PAPER --java-home JAVA21 --output NEW_CONTRACT_DIRECTORY
```

工具只读取 Paper 目录中的运行库，不启动服务器。完整快照、指纹、文件写入与恢复仍随保留历史增长；其他按位置编号的集合也可能移动。下一步继续原生快照常驻内存、其他集合排序、稀疏过滤与长期归档。第三方持久来源、全栈 Folia 和当前构件实服仍未完成，十阶段目标保持进行中。
