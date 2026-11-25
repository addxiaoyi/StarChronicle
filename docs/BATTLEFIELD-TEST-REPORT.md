# 原生据点积分胜利与双插件恢复验收

2026-11-25：非 OP 协议客户端通过原生战争菜单占领两座要塞，积分依次为 50、100；主战国玩家执行结束命令后，原生发出 `VICTORY_DETERMINED`，进攻方胜利。WAR 与同一新构件组合的 DUNGEON 回归各完成三次正常启动、唯一史诗结算与重放检查。41＋29＝70 项证据回归全部通过，无跳过。

本轮还修复国家快照 SQLite 写锁竞争。原生完整 verify 为 7,274 项、1,444 套件，零失败／错误、21 项既有跳过；Chronicle 产品未改动、未重建，沿用首都阶段 927 项构建证据。十阶段继续推进，未合并或部署生产服。

## 精确构件与来源

| 项目 | SHA-256 | 来源 |
| --- | --- | --- |
| StarChronicle | `daa123548d66b5da6c8bd3c759767256d1bc0e6f7a66edfe5eb9c79d1fe70bf7` | 首都增量冻结构件，产品仍为 `9b0e846` |
| StarCore | `3dcc34b00b4f4f84f945922c036822485a81fc4691803eb2aea2ac85d77c36f6` | `902fcb085d92e10c3227f3d91b21ac4c317783ff`，国家 SQLite 修复 |
| Paper 1.21.11 | `5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba` | 所选第三方锁 |

