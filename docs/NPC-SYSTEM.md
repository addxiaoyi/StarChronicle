# NPC 人物、长期关系与条件对话

NPC 模块把角色定义、世界成长和每位玩家的关系分开持久化。它不会创建、移动、删除或接管第三方 NPC 实体，也不把对话文本解析成命令。所有写入只从可信世界事件或已授权的剧情效果进入，同一 SQLite 事务内保存反应回执。

## 文件与逻辑人物

`npcs.yml` 或 JSON 使用顶层 `profiles`。加载器严格拒绝未知字段、重复字段、无效表达式、重复绑定和非法阈值。默认提供三个逻辑人物：`ancient-heir`（伊蕾娅）、`siege-veteran`（老兵岑岳）、`caravan-archivist`（档案员青芜），与示范剧情的声望目标一致。

```yaml
profiles:
  caravan-archivist:
    name: "商路档案员青芜"
    background: "研究古驿道账本与度量衡的学者。"
    faction: caravan-library
    initialPosition: archivist
    growth:
      - {id: senior, threshold: 20, position: senior-researcher}
      - {id: curator, threshold: 60, position: library-curator}
    bindings:
      - {provider: FancyNpcs, externalId: "替换为服务器真实NPC稳定ID"}
    dialogues:
      - id: trusted-scholar
        priority: 50
        condition:
          all:
            - {fact: player.role, op: eq, value: scholar}
            - {fact: npc.trust, op: gte, value: "10"}
        text: "同行，欢迎回来。当前职位：{npc.position}。"
      - {id: default, priority: 0, text: "欢迎阅读商路档案。"}
    reactions:
      - id: open-research
        events: [CARAVAN_RESEARCH_PUBLISHED]
        reputation: 12
        trust: 12
        growth: 20
```

示例中的外部 ID 必须替换；默认资源的 `bindings: []` 不会假设服务器已经存在某个 NPC。`provider` 支持 `FancyNpcs`、`Citizens`、`Adyeshach`，匹配不区分提供者名称大小写，外部 ID 精确匹配。同一提供者的同一外部 ID 不能映射给两个逻辑人物。

`name/background/faction` 是配置元数据；`faction` 是叙事阵营 ID，不等于国家授权或自动加入某个真实国家。配置最大 1 MiB、最多 256 个人物；每人物最多 64 个成长阈值、32 个绑定、128 条对话和 128 条历史反应。

## 关系和成长状态

| 字段 | 存储键 | 范围与含义 |
|---|---|---|
| 玩家声望 | 玩家 owner 下 `npc.ID.reputation` | -1000 至 1000；与原有剧情 `reputation` 效果同一键 |
| 玩家信任 | 玩家 owner 下 `npc.ID.trust` | -1000 至 1000 |
| 玩家敌意 | 玩家 owner 下 `npc.ID.hostility` | 0 至 1000 |
| NPC 成长 | WORLD owner 下 `npc.ID.growth` | 0 至 1000000，属于角色本身，不按观看玩家重复累计 |
| NPC 职位 | WORLD owner 下 `npc.ID.position` | 初始职位或配置声明的成长职位 |

关系与成长增减在边界处饱和，不会整数溢出。缺失关系默认为 0，缺失职位按当前成长选择最高已达阈值；阈值顺序可以任意排列，但阈值数值与阈值 ID 不得重复。

每次真实成长变化后重新计算职位。可信 `position` 效果也可显式设置一个已声明职位；下一次成长变化会重新按成长阈值决定职位。因此行政升迁与成长模型需要协调，不应依靠任意文本授予不存在的职位。

`NpcLedger.applyEffect(Connection, UUID, type, npcId, value)` 支持 `reputation/trust/hostility/growth/position`。前四种 value 为整数增量，绝对值上限 1000000；position 必须是目录已声明职位。关系效果要求玩家 UUID，成长和职位是全局效果。调用方仍负责权限与稳定结果 receipt；此低层函数不是玩家可调用的任意设置接口。

## 对历史的事务反应

`NpcLedger.applyEvent(Connection, WorldEvent)` 必须由引擎在**已插入来源事件之后、同一事务提交之前**调用。它从 `events` 行读取原始 canonical payload，避免调用方用同 ID 的另一个参数替换参与者名单。

