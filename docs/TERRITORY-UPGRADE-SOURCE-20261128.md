# 2026-11-28 领地升级持久恢复与玩家操作检查点

StarCore `f71233c582fa6b3af76b794c85c3a9a0c1713666` 已推送至 `codex/plugin-all-stages-20261125`。领地升级先持久提交再发布等级、经验和回调；配置路径、命令及分页菜单接入实际模块。仅执行源码、单元测试和离线副本验证，没有启动 Minecraft／Paper／Folia，没有部署或合并主分支。十阶段仍有源码待办。

## 经验与文件恢复

累计取得经验与累计消耗分开记账，消耗不再从两处重复扣减。schema 2 保存消耗、更新时间、每日奖励时间及完整进程时间；旧 `exp` 是余额，迁移为旧余额加文件实际记录的消耗。旧版本重载时已经丢失、没有保存在文件中的消耗无法还原。已删除定义的路径进度保留，坏字段、未知版本、重复路径和不完整记录拒绝加载。

`territory_upgrade/states-v2.props` 是新的文件权威。旧 `territory_upgrades.dat` 和 `upgrade/states.props` 保留为只读迁移证据；两份都存在时必须内容一致，不能按文件顺序或修改时间选取。新文件保存 revision、旧来源 SHA 和 payload checksum，写入使用进程锁、加载指纹与原子替换，拒绝陈旧写入和坏当前文件回退。旧写入器修改来源后新读写会隔离，不能据此认证安全降级。旧构造器的数据库参数保持兼容，但这里没有 SQL 权威；历史 `saveAsync` void API 现在同步确认同一持久提交。

实际模块对加经验、升级、取消、强制完成和 reset 使用候选副本，保存成功后才替换内存；失败不升等级、不发送完成通知，并隔离后续写入。每日奖励时间与经验同一提交，reset 保留每日时间。自动升级每次选择排序后的第一条可升级路径，实际主流程仍是即时升级。兼容 `UpgradeScheduler` 的进度完成回调、目标等级和取消保护另有测试；实际模块不调用这个辅助类的 `startUpgrade`，不能把辅助类测试当作实际升级链证明。

## 配置、命令和菜单

配置读取支持真实 Bukkit `ConfigurationSection`，修复默认三条路径可能加载为空；坏重载保留旧定义。路径等级要求连续唯一，经验为合法整数，前置条件格式经过校验。

发送者与目标玩家分别在原 owner 采样和回复，复核权限、原会话及国家；停用使捕获的旧处理器失效并恢复原命令 executor／tab completer。菜单 holder 绑定国家、页码、路径和槽位快照，每页 14 条路径；点击复制状态后在下一 tick 复核原玩家和库存。旧 close／quit 不清新会话，修复多种库存尺寸的边框。玩家空参数和 `/upgrade gui`／`menu` 打开实际菜单，控制台空参数显示帮助。

启用先验证持久状态再注册入口；停用先失效命令、菜单和任务，再逐项清理，单项异常不阻断其他清理。成员通知回各玩家 owner，完成事件回 global；死亡监听捕获 killer 后回其 owner 处理。

## 验证与失败记录

| 目录（位于 `run/`） | 实际结果 |
| --- | --- |
| `native-territory-upgrade-focused-1-20261127` | 28 项通过，四个必需套件齐全 |
| `native-territory-upgrade-focused-2-20261127` | 71 项，1 error；Mockito 重设 answer 时记录 null 的测试夹具错误，随后改用 `doReturn` |
| `native-territory-upgrade-integrated-verify-1-20261127` | 1,483 项，1 failure；天气固定 2,400 ticks 断言遇到已过去 1 ms，实际 2,399 |
| `native-territory-upgrade-integrated-verify-2-20261127` | 1,485 项相关整合 verify 通过，12 个必需套件齐全；不含最后公开 GUI 命令入口补丁 |
| `native-territory-upgrade-final-verify-1-20261127` | 最终源码 100 项 verify 通过，11 个必需套件齐全；零失败／错误／跳过、无源码漂移，格式与打包通过 |

