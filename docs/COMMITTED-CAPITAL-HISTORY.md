# 原生首都指定与迁移历史

2026-11-26：Chronicle `4ae05b0` 已接通配套 StarCore `93ff6bfb` 的独立首都提交日志，保留真实指定人、国家、前后城市及提交时间。主项目934项完整回归、原生14项首都专项、原生7,234项常规（21项既有跳过）、Paper139条历史的三次启停及13项证据破坏回归通过。十阶段整体仍在推进。

## 来源与迁移

原生新增 `NationService.capitalHistoryAuthorityDomainId()` 和 `capitalChangesAfter(sequence,limit)`；完整回执为 `CapitalChangeReceipt(authorityDomainId,designation)`。原 `CapitalDesignation` 以及指定／当前／旧分页API签名保留，公开基线只有新增。存储域独立于建国、科技和贸易。

`NationCapitalDesignationStore` 使用 `nation/capital-designations.log.sqlite`，在同一 SQLite FULL 同步事务中保存唯一operation、连续序号、指定人与前后城市，并更新提交位置。新 `Instant` 保留纳秒；不按时间做游标，时钟倒退仍依提交序号读取。重复operation检查国家／目的城市／原指定人，返回原时间及记录；既有当前首都变化不会让重试新增历史。`NationModule` 仍核验真实国家、当前国主和城市本国归属，重试也不绕过当前授权。

旧 `.log` 首次迁移为 `.log.legacy`，逐字节保留未提交尾部；只将有完整换行、连续序号、唯一operation和正确前城市链的记录导入。原毫秒时间保留，不伪造纳秒。原 `.log` 成为含域与归档SHA的 MIGRATING／READY 标记，迁移锁跨进程串行化。导入事务完成但 READY 尚未发布时可恢复同域；旧插件不能解析新标记，因而拒绝继续追加分叉首都。

正常重启完整检查历史；日常查询使用序号／国家索引，不扫描全部历史。来源库丢失、域更换、已提交尾部丢失、请求游标超前和已经观察到的序号倒退会拒绝继续。启动前后不支持人工替换或编辑源文件；把同域整套备份回滚时必须恢复匹配的 Chronicle 游标。没有声称可检测任意篡改或同时回滚所有文件。首次旧日志迁移会加载原文本，长期容量另行验收。

升级及回退必须正常停服并备份整套国家、城市、首都标记／SQLite／legacy与Chronicle数据库。不能删除标记让新库重新初始化；旧插件回退必须还原对应旧备份。国家模块首都SQLite独立于主 `starcore.db`，跨设备迁移不能漏掉它。

## Chronicle 行为

`StarCoreCapitalHistory` 每次读取最多128条，验证域、连续序号、UUID、时间与真实变更，随后将游标和 ingress 同事务提交。首次追赶仅归档；追到来源末尾后，后续实时／离线补读正常进入剧情收件箱。失败保留游标，消费成功才ACK。

首次指定为 `CAPITAL_DESIGNATED`，迁移为 `CAPITAL_MOVED`；稳定事件ID为 `starcore:capital:域:operation`。参与者仅包含原指定人，国家取原回执；事实包含 `city.id`、`capital.city`、可选 `capital.previous`、`capital.change`、`actor.id`、`nation.id`、`history.*` 和 `time_basis=COMMITTED`。城市等级、缓存Location和当前国主不会重写历史。不额外赋予贡献、不重写旧OBSERVED条目；内置首都快照保持不可用，避免再推测一条迁都。规则API见 [API.md](API.md)。

## 精确构件与验证

| 构件 | 源码 | SHA-256 |
| --- | --- | --- |
| Chronicle | `4ae05b0` | `00922cb57cf712b7afc65c78bf7c5648a97dd5c4665c7f7b9a1ab9d81f739df7` |
| StarCore | `93ff6bfb` | `8f4288858c77f1a8f7a50457a60a399f46fed6dd9a9edc7b2a3ae7b4921bb7cf` |
| Paper1.21.11-132-c5eb079 | Java21 | `5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba` |

Chronicle100套件／934项零失败、错误、跳过；新增8项首都来源测试及1项快照抑制测试。115个Java源文件逐个与sources JAR比对，API.md与最终产品JAR一致。日志 `target/capital-chronicle-verify-20261126-1.log`；此前22项聚焦日志 `target/capital-history-tests-20261126-1.log` 不含随后新增的快照测试。

