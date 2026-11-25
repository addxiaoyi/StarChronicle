# 国家工厂与绑定生产验收（2026-11-25）

后续国库写锁修复已在新原生构件独立完成，见[国库报告](TREASURY-WRITER-TEST-REPORT.md)。本报告仍只描述下方工厂构件及其原始失败，不改写当时结果。

付费建厂、升级、启停和工厂生产准入已完成本轮原生、Chronicle、MySQL专项及所选Paper组合验证。工厂是国家基础设施记录，不放置世界方块或申请领地；十阶段整体仍在推进，未合并或部署生产服。合同见[NATION-FACTORIES.md](NATION-FACTORIES.md)。

## 精确源码与构件

| 项目 | 冻结产品源码 | 实服JAR SHA-256 |
| --- | --- | --- |
| StarCore | `5c7eb07d9aab43b80eaf83aaee3d5c5b69615b3e` | `b06b317ffe1af77162da42e301851abc9de6ccdc4aa88e2e7da546ab8f8ced3b` |
| StarChronicle | `fc08038d5e9016ad04280552a3a641193c0971c4` | `d49927067fea2e787d24f1be6127a59287352a9a53402d780943b9829276c17f` |

两库续接`codex/nation-factories-20261125`：原生草稿[PR #16](https://github.com/addxiaoyi/starcore/pull/16)叠加#15，Chronicle草稿[PR #21](https://github.com/addxiaoyi/StarChronicle/pull/21)叠加#20。后续工具／报告提交不改变上述产品源码和实服JAR。源码归档提交记录在机器清单；CI重建产生不同指纹时，不转用本轮实服结论。

目标组合：Java21、Paper1.21.11、EssentialsX2.22.0、Vault1.7.3-b131、FancyNpcs2.10.1-java21+2、WorldEdit7.4.2+7450-eb8e82c、WorldGuard7.0.17+2370-e42d8bc。实际提供者与JAR哈希均由现场及[锁文件](verification/production-stack-candidate-20261125.json)核验。Paper现场原生与Chronicle均为SQLite；MySQL为独立原生测试。

## 构建和数据库

原生完整`clean verify`为7,465项、1,452套件，失败0、错误0、跳过47；其中26个MySQL测试方法入口默认opt-in跳过，另有21项既有跳过。显式MySQL8.4.13／InnoDB运行展开56例，全部通过，无跳过。Spotless／JaCoCo通过；离线构建跳过CycloneDX联网SBOM，未改覆盖率排除。工厂／生产／命令／原始旧字段保留聚焦136项通过。Chronicle完整构建988项／101套件全部通过，生产来源聚焦26项通过。

MySQL新增22例覆盖建造／升级／停用在事件、头、revision和库存四个边界回滚，三类确认丢失、重读失败后阻止继续扣料、两个独立实例争抢升级版本／最后生产名额、未提交隔离、精确材料扣除与SQL层准入复核、删尾拒绝。保留此前34例生产／贸易测试。专用实例`starcore-factories-mysql-20261125-2`，loopback33325，数据库`starcore_settlement_it`，镜像固定MySQL8.4.13摘要；已exit0停止，数据保留。13张表全部为InnoDB；最终TABLE_ROWS为近似元数据，精确清空由测试teardown另行断言。

首次MySQL56例有28个错误，因为启用binlog时测试账号不能创建故障触发器；仅在自有测试容器设置`log_bin_trust_function_creators=ON`后重跑56例通过，binlog仍启用。首轮日志、原始测试源码及报告全部保留。原始MySQL清单记录的是提交前HEAD `3391555e`；复制的测试源码SHA与最终`5c7eb07d`一致，不改写历史清单。

## 三轮工厂实服

现场`run/paper-epic-d4992706-dungeon-factories-3`，端口29783。四个真实非OP客户端；国家和初始库存是明确的夹具种子，建造、升级、启停、开工、领取、取消、重放全部通过玩家命令。创始人交接使用原生公开权限服务。

第一轮：拒绝缺少工厂、非创始人、跨国、等级不足与26批容量超限；建造1级冶炼厂，精确重放不重复收费、改类型重放拒绝；开工1批，过早领取和满额拒绝；升级至2级，旧版本拒绝；再开工2批后停用。保存工厂2级／版本3／停用、2个RUNNING共3批、3次工厂变更、0完成。

第二轮：停用时领取旧1批，取消2批精确退款；启用至版本4并开工要求2级的生产。A交接给D后，A不能继续变更或查看国家工厂，但可以查看自己的操作回执；D升级到3级／版本5、开工新批次并停用至版本6。保存2个RUNNING共3批、6次工厂变更、1完成。

第三轮：升级／停用重放不扣料；A不能领取尚未完成的旧批次，D按自然到期领取A和D各自发起的生产，原操作者保持不变。终态重放不增产出或贡献。最终3完成／1取消、8条生产事件、6次工厂变更、6个CIVILIZATION查询视图。45条真实命令、6次服务端／客户端exit0。

建造与两次升级合计消耗mineral280、energy56、industrial70；净生产消耗mineral12、energy4、产出industrial8。国家A最终mineral708、energy940、industrial938、agricultural100。工厂建造者仍为A，生产完成中A发起2笔、D发起1笔。Chronicle六个工厂事实保存开工时版本／等级，后续升级、停用和创始人交接不会改写旧生产。

## 同构件回归与独立证据

| 场景 | 启动次数 | 成功现场 | 端口 |
| --- | ---: | --- | ---: |
| 工厂 | 3 | `paper-epic-d4992706-dungeon-factories-3` | 29783 |
| 默认生产 | 3 | `paper-epic-d4992706-dungeon-fact-reg-production-2` | 29784 |
| 空服补录 | 2 | `paper-epic-d4992706-dungeon-fact-reg-offline-2` | 29785 |
| 国家直售 | 3 | `paper-epic-d4992706-dungeon-fact-reg-trade-2` | 29786 |
| DUNGEON | 3 | `paper-epic-d4992706-dungeon-fact-reg-dungeon-3` | 29787 |
| WAR | 3 | `paper-epic-d4992706-war-fact-reg-war-3` | 29788 |

17次成功启动与34次正常进程退出；全部停止且端口释放。默认生产4完成／1取消，8贡献，交接不改原开工人；空服在客户端进入前补录原生1完成为1事件／2贡献。国家直售3成交、29农业资源、75.00国库、12贡献。DUNGEON与WAR各唯一结算、8援助贡献及个人后续；WAR通过真实GUI占领两个据点取得100∶0原生胜利，未覆盖PvP或完整攻城。

六个检查器独立读取停止后的双库、每次重启检查点、前缀摘要、冻结JAR与夹具、实际协议消息和过程时间。214项证据检查中206通过、8场景不适用；工厂32项包含篡改价格／建造者／版本、缺头／删尾、库存不守恒、改写工厂绑定和贡献、缺少真实命令／回包等反例。公开包解压后再次执行全部六个检查器及同一组214项证据测试，结果一致。

## 保留的失败与限制

- 第一次全构建仅两个既有命令数量断言未更新（164→165），已补注册检查，原失败日志与全部报告保留。
- 工厂准备1重复编译夹具，新增`--prepare-only`组合流程修正；准备2首轮被客户端命令白名单拒绝，尚未执行工厂命令。两个目录保留，成功轮次使用新目录3。
- 初始回归准备名超过32字符，未启动游戏进程；原日志保留。
- `fact-reg-dungeon-2`在测试资金初始化出现SQLite写锁冲突，国库存入拒绝并回滚，场景判失败。停止后国库记录count为0，生产／工厂／贸易结算为0，日志与数据库保留。新目录3用相同产品和场景重跑通过；这不消除普通国库写入在竞争时可能拒绝的可用性限制。
- 工厂检查器首轮把SQL省略的零库存和API显式零库存判为不同；按固定七类资源补零后核对，新增两个反例保证非零／未知资源不能被忽略。原失败日志保留，实服数据未改写。
- 两次归档在发布前被凭据扫描拒绝：两次均为失败准备目录的公开默认占位值在其他模板说明中的出现被误判为密钥；同时将值提取改为YAML解析以避免尾注混入。现在核对已跟踪源码模板，实际私密环境口令仍逐字节扫描；两个未发布包和失败日志保留。
- FancyAnalytics个别上报`status 200`报错独立记录，只允许已识别的第三方遥测诊断；产品持久化、生命周期或其他错误仍使对应现场失败。Essentials可选更新检查在准备时关闭并记录，原版防刷屏机制未改。

旧`factory.*`数据按原样保留，包括未知字段、不可解析条目及越界旧值；不会自动变成已付费工厂、退款、产出或个人贡献。旧服迁移／付款审计、固定工厂价格的配置化、真实地图建筑与领地、普通国库写入竞争、完整Folia、长期容量和Paper实际调用路径事务中断仍未完成。当前日志读取完整校验的线性成本尚未压测。正常重启和JDBC故障注入不代表物理断电、生产部署或外部钱包的共同事务。

## 公开归档和续接

[证据ZIP](verification/nation-factories-20261125-final-3-evidence.zip)：22,067,852字节，7,363个哈希成员另加清单，SHA-256 `de02d0af908377f8321edc934563e832a9057588f88db92865bf622506833f36`。[机器清单](verification/nation-factories-20261125-final-3-summary.json)包含精确构件、各场景结果、完整构建统计、MySQL原始清单和解压复验。凭据扫描检查实际私密口令及非公开模板的配置密钥，公开包未包含这些字节；公开源码模板的默认占位值在凭据字段仍脱敏，其他模板说明不是私密凭据。配置脱敏项逐条记录。世界与第三方JAR不公开归档。

本机`target/nation-factories-20261125-final-3/`保留双JAR、两库源码tar、证据ZIP和解压复验日志。`target/nation-factories-frozen/`保留原始完整构建日志与Surefire报告。复验工具为`tools/inspect-nation-factory-paper.py`及`STARCHRONICLE_FACTORY_RUN=<现场> python3 -m unittest discover -s tools -p test_nation_factory_acceptance.py -v`。跨设备下一步见[十阶段交接](HANDOFF-2026-11-25.md)。