天气仅修改测试：按调用前后时间核验剩余 ticks 范围，未改天气产品源码。最终验证 Maven 248 秒，含源码归档 275.937 秒。Java 21、320 MiB heap、192 MiB metaspace、Serial GC、单处理器、`forkCount=0`；JaCoCo 跳过，离线 SBOM 没有重新生成。上述不是 StarCore 全库测试，也不能合称一次精确最终 1,486 项全量通过。Chronicle 产品仍为 `53562a2`，匹配其 1,195 项完整 verify。

| 最终冻结 JAR | SHA-256 |
| --- | --- |
| Chronicle | `3561a787cf02e343778d22e16bbfc05b15ad12e5e1c68f969cb63c818e2726fc` |
| StarCore shaded | `2bca4962a68c5d5194775052befa9288394ae3ee3941a05d4359278675cf0c22` |
| StarCore original | `5a63598ac6240592b326df276f3e115b194363b58da594e42f15b3d2cd87cf59` |

冻结目录 `run/territory-upgrade-frozen-pair-1-20261128`。配对检查 `territory-upgrade-pair-artifacts-1-20261128` 通过 Chronicle 214 个公开类型／1,866 个描述符、StarCore 3,010 个公开类型／35,883 个描述符、项目类和资源、源码快照、sources JAR 及八个仅编译夹具。

`territory-upgrade-offline-2-20261128` 用真实旧冻结 JAR codec 生成两份旧文件，新 JAR 文件存储完成迁移、两次独立重开、陈旧 writer 拒绝、旧 writer 改证据后的隔离、旧副本冲突及 checksum 损坏拒绝。11 个串行 JVM、四组检查全部通过；旧文件字节和拒绝后的权威文件保持。没有初始化服务器。首轮探针把 `javac -J` 参数放进 argument file，编译失败；修正工具后成功，原始工具与失败日志保留。

`processing-merge-databases-territory-upgrade-1-20261128-20261126` 使用最终配对 JAR 完成 13 个串行 JVM，原数据库表列与记录保持，升级／续接／重开不重复，四次 schema10 旧 writer 均拒绝。此项验证历史消费与持久契约，不证明玩家 GUI 或世界效果。

[证据 ZIP](verification/territory-upgrade-source-20261128-evidence.zip)及[逐成员清单](verification/territory-upgrade-source-20261128-summary.json)保存失败和成功日志／XML、最终完整源码、探针副本与构件清单。5,418 个 ZIP 成员，15,291,034 字节，SHA-256 `2bd69884cd49b46e4cde494c700e648a843364d54f0d7846d418e3f75642e7d5`；每个成员重新读取核验。JAR／class 未上传 Git。

当前提交的 [CI 46065270940](https://github.com/addxiaoyi/starcore/actions/runs/46065270940) 四个检查均为 `runner_id=0`、零步骤，发布跳过。[原始 jobs／annotations](verification/territory-upgrade-ci-annotations-20261128.json)明确指出账户付款／额度限制导致未启动，不是源码测试失败或远端测试通过。匿名读取先遇 GitHub 403 限流，随后通过既有本地 GitHub 凭据只读获取注解，凭据没有写入证据。同步确认上游仍为 Chronicle `92442e1`／StarCore `4d01f64`。

## 剩余源码与实服边界

领地收益查询目前没有接入国家领地上限、税收或资源消费路径，经验来源和部分升级设置仍硬编码；这些是源码待办，不能称领地玩法全部完成。社交模拟、联盟／政策／租赁／军队天气、兼容完成回调、叙事 Boss 和旧国家临时加成也继续推进。

每次变化同步写完整快照并 fsync，可能占用玩家 owner 时间；规模和长期负载尚未验证。文件状态与 Minecraft 世界／通知没有共同事务，完成通知不是持久 outbox，不承诺断电严格一次。旧来源冲突保持拒绝并保留证据供核对，不能自动猜测较新副本。

当前构件完整玩法、多人菜单、真实重启、第三方组合、全栈 Folia 与长期负载仍需实服验收；不复用旧构件实服证明，不设置 `folia-supported:true`，不认证在线热卸载。
