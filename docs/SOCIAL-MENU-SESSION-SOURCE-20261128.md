# 社交菜单页面快照、异步提交与原窗口生命周期

旧父社交菜单按 UUID 存放可变页面状态，关闭任意旧窗口都会删除菜单记录；点击从物品 Lore 重新解析目标，在事件线程直接持久写入，并忽略接受／拒绝的实际布尔结果。名单在有限槽位截断，信息和返回按钮还会覆盖名单；查询玩家名字会调用 Bukkit 离线资料接口。StarCore 导航入口直接构造 GUI，绕过监听器的当前菜单跟踪。

本批查询通过有界 I/O 取得页面数据，名单稳定 UUID 排序，每页 21 人，导航占独立槽位。只有可见页解析名字，在线名字回对应原玩家 owner；离线使用已知资料或完整 UUID。主菜单从已取得的统计快照渲染；收到、发出和方向不明的旧请求分开显示。旧方向记录不能点击接受。

页面保存固定目标和操作，点击不再信任物品 Lore。窗口动作离开当前点击事件后回原玩家 owner；好友、公会／派对邀请和取消私聊屏蔽在 I/O 队列执行，原窗口关闭、被新页面取代、断线重连或停用会取消尚未开始的提交。同页提交防重复，真实 false 不显示成功；已开始的存储调用不会强制撤销。旧窗口关闭、旧连接退出和旧查询回包不清除新会话。

导航入口改经 SocialModule 与监听器；旧公开 SocialMenuGui 构造入口仍可在当前原玩家窗口安全接管主菜单动作。旧构造器仍同步读取内存摘要，实际加载入口均先走 I/O。停用使捕获的旧监听器失效，尽力回原 owner 关闭仍属于它的窗口，不关闭替代界面。

私聊历史队列本来通过 `addFirst` 保留最新消息，旧查询却再次反转，导致最近 21 条显示旧消息。本批按插入顺序获取受锁保护的快照，先筛选会话再限制条数；保留 `limit <= 0` 返回全部、返回结果可修改且不改变服务状态的语义，未更换文件格式。旧公开监听器首次点击可延迟初始化；初始化与停用使用同一锁，停用后不再重新打开。

## 验证

第一轮 `native-social-menu-session-focused-1-20261128` 执行 462 项，零断言失败／9 个夹具错误／零跳过；没有打包、没有源码漂移。错误均为 SocialMenuSessionTest 的点击夹具在 Mockito thenReturn 参数里嵌套调用另一 mock。原始源码、日志和 XML 保留。修正为预先取出 InventoryView 与当前 Inventory，并增加旧公开入口、同连接旧 close、底部背包／键盘操作和所有主菜单路由的行为检查。

第二轮执行 **465 项**、42 必需套件，零失败／错误／跳过，verify 打包通过。补齐历史顺序和旧监听器首次点击后，第三轮 `native-social-menu-session-focused-3-20261128` 执行 **470 项**、44 必需套件，零失败／错误／跳过，完整 verify 打包通过，239.469 秒。两轮均无源码漂移。测试包含交错对话筛选后取最新 21 条、100 条上限、正常关闭后重新初始化文件恢复；不声称强制同毫秒或独立 JVM 恢复已经验证。

StarCore 源码提交 `ca1dce2`。最终 JAR SHA-256 为 `68826a842dce386402778b90d98dd6580029acb98e3863c070ffe6e9a7259e99`；未着色原始 JAR 为 `25f50441817064430a6de8a2b4c29c36b6defbcca37fcc593a68685f257d290b`。Chronicle 产品保持 `53562a2`／1,195 项完整 verify 的 `3561a787cf02e343778d22e16bbfc05b15ad12e5e1c68f969cb63c818e2726fc`，本批只更新文档和证据。

配对构件的 214 个 Chronicle／3,010 个 StarCore 基线公开类型及原声明保持，290／4,189 个当前项目类、资源与 Chronicle sources JAR 核对通过，八个夹具仅编译通过。冻结目录为 `run/social-menu-session-frozen-pair-1-20261128`。[证据 ZIP](verification/social-menu-session-source-20261128-evidence.zip)共 11,664 成员、27,178,153 字节，SHA-256 `4bf049d3199abec653bebb434bd84e19c51fe52978972dba3f687dfe0916be2a`；逐成员摘要已核验，见[机器摘要](verification/social-menu-session-source-20261128-summary.json)。含三轮原始源码、日志和测试 XML，前次失败未覆盖。

验证使用一个 320 MiB heap／192 MiB metaspace JVM、SerialGC、单 CPU、forkCount=0，保留现有 Maven 排除。470 项为相关回归，不是 StarCore 全量测试；离线未重建 SBOM，也没有 JaCoCo 覆盖率结果。上一批 Chronicle `83983e4` 的 [CI 运行](https://github.com/addxiaoyi/StarChronicle/actions/runs/46097941452)已核实 verify 十步成功，该结果只属于该提交。

StarCore `ca1dce2` 已推送开发分支。[CI 运行 46099259052](https://github.com/addxiaoyi/starcore/actions/runs/46099259052)四项检查均无 runner／无执行步骤，原始 annotation 明确为账户付款失败或支出额度限制，不能计为测试通过或代码测试失败。[CI 原始记录](verification/social-menu-session-ci-20261128.json)同时保留上一批 Chronicle 成功结果。

## 尚未覆盖

这里只保证菜单的页面选择、I/O 路由和反馈契约。公会及其他服务的持久原子性、私聊文件保存／恢复隔离、全量社交导入导出、周期任务与其他社交调用链须分别完成验证。整体图的复制／排序仍在内存中，不代表大规模负载通过。旧 GUI 同步构造器用于源码兼容，不能视为所有外部插件已经移植。

只做源码、单元测试和离线文件副本验证；未启动 Minecraft／Paper／Folia，未部署或合并主分支。真实多人跨区 GUI、服务器重启、MySQL、第三方组合与长期负载仍需精确构件实服验收，十阶段尚未完成。
