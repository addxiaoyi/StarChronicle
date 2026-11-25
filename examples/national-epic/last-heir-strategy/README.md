# 《帝国最后的继承人》国家战略链

这是独立的可选内容包，不覆盖已开始的 `last-heir/1`。报名、援助、任务、外交与最终战役都需要真实服务和持久凭证。

## 安装

在配套原生回执接口齐全的 Paper 测试服显式启用：

```yaml
install-native-example-quests: true
install-epic-strategy-example: true
```

已提供 `chronicle_heir_records` 任务时可只开启第二项。默认关闭战略示例，启动只补充缺失文件。手动安装时复制两个 stories 文件与 `epic-campaigns/heir-strategy.yml`；`route-bindings.yml` 是说明，不能放进严格加载的 epic-campaigns 目录。执行 `/chronicle validate`、`/chronicle reload`。已开放会期保留原计划；修改内容应提升版本并新建会期。

任务示例是抄录证据用的 `chronicle_heir_records`；`COOPERATIVE_RELIC` 副本必须由服务器实际配置。内容包不会生成副本或完成任务。

## 报名与操作

将占位参数换为真实 UUID、实例 ID 和查询返回的最新修订号：

```text
/chronicle epic plans
/chronicle epic providers
/chronicle epic open heir-01 heir-strategy <救济国UUID>
/chronicle epic signup heir-01
/chronicle epic enroll heir-01 <会期修订号>
/chronicle epic seal heir-01 <会期修订号>
/chronicle epic status <实例ID>
```

open 需管理权限，报名与冻结需原生在任代表。至少两国报名，救济国不能报名自己。冻结前可用 `epic withdraw-signup <会期> <席位修订号> <会期修订号>` 撤回。剧情内用 `/chronicle national <实例ID>` 获取票据，再用 `/chronicle vote` 形成议决。

每项操作先 prepare，再用返回 ID 和修订号 apply：

```text
/chronicle epic prepare <实例ID> <类型> <目标> <原生参数> <providerID>
/chronicle epic apply <操作ID> <操作修订号>
```

每国依次完成：

| 类型 | 目标 | 原生参数 | provider |
|---|---|---|---|
| QUEST_GATE | chronicle_heir_records | investigation/records-evidence | starcore.quest.evidence.v1:<storageUUID> |
| TREASURY_AID | 救济国 UUID | 10.00 | starcore.treasury |
| RESOURCE_AID | 救济国 UUID | agricultural:10 | starcore.epic.resource_aid |
| DIPLOMACY_INVITE | 救济国 UUID | ALLIANCE_INVITE_V1 | starcore.epic.diplomacy |
| DIPLOMACY_ACCEPT | 发邀请的报名国 UUID | 已提交邀请的 nativeReceipt UUID | starcore.epic.diplomacy |

任务先通过真实 quest.await 完成；接受邀请由救济国代表逐国操作。资金与资源必须真实充足。UNKNOWN 用 `epic reconcile <操作ID> <修订号>` 只读对账，不能重发。

## 路线与结算

全部准备完成后，各国用 `epic route <实例ID> <战役修订号> WAR` 或 DUNGEON 表决，严格多数批准。再在剧情 route 节点投对应国家票，进入等待结算节点。

WAR：

1. 用 `epic founding-reference <新国名>` 生成参数。代表以 NATION_FOUNDING、未占用的新国家 UUID 和 starcore.epic.nation_founding prepare。
2. 同一玩家经原生治理交接并退国后 apply；原生服务承担建国费用和身份规则。
3. 以新国现任代表身份，用 WAR_COMMIT、防守国 UUID、WAR_DECLARATION_V1、starcore.epic.war prepare/apply。原生新国保护继续生效。
4. 战争真实结束并落盘后，用 FINAL_BATTLE、同一防守国 UUID、宣战回执的 war UUID、starcore.epic.war prepare/reconcile。

DUNGEON：每国经 StarCore `DungeonService.nationEnterDungeon` 国家队伍入口完成真实 COOPERATIVE_RELIC，用 DUNGEON_COMPLETION、副本 ID、成功 record UUID、starcore.epic.dungeon_completion prepare/reconcile。记录须在路线批准后产生，actor、国家、副本都须一致。普通单人入口不保存国家 ID，其记录不能用于国家战役结算；服务器入口集成须先通过 NationService 核验队员当前国籍，再调用国家队伍服务。

全部原生凭证提交后，剧情 war-await / dungeon-await 的 settle 选项开放。到达终点时同事务写入结算、旧都记忆和个人后续档案。投票通过不代表战争获胜，也不生成战果。

权限及候选接口见 [契约](../../../docs/NATIONAL-EPIC-CONTRACT.md)。最终双插件实服验收不由单元测试替代。
