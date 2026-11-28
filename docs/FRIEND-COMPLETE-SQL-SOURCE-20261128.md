# 好友 SQL 完整比较提交

StarCore `24abbe35ef33fe78ab4a946fe240eef922bb00f6` 已推送开发分支。新增完整好友逻辑状态读取、事务内 expected 比较和共享 SQL 写入闸门；原公开 SQL 写接口也进入同一闸门。陈旧候选不能覆盖其他合作写入者的变更，commit 响应未知独立报告。事务失败回滚所有变更和修订号，提交前重新读取校验候选。

好友／请求／黑名单行严格检查原 ID、UUID、整数时间、重复行、在线布尔原始类型和旧接收者别名。差量提交保留未变行 ID、时间及扩展列；历史单行好友保持原有双向逻辑意义，无变化保存不补写反向历史行。未知方向旧联系人独立存于 `starcore_friend_legacy_contacts`，不发明时间或变成已收到的同意。旧已处理／到期请求保留原行；只有明确重新发送才更新同一行的请求时间／状态并移除对应未知联系人。

完整候选拒绝好友／有效请求与黑名单冲突，好友上限不能增长超过100或原有超容量。旧兼容快照保存继续合并在线状态；新增完整 expected API 可以明确删除快照中被移除的状态，并保留未变状态原时间／扩展列。读写标记缺失、版本不支持或修订号耗尽拒绝，不重置成空存储。共享闸门只协调接入它的写入者，不代表全局隔绝外部直写数据库。

`native-friend-complete-sql-focused-1-20261128`：**782项相关 verify／68必需套件通过**，零失败、错误、跳过和源码漂移，321.171秒。新增12项完整SQL测试覆盖原行保留、未知联系人、陈旧写入、事务中途失败、实际提交后丢失响应、类型／重复／别名错误、到期／已处理请求、并发写入、标记拒绝、单行历史、在线状态差量及候选约束。320 MiB heap／192 MiB metaspace、SerialGC、单CPU、forkCount=0，离线跳过JaCoCo／SBOM并保留既有排除；不是StarCore全库回归。

精确构件 **16个串行JVM** 通过真实旧JAR数据库写入、完整提交后halt／两次重开、失败回滚后的独立重开、旧API并发变更导致陈旧提交拒绝、7种坏SQL副本逐字节拒绝以及旧API重新发送后的未知联系人处理。配对保留Chronicle214类型／1,866描述符、StarCore3,010类型／35,883描述符，当前290／4,226类；8夹具仅编译通过。native SHA-256 `8f7aabdaef1351b73cd11007631aaf6d408c9718c8e4dc67be9a161935925106`，original `9bd7ae7e0694281a50175c9dff41669a035016eabb6c15f357332a8e88990194`。

证据：[摘要](verification/friend-complete-sql-source-20261128-summary.json)、[ZIP](verification/friend-complete-sql-source-20261128-evidence.zip)，4,016成员／10,072,254 bytes，SHA-256 `736c34d3399f88f6a245d1a5902d93d86f239e983effaa21d45a946982ad0d7a`，逐成员哈希通过。StarCore两条CI因账户付款／额度限制未启动，见[原始记录](verification/friend-complete-sql-native-ci-20261128.json)。Chronicle前批`c9f1dbd`两条CI十步成功，见[记录](verification/friend-checkpoint-chronicle-ci-success-20261128.json)；产品仍为`53562a2`及其1,195项完整verify。

实际FriendService操作／旧辅助入口尚未在本提交接入完整比较提交，正在下一批源码开发；全量社交导入／跨服务恢复、周期／表情／聊天、活动／联盟／文化及其他阶段也未完成。MySQL、真实多人跨区域和长期混合写入仍需实服验收。没有启动Minecraft／Paper／Folia；十阶段未完成。
