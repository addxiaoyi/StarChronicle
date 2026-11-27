# 2026-11-28 社交传播、八卦恢复与模块清理

StarCore `de73daf392391cc0ff7257c4799b754c8e735555` 已推送至 `codex/plugin-all-stages-20261125`。八卦传播合并当前触达集合，新闻限制为实际触达者可见，名字采样与通知返回各自原玩家 owner；停用失效旧处理器并继续逐项清理。仅做源码、单元测试和离线副本验证，没有启动 Minecraft／Paper／Folia，没有部署或合并主分支。社交其他服务和十阶段仍有源码待办。

## 传播与玩家会话

新八卦最大深度从 0 修正为 5，队列按当前记录合并触达，避免不同分支相互覆盖；中性话题不会因传播次数扣声望。状态、历史、评论及已读索引受锁保护，对外集合不可修改。通知失败或目标离线不自动标已读，通知完成才更新已读；声誉提交前再次检查停用、存储拒绝和记录是否仍存在。

新闻周期改用 Paper／Folia AsyncScheduler，重复 start 幂等，注册失败可重试，停用期间迟到的句柄取消，旧周期不能在重启服务后生效。分享更新接收者 reach，未读查询只包含已经触达的新闻；修复热门排序，过期清理同时移除评论、分享及已读索引。新闻仍只在内存中，本次没有增加新闻持久化。

共享 `SocialPlayerMessages` 在异步前捕获原 Player，名字与消息通过 `PlayerMenuTasks` 回该实体 owner；停用取消排队任务，原会话失效后不改投重连玩家。离线名称回退为 UUID 前八位，不做离线玩家名查询。通知完成代表本地发送调用结束，不是客户端确认。

## 八卦文件与模块生命周期

YAML schema 1 保存八卦、历史与已读索引，旧版本未保存的已读状态不臆造。重复键、未知顶层字段、未来 schema、非法记录和未完成写入痕迹被拒绝；先完整解析候选再替换内存。坏 reload 保留旧内存并隔离后续写入。保存采用原子文件替换、进程文件锁和加载 SHA 校验，陈旧实例不能覆盖其他实例已经提交的文件；主文件缺失而备份存在时拒绝自动回退。关闭会失效操作，健康状态仍可完成关闭后的保存。

模块启用失败逐项清理；停用先失效命令、八卦、监听器和任务，再保存、注销，单项异常不阻断后续步骤。命令及补全绑定启用代次，停用恢复之前的处理器；旧捕获入口无效，服务引用清空后再次启用不复用旧奖励服务。

八卦沿用显式保存／停用检查点，传播、声誉与文件不是共同事务，不承诺每次变化立即持久或断电严格一次。模块其他核心服务的恢复失败回退、社会事件、影响力、声誉和命令仍需继续审计，不能据此称社交模块已全部完成。

## 验证与原始失败

| `run/` 目录 | 实际结果 |
| --- | --- |
| `native-social-propagation-focused-1-20261128` | 0 项；PowerShell 拆分未加引号的 Maven 属性，未知生命周期 `.skip=true`，未进入测试 |
| `native-social-propagation-focused-2-20261128` | 81 项，1 failure；社交测试通过，天气订阅的固定源码字符串断言遇格式换行失败 |
| `native-social-propagation-focused-3-20261128` | 最终源码 86 项 verify 通过，0 failure／error／skipped，九个必需套件齐全，源码无漂移，格式与打包通过 |
| `social-gossip-offline-1-20261128` | 八个串行 JVM，旧 JAR 写入、升级、两次独立重开、陈旧写入拒绝、未来 schema／损坏／缺主文件拒绝通过 |
| `social-propagation-pair-artifacts-1-20261128` | 两库公开 API、项目类与资源、精确源码快照、sources JAR 和八个编译夹具通过 |

天气仅将两处源码断言改为允许空白的正则，保持检查含义，没有修改天气产品代码；最终执行 `WeatherModuleLifecycleTest`。第一轮格式命令的逗号文件选择未命中文件，后改为 Git ratchet 实际格式化，日志全部保留。

最终 Maven 212.64 秒，Java 21、320 MiB heap、192 MiB metaspace、Serial GC、单处理器、`forkCount=0`、Mockito agent。JaCoCo 跳过，离线 SBOM 没有重新生成；86 项是相关回归，不是 StarCore 全库。文件探针使用 96 MiB 堆，产品 JAR 优先，不加载 target/classes，不初始化服务器。Chronicle 产品仍为 `53562a2`，匹配其 1,195 项完整 verify。

| 冻结构件，`run/social-propagation-frozen-pair-1-20261128` | SHA-256 |
| --- | --- |
| Chronicle | `3561a787cf02e343778d22e16bbfc05b15ad12e5e1c68f969cb63c818e2726fc` |
| StarCore shaded | `cabacccd739425619a90baccc0e6fb043014c339b6ebd6f293b6a97104a80798` |
| StarCore original | `c230a646631ed376af9e2413b23b4b97e4c3f933ea69c12d003ca88df360253e` |

API 检查保留 Chronicle 214 个公开类型／1,866 个描述符、StarCore 3,010 个公开类型／35,883 个描述符。此前领地检查点的 13 JVM 历史数据库验证没有在本批新 StarCore JAR 上重跑，不转用于本构件。

[证据包](verification/social-propagation-source-20261128-evidence.zip)与[逐成员摘要](verification/social-propagation-source-20261128-summary.json)包括原始失败日志、XML、最终完整源码、工具、离线文件与配对清单。4,197 成员，10,828,365 字节，SHA-256 `97f74f8228f1fae94059a2227220bd2181afde59d96370384e4719c49ddc2a10`；逐成员重新读取核验，JAR／class 未上传 Git。

本次 fetch 确认已合入的上游开发提交仍为 Chronicle `92442e1`／StarCore `4d01f64`。当前构件多人交互、真实重启、第三方组合、完整 Folia 和长期负载仍需实服验收；不复用旧构件实服证据，不设置 `folia-supported:true`，不认证在线热卸载。
