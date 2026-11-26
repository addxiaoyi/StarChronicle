# 工厂加工历史兼容合并：源码检查点

日期：2026-11-26。两仓开发分支均为 `codex/plugin-all-stages-20261125`。本轮接续中断的上游合并：Chronicle `92442e1`，StarCore `4d01f64`。只做源码、单元测试和离线副本核对，没有启动 Minecraft。

## 合并范围

- 保留已安装的定时工厂批次、原子库存操作、完整回执归档和原公开 API；新即时加工协议采用独立持久运行编号。
- Chronicle 保留旧生产来源及其单位，新增加工来源独立 authority、游标与完整恢复锚点；贡献在同事务中保存并去重。
- Chronicle 数据库升级至 schema 11，拒绝旧 schema 10 写入器修改新库。
- 原生运行索引恢复时逐项核对不可变回执，拒绝索引编号替换或删除后重新执行已完成资产操作。
- 上游实服记录及其原始失败保留为对应冻结构件的历史证据，不计作本次合并构件的实服通过。

## 冻结加工检查点的实际验证

- Chronicle 完整回归 **1,161 项，零失败、错误或跳过**。逐文件对照 `run/processing-merge-chronicle-full-2-20261126` 的冻结源码和构建输入，与加工检查点源码一致。
- Chronicle 独立打包成功；`run/processing-merge-chronicle-package-5-20261126` 的冻结源码、构建输入及产品 JAR 哈希与加工检查点一致。
- StarCore `run/processing-merge-native-resource-9-20261126`：**78 套件／1,480 项，零失败、错误或跳过**，冻结源码无漂移。之后仅对 `ProductionPersistenceTest` 两处换行做格式修复；生产源码没有再改。
- StarCore 最终 `run/processing-merge-native-verify-13-20261126`：`verify` 成功，格式检查通过，`ProductionPersistenceTest,TradeCommitJournalTest` **19 项，零失败、错误或跳过**，最终源码无漂移。19 项是精确最终快照的复核，不与上述 1,480 项相加为独立用例数。离线构建明确跳过 CycloneDX，未声称生成新的 SBOM；JaCoCo 同样显式跳过。
- 两仓原有公开类及 public/protected JVM 描述符检查通过：Chronicle **214 类／1,866 描述符**，StarCore **3,010 类／35,883 描述符**。当前项目类／资源、Chronicle 源码 JAR 与 API.md 对照通过；**8 个夹具仅编译**。见 `run/processing-merge-artifacts-14-20261126`。
- 最终配对 JAR：Chronicle `e6ba140c3e287a33e3d0a9955c8478cefc94221c450dcf6d296182801522f3fc`；StarCore `c9f45c4530bf3354a99408f313348e180dd11456d8e7ab6780019a8af882660c`。本机在忽略的 `run/processing-merge-frozen-pair-20261126` 保留副本，后续源码修复不转用本检查点证据。
- `run/processing-merge-databases-final-18-20261126` **VERIFIED**：13 个独立 JVM 只调用实际构件存储和 Chronicle 消费者，未启动服务器。旧数据升级后保留两条旧生产、旧工厂、原库存和贡献；追加后库存为矿物 964／能源 988／工业 24，生产回执 4 条、加工记录 5 条、贡献 12 条，两次重开不再处理。上游副本的 137 条加工、139 条资源及 12 条贡献保持，升级和两次重开均零补发。
- 原表列与业务记录逐表对照；首次安装写入保护时允许且记录 `resource.factory-runs.head`、`resource.history.commit` 两项可变协议元数据更新，并保留前后快照，不将其描述为整库逐字节不变。不可变页、原生运行索引及贡献／回执另作精确对照。
- 两个独立 schema 10 写入器分别尝试两个 schema 11 副本，**四次均拒绝**，拒绝前后库文件哈希与记录不变。第二写入器由上游 `aed14242589f95bbbcc8cb76de22a2d4135bebab` 源码独立重建，不能代表原报告 `cb9fe0a5` 二进制或其测试结果。
- Chronicle 提交 `ff88ea9` 的 GitHub CI 两次成功（45854084603、45854095948）。StarCore 两次 CI（45854094867、45854105126）因账户付款／支出限制未启动任何步骤，属于远端执行障碍，不能记为源码失败或通过；没有修改账户计费设置。

## 失败记录与验证工具修正

首轮原生 1,480 项有三项错误：一项旧测试与非法配方构造校验不一致，两项 SQLite 内存／事务错误。已修正构造预期并保留注册／服务层未知资源拒绝覆盖；后续完整 1,480 项及精确最终 19 项通过。focused-6 JVM 初始化内存失败、focused-7 编译堆不足、package-10 进程中断无结果、package-11 PowerShell 参数拆分、package-12 两处格式检查失败均保留，没有计为通过。

数据库工具 final-15 因合法零库存字段触发过严字典断言，final-16／17 因首次升级的协议元数据触发过严原行断言失败。脚本改为精确核对所有非零余额，并仅允许上述两个指定键被同键替换，仍拒绝余额变化、额外非零资源或删除元数据；5 项 Python 回归通过。源码快照工具新增／删除文件和构建输入漂移检查另有 6 项回归通过。原失败目录完整保留，final-18 为修正后的重新验证。

脱敏源码、完整测试报告、构建日志、前述失败和最终副本证据见 [归档](verification/processing-merge-source-20261126-evidence.zip) 与 [校验摘要](verification/processing-merge-source-20261126-summary.json)。归档为 18,936,051 字节，包含 6,327 个证据成员及一个清单，逐成员 SHA-256 核验；ZIP SHA-256 为 `3aeacc66a68b4b9496629eb3d5fc2225d24203c356c9af44617d0f4eb21e1aef`。源码仅保留两仓精确最终快照一次，失败轮次保留原命令、源码哈希、报告和日志；排除 JAR、类文件和崩溃转储。首次较大归档因系统提交内存不足主动中断，残件本地保留为 `run/processing-merge-archive-interrupted-20261126.zip`，未计为成功；本归档以流式哈希、写入和读取重新完成。

## 后续源码与实服边界

此处完成的是工厂加工合并的源码、单元测试、构件兼容和离线副本检查点。科研轮次及未知提交隔离、派对邀请恢复、保存脏标记、战争保存协调器、邮件所有权和历史计数容量补丁另在独立工作树继续开发，不包含在上述测试数中。当前构件玩家业务、Folia 全栈、第三方插件组合、真实权限和长期运行仍需实服验收。十阶段状态以 [剩余工作](REMAINING-WORK.md) 和 [交接记录](HANDOFF-2026-11-26.md) 为准。
