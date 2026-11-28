# 社交导入导出：文件边界与严格 JSON

StarCore `0941895404b3541da92468fff04a95707bd485bf` 已推送开发分支。本批源码及单元／独立 JVM／配对验证通过。实际全量和单玩家导入统一使用 `SocialExportFiles`，只读取 `social_exports` 直接子目录内的普通 JSON 文件，保留目录内绝对路径与简单文件名入口。拒绝目录穿越、嵌套路径、Windows ADS／保留名、尾部别名、链接文件与链接导出目录；支持中文等 Unicode 字母／数字文件名。该边界基于受信任插件数据目录，不声称抵御有本机目录修改权的管理员在检查和打开之间替换目录。

JSON 读取最多 16 MiB、64 层和 250,000 个节点，读取增长也计入上限。重复键、坏 UTF-8、注释／非严格 JSON、尾部值、非法根和超大数值拒绝；全量导入的未知版本在服务变更前拒绝。单玩家原始定向／旧请求语义保持。既有单玩家测试改在导出目录内建输入，没有扩大路径授权。

导出先写唯一临时文件并刷盘，再使用硬链接原子发布；目标已存在则失败，跨线程／合作进程通过目录写锁串行，不会截断旧导出。发布失败保留临时证据，不退回部分复制；文件系统不支持硬链接时失败。Windows 目录 fsync 尽力执行，不代表断电验证。自动文件名使用 UUID，支持显式 `.json` 后缀。列出文件关闭流、排序并跳过非普通文件；读取目录失败显式抛错，不能显示成“没有文件”。这些预算约束文件解析，不代表所有内存服务状态或序列化前分配均已受限。

`native-social-export-files-focused-2-20261128`：**707 项相关 verify、58 必需套件通过**，零失败／错误／跳过，无源码漂移，265.25 秒完成测试、打包和证据捕获。320 MiB heap、192 MiB metaspace、SerialGC、单 CPU、forkCount=0；离线跳过 JaCoCo／SBOM，其他既有排除保留，不是 StarCore 全库回归。首轮进程在编译阶段中断，没有结果文件；确认 PID／工具 handle 均消失后才开启第二轮，原日志与中断记录保留，不能把它当通过。

**7 个串行 JVM** 使用真实旧 JAR 生成 JSON，新 JAR 导入保留公会 ID／创建时间／角色／余额及好友请求，提交新导出后 halt，再由独立进程打开；同名导出拒绝且哈希不变。四种坏副本（重复键、未来版本、深度超限、坏 UTF-8）拒绝，原输入字节保持。它验证文件交换，不是全量社交持久事务恢复；原全量导入仍分服务提交，文件模式好友落盘、派对 ID／创建时间导入与跨服务恢复仍有源码工作。

配对保留 Chronicle 214 类型／1,866 个、StarCore 3,010 类型／35,883 个既有公开／受保护描述符，读取全部 290／4,208 个当前类文件，8 个夹具仅编译通过。native SHA-256 `f5cbbafb67d405e79419ced2ec1aad77510e102e560d54efbd82978c18c29077`，original `4d2f49a6b4c163095a59ca4bdc8803ee9477985e88a701a23e24694f851d0031`；Chronicle 产品源码仍 `53562a2`／1,195 项完整 verify。见[摘要](verification/social-export-files-source-20261128-summary.json)与 [ZIP](verification/social-export-files-source-20261128-evidence.zip)：7,770 成员、18,375,574 bytes，SHA-256 `8b7ac26c080489dbef867dac606a6483e871d4bbe49f0b78f32f2109f4164ee1`，成员哈希逐项核验。

StarCore [CI 46135792348](https://github.com/addxiaoyi/starcore/actions/runs/46135792348) 因账户付款／额度没有启动，原始原因见[记录](verification/social-export-files-native-ci-20261128.json)，不能称 CI 通过。Chronicle 前批 `61b4ecd` CI 十步成功，见[记录](verification/guild-command-chronicle-ci-success-20261128.json)。

下一步继续社交服务完整提交、全量导入与实际命令 I/O／生命周期，其他阶段见剩余工作。未启动 Minecraft／Paper／Folia，不部署、不合并主分支；MySQL、实服多区域和长时负载仍待验收。十阶段未完成。
