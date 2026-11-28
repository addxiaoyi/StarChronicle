# 好友推荐评分、逐玩家缓存与实际入口

原推荐代码按从未赋值的 totalScore 排序后截取十人，影响力取自任意共同连接而非候选人，社交圈中并非好友的连接也被计作共同好友。一个玩家刷新会延长所有缓存，缓存结果和分析集合可被外部修改；对方拉黑自己的方向未过滤，原三个推荐／匹配／圈分析方法没有实际命令入口。

本批在所有候选人评分后按分数降序、UUID 稳定排序。共同好友严格按双方好友集合交集计算，影响力使用候选人自身，负值／上限统一 clamp。两项已存在指标按 5:3 归一为 0–100；没有虚构未采集的活跃度指标。只来自社交圈的候选理由明确为社交圈连接。匹配查询对完整候选集使用真实兼容度再截取，公开 limit 限 0–100，命令限 1–20；空好友的基础兼容度统一为 30/100。

缓存每名玩家单独记录时间、最多 512 名／每名十项，时钟回退视为过期。命中缓存时再次过滤既有好友及双方黑名单；显式清除无效推荐不延长原 TTL。返回列表、共同好友、分析中的嵌套圈集合均不可变。关闭会清空缓存、停止原玩家查名任务，迟到异步名字不返回旧推荐。

同步查询返回 UUID 短名；新增异步名字查询在各自原玩家 owner 捕获在线名字，不调用离线资料查找。`/social recommend [refresh]`、`/social match [人数]`、`/social circle` 已绑定到社会模拟模块实际持有的推荐服务；查询在命令受限 I/O 执行，名字与回复复核原会话，提供完整 UUID 便于辨认。推荐服务关闭归入 SocialModule 消费者清理。

## 验证

StarCore `1d834bdcc8ffbb4212834eaeebb39910f0a9b00b` 已推送开发分支。`run/native-friend-recommendation-focused-1-20261128` 执行 **311 项相关 verify**，32 个必需套件齐全，零失败／错误／跳过，无源码漂移，打包成功，244.610 秒。新增九项推荐测试和三项实际命令测试；采用 Java 21、320 MiB heap、192 MiB metaspace、Serial GC、单处理器、forkCount=0。JaCoCo 跳过，离线 SBOM 未重新生成；不是 StarCore 全库回归。

`run/friend-recommendation-pair-artifacts-1-20261128` 的公开 API、类资源、sources JAR 和八个编译夹具全部通过：Chronicle 保留 214 类型／1,866 描述符，StarCore 保留 3,010 类型／35,883 描述符。冻结 shaded JAR SHA-256 `c9f3a664c75e2f23734c274929b488086b7ac8e01dba37b5376d7b0cf8b5cf43`，original JAR `17caa2e65126674c97ee13b28210988975d637981c09c7f855d8bfc6315da293`。Chronicle 产品保持 `53562a2`／1,195 项完整 verify，JAR `3561a787cf02e343778d22e16bbfc05b15ad12e5e1c68f969cb63c818e2726fc`。

[证据 ZIP](verification/friend-recommendation-source-20261128-evidence.zip)及[逐成员摘要](verification/friend-recommendation-source-20261128-summary.json)：3,860 成员，9,610,881 bytes，SHA-256 `da15b95e7adc8b50acff26b5990a1c158adcd37bca8e509e8891affde97dd28c`。归档成员逐一读取核验；JAR／class 未提交 Git。

## 边界

推荐是当前内存图的查询，好友数据变动期间不提供跨服务原子快照；TTL 内新增候选需显式刷新或到期。候选生成仍遍历朋友／社交圈，虽返回和缓存有界，大型社交图的 CPU／临时集合和并发负载尚未实测。没有修改好友请求方向、双向关系持久事务或旧反射包装；这些源码以及其他阶段继续待办。本批无持久格式变化，没有新增数据库探针，不套用旧构件数据库结果。

只做源码、单元测试和离线构件验证，没有启动 Minecraft／Paper／Folia，没有部署或合并主分支。真实多人／重启、第三方组合、MySQL、长期负载和全栈 Folia 仍待验收；十阶段尚未完成。