反应的稳定键为来源事件 ID、逻辑 NPC ID 和反应 ID 的确定性哈希，存入 `outcomes` 的 DONE receipt。事件行、关系、成长和 receipt 一起提交或回滚；重复回调与重启后重放不会再次增长。即便条件不成立，也消费这条反应 receipt，避免后来关系变化后重放旧事件产生追溯奖励。已上线反应 ID 应保持稳定。

关系反应只影响**原始历史参与者**，不会因为玩家在线或加入剧情就把他冒充为参战者。来源没有参与玩家时，NPC 仍可对世界历史增长或升职；不会凭空给在线玩家增加信任。

反应条件可引用当前持久化世界记忆、真实历史计数、事件 facts 和参与者既有关系。`event.TYPE` 并非类型键，应使用 `event.type`；例如 `{fact: event.type, op: eq, value: WAR_ENDED}`。反应函数不接受即时玩家权限或职业快照；这类条件应由可信剧情选择或包含明确证据的事件生产者处理。使用玩家／关系条件的反应，不会用一个不存在的匿名玩家去满足否定条件。

`growth/position` 每条匹配反应只对 NPC 执行一次；关系增量按符合条件的证据参与者分别执行。它们不会修改第三方 NPC 插件的阵营 AI、职位权限或真实国家状态。

## 条件对话

`NpcLedger.view(UUID, npcId, PlayerContext, worldFacts)` 返回不可变 `NpcView`：背景、阵营、当前职位、成长、声望、信任、敌意、匹配对话 ID、渲染文本和只读事实。玩家 ID 必须与可信上下文一致。

对话复用 `Condition` 的 `all/any/not/fact` 与 `eq/ne/exists/gte/lte/gt/lt`。事实包括：

- 可信当前 `player.*`、`nation.*`、职业与其他上下文事实。
- 数据库实际 `history.TYPE` 计数，调用方不能虚构历史数量。
- 数据库世界记忆及受信任调用方给出的 `region.*`、`choice.*`、`story.choice.*`。
- 全名 `npc.ID.reputation/trust/hostility/growth/position`，以及当前人物的简写 `npc.reputation/trust/hostility/growth/position`。
- `npc.id/name/background/faction`。

按 priority 从高到低选首个满足条件的对话，同优先级按稳定 ID 排序。无匹配项时提供普通说明性回退文本。缺失事实的数值／字符串比较不成立；需要确认和平应显式判断 false，不要用否定真来接受未知状态。

文本可使用 `{player.name}`、`{npc.position}` 等键占位符。渲染只做一次普通字符串替换，不执行代码、不处理奖励、不授予权限，也不会将 AI 文本作为事实。未知占位符保留原样，渲染结果最多 16384 字符。

## 跨插件接线与线程

```java
NpcCatalog catalog = NpcCatalog.load(dataPath.resolve("npcs.yml"));
NpcLedger npcs = new NpcLedger(sharedSqliteStore, catalog);

// 在引擎正在记录事件的事务中；不要另开事务或异步脱离原提交。
npcs.applyEvent(connection, committedEvent);

// GUI/NPC 交互入口先解析外部稳定ID，再在编排工作线程查询投影。
catalog.resolveExternal("fancynpcs:实际稳定ID").ifPresent(logicalId -> {
    NpcView view = npcs.view(playerId, logicalId, trustedContext, worldFacts);
    // 将 view 送回对应玩家实体线程展示。
});
```

NpcLedger 不拥有 SqliteStore，不关闭共享连接，也不自己开启外部 NPC 生命周期。低层带 Connection 的写入拒绝 autoCommit，避免误用脱离事务。`facts(UUID)` 与 `view` 会读取数据库，应在编排工作线程调用；不要在 Folia 玩家区域线程同步等待。

FancyNpcs/Citizens/Adyeshach 的交互钩子只负责取得真实提供者 ID 与玩家，NPC 服务只解析配置映射并返回文字。插件停用时卸载自己的监听器，不删除任何第三方 NPC。AI 提供者只能接收公开文本投影，不能持有 NpcLedger、数据库连接或效果执行器。

## 测试

`NpcSystemTest` 覆盖默认目录、JSON 外部绑定、未知字段、非法条件／阈值、不可变投影、持久重启、反应重复、事务回滚、canonical 参与者、无玩家的世界反应、角色／历史／地区／关系对话、成长职位、上下界与非追溯反应。最终运行数字以统一构建报告为准。
