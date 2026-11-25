# 国家议事候选构件交付

本次在独立分支 `codex/national-council` 交付正式国家席位扩展。它沿用已有剧情执行、原生任务投递、持久事件和结果队列，不重写已接受的旧剧情定义；默认未声明投票单位的内容仍按PLAYER运行。

## 构件与完整源码

- 插件：`starchronicle-0.1.0-SNAPSHOT.jar`，SHA-256 `792a84e345c4478b3731e7bff426c30d1aa21e1f1be499a967ed3e7a4aaa4694`。
- 生产Java源码JAR：`starchronicle-0.1.0-SNAPSHOT-sources.jar`，SHA-256 `a0a0c5ed3913a760bb59f098146c812741bed8c321ccbbdfe286d7f10f56c642`。
- 完整交付ZIP的 `artifacts/` 保存两项构件，`StarChronicle/` 保存全部工程、测试、资源、可选完整国家剧情、API文档、上游审计、验收工具和归档证据。
- `MANIFEST-SHA256.json` 记录构件、每个项目文件和实际构建报告的校验值。运行世界、数据库、第三方插件JAR、缓存和凭据不进入交付包。

## 实际验证与边界

本地Java21 `clean verify` 与固定提交的Linux CI均通过674项测试，失败／错误／跳过均为0。插件与源码JAR已逐文件核对当前生产源码与资源；完整构建指纹见[测试报告](NATIONAL-COUNCIL-TEST-REPORT.md)。

[真实Paper验收](verification/national-council-paper-report.md)使用同一候选指纹，三次正常多人启动完成51/51项观察；它核对原生国家当前持有人、继任、同国一席、CAS、撤回重启、灭国票失效、冻结历史、跨国资格及稳定本地奖励。控制器预检、离线检查重连和上游Windows目录fsync问题单独保留，不隐去失败日志。

[真实Folia独立降级验收](verification/national-council-folia-report.md)使用同一候选指纹完成28/28项检查；四名真实玩家只读查看、投票／撤回默认拒绝，15张受保护表保持一致，服务器与客户端均退出0。没有原生国家服务时禁止投票和撤回，不根据旧存档、OP、称号或职业补造当前国家资格。此交付不认证未移植的StarCore全栈Folia。

## 使用

按既有[安装说明](../README.md)安装审计版本StarCore与插件；升级前正常停服并一致备份SQLite。schema5保留旧历史、结果回执、原始定义与来源证据，新增国家账本及版本格式sidecar。不能把已记录国家票当成原生财政、领土或外交操作已执行。

[国家议事协议](NATIONAL-COUNCIL.md)说明API、命令、权限、修订令牌和服务合同。`examples/national-council/council.yml` 与 `aftermath.yml` 是完整可选内容，复制到测试服stories目录后一并validate/reload；它们不默认覆盖既有三条主线。真实来源必须由可信业务提交后发布。

## 完整目标继续推进

正式国家席位是国家级长期史诗的基础。真实资源援助、任务运行完成闸门、遗迹与战役链、长期贡献和自动世界记忆仍按[剩余工作](REMAINING-WORK.md)推进；不能把提案文本、接取成功或接口存在当作实际完成。新的[任务完成证据审计](QUEST-AWAIT-UPSTREAM-AUDIT.md)记录固定上游缺少持久run/completion回执的边界。
