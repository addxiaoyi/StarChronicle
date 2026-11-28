# 2026-11-27 统计命令与天气所有权源码检查点

本轮沿用两库 `codex/plugin-all-stages-20261125`，只做源码、单元测试及离线副本验证，没有启动 Minecraft／Paper／Folia。StarCore `f586a641a5d8d853df15f53a3e0e5e95414a1eb1` 已推送，**1,422 项相关整合 verify、精确配对 JAR 的 API／八个编译夹具／13 JVM 数据库副本检查通过**。Chronicle 产品源码仍为 `53562a2`，1,195 项完整 verify 的原始证据与源码匹配。十阶段仍有源码工作。

## 统计命令

`1db8e2c` 将统计仪表盘、报表、导出、趋势回复和名字补全绑定原玩家／目标玩家 owner；控制台用全局线程，命令方块用捕获的区域。服务停用或代际变化会使旧命令与待完成回复失效。`29de117` 移除 Maven 对 `StatsCommandThreadingTest` 的旧编译排除，并修正泛型测试夹具。

第一轮 27 项虽然 Maven 成功，必需命令套件没有执行，因此验证器正确标记 `validation_success=false`；第二轮重新启用测试后遇到夹具编译错误，0 项；第三轮 **41 项实际通过**，含命令线程 14 项、服务生命周期 10 项与共享菜单任务 13 项，三个必需套件全部执行。原始目录分别为 `run/native-stats-owner-focused-{1,2,3}-20261127`，前两次失败保留。

## 天气世界与事件

`df56fcf` 将实际加载的 `WeatherModule` 三个周期迁移到全局调度；天气世界读写回全局 owner。玩家命令先提交国家天气目标，延迟投影只读取当前权威，释放控制、目标替换、到期或停用不会继续套用旧目标。调度拒绝保留已提交目标，不把投影失败误报成未提交；周期同步仍可恢复可应用的目标。

定时器部分注册失败会清理已获得的句柄；停用期间才返回的句柄立即取消。旧命令、菜单、监听器及事件订阅均有生命周期隔离。公共世界／国家设置查询返回副本，调用者不能通过 setter 绕过提交和冷却。

天气监听器按已提交的降雨／雷暴目标判断冲突，允许插件自己的天气投影；自动控制关闭时允许自然变化，不取消其他监听器的拒绝。

第一轮 **131 项、1 错误**：旧资源模块清理夹具仍填入 `BukkitTask`，与已存在的全局句柄不匹配。修正夹具及天气事件规则后，第二轮 **139 项通过**，零失败／错误／跳过，五个必需天气套件实际执行、源码无漂移。原始目录为 `run/native-weather-owner-focused-{1,2}-20261127`。

## 菜单与最终构件

`f586a64` 菜单补丁通过共享 `PlayerMenuTasks.executeNextTick` 离开当前库存事件再执行；复制被点击物品，保留原玩家与原库存，切换菜单、重连、退休及停用后丢弃回调。启动时天气恢复安排在本轮定时器代际确定之后。实际模块使用带插件生命周期的监听器构造方法；旧单参数构造方法保留原直接分派兼容语义。

`run/native-weather-stats-integrated-verify-1-20261127`：**1,422 项、212 套件、零失败／错误／跳过**，11 个必需套件全部执行，源码无漂移；Maven 351 秒，含证据归档 398 秒。它是相关整合回归，不是 StarCore 全库测试。Java 21 使用 320 MiB 堆、192 MiB metaspace、Serial GC、单处理器、`forkCount=0`；JaCoCo 明确跳过，离线 SBOM 没有重新生成。

| 冻结构件 | SHA-256 |
| --- | --- |
| Chronicle JAR | `3561a787cf02e343778d22e16bbfc05b15ad12e5e1c68f969cb63c818e2726fc` |
| StarCore shaded JAR | `85b82057e6e140bbd8ac6990c9e30469163207874457819d64481de4eafcc4b9` |
| StarCore original JAR | `707bf9a346f6925bb9af5e78089ea42bc63095cabbfae651fee5623fce9957a4` |

冻结目录为 `run/weather-stats-frozen-pair-1-20261127`。`weather-stats-pair-artifacts-2-20261127` 核对 Chronicle 214 个公开类型／1,866 个描述符、StarCore 3,010 个公开类型／35,883 个描述符、当前类与资源、源码快照及 sources JAR，八个夹具仅编译通过。首轮构件检查早于 Maven 证据归档完成，因缺 `result.json` 失败；原始失败目录保留，第二轮是实际通过结果。

`processing-merge-databases-weather-stats-1-20261127-20261126` 使用上述冻结 JAR 完成 13 个串行 JVM，旧／上游数据库原表原列与记录保持，升级、续接和独立重开不重复，四次 schema10 旧写入器尝试均拒绝。这些副本验证原生历史与 Chronicle 消费契约，不验证真实世界天气或玩家菜单。

验证工具另外修正了“仅执行 test 后列出 target 中旧 JAR”的歧义：今后只有 Maven 日志确实执行产品 jar 目标才记录 `artifacts`。工具 9 项回归通过。本轮早期 test 目录中的旧 `artifacts` 字段是当时目录库存，不是这些源码的打包结果；原始记录不覆盖，最终构件需要独立源码及类字节核验。

[证据包](verification/weather-stats-source-20261127-evidence.zip)与[逐成员清单](verification/weather-stats-source-20261127-summary.json)保留原始失败、成功 XML／日志、最终完整源码、构件及数据库证明。4,977 成员，14,471,812 字节，ZIP SHA-256 `45b04f1aecd23f3f57639c0b47cddeafa0f0082973b52102a9121c64940dae12`，逐成员重新读取核验通过；JAR／class 留在本地。为释放验证空间，对本任务旧副本做 NTFS 压缩，保留原字节和证据；空间恢复后停止本任务压缩进程，没有停止其他项目进程。

上游基线核对仍为 Chronicle `92442e1`／StarCore `4d01f64`。StarCore 开发分支已配置显式远端跟踪，直连失败后使用已启用的本机 `127.0.0.1:7897` 代理完成推送，没有改全局代理。当前 [CI 46015865707](https://github.com/addxiaoyi/starcore/actions/runs/46015865707) 四个检查均未启动（`runner_id=0`、零步骤），发布跳过。GitHub 原始注解明确为近期账户付款失败或额度需要增加：`The job was not started because recent account payments have failed or your spending limit needs to be increased.` [jobs 与 annotations 补充证据](verification/weather-stats-ci-annotations-20261127.json)单独保留，不覆盖先前 ZIP；这不是源码测试失败，也不能记为远端测试通过。

## 仍待完成与实服边界

天气设置成功表示目标已经提交；世界缺失、调度拒绝、其他插件取消天气事件时，不保证已出现视觉变化。文件或数据库与 Minecraft 世界没有共同事务，不能承诺断电后严格一次。单测未覆盖真实世界天气事件顺序、跨区域多人菜单或其他插件对天气的干预。

领地升级、社交模拟／八卦／影响力、联盟／政策／租赁／军队天气、兼容完成回调及叙事 Boss、旧国家临时加成玩法连接仍需源码工作。已检查到领地升级还存在经验消耗／恢复、异步与同步副本选择及完成回调问题，不能将它们归为仅待实服。

当前构件完整玩法、恢复、所选第三方组合、全栈 Folia、长期容量和生产负载继续需要实服验收。没有设置 `folia-supported:true`，没有合并主分支、部署或认证在线热卸载。十阶段目标保持未完成。
