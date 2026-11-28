# `/social` 入口源码检查点（2026-11-28）

StarCore `2b41e71` 将已在 `plugin.yml` 声明的 `/social` 入口绑定到社会模拟模块。命令实例有独立的 `SocialCommandTasks`，入口复制参数并保留原玩家 owner；关系、影响力、声誉和状态查询通过有界 I/O 后回到原玩家 owner；新闻为内存查询，在原玩家 owner 发送并标读。排行使用真实四维 `getTopProfiles`，关系／影响范围／新闻有分页和溢出边界，新闻发送后逐条标记已读。模块停用会关闭两个命令任务并恢复命令处理器。

本批只做源码、单元测试和离线构件检查，没有启动 Minecraft、Paper 或 Folia，没有部署或合并主分支。

## 验证

- `run/native-social-command-focused-2-20261128`：231 项相关 verify，24 个必需套件，零失败、错误、跳过；源码无漂移，打包成功。Java 21、320 MiB heap、192 MiB metaspace、Serial GC、单处理器、`forkCount=0`，耗时约 264 秒。JaCoCo 跳过，离线 SBOM 未重新生成。
- `run/social-command-pair-artifacts-1-20261128`：Chronicle／StarCore 公开 API、项目类资源、sources JAR 和 8 个编译夹具通过。
- 新构件声誉／关系／影响力数据库副本未重新冒领前序 JAR 结果；本批没有新增持久化格式。

| 冻结构件 | SHA-256 |
| --- | --- |
| Chronicle | `3561a787cf02e343778d22e16bbfc05b15ad12e5e1c68f969cb63c818e2726fc` |
| StarCore shaded | `74426a06521ebbbc83cd3d92a54be7ac8bc57d452acd6c306f048b7147b458af` |
| StarCore original | `8f9a708d24540f8dcc1c0e62a9dfc3cab63aaf8af29bb097f3b8e6622b846ad9` |

证据 ZIP：`docs/verification/social-command-source-20261128-evidence.zip`，3,831 个成员，9,706,606 bytes，SHA-256 `ef25f100d09456a7f938dcaa9e5d4ff8d6817a95e9ef9096ed0477639f1fc92d`；成员已逐一读取核验。JAR/class 未提交 Git。

前序 Chronicle `ad77dc7` 的 CI run `46084176957` 10 步成功；对应监听器的原始 CI 已补交。StarCore `2b41e71` 本批 run `46086406272` 因账户付款／额度限制未启动，四项检查 runner=0、零步骤，发布 skipped；见[原始结果](verification/social-command-ci-20261128.json)。不计为远端测试执行。

首轮 `native-social-command-focused-1-20261128` 在测试编译失败：新增测试同时导入了两组同名声誉枚举，七处引用不明确。该轮没有执行测试；显式导入后第二轮通过，原始失败、日志及相关源码保留。

## 仍需验收

命令别名、真实多人 GUI、真实重启、MySQL、长历史负载、第三方组合及全栈 Folia 仍需相应环境验收。GossipVerification、联盟／活动／文化、SocialModule 的关系包装／推荐、领地收益与经验来源、政策／租赁／军队天气、兼容回调、叙事 Boss 和旧国家加成仍有源码工作。冷却、里程碑等运行时状态不提供跨重启严格一次语义；不设置 `folia-supported:true`。
