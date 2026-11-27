# 社会模拟命令：原玩家回调与异步结果

StarCore `0353c0a8f118a66b7bfb5ef7faef51482f7e862e` 已推送开发分支。`/socialsim` 的玩家入口、查名和回复回到各自原玩家 owner，关系提交／声誉历史查询使用有界 I/O，活动创建按实际 future 完成反馈。只做源码、单元测试和离线副本验证，没有启动 Minecraft／Paper／Folia，没有部署或合并主分支。十阶段尚未完成。

## 行为

新增 `SocialCommandTasks`，每个命令实例拥有独立玩家任务与最多 256 个 I/O 准入任务。玩家参数先复制，入口复核原会话；查名在目标 owner 采集，失效时显示 UUID 前八位，回复不改投同 UUID 的重连玩家。排队操作在关闭时异常完成；已经开始执行的操作保留真实结果，关闭后不再发送回复。重复调度回调和迟到调度失败不会重复执行业务，完成 future 在锁外进行，避免与玩家任务锁反向等待。不承诺取消已开始的 JDBC。

声誉排行榜显示真实四维前 N 名，N 严格限制 1–20，默认 10；原生 `getTopProfiles` 返回不可变稳定排序列表，并列按 UUID 排序，上限 100，保留旧 `getTopPlayer` 入口。新增接口有默认实现，第三方旧实现默认只提供原来的第一名。历史使用保留同毫秒条目的列表 API，在 I/O 执行后回原玩家。

关系设置拒绝自己，先查目标原会话名字，再在 I/O 执行持久修改；只有实际完成才回复成功。活动异步创建不再因完成线程不是主线程而静默丢弃反馈；失败显示“未能确认操作结果，请查询当前状态后再试”，不宣称必定回滚。八卦正文和活动名保留多词，大小写使用 Locale.ROOT，补全按前缀和别名过滤。

模块实际注册命令时记录关闭动作；模块失活后不能新建命令任务。旧 `SocialCommand` 入口、联盟创建的持久成功语义及其他服务另行处理，不能把本批视为所有社交命令完成。

## 验证

`run/native-socialsim-command-focused-1-20261128`：**208 项相关 verify**，零失败／错误／跳过，22 个必需套件齐全，源码无漂移，格式及打包通过。新增九项命令行为测试、一项实际模块注册／停用测试，另扩展声誉排序契约。覆盖原 owner 查名／回复、非法人数、异步成功／失败、重连与关闭、重复调度、同毫秒历史、已准入真实结果及准入上限。

Java 21、320 MiB heap、192 MiB metaspace、Serial GC、单处理器、forkCount=0，验证 219.937 秒；JaCoCo 跳过，离线 SBOM 未重新生成。不是 StarCore 全库验证。

`run/socialsim-command-pair-artifacts-1-20261128`：公开 API、项目类／资源、源码快照、sources JAR 和 **8 个编译夹具**通过，保留 Chronicle 214 个公开类型／1,866 个描述符、StarCore 3,010 个公开类型／35,883 个描述符。

`run/socialsim-command-reputation-offline-1-20261128`：新 JAR 的声誉副本 **12 个串行 JVM／7 项对照**通过。真实冻结旧 JAR 写入、新 JAR 升级与两次独立重开、陈旧档案／缺行／历史失败及坏数据拒绝均有原始日志；旧基线来自修复声誉之前的影响力检查点。只实际验证 SQLite，没有启动服务器。

| 冻结构件 | SHA-256 |
| --- | --- |
| Chronicle | `3561a787cf02e343778d22e16bbfc05b15ad12e5e1c68f969cb63c818e2726fc` |
| StarCore shaded | `3131fc091a12feebade3f711250fd6c8100256bf1df3d729764c6e4df993159b` |
| StarCore original | `0fee9c31abdd2622762f14c66cef4c75b9b80caa11ef044281b6f48ed9f6773d` |

Chronicle 产品保持 `53562a2`／1,195 项完整 verify。[证据 ZIP](verification/socialsim-command-source-20261128-evidence.zip)和[摘要](verification/socialsim-command-source-20261128-summary.json)含完整源码、XML／日志、探针和副本：3,858 成员，9,543,789 字节，ZIP SHA-256 `0ebc4eb3d9e115ab7b7a3441c7e51ad41c81500f490d0459bab62d295102ed6c`，全部成员逐一读取核验。JAR／class 未上传 Git。

前序关系检查点的 CI 已另行核对并归档：Chronicle `268c55c` run `46080706814` 成功；StarCore `9b65b9e` run `46080613411` 因账户付款／额度限制未启动，四个检查 runner=0、零步骤，发布 skipped。见[原始 CI 结果](verification/social-relationships-ci-20261128.json)。不把前序 CI 当成本批 CI。

## 剩余边界

社交监听器、旧 SocialCommand、GossipVerification、联盟／活动／文化、另一关系包装／推荐、领地收益与经验配置、政策／租赁／军队天气、兼容回调、叙事 Boss 及旧国家加成仍有源码待办。

MySQL、长历史负载、实际多人 GUI、真实重启、第三方组合、全栈 Folia 仍需实服或相应环境验收。不设置 `folia-supported:true`，不认证在线热卸载、跨系统严格一次，不沿用其他 JAR 的实服或离线证明。
