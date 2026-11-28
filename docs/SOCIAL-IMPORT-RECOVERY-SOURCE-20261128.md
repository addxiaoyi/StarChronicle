# 全量社交导入与跨服务恢复

StarCore `c2623b9c8779f94b755eb9fd04ab86dc45382a46` 已推送开发分支。本批实现全量候选解码与预校验，所有好友／公会／派对候选验证完成后再进入持久提交。原逐行catch继续、前半段已经写入而后半段才发现格式错误的流程已替换。好友关系、方向请求、未知联系人、黑名单和在线状态以及公会／派对身份、原创建时间、成员／角色／余额、邀请／原派对绑定均纳入version2逻辑快照。旧version1输入保留兼容，未导出的证据不重建；新JSON序列化明确保留未知派对null。

操作固定friend→guild→party锁顺序，本进程查询不会看到半批发布。提交前写入不可覆盖的完整expected/candidate计划、原存储身份摘要及独立摘要凭据，完成后保留匹配回执。失败或未知结果关闭三个原服务，旧引用不能复活；SocialModule下次启用创建新好友／公会／派对实例，恢复完成后才初始化私聊和注册业务消费者。

启动恢复先检查所有参与者均为计划旧状态或新状态，再向前续接并记录完成。已为目标状态者仍以no-op完整比较提交确认存储，SQL保持未变原行；文件模式可能生成内容相同的新检查点／前一备份。数据库URL／catalog及文件路径仅保存摘要，切换SQL／YAML或目标库拒绝未完成计划。第三种状态、过期SQL请求投影变化、损坏日志、未完成临时文件、计划／回执丢失或整个日志目录丢失均拒绝自动恢复并保留证据；独立凭据目录也全部丢失时无法凭空检测历史。

第四轮806项相关verify／70必需套件，零失败、错误、跳过、漂移，321.343秒；新增14恢复测试及2模块生命周期测试。第一轮804项7失败1错误（null被省略及旧实例复活测试），第二轮磁盘满未启动，第三轮806项1失败（过期列测试夹具缺少列）均保留。320 MiB heap／192 MiB metaspace、SerialGC、单CPU、forkCount=0，离线跳过JaCoCo／SBOM并保留既有排除，不是全StarCore回归。

API配对保留214/1866与3010/35883，当前290/4229类，8夹具编译通过。native SHA-256 6600ecd75191af31291eebc01420d4be3fe20fb0b7640e38c43def8487953dd5；original 454fa0d80d6336f07d2b0346f8e3614dd3e17556f042211dbfa4ee2843d40113。精确旧／新构件 **55个串行JVM** 验证通过：真实旧版导出迁移及两次重开；文件／SQLite／混合模式各五个halt点和两次恢复；最后参与者冲突逐字节拒绝；损坏／丢失计划及未完成文件逐字节拒绝。

边界：这是可恢复的逻辑导入，不是跨数据库／外部写入者全局原子事务或原SQL灾备。原数据库行ID、入会／入队原行时间、好友请求原时间／到期／handled历史及扩展列仍需原库备份；已有未变行由差量SQL保持，新导入关系写入新持久行。日志每文档仍限16MiB／深度64／250000节点；整批计划包含两个快照，超出预算会拒绝，长期完整扫描／归档容量未验。合作锁不能拦截外部非合作直写；Windows目录fsync尽力处理，没有硬件断电实测。仅SQLite、文件和混合模式，MySQL／真实多人／长时间写入需独立验收。

后续继续SocialDataCommand原owner与I/O、周期／表情／聊天、活动／联盟／文化、其他实际加载旧调度及叙事Boss／领地玩法等源码。没有启动Minecraft／Paper／Folia；十阶段未完成。

证据：[摘要](verification/social-import-recovery-source-20261128-summary.json)、[ZIP](verification/social-import-recovery-source-20261128-evidence.zip)，13,539成员／32,498,337 bytes，SHA-256 `7d04767bc7a8ae137833b4f562b636f84f2fe902e4d34e75e62be7883699caaa`，逐成员哈希通过。StarCore CI见[原始记录](verification/social-import-recovery-native-ci-20261128.json)；Chronicle前批`cccc097`两条CI十步成功，见[记录](verification/friend-service-complete-chronicle-ci-success-20261128.json)。产品仍为`53562a2`及其1,195项完整verify。
