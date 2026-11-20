# 原生任务完成证据与只读界面审查

本记录覆盖独立 StarCore 原生补丁、Chronicle 的只读任务门槛投影及 GUI，以及对反射适配器的交叉核对。它不代替协调代理统一执行的全量构建和真实 Paper 验收报告。

## 原生权威与持久化边界

原生补丁基于 StarCore 已审计提交 `f769e62799e769a18fe51305b545869a17f80cd1`，位于隔离的 `run/upstream-starcore-quest-evidence` 工作树。原始审计仓库未修改；补丁尚未向上游推送。原生任务的接取、真实目标进度和提交完成仍复用已有 `QuestService`、`PlayerQuest`、事件监听器与任务 GUI。

接取成功、任务目标达标和 `PREPARED` 奖励保护都不能产生完成证明。原生提交必须先持久确认保护标记，再经原有目标检查完成任务，保存独立的原生 run 完成回执，等待真实 quest-state 文件写入 future 确认，最后追加并 force 不可变完成记录。只有这两个持久确认均成功后，canonical lookup 才暴露 `COMPLETED`、完成 ID 和完成证据。

接受证据与完成证据分别不可变。接受时的 run、原始开始时间、定义指纹、国籍检查时间和证据不会因为后续完成或奖励变化而改写。重启恢复保留原始进度和开始时间；模板未注册时延迟恢复，不把新模板静默替换进历史 run。定义不符或恢复身份不符进入待核对状态。

审查修复了原有恢复时二次克隆造成进度和开始时间丢失的问题、模板晚注册时记录被丢弃的问题，以及其他任务子服务用空 `pq` 分片覆盖原生任务账本的问题。第一次 focused run 还真实发现 Gson 将 JDK 匿名不可变 map values view 写成 JSON null：现已先复制为具体 `ArrayList`，并增加字面 null 恢复回归测试。缺失原生回执不会被补造为完成。

确定未调用原生接取，或原生接取明确无变更早退时，持久 `REJECTED` 可用同一绑定和预留 run 安全重试。接受异常、可能部分变更或确认丢失则保留 `UNKNOWN`，不自动再接另一个 run。未绑定的旧原生任务不会被接管。

## 存储域与生命周期

`storageAuthorityId()` 是持久 UUID，不是进程 ID、路径或固定插件名。只有 `quest/evidence-authority.properties` 已原子创建并 force 确认及严格读回，或有效 primary 已严格加载后，提供者才能 available。Chronicle 以固定前缀加该 UUID 作为 provider ID，避免替换数据目录后仍被当作原后端。

身份文件损坏，或 primary 缺失但仍有身份回退 artifact、tracked index、journal 或原生绑定/完成标记时，保留现场并关闭提供者，不静默 mint 新域。全新干净存储域获得新 UUID。原生完成证明的 issuer 仍固定为 `starcore.quest.evidence.v1`，不因域 ID 改写。

原生模块关闭会先使证据服务 unavailable，再清理监听器和服务注册；延迟 owner 回调重新核验可用性。原生 player、quest、nation 操作由 Paper 主 owner 线程执行。已审计原生任务模块尚未全栈兼容 Folia，该提供者在 Folia 上明确关闭；Chronicle 独立规则和界面等待受支持的提供者，不宣称原生已兼容。

国家模式在真实接取与真实提交前分别读取原生 `NationService`，要求相同且确知的国家。服务缺失或读取失败是 `UNAVAILABLE`，不是“没有国家”。Chronicle 和文本提供者均不直接修改国家状态，也不调用原生 complete 方法制造证明。

## 奖励边界

完成证明与原生奖励执行是两件独立事实。奖励保护先于原生奖励和完成命令持久化；重启、部分失败或确认丢失均不会盲目重发。`INVOKED` 仅表示方法已返回并保存了调用检查点，不承诺异步经济操作到账，也不承诺任意控制台命令具有事务性或严格一次语义。歧义保留为 `UNKNOWN` 供核对。

## 只读 API 与 GUI

`ChronicleService.questGateState(instanceId, playerId)` 的默认实现失败关闭。`QuestGateState` 只包含当前实例/节点、任务条件是否满足、等待的效果 ID，以及 player/effect/quest/mode/nation/status/provider/run/completion/message 摘要；不暴露完整 native proof、定义指纹或可变任务对象。服务端在投影前核验成员与国家可见范围。

