# GitHub 组合保护增量合并：2026-11-26

将上游 `9a0c103`／`3f79820` 合入 `89c361f`，接通 WorldGuard 纪念点创建保护，并保留原有 NPC 会话／生命周期修复和成员界面测试入口。解决 6 个文件冲突，没有启动 Minecraft。

| 构件 | SHA-256 |
| --- | --- |
| Chronicle | `10135139ddc6332ece286d16a5567da5e78fd527493789bc63cb1488ac851b77` |
| Sources | `1cad1823cc57ab99270140a3ebadd2f3bf25de005db0fd8896cb0763b375ecb9` |
| StarCore（本轮未改） | `e321d2d7cc3c05ef5b30395b58b98f613824fdefdcdc113772159f91e092b5e0` |
| 本轮证据 ZIP | `4f57c9fee549e71924c199c94792abc4ace9d55b39a96eae9f0ce771a85c2d78` |

完整 `clean verify` **1,050 项通过，0 failures／errors／skipped**，包括上游新增的 5 项保护边界测试。单个 256 MiB JVM，forkCount=0、jacoco.skip=true；不提供本机覆盖率。250 个源码／资源／测试文件与冻结副本一致，选定类与未重定位 JAR 字节一致；最终 shade 保留正常重定位。8 类旧公开声明及描述符保持。双插件史诗夹具针对当前构件编译通过，JavaScript 与 8 个 Python 工具语法通过。

创建检查使用玩家真实 WorldGuard 身份，覆盖纪念点相邻及上方共 36 个位置，不加载区块。管理员权限不替代领地权限；服务存在但停用、接口或查询失败时拒绝创建。只检查创建时授权，不自动清除后续规则变更下的既有点位。新增组合工具显式使用 UTF-8，兼容 Windows 中文日志和配置。

最终 JAR 对两份真实旧数据库副本以 1、2、1、0 批次恢复，再分别启动独立 JVM 重开，恢复量均为 0、贡献仍为 8；各 38 张原表原列完全保持。此检查不是 Minecraft 整服升级。原备份与失败历史均保留。

[证据包](verification/github-combination-merge-20261126-evidence.zip) 含 419 个文件，[机器摘要](verification/github-combination-merge-20261126-summary.json) 保存原始构建、API 与数据库结果。

上游[组合验收](PRODUCTION-COMBINATION-TEST-REPORT.md)的 603 个清单文件逐个哈希通过，原 ZIP SHA-256 为 `2c7d1081e8d8ca6b2cabaa61426de260ba1e48cca57cb97f22e0e7d68574fba1`。其 887 项与 Paper 双路线各三次启动、48 项证据回归属于 Chronicle `5758e54b…96daa`／StarCore `44f450ec…8518d`，没有移用于当前构件。本机缺少固定哈希的第三方 JAR，未编译新 ProductionCombinationFixture，也未重跑组合证据回归。在线热卸载仍保留其真实失败边界。

StarCore 沿用独立 484 项经济检查点。继续推进社交／GUI 所有权、外部经济不确定结果、全栈 Folia 与长期容量；当前构件实服验收保持待办，十阶段尚未全部完成。
