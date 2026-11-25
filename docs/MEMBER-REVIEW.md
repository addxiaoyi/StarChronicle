# 成员退出、身份复核与历史保留

2026-11-25：成员换国或原国家消失后，剧情会进入待复核状态；退出会移除实际成员及活动个人票，保留原国家归属和历史选择。恢复资格需要当前原生身份验证。退出者不会被后台自动加入，完成实例的成员记录不能再退出或复核。

## 玩家命令与 API

```text
/chronicle member list
/chronicle member status <实例ID>
/chronicle member revalidate <实例ID> <当前节点ID>
/chronicle member exit <实例ID> <当前节点ID>
/chronicle join <实例ID>
```

命令要求 `starchronicle.use`，只操作发送者，不需要国家持有人身份。`list` 展示最近 50 条当前复核记录；尚未发生复核的正常成员不会出现在该列表。`status` 显示当前节点、原国家、此次收集的当前国家及参与中／待复核／已核验／已退出。变更操作必须携带当前节点，过期页面或命令被拒绝。

公开服务增加 `memberState(instanceId, playerId)`、`memberReviews(playerId, limit)`、`revalidateMember(instanceId, playerId, expectedNode)`、`exitMember(instanceId, playerId, expectedNode)`。`memberReviews` 的 limit 为 1–1000。Paper 服务自行收集玩家身份和权限，不接受调用方提交的国籍。服务扩展属于可信插件边界；玩家命令不能指定他人 UUID。

复核只恢复原国家绑定。换国后不能通过改 JSON、称号、普通 join 或当前君主身份改写原归属。退出后直接 revalidate 被拒绝；恢复原国家身份再显式 join 才会恢复成员资格。个人剧情只允许原拥有者重新加入，其他人仍不能加入。没有原生核验服务时，退出仍可执行，复核和退出后的重新加入会拒绝。

## 权威与持久化

`NationalVoteAuthority.withMembership` 在原生 NationModule 的同一变更锁内核对普通成员身份、国家存在性并执行本地事务；明确的无国家身份也需原生证明。旧适配器的默认实现拒绝复核。该权限不会授予国家投票权，国家票继续使用当前持有人核验；个人退出也不等于撤回国家席位。

在线身份轮询发现换国，或内置 `starcore.nations` 的完整快照发现原国家不存在时，记录 `PENDING` 并移除活动个人票。历史个人票不删除；显式复核后即使再次投同一选项，也新增一条修订，旧票不会自行恢复计数。已完成实例不再受这些观察修改。观察只能证明扫描时的状态，不能重建两次扫描之间发生又恢复的身份变化。

`story_member_reviews` 保存当前复核状态，新增的 `story_member_review_log` 按提交序列追加审计。状态、审计及实例成员／活动票在同一 SQLite 事务中变更；审计写入失败时整体回滚。退出时分别保存原国家和当前身份快照。原国家缺失扫描保留已有的身份观察，避免与在线换国扫描交替刷日志。

该表通过现有 schema 7 初始化中的 `CREATE TABLE IF NOT EXISTS` 添加。已有当前复核记录继续保留，不反推或补造过去的状态转换。升级前应正常停服并备份数据；本次三次启动场景验证新状态的恢复，贡献旧库升级的历史证据仍属于原贡献构件。

## 构件与验证

| 项目 | 结果 |
| --- | --- |
| 源码与验收工具提交 | `199a798` |
| StarChronicle JAR | `0eb0688e5932472fcfdae99420bb53433ae08b0e9e5014715dcab196e698fbd1` |
| StarCore JAR | `44f450eccc95590aadc54736109e012d5a52bbae459f4107afa498fd4528518d` |
| 主项目 `verify` | 94 套件、896 项，零失败／错误／跳过；正常 fork 与 JaCoCo |
| 原生构件 | 本轮未改源码；沿用 90 项专项与 7,202 项常规回归通过的构件（21 skipped，保留既有测试排除配置） |
| 成员专项 | 3 次正常启动，17 条实际命令响应、10 次 API 观察、4 次原生换国；4 条复核审计、5 条个人票、2 条共同决议、1 条终点历史 |
| 同构件 DUNGEON／WAR | 各 3 次正常启动；12／13 项原生提交、每路线 4 个援助事件和 8 条贡献、唯一结算及 A/B/D 各一份后续 |
| 证据回归 | 成员 15 项，史诗与贡献每路线 16 项；合计 47 项，全部通过，无跳过 |

