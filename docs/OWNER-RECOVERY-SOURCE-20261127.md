# 2026-11-27 原玩家所有权、恢复凭据与事件链源码检查点

本检查点只包含源码、单元测试和离线数据库副本验证，**没有启动 Minecraft／Paper／Folia**，没有生产部署或合并主分支。十阶段仍有源码工作，不能写成只剩实服验收。

## 代码与分支

两库继续使用 `codex/plugin-all-stages-20261125`。Chronicle 产品源码为 `53562a2734bf2dd3a21bc089e4e0591040609c69`；StarCore 已推送到 `6412a73`。草稿 [Chronicle PR #2](https://github.com/addxiaoyi/StarChronicle/pull/2) 与 [StarCore PR #3](https://github.com/addxiaoyi/starcore/pull/3) 均保持打开、未合并。上游基线分别为 `92442e1`／`4d01f64`，上游原构件实服报告继续保留自己的指纹与范围。

已交付的主要改动：

- 史诗被明确拒绝后的重试保留追加式尝试谱系；必须是最新的确定 REJECTED、当前权威与修订号才可新建尝试。资源援助使用原请求的永久拒绝证明；UNKNOWN 不自动重放。
- 原生资源转移的 COMMITTED 与永久 REJECTED 互斥，文件／SQL 持久保存，并设置旧写入器栅栏。余额后来增加不改变旧拒绝证据，新操作使用新编号。
- 科研恢复、派对、脏标记／战争保存、邮件、家族、状态栏及导航／排行榜会话修复已有分批源码回归。状态栏仍是 hide-only；战争保存重试仍是进程内队列，不是持久 outbox。
- 副本玩家快照、中断恢复、玩家／怪物 owner 与停用生命周期已补齐。Folia 副本仅允许显式预加载白名单，默认空，不支持动态世界生命周期。
- 季节／宗教／时间同步、称号、领地边界、HUD 与共享动画改用实际玩家、区域或全局 owner；重连、退休、迟到句柄、关闭后回调均有对应测试。已准入回调允许结束，不能据此认证热卸载。
- 屏幕震动移除了反射相机 API 和装饰性传送／复位，改用 Paper `playHurtAnimation(float)`、声音及玩家局部粒子。旧 amplitude 配置保留兼容，但 Paper 没有任意相机位移接口。三个可选震动监听器的构造／注册调用未找到，不声称新增了真实订阅。
- HUD 仅在实际界面成功打开后登记；取消替换／刷新保留原菜单与处理器；重入开关界面不会误清理新菜单。
- 场景音效绑定原玩家／目标区域并在关闭时退休，UI／通知／世界音量实际影响播放；修正 `menu.*` 被错误归入世界音量的问题。
- 随机事件菜单核验当前界面、原玩家和链归属；先消费一次 UI 尝试再执行效果，异常后不重试未知结果。事件链按原玩家／指定区域／全局 owner 调度，位置复制，结束／退休／停用取消任务；后继只在同一链安排一次，冷却原子保留。

## 已执行验证

各行是各自冻结快照，存在覆盖重叠，**不能相加当作全库测试数**。所有通过行均零失败、错误、跳过，且验证期间源码没有漂移。

| 快照目录（`run/` 下） | 实际结果 | 范围 |
| --- | --- | --- |
| `epic-retry-chronicle-full-1-20261127` | 1,195 项、完整 verify | Chronicle `53562a2` |
| `native-owner-focused-5-20261127` | 329 项 | 科研、派对、保存、邮件等相关回归 |
| `native-gui-focused-2-20261127` | 67 项 | 导航／排行榜会话 |
| `native-runtime-rejection-focused-2-20261127` | 616 项 | 副本 owner／恢复及拒绝凭据 |
| `native-season-hud-focused-2-20261127` | 194 项 | 季节、宗教、时间同步、HUD |
| `native-title-territory-focused-3-20261127` | 87 项 | 称号与边界显示 |
| `native-animation-focused-2-20261127` | 69 项 | 动画及共享离线注册表 |
| `native-shake-focused-1-20261127` | 72 项 | 震动、动画、HUD |
| `native-hud-open-focused-1-20261127` | 87 项 | 实际 HUD 开／取消／重入及相关回归 |
| `native-owner-package-1-20261127` | 966 项、verify | StarCore **b7817ae** 的相关回归与打包；不是全库 |
| `native-random-scene-focused-2-20261127` | 61 项 | 仅音效、动画、HUD；随机事件目录被旧编译排除规则排除 |
| `native-event-chain-focused-1-20261127` | 61 项 | 同样没有实际执行随机事件测试，不能认证菜单／链 |
| `native-event-chain-focused-2-20261127` | 111 项 | 修正排除规则后含菜单 14 项、事件链 15 项、随机事件权限／诊断／经济等；源码对应 `6412a73` |

发现 `pom.xml` 原有 `**/event/random/**` 编译排除后，改为保留具体旧夹具的排除，恢复有效测试。前两次 Maven 成功日志原样保留，**不是菜单或事件链验证通过的证据**。验证脚本新增 `--require-suite`，从实际执行 XML 检查必需测试类；缺失则退出失败。

任务自有 JVM 串行运行，Maven 堆 320 MiB、metaspace 192 MiB、SerialGC、单 CPU、`forkCount=0`；离线探针另用小堆独立 JVM。`native-owner-package-1` 的 SBOM 目标因 Maven offline 跳过，没有声称新生成 SBOM。

## 精确构件与离线副本

以下仅属于 **Chronicle 53562a2／StarCore b7817ae** 配对构件；后续菜单、音效、事件链提交尚不属于这个打包指纹。

| 构件 | SHA-256 |
| --- | --- |
| Chronicle JAR | `3561a787cf02e343778d22e16bbfc05b15ad12e5e1c68f969cb63c818e2726fc` |
| Chronicle sources JAR | `2791ae87ef5f9ca572fe0559f23b78102884558decb865a12afb580c8df4ae6` |
| StarCore shaded JAR | `f2ceb8757d285e8f5535aad2aef1913f68133694fd3c6190a39e0697f87cdf54` |
| StarCore original JAR | `b6d17e3ead2707f89602f2cca0ac5e201cf1cb50483b483a42c921b3cdbbe99f` |

`owner-pair-artifacts-1-20261127`：Chronicle 214 个旧公开类型／1,866 个公开受保护成员描述符，StarCore 3,010 个类型／35,883 个成员描述符保持；290／4,129 个项目 class 与冻结源码核对，8 个编译夹具通过。

`processing-merge-databases-owner-1-20261127-20261126`：13 个串行独立 JVM 在一次性副本上完成旧数据升级、续写、重开与四次 schema10 旧写入拒绝。旧副本原生 6／Chronicle 49 张表、上游副本原生 124／Chronicle 47／工厂索引 2 张表的既有列与记录保持；137 条加工、139 条资源原回执不重放。

`resource-rejection-artifact-1-20261127`：文件与 SQLite 各六阶段，永久拒绝在跨进程重开及余额增加后保持；新编号可提交，旧编号不能翻转；旧写入器拒绝后原状态不变。

## 失败与限制

本检查点原始证据：[owner-recovery-source-20261127-evidence.zip](verification/owner-recovery-source-20261127-evidence.zip)，6,392 个 ZIP 成员，20,997,456 字节，SHA-256 `9b1556eade417cf1ee971a7d68a07924de0e0ee336f5d96ca71ac935a67036ff`。逐成员 SHA 重新读取核验通过；保留实际失败日志／XML／命令及两库最终源码各一份，排除 JAR、class、可执行文件与内存转储。运行中的后续随机事件检查器候选不包含在这个证据归档内。

保留首轮科研／持久化、运行时、副本、资源拒绝、季节 HUD、称号、动画注册表及场景音效重载断言的真实失败。`native-random-scene-focused-1` 因 Java 重载歧义编译失败，实际执行 0 项；后续明确类型修复。失败不会被通过记录覆盖。

Chronicle 精确产品提交的 GitHub Actions [45920482469](https://github.com/addxiaoyi/StarChronicle/actions/runs/45920482469) 通过。StarCore [45977803474](https://github.com/addxiaoyi/starcore/actions/runs/45977803474) 的作业注解明确为账户付款失败或支出额度限制，作业没有启动；不是已完成的远端回归。本轮没有修改账单或系统设置。

仍有源码待办：随机事件检查器及效果执行的完整 owner 迁移、围城任务清理、跨区域方块／生成效果、其余实际加载的旧调度调用链与叙事 Boss；全库适当分批回归及最终构件重打包仍待完成。不能把未加载的独立 BossManager 声明当作实际调用链证据。

当前构件真实玩法／任务恢复／成员业务、双人 GUI、所选第三方组合、全栈 Folia、长期生产负载均待实服验收；不设置 `folia-supported:true`。外部来源没有持久操作 ID 的场景不承诺严格一次；完整启动证据扫描仍为 O(N)，不支持外部 `INSERT OR REPLACE` 改写历史。独立 PLAYER 撤回、拖拽编辑器和邮件附件编辑器没有确立为原始必做范围。
