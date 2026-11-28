# 资源 SQL 快照差量写入与回滚验证

从 Chronicle `d2b74cc` 与 StarCore `494cded` 继续，两库仍使用 `codex/plugin-all-stages-20261125` 和草稿 PR #2／#3。按用户选择只做源码与隔离测试，本轮没有 Minecraft 实服。

## 写入变化

原 SQL 资源保存每次删除整张状态表，再插入完整快照；一个库存数字改变也会重复写入全部历史工厂批次。现在仍接收完整快照，但在 SERIALIZABLE 事务内比较已有行：相同行不写，已删除或变化的行按主键删除，变化和新增行插入。每批最多 256 行。完整替换语义、生产／交付回执事务和存储格式保持不变。

驱动明确报告零行写入时拒绝提交，防止触发器静默忽略插入后留下被删除的库存行。插入／删除或回执写入失败会回滚整次提交。未把历史批次删除或转成可过期缓存。

## H2 失败与修复

首次专项 **39 项，4 failures、1 error**。将写事务设为 SERIALIZABLE 后，H2 2.3.232 的 `ORDER BY sequence DESC LIMIT 1` 在同事务追加回执后返回旧尾部；独立 `H2IsolationProbe` 同时复现 REPEATABLE_READ 和 SERIALIZABLE，COUNT 已看到新行，MAX 仍返回旧值，按序号查询能读到新记录。该行为使新回执与日志头校验不一致，相关完成返回 UNKNOWN；初始日志与报告保留。

生产日志校验现改为按声明序号精确读取末条，再检查总行数以及位于 `1..head` 的行数。序号唯一约束下，两者都等于 head 才能证明无缺口、无额外尾部。新增回归验证同事务自身写入可见、超出 head 的额外回执被拒绝，并保留内部缺口与错配头检查。没有降低校验强度来通过测试。

## 构件与回归

| 构件 | SHA-256 |
| --- | --- |
| 当前 StarCore | `97db36ee40dbc6df8c765c0bbbb92dadcddf8989f5b31db77505d814e31470f1` |
| 旧 StarCore 对照 | `fd9b732d62e4e4906a93e3442a7fd324032347f2740357b97bee4a841c32de4c` |
| 配套 Chronicle | `6f613084bed21d54d0f7cbdef0150a60336aafb6900c95fce04c29af804bae51` |

修正专项 **41 项通过**，随后完整资源模块 `verify` **288 项，零失败／错误／跳过**。格式、打包通过，离线跳过的 SBOM 按仓库配置单独生成并验证 36 组件。实际日志 `run/resource-snapshot-diff-resource-verify-20261126.log`，使用 256 MiB Maven 堆、`forkCount=0`、`jacoco.skip=true`；没有当前原生全量或 JaCoCo 结果。

新增 9 项测试包含真实 SQLite／H2 的无变化零写入、单库存行修改保留全部回执、跨批次新增／删除／修改及重新打开、生产与库存共同回滚、驱动零行写入拒绝、事务隔离及上述 H2 自身写入可见性。513 个历史完成批次与活动批次完整保留。

Chronicle 主源码与 JAR 沿用历史查询检查点的 940 项完整构建；此前工具／文档提交 `d2b74cc` 的 Linux CI #69 通过。本增量没有重复本地 Chronicle 全量测试。

## 真实库存 API 对照

`tools/run-resource-snapshot-capacity.py` 用冻结旧 JAR 建立样本：1,000 个完成批次、1 个活动批次及 1,000 条生产回执。两份数据库分别交给旧、新 JAR 的实际 `ResourceModule.grant` 连续执行 **50 次各增加 1 industrial**；每个 JVM 使用 64 MiB 堆，串行运行。JDBC 包装记录实际受影响的状态表行数，不使用额外数据库触发器。

| 构件 | 删除行数 | 插入行数 | 50 次保存 p50 | p95 | 最大 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 旧构件 | 652,050 | 652,050 | 143.381 ms | 169.975 ms | 175.509 ms |
| 当前 | 50 | 50 | 101.265 ms | 115.272 ms | 134.265 ms |

每次保存仍构建、比较完整快照，因此降低写入量的幅度不等于延迟下降幅度。这里是共享机器的小堆隔离直接 API 测量，没有 Bukkit、并发玩家或网络，不是生产吞吐保证。

保存后重新构造模块核对库存和全部批次，Python 独立确认原来 2,000 industrial 变为 2,050；状态表仅该值改变，其余键值与全部日志表逐行摘要相同，数据库完整性通过。没有把 grant 伪造为新的生产回执。样本和结果保留在 `run/resource-snapshot-capacity-20261126-1`。

当前两库 JAR 也通过三个独立 JVM 的真实 SQLite 工厂契约：按冻结期限恢复、完成入史、重放不重复和系统批次贡献边界；目录 `run/factory-contract-snapshot-20261126-1`。仍使用受控时钟与固定国家身份夹具，不是 Minecraft 实服。

## 证据与剩余边界

[证据 ZIP](verification/resource-snapshot-source-20261126-evidence.zip)包含 **93 文件**，逐文件 SHA-256 已复核；ZIP SHA-256 为 `79256a9f79ceeab786050e5fa8f9d14042c6cdb58930774d6b06cb9a3ee8b397`。[机器摘要](verification/resource-snapshot-source-20261126-summary.json)记录源码、构件、测试、原始失败与实际测量。

批次仍常驻内存并保存在完整快照中；新批次 ID 改变排序时会移动多条索引行，文件保存和快照比较成本仍随历史增长。该检查点验证 SQLite 与 H2，没有认证 MySQL 或任意 JDBC 驱动。长期并发、归档、大量定义版本、深页查询、第三方生产、全栈 Folia 与当前构件实服仍待推进，十阶段目标未完成。
