# 2026-11-28 关系网络事务、旧行与历史恢复检查点

StarCore `9b65b9eb78cdac6efc4d1bca8bbbdb52c019e679` 已推送开发分支，完成关系网络原子提交、严格恢复、反向关系归一、实际变化历史与关闭保护。只做源码、单元测试及离线副本验证，没有启动 Minecraft／Paper／Folia，没有部署或合并主分支。十阶段尚未完成。

## 关系与历史

保留既有 `relationships`／`relationship_history` 表列。新关系按无向玩家对归一，更新旧行保留原编号、原 player1／player2 方向及创建时间，避免反向操作再插一行或 SQLite REPLACE 换掉编号。旧库若已经同时含 A→B 和 B→A，恢复明确拒绝，两个原行保留，不擅自选一条或合并数值；自己与自己的关系也拒绝。

加载以同一 SERIALIZABLE 事务读取完整关系和历史，严格校验整数、UUID、类型、动作及时间，无效行不再被吞掉后以部分／空数据继续。写入事务重读并比较完整旧行，包括 ID、方向与时间；外部改值、删行或反向重复行导致隔离。关系变化及单条历史共同提交，提交／关闭确认不确定时不盲目重放。SQL 失败不发布缓存。MySQL 路径使用行锁，本批只实际运行 SQLite。

set／increase／decrease 每次只生成一条实际应用增量，限制 [-100,100]，long 中间运算防止 int 溢出；删除保留 SQL 审计历史。历史按 timestamp DESC、id DESC，连续操作、衰减、删除后独立重开再建立均维持时间顺序。提交事务内读取最终历史快照，重建后立即显示既有 SQL 历史。每个活动关系只发布最近 100 项，SQL 全部历史保留；移除后的内存模式没有持久历史保证。

关系及历史列表不可变，查询与修改串行维护对称索引；重复移除不存在关系不会空指针。内存模式不再误调用 null 数据库。好友并列按 UUID 稳定排序，图查询与影响力计算使用同一服务快照，影响力中间累加用 long 并限制 int 范围。

`applyDecay(deltaMillis)` 保留原先每毫秒 0.001 的系数与只衰减正关系的行为，长间隔可降至 0，修复衰减量超过当前值反而完全不变；负间隔拒绝。衰减不更新真实最后互动时间。该 API 仍由调用者触发，本批没有新增自动周期；每对关系分别提交，没有全图共同事务、持久周期编号或断电严格一次保证。

新增默认 `shutdown()` 保持旧接口实现兼容，原生实现停止后续修改，并等待同锁上的同步操作退出；社会模拟模块停用和构造失败清理会调用它。没有新增强制 JDBC 取消或在线热卸载保证。

## 验证

`run/native-social-relationships-focused-3-20261128`：**198 项相关 verify**，零失败／错误／跳过，21 个必需套件齐全，源码无漂移，格式与打包通过。新增关系网络 4 项、SQLite 恢复／事务 18 项，连同前序声誉、事件、影响力、传播、模块及天气回归运行；不是 StarCore 全库验证。前两次 196／198 项检查点独立保留，只有第三次源码和 JAR 属于本报告最终构件。

Java 21、320 MiB heap、192 MiB metaspace、Serial GC、单处理器、forkCount=0，Maven 213.969 秒；JaCoCo 跳过，离线 SBOM 没有重新生成。

`run/social-relationships-offline-1-20261128` 的 **13 个串行 JVM／8 项对照**通过：冻结旧 JAR 写入反向关系及三条原历史，新 JAR 反向更新、衰减和两次独立重开；原 ID、方向、创建时间及历史所有列保持。另验证陈旧改值、缺行、历史失败回滚、坏强度、未知历史动作、负时间和双向重复行拒绝。四份拒绝恢复的数据库字节不变。调度／数据库服务接口为离线适配，没有启动服务器。

`run/social-relationships-pair-artifacts-1-20261128`：配对公开 API、项目类／资源、精确源码快照、sources JAR 及 8 个编译夹具通过。Chronicle 214 个公开类型／1,866 个描述符，StarCore 3,010 个公开类型／35,883 个描述符。

| `run/social-relationships-frozen-pair-1-20261128` | SHA-256 |
| --- | --- |
| Chronicle | `3561a787cf02e343778d22e16bbfc05b15ad12e5e1c68f969cb63c818e2726fc` |
| StarCore shaded | `7195db1f13e0358c03bc2341a53fcd68650b89ccfcb16773b5a543e99f83f624` |
| StarCore original | `5f241a76ebc4e165a30c1c38f2f54329a39af604d94cb5181e83af5f652e9de8` |

Chronicle 产品保持 `53562a2`／1,195 项完整 verify。[证据 ZIP](verification/social-relationships-source-20261128-evidence.zip)与[逐成员摘要](verification/social-relationships-source-20261128-summary.json)含源码、最终日志／XML、第一轮记录、探针和数据库副本：3,859 成员，9,732,946 字节，ZIP SHA-256 `e2fe5db88e2d7c565b9e6338ea91ca48924c575e893c4c07df140f14d4b94256`。全部成员逐一读取核验，JAR／class 未上传 Git。

## 剩余边界

查询反映加载及本实例提交的关系，外部 writer 不会自动刷新缓存；写入会重读拒绝陈旧状态。历史恢复全表校验、每次提交的历史查询和长期负载还需测量，不能用这批小型副本宣称大库性能通过。只验证 SQLite，MySQL、实际多人玩法、真实重启、第三方组合和全栈 Folia 仍需实际验收。

社交命令排行榜和异步完成反馈、监听器跨玩家调用、GossipVerification、联盟／活动／文化等服务，领地收益消费与经验配置，政策／租赁／军队天气、兼容回调、叙事 Boss 和旧国家加成仍有源码待办。既有 SocialModule 的另一关系包装／推荐接线也继续审查。没有设置 `folia-supported:true`，不把旧构件的实服或其他离线记录套到新 JAR。
