# 玩家线程与原会话传送：源码验证 2026-11-26

原 TPA 流程从发送者／接收者线程直接访问另一玩家，过期全局任务也读取在线状态并发送消息；实际传送使用同步 Player.teleport，延迟回调缺少原会话核验。本次贯穿邀请、接受／拒绝、过期、目标位置采样和异步完成反馈，按各玩家 owner 执行并保留原 Player 对象。

## 当前构件与证据

| 构件 | SHA-256 |
| --- | --- |
| StarCore | `a82d8eacc99beabe97eec89c47f5343f637c1d95eac5854f8e6149d7073a59c3` |
| Chronicle，主构件保持 | `1c7b8b010c9d330c15b0882753d54095fb906e42caedb926a81055d643cd78ce` |
| Chronicle 源码 JAR | `8a9634b60a7b256e228e5c96b62eb525824083d83cffb590828934d93dd2547c` |
| 证据 ZIP | `dec692e0c1c9f616182b08af9c58aa30e9fab4fed9a928089bc3fa7ec29f96ab` |

基线：Chronicle `6478dd2`、StarCore `e15b5e7`。[证据 ZIP](verification/teleport-owner-source-20261126-evidence.zip) **157 文件**，含全部测试失败与最终 XML、编译／打包／SBOM、源码、API 对照和工具；[机器摘要](verification/teleport-owner-source-20261126-summary.json)。上一检查点 Linux CI #175 成功日志和交付 Git 核对另行保留在本 ZIP。没有启动 Minecraft。

## 最终行为

- TPA／TPAHERE 保存原发送者和原接收者对象，唯一 nonce 区分同毫秒的新请求；新连接不能接受旧会话请求。每次玩家回调在该玩家 owner 上检查在线及当前 UUID 映射仍为同一对象。
- 邀请、接受／拒绝和过期通知分别在对应玩家 owner 上读取名字／在线状态并发送。全局过期任务只移除独立请求状态并派发原发送者通知；命令入口也不再读取远端目标的在线状态。
- 配置延迟结束后，在目标玩家 owner 上复制位置，再回到实际传送者 owner 核验原会话和原待执行对象，调用 teleportAsync。没有阻塞等待或同步 Player.teleport；成功、拒绝或异常结果再回原玩家 owner 显示。
- 待传送对象按身份区分，旧的相同位置回调不能消费新尝试；移动／受伤／退出可在目标查询期间取消。玩家清理移除其发出及收到的请求、涉及其 UUID 的待执行传送。Essentials 停用先关闭传送服务，再取消任务；新请求和晚到反馈会失效。
- 新增兼容调度器实体退休回调重载，取消、退休、空／拒绝注册和回调失败至多清理一次，正常单次完成不触发。清理只操作独立状态，可以运行在注册或取消线程。原公开方法保留。
- tick 类零延迟使用下一 tick，负延迟及非正周期在注册前拒绝。Paper 异步正的子 tick 时间向上取整，Folia 保留传入时间单位，异步零延迟走 runNow。TimeUnit 纳秒转换对极大值饱和，换算后不会溢出为负值／零。

公开玩家操作的入口必须已在发起者 owner 上，本次没有允许任意线程直接调用所有玩家 API。目标位置是采样时的独立快照，不承诺另一玩家在实际完成时仍位于原处，也不是跨区域共同事务。已准入／提交的传送允许完成；关闭后抑制反馈，不宣称位置回滚。

## 测试与失败保留

| 运行 | tests | failures | errors | skipped |
| --- | ---: | ---: | ---: | ---: |
| 旧实现所有权基线 | 19 | 19 | 0 | 0 |
| 首次完整相关回归 | 339 | 0 | 3 | 0 |
| 修正测试设置后的最终相关回归 | **339** | **0** | **0** | **0** |

新增 **37 项**：原会话／传送使用链 28，调度器边界 9。测试使用分别排队的玩家 owner，访问其他玩家的在线状态、名字、位置、消息或传送会直接断言失败；禁止同步传送，并模拟完成回包在线程外到达。覆盖两种传送方向、移动期间取消、原对象重连、相同位置的旧回调、退休／调度拒绝释放、关闭前后、独立目标快照、命令入口以及零／负／极大／子 tick 时间。

19 个基线失败分别暴露远端实体访问、同步传送、退出后发出请求残留或零延迟行为，原始 XML 与构件 SHA 保留。首次 339 项中新增 37 项全部通过，3 errors 是适配旧并发测试时嵌套调用 Mockito 的测试设置错误；只修正测试设置并重新编译测试，生产源码未再改变。没有新增测试排除。

调度、传送、经济命令、Essentials 数据异步保存／GUI／服务共同执行；339 项不是全库测试。原平台调度器／玩家使用替身，不能当作真实 Folia 区域传送验收。

本机每次单个 256 MiB JVM，forkCount=0、jacoco.skip=true 和 Mockito agent。Spotless、2,266 主源／1,086 测试源编译成功；修正测试设置后仅重新编译测试，最终运行：

```text
mvnw.cmd -o -B -ntp -Djacoco.skip=true -DforkCount=0 -Dtest=dev.starcore.starcore.core.scheduler.**,dev.starcore.starcore.essentials.** surefire:test
mvnw.cmd -o -B -ntp -Djacoco.skip=true -Dmaven.main.skip=true -Dmaven.test.skip=true verify
mvnw.cmd -B -ntp -Djacoco.skip=true cyclonedx:makeAggregateBom@generate-sbom
```

首次打包在 Wrapper／JVM 启动时因瞬时本机内存不足失败，未进入 Maven；两个 JVM 错误日志保留，可用内存恢复后以相同 256 MiB 参数重试成功。SBOM 启动时另一个 Wrapper native-access 探测 JVM 也因内存不足退出，但 Maven 与清单生成成功，其第三份错误日志单独保留。最终打包和在线 36 组件 SBOM 成功；7 个改动类与已测试 target/classes 字节相同，Java 21 字节码通过，源码与对应冻结副本一致。旧公开方法声明／描述符全保留，仅新增两个实体调度重载和 TeleportService.shutdown；没有本机 JaCoCo 结果。

Chronicle 主构件与 NPC 检查点的本地 1,021 项结果保持，本轮未本地重跑。此前 302 项、1,131 项（9 跳过）与更早国库／贸易／工厂契约均保留其原构件边界。Chronicle 文档提交推送后跟踪独立 Linux CI；StarCore 的现有触发器不覆盖基线为非 main 的草稿 PR。

## 尚未完成

完整模块命令解绑／热重载、其余 runSync／runNextTick 使用链及其他业务所有权迁移仍需继续。当前构件实服、实际 NPC／保护版本组合、全栈 Folia 和长期并发／容量验收继续待办。按用户选择只运行源码与单元测试，十阶段没有全部完成。
