# 首都事务历史与已安装账本兼容合并

2026-11-26：在 Chronicle `232ac9b`／StarCore `ef432da` 上合入上游 `be7023c`／`93ff6bfb`。当前完成 **1,123 项 Chronicle 完整 clean verify** 与 **275 项 StarCore 相关测试**，均零失败／错误／跳过。仅源码、打包与数据库副本验证；按用户要求未启动 Minecraft。十阶段仍在进行。

## 升级行为

- 首都源改为独立 SQLite 事务日志，稳定 operation、连续序号、前后城市、原指定人与纳秒时间同事务提交。旧文本只导入完整行，原字节及未完成尾部保存在 `.legacy`；原 `.log` 成为域与 SHA 标记。重启、失败回滚、并发重复提交、缺库／换域和旧备份回退均有原生测试。
- `CapitalHistoryCoordinator` 保留已安装路径：单数 `starcore.capital` 继续使用旧国家域、旧事件 ID／事实、原有激活行为；新安装和上游复数 `starcore.capitals` 使用独立首都域，首次追赶只归档。双游标冲突停止消费，不删除证明、不静默换域。
- 专用源每页重读并校验最后一条回执，内容变化或来源回退不能表现为空闲成功。含锚点的原始页决定是否追到头，满页移除锚点后的127条仍属于追赶。各协调器只消费自己的 pending。
- 原科技／贸易协调器、schema 9 净贡献语义、成员和其他提交来源保持。首都不额外产生贡献；快照不从城市等级或当前国主推定历史。
- 未来实服工具识别协调器的新失败日志；归档器从明确指定的验证日志读取实际套件和计数，拒绝混入旧 XML，不再硬编码934／7234。需传 `--native-verify-log`、`--evidence-test-log`，可传 `--verify-log` 和 `--native-package-log`。未执行本构件的13项实服证据回归。

## 本构件验证

| 检查 | 实际结果 |
| --- | --- |
| Chronicle | 116 套件／1,123 项完整 clean verify，0失败、0错误、0跳过；比1,105项基线增加上游9项与兼容9项 |
| StarCore | 国家相关59套件／272项，另3项公开API契约；包含15项首都存储与授权测试，不是本轮原生全库回归 |
| 构建 | StarCore test-compile、Spotless、skip-tests verify及36组件SBOM通过；Maven256 MiB、探针128 MiB，JVM串行 |
| 构件关联 | Chronicle271份源码／测试／资源与完整测试快照一致；两库关键编译字节码与最终JAR核对；Chronicle12类、StarCore6类旧公开API保留 |
| 夹具 | EpicCampaign、TechnologyHistory、TradeHistory、TradeContribution、CapitalHistory五夹具对当前两JAR编译通过，仅证明可链接 |
| 工具 | 四Python文件语法、3份原上游日志、6种追加旧／新告警拒绝，以及实际日志／XML计数和旧套件拒绝检查通过 |

首次57项专项有1项测试断言失败：新测试误把旧首都首次导入当作仅归档；旧协议实际会激活。修正为保留旧行为后完整1,123项通过，原失败和源码快照保留。工具探针另有一次Windows日志严格UTF-8解码失败；按产品归档器的容错解码修正，未改原日志，失败也保留。

## 实际 JAR 与副本恢复

旧JAR在独立JVM生成260条首都提交及对应Chronicle旧账本，再追加未提交尾部。当前JAR迁移／重开新增0，**47张原表原列保持**，52,653字节旧文本完整保留，原毫秒时间不改写；新增第261条只入史一次并保留纳秒，再次重开新增0。新Chronicle在同一原生迁移副本导入261条专用历史，重开新增0，独立域不同于旧国家域。

上游实服证据包8,659份清单文件逐个SHA通过。复制第三次停服的完整Chronicle DB/WAL/SHM并一致性备份，复制首都SQLite／标记／legacy；当前JAR两次重开均新增0，139条首都回执和总141事件保持，Chronicle43张原表原列及首都2张表保持。分别用旧JAR尝试打开已迁移旧库和上游库，两次均拒绝，原文件哈希保持。

这些探针调用真实打包的首都存储、Chronicle收集器与协调器，国家域由测试门面提供；没有启动NationModule或验证真实在线国主授权。它们不是整服升级、在线玩家、资产变更或生产部署验收。

## 固定构件和证据

| 文件 | SHA-256 |
| --- | --- |
| Chronicle JAR | `53844b0d1c78b014325fd483001ee0406cb9053204786144acfe8aa0d325f5a9` |
| Chronicle sources JAR | `9bffee5664099bdb39415c85ca6de1bff72c1322df7d5a4a9bade3799bf4bad6` |
| StarCore JAR | `c4b800534ebe89a1a5c7f02dcc19b1a86f78c5c3ac3962d09268858a3088121f` |
| 合并证据 ZIP | `07c459e6ba85414764fc38eee5de30f2669d38b3c0133c7ab96ae114c075955f` |

[机器摘要](verification/github-capital-merge-20261126-summary.json)与[证据包](verification/github-capital-merge-20261126-evidence.zip)含897成员，保存成功／失败日志、已完成XML、原冲突、构件API／字节码指纹、真实副本及探针、测试源码快照。上游[原首都验收](COMMITTED-CAPITAL-HISTORY.md)继续只适用于其 `00922cb5`／`8f428885` 构件，不覆盖本次合并。

首都旧文本迁移仍全量读取文件；长期容量、未撮合订单托管／待核对流程、其他所有权和生命周期、外部经济不确定结果，以及当前构件完整插件组合与全栈Folia仍待推进。没有合并主分支或部署生产。