个人与国家剧情界面均增加任务门槛入口。列表每页 36 项，详情可分页，长诊断文本不截断。刷新、翻页和详情都会重新查询已授权状态，并核对查询前后的实例/节点。GUI 再次隐藏其他玩家的个人门槛。异步请求令牌阻止旧查询覆盖较新的页面；返回剧情列表、切换剧情/国家页面和玩家退出会使相关请求失效。

所有新增点击动作只调用查询 API。没有手工完成、奖励、强制解锁或原生管理命令按钮。界面显示任务条件已满足，也不会替代选择、国家代表资格或最终服务端权限检查。

新增 `QuestGateMenusTest` 12 项覆盖只读投影不泄露 proof、不可变列表、默认失败关闭、个人隐私过滤、未加入实例拒绝、节点变化一致性、实际 session 点击路由、无变更调用、分页和长诊断保留。实现代理已读取协调代理最终 `clean verify` 的该 suite 原始 XML：**12 tests，0 failures/errors/skipped**。

## 构建证据与产物边界

任务闸门的冻结 Chronicle 检查点为 `8f6f83abe51f175078d3c0c07f2e4683f4d7884c5ef123a2d3b4c6555bea9b89`（17,799,411 bytes）。`run/quest-evidence-clean-verify-r4.log` 记录该检查点的 779 项 `clean verify`。随后加入配置地区首访、持久 ingress、有界缓冲和重连所有权复核；新构建 `8fdcf8d375c22ca06c5bc15eb8e67b0ac58342b1e5a5636ea29307a88b423051` 已通过 795 项 `clean verify`，无失败、错误或跳过（`run/exploration-clean-verify-r2.log`）。探索实服验收尚在进行；前述冻结任务验收不自动覆盖新构建。

| 证据 | 结果与覆盖范围 |
| --- | --- |
| `run/native-quest-focused-tests.log` | 21 项中 1 error；真实发现 Gson 集合序列化问题，失败记录保留。 |
| `run/native-quest-focused-r4.log` | 早期 StarCore 聚焦 38 项通过；不是当前候选的完整回归结果。 |
| 当前 StarCore 六份 Surefire XML | 共 44 项，0 failures/errors/skipped：原生证据 38 项加 dungeon 持久化 6 项。各 suite 使用相同测试选择器；未保留这次 44 项的统一原始控制台日志，不能补称已有该日志。 |
| `run/native-quest-full-regression-r5.log` | 编译阶段 OOM，未完成；保留失败。 |
| `run/native-quest-full-regression-r6.log` | 7,968 项，11 failures、123 errors、21 skipped，BUILD FAILURE；StarCore 全量回归仍未通过，不能将失败统称为与补丁无关。 |

当前 StarCore 候选为 `run/upstream-starcore-quest-evidence/target/starcore-1.0.0.jar`，19,550,467 bytes，SHA-256 `c6fb0e5e00d05b5545e515bd67596fed1af05ea644f20c996aeae98c809f197a`。它包含 Windows dungeon 目录同步修复：仅在 Windows 上、确认目标是真实目录且打开目录通道抛出 `AccessDeniedException` 时视为平台不支持；其他 I/O 错误与已打开通道的 force 错误仍失败关闭。

对应 `patches/quest-evidence/starcore-quest-evidence-v1.patch` 为 336,891 bytes，SHA-256 `2b2c941e82c1182436e4dff0b1f3f7d8d3431864a9537a5bb557cf6f82f2d69c`。原始审计基线 `f769e62799e769a18fe51305b545869a17f80cd1` 上的 `git apply --check` 与差异空白检查通过。完整证据及六份 XML 路径见 [静态交接报告](../patches/quest-evidence/static-handoff-report.json)。六份实际 XML、完整回归失败日志与候选打包日志已按原字节随 [原生证据清单](verification/native-quest-candidate-c6fb0e5e/evidence-manifest.json) 归档；没有补造未保留的聚焦控制台日志。候选补丁尚未上游合并或生产部署。

