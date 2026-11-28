# 实际好友服务完整提交

StarCore `617f443ef76660165057c1ecd9ec4cf43ee55fa1` 已推送开发分支。好友发送／接受／拒绝、解除、黑名单、在线状态、清理与单玩家导入统一构建完整候选，恢复原内存后持久提交，成功才发布；SQL 使用完整 expected 比较提交。原恢复辅助入口及兼容 `save*ToDatabase/delete*FromDatabase` 也立即持久化，文件模式全量保存不再空转。

陈旧 SQL authority 或未知提交结果隔离读写；明确成功回滚继续保留旧确认状态。导入校验双方容量、黑名单及请求冲突；新好友清除对应请求和未知联系人，历史超100好友保留但不可继续增长。旧单行好友恢复双向逻辑；未知方向联系人不会变成同意。

`native-friend-service-complete-focused-1-20261128`：**790项相关 verify／69必需套件通过**，零失败、错误、跳过和源码漂移，316.766秒，打包执行。新增8项测试覆盖文件与SQL实际流程、陈旧服务、到期SQL同意、旧辅助API、未知联系人、双方容量、冲突导入和历史超容量。320 MiB heap／192 MiB metaspace、SerialGC、单CPU、forkCount=0，离线跳过JaCoCo／SBOM并保留既有排除；不是StarCore全库回归。

精确构件 **24个串行JVM** 通过旧／schema2文件、SQLite、故障／重开和实际服务未知联系人导入后新邀请接受。配对保留Chronicle214类型／1,866描述符、StarCore3,010类型／35,883描述符，当前290／4,226类；8夹具编译通过。native SHA-256 `c1a0e98941a2b38983b6996604c8d92a4b7758bfd175462b6d529eefdd7c66d1`，original `11595f0dcc4bc8ebb89b3a6adf06bee4822e00f28338a04deb54dd08ab14b2e7`。

证据：[摘要](verification/friend-service-complete-source-20261128-summary.json)、[ZIP](verification/friend-service-complete-source-20261128-evidence.zip)，4,078成员／10,089,188 bytes，SHA-256 `b2aac5863dc367d9bc96bf47cde5c4de0c01cccdc0c862ba8cf3707a69e546b6`，逐成员哈希通过。StarCore CI状态见[原始记录](verification/friend-service-complete-native-ci-20261128.json)。Chronicle前批`fc47f82`两条CI十步成功，见[记录](verification/friend-complete-sql-chronicle-ci-success-20261128.json)；产品仍为`53562a2`及其1,195项完整verify。

全量社交导入仍需整批预校验及跨服务恢复，周期／表情／聊天、活动／联盟／文化和其他阶段继续源码开发。MySQL、真实多人跨区域、长期混合写入及硬件断电仍需独立验收。没有启动Minecraft／Paper／Folia；十阶段未完成。
