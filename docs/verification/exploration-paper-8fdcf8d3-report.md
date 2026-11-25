# 配置地区首访 Paper 验收

新插件 `8fdcf8d375c22ca06c5bc15eb8e67b0ac58342b1e5a5636ea29307a88b423051` 在独立 Paper 1.21.11 实服完成两次正常启动／退出，server 和 client 的退出码均为 0。普通非 OP 客户端完成真实行走、成功传送与两个实际 GUI 选择，未直接调用剧情选择 API 或制造探索事件。

六项检查全部通过：真实步行到访、取消传送不入史、成功传送到访、实际 GUI 选择、重启后规范首访内容不变、再次到访不重复领取。两次停服 SQLite 均完整，包含 2 条 ACKED 首访、2 个完成实例、2 条 LIVE ballot／2 条 resolution、4 条 DONE 结果，荣誉严格为 5。来源世界 UUID、范围指纹、坐标、事件哈希、个人索引和原始 ingress JSON 互相一致。

机器结论见 [summary](exploration-paper-8fdcf8d3-summary.json)，逐文件原始日志、使用过的控制器／客户端、配置与检查点描述见 [清单](exploration-paper-8fdcf8d3/evidence-manifest.json)。正常停服数据库仍留在隔离运行目录 `run/exploration-8fdcf8d3-paper-retry1`；分发归档不包含运行数据库或第三方服务器 JAR。

这轮以 `require-starcore:false` 验证 Chronicle 自有的真实探索生产者，不验证原生国家权威或 StarCore 全栈 Folia。旧构建 Paper retry1／2 的测试工具失败和旧构建 Folia 的到访遗漏保留在独立目录；它们不与本轮合并计算。
