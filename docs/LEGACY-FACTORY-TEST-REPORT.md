# 旧工厂审计与显式付费登记验收（2026-11-25批次）

旧工厂已有只读原始审计和显式新付款登记。只有结构合法、空闲、归属有效的记录可以由当前创始人支付当前累计材料后登记；旧记录不能证明历史付款或原建造人。协议详见[迁移合同](LEGACY-FACTORY-MIGRATION.md)。异常或未完成旧加工继续隔离，十阶段整体仍在推进。

## 冻结源码与构件

| 项目 | 产品源码 | 实服JAR SHA-256 |
| --- | --- | --- |
| StarCore | `530e391a31280c4f7595e4d8f1d2350b67310c70` | `5cf4f203861ce7aace423da359986c14c29f219801aba79b55197b1e5c7fb1c2` |
| StarChronicle | `8f270c6f178a1e01a71b7e4fa3ebbfa7660de0dc` | `7ccb4d1fa111e87e9f9bd5e9b5ccd80d456790797e989942885d1d78a4916778` |

两库续接`codex/legacy-factory-audit-20261125`；草稿[StarCore #18](https://github.com/addxiaoyi/starcore/pull/18)叠加国库#17，配对[StarChronicle #23](https://github.com/addxiaoyi/StarChronicle/pull/23)叠加国库#22。未合并或部署生产服。后续CI重建产物单独记录，不能继承冻结实服指纹。

## 付款和来源

原始`factory.*`按原值审计；摘要包含整个旧档及资源权威域。等级、布尔值、UUID、未知字段、重复ID、缺失／孤立索引、失效国家或加工字段均严格检查。审计不保存资源，不推进revision，不重新设定旧内存快照基线；声明的巨大count不用于分配列表。

登记保留工厂ID、国家、名称、类型与1–10级等级，从国家库存新收取第1级至当前级的累计材料，版本从1开始、初始停用。`origin=LEGACY_REGISTERED`标明来源；该来源下`builderId`和`builtAt`只指本次付款登记人和登记时间，原始时间、效率与所有字段仍在回执和旧档保留。登记本身不产生贡献。新生产冻结当前配方、工厂版本与来源，完成后才记原开工人的文明贡献。

扣料、资源revision、工厂事件与独立head共同SQL提交；SQL层重核原始摘要、实际扣料、身份占用与容量。相同请求只有原付款人、工厂ID与摘要全部匹配才可重放；创始人交接后旧付款人仍可核对原请求，新操作需当前创始人。确认丢失按UNKNOWN对账；对账失败阻止继续写入。普通BUILT工厂format1字节与版本不少于等级的约束保持不变。

## 本机完整构建与真实MySQL

- 原生Java21离线`clean verify`：7,526项／1,455套件，0失败／0错误，51跳过；Spotless及JaCoCo通过，排除项未改，仅离线跳过联网CycloneDX。51项为30个opt-in MySQL方法入口与21项既有跳过。
- 原生聚焦106项全部通过：原始审计23、登记21、既有工厂37、资源编码10、命令15。完整构建另实际执行生产38、国家直售27项。
- Chronicle Java21离线`clean verify`：991项／101套件，0失败／0错误／0跳过。新增登记来源、实际生产贡献及普通来源兼容测试。
- 真实MySQL8.4.13／InnoDB／REPEATABLE-READ专项：30个方法入口展开64例全部通过。新增8例覆盖登记四处SQL失败完整回滚、两种确认丢失、独立实例竞争及登记→启用→生产→恢复。

MySQL启用flush_log=1、sync_binlog=1、binlog=1；仅自有隔离实例启用`log_bin_trust_function_creators=1`以运行故障触发器。最终13张自有表实际COUNT均为0，容器`starcore-legacy-factory-mysql-20261125`正常exit0、无OOM、数据保留。私密配置在`/tmp/starcore-legacy-factory-mysql-20261125-2/mysql.env`，禁止输出或上传。

首轮MySQL64例中32个触发器测试因环境权限报错，修正隔离实例配置后全通过；失败报告完整保留。早期编译失败、聚焦测试中的可选值断言错误及普通工厂来源约束反例均单独保留，不以失败计为成功，也不覆盖最终冻结构件结果。

## 所选Paper实服

固定Java21／Paper1.21.11、EssentialsX2.22.0／Vault1.7.3-b131、FancyNpcs2.10.1-java21+2、WorldEdit7.4.2／WorldGuard7.0.17，依赖版本和哈希逐次核对。国家与初始材料为显式夹具种子，七条旧工厂记录在第一轮正常停服后造数；它们是测试记录，不是实际用户旧资产。

迁移专项四次正常启动、8次正常进程退出及独立SQL检查通过：40条真实非OP客户端命令；7条原始记录中1条成功登记、6条继续隔离；4次工厂变更、2次新生产完成、1次取消、4条个人／国家贡献视图。初始停用拒绝开工、非创始人／外人／过期摘要／异常记录拒绝、重复请求去重、旧付款人跨交接重放和当前创始人升级均已验证。

原3级SMELTER新登记扣mineral280／industrial70／energy56；升至4级另扣320／80／64。初始每项1000，净生产消耗6矿物／2能源、产出4工业资源，最终mineral394、energy878、industrial854。七条旧记录61个原始属性及旧加工37批字段跨全部后续重启保持；只为两次实际完成的新批次归属原开工人贡献。

同一双JAR完成普通工厂3轮、默认生产3轮、空服补录2轮、国家直售3轮、DUNGEON3轮与WAR3轮；加迁移4轮，共21次成功启动、42次服务端／客户端正常exit0。全部现场正常停止、端口释放。国战为真实非OP客户端两次据点占领、原生100∶0判定AGGRESSOR／VICTORY_DETERMINED，随后唯一结算和重放。

| 场景 | 启动 | 证据测试（通过／跳过） | 主要结果 |
| --- | ---: | ---: | --- |
| 旧工厂登记 | 4 | 36／0 | 40条命令、1登记／6隔离、2完成／1取消、4贡献 |
| 普通工厂 | 3 | 32／0 | 45条命令、6变更、3完成／1取消、6贡献 |
| 默认生产 | 3 | 33／4 | 24条命令、4完成、8贡献 |
| 空服生产补录 | 2 | 37／0 | 11条命令、1完成、2贡献 |
| 国家直售 | 3 | 38／0 | 24条命令、3成交、12贡献 |
| DUNGEON | 3 | 27／2 | 1结算、8援助贡献、3后续实例 |
| WAR据点 | 3 | 39／2 | 两据点100∶0、1结算、8援助贡献、3后续实例 |

合计250项证据测试：242通过、8个不适用场景跳过，无失败／错误。以上正常启动不包含前两次迁移失败现场。第三方FancyAnalytics已知遥测诊断单列；原生SQL、持久化或生命周期错误不能作为遥测忽略。

目录均位于Chronicle工作区`run/`，前缀`paper-epic-7ccb4d1f-`：迁移`dungeon-legacy-factory-3`端口29802；普通工厂／默认生产／空服／直售／副本为`dungeon-legacy-reg-1-{factory,production,offline,trade,dungeon}`，端口29796–29800；国战`war-legacy-reg-1-war`端口29801。两个保留失败为`dungeon-legacy-factory-1`（29794）与`dungeon-legacy-factory-2`（29795）。

首次现场`legacy-factory-1`第二轮客户端尚未发送登记命令即被测试白名单拒绝，工厂／生产事件均0条。修复仅涉及客户端准备识别。第二现场`legacy-factory-2`四轮正常完成，但独立检查拒绝其原始快照：Python SQLite事务上下文未关闭连接，复制主DB时遗漏已提交WAL；快照实际仍是第一轮旧库。修复为显式检查点／关闭连接后复制，并对副本重读核对全部属性及revision；第三个全新现场重跑。前两现场及失败检查日志全部保留，不计入最终成功启动。

新增证据反例要求检查器拒绝漏记WAL的旧快照、额外改料及缺少revision，即使文件哈希已同步更新也不能通过。其他反例覆盖少扣材料、伪造原建造人／来源、删除旧加工字段、摘要漂移、缺尾、重启前缀、客户端命令缺失和贡献遗漏。

## 交付与边界

复验迁移证据：

```bash
python3 tools/inspect-legacy-factory-paper.py run/paper-epic-7ccb4d1f-dungeon-legacy-factory-3
STARCHRONICLE_LEGACY_FACTORY_RUN="$PWD/run/paper-epic-7ccb4d1f-dungeon-legacy-factory-3" python3 -m unittest discover -s tools -p test_legacy_factory_acceptance.py -v
```

重做实服须用新attempt与空闲端口：先运行`prepare-legacy-factory-paper.py`，再依次运行`epic-paper-session.py`的`legacy-seed`、`legacy-first`、`legacy-second`、`legacy-third`；仅在第一轮正常停止后执行`stage-legacy-factory-paper.py`。提供相同锁定依赖、精确双JAR、Java21与Mineflayer模块，不覆盖旧目录。六组普通业务用`run-legacy-factory-regressions.py`；每组独立目录、正常停止后检查及证据回归。

归档工具`tools/archive-legacy-factory-evidence.py`集中保存七个成功现场、两个被拒绝现场、完整构建／MySQL报告与原始工具；逐文件清单、私密凭据扫描及七组解压复验结果写入`docs/verification/legacy-factory-20261125-summary.json`。公开证据ZIP与最终摘要在归档成功后发布，源码tar和精确双JAR保留于`target/legacy-factory-20261125/`。

本机工作区`/home/add/starchronicle-legacy-factory-audit`与`/home/add/starcore-legacy-factory-audit`；冻结构件及完整报告在Chronicle的`target/legacy-factory-frozen/`。第三方JAR、世界与私密配置仅本机保留。不得修改`/home/add/starcore`或停止无关服务。

本轮只解决合法空闲旧记录的审计与新付款登记。已由外部调用方收费的旧资产仍需实际凭据及另行处置协议，不能将本协议当作旧付款抵扣或宣称已付款迁移完成；旧加工、未知状态、重复或损坏档案不自动退款、交货或签发贡献。实际方块建筑／领地授权、旧钱包／市场、永久UNKNOWN、长期容量、Paper实际事务中断、物理断电、远端TLS与全栈Folia仍待推进。真实MySQL专项与Paper／SQLite分别计证，旧构件MySQL实服及中断恢复不移给当前双JAR。
