# 工厂恢复规范校验：源码与内存分配验证

2026-11-26，继续两库 `codex/plugin-all-stages-20261125`。按用户选择只推进源码、单元／数据库与打包服务契约，未启动 Minecraft。本轮减少工厂恢复时的临时属性表；完整输入快照与全部历史批次仍保留，不把这项优化称为长期内存容量已解决。

## 行为与兼容性

先前恢复读取完整批次后，再调用 writer 构造一份完整规范 `Properties`，生成其指纹，并建立预期／实际键集合逐项比较。现在抽出同一份规范字段生成规则，写入时仍生成完整快照；读取时将每个字段直接与原快照比较，再核对总字段数。每个规范字段必须存在且内容一致，计数还会拒绝任何额外字段。一次获取原属性名称后复用于指纹、行发现与字段计数，避免多次复制。

原输入快照的指纹校验仍执行，覆盖全部原工厂与批次字段；V1 数字行顺序、V2 摘要键、活动绑定、存储域及持久完成回执仍按原规则验证。原始文件和数据库不会因只读恢复而改写；写出的 V2 格式不变。首次 V1→V2 保存的全量改键与旧 V1 JAR 不支持 V2 的边界仍见[稳定键报告](FACTORY-STABLE-SOURCE-20261126.md)。

## 实际源码回归

Java 21、串行 Maven、256 MiB 堆、`forkCount=0`：专项 **89 项**、资源域 `verify` **322 项**均零失败／错误／跳过。新增 18 项在 V1 和 V2 下分别核对额外元数据、额外／缺失／错误绑定、等数量字段替换、非规范计数／数量／序号，以及继承属性的兼容读取；指纹有效时仍须拒绝坏内容，拒绝过程不改原快照。

第一次专项启动因 PowerShell 未引用 `-Djacoco.skip=true`，Maven 将其拆成未知生命周期阶段；尚未开始编译或测试。修正参数引用后通过，原始失败日志保留。本轮没有原生全库回归或当前 JaCoCo；Chronicle 主源码未改，沿用此前 957 项通过的同一构件，上一工具提交 `4979f52` 的 Linux CI #89 也已通过。

格式、打包与独立在线生成的 36 组件 SBOM 通过。当前两库三个串行 JVM 工厂契约也通过，独立核对固定批次开始、无配方重启、按原期限完成、编号重放、系统批次、历史与贡献。

| 构件 | SHA-256 |
| --- | --- |
| V1 夹具生成 JAR | `97db36ee40dbc6df8c765c0bbbb92dadcddf8989f5b31db77505d814e31470f1` |
| 旧恢复／V2 夹具生成 JAR | `c5f459b181ff92e828eed9d4c58c3b699593dc261d763b28aa61c69e458bbf02` |
| 当前 StarCore | `b9c084e21ac5620cb95e5038a9cec289dfebd4294527596844651f693b844d53` |
| 配套 Chronicle | `76606f33bcc50ee2faf753cb144acf90cf7513a49e36ecf99d823be6e1408886` |

## 5,000 批次对照

`tools/run-factory-canonical-memory.py` 分别使用冻结的真实 V1／V2 writer 生成 5,000 个完成批次和 1 个活动批次的文件与 SQLite 库。生成进程使用 256 MiB 堆；16 个恢复进程串行运行，每个上限 96 MiB。两个格式分别用旧／新构件完成文件和 SQL 的首次与独立进程重启。

测量从完整输入读取、普通快照解码及一次 GC 之后开始，覆盖实际 `FactoryRunStore.read` 的输入指纹、批次还原、规范字段及全部生产回执校验。HotSpot 当前线程分配计数在调用前后取差值，原始输入和全部返回批次在测量后仍保持引用。以下是首次／重启两次结果的平均值，单位 MiB：

| 格式与存储 | 旧累计分配 | 新累计分配 | 减少 |
| --- | ---: | ---: | ---: |
| V1 文件 | 213.980 | 163.766 | 23.47% |
| V1 SQLite | 192.633 | 149.222 | 22.54% |
| V2 文件 | 248.481 | 186.534 | 24.93% |
| V2 SQLite | 223.670 | 171.932 | 23.13% |

这是回收前后不断累计的 **Java 分配量，不是峰值或常驻内存**，所以可以大于堆上限。当前恢复后的 GC 存活堆约为 V1 文件 33.19 MiB、V1 SQLite 27.80 MiB、V2 文件 36.35 MiB、V2 SQLite 31.75 MiB；输入快照和全部历史对象仍存在，常驻量继续随历史增长。

本样本 V1 文件两次恢复从 1,224.662／1,222.311 ms 到 780.003／761.937 ms，V2 文件从 1,790.445／2,014.866 ms 到 1,005.930／907.056 ms。V1 SQLite 从 953.534／936.059 ms 到 619.141／619.426 ms，V2 SQLite 从 1,195.442／1,136.674 ms 到 791.088／815.498 ms。这些是共享机器上的冷恢复样本，不是延迟承诺，也不包括输入读盘、模块完整初始化、Minecraft 或并发业务。

每次恢复逐项核对全部 5,001 个批次、原期限、投入／产出、完成序号与库存；Python 独立比较四张 SQL 表的行数和逐行摘要，并核验文件字节哈希。所有进程只读数据不变，V1／V2 的完整逻辑状态和生产回执相同，文件路径各只读盘一次。

## 证据与复现

[136 文件证据 ZIP](verification/factory-canonical-source-20261126-evidence.zip)的内部哈希已逐项核验，ZIP SHA-256 为 `fa49ebe68c44a485a7ac7ba66a98fb8d63c30a3840efa01b06a1d5bd8dced162`。[机器摘要](verification/factory-canonical-source-20261126-summary.json)包含原始测量、构件和源码哈希、文件指纹、回归与合同清单。

冻结材料在 `run/factory-canonical-memory-20261126-1`，当前双库契约在 `run/factory-contract-canonical-20261126-1`。可用新输出目录复现：

```text
python tools/run-factory-canonical-memory.py --legacy V1_WRITER.jar --previous V2_PREVIOUS.jar --starcore CURRENT.jar --chronicle CHRONICLE.jar --runtime INITIALIZED_PAPER --java-home JAVA21 --output NEW_MEMORY_DIRECTORY
python tools/run-factory-contract.py --starcore CURRENT.jar --chronicle CHRONICLE.jar --runtime INITIALIZED_PAPER --java-home JAVA21 --output NEW_CONTRACT_DIRECTORY
```

工具只读取已有 Paper 运行库，不启动服务器。快照常驻内存、写入构建、指纹排序、其他位置集合、长期归档和并发仍待继续；不删除奖励、来源或重试键换取容量。MySQL、全栈 Folia、第三方持久来源和当前构件实际插件组合尚未认证，十阶段目标继续进行。
