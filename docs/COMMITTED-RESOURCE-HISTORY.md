# 国家资源提交历史与不确定写入恢复

2026-11-26：Chronicle `d7df0ca` 接通配套 StarCore `3da33a00` 的资源提交历史，完整保留每笔国家／资源的前后余额、原因和原生事务引用。主项目945项与原生7,251项完整验证通过；原生21项既有跳过、零失败／错误。普通资源变动不计为生产贡献，也不重复计入已有援助／贸易贡献。十阶段整体仍在推进。

## 原生权威与恢复

新增独立注册服务 `ResourceHistoryService`，通过服务注册表或 `StarCoreApi.service(ResourceHistoryService.class)` 获取。`historyAuthorityDomainId()` 返回独立域；`resourceChangesAfter(after,limit)` 按连续序号返回1..128条。`ResourceChangeReceipt` 保存操作UUID、类型、原生引用／分类／说明、可空actor、纳秒时间，以及最多128项国家／资源的实际前后余额。既有库存只建立起点，不推造安装前历史；饱和grant只记录实际增量，无变化不新增回执。

`ResourceHistoryStorage` 将库存、唯一提交令牌、连续序号与单条pending同事务提交。下次提交前，将pending幂等归档为 `resource/history/<domain>/<bucket>/<sequence>.receipt`；每目录最多1024条。归档失败阻止下一次资产提交，重启可补最后一条。`authority-domain`、`high-water`、已观察序号与最后前驱检查防止缺源、换域和回退；较早的损坏在请求该页时拒绝，启动不全量扫描终生历史。

提交前失败不产生历史；已提交但回包丢失时，只读回持久提交令牌／回执／余额来确认，不再次执行资产操作。无法读取确认则阻止后续库存读写及旧内存停服保存。读写共用模块锁，资源恢复不会替换调用者持有的工厂／贸易对象。单国幂等回执新增原始Instant字段，保留旧毫秒字段并交叉核对。

SQL模式启动时固定，`resource/resource-sql-authority` 用 MIGRATING／READY 防止数据库不可用时恢复陈旧文件。首次迁移在一个事务导入完整库存与旧转账回执；普通保存与转账共用提交队列，禁止SQL失败fallback。首次失败可恢复迁移，已有标记后不再读旧文件。本次修复了先写authority造成库存漏迁移的路径。

这是单个原生模块写入者的提交协议，不是多服务器共享SQL事务。备份须整服停止并同时保存数据库、resource历史与SQL标记、国家数据和Chronicle库；禁止删标记重新初始化或将旧Properties替代SQL。旧原生版本不理解该协议，回退须恢复匹配备份，不能假设任意旧版本都会主动拒绝资源写入。

## Chronicle消费与兼容

来源为 `starcore.resources`，事件 `RESOURCE_BALANCE_CHANGED`，稳定ID `starcore:resource:<domain>:<operation>`。来源页先校验域、连续序号、类型和余额；游标、事件及完整回执ingress一起提交。首次追赶只归档，后续实时／离线补读进入剧情收件箱。

消费事务一起提交完整 `ResourceHistoryReceipt`、历史及收件箱；完整回执缺失时不能ACK。`ChronicleService.resourceHistoryReceipt(eventId)` 提供详细查询，事实中保留原因、原生引用、条数与SHA。现有原生资源API没有个人actor，回执保留null，不用当前国主或当前国籍补造主体。资源余额不是生产完成凭证，也不为转账或贸易再次加贡献。公开合同见 [API.md](API.md)。

schema9使版本8及更早插件拒绝打开升级库，防止旧消费者跳过完整资源证据。使用此前真实首都Paper的schema8数据库副本升级：43张旧表、753行原数据保持不变；只新增资源回执表。随后运行旧 `00922cb5` 实际JAR，确认拒绝打开该schema9库。证据在 `target/resource-schema-upgrade/`；这是离线库升级与旧JAR验证，不是生产整服升级。

## 固定构件与测试

| 构件 | 源码 | SHA-256 |
| --- | --- | --- |
| Chronicle | `d7df0ca` | `17508f9f9842b5e815db6d0b6a5c75a2f9d1fd7c35678a67b31d56268bea783f` |
| StarCore | `3da33a00` | `6f18f01f4ebaa7c54c70eded6647202eb7ee3f9c0103c925c90347924f50c850` |
| Paper1.21.11-132-c5eb079 | Java21 | `5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba` |

Chronicle101套件／945项，零失败、错误和跳过；最终完整日志 `target/resource-chronicle-verify-20261126-3.log`。新增10项资源来源／原子消费测试与1项schema8升级测试。119个Java源码逐个与最终sources JAR一致，API.md与产品JAR一致。早期schema8构件的944项通过；首次schema9全量因一个旧测试硬编码版本8失败，更新该断言后最终全量通过，保留失败XML和日志。原有历史字节保持等检查未减弱。

StarCore1,439套件／7,251项，零失败／错误，21项既有skip，未扩大排除；`target/resource-native-verify-20261126-1.log` 包含成功API兼容与Spotless检查。资源专项38项，其中17项新增，覆盖SQL故障回滚、库存／旧转账同事务迁移、固定权威、文件重启、跨128分页、饱和实际增量、纳秒、幂等、丢回包与读失败隔离、归档阻塞、缺记录／换域／旧快照／不一致余额。

