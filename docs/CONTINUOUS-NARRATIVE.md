# 跨剧情选择、范围历史与后续事件

这部分是第一阶段之后新增的实际执行链：正史事件和投票日志进入同一 SQLite 事务，后续剧情使用服务端当前身份读取它们。它不修改 StarCore 的任务目标、国家模型或原生脚本解释器。

## 历史事实的范围

旧 `history.TYPE` 保持原来的全世界计数含义，只有已经出现的类型才有该键。包括 `nation.X`、`world.X`、`player.X` 在内的旧事件类型都逐字保留；它们不会被重新解释成新作用域。

新作用域使用独立的命名空间：

| 条件事实 | 实际数据来源 |
| --- | --- |
| `chronicle.history.world.TYPE` | 全部已记录事件 |
| `chronicle.history.nation.TYPE` | 当前真实国家与 `event_nations` 的关联 |
| `chronicle.history.player.TYPE` | 当前玩家与 `event_players` 的明确参与关联 |

同一事件关联两个国家，全世界仍只计一次，对其中每个国家也各计一次。在线国民、旁观者或广播受众不会因此变成原事件的历史参与者。换国只改变国家视角，不改写个人旧历史。

对条件明确请求的新作用域类型，已知主体没有匹配事件时返回字符串 `"0"`。未知国家或未知玩家的对应键缺失，继续遵守三值逻辑；不能用零冒充未知身份。判断发生过应使用 `gt: "0"`，而不是 `exists`。旧的 `history.TYPE` 不补零。

```yaml
condition:
  all:
    - {fact: chronicle.history.nation.WAR_ENDED, op: gt, value: "0"}
    - {fact: chronicle.history.player.HEIR_RESTORATION_SUPPORTED, op: gt, value: "0"}
```

所有计数直接查询原正史及索引，不维护第二套计数。来源在同一事务中已经插入时，当前事件也计入；延后恢复的触发读取恢复当时已记录的历史，不声称这是事件发生瞬间的全世界快照。

## 个人投票与共同决议

本实例原有 `choice.NODE`、`story.choice.NODE` 不变。跨剧情使用以下显式引用：

```yaml
condition:
  all:
    - {fact: past.player.choice.last-heir/1/council, op: eq, value: oppose}
    - {fact: past.resolution.world.last-heir/1/council, op: eq, value: support}
```

上例表示这个玩家投过反对票，但共同决议选择支持。不能把胜出结果覆盖到败方的个人选择，也不能给没有投票的成员补写同意。

| 引用前缀 | 主体与规则 |
| --- | --- |
| `past.player.choice.STORY/VERSION/NODE` | 该玩家实际投出的最后有效票；换国后仍属于原玩家 |
| `past.resolution.world.STORY/VERSION/NODE` | WORLD 实例达到法定人数后的共同决议 |
| `past.resolution.nation.STORY/VERSION/NODE` | 当前真实国家对应 NATION 实例的共同决议 |

只投影已完成实例。多次游玩时选择最新已提交日志序号的匹配记录；它表示本数据库记录顺序，不是来源事件发生时间，也不是按完成时间重排。版本必须明确，世界与国家共同决议不能混用。

个人每次真正改投会追加 `BALLOT` revision；相同投票重试不加行。达到 quorum 才追加唯一 `RESOLUTION`。日志、实例迁移、荣誉、称号、NPC 反馈、结果历史和外部 outbox 同事务提交或回滚。完成结局先在事务内保存已完成状态，再执行结果效果，因此同一结局事件的 NPC 反应可以读取刚刚完成的选择；事务提交前不会向其他线程宣布结果。

当前目录校验会检查引用的故事、版本、节点、选项和作用域。`past.*` 支持 `eq`、`ne`、`exists`，可组合 `all/any/not`。所有引用都验证，只有激活和所有可完成路径无法绕过的引用形成硬依赖；`NOT exists` 首次参与分支、独立 `ANY` 回退和可选分支不会被误判为必需循环。

当前版本的**新配置引用校验仍以当前加载目录中的故事版本为依据**。退休或删除的定义尚无独立的历史版本目录；已有实例／待触发的旧快照与公开只读 API 仍能查询保存的旧版本记录，但不能据此声称任意退休版本的新内容引用已经支持。

