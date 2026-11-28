# 派对命令：原玩家、准确结果与邀请版本

StarCore `364e56c7a6922b71b0ef6feee0b6eb8d7b13ffba` 已推送开发分支。实际 `/party` 的创建、邀请、接受／拒绝、踢出、离开、转让、解散、友军伤害和经验共享设置统一进入有界 I/O。变更前在服务锁内重检当前权限；消息与名字查询使用原玩家 owner，关闭和重连拒绝尚未准入的旧会话任务。已经准入的持久操作可以完成，通知失败不撤销提交或自动重发。

所有 boolean 返回均被检查，未生效不能报成功或通知目标。明确的 `PartyService.Rejected` 显示业务原因，持久化异常／未知结果提示重新查询状态。成员与邀请每页 20 条，只查询当前页名字；离线目标使用缓存名字或完整 UUID，不创建离线档案。邀请按钮包含邀请者、原派对与原时间，执行时同时核验当前接收者，旧页面不能消费重发邀请。无法证明归属的旧邀请保留原时间、提示重新邀请且无接受按钮，仍可精确拒绝。补全不读取派对存储或外玩家实体名字。模块按原命令实例恢复前任 executor／completer，并保留后来的替换。

`native-party-command-focused-2-20261128`：**759 项相关 verify／66 个必需套件通过**，零失败、错误、跳过和源码漂移，315.031 秒。使用 320 MiB heap／192 MiB metaspace、SerialGC、单 CPU、forkCount=0，离线跳过 JaCoCo／SBOM，保留既有排除；不是 StarCore 全库回归。新增 12 个命令用例和 2 个实际模块绑定用例覆盖权限、原会话／关闭、错误／false、旧邀请 token、分页、离线身份和补全。第一轮因测试辅助方法重复而编译失败，零测试执行；原始快照和日志保留。

精确构件通过 **19 个串行 JVM** 的旧 YAML／SQLite 升级、halt／重开、邀请接受、解散、五种文件拒绝与原始备份对照。一次探针调用路径写为 `surefire-reports` 而非快照的 `surefire`，尚未启动 JVM，独立保留 `NOT_STARTED` 记录；第二次完整通过。配对保留 Chronicle 214 类型／1,866 描述符和 StarCore 3,010 类型／35,883 描述符，读取当前 290／4,223 类，8 个夹具仅编译通过。native SHA-256 `4c5f967cc7290c698c59edbf2ea96e0bc9bae97ce07a80bc6ef67e5974b23ea7`；original `63633f4d090ce9d3cc76e014e458f9192cdd0393577d976a5554069a44556b94`。Chronicle 产品仍为 `53562a2`，沿用其 1,195 项完整 verify 和构件证据。

证据：[摘要](verification/party-command-source-20261128-summary.json)、[ZIP](verification/party-command-source-20261128-evidence.zip)，7,886 成员／18,745,673 bytes，SHA-256 `027901fdece86a0da022c979d221d0e2df189283d370779e3124486a5f1fe4e8`，成员哈希全部一致。StarCore 两条 CI 因账户付款／额度限制未启动（四个检查均零 runner、零步骤），原始状态见[记录](verification/party-command-native-ci-20261128.json)，不能当作本地验证结果。Chronicle 前批 `964d066` 两条 CI 均十步成功，见[记录](verification/party-state-chronicle-ci-success-20261128.json)。

全量社交导入／跨服务恢复、好友完整存储基础、周期／表情／聊天、活动／联盟／文化及其余阶段仍是源码工作，继续推进。未启动 Minecraft／Paper／Folia，当前构件多人跨区域、断线通知时序、服务器重启及 MySQL 仍需实服验收；十阶段未完成。
