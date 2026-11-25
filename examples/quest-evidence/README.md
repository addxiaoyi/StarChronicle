# 原生任务完成证据示例

这些是可选的新剧情文件，不覆盖默认剧情或已保存实例。它们使用 StarCore 的真实 CRAFT_ITEM 目标和原生任务界面，不另写目标解释器。

部署前安装 `patches/quest-evidence/` 中有构建与运行证据的 StarCore 候选版本，在 Paper 上确认原生 `QuestEvidenceService` 协议及持久存储域可用。随后设置 `install-native-example-quests: true`，再把本目录三份剧情文件复制到 `plugins/StarChronicle/stories/`。开关只注册三个无命令、无奖励的原生任务模板，不自动向旧剧情添加任务或门槛；在缺少受支持凭证服务时启动会明确失败。

| 文件 | 原生任务 | 模式 | 目标 |
| --- | --- | --- | --- |
| records.yml | chronicle_heir_records | PLAYER | 实际制作 PAPER ×3 |
| testimonies.yml | chronicle_siege_testimonies | NATION | 实际制作 BOOK ×1 |
| atlas.json | chronicle_caravan_atlas | PLAYER | 实际制作 MAP ×1 |

`CHRONICLE_RECORDS_OPENED`、`CHRONICLE_TESTIMONIES_OPENED`、`CHRONICLE_ATLAS_OPENED` 是服主选择接入的可信业务信号，并非本插件声称已经自动发现的探索、围城或贸易事件。扩展插件须通过 `ChronicleService.submit` 提交真实来源、稳定事件 ID、参与者和证据。没有信号不会虚构剧情启动。原生模板是否允许再次接取由原生任务定义决定；一个旧的“曾经完成”标志不能满足新运行的门槛。

国会示例的 quorum 是两个真实国家；同国增加参与人数不会增加席位。原成员完成任务并不自动投票，国家投票仍需实际当前持有人通过 `NationalVoteAuthority` 验证。示例的历史效果只记录决议，不自动建造建筑、扣除物资或改变国家外交状态。

详细协议、恢复和运维边界见 `docs/QUEST-GATES.md`。尚在开发或未实测的候选版本不能按上述部署步骤视作生产认证。
