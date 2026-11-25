# 国家工厂与生产准入协议

本增量提供持久化国家基础设施记录、建造材料、1–10级升级、启停及生产准入。工厂记录不会放置世界方块、自动施工或申请领地；地图建筑仍须独立的保护授权与实现。默认格式1生产配方保持原行为，运营者可显式启用格式2的工厂要求。

## 权限、操作ID与材料

`NationFactoryService`仅接受实际当前国家创始人，且调用必须在受支持的Paper主线程。命令权限默认开放，但不代替原生国家成员／创始人复核。OP或控制台不能越过业务授权。Folia上的本协议写入拒绝。

建造的请求UUID同时是工厂UUID；升级／启停使用单独请求UUID和期望版本号。每次成功变化把版本加一；同一请求的原操作者、操作类型、工厂、名称／类型或期望版本、启停目标不可改绑。精确重试只读返回ALREADY_APPLIED，包含创始人交接后的原操作者；新资产变化仍须当前创始人。过期版本返回CONFLICT，避免重复点击升级到下一等级。

材料来自国家库存，不使用玩家背包、Vault或国库。首版固定价格如下，达到目标等级L的材料为各项基价×L²；建造L=1，升级每次只增加一级。`/nationfactory plans <类型> <目标等级>`和`show`可查看成本。

| 类型 | mineral | industrial | energy |
| --- | ---: | ---: | ---: |
| SMELTER | 20 | 5 | 4 |
| PROCESSING_PLANT | 16 | 4 | 3 |
| CHEMICAL_PLANT | 30 | 8 | 6 |
| ASSEMBLY_PLANT | 40 | 10 | 8 |
| REFINERY | 50 | 12 | 10 |
| INTEGRATED_FACTORY | 100 | 25 | 20 |

每国最多32座，最高10级。每座L级工厂允许同时L个RUNNING生产、合计25×L批；两项都须满足。到期未领取仍为RUNNING，必须领取或取消才释放容量。升级扩大容量但不改旧批次时间／成本／产出，停用只阻止新开工。暂无拆除、转让、降级、自动领取、返还建设成本或可编辑建设价格。价格版本1与历史回执固定，未来价格版本必须兼容旧日志校验。

## 命令

```
/nationfactory plans [类型 目标等级]
/nationfactory build <类型> <名称> [原请求ID]
/nationfactory upgrade|enable|disable <工厂ID> <当前版本号> [原请求ID]
/nationfactory list
/nationfactory show <工厂ID>
/nationfactory operation <请求ID>
/nationproduction startat <工厂ID> <配方> <批数> [原批次ID]
```

名称是单个命令参数；API允许不含控制符、两端空白的1–80字符名称。新请求在调用前显示UUID；UNKNOWN后保留原UUID查询／重试，不能换UUID猜测提交失败。查询工厂／容量要求当前创始人；单次操作回执也允许该回执原操作者查看。内部可信服务可通过独立权威域和连续序号分页读取变化；建造／升级不签发生产贡献。

## 格式2配方

`plugins/StarCore/nation-production.json`保留格式1。格式2每个配方必须写`factory`，空对象`{}`明确表示不需要工厂；非空对象只允许`type`与`minimum-level`。配方管理继续使用`nationproduction validate/reload`，非法重载保持当前配置，非法启动禁新开工。示例：

```json
{
  "format": 2,
  "enabled": true,
  "limits": {"max-running-runs": 16, "max-running-batches": 1000},
  "recipes": [{
    "id": "factory_refining", "name": "工厂精炼",
    "inputs": {"mineral": 3, "energy": 1}, "outputs": {"industrial": 2},
    "seconds-per-batch": 30, "max-batches": 100,
    "factory": {"type": "SMELTER", "minimum-level": 2}
  }]
}
```

要求工厂的配方只能用`startat`显式指定本国、启用且类型／等级符合的工厂；普通start返回FACTORY_REQUIRED。无工厂要求的配方拒绝附加工厂，防止请求语义含混。国家总运行上限与工厂上限同时执行，不叠加为奖励容量。配置仍为单服本地重载；共享SQL的多个写实例须部署一致配方政策，不提供分布式配置共识。

新批次冻结工厂权威域、ID、开工时版本／类型／等级和要求等级，编码为run format2；旧无工厂批次保留字节一致的format1。重试还须匹配原工厂ID。工厂后续启停或升级不改变付费批次的完成／取消权利；原始输入、输出、演员、国家和时间仍冻结。

Chronicle保留同一生产来源和去重ID，新增六个`production.factory.*`事实，原事件未绑定工厂时投影完全不变。绑定工厂的完成记录仍只有原生产操作者及国家两个贡献查询视图，不因建设、升级或后继创始人领取而额外奖励。发布应使用配套双JAR，旧Chronicle不会记录新增工厂字段；旧StarCore不支持format2回执，回退须恢复匹配的完整备份。

## SQL与故障边界

新增`starcore_nation_factory_head/events`与`nation-factory-journal-v1`迁移标记。头记录独立权威域、资源域、已提交末尾及前缀摘要，日志记录规范JSON、请求ID、原操作者、准确成本、完整工厂版本和时间。每次读取校验连续性、摘要、请求唯一性、固定身份、精确价格与合法等级／启停变化；缺尾／缺头／域改变等拒绝继续。

建造／升级扣料、工厂事件和资源revision在同一个SQL写锁／事务中提交。SQL层再次检查精确库存扣除与回执成本完全一致、版本与数量上限；生产开工在同一事务中再次校验工厂当前版本、启用状态和RUNNING占用。数据库提交后才发布内存库存。提交确认丢失返回UNKNOWN并重新读取权威状态；重读不可用时阻止后续库存写入。普通资源保存、转移、贸易和生产都校验工厂日志，不能用旧快照覆盖已付材料。

完成／取消仍校验工厂历史准入版本存在，但不要求工厂当前版本或当前配置继续允许开工。删除整段工厂日志而保留绑定生产也会被拒绝。单纯前缀摘要不是外部不可篡改锚；具备数据库完整改写权限的攻击或恢复到自洽旧备份仍需独立备份／审计治理。

## 旧工厂保留

后续增量已增加原始审计和显式新付款登记，详见[旧工厂迁移协议](LEGACY-FACTORY-MIGRATION.md)。符合条件的空闲记录可登记为明确的LEGACY_REGISTERED来源；以下自动导入限制仍有效，加工中与异常记录继续隔离。

旧`ProcessingService.buildFactory`抛出UnsupportedOperationException，upgradeFactory/setFactoryOperational返回false，原有actorless生产资产入口继续停用。调用方须迁移新API。旧Factory对象仅用于检查，任何setter都不会变成新协议的付费建筑。

资源模块按原样保留加载到的全部`factory.*`属性，包括不能解析的条目、越界等级／效率、旧加工时间和未知字段。普通保存、新工厂付费和生产保存不会重编码这些字段。旧记录没有可靠原操作者／稳定付款ID，**不会自动导入为已付费工厂、退款、发货或补记贡献**。自动保留本身不等于旧服迁移完成；后续协议仅增加空闲合法记录的显式付费登记，其他情况仍待凭据核对与处置。

## 验证范围

构建、SQLite回滚／并发、MySQL与实际Paper证据分别记录于本次验收报告；未完成检查不得继承其他JAR结论。当前数据库读会完整校验日志，超大历史的容量与查询延迟仍待专项压测。正常重启、JDBC确认丢失注入或独立MySQL测试不等于Paper调用路径事务中断／物理断电、远端TLS或生产部署验收。
