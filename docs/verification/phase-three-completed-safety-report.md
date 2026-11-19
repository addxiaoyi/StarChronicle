# 旧完成实例保护：真实红例与修复回归

修复前，使用生产 `RuleEngine.stable(story,version,scope,source)` 和真实SQLite构造“已完成实例、残留pending、缺scope receipt”的合法旧表形状，实际运行4项测试，结果为4 failures、0 errors。分别复现恢复覆盖、重复accept覆盖、非标准旧ID产生副本，以及新版请求被旧活动版本消费。原始 [Maven日志](phase-three-completed-safety-red.log)、[Surefire XML](phase-three-completed-safety-red.xml)及[文本](phase-three-completed-safety-red.txt)保留，未改成成功日志。

该构造形状并未出现在上一轮实际V2备份中，不能将它表述为在那份实服备份复现。修复和schema4迁移后的完整回归共有514项通过，其中 `CompletedInstanceSafetyTest` 9项包含真实V2 DDL经生产构造器升级、并发、重启、缺receipt、有／无pending、旧非标准ID以及新版本等待。当前JAR及完整测试对应见 [TEST-REPORT](../TEST-REPORT.md)。

修复在实例创建边界检查稳定ID与语义tuple（含completed）；旧完成结果只补消费证明，不改原JSON、选择、开始／完成时间、honor或既有outcome payload。创建使用纯INSERT；推进只UPDATE未完成且身份tuple一致的行。不同活动版本不消费新版receipt。它没有通过删回执、清历史、重置旧图或跳过效果权限掩盖问题。
