# 国家加工源码与测试检查点

2026-11-25，两库继续使用 `codex/plugin-all-stages-20261125`。从 Chronicle `11ff080` 与 StarCore `1dd1562` 续接；用户要求先做源码与单元测试，本轮未启动 Minecraft。

## 完成范围

StarCore 国家即时加工现在将全部扣料、全部产出与不可变生产回执放在同一个存储提交中。显式操作编号支持重启后安全重试，拒绝编号改绑国家、操作者、配方或批次数。已提交编号固定原始数量，即使配方随后修改也只返回原结果。新增 `/resource process <配方ID> <批次数> <操作编号>`，命令和原生服务均核验当前国家创始人；`/resource factory recipes` 展示可用配方 ID。新增两条使用原生国家库存资源类型的工业与化工配方。

文件模式同一快照保存资源与回执；SQL 模式使用独立索引表并与库存同事务保存。保存失败先返回 UNKNOWN，再从同域持久状态核对；无法核对时禁止继续保存旧内存快照。文件孤立日志、缺失游标、序号缺口、混用备份与不一致迁移均拒绝恢复，SQL 失败不另行写入旧文件。当前文件模式完整日志加载和 SQL 完整性计数的长期成本仍需容量验收。

Chronicle 自动分页读取第五类原生持久来源，历史事件 `CIVILIZATION_PRODUCTION_COMMITTED` 保留真实投入、产出、配方、批次、时间与身份。来源游标、历史与个人／国家的 `CIVILIZATION` 贡献同事务提交，每种产出独立计量并持久去重。系统调用没有玩家身份时只记录国家历史。旧来源哈希保持兼容；超大数量仍保留历史但不截断后记账。

此来源覆盖国家即时资源加工。旧定时工厂、第三方工厂、大型文明建设、普通收割和采集没有被声明为该来源；单笔加工也不会自动宣告文明里程碑。使用方法见 [持久来源](COMMITTED-SOURCES.md) 与配套 StarCore `docs/NATIONAL-PRODUCTION.md`。

## 验证与构件

| 构件 | SHA-256 |
| --- | --- |
| StarChronicle | `71d98cd504338b1116109cc8b793897727e848867b97223f8b48d7a730b623a6` |
| StarCore | `693fee46972d26668aaded6b62f83263e0828238ec50ce5d04b1aab7d37a59d2` |

Chronicle 完整 `verify` 为 **922 项、零失败／错误／跳过**。StarCore 专项 `verify` 为 **71 项、零失败／错误／跳过**，包括加工持久化、真实 SQL 回滚与迁移、资源交付回归、命令身份、角色矩阵、资源编码和公共 API 检查；格式、打包和 36 组件 SBOM 通过。未重跑 StarCore 全量，不沿用旧全量成绩。两库使用 256 MB Maven heap 和 `forkCount=0`，没有本轮 JaCoCo 覆盖率证据。

`tools/run-production-contract.py` 对上述两份最终 JAR 启动三个独立 JVM，调用实际 ResourceModule、SimpleProcessingService、SQL 资源存储、反射适配器与 Chronicle SQLite：

1. 建立隔离库存，指定操作者完成第一笔原生资源转换，读取回执并写入历史与双主体贡献。
2. 新进程重放原编号、拒绝改批次；完成第二笔玩家加工及一笔无玩家身份的系统加工，追赶并再次重放。
3. 再次重启并修改配方，原编号仍返回原回执；库存保持真实扣料／产出结果，查询不生成额外提交、历史或贡献。

Python 随后独立读取两份 SQLite，核对三条原生回执、三条历史、四份贡献及两边的存储域、游标、数量、身份与提交时间。国家查询使用固定的脱离 Bukkit 的 NationService 测试夹具；未执行真实玩家命令、工厂等待或 Minecraft 生命周期。最终目录 `run/production-contract-20261125-1`，冻结 JAR 在其 `artifacts/`，未提交二进制插件。

[证据包](verification/production-source-20261125-evidence.zip)包含 251 份文件与内部逐文件哈希，ZIP SHA-256 为 `46cea3d686cd37e9c3b7969d883e9feb09c70215d5482ced3deb0e03fc6fa059`；[机器摘要](verification/production-source-20261125-summary.json)保存精确构件及源码哈希。

## 保留失败与限制

首次 Chronicle 全量 921 项出现一个旧跨剧情恢复用例错误：清理事务时的 `cannot commit - no transaction is active` 遮住了原始异常。已修正异常保留，在 rollback／reset 失败时保留 suppressed 异常并关闭不可靠连接；用真实 SQLite `RAISE(ROLLBACK)` 测试验证该行为。专项 13 项与全量 922 项通过。原始瞬时触发原因未复现、尚未确定，不能声称已证明其根因；首次日志与 XML 原样保留。原生首次 55 项通过，补充恢复边界后扩展至上述 71 项。

本轮还发现并修复了 GitHub 旧证据换行损坏，86 个文件只在字节数与原清单 SHA-256 精确匹配时恢复。修复提交 `11ff080` 的 Linux PR 检查完整通过；详情见 [CI 修复报告](CI-EVIDENCE-REPAIR-20261125.md)。该 914 项 CI 属于修复提交，当前新增生产功能的远端检查以其自身运行结果为准。

当前构件实服、全栈 Folia、第三方 NPC／保护／生产组合、旧定时工厂原子恢复、长期压力与生产部署仍待完成。下步优先处理容量与维护，再继续这些阶段；十阶段目标保持进行中。