原生1,437套件／7,234项零失败、零错误，21项既有跳过，排除范围未扩大。首都专项共14项，包括30,000条分页、旧日志迁移及尾部保留、缺库／换域／截短／旧备份、事务提交失败回滚、两个store和并发重复请求、迁移发布中断、operation和前城市链、现任国主授权。首次全量因新增公开API基线失败；审核只有新增10条签名后更新基线。第二次所有测试通过，随后4文件格式检查失败。Spotless修正格式后 `-DskipTests verify` 成功，不将该步骤记为再次执行全部测试。全部真实失败日志和首次失败reports已归档。

实服目录 `run/paper-capital-00922cb5-20261126-1`，回环29661。固定离线fixture账户通过真实原生服务建立两国／三城；在本国两城间指定迁移。来源域 `80c4545d-5fe2-4b19-b3c0-53bc81b7b1a3`。

| 启动 | 原生提交位置 | Chronicle已消费 | 结果 |
| --- | --- | --- | --- |
| 1 | 136 | 6 | 初始3条只归档，随后3条进入收件箱；停用Chronicle后真实转让国主，分tick提交130次迁都 |
| 2 | 139 | 139 | 跨128条分页补齐130条离线变更，再提交3条；旧actor不变，新actor按回执记录 |
| 3 | 139 | 139 | 两次公开查询及持续轮询均不增加历史或收件箱 |

每轮拒绝非当前国主、异国城市及相同城市指定，原operation重试保持原记录。最终139条历史／136条剧情收件箱／3条初始归档、零pending，三次均正常退出0。独立检查器比对真实原生国家／城市文件、首都SQLite／迁移标记、完整公开回执、Chronicle ingress／events／收件箱及公开history API，并检查纳秒、原actor、前后城市和跨重启稳定性。13项真实证据破坏回归零跳过，覆盖伪造现任国主、修改城市链、丢记录／时间精度／授权证据、提前激活、缺收件箱、源告警和异常退出。

范围为离线fixture身份调用真实服务，没有在线客户端／命令GUI、生产部署或旧生产库整服升级验收。没有认证全栈Folia、MySQL、第三方经济或新构件完整五插件史诗组合。此前贸易、科技和完整组合证据仍按原指纹保留。

## 跨设备复验

```bash
env MAVEN_OPTS='-Xmx768m -XX:+UseSerialGC' ./mvnw -B -ntp verify
python3 tools/capital-history-paper.py prepare --starcore /path/to/starcore/target/starcore-1.0.0.jar --runtime /path/to/initialized-paper --attempt NEW-ATTEMPT --port 29661 --java-home /path/to/java21
python3 tools/capital-history-paper.py run --run run/PREPARED-DIRECTORY --java-home /path/to/java21 --boot 1
python3 tools/capital-history-paper.py run --run run/PREPARED-DIRECTORY --java-home /path/to/java21 --boot 2
python3 tools/capital-history-paper.py run --run run/PREPARED-DIRECTORY --java-home /path/to/java21 --boot 3
python3 tools/inspect-capital-history.py run/PREPARED-DIRECTORY
STARCHRONICLE_CAPITAL_RUN=run/PREPARED-DIRECTORY python3 -m unittest discover -s tools -p test_capital_history.py -v
```

准备器只接受新attempt和独立回环端口，失败不可覆盖；`--runtime` 包含固定Paper的paper.jar、cache、libraries。Java21和Python3.11+足够，不依赖客户端Node。两个仓库的分支均为 `codex/national-epic-linux-acceptance-20261125`；本机只在 `/home/add/starcore-national-epic` 操作原生候选，不改另有工作的 `/home/add/starcore`。

[清单](verification/capital-history-linux-20261126-summary.json)与[证据包](verification/capital-history-linux-20261126-evidence.zip)包含8,660个成员／24,413,169字节，SHA-256 `6da540d7c48b1431568c7e1857c56b2a9e66b10b80101450529331832bc97671`。两个仓库源码指纹、全部实际XML、首次失败reports、所有构建日志、三个停服快照、工具和公开API证据均已归档。一个生成密钥已脱敏，ZIP及每成员哈希已核对；冻结JAR留于 `target/capital-history-linux-20261126/`。本机已停服，29661释放。
