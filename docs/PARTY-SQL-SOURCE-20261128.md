# 派对 SQL 完整状态与原行身份保留

StarCore `cc6d8cc1f6c9887e3ab020659f861d250ae18c8d` 已推送开发分支。本批新增不可变 `PartyState` 和实际 `PartySqlStore`，旧派对 SQL API 委托同一事务。完整读取后先校验唯一成员归属、队长成员关系、原创建时间、数值／布尔类型、容量、成员和邀请行及绑定；差量提交保留未改变成员／邀请的行 ID、加入／邀请时间、额外列与派对创建时间。接受邀请和成员加入可以在同一事务提交，失败回滚全部变化，提交回包丢失明确报告结果未知。

共享 SQL 写闸门串行合作的新 writer，完整逻辑 expected-state 比较拒绝陈旧覆盖。它不是外部旧 writer 的锁，也不认证混合版本同时写入。旧 inviter／target／时间无法证明原始派对，旧 `party_uuid` 曾写成目标玩家 UUID，因此新绑定表迁移为 `NULL` 并保留原时间，不能推断为邀请者当前所在派对。当前 SQL 层保留这类证据；实际服务要求重新邀请及文件恢复正在下一批源码开发。

`native-party-sql-focused-5-20261128` 的 **730 项相关 verify、62 必需套件通过**，零失败／错误／跳过，无源码漂移，完整测试与打包证据捕获耗时 411.859 秒。320 MiB heap、192 MiB metaspace、SerialGC、单 CPU、forkCount=0，离线运行并跳过 JaCoCo／SBOM；既有排除仍保留，不是全库回归。第一轮编译发现移除的旧派对事务接口仍被好友事务引用，已改为独立好友事务接口；第二轮 2 failures／1 error、第三轮 1 error 来自需要适配完整读取和差量写入的旧故障夹具，已改为真实连接上的读取中断、缺表、写闸门拒绝与显式解除故障。第四轮因磁盘不足在复制快照时失败，Maven 未启动。各轮原始证据独立保留。

**14 个串行 JVM** 使用真实旧 JAR 建 SQLite 数据，新 JAR 迁移后逐行比较原三表，保留附加列与时间；设置变更、新邀请消费及成员加入提交后直接 halt，再独立重开两次。八个坏副本（未来 schema、非法队长、缺绑定、非整数时间、非法布尔、孤儿成员、变化的邀请时间、非法容量）拒绝读取和写入且原文件字节不变。解散后独立重开保留其他派对。这是 SQL 层验证，不代替实际服务统一发布／YAML 恢复。

配对核验保留 Chronicle 214 类型／1,866 个、StarCore 3,010 类型／35,883 个既有公开／受保护描述符；读取全部 290／4,215 个当前类文件，8 个夹具仅编译通过。native SHA-256 `28eb13630a26427f1c04c7de00bdd2aaaf4c88a33fe5786e346c167e0c8aa537`，original `7245163e80e85546124d7477b58254eb19579d7964b4318c468c15877264409e`。Chronicle 产品仍 `53562a2`／1,195 项完整 verify。摘要与完整证据归档见 [JSON](verification/party-sql-source-20261128-summary.json) 和 [ZIP](verification/party-sql-source-20261128-evidence.zip)：18,464 成员、43,417,265 bytes，SHA-256 `2f21f4e921a337f996825530276d213f7c3f7188c74c456811a1043f14fcb92b`，归档成员逐项核验。

StarCore 当前提交的 push／PR 两条 CI 均因账户付款／额度限制未获得 runner，四项检查零步骤，不能称 CI 通过；结果见[原始记录](verification/party-sql-native-ci-20261128.json)。Chronicle 上批 `cf95051` 的 push／PR 两条 CI 均十步成功，见[记录](verification/social-export-files-chronicle-ci-success-20261128.json)。

实际 PartyService 的完整提交、失败读隔离、YAML 统一检查点、派对导入 ID／时间及原会话活动采样继续源码开发；命令 I/O／绑定、跨服务导入恢复、其他社交与阶段源码仍待继续。没有启动 Minecraft／Paper／Folia，不部署、不合并主分支；MySQL、硬件断电、长时并发及当前构件实服仍待验。十阶段未完成。
