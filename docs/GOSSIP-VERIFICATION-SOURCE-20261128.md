# 八卦验证：状态、原子文件检查点与命令

StarCore `120dd9b9f7a6660961e59acf80bb47e307c951a9` 已推送开发分支。修复投票／举报状态被旧局部记录覆盖、虚假票不触发阈值、重复处理或虚构举报可领奖的问题，并接入文件恢复、实际命令和自动检查。只做源码、单元测试和离线副本验证，没有启动 Minecraft／Paper／Folia，没有部署或合并主分支。十阶段尚未完成。

## 行为与兼容

验证记录由 GossipService 持有，和八卦可信度共用同一个状态锁。投票、举报、辟谣、证据、来源、处理状态和可信度在一次 `gossip_data.yml` 检查点中保存；不会用两份各自成功的文件冒充原子提交。新 schema 2 严格校验记录类型、字段、重复投票／举报／来源、真假票互斥、有限可信度及其实际变化量。模型对集合深复制，辟谣证据也不可变。无版本／schema 1 数据可读取，旧数据不会凭空得到验证记录；前序 schema 1 写入器拒绝新 schema 2。公开原方法和五参数统计构造器保留，统计新增 disputed 计数。

构造时复制并校验 VerificationConfig，使用配置阈值和自动等待时间；模块仍使用默认配置。真假票都检查阈值，负声誉权重不能反转投票方向；每条八卦验证通过加成最多一次，已辟谣不被后续投票恢复。可信度审计记录实际 clamp 到 0.1–1.0 后的差值。来源仅接受存在的八卦；只有实际创建者可作为原创来源，同玩家重复追踪不再加分。添加证据限原辟谣作者。

待处理队列从未处理的持久举报推导；处理必须命中真实且未处理的举报。先保存处理／官方辟谣状态，再单独调用声誉奖励，重复请求或重开不重复提交奖励。奖励失败记录日志且不自动重试；**保存与声誉不是共同事务，保存后崩溃或声誉失败可能漏奖**，不承诺跨服务严格一次或自动补偿。

新增每分钟自动检查，通过模块既有有界串行 I/O 队列执行，同一时刻最多一个待执行检查。关闭先失效旧周期和排队回调，迟到句柄被取消，重复回调不重复执行业务。`/socialsim gossip` 增加 list、verification、vote、report、evidence、reports、debunk、process；多词正文保留、显示可操作 ID，所有持久工作进入命令 I/O，名字在原玩家 owner 获取，结果回原会话。reports／debunk／process 使用 `starcore.social.moderate`，默认仅 op。旧同步辟谣 API 使用 UUID 显示名，不跨 owner 读取玩家。

文件异常仍保留原始主文件及备份，拒绝陈旧写入者、未知 schema、坏验证记录和主文件丢失；失败会隔离写入，需核验并显式 reload。写入失败后内存可能包含尚未确认的候选状态，不能视为回滚或成功。普通八卦创建仍可能先传播通知，再由命令保存来源／检查点，外部通知不属于文件事务。

## 验证

`run/native-gossip-verification-focused-1-20261128`：**250 项相关 verify**，26 个必需套件齐全，零失败／错误／跳过，无源码漂移，格式和打包通过。新增 13 项服务测试、6 项实际命令测试，另扩展模块关闭和旧文件迁移；覆盖状态阈值、并发去重、重开不重复领奖、保存失败不发奖、证据不可变、配置时钟和周期准入／关闭、权限与原会话回复。

Java 21、320 MiB heap、192 MiB metaspace、Serial GC、单处理器、forkCount=0，验证 246.672 秒。JaCoCo 跳过，离线 SBOM 未重新生成；不是 StarCore 全库验证。

`run/gossip-verification-pair-artifacts-1-20261128`：公开 API、项目类／资源、源码快照、sources JAR 与 **8 个编译夹具**通过，保留 Chronicle 214 个公开类型／1,866 个描述符和 StarCore 3,010 个公开类型／35,883 个描述符。

`run/gossip-verification-offline-1-20261128`：**13 个串行 JVM／7 项对照**通过，使用真实冻结旧／新产品 JAR。包括旧 schema 1 写入、新 schema 2 升级、两次独立重开、举报处理后再次重开不领奖、旧 JAR 拒绝写入 schema 2、陈旧验证写入拒绝，以及未来 schema、坏记录／阈值和主文件丢失的原证据保留。每个 JVM 为 96 MiB heap／128 MiB metaspace；无服务器进程。

| 冻结构件 | SHA-256 |
| --- | --- |
| Chronicle | `3561a787cf02e343778d22e16bbfc05b15ad12e5e1c68f969cb63c818e2726fc` |
| StarCore shaded | `049ca4f3251a67c9bfac82065f499b3c9bd65baf50f5b810834dc479d6e09a0c` |
| StarCore original | `4e03a0882b79f7685da7b53329251291aa8ec85f1d3863a6c241bea6067833af` |

Chronicle 产品保持 `53562a2`／1,195 项完整 verify。[证据 ZIP](verification/gossip-verification-source-20261128-evidence.zip)和[逐成员摘要](verification/gossip-verification-source-20261128-summary.json)：3,901 成员、9,604,190 bytes，ZIP SHA-256 `c54efa9256a1229637aa74b78b56982104f1e6685432b4bfe36ffb640695b38a`，全部成员逐一读取核验。JAR／class 未提交 Git。

前序 `/social` 的 CI 原始结果已补齐：Chronicle `0c1fd38` run `46087560232` 的 10 步成功；StarCore `2b41e71` run `46086406272` 因账户付款／额度限制未启动，四项检查 runner=0、零步骤，见[原始结果](verification/social-command-ci-20261128.json)。当前 StarCore `120dd9b` run `46088956109` 同样因账户付款／额度限制未启动，四项检查 runner=0、零步骤；Chronicle `d0b1db8` run `46089052642` 的 10 步成功，见[本检查点 CI 原始结果](verification/gossip-verification-ci-20261128.json)。

## 剩余边界

文件检查点仍全量编码／写入，所有验证记录和历史常驻；长期增长、启动内存、多人高频并发、磁盘中断／断电尚未验证。记录已经移入同一文件不能证明声誉事务原子性，也不能将自动多数投票等同事实查证。

SocialModule 的关系／活动双实例与推荐、联盟申请审批及持久成功语义、活动／文化恢复与调度、领地收益及经验来源配置、政策／租赁／军队天气、兼容回调、叙事 Boss 和旧国家加成仍有源码待办。当前构件实际命令权限、多人 GUI、真实重启、第三方组合及全栈 Folia 仍需实服验收；MySQL 等未执行环境另行验证。不设置 `folia-supported:true`，不认证在线热卸载，不沿用旧构件实服结果。
