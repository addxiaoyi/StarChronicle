# 配置地区首访 Folia 验收

新插件 `8fdcf8d375c22ca06c5bc15eb8e67b0ac58342b1e5a5636ea29307a88b423051` 在独立 Folia 1.21.11-14（`529aabc`）实服完成两次正常启动／退出，server 和 client 的退出码均为 0。普通非 OP 客户端完成真实行走、成功 `teleportAsync` 传送与两个实际 GUI 选择，未直接调用剧情选择 API 或制造探索事件。

本轮已支持的五项检查全部通过：真实步行到访、成功传送到访、实际 GUI 选择、重启后规范首访内容不变、再次到访不重复领取。两次正常停服的 SQLite 均完整，包含 2 条首访历史、2 条 ACKED 首访回执、2 个完成实例、2 条 LIVE ballot／2 条 resolution、4 条 DONE 结果，荣誉严格为 5。来源世界 UUID、范围指纹、坐标、事件哈希、个人索引和原始 ingress JSON 互相一致。

取消传送不入史一项明确为 `UNSUPPORTED`，机器值为 `null`，不计入通过项。保存的旧构建 Folia 探针中，`Player.teleportAsync` 接受并实际传送玩家，却未发布 `PlayerTeleportEvent`，因此无法利用该事件验证取消传送。本轮每次启动均记录 `CANCELLATION_UNSUPPORTED`，没有伪造取消事件。产品通过实体所属线程的实际位置轮询补足无事件到访；时间基准为 `OBSERVED`，不重建未观察到的瞬间穿越。

机器结论见 [summary](exploration-folia-8fdcf8d3-summary.json)，18 份逐文件原始日志、使用过的控制器／客户端、配置与检查点描述见 [清单](exploration-folia-8fdcf8d3/evidence-manifest.json)。正常停服数据库仍留在隔离运行目录 `run/exploration-8fdcf8d3-folia-retry1`；分发归档不包含运行数据库或第三方服务器 JAR。独立审查复跑严格检查器和 9 项工具回归，含证据篡改负例，全部通过；未改写运行证据。

这轮以 `require-starcore:false` 验证 Chronicle 自有的真实探索生产者，不验证原生国家权威或 StarCore 全栈 Folia。旧构建 `9612b1a4` 的 Folia 到访遗漏保留在原隔离运行目录，其失败不会因本轮通过而改写。相同新插件的 [Paper 验收](exploration-paper-8fdcf8d3-report.md) 另行记录，包括实际取消传送检查。
