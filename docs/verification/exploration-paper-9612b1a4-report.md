# 配置首访 Paper 检查点（已被后续修复取代）

插件 `9612b1a4fa8039e4115cd4f3da81d07e9a0782d5aa3f1b452a965f858d856e02` 在 Paper 1.21.11 上完成两次正常启动与退出。严格检查器核对真实行走、成功／取消传送、实际 GUI 选择、首访持久身份、重启重访去重与精确 5 点荣誉；两次停服数据库均为 schema 6 且完整，存在 2 条首访、2 条完成剧情、2 条 LIVE ballot、2 条 resolution 及 4 条 DONE 结果。

原始日志、准备与过程清单、机器检查结果逐字归档在 [证据目录](exploration-paper-9612b1a4/evidence-manifest.json)。运行目录为 `run/exploration-9612b1a4-paper-retry3`，两次 server/client exit 均为 0。独立离线检查及破坏副本反例全部通过；原始数据库和记录未改动。

这份报告只覆盖该冻结 Paper 构建。随后 Folia 实测发现 `teleportAsync` 不发布 `PlayerTeleportEvent`，旧插件因此漏记异步传送到访。修复增加玩家实体线程上的周期实际位置复核，产生新 JAR `8fdcf8d3…b423051`；本报告不自动覆盖新构建，也不证明旧构建兼容 Folia。失败的 Paper retry1（用户名长度）／retry2（测试客户端 lore 解析）与 Folia retry1 保留原目录，未合并成成功。
