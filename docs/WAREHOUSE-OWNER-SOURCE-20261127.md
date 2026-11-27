# 2026-11-27 仓库 GUI 所有权与旧会话检查点

StarCore `060a7af4c16e350b81e945435b9230b759873e3b` 已推送到现有开发分支。此提交包含此前区域效果／围城源码，另修复仓库 GUI 的玩家所有权、旧关闭／退出会话及旧显示副本写回问题。没有启动 Minecraft／Paper／Folia，没有新的配对 JAR 或实服验收声明。

- 打开、模式切换、关闭和升级回包使用 PlayerMenuTasks，核验原 Player、当前服务器会话及界面。等待升级时重复点击不再次请求；关闭、换界面、重连、实体退休或停用后，不发旧结果也不重开界面。
- 替换已注册 GUI 时使旧回调失效；旧 Player 的关闭／退出不移除新 Player 的注册，模式切换导致旧 Inventory 关闭也不会清掉新界面。书本输入按原会话处理。
- 仓库界面已取消点击／拖拽并逐次提交存取，显示 Inventory 是副本。关闭和退出不再清空共享仓库并把这个旧副本整份写回，避免覆盖其他查看者期间的变更。旧内部同步方法暂保留兼容测试，实际关闭／退出路径不调用它。

## 实际验证

`run/native-warehouse-owner-focused-1-20261127` 实际执行 199 项，1 项失败：新 GUI 测试没有为仓库类型配置桩，导致真实渲染路径中断；错误修正，原日志保留。

`run/native-warehouse-owner-focused-2-20261127`：**199 项通过**，零失败／错误／跳过，169.859 秒，源码无漂移。强制的 WarehouseGUIThreadingTest 7 项、WarehouseGuiSessionTest 3 项、仓库事件安全／PlayerMenuTasks／SiegeEffectTaskLifecycleTest 均实际执行；另含仓库权限矩阵和升级回归。该选择不是 StarCore 全库套件。Java 21、320 MiB heap、192 MiB metaspace、串行 GC 和 `forkCount=0`。

[证据包](verification/warehouse-owner-source-20261127-evidence.zip)：3,824 成员、8,703,063 字节，SHA-256 `049286364f47f94bc82ab3c07ea634d28ffbee82e4fa95f1e12e3a6fbdc83298`。逐成员读取验证，包含两轮日志／XML／命令和最终源码一份，排除 JAR／class／转储。

## 后续工作

共享仓库存取仍需单独处理同一显示物品的并发领取、背包只能接收部分物品时的余量，以及玩家本地槽位与 GUI raw slot 的区别。这些改动正在独立候选验证，不计入上述 199 项通过。远程仓库打开的注册路径、其他旧调度、叙事 Boss 和国家旧临时加成玩法连接仍须继续；十阶段尚未完成。

最新已核验的配对构件仍是 StarCore `6276140`／Chronicle `53562a2`，见[恢复报告](OWNER-RECOVERY-SOURCE-20261127.md)。当前源码真实多人 GUI、物品提交与恢复、第三方插件组合、全栈 Folia、长期负载均需实服验收；不以单测推导支持声明或热卸载安全。
