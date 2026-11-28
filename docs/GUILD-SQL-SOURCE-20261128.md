# 公会 SQL：完整事务与原行恢复

StarCore `9d8617fa5d57fa832a4ad18eb9dd50f643dc5951` 已推送到开发分支 `codex/plugin-all-stages-20261125`。旧 SQL 保存会删除重建成员行并重置加入时间，恢复时读出了创建时间却未传入模型，批量保存会清空公会主表，异常仅记日志。新存储层引入不可变的完整 `GuildState`，公会／成员／邀请在同一事务中提交；已有行按字段差异更新，保持原成员与邀请行 ID、加入时间、未变化字段及其他扩展列。

读入整组公会、成员、邀请后检查 ID、唯一名称、玩家单公会、唯一会长、已知角色、整数时间和余额，再发布完整候选。失败不会清空调用者的现有集合。新增恢复构造器和 `GuildData` 保留原创建时间，旧公开方法描述符保留。

旧 SQL schema 没有余额列，本次用独立余额表和 schema 1 标记事务首次建立零余额；这不是恢复旧版从未持久化的金额，也不从 YAML 混入金额。标记建立后余额缺行、未知版本、外来成员／邀请或其他损坏均拒绝读写，不能静默补零。新软件的写事务使用共享锁行，提交前比较加载时的完整逻辑状态，拒绝陈旧候选；提交回执丢失或回滚失败明确报告结果不明。旧 JAR 不遵守该锁行，因此不宣称旧／新软件同时写库安全。

首轮 `native-guild-sql-focused-1-20261128` 实际执行 622 项，零失败／错误／跳过，打包成功、无漂移，但必需的 `GuildServiceOperationsTest` 被项目原有编译排除，整轮验收失败。两处明确的该套件排除已解除，原日志／源码／结果保留。复审另补按实际变化字段更新，避免仅改职位或余额时把旧 `NULL` 描述改为空串；对应回归测试与第二轮完整相关验证尚在执行。

第二轮 `native-guild-sql-focused-2-20261128` 执行 664 项、54 必需套件均出现，但有 1 个失败：重新启用的操作测试要求仅会长可管理职位，而模型允许 OFFICER。没有打包、无源码漂移。现修正为实际会长身份加 LEADER 成员角色，并拒绝普通职位操作增加第二个 LEADER；同步更正与既有操作合同矛盾的旧模型断言，增加服务级越权与增设会长回归。

第三轮 `native-guild-sql-focused-3-20261128` 的 **665 项相关 verify、54 个必需套件全部通过**，零失败／错误／跳过，无源码漂移，237.625 秒完成打包。使用 320 MiB heap、192 MiB metaspace、单 CPU、SerialGC、forkCount=0；离线跳过 SBOM 和 JaCoCo，保留其他项目测试排除，不代表 StarCore 全库回归。

`guild-sql-replicas-1-20261128` 的 **12 个串行 JVM** 使用实际旧／新 JAR：旧 JAR 建立公会、成员、邀请三张表，升级后原行 ID／时间／字段一致，新余额与成员加入／邀请删除完整事务在显式提交后 halt，两个独立进程重开保持一致；未来 schema、未知角色、余额缺行、非整数时间、双会长、孤立邀请六种副本拒绝且数据库字节不变；解散后独立重开仍保留另一公会。旧版 SQL 中内存余额 99 未落盘，新表初始零与此证据一致。

配对公开 API 保留 214／3,010 个基线公开类型，290／4,199 个当前项目类、资源及 Chronicle sources JAR 核对一致，8 个夹具编译通过。冻结目录 `run/guild-sql-frozen-pair-1-20261128`。

| 构件 | SHA-256 |
| --- | --- |
| StarCore | `9d3e3d7f2ff6204a3080d8fedac976d92f54f7957c1141ac6d9750aff7a88b11` |
| StarCore 原始 JAR | `db9a5dca3ebf406568de3f6bbeae5a2387c96bf7c1e93962b1ac2b9ab5113384` |
| Chronicle，仍为 `53562a2`／1,195 项完整 verify | `3561a787cf02e343778d22e16bbfc05b15ad12e5e1c68f969cb63c818e2726fc` |
| 证据 ZIP | `dbe93e0f0be2271b8c43d9ffa839efdebad34bab568ecc1afced2cb889412b09` |

[证据包](verification/guild-sql-source-20261128-evidence.zip)包含 11,810 个成员、27,967,705 字节；[机器摘要](verification/guild-sql-source-20261128-summary.json)逐文件核验，三轮结果均独立保留。StarCore [CI 46103316493](https://github.com/addxiaoyi/starcore/actions/runs/46103316493)四项检查因账户付款／额度限制没有 runner 或步骤；Chronicle 前批 `5018442` 的 [CI 46101355052](https://github.com/addxiaoyi/StarChronicle/actions/runs/46101355052)十步成功。[原始 CI](verification/guild-sql-ci-20261128.json)不将未启动视为通过。

公会服务层的先持久化后发布、未知结果隔离、YAML 严格恢复、其余权限竞态、命令 owner、全量导入导出仍需后续源码接入。本批提供真实 SQL 事务接口，不能将它视作实际 `acceptInvite` 已经合并了成员保存与邀请删除。未启动 Minecraft／Paper／Folia，MySQL、跨 JVM 长并发、硬件断电及当前构件实服仍待验；十阶段未完成。
