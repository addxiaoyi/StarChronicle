# 原生任务核对与安全新轮次验收

2026-11-25：schema 8 的本人核对／重试 API、任务详情 GUI、退休轮次与原生持久终止能力已按同一对新构件完成所选 Paper 组合验收。任务恢复／DUNGEON 四次正常启动，WAR 据点胜利回归三次正常启动；本轮 67 项证据回归通过，另29项旧副本证据检查证明核对工具向后兼容。十阶段仍在推进，未合并或部署生产服。

## 精确源码与构件

| 项目 | 源码 | 实服 JAR SHA-256 |
| --- | --- | --- |
| StarChronicle | 产品 `70bd4e0e75b841def58ab5c23e5d06603359668d`；工具 `25d5c091d31ea403de15d79654394b95d742e3c9`，基于据点增量 `546d66d` | `c5455ec5e790b6b4d9d48ab381938be4e91a80aa001a29e4bd90e6b817bda55e` |
| StarCore | 产品 `9e73e5136ad880384ad96c28566ace65fe25eba2`，基于国家写锁增量 `5aa091ce` | `42063ee7694b0074c23548047a08adfac9cc359266fc3fccd82bfab1b82b4d9c` |

Chronicle 分支 `codex/quest-recovery-20261125`，原生分支 `codex/durable-quest-terminal-20261125`。原生草稿 [PR #10](https://github.com/addxiaoyi/starcore/pull/10) 叠加 #9。构建后冻结 JAR，实服、工具开发与报告整理期间均未重建产品；后续 CI 的新构件必须独立登记，不能继承这些实服结果。

沿用已授权自行确定的 Java21／Paper1.21.11、EssentialsX2.22.0／Vault1.7.3-b131、FancyNpcs2.10.1-java21+2、WorldEdit7.4.2／WorldGuard7.0.17。精确第三方指纹见[组合锁](verification/production-stack-candidate-20261125.json)。七次启动均实际加载七插件，原生业务经济使用 PlayerVaultEconomyService。

## 已实现的行为

`reconcileQuestGate(gateId, playerId, expectedRound)` 只读核对当前绑定，可消费真实已提交完成；`retryQuestGate` 只接受专门查询确认的持久 FAILED／ABANDONED。UNKNOWN、ACTIVE、COMMITTED 不产生新轮次。旧轮次的 gate 快照、接受身份、终止证明、本人和时间被同事务归档，再创建独立 binding 意图，普通驱动随后重新核验接取。第0轮继续使用旧 gate ID，旧schema数据不重编码；第1轮起按稳定 gate＋轮次派生绑定。

查询前后均检查本人、原国籍、权限、剧情条件及成员复核；同国读者不能替本人重试，PENDING 不因回到原国家而自动解除。重复旧页面和迟到旧轮回包不能修改新轮。成员显式退出保留 EXITED 审计与已完成贡献，未完成门槛停止轮询，其他成员可以继续查询。最多1000次显式重试。

原生 `durableTerminalStates()` 保持协议1兼容。终止文件写入前只公开 UNKNOWN，确认后才公开 FAILED／ABANDONED；写失败继续 UNKNOWN。适配器确认能力和查询使用同一个实际原生服务实例及存储域。旧服务仍可普通查询，但不能授权新轮次。完整合同见[任务门槛说明](QUEST-GATES.md)和[公开 API](API.md)。

## 实服过程与独立核对

任务路线：`run/paper-epic-c5455ec5-dungeon-quest-recovery-candidate-2`，端口29735。

| 启动 | 实际行为与停服状态 |
| --- | --- |
| boot1／quest-first | 非OP客户端A从原生“任务进度”进入详情并点击放弃；夹具短暂持有该shard真实PersistenceService写入stripe。内存canonical状态为UNKNOWN，磁盘仍为原ACTIVE接受记录；真实GUI核对及公开retry查询都不新开。放行存储后确认ABANDONED，再由本人点击“重新尝试任务”；A第1轮ACTIVE，B／D保持第0轮。拒绝同国D替A重试及A旧轮请求。 |
| boot2／first | 恢复A的新绑定／run／开始时间；再次拒绝旧轮请求。A／B真实制作三张纸并由公开原生完成路径提交；检查实际目标已经完成。执行两笔国库与两笔资源援助、外交邀请／接受、DUNGEON路线和两次原生三秒生存房间。未结算即正常停服。 |
| boot3／second | 核对最终回执，唯一结算并检查重放拒绝；8条贡献、4条援助事件、3份个人后续；完成剧情不允许旧任务请求。 |
| boot4／third | 再次恢复并拒绝史诗与任务旧请求；退休轮次、完成消费记录、结算与贡献不变。 |

A的稳定gate为 `ea3b268ae5d72552bfcb696a27229076d1c0ca083f5e0c4ee254dcffc5e98bcf`。旧run `7382a956-f958-409d-8103-55092feb84b4` 保持ABANDONED；新binding `c6381d5f65856ba5e6889dadc03691e05177f553c37a615284a7f7fb65fb1cec`、run `613040a4-6b3f-430a-b5e8-1fcbf37996c1`，完成ID `8e949289-5104-4a30-8a9b-048ac2fe6d65`。原生四份shard为A旧／新轮、B和D；只有A新轮与B有两份完成journal、原生完成标记及各一次计数。D的未完成原生任务保留，没有被冒充完成或取消。

WAR回归：`run/paper-epic-c5455ec5-war-quest-war-candidate-1`，端口29734，依次 `first|second|third`。非OP且无战争管理员权限的A实际导航原生GUI、占领两座各50分的CITADEL，再执行本人结束命令；原生发出VICTORY_DETERMINED／AGGRESSOR／100∶0。两次捕获、六次无效尝试，两个计分标记及据点归属跨两次重启不变。13项已提交史诗操作、唯一结算；DUNGEON为12项。两路线各2笔国库＋2笔资源转账、4条援助源事件、8条贡献、3份个人后续。

七个服务端、七个客户端进程均exit0，全部已停止，端口释放。每次正常停服保存双数据库；任务路线另存整个原生quest目录及逐文件哈希。独立检查器连接停止后的数据库，关联原生权威域、接受／终止／完成、退休归档摘要、派生绑定、原生完成journal和runCompletions、历史及消费键，逐轮与实际日志比对。已checkpoint的库用immutable只读方式，旧核对生成的空WAL／shm不作为业务证据；非空停服journal仍拒绝。

## 构建和证据回归

- Chronicle完整 `./mvnw -o -B -ntp verify`：947项／99套件，零失败、错误或跳过，JaCoCo通过。
- 原生完整同命令：7,278项／1,444套件，零失败／错误，21项既有跳过；40项专项、Spotless／JaCoCo通过，离线SBOM按既有规则跳过。
- 真实任务恢复目录副本：26项，包括伪造UNKNOWN新轮、缺少GUI放弃、退休摘要／主体／提供者／时间／下一binding篡改、重启丢失、重复完成和提前结算均拒绝。
- 本轮WAR目录：41项；旧冻结DUNGEON目录：29项工具兼容回归。合计96项无跳过，其中67项属于本轮构件现场，29项不算新构件实服结果。

全部失败均保留：原生修复前18项中2项失败证明终止状态提前公开；Chronicle第一次完整构建944项通过，增加退出用例后946项中1项失败，揭示退出成员门槛导致其他查询失败；修复后最终947项通过。第一次实服candidate-1因客户端从“可接取列表”寻找活动任务失败，服务端与客户端正常停止；改用原生任务进度后candidate-2完成。独立检查首轮因只读SQLite空辅助文件清单差异失败；26项负例首轮有1项在造坏数据时先触发数据库CHECK，修改负例使其满足表约束再验证语义后全部通过。原日志、XML和失败运行目录完整归档，没有覆盖。

两条成功路线boot1各有一次FancyAnalytics status200空响应；按精确可选遥测类型记录。原生持久化／生命周期错误仍直接拒绝，不能按遥测放行。

## 归档与复现

[证据ZIP](verification/quest-recovery-paper-20261125-evidence.zip)：10,189,469字节，2,401个有哈希成员，SHA-256 `a281ba4696b968a4c0f7c6c7748288cde7e2327029d2c9fbaaf1cda06028bfdc`。全部成员与ZIP完整性校验通过；9个配置密钥字段脱敏。[机器清单](verification/quest-recovery-paper-20261125-summary.json)保存精确源码、构件、全部XML指纹和逐轮结果。本机 `target/quest-recovery-paper-20261125/` 另有冻结双JAR及两库源码tar；新设备重建后必须登记新指纹并重新实测。

```bash
python3 tools/prepare-quest-recovery-paper.py --attempt NEW --port 29735 \
  --starcore /ABS/PATH/StarCore.jar --runtime /ABS/PATH/paper-runtime \
  --artifacts-dir /ABS/PATH/locked-stack --java-home /ABS/PATH/java21
# 使用输出的新目录；boot1不加--contributions，随后boot2/3/4加。
EPIC_MINEFLAYER_MODULE=/ABS/PATH/node_modules/mineflayer python3 tools/epic-paper-session.py \
  --run run/NEW --java-home /ABS/PATH/java21 --node /ABS/PATH/node --boot 1 --scenario quest-first
# boot2 first；boot3 second；boot4 third。
python3 tools/inspect-quest-recovery-paper.py run/NEW
STARCHRONICLE_QUEST_RECOVERY_RUN=run/NEW python3 -m unittest discover -s tools -p test_quest_recovery_acceptance.py -v
```

WAR准备器与三轮命令见[据点报告](BATTLEFIELD-TEST-REPORT.md)；本次必须使用上方新双构件，不能借用旧报告的指纹结论。

## 范围边界

真实延迟的是旧任务shard写入，任务放弃和后续状态来自实际原生服务。该场景没有断电、不可修复UNKNOWN、伪造任务进度、替换提供者或直接改库。原生任务文件和终止证据文件没有共同事务；若崩溃后旧活动任务仍在，原生会阻止新接取，不把它说成已恢复成功。FAILED的新轮次在产品回归覆盖，本次实服终止路径为ABANDONED。核对不补发任务或外部奖励；INVOKED仍只表示原生奖励方法返回。

免费建国、初始国家／资金／材料、战场对象、跳过国战准备计时及三秒副本均为显式隔离夹具。菜单据点积分胜利不代表PvP、攻城移动、军队模拟、占领时长或世界领地转移。FINAL_BATTLE仍接通用已结束战争回执；本轮不增加仅胜利才结算的语义。完整Folia、生产部署、MySQL、长期容量、贸易／生产资产与历史共同提交仍未完成。