中间产物保留为历史记录，不再标为当前候选：33 项阶段的 StarCore JAR 为 `9b6cf3eb1480de9cf4e1df2e8dcfff2762a277ff4939955b6f5ae6094ee696cd`，仅覆盖当时源码 diff `6c42f8c5ce26fb2300ff2142fcbabdfae7ea475265213c5e74bc7b517de38ce7`；同期 Chronicle 为 `9fd7769f0bff7d30fc67faa3e2e11beed5cd806e0c4f96355c894e17a9c10ddb`。原生详情页 36 格写入 slot49 的越界修复改为 54 格，保留完成按钮 slot32，并加入 3 项实际 renderer 测试；该阶段 180 项原生任务回归及其 JAR `1358d8e304c72f7ea575e9b30a9d6b311edd7651e132a4c66ed4bb450ef9c3a7` 的记录见静态交接报告。retry16 使用的 StarCore 是另一已冻结候选 `dca50c8aa6025e0c319d0756ca0ab19a90b33428ad619688d2f7320c577e4f5b`。

## 真实 Paper 验收状态

每个 attempt 使用独立目录，不复制旧世界、数据库或原生任务数据；任一失败或不完整记录都保留，不合并成下一次通过。

| Attempt | 实际结论 |
| --- | --- |
| retry16 | 三 boot 任务功能断言通过，见 [独立报告](verification/quest-evidence-paper-retry16-report.md)。boot1/2 各有一次 `DUNGEON_DIRECTORY_SYNC_FAILED`，boot3 的 dungeon 正常启动；不是三次都失败，也不是整服零错误验收。它使用旧客户端 `fa5a669facb4a5b678b87b9c2dc077d6e83b46b43e9011ad6fd03f930e0402b7`，不覆盖随后收紧的客户端。 |
| retry20 | 原生 map 目标已推进，但客户端未确认背包差量，记录 `CRAFT_UNCONFIRMED`／`ACTION_FAILED`，client exit 1；没有可用 boot1 检查点，不能继续 boot2。 |
| retry21 | 三次 server/client 均正常退出并产生检查点，三次 dungeon 均成功启动；真实制作、恢复、原生 GUI、离线贡献和剧情结果已有记录。但 boot3 在 A 连线前调用检查留下真实 `QUEST_EVIDENCE_FAILED`，且缺少新版验收所需标记；严格整轮验收仍失败，不能改成 PASS。 |
| retry22 | 停在 boot1，最后客户端记录为 B 制作书；控制器、server 与 client 进程经核实均不存在，没有正常退出或检查点证据，不能继续 boot2。 |
| retry23 | boot1 的真实制作、进度、阻塞状态及检查点通过。boot2 三名客户端连接后 Node 原生 Zone Allocation OOM，client exit134；Paper 已普通 stop，server exit0。没有 boot2 检查点，未继续 boot3。两次 dungeon 均启动成功，整轮保持失败。 |
| retry24 | 使用探索构建 `8fdcf8d3` 与相同 StarCore 候选；boot1/2 均正常 server/client 0/0 并产生检查点。boot3 已到 READY，但原控制器句柄已丢失，Win32_Process 查不到所属控制器／server，也没有 client 启动、正常退出或第三份检查点证据。原始 process record 保持 null；另存观察报告，不推断 exit 0、不续用这一轮，严格整轮未完成。 |
| retry25 | 使用重连修复构建 `4ede86a8` 与独立后台控制器。boot1 的 A 纸与 B 书真实制作、原生进度核验通过；C 地图在指南针格子放置确认阶段超时，尚未点击结果槽，客户端留下真实 ACTION_FAILED。控制器停止后续步骤并正常清理，server/client 0/1、无检查点，整轮失败。现有日志不足以确定是服务端未放置还是客户端观察／同步问题；没有自动重做地图或改写失败。 |

客户端每次制作只提交一次。15 秒内未观察到精确输出差量时记录 `CRAFT_UNCONFIRMED` 并停止，不依据“背包未变”自动再次点击配方。map 路径使用真实工作台逐槽放置材料、确认服务端结果槽、点击一次结果，并仅移动已产生的光标物品；关闭工作台使 Mineflayer 同步主背包后才核对净差量。retry20 保留了关闭窗口前读取主背包所致的失败观察；retry21 的修正客户端已真实确认三种配方，但这不消除该轮其他失败。

QuestService 的过期清理与每日重置已加入持久 `FAILED`／`ABANDONED` 状态和服务通知；确认丢失仍降级为 `UNKNOWN`，Chronicle 不据此重接任务。UNKNOWN 人工核对 API 与失败／放弃后同一闸门的安全新轮次 API 仍缺失；原生 provider 在 Folia 上仍关闭。聚焦测试、Paper 功能记录和候选补丁均不替代 StarCore 全量回归、上游合并或生产组合验收。