## 结果历史会真正排入后续剧情

`history` 效果不再只写正史。新事件、参与者／国家索引、NPC 反应、因果证据以及匹配后续定义的 `story_triggers` 同事务落库。规则读取当次已加载的后续定义并保存快照。无人在线也不会丢掉后续；登录和后台轮询使用当前权威身份恢复。

效果不会递归调用公开 `accept`、等待玩家线程或在事务中重新执行整条故事。每次有界 drain 处理已入箱工作，结果再排入下一批。来源／作用域 receipt、稳定实例 ID 和本地结果 receipt 共同防止重启或重放重复领奖。

派生事件的 `event_causes` 记录真实父事件、已知链根、深度和已经经过的故事 ID。`WorldEvent.facts` 即使伪造同名描述也不能写入这些内部字段。V1/V2 没有保存的祖先链保持未知，以原存档事件作为已知链的边界，不倒推不存在的证据。

当前目录的历史生产者→消费者图会拒绝直接及间接循环。旧 pinned 定义和后来配置仍可能组合出新环，因此运行时另有保护：

- 同一因果链不能再次进入已经经过的故事 ID，即使版本变化。
- 最多经过 32 个故事；边界结果历史照常保存，其后续触发被拦截。
- 拦截记录保存在 `story_trigger_blocks`，管理员通过 `/chronicle blocked` 查看来源、目标版本与原因。

这里的拦截表示对应后续未启动，和外部效果已经可能发生的 `UNKNOWN` 不同。它不会修改原历史、盲目重放、发奖或自动解除外部隔离。

## 实际发布的后续：旧都档案

`stories/heir-legacy.yml` 消费《帝国最后的继承人》的三个结局事件，并按真实个人参与历史生成 PLAYER 实例。玩家可以保存实际投票、以未投票见证者身份说明，或对照共同决议。三条完整结局有独立历史与稳定荣誉／称号回执。

只有原叙事结果明确列出的参与者满足个人历史前置条件。晚来的同国玩家虽然可以读取国家历史和世界决议，也不会因此被补写为参与者或得到该个人后续。NPC 默认目录加入个人异议对白；已有自定义 `npcs.yml` 不会被覆盖，管理员可自行合并该对白。

NPC 条件与剧情共用正史和选择投影。对白视图使用当前真实玩家／国家；单个来源事件没有给出玩家当前国籍时，NPC 反应不能挑选 `event.nations` 的第一个国家代替。匿名来源也不会满足依赖个人历史的成长反应。纯文本占位符支持斜杠引用，例如 `{past.player.choice.last-heir/1/council}`；缺失键保持原文，返回文本不会作为命令执行。

## API、命令与数据升级

```java
service.recollections(playerId, List.of(
    "past.player.choice.last-heir/1/council",
    "past.resolution.world.last-heir/1/council",
    "chronicle.history.player.HEIR_RESTORATION_SUPPORTED"
)).thenAccept(facts -> {
    // 只读不可变结果；如需操作 Player，另回该玩家的 entity scheduler。
});
```

一次最多请求 256 个明确引用，只返回被请求且可核验的键。调用方不能提供假国籍或权限。玩家命令 `/chronicle past last-heir/1/council` 展示本人与可用共同决议，仍由服务端收集身份。

schema 3 将旧实例仍保存的最后个人选择／共同决议导入 `story_choice_log`，标为 `LEGACY_INSTANCE`；原投票时间和提交人保持 `NULL`。无法恢复已经覆盖掉的中间改投。迁移按旧实例时间／ID及键的确定顺序导入，整笔 schema、日志和版本推进同事务完成；异常回滚，第二次开库不会再次导入。原事件 JSON、哈希、时间、实例、触发和回执保持原值，只有此前 `RUNNING` 外部效果按既有规则隔离为 `UNKNOWN`。

升级前正常停服并备份完整插件数据目录。已有 V2 结果不会自动补排后来才安装的新后续定义；本功能不伪装为恢复旧版从未保存的触发资格。升级后新产生的结果（包括旧 pinned 故事此后完成的结局）使用新入箱链路。

默认仍安装缺失示范故事。正式服如需删除或重命名示范文件，先设置 `install-default-stories: false`，再保留至少一条有效自定义剧情；该开关不会删除或覆盖已有文件。
