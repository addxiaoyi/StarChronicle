# 私聊状态：统一检查点、严格恢复与关闭边界

StarCore `843f0aed6211216ac8a610049a54233171b1e4c4` 已推送到开发分支 `codex/plugin-all-stages-20261125`。旧私聊服务分别写入历史、最近对象和屏蔽三个 YAML 文件，恢复会跳过坏记录，写入异常只记录日志，关闭后仍可修改内存。本批统一为 `private-message-state.yml` schema 1，由单个有界 writer 保存完整一致的内存快照；历史、双方回复指针和屏蔽检查共用短锁。普通发送仍是内存准入加异步保存，不承诺发送返回即已落盘或客户端已收到。

旧三文件只在无新检查点且无迁移残留时导入，完整候选验证后才替换内存。保留各旧文件原字节和按 SHA-256 命名的备份；新检查点绑定三个来源的指纹，旧软件后续修改来源会使新版本拒绝恢复，不会静默混合。旧 JAR 本身不能理解新检查点，也不会被新软件改写成支持该协议。

新检查点使用原子重命名、前代备份和文件强制写盘；不支持原子重命名时明确失败，保留临时证据，不降级为普通覆盖。迁移标记防止新文件删除后退回旧数据或空状态。未知 schema、重复 YAML 键、无效 UUID／时间／字段、不属于该玩家的消息或冲突消息 ID、超过 100 条的单人历史及未完成临时文件均拒绝加载，保留原文件供明确恢复。空集合可正确替换旧内存。新写入检查加载时指纹并持有独立文件锁，陈旧实例不能直接覆盖已变化检查点。Windows 目录 fsync 不可用时仅尽力处理，不声称硬件断电已验。

解析预算为 16 MiB 文件与 4,194,304 个 Unicode 码点；保存也遵守同一预算。超过预算会明确隔离，不截断既有数据。正常业务保留每人最近 100 条；对话筛选后取最近条数。清除操作也提交保存，显式清理不会擦除他人的屏蔽偏好。

保存或恢复失败阻止后续业务和旧内存覆盖，显式成功 reload 才恢复；关闭停止新准入、等待最后保存，等待有界。上一实例 writer 尚未终止时不允许本对象重新初始化。

首轮 `native-private-message-state-focused-1-20261128` 执行 517 项，零断言失败／8 个错误／零跳过，没有打包且无源码漂移。新 YAML 来源键直接使用带 `.yml` 的文件名，被 Bukkit 配置转换成层级，导致正常重开错误拒绝；现改为无点的固定来源键。失败源码、日志和 XML 完整保留。随后补充文件锁占用、替换失败保留证据、超过历史预算、保存中断后隔离测试。

第二轮 `native-private-message-state-focused-2-20261128` 的 **521 项相关 verify、47 个必需套件全部通过**，零失败／错误／跳过，无源码漂移，233.484 秒完成打包。使用 320 MiB heap、192 MiB metaspace、单 CPU、SerialGC、forkCount=0；离线跳过 SBOM 和 JaCoCo，保留项目原有测试排除，不代表 StarCore 全库回归。

`private-message-state-replicas-1-20261128` 的 **11 个串行 JVM** 使用实际旧／新 JAR：旧三文件迁移保持原字节及精确备份，显式保存后直接 halt，再用两个独立进程核对消息 ID／正文／顺序、最近对象和屏蔽；未来 schema、重复键、主文件丢失、临时残留、旧来源被修改五种副本均拒绝且原字节不变；清除对话／历史后独立重开仍保持清除。探针未执行其中预留的并发双进程分支，不能声称多 JVM 并发验收。

配对 API 核对保留 214／3,010 个基线公开类型及其声明，290／4,193 个当前项目类、资源和 Chronicle sources JAR 一致，8 个夹具编译通过。冻结目录为 `run/private-message-state-frozen-pair-1-20261128`。

| 构件 | SHA-256 |
| --- | --- |
| StarCore | `75ed02e941032d253a79b0d8aba542c94a550a3dc26e179d152454e557b5efd4` |
| StarCore 原始 JAR | `d081e96935a0c2eba89add61a6a2912ba99445d9779253e645cc6fa3344d13ae` |
| Chronicle，仍为 `53562a2`／1,195 项完整 verify | `3561a787cf02e343778d22e16bbfc05b15ad12e5e1c68f969cb63c818e2726fc` |
| 证据 ZIP | `8da6ddab9c904be58c3183381245708fb16920211d3f6adbb96cb108e5248aaa` |

[证据包](verification/private-message-state-source-20261128-evidence.zip)包含 7,897 个成员、18,599,110 字节；[逐文件机器摘要](verification/private-message-state-source-20261128-summary.json)已核验。StarCore [CI 46101165227](https://github.com/addxiaoyi/starcore/actions/runs/46101165227)因账户付款／额度限制，四项检查均未分配 runner 或执行步骤，不计为通过。Chronicle 前批 `0db6944` 的 [CI 46100198164](https://github.com/addxiaoyi/StarChronicle/actions/runs/46100198164)十步通过。[原始记录](verification/private-message-state-ci-20261128.json)保留两者证据。

未启动 Minecraft／Paper／Folia。异步消息从内存准入到检查点之间的进程崩溃仍可能丢失最近尚未保存的记录，不能当作持久投递凭据。跨进程长期并发、实际服务器重启、多人区域调度和第三方组合保持实服边界；公会／导入导出／活动及其他阶段仍有源码待办，十阶段未完成。
