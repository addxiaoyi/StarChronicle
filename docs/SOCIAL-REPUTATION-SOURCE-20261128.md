# 2026-11-28 声誉事务、恢复与回调检查点

StarCore `ba2023f1e9d2feae95af9c09c501c134472285ae` 完成声誉档案／历史原子提交、严格恢复、正确等级选择、原玩家通知与停用清理。只做源码、单元测试和离线副本验证，没有启动 Minecraft／Paper／Folia，没有部署或合并主分支。关系网络、社交入口和其他阶段仍有源码待办，十阶段未完成。

## 数据与接口

保留既有 `player_reputation` 与 `reputation_history` 表和列。恢复在同一 SERIALIZABLE 事务读取全部档案并逐行校验历史；无效整数、UUID、枚举或负时间拒绝整个服务，不丢弃坏行后继续空库运行。旧 overall 列可能没有更新，展示值仍按四维相加，加载不重写旧行；写入比较完整原始档案（包括原 overall），陈旧实例拒绝覆盖。档案更新与历史插入同事务，提交／连接关闭失败后隔离，独立重开确认真实状态，不盲目重试不确定提交。MySQL 路径使用行锁，但本批只实际验证 SQLite。

等级从高到低匹配已达阈值，修复非负声誉总先命中堕落者的问题。四维公开快照不可变；加分以 long 中间计算，维度限制 [-1000,1000]，历史及事件记录实际应用增量，时间按玩家单调增加。自定义奖励说明进入 SQL 历史。

新增默认列表接口 `getHistoryEntries`，原生实现按 timestamp DESC、id DESC 返回，保留同毫秒多条旧历史；两个命令入口已改用列表。旧 `getHistory` Map 签名保留，相同时间只显示最新一条，不能视作无损接口。单次最多返回 1000 项，SQL 原历史不删除；明确 null 数据库的内存模式仅保留每人最近 1000 项。排名取已加载和本服务提交的档案，外部 writer 改值不会自动刷新查询缓存；写入重读会拒绝陈旧档案。

社会模拟模块不再捕获核心服务构造异常并重建 null 数据库服务。明确关闭数据库配置仍使用内存模式；初始化失败进入既有清理路径，已创建服务关闭，命令尚未暴露。关系网络等服务内部吞恢复错误的问题尚属下一批待办。

## 调度与停用

四种奖励在异步提交前捕获原玩家与原受众，名字在来源玩家 owner 采集，消息分别回每位原玩家 owner；重连不改投新会话。UUID 声誉事件通过 GlobalRegionScheduler 发布，回调只执行一次，关闭后旧任务失效，迟到句柄取消，监听器异常不把已提交事务报告为失败。

写入使用单线程、有界 4096 队列，恢复成功后才创建执行器。关闭停止新请求和通知，等待至多 5 秒；尚未开始的任务 future 明确异常。已准入事务在超时后收到中断，但最终退出时间由驱动决定，不能把未知提交伪报为取消；不认证在线热卸载或持久严格一次。

## 验证

`run/native-social-reputation-focused-2-20261128`：**176 项相关 verify**，零失败／错误／跳过，19 个必需套件齐全，源码无漂移，格式与打包通过。包括新声誉契约、SQLite 事务与恢复、原玩家／事件生命周期、实际模块构造失败清理及前序社交／天气回归；不是 StarCore 全库验证。首次编译的同名枚举导入错误已修复，原失败日志保留于证据包。

Java 21、320 MiB heap、192 MiB metaspace、Serial GC、单处理器、forkCount=0，Maven 223.875 秒；JaCoCo 跳过，离线 SBOM 未重新生成。

`run/social-reputation-offline-1-20261128`：**12 个串行 JVM** 用冻结旧 JAR 写入真实 SQLite 档案／历史，新 JAR 升级和两次独立重开；旧历史所有列原值保留。另验证外部改值、删行、历史插入失败回滚，以及坏档案、未来历史原因、负时间恢复拒绝；拒绝恢复后三份数据库字节不变。模拟调度接口，没有启动服务器。

`run/social-reputation-pair-artifacts-1-20261128`：配对公开 API、项目类／资源、精确源码快照、sources JAR 与 8 个编译夹具通过。Chronicle 214 个公开类型／1,866 个描述符，StarCore 3,010 个公开类型／35,883 个描述符。

| `run/social-reputation-frozen-pair-1-20261128` | SHA-256 |
| --- | --- |
| Chronicle | `3561a787cf02e343778d22e16bbfc05b15ad12e5e1c68f969cb63c818e2726fc` |
| StarCore shaded | `77b369e375a7ef535890a90211678664bde95a4f61b32bd708994deaf88562e2` |
| StarCore original | `89a1f642f0cd55ff03cfc066354453c3c98f33750699393a65eb3567584591ae` |

Chronicle 产品保持 `53562a2`／1,195 项完整 verify。[完整证据 ZIP](verification/social-reputation-source-20261128-evidence.zip)与[逐成员摘要](verification/social-reputation-source-20261128-summary.json)含源码、日志／XML、失败记录、探针和数据库副本：3,849 成员、9,702,422 字节，ZIP SHA-256 `4f74bd4d117db4232f303490120c89665060c7ddad53fa5a8e7f912c7cd31756`。全部成员读取核验，JAR／class 不上传 Git。

## 剩余范围

GitHub 原始结果见 [social-reputation-ci-20261128.json](verification/social-reputation-ci-20261128.json)：Chronicle `3a84117c360e66934fd3dfdc5bfb04547c2538f5` 的 [46078643692](https://github.com/addxiaoyi/StarChronicle/actions/runs/46078643692) 全部步骤通过；StarCore `ba2023f1e9d2feae95af9c09c501c134472285ae` 的 [46078584826](https://github.com/addxiaoyi/starcore/actions/runs/46078584826) 四个检查 runner 为 0、无执行步骤，付款／额度注解阻止任务启动，发布跳过，不能记为远程验证通过。

关系网络事务／恢复，社交命令和监听器，联盟／活动／文化服务，领地收益消费与经验配置，政策／租赁／军队天气、兼容回调、叙事 Boss 和旧国家临时加成继续推进。当前构件完整多人玩法、真实重启、数据库部署、第三方组合、全栈 Folia 与长期负载仍需实服验收。不设置 `folia-supported:true`，不复用旧构件实服或其他批次离线结果。
