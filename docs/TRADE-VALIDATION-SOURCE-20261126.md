# 贸易协议、路线、禁运与配额恢复

2026-11-26，续接两库 `codex/plugin-all-stages-20261125`。原生历史解码器会跳过错误贸易记录、保留重复编号的第一条、把错误布尔值转成 false、丢弃部分禁运目标，并将路线效率钳制到 0–2。直接将这种结果恢复为权威状态，可能丢失贸易规则及原始记录。本轮在权威启动和提交异常对账入口增加四类集合的完整校验。

检查包括声明行数、连续规范索引、允许字段、必需字段、唯一编号、规范 UUID／整数／有限浮点数、精确布尔值、正协议数量与价格、时间先后、完整唯一禁运目标、非负累计贸易量及配额使用范围。声明数量先受实际字段量约束，不能驱动巨量空循环。损坏快照保留原存储并关闭资源权威和库存写入，不把旧解码器的残缺结果写回。

依赖同一 ResourceModule 的贸易服务在全部 22 个业务查询／修改入口检查隔离状态，错误恢复时抛出 IllegalStateException，而不是报告“没有禁运或配额”。执行协议、定时重置、配额使用、创建和取消规则均不能在已隔离状态开始。提交异常后的恢复先检查全部字段，再替换各集合；有效状态按实际提交恢复。

兼容旧可选字段：协议 autoRenew 缺失为 false；路线 active／volume 为 true／0；禁运 expiry／reason／active 为无限期／未说明／true；配额 current 为 0。继承的 Properties 属性同样可读。保留历史自定义资源 ID、原毫秒时间及历史数量／价格／配额上限，不追溯应用当前新建贸易限额。恢复不通过 trim、截断、合并重复项或钳制数值来掩盖损坏。

原模型的 equals 只比较编号，因此回归逐项重编码并对照完整持久字段，不能以集合 equals 冒充完整恢复。测试在加载前接入真实 SimpleResourceTradeService，沿用生产初始化顺序，避免无贸易服务的测试模块在后续库存保存中自己清空规则。

## 验证与证据

旧代码首次 **368 项、364 failures、0 errors、0 skipped**。三种存储各 120 种损坏，加 4 种提交异常损坏均复现；4 项合法数据用例通过。原日志、XML、测试源码及编译后的测试类另存，未覆盖失败记录。随后增加巨大计数有界拒绝、全部业务入口隔离及真实文件／SQLite／H2 提交成功后回包丢失时的完整字段恢复。

资源域 **908 项＋科研支付 13 项，共 921 项**零失败／错误／跳过，新增贸易恢复 376 项全部通过。Java 21、串行 Maven、256 MiB 堆、`forkCount=0`、`jacoco.skip=true`；没有当前原生全库或 JaCoCo。测试结束后的 JAR 组装发生 Java heap space，完整失败日志及全部 XML 保留。源码未修改，另用新的串行 256 MiB JVM 跳过测试完成打包检查，不能把这次打包算作第二次测试通过。

Chronicle 主源码未变，沿用此前 957 项完整测试通过的同一 JAR；未新增本地 Chronicle 全量测试。基线 `2850de4` 的 GitHub Linux Maven verification #111 已成功。

新的独立 `verify -DskipTests` 已完成 JAR、依赖打包和格式检查；独立在线 36 组件 SBOM 成功，保留可选依赖元数据及模式关键字警告。

| 构件 | SHA-256 |
| --- | --- |
| 当前 StarCore | `c484ea1dc5c5cfd08146d9756a221ead3625ae68f2427225ce9c27fcaf05bbb5` |
| 配套 Chronicle | `76606f33bcc50ee2faf753cb144acf90cf7513a49e36ecf99d823be6e1408886` |

最终两库三 JVM 工厂契约通过：原子扣料、无配方恢复、冻结期限完成、归档后原编号重放、系统批次及 Chronicle 历史／贡献一致。使用实际打包服务、SQLite、受控时钟和固定身份夹具，仅取 Paper 运行库，未启动 Minecraft。冻结目录 `run/factory-contract-trade-20261126-1`，可使用 `tools/run-factory-contract.py` 指定新的输出目录复现。

[145 文件证据包](verification/trade-validation-source-20261126-evidence.zip)包含逐文件 SHA-256，已逐项复核，ZIP SHA-256：`e91a157b2e10167136cf3f333d88a166dd85b04ead600c63dfbfc1afe1cbdea3`。[机器摘要](verification/trade-validation-source-20261126-summary.json)记录原始失败、921 项最终 XML、源码及构件指纹、独立打包、SBOM 和契约日志。配套源码恢复规则见 StarCore `docs/TRADE-SNAPSHOT-RECOVERY.md`。

## 边界与后续

本轮是格式与恢复完整性保护，不认证合法格式任意篡改，也不证明旧文件和 SQL 全字段一致。当前 service 仍暴露可变模型对象；已持有引用、并发在途操作和跨服务贸易事务仍需独立处理。普通布尔库存 API 的 false 不能证明未提交，不能据此安全自动重试。

前一工厂 5,000 批次容量数字保留在原检查点，未套用到本轮 JAR。继续推进旧文件核对、其他集合容量、首次迁移峰值及长期并发。按用户选择未启动 Minecraft，MySQL、完整 Folia 和实际第三方组合仍待验收，十阶段整体尚未完成。