Chronicle 工具提交为 `47d14653be85836a85774c91c0a5b69520c6ec6b`，分支 `codex/battlefield-victory-runtime-20261125` 的草稿 [PR #12](https://github.com/addxiaoyi/StarChronicle/pull/12) 叠加完整组合史诗 PR #11。原生分支 `codex/nation-sqlite-battlefield-20261125` 的草稿 [PR #9](https://github.com/addxiaoyi/starcore/pull/9) 叠加首都 PR #8。归档前两库均干净；归档工具验证 Chronicle src／pom.xml 与继承构建一致、原生冻结 JAR 与实际运行指纹一致。后续文档提交及 CI 重建不替换上述构件。

固定环境为 Java 21、Paper 1.21.11、EssentialsX 2.22.0、Vault 1.7.3-b131、FancyNpcs 2.10.1-java21+2、WorldEdit 7.4.2+7450-eb8e82c、WorldGuard 7.0.17+2370-e42d8bc。每次启动均验证七插件及 PlayerVaultEconomyService；下载和哈希见[锁文件](verification/production-stack-candidate-20261125.json)。

## 实际流程及边界

| 项目 | WAR | DUNGEON 回归 |
| --- | --- | --- |
| 运行目录 | `paper-epic-daa12354-war-battlefield-candidate-3` | `paper-epic-daa12354-dungeon-battlefield-dungeon-candidate-1` |
| 端口 | 29732 | 29731 |
| 已提交史诗操作 | 13 | 12 |
| 国库／资源转账 | 2／2 | 2／2 |
| 援助源事件／贡献 | 4／8 | 4／8 |
| 结算／个人后续 | 1／3 | 1／3 |
| 正常启动与证据回归 | 3 次；41 项通过 | 3 次；29 项通过 |

四名非 OP 客户端 A／B／C／D 登录，A／B 实际制作完成原生任务。两条路线均执行援助、外交、审批和原生最终完成证据。WAR 中 A 完成复国／宣战后，从 `/war gui` 经战场、详情与要塞管理菜单真实点击两座敌方要塞；观察器只记录真实 InventoryClickEvent，不合成事件。A 明确没有 `starcore.war.admin`，以实际主战国创始人身份执行 `/war end 复兴帝国 EpicTestB`。

战场和两座 CITADEL 由夹具调用原生服务创建，每座原生战略价值 50；准备计时由夹具调用进攻国授权的原生 startWar 服务跳过。初始国家、资金、库存及材料也是显式种子，隔离配置关闭建国费用。没有设置战争积分、直接修改模型或伪造完成回执。

准备期点击、重复占领、非参战国点击及结束后点击均未增加积分。boot1 有两次有效占领与四次无效点击，boot2／3 各增加一次无效点击；独立检查器把八次客户端点击与服务端记录逐条关联。结束后的点击针对己方据点，只证明该入口不再计分，不宣称覆盖所有结束态下敌方据点组合。

boot1 在原生战争结束后留下 ROUTE_APPROVED，未结算即正常停止；boot2 从持久回执录入最终证据、结算、拒绝重放；boot3 恢复后再次核对及拒绝重放。两个实际占领计分标记、据点归属／时间、100∶0 积分与结束时间在三份停服原生库和最终库保持一致，没有待发计分。Chronicle 的 boot2／3 与最终库中战役、操作、选票、贡献、投影及决议逐表一致。每轮 A／B 均执行实际贡献查询，六个服务端与六个客户端进程全部退出 0，端口已释放。

本轮证明原生菜单据点占领带来的积分胜利及其恢复，不包含 PvP 击杀、攻城移动、军队模拟、占领时长或世界领地转移。战场 controller 仍是防守国，变化的是两座据点所有者。DUNGEON 仍是三秒原生生存房间。Chronicle 的 FINAL_BATTLE 契约仍接收原生已结束战争回执，本轮检查器额外核对原生胜利事件；没有新增“仅胜利才可结算”的产品语义。

## 修复与保留的失败

| 记录 | 真实结果与处理 |
| --- | --- |
| `war-battlefield-candidate-1` | 旧原生 `3199771b…c135` 在 seed 保存国家时 SQLITE_BUSY／NATION_STATE_CONFLICT，战场流程尚未开始；完整失败与正常退出保留 |
| 原生修复前专项 | 两项真实 WAL 双连接竞争测试失败；保存日志与 XML |
| 原生修复后首次专项 | 15 项中的既有并发屏障测试失败：读版本后的双写者屏障与先取写锁冲突；屏障移到事务开始前，仍断言一胜一版本冲突与重载成功 |
| 原生最终构建 | 15 项专项、7,274 项完整 verify 通过；Spotless／JaCoCo 通过；离线 SBOM 按既有规则跳过 |
| `war-battlefield-candidate-2` | 新原生建国／宣战通过，客户端错误地用战场名称匹配窗口标题而超时；详情实际复用总览标题、名称在头部槽位；仅修复客户端选择器，保留失败 boot |
| 首次证据回归 | 40 项中四个负例提前计算 WAL 数据库哈希，实际拒绝发生在文件完整性层；改为提交并关闭连接后哈希，未放宽业务断言；新增未清待发积分负例后最终 41 项通过 |

SQLite 修复在读国家版本前执行不改变值的 UPDATE，先等待写锁再读取一致版本；版本行不存在时也适用。版本 CAS、有界重试、回滚、建国状态与回执同事务保留，其他数据库路径不变。详见原生 [NATION-SQLITE-WRITER.md](https://github.com/addxiaoyi/starcore/blob/codex/nation-sqlite-battlefield-20261125/docs/NATION-SQLITE-WRITER.md)。

两个最终 boot1 均有 FancyAnalytics status200 空响应，按前一增量已限定的可选遥测类型逐条保留；原生持久化／生命周期错误仍拒绝。70 项回归还覆盖主体替换、伪造／缺失事件或客户端命令、管理员绕过、改写并重算哈希后的积分／标记／归属／待发状态，以及继承的插件、回执、贡献、重启检查和两份历史真实失败。

## 归档和复验

[证据 ZIP](verification/battlefield-paper-20261125-evidence.zip)为 9,000,646 字节、1,971 个有哈希成员，另含成员清单，SHA-256 为 `a77cbae9567428c14c06fec793c780dd8656762ff274c346d39697879768ead7`。ZIP 完整性与所有成员哈希通过；四个现场配置共 12 个密钥字段脱敏。[汇总 JSON](verification/battlefield-paper-20261125-summary.json)记录源码、构件、全量 XML、继承构建、失败轮次和回归日志指纹。本机 `target/battlefield-paper-20261125/` 另含双 JAR 与两库源码 tar。

新设备先构建两库、登记新指纹并创建独立目录；旧实服结论不能转移给新构件。实际路径替换以下占位项：

~~~bash
python3 tools/prepare-battlefield-paper.py \
  --attempt battlefield-new-device-1 --port 29732 \
  --starcore /path/to/StarCore.jar --runtime /path/to/paper-runtime \
  --artifacts-dir /path/to/locked-third-party-jars --java-home /path/to/java21

# 同一新目录依次 boot 1/2/3，scenario 为 first/second/third
EPIC_MINEFLAYER_MODULE=/path/to/node_modules/mineflayer \
python3 tools/epic-paper-session.py --run run/NEW_RUN \
  --java-home /path/to/java21 --node /path/to/node \
  --boot 1 --scenario first --contributions
python3 tools/inspect-battlefield-paper.py run/NEW_RUN

STARCHRONICLE_EPIC_RUN=run/NEW_RUN \
STARCHRONICLE_EPIC_FAILURE_LOG=/path/to/retained-policy-failure.log \
STARCHRONICLE_DIPLOMACY_FAILURE_FIXTURE=/path/to/retained-diplomacy-failure.jsonl \
python3 tools/test_battlefield_acceptance.py
~~~

DUNGEON 回归使用 `prepare-production-paper.py --campaign --route DUNGEON`，独立端口／目录同样执行三次启动，再用 `inspect-epic-production-paper.py` 与 `test_epic_production_acceptance.py`。下一项为 UNKNOWN 对账和失败／放弃后的安全新轮次；收费建国、贸易／生产共同提交、全栈 Folia、MySQL 与容量继续见[交接](HANDOFF-2026-11-25.md)及[剩余工作](REMAINING-WORK.md)。

最终 [CI 45671933354](https://github.com/addxiaoyi/StarChronicle/actions/runs/45671933354) 在 `546d66d` 通过927项测试和完整1,397项交付检查。CI JAR SHA-256 为 `f75881b5d6024aa5f64bb9821a537f3522467a46d351ad9c2bdd036d7c16312c`，交付 ZIP 为 `b17372c044a786eb39c3e263a0e6dc51bcc39d445e47c3dcfc1324a5ab8808d3`；该 CI 重建构件未做实服测试，不替代上述冻结双 JAR。
