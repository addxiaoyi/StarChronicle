# 好友命令 I/O、原会话反馈与分页

旧 `/friend` 在命令线程直接执行持久好友操作，并读取、通知其他玩家；接受／拒绝／删除忽略服务布尔结果，未成功也会显示成功。名字解析调用可创建离线资料的接口；列表无分页，旧执行器停用后仍可被捕获调用。

本批使用共享 `SocialCommandTasks`：先回原玩家 owner 解释复制的参数并采集本人名字，再把身份缓存读取、好友查询和持久变更放入有界 I/O。变更开始前复核原 Player 对象，排队期间重连／停用不再变更；已经进入执行的存储提交不强行回滚。对方名字在其原 owner 读取，双方消息分别回原会话，迟到结果不通知替代连接。

同方向重复发送提示已有待处理请求；互相请求实际结为好友时提示已成为好友。接受／拒绝／删除根据实际布尔结果反馈，不存在请求或关系不显示成功。服务处于恢复隔离时，命令不发布旧快照为当前查询结果。存储异常报告未能确认结果，详细异常留日志。

在线目标按当前名字或完整 UUID 捕获；离线接受／拒绝／删除支持缓存身份、已记录名字或完整 UUID，不调用 `getOfflinePlayer` 创建／请求资料。未知或重名的档案不猜测身份。没有已知名字时以短 UUID 显示，同时始终给出完整 UUID 供命令使用；首次未缓存的离线名字不会自动网络查询。

`/friend list [页码]`、`requests [页码]` 与新增 `sent [页码]` 每页最多 20 人，稳定 UUID 排序，只解析该页名字。requests 明确标记旧方向不确定的记录需重新发起，sent 显示真实发出请求。页码校验在查询前完成，中英消息与使用帮助同步。社交模块按实例恢复旧 friend 执行器／补全，不覆盖替代注册；已捕获的旧命令在停用后失效。

## 验证

第一轮 `native-friend-command-focused-1-20261128` 的 458 项 verify 通过。复核后补齐显式接受请求时对原对方的通知；自操作、已有好友、黑名单和双方上限使用确定拒绝反馈，存储失败继续保留未知结果提示；补齐空发件箱、超出实际页数和中英分页帮助。新增四项行为回归，不把第一轮构件套用到这些补丁。

第二轮 `native-friend-command-focused-2-20261128` 在复制源码快照时遇到 WinError 112 磁盘空间不足，Maven 未启动、零测试执行；部分快照与失败记录保留。未删除源码或证据；四份既有离线数据库副本采用 NTFS 压缩并核对压缩前后 SHA-256 一致。本次 class 缓存清理命令被执行策略拒绝，未执行删除。

第三轮 `native-friend-command-focused-3-20261128` 的 **462 项相关 verify** 通过，41 个必需套件齐全，零失败／错误／跳过，无源码漂移，打包与格式检查成功，262.579 秒。Java 21、320 MiB heap、192 MiB metaspace、Serial GC、单处理器、forkCount=0；JaCoCo 跳过，离线 SBOM 未重新生成，不是 StarCore 全库回归。

StarCore `472de9b45d14c11b2eb316e7358b41c4a8ef361e` 已推送。配对 API、类与资源、sources JAR 及八个编译夹具通过：Chronicle 保留 214 类型／1,866 描述符，StarCore 保留 3,010 类型／35,883 描述符。最终 StarCore JAR SHA-256 `e9e504c5d21c3dfe9faa32937ef7f08bae5b6ffd5548afae48c0da2ca32e2a43`；original JAR `402014f6b36190db2b9267b37348b535129b8f3b467dc32fe080ac1b9315aa56`。Chronicle 产品仍为 `53562a2`／1,195 项完整 verify，JAR `3561a787cf02e343778d22e16bbfc05b15ad12e5e1c68f969cb63c818e2726fc`。本批未改持久格式，未重新执行好友状态批的 14 JVM 迁移；该结果仅属于前序冻结 JAR。

[证据 ZIP](verification/friend-command-source-20261128-evidence.zip)与[逐成员摘要](verification/friend-command-source-20261128-summary.json)：7,768 成员，18,430,313 bytes，SHA-256 `104d457d3caaa531cfc962db8a154b629d1945d49dc5e843026235e21d6ee3ae`。包含第一轮及最终源码、日志、XML、未启动轮次失败说明和配对验证；每个成员均读回校验。冻结构件保存在本机 run/friend-command-frozen-pair-1-20261128，JAR／class 未加入 Git。

## 边界

StarCore 当前提交 CI run `46097606702` 的四项检查因账户付款／额度限制未启动，不是测试断言失败，也不算远程通过。[原始 run、jobs、checks 与注释](verification/friend-command-native-ci-20261128.json)已保存。此前好友状态检查点的两库 CI 归档另见[前序记录](verification/friend-state-ci-20261128.json)。

本批没有改变好友持久格式。原会话保护不取消已经开始的提交，也不声明在线热卸载安全。查询返回有界，但整体好友图和列表排序仍在内存中，完整并发／容量性能未实测。

父社交 GUI、全量导入导出路径与跨服务提交、社交周期、活动／联盟／文化和其余阶段仍有源码待办。没有启动 Minecraft／Paper／Folia，没有部署或合并主分支；MySQL、真实多人／重启、长期负载、第三方组合和全栈 Folia 仍待实服验收。十阶段未完成，旧构件实服结果不转用于本 JAR。
