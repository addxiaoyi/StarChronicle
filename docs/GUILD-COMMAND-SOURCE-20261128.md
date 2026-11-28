# 公会命令：管理入口、原玩家回调与有界 I/O

StarCore `01f7e24d1d3beaff761421cc0f1e9e8cd4b4b1c5` 已推送开发分支。本批源码、相关测试和配对核验通过。实际 `/guild` 及既有别名接入解散意图与 `confirm`、会长转让、`officer|member` 职位任免。所有服务读取与持久操作进入共享有界 I/O 队列，变更前在服务锁内重检当前状态和权限。收发消息及在线查名在原玩家 owner 执行；排队操作开始前拒绝已经退役的发送者会话，关闭取消尚未准入的工作。已经准入的持久操作可完成，通知失败不撤销提交，也不盲目重试。

所有 boolean 结果均被检查；无变化不能报告成功或通知目标。服务新增 `Rejected`（仍是 `IllegalStateException` 子类）表示明确业务拒绝；未开始写入的容量、权限、邀请与确认错误显示具体原因，存储／未知结果只提示重新查询当前状态。模块关闭按原 `PluginCommand` 实例恢复前任 executor／completer，保留后来替换的绑定。

公会列表、排行、邀请和成员每页 20 项；详情支持指定公会名／完整 UUID，`me` 选择自己的公会。交互按钮携带公会 UUID，避免含空格的导入名称破坏命令参数；邀请显示接受和拒绝入口，只查询所选页的邀请者／成员名字。排行保留等级、经验、创建时间与 ID 的稳定顺序。玩家目标支持在线姓名、已缓存离线身份或完整 UUID，不创建离线档案。补全使用玩家名字目录和最多 200 个异步公会提示，不能作为授权依据。公会查询统一使用与完整状态重名验证相同的 `Locale.ROOT` 小写键，消除特殊 Unicode 名称歧义。

`native-guild-command-focused-2-20261128`：**699 项相关 verify、57 必需套件通过**，零失败／错误／跳过，无源码漂移，281.594 秒完成构件与快照。320 MiB heap、192 MiB metaspace、SerialGC、单 CPU、forkCount=0，离线跳过 JaCoCo／SBOM，保留其他既有排除；不是 StarCore 全库回归。新增 13 个命令用例与 2 个模块绑定用例覆盖真实创建／邀请／接受／任免／转让／解散流程、false／权限／持久失败、原会话、关闭、分页和补全。

首轮 699 项中的 1 failure／1 error 完整保留：未匹配邀请的 `orElseThrow` 未转换为业务拒绝，以及测试设置里嵌套读取模拟玩家导致未完成 stubbing。前者源码已修正，后者改用预先确定的 UUID，第二轮同套件通过。

精确新构件 **19 个串行 JVM** 通过旧 YAML／SQLite 迁移、邀请／经验提交后 halt 与重开、接受邀请和解散后重开、五种文件拒绝及旧字节备份对照。配对保留 Chronicle 214 类型／1,866 个、StarCore 3,010 类型／35,883 个公开／受保护描述符，读取 290／4,206 个当前类文件；8 个夹具仅编译通过。native JAR SHA-256 `b5211721bbb14e733fa55213955e9e7879cfacda3c2d42b0ab71bde0b2e270a4`，original `324d33a1eb6e7e5312c89c55f8b18fdedda292ea8ffb843a1216797c6fe572fd`。Chronicle 产品源码仍为 `53562a2`，沿用 1,195 项完整 verify 及原构件证据。

证据见[摘要](verification/guild-command-source-20261128-summary.json)和 [ZIP](verification/guild-command-source-20261128-evidence.zip)：7,965 成员、18,842,511 bytes，SHA-256 `5fb84a0ea9faff8d2b35ee97350187ca0cc5bd2a3262604863f378b4c140f0e5`，逐成员哈希通过。StarCore [CI 46123584232](https://github.com/addxiaoyi/starcore/actions/runs/46123584232) 账户付款／额度限制详见[原始记录](verification/guild-command-native-ci-20261128.json)，不称 CI 通过。Chronicle 前批 `daacbbc` CI 十步成功，见[记录](verification/guild-state-chronicle-ci-success-20261128.json)。

仍有源码待办：全量社交导入导出文件范围／跨服务提交、周期保存、表情与聊天入口、活动／联盟／文化，以及其他阶段。解散确认由服务按公会和当前会长记录，转让／重载／关闭会清除；不代表客户端已读或持久投递凭据。未启动 Minecraft／Paper／Folia，真实多人跨区域、断线时序、当前构件服务器重启与 MySQL 等仍需实服验收；十阶段未完成。