新增测试覆盖当前成员核验、无国家身份、失效与重复回调、权限、原票失效、同选项新修订、退出恢复、个人实例拥有者、换国后退出的审计、过期节点／完成实例拒绝、SQL 回滚，以及在线／灭国轮询去重。

成员专项使用四个真实非 OP 客户端，D 为 A 国普通成员。它经实际 NationService 加退成员在 A/B 间往返，客户端实际发送投票、查询、复核、退出和重新加入命令。剧情来源明确标为隔离测试事件，不冒称原生战争或经济活动。独立检查器核对客户端响应与顺序、实时 API、服务端原始流、SQLite 历史票和审计，以及正常停服后的原生成员存档。

同构件另跑 DUNGEON／WAR 史诗与贡献回归。WAR 仍以原生和谈结束，DUNGEON 使用三秒原生生存房间；初始资金、资源及材料由隔离夹具提供。本次不覆盖生产服、真实战场胜利、全栈 Folia、第三方组合或所有旧库升级路径。

最终运行目录为 `run/paper-epic-0eb0688e-dungeon-member-review-1`（29660）、`run/paper-epic-0eb0688e-dungeon-member-regression-1`（29661）、`run/paper-epic-0eb0688e-war-member-regression-1`（29662）。九次服务端和九次客户端进程均正常退出。玩家原国家、旧票、审计状态或原生存档被修改，以及缺失 boot／查询或异常退出时，检查器都会拒绝；真实旧 WAR 保存失败日志也在三套负例中被拒绝。

[机器清单](verification/national-epic-members-20261125-summary.json)与[原始证据包](verification/national-epic-members-20261125-evidence.zip)保存当前和早期 12 个运行目录、构建日志、测试 XML、原生存档及验收工具。已核验 ZIP 完整性、2,145 个成员哈希和 12 份配置脱敏；ZIP SHA-256：`8642e1ef58d094706b3bc4b48fe5656cf9c96b865964ad33b09b20ccf640db78`。完整受验 JAR 和原生源码包另存本机 `target/national-epic-members-20261125/`，不包含在 Git 证据包中。新设备重新构建后用新指纹另开目录复验。

## 复验与保留记录

```bash
python3 tools/prepare-member-paper.py --attempt new-members --port 29660 \
  --starcore /path/to/starcore.jar --runtime /path/to/initialized-paper \
  --java-home /path/to/java21
# 使用上一步返回的目录；每次启动均等正常结束后继续下一次。
python3 tools/epic-paper-session.py --run run/PREPARED --java-home /path/to/java21 --node /path/to/node --boot 1 --scenario members-first
python3 tools/epic-paper-session.py --run run/PREPARED --java-home /path/to/java21 --node /path/to/node --boot 2 --scenario members-second
python3 tools/epic-paper-session.py --run run/PREPARED --java-home /path/to/java21 --node /path/to/node --boot 3 --scenario members-third
python3 tools/inspect-member-paper.py run/PREPARED
STARCHRONICLE_MEMBER_RUN=run/PREPARED python3 -m unittest discover -s tools -p test_member_acceptance.py -v
```

Node 使用 Mineflayer 4.39.0，设置 `EPIC_MINEFLAYER_MODULE` 为其安装目录，详细环境与史诗双路线命令见[交接报告](HANDOFF-2026-11-25.md)。负例检查需要 `STARCHRONICLE_EPIC_FAILURE_LOG` 指向保留的真实策略停服失败日志；缺失时明确跳过，不能报告全部负例通过。

早期 `cb860138`（893 项）和 `9a994570`（894 项）各三条路线均完成三次启动；前者未含灭国／换国交替轮询修复，后者未含个人实例退出后恢复。`e848d870`（895 项）三条路线仅完成首次启动，随后修正换国后退出的审计字段，因此该构件不算完整重启验收。上述目录、原始日志、初次编译失败以及逐次构建日志全部保留，不被最新构件覆盖。当前精确构件的结果独立记录。

阶段 5 的当前流程仍不包含把历史成员任意改绑到新国家、通用个人票撤回、管理员越权审批或强制恢复。精确无损全类历史、非史诗贡献来源、全栈 Folia、生产组合与容量目标继续推进，见[剩余工作](REMAINING-WORK.md)。
