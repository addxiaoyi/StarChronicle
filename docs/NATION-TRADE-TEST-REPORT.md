# 国家贸易共同提交与贡献验收

2026-11-25：国家库存直售已完成原生资产／报价／回执共同SQL提交，Chronicle接入专用权威来源和四条贡献视图。962项Chronicle、7,313项原生完整测试通过（原生21项既有跳过）。所选Paper组合完成贸易三次启动、空服补录两次启动及DUNGEON三次启动；WAR据点胜利三次启动通过。11个服务端和11个客户端均正常退出，116项证据检查通过、4项依赖旧失败场景的检查跳过。十阶段继续推进，未合并或部署生产服。

## 精确源码与构件

| 项目 | 产品源码 | 实服JAR SHA-256 |
| --- | --- | --- |
| StarChronicle | `01cbe53735fb8692b59ec552051956e80bd44b05`，分支 `codex/trade-settlement-20261125` | `9363071927b8293c9d39dc829001279fb769d4fcbc0e24ff721232a01cbe66e1` |
| StarCore | `253added0228032fe7a687a84f5d5e0b02788ece`，分支 `codex/atomic-trade-settlement-20261125` | `b7cfd0a65d394785a2b03667d62edec2960b8c3bab3959b347ee0001f31231a0` |

原生草稿[PR #11](https://github.com/addxiaoyi/starcore/pull/11)叠加任务恢复#10；Chronicle叠加#13。构建后冻结双JAR，工具和报告开发未重建产品。后续CI构件按自身指纹登记，不能继承本轮实服结论。

环境由用户授权自行确定：Java21／Paper1.21.11、EssentialsX2.22.0／Vault1.7.3-b131、FancyNpcs2.10.1-java21+2、WorldEdit7.4.2／WorldGuard7.0.17，见[精确组合锁](verification/production-stack-candidate-20261125.json)。实际加载七插件，原生业务经济仍选用PlayerVaultEconomyService；本直售协议付款来源明确是国家国库。

## 实际业务与资产

[合同](NATION-TRADE-CONTRACT.md)说明命令、限制、保存顺序和UNKNOWN边界。没有将旧TradeHistoryService行升级成资产证明，也没有从当前创始人猜测旧操作者。

主场景为 `run/paper-epic-93630719-dungeon-nation-trade-candidate-1`，29736。A／B／C各建测试国家，D加入A国；每国显式种子1,000,000.00国库与100农业资源。四名真实非OP客户端实际输入报价、查看、接受、取消和查询命令。

| 启动 | 实际行为与正常停服状态 |
| --- | --- |
| sale-first | A向B报价20×2.50；D和C接受被拒，B实际查看50.00总价后接受；重复接受不重付。A再报价5×4.00，保持OPEN跨重启。1笔成交、4条贡献。 |
| sale-second | B接受持久OPEN报价；A创建并取消1×1.00报价，B接受被拒。A另报3×3.00，然后通过真实原生授权服务把创始人交给D；B接受旧报价被拒。D新报4×1.25，保持OPEN跨重启。2笔成交、8条贡献。 |
| sale-third | 旧创始人和取消报价仍拒绝，原两笔重放无新扣款；B接受D的新报价，再次重放无变化。A／D／B真实查询个人贡献，D查询国家贡献。3笔成交、12条贡献。 |

合计29农业资源、75.00国库资金；A国库存71／国库1,000,075.00，B国库存129／国库999,925.00，C国资产未参与交易。3条独立贸易结算、3条资源转移、3条国库转账完全对应；5报价为3 SETTLED、1 CANCELLED、1 OPEN。前两笔卖方操作者仍是A，第三笔是D。国家与个人贡献是同一交付的查询视角，不能重复计收益；资源和货币单位不能相加。

空服场景为 `run/paper-epic-93630719-dungeon-nation-trade-offline-1`，29739。第一轮将轮询间隔设为72000tick，真实接受20×2.50，并保存另一份OPEN报价；停服数据库证实原生已成交、Chronicle贸易事件／ingress／贡献均为0。仅在停服后将间隔改回20tick，保留前后配置与哈希；第二轮在任何客户端进程启动前完成1事件／4贡献，然后连接四人、重复接受和查询，原生资产、报价与回执不变。这是空服来源补录，不是重复调用资产交付。

## 同一双构件的完整史诗回归

DUNGEON：`run/paper-epic-93630719-dungeon-nation-trade-dungeon-1`，29737，依次 `first|second|third --contributions`。真实任务制作、国库／资源援助、外交邀请／接受、三秒原生生存房间、重启后唯一结算及再重放通过。12项史诗操作、4条援助事件、8条援助贡献、A／B／D各一份后续，跨两次重启不变。

WAR：`run/paper-epic-93630719-war-nation-trade-war-2`，29738，同样三轮。A非OP、无战争管理员权限，实际GUI占领两座各50分据点，原生判定VICTORY_DETERMINED／AGGRESSOR／100∶0；两次捕获与持久计分标记、据点归属、结束回执及结算跨重启核对。13项史诗操作、4条援助事件、8条贡献和3份后续。

这些场景重新使用本轮资源revision和共同提交实现；未借用旧双JAR的实服结论。国家种子、免费建国、战场对象、跳过国战准备计时及三秒副本均为显式测试条件。菜单据点胜利不代表PvP、完整攻城、军队移动或世界领地转移；FINAL_BATTLE仍接受通用结束战争回执。

## 构建、独立检查和保留失败

Chronicle最终 `./mvnw -o -B -ntp verify`：100套件／962项，零失败、错误或跳过，JaCoCo通过。原生最终 `./mvnw -o -B -ntp -Dcyclonedx.skip=true verify`：1,446套件／7,313项，零失败／错误、21既有跳过，Spotless和JaCoCo通过；离线SBOM按既有流程跳过。

原生测试覆盖五处SQL写入点整体回滚、确认丢失、持久重读也失败后阻止普通保存、提交前活读不见暂存资产、并发只成交一次、旧服务器与普通快照拒绝覆盖、完整幂等回执重载、不同数据源、国籍／创始人变化、取消／过期、禁运／配额、独立连续末尾及摘要。Chronicle覆盖真实持久ingress要求、本地事务失败回滚、重放、无在线玩家及伪造普通事件不签发贡献。

独立Python检查器只读停服SQLite，对照每轮双库清单哈希、规范JSON与摘要、原生独立head、资产守恒、历史事件、原始操作者、12条逐字段贡献、连续游标和ACKED收件。它同时核对实际客户端命令响应、服务端日志、进程退出／时间、双JAR、Paper、第三方和捕获脚本指纹；不将夹具“通过”字符串当作资产证明。主贸易38项、空服12项证据检查通过。DUNGEON 29项中27通过、2项依赖旧失败场景的检查跳过；WAR 41项中39通过、同类2项跳过。共120项执行，116通过、4跳过。

失败原始日志与XML全部保留：系统Maven版本被enforcer拒绝；原生首轮专项55项中3项夹具迁移错误，修复后58项通过；原生首轮全量两项仍固定旧命令总数162，更新为163后通过；Chronicle首轮来源专项35项中2项测试supplier每次返回新对象，正确触发提供者身份检查，固定测试服务对象后完整构建通过。

首个WAR目录 `nation-trade-war-1` 已取得原生100∶0胜利回执，但客户端未读到结束后菜单项，控制器失败，服务端和客户端均正常退出；不能计作完整通过。客户端改为有界等待真实菜单物品数据包，超时保留标题／物品诊断，且不重放捕获动作；新目录重做完整三轮。旧目录、捕获脚本与失败日志保留。可选FancyAnalytics status200空响应按精确类型单独记录，原生生命周期／持久化错误不能按遥测放行。

## 复验与交接

```bash
python3 tools/prepare-nation-trade-paper.py --attempt NEW --port 29736 \
  --starcore /ABS/PATH/StarCore.jar --runtime /ABS/PATH/paper-runtime \
  --artifacts-dir /ABS/PATH/locked-stack --java-home /ABS/PATH/java21
EPIC_MINEFLAYER_MODULE=/ABS/PATH/node_modules/mineflayer python3 tools/epic-paper-session.py \
  --run run/NEW --java-home /ABS/PATH/java21 --node /ABS/PATH/node --boot 1 --scenario sale-first
# boot2 sale-second；boot3 sale-third。贸易场景不要加--contributions。
python3 tools/inspect-nation-trade-paper.py run/NEW
STARCHRONICLE_NATION_TRADE_RUN=run/NEW python3 -m unittest discover -s tools -p test_nation_trade_acceptance.py -v
```

空服新目录准备时加 `--offline`，依次执行 `sale-offline-first`、停服后的 `prepare-nation-trade-resume.py --run run/NEW`、`sale-offline-second`。最后执行 `inspect-nation-trade-offline-paper.py run/NEW`；回归环境变量为 `STARCHRONICLE_NATION_TRADE_OFFLINE_RUN`。史诗双路线沿用production／battlefield准备器和各三轮命令；必须登记新构件指纹，不覆盖旧失败目录。

证据包和机器清单由 `archive-nation-trade-evidence.py` 验证四个成功目录、失败目录、两库源码、实际构建日志与全部保留XML后生成。新设备按本报告分支续接，重建产生的新JAR必须重新验收。

上一任务恢复PR #13最终源码 `97edd98` 的push CI 45677086351与PR CI 45677100191均成功：947项及1,405项交付校验；其CI JAR `b6544de44d4cf4aef3581d5e7b1d37d7198ce17c21c3e0e66550470ae89e2078`、交付ZIP `fbed06962bd42238cb75abbe63eab62619c5584bafced5c38c58986b38bc6e32`与旧实服冻结构件分开记录。本轮没有修改旧分支制造CI循环。

## 剩余边界

只交付国家国库与国家库存直售；旧玩家钱包／背包交易、市场撮合、贸易协定、税收及文明生产仍需各自共同提交协议。正常停服重启不能代替真实断电验收；SQL故障与确认丢失目前是自动化注入证据。MySQL、全栈Folia、生产部署、长期容量和一致旧备份回滚仍未完成。国家／个人贡献不发奖励。十阶段保持active。
