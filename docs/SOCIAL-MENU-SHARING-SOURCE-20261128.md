# 社交菜单共享服务与会话生命周期

StarCore `b3a99deafdd527214b032ae66cedf56ee080cddb` 已推送开发分支。原 SocialModule 在社会模拟模块加载前另建活动／关系服务，同一 SQL 表可能由两套实例恢复和修改；它还把未赋值的关系字段以及空影响力／排行榜服务交给菜单。本批使启用了社会模拟的社交模块等待其恢复完成，再显式连接同一组活动、关系、影响力和排行榜实例；恢复失败时保持不可用页面，不另外创建权威副本。明确关闭社会模拟时，保留一套独立活动服务；启用数据库时保留唯一持久关系网络，明确关闭数据库时使用已有好友适配器。

社会模拟停用先使菜单消费者失效，再关闭其服务。连接按模块实例拥有，旧模块 detach 不会清掉新实例。社交模块停用即使某项保存抛错，也继续取消计时、关闭私聊、清理其余消费者，并按实例注销注册表服务，不删除替代服务。

活动和排行榜监听器跟踪原 Player 对象和本实例创建的菜单。点击动作在下一次原玩家调度执行，复核原菜单；旧页面关闭只删除匹配页面。异步活动创建／加入的成功与失败消息回到原玩家 owner，重连、停用和被更新操作取代的回包不会继续操作界面。创建完成后若玩家已切换视图，只发结果，不重新打开菜单。

书本待办按原 Player 会话保存，关闭清理携带 BookInputSupport 原 token，旧 quit／迟到清理不消费新输入。业务任务关闭与菜单／书本清理使用不同任务生命周期；清理只关仍在显示的原菜单，且保留旧监听器的点击取消直到清理完成。调度拒绝／玩家退休只结束清理，不据此宣称实服热卸载支持。

首轮 `run/native-social-menu-sharing-focused-1-20261128` 实际执行 296 项，0 失败／6 错误／0 跳过：SocialLeaderboardListenerTest 的共享点击夹具在尚未完成 `when(...).thenReturn(...)` 时调用另一个 mock，触发六个 UnfinishedStubbing 错误。该轮未打包，无源码漂移，原日志／XML／源码保留。修正夹具后又补入父菜单捕获引用、独立数据库模式及原书本 token 校验，第二轮 `native-social-menu-sharing-focused-2-20261128` 的 298 项 verify 和打包通过（226.312 秒）；随后发现排队清理之前玩家退出时书本 token 需保留到退出恢复，增加回归后用第三轮精确最终构件再验证。

## 最终验证

`run/native-social-menu-sharing-focused-3-20261128`：299 项相关 verify，31 个必需套件齐全，零失败／错误／跳过，无源码漂移，打包成功。Java 21、320 MiB heap、192 MiB metaspace、Serial GC、单处理器、forkCount=0，289.500 秒；JaCoCo 跳过，离线 SBOM 未重新生成。不是 StarCore 全库回归。

新增六项模块共享／清理测试，七项活动书本与会话回归、四项排行榜生命周期回归和一项异步加入回归；既有库存安全用例继续通过。中间失败与 298 项通过均保留，不计为最终构件的额外通过数。

`run/social-menu-sharing-pair-artifacts-1-20261128`：配对公开 API、项目类资源、sources JAR 与 8 个编译夹具通过，保留 Chronicle 214 类型／1,866 描述符及 StarCore 3,010 类型／35,883 描述符。

冻结 StarCore shaded JAR SHA-256 `32494f23722bd46b7899d8275b7eaa3bfd7586c163f42a63bba8c56307652b7a`；original JAR `402b8e852376792d73d506904b026768934deb4796895542ec4048cfd9d632b9`。Chronicle 产品保持 `53562a2`／1,195 项完整 verify，JAR `3561a787cf02e343778d22e16bbfc05b15ad12e5e1c68f969cb63c818e2726fc`。

[证据 ZIP](verification/social-menu-sharing-source-20261128-evidence.zip)与[逐成员摘要](verification/social-menu-sharing-source-20261128-summary.json)：11,549 成员，26,759,828 bytes，SHA-256 `cab2622ad450737de095c98befdcd96eec173da6be24910e39588cb2e9e9bc82`，成员逐一读取核验。JAR／class 未提交 Git。本批没有持久格式变化，不重复冒领旧数据库副本结论。

前序路由 CI 已归档：StarCore `7daaa42` run `46090518289` 因付款／额度限制未启动，四项检查零步骤；Chronicle `fd77a80` run `46090609448` 十步成功。当前 StarCore `b3a99de` run `46092683200` 也因付款／额度限制未启动，四项检查 runner=0、零步骤；Chronicle `1904113` run `46092805187` 十步成功，见[本检查点原始 CI](verification/social-menu-sharing-ci-20261128.json)。

## 剩余源码与实服边界

本批未修改活动持久格式，也未完成 SocialActivityService 的严格恢复、生命周期和事务工作。好友适配器反射写入、请求方向、推荐评分／缓存／实际入口，以及父社交菜单、活动／排行榜 GUI 内部跨玩家查名和同步数据库读取仍需继续。InfluenceLeaderboardService 周期、缓存与持久恢复也仍未全面修复。本次接线和回调测试不证明这些内部链路已支持 Folia。

只做源码、单元测试和离线构件验证，没有启动 Minecraft／Paper／Folia，没有部署或合并主分支。多人 GUI、真实重启、MySQL、长期负载、第三方组合和全栈 Folia 均待实服验收；十阶段尚未完成。旧构件数据库／实服证据仍属于原构件，不套用到本 JAR。
