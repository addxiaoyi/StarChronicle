# 社交数据命令的会话与文件操作

StarCore `4a79aa3ff250b65fb9d44a9b81bafaf0b70d4f51` 已推送开发分支。`/socialdata` 与 `/social export/import/exportplayer/list` 的权限检查和反馈在原玩家 owner 或控制台 global owner 执行，参数先复制。文件工作走每命令最多256个未完成操作的队列；排队玩家重连后拒绝旧操作，关闭取消尚未开始的工作并停止旧回包。已开始的持久操作不宣称被取消或回滚，失败／未知结果不会自动重试。

文件列表排序，每页20项，页码1..100000；导入只接受简单文件名并沿用服务路径边界。玩家导出支持自己、完整UUID、当前在线身份、缓存离线身份或唯一资料名，不创建远程离线资料。补全读取缓存并异步刷新文件列表，名字使用原玩家名字目录。返回false、异常和成功分别反馈。保留原public构造器和可扩展类形状。

模块按实际命令对象去重绑定，停用仅恢复仍属于自己的executor／completer；后续替代者保持。社交模拟包装器晚于父模块关闭时，跳过已经关闭的数据命令并恢复更早前任，避免重新安装关闭实例。新增11项命令测试、2项父模块绑定测试及1项逆序卸载测试。

本轮 **820项相关verify／71必需套件** 全部通过，零失败、错误、跳过、源码漂移，294.422秒，实际完成打包。验证限制为320MiB heap／192MiB metaspace、SerialGC、单CPU、forkCount=0，离线跳过JaCoCo／SBOM并保留既有排除；这是相关回归，不是全StarCore回归。

精确构件API核对保留214/1866与3010/35883，当前290/4231类，8夹具编译通过。native SHA-256 `dc0afe27a841826d2e4e81e5542d064f39bb64dc6296383df780c1ab0b4ab58b`；original `7a998fb3ee55be12c89a6d79f18ddae88bae4506b6c193486bbdf3c8d538d39f`。同一冻结构件再跑 **55个串行JVM**：旧导出迁移，文件／SQLite／混合模式五个中断点及两次恢复，第三状态、损坏／缺失计划与临时文件拒绝，全部通过。Chronicle产品仍为`53562a2`及其1,195项完整verify。

导入仍是可恢复的逻辑状态迁移，原SQL行ID／原时间／handled与过期历史／扩展列需原库备份；旧版本未导出的证据不重建。完整计划与独立凭据、完成回执均保留；不是跨库或外部非合作写入者全局事务。原存储身份变化、第三状态、过期请求投影和未完成证据拒绝恢复。全部独立证据同时丢失无法推知；16MiB／深度64／250000节点预算及长期全计划扫描边界继续保留。Windows目录fsync尽力处理，未做硬件断电／MySQL验证。

证据：[摘要](verification/social-data-command-source-20261128-summary.json)、[ZIP](verification/social-data-command-source-20261128-evidence.zip)，4,431成员／11,103,754 bytes，SHA-256 `e721d24d70d15fa329574bbaba03361bab145f6e79277f77bb83bd0fb31d899c`，每成员哈希核对通过。包含历史源码／JAR去重与压缩记录，所有历史路径与原字节保留。StarCore CI原始状态见[记录](verification/social-data-command-native-ci-20261128.json)，无运行器及执行步骤；Chronicle前批`8340e8d`两条CI均10步成功，见[记录](verification/social-import-recovery-chronicle-ci-success-20261128.json)。

继续表情冷却／动画与自定义持久化、周期／聊天、活动／联盟／文化、其他实际加载旧调度及叙事Boss／领地玩法源码。没有启动Minecraft／Paper／Folia；当前构件的多人、Folia区域迁移与实服组合仍需独立验收，十阶段未完成。
