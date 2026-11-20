# 配置地区首访

StarChronicle 可将玩家首次进入管理员配置的区域记录为 `EXPLORATION_DISCOVERY`，供现有探索剧情规则使用。此来源只表示服务器第一次观察到该玩家进入这个明确定义的区域，不表示首次发现任意程序生成的地形。

在 `plugins/StarChronicle/config.yml` 中取得世界 UUID 后，添加地区：

地区配置在插件启动时验证并固定。修改地区或开关后应正常停服并重新启动；`/chronicle reload` 只重载剧情定义。

插件在移动／传送事件后回到玩家实体调度器确认实际落点，并在正常上下文轮询时复核实际位置。这样也能记录不会发布 `PlayerTeleportEvent` 的 Folia `teleportAsync` 到访。来源时间始终是首次被插件确认的观察时间，不冒称精确移动发生时刻；只短暂经过且在任何确认或轮询前已离开的区域不会被补造为到访。

```yaml
exploration:
  enabled: true
  regions:
    - id: old-caravan-road
      world: 12345678-1234-1234-1234-123456789abc
      min-x: -120
      min-y: 50
      min-z: 40
      max-x: -32
      max-y: 110
      max-z: 128
```

地区 ID 只能使用小写 ASCII 字母、数字、点、下划线和连字符。坐标范围闭合，每次方块移动或传送进入范围时，由玩家实体所在调度器检查。世界 UUID 和全部边界共同形成持久范围指纹。已登记的地区 ID 不能在另一个世界或不同边界上复用；要重新划定区域时，应使用新的 ID，并保留旧名称表示旧范围。

一个玩家／地区只写入一条首访事件。事件 ID 由玩家 UUID 与范围指纹决定；区域事实包括可信的当前国家（若存在）、世界 UUID、方块坐标、来源和观察时间。玩家实际到达后的实体线程采集不可变快照，经最多 2048 项的有界缓冲写入 SQLite pending ingress，再由现有规则引擎消费。工作队列满时保留已接纳的快照，正常停服会在关闭数据库前排空；缓冲自身已满时明确拒绝并在日志保留诊断，后续真实移动可重试。断电若发生在 SQLite 提交前，尚在内存的观察不具有持久确认；提交后的 pending 与确认回执均跨重启保留。正常重投使用相同事件，已经确认的事件不会再次触发剧情。

该能力默认关闭，最多支持 256 个区域。它不读取或扫描未配置区域，不生成地图上的纪念设施，也不将普通采集解释为世界探索。后台 GUI 选择仍按剧情配置的身份、科技与其他条件重新验证。

可将 [首访示范剧情](../examples/exploration/first-visit.yml) 放入服内 `stories/` 并验证重载。该剧情绑定 `old-caravan-road`，由一次真实到访提供入口，调查／暂离分支仍由玩家在剧情 GUI 中选择。已有《丝路学者》同样监听探索事件，并继续要求可信身份与科技条件。

2026-11-20 的当前冻结构建 SHA-256 为 `4ede86a8bb6315776184c612a488f0c9ebc0321ccef73ce84ddfb097c1f374ca`，[Maven 构建记录](verification/owner-consumers-build-summary.json) 包含 800 项测试，失败、错误和跳过均为 0。该 JAR 分别完成 [Paper 两次启动验收](verification/exploration-paper-4ede86a8-report.md) 和 [Folia 两次启动验收](verification/exploration-folia-4ede86a8-report.md)：真实步行、实际传送、GUI 选择、重启规范来源和重复到访幂等均通过，两轮最终荣誉都保持为 5。快照、界面与投递均使用所属调度器捕获的玩家实体，避免重连后再次按 UUID 解析到替换会话。

Paper 还验证了实际取消传送不会生成首访。冻结 Folia 的 `teleportAsync` 探针未发布可取消的 `PlayerTeleportEvent`，该项明确记为 `UNSUPPORTED`／`null`，不计为通过；Folia 的 PASS 仅覆盖其余五项。两种平台均以 `require-starcore:false` 验证本插件探索能力，StarCore 全栈边界见 [兼容性说明](FOLIA-COMPATIBILITY.md)。原始日志、协议客户端、控制器、配置和正常停服检查点均在报告链接的哈希清单中保留。

本轮由隐藏独立控制器按固定协议顺序运行，按 PID 与创建身份核验所属进程，命令只发送一次，仅接受发送后游标之后的本 boot 响应。两平台每次服务端与客户端退出码均为 0，严格检查后再归档；每平台另有 9 项证据回归通过。此前 `8fdcf8d3` 的 [Paper 报告](verification/exploration-paper-8fdcf8d3-report.md) 与 [Folia 报告](verification/exploration-folia-8fdcf8d3-report.md) 继续作为独立历史检查点保留，不替代当前构建验证。