## 隔离Paper验收

最终目录 `run/paper-resource-17508f9f-20261126-3`，回环29661，来源域 `70c3bad4-5244-46d0-9e0f-310282f98c15`。固定离线fixture身份通过真实国家服务建两国，并调用真实资源grant／consume／单国幂等事务／两国转账；未直接编辑源数据库来伪造历史。

| 启动 | 原生提交位置 | Chronicle消费 | 国家剧情收件箱 | 结果 |
| --- | --- | --- | --- | --- |
| 1 | 136 | 6 | 4 | 初始3条仅归档；实时3条含一次两国转账；停用Chronicle后提交130条 |
| 2 | 139 | 139 | 164 | 跨128条分页补齐离线变更，再提交3条；原生幂等请求重试不改库存 |
| 3 | 139 | 139 | 164 | 两次公开查询及持续轮询保持不变 |

三个进程均正常退出0，最终3条初始归档、136条正常历史、164条国家收件箱和零pending。28笔跨国转账各对两国建收件箱，其余事件各一国；未产生贡献、奖励或在线实例。独立检查器逐项重放原生前后余额，与实际SQL库存、转账／单国幂等回执、历史文件／pending、Chronicle完整回执／ingress／事件／收件箱及公开API核对，保留原始纳秒与空actor。

18项证据回归通过，零跳过，覆盖丢历史／完整回执、改库存／余额／时间、补造actor、缺跨国收件箱、改转账、SQL权威标记、schema降级、游标回退、错误日志、异常退出及公开API篡改。Java Set的国家顺序跨进程可以改变，检查器按成员集合比较且拒绝重复成员；完整余额List仍按原生次序核对。首次证据回归因检查器按JSON数组顺序比较国家Set而在setUp失败（0项、1error），日志保留；修正仅比较集合语义，并新增正反两项测试后18项通过。

## 真实失败与边界

`paper-resource-d1057a8c-20261126-1` 首次boot1失败：检查器假设每事件只产生一条国家收件箱，实际跨国转账应为两国各一条，因此在6条事件／4条收件箱处超时。服务器正常退出，失败记录保留。修正检查器后，schema8中间构件 `paper-resource-d1057a8c-20261126-2` 三次启动通过独立库存／回执检查；该指纹不替代最终schema9构件验收。最终轮次使用全新目录和相同原生构件。

本次不认证在线玩家／GUI、工厂完成、个人生产贡献、MySQL、全栈Folia、长期压力、生产升级或最新五插件完整史诗双路线。历史纳秒表示源提交边界时间，不能恢复旧数据本来没有的精度。

工厂仍是下一项：当前加工逐项扣原料、补偿grant、产出和更新工厂状态分离，崩溃恢复并不完整；默认配方还含旧资源名。必须实现原子开始／完成、持久run与真实actor并接到实际入口，再计文明生产贡献。UNKNOWN对账／安全新轮次、市场托管／管理员核对、完整五插件和全栈Folia仍在[剩余工作](REMAINING-WORK.md)。

## 跨设备复验

```bash
env MAVEN_OPTS='-Xmx768m -XX:+UseSerialGC' ./mvnw -B -ntp verify
python3 tools/resource-history-paper.py prepare --starcore /path/to/starcore/target/starcore-1.0.0.jar --runtime /path/to/initialized-paper --attempt NEW-ATTEMPT --port 29661 --java-home /path/to/java21
python3 tools/resource-history-paper.py run --run run/PREPARED-DIRECTORY --java-home /path/to/java21 --boot 1
python3 tools/resource-history-paper.py run --run run/PREPARED-DIRECTORY --java-home /path/to/java21 --boot 2
python3 tools/resource-history-paper.py run --run run/PREPARED-DIRECTORY --java-home /path/to/java21 --boot 3
python3 tools/inspect-resource-history.py run/PREPARED-DIRECTORY
STARCHRONICLE_RESOURCE_RUN=run/PREPARED-DIRECTORY python3 -m unittest discover -s tools -p test_resource_history.py -v
```

Java21与Python3.11+；runtime包含固定Paper的paper.jar、cache和libraries。准备器只接受新attempt及独立回环端口；旧失败或旧构件证据不能覆盖。两个仓库使用 `codex/national-epic-linux-acceptance-20261125`，本机只操作 `/home/add/starcore-national-epic`，不修改另有工作的 `/home/add/starcore`。


[跨设备清单](verification/resource-history-linux-20261126-summary.json)与[完整证据包](verification/resource-history-linux-20261126-evidence.zip)包含7,512成员／20,712,281字节，SHA-256 `b6f8e7785640c2e9b8d19822be2fcffb83c9b358e461e1feb48f0273af4800c6`。最终源码、全部最终XML、失败XML／日志、schema8中间轮次、schema9三次停服快照、公开回执及真实旧JAR拒绝验证均已归档；3个隔离服生成密钥已脱敏，ZIP及成员哈希已核对。冻结JAR留在 `target/resource-history-linux-20261126/`；服务已停止、端口29661释放。
