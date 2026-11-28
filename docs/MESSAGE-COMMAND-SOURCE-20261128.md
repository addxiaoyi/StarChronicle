# 社交私聊命令：原玩家投递与关闭隔离

StarCore `e371b343001a50e21c0261b2c0ff38429b0e647d` 已推送两库沿用的开发分支 `codex/plugin-all-stages-20261125`。实际 SocialModule 的 `/msg`、`/r` 和别名此前直接跨玩家查名与通知，现在分别回原发送者、原接收者的 owner；参数先复制，回复捕获一次目标并用同一 UUID 写历史与投递。不会因后续其他会话更新最近对象而把记录和消息发到不同的人。

目标 owner 在准入前重检双方原连接、模块状态和当前屏蔽，依据服务真实布尔结果决定是否投递；接收者发送调用成功后才回原发送者回显。排队中重连、停用或重复调度回调不会产生新消息；已经准入的动作可以结束，不承诺两个玩家在同一原子时刻均在线。投递异常明确为未确认，不自动重发；历史表示服务接受的尝试，不表示客户端确认收到。回显失败不再次投递给接收者。

待处理命令有 256 个准入上限，补全复用原玩家名字目录，回复参数不建议玩家名。`/socialgui` 经过菜单监听器，帮助移除了不存在的 `/msghistory` 入口。实际模块绑定按 PluginCommand 对象去重，停用时关闭捕获处理器，并仅在绑定仍属于本实例时恢复前任执行器／补全。原构造器与方法描述符保持；旧未加载的 `MainCommandHandler` 私聊分派不由本批替换，实际源码中没有该类的生产构造入口。

## 验证

`native-message-command-focused-1-20261128` 执行 **504 项相关 verify**，46 个必需套件，零失败／错误／跳过、无源码漂移，243.109 秒完成打包。新增 14 个命令 owner／异常／会话测试和 2 个实际模块别名／恢复绑定测试，并纳入 18 个既有名字目录测试。单个 320 MiB heap／192 MiB metaspace、单 CPU、SerialGC、forkCount=0；保留现有 Maven 排除，离线未重建 SBOM，未执行 JaCoCo。这不是 StarCore 全库测试。

配对 API 的 214／3,010 个基线公开类型和原声明保持，290／4,190 个当前项目类、资源及 Chronicle sources JAR 核对通过，八个夹具仅编译通过。冻结目录 `run/message-command-frozen-pair-1-20261128`。

| 构件 | SHA-256 |
| --- | --- |
| StarCore | `801aa901e58b355534e6bd13e61be60da30e17ad0dd675357aa35bd0810dd5cb` |
| StarCore 原始 JAR | `31e9c581b8ee40df7b641ec13b29cba94e4fe181f6b1b8da90c9f45df8557c4a` |
| Chronicle，仍为 `53562a2`／1,195 项完整 verify | `3561a787cf02e343778d22e16bbfc05b15ad12e5e1c68f969cb63c818e2726fc` |
| 证据 ZIP | `68a81e8f0d3160b49a0729b72ef073eb00dbd62761039f1d3da8276d3065d629` |

[证据包](verification/message-command-source-20261128-evidence.zip)含 3,903 成员、9,749,367 字节；[逐文件机器摘要](verification/message-command-source-20261128-summary.json)已核验。没有独立 JVM 持久迁移探针，本批未更换持久格式。

StarCore [CI 46099912961](https://github.com/addxiaoyi/starcore/actions/runs/46099912961)四项检查因账户付款／支出额度限制未启动，不计为远程通过。前批 Chronicle `2744400` 的 [CI 46099625211](https://github.com/addxiaoyi/StarChronicle/actions/runs/46099625211) verify 十步通过。[原始 CI 记录](verification/message-command-ci-20261128.json)分别保留两者证据。

## 仍需完成

私聊服务的严格文件恢复、三文件一致检查点、写入失败隔离和状态关闭仍在后续源码工作中；公会事务、全量社交导入导出、周期／活动、联盟／文化、其余旧调度与叙事 Boss 等继续待办。本批不证明异步文件持久成功，也不把未完成源码归为实服待验。

未启动 Minecraft／Paper／Folia，未部署、未合并主分支。当前构件的多人跨区投递、真实重启、MySQL、其他插件组合和长期负载仍需实服验收；十阶段尚未完成。
